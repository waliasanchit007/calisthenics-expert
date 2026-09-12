"""Behavioral tests for the bounded historical movement scorecard."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[2]
TOOL = ROOT / "tools/offline_movement_scorecard.py"
sys.path.insert(0, str(TOOL.parent))
SPEC = importlib.util.spec_from_file_location("offline_movement_scorecard", TOOL)
scorecard = None
if SPEC and SPEC.loader:
    try:
        scorecard = importlib.util.module_from_spec(SPEC)
        sys.modules[SPEC.name] = scorecard
        SPEC.loader.exec_module(scorecard)
    except (FileNotFoundError, ModuleNotFoundError):
        scorecard = None


def require_scorecard():
    assert scorecard is not None, "the historical scorecard module is missing"
    return scorecard


def write_report(tmp_path: Path, name: str, **updates) -> Path:
    report = {
        "status": "EXPLORATORY_COMPARISON",
        "runtime": {
            "runtime": "desktop_reference_not_android",
            "source": {"split": "development"},
            "model_sha256": "a" * 64,
        },
        "annotation_method": "visual review | before predictions",
        "config": {"side": "LEFT", "minimum_confidence": 0.5},
        "config_sha256": "c" * 64,
        "kotlin_run": {
            "engine_sources": {"CycleExperiment.kt": "d" * 64},
            "shared_jar_sha256": "e" * 64,
        },
        "source": {"video_sha256": "f" * 64, "id": "development-clip"},
        "model_sha256": "a" * 64,
        "predictions_sha256": "b" * 64,
        "frame_count": 120,
        "unusable_frames": 7,
        "reason_counts": {"NONE": 113, "LOW_CONFIDENCE": 7},
        "predicted": 10,
        "annotated": 10,
        "matched": 8,
        "missed": 2,
        "extra": 2,
        "missed_event_indices": [3, 8],
        "extra_timestamps_ns": [2100, 9100],
    }
    report.update(updates)
    path = tmp_path / name
    path.write_text(json.dumps(report), encoding="utf-8")
    return path


def write_hold_report(tmp_path: Path, name: str = "plank.json", **updates) -> Path:
    report = {
        "status": "EXPLORATORY_COMPARISON",
        "runtime": {
            "runtime": "desktop_reference_not_android",
            "source": {"group": "group-one", "split": "development", "video_sha256": "f" * 64},
            "model_sha256": "a" * 64,
        },
        "annotation_method": "a very long annotation method that belongs in details",
        "predicted_holds": 2,
        "annotated_intervals": 2,
        "missed_label_indices": [1],
        "label_matches": [{"coverage_ratio": 1.0}, {"coverage_ratio": 0.5}],
        "hold_diagnostics": [
            {
                "entry_timestamp_ns": 100,
                "exit_timestamp_ns": 200,
                "observed_ns": 90,
                "unlabelled_ns": 47_000_000_000,
                "end_reason": "CENSORED_AT_END_OF_INPUT",
            }
        ],
        "extra_hold_count": 1,
        "censored_hold_count": 1,
        "frame_count": 120,
        "unusable_frames": 7,
        "reason_counts": {"NONE": 113, "LOW_CONFIDENCE": 7},
        "config": {"maximum_orientation_degrees_from_horizontal": 15},
        "predictions_sha256": "b" * 64,
    }
    report.update(updates)
    path = tmp_path / name
    path.write_text(json.dumps(report), encoding="utf-8")
    return path


def manifest_for(tmp_path: Path, cases: list[dict]) -> Path:
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps({"version": 1, "cases": cases}), encoding="utf-8")
    return path


def reported_case(tmp_path: Path, identifier: str = "squat-development", **updates) -> dict:
    report = write_report(tmp_path, f"{identifier}.json", **updates)
    return {
        "id": identifier,
        "engine": "squat",
        "report": str(report.resolve()),
        "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
    }


def run_cli(manifest: Path, output_dir: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(TOOL), "--manifest", str(manifest), "--output-dir", str(output_dir)],
        text=True,
        capture_output=True,
        check=False,
    )


def test_equal_totals_keep_rep_misses_and_extras_visible_real_file(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(tmp_path, [reported_case(tmp_path)])

    result = module.build_scorecard(manifest)
    row = result["rep_rows"][0]
    assert (row["metrics"]["predicted"], row["metrics"]["annotated"]) == (10, 10)
    assert (row["metrics"]["matched"], row["metrics"]["missed"], row["metrics"]["extra"]) == (
        8,
        2,
        2,
    )
    assert row["metrics"]["missed_event_indices"] == [3, 8]
    assert row["metrics"]["extra_timestamps_ns"] == [2100, 9100]


def test_missing_labels_are_unlabelled_and_never_a_green_result(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(
        tmp_path,
        [
            reported_case(
                tmp_path, status="UNLABELLED", annotated=None, matched=None, missed=None, extra=None
            )
        ],
    )

    row = module.build_scorecard(manifest)["rep_rows"][0]
    assert row["status"] == "UNLABELLED"
    assert row["metrics"]["predicted"] == 10
    assert "annotated" not in row["metrics"]
    assert row["verdict"] == "UNMEASURED"


def test_manifest_missing_case_is_explicitly_unmeasured(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "plank-not-available",
                "engine": "plank",
                "missing_reason": "no historical hold report",
            }
        ],
    )

    row = module.build_scorecard(manifest)["hold_rows"][0]
    assert row == {
        "id": "plank-not-available",
        "engine": "plank",
        "status": "UNMEASURED",
        "missing_reason": "no historical hold report",
    }
    assert "metrics" not in row


def test_hold_metrics_are_separate_from_rep_metrics(tmp_path: Path) -> None:
    module = require_scorecard()
    report = write_hold_report(tmp_path)
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "plank-development",
                "engine": "plank",
                "report": str(report.resolve()),
                "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
            }
        ],
    )

    row = module.build_scorecard(manifest)["hold_rows"][0]
    assert row["hold_metrics"]["predicted_holds"] == 2
    assert row["hold_metrics"]["missed_label_indices"] == [1]
    assert "rep_metrics" not in row
    assert "predicted" not in row["hold_metrics"]


def test_markdown_tables_are_compact_and_keep_hold_diagnostics_visible(tmp_path: Path) -> None:
    module = require_scorecard()
    rep = write_report(
        tmp_path,
        "compact-rep.json",
        runtime={
            "runtime": "desktop_reference_not_android",
            "source": {"group": "group-one", "split": "development", "video_sha256": "f" * 64},
            "model_sha256": "a" * 64,
        },
        annotation_method="a very long annotation method that belongs in details",
    )
    hold = write_hold_report(tmp_path, "compact-hold.json")
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "rep",
                "engine": "squat",
                "report": str(rep.resolve()),
                "report_sha256": hashlib.sha256(rep.read_bytes()).hexdigest(),
            },
            {
                "id": "hold",
                "engine": "plank",
                "report": str(hold.resolve()),
                "report_sha256": hashlib.sha256(hold.read_bytes()).hexdigest(),
            },
        ],
    )

    markdown = module._render_markdown(module.build_scorecard(manifest))
    assert (
        "| case | engine | status | runtime | split | predicted | annotated | matched | missed | extra | unusable/frame |"
        in markdown
    )
    assert "desktop_reference_not_android" in markdown
    assert "| development |" in markdown
    assert (
        "a very long annotation method that belongs in details"
        not in markdown.split("## Repetition reports", 1)[1].split("## Hold reports", 1)[0]
    )
    assert "47&#95;000&#95;000&#95;000" in markdown or "47000000000" in markdown
    assert "CENSORED_AT_END_OF_INPUT" in markdown


@pytest.mark.parametrize(
    "change, match",
    [
        (lambda report: report.pop("predicted"), "predicted"),
        (lambda report: report.pop("frame_count"), "frame_count"),
        (lambda report: report.update(unusable_frames=121), "unusable"),
        (lambda report: report.update(predicted=11), "matched.*extra"),
    ],
)
def test_report_shape_is_validated_before_projection(change, match, tmp_path: Path) -> None:
    module = require_scorecard()
    report_path = write_report(tmp_path, "shape.json")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    change(report)
    report_path.write_text(json.dumps(report), encoding="utf-8")
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "shape",
                "engine": "squat",
                "report": str(report_path.resolve()),
                "report_sha256": hashlib.sha256(report_path.read_bytes()).hexdigest(),
            }
        ],
    )

    with pytest.raises(ValueError, match=match):
        module.build_scorecard(manifest)


def test_plank_report_must_have_hold_shape_not_rep_shape(tmp_path: Path) -> None:
    module = require_scorecard()
    report = write_report(tmp_path, "rep-shaped-plank.json")
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "wrong-plank",
                "engine": "plank",
                "report": str(report.resolve()),
                "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
            }
        ],
    )
    with pytest.raises(ValueError, match="hold"):
        module.build_scorecard(manifest)


@pytest.mark.parametrize(
    "case_mutator, match",
    [
        (lambda case: case.update(id="squat-development"), "duplicate"),
        (lambda case: case.update(engine="burpee"), "engine"),
        (lambda case: case.update(missing_reason="reason"), "report and missing"),
        (lambda case: case.update(report_sha256="0" * 64), "hash"),
    ],
)
def test_invalid_manifest_cases_fail_before_scorecard(case_mutator, match, tmp_path: Path) -> None:
    module = require_scorecard()
    first = reported_case(tmp_path)
    second = reported_case(tmp_path, "lunge-development")
    case_mutator(second)
    manifest = manifest_for(tmp_path, [first, second])

    with pytest.raises(ValueError, match=match):
        module.build_scorecard(manifest)


def test_empty_manifest_is_rejected(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(tmp_path, [])
    with pytest.raises(ValueError, match="nonempty"):
        module.build_scorecard(manifest)


@pytest.mark.parametrize("report_state", ["missing", "malformed"])
def test_missing_or_malformed_report_cannot_create_completed_artifact(
    report_state: str, tmp_path: Path
) -> None:
    report = tmp_path / "report.json"
    if report_state == "malformed":
        report.write_text("not json", encoding="utf-8")
    case = {
        "id": "squat-bad-report",
        "engine": "squat",
        "report": str(report.resolve()),
        "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest()
        if report.exists()
        else "0" * 64,
    }
    manifest = manifest_for(tmp_path, [case])
    output = tmp_path / "scorecard-output"

    completed = run_cli(manifest, output)
    assert completed.returncode == 1
    assert not output.exists()


def test_report_digest_is_checked_against_the_bytes_that_are_loaded(tmp_path: Path) -> None:
    module = require_scorecard()
    report = write_report(tmp_path, "mutable.json")
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "mutable",
                "engine": "squat",
                "report": str(report.resolve()),
                "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
            }
        ],
    )
    report.write_text(report.read_text(encoding="utf-8") + "\n", encoding="utf-8")

    with pytest.raises(ValueError, match="hash"):
        module.build_scorecard(manifest)


def test_cli_writes_private_summary_and_escaped_markdown_to_new_directory(tmp_path: Path) -> None:
    report = write_report(tmp_path, "cli.json")
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "squat-cli",
                "engine": "squat",
                "report": str(report.resolve()),
                "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
            }
        ],
    )
    output = tmp_path / "private-output"

    completed = run_cli(manifest, output)
    assert completed.returncode == 0, completed.stderr
    summary = json.loads((output / "summary.json").read_text(encoding="utf-8"))
    markdown = (output / "scorecard.md").read_text(encoding="utf-8")
    assert summary["manifest_sha256"] == hashlib.sha256(manifest.read_bytes()).hexdigest()
    assert (
        summary["rep_rows"][0]["report_sha256"] == hashlib.sha256(report.read_bytes()).hexdigest()
    )
    assert summary["rep_rows"][0]["annotation_method"] == "visual review | before predictions"
    assert "visual review &#124; before predictions" not in markdown
    assert "runtime" in markdown and "split" in markdown
    assert (
        "annotation_method, provenance, and original report metrics/reasons are in summary.json"
        in markdown
    )
    assert "a very long annotation method that belongs in details" not in markdown


def test_cli_refuses_to_overwrite_existing_output(tmp_path: Path) -> None:
    write_report(tmp_path, "existing.json")
    manifest = manifest_for(tmp_path, [reported_case(tmp_path)])
    output = tmp_path / "already-there"
    output.mkdir()
    sentinel = output / "sentinel.txt"
    sentinel.write_text("keep", encoding="utf-8")

    completed = run_cli(manifest, output)
    assert completed.returncode == 1
    assert sentinel.read_text(encoding="utf-8") == "keep"
    assert not (output / "summary.json").exists()


def write_situp_report(tmp_path: Path, name: str = "situp.json", **updates) -> Path:
    """A genuine sit-up report shape: torso-elevation missingness, not unusable_frames."""
    report = {
        "status": "EXPLORATORY_COMPARISON",
        "engine": "situp",
        "runtime": {
            "runtime": "desktop_reference_not_android",
            "source": {"split": "development"},
            "model_sha256": "1" * 64,
        },
        "annotation_method": "independent visual labelling before predictions",
        "config": {"minimum_torso_elevation_degrees": 35},
        "config_sha256": "2" * 64,
        "kotlin_run": {
            "engine_sources": {"SitupCycleExperiment.kt": "3" * 64},
            "shared_jar_sha256": "4" * 64,
        },
        "labels_sha256": "5" * 64,
        "predictions_sha256": "6" * 64,
        "frame_count": 1080,
        "frames_missing_torso_elevation_geometry": 0,
        "reason_counts": {"NONE": 1080},
        "predicted": 9,
        "annotated": 9,
        "matched": 8,
        "missed": 1,
        "extra": 1,
        "missed_event_indices": [5],
        "extra_timestamps_ns": [31_400_000_000],
    }
    report.update(updates)
    path = tmp_path / name
    path.write_text(json.dumps(report), encoding="utf-8")
    return path


def situp_case(tmp_path: Path, identifier: str = "situp-development", **updates) -> dict:
    report = write_situp_report(tmp_path, f"{identifier}.json", **updates)
    return {
        "id": identifier,
        "engine": "situp",
        "report": str(report.resolve()),
        "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
    }


def test_situp_equal_totals_still_show_one_miss_and_one_extra(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(tmp_path, [situp_case(tmp_path)])

    result = module.build_scorecard(manifest)
    assert [row["id"] for row in result["rep_rows"]] == ["situp-development"]
    assert result["hold_rows"] == []
    row = result["rep_rows"][0]
    assert row["engine"] == "situp"
    assert row["status"] == "EXPLORATORY_COMPARISON"
    assert row["verdict"] == "COMPARISON_RECORDED"
    metrics = row["rep_metrics"]
    assert (metrics["predicted"], metrics["annotated"]) == (9, 9)
    assert (metrics["matched"], metrics["missed"], metrics["extra"]) == (8, 1, 1)
    assert metrics["missed_event_indices"] == [5]
    assert metrics["extra_timestamps_ns"] == [31_400_000_000]
    assert "average_accuracy" not in row and "score" not in row


def test_situp_torso_elevation_missingness_is_projected_and_range_checked(
    tmp_path: Path,
) -> None:
    module = require_scorecard()
    manifest = manifest_for(tmp_path, [situp_case(tmp_path)])

    metrics = module.build_scorecard(manifest)["rep_rows"][0]["rep_metrics"]
    assert metrics["frames_missing_torso_elevation_geometry"] == 0
    assert metrics["frame_count"] == 1080
    assert "unusable_frames" not in metrics

    over = manifest_for(
        tmp_path,
        [
            situp_case(
                tmp_path,
                "situp-over-range",
                frames_missing_torso_elevation_geometry=1081,
            )
        ],
    )
    with pytest.raises(ValueError, match="frames_missing_torso_elevation_geometry"):
        module.build_scorecard(over)


def test_situp_report_without_its_missingness_field_is_rejected(tmp_path: Path) -> None:
    module = require_scorecard()
    report_path = write_situp_report(tmp_path, "no-missingness.json")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    del report["frames_missing_torso_elevation_geometry"]
    report_path.write_text(json.dumps(report), encoding="utf-8")
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "situp-no-missingness",
                "engine": "situp",
                "report": str(report_path.resolve()),
                "report_sha256": hashlib.sha256(report_path.read_bytes()).hexdigest(),
            }
        ],
    )

    with pytest.raises(ValueError, match="frames_missing_torso_elevation_geometry"):
        module.build_scorecard(manifest)


def test_unlabelled_situp_keeps_its_count_but_stays_unmeasured(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(
        tmp_path,
        [
            situp_case(
                tmp_path,
                "situp-unlabelled",
                status="UNLABELLED",
                annotated=None,
                matched=None,
                missed=None,
                extra=None,
                missed_event_indices=None,
                extra_timestamps_ns=None,
            )
        ],
    )

    row = module.build_scorecard(manifest)["rep_rows"][0]
    assert row["status"] == "UNLABELLED"
    assert row["verdict"] == "UNMEASURED"
    assert row["rep_metrics"]["predicted"] == 9
    for absent in ("annotated", "matched", "missed", "extra"):
        assert absent not in row["rep_metrics"]


def test_explicitly_missing_situp_case_stays_unmeasured(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "situp-w16-frozen",
                "engine": "situp",
                "missing_reason": "official source download unavailable",
            }
        ],
    )

    result = module.build_scorecard(manifest)
    assert result["rep_rows"][0] == {
        "id": "situp-w16-frozen",
        "engine": "situp",
        "status": "UNMEASURED",
        "missing_reason": "official source download unavailable",
    }
    assert "metrics" not in result["rep_rows"][0]


def test_situp_shaped_report_is_rejected_for_the_plank_hold_engine(tmp_path: Path) -> None:
    module = require_scorecard()
    report = write_situp_report(tmp_path, "situp-as-plank.json")
    manifest = manifest_for(
        tmp_path,
        [
            {
                "id": "situp-as-plank",
                "engine": "plank",
                "report": str(report.resolve()),
                "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
            }
        ],
    )

    with pytest.raises(ValueError, match="predicted_holds"):
        module.build_scorecard(manifest)


def test_situp_and_squat_rows_share_one_rep_table_without_pooling(tmp_path: Path) -> None:
    module = require_scorecard()
    manifest = manifest_for(tmp_path, [reported_case(tmp_path), situp_case(tmp_path)])

    summary = module.build_scorecard(manifest)
    markdown = module._render_markdown(summary)
    assert [row["engine"] for row in summary["rep_rows"]] == ["squat", "situp"]
    assert "| situp-development | situp | EXPLORATORY_COMPARISON |" in markdown
    assert "| 0/1080 |" in markdown
    assert "average" not in markdown.lower()
