#!/usr/bin/env python3
"""Independently admit one synthetic physical-purge rehearsal.

The instrumentation record is an observation, never the verdict. This checker independently
enumerates the three registry-owned purge roots through ``adb exec-out run-as``, pulls the exact
tombstone bytes, validates their append-only semantics, and exits non-zero on any mismatch. It
prints a sanitized candidate record but never edits the evidence ledger.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

from observation_contracts.purge import validate_tombstone_journal

_RECORD = re.compile(
    r"^INSTRUMENTATION_STATUS: M05BPhysicalPurgeEvidence=(?P<json>\{.*\})$",
    re.MULTILINE,
)
_SESSION_ID = re.compile(r"^00000000-0000-4000-8000-[0-9a-f]{12}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_LOCATION_IDS = (
    "device_export",
    "device_staging",
    "device_transfer_staging",
)
_ROOTS = {
    "device_export": "files/diagnostic-harness/exports",
    "device_staging": "files/diagnostic-harness/staging",
    "device_transfer_staging": "files/diagnostic-harness/transfer-staging",
}

EXIT_OK = 0
EXIT_BLOCKED = 1
EXIT_USAGE = 2


class PhysicalPurgeCheckError(Exception):
    """The invocation or evidence transport is untrustworthy."""


def parse_device_record(transcript: str, subprocess_exit_code: int) -> dict[str, Any]:
    if subprocess_exit_code != 0:
        raise PhysicalPurgeCheckError(
            f"instrumentation exited {subprocess_exit_code}; transcript is untrustworthy"
        )
    matches = list(_RECORD.finditer(transcript))
    if len(matches) != 1:
        raise PhysicalPurgeCheckError(
            f"expected exactly one physical purge record, found {len(matches)}"
        )
    try:
        parsed = json.loads(matches[0].group("json"))
    except json.JSONDecodeError as error:
        raise PhysicalPurgeCheckError(
            "physical purge record is not valid JSON"
        ) from error
    if not isinstance(parsed, dict):
        raise PhysicalPurgeCheckError("physical purge record root is not an object")
    return parsed


def verify_device_record(
    record: Mapping[str, Any],
    *,
    expected_app_sha256: str,
    expected_test_sha256: str,
) -> list[str]:
    reasons: list[str] = list(_string_list(record.get("blocking_reasons")))
    if record.get("record_kind") != "m05b_physical_purge_device_observation":
        reasons.append("unexpected_record_kind")
    if record.get("sanitized") is not True:
        reasons.append("device_record_not_sanitized")
    if record.get("non_participant_observation") is not True:
        reasons.append("participant_scope_not_refused")
    if record.get("contains_landmark_coordinates") is not False:
        reasons.append("landmark_coordinates_present")
    if record.get("policy_status") != "proposal":
        reasons.append("policy_scope_not_proposal")
    if record.get("admissible") is not True:
        reasons.append("device_record_blocking")

    session_id = record.get("session_id")
    if not isinstance(session_id, str) or _SESSION_ID.fullmatch(session_id) is None:
        reasons.append("invalid_reserved_session_id")
    if record.get("app_apk_sha256") != expected_app_sha256 or not _valid_sha256(
        expected_app_sha256
    ):
        reasons.append("app_apk_identity_mismatch")
    if record.get("test_apk_sha256") != expected_test_sha256 or not _valid_sha256(
        expected_test_sha256
    ):
        reasons.append("test_apk_identity_mismatch")

    environment = _mapping(record.get("environment"))
    if not (
        environment.get("device_class") == "physical_device"
        and str(environment.get("manufacturer", "")).lower() == "samsung"
        and str(environment.get("model", "")).upper().startswith("SM-S938")
        and environment.get("api_level") == 36
    ):
        reasons.append("unauthorized_device_target")

    artifacts = _mapping(record.get("artifacts_before_purge"))
    required_artifacts = (
        "manifest_present",
        "video_present",
        "pose_stream_present",
        "synchronization_evidence_present",
        "complete_video_session",
    )
    if any(artifacts.get(field) is not True for field in required_artifacts):
        reasons.append("complete_video_session_missing")

    purge = _mapping(record.get("purge"))
    if purge.get("state") != "PURGE_COMPLETE":
        reasons.append("purge_not_complete")
    locations = purge.get("location_results")
    location_records = locations if isinstance(locations, list) else []
    ids = tuple(_mapping(location).get("location_id") for location in location_records)
    if ids != _LOCATION_IDS:
        reasons.append("purge_location_set_mismatch")
    for location in location_records:
        entry = _mapping(location)
        before = _mapping(entry.get("pre_inventory"))
        after = _mapping(entry.get("post_scan"))
        if not _positive_int(before.get("entries_scanned")) or not _positive_int(
            before.get("owned_entries")
        ):
            reasons.append("vacuous_pre_inventory")
        if (
            not _positive_int(after.get("entries_scanned"))
            or after.get("owned_remaining") != 0
        ):
            reasons.append("device_post_scan_residue")

    tombstone = _mapping(record.get("tombstone"))
    if not _valid_sha256(tombstone.get("sha256")) or not _positive_int(
        tombstone.get("byte_length")
    ):
        reasons.append("invalid_tombstone_measurement")
    if tombstone.get("readmission_refusal_code") != "PURGE.SESSION_TOMBSTONED":
        reasons.append("readmission_fence_not_proven")
    return sorted(set(reasons))


def verify_independent_post_scan(
    session_id: str, listings: Mapping[str, Sequence[str]]
) -> list[str]:
    if _SESSION_ID.fullmatch(session_id) is None:
        return ["invalid_reserved_session_id"]
    reasons: list[str] = []
    expected = set(_LOCATION_IDS)
    if set(listings) != expected:
        return ["host_post_scan_location_set_mismatch"]
    patterns = {
        "device_export": re.compile(rf"^{re.escape(session_id)}$"),
        "device_staging": re.compile(rf"^{re.escape(session_id)}\.[0-9]+\.staging$"),
        "device_transfer_staging": re.compile(
            rf"^{re.escape(session_id)}\.transfer\.bundle$"
        ),
    }
    for location_id in _LOCATION_IDS:
        if any(patterns[location_id].fullmatch(name) for name in listings[location_id]):
            reasons.append(f"host_post_scan_residue:{location_id}")
    return reasons


def verify_terminal_tombstone_bytes(
    serialized: bytes,
    session_id: str,
    *,
    expected_sha256: str,
    expected_byte_length: int,
    semantic_validator: Callable[
        [bytes], Sequence[object]
    ] = validate_tombstone_journal,
) -> list[str]:
    reasons: list[str] = []
    if len(serialized) != expected_byte_length:
        reasons.append("tombstone_byte_length_mismatch")
    if (
        not _valid_sha256(expected_sha256)
        or hashlib.sha256(serialized).hexdigest() != expected_sha256
    ):
        reasons.append("tombstone_digest_mismatch")
    if semantic_validator(serialized):
        reasons.append("tombstone_semantics_invalid")
    try:
        lines = serialized.splitlines()
        terminal = json.loads(lines[-1]) if lines else None
    except (IndexError, UnicodeDecodeError, json.JSONDecodeError):
        terminal = None
    if not isinstance(terminal, dict) or terminal.get("state") != "PURGE_COMPLETE":
        reasons.append("tombstone_not_complete")
    elif terminal.get("session_id") != session_id:
        reasons.append("tombstone_session_mismatch")
    return sorted(set(reasons))


def read_root_listings(serial: str, package: str) -> dict[str, list[str]]:
    listings: dict[str, list[str]] = {}
    for location_id, root in _ROOTS.items():
        result = subprocess.run(
            ["adb", "-s", serial, "exec-out", "run-as", package, "ls", "-1", root],
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise PhysicalPurgeCheckError(
                f"independent post-scan failed for {location_id}"
            )
        try:
            listings[location_id] = [
                line for line in result.stdout.decode("utf-8").splitlines() if line
            ]
        except UnicodeDecodeError as error:
            raise PhysicalPurgeCheckError("post-scan listing is not UTF-8") from error
    return listings


def pull_tombstone(serial: str, package: str, session_id: str) -> bytes:
    if _SESSION_ID.fullmatch(session_id) is None:
        raise PhysicalPurgeCheckError("refusing tombstone pull for invalid session ID")
    remote = f"files/diagnostic-harness/tombstones/{session_id}.purge-tombstone.json"
    result = subprocess.run(
        ["adb", "-s", serial, "exec-out", "run-as", package, "cat", remote],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0 or not result.stdout:
        raise PhysicalPurgeCheckError("durable tombstone could not be pulled")
    return result.stdout


def verify_instrumented_run(
    *,
    serial: str,
    package: str,
    transcript: str,
    subprocess_exit_code: int,
    expected_app_sha256: str,
    expected_test_sha256: str,
) -> tuple[dict[str, Any], list[str]]:
    record = parse_device_record(transcript, subprocess_exit_code)
    reasons = verify_device_record(
        record,
        expected_app_sha256=expected_app_sha256,
        expected_test_sha256=expected_test_sha256,
    )
    session_id = record.get("session_id")
    if not isinstance(session_id, str) or _SESSION_ID.fullmatch(session_id) is None:
        return record, sorted(set(reasons + ["invalid_reserved_session_id"]))
    listings = read_root_listings(serial, package)
    reasons.extend(verify_independent_post_scan(session_id, listings))
    serialized = pull_tombstone(serial, package, session_id)
    tombstone = _mapping(record.get("tombstone"))
    reasons.extend(
        verify_terminal_tombstone_bytes(
            serialized,
            session_id,
            expected_sha256=str(tombstone.get("sha256", "")),
            expected_byte_length=_int_or_zero(tombstone.get("byte_length")),
        )
    )
    return record, sorted(set(reasons))


def verify_recorded_ledger(root: Path, ledger: Mapping[str, Any]) -> list[str]:
    """Reopen and admit the exact immutable host evidence named by a recorded ledger."""
    reasons: list[str] = []
    if ledger.get("status") != "recorded":
        return ["physical_purge_ledger_not_recorded"]
    if ledger.get("required_device") != "samsung_galaxy_s25_ultra":
        reasons.append("physical_purge_device_scope_mismatch")
    relative = ledger.get("evidence_file")
    if not isinstance(relative, str) or not relative.startswith(
        "docs/evidence/m05b-purge/"
    ):
        return sorted(set(reasons + ["physical_purge_evidence_path_invalid"]))
    candidate = Path(relative)
    root_resolved = root.resolve()
    evidence_path = (root / candidate).resolve()
    if candidate.is_absolute() or root_resolved not in evidence_path.parents:
        return sorted(set(reasons + ["physical_purge_evidence_path_invalid"]))
    try:
        serialized = evidence_path.read_bytes()
        evidence = json.loads(serialized)
    except (OSError, json.JSONDecodeError):
        return sorted(set(reasons + ["physical_purge_evidence_unreadable"]))
    if hashlib.sha256(serialized).hexdigest() != ledger.get("evidence_sha256"):
        reasons.append("physical_purge_evidence_digest_mismatch")
    if not isinstance(evidence, dict):
        reasons.append("physical_purge_evidence_invalid")
        return sorted(set(reasons))
    if evidence.get("record_kind") != "m05b_physical_purge_host_evidence":
        reasons.append("physical_purge_evidence_invalid")
    if (
        evidence.get("sanitized") is not True
        or evidence.get("non_participant_observation") is not True
    ):
        reasons.append("physical_purge_evidence_invalid")
    if evidence.get("independent_host_post_scan_empty") is not True:
        reasons.append("physical_purge_host_post_scan_not_empty")
    if evidence.get("tombstone_independently_validated") is not True:
        reasons.append("physical_purge_tombstone_not_validated")
    if evidence.get("blocking_reasons") != [] or evidence.get("admitted") is not True:
        reasons.append("physical_purge_evidence_blocking")
    return sorted(set(reasons))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--transcript", type=Path, required=True)
    parser.add_argument("--subprocess-exit-code", type=int, required=True)
    parser.add_argument("--app-apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        transcript = args.transcript.read_text(encoding="utf-8")
        record, reasons = verify_instrumented_run(
            serial=args.serial,
            package=args.package,
            transcript=transcript,
            subprocess_exit_code=args.subprocess_exit_code,
            expected_app_sha256=args.app_apk_sha256,
            expected_test_sha256=args.test_apk_sha256,
        )
    except (OSError, PhysicalPurgeCheckError) as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_USAGE
    host_record = {
        "artifact_schema_version": "1.0.0-draft",
        "record_kind": "m05b_physical_purge_host_evidence",
        "sanitized": True,
        "non_participant_observation": True,
        "device_observation": record,
        "independent_host_post_scan_empty": not any(
            reason.startswith("host_post_scan_") for reason in reasons
        ),
        "tombstone_independently_validated": not any(
            reason.startswith("tombstone_") for reason in reasons
        ),
        "blocking_reasons": reasons,
        "admitted": not reasons,
    }
    print(json.dumps(host_record, separators=(",", ":"), sort_keys=True))
    return EXIT_OK if not reasons else EXIT_BLOCKED


def _mapping(value: object) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _string_list(value: object) -> list[str]:
    return (
        [item for item in value if isinstance(item, str)]
        if isinstance(value, list)
        else []
    )


def _valid_sha256(value: object) -> bool:
    return isinstance(value, str) and _SHA256.fullmatch(value) is not None


def _positive_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _int_or_zero(value: object) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


if __name__ == "__main__":
    raise SystemExit(main())
