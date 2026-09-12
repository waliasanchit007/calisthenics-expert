"""Offline sit-up feedback: thin CLI wiring atop the shared event-count report helpers.

Subprocess-boundary tests here only prove this CLI builds the correct Gradle invocation and
honors its own hash binding; they stub `subprocess.run` and never execute the real Kotlin engine.
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

TOOLS = Path(__file__).parents[2] / "tools"
sys.path.insert(0, str(TOOLS))

SPEC = importlib.util.spec_from_file_location(
    "offline_situp_feedback", TOOLS / "offline_situp_feedback.py"
)
assert SPEC and SPEC.loader
feedback = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = feedback
SPEC.loader.exec_module(feedback)

FRAMES_NS = [0, 500_000_000, 1_000_000_000, 1_500_000_000]


def pose_row(timestamp_ns: int) -> dict[str, Any]:
    return {"timestamp_ns": timestamp_ns, "width": 100, "height": 100, "landmarks": []}


def prediction_row(
    timestamp_ns: int, count: int, counted: bool, torso_elevation_degrees: float | None
) -> dict[str, Any]:
    return {
        "timestamp_ns": timestamp_ns,
        "phase": "UP" if counted else "DOWN",
        "count": count,
        "reason": "NONE",
        "counted": counted,
        "torso_elevation_degrees": torso_elevation_degrees,
    }


def build_cache(tmp_path: Path) -> tuple[Path, Path, Path]:
    video, model = tmp_path / "clip.mp4", tmp_path / "model.task"
    video.write_bytes(b"video-bytes")
    model.write_bytes(b"model-bytes")
    cache = tmp_path / "cache"
    cache.mkdir()
    poses_path = cache / "poses.jsonl"
    poses_path.write_text("\n".join(json.dumps(pose_row(t)) for t in FRAMES_NS) + "\n")
    metadata = {
        "coordinate_space": "upright_source_normalized",
        "video_sha256": feedback.sha256(video),
        "model_sha256": feedback.sha256(model),
        "poses_sha256": feedback.sha256(poses_path),
        "source": {
            "id": "clip-1",
            "group": "group-1",
            "split": "development",
            "video_sha256": feedback.sha256(video),
        },
    }
    (cache / "metadata.json").write_text(json.dumps(metadata))
    return video, model, cache


def write_predictions(path: Path, rows: list[dict[str, Any]], config: Path, poses: Path) -> None:
    path.write_text("\n".join(json.dumps(row) for row in rows) + "\n")
    run_record = {
        "config_sha256": feedback.sha256(config),
        "poses_sha256": feedback.sha256(poses),
        "predictions_sha256": feedback.sha256(path),
    }
    path.with_suffix(".run.json").write_text(json.dumps(run_record))


def counted_predictions(counted_indices: set[int]) -> list[dict[str, Any]]:
    rows, count = [], 0
    for index, timestamp_ns in enumerate(FRAMES_NS):
        counted = index in counted_indices
        count += int(counted)
        torso = None if index == 0 else 42.5
        rows.append(prediction_row(timestamp_ns, count, counted, torso))
    return rows


def run_cli(monkeypatch: pytest.MonkeyPatch, args: list[str]) -> None:
    monkeypatch.setattr(sys, "argv", ["offline_situp_feedback.py", *args])
    feedback.main()


def scenario(tmp_path: Path, counted_indices: set[int]) -> tuple[Path, Path, Path, Path, Path]:
    video, model, cache = build_cache(tmp_path)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"minimum_confidence": 0.5}))
    predictions = tmp_path / "run.predictions.jsonl"
    write_predictions(
        predictions, counted_predictions(counted_indices), config, cache / "poses.jsonl"
    )
    return video, model, cache, config, predictions


def cli_args(
    video: Path,
    model: Path,
    cache: Path,
    config: Path,
    predictions: Path,
    output: Path,
    labels: Path | None = None,
) -> list[str]:
    args = [
        "--video",
        str(video),
        "--model",
        str(model),
        "--cache",
        str(cache),
        "--config",
        str(config),
        "--predictions",
        str(predictions),
        "--output",
        str(output),
    ]
    if labels is not None:
        args += ["--labels", str(labels)]
    return args


def test_unlabelled_run_is_reported_as_unmeasured_not_scored(monkeypatch, tmp_path):
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    result = json.loads(output.read_text())
    assert result["status"] == "UNLABELLED"
    assert result["predicted"] == 2
    assert "recall" not in result


def test_reviewed_zero_events_scores_a_true_negative_not_a_perfect_default(monkeypatch, tmp_path):
    video, model, cache, config, predictions = scenario(tmp_path, set())
    labels = tmp_path / "labels.json"
    labels.write_text(
        json.dumps(
            {
                "version": 1,
                "video_sha256": feedback.sha256(video),
                "annotation_status": "visually_reviewed",
                "annotation_method": "independent visual inspection before detector output",
                "events": [],
            }
        )
    )
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output, labels))
    result = json.loads(output.read_text())
    assert result["status"] == "EXPLORATORY_COMPARISON"
    assert (result["predicted"], result["annotated"], result["matched"]) == (0, 0, 0)


def test_equal_totals_with_different_timing_still_reports_misses_and_extras(monkeypatch, tmp_path):
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    labels = tmp_path / "labels.json"
    labels.write_text(
        json.dumps(
            {
                "version": 1,
                "video_sha256": feedback.sha256(video),
                "annotation_status": "visually_reviewed",
                "annotation_method": "independent visual inspection before detector output",
                "events": [
                    {"start_ns": 100_000_000, "end_ns": 200_000_000},
                    {"start_ns": 1_400_000_000, "end_ns": 1_450_000_000},
                ],
            }
        )
    )
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output, labels))
    result = json.loads(output.read_text())
    assert (result["predicted"], result["annotated"]) == (2, 2)
    assert result["matched"] == 0
    assert result["missed"] == 2
    assert result["extra"] == 2


def test_mutated_config_after_recorded_run_is_rejected(monkeypatch, tmp_path):
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    config.write_text(json.dumps({"minimum_confidence": 0.99}))
    output = tmp_path / "report.json"
    with pytest.raises(ValueError, match="config"):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


def test_output_report_path_is_exclusive(monkeypatch, tmp_path):
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    with pytest.raises(FileExistsError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))


def test_frames_missing_torso_elevation_geometry_is_a_geometry_metric_not_a_reason_label(
    monkeypatch, tmp_path
):
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    result = json.loads(output.read_text())
    assert result["frames_missing_torso_elevation_geometry"] == 1
    assert result["reason_counts"] == {"NONE": 4}


def test_run_kotlin_builds_the_frozen_gradle_invocation_with_bound_paths(monkeypatch, tmp_path):
    video, model, cache = build_cache(tmp_path)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"minimum_confidence": 0.5}))
    output = tmp_path / "report.json"
    calls: list[list[str]] = []

    def fake_run(command, **kwargs):
        calls.append(command)
        out_path = output.with_suffix(".predictions.jsonl")
        rows = counted_predictions({1, 3})
        out_path.write_text("\n".join(json.dumps(row) for row in rows) + "\n")
        return subprocess.CompletedProcess(command, 0)

    original_sha256 = feedback.sha256

    def stub_sha256(path: Path) -> str:
        return original_sha256(path) if path.exists() else "0" * 64

    monkeypatch.setattr(feedback.subprocess, "run", fake_run)
    monkeypatch.setattr(feedback, "sha256", stub_sha256)
    run_cli(
        monkeypatch,
        [
            "--video",
            str(video),
            "--model",
            str(model),
            "--cache",
            str(cache),
            "--config",
            str(config),
            "--run-kotlin",
            "--output",
            str(output),
        ],
    )
    command = calls[0]
    assert command[2] == ":shared:replaySitupPose"
    assert any(arg.startswith("-PposePath=") for arg in command)
    assert any(arg.startswith("-PsitupConfigPath=") for arg in command)
    assert any(arg.startswith("-PsitupOutputPath=") for arg in command)
    result = json.loads(output.read_text())
    assert result["status"] == "UNLABELLED"
    assert result["predicted"] == 2


def test_failed_subprocess_during_run_kotlin_leaves_no_run_binding(monkeypatch, tmp_path):
    video, model, cache = build_cache(tmp_path)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"minimum_confidence": 0.5}))
    output = tmp_path / "report.json"

    def failing_run(command, **kwargs):
        raise subprocess.CalledProcessError(9, command)

    original_sha256 = feedback.sha256

    def stub_sha256(path: Path) -> str:
        return original_sha256(path) if path.exists() else "0" * 64

    monkeypatch.setattr(feedback.subprocess, "run", failing_run)
    monkeypatch.setattr(feedback, "sha256", stub_sha256)
    with pytest.raises(subprocess.CalledProcessError):
        run_cli(
            monkeypatch,
            [
                "--video",
                str(video),
                "--model",
                str(model),
                "--cache",
                str(cache),
                "--config",
                str(config),
                "--run-kotlin",
                "--output",
                str(output),
            ],
        )
    assert not output.with_suffix(".predictions.run.json").exists()
    assert not output.exists()


def test_report_declares_its_engine_identity_as_situp(monkeypatch, tmp_path):
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    result = json.loads(output.read_text())
    assert result["engine"] == "situp"


def test_engine_snapshot_binds_the_reused_arm_side_and_landmark_source(tmp_path):
    harness = tmp_path / "harness"
    relative_paths = (
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/SitupCycleExperiment.kt",
        "shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/ReplaySitupPose.kt",
        "shared/build.gradle.kts",
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/PullUpExperiment.kt",
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/contract/ObservationModels.kt",
    )
    for relative in relative_paths:
        path = harness / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(relative.encode())
    snapshot = feedback.engine_snapshot(harness)
    assert set(snapshot) == set(relative_paths)


# --- side_admission_window (opt-in bounded sit-up side admission) ---
#
# These test the reporter-side metadata validation described in
# docs/superpowers/sdd/2026-09-09-overnight-movement-evidence/situp-admission/design.md. They are
# expected to fail (missing "side_admission"/"evaluation_frame_count" keys, or no ValueError raised
# for malformed metadata) until offline_situp_feedback.py gains admission-window wiring; that is the
# currently-correct RED, not a bug in these tests.

ADMISSION_END_NS = 750_000_000


def side_admission_config(start_ns: int, end_ns: int) -> dict[str, Any]:
    return {
        "side": "LEFT",
        "minimum_endpoint_samples": 2,
        "minimum_confidence": 0.5,
        "side_admission_window": {"start_ns": start_ns, "end_ns": end_ns},
    }


def side_admission_metadata(**overrides: Any) -> dict[str, Any]:
    metadata = {
        "profile": "tracking_only_fixed_side_v1",
        "start_ns": 0,
        "end_ns": ADMISSION_END_NS,
        "preferred_side": "LEFT",
        "selected_side": "LEFT",
        "minimum_samples": 2,
        "observed_samples": 2,
        "left_usable_samples": 2,
        "right_usable_samples": 0,
    }
    metadata.update(overrides)
    return metadata


def admission_row(
    timestamp_ns: int,
    *,
    count: int,
    counted: bool,
    evaluation: bool,
    reason: str = "NONE",
    torso: float | None = None,
    closed_cycle: dict[str, Any] | None = None,
    side_admission: dict[str, Any] | None = None,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "timestamp_ns": timestamp_ns,
        "phase": "WAITING",
        "count": count,
        "reason": reason,
        "counted": counted,
        "torso_elevation_degrees": torso,
        "closed_cycle": closed_cycle,
        "evaluation": evaluation,
    }
    if side_admission is not None:
        row["side_admission"] = side_admission
    return row


def valid_admission_rows(metadata: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    return [
        admission_row(
            FRAMES_NS[0],
            count=0,
            counted=False,
            evaluation=False,
            reason="SIDE_ADMISSION",
            side_admission=metadata if metadata is not None else side_admission_metadata(),
        ),
        admission_row(
            FRAMES_NS[1], count=0, counted=False, evaluation=False, reason="SIDE_ADMISSION"
        ),
        admission_row(FRAMES_NS[2], count=1, counted=True, evaluation=True, torso=42.5),
        admission_row(FRAMES_NS[3], count=1, counted=False, evaluation=True, torso=42.5),
    ]


def admission_scenario(
    tmp_path: Path, rows: list[dict[str, Any]]
) -> tuple[Path, Path, Path, Path, Path]:
    video, model, cache = build_cache(tmp_path)
    config = tmp_path / "config.json"
    config.write_text(json.dumps(side_admission_config(0, ADMISSION_END_NS)))
    predictions = tmp_path / "run.predictions.jsonl"
    write_predictions(predictions, rows, config, cache / "poses.jsonl")
    return video, model, cache, config, predictions


def test_well_formed_side_admission_window_adds_metadata_and_evaluation_frame_count(
    monkeypatch, tmp_path
):
    video, model, cache, config, predictions = admission_scenario(tmp_path, valid_admission_rows())
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    result = json.loads(output.read_text())
    assert result["side_admission"] == side_admission_metadata()
    assert result["evaluation_frame_count"] == 2
    assert result["predicted"] == 1


def test_configured_window_without_a_first_row_side_admission_object_is_rejected(
    monkeypatch, tmp_path
):
    rows = valid_admission_rows()
    rows[0] = {k: v for k, v in rows[0].items() if k != "side_admission"}
    video, model, cache, config, predictions = admission_scenario(tmp_path, rows)
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


def test_a_first_row_side_admission_object_without_a_configured_window_is_rejected(
    monkeypatch, tmp_path
):
    video, model, cache = build_cache(tmp_path)
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"minimum_confidence": 0.5}))
    rows = counted_predictions({1, 3})
    rows[0]["side_admission"] = side_admission_metadata()
    predictions = tmp_path / "run.predictions.jsonl"
    write_predictions(predictions, rows, config, cache / "poses.jsonl")
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


def test_a_second_row_side_admission_object_is_rejected(monkeypatch, tmp_path):
    rows = valid_admission_rows()
    rows[1]["side_admission"] = side_admission_metadata()
    video, model, cache, config, predictions = admission_scenario(tmp_path, rows)
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


@pytest.mark.parametrize(
    "overrides",
    [
        {"profile": "tracking_only_fixed_side_v2"},
        {"start_ns": 1},
        {"end_ns": ADMISSION_END_NS + 1},
        {"minimum_samples": 2.0},
        {"observed_samples": True},
        {"observed_samples": 1},  # below minimum_samples
        {"left_usable_samples": 3},  # above observed_samples
        {"right_usable_samples": -1},
        {"preferred_side": "RIGHT"},
        {"selected_side": "RIGHT"},  # no right-side usable observations
        {"minimum_samples": 3},  # disagrees with configured endpoint minimum
    ],
)
def test_malformed_side_admission_metadata_is_rejected(monkeypatch, tmp_path, overrides):
    rows = valid_admission_rows(metadata=side_admission_metadata(**overrides))
    video, model, cache, config, predictions = admission_scenario(tmp_path, rows)
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


def test_evaluation_flag_must_exactly_match_timestamp_after_end(monkeypatch, tmp_path):
    rows = valid_admission_rows()
    rows[2]["evaluation"] = False  # FRAMES_NS[2] > ADMISSION_END_NS but flagged practice
    video, model, cache, config, predictions = admission_scenario(tmp_path, rows)
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


def test_prefix_rows_must_stay_uncounted_with_no_closed_cycle(monkeypatch, tmp_path):
    rows = valid_admission_rows()
    rows[1]["count"] = 1
    rows[1]["counted"] = True
    # Keep the cumulative sequence valid so only the admission-prefix rule rejects it.
    rows[2]["count"] = 2
    rows[3]["count"] = 2
    video, model, cache, config, predictions = admission_scenario(tmp_path, rows)
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


def test_baseline_report_without_a_configured_window_has_no_admission_fields(monkeypatch, tmp_path):
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    result = json.loads(output.read_text())
    assert "side_admission" not in result
    assert "evaluation_frame_count" not in result


def config_with_raw_window(window_value: Any) -> dict[str, Any]:
    return {
        "side": "LEFT",
        "minimum_endpoint_samples": 2,
        "minimum_confidence": 0.5,
        "side_admission_window": window_value,
    }


def raw_window_scenario(
    tmp_path: Path, window_value: Any, rows: list[dict[str, Any]]
) -> tuple[Path, Path, Path, Path, Path]:
    video, model, cache = build_cache(tmp_path)
    config = tmp_path / "config.json"
    config.write_text(json.dumps(config_with_raw_window(window_value)))
    predictions = tmp_path / "run.predictions.jsonl"
    write_predictions(predictions, rows, config, cache / "poses.jsonl")
    return video, model, cache, config, predictions


def test_a_missing_side_admission_window_key_remains_baseline_not_an_error(monkeypatch, tmp_path):
    # Regression guard: a genuinely missing key must NOT raise, unlike an explicit null below.
    video, model, cache, config, predictions = scenario(tmp_path, {1, 3})
    output = tmp_path / "report.json"
    run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert json.loads(output.read_text())["status"] == "UNLABELLED"


def test_an_explicit_null_side_admission_window_is_rejected_unlike_a_missing_key(
    monkeypatch, tmp_path
):
    video, model, cache, config, predictions = raw_window_scenario(
        tmp_path, None, counted_predictions({1, 3})
    )
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


@pytest.mark.parametrize(
    "window_value",
    [
        True,
        [0, ADMISSION_END_NS],
        {"start_ns": 0, "end_ns": ADMISSION_END_NS, "extra": 1},
        {"start_ns": True, "end_ns": ADMISSION_END_NS},
        {"start_ns": "0", "end_ns": ADMISSION_END_NS},
        {"start_ns": ADMISSION_END_NS, "end_ns": ADMISSION_END_NS},  # empty: start == end
        {"start_ns": ADMISSION_END_NS, "end_ns": 0},  # reversed: start > end
    ],
)
def test_a_present_but_malformed_side_admission_window_shape_is_rejected(
    monkeypatch, tmp_path, window_value
):
    video, model, cache, config, predictions = raw_window_scenario(
        tmp_path, window_value, valid_admission_rows()
    )
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()


def test_metadata_start_ns_boolean_is_not_treated_as_equal_to_zero(monkeypatch, tmp_path):
    rows = valid_admission_rows(metadata=side_admission_metadata(start_ns=False))
    video, model, cache, config, predictions = admission_scenario(tmp_path, rows)
    output = tmp_path / "report.json"
    with pytest.raises(ValueError):
        run_cli(monkeypatch, cli_args(video, model, cache, config, predictions, output))
    assert not output.exists()
