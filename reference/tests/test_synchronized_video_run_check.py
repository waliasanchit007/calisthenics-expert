"""Host-side checks for the short synchronized-video S25 gate."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location(
    "check_synchronized_video_run", ROOT / "tools" / "check_synchronized_video_run.py"
)
assert _SPEC is not None and _SPEC.loader is not None
checker = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(checker)

PROBE_CODEC = "c2.qti.avc.encoder"
PROBE_CONTAINER = "video/mp4"


def _probe_document() -> dict:
    return {
        "record_kind": "container_timestamp_probe_device_gate_observation",
        "blocking_result": False,
        "provenance": {
            "harness_git_sha": "a" * 40,
            "container": PROBE_CONTAINER,
            "codec": PROBE_CODEC,
        },
        "observation": {
            "submitted_pts_us": [1_000_000, 1_033_333],
            "demuxed_pts_us": [0, 33_333],
            "constant_offset_us": 1_000_000,
        },
    }


def _write_probe(tmp_path: Path, document: dict | None = None) -> Path:
    path = tmp_path / "probe.json"
    path.write_text(json.dumps(document or _probe_document()), encoding="utf-8")
    return path


def _record(probe_path: Path, **overrides) -> dict:
    session_id = "00000000-0000-4000-8000-000000000001"
    record = {
        "record_kind": "synchronized_video_device_gate_observation",
        "sanitized": True,
        "non_participant_observation": True,
        "contains_landmark_coordinates": False,
        "session_id": session_id,
        "environment": {
            "device_class": "physical_device",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "marketing_name": "",
        },
        "provenance": {
            "codec": PROBE_CODEC,
            "container": PROBE_CONTAINER,
            "probe_sha256": checker.probe_digest(probe_path),
        },
        "artifacts": {
            "manifest_path_ref": f"exports/{session_id}/manifest.json",
            "manifest_sha256": "a" * 64,
            "manifest_byte_length": 512,
            "video_path_ref": f"exports/{session_id}/video.mp4",
            "video_sha256": "b" * 64,
            "video_byte_length": 1024,
            "pose_stream_path_ref": f"exports/{session_id}/frames.ndjson",
            "pose_stream_sha256": "c" * 64,
            "pose_stream_byte_length": 256,
            "synchronization_evidence_path_ref": (
                f"exports/{session_id}/synchronization-evidence.json"
            ),
            "synchronization_evidence_sha256": "d" * 64,
            "synchronization_evidence_byte_length": 768,
        },
        "blocking_reasons": [],
    }
    record.update(overrides)
    return record


def test_a_well_formed_record_is_admissible(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)

    assert checker.check_record(_record(probe_path), probe, probe_path) == []


def test_a_codec_other_than_the_probe_codec_is_blocked(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)
    record = _record(probe_path)
    record["provenance"]["codec"] = "c2.android.avc.encoder"

    assert "probe_codec_mismatch" in checker.check_record(record, probe, probe_path)


def test_a_container_other_than_the_probe_container_is_blocked(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)
    record = _record(probe_path)
    record["provenance"]["container"] = "video/webm"

    assert "probe_container_mismatch" in checker.check_record(record, probe, probe_path)


def test_an_artifact_reference_outside_the_committed_session_is_blocked(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)
    record = _record(probe_path)
    record["artifacts"]["video_path_ref"] = "exports/other/video.mp4"

    assert "committed_artifact_reference_mismatch" in checker.check_record(
        record, probe, probe_path
    )


def test_the_host_binds_the_probe_digest_rather_than_trusting_the_device(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)
    record = _record(probe_path)
    # The device cannot know the digest of a repository file, so a device-reported value is never
    # the binding. A wrong one must not be able to block or to pass.
    record["provenance"]["probe_sha256"] = "c" * 64

    assert checker.check_record(record, probe, probe_path) == []
    assert checker.probe_digest(probe_path) != "c" * 64


def test_an_unauthorized_device_is_blocked(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)
    record = _record(probe_path)
    record["environment"]["device_class"] = "emulator"

    assert "unauthorized_device_target" in checker.check_record(record, probe, probe_path)


def test_a_blocking_probe_cannot_be_inherited(tmp_path: Path) -> None:
    document = _probe_document()
    document["blocking_result"] = True
    probe_path = _write_probe(tmp_path, document)

    with pytest.raises(checker.SynchronizedVideoCheckError):
        checker.load_probe(probe_path)


def test_device_reported_blocking_reasons_survive(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)
    record = _record(probe_path, blocking_reasons=["container_timestamp_transform_non_constant"])

    reasons = checker.check_record(record, probe, probe_path)

    # A device-reported blocking observation is never dropped by the host checker.
    assert "container_timestamp_transform_non_constant" in reasons


def test_a_payload_whose_digest_disagrees_with_the_manifest_is_blocked(tmp_path: Path) -> None:
    payload = tmp_path / "video.mp4"
    payload.write_bytes(b"synthetic")

    reasons = checker.verify_payload(payload, "d" * 64, len(b"synthetic"))

    assert "video_digest_mismatch" in reasons


def test_each_committed_artifact_keeps_its_own_verification_reason(tmp_path: Path) -> None:
    payload = tmp_path / "manifest.json"
    payload.write_bytes(b"{}")

    reasons = checker.verify_payload(payload, "d" * 64, 9999, artifact_name="manifest")

    assert reasons == ["manifest_byte_length_mismatch", "manifest_digest_mismatch"]


def test_a_payload_whose_byte_count_disagrees_with_the_manifest_is_blocked(tmp_path: Path) -> None:
    payload = tmp_path / "video.mp4"
    payload.write_bytes(b"synthetic")
    digest = hashlib.sha256(b"synthetic").hexdigest()

    reasons = checker.verify_payload(payload, digest, 9999)

    assert "video_byte_length_mismatch" in reasons


def test_a_matching_payload_produces_no_reasons(tmp_path: Path) -> None:
    payload = tmp_path / "video.mp4"
    payload.write_bytes(b"synthetic")
    digest = hashlib.sha256(b"synthetic").hexdigest()

    assert checker.verify_payload(payload, digest, len(b"synthetic")) == []


def test_committed_artifact_paths_are_derived_from_the_session_export() -> None:
    session_id = "00000000-0000-4000-8000-000000000001"

    assert checker.remote_artifact_path(session_id, "manifest.json") == (
        f"files/diagnostic-harness/exports/{session_id}/manifest.json"
    )
    assert checker.remote_artifact_path(session_id, "video.mp4") == (
        f"files/diagnostic-harness/exports/{session_id}/video.mp4"
    )


def test_all_four_artifact_references_must_name_the_same_committed_session(
    tmp_path: Path,
) -> None:
    probe_path = _write_probe(tmp_path)
    probe = checker.load_probe(probe_path)

    for key in (
        "manifest_path_ref",
        "video_path_ref",
        "pose_stream_path_ref",
        "synchronization_evidence_path_ref",
    ):
        record = _record(probe_path)
        record["artifacts"][key] = record["artifacts"][key].replace(
            record["session_id"], "00000000-0000-4000-8000-000000000002"
        )
        assert "committed_artifact_reference_mismatch" in checker.check_record(
            record, probe, probe_path
        )


def test_export_validation_uses_the_authoritative_contract_cli(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest = tmp_path / "manifest.json"
    frames = tmp_path / "frames.ndjson"
    manifest.write_text("{}", encoding="utf-8")
    frames.write_text("", encoding="utf-8")
    observed: list[list[str]] = []

    class Result:
        returncode = 0

    def fake_run(command, **kwargs):
        observed.append(command)
        return Result()

    monkeypatch.setattr(checker.subprocess, "run", fake_run)

    assert checker.validate_export(manifest, frames) is True
    assert observed == [
        [
            checker.sys.executable,
            "-m",
            "observation_contracts",
            "validate-session",
            str(manifest),
            str(frames),
        ]
    ]


@pytest.mark.parametrize("name", ["../video.mp4", "/video.mp4", "nested/video.mp4", ""])
def test_committed_artifact_paths_reject_traversal_and_non_leaf_names(name: str) -> None:
    with pytest.raises(checker.SynchronizedVideoCheckError):
        checker.remote_artifact_path("00000000-0000-4000-8000-000000000001", name)


def test_an_absolute_path_makes_a_record_unsanitized(tmp_path: Path) -> None:
    probe_path = _write_probe(tmp_path)
    record = _record(probe_path)
    record["artifacts"]["video_path_ref"] = "/data/user/0/com.example/files/video.mp4"

    with pytest.raises(checker.SynchronizedVideoCheckError):
        checker.assert_sanitized(record)


def test_a_transcript_without_the_named_suite_is_refused() -> None:
    with pytest.raises(checker.SynchronizedVideoCheckError):
        checker.extract_record("OK (1 test)")


def test_a_transcript_with_two_records_is_refused() -> None:
    line = (
        "INSTRUMENTATION_STATUS: SynchronizedVideoEvidence="
        '{"record_kind": "synchronized_video_device_gate_observation"}'
    )
    transcript = (
        "class=com.calisthenics.diagnostic.composition.SynchronizedVideoDeviceGateTest\n"
        f"{line}\n{line}\n"
    )

    with pytest.raises(checker.SynchronizedVideoCheckError):
        checker.extract_record(transcript)


def test_a_run_as_error_is_never_accepted_as_a_payload() -> None:
    source = (ROOT / "tools" / "check_synchronized_video_run.py").read_text(encoding="utf-8")

    # run-as writes its diagnostics to stdout and can still exit zero. Observed on the device:
    # "run-as: unknown package: ..." was written out as a 53-byte MP4 and then hashed and compared
    # as if it were the payload. Only the digest check caught it.
    assert "_RUN_AS_ERROR" in source
    assert "returned a tool error" in source


def test_the_checker_targets_the_real_application_id() -> None:
    source = (ROOT / "tools" / "check_synchronized_video_run.py").read_text(encoding="utf-8")

    assert 'default="com.calisthenics.diagnostic.android"' in source


def test_the_binary_pull_never_uses_text_mode_adb_shell() -> None:
    source = (ROOT / "tools" / "check_synchronized_video_run.py").read_text(encoding="utf-8")

    # `adb shell` performs LF/CRLF translation on some hosts and silently corrupts an MP4.
    assert "exec-out" in source
    assert '"shell", "run-as"' not in source
    assert "text=True" not in source.split("def pull_binary")[1].split("def ")[0]
