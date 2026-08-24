from __future__ import annotations

import inspect
import sys
from pathlib import Path

ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import check_live_capture_run as live_checker


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
