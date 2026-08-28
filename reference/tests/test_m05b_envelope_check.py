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

from observation_contracts.m05b_envelope import canonical_json_bytes

APP_SHA = "a" * 64
GIT_SHA = "d" * 40


def _artifacts() -> tuple[dict[str, object], dict[str, object]]:
    order: dict[str, object] = {
        "execution_order_schema_version": "1.0.0",
        "record_kind": "m05b_execution_order",
        "order_id": "m05b-s25-order",
        "cadences_fps": [10, 15, 30],
        "producer_git_sha": GIT_SHA,
    }
    order_digest = hashlib.sha256(canonical_json_bytes(order)).hexdigest()
    envelope: dict[str, object] = {
        "start_envelope_schema_version": "1.0.0",
        "record_kind": "m05b_start_envelope",
        "envelope_id": "m05b-envelope-01",
        "procedure": {
            "procedure_version": "1.0.0",
            "procedure_sha256": "e" * 64,
            "idle_baseline_duration_ns": 300_000_000_000,
            "sampling_interval_ns": 1_000_000_000,
            "cooldown_max_duration_ns": 600_000_000_000,
            "cooldown_sampling_interval_ns": 1_000_000_000,
        },
        "device": {
            "device_class": "physical_device",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "device_build": "build",
            "api_level": 36,
            "app_sha256": APP_SHA,
            "model_sha256": "b" * 64,
            "runtime_version": "mediapipe-tasks-vision-1.0.0",
            "delegate": "CPU",
            "preprocessing_version": "1.0.0",
            "device_unit_digest": "c" * 64,
        },
        "power": {
            "charging": False,
            "power_source": "battery",
            "host_battery_override_rejected": True,
            "power_save_mode": False,
            "baseline_battery_observation": {"unit": "percent", "value": 80.0},
        },
        "connectivity": {
            "host_adb_transport": "wifi",
            "app_transport": "wifi",
            "network_identity_persisted": False,
        },
        "thermal": {
            "initial_status": "none",
            "battery_temperature_min_c": 29.0,
            "battery_temperature_max_c": 30.0,
            "battery_temperature_range_c": 1.0,
        },
        "ambient": {
            "token": "ambient-01",
            "constancy": "constant",
            "constancy_source": "operator_attestation_end_of_baseline",
        },
        "display": {
            "keep_awake": True,
            "brightness_mode": "manual",
            "brightness_raw": 0.5,
            "brightness_scale_min": 0.0,
            "brightness_scale_max": 1.0,
        },
        "protocol": {
            "camera_fps": 30,
            "inference_cadences_fps": [10, 15, 30],
            "duration_ns": 1_500_000_000_000,
            "overshoot_ns": 1_000_000_000,
            "latency_bucket_upper_bounds_ns": [
                1_000_000,
                2_000_000,
                4_000_000,
                8_000_000,
                16_000_000,
                32_000_000,
                64_000_000,
                128_000_000,
                256_000_000,
                512_000_000,
                1_024_000_000,
                2_048_000_000,
                4_096_000_000,
            ],
            "latency_percentile_ranks": [50, 95, 99],
        },
        "order_binding": {"path": "execution-order.json", "sha256": order_digest},
        "producer_git_sha": GIT_SHA,
    }
    return envelope, order


def _record(**overrides: object) -> dict[str, object]:
    envelope, order = _artifacts()
    envelope_bytes = canonical_json_bytes(envelope)
    order_bytes = canonical_json_bytes(order)
    record: dict[str, object] = {
        "artifact_schema_version": "1.0.0",
        "record_kind": "m05b_start_envelope_device_gate_observation",
        "sanitized": True,
        "non_participant_observation": True,
        "envelope_path": "envelope.json",
        "envelope_sha256": hashlib.sha256(envelope_bytes).hexdigest(),
        "envelope_json": envelope_bytes.decode(),
        "order_path": "execution-order.json",
        "order_sha256": hashlib.sha256(order_bytes).hexdigest(),
        "execution_order_json": order_bytes.decode(),
        "observed_start": {
            "device": dict(envelope["device"]),
            "power": {
                "charging": False,
                "power_source": "battery",
                "host_battery_override_rejected": True,
                "power_save_mode": False,
                "battery_observation": {"unit": "percent", "value": 79.0},
                "device_idle_mode": False,
            },
            "connectivity": dict(envelope["connectivity"]),
            "thermal": {"initial_status": "none", "battery_temperature_c": 29.5},
            "ambient": {
                "token": "ambient-01",
                "constancy": "constant",
                "constancy_operator_supplied": True,
            },
            "display": dict(envelope["display"]),
        },
        "blocking_codes": [],
        "satisfies_s25_m05b_admission": True,
    }
    record.update(overrides)
    return record


def _transcript(record: dict[str, object], status: str = "admitted") -> str:
    return (
        "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.probe.M05BEnvelopeDeviceGateTest\n"
        f"M05BStartEnvelopeEvidence={json.dumps(record, sort_keys=True)}\n"
        f"INSTRUMENTATION_STATUS: M05BEnvelopeGateStatus={status}\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


def test_checker_accepts_one_trusted_admitted_record() -> None:
    record = check_transcript(_transcript(_record()), APP_SHA, GIT_SHA)
    assert record["satisfies_s25_m05b_admission"] is True


def test_blocking_observation_is_trusted_but_nonzero() -> None:
    with pytest.raises(SystemExit) as error:
        check_transcript(
            _transcript(
                _record(blocking_codes=["POWER.CHARGING"], satisfies_s25_m05b_admission=False),
                status="blocked",
            ),
            APP_SHA,
            GIT_SHA,
        )
    assert error.value.code == EXIT_BLOCKING


def test_checker_rejects_forged_verdict_and_private_fields() -> None:
    for record in (
        _record(blocking_codes=["POWER.CHARGING"], satisfies_s25_m05b_admission=True),
        _record(adb_serial="ABC123"),
    ):
        with pytest.raises(SystemExit) as error:
            check_transcript(_transcript(record), APP_SHA, GIT_SHA)
        assert error.value.code == EXIT_UNTRUSTED


def test_checker_rejects_empty_unrelated_or_status_mismatch() -> None:
    for transcript in ("OK (0 tests)\n", _transcript(_record(), status="blocked")):
        with pytest.raises(SystemExit) as error:
            check_transcript(transcript, APP_SHA, GIT_SHA)
        assert error.value.code == EXIT_UNTRUSTED


def test_checker_rehashes_and_schema_validates_exact_embedded_artifacts() -> None:
    record = _record()
    record["envelope_json"] = str(record["envelope_json"]).replace(
        '"api_level":36', '"api_level":35'
    )
    with pytest.raises(SystemExit) as error:
        check_transcript(_transcript(record), APP_SHA, GIT_SHA)
    assert error.value.code == EXIT_UNTRUSTED


def test_checker_schema_validates_nested_observed_start_wire_shape() -> None:
    record = _record()
    observed = dict(record["observed_start"])  # type: ignore[arg-type]
    observed["thermal"] = {
        "initial_status": "none",
        "battery_temperature_min_c": 29.0,
        "battery_temperature_max_c": 30.0,
    }
    record["observed_start"] = observed

    with pytest.raises(SystemExit) as error:
        check_transcript(_transcript(record), APP_SHA, GIT_SHA)
    assert error.value.code == EXIT_UNTRUSTED

    record = _record()
    record["envelope_sha256"] = hashlib.sha256(str(record["envelope_json"]).encode()).hexdigest()
    with pytest.raises(SystemExit) as error:
        check_transcript(_transcript(record), "0" * 64, GIT_SHA)
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
