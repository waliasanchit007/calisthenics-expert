"""Pure M0.5B start-envelope derivation and per-run admission."""

from __future__ import annotations

import copy
import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from typing import Literal, cast

from jsonschema import Draft202012Validator

from observation_contracts.errors import ValidationIssue
from observation_contracts.schema_loader import (
    load_m05b_execution_order_schema,
    load_m05b_run_admission_schema,
    load_m05b_start_envelope_schema,
)

M05BKind = Literal["start_envelope", "execution_order", "run_admission"]

REQUIRED_CONJUNCT_IDS: tuple[str, ...] = (
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

_FORBIDDEN_KEYS = {
    "adb_serial",
    "endpoint",
    "ip_address",
    "ssid",
    "bssid",
    "android_id",
    "hardware_serial",
    "observed_at",
    "timestamp",
    "timestamp_ns",
    "ambient_description",
    "participant_id",
    "identity_mapping",
}
_SCHEMAS = {
    "start_envelope": load_m05b_start_envelope_schema,
    "execution_order": load_m05b_execution_order_schema,
    "run_admission": load_m05b_run_admission_schema,
}


def canonical_json_bytes(document: Mapping[str, object]) -> bytes:
    """Encode one artifact deterministically; the artifact itself carries no digest."""
    return json.dumps(document, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode(
        "utf-8"
    )


def canonical_sha256(document: Mapping[str, object]) -> str:
    """Return the digest of the exact canonical bytes used for binding."""
    return hashlib.sha256(canonical_json_bytes(document)).hexdigest()


def derive_start_envelope(
    baseline: Mapping[str, object],
    procedure: Mapping[str, object],
    producer_git_sha: str,
    order_sha256: str,
) -> dict[str, object]:
    """Derive a frozen envelope from observed idle values and fixed procedure inputs."""
    _reject_forbidden_keys(baseline)
    if len(producer_git_sha) != 40 or any(
        character not in "0123456789abcdef" for character in producer_git_sha
    ):
        raise ValueError("producer_git_sha must be lowercase 40-hex")
    if len(order_sha256) != 64 or any(
        character not in "0123456789abcdef" for character in order_sha256
    ):
        raise ValueError("order_sha256 must be lowercase 64-hex")
    procedure_copy = copy.deepcopy(dict(procedure))
    procedure_copy["procedure_sha256"] = canonical_sha256(procedure_copy)
    return {
        "start_envelope_schema_version": "1.0.0",
        "record_kind": "m05b_start_envelope",
        "envelope_id": f"m05b-envelope-{producer_git_sha[:12]}",
        "procedure": procedure_copy,
        "device": copy.deepcopy(_section(baseline, "device")),
        "power": copy.deepcopy(_section(baseline, "power")),
        "connectivity": copy.deepcopy(_section(baseline, "connectivity")),
        "thermal": copy.deepcopy(_section(baseline, "thermal")),
        "ambient": copy.deepcopy(_section(baseline, "ambient")),
        "display": copy.deepcopy(_section(baseline, "display")),
        "protocol": {
            "camera_fps": 30,
            "inference_cadences_fps": [10, 15, 30],
            "duration_ns": 1_500_000_000_000,
            "overshoot_ns": 1_000_000_000,
        },
        "order_binding": {"path": "execution-order.json", "sha256": order_sha256},
        "producer_git_sha": producer_git_sha,
    }


def validate_m05b_document(
    document: object, kind: M05BKind, path: str = "<memory>"
) -> list[ValidationIssue]:
    """Validate one M0.5B artifact structurally and apply its cross-field rules."""
    if kind not in _SCHEMAS:
        raise ValueError(f"unknown M0.5B artifact kind: {kind}")
    issues = _schema_issues(document, _SCHEMAS[kind](), kind, path)
    if issues:
        return issues
    if not isinstance(document, Mapping):
        return issues
    try:
        _reject_forbidden_keys(document)
    except ValueError as error:
        return [_issue(kind, path, "PRIVACY.FORBIDDEN_FIELD", "", str(error))]
    if kind == "execution_order" and document.get("cadences_fps") != [10, 15, 30]:
        issues.append(
            _issue(
                kind,
                path,
                "ORDER.PERMUTATION",
                "/cadences_fps",
                "execution order must be [10, 15, 30]",
            )
        )
    if kind == "run_admission":
        if tuple(document.get("evaluated_conjunct_ids", ())) != REQUIRED_CONJUNCT_IDS:
            issues.append(
                _issue(
                    kind,
                    path,
                    "ADMISSION.CONJUNCT_SET",
                    "/evaluated_conjunct_ids",
                    "evaluated conjunct IDs must equal the released exact set and order",
                )
            )
        blocking_codes = document.get("blocking_codes", ())
        expected_status = "admitted" if not blocking_codes else "blocked"
        if document.get("admission_status") != expected_status:
            issues.append(
                _issue(
                    kind,
                    path,
                    "ADMISSION.VERDICT_FORGED",
                    "/admission_status",
                    "admission status must be derived from blocking codes",
                )
            )
    if kind == "start_envelope":
        thermal = cast(Mapping[str, object], document["thermal"])
        minimum = cast(float, thermal["battery_temperature_min_c"])
        maximum = cast(float, thermal["battery_temperature_max_c"])
        observed_range = cast(float, thermal["battery_temperature_range_c"])
        if minimum > maximum or observed_range != maximum - minimum:
            issues.append(
                _issue(kind, path, "THERMAL.RANGE", "/thermal", "temperature range is inconsistent")
            )
        display = cast(Mapping[str, object], document["display"])
        display_min = cast(float, display["brightness_scale_min"])
        display_max = cast(float, display["brightness_scale_max"])
        if display_min > display_max:
            issues.append(
                _issue(
                    kind,
                    path,
                    "DISPLAY.BRIGHTNESS_RANGE",
                    "/display",
                    "brightness scale is inverted",
                )
            )
    return issues


def admit_run(
    *,
    envelope: Mapping[str, object],
    order: Mapping[str, object],
    observed_start: Mapping[str, object],
    session_id: str,
    cadence_fps: int,
    attempt_sequence: int,
    predecessor_status: str,
    envelope_path: str,
    order_path: str,
    caller_fields: Mapping[str, object] | None = None,
    input_class: str = "empty_scene",
    operator_attested_no_person: bool = True,
) -> dict[str, object]:
    """Derive a run admission record solely from observed values and bound artifacts."""
    if caller_fields and set(caller_fields) & {"success", "verdict", "admitted", "blocking_codes"}:
        raise ValueError("caller-declared verdict fields are not accepted")
    if validate_m05b_document(envelope, "start_envelope"):
        raise ValueError("start envelope is not valid")
    if validate_m05b_document(order, "execution_order"):
        raise ValueError("execution order is not valid")
    _reject_forbidden_keys(observed_start)

    blocking: list[str] = []
    _compare_section(
        envelope,
        observed_start,
        "device",
        blocking,
        {
            "device_class": "DEVICE.CLASS_MISMATCH",
            "manufacturer": "DEVICE.MANUFACTURER_MISMATCH",
            "model": "DEVICE.MODEL_MISMATCH",
            "device_build": "DEVICE.BUILD_MISMATCH",
            "api_level": "DEVICE.API_MISMATCH",
            "app_sha256": "IDENTITY.APP_DIGEST_MISMATCH",
            "model_sha256": "IDENTITY.MODEL_DIGEST_MISMATCH",
            "runtime_version": "IDENTITY.RUNTIME_MISMATCH",
            "delegate": "IDENTITY.DELEGATE_MISMATCH",
            "preprocessing_version": "IDENTITY.PREPROCESSING_MISMATCH",
            "device_unit_digest": "IDENTITY.DEVICE_UNIT_MISMATCH",
        },
    )
    _require_section_values(
        envelope,
        observed_start,
        "power",
        blocking,
        {
            "charging": "POWER.CHARGING",
            "power_source": "POWER.SOURCE_NOT_BATTERY",
            "host_battery_override_rejected": "POWER.HOST_OVERRIDE",
        },
    )
    _require_section_values(
        envelope,
        observed_start,
        "connectivity",
        blocking,
        {
            "host_adb_transport": "CONNECTIVITY.HOST_NOT_WIFI_ADB",
            "app_transport": "CONNECTIVITY.APP_NOT_WIFI",
            "network_identity_persisted": "CONNECTIVITY.NETWORK_IDENTITY",
        },
    )
    _compare_section(
        envelope,
        observed_start,
        "ambient",
        blocking,
        {"token": "AMBIENT.TOKEN", "constancy": "AMBIENT.NOT_CONSTANT"},
    )
    _compare_section(
        envelope,
        observed_start,
        "thermal",
        blocking,
        {
            "initial_status": "THERMAL.STATUS",
            "battery_temperature_min_c": "THERMAL.RANGE",
            "battery_temperature_max_c": "THERMAL.RANGE",
            "battery_temperature_range_c": "THERMAL.RANGE",
        },
    )
    _compare_section(
        envelope,
        observed_start,
        "display",
        blocking,
        {
            "keep_awake": "DISPLAY.KEEP_AWAKE",
            "brightness_mode": "DISPLAY.BRIGHTNESS_NOT_MANUAL",
            "brightness_raw": "DISPLAY.BRIGHTNESS_VALUE",
            "brightness_scale_min": "DISPLAY.BRIGHTNESS_RANGE",
            "brightness_scale_max": "DISPLAY.BRIGHTNESS_RANGE",
        },
    )
    _require_non_null(
        observed_start, "display", "brightness_raw", "DISPLAY.BRIGHTNESS_UNAVAILABLE", blocking
    )
    _require_non_null(
        observed_start,
        "display",
        "brightness_scale_min",
        "DISPLAY.BRIGHTNESS_UNAVAILABLE",
        blocking,
    )
    _require_non_null(
        observed_start,
        "display",
        "brightness_scale_max",
        "DISPLAY.BRIGHTNESS_UNAVAILABLE",
        blocking,
    )
    display = _section(observed_start, "display")
    raw = display.get("brightness_raw")
    lower = display.get("brightness_scale_min")
    upper = display.get("brightness_scale_max")
    if (
        isinstance(raw, (int, float))
        and isinstance(lower, (int, float))
        and isinstance(upper, (int, float))
        and not lower <= raw <= upper
    ):
        blocking.append("DISPLAY.BRIGHTNESS_RANGE")

    power = _section(observed_start, "power")
    if power.get("charging") is not False:
        blocking.append("POWER.CHARGING")
    if power.get("power_source") != "battery":
        blocking.append("POWER.SOURCE_NOT_BATTERY")
    if power.get("host_battery_override_rejected") is not True:
        blocking.append("POWER.HOST_OVERRIDE")
    connectivity = _section(observed_start, "connectivity")
    if connectivity.get("host_adb_transport") != "wifi":
        blocking.append("CONNECTIVITY.HOST_NOT_WIFI_ADB")
    if connectivity.get("app_transport") != "wifi":
        blocking.append("CONNECTIVITY.APP_NOT_WIFI")
    if connectivity.get("network_identity_persisted") is not False:
        blocking.append("CONNECTIVITY.NETWORK_IDENTITY")
    ambient = _section(observed_start, "ambient")
    if ambient.get("constancy") != "constant":
        blocking.append("AMBIENT.NOT_CONSTANT")

    protocol = _section(envelope, "protocol")
    if protocol != {
        "camera_fps": 30,
        "inference_cadences_fps": [10, 15, 30],
        "duration_ns": 1_500_000_000_000,
        "overshoot_ns": 1_000_000_000,
    }:
        blocking.extend(
            [
                "PROTOCOL.CAMERA_FPS",
                "PROTOCOL.CADENCE_SET",
                "PROTOCOL.DURATION",
                "PROTOCOL.OVERSHOOT",
            ]
        )
    procedure = _section(envelope, "procedure")
    procedure_without_digest = {
        key: value for key, value in procedure.items() if key != "procedure_sha256"
    }
    if procedure.get("procedure_sha256") != canonical_sha256(procedure_without_digest):
        blocking.append("PROTOCOL.PROCEDURE_DIGEST")
    procedure_durations = [
        procedure.get(key)
        for key in (
            "idle_baseline_duration_ns",
            "sampling_interval_ns",
            "cooldown_max_duration_ns",
            "cooldown_sampling_interval_ns",
        )
    ]
    if not all(isinstance(duration, int) and duration > 0 for duration in procedure_durations):
        blocking.append("PROTOCOL.COOLDOWN")
    order_cadences = order.get("cadences_fps", [])
    if not isinstance(order_cadences, list) or order_cadences != [10, 15, 30]:
        blocking.append("ORDER.PERMITTED")
    order_binding = _section(envelope, "order_binding")
    if order_binding.get("sha256") != canonical_sha256(order):
        blocking.append("ORDER.DIGEST")
    if cadence_fps not in (10, 15, 30):
        blocking.append("ORDER.PERMITTED")
    if cadence_fps != 10 and predecessor_status != "completed":
        blocking.append("ATTEMPT.PREDECESSOR_INCOMPLETE")
    if not re.fullmatch(r"00000000-0000-4000-8000-[0-9a-f]{12}", session_id):
        blocking.append("SESSION.IDENTITY")
    if attempt_sequence < 1:
        blocking.append("ATTEMPT.SEQUENCE")
    if input_class not in ("empty_scene", "displayed_synthetic_figure"):
        blocking.append("SESSION.INPUT_CLASS")
    if not operator_attested_no_person:
        blocking.append("SESSION.NO_PERSON")

    observed = copy.deepcopy(dict(observed_start))
    blocking = list(dict.fromkeys(blocking))
    return {
        "run_admission_schema_version": "1.0.0",
        "record_kind": "m05b_run_admission",
        "session_id": session_id,
        "session_kind": "live_conformance",
        "input_class": input_class,
        "operator_attested_no_person": operator_attested_no_person,
        "cadence_fps": cadence_fps,
        "attempt_sequence": attempt_sequence,
        "predecessor_status": predecessor_status,
        "envelope_path": envelope_path,
        "order_path": order_path,
        "envelope_sha256": canonical_sha256(envelope),
        "order_sha256": canonical_sha256(order),
        "evaluated_conjunct_ids": list(REQUIRED_CONJUNCT_IDS),
        "blocking_codes": blocking,
        "admission_status": "admitted" if not blocking else "blocked",
        "observed_start": observed,
    }


def _schema_issues(
    document: object, schema: Mapping[str, object], kind: str, path: str
) -> list[ValidationIssue]:
    validator = Draft202012Validator(schema)
    return [
        _issue(
            kind,
            path,
            "SCHEMA.INVALID",
            "/" + "/".join(str(part) for part in error.absolute_path),
            error.message,
        )
        for error in validator.iter_errors(document)
    ]


def _issue(kind: str, path: str, code: str, pointer: str, message: str) -> ValidationIssue:
    return ValidationIssue(
        code=code, artifact_path=path, artifact_kind=kind, pointer=pointer, message=message
    )


def _section(document: Mapping[str, object], name: str) -> dict[str, object]:
    section = document.get(name)
    if not isinstance(section, Mapping):
        return {}
    return dict(section)


def _compare_section(
    expected_document: Mapping[str, object],
    observed_document: Mapping[str, object],
    section: str,
    blocking: list[str],
    codes: Mapping[str, str],
) -> None:
    expected = _section(expected_document, section)
    observed = _section(observed_document, section)
    for field, code in codes.items():
        if (
            field not in observed
            or observed[field] is None
            or expected.get(field) != observed[field]
        ):
            blocking.append(code)


def _require_section_values(
    expected_document: Mapping[str, object],
    observed_document: Mapping[str, object],
    section: str,
    blocking: list[str],
    codes: Mapping[str, str],
) -> None:
    _compare_section(expected_document, observed_document, section, blocking, codes)


def _require_non_null(
    document: Mapping[str, object], section: str, field: str, code: str, blocking: list[str]
) -> None:
    if _section(document, section).get(field) is None:
        blocking.append(code)


def _reject_forbidden_keys(document: Mapping[str, object]) -> None:
    def visit(value: object) -> None:
        if isinstance(value, Mapping):
            for key, nested in value.items():
                if key in _FORBIDDEN_KEYS:
                    raise ValueError(f"privacy-forbidden field {key!r} is not accepted")
                visit(nested)
        elif isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
            for nested in value:
                visit(nested)

    visit(document)
