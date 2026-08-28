from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from observation_contracts.cli import main
from observation_contracts.m05b_envelope import (
    REQUIRED_CONJUNCT_IDS,
    admit_run,
    canonical_sha256,
    derive_start_envelope,
    validate_m05b_document,
)


def _baseline() -> dict[str, object]:
    return {
        "device": {
            "device_class": "physical_device",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "device_build": "samsung/pa3q/pa3q:16/UP1A.231005.007/S938BXXU1/test:user/release-keys",
            "api_level": 36,
            "app_sha256": "a" * 64,
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
        "ambient": {"token": "ambient-01", "constancy": "constant"},
        "display": {
            "keep_awake": True,
            "brightness_mode": "manual",
            "brightness_raw": 128,
            "brightness_scale_min": 0,
            "brightness_scale_max": 255,
        },
    }


def _procedure() -> dict[str, object]:
    return {
        "procedure_version": "1.0.0",
        "idle_baseline_duration_ns": 300_000_000_000,
        "sampling_interval_ns": 1_000_000_000,
        "cooldown_max_duration_ns": 600_000_000_000,
        "cooldown_sampling_interval_ns": 1_000_000_000,
    }


def _envelope() -> dict[str, object]:
    return derive_start_envelope(_baseline(), _procedure(), "d" * 40, canonical_sha256(_order()))


def _order() -> dict[str, object]:
    return {
        "execution_order_schema_version": "1.0.0",
        "record_kind": "m05b_execution_order",
        "order_id": "order-01",
        "cadences_fps": [10, 15, 30],
        "producer_git_sha": "f" * 40,
    }


def _observed_start() -> dict[str, object]:
    return copy.deepcopy(_baseline())


def _admission(**changes: object) -> dict[str, object]:
    observed = _observed_start()
    cadence = 10
    for key, value in changes.items():
        if key == "cadence_fps":
            cadence = int(value)
            continue
        section, field = key.split(".", 1)
        observed[section] = {**observed[section], field: value}  # type: ignore[index]
    return admit_run(
        envelope=_envelope(),
        order=_order(),
        observed_start=observed,
        session_id="00000000-0000-4000-8000-000000000001",
        cadence_fps=cadence,
        attempt_sequence=1,
        predecessor_status="not_applicable",
        envelope_path="envelope.json",
        order_path="order.json",
    )


def test_literal_envelope_derivation_freezes_protocol_without_self_digest() -> None:
    envelope = _envelope()

    assert envelope["record_kind"] == "m05b_start_envelope"
    assert envelope["protocol"]["camera_fps"] == 30  # type: ignore[index]
    assert envelope["protocol"]["inference_cadences_fps"] == [10, 15, 30]  # type: ignore[index]
    assert envelope["protocol"]["duration_ns"] == 1_500_000_000_000  # type: ignore[index]
    assert envelope["protocol"]["overshoot_ns"] == 1_000_000_000  # type: ignore[index]
    assert "sha256" not in envelope
    assert "ambient_prose" not in envelope


def test_exact_canonical_digest_is_external_and_byte_deterministic() -> None:
    envelope = _envelope()
    expected = hashlib.sha256(
        json.dumps(envelope, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    ).hexdigest()

    assert canonical_sha256(envelope) == expected
    assert canonical_sha256(envelope) == canonical_sha256(copy.deepcopy(envelope))


def test_execution_order_requires_duplicate_free_exact_permutation() -> None:
    assert validate_m05b_document(_order(), "execution_order") == []

    for mutation in ([10, 10, 30], [15, 10, 30], [10, 15], [10, 15, 30, 60]):
        invalid = _order() | {"cadences_fps": mutation}
        assert validate_m05b_document(invalid, "execution_order")


def test_admission_derives_admitted_status_and_exact_required_conjunct_set() -> None:
    record = _admission()

    assert record["admission_status"] == "admitted"
    assert record["blocking_codes"] == []
    assert record["evaluated_conjunct_ids"] == list(REQUIRED_CONJUNCT_IDS)
    assert validate_m05b_document(record, "run_admission") == []
    assert "success" not in record
    assert "verdict" not in record
    assert "admitted" not in record


def test_required_conjunct_ids_are_the_released_literal_set() -> None:
    assert REQUIRED_CONJUNCT_IDS == (
        "DEVICE.CLASS",
        "DEVICE.MANUFACTURER",
        "DEVICE.MODEL",
        "DEVICE.BUILD",
        "DEVICE.API",
        "IDENTITY.APP_DIGEST",
        "IDENTITY.MODEL_DIGEST",
        "IDENTITY.RUNTIME",
        "IDENTITY.DELEGATE",
        "IDENTITY.PREPROCESSING",
        "IDENTITY.DEVICE_UNIT",
        "THERMAL.STATUS",
        "THERMAL.RANGE",
        "POWER.CHARGING",
        "POWER.SOURCE",
        "POWER.HOST_OVERRIDE",
        "CONNECTIVITY.HOST_WIFI_ADB",
        "CONNECTIVITY.APP_WIFI",
        "AMBIENT.TOKEN",
        "AMBIENT.CONSTANCY",
        "DISPLAY.KEEP_AWAKE",
        "DISPLAY.BRIGHTNESS_MODE",
        "DISPLAY.BRIGHTNESS_VALUE",
        "DISPLAY.BRIGHTNESS_RANGE",
        "PROTOCOL.CAMERA_FPS",
        "PROTOCOL.CADENCE_SET",
        "PROTOCOL.DURATION",
        "PROTOCOL.OVERSHOOT",
        "PROTOCOL.COOLDOWN",
        "ORDER.PERMITTED",
        "SESSION.IDENTITY",
        "SESSION.INPUT_CLASS",
        "SESSION.NO_PERSON",
    )


@pytest.mark.parametrize(
    ("field", "value", "code"),
    [
        ("device.device_class", "emulator", "DEVICE.CLASS_MISMATCH"),
        ("device.app_sha256", "0" * 64, "IDENTITY.APP_DIGEST_MISMATCH"),
        ("device.model_sha256", "0" * 64, "IDENTITY.MODEL_DIGEST_MISMATCH"),
        ("device.runtime_version", "other", "IDENTITY.RUNTIME_MISMATCH"),
        ("device.delegate", "GPU", "IDENTITY.DELEGATE_MISMATCH"),
        ("device.preprocessing_version", "2.0.0", "IDENTITY.PREPROCESSING_MISMATCH"),
        ("device.device_unit_digest", "0" * 64, "IDENTITY.DEVICE_UNIT_MISMATCH"),
        ("power.charging", True, "POWER.CHARGING"),
        ("power.power_source", "usb", "POWER.SOURCE_NOT_BATTERY"),
        ("power.host_battery_override_rejected", False, "POWER.HOST_OVERRIDE"),
        ("connectivity.host_adb_transport", "usb", "CONNECTIVITY.HOST_NOT_WIFI_ADB"),
        ("connectivity.app_transport", "cellular", "CONNECTIVITY.APP_NOT_WIFI"),
        ("ambient.constancy", "changed", "AMBIENT.NOT_CONSTANT"),
        ("display.brightness_mode", "automatic", "DISPLAY.BRIGHTNESS_NOT_MANUAL"),
        ("display.brightness_raw", None, "DISPLAY.BRIGHTNESS_UNAVAILABLE"),
        ("display.keep_awake", False, "DISPLAY.KEEP_AWAKE"),
    ],
)
def test_each_start_conjunct_is_load_bearing(field: str, value: object, code: str) -> None:
    record = _admission(**{field: value})
    assert record["admission_status"] == "blocked"
    assert code in record["blocking_codes"]


@pytest.mark.parametrize(
    ("field", "value", "code"),
    [
        ("device.manufacturer", "google", "DEVICE.MANUFACTURER_MISMATCH"),
        ("device.model", "SM-S918B", "DEVICE.MODEL_MISMATCH"),
        ("device.device_build", "other-build", "DEVICE.BUILD_MISMATCH"),
        ("device.api_level", 35, "DEVICE.API_MISMATCH"),
        ("thermal.initial_status", "light", "THERMAL.STATUS"),
        ("thermal.battery_temperature_min_c", 30.0, "THERMAL.RANGE"),
        ("thermal.battery_temperature_max_c", 31.0, "THERMAL.RANGE"),
        ("thermal.battery_temperature_range_c", 2.0, "THERMAL.RANGE"),
        ("ambient.token", "ambient-02", "AMBIENT.TOKEN"),
        ("display.brightness_raw", 129, "DISPLAY.BRIGHTNESS_VALUE"),
        ("display.brightness_scale_min", 1, "DISPLAY.BRIGHTNESS_RANGE"),
        ("display.brightness_scale_max", 254, "DISPLAY.BRIGHTNESS_RANGE"),
    ],
)
def test_remaining_start_conjunct_mutations_block(field: str, value: object, code: str) -> None:
    record = _admission(**{field: value})
    assert record["admission_status"] == "blocked"
    assert code in record["blocking_codes"]


def test_duration_overshoot_cooldown_camera_and_cadence_are_load_bearing() -> None:
    envelope = _envelope()
    for mutation in (
        {"protocol": {**envelope["protocol"], "camera_fps": 29}},  # type: ignore[index]
        {"protocol": {**envelope["protocol"], "duration_ns": 1}},  # type: ignore[index]
        {"protocol": {**envelope["protocol"], "overshoot_ns": 2_000_000_000}},  # type: ignore[index]
    ):
        assert validate_m05b_document(envelope | mutation, "start_envelope")

    assert _admission(cadence_fps=15)["admission_status"] == "blocked"
    blocked = admit_run(
        envelope=envelope,
        order=_order(),
        observed_start=_observed_start(),
        session_id="00000000-0000-4000-8000-000000000002",
        cadence_fps=15,
        attempt_sequence=2,
        predecessor_status="blocked",
        envelope_path="envelope.json",
        order_path="order.json",
    )
    assert "ATTEMPT.PREDECESSOR_INCOMPLETE" in blocked["blocking_codes"]


def test_session_attempt_input_and_no_person_mutations_block_and_remain_schema_valid() -> None:
    cases = [
        ("bad-session", 1, "not_applicable", "empty_scene", True, "SESSION.IDENTITY"),
        (
            "00000000-0000-4000-8000-000000000003",
            0,
            "not_applicable",
            "empty_scene",
            True,
            "ATTEMPT.SEQUENCE",
        ),
        (
            "00000000-0000-4000-8000-000000000004",
            1,
            "not_applicable",
            "person",
            True,
            "SESSION.INPUT_CLASS",
        ),
        (
            "00000000-0000-4000-8000-000000000005",
            1,
            "not_applicable",
            "empty_scene",
            False,
            "SESSION.NO_PERSON",
        ),
    ]
    for session_id, sequence, predecessor, input_class, no_person, code in cases:
        record = admit_run(
            envelope=_envelope(),
            order=_order(),
            observed_start=_observed_start(),
            session_id=session_id,
            cadence_fps=10,
            attempt_sequence=sequence,
            predecessor_status=predecessor,
            envelope_path="envelope.json",
            order_path="order.json",
            input_class=input_class,
            operator_attested_no_person=no_person,
        )
        assert record["admission_status"] == "blocked"
        assert code in record["blocking_codes"]
        if session_id == "00000000-0000-4000-8000-000000000005":
            assert validate_m05b_document(record, "run_admission") == []
        else:
            assert validate_m05b_document(record, "run_admission")


def test_exact_digest_and_order_binding_are_load_bearing() -> None:
    record = _admission()
    bad_envelope = admit_run(
        envelope=_envelope() | {"producer_git_sha": "0" * 40},
        order=_order(),
        observed_start=_observed_start(),
        session_id="00000000-0000-4000-8000-000000000001",
        cadence_fps=10,
        attempt_sequence=1,
        predecessor_status="not_applicable",
        envelope_path="envelope.json",
        order_path="order.json",
    )
    assert record["envelope_sha256"] != bad_envelope["envelope_sha256"]
    assert "envelope_sha256" in record
    assert "order_sha256" in record


def test_order_and_procedure_digest_bindings_block_tampering() -> None:
    order = _order()
    bad_order_binding = copy.deepcopy(_envelope())
    bad_order_binding["order_binding"] = {"path": "execution-order.json", "sha256": "0" * 64}
    blocked_order = admit_run(
        envelope=bad_order_binding,
        order=order,
        observed_start=_observed_start(),
        session_id="00000000-0000-4000-8000-000000000001",
        cadence_fps=10,
        attempt_sequence=1,
        predecessor_status="not_applicable",
        envelope_path="envelope.json",
        order_path="order.json",
    )
    assert "ORDER.DIGEST" in blocked_order["blocking_codes"]

    bad_procedure = copy.deepcopy(_envelope())
    bad_procedure["procedure"] = {
        **bad_procedure["procedure"],  # type: ignore[index]
        "procedure_sha256": "0" * 64,
    }
    blocked_procedure = admit_run(
        envelope=bad_procedure,
        order=order,
        observed_start=_observed_start(),
        session_id="00000000-0000-4000-8000-000000000001",
        cadence_fps=10,
        attempt_sequence=1,
        predecessor_status="not_applicable",
        envelope_path="envelope.json",
        order_path="order.json",
    )
    assert "PROTOCOL.PROCEDURE_DIGEST" in blocked_procedure["blocking_codes"]


@pytest.mark.parametrize(
    "forbidden",
    [
        {"adb_serial": "ABC123"},
        {"endpoint": "192.0.2.1:5555"},
        {"ssid": "lab-wifi"},
        {"bssid": "00:11:22:33:44:55"},
        {"android_id": "raw-id"},
        {"hardware_serial": "HW123"},
        {"observed_at": "2026-08-27T10:20:30.123456+05:30"},
        {"ambient_description": "quiet room"},
        {"participant_id": "p001"},
        {"identity_mapping": {"p001": "name"}},
    ],
)
def test_privacy_negative_fields_are_rejected(forbidden: dict[str, object]) -> None:
    assert validate_m05b_document(_envelope() | forbidden, "start_envelope")


def test_nested_privacy_forbidden_fields_are_rejected_before_admission() -> None:
    observed = _observed_start()
    observed["device"] = {**observed["device"], "android_id": "raw-id"}  # type: ignore[index]
    with pytest.raises(ValueError, match="privacy-forbidden"):
        admit_run(
            envelope=_envelope(),
            order=_order(),
            observed_start=observed,
            session_id="00000000-0000-4000-8000-000000000001",
            cadence_fps=10,
            attempt_sequence=1,
            predecessor_status="not_applicable",
            envelope_path="envelope.json",
            order_path="order.json",
        )


def test_forged_verdict_fields_are_rejected_even_when_true() -> None:
    with pytest.raises(ValueError, match="caller-declared"):
        admit_run(
            envelope=_envelope(),
            order=_order(),
            observed_start=_observed_start(),
            session_id="00000000-0000-4000-8000-000000000001",
            cadence_fps=10,
            attempt_sequence=1,
            predecessor_status="not_applicable",
            envelope_path="envelope.json",
            order_path="order.json",
            caller_fields={"success": True},
        )


def test_run_admission_rejects_forged_conjunct_set_or_status() -> None:
    record = _admission()
    assert validate_m05b_document(record | {"evaluated_conjunct_ids": ["FAKE.ID"]}, "run_admission")
    assert validate_m05b_document(record | {"admission_status": "blocked"}, "run_admission")


def test_run_admission_schema_allows_unavailable_load_bearing_digests() -> None:
    observed = _observed_start()
    observed["device"] = {
        **observed["device"],  # type: ignore[index]
        "app_sha256": None,
        "model_sha256": None,
        "device_unit_digest": None,
    }
    record = _admission()
    record["observed_start"] = observed
    assert validate_m05b_document(record, "run_admission") == []


def test_cli_validates_m05b_artifacts_and_returns_contract_exit_codes(tmp_path: Path) -> None:
    path = tmp_path / "execution-order.json"
    path.write_text(json.dumps(_order()), encoding="utf-8")
    assert main(["verify-m05b-envelope", "execution_order", str(path)]) == 0
    path.write_text(json.dumps(_order() | {"cadences_fps": [10, 10, 30]}), encoding="utf-8")
    assert main(["verify-m05b-envelope", "execution_order", str(path)]) == 2
