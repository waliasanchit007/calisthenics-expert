"""Validation for M0.5B synthetic purge tombstones."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from typing import cast

from jsonschema import Draft202012Validator

from observation_contracts.errors import ValidationIssue
from observation_contracts.schema_loader import load_purge_tombstone_schema
from observation_contracts.validator_codes import require_stable_validator_code

_LOCATION_IDS = ("device_export", "device_staging", "device_transfer_staging")
_RESERVED_SESSION_ID = re.compile(r"^00000000-0000-4000-8000-[0-9a-f]{12}$")
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_WALL_TIME = re.compile(r"(?:^|_)(?:at|time|timestamp|date)(?:$|_)")
_MAX_JOURNAL_RECORDS = 64


def validate_tombstone_record(document: object, path: str = "<memory>") -> list[ValidationIssue]:
    """Return deterministic draft-local ``PURGE.*`` issues for one journal record."""
    schema_errors = sorted(
        Draft202012Validator(load_purge_tombstone_schema()).iter_errors(document),
        key=lambda error: (list(error.absolute_path), error.message),
    )
    if schema_errors:
        return [_issue(path, "PURGE.SCHEMA", "tombstone does not satisfy the draft schema")]
    assert isinstance(document, Mapping)
    record = cast(Mapping[str, object], document)
    issues: list[ValidationIssue] = []
    session_id = record.get("session_id")
    if not isinstance(session_id, str) or _RESERVED_SESSION_ID.fullmatch(session_id) is None:
        issues.append(
            _issue(
                path,
                "PURGE.INVALID_SESSION_ID",
                "session ID is outside the reserved synthetic namespace",
            )
        )

    locations = record.get("locations")
    location_records = locations if isinstance(locations, Sequence) else ()
    location_ids = tuple(
        str(location.get("location_id", "")) if isinstance(location, Mapping) else ""
        for location in location_records
    )
    if location_ids != _LOCATION_IDS:
        issues.append(
            _issue(
                path,
                "PURGE.LOCATION_SET",
                "locations must be the sorted registered device purge set",
            )
        )

    if record.get("state") != "INTENT_RECORDED":
        for location in location_records:
            if not isinstance(location, Mapping):
                continue
            inventory = location.get("pre_inventory")
            post_scan = location.get("post_scan")
            if (
                not isinstance(inventory, Mapping)
                or not isinstance(post_scan, Mapping)
                or not isinstance(inventory.get("entries_scanned"), int)
                or not isinstance(post_scan.get("entries_scanned"), int)
                or inventory["entries_scanned"] < 1
                or post_scan["entries_scanned"] < 1
            ):
                issues.append(
                    _issue(
                        path,
                        "PURGE.INVENTORY_VACUOUS",
                        "terminal records require fresh non-vacuous inventories",
                    )
                )
                break
    return issues


def validate_tombstone_journal(serialized: bytes, path: str = "<memory>") -> list[ValidationIssue]:
    """Validate bounded exact-byte hash chaining and monotonic operation sequence."""
    if not serialized or not serialized.endswith(b"\n"):
        return [_issue(path, "PURGE.TOMBSTONE_INTEGRITY", "journal must be LF-terminated")]
    lines = serialized.splitlines(keepends=True)
    if len(lines) > _MAX_JOURNAL_RECORDS:
        return [
            _issue(path, "PURGE.OPERATION_SEQUENCE", "journal exceeds the bounded operation count")
        ]

    previous_line: bytes | None = None
    issues: list[ValidationIssue] = []
    for index, line in enumerate(lines, start=1):
        try:
            value = json.loads(line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return [_issue(path, "PURGE.TOMBSTONE_INTEGRITY", "journal contains invalid JSON")]
        record_issues = validate_tombstone_record(value, path)
        if record_issues:
            return record_issues
        assert isinstance(value, Mapping)
        if value.get("operation_sequence") != index:
            return [
                _issue(
                    path,
                    "PURGE.OPERATION_SEQUENCE",
                    "operation sequence must increase by one from one",
                )
            ]
        expected_previous = (
            None if previous_line is None else hashlib.sha256(previous_line).hexdigest()
        )
        if value.get("previous_record_sha256") != expected_previous:
            return [
                _issue(
                    path,
                    "PURGE.TOMBSTONE_INTEGRITY",
                    "record does not bind the exact preceding journal bytes",
                )
            ]
        previous_line = line
    return issues


def validate_purge_record(document: object, path: str = "<memory>") -> list[ValidationIssue]:
    """Reject any non-sanitized public purge result before it can become evidence."""
    if not isinstance(document, Mapping):
        return [_issue(path, "PURGE.RECORD_SANITIZATION", "purge record must be an object")]
    allowed = {
        "purge_record_schema_version",
        "policy_status",
        "deletion_scope",
        "state",
        "operation_sequence",
        "location_results",
    }
    if set(document) != allowed or _contains_sensitive_value(document):
        return [
            _issue(
                path,
                "PURGE.RECORD_SANITIZATION",
                "purge record contains an identifier, path, wall time, or open value",
            )
        ]
    if document.get("purge_record_schema_version") != "1.0.0-draft":
        return [
            _issue(
                path,
                "PURGE.RECORD_SANITIZATION",
                "purge record schema version is not the draft literal",
            )
        ]
    if (
        document.get("policy_status") != "proposal"
        or document.get("deletion_scope") != "registered_device_roots_only"
    ):
        return [
            _issue(
                path, "PURGE.RECORD_SANITIZATION", "purge record must preserve proposal-only scope"
            )
        ]
    if document.get("state") not in {"INTENT_RECORDED", "PURGE_INCOMPLETE", "PURGE_COMPLETE"}:
        return [_issue(path, "PURGE.RECORD_SANITIZATION", "purge record state is not closed")]
    return []


def _contains_sensitive_value(value: object) -> bool:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if (
                not isinstance(key, str)
                or _WALL_TIME.search(key)
                or any(
                    token in key.lower()
                    for token in ("path", "session", "pseudonym", "participant", "identity")
                )
            ):
                return True
            if _contains_sensitive_value(child):
                return True
        return False
    if isinstance(value, list):
        return any(_contains_sensitive_value(child) for child in value)
    if isinstance(value, str):
        return (
            "/" in value
            or "\\" in value
            or _UUID.fullmatch(value) is not None
            or value.startswith("synthetic-m05-")
        )
    return False


def _issue(path: str, code: str, message: str) -> ValidationIssue:
    return ValidationIssue(
        code=require_stable_validator_code(code),
        artifact_path=path,
        artifact_kind="purge_tombstone_draft",
        message=message,
    )
