from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

import observation_contracts.m05b_sustained as sustained
from observation_contracts.cli import main
from observation_contracts.m05b_envelope import canonical_sha256

ROOT = Path(__file__).parents[2]


def _codes(record: dict[str, object]) -> set[str]:
    return {issue.code for issue in sustained.validate_sustained_record(record)}


def _attempt_entry(
    *,
    attempt_sequence: int = 1,
    cadence_fps: int = 10,
    previous_entry_sha256: str | None = None,
) -> dict[str, object]:
    entry = {
        "attempt_entry_schema_version": "1.0.0",
        "record_kind": "m05b_attempt_entry",
        "attempt_sequence": attempt_sequence,
        "cadence_fps": cadence_fps,
        "admission_sha256": "c" * 64,
        "outcome": "completed",
        "previous_entry_sha256": previous_entry_sha256,
    }
    return {"entry": entry, "entry_sha256": canonical_sha256(entry)}


def _valid_record() -> dict[str, object]:
    return {
        "sustained_run_schema_version": "1.0.0",
        "record_kind": "m05b_sustained_run",
        "session_id": "00000000-0000-4000-8000-000000000010",
        "session_kind": "live_conformance",
        "attempt_sequence": 1,
        "cadence_fps": 10,
        "video_enabled": False,
        "camera_fps": 30,
        "duration_target_ns": 1_500_000_000_000,
        "stop_requested_ns": 1_500_000_000_000,
        "actual_capture_span_ns": 1_500_500_000_000,
        "boundary_events": [
            "attempt_open",
            "admission_append",
            "admission_flush",
            "admission_fsync",
            "capture_start",
        ],
        "artifact_refs": {
            "envelope": {"path": "envelope.json", "sha256": "a" * 64},
            "order": {"path": "execution-order.json", "sha256": "b" * 64},
            "admission": {"path": "run-admission.json", "sha256": "c" * 64},
        },
        "attempt_chain": [_attempt_entry()],
        "stage_accounting": {
            "camera_callbacks_received": 100,
            "yuv_frames_acquired": 100,
            "yuv_frames_released": 100,
            "preprocessing_started": 100,
            "preprocessing_completed": 100,
            "cadence_admitted": 10,
            "cadence_rejected": 90,
            "inference_submitted": 10,
            "inference_completed": 10,
            "inference_failed": 0,
            "pose_records_appended": 10,
            "pose_records_durable": 10,
            "source_failures": 0,
            "process_failures": 0,
        },
        "latency": {
            "bucket_upper_bounds_ns": sustained.LATENCY_BUCKET_UPPER_BOUNDS_NS,
            "bucket_counts": [4, 3, 2, 1] + [0] * 10,
            "percentiles": {
                "50": {"lower_inclusive_ns": 1_000_001, "upper_inclusive_ns": 2_000_000},
                "95": {"lower_inclusive_ns": 4_000_001, "upper_inclusive_ns": 8_000_000},
                "99": {"lower_inclusive_ns": 4_000_001, "upper_inclusive_ns": 8_000_000},
            },
        },
        "telemetry": {
            "fixed_sample_count": 1502,
            "first_t_session_ns": 0,
            "last_t_session_ns": 1_500_500_000_000,
            "max_inter_sample_gap_ns": 1_000_000_000,
            "terminal_sample": True,
        },
        "battery": {
            "start_temperature_c": 29.5,
            "end_temperature_c": 30.0,
            "delta_temperature_c": 0.5,
        },
        "source_outcome": "completed",
        "process_outcome": "completed",
        "blocking_codes": [],
        "non_participant_observation": True,
        "sanitized": True,
    }


def test_schema_is_registered_and_valid_record_is_admitted() -> None:
    record = _valid_record()
    assert sustained.validate_sustained_record(record) == []


def test_sustained_semantic_codes_await_task3_4_registry_integration() -> None:
    registry = json.loads(
        (ROOT / "contracts/tooling/validator_error_codes.v1.json").read_text(encoding="utf-8")
    )
    released = set(registry["codes"])
    draft_task8_codes = {
        "DURATION.TARGET",
        "DURATION.STOP_REQUEST",
        "DURATION.OVERSHOOT",
        "PROTOCOL.VIDEO_ENABLED",
        "PROTOCOL.CAMERA_FPS",
        "BOUNDARY.ADMISSION_NOT_DURABLE_BEFORE_CAPTURE",
        "ATTEMPT.ENTRY_SCHEMA",
        "ATTEMPT.ENTRY_DIGEST",
        "ATTEMPT.CHAIN_ORDER",
        "ATTEMPT.CADENCE",
        "ATTEMPT.RECOVERY_REQUIRED",
        "ATTEMPT.RECORD_MISMATCH",
        "ATTEMPT.ADMISSION_DIGEST",
        "STAGE.YUV",
        "STAGE.PREPROCESS",
        "STAGE.CADENCE",
        "STAGE.INFERENCE",
        "STAGE.POSE",
        "LATENCY.EMPTY",
        "LATENCY.COUNT",
        "LATENCY.PERCENTILE_50",
        "LATENCY.PERCENTILE_95",
        "LATENCY.PERCENTILE_99",
        "TELEMETRY.COUNT",
        "TELEMETRY.START_COVERAGE",
        "TELEMETRY.MAX_GAP",
        "TELEMETRY.END_COVERAGE",
        "BATTERY.START_TEMPERATURE_UNAVAILABLE",
        "BATTERY.END_TEMPERATURE_UNAVAILABLE",
        "BATTERY.DELTA_TEMPERATURE_UNAVAILABLE",
        "BATTERY.DELTA",
        "RUN.TRANSPORT_LOST",
        "SOURCE.OUTCOME",
        "BLOCKING_CODES.MISMATCH",
    }
    # Task 3 completed its coordinated 1.3.0 contract/error-code release after its
    # physical precondition. Task 4 has not released yet, so Task 8 must still not
    # preempt the remaining registry integration from WIP.
    assert registry["registry_version"] == "1.3.0"
    assert draft_task8_codes.isdisjoint(released)


@pytest.mark.parametrize(
    ("field", "value", "code"),
    [
        ("duration_target_ns", 1_499_000_000_000, "DURATION.TARGET"),
        ("stop_requested_ns", 1_501_000_000_001, "DURATION.OVERSHOOT"),
        ("video_enabled", True, "PROTOCOL.VIDEO_ENABLED"),
        ("camera_fps", 29, "PROTOCOL.CAMERA_FPS"),
    ],
)
def test_load_bearing_duration_and_protocol_mutations_block(
    field: str, value: object, code: str
) -> None:
    record = _valid_record()
    record[field] = value
    issues = _codes(record)
    assert code in issues


def test_missing_scalar_temperature_is_blocking_and_never_defaults() -> None:
    record = _valid_record()
    battery = copy.deepcopy(record["battery"])
    assert isinstance(battery, dict)
    battery.pop("end_temperature_c")
    record["battery"] = battery
    assert "BATTERY.END_TEMPERATURE_UNAVAILABLE" in _codes(record)


def test_boundary_events_and_attempt_chain_are_load_bearing() -> None:
    record = _valid_record()
    record["boundary_events"] = ["attempt_open", "capture_start"]
    assert "BOUNDARY.ADMISSION_NOT_DURABLE_BEFORE_CAPTURE" in _codes(record)


@pytest.mark.parametrize(
    ("mutation", "code"),
    [
        (("attempt_entry_schema_version", "2.0.0"), "ATTEMPT.ENTRY_SCHEMA"),
        (("record_kind", "other"), "ATTEMPT.ENTRY_SCHEMA"),
        (("previous_entry_sha256", "e" * 64), "ATTEMPT.CHAIN_ORDER"),
    ],
)
def test_released_task7_attempt_shape_and_link_are_load_bearing(
    mutation: tuple[str, object], code: str
) -> None:
    record = _valid_record()
    chain = copy.deepcopy(record["attempt_chain"])
    assert isinstance(chain, list) and isinstance(chain[0], dict)
    entry = chain[0]["entry"]
    assert isinstance(entry, dict)
    entry[mutation[0]] = mutation[1]
    chain[0]["entry_sha256"] = canonical_sha256(entry)
    record["attempt_chain"] = chain
    assert code in _codes(record)


def test_omitting_released_attempt_schema_version_blocks() -> None:
    record = _valid_record()
    chain = copy.deepcopy(record["attempt_chain"])
    assert isinstance(chain, list) and isinstance(chain[0], dict)
    entry = chain[0]["entry"]
    assert isinstance(entry, dict)
    entry.pop("attempt_entry_schema_version")
    chain[0]["entry_sha256"] = canonical_sha256(entry)
    record["attempt_chain"] = chain
    assert "ATTEMPT.ENTRY_SCHEMA" in _codes(record)


def test_final_attempt_admission_digest_binds_artifact_reference() -> None:
    record = _valid_record()
    refs = copy.deepcopy(record["artifact_refs"])
    assert isinstance(refs, dict) and isinstance(refs["admission"], dict)
    refs["admission"]["sha256"] = "f" * 64
    record["artifact_refs"] = refs
    assert "ATTEMPT.ADMISSION_DIGEST" in _codes(record)


def test_attempt_entry_hash_and_latency_percentiles_are_derived_not_trusted() -> None:
    record = _valid_record()
    chain = copy.deepcopy(record["attempt_chain"])
    assert isinstance(chain, list) and isinstance(chain[0], dict)
    chain[0]["entry_sha256"] = "d" * 64
    record["attempt_chain"] = chain
    latency = copy.deepcopy(record["latency"])
    assert isinstance(latency, dict)
    percentiles = latency["percentiles"]
    assert isinstance(percentiles, dict)
    percentiles["50"] = {"lower_inclusive_ns": 0, "upper_inclusive_ns": 1_000_000}
    record["latency"] = latency
    issues = _codes(record)
    assert "ATTEMPT.ENTRY_DIGEST" in issues
    assert "LATENCY.PERCENTILE_50" in issues


def test_empty_latency_histogram_is_blocking() -> None:
    record = _valid_record()
    latency = copy.deepcopy(record["latency"])
    assert isinstance(latency, dict)
    latency["bucket_counts"] = [0] * 14
    record["latency"] = latency
    assert "LATENCY.EMPTY" in _codes(record)


def test_telemetry_gap_and_end_coverage_block() -> None:
    record = _valid_record()
    telemetry = copy.deepcopy(record["telemetry"])
    assert isinstance(telemetry, dict)
    telemetry["max_inter_sample_gap_ns"] = 2_000_000_001
    telemetry["terminal_sample"] = False
    record["telemetry"] = telemetry
    issues = _codes(record)
    assert "TELEMETRY.MAX_GAP" in issues
    assert "TELEMETRY.END_COVERAGE" in issues


def test_delayed_first_telemetry_sample_blocks_start_coverage() -> None:
    record = _valid_record()
    telemetry = copy.deepcopy(record["telemetry"])
    assert isinstance(telemetry, dict)
    telemetry["first_t_session_ns"] = 1
    record["telemetry"] = telemetry
    assert "TELEMETRY.START_COVERAGE" in _codes(record)


def test_blocking_codes_are_exactly_recomputed() -> None:
    forged_nonempty = _valid_record()
    forged_nonempty["blocking_codes"] = ["DURATION.TARGET"]
    assert "BLOCKING_CODES.MISMATCH" in _codes(forged_nonempty)

    forged_empty = _valid_record()
    forged_empty["duration_target_ns"] = 1
    assert "DURATION.TARGET" in _codes(forged_empty)
    assert "BLOCKING_CODES.MISMATCH" in _codes(forged_empty)


def test_malformed_nested_containers_report_schema_errors_without_crashing() -> None:
    for field in ("attempt_chain", "stage_accounting", "latency", "telemetry", "battery"):
        record = _valid_record()
        record[field] = "malformed"
        assert "SCHEMA.INVALID" in _codes(record)


def test_cli_returns_contract_failure_for_invalid_record(tmp_path: Path) -> None:
    path = tmp_path / "run.json"
    record = _valid_record()
    record["duration_target_ns"] = 1
    path.write_text(json.dumps(record), encoding="utf-8")
    assert main(["verify-m05b-sustained", str(path)]) == 2


def test_cli_recomputes_and_rejects_caller_forged_blockers(tmp_path: Path) -> None:
    path = tmp_path / "forged-run.json"
    record = _valid_record()
    record["blocking_codes"] = ["DURATION.TARGET"]
    path.write_text(json.dumps(record), encoding="utf-8")
    assert main(["verify-m05b-sustained", str(path)]) == 2


def test_pending_ledgers_are_explicit_and_no_physical_record_is_claimed() -> None:
    for cadence in (10, 15, 30):
        path = ROOT / "docs/evidence/m05b-sustained" / f"s25-{cadence}fps-ledger.json"
        ledger = json.loads(path.read_text(encoding="utf-8"))
        assert ledger["status"] == "pending"
        assert ledger["missing_precondition"]
        assert "physical" in ledger["missing_precondition"].lower()
