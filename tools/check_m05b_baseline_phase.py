#!/usr/bin/env python3
"""Validate the completed M0.5B baseline phase and return its one-time challenge."""

from __future__ import annotations

import re
import sys
from pathlib import Path

_CHALLENGE = re.compile(r"M05BBaselineChallenge=([0-9a-f]{64})")
_READY = "M05BBaselinePhaseStatus=awaiting_operator_attestation"
_SUITE = "class=com.calisthenics.diagnostic.probe.M05BEnvelopeDeviceGateTest"
_TEST = "test=observeBaseline"


def check_transcript(transcript: str) -> str:
    if transcript.count(_SUITE) != 1 or transcript.count(_TEST) != 1:
        raise ValueError(
            "baseline transcript does not identify exactly one expected test"
        )
    if transcript.count(_READY) != 1:
        raise ValueError(
            "baseline did not reach the post-observation attestation boundary"
        )
    if transcript.count("INSTRUMENTATION_CODE: -1") != 1:
        raise ValueError("baseline instrumentation did not finish successfully")
    if "AMBIENT.ATTESTATION_PRE_SUPPLIED" in transcript:
        raise ValueError("pre-supplied ambient attestation was rejected")
    challenges = _CHALLENGE.findall(transcript)
    if len(challenges) != 1:
        raise ValueError("baseline transcript must contain exactly one challenge")
    return str(challenges[0])


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    if len(arguments) != 1:
        print("usage: check_m05b_baseline_phase.py TRANSCRIPT", file=sys.stderr)
        return 2
    try:
        challenge = check_transcript(Path(arguments[0]).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(challenge)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
