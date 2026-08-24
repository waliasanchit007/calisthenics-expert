from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from observation_contracts.schema_loader import (
    load_study_condition_matrix_schema,
    load_study_readiness_schema,
    study_condition_matrix_schema_path,
    study_condition_matrix_schema_validator,
    study_readiness_schema_path,
    study_readiness_schema_validator,
)

SHA256 = "a" * 64
PRIVACY_KEYS = (
    "storage_administrator",
    "storage_location",
    "access_matrix",
    "retention_period",
    "withdrawal_and_deletion",
    "backup_handling",
    "incident_response",
    "face_handling",
)


def artifact_ref(path: str = "docs/protocols/capture.v1.md") -> dict[str, str]:
    return {"path": path, "sha256": SHA256}


def condition_matrix() -> dict[str, Any]:
    return {
        "condition_matrix_schema_version": "1.0.0",
        "matrix_id": "phase0-flagship-android-v1",
        "conditions": [
            {
                "condition_id": "anchor-45deg-3m-chest-indoor-bright-normal",
                "classification": "required",
                "set_count": 1,
                "required_artifact_cells": [
                    "session_manifest",
                    "raw_pose_stream",
                    "synchronization_evidence",
                ],
                "required_metric_cells": [
                    "detection_rate_curve",
                    "dropout_runs",
                    "landmark_jitter",
                    "cadence_latency_thermal_battery",
                ],
                "source": "docs/phase-0-plan.md#7-exploratory-study-allocation--28-sets",
            },
        ],
    }


def cohort_scope() -> dict[str, str]:
    return {
        "platform": "android",
        "device_model": "SM-S938B",
        "model_digest": SHA256,
        "delegate": "cpu",
        "preprocessing_version": "1.0.0",
        "observation_contract_version": "1.0.0",
    }


def readiness_manifest() -> dict[str, Any]:
    scope = cohort_scope()
    return {
        "study_readiness_schema_version": "1.0.0",
        "readiness_id": "phase0-synthetic-readiness-v1",
        "phase_scope": "m0_5_non_participant",
        "protocols": {
            "capture": artifact_ref("docs/protocols/capture.v1.md"),
            "annotation": artifact_ref("docs/protocols/annotation.v1.md"),
            "privacy": artifact_ref("docs/protocols/privacy.v1.md"),
        },
        "condition_matrix": {
            "current": artifact_ref("docs/conditions/phase0.v1.json"),
            "pilot_lock": {
                "state": "UNLOCKED",
                "locked_matrix": None,
                "resource_availability_confirmation": None,
            },
            "prior": None,
            "matrix_revision_reason": None,
        },
        "condition_outcomes": [
            {
                "condition_id": "anchor-45deg-3m-chest-indoor-bright-normal",
                "status": "planned",
                "inadmissibility_reason": None,
            },
        ],
        "privacy_decisions": {
            key: {"state": "UNDECIDED", "decision_record": None} for key in PRIVACY_KEYS
        },
        "platform_scope": {
            "platform": "android",
            "device_model": "SM-S938B",
            "os_build": "UP1A.231005.007.S938BXXU1",
            "camera_capability_ref": artifact_ref("docs/evidence/device-capability.json"),
            "model_digest": SHA256,
            "runtime_version": "0.10.14",
            "delegate": "cpu",
            "requested_cadence": 30,
            "preprocessing_version": "1.0.0",
            "observation_contract_version": "1.0.0",
            "input_class": "empty_scene",
            "detection_active_frame_fraction": 0,
            "rendered_scope_statement": (
                "scope: flagship Android only, non-participant empty_scene; no cross-device, "
                "cross-platform, or participant-workload claim"
            ),
        },
        "artifact_locations": ["device_export"],
        "analysis_cohorts": [
            {
                "cohort_id": "flagship-android-synthetic",
                "scope": scope,
                "inputs": [
                    {"artifact": artifact_ref("exports/session-manifest.json"), "scope": scope}
                ],
            },
        ],
    }


def assert_valid(validator: Draft202012Validator, document: dict[str, Any]) -> None:
    errors = list(validator.iter_errors(document))
    assert errors == []


def test_study_tooling_schemas_are_draft_2020_12_and_have_dedicated_paths() -> None:
    readiness_schema = load_study_readiness_schema()
    matrix_schema = load_study_condition_matrix_schema()

    Draft202012Validator.check_schema(readiness_schema)
    Draft202012Validator.check_schema(matrix_schema)
    assert study_readiness_schema_path().name == "study_readiness_manifest.v1.schema.json"
    assert study_condition_matrix_schema_path().name == "study_condition_matrix.v1.schema.json"
    assert study_readiness_schema_validator().schema == readiness_schema
    assert study_condition_matrix_schema_validator().schema == matrix_schema


def test_minimal_condition_matrix_is_valid() -> None:
    assert_valid(study_condition_matrix_schema_validator(), condition_matrix())


def test_minimal_readiness_manifest_is_valid() -> None:
    assert_valid(study_readiness_schema_validator(), readiness_manifest())


def test_new_study_error_codes_are_unique_and_appended() -> None:
    registry_path = (
        Path(__file__).parents[2] / "contracts" / "tooling" / "validator_error_codes.v1.json"
    )
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    expected = [
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
    assert registry["registry_version"] == "1.2.0"
    assert registry["codes"][-len(expected) :] == expected
    for code in expected:
        assert registry["codes"].count(code) == 1


def test_missing_required_properties_are_rejected() -> None:
    matrix = condition_matrix()
    del matrix["conditions"]
    assert not study_condition_matrix_schema_validator().is_valid(matrix)

    manifest = readiness_manifest()
    del manifest["protocols"]
    assert not study_readiness_schema_validator().is_valid(manifest)


@pytest.mark.parametrize("bad_path", ["/absolute/ref.json", "../outside/ref.json"])
def test_artifact_reference_rejects_absolute_and_parent_paths(bad_path: str) -> None:
    manifest = readiness_manifest()
    manifest["protocols"]["capture"]["path"] = bad_path
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_artifact_reference_rejects_non_sha256_digest() -> None:
    manifest = readiness_manifest()
    manifest["protocols"]["capture"]["sha256"] = "not-a-sha256"
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_unknown_privacy_decision_state_is_rejected() -> None:
    manifest = readiness_manifest()
    manifest["privacy_decisions"]["face_handling"]["state"] = "PENDING"
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_pilot_lock_references_must_match_the_lock_state() -> None:
    manifest = readiness_manifest()
    manifest["condition_matrix"]["pilot_lock"]["locked_matrix"] = artifact_ref(
        "docs/conditions/phase0.v1.json"
    )
    assert not study_readiness_schema_validator().is_valid(manifest)


@pytest.mark.parametrize(
    ("status", "reason"),
    [
        ("planned", "instrument_failure"),
        ("admissible", "not_executed_resource"),
        ("inadmissible", None),
    ],
)
def test_condition_outcome_reason_matches_status(status: str, reason: str | None) -> None:
    manifest = readiness_manifest()
    manifest["condition_outcomes"][0]["status"] = status
    manifest["condition_outcomes"][0]["inadmissibility_reason"] = reason
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_condition_matrix_rejects_zero_set_count() -> None:
    matrix = condition_matrix()
    matrix["conditions"][0]["set_count"] = 0
    assert not study_condition_matrix_schema_validator().is_valid(matrix)


def test_readiness_manifest_rejects_unknown_top_level_property() -> None:
    manifest = readiness_manifest()
    manifest["unexpected"] = True
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_readiness_manifest_rejects_decision_record_for_undecided_state() -> None:
    manifest = readiness_manifest()
    manifest["privacy_decisions"]["face_handling"]["decision_record"] = artifact_ref(
        "docs/privacy/face.json"
    )
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_readiness_manifest_rejects_non_null_reason_for_priorless_matrix() -> None:
    manifest = readiness_manifest()
    manifest["condition_matrix"]["matrix_revision_reason"] = "revised"
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_readiness_manifest_rejects_non_empty_lock_references_while_unlocked() -> None:
    manifest = readiness_manifest()
    manifest["condition_matrix"]["pilot_lock"]["resource_availability_confirmation"] = artifact_ref(
        "docs/resources/available.json"
    )
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_readiness_manifest_rejects_non_unmeasured_fraction_outside_range() -> None:
    manifest = readiness_manifest()
    manifest["platform_scope"]["detection_active_frame_fraction"] = 1.1
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_readiness_manifest_rejects_cohort_input_with_wrong_scope_shape() -> None:
    manifest = readiness_manifest()
    manifest["analysis_cohorts"][0]["inputs"][0]["scope"]["delegate"] = None
    assert not study_readiness_schema_validator().is_valid(manifest)


def test_valid_locked_matrix_has_both_digest_bound_references() -> None:
    manifest = readiness_manifest()
    lock = manifest["condition_matrix"]["pilot_lock"]
    lock["state"] = "LOCKED"
    lock["locked_matrix"] = artifact_ref("docs/conditions/phase0.v1.json")
    lock["resource_availability_confirmation"] = artifact_ref("docs/resources/available.json")
    assert_valid(study_readiness_schema_validator(), manifest)


def test_valid_accepted_privacy_decision_has_a_decision_record() -> None:
    manifest = readiness_manifest()
    decision = manifest["privacy_decisions"]["storage_location"]
    decision["state"] = "ACCEPTED"
    decision["decision_record"] = artifact_ref("docs/privacy/storage-location.json")
    assert_valid(study_readiness_schema_validator(), manifest)
