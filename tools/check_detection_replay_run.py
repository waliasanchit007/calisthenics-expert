#!/usr/bin/env python3
"""Decide whether an `am instrument` transcript is a real, complete detection-tier replay run.

`make verify-harness-detection-replay` pipes the transcript here and uses this script's exit
status as its verdict. `am instrument` exits 0 even when every test fails, and a generic `OK (`
match accepts `OK (0 tests)` from a mistyped class name, a run of some unrelated suite, and a run
that observed nothing at all. So the verdict is: the exact expected suite, every one of its tests
passing, one sanitized observation record, and that record bound to the model SHA-256, the Tasks
Vision pin, and the fixture frame digests this repository actually pins.

Usage: check_detection_replay_run.py <transcript-file>   ("-" reads stdin)
"""

from __future__ import annotations

import sys
from pathlib import Path

from observation_contracts.detection_evidence import (
    DetectionEvidenceError,
    verify_committed_evidence,
    verify_instrumented_run,
)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {Path(argv[0]).name} <transcript-file>", file=sys.stderr)
        return 2

    root = Path(__file__).resolve().parents[1]
    transcript = sys.stdin.read() if argv[1] == "-" else Path(argv[1]).read_text(encoding="utf-8")

    try:
        verify_committed_evidence(root)
        record = verify_instrumented_run(root, transcript)
    except DetectionEvidenceError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    environment = record["environment"]
    print(
        "detection-tier replay run verified: suite complete, observation record bound to the "
        "pinned model and fixture"
    )
    print(
        f"  observed on {environment['device_class']} {environment['device_identity']} "
        f"api={environment['api_level']} abi={environment['abi']}"
    )
    print(f"  model_sha256={record['provenance']['model_sha256']}")
    print(f"  tasks_vision_version={record['provenance']['tasks_vision_version']}")
    print("  satisfies_s25_device_gate=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
