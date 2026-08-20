"""Executable checks for the committed synthetic observation fixtures."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from observation_contracts.detection_evidence import verify_committed_evidence
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


def _committed_fixture_metadata_dirs() -> list[Path]:
    """Every committed positive fixture directory that declares fixture metadata.

    Enumerating rather than naming fixtures is deliberate. The per-fixture tests above cover
    `session` and `fixture_replay` by name, so a newly added fixture was previously verified by
    nothing and `make verify` stayed green while its declared hashes went unchecked.
    """
    valid_root = repository_root() / "fixtures/synthetic/valid"
    return sorted(path.parent for path in valid_root.glob("*/fixture_metadata.json"))


def test_every_committed_fixture_directory_is_enumerated() -> None:
    discovered = {path.name for path in _committed_fixture_metadata_dirs()}
    assert "session" in discovered
    assert "fixture_replay" in discovered
    assert "fixture_detection" in discovered


def test_every_committed_fixture_metadata_is_schema_valid_and_hash_exact() -> None:
    directories = _committed_fixture_metadata_dirs()
    assert directories, "no committed fixture metadata was discovered"

    for directory in directories:
        metadata_path = directory / "fixture_metadata.json"
        metadata = _json(metadata_path)

        issues = validate_document(metadata, "fixture_metadata", str(metadata_path))
        issues.extend(verify_fixture(metadata_path))
        assert issues == [], f"{directory.name}: {[issue.code for issue in issues]}"

        declared = metadata["frames"]
        assert declared, f"{directory.name}: fixture declares no frames"
        for frame in declared:
            plane = directory / frame["path"]
            assert plane.is_file(), f"{directory.name}: missing declared plane {frame['path']}"
            assert sha256_file(plane) == frame["sha256"], (
                f"{directory.name}: {frame['path']} sha256 does not match the declared value"
            )
            assert plane.stat().st_size == frame["byte_length"], (
                f"{directory.name}: {frame['path']} byte_length does not match the declared value"
            )


def test_no_undeclared_plane_sits_inside_a_committed_fixture() -> None:
    """A plane is declared either by fixture metadata frames or by a manifest media reference.

    The session fixture declares `media/static_reference.rgb24` through its manifest rather than
    through fixture metadata, so both declaration sources count.
    """
    for directory in _committed_fixture_metadata_dirs():
        metadata = _json(directory / "fixture_metadata.json")
        declared = {frame["path"] for frame in metadata["frames"]}
        manifest_path = directory / "manifest.json"
        if manifest_path.is_file():
            manifest = _json(manifest_path)
            declared.update(
                reference["path_ref"] for reference in manifest.get("media_references", [])
            )
        on_disk = {
            str(path.relative_to(directory))
            for path in directory.rglob("*.rgb24")
            if path.is_file()
        }
        assert on_disk <= declared, (
            f"{directory.name}: undeclared planes present: {sorted(on_disk - declared)}"
        )


def _detection_fixture_metadata() -> dict[str, Any]:
    return _json(
        repository_root() / "fixtures/synthetic/valid/fixture_detection/fixture_metadata.json"
    )


def test_detection_fixture_records_its_admission_evidence() -> None:
    """Checks 5 and 6 may only treat the detection fixture as exercising the pose detector once a
    run against the pinned model records that it actually fires."""
    metadata = _detection_fixture_metadata()
    evidence = metadata["detection_evidence"]
    assert evidence["status"] in {"pending", "confirmed"}
    if evidence["status"] == "pending":
        assert evidence["admissible_for_topology_checks"] is False
        assert evidence["missing_precondition"]
    assert "not a photograph" in metadata["figure_provenance"]
    assert metadata["generator"] == "tools/generate_detection_fixture.py"


def test_a_confirmed_detection_fixture_carries_the_run_that_confirmed_it() -> None:
    """`confirmed` is a claim about a real run, so the run has to be recorded.

    Design section 7 admits the fixture only when the pinned model, Tasks Vision version, and CPU
    delegate produce a pose on every frame. Flipping the status without the observation behind it
    would make admission a declaration rather than evidence.
    """
    metadata = _detection_fixture_metadata()
    evidence = metadata["detection_evidence"]
    if evidence["status"] != "confirmed":
        return

    declared_indexes = [frame["frame_index"] for frame in metadata["frames"]]
    assert evidence["admissible_for_topology_checks"] is True
    assert evidence["confirmed_by"]
    observations = evidence["observations"]
    assert observations, "a confirmed status needs at least one recorded observation"

    for observation in observations:
        assert observation["device_class"] in {"emulator", "physical_device"}
        assert observation["device_identity"]
        assert observation["device_fingerprint"]
        assert isinstance(observation["api_level"], int)
        assert observation["api_level"] >= 1
        assert observation["delegate"] == "CPU"
        assert observation["running_mode"] == "VIDEO"
        assert observation["tasks_vision_version"]
        assert observation["model_sha256"]
        assert observation["runs_per_invocation"] >= 2, (
            "cross-run topology equality needs at least two runs"
        )
        assert observation["instrumentation_invocations"] >= 1
        assert (
            observation["total_fresh_pose_landmarker_runs"]
            == observation["runs_per_invocation"] * observation["instrumentation_invocations"]
        )

        # Detection actually fired, on every declared frame, with a pose behind every flag.
        assert observation["pose_present_frames"] == declared_indexes
        assert observation["pose_count_per_frame"] == [1] * len(declared_indexes)
        assert all(count >= 1 for count in observation["normalized_landmark_count_per_frame"])
        assert all(count >= 1 for count in observation["world_landmark_count_per_frame"])
        assert observation["presence_pattern_fully_populated"] is True

        # Not vacuous: a full-cardinality all-zero output, or one frozen result repeated on every
        # frame, would satisfy every cardinality claim above while observing nothing.
        digests = observation["landmark_observation_sha256"]
        assert len(digests) == len(declared_indexes)
        assert len(set(digests)) == len(digests)


def test_emulator_detection_evidence_is_never_recorded_as_the_s25_device_gate() -> None:
    """The emulator is never an S25 substitute (design section 7 / plan global constraints).

    The two are different observations, so an emulator entry must say so in the artifact rather
    than relying on a reader to remember which device produced it.
    """
    evidence = _detection_fixture_metadata()["detection_evidence"]
    for observation in evidence.get("observations", []):
        if observation["device_class"] == "emulator":
            assert observation["satisfies_s25_device_gate"] is False
            assert "S25" in observation["note"]


def test_a_confirmed_detection_fixture_is_bound_to_the_pinned_inputs_and_its_native_run() -> None:
    """The `confirmed` status must be bound, not merely well-formed.

    The two guards above check that the recorded observation is internally coherent. They cannot
    tell whether it describes *this* repository: a confirmed entry survives them with a different
    model SHA-256, a different Tasks Vision pin, a different render, and no run artifact at all.
    The binding lives in `observation_contracts.detection_evidence` and is exercised mutation by
    mutation in `reference/tests/test_detection_replay_evidence.py`; here it runs against the
    committed state.
    """
    verified = verify_committed_evidence(repository_root())
    evidence = _detection_fixture_metadata()["detection_evidence"]
    if evidence["status"] != "confirmed":
        assert verified == []
        return
    assert len(verified) == len(evidence["observations"]), (
        "every confirmed observation must be bound to a committed native-run artifact"
    )
