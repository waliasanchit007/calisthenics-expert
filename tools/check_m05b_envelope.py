#!/usr/bin/env python3
"""Check the sanitized M0.5B envelope device-gate transcript.

The command is intentionally read-only. It prints no ledger edits and treats a trusted blocking
observation as a distinct non-zero result from an untrusted transcript.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

EXIT_OK = 0
EXIT_UNTRUSTED = 1
EXIT_USAGE = 2
EXIT_BLOCKING = 3

RECORD_KIND = "m05b_start_envelope_device_gate_observation"
LEDGER_PATH = "docs/evidence/m05b-envelope/s25-ultra-m05b-envelope-ledger.json"
_SUITE_MARKER = "class=com.calisthenics.diagnostic.probe.M05BEnvelopeDeviceGateTest"
_RECORD_PATTERN = re.compile(r"M05BStartEnvelopeEvidence=(?P<json>\{.*\})")
_FORBIDDEN_KEYS = {
    "adb_serial",
    "endpoint",
    "ip_address",
    "ssid",
    "bssid",
    "android_id",
    "hardware_serial",
    "observed_at",
    "timestamp",
    "timestamp_ns",
    "ambient_description",
    "participant_id",
    "identity_mapping",
}


def check_transcript(transcript: str) -> dict[str, Any]:
    """Validate one exact instrumentation transcript or exit with its gate status."""
    if _SUITE_MARKER not in transcript or "OK (1 test)" not in transcript:
        raise SystemExit(EXIT_UNTRUSTED)
    records = _RECORD_PATTERN.findall(transcript)
    if len(records) != 1:
        raise SystemExit(EXIT_UNTRUSTED)
    try:
        record = json.loads(records[0])
    except json.JSONDecodeError:
        raise SystemExit(EXIT_UNTRUSTED) from None
    if not isinstance(record, dict):
        raise SystemExit(EXIT_UNTRUSTED)
    _validate_record_shape(record)
    computed_admitted = not record["blocking_codes"]
    if record["satisfies_s25_m05b_admission"] is not computed_admitted:
        raise SystemExit(EXIT_UNTRUSTED)
    if not computed_admitted:
        raise SystemExit(EXIT_BLOCKING)
    return record


def verify_recorded_ledger(
    root: Path, ledger_relative: str = LEDGER_PATH
) -> dict[str, Any]:
    """Validate a pending/recorded ledger and rehash its exact referenced evidence bytes."""
    ledger_path = _resolve_relative(root, ledger_relative)
    try:
        ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read ledger: {error}") from error
    if not isinstance(ledger, dict) or ledger.get("ledger_schema_version") != "1.0.0":
        raise ValueError("invalid M0.5B envelope ledger")
    status = ledger.get("status")
    if status == "pending":
        if (
            not isinstance(ledger.get("missing_precondition"), str)
            or not ledger["missing_precondition"]
        ):
            raise ValueError(
                "pending ledger must name the missing physical precondition"
            )
        return ledger
    if status != "recorded":
        raise ValueError("envelope ledger status must be pending or recorded")
    evidence_relative = ledger.get("evidence_file")
    if not isinstance(evidence_relative, str):
        raise TypeError("recorded ledger must reference evidence_file")
    evidence_path = _resolve_relative(root, evidence_relative)
    try:
        actual_digest = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
    except OSError as error:
        raise ValueError(f"cannot read referenced evidence: {error}") from error
    if ledger.get("evidence_sha256") != actual_digest:
        raise ValueError(
            "recorded evidence digest does not match the exact referenced file bytes"
        )
    try:
        record = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"referenced evidence is not JSON: {error}") from error
    if not isinstance(record, dict):
        raise TypeError("referenced evidence is not an object")
    _validate_record_shape(record)
    expected_status = "admitted" if not record["blocking_codes"] else "blocked"
    if ledger.get("admission_status") != expected_status:
        raise ValueError(
            "ledger admission_status disagrees with derived evidence status"
        )
    return ledger


def _validate_record_shape(record: dict[str, Any]) -> None:
    if (
        record.get("artifact_schema_version") != "1.0.0"
        or record.get("record_kind") != RECORD_KIND
    ):
        raise SystemExit(EXIT_UNTRUSTED)
    if (
        record.get("sanitized") is not True
        or record.get("non_participant_observation") is not True
    ):
        raise SystemExit(EXIT_UNTRUSTED)
    if not isinstance(record.get("blocking_codes"), list) or any(
        not isinstance(code, str) for code in record["blocking_codes"]
    ):
        raise SystemExit(EXIT_UNTRUSTED)
    if not isinstance(record.get("satisfies_s25_m05b_admission"), bool):
        raise SystemExit(EXIT_UNTRUSTED)
    if _contains_forbidden(record):
        raise SystemExit(EXIT_UNTRUSTED)


def _contains_forbidden(value: object) -> bool:
    if isinstance(value, dict):
        return any(
            key in _FORBIDDEN_KEYS or _contains_forbidden(nested)
            for key, nested in value.items()
        )
    if isinstance(value, list):
        return any(_contains_forbidden(item) for item in value)
    return False


def _resolve_relative(root: Path, relative: str) -> Path:
    candidate = (root / relative).resolve()
    if Path(relative).is_absolute() or not candidate.is_relative_to(root.resolve()):
        raise ValueError("ledger/evidence path must remain beneath repository root")
    return candidate


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print(f"usage: {Path(sys.argv[0]).name} <transcript-file|->", file=sys.stderr)
        return EXIT_USAGE
    try:
        transcript = (
            sys.stdin.read()
            if args[0] == "-"
            else Path(args[0]).read_text(encoding="utf-8")
        )
        record = check_transcript(transcript)
    except (OSError, SystemExit) as error:
        if isinstance(error, SystemExit):
            return int(error.code)
        print(f"error: {error}", file=sys.stderr)
        return EXIT_UNTRUSTED
    print(json.dumps(record, sort_keys=True, separators=(",", ":")))
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
