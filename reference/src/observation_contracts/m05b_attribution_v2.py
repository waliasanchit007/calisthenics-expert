"""Independent integer-only derivation for M0.5B attribution protocol v2."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from itertools import pairwise
from typing import cast

from observation_contracts.errors import ValidationIssue

TARGET_CADENCES_FPS = (10, 15, 30)
SOURCE_MEASURED_CALLBACKS = 300
BACKEND_MEASURED_COMPLETIONS = 100
REPLICATE_ORDERS = ([10, 15, 30], [30, 15, 10])


@dataclass(frozen=True)
class CapacityResult:
    supported: bool


def closed_interval_opportunities(span_ns: int, fps: int) -> int:
    """Count cadence opportunities in a closed interval without floating point."""
    if span_ns < 0 or fps <= 0:
        raise ValueError("span_ns must be non-negative and fps must be positive")
    return (span_ns // 1_000_000_000) * fps + (span_ns % 1_000_000_000) * fps // 1_000_000_000 + 1


def source_capacity(timestamps_ns: Sequence[int], cadence_fps: int) -> CapacityResult:
    valid = len(timestamps_ns) == SOURCE_MEASURED_CALLBACKS and all(
        second > first for first, second in pairwise(timestamps_ns)
    )
    if not valid:
        return CapacityResult(False)
    span_ns = timestamps_ns[-1] - timestamps_ns[0]
    return CapacityResult(len(timestamps_ns) >= closed_interval_opportunities(span_ns, cadence_fps))


def source_supports(timestamps_ns: Sequence[int], cadence_fps: int) -> bool:
    return source_capacity(timestamps_ns, cadence_fps).supported


def backend_capacity(service_durations_ns: Sequence[int], cadence_fps: int) -> CapacityResult:
    interval_ns = 1_000_000_000 // cadence_fps
    return CapacityResult(
        len(service_durations_ns) == BACKEND_MEASURED_COMPLETIONS
        and all(0 <= duration <= interval_ns for duration in service_durations_ns)
    )


def backend_supports(service_durations_ns: Sequence[int], cadence_fps: int) -> bool:
    return backend_capacity(service_durations_ns, cadence_fps).supported


def selector_timestamps(timestamps_ns: Sequence[int], cadence_fps: int) -> list[int]:
    """Independently apply the documented integer cadence interval rule."""
    interval_ns = 1_000_000_000 // cadence_fps
    selected: list[int] = []
    last_selected: int | None = None
    for timestamp_ns in timestamps_ns:
        if timestamp_ns < 0:
            raise ValueError("timestamps must be non-negative")
        if last_selected is None or timestamp_ns - last_selected >= interval_ns:
            selected.append(timestamp_ns)
            last_selected = timestamp_ns
    return selected


def validate_attribution_v2(document: Mapping[str, object], path: str) -> list[ValidationIssue]:
    """Recompute v2 blockers; no device-supplied success Boolean is consumed."""
    blockers: set[str] = set()
    if document.get("protocol_version") != "2.0.0":
        blockers.add("ATTRIBUTION.PROTOCOL_DIGEST")
    if document.get("identity_matches") is not True:
        blockers.add("ATTRIBUTION.CANDIDATE_IDENTITY")
    if document.get("environment_matches") is not True:
        blockers.add("ATTRIBUTION.ENVIRONMENT_MISMATCH")

    source = _mapping(document.get("source_arm"))
    backend = _mapping(document.get("backend_arm"))
    selector = _mapping(document.get("selector_arm"))
    _validate_source(source, blockers)
    _validate_backend(backend, blockers)
    _validate_selector(selector, blockers)

    callback_arm = _mapping(document.get("callback_arm"))
    if callback_arm is not None:
        _validate_callbacks(callback_arm, blockers)
    replicates = _mapping_sequence(document.get("replicates"))
    if selector is not None and (source is None or backend is None or not replicates):
        blockers.add("ATTRIBUTION.SELECTOR_ONLY")
    _validate_replicates(replicates, blockers)
    return [_issue(path, code) for code in sorted(blockers)]


def _validate_source(source: Mapping[str, object] | None, blockers: set[str]) -> None:
    timestamps = (
        _integer_sequence(source.get("measured_timestamps_ns")) if source is not None else None
    )
    if timestamps is None or len(timestamps) != SOURCE_MEASURED_CALLBACKS:
        blockers.add("ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT")
        return
    if any(second <= first for first, second in pairwise(timestamps)):
        blockers.add("ATTRIBUTION.SOURCE_TIMESTAMP_INVALID")
        return
    if any(not source_supports(timestamps, fps) for fps in TARGET_CADENCES_FPS):
        blockers.add("ATTRIBUTION.SOURCE_LIMITED")


def _validate_backend(backend: Mapping[str, object] | None, blockers: set[str]) -> None:
    durations = (
        _integer_sequence(backend.get("measured_service_durations_ns"))
        if backend is not None
        else None
    )
    if durations is None or len(durations) != BACKEND_MEASURED_COMPLETIONS:
        blockers.add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
        return
    if backend is not None and backend.get("inference_failures") != 0:
        blockers.add("ATTRIBUTION.INFERENCE_FAILURE")
    if any(not backend_supports(durations, fps) for fps in TARGET_CADENCES_FPS):
        blockers.add("ATTRIBUTION.BACKEND_LIMITED")


def _validate_selector(selector: Mapping[str, object] | None, blockers: set[str]) -> None:
    if selector is None:
        blockers.add("ATTRIBUTION.SELECTOR_DIVERGENCE")
        return
    production = _cadence_sequences(selector.get("production_selections"))
    host = _cadence_sequences(selector.get("host_selections"))
    if production is None or host is None or production != host:
        blockers.add("ATTRIBUTION.SELECTOR_DIVERGENCE")
        return
    counts = [len(production[fps]) for fps in TARGET_CADENCES_FPS]
    if not all(lower < upper for lower, upper in pairwise(counts)):
        blockers.add("ATTRIBUTION.SELECTOR_DIVERGENCE")


def _validate_callbacks(arm: Mapping[str, object], blockers: set[str]) -> None:
    delivered = _integer_sequence(arm.get("delivered_callback_ids"))
    submitted = _integer_sequence(arm.get("submitted_inference_ids"))
    pending = _integer_sequence(arm.get("pending_inference_ids"))
    terminal_values = arm.get("terminal_callbacks")
    terminals = _mapping_sequence(terminal_values)
    if delivered is None or submitted is None or pending is None or terminals is None:
        blockers.update({"ATTRIBUTION.CALLBACK_CONSERVATION", "ATTRIBUTION.TERMINAL_CLOSURE"})
        return
    terminal_ids = _integer_sequence([terminal.get("callback_id") for terminal in terminals])
    admitted_ids = _integer_sequence(
        [
            terminal.get("callback_id")
            for terminal in terminals
            if terminal.get("terminal_class") in {"admitted_completed", "admitted_inference_failed"}
        ]
    )
    if terminal_ids is None or admitted_ids is None:
        blockers.add("ATTRIBUTION.TERMINAL_CLOSURE")
        return
    if len(delivered) != len(set(delivered)) or len(terminal_ids) != len(set(terminal_ids)):
        blockers.add("ATTRIBUTION.TERMINAL_CLOSURE")
    if set(delivered) != set(terminal_ids) or set(submitted) != set(admitted_ids) or pending:
        blockers.add("ATTRIBUTION.TERMINAL_CLOSURE")
    if len(delivered) != len(terminals):
        blockers.add("ATTRIBUTION.CALLBACK_CONSERVATION")
    if any(not _terminal_matches(terminal) for terminal in terminals):
        blockers.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")


def _terminal_matches(callback: Mapping[str, object]) -> bool:
    terminal = callback.get("terminal_class")
    production = callback.get("production_classification")
    cadence_due = callback.get("cadence_due")
    slot_available = callback.get("slot_available")
    if terminal in {"admitted_completed", "admitted_inference_failed"}:
        return production == "submit"
    if terminal == "cadence_only":
        return cadence_due is False and slot_available is True and production == "cadence_selection"
    if terminal == "backpressure_only":
        return cadence_due is True and slot_available is False and production == "backpressure"
    if terminal == "cadence_and_backpressure":
        return cadence_due is False and slot_available is False and production == "backpressure"
    if terminal == "preprocessing_failed":
        return production == "preprocessing_failed"
    if terminal == "session_stopping":
        return production == "session_stopping"
    return False


def _validate_replicates(replicates: list[Mapping[str, object]] | None, blockers: set[str]) -> None:
    if replicates is None or len(replicates) != 2:
        blockers.add("ATTRIBUTION.REPLICATE_DISAGREEMENT")
        return
    orders = [_integer_sequence(replicate.get("cadence_order")) for replicate in replicates]
    if orders != list(REPLICATE_ORDERS):
        blockers.add("ATTRIBUTION.REPLICATE_DISAGREEMENT")
        return
    for replicate in replicates:
        admitted = _cadence_integers(replicate.get("admitted_completion_rates"))
        opportunities = _cadence_integers(replicate.get("cadence_opportunity_rates"))
        source = _cadence_booleans(replicate.get("source_supported"))
        backend = _cadence_booleans(replicate.get("backend_supported"))
        if admitted is None or opportunities is None or source is None or backend is None:
            blockers.add("ATTRIBUTION.END_TO_END_NOT_DISTINCT")
            continue
        admitted_values = [admitted[fps] for fps in TARGET_CADENCES_FPS]
        opportunity_values = [opportunities[fps] for fps in TARGET_CADENCES_FPS]
        if (
            not all(source.values())
            or not all(backend.values())
            or not all(a < b for a, b in pairwise(admitted_values))
            or not all(a < b for a, b in pairwise(opportunity_values))
        ):
            blockers.add("ATTRIBUTION.END_TO_END_NOT_DISTINCT")


def _mapping(value: object) -> Mapping[str, object] | None:
    return cast(Mapping[str, object], value) if isinstance(value, Mapping) else None


def _mapping_sequence(value: object) -> list[Mapping[str, object]] | None:
    if not isinstance(value, list) or not all(isinstance(item, Mapping) for item in value):
        return None
    return [cast(Mapping[str, object], item) for item in value]


def _integer_sequence(value: object) -> list[int] | None:
    if not isinstance(value, list) or not all(
        isinstance(item, int) and not isinstance(item, bool) for item in value
    ):
        return None
    return cast(list[int], value)


def _cadence_sequences(value: object) -> dict[int, list[int]] | None:
    mapping = _mapping(value)
    if mapping is None or set(mapping) != {str(fps) for fps in TARGET_CADENCES_FPS}:
        return None
    converted = {fps: _integer_sequence(mapping[str(fps)]) for fps in TARGET_CADENCES_FPS}
    if any(sequence is None for sequence in converted.values()):
        return None
    return {fps: cast(list[int], sequence) for fps, sequence in converted.items()}


def _cadence_integers(value: object) -> dict[int, int] | None:
    mapping = _mapping(value)
    if mapping is None or set(mapping) != {str(fps) for fps in TARGET_CADENCES_FPS}:
        return None
    if not all(
        isinstance(mapping[str(fps)], int) and not isinstance(mapping[str(fps)], bool)
        for fps in TARGET_CADENCES_FPS
    ):
        return None
    return {fps: cast(int, mapping[str(fps)]) for fps in TARGET_CADENCES_FPS}


def _cadence_booleans(value: object) -> dict[int, bool] | None:
    mapping = _mapping(value)
    if mapping is None or set(mapping) != {str(fps) for fps in TARGET_CADENCES_FPS}:
        return None
    if not all(isinstance(mapping[str(fps)], bool) for fps in TARGET_CADENCES_FPS):
        return None
    return {fps: cast(bool, mapping[str(fps)]) for fps in TARGET_CADENCES_FPS}


def _issue(path: str, code: str) -> ValidationIssue:
    return ValidationIssue(
        code=code,
        artifact_path=path,
        artifact_kind="m05b_attribution_v2_device_observation",
        message="M0.5B attribution-v2 evidence failed an independently derived predicate",
    )
