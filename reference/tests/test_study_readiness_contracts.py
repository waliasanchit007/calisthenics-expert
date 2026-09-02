from __future__ import annotations

import hashlib
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
from observation_contracts.study_readiness import (
    ConditionOutcomeSummary,
    scope_tuple,
    summarize_condition_outcomes,
    verify_study_readiness,
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
        "model_sha256": SHA256,
        "delegate": "cpu",
        "preprocessing_contract_version": "1.0.0",
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
            "model_sha256": SHA256,
            "runtime_version": "0.10.14",
            "delegate": "cpu",
            "requested_cadence": 30,
            "preprocessing_contract_version": "1.0.0",
            "observation_contract_version": "1.0.0",
            "input_class": "empty_scene",
            "submitted_frame_count": 10,
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


def assert_invalid_at(
    validator: Draft202012Validator,
    document: dict[str, Any],
    pointer: tuple[str, ...],
) -> None:
    errors = list(validator.iter_errors(document))
    assert any(tuple(error.absolute_path) == pointer for error in errors)


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


def test_study_error_code_release_block_remains_unique_and_contiguous() -> None:
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
    assert registry["registry_version"] == "1.5.0"
    start = registry["codes"].index(expected[0])
    assert registry["codes"][start : start + len(expected)] == expected
    for code in expected:
        assert registry["codes"].count(code) == 1


def test_missing_required_properties_are_rejected() -> None:
    matrix = condition_matrix()
    del matrix["conditions"]
    assert not study_condition_matrix_schema_validator().is_valid(matrix)

    manifest = readiness_manifest()
    del manifest["protocols"]
    assert not study_readiness_schema_validator().is_valid(manifest)


@pytest.mark.parametrize(
    "bad_path",
    [
        "/absolute/ref.json",
        "../outside/ref.json",
        r"\rooted\ref.json",
        r"\\server\share\ref.json",
    ],
)
def test_artifact_reference_rejects_absolute_and_parent_paths(bad_path: str) -> None:
    manifest = readiness_manifest()
    manifest["protocols"]["capture"]["path"] = bad_path
    assert_invalid_at(
        study_readiness_schema_validator(), manifest, ("protocols", "capture", "path")
    )


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


def test_platform_scope_rejects_participant_input_class() -> None:
    manifest = readiness_manifest()
    manifest["platform_scope"]["input_class"] = "participant"
    assert_invalid_at(
        study_readiness_schema_validator(), manifest, ("platform_scope", "input_class")
    )


def test_unmeasured_detection_fraction_requires_zero_submitted_frames() -> None:
    manifest = readiness_manifest()
    manifest["platform_scope"]["detection_active_frame_fraction"] = "UNMEASURED"
    manifest["platform_scope"]["submitted_frame_count"] = 1
    assert_invalid_at(
        study_readiness_schema_validator(),
        manifest,
        ("platform_scope", "detection_active_frame_fraction"),
    )


def test_numeric_detection_fraction_requires_positive_submitted_frames() -> None:
    manifest = readiness_manifest()
    manifest["platform_scope"]["detection_active_frame_fraction"] = 0
    manifest["platform_scope"]["submitted_frame_count"] = 1
    assert_valid(study_readiness_schema_validator(), manifest)

    manifest["platform_scope"]["submitted_frame_count"] = 0
    assert_invalid_at(
        study_readiness_schema_validator(),
        manifest,
        ("platform_scope", "detection_active_frame_fraction"),
    )


def test_submitted_frame_count_is_non_negative() -> None:
    manifest = readiness_manifest()
    manifest["platform_scope"]["submitted_frame_count"] = -1
    assert_invalid_at(
        study_readiness_schema_validator(), manifest, ("platform_scope", "submitted_frame_count")
    )


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


_REGISTERED_LOCATIONS = {
    "device_export",
    "device_staging",
    "device_transfer_staging",
    "study_store_source",
    "study_store_derived",
    "study_store_report",
    "tombstone",
}


def _write_json(path: Path, document: object) -> bytes:
    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(document, sort_keys=True).encode("utf-8")
    path.write_bytes(serialized)
    return serialized


def _write_reference(root: Path, relative: str, document: object) -> dict[str, str]:
    content = _write_json(root / relative, document)
    return {"path": relative, "sha256": hashlib.sha256(content).hexdigest()}


def _semantic_matrix(*, include_exploratory: bool = False) -> dict[str, object]:
    conditions: list[dict[str, object]] = [
        {
            "condition_id": "anchor-required",
            "classification": "required",
            "set_count": 1,
            "required_artifact_cells": ["session_manifest"],
            "required_metric_cells": ["detection_rate_curve"],
            "source": "test",
        }
    ]
    if include_exploratory:
        conditions.append(
            {
                "condition_id": "exploratory-angle",
                "classification": "exploratory",
                "set_count": 1,
                "required_artifact_cells": ["session_manifest"],
                "required_metric_cells": ["detection_rate_curve"],
                "source": "test",
            }
        )
    return {
        "condition_matrix_schema_version": "1.0.0",
        "matrix_id": "semantic-test-matrix",
        "conditions": conditions,
    }


def _write_manifest(path: Path, manifest: dict[str, object]) -> None:
    _write_json(path, manifest)


def _semantic_repository(
    tmp_path: Path,
    *,
    locked: bool = False,
    accepted_privacy: bool = False,
    include_exploratory: bool = False,
) -> tuple[Path, Path, dict[str, object], dict[str, object]]:
    root = tmp_path / "repository"
    root.mkdir()
    (root / ".git").mkdir()
    matrix = _semantic_matrix(include_exploratory=include_exploratory)
    current = _write_reference(root, "docs/conditions/current.json", matrix)
    scope = cohort_scope()
    protocols = {
        name: _write_reference(root, f"docs/protocols/{name}.json", {"name": name})
        for name in ("capture", "annotation", "privacy")
    }
    privacy_decisions: dict[str, object] = {
        key: {"state": "UNDECIDED", "decision_record": None} for key in PRIVACY_KEYS
    }
    if accepted_privacy:
        privacy_decisions = {
            key: {
                "state": "ACCEPTED",
                "decision_record": _write_reference(
                    root, f"docs/privacy-decisions/{key}.json", {"decision": key}
                ),
            }
            for key in PRIVACY_KEYS
        }
    pilot_lock: dict[str, object] = {
        "state": "UNLOCKED",
        "locked_matrix": None,
        "resource_availability_confirmation": None,
    }
    if locked:
        pilot_lock = {
            "state": "LOCKED",
            "locked_matrix": _write_reference(root, "docs/conditions/locked.json", matrix),
            "resource_availability_confirmation": _write_reference(
                root, "docs/resources/confirmed.json", {"confirmed": True}
            ),
        }
    outcomes = [
        {
            "condition_id": condition["condition_id"],
            "status": "planned",
            "inadmissibility_reason": None,
        }
        for condition in matrix["conditions"]
    ]
    manifest: dict[str, object] = {
        "study_readiness_schema_version": "1.0.0",
        "readiness_id": "semantic-test-readiness",
        "phase_scope": "m0_5_non_participant",
        "protocols": protocols,
        "condition_matrix": {
            "current": current,
            "pilot_lock": pilot_lock,
            "prior": None,
            "matrix_revision_reason": None,
        },
        "condition_outcomes": outcomes,
        "privacy_decisions": privacy_decisions,
        "platform_scope": {
            "platform": "android",
            "device_model": "SM-S938B",
            "os_build": "UP1A.231005.007.S938BXXU1",
            "camera_capability_ref": _write_reference(
                root, "docs/evidence/camera.json", {"camera": "known"}
            ),
            "model_sha256": SHA256,
            "runtime_version": "0.10.14",
            "delegate": "cpu",
            "requested_cadence": 30,
            "preprocessing_contract_version": "1.0.0",
            "observation_contract_version": "1.0.0",
            "input_class": "empty_scene",
            "submitted_frame_count": 10,
            "detection_active_frame_fraction": 0,
            "rendered_scope_statement": "synthetic-only test scope",
        },
        "artifact_locations": sorted(_REGISTERED_LOCATIONS),
        "analysis_cohorts": [
            {
                "cohort_id": "synthetic-test-cohort",
                "scope": scope,
                "inputs": [
                    {
                        "artifact": _write_reference(
                            root, "exports/session-manifest.json", {"session": "synthetic"}
                        ),
                        "scope": scope,
                    }
                ],
            }
        ],
    }
    manifest_path = root / "study" / "readiness.json"
    _write_manifest(manifest_path, manifest)
    return root, manifest_path, manifest, matrix


def _issue_codes(issues: list[object]) -> set[str]:
    return {issue.code for issue in issues if hasattr(issue, "code")}


@pytest.mark.parametrize(
    ("relative", "pointer"),
    [
        ("docs/protocols/capture.json", "/protocols/capture"),
        ("docs/protocols/annotation.json", "/protocols/annotation"),
        ("docs/protocols/privacy.json", "/protocols/privacy"),
        ("docs/conditions/current.json", "/condition_matrix/current"),
        ("docs/evidence/camera.json", "/platform_scope/camera_capability_ref"),
        ("exports/session-manifest.json", "/analysis_cohorts/0/inputs/0/artifact"),
    ],
)
def test_verify_study_readiness_rejects_each_mutated_reference(
    tmp_path: Path, relative: str, pointer: str
) -> None:
    root, manifest_path, _manifest, _matrix = _semantic_repository(tmp_path)

    (root / relative).write_text("mutated", encoding="utf-8")

    issues = verify_study_readiness(manifest_path)

    assert [(issue.code, issue.pointer) for issue in issues] == [
        ("STUDY.ARTIFACT_REFERENCE", pointer)
    ]


@pytest.mark.parametrize("bad_path", ("../outside.json", "/outside.json"))
def test_verify_study_readiness_never_opens_escaping_reference(
    tmp_path: Path, bad_path: str
) -> None:
    root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    outside = tmp_path / "outside.json"
    outside.write_text("must not be read", encoding="utf-8")
    capture = manifest["protocols"]["capture"]
    capture["path"] = str(outside) if bad_path.startswith("/") else bad_path
    _write_manifest(manifest_path, manifest)

    issues = verify_study_readiness(manifest_path)

    assert [(issue.code, issue.pointer) for issue in issues if issue.code.startswith("STUDY.")] == [
        ("STUDY.ARTIFACT_REFERENCE", "/protocols/capture")
    ]
    assert outside.read_text(encoding="utf-8") == "must not be read"
    assert root.is_dir()


def test_verify_study_readiness_requires_a_repository_marker(tmp_path: Path) -> None:
    manifest_path = tmp_path / "readiness.json"
    _write_json(manifest_path, {})

    issues = verify_study_readiness(manifest_path)

    assert [(issue.code, issue.pointer) for issue in issues] == [("STUDY.ARTIFACT_REFERENCE", None)]


def test_participant_gate_allows_undecided_privacy_for_non_participant_readiness(
    tmp_path: Path,
) -> None:
    _root, manifest_path, _manifest, _matrix = _semantic_repository(tmp_path, locked=True)

    assert verify_study_readiness(manifest_path) == []
    issues = verify_study_readiness(manifest_path, require_participant_ready=True)

    assert {issue.code for issue in issues} == {"STUDY.PRIVACY_DECISION"}
    assert len(issues) == 8


def test_participant_gate_rejects_a_rejected_privacy_decision(tmp_path: Path) -> None:
    _root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path, locked=True)
    manifest["privacy_decisions"]["face_handling"]["state"] = "REJECTED"
    manifest["privacy_decisions"]["face_handling"]["decision_record"] = _write_reference(
        manifest_path.parents[1], "docs/privacy-decisions/face.json", {"decision": "rejected"}
    )
    _write_manifest(manifest_path, manifest)

    issues = verify_study_readiness(manifest_path, require_participant_ready=True)

    assert "STUDY.PRIVACY_DECISION" in _issue_codes(issues)


def test_participant_gate_accepts_digest_valid_accepted_privacy_decisions(tmp_path: Path) -> None:
    _root, manifest_path, _manifest, _matrix = _semantic_repository(
        tmp_path, locked=True, accepted_privacy=True
    )

    assert verify_study_readiness(manifest_path, require_participant_ready=True) == []


@pytest.mark.parametrize(
    ("input_class", "submitted_frame_count", "fraction", "expected_code"),
    (
        ("empty_scene", 10, 0.5, "STUDY.EMPTY_SCENE_DETECTION"),
        ("displayed_synthetic_figure", 10, 0, "STUDY.SYNTHETIC_DETECTION_PENDING"),
        (
            "displayed_synthetic_figure",
            0,
            "UNMEASURED",
            "STUDY.SYNTHETIC_DETECTION_PENDING",
        ),
    ),
)
def test_participant_gate_rejects_contradictory_or_pending_detection_evidence(
    tmp_path: Path,
    input_class: str,
    submitted_frame_count: int,
    fraction: float | str,
    expected_code: str,
) -> None:
    _root, manifest_path, manifest, _matrix = _semantic_repository(
        tmp_path,
        locked=True,
        accepted_privacy=True,
    )
    manifest["platform_scope"].update(
        input_class=input_class,
        submitted_frame_count=submitted_frame_count,
        detection_active_frame_fraction=fraction,
    )
    _write_manifest(manifest_path, manifest)

    issues = verify_study_readiness(manifest_path, require_participant_ready=True)

    assert [(issue.code, issue.pointer) for issue in issues] == [
        (expected_code, "/platform_scope/detection_active_frame_fraction")
    ]


@pytest.mark.parametrize(
    "mutation",
    ("missing_prior", "missing_reason"),
)
def test_unlocked_matrix_revision_requires_prior_and_reason(tmp_path: Path, mutation: str) -> None:
    root, manifest_path, manifest, matrix = _semantic_repository(tmp_path)
    prior = _write_reference(root, "docs/conditions/prior.json", matrix)
    binding = manifest["condition_matrix"]
    binding["prior"] = prior
    binding["matrix_revision_reason"] = "clarify allocation"
    if mutation == "missing_prior":
        binding["prior"] = None
    else:
        binding["matrix_revision_reason"] = None
    _write_manifest(manifest_path, manifest)

    assert "STUDY.CONDITION_MATRIX_DRIFT" in _issue_codes(verify_study_readiness(manifest_path))


def test_unlocked_matrix_revision_with_digest_valid_prior_and_reason_is_valid(
    tmp_path: Path,
) -> None:
    root, manifest_path, manifest, matrix = _semantic_repository(tmp_path)
    prior_matrix = dict(matrix)
    prior_matrix["matrix_id"] = "phase0-flagship-android-prior"
    manifest["condition_matrix"]["prior"] = _write_reference(
        root, "docs/conditions/prior.json", prior_matrix
    )
    manifest["condition_matrix"]["matrix_revision_reason"] = "clarify allocation"
    _write_manifest(manifest_path, manifest)

    assert verify_study_readiness(manifest_path) == []


def test_unlocked_matrix_revision_rejects_digest_valid_non_matrix_prior(tmp_path: Path) -> None:
    root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    manifest["condition_matrix"]["prior"] = _write_reference(
        root,
        "docs/conditions/prior.json",
        {"capture_protocol": "digest-valid but not a condition matrix"},
    )
    manifest["condition_matrix"]["matrix_revision_reason"] = "clarify allocation"
    _write_manifest(manifest_path, manifest)

    issues = verify_study_readiness(manifest_path)

    assert "SCHEMA.INVALID" in _issue_codes(issues)
    assert any(issue.artifact_kind == "study_condition_matrix" for issue in issues)


def test_unlocked_matrix_revision_rejects_prior_digest_equal_to_current(tmp_path: Path) -> None:
    _root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    manifest["condition_matrix"]["prior"] = dict(manifest["condition_matrix"]["current"])
    manifest["condition_matrix"]["matrix_revision_reason"] = "clarify allocation"
    _write_manifest(manifest_path, manifest)

    issues = verify_study_readiness(manifest_path)

    assert [(issue.code, issue.pointer) for issue in issues] == [
        ("STUDY.CONDITION_MATRIX_DRIFT", "/condition_matrix/prior")
    ]


def test_condition_matrix_duplicate_id_fails_closed(tmp_path: Path) -> None:
    root, manifest_path, manifest, matrix = _semantic_repository(tmp_path)
    matrix["conditions"].append(dict(matrix["conditions"][0]))
    manifest["condition_matrix"]["current"] = _write_reference(
        root, "docs/conditions/current.json", matrix
    )
    _write_manifest(manifest_path, manifest)

    assert "STUDY.CONDITION_MATRIX_DRIFT" in _issue_codes(verify_study_readiness(manifest_path))


def test_locked_matrix_requires_both_lock_artifacts(tmp_path: Path) -> None:
    _root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    manifest["condition_matrix"]["pilot_lock"] = {
        "state": "LOCKED",
        "locked_matrix": None,
        "resource_availability_confirmation": None,
    }
    _write_manifest(manifest_path, manifest)

    assert "STUDY.PILOT_LOCK" in _issue_codes(verify_study_readiness(manifest_path))


@pytest.mark.parametrize("change", ("removed", "demoted"))
def test_locked_matrix_cannot_remove_or_demote_required_conditions(
    tmp_path: Path, change: str
) -> None:
    root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path, locked=True)
    current = _semantic_matrix()
    if change == "removed":
        current["conditions"] = [
            {
                "condition_id": "replacement-required",
                "classification": "required",
                "set_count": 1,
                "required_artifact_cells": ["session_manifest"],
                "required_metric_cells": ["detection_rate_curve"],
                "source": "test",
            }
        ]
    else:
        current["conditions"].append(
            {
                "condition_id": "replacement-required",
                "classification": "required",
                "set_count": 1,
                "required_artifact_cells": ["session_manifest"],
                "required_metric_cells": ["detection_rate_curve"],
                "source": "test",
            }
        )
        current["conditions"][0]["classification"] = "exploratory"
    manifest["condition_matrix"]["current"] = _write_reference(
        root, "docs/conditions/current.json", current
    )
    manifest["condition_outcomes"] = [
        {"condition_id": item["condition_id"], "status": "planned", "inadmissibility_reason": None}
        for item in current["conditions"]
    ]
    _write_manifest(manifest_path, manifest)

    assert "STUDY.CONDITION_MATRIX_DRIFT" in _issue_codes(verify_study_readiness(manifest_path))


def test_participant_gate_requires_a_locked_pilot(tmp_path: Path) -> None:
    _root, manifest_path, _manifest, _matrix = _semantic_repository(tmp_path, accepted_privacy=True)

    assert "STUDY.PILOT_LOCK" in _issue_codes(
        verify_study_readiness(manifest_path, require_participant_ready=True)
    )


@pytest.mark.parametrize(
    ("status", "reason"),
    (
        ("inadmissible", None),
        ("planned", "instrument_failure"),
        ("admissible", "instrument_failure"),
    ),
)
def test_verifier_enforces_condition_outcome_reason_contract(
    tmp_path: Path, status: str, reason: str | None
) -> None:
    _root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    manifest["condition_outcomes"][0]["status"] = status
    manifest["condition_outcomes"][0]["inadmissibility_reason"] = reason
    _write_manifest(manifest_path, manifest)

    assert "STUDY.CONDITION_OUTCOME" in _issue_codes(verify_study_readiness(manifest_path))


def test_condition_summary_treats_required_instrument_failure_as_viability_failure() -> None:
    manifest = readiness_manifest()
    manifest["condition_outcomes"][0] = {
        "condition_id": "anchor-45deg-3m-chest-indoor-bright-normal",
        "status": "inadmissible",
        "inadmissibility_reason": "instrument_failure",
    }

    summary = summarize_condition_outcomes(manifest, condition_matrix())

    assert summary == ConditionOutcomeSummary(0, 1, 0, 0, "NO-GO")


def test_required_resource_gap_yields_incomplete_coverage() -> None:
    manifest = readiness_manifest()
    manifest["condition_outcomes"][0] = {
        "condition_id": "anchor-45deg-3m-chest-indoor-bright-normal",
        "status": "inadmissible",
        "inadmissibility_reason": "not_executed_resource",
    }

    summary = summarize_condition_outcomes(manifest, condition_matrix())

    assert summary == ConditionOutcomeSummary(0, 0, 1, 0, "INCOMPLETE_COVERAGE")


def test_exploratory_inadmissibility_does_not_affect_required_decision_state() -> None:
    matrix = _semantic_matrix(include_exploratory=True)
    manifest = readiness_manifest()
    manifest["condition_outcomes"] = [
        {"condition_id": "anchor-required", "status": "admissible", "inadmissibility_reason": None},
        {
            "condition_id": "exploratory-angle",
            "status": "inadmissible",
            "inadmissibility_reason": "instrument_failure",
        },
    ]

    summary = summarize_condition_outcomes(manifest, matrix)

    assert summary == ConditionOutcomeSummary(1, 0, 0, 0, "GO")


def test_scope_tuple_uses_the_canonical_order() -> None:
    assert scope_tuple(cohort_scope()) == (
        "android",
        "SM-S938B",
        SHA256,
        "cpu",
        "1.0.0",
        "1.0.0",
    )


def test_verifier_rejects_mixed_scope_within_a_pooled_cohort(tmp_path: Path) -> None:
    root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    different_scope = cohort_scope()
    different_scope["delegate"] = "gpu"
    manifest["analysis_cohorts"][0]["inputs"].append(
        {
            "artifact": _write_reference(root, "exports/other-session.json", {"session": "other"}),
            "scope": different_scope,
        }
    )
    _write_manifest(manifest_path, manifest)

    assert "STUDY.COHORT_SCOPE_MISMATCH" in _issue_codes(verify_study_readiness(manifest_path))


def test_verifier_allows_separate_cohorts_with_different_scopes(tmp_path: Path) -> None:
    root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    different_scope = cohort_scope()
    different_scope["delegate"] = "gpu"
    manifest["analysis_cohorts"].append(
        {
            "cohort_id": "separate-gpu-cohort",
            "scope": different_scope,
            "inputs": [
                {
                    "artifact": _write_reference(
                        root, "exports/gpu-session.json", {"session": "gpu"}
                    ),
                    "scope": different_scope,
                }
            ],
        }
    )
    _write_manifest(manifest_path, manifest)

    assert verify_study_readiness(manifest_path) == []


@pytest.mark.parametrize(
    "locations",
    (
        sorted(_REGISTERED_LOCATIONS - {"tombstone"}),
        [*sorted(_REGISTERED_LOCATIONS), "device_export"],
        ["unregistered"],
    ),
)
def test_verifier_requires_the_complete_registered_location_set(
    tmp_path: Path, locations: list[str]
) -> None:
    _root, manifest_path, manifest, _matrix = _semantic_repository(tmp_path)
    manifest["artifact_locations"] = locations
    _write_manifest(manifest_path, manifest)

    assert "STUDY.LOCATION_REGISTRY" in _issue_codes(verify_study_readiness(manifest_path))


def _canonical_readiness_manifest() -> dict[str, Any]:
    return readiness_manifest()


def test_readiness_schema_accepts_canonical_scope_field_names() -> None:
    assert_valid(study_readiness_schema_validator(), _canonical_readiness_manifest())


@pytest.mark.parametrize(
    "scope_name",
    ("platform_scope", "analysis_cohorts[0].scope", "analysis_cohorts[0].inputs[0].scope"),
)
def test_readiness_schema_rejects_legacy_generic_scope_field_names(scope_name: str) -> None:
    manifest = _canonical_readiness_manifest()
    if scope_name == "platform_scope":
        scope = manifest[scope_name]
    elif scope_name == "analysis_cohorts[0].scope":
        scope = manifest["analysis_cohorts"][0]["scope"]
    else:
        scope = manifest["analysis_cohorts"][0]["inputs"][0]["scope"]
    scope["model_digest"] = scope.pop("model_sha256")
    scope["preprocessing_version"] = scope.pop("preprocessing_contract_version")

    assert not study_readiness_schema_validator().is_valid(manifest)


def test_scope_tuple_returns_the_exact_canonical_six_field_order() -> None:
    assert scope_tuple(cohort_scope()) == (
        "android",
        "SM-S938B",
        SHA256,
        "cpu",
        "1.0.0",
        "1.0.0",
    )
