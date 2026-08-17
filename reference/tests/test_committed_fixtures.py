"""Executable checks for the committed synthetic observation fixtures."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from observation_contracts.fixture_integrity import (
    discover_negative_fixtures,
    run_declared_validation,
    sha256_file,
    verify_fixture,
)
from observation_contracts.semantic import (
    validate_annotations,
    validate_frame_stream,
    validate_session_manifest_semantics,
)
from observation_contracts.structural import validate_document, validate_ndjson


def repository_root() -> Path:
    """Return the repository root from this test module's location."""
    return Path(__file__).parents[2]


def _json(path: Path) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    assert isinstance(document, dict)
    return document


def test_every_negative_fixture_fails_with_declared_code() -> None:
    cases = discover_negative_fixtures(repository_root())

    assert {case.name for case in cases} == {
        "backend_timestamp_regression",
        "timestamp_regression",
        "session_switch",
        "landmark_count",
        "fixture_checksum",
        "replay_media_role",
    }
    for case in cases:
        result = run_declared_validation(case)
        assert result.codes == [case.expected_error_code]


def test_declared_negative_artifact_paths_match_reported_issue_artifacts() -> None:
    root = repository_root()
    for case in discover_negative_fixtures(root):
        result = run_declared_validation(case)
        declared_artifact = (root / Path(*case.artifact_path.split("/"))).resolve()
        reported_artifacts = {Path(issue.artifact_path).resolve() for issue in result.issues}

        assert reported_artifacts == {declared_artifact}, case.name


def test_committed_positive_session_is_fully_valid() -> None:
    session = repository_root() / "fixtures/synthetic/valid/session"
    manifest_path = session / "manifest.json"
    frames_path = session / "frames.ndjson"
    manifest = _json(manifest_path)

    issues = validate_document(manifest, "session_manifest", str(manifest_path))
    frames, frame_schema_issues = validate_ndjson(frames_path)
    issues.extend(frame_schema_issues)
    issues.extend(validate_frame_stream(manifest, frames, str(manifest_path), str(frames_path)))
    issues.extend(validate_session_manifest_semantics(manifest, frames, str(manifest_path)))

    annotation_paths = [
        session / "annotation_independent_a.json",
        session / "annotation_independent_b.json",
        session / "annotation_adjudicated.json",
    ]
    annotations = []
    for path in annotation_paths:
        annotation = _json(path)
        annotations.append(annotation)
        issues.extend(validate_document(annotation, "annotation", str(path)))
    issues.extend(
        validate_annotations(
            manifest,
            annotations,
            [str(path) for path in annotation_paths],
        )
    )

    fixture_metadata_path = session / "fixture_metadata.json"
    fixture_metadata = _json(fixture_metadata_path)
    issues.extend(
        validate_document(fixture_metadata, "fixture_metadata", str(fixture_metadata_path))
    )
    issues.extend(verify_fixture(fixture_metadata_path))

    media_reference = manifest["media_references"][0]
    static_reference = session / media_reference["path_ref"]
    assert sha256_file(static_reference) == media_reference["sha256"]
    assert fixture_metadata["synthetic"] is True
    assert [frame["pose_present"] for frame in frames] == [False, True]
    assert frames[1]["normalized_image_landmarks"] != frames[1]["world_landmarks"]
    assert issues == []


def test_committed_fixture_replay_group_is_fully_valid() -> None:
    replay = repository_root() / "fixtures/synthetic/valid/fixture_replay"
    manifest_path = replay / "manifest.json"
    frames_path = replay / "frames.ndjson"
    fixture_metadata_path = replay / "fixture_metadata.json"
    manifest = _json(manifest_path)
    fixture_metadata = _json(fixture_metadata_path)

    issues = validate_document(manifest, "session_manifest", str(manifest_path))
    frames, frame_schema_issues = validate_ndjson(frames_path)
    issues.extend(frame_schema_issues)
    issues.extend(validate_frame_stream(manifest, frames, str(manifest_path), str(frames_path)))
    issues.extend(validate_session_manifest_semantics(manifest, frames, str(manifest_path)))
    issues.extend(
        validate_document(fixture_metadata, "fixture_metadata", str(fixture_metadata_path))
    )
    issues.extend(verify_fixture(fixture_metadata_path))

    media_reference = manifest["media_references"][0]
    media_path = replay / media_reference["path_ref"]
    fixture_frames = fixture_metadata["frames"]
    for fixture_frame in fixture_frames:
        fixture_media_path = replay / fixture_frame["path"]
        assert sha256_file(fixture_media_path) == fixture_frame["sha256"]
        assert fixture_media_path.stat().st_size == fixture_frame["byte_length"]
    assert media_reference["path_ref"] == fixture_frames[0]["path"]
    assert sha256_file(media_path) == media_reference["sha256"] == fixture_frames[0]["sha256"]
    assert manifest["session_manifest_schema_version"] == "1.1.0"
    assert manifest["participant_pseudonym_id"] == "synthetic-fixture-replay-01"
    assert manifest["session_kind"] == "fixture_replay"
    assert manifest["camera"]["facing"] == "fixture"
    assert [reference["role"] for reference in manifest["media_references"]] == [
        "conformance_fixture"
    ]
    assert manifest["capture_quality_observed"]["lighting_class"].startswith("synthetic_")
    assert manifest["study_context"]["environment"].startswith("synthetic_")
    assert all(frame["raw_pose_frame_schema_version"] == "1.1.0" for frame in frames)
    assert all(
        "backend_timestamp_ms" not in frame or frame["backend_timestamp_ms"] >= 0
        for frame in frames
    )
    assert [frame["backend_timestamp_ms"] for frame in frames] == [100, 133, 134]
    assert (
        frames[1]["capture_timestamp_ns"] // 1_000_000
        == frames[2]["capture_timestamp_ns"] // 1_000_000
    )
    assert fixture_metadata["synthetic"] is True
    assert issues == []


def test_committed_fixture_replay_sequence_matches_raw_stream_and_accounting() -> None:
    replay = repository_root() / "fixtures/synthetic/valid/fixture_replay"
    manifest = _json(replay / "manifest.json")
    fixture_metadata = _json(replay / "fixture_metadata.json")
    frames, frame_issues = validate_ndjson(replay / "frames.ndjson")

    fixture_frames = fixture_metadata["frames"]
    assert frame_issues == []
    assert len(fixture_frames) == len(frames) == 3
    assert [descriptor["frame_index"] for descriptor in fixture_frames] == [
        frame["frame_index"] for frame in frames
    ]
    assert [descriptor["timestamp_ns"] for descriptor in fixture_frames] == [
        frame["capture_timestamp_ns"] - manifest["clock"]["t0_monotonic_ns"] for frame in frames
    ]
    counts = manifest["counts"]
    assert counts["camera_frames_received"] == len(fixture_frames)
    assert counts["inference_frames_submitted"] == len(frames)
    assert counts["inference_records_emitted"] == len(frames)
    assert counts["pose_detected_records"] == sum(frame["pose_present"] for frame in frames)


def test_every_committed_fixture_metadata_declares_synthetic_provenance() -> None:
    metadata_paths = sorted(repository_root().glob("fixtures/synthetic/**/fixture_metadata.json"))

    assert metadata_paths
    assert all(_json(path)["synthetic"] is True for path in metadata_paths)
