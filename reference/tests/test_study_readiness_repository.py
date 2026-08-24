from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from observation_contracts.study_readiness import verify_study_readiness

REPOSITORY_ROOT = Path(__file__).parents[2]
PROTOCOLS_ROOT = REPOSITORY_ROOT / "docs" / "protocols"
MANIFEST_PATH = PROTOCOLS_ROOT / "study-readiness.m05.v1.json"
MATRIX_PATH = PROTOCOLS_ROOT / "condition-matrix.m05.v1.json"
PROTOCOL_PATHS = {
    "capture": PROTOCOLS_ROOT / "capture-protocol.v1.md",
    "annotation": PROTOCOLS_ROOT / "annotation-protocol.v1.md",
    "privacy": PROTOCOLS_ROOT / "privacy-retention-withdrawal.v1.md",
}

EXPECTED_MODEL_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
EXPECTED_CAMERA_CAPABILITY_SHA256 = (
    "475bc9592b702f75c7890fb62a520e1ebe2e32e961d8de6cd15c9436ed51fde7"
)
EXPECTED_OS_BUILD = "BP4A.251205.006.S938BXXSBCZG3"
EXPECTED_ARTIFACT_CELLS = {
    "session_manifest",
    "raw_pose_stream",
    "synchronization_evidence",
    "device_metadata",
    "camera_capability",
    "failure_log",
    "thermal_battery_trace",
    "cadence_trace",
    "synthetic_input_attestation",
    "independent_annotation",
    "adjudicated_annotation",
}
EXPECTED_METRIC_CELLS = {
    "detection_rate_curve",
    "dropout_runs",
    "landmark_jitter",
    "cadence_latency_thermal_battery",
    "synchronization_offset_drift",
    "annotation_agreement",
}
EXPECTED_EXPLORATORY_IDS = {
    "athlete-c-anchor",
    "athlete-c-sixty-degrees",
    "reserve-re-shoot-one",
    "reserve-re-shoot-two",
}
EXPECTED_SOURCE = "docs/phase-0-plan.md#7-exploratory-study-allocation--28-sets"
EXPECTED_PRIVACY_KEYS = {
    "storage_administrator",
    "storage_location",
    "access_matrix",
    "retention_period",
    "withdrawal_and_deletion",
    "backup_handling",
    "incident_response",
    "face_handling",
}
EXPECTED_LOCATIONS = {
    "device_export",
    "device_staging",
    "device_transfer_staging",
    "study_store_source",
    "study_store_derived",
    "study_store_report",
    "tombstone",
}


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_committed_manifest_is_non_participant_ready_but_participant_blocked() -> None:
    assert verify_study_readiness(MANIFEST_PATH) == []
    issues = verify_study_readiness(MANIFEST_PATH, require_participant_ready=True)
    assert [issue.code for issue in issues].count("STUDY.PRIVACY_DECISION") == 8
    assert [issue.code for issue in issues].count("STUDY.PILOT_LOCK") == 1
    assert len(issues) == 9


def test_manifest_binds_final_protocols_and_matrix_by_actual_sha256() -> None:
    manifest = _load_json(MANIFEST_PATH)
    for name, path in PROTOCOL_PATHS.items():
        reference = manifest["protocols"][name]
        assert reference["path"] == f"docs/protocols/{path.name}"
        assert reference["sha256"] == _sha256(path)
        assert path.is_relative_to(PROTOCOLS_ROOT)

    matrix_reference = manifest["condition_matrix"]["current"]
    assert matrix_reference["path"] == "docs/protocols/condition-matrix.m05.v1.json"
    assert matrix_reference["sha256"] == _sha256(MATRIX_PATH)
    assert MATRIX_PATH.is_relative_to(PROTOCOLS_ROOT)


def test_candidate_matrix_has_exact_28_sets_and_declared_cells() -> None:
    matrix = _load_json(MATRIX_PATH)
    conditions = matrix["conditions"]
    assert len(conditions) == 28
    assert sum(condition["set_count"] for condition in conditions) == 28
    assert len({condition["condition_id"] for condition in conditions}) == 28
    assert {condition["classification"] for condition in conditions} == {
        "required",
        "exploratory",
    }
    assert {
        condition["condition_id"]
        for condition in conditions
        if condition["classification"] == "exploratory"
    } == EXPECTED_EXPLORATORY_IDS
    assert all(condition["source"] == EXPECTED_SOURCE for condition in conditions)
    assert all(condition["required_artifact_cells"] for condition in conditions)
    assert all(condition["required_metric_cells"] for condition in conditions)
    assert all(
        set(condition["required_artifact_cells"]).issubset(EXPECTED_ARTIFACT_CELLS)
        and set(condition["required_metric_cells"]).issubset(EXPECTED_METRIC_CELLS)
        for condition in conditions
    )

    anchor_sessions = [
        condition["condition_id"]
        for condition in conditions
        if condition["condition_id"].startswith("anchor-replication-")
    ]
    assert anchor_sessions == [
        "anchor-replication-day-one",
        "anchor-replication-day-two",
        "anchor-replication-day-three",
    ]
    serialized = MATRIX_PATH.read_text(encoding="utf-8").lower()
    assert "dual-phone" not in serialized
    assert "cross-platform" not in serialized
    assert '"ios"' not in serialized


def test_protocols_declare_every_canonical_cell_used_by_matrix() -> None:
    protocol_text = "\n".join(path.read_text(encoding="utf-8") for path in PROTOCOL_PATHS.values())
    matrix = _load_json(MATRIX_PATH)
    used_artifact_cells = {
        cell for condition in matrix["conditions"] for cell in condition["required_artifact_cells"]
    }
    used_metric_cells = {
        cell for condition in matrix["conditions"] for cell in condition["required_metric_cells"]
    }
    for slug in used_artifact_cells | used_metric_cells:
        assert f"`{slug}`" in protocol_text
    assert "Canonical artifact cell slugs" in protocol_text
    assert "Canonical metric cell slugs" in protocol_text


def test_manifest_preserves_synthetic_scope_privacy_lock_and_authoritative_metadata() -> None:
    manifest = _load_json(MANIFEST_PATH)
    assert manifest["phase_scope"] == "m0_5_non_participant"
    lock = manifest["condition_matrix"]["pilot_lock"]
    assert lock == {
        "state": "UNLOCKED",
        "locked_matrix": None,
        "resource_availability_confirmation": None,
    }
    assert manifest["condition_matrix"]["prior"] is None
    assert manifest["condition_matrix"]["matrix_revision_reason"] is None
    assert set(manifest["privacy_decisions"]) == EXPECTED_PRIVACY_KEYS
    assert all(
        decision == {"state": "UNDECIDED", "decision_record": None}
        for decision in manifest["privacy_decisions"].values()
    )
    assert set(manifest["artifact_locations"]) == EXPECTED_LOCATIONS

    scope = manifest["platform_scope"]
    assert scope["platform"] == "android"
    assert scope["device_model"] == "SM-S938B"
    assert scope["os_build"] == EXPECTED_OS_BUILD
    assert scope["camera_capability_ref"]["sha256"] == EXPECTED_CAMERA_CAPABILITY_SHA256
    assert scope["model_sha256"] == EXPECTED_MODEL_SHA256
    assert scope["runtime_version"] == "1.0.0"
    assert scope["delegate"] == "cpu"
    assert scope["requested_cadence"] == 30
    assert scope["preprocessing_contract_version"] == "1.0.0"
    assert scope["observation_contract_version"] == "1.0.0"
    assert scope["input_class"] == "empty_scene"
    assert scope["detection_active_frame_fraction"] == 0
    for excluded in (
        "iOS equivalence",
        "mid-range behavior",
        "participant evidence",
        "signal-quality thresholds",
        "production readiness",
    ):
        assert excluded in scope["rendered_scope_statement"]


def test_manifest_has_one_planned_null_reason_outcome_per_matrix_condition() -> None:
    manifest = _load_json(MANIFEST_PATH)
    matrix = _load_json(MATRIX_PATH)
    outcomes = manifest["condition_outcomes"]
    assert len(outcomes) == 28
    assert {outcome["condition_id"] for outcome in outcomes} == {
        condition["condition_id"] for condition in matrix["conditions"]
    }
    assert all(
        outcome["status"] == "planned" and outcome["inadmissibility_reason"] is None
        for outcome in outcomes
    )


def test_analysis_cohort_is_homogeneous_and_uses_flagship_scope() -> None:
    manifest = _load_json(MANIFEST_PATH)
    cohorts = manifest["analysis_cohorts"]
    assert len(cohorts) == 1
    cohort = cohorts[0]
    assert cohort["scope"] == {
        "platform": "android",
        "device_model": "SM-S938B",
        "model_sha256": EXPECTED_MODEL_SHA256,
        "delegate": "cpu",
        "preprocessing_contract_version": "1.0.0",
        "observation_contract_version": "1.0.0",
    }
    assert all(item["scope"] == cohort["scope"] for item in cohort["inputs"])


def test_tooling_contract_release_1_2_0_records_m05_operating_boundary() -> None:
    changelog = (REPOSITORY_ROOT / "contracts" / "CHANGELOG.md").read_text(encoding="utf-8")

    assert "## [1.2.0]" in changelog
    for schema in (
        "study_readiness_manifest.v1.schema.json",
        "study_condition_matrix.v1.schema.json",
    ):
        assert schema in changelog
    for code in (
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
    ):
        assert code in changelog
    assert "verify-study-readiness" in changelog
    assert "verify-participant-capture-gate" in changelog
    assert "ADR-0005" in changelog
    assert "participant capture is blocked" in changelog.lower()
    assert "no tag" in changelog.lower()
    assert "absent `session_kind`" in changelog
