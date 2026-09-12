"""Window-local jumping-jack predicate support diagnostics."""

from __future__ import annotations

import importlib.util
import json
import sys
from copy import deepcopy
from pathlib import Path

import pytest

TOOLS = Path(__file__).parents[2] / "tools"
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location(
    "offline_jumping_jack_feedback", TOOLS / "offline_jumping_jack_feedback.py"
)
assert SPEC and SPEC.loader
feedback = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = feedback
SPEC.loader.exec_module(feedback)


CONFIG = {
    "arms_down_degrees": 30.0,
    "arms_up_degrees": 100.0,
    "legs_together_ratio": 1.4,
    "maximum_gap_ns": 10,
    "minimum_endpoint_samples": 2,
}


def labels() -> dict:
    return {
        "version": 1,
        "video_sha256": "a" * 64,
        "annotation_status": "visually_reviewed",
        "annotation_method": "independent visual inspection before detector output",
        "events": [{"start_ns": 10, "end_ns": 40}],
    }


def row(timestamp_ns: int, arm: object, leg: object, reason: str = "NONE") -> dict:
    return {
        "timestamp_ns": timestamp_ns,
        "arm_degrees": arm,
        "leg_spread_ratio": leg,
        "reason": reason,
    }


def test_support_counts_one_vs_two_qualifying_frames_and_includes_boundaries() -> None:
    rows = [
        row(10, 30.0, 1.4),
        row(20, 29.0, 1.3),
        row(30, 31.0, 1.3),
        row(40, 28.0, 1.5),
    ]

    result = feedback.summarize_closing_predicate_support(rows, CONFIG, labels())

    assert result["status"] == "LABELLED"
    assert result["windows"] == [
        {
            "event_index": 0,
            "start_ns": 10,
            "end_ns": 40,
            "usable_rows": 4,
            "minimum_arm_degrees": 28.0,
            "minimum_leg_spread_ratio": 1.3,
            "arm_predicate_rows": 3,
            "leg_predicate_rows": 3,
            "joint_predicate_rows": 2,
            "longest_joint_predicate_run": 2,
            "joint_predicate_timestamps_ns": [10, 20],
            "minimum_endpoint_samples": 2,
        }
    ]


def test_support_separates_arm_and_leg_failures_and_breaks_on_unusable_or_gap() -> None:
    rows = [
        row(0, 29.0, 1.3),
        row(5, 31.0, 1.3),
        row(10, 29.0, 1.5),
        row(15, 29.0, 1.3),
        row(20, 29.0, 1.3, reason="LOW_CONFIDENCE"),
        row(25, 29.0, 1.3),
        row(40, 29.0, 1.3),
    ]
    event_labels = {**labels(), "events": [{"start_ns": 0, "end_ns": 40}]}

    summary = feedback.summarize_closing_predicate_support(rows, CONFIG, event_labels)["windows"][0]

    assert (summary["arm_predicate_rows"], summary["leg_predicate_rows"]) == (5, 5)
    assert summary["joint_predicate_rows"] == 4
    assert summary["joint_predicate_timestamps_ns"] == [0, 15, 25, 40]
    assert summary["longest_joint_predicate_run"] == 1


@pytest.mark.parametrize("value", [True, False, "29.0", None, float("nan"), float("inf")])
def test_non_numeric_geometry_is_not_usable(value: object) -> None:
    summary = feedback.summarize_closing_predicate_support([row(10, value, 1.3)], CONFIG, labels())[
        "windows"
    ][0]

    assert summary["usable_rows"] == 0
    assert summary["minimum_arm_degrees"] is None
    assert summary["joint_predicate_rows"] == 0


def test_no_labels_is_unlabelled_and_does_not_become_zero_event_perfection() -> None:
    result = feedback.summarize_closing_predicate_support([row(10, 20.0, 1.0)], CONFIG, None)

    assert result["status"] == "UNLABELLED"
    assert result["windows"] == []
    assert "accuracy" not in result


def test_window_boundary_does_not_bridge_and_inputs_are_unchanged() -> None:
    rows = [row(5, 20.0, 1.0), row(6, 20.0, 1.0)]
    event_labels = {
        **labels(),
        "events": [{"start_ns": 0, "end_ns": 5}, {"start_ns": 6, "end_ns": 10}],
    }
    original_rows, original_config, original_labels = (
        deepcopy(rows),
        deepcopy(CONFIG),
        deepcopy(event_labels),
    )

    result = feedback.summarize_closing_predicate_support(rows, CONFIG, event_labels)

    assert [window["longest_joint_predicate_run"] for window in result["windows"]] == [1, 1]
    assert rows == original_rows
    assert CONFIG == original_config
    assert event_labels == original_labels


def test_main_emits_diagnostic_without_changing_comparison_or_bindings(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video, model, config, labels_path = (
        tmp_path / name for name in ("video", "model", "config.json", "labels.json")
    )
    cache = tmp_path / "cache"
    predictions = tmp_path / "predictions.jsonl"
    output = tmp_path / "report.json"
    video.write_bytes(b"video")
    model.write_bytes(b"model")
    config.write_text(json.dumps(CONFIG))
    label_record = {**labels(), "video_sha256": feedback.sha256(video)}
    labels_path.write_text(json.dumps(label_record))
    cache.mkdir()
    pose_rows = [{"timestamp_ns": 10}, {"timestamp_ns": 20}]
    (cache / "poses.jsonl").write_text("".join(json.dumps(item) + "\n" for item in pose_rows))
    metadata = {
        "coordinate_space": "upright_source_normalized",
        "video_sha256": feedback.sha256(video),
        "model_sha256": feedback.sha256(model),
        "poses_sha256": feedback.sha256(cache / "poses.jsonl"),
        "source": {
            "id": "one",
            "group": "person-one",
            "split": "development",
            "video_sha256": feedback.sha256(video),
        },
    }
    (cache / "metadata.json").write_text(json.dumps(metadata))
    predictions.write_text(
        "".join(
            json.dumps(
                {
                    "timestamp_ns": timestamp,
                    "count": 0,
                    "counted": False,
                    "reason": "NONE",
                    "arm_degrees": 20.0,
                    "leg_spread_ratio": 1.0,
                }
            )
            + "\n"
            for timestamp in (10, 20)
        )
    )
    (predictions.with_suffix(".run.json")).write_text(
        json.dumps(
            {
                "config_sha256": feedback.sha256(config),
                "poses_sha256": feedback.sha256(cache / "poses.jsonl"),
                "predictions_sha256": feedback.sha256(predictions),
            }
        )
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "offline_jumping_jack_feedback.py",
            "--video",
            str(video),
            "--model",
            str(model),
            "--cache",
            str(cache),
            "--predictions",
            str(predictions),
            "--labels",
            str(labels_path),
            "--config",
            str(config),
            "--output",
            str(output),
        ],
    )

    feedback.main()

    report = json.loads(output.read_text())
    assert (report["matched"], report["missed"], report["extra"]) == (0, 1, 0)
    assert report["kotlin_run"]["predictions_sha256"] == feedback.sha256(predictions)
    assert report["closing_predicate_support"]["status"] == "LABELLED"
    assert "calibration" not in report
    assert "evaluation_frame_count" not in report


def _write_run(
    tmp_path: Path,
    *,
    config: dict,
    prediction_rows: list[dict],
    labels_record: dict | None,
) -> tuple[Path, ...]:
    video, model, config_path, labels_path = (
        tmp_path / name for name in ("video", "model", "config.json", "labels.json")
    )
    cache = tmp_path / "cache"
    predictions = tmp_path / "predictions.jsonl"
    output = tmp_path / "report.json"
    video.write_bytes(b"video")
    model.write_bytes(b"model")
    config_path.write_text(json.dumps(config))
    if labels_record is not None:
        labels_path.write_text(
            json.dumps({**labels_record, "video_sha256": feedback.sha256(video)})
        )
    cache.mkdir()
    pose_rows = [{"timestamp_ns": row["timestamp_ns"]} for row in prediction_rows]
    (cache / "poses.jsonl").write_text("".join(json.dumps(item) + "\n" for item in pose_rows))
    metadata = {
        "coordinate_space": "upright_source_normalized",
        "video_sha256": feedback.sha256(video),
        "model_sha256": feedback.sha256(model),
        "poses_sha256": feedback.sha256(cache / "poses.jsonl"),
        "source": {
            "id": "one",
            "group": "person-one",
            "split": "development",
            "video_sha256": feedback.sha256(video),
        },
    }
    (cache / "metadata.json").write_text(json.dumps(metadata))
    predictions.write_text("".join(json.dumps(row) + "\n" for row in prediction_rows))
    (predictions.with_suffix(".run.json")).write_text(
        json.dumps(
            {
                "config_sha256": feedback.sha256(config_path),
                "poses_sha256": feedback.sha256(cache / "poses.jsonl"),
                "predictions_sha256": feedback.sha256(predictions),
            }
        )
    )
    return video, model, cache, predictions, config_path, labels_path, output


def _run_main(
    monkeypatch: pytest.MonkeyPatch,
    *,
    video,
    model,
    cache,
    predictions,
    config_path,
    labels_path,
    output,
    with_labels: bool,
) -> None:
    argv = [
        "offline_jumping_jack_feedback.py",
        "--video",
        str(video),
        "--model",
        str(model),
        "--cache",
        str(cache),
        "--predictions",
        str(predictions),
        "--config",
        str(config_path),
        "--output",
        str(output),
    ]
    if with_labels:
        argv += ["--labels", str(labels_path)]
    monkeypatch.setattr(sys, "argv", argv)
    feedback.main()


def _practice_row(
    timestamp_ns: int,
    arm: float,
    leg: float,
    calibration: dict | None = None,
    evaluation: bool = True,
) -> dict:
    row = {
        "timestamp_ns": timestamp_ns,
        "count": 0,
        "counted": False,
        "reason": "NONE",
        "arm_degrees": arm,
        "leg_spread_ratio": leg,
        "evaluation": evaluation,
    }
    if calibration is not None:
        row["calibration"] = calibration
    return row


CALIBRATION = {
    "profile": "supervised_arms_down_projected_range_v1",
    "start_ns": 0,
    "end_ns": 40,
    "minimum_range_degrees": 20.0,
    "endpoint_fraction": 0.15,
    "arms_down_degrees": 35.0,
    "arms_up_degrees": 100.0,
}


def test_missing_calibration_metadata_for_a_practice_enabled_config_is_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = {
        **CONFIG,
        "practice_window": {
            "start_ns": 0,
            "end_ns": 40,
            "minimum_range_degrees": 20.0,
            "endpoint_fraction": 0.15,
        },
    }
    rows = [
        _practice_row(10, 20.0, 1.0, calibration=None, evaluation=False),
        _practice_row(50, 20.0, 1.0),
    ]
    paths = _write_run(tmp_path, config=config, prediction_rows=rows, labels_record=None)
    video, model, cache, predictions, config_path, labels_path, output = paths

    with pytest.raises(ValueError, match="calibration"):
        _run_main(
            monkeypatch,
            video=video,
            model=model,
            cache=cache,
            predictions=predictions,
            config_path=config_path,
            labels_path=labels_path,
            output=output,
            with_labels=False,
        )
    assert not output.exists()


def test_overlapping_practice_labels_are_rejected_not_silently_dropped(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = {
        **CONFIG,
        "practice_window": {
            "start_ns": 0,
            "end_ns": 40,
            "minimum_range_degrees": 20.0,
            "endpoint_fraction": 0.15,
        },
    }
    rows = [
        _practice_row(10, 20.0, 1.0, calibration=CALIBRATION, evaluation=False),
        _practice_row(50, 20.0, 1.0),
    ]
    overlapping_labels = {**labels(), "events": [{"start_ns": 5, "end_ns": 20}]}
    paths = _write_run(
        tmp_path, config=config, prediction_rows=rows, labels_record=overlapping_labels
    )
    video, model, cache, predictions, config_path, labels_path, output = paths

    with pytest.raises(ValueError, match="overlap"):
        _run_main(
            monkeypatch,
            video=video,
            model=model,
            cache=cache,
            predictions=predictions,
            config_path=config_path,
            labels_path=labels_path,
            output=output,
            with_labels=True,
        )
    assert not output.exists()


def test_effective_learned_arms_down_feeds_the_closing_predicate_diagnostic_not_input_value(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = {
        **CONFIG,
        "practice_window": {
            "start_ns": 0,
            "end_ns": 40,
            "minimum_range_degrees": 20.0,
            "endpoint_fraction": 0.15,
        },
    }
    assert config["arms_down_degrees"] == 30.0
    rows = [
        _practice_row(10, 20.0, 1.0, calibration=CALIBRATION, evaluation=False),
        # 33.0 is above the input arms_down_degrees (30.0) but below the learned 35.0: only the
        # effective/learned threshold, not the input config, may admit it as a closing-predicate pass.
        _practice_row(50, 33.0, 1.0),
    ]
    event_labels = {**labels(), "events": [{"start_ns": 41, "end_ns": 60}]}
    paths = _write_run(tmp_path, config=config, prediction_rows=rows, labels_record=event_labels)
    video, model, cache, predictions, config_path, labels_path, output = paths

    _run_main(
        monkeypatch,
        video=video,
        model=model,
        cache=cache,
        predictions=predictions,
        config_path=config_path,
        labels_path=labels_path,
        output=output,
        with_labels=True,
    )

    report = json.loads(output.read_text())
    assert report["calibration"] == CALIBRATION
    assert report["evaluation_frame_count"] == 1
    window = report["closing_predicate_support"]["windows"][0]
    assert window["arm_predicate_rows"] == 1
    assert window["joint_predicate_timestamps_ns"] == [50]


def _practice_run(
    tmp_path: Path,
    *,
    first_row_calibration: dict | None,
    second_row_calibration: dict | None = None,
):
    config = {
        **CONFIG,
        "practice_window": {
            "start_ns": 0,
            "end_ns": 40,
            "minimum_range_degrees": 20.0,
            "endpoint_fraction": 0.15,
        },
    }
    rows = [
        _practice_row(10, 20.0, 1.0, calibration=first_row_calibration, evaluation=False),
        _practice_row(50, 20.0, 1.0),
    ]
    if second_row_calibration is not None:
        rows[1]["calibration"] = second_row_calibration
    return _write_run(tmp_path, config=config, prediction_rows=rows, labels_record=None)


@pytest.mark.parametrize(
    "mutation",
    [
        {"profile": "unknown_profile_v1"},
        {"end_ns": 41},
        {"start_ns": 1},
        {"minimum_range_degrees": 21.0},
        {"endpoint_fraction": 0.16},
        {"arms_up_degrees": 99.0},
        {"arms_down_degrees": True},
        {"arms_down_degrees": "35.0"},
        {"arms_down_degrees": float("nan")},
        {"arms_down_degrees": float("inf")},
        {"arms_down_degrees": 100.0},
        {"arms_down_degrees": -1.0},
    ],
)
def test_inconsistent_calibration_metadata_is_rejected_before_report_write(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, mutation: dict
) -> None:
    calibration = {**CALIBRATION, **mutation}
    video, model, cache, predictions, config_path, labels_path, output = _practice_run(
        tmp_path,
        first_row_calibration=calibration,
    )

    with pytest.raises(ValueError):
        _run_main(
            monkeypatch,
            video=video,
            model=model,
            cache=cache,
            predictions=predictions,
            config_path=config_path,
            labels_path=labels_path,
            output=output,
            with_labels=False,
        )
    assert not output.exists()


def test_repeated_calibration_metadata_on_a_later_row_is_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video, model, cache, predictions, config_path, labels_path, output = _practice_run(
        tmp_path,
        first_row_calibration=CALIBRATION,
        second_row_calibration=CALIBRATION,
    )

    with pytest.raises(ValueError, match="calibration"):
        _run_main(
            monkeypatch,
            video=video,
            model=model,
            cache=cache,
            predictions=predictions,
            config_path=config_path,
            labels_path=labels_path,
            output=output,
            with_labels=False,
        )
    assert not output.exists()


def test_calibration_metadata_on_a_config_without_practice_window_is_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    rows = [
        _practice_row(10, 20.0, 1.0, calibration=CALIBRATION, evaluation=True),
        _practice_row(50, 20.0, 1.0),
    ]
    paths = _write_run(tmp_path, config=CONFIG, prediction_rows=rows, labels_record=None)
    video, model, cache, predictions, config_path, labels_path, output = paths

    with pytest.raises(ValueError, match="practice_window"):
        _run_main(
            monkeypatch,
            video=video,
            model=model,
            cache=cache,
            predictions=predictions,
            config_path=config_path,
            labels_path=labels_path,
            output=output,
            with_labels=False,
        )
    assert not output.exists()


@pytest.mark.parametrize(
    "broken_rows",
    [
        [
            {"timestamp_ns": 10, "calibration": None, "evaluation": True},
            {"timestamp_ns": 50, "evaluation": True},
        ],
        [
            {"timestamp_ns": 10, "calibration": None, "evaluation": False},
            {"timestamp_ns": 50, "evaluation": False},
        ],
        [{"timestamp_ns": 10, "calibration": None}, {"timestamp_ns": 50}],
        [
            {"timestamp_ns": 10, "calibration": None, "evaluation": False, "count": 1},
            {"timestamp_ns": 50, "evaluation": True},
        ],
        [
            {"timestamp_ns": 10, "calibration": None, "evaluation": False, "counted": True},
            {"timestamp_ns": 50, "evaluation": True},
        ],
    ],
)
def test_missing_or_wrong_evaluation_flags_around_the_boundary_are_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, broken_rows: list[dict]
) -> None:
    config = {
        **CONFIG,
        "practice_window": {
            "start_ns": 0,
            "end_ns": 40,
            "minimum_range_degrees": 20.0,
            "endpoint_fraction": 0.15,
        },
    }
    rows = [dict(row) for row in broken_rows]
    rows[0]["calibration"] = CALIBRATION
    rows[0].setdefault("count", 0)
    rows[0].setdefault("counted", False)
    rows[1].setdefault("count", 0)
    rows[1].setdefault("counted", False)
    paths = _write_run(tmp_path, config=config, prediction_rows=rows, labels_record=None)
    video, model, cache, predictions, config_path, labels_path, output = paths

    with pytest.raises(ValueError):
        _run_main(
            monkeypatch,
            video=video,
            model=model,
            cache=cache,
            predictions=predictions,
            config_path=config_path,
            labels_path=labels_path,
            output=output,
            with_labels=False,
        )
    assert not output.exists()


def test_valid_baseline_and_valid_practice_reports_still_succeed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Baseline (no practice_window, no calibration metadata) must still succeed.
    baseline_rows = [
        {
            "timestamp_ns": 10,
            "count": 0,
            "counted": False,
            "reason": "NONE",
            "arm_degrees": 20.0,
            "leg_spread_ratio": 1.0,
        },
    ]
    video, model, cache, predictions, config_path, labels_path, output = _write_run(
        tmp_path,
        config=CONFIG,
        prediction_rows=baseline_rows,
        labels_record=None,
    )
    _run_main(
        monkeypatch,
        video=video,
        model=model,
        cache=cache,
        predictions=predictions,
        config_path=config_path,
        labels_path=labels_path,
        output=output,
        with_labels=False,
    )
    assert "calibration" not in json.loads(output.read_text())

    # A fully valid practice run must still succeed and report the calibration.
    (tmp_path / "practice").mkdir()
    video2, model2, cache2, predictions2, config_path2, labels_path2, output2 = _practice_run(
        tmp_path / "practice",
        first_row_calibration=CALIBRATION,
    )
    _run_main(
        monkeypatch,
        video=video2,
        model=model2,
        cache=cache2,
        predictions=predictions2,
        config_path=config_path2,
        labels_path=labels_path2,
        output=output2,
        with_labels=False,
    )
    assert json.loads(output2.read_text())["calibration"] == CALIBRATION


CAUSAL_CALIBRATION = {
    "profile": "causal_first_supported_return_v1",
    "start_ns": 0,
    "deadline_ns": 1000,
    "end_ns": 500,
    "minimum_range_degrees": 20.0,
    "endpoint_fraction": 0.15,
    "arms_down_degrees": 35.0,
    "arms_up_degrees": 100.0,
}


def _causal_search_config() -> dict:
    return {
        **CONFIG,
        "practice_search": {
            "start_ns": 0,
            "deadline_ns": 1000,
            "minimum_range_degrees": 20.0,
            "endpoint_fraction": 0.15,
        },
    }


def _causal_rows(calibration: dict | None = CAUSAL_CALIBRATION) -> list[dict]:
    prefix = [
        _practice_row(
            timestamp,
            20.0,
            1.0,
            calibration=calibration if timestamp == 0 else None,
            evaluation=False,
        )
        for timestamp in (0, 100, 200, 300, 400, 500)
    ]
    for row in prefix:
        row["reason"] = "CALIBRATION_PRACTICE"
    return prefix + [_practice_row(600, 33.0, 1.0)]


def test_causal_receipt_uses_actual_end_and_reports_the_frozen_practice_prefix(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    event_labels = {**labels(), "events": [{"start_ns": 600, "end_ns": 700}]}
    paths = _write_run(
        tmp_path,
        config=_causal_search_config(),
        prediction_rows=_causal_rows(),
        labels_record=event_labels,
    )
    video, model, cache, predictions, config_path, labels_path, output = paths

    _run_main(
        monkeypatch,
        video=video,
        model=model,
        cache=cache,
        predictions=predictions,
        config_path=config_path,
        labels_path=labels_path,
        output=output,
        with_labels=True,
    )

    report = json.loads(output.read_text())
    assert report["calibration"] == CAUSAL_CALIBRATION
    assert report["reason_counts"]["CALIBRATION_PRACTICE"] == 6
    assert report["evaluation_frame_count"] == 1
    assert report["closing_predicate_support"]["windows"][0]["joint_predicate_timestamps_ns"] == [
        600
    ]


def test_causal_config_or_receipt_mismatch_is_rejected_before_report_write(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    invalid_cases = [
        (
            {
                **_causal_search_config(),
                "practice_window": {
                    "start_ns": 0,
                    "end_ns": 500,
                    "minimum_range_degrees": 20.0,
                    "endpoint_fraction": 0.15,
                },
            },
            _causal_rows(),
            None,
        ),
        (
            _causal_search_config(),
            _causal_rows({**CAUSAL_CALIBRATION, "end_ns": 501}),
            None,
        ),
        (
            _causal_search_config(),
            _causal_rows(),
            {**labels(), "events": [{"start_ns": 500, "end_ns": 700}]},
        ),
    ]
    for index, (config, rows, event_labels) in enumerate(invalid_cases):
        case_dir = tmp_path / str(index)
        case_dir.mkdir()
        paths = _write_run(
            case_dir,
            config=config,
            prediction_rows=rows,
            labels_record=event_labels,
        )
        video, model, cache, predictions, config_path, labels_path, output = paths

        with pytest.raises(ValueError):
            _run_main(
                monkeypatch,
                video=video,
                model=model,
                cache=cache,
                predictions=predictions,
                config_path=config_path,
                labels_path=labels_path,
                output=output,
                with_labels=event_labels is not None,
            )
        assert not output.exists()
