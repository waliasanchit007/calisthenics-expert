from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "tools/check_m05b_baseline_phase.py"
CHALLENGE = "a" * 64


def _run(tmp_path: Path, transcript: str) -> subprocess.CompletedProcess[str]:
    path = tmp_path / "baseline.txt"
    path.write_text(transcript, encoding="utf-8")
    return subprocess.run(
        [sys.executable, str(CHECKER), str(path)],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def _valid() -> str:
    return (
        "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.probe.M05BEnvelopeDeviceGateTest\n"
        "INSTRUMENTATION_STATUS: test=observeBaseline\n"
        "INSTRUMENTATION_STATUS: M05BBaselinePhaseStatus=awaiting_operator_attestation\n"
        f"INSTRUMENTATION_STATUS: M05BBaselineChallenge={CHALLENGE}\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


def test_accepts_one_successful_ready_phase_and_prints_only_the_challenge(tmp_path: Path) -> None:
    result = _run(tmp_path, _valid())

    assert result.returncode == 0
    assert result.stdout.strip() == CHALLENGE


def test_rejects_missing_duplicate_failed_or_presupplied_phase(tmp_path: Path) -> None:
    mutations = (
        _valid().replace("M05BBaselineChallenge=" + CHALLENGE + "\n", ""),
        _valid() + f"INSTRUMENTATION_STATUS: M05BBaselineChallenge={CHALLENGE}\n",
        _valid().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
        _valid().replace(
            "M05BBaselinePhaseStatus=awaiting_operator_attestation",
            "M05BBaselinePhaseStatus=blocked\n"
            "INSTRUMENTATION_STATUS: M05BBaselineBlockingCode=AMBIENT.ATTESTATION_PRE_SUPPLIED",
        ),
    )

    for transcript in mutations:
        result = _run(tmp_path, transcript)
        assert result.returncode != 0
