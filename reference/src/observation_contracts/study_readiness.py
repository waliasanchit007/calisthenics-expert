"""Canonical semantic validation for M0.5 study-readiness manifests."""

from __future__ import annotations

import hashlib
import hmac
import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from jsonschema import Draft202012Validator

from observation_contracts.errors import ValidationIssue
from observation_contracts.schema_loader import (
    load_strict_json,
    study_condition_matrix_schema_validator,
    study_readiness_schema_validator,
)
from observation_contracts.structural import json_pointer

_SCOPE_KEYS = (
    "platform",
    "device_model",
    "model_sha256",
    "delegate",
    "preprocessing_contract_version",
    "observation_contract_version",
)
_PRIVACY_KEYS = (
    "storage_administrator",
    "storage_location",
    "access_matrix",
    "retention_period",
    "withdrawal_and_deletion",
    "backup_handling",
    "incident_response",
    "face_handling",
)
_REGISTERED_LOCATIONS = frozenset(
    {
        "device_export",
        "device_staging",
        "device_transfer_staging",
        "study_store_source",
        "study_store_derived",
        "study_store_report",
        "tombstone",
    }
)


@dataclass(frozen=True)
class ConditionOutcomeSummary:
    """Required-condition outcomes kept distinct from coverage and viability failures."""

    required_admissible: int
    instrument_failures: int
    resource_gaps: int
    planned: int
    decision_state: str


def resolve_artifact(root: Path, relative: str) -> Path:
    """Resolve one repository-relative artifact reference without permitting root escapes."""
    candidate = (root / relative).resolve()
    if Path(relative).is_absolute() or candidate == root or not candidate.is_relative_to(root):
        raise ValueError("artifact reference escapes repository root")
    return candidate


def scope_tuple(scope: Mapping[str, object]) -> tuple[str, str, str, str, str, str]:
    """Return the canonical comparison tuple for one analysis cohort scope."""
    values = tuple(scope.get(key) for key in _SCOPE_KEYS)
    if not all(isinstance(value, str) for value in values):
        raise ValueError("cohort scope is missing a canonical string field")
    return values  # type: ignore[return-value]


def summarize_condition_outcomes(
    manifest: Mapping[str, object], matrix: Mapping[str, object]
) -> ConditionOutcomeSummary:
    """Summarize only required current-matrix outcomes without performing I/O.

    The caller supplies the already schema-validated current matrix so classifications never need
    to be duplicated in the manifest. Exploratory outcomes are intentionally ignored.
    """
    required_ids = {
        condition.get("condition_id")
        for condition in _mappings(matrix.get("conditions"))
        if condition.get("classification") == "required"
        and isinstance(condition.get("condition_id"), str)
    }
    outcomes = {
        outcome.get("condition_id"): outcome
        for outcome in _mappings(manifest.get("condition_outcomes"))
        if isinstance(outcome.get("condition_id"), str)
    }
    required_admissible = 0
    instrument_failures = 0
    resource_gaps = 0
    planned = 0
    for condition_id in required_ids:
        outcome = outcomes.get(condition_id)
        if outcome is None:
            continue
        status = outcome.get("status")
        reason = outcome.get("inadmissibility_reason")
        if status == "admissible":
            required_admissible += 1
        elif status == "planned":
            planned += 1
        elif status == "inadmissible" and reason == "instrument_failure":
            instrument_failures += 1
        elif status == "inadmissible" and reason == "not_executed_resource":
            resource_gaps += 1
    if resource_gaps:
        decision_state = "INCOMPLETE_COVERAGE"
    elif planned:
        decision_state = "PLANNED"
    elif instrument_failures:
        decision_state = "NO-GO" if required_admissible == 0 else "CONDITIONAL-GO"
    else:
        decision_state = "GO"
    return ConditionOutcomeSummary(
        required_admissible,
        instrument_failures,
        resource_gaps,
        planned,
        decision_state,
    )


def verify_study_readiness(
    path: Path, *, require_participant_ready: bool = False
) -> list[ValidationIssue]:
    """Return deterministic semantic and structural failures for one readiness manifest."""
    manifest_path = Path(path).resolve()
    root = _repository_root(manifest_path)
    if root is None:
        return [
            _issue(
                "STUDY.ARTIFACT_REFERENCE",
                manifest_path,
                "could not discover a repository root for the readiness manifest",
            )
        ]
    manifest = _load_json(manifest_path, "study_readiness_manifest")
    if manifest is None:
        return _sorted(_load_error_issues(manifest_path, "study_readiness_manifest"))
    issues = _schema_issues(
        manifest, study_readiness_schema_validator(), manifest_path, "study_readiness_manifest"
    )
    if not isinstance(manifest, Mapping):
        return _sorted(issues)

    artifact_success: dict[str, bool] = {}
    references = _references(manifest)
    reference_content: dict[str, bytes] = {}
    for pointer, reference in references:
        content, reference_issues = _read_reference(root, reference, manifest_path, pointer)
        issues.extend(reference_issues)
        artifact_success[pointer] = content is not None
        if content is not None:
            reference_content[pointer] = content

    current_matrix = _load_matrix(
        reference_content.get("/condition_matrix/current"),
        manifest_path,
        "/condition_matrix/current",
        issues,
    )
    locked_matrix = _load_matrix(
        reference_content.get("/condition_matrix/pilot_lock/locked_matrix"),
        manifest_path,
        "/condition_matrix/pilot_lock/locked_matrix",
        issues,
    )
    _validate_matrix_binding(manifest, current_matrix, locked_matrix, manifest_path, issues)
    _validate_outcomes(manifest, current_matrix, manifest_path, issues)
    _validate_locations(manifest, manifest_path, issues)
    _validate_cohorts(manifest, manifest_path, issues)
    if require_participant_ready:
        _validate_participant_gate(manifest, artifact_success, manifest_path, issues)
    return _sorted(issues)


def _repository_root(manifest_path: Path) -> Path | None:
    """Find the nearest enclosing checkout marker without invoking git."""
    for ancestor in (manifest_path.parent, *manifest_path.parents):
        if (ancestor / ".git").is_file() or (ancestor / ".git").is_dir():
            return ancestor
    return None


def _load_json(path: Path, artifact_kind: str) -> object | None:
    try:
        return load_strict_json(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _load_error_issues(path: Path, artifact_kind: str) -> list[ValidationIssue]:
    return [_issue("SCHEMA.INVALID", path, f"could not read valid JSON {artifact_kind}")]


def _schema_issues(
    document: object, validator: Draft202012Validator, path: Path, artifact_kind: str
) -> list[ValidationIssue]:
    return [
        ValidationIssue(
            code="SCHEMA.INVALID",
            artifact_path=str(path),
            artifact_kind=artifact_kind,
            message=f"schema validation failed: {error.validator}",
            pointer=json_pointer(error.absolute_path),
        )
        for error in validator.iter_errors(document)
    ]


def _references(manifest: Mapping[str, object]) -> list[tuple[str, object]]:
    references: list[tuple[str, object]] = []
    protocols = _mapping(manifest.get("protocols"))
    for name in ("capture", "annotation", "privacy"):
        references.append((f"/protocols/{name}", protocols.get(name)))
    binding = _mapping(manifest.get("condition_matrix"))
    references.append(("/condition_matrix/current", binding.get("current")))
    if binding.get("prior") is not None:
        references.append(("/condition_matrix/prior", binding.get("prior")))
    lock = _mapping(binding.get("pilot_lock"))
    for name in ("locked_matrix", "resource_availability_confirmation"):
        if lock.get(name) is not None:
            references.append((f"/condition_matrix/pilot_lock/{name}", lock.get(name)))
    decisions = _mapping(manifest.get("privacy_decisions"))
    for name in _PRIVACY_KEYS:
        record = _mapping(decisions.get(name)).get("decision_record")
        if record is not None:
            references.append((f"/privacy_decisions/{name}/decision_record", record))
    platform_scope = _mapping(manifest.get("platform_scope"))
    references.append(
        ("/platform_scope/camera_capability_ref", platform_scope.get("camera_capability_ref"))
    )
    for index, cohort in enumerate(_mappings(manifest.get("analysis_cohorts"))):
        for input_index, item in enumerate(_mappings(cohort.get("inputs"))):
            references.append(
                (f"/analysis_cohorts/{index}/inputs/{input_index}/artifact", item.get("artifact"))
            )
    return references


def _read_reference(
    root: Path, reference: object, manifest_path: Path, pointer: str
) -> tuple[bytes | None, list[ValidationIssue]]:
    artifact_ref = _mapping(reference)
    relative = artifact_ref.get("path")
    expected_digest = artifact_ref.get("sha256")
    if not isinstance(relative, str) or not isinstance(expected_digest, str):
        return None, [_artifact_issue(manifest_path, pointer, "artifact reference is incomplete")]
    try:
        candidate = resolve_artifact(root, relative)
        content = candidate.read_bytes()
    except (OSError, ValueError):
        return None, [_artifact_issue(manifest_path, pointer, "artifact reference is unavailable")]
    actual_digest = hashlib.sha256(content).hexdigest()
    if not hmac.compare_digest(actual_digest, expected_digest):
        return None, [
            _artifact_issue(manifest_path, pointer, "artifact reference digest does not match")
        ]
    return content, []


def _load_matrix(
    content: bytes | None, manifest_path: Path, pointer: str, issues: list[ValidationIssue]
) -> Mapping[str, object] | None:
    if content is None:
        return None
    try:
        matrix = load_strict_json(content.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        issues.append(
            _issue("SCHEMA.INVALID", manifest_path, "condition matrix is not valid JSON", pointer)
        )
        return None
    issues.extend(
        _schema_issues(
            matrix,
            study_condition_matrix_schema_validator(),
            manifest_path,
            "study_condition_matrix",
        )
    )
    return matrix if isinstance(matrix, Mapping) else None


def _validate_matrix_binding(
    manifest: Mapping[str, object],
    current: Mapping[str, object] | None,
    locked: Mapping[str, object] | None,
    path: Path,
    issues: list[ValidationIssue],
) -> None:
    binding = _mapping(manifest.get("condition_matrix"))
    prior = binding.get("prior")
    reason = binding.get("matrix_revision_reason")
    if (prior is None) != (reason is None):
        issues.append(
            _issue(
                "STUDY.CONDITION_MATRIX_DRIFT",
                path,
                "an unlocked matrix revision requires both prior and matrix_revision_reason",
                "/condition_matrix",
            )
        )
    lock = _mapping(binding.get("pilot_lock"))
    is_locked = lock.get("state") == "LOCKED"
    has_locked_matrix = isinstance(lock.get("locked_matrix"), Mapping)
    has_confirmation = isinstance(lock.get("resource_availability_confirmation"), Mapping)
    if is_locked and (not has_locked_matrix or not has_confirmation):
        issues.append(
            _issue(
                "STUDY.PILOT_LOCK",
                path,
                "a locked pilot requires both lock artifacts",
                "/condition_matrix/pilot_lock",
            )
        )
    if current is not None:
        _validate_unique_condition_ids(current, path, "/condition_matrix/current", issues)
    if is_locked and current is not None and locked is not None:
        current_conditions = {
            item.get("condition_id"): item for item in _mappings(current.get("conditions"))
        }
        for condition in _mappings(locked.get("conditions")):
            condition_id = condition.get("condition_id")
            if condition.get("classification") != "required" or not isinstance(condition_id, str):
                continue
            replacement = current_conditions.get(condition_id)
            if replacement is None or replacement.get("classification") != "required":
                issues.append(
                    _issue(
                        "STUDY.CONDITION_MATRIX_DRIFT",
                        path,
                        f"locked required condition {condition_id!r} was removed or demoted",
                        "/condition_matrix/current",
                    )
                )


def _validate_unique_condition_ids(
    matrix: Mapping[str, object], path: Path, pointer: str, issues: list[ValidationIssue]
) -> None:
    seen: set[str] = set()
    for condition in _mappings(matrix.get("conditions")):
        condition_id = condition.get("condition_id")
        if isinstance(condition_id, str):
            if condition_id in seen:
                issues.append(
                    _issue(
                        "STUDY.CONDITION_MATRIX_DRIFT",
                        path,
                        f"condition_id {condition_id!r} is duplicated",
                        pointer,
                    )
                )
            seen.add(condition_id)


def _validate_outcomes(
    manifest: Mapping[str, object],
    matrix: Mapping[str, object] | None,
    path: Path,
    issues: list[ValidationIssue],
) -> None:
    outcomes = _mappings(manifest.get("condition_outcomes"))
    ids: list[str] = []
    for index, outcome in enumerate(outcomes):
        pointer = f"/condition_outcomes/{index}"
        condition_id = outcome.get("condition_id")
        if isinstance(condition_id, str):
            ids.append(condition_id)
        status = outcome.get("status")
        reason = outcome.get("inadmissibility_reason")
        valid_reason = reason in {"instrument_failure", "not_executed_resource"}
        if (status == "inadmissible" and not valid_reason) or (
            status in {"planned", "admissible"} and reason is not None
        ):
            issues.append(
                _issue(
                    "STUDY.CONDITION_OUTCOME",
                    path,
                    "condition outcome status and inadmissibility reason disagree",
                    pointer,
                )
            )
    if len(ids) != len(set(ids)):
        issues.append(
            _issue(
                "STUDY.CONDITION_OUTCOME",
                path,
                "each current condition requires exactly one outcome",
                "/condition_outcomes",
            )
        )
    if matrix is not None:
        condition_ids = {
            condition.get("condition_id")
            for condition in _mappings(matrix.get("conditions"))
            if isinstance(condition.get("condition_id"), str)
        }
        if set(ids) != condition_ids:
            issues.append(
                _issue(
                    "STUDY.CONDITION_OUTCOME",
                    path,
                    "outcomes must cover each current condition exactly once",
                    "/condition_outcomes",
                )
            )


def _validate_locations(
    manifest: Mapping[str, object], path: Path, issues: list[ValidationIssue]
) -> None:
    locations = manifest.get("artifact_locations")
    location_list = locations if isinstance(locations, list) else []
    if len(location_list) != len(set(location_list)) or set(location_list) != _REGISTERED_LOCATIONS:
        issues.append(
            _issue(
                "STUDY.LOCATION_REGISTRY",
                path,
                "artifact_locations must equal the registered location set",
                "/artifact_locations",
            )
        )


def _validate_cohorts(
    manifest: Mapping[str, object], path: Path, issues: list[ValidationIssue]
) -> None:
    for index, cohort in enumerate(_mappings(manifest.get("analysis_cohorts"))):
        try:
            declared = scope_tuple(_mapping(cohort.get("scope")))
        except ValueError:
            continue
        for input_index, item in enumerate(_mappings(cohort.get("inputs"))):
            try:
                actual = scope_tuple(_mapping(item.get("scope")))
            except ValueError:
                continue
            if actual != declared:
                issues.append(
                    _issue(
                        "STUDY.COHORT_SCOPE_MISMATCH",
                        path,
                        "pooled input scope does not match its cohort scope",
                        f"/analysis_cohorts/{index}/inputs/{input_index}/scope",
                    )
                )


def _validate_participant_gate(
    manifest: Mapping[str, object],
    artifact_success: Mapping[str, bool],
    path: Path,
    issues: list[ValidationIssue],
) -> None:
    binding = _mapping(manifest.get("condition_matrix"))
    lock = _mapping(binding.get("pilot_lock"))
    if (
        lock.get("state") != "LOCKED"
        or not artifact_success.get("/condition_matrix/pilot_lock/locked_matrix", False)
        or not artifact_success.get(
            "/condition_matrix/pilot_lock/resource_availability_confirmation", False
        )
    ):
        issues.append(
            _issue(
                "STUDY.PILOT_LOCK",
                path,
                "participant capture requires a digest-valid locked pilot matrix and resource confirmation",
                "/condition_matrix/pilot_lock",
            )
        )
    decisions = _mapping(manifest.get("privacy_decisions"))
    for name in _PRIVACY_KEYS:
        decision = _mapping(decisions.get(name))
        record_pointer = f"/privacy_decisions/{name}/decision_record"
        if decision.get("state") != "ACCEPTED" or not artifact_success.get(record_pointer, False):
            issues.append(
                _issue(
                    "STUDY.PRIVACY_DECISION",
                    path,
                    "participant capture requires an accepted digest-valid privacy decision",
                    f"/privacy_decisions/{name}",
                )
            )


def _mapping(value: object) -> Mapping[str, object]:
    return value if isinstance(value, Mapping) else {}


def _mappings(value: object) -> list[Mapping[str, object]]:
    return [item for item in value if isinstance(item, Mapping)] if isinstance(value, list) else []


def _artifact_issue(path: Path, pointer: str, message: str) -> ValidationIssue:
    return _issue("STUDY.ARTIFACT_REFERENCE", path, message, pointer)


def _issue(code: str, path: Path, message: str, pointer: str | None = None) -> ValidationIssue:
    return ValidationIssue(
        code=code,
        artifact_path=str(path),
        artifact_kind="study_readiness_manifest",
        message=message,
        pointer=pointer,
    )


def _sorted(issues: list[ValidationIssue]) -> list[ValidationIssue]:
    return sorted(issues, key=lambda issue: (issue.pointer or "", issue.code, issue.message))
