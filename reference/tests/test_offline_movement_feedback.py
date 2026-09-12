"""Offline feedback must expose event errors hidden by equal aggregate counts."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "offline_movement_feedback", Path(__file__).parents[2] / "tools/offline_movement_feedback.py"
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
        "events": [{"start_ns": 1000, "end_ns": 1100}, {"start_ns": 3000, "end_ns": 3100}],
    }


def test_equal_counts_do_not_hide_a_miss_and_extra() -> None:
    result = feedback.compare_events([1050, 2000], labels(), "a" * 64)
    assert (result["matched"], result["missed"], result["extra"]) == (1, 1, 1)
    assert result["missed_event_indices"] == [1]
    assert result["extra_timestamps_ns"] == [2000]


def test_two_predictions_cannot_match_one_annotation() -> None:
    result = feedback.compare_events([1010, 1090, 3050], labels(), "a" * 64)
    assert (result["matched"], result["missed"], result["extra"]) == (2, 0, 1)


def test_missing_labels_are_unmeasured_not_a_perfect_score() -> None:
    result = feedback.compare_events([], None, "a" * 64)
    assert result["status"] == "UNLABELLED"
    assert "recall" not in result


def test_calibration_cannot_be_scored_as_evaluation_or_silently_drop_labels() -> None:
    with pytest.raises(ValueError, match="practice"):
        feedback.compare_events([1050, 3050], labels(), "a" * 64, evaluation_after_ns=2000)
    with pytest.raises(ValueError, match="practice"):
        feedback.compare_events([1050], None, "a" * 64, evaluation_after_ns=2000)
    with pytest.raises(ValueError, match="practice"):
        feedback.compare_events([3050], labels(), "a" * 64, evaluation_after_ns=2000)


def test_evaluation_labels_exclude_practice_without_changing_the_frozen_original() -> None:
    original = labels()
    evaluation = {**original, "events": original["events"][1:]}
    result = feedback.compare_events([3050], evaluation, "a" * 64, evaluation_after_ns=2000)
    assert (result["annotated"], result["matched"], result["missed"]) == (1, 1, 0)
    assert len(original["events"]) == 2


@pytest.mark.parametrize("change", ["hash", "status", "overlap", "negative", "float", "bool"])
def test_invalid_annotations_cannot_be_admitted(change: str) -> None:
    record = labels()
    if change == "hash":
        record["video_sha256"] = "b" * 64
    elif change == "status":
        record["annotation_status"] = "model_generated"
    elif change == "overlap":
        record["events"][1] = {"start_ns": 1050, "end_ns": 3100}
    elif change == "negative":
        record["events"][0]["start_ns"] = -1
    elif change == "float":
        record["events"][0]["start_ns"] = 1.5
    else:
        record["events"][0]["start_ns"] = True
    with pytest.raises(ValueError):
        feedback.compare_events([1050], record, "a" * 64)


@pytest.mark.parametrize("predictions", [[2, 1], [1, 1], [-1], [True], [1.5]])
def test_prediction_timestamps_must_be_real_ordered_integer_times(predictions: list) -> None:
    with pytest.raises(ValueError):
        feedback.compare_events(predictions, labels(), "a" * 64)


def test_split_isolation_uses_groups_and_hashes_not_just_clip_names() -> None:
    corpus = [
        {"id": "one", "group": "shoot-one", "split": "development", "video_sha256": "a" * 64},
        {"id": "two", "group": "shoot-one", "split": "held_out", "video_sha256": "b" * 64},
    ]
    with pytest.raises(ValueError, match="group"):
        feedback.validate_splits(corpus)
    corpus[1]["group"] = "shoot-two"
    corpus[1]["video_sha256"] = "a" * 64
    with pytest.raises(ValueError, match="hash"):
        feedback.validate_splits(corpus)
    corpus[1]["video_sha256"] = "b" * 64
    feedback.validate_splits(corpus)


def test_replay_admission_checks_whole_manifest_and_current_source() -> None:
    source = {"id": "one", "group": "person-one", "split": "development", "video_sha256": "a" * 64}
    feedback.verify_corpus(source, None)
    held = {**source, "id": "two", "split": "held_out", "video_sha256": "b" * 64}
    with pytest.raises(ValueError, match="manifest"):
        feedback.verify_corpus(held, None)
    with pytest.raises(ValueError, match="group"):
        feedback.verify_corpus(source, [source, held])
    with pytest.raises(ValueError, match="source"):
        feedback.verify_corpus(source, [{**source, "group": "different"}])
    held["group"] = "person-two"
    feedback.verify_corpus(held, [source, held])


def test_prediction_rows_require_consistent_counts_and_frame_binding() -> None:
    poses = [{"timestamp_ns": 1}, {"timestamp_ns": 2}]
    rows = [
        {"timestamp_ns": 1, "count": 0, "counted": False},
        {"timestamp_ns": 2, "count": 1, "counted": True},
    ]
    assert feedback.prediction_events(rows, poses) == [2]
    rows[1]["counted"] = False
    with pytest.raises(ValueError, match="count"):
        feedback.prediction_events(rows, poses)
    rows[1]["counted"] = True
    rows[1]["timestamp_ns"] = 3
    with pytest.raises(ValueError, match="timestamp"):
        feedback.prediction_events(rows, poses)


def test_corpus_outputs_cannot_land_inside_a_git_worktree(tmp_path: Path) -> None:
    (tmp_path / ".git").write_text("gitdir: elsewhere")
    with pytest.raises(ValueError, match="Git"):
        feedback.private_output(tmp_path / "media")


def test_cache_binding_catches_mutated_pose_or_model(tmp_path: Path) -> None:
    video, model, pose = (tmp_path / name for name in ("video", "model", "pose"))
    for file in (video, model, pose):
        file.write_bytes(b"original")
    metadata = {
        "coordinate_space": "upright_source_normalized",
        "video_sha256": feedback.sha256(video),
        "model_sha256": feedback.sha256(model),
        "poses_sha256": feedback.sha256(pose),
    }
    feedback.verify_cache(metadata, video, model, pose)
    pose.write_bytes(b"modified")
    with pytest.raises(ValueError, match="poses"):
        feedback.verify_cache(metadata, video, model, pose)


def test_pts_conversion_preserves_irregular_cadence_and_nonzero_origin() -> None:
    from fractions import Fraction

    assert [feedback.pts_ns(pts, Fraction(1, 90000)) for pts in (90000, 93000, 99000)] == [
        1000000000,
        1033333333,
        1100000000,
    ]
    with pytest.raises(ValueError):
        feedback.pts_ns(None, Fraction(1, 90000))


def test_cache_writer_rejects_duplicate_inference_times_and_leaves_no_admission(
    tmp_path: Path,
) -> None:
    frames = [
        {"timestamp_ns": 1000000, "width": 100, "height": 100, "landmarks": []},
        {"timestamp_ns": 1000001, "width": 100, "height": 100, "landmarks": []},
    ]
    with pytest.raises(ValueError, match="millisecond"):
        feedback.write_pose_cache(tmp_path / "cache", frames, {"video_sha256": "a" * 64})
    assert not (tmp_path / "cache/metadata.json").exists()


def test_cache_writer_preserves_missing_pose_and_binds_complete_rows(tmp_path: Path) -> None:
    frames = [
        {"timestamp_ns": 1000000, "width": 100, "height": 50, "landmarks": []},
        {"timestamp_ns": 3000000, "width": 100, "height": 50, "landmarks": []},
    ]
    cache = tmp_path / "cache"
    feedback.write_pose_cache(cache, frames, {"video_sha256": "a" * 64})
    metadata = feedback.load_json(cache / "metadata.json")
    assert metadata["frame_count"] == 2
    assert metadata["poses_sha256"] == feedback.sha256(cache / "poses.jsonl")
    assert feedback.load_rows(cache / "poses.jsonl") == frames
    with pytest.raises(FileExistsError):
        feedback.write_pose_cache(cache, frames, {})


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
    rows = [{"timestamp_ns": 1000, "count": 0, "counted": False, "reason": "</script>"}]
    target = tmp_path / "report.html"
    feedback.write_overlay(target, tmp_path / "clip.mp4", poses, rows, {"status": "UNLABELLED"})
    html = target.read_text()
    assert '"reason": "</script>"' not in html
    assert '"reason": "<\\/script>"' in html
    rows[0]["timestamp_ns"] = 2000
    with pytest.raises(ValueError, match="timestamp"):
        feedback.write_overlay(tmp_path / "invalid.html", tmp_path / "clip.mp4", poses, rows, {})


def test_boundary_miss_reports_distance_without_widening_annotations() -> None:
    result = feedback.compare_events([999, 3050], labels(), "a" * 64)
    assert result["missed"] == 1
    assert result["extra"] == 1
    assert result["miss_diagnostics"] == [
        {"event_index": 0, "nearest_prediction_ns": 999, "distance_to_window_ns": 1}
    ]


def landmark(
    index: int,
    x: float = 0.5,
    y: float = 0.5,
    z: float = 0.0,
    visibility: float = 0.95,
    presence: float = 0.95,
) -> dict:
    return {"index": index, "x": x, "y": y, "z": z, "visibility": visibility, "presence": presence}


JOINTS = (24, 26, 28)


def test_diagnoses_nonfinite_coordinate_distinctly_from_out_of_view() -> None:
    landmarks = [landmark(24), landmark(26), landmark(28)]
    landmarks[2]["y"] = float("nan")
    nonfinite = feedback.diagnose_landmark_reason(landmarks, JOINTS, 0.5)
    assert nonfinite == {
        "reason": "INVALID_LANDMARK",
        "joint_index": 28,
        "cause": "nonfinite_y",
        "value": landmarks[2]["y"],
    }

    landmarks[2]["y"] = 1.0235
    out_of_view = feedback.diagnose_landmark_reason(landmarks, JOINTS, 0.5)
    assert out_of_view == {
        "reason": "INVALID_LANDMARK",
        "joint_index": 28,
        "cause": "y_out_of_view",
        "value": 1.0235,
    }


def test_diagnoses_missing_and_duplicate_landmark_indices() -> None:
    missing = feedback.diagnose_landmark_reason([landmark(24), landmark(26)], JOINTS, 0.5)
    assert missing == {"reason": "MISSING_LANDMARK", "joint_index": 28, "cause": "missing"}

    duplicate = feedback.diagnose_landmark_reason(
        [landmark(24), landmark(26), landmark(28), landmark(28)], JOINTS, 0.5
    )
    assert duplicate == {
        "reason": "DUPLICATE_LANDMARK_INDEX",
        "joint_index": 28,
        "cause": "duplicate_index",
    }


def test_diagnoses_low_confidence_after_coordinates_are_ruled_out() -> None:
    landmarks = [landmark(24), landmark(26), landmark(28, visibility=0.2)]
    result = feedback.diagnose_landmark_reason(landmarks, JOINTS, 0.5)
    assert result == {
        "reason": "LOW_CONFIDENCE",
        "joint_index": 28,
        "cause": "visibility",
        "visibility": 0.2,
        "presence": 0.95,
    }


def test_diagnoses_none_when_all_joints_are_usable() -> None:
    landmarks = [landmark(24), landmark(26), landmark(28)]
    assert feedback.diagnose_landmark_reason(landmarks, JOINTS, 0.5) == {"reason": "NONE"}


def test_diagnose_unusable_frames_scans_a_pose_cache_for_joint_failures() -> None:
    poses = [
        {"timestamp_ns": 0, "landmarks": [landmark(24), landmark(26), landmark(28)]},
        {"timestamp_ns": 1000, "landmarks": [landmark(24), landmark(26), landmark(28, y=1.05)]},
        {"timestamp_ns": 2000, "landmarks": []},
    ]
    diagnoses = feedback.diagnose_unusable_frames(poses, JOINTS, 0.5)
    assert diagnoses == [
        {
            "timestamp_ns": 1000,
            "reason": "INVALID_LANDMARK",
            "joint_index": 28,
            "cause": "y_out_of_view",
            "value": 1.05,
        },
        {"timestamp_ns": 2000, "reason": "NO_POSE"},
    ]
