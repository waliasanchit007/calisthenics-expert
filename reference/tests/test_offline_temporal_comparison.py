"""Causal timing and missingness must survive the temporal comparison."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


@pytest.fixture
def temporal():
    directory = Path(__file__).parents[2] / "tools"
    sys.path.insert(0, str(directory))
    path = directory / "offline_temporal_comparison.py"
    assert path.exists(), "causal temporal comparison has not been implemented"
    try:
        spec = importlib.util.spec_from_file_location("offline_temporal_comparison", path)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        yield module
    finally:
        sys.path.remove(str(directory))


def samples(times, *, missing=None):
    return [(time, None if time == missing else [(float(time), 1.0, 2.0)]) for time in times]


def config():
    return {"window_ns": 200, "grid_ns": 100, "maximum_gap_ns": 150, "sample_tolerance_ns": 40}


def test_window_uses_ordered_past_observations_not_future_neighbours(temporal):
    rows = samples([0, 70, 110, 170, 200, 210])
    points, times, reason = temporal.causal_window(rows, 200, config())
    assert reason is None
    assert times == [0, 70, 200]
    assert [point[0][0] for point in points] == [0, 70, 200]


def test_missing_off_grid_observation_still_invalidates_window(temporal):
    points, times, reason = temporal.causal_window(
        samples([0, 50, 100, 200], missing=50), 200, config()
    )
    assert points is None and times == []
    assert reason == "tracking_gap"


def test_duplicate_timestamps_cannot_be_counted_as_new_evidence(temporal):
    with pytest.raises(ValueError, match="increasing"):
        temporal.causal_window(samples([0, 100, 100, 200]), 200, config())


def test_warmup_does_not_borrow_future_or_pad_first_frame(temporal):
    points, times, reason = temporal.causal_window(samples([0, 100, 200]), 100, config())
    assert points is None and times == [] and reason == "warming_up"


def test_old_observation_is_not_silently_reused_on_grid(temporal):
    _, _, reason = temporal.causal_window(samples([0, 30, 130, 200]), 200, config())
    assert reason == "sampling_gap"


def test_large_capture_gap_rejected_even_with_loose_sampling_tolerance(temporal):
    cfg = {**config(), "sample_tolerance_ns": 100}
    _, _, reason = temporal.causal_window(samples([0, 180, 200]), 200, cfg)
    assert reason == "tracking_gap"


def test_new_window_recovers_without_stitching_pre_gap_motion(temporal):
    rows = samples([0, 100, 200, 300, 400, 500], missing=200)
    assert temporal.causal_window(rows, 400, config())[2] == "tracking_gap"
    assert temporal.causal_window(rows, 500, config())[1] == [300, 400, 500]


@pytest.mark.parametrize(
    "key,value",
    [
        ("window_ns", 0),
        ("grid_ns", 0),
        ("maximum_gap_ns", -1),
        ("window_ns", 250),
        ("grid_ns", True),
    ],
)
def test_invalid_sampling_config_fails_not_changes_algorithm(temporal, key, value):
    with pytest.raises(ValueError):
        temporal.causal_window(samples([0, 100, 200]), 200, {**config(), key: value})


def test_paired_summary_uses_same_admitted_timestamps_and_preserves_rejections(temporal):
    rows = [
        {"temporal_label": "other", "static_label": "squat", "reason": None},
        {"temporal_label": "squat", "static_label": "squat", "reason": None},
        {"temporal_label": None, "static_label": None, "reason": "tracking_gap"},
    ]
    summary = temporal.summarize(rows)
    assert summary["evaluated_windows"] == 2
    assert summary["attempted_windows"] == 3
    assert summary["static_labels"] == {"squat": 2}
    assert summary["temporal_labels"] == {"other": 1, "squat": 1}
    assert summary["rejections"] == {"tracking_gap": 1}
    assert "accuracy" not in summary


def prepare_request(tmp_path):
    import hashlib

    model = tmp_path / "model"
    model.write_bytes(b"synthetic")
    items = []
    for name in ("bank", "query"):
        video = tmp_path / f"{name}.mp4"
        video.write_bytes(name.encode())
        video_hash = hashlib.sha256(video.read_bytes()).hexdigest()
        directory = tmp_path / name
        directory.mkdir()
        rows = [
            {
                "timestamp_ns": time,
                "width": 640,
                "height": 360,
                "landmarks": [
                    {
                        "index": i,
                        "x": 0.5,
                        "y": 0.1 + i * 0.02,
                        "z": 0.0,
                        "visibility": 1.0,
                        "presence": 1.0,
                    }
                    for i in range(33)
                ],
            }
            for time in (0, 100, 200, 300, 400)
        ]
        poses = directory / "poses.jsonl"
        poses.write_text("".join(json.dumps(row) + "\n" for row in rows))
        (directory / "metadata.json").write_text(
            json.dumps(
                {
                    "frame_count": len(rows),
                    "coordinate_space": "upright_source_normalized",
                    "poses_sha256": hashlib.sha256(poses.read_bytes()).hexdigest(),
                    "video_sha256": video_hash,
                    "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
                    "source": {
                        "id": name,
                        "group": name,
                        "source_sha256": video_hash,
                        "video_sha256": video_hash,
                        "split": "development",
                        "source_url": "synthetic",
                        "license_url": "synthetic",
                    },
                }
            )
        )
        items.append(
            {
                "video": str(video),
                "cache": str(directory),
                "annotation_method": "independent synthetic fixture",
                "segments": [{"label": "fixture", "start_ns": 0, "end_ns": 400}],
            }
        )
    manifest = tmp_path / "manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "version": 1,
                "model": str(model),
                "minimum_confidence": 0.5,
                "sample_period_ns": 100,
                "query_period_ns": 100,
                "temporal": config(),
                "bank": [items[0]],
                "queries": [items[1]],
            }
        )
    )
    return manifest


def test_real_runner_compares_only_causal_matched_windows(temporal, tmp_path):
    manifest = prepare_request(tmp_path)
    result = temporal.run_temporal(manifest, tmp_path / "out")
    query = result["queries"][0]
    assert query["attempted_windows"] == 5
    assert query["evaluated_windows"] == 3
    assert query["static_labels"] == query["temporal_labels"] == {"fixture": 3}
    assert query["rejections"] == {"warming_up": 2}
    assert result["bank_class_counts"] == {"fixture": 3}
    assert "accuracy" not in query
    assert (tmp_path / "out/summary.json").exists()


def test_reusing_same_source_for_query_fails_before_output(temporal, tmp_path):
    manifest = prepare_request(tmp_path)
    request = json.loads(manifest.read_text())
    request["queries"] = request["bank"]
    manifest.write_text(json.dumps(request))
    with pytest.raises(ValueError, match="overlap"):
        temporal.run_temporal(manifest, tmp_path / "out")
    assert not (tmp_path / "out").exists()


def test_insufficient_example_class_is_not_silently_removed(temporal, tmp_path):
    manifest = prepare_request(tmp_path)
    request = json.loads(manifest.read_text())
    request["bank"][0]["segments"][0]["end_ns"] = 100
    manifest.write_text(json.dumps(request))
    with pytest.raises(ValueError, match="no usable windows"):
        temporal.run_temporal(manifest, tmp_path / "out")
