"""Extract the sanitized process-kill record and print it for the human recording step.

The Makefile target is the only layer that witnessed the instrumentation process actually die, so
it is the only layer entitled to set `process_crash_confirmed`. The device test emits the record
with that field false; this script flips it only because the target already refused to continue
unless the first transcript contained "Process crashed".

Like the five-run checker, this never writes the ledger. A human copies the record and points the
ledger at it.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

_RECORD = re.compile(r"ProcessKillEvidence=(\{.*\})")
_HARNESS_GIT_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")


class ProcessKillRecordError(RuntimeError):
    """Raised when the verifying transcript cannot be trusted."""


def extract_record(transcript: str) -> dict:
    matches = _RECORD.findall(transcript)
    if not matches:
        raise ProcessKillRecordError(
            "verifying transcript contains no ProcessKillEvidence record"
        )
    if len(matches) > 1:
        raise ProcessKillRecordError(
            f"verifying transcript contains {len(matches)} records; refusing to guess which is this run"
        )
    if "OK (1 test)" not in transcript:
        raise ProcessKillRecordError(
            "verifying transcript does not report exactly one passing test"
        )
    record = json.loads(matches[0])
    harness_git_sha = record.get("harness_git_sha")
    if (
        not isinstance(harness_git_sha, str)
        or _HARNESS_GIT_SHA_PATTERN.fullmatch(harness_git_sha) is None
    ):
        raise ProcessKillRecordError(
            "ProcessKillEvidence harness_git_sha must be a lowercase 40-hex commit SHA"
        )
    return record


def _satisfies_process_kill_gate(record: dict) -> bool:
    observation = record["observation"]
    return bool(
        observation["process_crash_confirmed"]
        and observation["export_root_corruption_count"] == 0
        and observation["staging_residue_count"] == 1
        and observation["staging_residue_classification"] == "CrashResidue"
        and record["environment"]["device_class"] == "physical_device"
    )


def verify_recorded_ledger(root: Path, ledger_relative: str) -> dict:
    ledger = json.loads((root / ledger_relative).read_text(encoding="utf-8"))
    status = ledger.get("status")
    if status not in {"pending", "recorded"}:
        raise ProcessKillRecordError(
            "process-kill ledger status must be pending or recorded"
        )
    if status == "pending":
        if not ledger.get("missing_precondition"):
            raise ProcessKillRecordError(
                "pending process-kill ledger must name its missing physical precondition"
            )
        return ledger

    evidence_file = ledger.get("evidence_file")
    if not isinstance(evidence_file, str) or not evidence_file:
        raise ProcessKillRecordError(
            "recorded process-kill ledger must reference evidence_file"
        )
    evidence_path = root / evidence_file
    if not evidence_path.is_file():
        raise ProcessKillRecordError(
            f"recorded process-kill evidence file is missing: {evidence_file}"
        )
    payload = evidence_path.read_bytes()
    actual_sha = hashlib.sha256(payload).hexdigest()
    if ledger.get("evidence_sha256") != actual_sha:
        raise ProcessKillRecordError(
            "recorded process-kill evidence_sha256 does not match evidence file sha256"
        )
    record = json.loads(payload)
    harness_git_sha = record.get("harness_git_sha")
    if (
        not isinstance(harness_git_sha, str)
        or _HARNESS_GIT_SHA_PATTERN.fullmatch(harness_git_sha) is None
    ):
        raise ProcessKillRecordError(
            "recorded process-kill evidence harness_git_sha must be a lowercase 40-hex commit SHA"
        )
    if record.get(
        "satisfies_s25_process_kill_gate"
    ) is not _satisfies_process_kill_gate(record):
        raise ProcessKillRecordError(
            "recorded process-kill evidence verdict disagrees with recomputed observations"
        )
    return ledger


def main(argv: list[str]) -> int:
    transcript = Path(argv[1]).read_text(encoding="utf-8")
    try:
        record = extract_record(transcript)
    except ProcessKillRecordError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    # The target already proved the crash before reaching here; see this module's docstring.
    record["observation"]["process_crash_confirmed"] = True
    record["satisfies_s25_process_kill_gate"] = _satisfies_process_kill_gate(record)
    if not record["satisfies_s25_process_kill_gate"]:
        print(
            "error: observed evidence does not satisfy the process-kill gate",
            file=sys.stderr,
        )
        print(json.dumps(record, indent=2, sort_keys=True), file=sys.stderr)
        return 1

    print("process-kill gate verified: no partial entry, crash residue classified")
    print("  satisfies_s25_process_kill_gate=true")
    print(
        "human step required: copy the record below into docs/evidence/device-probes/, "
        'then set the ledger\'s status to "recorded" pointing at it'
    )
    print(json.dumps(record, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
