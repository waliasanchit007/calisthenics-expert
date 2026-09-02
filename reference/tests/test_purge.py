from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

from observation_contracts.purge import (
    validate_purge_record,
    validate_tombstone_journal,
    validate_tombstone_record,
)

ROOT = Path(__file__).parents[2]
SCHEMA_PATH = ROOT / "contracts" / "tooling" / "purge_tombstone.v1.schema.json"
SESSION_ID = "00000000-0000-4000-8000-000000000001"
OPERATION_ID = "10000000-0000-4000-8000-000000000001"
REGISTRY_SHA256 = "a" * 64


def _inventory(*, owned_entries: int = 1) -> dict[str, int]:
    return {
        "entries_scanned": 3,
        "owned_entries": owned_entries,
        "owned_files": owned_entries,
        "owned_bytes": 17 if owned_entries else 0,
    }


def _location(location_id: str) -> dict[str, object]:
    return {
        "location_id": location_id,
        "pre_inventory": _inventory(),
        "terminal_result": "deleted",
        "post_scan": {"entries_scanned": 2, "owned_remaining": 0},
    }


def _tombstone(*, sequence: int = 1, previous: str | None = None) -> dict[str, object]:
    return {
        "purge_tombstone_schema_version": "1.0.0-draft",
        "policy_status": "proposal",
        "deletion_scope": "registered_device_roots_only",
        "session_id": SESSION_ID,
        "pseudonym_class": "synthetic_m05",
        "operation_id": OPERATION_ID,
        "operation_sequence": sequence,
        "state": "PURGE_COMPLETE",
        "registry_version": "1.0.0",
        "registry_sha256": REGISTRY_SHA256,
        "locations": [
            _location("device_export"),
            _location("device_staging"),
            _location("device_transfer_staging"),
        ],
        "previous_record_sha256": previous,
        "producer_git_sha": "b" * 40,
    }


def _line(record: dict[str, object]) -> bytes:
    return json.dumps(record, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n"


def test_draft_schema_is_structurally_valid_and_tombstone_semantics_are_draft_local() -> None:
    record = _tombstone()

    assert SCHEMA_PATH.is_file()
    assert validate_tombstone_record(record) == []


def test_invalid_tombstone_structure_emits_the_stable_schema_code() -> None:
    assert [issue.code for issue in validate_tombstone_record({})] == ["PURGE.SCHEMA"]


def test_tombstone_rejects_non_reserved_id_wrong_registered_locations_and_vacuous_inventory() -> (
    None
):
    non_reserved = _tombstone()
    non_reserved["session_id"] = "10000000-0000-4000-8000-000000000001"
    wrong_locations = _tombstone()
    wrong_locations["locations"] = [_location("device_export")]
    vacuous_inventory = _tombstone()
    locations = vacuous_inventory["locations"]
    assert isinstance(locations, list)
    first = locations[0]
    assert isinstance(first, dict)
    pre_inventory = first["pre_inventory"]
    assert isinstance(pre_inventory, dict)
    pre_inventory["entries_scanned"] = 0

    assert [issue.code for issue in validate_tombstone_record(non_reserved)] == [
        "PURGE.INVALID_SESSION_ID"
    ]
    assert [issue.code for issue in validate_tombstone_record(wrong_locations)] == [
        "PURGE.LOCATION_SET"
    ]
    assert [issue.code for issue in validate_tombstone_record(vacuous_inventory)] == [
        "PURGE.INVENTORY_VACUOUS"
    ]


def test_append_only_journal_requires_monotonic_sequence_and_exact_byte_hash_link() -> None:
    first = _tombstone(sequence=1)
    first_line = _line(first)
    second = _tombstone(
        sequence=2,
        previous=hashlib.sha256(first_line).hexdigest(),
    )
    second["state"] = "PURGE_INCOMPLETE"

    assert validate_tombstone_journal(first_line + _line(second)) == []

    wrong_sequence = copy.deepcopy(second)
    wrong_sequence["operation_sequence"] = 3
    assert [
        issue.code for issue in validate_tombstone_journal(first_line + _line(wrong_sequence))
    ] == ["PURGE.OPERATION_SEQUENCE"]

    wrong_link = copy.deepcopy(second)
    wrong_link["previous_record_sha256"] = "c" * 64
    assert [issue.code for issue in validate_tombstone_journal(first_line + _line(wrong_link))] == [
        "PURGE.TOMBSTONE_INTEGRITY"
    ]


def test_sanitized_purge_record_contains_only_closed_outcomes_and_counts() -> None:
    record = {
        "purge_record_schema_version": "1.0.0-draft",
        "policy_status": "proposal",
        "deletion_scope": "registered_device_roots_only",
        "state": "PURGE_COMPLETE",
        "operation_sequence": 2,
        "location_results": [
            {
                "location_id": "device_export",
                "terminal_result": "deleted",
                "pre_inventory": _inventory(),
                "post_scan": {"entries_scanned": 2, "owned_remaining": 0},
            }
        ],
    }

    assert validate_purge_record(record) == []

    unsafe = copy.deepcopy(record)
    unsafe["session_id"] = SESSION_ID
    assert [issue.code for issue in validate_purge_record(unsafe)] == ["PURGE.RECORD_SANITIZATION"]
