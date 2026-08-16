import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

from observation_contracts.schema_loader import ARTIFACT_KINDS, load_schema
from observation_contracts.structural import json_pointer, validate_document, validate_ndjson
from tests.samples import (
    ANNOTATION_ID_A,
    ANNOTATION_ID_B,
    minimal_annotation,
    minimal_documents,
    minimal_fixture_metadata,
    minimal_manifest,
    minimal_pose_frame,
)


def test_minimal_documents_match_v1_schemas() -> None:
    for kind, document in minimal_documents().items():
        assert validate_document(document, kind, f"{kind}.json") == []


def test_unknown_optional_field_is_tolerated() -> None:
    manifest = minimal_manifest()
    manifest["future_optional_field"] = {"value": 1}
    assert validate_document(manifest, "session_manifest", "manifest.json") == []


def test_session_manifest_accepts_1_1_camera_provenance_and_fixture_facing() -> None:
    manifest = minimal_manifest(schema_version="1.1.0")
    manifest["session_kind"] = "fixture_replay"
    manifest["camera"].update(
        {
            "facing": "fixture",
            "source_pixel_format": "rgb24",
            "source_color_matrix": "sRGB",
            "source_color_range": "full",
            "source_dataspace_raw": 42,
            "timestamp_source": "fixture_monotonic",
            "delivery_semantics": "ordered",
        }
    )
    assert validate_document(manifest, "session_manifest", "manifest.json") == []


@pytest.mark.parametrize("session_kind", ["unknown", "fixture", "live"])
def test_session_manifest_rejects_unknown_session_kind(session_kind: str) -> None:
    manifest = minimal_manifest()
    manifest["session_kind"] = session_kind
    issues = validate_document(manifest, "session_manifest", "manifest.json")
    assert issues[0].pointer == "/session_kind"


@pytest.mark.parametrize("timestamp", [True, 1.5, "100", -1])
def test_raw_pose_frame_rejects_non_integer_backend_timestamp(timestamp: object) -> None:
    frame = minimal_pose_frame()
    frame["backend_timestamp_ms"] = timestamp
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert issues[0].pointer == "/backend_timestamp_ms"


def test_raw_pose_frame_accepts_non_negative_backend_timestamp() -> None:
    frame = minimal_pose_frame()
    frame["backend_timestamp_ms"] = 0
    assert validate_document(frame, "raw_pose_frame", "frames.ndjson") == []


def test_missing_required_field_reports_json_pointer() -> None:
    frame = minimal_pose_frame()
    del frame["capture_timestamp_ns"]
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert issues[0].code == "SCHEMA.INVALID"
    assert issues[0].pointer == "/capture_timestamp_ns"


def test_every_artifact_schema_is_draft_2020_12() -> None:
    for kind in ARTIFACT_KINDS:
        schema = load_schema(kind)
        assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
        Draft202012Validator.check_schema(schema)


def test_load_schema_rejects_an_unpublished_major() -> None:
    with pytest.raises(ValueError):
        load_schema("session_manifest", 2)


def test_json_pointer_escapes_reserved_characters() -> None:
    assert json_pointer(["a~b", "c/d", 3]) == "/a~0b/c~1d/3"


def test_issue_records_artifact_path_and_kind() -> None:
    frame = minimal_pose_frame()
    del frame["frame_index"]
    issue = validate_document(frame, "raw_pose_frame", "sessions/s1/frames.ndjson")[0]
    assert issue.artifact_path == "sessions/s1/frames.ndjson"
    assert issue.artifact_kind == "raw_pose_frame"


def test_nested_failure_reports_the_full_pointer() -> None:
    frame = minimal_pose_frame()
    frame["normalized_image_landmarks"][1]["x"] = "not-a-number"
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert [issue.pointer for issue in issues] == ["/normalized_image_landmarks/1/x"]


def test_issues_are_sorted_by_pointer() -> None:
    frame = minimal_pose_frame()
    del frame["capture_timestamp_ns"]
    del frame["image_width"]
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert [issue.pointer for issue in issues] == ["/capture_timestamp_ns", "/image_width"]


def test_array_indexes_are_ordered_numerically_not_lexically() -> None:
    frame = minimal_pose_frame()
    frame["normalized_image_landmarks"][10]["x"] = "not-a-number"
    frame["normalized_image_landmarks"][2]["x"] = "not-a-number"
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert [issue.pointer for issue in issues] == [
        "/normalized_image_landmarks/2/x",
        "/normalized_image_landmarks/10/x",
    ]


def test_newer_minor_within_supported_major_is_accepted() -> None:
    manifest = minimal_manifest(schema_version="1.4.2")
    manifest["future_optional_field"] = True
    assert validate_document(manifest, "session_manifest", "manifest.json") == []


def test_unknown_major_reports_the_real_artifact_path() -> None:
    manifest = minimal_manifest(schema_version="2.0.0")
    issues = validate_document(manifest, "session_manifest", "sessions/s1/manifest.json")
    assert len(issues) == 1
    assert issues[0].code == "VERSION.UNKNOWN_MAJOR"
    assert issues[0].artifact_path == "sessions/s1/manifest.json"
    assert issues[0].artifact_kind == "session_manifest"


def test_malformed_version_string_is_a_structural_failure() -> None:
    frame = minimal_pose_frame(schema_version="1.0")
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert issues[0].code == "SCHEMA.INVALID"
    assert issues[0].pointer == "/raw_pose_frame_schema_version"


def test_absent_pose_requires_null_landmark_arrays() -> None:
    frame = minimal_pose_frame(pose_present=False)
    assert validate_document(frame, "raw_pose_frame", "frames.ndjson") == []
    frame["normalized_image_landmarks"] = minimal_pose_frame()["normalized_image_landmarks"]
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert issues[0].code == "SCHEMA.INVALID"
    assert issues[0].pointer == "/normalized_image_landmarks"


def test_container_failure_message_never_embeds_the_observation() -> None:
    frame = minimal_pose_frame(pose_present=False)
    frame["normalized_image_landmarks"] = minimal_pose_frame()["normalized_image_landmarks"]
    issue = validate_document(frame, "raw_pose_frame", "frames.ndjson")[0]
    assert issue.pointer == "/normalized_image_landmarks"
    assert "array of 33 items" in issue.message
    assert "visibility" not in issue.message
    assert len(issue.message) <= 200


def test_backend_omitted_confidence_is_null_not_a_sentinel() -> None:
    frame = minimal_pose_frame()
    frame["normalized_image_landmarks"][0]["visibility"] = None
    frame["normalized_image_landmarks"][0]["presence"] = None
    assert validate_document(frame, "raw_pose_frame", "frames.ndjson") == []


def test_negative_frame_index_is_rejected() -> None:
    frame = minimal_pose_frame(index=-1)
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert [issue.pointer for issue in issues] == ["/frame_index"]


def test_invalid_label_requires_at_least_one_reason() -> None:
    annotation = minimal_annotation()
    annotation["events"][0]["label"] = "invalid"
    issues = validate_document(annotation, "annotation", "annotation.json")
    assert issues[0].code == "SCHEMA.INVALID"
    assert issues[0].pointer == "/events/0/invalid_reasons"

    annotation["events"][0]["invalid_reasons"] = ["partial_rom_top"]
    assert validate_document(annotation, "annotation", "annotation.json") == []


def test_non_invalid_label_rejects_invalid_reasons() -> None:
    annotation = minimal_annotation()
    annotation["events"][0]["label"] = "uncertain"
    annotation["events"][0]["invalid_reasons"] = ["assisted"]
    issues = validate_document(annotation, "annotation", "annotation.json")
    assert issues[0].pointer == "/events/0/invalid_reasons"


def test_invalid_reason_enumeration_is_closed() -> None:
    annotation = minimal_annotation()
    annotation["events"][0]["label"] = "invalid"
    annotation["events"][0]["invalid_reasons"] = ["bad_vibes"]
    issues = validate_document(annotation, "annotation", "annotation.json")
    assert issues[0].pointer == "/events/0/invalid_reasons/0"


def test_event_accepts_a_timestamp_range_instead_of_an_instant() -> None:
    annotation = minimal_annotation()
    event = annotation["events"][0]
    del event["timestamp_ns"]
    event["start_timestamp_ns"] = 100
    event["end_timestamp_ns"] = 200
    assert validate_document(annotation, "annotation", "annotation.json") == []


def test_event_without_any_timing_is_rejected() -> None:
    annotation = minimal_annotation()
    del annotation["events"][0]["timestamp_ns"]
    issues = validate_document(annotation, "annotation", "annotation.json")
    assert issues[0].pointer == "/events/0"


def test_independent_annotation_requires_empty_inputs() -> None:
    annotation = minimal_annotation(stage="independent", input_annotation_ids=[ANNOTATION_ID_B])
    issues = validate_document(annotation, "annotation", "annotation.json")
    assert issues[0].pointer == "/input_annotation_ids"


def test_adjudicated_annotation_requires_two_inputs() -> None:
    annotation = minimal_annotation(stage="adjudicated", input_annotation_ids=[ANNOTATION_ID_A])
    issues = validate_document(annotation, "annotation", "annotation.json")
    assert issues[0].pointer == "/input_annotation_ids"

    annotation["input_annotation_ids"] = [ANNOTATION_ID_A, ANNOTATION_ID_B]
    assert validate_document(annotation, "annotation", "annotation.json") == []


def test_fixture_metadata_requires_synthetic_provenance() -> None:
    fixture = minimal_fixture_metadata()
    fixture["synthetic"] = False
    issues = validate_document(fixture, "fixture_metadata", "fixture.json")
    assert issues[0].pointer == "/synthetic"


def test_fixture_frame_requires_a_lowercase_sha256() -> None:
    fixture = minimal_fixture_metadata()
    fixture["frames"][0]["sha256"] = "NOTAHASH"
    issues = validate_document(fixture, "fixture_metadata", "fixture.json")
    assert issues[0].pointer == "/frames/0/sha256"


def test_malformed_ndjson_line_is_reported_and_valid_lines_remain(tmp_path: Path) -> None:
    path = tmp_path / "frames.ndjson"
    path.write_text(
        json.dumps(minimal_pose_frame(index=0, capture_ns=100)) + "\n{not json\n",
        encoding="utf-8",
    )

    records, issues = validate_ndjson(path)

    assert [record["frame_index"] for record in records] == [0]
    assert len(issues) == 1
    assert issues[0].code == "SCHEMA.INVALID"
    assert issues[0].line == 2


@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
def test_ndjson_rejects_non_json_numeric_constants(tmp_path: Path, constant: str) -> None:
    path = tmp_path / "frames.ndjson"
    path.write_text(f'{{"coordinate": {constant}}}\n', encoding="utf-8")

    records, issues = validate_ndjson(path)

    assert records == []
    assert [(issue.code, issue.artifact_path, issue.line) for issue in issues] == [
        ("SCHEMA.INVALID", str(path), 1)
    ]


def test_ndjson_schema_failure_carries_its_line_number(tmp_path: Path) -> None:
    broken = minimal_pose_frame(index=1, capture_ns=200)
    del broken["image_height"]
    path = tmp_path / "frames.ndjson"
    path.write_text(
        json.dumps(minimal_pose_frame(index=0, capture_ns=100)) + "\n" + json.dumps(broken) + "\n",
        encoding="utf-8",
    )

    records, issues = validate_ndjson(path)

    assert [record["frame_index"] for record in records] == [0, 1]
    assert [(issue.line, issue.pointer) for issue in issues] == [(2, "/image_height")]


def test_valid_ndjson_stream_reports_no_issues(tmp_path: Path) -> None:
    path = tmp_path / "frames.ndjson"
    path.write_text(
        "\n".join(
            json.dumps(minimal_pose_frame(index=index, capture_ns=100 * (index + 1)))
            for index in range(2)
        )
        + "\n",
        encoding="utf-8",
    )

    records, issues = validate_ndjson(path)

    assert len(records) == 2
    assert issues == []
