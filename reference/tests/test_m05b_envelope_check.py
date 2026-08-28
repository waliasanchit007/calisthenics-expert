from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
from tools.check_m05b_envelope import (
    EXIT_BLOCKING,
    EXIT_OK,
    EXIT_UNTRUSTED,
    check_transcript,
    verify_recorded_ledger,
)


def _record(**overrides: object) -> dict[str, object]:
    record: dict[str, object] = {
        "artifact_schema_version": "1.0.0",
        "record_kind": "m05b_start_envelope_device_gate_observation",
        "sanitized": True,
        "non_participant_observation": True,
        "blocking_codes": [],
        "satisfies_s25_m05b_admission": True,
    }
    record.update(overrides)
    return record


def _transcript(record: dict[str, object], passing: bool = True) -> str:
    result = "OK (1 test)" if passing else "FAILURES!!!"
    return (
        "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.probe.M05BEnvelopeDeviceGateTest\n"
        f"M05BStartEnvelopeEvidence={json.dumps(record, sort_keys=True)}\n"
        f"{result}\n"
    )


def test_checker_accepts_one_trusted_admitted_record() -> None:
    record = check_transcript(_transcript(_record()))
    assert record["satisfies_s25_m05b_admission"] is True


def test_blocking_observation_is_trusted_but_nonzero() -> None:
    with pytest.raises(SystemExit) as error:
        check_transcript(
            _transcript(
                _record(blocking_codes=["POWER.CHARGING"], satisfies_s25_m05b_admission=False)
            )
        )
    assert error.value.code == EXIT_BLOCKING


def test_checker_rejects_forged_verdict_and_private_fields() -> None:
    for record in (
        _record(blocking_codes=["POWER.CHARGING"], satisfies_s25_m05b_admission=True),
        _record(adb_serial="ABC123"),
    ):
        with pytest.raises(SystemExit) as error:
            check_transcript(_transcript(record))
        assert error.value.code == EXIT_UNTRUSTED


def test_checker_rejects_empty_or_unrelated_transcript() -> None:
    for transcript in ("OK (0 tests)\n", _transcript(_record(), passing=False)):
        with pytest.raises(SystemExit) as error:
            check_transcript(transcript)
        assert error.value.code == EXIT_UNTRUSTED


def test_pending_ledger_is_valid_and_recorded_ledger_rehashes_exact_file(tmp_path: Path) -> None:
    ledger = {
        "ledger_schema_version": "1.0.0",
        "status": "pending",
        "missing_precondition": "authorized physical S25",
    }
    path = tmp_path / "ledger.json"
    path.write_text(json.dumps(ledger), encoding="utf-8")
    assert verify_recorded_ledger(tmp_path, "ledger.json")["status"] == "pending"

    evidence = tmp_path / "evidence.json"
    evidence.write_text(json.dumps(_record()), encoding="utf-8")
    recorded = ledger | {
        "status": "recorded",
        "evidence_file": "evidence.json",
        "evidence_sha256": hashlib.sha256(evidence.read_bytes()).hexdigest(),
        "admission_status": "admitted",
    }
    path.write_text(json.dumps(recorded), encoding="utf-8")
    assert verify_recorded_ledger(tmp_path, "ledger.json")["status"] == "recorded"

    evidence.write_text(json.dumps(_record(satisfies_s25_m05b_admission=False)), encoding="utf-8")
    with pytest.raises(ValueError, match="digest"):
        verify_recorded_ledger(tmp_path, "ledger.json")


def test_checker_returns_documented_exit_constants() -> None:
    assert EXIT_OK == 0
    assert EXIT_BLOCKING == 3
    assert EXIT_UNTRUSTED == 1
