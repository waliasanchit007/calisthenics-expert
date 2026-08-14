"""Semantic validation of annotation provenance and observational labels."""

from __future__ import annotations

from typing import Any

from observation_contracts.errors import ValidationIssue
from observation_contracts.semantic import validate_annotations
from tests.samples import (
    ANNOTATION_ID_A,
    ANNOTATION_ID_B,
    ANNOTATION_ID_C,
    minimal_annotation,
    minimal_manifest,
)

OTHER_SESSION_ID = "8e7d6c5b-4a39-4b28-9c17-0d6e5f4a3b21"


def codes(issues: list[ValidationIssue]) -> list[str]:
    """Return stable codes so tests do not depend on prose wording."""
    return [issue.code for issue in issues]


def test_adjudicated_annotation_resolves_two_independent_inputs() -> None:
    first = minimal_annotation(annotation_id=ANNOTATION_ID_A, stage="independent")
    second = minimal_annotation(annotation_id=ANNOTATION_ID_B, stage="independent")
    consensus = minimal_annotation(
        annotation_id=ANNOTATION_ID_C,
        stage="adjudicated",
        input_annotation_ids=[ANNOTATION_ID_A, ANNOTATION_ID_B],
    )

    assert validate_annotations(
        minimal_manifest(),
        [first, second, consensus],
        ["a.json", "b.json", "c.json"],
    ) == []


def test_missing_input_id_is_reported_on_the_adjudicated_artifact() -> None:
    consensus = minimal_annotation(
        annotation_id=ANNOTATION_ID_C,
        stage="adjudicated",
        input_annotation_ids=[ANNOTATION_ID_A, ANNOTATION_ID_B],
    )

    issues = validate_annotations(minimal_manifest(), [consensus], ["c.json"])

    assert codes(issues) == ["ANNOTATION.LINK_MISSING", "ANNOTATION.LINK_MISSING"]
    assert [issue.pointer for issue in issues] == [
        "/input_annotation_ids/0",
        "/input_annotation_ids/1",
    ]


def test_duplicate_annotation_id_is_reported_before_link_resolution() -> None:
    first = minimal_annotation(annotation_id=ANNOTATION_ID_A)
    duplicate = minimal_annotation(annotation_id=ANNOTATION_ID_A)
    consensus = minimal_annotation(
        annotation_id=ANNOTATION_ID_C,
        stage="adjudicated",
        input_annotation_ids=[ANNOTATION_ID_A, ANNOTATION_ID_B],
    )

    issues = validate_annotations(
        minimal_manifest(),
        [first, duplicate, consensus],
        ["a.json", "duplicate.json", "c.json"],
    )

    assert codes(issues) == ["ANNOTATION.LINK_DUPLICATE", "ANNOTATION.LINK_MISSING"]
    assert issues[0].artifact_path == "duplicate.json"
    assert issues[1].pointer == "/input_annotation_ids/1"


def test_adjudicated_annotation_cannot_link_to_an_adjudicated_annotation() -> None:
    adjudicated = minimal_annotation(
        annotation_id=ANNOTATION_ID_B,
        stage="adjudicated",
        input_annotation_ids=[ANNOTATION_ID_A, ANNOTATION_ID_C],
    )
    consensus = minimal_annotation(
        annotation_id=ANNOTATION_ID_C,
        stage="adjudicated",
        input_annotation_ids=[ANNOTATION_ID_B, ANNOTATION_ID_A],
    )

    issues = validate_annotations(
        minimal_manifest(),
        [minimal_annotation(annotation_id=ANNOTATION_ID_A), adjudicated, consensus],
        ["a.json", "b.json", "c.json"],
    )

    assert "ANNOTATION.INVALID_STAGE" in codes(issues)
    assert any(issue.pointer == "/input_annotation_ids/0" for issue in issues)


def test_cross_session_annotation_linkage_is_rejected() -> None:
    foreign = minimal_annotation(annotation_id=ANNOTATION_ID_A, session_id=OTHER_SESSION_ID)
    consensus = minimal_annotation(
        annotation_id=ANNOTATION_ID_C,
        stage="adjudicated",
        input_annotation_ids=[ANNOTATION_ID_A, ANNOTATION_ID_B],
    )

    issues = validate_annotations(
        minimal_manifest(),
        [foreign, minimal_annotation(annotation_id=ANNOTATION_ID_B), consensus],
        ["foreign.json", "b.json", "c.json"],
    )

    assert "ANNOTATION.LINK_MISSING" in codes(issues)
    assert any(issue.artifact_path == "foreign.json" for issue in issues)


def test_independent_annotation_rejects_nonempty_inputs() -> None:
    annotation = minimal_annotation(
        annotation_id=ANNOTATION_ID_A,
        stage="independent",
        input_annotation_ids=[ANNOTATION_ID_B],
    )

    issues = validate_annotations(minimal_manifest(), [annotation], ["a.json"])

    assert codes(issues) == ["ANNOTATION.INVALID_STAGE"]
    assert issues[0].pointer == "/input_annotation_ids"


def test_uncertain_label_rejects_invalid_reasons() -> None:
    annotation = minimal_annotation()
    event = annotation["events"][0]
    event["label"] = "uncertain"
    event["invalid_reasons"] = ["assisted"]

    issues = validate_annotations(minimal_manifest(), [annotation], ["a.json"])

    assert codes(issues) == ["ANNOTATION.INVALID_LABEL"]
    assert issues[0].pointer == "/events/0/invalid_reasons"


def test_invalid_label_requires_invalid_reasons() -> None:
    annotation = minimal_annotation()
    annotation["events"][0]["label"] = "invalid"

    issues = validate_annotations(minimal_manifest(), [annotation], ["a.json"])

    assert codes(issues) == ["ANNOTATION.INVALID_LABEL"]
    assert issues[0].pointer == "/events/0/invalid_reasons"


def test_valid_label_rejects_invalid_reasons() -> None:
    annotation = minimal_annotation()
    annotation["events"][0]["invalid_reasons"] = ["partial_rom_top"]

    issues = validate_annotations(minimal_manifest(), [annotation], ["a.json"])

    assert codes(issues) == ["ANNOTATION.INVALID_LABEL"]
    assert issues[0].pointer == "/events/0/invalid_reasons"


def test_rep_label_requires_rep_index() -> None:
    annotation = minimal_annotation()
    del annotation["events"][0]["rep_index"]

    issues = validate_annotations(minimal_manifest(), [annotation], ["a.json"])

    assert codes(issues) == ["ANNOTATION.INVALID_LABEL"]
    assert issues[0].pointer == "/events/0/rep_index"


def test_annotation_issue_order_is_artifact_then_event_order() -> None:
    first = minimal_annotation(annotation_id=ANNOTATION_ID_A)
    first["events"][0]["invalid_reasons"] = ["assisted"]
    second = minimal_annotation(annotation_id=ANNOTATION_ID_B)
    second["events"][0]["label"] = "invalid"

    issues = validate_annotations(
        minimal_manifest(),
        [first, second],
        ["a.json", "b.json"],
    )

    assert [(issue.artifact_path, issue.pointer) for issue in issues] == [
        ("a.json", "/events/0/invalid_reasons"),
        ("b.json", "/events/0/invalid_reasons"),
    ]


def test_labels_remain_observational_without_threshold_validation() -> None:
    annotation = minimal_annotation()
    event: dict[str, Any] = annotation["events"][0]
    event["annotator_confidence"] = 0.0
    event["value"] = {"movement_prediction": "ignored"}

    assert validate_annotations(minimal_manifest(), [annotation], ["a.json"]) == []
