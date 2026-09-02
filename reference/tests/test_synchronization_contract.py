"""Regression tests for the M0.5B synchronized-session contract release."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import pytest

from observation_contracts.errors import ValidationIssue
from observation_contracts.semantic import validate_session_manifest_semantics
from observation_contracts.structural import validate_document
from tests.samples import minimal_manifest, minimal_pose_frame

NEW_CODES = {
    "SESSION.MEDIA_REQUIRED_MISSING",
    "SESSION.MEDIA_REQUIREMENT_CONTRADICTED",
    "SESSION.MEDIA_DIGEST_MISMATCH",
    "SESSION.SYNC_EVIDENCE_MISSING",
    "SESSION.SYNC_CLOCK_MAPPING",
    "SESSION.SYNC_ENDPOINT_MISMATCH",
    "SESSION.VIDEO_COUNT_MISMATCH",
}

BOUNDED_PROFILE_CODES = {
    "SESSION.SYNC_PROFILE_UNSUPPORTED",
    "SESSION.SYNC_TIMEBASE_INVALID",
}

SESSION_ID = "3f1c2a90-7b45-4c8e-9d21-6a0e5b4c3d27"
VIDEO_BYTES = b"synthetic mp4 payload"
ROOT = Path(__file__).resolve().parents[2]


def _synchronization_evidence(*, video_sha256: str | None = None) -> dict[str, Any]:
    return {
        "synchronization_evidence_schema_version": "1.0.0",
        "session_id": SESSION_ID,
        "manifest_session_id": SESSION_ID,
        "video_role": "session_video",
        "video_path_ref": "media/session.mp4",
        "video_sha256": video_sha256 or hashlib.sha256(VIDEO_BYTES).hexdigest(),
        "video_byte_length": len(VIDEO_BYTES),
        "raw_pose_stream_path_ref": "frames.ndjson",
        "raw_pose_stream_sha256": hashlib.sha256(b"pose-stream").hexdigest(),
        "container": "video/mp4",
        "codec": "c2.qti.avc.encoder",
        "camera_timestamp_domain": "android_elapsed_realtime_nanos_rebased",
        "encoder_timestamp_domain": "container_presentation_time_us",
        "mapping_method": "camera_capture_to_container_pts_constant_offset_v1",
        "container_offset_ns": 1_000_000_000,
        "container_drift_ns_per_s": 0,
        "video_pts_us": [0, 33_333, 66_666],
        "samples": [
            {
                "frame_index": 0,
                "rebased_capture_timestamp_ns": 1_000_000_000,
                "submitted_pts_us": 1_000_000,
                "demuxed_pts_us": 0,
            },
            {
                "frame_index": 1,
                "rebased_capture_timestamp_ns": 1_033_333_000,
                "submitted_pts_us": 1_033_333,
                "demuxed_pts_us": 33_333,
            },
            {
                "frame_index": 2,
                "rebased_capture_timestamp_ns": 1_066_666_000,
                "submitted_pts_us": 1_066_666,
                "demuxed_pts_us": 66_666,
            },
        ],
        "first_camera_frame_timestamp_ns": 1_000_000_000,
        "last_camera_frame_timestamp_ns": 1_066_666_000,
        "first_encoded_frame_pts_us": 0,
        "last_encoded_frame_pts_us": 66_666,
        "first_pose_capture_timestamp_ns": 1_000_000_000,
        "last_pose_capture_timestamp_ns": 1_066_666_000,
        "counts": {
            "delivered_camera_callbacks": 3,
            "encoded_video_frames": 3,
            "submitted_inference_frames": 3,
            "emitted_pose_records": 3,
            "dropped_before_encoder": 0,
            "encoder_failures": 0,
            "inference_skipped_frames": 0,
            "inference_failures": 0,
        },
        "producer_git_sha": "0" * 39 + "5",
        "device_model": "SM-S938B",
        "device_api_level": 36,
        "device_abi": "arm64-v8a",
    }


def _bounded_synchronization_evidence(*, video_sha256: str | None = None) -> dict[str, Any]:
    evidence = _synchronization_evidence(video_sha256=video_sha256)
    demuxed = [0, 33_332, 66_657]
    evidence.update(
        {
            "synchronization_evidence_schema_version": "1.1.0",
            "sync_profile": "bounded_quantization",
            "mapping_method": "camera_capture_to_container_pts_bounded_quantization_v1",
            "container_offset_ns": 1_000_001_000,
            "container_offset_interval_ns": {
                "minimum": 1_000_000_000,
                "maximum": 1_000_009_000,
            },
            "container_quantization_span_bound_ns": 24_223,
            "container_timebases": {
                "video_ticks_per_second": 90_000,
                "metadata_ticks_per_second": 90_000,
            },
            "video_pts_us": demuxed,
            "first_encoded_frame_pts_us": demuxed[0],
            "last_encoded_frame_pts_us": demuxed[-1],
        }
    )
    for sample, demuxed_pts_us in zip(evidence["samples"], demuxed, strict=True):
        sample["demuxed_pts_us"] = demuxed_pts_us
    return evidence


def synchronized_manifest(
    tmp_path: Path,
    *,
    evidence: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    video_path = tmp_path / "media" / "session.mp4"
    video_path.parent.mkdir(parents=True)
    video_path.write_bytes(VIDEO_BYTES)
    pose_path = tmp_path / "frames.ndjson"
    pose_path.write_bytes(b"pose-stream")
    evidence_path = tmp_path / "sync.json"
    evidence_document = evidence or _synchronization_evidence()
    evidence_path.write_text(json.dumps(evidence_document), encoding="utf-8")

    manifest = minimal_manifest(
        schema_version="1.2.0",
        camera_frames_received=3,
        inference_frames_submitted=3,
        inference_records_emitted=3,
        pose_detected_records=3,
    )
    manifest.update(
        {
            "session_kind": "live_conformance",
            "session_manifest_schema_version": "1.2.0",
            "media_references": [
                {
                    "role": "session_video",
                    "path_ref": "media/session.mp4",
                    "sha256": hashlib.sha256(VIDEO_BYTES).hexdigest(),
                }
            ],
            "raw_pose_stream": {
                "path_ref": "frames.ndjson",
                "sha256": hashlib.sha256(b"pose-stream").hexdigest(),
                "schema_version": evidence_document["synchronization_evidence_schema_version"],
            },
            "synchronization_evidence": {
                "path_ref": "sync.json",
                "sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
                "schema_version": "1.0.0",
            },
            "media_required": True,
        }
    )
    frames = [
        minimal_pose_frame(index=0, capture_ns=1_000_000_000),
        minimal_pose_frame(index=1, capture_ns=1_033_333_000),
        minimal_pose_frame(index=2, capture_ns=1_066_666_000),
    ]
    return manifest, frames


def _codes(issues: list[ValidationIssue]) -> list[str]:
    return [issue.code for issue in issues]


def test_valid_media_required_manifest_and_sync_evidence_are_admitted(tmp_path: Path) -> None:
    manifest, frames = synchronized_manifest(tmp_path)

    assert (
        _codes(
            validate_session_manifest_semantics(manifest, frames, str(tmp_path / "manifest.json"))
        )
        == []
    )


def test_each_new_stable_failure_is_exercised_by_one_focused_mutation(tmp_path: Path) -> None:
    mutations = {
        "SESSION.MEDIA_REQUIRED_MISSING": lambda manifest: manifest.update(
            {"media_references": [], "media_required": True}
        ),
        "SESSION.MEDIA_REQUIREMENT_CONTRADICTED": lambda manifest: manifest.update(
            {"media_required": False}
        ),
        "SESSION.MEDIA_DIGEST_MISMATCH": lambda manifest: manifest["media_references"][0].update(
            {"sha256": "f" * 64}
        ),
        "SESSION.SYNC_EVIDENCE_MISSING": lambda manifest: manifest.pop("synchronization_evidence"),
        "SESSION.SYNC_CLOCK_MAPPING": lambda manifest: None,
        "SESSION.SYNC_ENDPOINT_MISMATCH": lambda manifest: None,
        "SESSION.VIDEO_COUNT_MISMATCH": lambda manifest: None,
    }
    assert set(mutations) == NEW_CODES

    for expected, mutate in mutations.items():
        manifest, frames = synchronized_manifest(tmp_path / expected.replace(".", "_"))
        if expected in {
            "SESSION.SYNC_CLOCK_MAPPING",
            "SESSION.SYNC_ENDPOINT_MISMATCH",
            "SESSION.VIDEO_COUNT_MISMATCH",
        }:
            evidence_path = Path(manifest["synchronization_evidence"]["path_ref"])
            assert evidence_path.name == "sync.json"
            evidence = json.loads(
                (tmp_path / expected.replace(".", "_") / evidence_path).read_text()
            )
            if expected.endswith("CLOCK_MAPPING"):
                evidence["mapping_method"] = "identity"
            elif expected.endswith("ENDPOINT_MISMATCH"):
                evidence["last_encoded_frame_pts_us"] = 1
            else:
                evidence["counts"]["encoded_video_frames"] = 2
            evidence_file = tmp_path / expected.replace(".", "_") / "sync.json"
            evidence_file.write_text(json.dumps(evidence), encoding="utf-8")
            manifest["synchronization_evidence"]["sha256"] = hashlib.sha256(
                evidence_file.read_bytes()
            ).hexdigest()
        else:
            mutate(manifest)
        assert _codes(
            validate_session_manifest_semantics(
                manifest,
                frames,
                str(tmp_path / expected.replace(".", "_") / "manifest.json"),
            )
        ) == [expected]


def test_manifest_schema_admits_1_2_optional_contract_fields(tmp_path: Path) -> None:
    manifest, _frames = synchronized_manifest(tmp_path)

    assert validate_document(manifest, "session_manifest", str(tmp_path / "manifest.json")) == []


@pytest.mark.parametrize("flag", [None, False])
def test_video_reference_contradicts_absent_or_false_media_required(
    tmp_path: Path, flag: bool | None
) -> None:
    manifest, frames = synchronized_manifest(tmp_path)
    if flag is None:
        manifest.pop("media_required")
    else:
        manifest["media_required"] = flag

    assert _codes(
        validate_session_manifest_semantics(manifest, frames, str(tmp_path / "manifest.json"))
    ) == ["SESSION.MEDIA_REQUIREMENT_CONTRADICTED"]


@pytest.mark.parametrize(
    ("mutation", "expected"),
    [
        ("remove_video_reference", "SESSION.MEDIA_REQUIRED_MISSING"),
        ("remove_raw_pose_reference", "SESSION.MEDIA_REQUIRED_MISSING"),
        ("remove_sync_reference", "SESSION.SYNC_EVIDENCE_MISSING"),
        ("change_offset", "SESSION.SYNC_CLOCK_MAPPING"),
        ("swap_pts_endpoints", "SESSION.SYNC_CLOCK_MAPPING"),
    ],
)
def test_required_reference_and_offset_mutations_fail_closed(
    tmp_path: Path, mutation: str, expected: str
) -> None:
    manifest, frames = synchronized_manifest(tmp_path)
    if mutation == "remove_video_reference":
        manifest["media_references"] = []
    elif mutation == "remove_raw_pose_reference":
        manifest.pop("raw_pose_stream")
    elif mutation == "remove_sync_reference":
        manifest.pop("synchronization_evidence")
    else:
        evidence_path = tmp_path / "sync.json"
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        if mutation == "change_offset":
            evidence["container_offset_ns"] = 999_999_000
        else:
            evidence["samples"][0]["submitted_pts_us"], evidence["samples"][0]["demuxed_pts_us"] = (
                evidence["samples"][0]["demuxed_pts_us"],
                evidence["samples"][0]["submitted_pts_us"],
            )
        evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
        manifest["synchronization_evidence"]["sha256"] = hashlib.sha256(
            evidence_path.read_bytes()
        ).hexdigest()

    assert _codes(
        validate_session_manifest_semantics(manifest, frames, str(tmp_path / "manifest.json"))
    ) == [expected]


def test_synchronization_evidence_schema_is_structurally_admitted(tmp_path: Path) -> None:
    evidence = _synchronization_evidence()

    assert (
        validate_document(evidence, "synchronization_evidence", str(tmp_path / "sync.json")) == []
    )


def test_bounded_quantization_evidence_is_structurally_and_semantically_admitted(
    tmp_path: Path,
) -> None:
    evidence = _bounded_synchronization_evidence()
    manifest, frames = synchronized_manifest(tmp_path, evidence=evidence)

    assert (
        validate_document(
            evidence,
            "synchronization_evidence",
            str(tmp_path / "sync.json"),
        )
        == []
    )
    assert (
        _codes(
            validate_session_manifest_semantics(
                manifest,
                frames,
                str(tmp_path / "manifest.json"),
            )
        )
        == []
    )


def test_bounded_profile_accepts_a_derived_sub_tick_middle_sample_perturbation(
    tmp_path: Path,
) -> None:
    evidence = _bounded_synchronization_evidence()
    ticks_per_second = evidence["container_timebases"]["video_ticks_per_second"]
    sub_tick_us = (1_000_000 // ticks_per_second) - 1
    evidence["samples"][1]["demuxed_pts_us"] -= sub_tick_us
    evidence["video_pts_us"][1] -= sub_tick_us
    offsets_ns = sorted(
        (sample["submitted_pts_us"] - sample["demuxed_pts_us"]) * 1_000
        for sample in evidence["samples"]
    )
    evidence["container_offset_ns"] = offsets_ns[(len(offsets_ns) - 1) // 2]
    evidence["container_offset_interval_ns"] = {
        "minimum": offsets_ns[0],
        "maximum": offsets_ns[-1],
    }
    manifest, frames = synchronized_manifest(tmp_path, evidence=evidence)

    assert (
        _codes(
            validate_session_manifest_semantics(
                manifest,
                frames,
                str(tmp_path / "manifest.json"),
            )
        )
        == []
    )


def test_bounded_profile_blocks_the_first_integral_microsecond_span_above_the_derived_bound(
    tmp_path: Path,
) -> None:
    evidence = _bounded_synchronization_evidence()
    ticks_per_second = evidence["container_timebases"]["video_ticks_per_second"]
    derived_bound_ns = ((2_000_000_000 + ticks_per_second - 1) // ticks_per_second) + 2_000
    over_bound_us = (derived_bound_ns // 1_000) + 1
    evidence["samples"][1]["demuxed_pts_us"] -= over_bound_us
    evidence["video_pts_us"][1] -= over_bound_us
    offsets_ns = sorted(
        (sample["submitted_pts_us"] - sample["demuxed_pts_us"]) * 1_000
        for sample in evidence["samples"]
    )
    evidence["container_offset_ns"] = offsets_ns[(len(offsets_ns) - 1) // 2]
    evidence["container_offset_interval_ns"] = {
        "minimum": offsets_ns[0],
        "maximum": offsets_ns[-1],
    }
    manifest, frames = synchronized_manifest(tmp_path, evidence=evidence)

    assert _codes(
        validate_session_manifest_semantics(
            manifest,
            frames,
            str(tmp_path / "manifest.json"),
        )
    ) == ["SESSION.SYNC_CLOCK_MAPPING"]


@pytest.mark.parametrize(
    ("field", "replacement"),
    [
        ("container_offset_interval_ns", {"minimum": 0, "maximum": 9_000}),
        ("container_quantization_span_bound_ns", 24_222),
        ("container_offset_ns", 1_000_000_000),
    ],
)
def test_bounded_profile_rejects_claimed_values_not_derived_from_samples_and_timebase(
    tmp_path: Path,
    field: str,
    replacement: object,
) -> None:
    evidence = _bounded_synchronization_evidence()
    evidence[field] = replacement
    manifest, frames = synchronized_manifest(tmp_path, evidence=evidence)

    assert _codes(
        validate_session_manifest_semantics(
            manifest,
            frames,
            str(tmp_path / "manifest.json"),
        )
    ) == ["SESSION.SYNC_CLOCK_MAPPING"]


@pytest.mark.parametrize(
    "mutation",
    ["missing", "zero", "inconsistent"],
)
def test_bounded_profile_fails_closed_on_invalid_track_timebases(
    tmp_path: Path,
    mutation: str,
) -> None:
    evidence = _bounded_synchronization_evidence()
    if mutation == "missing":
        evidence.pop("container_timebases")
    elif mutation == "zero":
        evidence["container_timebases"]["video_ticks_per_second"] = 0
    else:
        evidence["container_timebases"]["metadata_ticks_per_second"] = 1_000
    manifest, frames = synchronized_manifest(tmp_path, evidence=evidence)

    assert _codes(
        validate_session_manifest_semantics(
            manifest,
            frames,
            str(tmp_path / "manifest.json"),
        )
    ) == ["SESSION.SYNC_TIMEBASE_INVALID"]


def test_unknown_synchronization_profile_fails_closed_with_its_stable_code(
    tmp_path: Path,
) -> None:
    evidence = _bounded_synchronization_evidence()
    evidence["sync_profile"] = "future_profile"
    manifest, frames = synchronized_manifest(tmp_path, evidence=evidence)

    assert _codes(
        validate_session_manifest_semantics(
            manifest,
            frames,
            str(tmp_path / "manifest.json"),
        )
    ) == ["SESSION.SYNC_PROFILE_UNSUPPORTED"]


def test_bounded_profile_codes_are_additive_registry_entries() -> None:
    registry = json.loads(
        (ROOT / "contracts/tooling/validator_error_codes.v1.json").read_text(encoding="utf-8")
    )

    assert registry["registry_version"] == "1.5.0"
    assert BOUNDED_PROFILE_CODES <= set(registry["codes"])
