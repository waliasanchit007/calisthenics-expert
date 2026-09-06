"""Regression tests for the bounded movement-feedback collector and analyzer."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
from collections.abc import Iterable
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location(
    "collect_movement_feedback", ROOT / "tools/collect_movement_feedback.py"
)
assert SPEC and SPEC.loader
feedback = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = feedback
SPEC.loader.exec_module(feedback)


SESSION = "123e4567-e89b-12d3-a456-426614174000"


@pytest.mark.parametrize("movement", [None, "UNKNOWN", 123])
def test_invalid_movement_identity_is_not_a_valid_diagnostic_log(movement: object) -> None:
    rows = [{"session": SESSION, "seq": 0, "movement": movement}]
    assert not feedback._trace_summary(rows, feedback.JsonLines(rows), 0)["diagnostic_log_valid"]


def test_trace_movement_defaults_only_for_legacy_and_rejects_mixing() -> None:
    old = {"session": SESSION, "seq": 0}
    push = {"session": SESSION, "seq": 1, "movement": "PUSH_UP"}
    assert feedback._trace_summary([old], feedback.JsonLines([old]), 0)["movement"] == "PULL_UP"
    assert feedback._trace_summary([push], feedback.JsonLines([push]), 0)["movement"] == "PUSH_UP"
    assert not feedback._trace_summary([old, push], feedback.JsonLines([old, push]), 0)[
        "diagnostic_log_valid"
    ]


def _trace_row(
    seq: int,
    event: str = "SAMPLE",
    *,
    capture_ns: int | None = None,
    failure_code: str | None = None,
    input_issue: str | None = None,
    candidate_cycles: int = 0,
    session: str = SESSION,
    stage: str = "IDLE",
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "v": 1,
        "session": session,
        "seq": seq,
        "build": "test",
        "event": event,
        "elapsed_ms": seq * 100.0,
        "capture_ms": seq * 100.0,
        "age_ms": 0.0,
        "feed": "FRESH",
        "input_issue": input_issue,
        "stage": stage,
        "phase": "WAITING",
        "failure_code": failure_code,
        "candidate_cycles": candidate_cycles,
        "writer_dropped": 0,
        "pipeline_skipped": 0,
        "rate_suppressed": 0,
        "measurements_mode": "exact",
    }
    if capture_ns is not None:
        row["capture_ns"] = capture_ns
    return row


def _frame_row(
    capture_ns: int,
    image: str,
    payload: bytes,
    *,
    sha: str | None = None,
    session: str | None = None,
    seq: int | None = None,
    input_rgb_sha256: str | None = None,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "capture_ns": capture_ns,
        "image": image,
        "jpeg_sha256": sha or hashlib.sha256(payload).hexdigest(),
        "input_rgb_sha256": "a" * 64,
        "width": 256,
        "height": 256,
        "source_width": 1080,
        "source_height": 1920,
        "landmarks": [
            {"index": 0, "x": 0.1, "y": 0.2, "z": 0.0, "visibility": 0.9, "presence": 0.9}
        ],
        "pose_present": True,
    }
    if session is not None:
        row["session"] = session
    if seq is not None:
        row["seq"] = seq
    if input_rgb_sha256 is not None:
        row["input_rgb_sha256"] = input_rgb_sha256
    return row


def _write_bundle(
    root: Path,
    *,
    trace: list[dict[str, Any]],
    frames: Iterable[tuple[dict[str, Any], bytes]] = (),
    end: dict[str, Any] | None = None,
    session: dict[str, Any] | None = None,
    partial_trace: bytes = b"",
) -> Path:
    bundle = root / SESSION
    (bundle / "frames").mkdir(parents=True)
    (bundle / "session.json").write_text(
        json.dumps(session or {"session": SESSION, "input": "CAMERA"}), encoding="utf-8"
    )
    with (bundle / "trace.jsonl").open("wb") as handle:
        for row in trace:
            handle.write(json.dumps(row).encode() + b"\n")
        handle.write(partial_trace)
    with (bundle / "frames.jsonl").open("w", encoding="utf-8") as handle:
        for row, payload in frames:
            handle.write(json.dumps(row) + "\n")
            (bundle / row["image"]).write_bytes(payload)
    if end is not None:
        (bundle / "end.json").write_text(json.dumps(end), encoding="utf-8")
    return bundle


def test_duplicate_and_partial_trace_rows_are_deduplicated_and_last_line_ignored(
    tmp_path: Path,
) -> None:
    row = _trace_row(0, "START_CAMERA")
    path = tmp_path / "trace.jsonl"
    path.write_text(json.dumps(row) + "\n" + json.dumps(row) + "\n{", encoding="utf-8")

    result = feedback.read_complete_jsonl(path)

    assert result.rows == [row, row]
    assert result.incomplete_lines == 1
    assert feedback.deduplicate_trace(result.rows, SESSION) == [row]


def test_missing_sequence_is_reported_separately_from_pipeline_drop_and_rate_suppression(
    tmp_path: Path,
) -> None:
    rows = [
        _trace_row(0, "START_CAMERA"),
        {**_trace_row(2), "pipeline_skipped": 3, "writer_dropped": 2, "rate_suppressed": 4},
    ]
    bundle = _write_bundle(tmp_path, trace=rows)

    summary = feedback.analyze_session(bundle)

    assert summary["trace"]["missing_sequences"] == [1]
    assert summary["trace"]["pipeline_skipped"] == 3
    assert summary["trace"]["writer_dropped"] == 2
    assert summary["trace"]["rate_suppressed"] == 4


def test_cumulative_skip_counters_use_the_session_maximum() -> None:
    rows = [
        {**_trace_row(0), "pipeline_skipped": 1, "rate_suppressed": 1},
        {**_trace_row(1), "pipeline_skipped": 2, "rate_suppressed": 2},
    ]

    summary = feedback._trace_summary(
        feedback.deduplicate_trace(rows, SESSION), feedback.JsonLines(rows), 0
    )

    assert summary["pipeline_skipped"] == 2
    assert summary["rate_suppressed"] == 2


def test_countdown_input_issue_is_not_the_calibration_failure(tmp_path: Path) -> None:
    rows = [
        _trace_row(0, "START_CAMERA"),
        _trace_row(100, "CALIBRATE", stage="COUNTDOWN"),
        _trace_row(160, "SAMPLE", input_issue="ELBOW_UNAVAILABLE", stage="COUNTDOWN"),
        _trace_row(200, "SAMPLE", input_issue="WRIST_UNAVAILABLE", stage="CALIBRATING"),
        _trace_row(
            306,
            "SAMPLE",
            failure_code="INCOMPLETE_CYCLE",
            input_issue="WRIST_NOT_ABOVE_SHOULDER",
            stage="ERROR",
        ),
        _trace_row(307, "SAMPLE", failure_code="INCOMPLETE_CYCLE", stage="ERROR"),
    ]

    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows))

    assert summary["attempts"][0]["first_failure"] == {
        "seq": 306,
        "failure_code": "INCOMPLETE_CYCLE",
        "reason": "INCOMPLETE_CYCLE",
        "input_issue": "WRIST_NOT_ABOVE_SHOULDER",
    }


def test_first_failure_wins_over_later_missing_tracking(tmp_path: Path) -> None:
    rows = [
        _trace_row(0, "START_CAMERA"),
        _trace_row(1, "CALIBRATE", failure_code="CALIBRATION_FAILED", stage="ERROR"),
        _trace_row(2, "SAMPLE", input_issue="MISSING_TRACKING"),
        _trace_row(3, "STOP", failure_code="MISSING_TRACKING"),
    ]
    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows))

    assert summary["attempts"][0]["first_failure"] == {
        "seq": 1,
        "failure_code": "CALIBRATION_FAILED",
        "reason": "CALIBRATION_FAILED",
        "input_issue": None,
    }
    assert summary["attempts"][0]["status"] == "ERROR"


def test_start_synthetic_session_is_excluded_from_real_attempt_reports(tmp_path: Path) -> None:
    rows = [_trace_row(0, "START_SYNTHETIC"), _trace_row(1, "CALIBRATE"), _trace_row(2, "STOP")]
    summary = feedback.analyze_session(
        _write_bundle(tmp_path, trace=rows, session={"session": SESSION, "input": "SYNTHETIC"})
    )

    assert summary["synthetic"] is True
    assert summary["attempts"] == []


def test_two_calibrates_start_two_attempts(tmp_path: Path) -> None:
    rows = [
        _trace_row(0, "START_CAMERA"),
        _trace_row(1, "CALIBRATE"),
        _trace_row(2, "RESET"),
        _trace_row(3, "CALIBRATE"),
        _trace_row(4, "CONFIRM"),
        _trace_row(5, "STOP"),
    ]
    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows))

    assert [attempt["start_seq"] for attempt in summary["attempts"]] == [1, 3]
    assert summary["attempts"][0]["finish"] == "RESET"
    assert summary["attempts"][1]["finish"] == "STOP"


def test_successful_candidate_count_is_reported_without_calling_it_a_real_rep(
    tmp_path: Path,
) -> None:
    rows = [
        _trace_row(0, "START_CAMERA"),
        _trace_row(1, "CALIBRATE"),
        _trace_row(2, "CONFIRM"),
        _trace_row(3, "SAMPLE", candidate_cycles=1),
        _trace_row(4, "STOP", candidate_cycles=1),
    ]
    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows))
    encoded = json.dumps(summary).lower()

    assert summary["attempts"][0]["candidate_count"] == 1
    assert summary["attempts"][0]["finish"] == "COUNT"
    assert "real_rep" not in encoded
    assert "anatom" not in encoded


def test_matching_frame_requires_safe_path_and_sha_but_missing_image_does_not_invalidate_log(
    tmp_path: Path,
) -> None:
    payload = b"jpeg-bytes"
    rows = [
        _trace_row(0, "START_CAMERA", capture_ns=100),
        _trace_row(1, "STOP", capture_ns=101),
        _trace_row(2, "SAMPLE", capture_ns=102),
        _trace_row(3, "SAMPLE", capture_ns=103),
    ]
    frames = [
        (_frame_row(100, "frames/100.jpg", payload), payload),
        (_frame_row(101, "../escape.jpg", payload), payload),
        (_frame_row(102, "frames/102.jpg", payload, sha="b" * 64), payload),
        (_frame_row(103, "frames/103.jpg", payload), b"not-the-indexed-image"),
    ]
    bundle = _write_bundle(tmp_path, trace=rows, frames=frames)
    (bundle / "frames/102.jpg").unlink()

    summary = feedback.analyze_session(bundle)

    assert summary["diagnostic_log_valid"] is True
    assert summary["frames"]["available"] == 1
    assert summary["frames"]["unavailable"] == 3
    assert set(summary["frames"]["unavailable_reasons"]) >= {
        "unsafe_image_path",
        "jpeg_sha256_mismatch",
        "image_missing",
    }


def test_missing_end_preserves_ongoing_status_and_never_claims_disconnect_complete(
    tmp_path: Path,
) -> None:
    rows = [_trace_row(0, "START_CAMERA"), _trace_row(1, "CALIBRATE")]
    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows))

    assert summary["status"] == "ONGOING"
    assert summary["complete"] is False
    assert summary["end"] is None
    assert summary["disconnect_complete"] is False


def test_legacy_capture_ms_rows_analyze_without_image_association(tmp_path: Path) -> None:
    rows = [_trace_row(0, "START_CAMERA"), _trace_row(1, "STOP")]
    for row in rows:
        row.pop("capture_ns", None)
    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows))

    assert summary["trace"]["legacy_capture_ms"] is True
    assert summary["frames"]["association"] == "unavailable_legacy_timestamps"


def test_v2_observation_capture_binds_frame_when_measured_capture_is_null(tmp_path: Path) -> None:
    trace = _trace_row(0, "START_CAMERA")
    trace["capture_ns"] = None
    trace["v"] = 2
    trace["angle_quantum_deg"] = 0
    trace["height_quantum"] = 0
    trace.pop("measurements_mode")
    trace["observation_capture_ns"] = 500
    trace["input_rgb_sha256"] = "c" * 64
    frame_payload = b"v2-jpeg"
    frame = _frame_row(
        500,
        "frames/500.jpg",
        frame_payload,
        session=SESSION,
        seq=0,
        input_rgb_sha256="c" * 64,
    )

    summary = feedback.analyze_session(
        _write_bundle(tmp_path, trace=[trace], frames=[(frame, frame_payload)])
    )

    assert summary["trace"]["capture_ns_rows"] == 0
    assert summary["trace"]["measurements_mode"] == "exact"
    assert summary["frames"]["association"] == "capture_ns_exact"
    assert summary["frames"]["available"] == 1


def test_v2_frame_join_uses_capture_and_rgb_hash_not_frame_seq(tmp_path: Path) -> None:
    trace = _trace_row(1, "SAMPLE", capture_ns=700)
    trace["input_rgb_sha256"] = "d" * 64
    frame_payload = b"different-stream-seq"
    frame = _frame_row(
        700,
        "frames/700.jpg",
        frame_payload,
        session=SESSION,
        seq=99,
        input_rgb_sha256="d" * 64,
    )

    summary = feedback.analyze_session(
        _write_bundle(tmp_path, trace=[trace], frames=[(frame, frame_payload)])
    )

    assert summary["frames"]["available"] == 1


def test_end_accounting_and_trace_logger_drop_make_session_partial_but_terminal(
    tmp_path: Path,
) -> None:
    rows = [_trace_row(0, "START_CAMERA"), _trace_row(1, "STOP")]
    end = {
        "reason": "user_stop",
        "frames_offered": 0,
        "frames_written": 0,
        "traces_offered": 2,
        "traces_written": 2,
        "dropped": 1,
        "trace_logger_dropped": 2,
        "first_capture_ns": None,
        "last_capture_ns": None,
        "bytes_written": 10,
        "durability": "best_effort_local_debug",
    }
    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows, end=end))

    assert summary["complete"] is True
    assert summary["partial"] is True
    assert summary["end_error"] is None
    assert summary["trace"]["trace_logger_dropped"] == 2
    assert summary["trace"]["bundle_dropped"] == 1


def test_end_with_uncollected_written_rows_is_incomplete(tmp_path: Path) -> None:
    rows = [_trace_row(0, "START_CAMERA")]
    end = {
        "reason": "user_stop",
        "frames_offered": 0,
        "frames_written": 0,
        "traces_offered": 2,
        "traces_written": 2,
        "dropped": 0,
        "trace_logger_dropped": 0,
        "first_capture_ns": None,
        "last_capture_ns": None,
        "bytes_written": 10,
        "durability": "best_effort_local_debug",
    }

    summary = feedback.analyze_session(_write_bundle(tmp_path, trace=rows, end=end))

    assert summary["complete"] is False
    assert summary["active"] is True
    assert summary["end_error"] == "end_accounting_mismatch"


def test_old_standalone_trace_preserves_multiple_sessions(tmp_path: Path) -> None:
    path = tmp_path / "trace.jsonl"
    other = "123e4567-e89b-12d3-a456-426614174001"
    path.write_text(
        json.dumps(_trace_row(0, "START_CAMERA"))
        + "\n"
        + json.dumps(_trace_row(0, "START_CAMERA", session=other))
        + "\n",
        encoding="utf-8",
    )

    result = feedback.analyze_local(path)

    assert [summary["session"] for summary in result["sessions"]] == [SESSION, other]


def test_collector_retries_a_missing_image_on_the_next_poll(tmp_path: Path) -> None:
    payload = b"eventually-present"
    frame = _frame_row(
        900,
        "frames/900.jpg",
        payload,
        session=SESSION,
        seq=88,
    )

    class Client:
        def __init__(self) -> None:
            self.image_reads = 0

        def list_sessions(self) -> list[str]:
            return [SESSION]

        def run_as(self, *args: str) -> bytes:
            remote = args[-1]
            if remote.endswith("session.json"):
                return json.dumps({"session": SESSION, "input": "CAMERA"}).encode()
            if remote.endswith("trace.jsonl"):
                return (json.dumps(_trace_row(0, "START_CAMERA", capture_ns=900)) + "\n").encode()
            if remote.endswith("frames.jsonl"):
                return (json.dumps(frame) + "\n").encode()
            if remote.endswith("frames/900.jpg"):
                self.image_reads += 1
                if self.image_reads == 1:
                    raise feedback.AdbDisconnected("temporary disconnect")
                return payload
            raise feedback.AdbDisconnected("not present yet")

    client = Client()
    collector = feedback.FeedbackCollector(client, tmp_path / "output")
    collector.collect_once()
    session_dir = tmp_path / "output" / SESSION
    assert (session_dir / "report.json").is_file()
    assert (session_dir / "report.md").is_file()
    collector.collect_once()

    assert client.image_reads == 2
    assert (session_dir / "frames/900.jpg").read_bytes() == payload
    assert feedback.analyze_session(session_dir)["complete"] is False


def test_session_cap_is_checked_before_writing_remote_payload(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    class Client:
        def list_sessions(self) -> list[str]:
            return [SESSION]

        def run_as(self, *args: str) -> bytes:
            if args[-1].endswith("session.json"):
                return b"{}"
            raise feedback.AdbDisconnected("not needed")

    monkeypatch.setattr(feedback, "MAX_SESSION_BYTES", 1)
    collector = feedback.FeedbackCollector(Client(), tmp_path / "output")

    with pytest.raises(feedback.FeedbackError, match="exceeds"):
        collector.collect_once()
    assert not (tmp_path / "output" / SESSION / "session.json").exists()


def test_output_path_inside_git_tree_is_rejected(tmp_path: Path) -> None:
    git_root = tmp_path / "repo"
    git_root.mkdir()
    (git_root / ".git").mkdir()

    with pytest.raises(feedback.FeedbackError, match="Git tree"):
        feedback.ensure_safe_output_dir(git_root / "nested" / "output")
