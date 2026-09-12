"""Protect coordinate, provenance and split boundaries of the offline comparator."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


@pytest.fixture
def comparison():
    directory = Path(__file__).parents[2] / "tools"
    path = directory / "offline_pose_comparison.py"
    assert path.exists(), "offline comparator is not implemented"
    sys.path.insert(0, str(directory))
    try:
        spec = importlib.util.spec_from_file_location("offline_pose_comparison", path)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        yield module
    finally:
        sys.path.remove(str(directory))


def pose():
    return {
        "timestamp_ns": 0,
        "width": 640,
        "height": 360,
        "landmarks": [
            {"index": i, "x": 0.5, "y": 0.25, "z": -0.1, "visibility": 0.9, "presence": 0.8}
            for i in range(33)
        ],
    }


def test_aspect_ratio_and_z_use_common_pixel_units(comparison):
    # Using width for Y would silently distort non-square inputs.
    points, reason = comparison.pixel_points(pose(), 0.5)
    assert reason is None
    assert points[0] == pytest.approx((320, 90, -64))


@pytest.mark.parametrize("defect", ["absent", "duplicate", "nan", "presence", "visibility"])
def test_bad_landmarks_are_rejected_not_zero_filled(comparison, defect):
    row = pose()
    if defect == "absent":
        row["landmarks"] = []
    elif defect == "duplicate":
        row["landmarks"][1]["index"] = 0
    elif defect == "nan":
        row["landmarks"][15]["z"] = float("nan")
    else:
        row["landmarks"][15][defect] = 0.49
    points, reason = comparison.pixel_points(row, 0.5)
    assert points is None
    assert reason is not None


def test_index_order_not_list_order_controls_embedding(comparison):
    row = pose()
    row["landmarks"][0]["x"] = 0.25
    row["landmarks"].reverse()
    points, _ = comparison.pixel_points(row, 0.5)
    assert points[0][0] == 160


@pytest.mark.parametrize("key", ["group", "source_sha256", "video_sha256"])
def test_same_parent_recording_or_group_cannot_train_and_evaluate(comparison, key):
    bank = {"group": "p1", "source_sha256": "a" * 64, "video_sha256": "b" * 64}
    query = {"group": "p2", "source_sha256": "c" * 64, "video_sha256": "d" * 64}
    query[key] = bank[key]
    with pytest.raises(ValueError, match="overlap"):
        comparison.check_separation([bank], query)


def test_disjoint_sources_do_not_become_heldout_accuracy(comparison):
    a = {"group": "p1", "source_sha256": "a" * 64, "video_sha256": "b" * 64}
    b = {"group": "p2", "source_sha256": "c" * 64, "video_sha256": "d" * 64}
    assert comparison.check_separation([a], b) is None


def test_tie_is_exposed_not_broken_as_confident_label(comparison):
    assert comparison.vote_label({"squat": 5, "lunge": 5}) is None
    assert comparison.vote_label({"squat": 6, "lunge": 4}) == "squat"
    assert comparison.vote_label({}) is None


def cache(comparison, tmp_path, name, model):
    video = tmp_path / f"{name}.mp4"
    video.write_bytes(name.encode())
    directory = tmp_path / name
    source = {
        "id": name,
        "group": name,
        "split": "development",
        "source_sha256": comparison.sha256(video),
        "video_sha256": comparison.sha256(video),
        "source_url": "https://example.invalid/synthetic",
        "license_url": "synthetic",
    }
    from offline_movement_feedback import write_pose_cache

    write_pose_cache(
        directory,
        [pose()],
        {
            "video_sha256": comparison.sha256(video),
            "model_sha256": comparison.sha256(model),
            "source": source,
            "runtime": "synthetic_test_only",
        },
    )
    return {"cache": str(directory), "video": str(video)}


def test_cache_tampering_is_detected_before_classification(comparison, tmp_path):
    model = tmp_path / "model"
    model.write_bytes(b"synthetic")
    item = cache(comparison, tmp_path, "train", model)
    comparison.read_cache(item, model)
    with (Path(item["cache"]) / "poses.jsonl").open("a") as handle:
        handle.write("{}\n")
    with pytest.raises(ValueError, match="hash"):
        comparison.read_cache(item, model)


def test_heldout_cache_cannot_be_used_as_example_bank(comparison, tmp_path):
    model = tmp_path / "model"
    model.write_bytes(b"synthetic")
    item = cache(comparison, tmp_path, "train", model)
    path = Path(item["cache"]) / "metadata.json"
    metadata = json.loads(path.read_text())
    metadata["source"]["split"] = "held_out"
    path.write_text(json.dumps(metadata))
    item.update(
        segments=[{"label": "squat", "start_ns": 0, "end_ns": 1}],
        annotation_method="synthetic independently specified",
    )
    with pytest.raises(ValueError, match="development"):
        comparison.build_bank([item], model, minimum_confidence=0.5, sample_period_ns=1)


def request(comparison, tmp_path):
    model = tmp_path / "model"
    model.write_bytes(b"synthetic")
    bank = cache(comparison, tmp_path, "bank", model)
    query = cache(comparison, tmp_path, "query", model)
    # Distinct, finite synthetic geometry; expectations concern real admission,
    # rejection and provenance, not an anatomical exercise label.
    for item in (bank, query):
        directory = Path(item["cache"])
        row = pose()
        for point in row["landmarks"]:
            point["y"] = 0.1 + point["index"] * 0.02
        rows = [row]
        if item is query:
            rows.append({**row, "timestamp_ns": 100, "landmarks": []})
        pose_path = directory / "poses.jsonl"
        pose_path.write_text("".join(json.dumps(row) + "\n" for row in rows))
        metadata_path = directory / "metadata.json"
        metadata = json.loads(metadata_path.read_text())
        metadata.update(poses_sha256=comparison.sha256(pose_path), frame_count=len(rows))
        metadata_path.write_text(json.dumps(metadata))
    bank.update(
        annotation_method="independent synthetic fixture",
        segments=[{"label": "example", "start_ns": 0, "end_ns": 0}],
    )
    path = tmp_path / "request.json"
    path.write_text(
        json.dumps(
            {
                "version": 1,
                "model": str(model),
                "minimum_confidence": 0.5,
                "sample_period_ns": 1,
                "bank": [bank],
                "queries": [query],
            }
        )
    )
    return path


def test_real_pipeline_reports_missingness_not_accuracy_and_binds_outputs(comparison, tmp_path):
    path = request(comparison, tmp_path)
    result = comparison.run_comparison(path, tmp_path / "result")
    assert result["status"] == "DEVELOPMENT_DIAGNOSTIC"
    assert result["request_sha256"] == comparison.sha256(path)
    row = result["queries"][0]
    assert row["frame_count"] == 2
    assert row["label_counts"] == {"example": 1}
    assert row["rejection_counts"] == {"missing_landmarks": 1}
    assert "accuracy" not in row
    assert row["predictions_sha256"] == comparison.sha256(tmp_path / "result/query-0.jsonl")
    predictions = [
        json.loads(line) for line in (tmp_path / "result/query-0.jsonl").read_text().splitlines()
    ]
    assert predictions[1]["votes"] == {}
    assert predictions[1]["label"] is None


def test_existing_result_is_not_overwritten(comparison, tmp_path):
    path = request(comparison, tmp_path)
    output = tmp_path / "result"
    output.mkdir()
    marker = output / "summary.json"
    marker.write_text("original")
    with pytest.raises(FileExistsError):
        comparison.run_comparison(path, output)
    assert marker.read_text() == "original"


def test_overlapping_query_rejected_before_any_result_is_published(comparison, tmp_path):
    path = request(comparison, tmp_path)
    value = json.loads(path.read_text())
    value["queries"] = [value["bank"][0]]
    path.write_text(json.dumps(value))
    with pytest.raises(ValueError, match="overlap"):
        comparison.run_comparison(path, tmp_path / "result")
    assert not (tmp_path / "result/summary.json").exists()


def test_metadata_change_during_run_cannot_publish_complete_summary(
    comparison, tmp_path, monkeypatch
):
    import google_pose_baseline

    path = request(comparison, tmp_path)
    metadata_path = tmp_path / "query/metadata.json"
    original = google_pose_baseline.classify

    def classify_and_change_metadata(*args, **kwargs):
        votes = original(*args, **kwargs)
        metadata = json.loads(metadata_path.read_text())
        metadata["source"]["group"] = "silently-relabelled"
        metadata_path.write_text(json.dumps(metadata))
        return votes

    monkeypatch.setattr(google_pose_baseline, "classify", classify_and_change_metadata)
    with pytest.raises(ValueError, match="changed"):
        comparison.run_comparison(path, tmp_path / "result")
    assert not (tmp_path / "result/summary.json").exists()


def test_degenerate_bank_is_not_disguised_as_query_tracking_failure(comparison, tmp_path):
    path = request(comparison, tmp_path)
    directory = tmp_path / "bank"
    poses = directory / "poses.jsonl"
    poses.write_text(json.dumps(pose()) + "\n")
    metadata_path = directory / "metadata.json"
    metadata = json.loads(metadata_path.read_text())
    metadata["poses_sha256"] = comparison.sha256(poses)
    metadata_path.write_text(json.dumps(metadata))
    with pytest.raises(ValueError):
        comparison.run_comparison(path, tmp_path / "result")
    assert not (tmp_path / "result/summary.json").exists()
