"""Hermetic checks for the five-run S25 replay device-gate transcript wrapper (Task 5).

`satisfies_gate` is the host-side re-derivation of the same gate `renderFiveRunReplayEvidence`
(Kotlin, `shared/commonMain`) computes: exactly five runs, all published and CLI-validated, every
run agreeing on submitted indices/backend timestamps/canonical digests, and the observing device a
physical Samsung Galaxy S25 Ultra. It must never be satisfiable by an emulator or by a partial or
inconsistent set of runs, no matter what the on-device record otherwise claims.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from check_five_run_replay import (
    FiveRunReplayCheckError,
    extract_record,
    require_exact_suite,
    satisfies_gate,
)

SUITE_MARKER = "class=com.calisthenics.diagnostic.composition.FiveRunReplayDeviceGateTest"


def _run(index: int, *, published: bool = True, validated: bool = True, indices=(0, 1)) -> dict:
    return {
        "run_index": index,
        "session_id": f"session-{index}",
        "published": published,
        "submitted_indices": list(indices),
        "backend_timestamps_ms": [0, 33],
        "canonical_input_sha256": ["c0", "c1"],
        "manifest_sha256": f"m{index}",
        "frames_sha256": f"n{index}",
        "validated_by_reference_cli": validated,
    }


def _record(
    *, device_class="physical_device", marketing_name="Galaxy S25 Ultra", runs=None
) -> dict:
    return {
        "runs_completed": 5,
        "runs": runs if runs is not None else [_run(i) for i in range(1, 6)],
        "environment": {"device_class": device_class, "marketing_name": marketing_name},
    }


def _transcript(record_json: str) -> str:
    return f"INSTRUMENTATION_STATUS: class=...FiveRunReplayDeviceGateTest\n{SUITE_MARKER}\nOK (1 test)\nINSTRUMENTATION_STATUS: FiveRunReplayEvidence={record_json}\n"


def test_require_exact_suite_accepts_a_genuine_single_passing_run() -> None:
    require_exact_suite(f"{SUITE_MARKER}\nOK (1 test)\n")


def test_require_exact_suite_rejects_a_vacuous_zero_test_run() -> None:
    with pytest.raises(FiveRunReplayCheckError):
        require_exact_suite(f"{SUITE_MARKER}\nOK (0 tests)\n")


def test_extract_record_requires_exactly_five_runs() -> None:
    import json

    transcript = _transcript(json.dumps(_record(runs=[_run(i) for i in range(1, 5)])))
    with pytest.raises(FiveRunReplayCheckError):
        extract_record(transcript)


def test_extract_record_reads_a_well_formed_five_run_record() -> None:
    import json

    transcript = _transcript(json.dumps(_record()))
    record = extract_record(transcript)
    assert len(record["runs"]) == 5


def test_satisfies_gate_is_true_for_five_consistent_published_validated_runs_on_an_s25() -> None:
    assert satisfies_gate(_record()) is True


def test_satisfies_gate_is_false_for_an_emulator() -> None:
    assert satisfies_gate(_record(device_class="emulator")) is False


def test_satisfies_gate_is_false_for_a_non_s25_physical_device() -> None:
    assert satisfies_gate(_record(marketing_name="Galaxy A15")) is False


def test_satisfies_gate_is_false_when_a_run_is_not_published() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[2] = _run(3, published=False)
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_a_run_is_not_validated() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[4] = _run(5, validated=False)
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_runs_diverge() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[1] = _run(2, indices=(0,))
    assert satisfies_gate(_record(runs=runs)) is False
