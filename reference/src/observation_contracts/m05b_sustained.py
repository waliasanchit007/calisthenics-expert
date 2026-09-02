"""Fail-closed validation for one non-participant M0.5B sustained observation.

This module deliberately validates only the draft sustained record. It does not
manufacture or accept a SessionManifest, and its semantic codes remain draft
until Task 8's registry stabilization checkpoint performs the coordinated
contract/error-code release. Task 8 owns sustained semantics; Task 4 does not.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from typing import cast

from jsonschema import Draft202012Validator

from observation_contracts.errors import ValidationIssue
from observation_contracts.m05b_envelope import canonical_sha256, validate_m05b_document
from observation_contracts.schema_loader import load_m05b_sustained_run_schema

LATENCY_BUCKET_UPPER_BOUNDS_NS: list[int | None] = [
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
    None,
]
_BOUNDARY_EVENTS = [
    "attempt_open",
    "admission_append",
    "admission_flush",
    "admission_fsync",
    "capture_start",
]
_MAX_STOP_REQUEST_NS = 1_501_000_000_000


def validate_sustained_record(document: object, path: str = "<memory>") -> list[ValidationIssue]:
    """Return deterministic issues; no caller-supplied success field is trusted."""
    schema = load_m05b_sustained_run_schema()
    issues = [
        _issue(
            path,
            "SCHEMA.INVALID",
            "/" + "/".join(str(p) for p in error.absolute_path),
            error.message,
        )
        for error in Draft202012Validator(schema).iter_errors(document)
    ]
    if not isinstance(document, Mapping):
        return issues

    record = cast(Mapping[str, object], document)
    derived: list[ValidationIssue] = []
    _check_duration(record, path, derived)
    _check_attempt_chain(record, path, derived)
    _check_stage_accounting(record, path, derived)
    _check_latency(record, path, derived)
    _check_telemetry(record, path, derived)
    _check_battery(record, path, derived)
    _check_outcomes(record, path, derived)
    expected_codes = sorted({issue.code for issue in derived})
    supplied_codes = record.get("blocking_codes")
    if supplied_codes != expected_codes:
        derived.append(
            _issue(
                path,
                "BLOCKING_CODES.MISMATCH",
                "/blocking_codes",
                "blocking codes must equal sorted distinct recomputed semantic blockers",
            )
        )
    issues.extend(derived)
    return issues


def _check_duration(record: Mapping[str, object], path: str, issues: list[ValidationIssue]) -> None:
    if record.get("duration_target_ns") != 1_500_000_000_000:
        issues.append(
            _issue(
                path,
                "DURATION.TARGET",
                "/duration_target_ns",
                "duration target must be exactly 1,500 seconds",
            )
        )
    if record.get("video_enabled") is not False:
        issues.append(
            _issue(
                path, "PROTOCOL.VIDEO_ENABLED", "/video_enabled", "sustained runs prohibit video"
            )
        )
    if record.get("camera_fps") != 30:
        issues.append(
            _issue(path, "PROTOCOL.CAMERA_FPS", "/camera_fps", "camera cadence must be 30 FPS")
        )
    stop = record.get("stop_requested_ns")
    if not isinstance(stop, int) or stop != 1_500_000_000_000:
        issues.append(
            _issue(
                path,
                "DURATION.STOP_REQUEST",
                "/stop_requested_ns",
                "stop must be requested exactly at 1,500 seconds",
            )
        )
    span = record.get("actual_capture_span_ns")
    if isinstance(span, int) and span > _MAX_STOP_REQUEST_NS:
        issues.append(
            _issue(
                path,
                "DURATION.OVERSHOOT",
                "/actual_capture_span_ns",
                "capture completion exceeds the one-second stop allowance",
            )
        )
    if record.get("boundary_events") != _BOUNDARY_EVENTS:
        issues.append(
            _issue(
                path,
                "BOUNDARY.ADMISSION_NOT_DURABLE_BEFORE_CAPTURE",
                "/boundary_events",
                "admission must append, flush, and fsync before capture",
            )
        )


def _check_attempt_chain(
    record: Mapping[str, object], path: str, issues: list[ValidationIssue]
) -> None:
    raw_chain = record.get("attempt_chain")
    if not isinstance(raw_chain, list) or not raw_chain:
        return
    chain = cast(list[object], raw_chain)
    previous: str | None = None
    expected_cadence = 10
    last_entry: Mapping[str, object] | None = None
    for index, raw in enumerate(chain, start=1):
        if not isinstance(raw, Mapping):
            continue
        wrapper = cast(Mapping[str, object], raw)
        payload = wrapper.get("entry")
        if not isinstance(payload, Mapping):
            continue
        entry = cast(Mapping[str, object], payload)
        if validate_m05b_document(entry, "attempt_entry"):
            issues.append(
                _issue(
                    path,
                    "ATTEMPT.ENTRY_SCHEMA",
                    f"/attempt_chain/{index - 1}/entry",
                    "embedded attempt must satisfy released Task 7 schema",
                )
            )
        if wrapper.get("entry_sha256") != canonical_sha256(entry):
            issues.append(
                _issue(
                    path,
                    "ATTEMPT.ENTRY_DIGEST",
                    f"/attempt_chain/{index - 1}/entry_sha256",
                    "entry digest must bind exact canonical entry bytes",
                )
            )
        if entry.get("attempt_sequence") != index or entry.get("previous_entry_sha256") != previous:
            issues.append(
                _issue(
                    path,
                    "ATTEMPT.CHAIN_ORDER",
                    f"/attempt_chain/{index - 1}",
                    "attempt entries must be append-only predecessors",
                )
            )
        cadence = entry.get("cadence_fps")
        if cadence != expected_cadence:
            issues.append(
                _issue(
                    path,
                    "ATTEMPT.CADENCE",
                    f"/attempt_chain/{index - 1}/cadence_fps",
                    "cadence must follow the frozen order",
                )
            )
        outcome = entry.get("outcome")
        if outcome == "completed":
            expected_cadence = {10: 15, 15: 30, 30: 0}.get(expected_cadence, 0)
        elif outcome not in {"aborted", "failed"}:
            issues.append(
                _issue(
                    path,
                    "ATTEMPT.RECOVERY_REQUIRED",
                    f"/attempt_chain/{index - 1}/outcome",
                    "blocked or transport-lost attempts cannot be rerun",
                )
            )
        digest = wrapper.get("entry_sha256")
        previous = digest if isinstance(digest, str) else None
        last_entry = entry
    if last_entry is None:
        return
    if record.get("cadence_fps") != last_entry.get("cadence_fps") or record.get(
        "attempt_sequence"
    ) != last_entry.get("attempt_sequence"):
        issues.append(
            _issue(
                path,
                "ATTEMPT.RECORD_MISMATCH",
                "/attempt_chain",
                "record must describe its final immutable attempt",
            )
        )
    refs = record.get("artifact_refs")
    admission = refs.get("admission") if isinstance(refs, Mapping) else None
    admission_digest = admission.get("sha256") if isinstance(admission, Mapping) else None
    if admission_digest != last_entry.get("admission_sha256"):
        issues.append(
            _issue(
                path,
                "ATTEMPT.ADMISSION_DIGEST",
                "/artifact_refs/admission/sha256",
                "admission reference must bind the final attempt",
            )
        )


def _check_stage_accounting(
    record: Mapping[str, object], path: str, issues: list[ValidationIssue]
) -> None:
    raw_values = record.get("stage_accounting")
    if not isinstance(raw_values, Mapping):
        return
    required = (
        "camera_callbacks_received",
        "yuv_frames_acquired",
        "yuv_frames_released",
        "preprocessing_started",
        "preprocessing_completed",
        "cadence_admitted",
        "cadence_rejected",
        "inference_submitted",
        "inference_completed",
        "inference_failed",
        "pose_records_appended",
        "pose_records_durable",
    )
    if any(not isinstance(raw_values.get(key), int) for key in required):
        return
    values = cast(Mapping[str, int], raw_values)
    equations = (
        (
            "STAGE.YUV",
            values["camera_callbacks_received"]
            == values["yuv_frames_acquired"]
            == values["yuv_frames_released"],
        ),
        (
            "STAGE.PREPROCESS",
            values["yuv_frames_acquired"]
            == values["preprocessing_started"]
            == values["preprocessing_completed"],
        ),
        (
            "STAGE.CADENCE",
            values["preprocessing_completed"]
            == values["cadence_admitted"] + values["cadence_rejected"],
        ),
        (
            "STAGE.INFERENCE",
            values["cadence_admitted"]
            == values["inference_submitted"]
            == values["inference_completed"] + values["inference_failed"],
        ),
        (
            "STAGE.POSE",
            values["inference_completed"]
            == values["pose_records_appended"]
            == values["pose_records_durable"],
        ),
    )
    for code, valid in equations:
        if not valid:
            issues.append(
                _issue(path, code, "/stage_accounting", "stage equation does not reconcile")
            )


def _check_latency(record: Mapping[str, object], path: str, issues: list[ValidationIssue]) -> None:
    raw_latency = record.get("latency")
    accounting = record.get("stage_accounting")
    if not isinstance(raw_latency, Mapping) or not isinstance(accounting, Mapping):
        return
    latency = cast(Mapping[str, object], raw_latency)
    raw_counts = latency.get("bucket_counts")
    completed = accounting.get("inference_completed")
    if (
        not isinstance(raw_counts, list)
        or len(raw_counts) != len(LATENCY_BUCKET_UPPER_BOUNDS_NS)
        or any(not isinstance(value, int) or value < 0 for value in raw_counts)
        or not isinstance(completed, int)
    ):
        return
    counts = cast(list[int], raw_counts)
    total = sum(counts)
    if total == 0:
        issues.append(
            _issue(
                path,
                "LATENCY.EMPTY",
                "/latency/bucket_counts",
                "completed runs require a non-empty histogram",
            )
        )
        return
    if total != completed:
        issues.append(
            _issue(
                path,
                "LATENCY.COUNT",
                "/latency/bucket_counts",
                "bucket total must equal completed inferences",
            )
        )
    raw_percentiles = latency.get("percentiles")
    if not isinstance(raw_percentiles, Mapping):
        return
    percentiles = cast(Mapping[str, object], raw_percentiles)
    for rank in (50, 95, 99):
        expected = _percentile_bounds(counts, rank)
        actual = percentiles.get(str(rank))
        if actual != expected:
            issues.append(
                _issue(
                    path,
                    f"LATENCY.PERCENTILE_{rank}",
                    f"/latency/percentiles/{rank}",
                    "percentile must be the fixed nearest-rank containing bucket",
                )
            )


def _percentile_bounds(counts: list[int], rank: int) -> dict[str, int | None]:
    target = math.ceil(sum(counts) * rank / 100)
    cumulative = 0
    lower = 0
    for upper, count in zip(LATENCY_BUCKET_UPPER_BOUNDS_NS, counts, strict=True):
        cumulative += count
        if cumulative >= target:
            return {"lower_inclusive_ns": lower, "upper_inclusive_ns": upper}
        if upper is not None:
            lower = upper + 1
    raise ValueError("non-empty histogram has no containing bucket")


def _check_telemetry(
    record: Mapping[str, object], path: str, issues: list[ValidationIssue]
) -> None:
    raw_telemetry = record.get("telemetry")
    span = record.get("actual_capture_span_ns")
    if not isinstance(raw_telemetry, Mapping) or not isinstance(span, int):
        return
    telemetry = cast(Mapping[str, object], raw_telemetry)
    expected = span // 1_000_000_000 + 2
    count = telemetry.get("fixed_sample_count")
    if not isinstance(count, int) or abs(count - expected) > 1:
        issues.append(
            _issue(
                path,
                "TELEMETRY.COUNT",
                "/telemetry/fixed_sample_count",
                "fixed interval sample count lacks required coverage",
            )
        )
    first_time = telemetry.get("first_t_session_ns")
    if first_time != 0:
        issues.append(
            _issue(
                path,
                "TELEMETRY.START_COVERAGE",
                "/telemetry/first_t_session_ns",
                "first fixed sample must be at the session origin",
            )
        )
    maximum_gap = telemetry.get("max_inter_sample_gap_ns")
    if not isinstance(maximum_gap, int) or maximum_gap > 2_000_000_000:
        issues.append(
            _issue(
                path,
                "TELEMETRY.MAX_GAP",
                "/telemetry/max_inter_sample_gap_ns",
                "telemetry gap exceeds two seconds",
            )
        )
    last_time = telemetry.get("last_t_session_ns")
    if (
        telemetry.get("terminal_sample") is not True
        or not isinstance(last_time, int)
        or last_time < span
    ):
        issues.append(
            _issue(
                path,
                "TELEMETRY.END_COVERAGE",
                "/telemetry",
                "terminal telemetry must cover the capture end",
            )
        )


def _check_battery(record: Mapping[str, object], path: str, issues: list[ValidationIssue]) -> None:
    raw_battery = record.get("battery")
    if not isinstance(raw_battery, Mapping):
        return
    battery = cast(Mapping[str, object], raw_battery)
    start = battery.get("start_temperature_c")
    end = battery.get("end_temperature_c")
    delta = battery.get("delta_temperature_c")
    if not isinstance(start, (int, float)):
        issues.append(
            _issue(
                path,
                "BATTERY.START_TEMPERATURE_UNAVAILABLE",
                "/battery/start_temperature_c",
                "start temperature is required without a default",
            )
        )
    if not isinstance(end, (int, float)):
        issues.append(
            _issue(
                path,
                "BATTERY.END_TEMPERATURE_UNAVAILABLE",
                "/battery/end_temperature_c",
                "end temperature is required without a default",
            )
        )
    if not isinstance(delta, (int, float)):
        issues.append(
            _issue(
                path,
                "BATTERY.DELTA_TEMPERATURE_UNAVAILABLE",
                "/battery/delta_temperature_c",
                "temperature delta is required without a default",
            )
        )
    if not all(isinstance(value, (int, float)) for value in (start, end, delta)):
        return
    start_number = cast(float | int, start)
    end_number = cast(float | int, end)
    delta_number = cast(float | int, delta)
    if not math.isclose(float(delta_number), float(end_number) - float(start_number), abs_tol=1e-9):
        issues.append(
            _issue(
                path,
                "BATTERY.DELTA",
                "/battery/delta_temperature_c",
                "temperature delta must be derived without defaults",
            )
        )


def _check_outcomes(record: Mapping[str, object], path: str, issues: list[ValidationIssue]) -> None:
    if record.get("process_outcome") == "transport_lost":
        issues.append(
            _issue(
                path,
                "RUN.TRANSPORT_LOST",
                "/process_outcome",
                "durably committed transport loss awaits recovery",
            )
        )
    if record.get("source_outcome") != "completed":
        issues.append(
            _issue(path, "SOURCE.OUTCOME", "/source_outcome", "source failure is blocking")
        )


def _issue(path: str, code: str, pointer: str, message: str) -> ValidationIssue:
    return ValidationIssue(
        code=code,
        artifact_path=path,
        artifact_kind="m05b_sustained_run",
        pointer=pointer,
        message=message,
    )
