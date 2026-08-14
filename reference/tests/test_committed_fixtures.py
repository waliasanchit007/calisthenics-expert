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
        "timestamp_regression",
        "session_switch",
        "landmark_count",
        "fixture_checksum",
    }
    for case in cases:
        result = run_declared_validation(case)
        assert result.codes == [case.expected_error_code]


def test_committed_positive_session_is_fully_valid() -> None:
    session = repository_root() / "fixtures/synthetic/valid/session"
    manifest_path = session / "manifest.json"
    frames_path = session / "frames.ndjson"
    manifest = _json(manifest_path)

    issues = validate_document(manifest, "session_manifest", str(manifest_path))
    frames, frame_schema_issues = validate_ndjson(frames_path)
    issues.extend(frame_schema_issues)
    issues.extend(
        validate_frame_stream(manifest, frames, str(manifest_path), str(frames_path))
    )
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


def test_every_committed_fixture_metadata_declares_synthetic_provenance() -> None:
    metadata_paths = sorted(
        repository_root().glob("fixtures/synthetic/**/fixture_metadata.json")
    )

    assert metadata_paths
    assert all(_json(path)["synthetic"] is True for path in metadata_paths)
