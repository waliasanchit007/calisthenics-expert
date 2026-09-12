"""Offline plank feedback must expose interval coverage errors hidden by a matching count."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).parents[2] / "tools"
sys.path.insert(0, str(TOOLS))

SPEC = importlib.util.spec_from_file_location(
    "offline_plank_feedback", TOOLS / "offline_plank_feedback.py"
)
assert SPEC and SPEC.loader
feedback = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = feedback
SPEC.loader.exec_module(feedback)


def labels() -> dict:
    return {
        "version": 1,
        "video_sha256": "a" * 64,
        "annotation_status": "visually_reviewed",
        "annotation_method": "independent visual inspection before detector output",
        "intervals": [{"start_ns": 1000, "end_ns": 2000}],
    }


def hold(entry: int, exit_: int, observed: int | None = None) -> dict:
    return {
        "entry_timestamp_ns": entry,
        "exit_timestamp_ns": exit_,
        "observed_ns": observed if observed is not None else exit_ - entry,
        "end_reason": "OUT_OF_BAND",
    }


def test_exact_overlap_is_fully_covered_with_no_extra() -> None:
    result = feedback.compare_holds([hold(1000, 2000)], labels(), "a" * 64)
    assert result["missed_label_indices"] == []
    assert result["extra_hold_count"] == 0
    assert result["label_matches"][0]["coverage_ratio"] == 1.0
    assert result["hold_diagnostics"][0]["unlabelled_ns"] == 0
    assert result["hold_diagnostics"][0]["end_reason"] == "OUT_OF_BAND"
    assert result["censored_hold_count"] == 0


def test_a_censored_hold_is_counted_and_its_end_reason_is_not_hidden() -> None:
    censored = hold(1000, 2000)
    censored["end_reason"] = "CENSORED_AT_END_OF_INPUT"
    result = feedback.compare_holds([censored], labels(), "a" * 64)
    assert result["censored_hold_count"] == 1
    assert result["hold_diagnostics"][0]["end_reason"] == "CENSORED_AT_END_OF_INPUT"
    assert "censored" in result["claim"]


def test_overextended_hold_reports_unlabelled_time_without_hiding_full_coverage() -> None:
    result = feedback.compare_holds([hold(500, 2500)], labels(), "a" * 64)
    assert result["label_matches"][0]["coverage_ratio"] == 1.0
    assert result["hold_diagnostics"][0]["unlabelled_ns"] == 1000


def test_a_matching_total_duration_does_not_hide_a_missed_interval_and_an_extra() -> None:
    two_label_set = labels()
    two_label_set["intervals"] = [
        {"start_ns": 1000, "end_ns": 2000},
        {"start_ns": 5000, "end_ns": 6000},
    ]
    result = feedback.compare_holds([hold(1000, 2000), hold(3000, 4000)], two_label_set, "a" * 64)
    assert result["missed_label_indices"] == [1]
    assert result["extra_hold_count"] == 1
    assert result["label_matches"][0]["coverage_ratio"] == 1.0


def test_partial_overlap_reports_a_fractional_coverage_ratio() -> None:
    result = feedback.compare_holds([hold(1500, 2500)], labels(), "a" * 64)
    assert result["label_matches"][0]["covered_ns"] == 500
    assert result["label_matches"][0]["coverage_ratio"] == 0.5


def test_missing_labels_are_unmeasured_not_a_perfect_score() -> None:
    result = feedback.compare_holds([], None, "a" * 64)
    assert result["status"] == "UNLABELLED"
    assert "label_matches" not in result


@pytest.mark.parametrize("change", ["hash", "status", "overlap", "empty_interval"])
def test_invalid_annotations_cannot_be_admitted(change: str) -> None:
    record = labels()
    record["intervals"].append({"start_ns": 5000, "end_ns": 6000})
    if change == "hash":
        record["video_sha256"] = "b" * 64
    elif change == "status":
        record["annotation_status"] = "model_generated"
    elif change == "overlap":
        record["intervals"][1] = {"start_ns": 1500, "end_ns": 6000}
    else:
        record["intervals"][1] = {"start_ns": 5000, "end_ns": 5000}
    with pytest.raises(ValueError):
        feedback.compare_holds([hold(1000, 2000)], record, "a" * 64)


def test_predicted_hold_with_exit_before_entry_is_rejected() -> None:
    with pytest.raises(ValueError, match="exit"):
        feedback.compare_holds([hold(2000, 1000)], labels(), "a" * 64)


def test_predicted_observed_time_cannot_exceed_its_own_wall_duration() -> None:
    with pytest.raises(ValueError, match="observed"):
        feedback.compare_holds([hold(1000, 2000, observed=2000)], labels(), "a" * 64)


def test_prediction_holds_extracts_only_closed_hold_rows_and_checks_row_order() -> None:
    rows = [
        {"timestamp_ns": 1, "closed_hold": None},
        {"timestamp_ns": 2, "closed_hold": hold(1000, 2000)},
        {"timestamp_ns": 3, "closed_hold": None},
    ]
    assert feedback.prediction_holds(rows) == [hold(1000, 2000)]

    out_of_order = [
        {"timestamp_ns": 2, "closed_hold": hold(1000, 2000)},
        {"timestamp_ns": 1, "closed_hold": None},
    ]
    with pytest.raises(ValueError):
        feedback.prediction_holds(out_of_order)


def test_run_binding_refuses_a_config_substituted_after_replay(tmp_path: Path) -> None:
    paths = {name: tmp_path / name for name in ("config", "poses", "predictions")}
    for path in paths.values():
        path.write_text("original")
    record = {f"{name}_sha256": feedback.sha256(path) for name, path in paths.items()}
    feedback.verify_run(record, **paths)
    paths["config"].write_text("changed thresholds")
    with pytest.raises(ValueError, match="config"):
        feedback.verify_run(record, **paths)


def test_overlay_uses_bound_frames_and_escapes_script_content(tmp_path: Path) -> None:
    poses = [{"timestamp_ns": 1000, "width": 640, "height": 360, "landmarks": []}]
    rows = [{"timestamp_ns": 1000, "state": "WAITING", "reason": "</script>", "closed_hold": None}]
    target = tmp_path / "report.html"
    feedback.write_overlay(target, tmp_path / "clip.mp4", poses, rows, {"status": "UNLABELLED"})
    html = target.read_text()
    assert '"reason": "</script>"' not in html
    assert '"reason": "<\\/script>"' in html
