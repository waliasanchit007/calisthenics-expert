"""Extract the sanitized process-kill record and print it for the human recording step.

The Makefile target is the only layer that witnessed the instrumentation process actually die, so
it is the only layer entitled to set `process_crash_confirmed`. The device test emits the record
with that field false; this script flips it only because the target already refused to continue
unless the first transcript contained "Process crashed".

Like the five-run checker, this never writes the ledger. A human copies the record and points the
ledger at it.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

_RECORD = re.compile(r"ProcessKillEvidence=(\{.*\})")


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
    return json.loads(matches[0])


def main(argv: list[str]) -> int:
    transcript = Path(argv[1]).read_text(encoding="utf-8")
    try:
        record = extract_record(transcript)
    except ProcessKillRecordError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    # The target already proved the crash before reaching here; see this module's docstring.
    record["observation"]["process_crash_confirmed"] = True
    observation = record["observation"]
    record["satisfies_s25_process_kill_gate"] = bool(
        observation["process_crash_confirmed"]
        and observation["export_root_corruption_count"] == 0
        and observation["staging_residue_count"] == 1
        and observation["staging_residue_classification"] == "CrashResidue"
        and record["environment"]["device_class"] == "physical_device"
    )
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
