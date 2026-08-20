"""Hermetic checks for the real-replay-export transcript wrapper (Task 5).

`make verify-harness-replay-export` pipes an `am instrument` transcript to
`tools/check_real_replay_export.py` and trusts its exit status. `am instrument` exits 0 even when
every test fails, so the wrapper's own parsing is exactly the thing that must not be fooled by a
mistyped class name, an unrelated suite, or a vacuous zero-test run.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from check_real_replay_export import (
    RealReplayExportCheckError,
    extract_record,
    require_exact_suite,
)

PASSING_TRANSCRIPT = (
    "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.composition.RealReplayCompositionExportTest\n"
    "INSTRUMENTATION_STATUS: RealReplayExport=session_id=abc-123 export_path=/data/user/0/pkg/files/exports/abc-123\n"
    "OK (1 test)\n"
)


def test_require_exact_suite_accepts_a_genuine_single_passing_run() -> None:
    require_exact_suite(PASSING_TRANSCRIPT)


def test_require_exact_suite_rejects_a_transcript_missing_the_suite_marker() -> None:
    with pytest.raises(RealReplayExportCheckError):
        require_exact_suite("OK (1 test)\n")


def test_require_exact_suite_rejects_a_vacuous_zero_test_run() -> None:
    transcript = (
        "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.composition.RealReplayCompositionExportTest\n"
        "OK (0 tests)\n"
    )
    with pytest.raises(RealReplayExportCheckError):
        require_exact_suite(transcript)


def test_extract_record_reads_the_session_id_and_export_path() -> None:
    session_id, export_path = extract_record(PASSING_TRANSCRIPT)
    assert session_id == "abc-123"
    assert export_path == "/data/user/0/pkg/files/exports/abc-123"


def test_extract_record_rejects_a_transcript_with_no_record() -> None:
    with pytest.raises(RealReplayExportCheckError):
        extract_record("OK (1 test)\n")


def test_extract_record_rejects_a_transcript_with_more_than_one_record() -> None:
    doubled = PASSING_TRANSCRIPT + PASSING_TRANSCRIPT
    with pytest.raises(RealReplayExportCheckError):
        extract_record(doubled)
