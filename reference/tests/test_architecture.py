from __future__ import annotations

import inspect
import re
import sys
from pathlib import Path

ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import check_live_capture_run as live_checker


def test_m05_operating_gate_is_wired_into_make_and_preserves_required_job() -> None:
    makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    workflow_text = "\n".join(
        path.read_text(encoding="utf-8") for path in (ROOT / ".github" / "workflows").glob("*.yml")
    )

    assert "verify-study-readiness" in makefile
    assert re.search(r"(?m)^verify-study-readiness:", makefile)
    assert re.search(r"(?m)^verify:\n(?:(?:\t| ).*\n)*\t.*verify-study-readiness", makefile)
    assert "verify-participant-capture-gate" in makefile
    assert "participant_gate_status=$$?" in makefile
    assert 'if [ "$$participant_gate_status" -ne 2 ]; then' in makefile
    assert "grep" not in makefile.split("verify-study-readiness:", 1)[1].split("\n\n", 1)[0]
    assert "adb" not in makefile.split("verify:", 1)[1]
    assert "curl" not in makefile.split("verify:", 1)[1]
    assert "fetch-model-asset" not in makefile.split("verify:", 1)[1]

    assert "docs/superpowers/specs/2026-08-24-m0-5-study-readiness-design.md" in agents
    assert "docs/superpowers/specs/2026-08-24-m0-5b-instrument-prerequisites-design.md" in agents
    assert "ca623d32837dec99d8229083c9159bfd140b1843" in agents
    assert "789af8eddc0cdb81a3e4ad96ad119dd28111fa7b" in agents
    assert "participant capture" in agents.lower()
    assert "participant data" in agents.lower()
    assert "M0.5B permits only the non-participant Android instrument work" in agents
    assert "M0.5C remains a separate approved slice" in agents
    assert "numeric reliability threshold" in agents
    assert "iOS-equivalence" in agents

    assert re.search(r"(?m)^\s+verify:\s*$", workflow_text)


def test_m05_operating_gate_preserves_collaboration_and_release_authority() -> None:
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")

    assert "## Collaboration" in agents
    assert "## Data and safety" in agents
    assert "release authority" in agents.lower()


def test_m05_admission_paths_are_exhaustive_and_use_one_shared_policy_helper() -> None:
    assert live_checker.M05_ADMISSION_PATHS == (
        "live_transcript",
        "recorded_live_ledger",
    )

    helper = inspect.getsource(live_checker._m05_admission_blocking_codes)
    transcript_path = inspect.getsource(live_checker._blocking_reasons)
    ledger_path = inspect.getsource(live_checker._recorded_blocking_reasons)
    transcript_entry = inspect.getsource(live_checker.check_transcript)
    ledger_entry = inspect.getsource(live_checker.verify_recorded_ledger)

    assert "session_kind" in helper
    assert "input_class" in helper
    assert "_m05_admission_blocking_codes(record)" in transcript_path
    assert "_m05_admission_blocking_codes(record)" in ledger_path
    assert "_blocking_reasons(" in transcript_entry
    assert "_recorded_blocking_reasons(record)" in ledger_entry
    assert "session_kind" not in transcript_path
    assert "input_class" not in transcript_path
    assert "session_kind" not in ledger_path
    assert "input_class" not in ledger_path


def test_m05b_session_runtime_has_no_unbounded_per_frame_or_terminal_collections() -> None:
    session_root = (
        ROOT
        / "apps"
        / "diagnostic-harness"
        / "shared"
        / "src"
        / "commonMain"
        / "kotlin"
        / "com"
        / "calisthenics"
        / "diagnostic"
        / "session"
    )
    live_composition = (
        ROOT
        / "apps"
        / "diagnostic-harness"
        / "androidApp"
        / "src"
        / "main"
        / "kotlin"
        / "com"
        / "calisthenics"
        / "diagnostic"
        / "composition"
        / "LiveCameraComposition.kt"
    ).read_text(encoding="utf-8")
    session_sources = "\n".join(
        path.read_text(encoding="utf-8") for path in session_root.glob("*.kt")
    )

    for removed_collection in (
        "terminalMap",
        "completedSubmissionIndices",
        "pendingSubmissions",
        "terminalSubmissionIndices",
        "admittedCallbackTimestampsNs",
    ):
        assert removed_collection not in session_sources
        assert removed_collection not in live_composition

    assert not re.search(r"mutableListOf\s*<\s*RawPoseFrame", session_sources)
    assert "LiveCaptureCadenceAccumulator" in live_composition
