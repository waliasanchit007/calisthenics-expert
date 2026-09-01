"""Host authority checks for the M0.5B Task 5b physical purge rehearsal."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location(
    "check_m05b_physical_purge", ROOT / "tools" / "check_m05b_physical_purge.py"
)
assert _SPEC is not None and _SPEC.loader is not None
checker = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(checker)

SESSION_ID = "00000000-0000-4000-8000-000000000001"


def _location(location_id: str) -> dict[str, object]:
    return {
        "location_id": location_id,
        "terminal_result": "deleted",
        "pre_inventory": {
            "entries_scanned": 3,
            "owned_entries": 1,
            "owned_files": 1,
            "owned_bytes": 17,
        },
        "post_scan": {"entries_scanned": 1, "owned_remaining": 0},
    }


def _record(**overrides: object) -> dict[str, object]:
    record: dict[str, object] = {
        "artifact_schema_version": "1.0.0-draft",
        "record_kind": "m05b_physical_purge_device_observation",
        "sanitized": True,
        "non_participant_observation": True,
        "contains_landmark_coordinates": False,
        "policy_status": "proposal",
        "session_id": SESSION_ID,
        "harness_git_sha": "a" * 40,
        "app_apk_sha256": "b" * 64,
        "test_apk_sha256": "c" * 64,
        "environment": {
            "device_class": "physical_device",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "marketing_name": "Galaxy S25 Ultra",
            "api_level": 36,
        },
        "artifacts_before_purge": {
            "manifest_present": True,
            "video_present": True,
            "pose_stream_present": True,
            "synchronization_evidence_present": True,
            "complete_video_session": True,
        },
        "purge": {
            "state": "PURGE_COMPLETE",
            "operation_sequence": 3,
            "location_results": [
                _location("device_export"),
                _location("device_staging"),
                _location("device_transfer_staging"),
            ],
        },
        "tombstone": {
            "sha256": "d" * 64,
            "byte_length": 1024,
            "readmission_refusal_code": "PURGE.SESSION_TOMBSTONED",
        },
        "blocking_reasons": [],
        "admissible": True,
    }
    record.update(overrides)
    return record


def test_valid_device_record_is_admissible() -> None:
    assert (
        checker.verify_device_record(
            _record(), expected_app_sha256="b" * 64, expected_test_sha256="c" * 64
        )
        == []
    )


@pytest.mark.parametrize(
    ("mutation", "reason"),
    [
        ({"admissible": False}, "device_record_blocking"),
        ({"session_id": "not-reserved"}, "invalid_reserved_session_id"),
        ({"non_participant_observation": False}, "participant_scope_not_refused"),
        ({"contains_landmark_coordinates": True}, "landmark_coordinates_present"),
    ],
)
def test_closed_device_record_failures_are_blocking(
    mutation: dict[str, object], reason: str
) -> None:
    assert reason in checker.verify_device_record(
        _record(**mutation), expected_app_sha256="b" * 64, expected_test_sha256="c" * 64
    )


def test_pose_only_or_vacuous_root_cannot_pass() -> None:
    pose_only = _record()
    pose_only["artifacts_before_purge"]["video_present"] = False
    vacuous = _record()
    vacuous["purge"]["location_results"][1]["pre_inventory"]["owned_entries"] = 0

    assert "complete_video_session_missing" in checker.verify_device_record(
        pose_only, expected_app_sha256="b" * 64, expected_test_sha256="c" * 64
    )
    assert "vacuous_pre_inventory" in checker.verify_device_record(
        vacuous, expected_app_sha256="b" * 64, expected_test_sha256="c" * 64
    )


def test_independent_post_scan_rejects_every_owned_name_shape() -> None:
    listings = {
        "device_export": [SESSION_ID],
        "device_staging": [f"{SESSION_ID}.7.staging"],
        "device_transfer_staging": [f"{SESSION_ID}.transfer.bundle"],
    }

    assert checker.verify_independent_post_scan(SESSION_ID, listings) == [
        "host_post_scan_residue:device_export",
        "host_post_scan_residue:device_staging",
        "host_post_scan_residue:device_transfer_staging",
    ]
    assert (
        checker.verify_independent_post_scan(
            SESSION_ID,
            {"device_export": [], "device_staging": [], "device_transfer_staging": []},
        )
        == []
    )


def test_exact_terminal_tombstone_digest_and_state_are_required() -> None:
    terminal = {
        "state": "PURGE_COMPLETE",
        "session_id": SESSION_ID,
        "operation_sequence": 1,
    }
    serialized = json.dumps(terminal).encode() + b"\n"
    digest = hashlib.sha256(serialized).hexdigest()

    assert (
        checker.verify_terminal_tombstone_bytes(
            serialized,
            SESSION_ID,
            expected_sha256=digest,
            expected_byte_length=len(serialized),
            semantic_validator=lambda _value: [],
        )
        == []
    )
    incomplete = serialized.replace(b"PURGE_COMPLETE", b"PURGE_INCOMPLETE")
    assert "tombstone_not_complete" in checker.verify_terminal_tombstone_bytes(
        incomplete,
        SESSION_ID,
        expected_sha256=hashlib.sha256(incomplete).hexdigest(),
        expected_byte_length=len(incomplete),
        semantic_validator=lambda _value: [],
    )


def test_transcript_requires_one_record_and_successful_instrumentation() -> None:
    payload = json.dumps(_record(), separators=(",", ":"))
    transcript = f"INSTRUMENTATION_STATUS: M05BPhysicalPurgeEvidence={payload}\n"

    assert checker.parse_device_record(transcript, 0)["session_id"] == SESSION_ID
    with pytest.raises(checker.PhysicalPurgeCheckError):
        checker.parse_device_record(transcript, 1)
    with pytest.raises(checker.PhysicalPurgeCheckError):
        checker.parse_device_record(transcript + transcript, 0)
