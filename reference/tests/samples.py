"""Minimal synthetic observation documents used by the contract tests.

Every value in this module is deliberately generated. No participant, biometric, media,
or study observation appears here, so these samples are safe to commit and to reuse as
the starting point for the synthetic repository fixtures.

Each helper returns a fresh mutable document so a test can delete or corrupt exactly one
field without affecting any other test.
"""

from __future__ import annotations

from typing import Any

from observation_contracts.schema_loader import ArtifactKind

SESSION_ID = "3f1c2a90-7b45-4c8e-9d21-6a0e5b4c3d27"
ANNOTATION_ID_A = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
ANNOTATION_ID_B = "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e"
ANNOTATION_ID_C = "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f"
FIXTURE_ID = "4d5e6f7a-8b9c-4d0e-9f1a-2b3c4d5e6f7a"
EVENT_ID = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b"

TOPOLOGY = "mediapipe_pose_33_v1"
LANDMARK_COUNT = 33

_SYNTHETIC_SHA256 = "0" * 63 + "1"
_VIDEO_SHA256 = "0" * 63 + "2"
_MODEL_SHA256 = "0" * 63 + "3"
_FRAME_SHA256 = "0" * 63 + "4"
_HARNESS_GIT_SHA = "0" * 39 + "5"


def _normalized_landmark(index: int) -> dict[str, Any]:
    """Return one synthetic normalized-image landmark with backend confidences present."""
    return {
        "index": index,
        "x": round(0.001 * index, 3),
        "y": round(0.002 * index, 3),
        "z": round(0.003 * index, 3),
        "visibility": 0.9,
        "presence": 0.8,
    }


def _world_landmark(index: int) -> dict[str, Any]:
    """Return one synthetic world landmark, deliberately not equal to its normalized peer."""
    return {
        "index": index,
        "x": round(0.011 * index, 3),
        "y": round(0.012 * index, 3),
        "z": round(0.013 * index, 3),
        "visibility": None,
        "presence": None,
    }


def minimal_manifest(
    *,
    session_id: str = SESSION_ID,
    schema_version: str = "1.0.0",
    camera_frames_received: int = 1,
    inference_frames_submitted: int = 1,
    inference_records_emitted: int = 1,
    pose_detected_records: int = 1,
    frames_skipped_before_inference: int = 0,
    trailing_frames_skipped_after_last_emitted_record: int = 0,
    inference_failures: int = 0,
) -> dict[str, Any]:
    """Return a minimal `SessionManifest v1` document whose counts close by default."""
    return {
        "session_manifest_schema_version": schema_version,
        "session_id": session_id,
        "participant_pseudonym_id": "participant-synthetic-01",
        "capture_protocol_version": "1.0.0",
        "harness_git_sha": _HARNESS_GIT_SHA,
        "device": {
            "platform": "android",
            "os_version": "14",
            "device_model": "synthetic-device",
            "thermal_api_available": True,
        },
        "pose_pipeline": {
            "backend_name": "mediapipe_tasks_vision",
            "backend_version": "0.10.14",
            "model_asset_name": "pose_landmarker_full.task",
            "model_asset_sha256": _MODEL_SHA256,
            "delegate": "cpu",
            "running_mode": "live_stream",
            "num_poses": 1,
            "min_pose_detection_confidence": 0.5,
            "min_pose_presence_confidence": 0.5,
            "min_tracking_confidence": 0.5,
            "landmark_topology": TOPOLOGY,
        },
        "camera": {
            "facing": "back",
            "capture_width": 1280,
            "capture_height": 720,
            "orientation_degrees": 0,
            "mirrored": False,
            "requested_camera_fps": 30,
        },
        "model_input": {
            "model_input_width": 256,
            "model_input_height": 256,
            "live_preprocessing_contract_version": "1.0.0",
        },
        "inference_drain_timeout_ms": 2000,
        "cadence": {
            "configured_inference_fps": 30,
            "achieved_inference_fps_timeseries": [
                {"t_monotonic_ns": 0, "achieved_inference_fps": 29.5},
            ],
        },
        "clock": {
            "monotonic_time_base": "android_elapsed_realtime_nanos",
            "t0_monotonic_ns": 100,
            "started_at_utc": "2026-08-12T00:00:00Z",
            "ended_at_utc": "2026-08-12T00:00:10Z",
        },
        "available_observations": {
            "normalized_image_landmarks_available": True,
            "world_landmarks_available": True,
        },
        "media_references": [
            {
                "role": "static_reference_clip",
                "path_ref": "media/static_reference.rgb24",
                "sha256": _SYNTHETIC_SHA256,
            },
        ],
        "counts": {
            "camera_frames_received": camera_frames_received,
            "inference_frames_submitted": inference_frames_submitted,
            "inference_records_emitted": inference_records_emitted,
            "pose_detected_records": pose_detected_records,
            "frames_skipped_before_inference": frames_skipped_before_inference,
            "frames_skipped_before_inference_by_reason": {
                "cadence_selection": frames_skipped_before_inference,
                "backpressure": 0,
                "session_stopping": 0,
            },
            "trailing_frames_skipped_after_last_emitted_record": (
                trailing_frames_skipped_after_last_emitted_record
            ),
            "inference_failures": inference_failures,
        },
        "machine_inference_failures": [],
        "capture_quality_observed": {
            "full_body_visible_at_hang": True,
            "required_joints_visible": True,
            "subject_scale_acceptable": True,
            "lighting_class": "indoor_bright",
            "visible_occlusion": False,
            "setup_failure_log": "",
        },
        "study_context": {
            "angle_deg_nominal": 45,
            "distance_m_nominal": 3,
            "camera_height_class": "chest",
            "environment": "indoor",
            "lighting_class": "indoor_bright",
            "clothing_class": "normal",
        },
    }


def minimal_pose_frame(
    *,
    index: int = 0,
    capture_ns: int = 100,
    skipped: int = 0,
    pose_present: bool = True,
    session_id: str = SESSION_ID,
    schema_version: str = "1.0.0",
) -> dict[str, Any]:
    """Return a minimal `RawPoseFrame v1` record for one completed inference call."""
    normalized: list[dict[str, Any]] | None = None
    world: list[dict[str, Any]] | None = None
    if pose_present:
        normalized = [_normalized_landmark(i) for i in range(LANDMARK_COUNT)]
        world = [_world_landmark(i) for i in range(LANDMARK_COUNT)]
    return {
        "raw_pose_frame_schema_version": schema_version,
        "session_id": session_id,
        "frame_index": index,
        "capture_timestamp_ns": capture_ns,
        "inference_start_ns": capture_ns + 1,
        "inference_end_ns": capture_ns + 2,
        "pose_present": pose_present,
        "normalized_image_landmarks": normalized,
        "world_landmarks": world,
        "image_width": 1280,
        "image_height": 720,
        "frames_skipped_since_last_emitted_record": skipped,
    }


def minimal_annotation(
    *,
    annotation_id: str = ANNOTATION_ID_A,
    stage: str = "independent",
    input_annotation_ids: list[str] | None = None,
    session_id: str = SESSION_ID,
    schema_version: str = "1.0.0",
) -> dict[str, Any]:
    """Return a minimal `Annotation v1` artifact carrying one labelled rep event."""
    return {
        "annotation_schema_version": schema_version,
        "annotation_id": annotation_id,
        "session_id": session_id,
        "annotation_protocol_version": "1.0.0",
        "annotator_pseudonym": "annotator-synthetic-01",
        "label_tier": "gold",
        "annotation_stage": stage,
        "input_annotation_ids": list(input_annotation_ids or []),
        "events": [
            {
                "event_id": EVENT_ID,
                "type": "rep_label",
                "timestamp_ns": 100,
                "rep_index": 0,
                "label": "valid",
                "invalid_reasons": [],
                "annotator_confidence": 0.9,
                "source": "human",
            },
        ],
        "set_level": {"athlete_reported_reps": 1},
        "media_reference": {"video_sha256": _VIDEO_SHA256},
        "created_at": "2026-08-12T00:01:00Z",
    }


def minimal_fixture_metadata(
    *,
    fixture_id: str = FIXTURE_ID,
    schema_version: str = "1.0.0",
) -> dict[str, Any]:
    """Return a minimal `FixtureMetadata v1` document for one decoded RGB24 frame."""
    return {
        "fixture_metadata_schema_version": schema_version,
        "fixture_id": fixture_id,
        "synthetic": True,
        "preprocessing_contract_version": "1.0.0",
        "frame_width": 4,
        "frame_height": 2,
        "pixel_format": "rgb24",
        "color_space": "srgb",
        "color_range": "full",
        "channel_order": "rgb",
        "orientation": "upright",
        "frames": [
            {
                "frame_index": 0,
                "timestamp_ns": 0,
                "path": "frame_000000.rgb24",
                "byte_length": 24,
                "sha256": _FRAME_SHA256,
            },
        ],
        "generation": {
            "generator_name": "synthetic_fixture_generator",
            "generator_version": "1.0.0",
            "generated_at_utc": "2026-08-12T00:00:00Z",
        },
    }


def minimal_documents() -> dict[ArtifactKind, dict[str, Any]]:
    """Return one minimal valid document for every stable artifact kind."""
    return {
        "session_manifest": minimal_manifest(),
        "raw_pose_frame": minimal_pose_frame(),
        "annotation": minimal_annotation(),
        "fixture_metadata": minimal_fixture_metadata(),
    }
