from __future__ import annotations

import ast
import json
import re
from pathlib import Path

import pytest

from observation_contracts.validator_codes import (
    DRAFT_EMITTED_CODES,
    NON_ERROR_DOTTED_IDENTIFIERS,
    STABLE_EMITTED_CODES,
    require_stable_validator_code,
)

ROOT = Path(__file__).parents[2]
PRE_1_5_RELEASED_CODES = [
    "VERSION.INVALID",
    "VERSION.UNKNOWN_MAJOR",
    "SCHEMA.INVALID",
    "STREAM.SCHEMA_VERSION_CHANGED",
    "STREAM.SESSION_CHANGED",
    "FRAME.INDEX_ORDER",
    "FRAME.TIMESTAMP_ORDER",
    "FRAME.INFERENCE_TIME_ORDER",
    "FRAME.BACKEND_TIMESTAMP_ORDER",
    "FRAME.TOPOLOGY_UNSUPPORTED",
    "FRAME.LANDMARK_COUNT",
    "FRAME.ABSENT_POSE_HAS_LANDMARKS",
    "FRAME.NORMALIZED_AVAILABILITY",
    "FRAME.WORLD_AVAILABILITY",
    "FRAME.COORDINATE_ARRAYS_IDENTICAL",
    "SESSION.COUNT_MISMATCH",
    "SESSION.MEDIA_ROLE",
    "SESSION.MEDIA_REQUIRED_MISSING",
    "SESSION.MEDIA_REQUIREMENT_CONTRADICTED",
    "SESSION.MEDIA_DIGEST_MISMATCH",
    "SESSION.SYNC_EVIDENCE_MISSING",
    "SESSION.SYNC_CLOCK_MAPPING",
    "SESSION.SYNC_PROFILE_UNSUPPORTED",
    "SESSION.SYNC_TIMEBASE_INVALID",
    "SESSION.SYNC_ENDPOINT_MISMATCH",
    "SESSION.VIDEO_COUNT_MISMATCH",
    "ANNOTATION.INVALID_STAGE",
    "ANNOTATION.LINK_MISSING",
    "ANNOTATION.LINK_DUPLICATE",
    "ANNOTATION.INVALID_LABEL",
    "FIXTURE.FRAME_INDEX",
    "FIXTURE.TIMESTAMP_ORDER",
    "FIXTURE.BYTE_LENGTH",
    "FIXTURE.CHECKSUM_MISMATCH",
    "PREPROCESSING.VECTOR_INVALID",
    "REPOSITORY.PROHIBITED_PATH",
    "REPOSITORY.PROHIBITED_CONTENT",
    "REPOSITORY.INVALID_FIXTURE_DECLARATION",
    "STUDY.ARTIFACT_REFERENCE",
    "STUDY.PRIVACY_DECISION",
    "STUDY.CONDITION_MATRIX_DRIFT",
    "STUDY.PILOT_LOCK",
    "STUDY.CONDITION_OUTCOME",
    "STUDY.COHORT_SCOPE_MISMATCH",
    "STUDY.ADMISSION_SESSION_KIND",
    "STUDY.ADMISSION_INPUT_CLASS",
    "STUDY.EMPTY_SCENE_DETECTION",
    "STUDY.SYNTHETIC_DETECTION_PENDING",
    "STUDY.LOCATION_REGISTRY",
]


def test_every_declared_stable_emission_is_released_and_no_draft_code_leaks() -> None:
    registry = json.loads(
        (ROOT / "contracts/tooling/validator_error_codes.v1.json").read_text(encoding="utf-8")
    )
    released = set(registry["codes"])

    assert registry["registry_version"] == "1.5.0"
    assert registry["codes"][: len(PRE_1_5_RELEASED_CODES)] == PRE_1_5_RELEASED_CODES
    assert registry["codes"][len(PRE_1_5_RELEASED_CODES) :] == sorted(
        registry["codes"][len(PRE_1_5_RELEASED_CODES) :]
    )
    assert len(registry["codes"]) == len(released)
    assert STABLE_EMITTED_CODES <= released
    assert DRAFT_EMITTED_CODES.isdisjoint(released)
    assert STABLE_EMITTED_CODES.isdisjoint(DRAFT_EMITTED_CODES)
    assert NON_ERROR_DOTTED_IDENTIFIERS.isdisjoint(released)
    assert NON_ERROR_DOTTED_IDENTIFIERS.isdisjoint(STABLE_EMITTED_CODES)
    assert NON_ERROR_DOTTED_IDENTIFIERS.isdisjoint(DRAFT_EMITTED_CODES)


def test_dynamic_latency_codes_are_fully_enumerated() -> None:
    assert {code for code in STABLE_EMITTED_CODES if code.startswith("LATENCY.PERCENTILE_")} == {
        "LATENCY.PERCENTILE_50",
        "LATENCY.PERCENTILE_95",
        "LATENCY.PERCENTILE_99",
    }


def test_every_source_emission_is_explicitly_classified() -> None:
    pattern = re.compile(r"^[A-Z][A-Z0-9_]*(?:\.[A-Z][A-Z0-9_]*)+$")
    literal_codes: set[str] = set()
    sources = (ROOT / "reference/src/observation_contracts").glob("*.py")
    for source in (path for path in sources if path.name != "validator_codes.py"):
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
            if (
                isinstance(node, ast.Constant)
                and isinstance(node.value, str)
                and pattern.fullmatch(node.value)
                and node.value != "LATENCY.PERCENTILE_"
            ):
                literal_codes.add(node.value)
    emitted_codes = literal_codes | {
        "LATENCY.PERCENTILE_50",
        "LATENCY.PERCENTILE_95",
        "LATENCY.PERCENTILE_99",
    }

    assert emitted_codes == (
        STABLE_EMITTED_CODES | DRAFT_EMITTED_CODES | NON_ERROR_DOTTED_IDENTIFIERS
    )


def test_every_stable_code_is_bound_to_a_behavioral_oracle_test() -> None:
    membership_only_tests = {
        "test_every_declared_stable_emission_is_released_and_no_draft_code_leaks",
        "test_every_source_emission_is_explicitly_classified",
        "test_every_stable_code_is_bound_to_a_behavioral_oracle_test",
        "test_media_role_code_is_in_the_released_registry",
        "test_dynamic_latency_codes_are_fully_enumerated",
        "test_required_conjunct_ids_are_the_released_literal_set",
        "test_study_error_code_release_block_remains_unique_and_contiguous",
        "test_sustained_semantic_codes_are_completely_released_by_task8",
    }
    oracle_codes: set[str] = set()
    for path in (ROOT / "reference/tests").glob("test_*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for function in (
            node
            for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
            and node.name not in membership_only_tests
        ):
            has_behavior_assertion = any(
                isinstance(node, (ast.Assert, ast.With)) for node in ast.walk(function)
            )
            if has_behavior_assertion:
                oracle_codes.update(
                    node.value
                    for node in ast.walk(function)
                    if isinstance(node, ast.Constant)
                    and isinstance(node.value, str)
                    and node.value in STABLE_EMITTED_CODES
                )

    assert STABLE_EMITTED_CODES <= oracle_codes


def test_an_emission_site_cannot_invent_an_undeclared_code() -> None:
    with pytest.raises(ValueError, match="undeclared stable code"):
        require_stable_validator_code("UNDECLARED.CODE")
