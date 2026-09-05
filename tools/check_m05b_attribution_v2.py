"""Independent host admission and ledger verification for M0.5B attribution v2."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from collections.abc import Mapping, Sequence
from itertools import pairwise
from math import lcm
from pathlib import Path
from typing import cast

from jsonschema import Draft202012Validator
from observation_contracts.m05b_attribution_v2 import (
    REPLICATE_ORDERS,
    TARGET_CADENCES_FPS,
    backend_supports,
    selector_timestamps,
    source_supports,
    validate_attribution_v2,
)
from observation_contracts.schema_loader import (
    load_m05b_attribution_observation_v2_schema,
    load_strict_json,
)

STATUS_TAG = "M05BAttributionV2Evidence"
SUITE = "com.calisthenics.diagnostic.composition.M05BAttributionV2DeviceGateTest"
RECORD_KIND = "m05b_attribution_v2_reference"
OBSERVATION_KIND = "m05b_attribution_v2_device_observation"
HOST_EVIDENCE_KIND = "m05b_attribution_v2_host_evidence"
DEFAULT_LEDGER = "docs/evidence/m05b-attribution-v2/s25-attribution-v2-ledger.json"
EVIDENCE_ROOT = "docs/evidence/m05b-attribution-v2/"
PROTOCOL_RELATIVE = "docs/protocols/m05b-attribution-v2.protocol.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA = re.compile(r"^[0-9a-f]{40}$")
SAFE_RELATIVE = re.compile(
    r"^(?!/)(?![A-Za-z]:[\\/])(?!.*(?:^|/)\.\.(?:/|$))[A-Za-z0-9._/-]+$"
)
SESSION_REFERENCE = re.compile(
    r"^exports/(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|[0-9a-f]{64})$"
)
IDENTITY_TOKEN = re.compile(r"^[A-Za-z0-9._+-]{1,128}$")
DEVICE_TOKEN = re.compile(r"^[A-Za-z0-9._-]+$")
DEVICE_KEYS = {
    "device_class",
    "device_identity",
    "manufacturer",
    "model",
    "api_level",
    "android_release",
    "os_build",
}
REFERENCE_KEYS = {
    "record_kind",
    "protocol_sha256",
    "relative_path",
    "sha256",
    "length_bytes",
}
IDENTITY_KEYS = {
    "app_git_sha",
    "probe_git_sha",
    "app_apk_sha256",
    "test_apk_sha256",
    "model_sha256",
    "runtime_version",
    "backend_version",
    "delegate",
}
V2_CODES = {
    "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE",
    "ATTRIBUTION.BACKEND_LIMITED",
    "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT",
    "ATTRIBUTION.CALLBACK_CONSERVATION",
    "ATTRIBUTION.CANDIDATE_IDENTITY",
    "ATTRIBUTION.END_TO_END_NOT_DISTINCT",
    "ATTRIBUTION.ENVIRONMENT_MISMATCH",
    "ATTRIBUTION.INFERENCE_FAILURE",
    "ATTRIBUTION.PROTOCOL_DIGEST",
    "ATTRIBUTION.REPLICATE_DISAGREEMENT",
    "ATTRIBUTION.SELECTOR_DIVERGENCE",
    "ATTRIBUTION.SELECTOR_ONLY",
    "ATTRIBUTION.SOURCE_CONFIGURATION",
    "ATTRIBUTION.SOURCE_LIMITED",
    "ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT",
    "ATTRIBUTION.SOURCE_TIMESTAMP_INVALID",
    "ATTRIBUTION.TERMINAL_CLOSURE",
    "ATTRIBUTION.THERMAL_STATE_MISMATCH",
}


class AttributionV2CheckError(ValueError):
    """Raised for malformed or unrecoverable host-check inputs."""


def extract_reference(transcript: str) -> Mapping[str, object]:
    """Extract and strictly validate the one v2 instrumentation reference."""
    matches = re.findall(
        rf"^INSTRUMENTATION_STATUS:\s*{re.escape(STATUS_TAG)}=(.*)$",
        transcript,
        re.MULTILINE,
    )
    if len(matches) != 1:
        raise AttributionV2CheckError(
            f"expected exactly one {STATUS_TAG} record, found {len(matches)}"
        )
    class_markers = re.findall(
        r"^INSTRUMENTATION_STATUS:\s*class=(.*)$", transcript, re.MULTILINE
    )
    if class_markers.count(SUITE) != 1:
        raise AttributionV2CheckError(
            "transcript does not show exactly one attribution v2 suite"
        )
    completion = re.findall(
        r"^INSTRUMENTATION_CODE:\s*(-?\d+)$", transcript, re.MULTILINE
    )
    if completion != ["-1"]:
        raise AttributionV2CheckError(
            "instrumentation completion status is not successful"
        )
    if re.findall(r"^OK \(1 test\)$", transcript, re.MULTILINE) != ["OK (1 test)"]:
        raise AttributionV2CheckError(
            "transcript does not report exactly one passing test"
        )
    try:
        record = load_strict_json(matches[0])
    except json.JSONDecodeError as error:
        raise AttributionV2CheckError(
            f"status record is not strict JSON: {error}"
        ) from error
    if not isinstance(record, Mapping):
        raise AttributionV2CheckError("status record must be an object")
    if record.get("record_kind") != RECORD_KIND:
        raise AttributionV2CheckError("status record has the wrong record_kind")
    if set(record) != REFERENCE_KEYS:
        raise AttributionV2CheckError("status reference has unknown or missing fields")
    _validate_reference(record)
    return cast(Mapping[str, object], record)


def validate_single_status_record(
    transcript: str, status_tag: str, suite: str
) -> Mapping[str, object]:
    """Compatibility seam used by focused tests and the public extractor."""
    if status_tag != STATUS_TAG or suite != SUITE:
        raise AttributionV2CheckError("unexpected v2 status contract")
    return extract_reference(transcript)


def transfer_reference(
    serial: str,
    package: str,
    relative_path: str,
    destination: Path,
    *,
    expected_length: int | None = None,
    expected_sha256: str | None = None,
) -> None:
    """Pull exact app-private bytes with fixed argv and publish them atomically."""
    if not _safe_relative(relative_path):
        raise AttributionV2CheckError("reference path must be safe and relative")
    if not serial or not package:
        raise AttributionV2CheckError("serial and package are required")
    if (expected_length is None) != (expected_sha256 is None):
        raise AttributionV2CheckError(
            "expected transfer length and digest must be paired"
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    argv = ["adb", "-s", serial, "exec-out", "run-as", package, "cat", relative_path]
    try:
        completed = subprocess.run(
            argv,
            capture_output=True,
            check=False,
            shell=False,
        )
    except OSError as error:
        raise AttributionV2CheckError(
            "awaiting_recovery: adb transport failed"
        ) from error
    if completed.returncode != 0:
        raise AttributionV2CheckError(
            "awaiting_recovery: adb could not pull published bytes"
        )
    temporary = destination.with_name(f".{destination.name}.transfer")
    try:
        with temporary.open("wb") as handle:
            handle.write(completed.stdout)
            handle.flush()
            os.fsync(handle.fileno())
        if expected_length is not None and expected_sha256 is not None:
            transferred = temporary.read_bytes()
            if (
                len(transferred) != expected_length
                or _sha256(transferred) != expected_sha256
            ):
                raise AttributionV2CheckError("exact-byte digest or length mismatch")
        os.replace(temporary, destination)
        _fsync_directory(destination.parent)
    finally:
        temporary.unlink(missing_ok=True)


def check_attribution_v2(
    transcript: str,
    *,
    subprocess_exit_code: int,
    serial: str,
    package: str,
    prepared_identity: Mapping[str, object],
    root: Path,
) -> dict[str, object]:
    """Transfer, reopen, and independently classify one v2 physical observation."""
    if type(subprocess_exit_code) is not int or subprocess_exit_code != 0:
        raise AttributionV2CheckError("instrumentation subprocess exited non-zero")
    _validate_identity(prepared_identity)
    reference = extract_reference(transcript)
    with tempfile.TemporaryDirectory(prefix="m05b-attribution-v2-") as temporary:
        transferred = Path(temporary) / "observation.json"
        transfer_reference(
            serial,
            package,
            str(reference["relative_path"]),
            transferred,
            expected_length=cast(int, reference["length_bytes"]),
            expected_sha256=cast(str, reference["sha256"]),
        )
        data = _read_exact_bytes(transferred, reference)
        try:
            observation = load_strict_json(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AttributionV2CheckError(
                "transferred observation is not strict UTF-8 JSON"
            ) from error
        if not isinstance(observation, Mapping):
            raise AttributionV2CheckError("transferred observation must be an object")
        return AttributionV2HostCheck(root).classify(
            cast(Mapping[str, object], observation), prepared_identity
        )


class AttributionV2HostCheck:
    """Host-side verifier whose authorities are protocol and raw observation bytes."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def run(
        self,
        transcript: str,
        subprocess_exit_code: int,
        serial: str,
        package: str,
        prepared_identity: Mapping[str, object],
    ) -> dict[str, object]:
        return check_attribution_v2(
            transcript,
            subprocess_exit_code=subprocess_exit_code,
            serial=serial,
            package=package,
            prepared_identity=prepared_identity,
            root=self.root,
        )

    def classify(
        self, observation: Mapping[str, object], prepared_identity: Mapping[str, object]
    ) -> dict[str, object]:
        _validate_observation_schema(observation)
        protocol_bytes = _read_protocol(self.root)
        protocol_sha256 = _sha256(protocol_bytes)
        authorized_device = _read_authorized_device_pin(self.root)
        codes = _derive_blockers(
            observation, protocol_sha256, prepared_identity, authorized_device
        )
        device_codes = _string_list(observation.get("blocking_codes"))
        if device_codes != sorted(set(device_codes)) or device_codes != sorted(codes):
            codes.add("BLOCKING_CODES.MISMATCH")
        sorted_codes = sorted(codes)
        sanitized_observation = _sanitize_observation(
            observation, prepared_identity, authorized_device
        )
        result: dict[str, object] = {
            "evidence_schema_version": "2.0.0",
            "record_kind": HOST_EVIDENCE_KIND,
            "sanitized": True,
            "non_participant_observation": True,
            "protocol_sha256": protocol_sha256,
            "device": dict(
                _sanitize_device(_mapping(observation, "device"), authorized_device)
            ),
            "identity": dict(prepared_identity),
            "observation": sanitized_observation,
            "blocking_codes": sorted_codes,
            "physical_evidence_status": "recorded" if not sorted_codes else "blocked",
            "satisfies_s25_attribution_v2_gate": not sorted_codes,
        }
        return result


def verify_recorded_attribution_v2_ledger(
    root: Path, ledger_relative: str = DEFAULT_LEDGER
) -> dict[str, object]:
    """Reopen a pending/recorded/blocked v2 ledger without trusting its verdict."""
    ledger_path = _safe_repo_path(root, ledger_relative)
    try:
        ledger = load_strict_json(ledger_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AttributionV2CheckError("could not open v2 ledger") from error
    if (
        not isinstance(ledger, Mapping)
        or ledger.get("record_kind") != "s25_attribution_v2_ledger"
    ):
        raise AttributionV2CheckError("v2 ledger has the wrong record kind")
    if ledger.get("ledger_schema_version") != "2.0.0":
        raise AttributionV2CheckError("v2 ledger has the wrong schema version")
    protocol_bytes = _read_protocol(root)
    protocol_sha256 = _sha256(protocol_bytes)
    if (
        ledger.get("protocol_file") != PROTOCOL_RELATIVE
        or ledger.get("protocol_sha256") != protocol_sha256
    ):
        raise AttributionV2CheckError("v2 ledger protocol binding is invalid")
    status = ledger.get("status")
    if status == "pending":
        if (
            not isinstance(ledger.get("missing_precondition"), str)
            or not ledger["missing_precondition"]
        ):
            raise AttributionV2CheckError(
                "pending v2 ledger requires a named precondition"
            )
        if (
            ledger.get("evidence_file") is not None
            or ledger.get("evidence_sha256") is not None
        ):
            raise AttributionV2CheckError("pending v2 ledger must not bind evidence")
        return dict(ledger)
    if status not in {"recorded", "blocked"}:
        raise AttributionV2CheckError(
            "v2 ledger status must be pending, recorded, or blocked"
        )
    evidence_relative = ledger.get("evidence_file")
    if not isinstance(evidence_relative, str) or not _safe_relative(evidence_relative):
        raise AttributionV2CheckError("recorded v2 ledger evidence path is unsafe")
    if not evidence_relative.startswith(EVIDENCE_ROOT):
        raise AttributionV2CheckError(
            "recorded v2 evidence must remain under its evidence root"
        )
    evidence_path = _safe_repo_path(root, evidence_relative)
    try:
        evidence_bytes = evidence_path.read_bytes()
    except OSError as error:
        raise AttributionV2CheckError("recorded v2 evidence is unavailable") from error
    if ledger.get("evidence_sha256") != _sha256(evidence_bytes):
        raise AttributionV2CheckError("recorded v2 evidence sha256 mismatch")
    try:
        evidence = load_strict_json(evidence_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AttributionV2CheckError(
            "recorded v2 evidence is not strict UTF-8 JSON"
        ) from error
    if not isinstance(evidence, Mapping):
        raise AttributionV2CheckError("recorded v2 evidence must be an object")
    authorized_device = _read_authorized_device_pin(root)
    _validate_host_evidence(evidence, authorized_device)
    if evidence.get("protocol_sha256") != protocol_sha256 or evidence.get(
        "protocol_sha256"
    ) != ledger.get("protocol_sha256"):
        raise AttributionV2CheckError(
            "recorded v2 evidence protocol binding is invalid"
        )
    identity = _mapping(ledger, "identity")
    _validate_identity(identity)
    _validate_identity(_mapping(evidence, "identity"))
    if dict(identity) != dict(_mapping(evidence, "identity")):
        raise AttributionV2CheckError(
            "v2 ledger identity binding disagrees with evidence"
        )
    if not isinstance(ledger.get("device"), Mapping) or dict(
        _mapping(ledger, "device")
    ) != dict(_mapping(evidence, "device")):
        raise AttributionV2CheckError(
            "v2 ledger device binding disagrees with evidence"
        )
    observation = _mapping(evidence, "observation")
    recomputed = AttributionV2HostCheck(root).classify(observation, identity)
    if recomputed.get("protocol_sha256") != evidence.get("protocol_sha256"):
        raise AttributionV2CheckError("recorded v2 protocol digest was not recomputed")
    if recomputed["blocking_codes"] != evidence.get("blocking_codes"):
        raise AttributionV2CheckError("recorded v2 blockers were not recomputed")
    if recomputed["device"] != evidence.get("device"):
        raise AttributionV2CheckError("recorded v2 device was not recomputed")
    if recomputed["physical_evidence_status"] != evidence.get(
        "physical_evidence_status"
    ):
        raise AttributionV2CheckError("recorded v2 status was not recomputed")
    if evidence.get("physical_evidence_status") == "recorded" and evidence.get(
        "blocking_codes"
    ):
        raise AttributionV2CheckError("recorded v2 evidence contains blockers")
    if ledger.get("status") != evidence.get("physical_evidence_status"):
        raise AttributionV2CheckError("v2 ledger status disagrees with evidence")
    return dict(evidence)


def _derive_blockers(
    observation: Mapping[str, object],
    protocol_sha256: str,
    prepared_identity: Mapping[str, object],
    authorized_device: Mapping[str, object],
) -> set[str]:
    blockers: set[str] = set()
    if observation.get("protocol_sha256") != protocol_sha256:
        blockers.add("ATTRIBUTION.PROTOCOL_DIGEST")
    identity = _mapping(observation, "identity")
    if dict(identity) != dict(prepared_identity):
        blockers.add("ATTRIBUTION.CANDIDATE_IDENTITY")
    if not _authorized_device(_mapping(observation, "device")) or dict(
        _mapping(observation, "device")
    ) != dict(authorized_device):
        blockers.add("ATTRIBUTION.CANDIDATE_IDENTITY")

    source = _mapping(observation, "source_arm")
    source_codes = _derive_source(source)
    _assert_arm(source, source_codes, blockers)

    backend_value = observation.get("backend_arm")
    backend = (
        cast(Mapping[str, object] | None, backend_value)
        if isinstance(backend_value, Mapping)
        else None
    )
    backend_codes, measured_durations = _derive_backend(backend)
    if backend is not None:
        _assert_arm(backend, backend_codes, blockers)
    elif observation.get("selector_arm") is not None:
        blockers.add("ATTRIBUTION.SELECTOR_ONLY")

    selector_value = observation.get("selector_arm")
    selector = (
        cast(Mapping[str, object] | None, selector_value)
        if isinstance(selector_value, Mapping)
        else None
    )
    selector_codes = _derive_selector(
        selector, _integer_list(source.get("timestamps_ns"))
    )
    if selector is not None:
        _assert_arm(selector, selector_codes, blockers)

    if backend is not None:
        _assert_value(
            source, "initial_cooldown_recovered", _cooldown_recovered(source), blockers
        )
    if backend is not None and not _cooldown_recovered(source):
        blockers.add("ATTRIBUTION.THERMAL_STATE_MISMATCH")

    replicates_value = observation.get("end_to_end_replicates")
    replicates = _mapping_list(replicates_value)
    replicate_codes = _derive_replicates(
        replicates,
        source,
        backend,
        source_codes,
        backend_codes,
        selector_codes,
        measured_durations,
    )
    blockers.update(replicate_codes)

    adapter = _validation_adapter(
        observation,
        source,
        backend,
        selector,
        replicates,
        measured_durations,
        observation.get("protocol_sha256") == protocol_sha256,
    )
    for issue in validate_attribution_v2(adapter, "observation.json"):
        if issue.code in V2_CODES:
            blockers.add(issue.code)
    return blockers


def _derive_source(source: Mapping[str, object]) -> set[str]:
    timestamps = _integer_list(source.get("timestamps_ns"))
    codes: set[str] = set()
    if len(timestamps) != 300:
        codes.add("ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT")
    if len(timestamps) >= 2 and any(
        second <= first for first, second in pairwise(timestamps)
    ):
        codes.add("ATTRIBUTION.SOURCE_TIMESTAMP_INVALID")
    fps = source.get("observed_ae_target_fps")
    if not isinstance(fps, Mapping) or fps.get("lower") != 30 or fps.get("upper") != 30:
        codes.add("ATTRIBUTION.SOURCE_CONFIGURATION")
    if len(timestamps) == 300 and any(
        not source_supports(timestamps, cadence) for cadence in TARGET_CADENCES_FPS
    ):
        codes.add("ATTRIBUTION.SOURCE_LIMITED")
    _assert_value(source, "measured_count", len(timestamps), codes)
    return codes


def _derive_backend(
    backend: Mapping[str, object] | None,
) -> tuple[set[str], list[int]]:
    if backend is None:
        return {"ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT"}, []
    codes: set[str] = set()
    callbacks = _mapping_list(backend.get("terminal_callbacks"))
    delivered = _integer_list(backend.get("delivered_callback_ids"))
    submitted = _integer_list(backend.get("submitted_inference_ids"))
    pending = _integer_list(backend.get("pending_inference_ids"))
    codes.update(
        _callback_blockers(
            delivered,
            submitted,
            pending,
            callbacks,
            bool(backend.get("raw_evidence_overflow")),
        )
    )
    if any(callback.get("phase") is None for callback in callbacks):
        codes.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    completed = [
        callback
        for callback in callbacks
        if callback.get("terminal_class")
        in {"admitted_completed", "admitted_inference_failed"}
    ]
    warmup = [callback for callback in completed if callback.get("phase") == "warmup"]
    measured = [
        callback for callback in completed if callback.get("phase") == "measured"
    ]
    arm_started = _nonnegative_int(backend.get("arm_started_ns"))
    warmup_completions: list[int] = []
    for callback in warmup:
        completion = _nonnegative_int(callback.get("completion_timestamp_ns"))
        if completion is not None:
            warmup_completions.append(completion)
    warmup_elapsed = max(warmup_completions, default=0) - (arm_started or 0)
    if (
        len(warmup_completions) < 20
        or warmup_elapsed < 2_000_000_000
        or len(measured) != 100
    ):
        codes.add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
    exact_durations: list[int] = []
    for callback in measured:
        admission = _nonnegative_int(callback.get("admission_observed_session_ns"))
        completion = _nonnegative_int(callback.get("completion_timestamp_ns"))
        if admission is None or completion is None or completion < admission:
            codes.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
        else:
            exact_durations.append(completion - admission)
    if len(exact_durations) != len(measured) or exact_durations != _integer_list(
        backend.get("service_durations_ns")
    ):
        codes.add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
    if len(exact_durations) == 100 and any(
        not backend_supports(exact_durations, cadence)
        for cadence in TARGET_CADENCES_FPS
    ):
        codes.add("ATTRIBUTION.BACKEND_LIMITED")
    failures = sum(
        callback.get("terminal_class") == "admitted_inference_failed"
        for callback in completed
    )
    if failures != _nonnegative_int(backend.get("inference_failures")) or failures != 0:
        codes.add("ATTRIBUTION.INFERENCE_FAILURE")
    _assert_value(backend, "measured_count", len(exact_durations), codes)
    _assert_value(backend, "warmup_count", len(warmup_completions), codes)
    _assert_value(backend, "warmup_elapsed_ns", warmup_elapsed, codes)
    predicates = _mapping(backend, "warmup_predicates")
    _assert_value(predicates, "completion_count", len(warmup_completions), codes)
    _assert_value(predicates, "elapsed_ns", warmup_elapsed, codes)
    _assert_value(
        predicates, "completion_count_satisfied", len(warmup_completions) >= 20, codes
    )
    _assert_value(
        predicates, "elapsed_satisfied", warmup_elapsed >= 2_000_000_000, codes
    )
    return codes, exact_durations


def _derive_selector(
    selector: Mapping[str, object] | None, timestamps: list[int]
) -> set[str]:
    if selector is None:
        return {"ATTRIBUTION.SELECTOR_DIVERGENCE"}
    runs = _mapping_list(selector.get("runs"))
    production: dict[str, list[int]] = {}
    host: dict[str, list[int]] = {}
    codes: set[str] = set()
    for run in runs:
        cadence = run.get("cadence_fps")
        key = str(cadence)
        expected = (
            selector_timestamps(timestamps, cadence)
            if cadence in TARGET_CADENCES_FPS
            else []
        )
        actual_production = _integer_list(run.get("production_selected_timestamps_ns"))
        actual_host = _integer_list(run.get("host_selected_timestamps_ns"))
        production[key] = actual_production
        host[key] = actual_host
        if actual_production != expected or actual_host != expected:
            codes.add("ATTRIBUTION.SELECTOR_DIVERGENCE")
    if (
        set(production) != {str(cadence) for cadence in TARGET_CADENCES_FPS}
        or production != host
    ):
        codes.add("ATTRIBUTION.SELECTOR_DIVERGENCE")
    if set(production) == {str(cadence) for cadence in TARGET_CADENCES_FPS}:
        counts = [len(production[str(cadence)]) for cadence in TARGET_CADENCES_FPS]
        if not all(lower < upper for lower, upper in pairwise(counts)):
            codes.add("ATTRIBUTION.SELECTOR_DIVERGENCE")
    return codes


def _derive_replicates(
    replicates: list[Mapping[str, object]],
    source: Mapping[str, object],
    backend: Mapping[str, object] | None,
    source_codes: set[str],
    backend_codes: set[str],
    selector_codes: set[str],
    measured_durations: list[int],
) -> set[str]:
    codes: set[str] = set()
    if len(replicates) != 2 or [replicate.get("order") for replicate in replicates] != [
        list(order) for order in REPLICATE_ORDERS
    ]:
        codes.add("ATTRIBUTION.REPLICATE_DISAGREEMENT")
        return codes
    source_thermal = _nonnegative_int(source.get("thermal_status"))
    for replicate in replicates:
        runs = _mapping_list(replicate.get("runs"))
        order = _integer_list(replicate.get("order"))
        if [run.get("cadence_fps") for run in runs] != order or len(runs) != 3:
            codes.add("ATTRIBUTION.REPLICATE_DISAGREEMENT")
        cooldown = _integer_list(replicate.get("cooldown_thermal_statuses"))
        if source_thermal is None or source_thermal not in cooldown:
            codes.add("ATTRIBUTION.THERMAL_STATE_MISMATCH")
        run_codes: set[str] = set()
        completion_rates: dict[int, tuple[int, int]] = {}
        opportunity_rates: dict[int, tuple[int, int]] = {}
        for run in runs:
            initial = _nonnegative_int(run.get("initial_thermal_status"))
            final = _nonnegative_int(run.get("final_thermal_status"))
            if initial != final or initial != source_thermal:
                run_codes.add("ATTRIBUTION.THERMAL_STATE_MISMATCH")
            if initial != final:
                run_codes.add("ATTRIBUTION.ENVIRONMENT_MISMATCH")
            callbacks = _mapping_list(run.get("terminal_callbacks"))
            run_codes.update(
                _callback_blockers(
                    _integer_list(run.get("delivered_callback_ids")),
                    _integer_list(run.get("submitted_inference_ids")),
                    _integer_list(run.get("pending_inference_ids")),
                    callbacks,
                    False,
                )
            )
            run_codes_from_raw, completion_count, opportunity_count, span = (
                _derive_end_to_end_run(run, source_thermal)
            )
            run_codes.update(run_codes_from_raw)
            if run_codes_from_raw:
                run_codes.add("ATTRIBUTION.END_TO_END_NOT_DISTINCT")
            cadence = _nonnegative_int(run.get("cadence_fps")) or 0
            completion_rates[cadence] = (completion_count, span)
            opportunity_rates[cadence] = (opportunity_count, span)
        duration_assertion_failed = (
            backend is None
            or measured_durations != _integer_list(backend.get("service_durations_ns"))
        )
        if source_codes or backend_codes or selector_codes or duration_assertion_failed:
            run_codes.add("ATTRIBUTION.END_TO_END_NOT_DISTINCT")
        if not _strictly_increasing_rates(
            completion_rates
        ) or not _strictly_increasing_rates(opportunity_rates):
            run_codes.add("ATTRIBUTION.END_TO_END_NOT_DISTINCT")
        if not run_codes and replicate.get("status") != "recorded":
            codes.add("BLOCKING_CODES.MISMATCH")
        expected_status = "recorded" if not run_codes else "blocked"
        if replicate.get("status") != expected_status or _string_list(
            replicate.get("blocking_codes")
        ) != sorted(run_codes):
            codes.add("BLOCKING_CODES.MISMATCH")
        codes.update(run_codes)
    return codes


def _derive_end_to_end_run(
    run: Mapping[str, object], source_thermal: int | None
) -> tuple[set[str], int, int, int]:
    codes: set[str] = set()
    callbacks = _mapping_list(run.get("terminal_callbacks"))
    arm_started = _nonnegative_int(run.get("arm_started_ns"))
    if not _valid_arm_two_chronology(callbacks, arm_started):
        codes.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    phases = _replay_arm_two_phases(callbacks, arm_started)
    if len(phases) != len(callbacks):
        codes.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    if any(
        callback.get("phase") != phase for callback, phase in zip(callbacks, phases)
    ):
        codes.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    if any(not _valid_end_to_end_operands(callback) for callback in callbacks):
        codes.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")

    measured_callbacks = [
        callback for callback, phase in zip(callbacks, phases) if phase == "measured"
    ]
    measured_completions = [
        callback
        for callback in measured_callbacks
        if callback.get("terminal_class")
        in {"admitted_completed", "admitted_inference_failed"}
    ]
    warmup_completions = [
        callback
        for callback, phase in zip(callbacks, phases)
        if phase == "warmup"
        and callback.get("terminal_class")
        in {"admitted_completed", "admitted_inference_failed"}
    ]
    completion_times = [
        _nonnegative_int(callback.get("completion_timestamp_ns"))
        for callback in warmup_completions
    ]
    valid_completion_times = [value for value in completion_times if value is not None]
    warmup_elapsed = (
        max(valid_completion_times) - arm_started
        if arm_started is not None and valid_completion_times
        else 0
    )
    if (
        len(warmup_completions) < 20
        or warmup_elapsed < 2_000_000_000
        or len(measured_completions) != 100
    ):
        codes.add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
    if any(
        callback.get("terminal_class") == "admitted_inference_failed"
        for callback in callbacks
    ):
        codes.add("ATTRIBUTION.INFERENCE_FAILURE")

    measured_captures = _integer_list(
        [callback.get("capture_timestamp_ns") for callback in measured_callbacks]
    )
    span = (
        measured_captures[-1] - measured_captures[0]
        if len(measured_captures) >= 2
        else 0
    )
    if len(measured_captures) != len(measured_callbacks) or any(
        second <= first for first, second in pairwise(measured_captures)
    ):
        codes.add("ATTRIBUTION.END_TO_END_NOT_DISTINCT")
    if _nonnegative_int(run.get("measured_span_ns")) != span:
        codes.add("ATTRIBUTION.END_TO_END_NOT_DISTINCT")
    initial = _nonnegative_int(run.get("initial_thermal_status"))
    final = _nonnegative_int(run.get("final_thermal_status"))
    if initial != final or initial != source_thermal:
        codes.add("ATTRIBUTION.THERMAL_STATE_MISMATCH")
    if initial != final:
        codes.add("ATTRIBUTION.ENVIRONMENT_MISMATCH")
    return (
        codes,
        sum(
            callback.get("terminal_class") == "admitted_completed"
            for callback in measured_completions
        ),
        sum(callback.get("cadence_due") is True for callback in measured_callbacks),
        span,
    )


def _valid_end_to_end_operands(callback: Mapping[str, object]) -> bool:
    admission = _nonnegative_int(callback.get("admission_observed_session_ns"))
    completion = _nonnegative_int(callback.get("completion_timestamp_ns"))
    return (
        callback.get("phase") is not None
        and admission is not None
        and completion is not None
        and completion >= admission
    )


def _valid_arm_two_chronology(
    callbacks: list[Mapping[str, object]], arm_started: int | None
) -> bool:
    if arm_started is None:
        return False
    timestamps = [
        _nonnegative_int(callback.get("completion_timestamp_ns"))
        for callback in callbacks
    ]
    valid_timestamps = [timestamp for timestamp in timestamps if timestamp is not None]
    return (
        len(valid_timestamps) == len(callbacks)
        and all(timestamp >= arm_started for timestamp in valid_timestamps)
        and all(second >= first for first, second in pairwise(valid_timestamps))
    )


def _replay_arm_two_phases(
    callbacks: list[Mapping[str, object]], arm_started: int | None
) -> list[str]:
    phases: list[str] = []
    completion_count = 0
    boundary_reached = False
    for callback in callbacks:
        terminal = callback.get("terminal_class")
        is_completion = terminal in {
            "admitted_completed",
            "admitted_inference_failed",
        }
        prior_completions = completion_count
        if is_completion:
            completion_count += 1
        completion = _nonnegative_int(callback.get("completion_timestamp_ns"))
        elapsed = (
            completion - arm_started
            if completion is not None and arm_started is not None
            else -1
        )
        transition_ready = completion_count >= 20 and elapsed >= 2_000_000_000
        boundary_completion = is_completion and prior_completions < 20
        phases.append(
            "warmup"
            if not boundary_reached and (not transition_ready or boundary_completion)
            else "measured"
        )
        if transition_ready:
            boundary_reached = True
    return phases


def _strictly_increasing_rates(rates: Mapping[int, tuple[int, int]]) -> bool:
    ordered = [rates.get(cadence, (0, 0)) for cadence in TARGET_CADENCES_FPS]
    return all(
        left[0] * right[1] < right[0] * left[1]
        for left, right in pairwise(ordered)
        if left[1] > 0 and right[1] > 0
    ) and all(span > 0 for _, span in ordered)


def _validation_adapter(
    observation: Mapping[str, object],
    source: Mapping[str, object],
    backend: Mapping[str, object] | None,
    selector: Mapping[str, object] | None,
    replicates: list[Mapping[str, object]],
    measured_durations: list[int],
    protocol_matches: bool,
) -> dict[str, object]:
    source_timestamps = _integer_list(source.get("timestamps_ns"))
    selector_production: dict[str, list[int]] = {}
    selector_host: dict[str, list[int]] = {}
    if selector is not None:
        for run in _mapping_list(selector.get("runs")):
            selector_production[str(run.get("cadence_fps"))] = _integer_list(
                run.get("production_selected_timestamps_ns")
            )
            selector_host[str(run.get("cadence_fps"))] = _integer_list(
                run.get("host_selected_timestamps_ns")
            )
    adapter_replicates: list[dict[str, object]] = []
    for replicate in replicates:
        runs = _mapping_list(replicate.get("runs"))
        admitted = _exact_scaled_rate_values(runs, admitted=True)
        opportunities = _exact_scaled_rate_values(runs, admitted=False)
        adapter_replicates.append(
            {
                "cadence_order": _integer_list(replicate.get("order")),
                "admitted_completion_rates": admitted,
                "cadence_opportunity_rates": opportunities,
                "source_supported": {
                    str(cadence): source_supports(source_timestamps, cadence)
                    for cadence in TARGET_CADENCES_FPS
                },
                "backend_supported": {
                    str(cadence): backend_supports(measured_durations, cadence)
                    for cadence in TARGET_CADENCES_FPS
                },
            }
        )
    return {
        "protocol_version": "2.0.0" if protocol_matches else "invalid",
        "identity_matches": True,
        "environment_matches": all(
            run.get("initial_thermal_status") == run.get("final_thermal_status")
            for replicate in replicates
            for run in _mapping_list(replicate.get("runs"))
        ),
        "source_arm": {"measured_timestamps_ns": source_timestamps},
        "backend_arm": None
        if backend is None
        else {
            "measured_service_durations_ns": measured_durations,
            "inference_failures": _nonnegative_int(backend.get("inference_failures")),
        },
        "selector_arm": None
        if selector is None
        else {
            "production_selections": selector_production,
            "host_selections": selector_host,
        },
        "callback_arm": None
        if backend is None
        else {
            "delivered_callback_ids": _integer_list(
                backend.get("delivered_callback_ids")
            ),
            "terminal_callbacks": _mapping_list(backend.get("terminal_callbacks")),
            "submitted_inference_ids": _integer_list(
                backend.get("submitted_inference_ids")
            ),
            "pending_inference_ids": _integer_list(
                backend.get("pending_inference_ids")
            ),
        },
        "replicates": adapter_replicates,
    }


def _exact_scaled_rate_values(
    runs: list[Mapping[str, object]], *, admitted: bool
) -> dict[str, int]:
    inputs: list[tuple[str, int, int]] = []
    for run in runs:
        _, completion_count, opportunity_count, span = _derive_end_to_end_run(run, None)
        count = completion_count if admitted else opportunity_count
        inputs.append((str(run.get("cadence_fps")), count, span))
    positive_spans = [span for _, _, span in inputs if span > 0]
    common_span = lcm(*positive_spans) if positive_spans else 1
    return {
        cadence: count * (common_span // span) if span > 0 else 0
        for cadence, count, span in inputs
    }


def _callback_blockers(
    delivered: list[int],
    submitted: list[int],
    pending: list[int],
    callbacks: list[Mapping[str, object]],
    overflow: bool,
) -> set[str]:
    codes: set[str] = set()
    terminal_ids = [callback.get("callback_id") for callback in callbacks]
    admitted_ids = [
        callback.get("callback_id")
        for callback in callbacks
        if callback.get("terminal_class")
        in {"admitted_completed", "admitted_inference_failed"}
    ]
    if overflow or len(callbacks) != len(delivered):
        codes.add("ATTRIBUTION.CALLBACK_CONSERVATION")
    if (
        len(delivered) != len(set(delivered))
        or len(terminal_ids) != len(set(terminal_ids))
        or set(delivered) != set(terminal_ids)
        or set(submitted) != set(admitted_ids)
        or pending
    ):
        codes.add("ATTRIBUTION.TERMINAL_CLOSURE")
    for callback in callbacks:
        if not _terminal_matches(callback):
            codes.add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    return codes


def _terminal_matches(callback: Mapping[str, object]) -> bool:
    terminal = callback.get("terminal_class")
    production = callback.get("production_classification")
    cadence_due = callback.get("cadence_due")
    slot_available = callback.get("slot_available")
    if terminal in {"admitted_completed", "admitted_inference_failed"}:
        return production == "submit"
    if terminal == "cadence_only":
        return (
            cadence_due is False
            and slot_available is True
            and production == "cadence_selection"
        )
    if terminal == "backpressure_only":
        return (
            cadence_due is True
            and slot_available is False
            and production == "backpressure"
        )
    if terminal == "cadence_and_backpressure":
        return (
            cadence_due is False
            and slot_available is False
            and production == "backpressure"
        )
    if terminal == "preprocessing_failed":
        return production == "preprocessing_failed"
    if terminal == "session_stopping":
        return production == "session_stopping"
    return False


def _validate_observation_schema(observation: Mapping[str, object]) -> None:
    errors = sorted(
        Draft202012Validator(load_m05b_attribution_observation_v2_schema()).iter_errors(
            observation
        ),
        key=lambda error: list(error.absolute_path),
    )
    if errors:
        raise AttributionV2CheckError("observation schema validation failed")
    if observation.get("record_kind") != OBSERVATION_KIND:
        raise AttributionV2CheckError("observation has the wrong record_kind")


def _sanitize_observation(
    observation: Mapping[str, object],
    prepared_identity: Mapping[str, object],
    authorized_device: Mapping[str, object],
) -> dict[str, object]:
    projected = dict(observation)
    projected["device"] = dict(
        _sanitize_device(_mapping(observation, "device"), authorized_device)
    )
    projected["identity"] = _sanitize_identity(
        _mapping(observation, "identity"), prepared_identity
    )
    backend = observation.get("backend_arm")
    if isinstance(backend, Mapping):
        backend_projection = dict(backend)
        backend_projection["terminal_callbacks"] = [
            dict(callback)
            for callback in _mapping_list(backend.get("terminal_callbacks"))
        ]
        projected["backend_arm"] = backend_projection
    projected_replicates: list[dict[str, object]] = []
    for replicate in _mapping_list(observation.get("end_to_end_replicates")):
        replicate_projection = dict(replicate)
        projected_runs: list[dict[str, object]] = []
        for run in _mapping_list(replicate.get("runs")):
            run_projection = dict(run)
            run_projection["session_reference"] = _sanitize_session_reference(
                run.get("session_reference")
            )
            run_projection["terminal_callbacks"] = [
                dict(callback)
                for callback in _mapping_list(run.get("terminal_callbacks"))
            ]
            projected_runs.append(run_projection)
        replicate_projection["runs"] = projected_runs
        projected_replicates.append(replicate_projection)
    projected["end_to_end_replicates"] = projected_replicates
    return projected


def _sanitize_device(
    device: Mapping[str, object], authorized_device: Mapping[str, object]
) -> Mapping[str, object]:
    identity = device.get("device_identity")
    manufacturer = device.get("manufacturer")
    model = device.get("model")
    if (
        isinstance(identity, str)
        and _valid_device_identity(identity, manufacturer, model)
        and _authorized_device(device)
        and dict(device) == dict(authorized_device)
    ):
        return dict(authorized_device)
    return _invalid_device_sentinel()


def _invalid_device_sentinel() -> dict[str, object]:
    return {
        "device_class": "physical_device",
        "device_identity": "invalid/invalid/invalid/invalid",
        "manufacturer": "invalid",
        "model": "invalid",
        "api_level": 1,
        "android_release": "invalid",
        "os_build": "invalid",
    }


def _sanitize_identity(
    identity: Mapping[str, object], prepared: Mapping[str, object]
) -> dict[str, object]:
    projected: dict[str, object] = {}
    for key in IDENTITY_KEYS:
        if identity.get(key) == prepared.get(key):
            projected[key] = prepared[key]
        elif key in {"app_git_sha", "probe_git_sha"}:
            projected[key] = "0" * 40 if prepared.get(key) != "0" * 40 else "1" * 40
        elif key in {"app_apk_sha256", "test_apk_sha256", "model_sha256"}:
            projected[key] = "0" * 64 if prepared.get(key) != "0" * 64 else "1" * 64
        elif key == "delegate":
            projected[key] = next(
                value for value in ("cpu", "gpu", "nnapi") if value != prepared.get(key)
            )
        else:
            projected[key] = "untrusted"
    return projected


def _sanitize_session_reference(value: object) -> str:
    if not isinstance(value, str) or not SESSION_REFERENCE.fullmatch(value):
        return "exports/" + "0" * 64
    if re.fullmatch(r"exports/[0-9a-f]{64}", value):
        return value
    return "exports/" + _sha256(value.encode("utf-8"))


def _valid_device_identity(
    device_identity: object, manufacturer: object, model: object
) -> bool:
    if not isinstance(device_identity, str):
        return False
    parts = device_identity.split("/")
    return (
        len(parts) == 4
        and all(DEVICE_TOKEN.fullmatch(part) for part in parts)
        and isinstance(manufacturer, str)
        and isinstance(model, str)
        and parts[0].lower() == manufacturer.lower()
        and parts[1] == model
    )


def _validate_host_evidence(
    evidence: Mapping[str, object], authorized_device: Mapping[str, object]
) -> None:
    required = {
        "evidence_schema_version",
        "record_kind",
        "sanitized",
        "non_participant_observation",
        "protocol_sha256",
        "device",
        "identity",
        "observation",
        "blocking_codes",
        "physical_evidence_status",
        "satisfies_s25_attribution_v2_gate",
    }
    if (
        set(evidence) != required
        or evidence.get("evidence_schema_version") != "2.0.0"
        or evidence.get("record_kind") != HOST_EVIDENCE_KIND
    ):
        raise AttributionV2CheckError("host evidence schema is closed or invalid")
    if (
        evidence.get("sanitized") is not True
        or evidence.get("non_participant_observation") is not True
    ):
        raise AttributionV2CheckError(
            "host evidence must be sanitized non-participant evidence"
        )
    if not isinstance(evidence.get("protocol_sha256"), str) or not SHA256.fullmatch(
        cast(str, evidence["protocol_sha256"])
    ):
        raise AttributionV2CheckError("host evidence protocol digest is invalid")
    device = _mapping(evidence, "device")
    if set(device) != DEVICE_KEYS or (
        dict(device) != dict(authorized_device)
        and dict(device) != _invalid_device_sentinel()
    ):
        raise AttributionV2CheckError("host evidence device binding is invalid")
    _validate_identity(_mapping(evidence, "identity"))
    observation = _mapping(evidence, "observation")
    _validate_observation_schema(observation)
    nested_device = _mapping(observation, "device")
    if set(nested_device) != DEVICE_KEYS or (
        dict(nested_device) != dict(authorized_device)
        and dict(nested_device) != _invalid_device_sentinel()
    ):
        raise AttributionV2CheckError("host evidence nested device binding is invalid")
    if _string_list(evidence.get("blocking_codes")) != sorted(
        set(_string_list(evidence.get("blocking_codes")))
    ):
        raise AttributionV2CheckError(
            "host evidence blockers must be sorted and unique"
        )
    if evidence.get("physical_evidence_status") not in {"recorded", "blocked"}:
        raise AttributionV2CheckError("host evidence status is invalid")
    if evidence.get("satisfies_s25_attribution_v2_gate") is not (
        not _string_list(evidence.get("blocking_codes"))
    ):
        raise AttributionV2CheckError("host evidence gate is not derived from blockers")


def _validate_reference(reference: Mapping[str, object]) -> None:
    if not isinstance(reference.get("protocol_sha256"), str) or not SHA256.fullmatch(
        cast(str, reference["protocol_sha256"])
    ):
        raise AttributionV2CheckError("reference protocol digest is invalid")
    if not isinstance(reference.get("sha256"), str) or not SHA256.fullmatch(
        cast(str, reference["sha256"])
    ):
        raise AttributionV2CheckError("reference digest is invalid")
    if not isinstance(reference.get("relative_path"), str) or not _safe_relative(
        cast(str, reference["relative_path"])
    ):
        raise AttributionV2CheckError("reference path is unsafe")
    if (
        type(reference.get("length_bytes")) is not int
        or cast(int, reference["length_bytes"]) < 0
    ):
        raise AttributionV2CheckError("reference length is invalid")


def _validate_identity(identity: Mapping[str, object]) -> None:
    if set(identity) != IDENTITY_KEYS:
        raise AttributionV2CheckError("prepared identity is incomplete")
    for key in ("app_git_sha", "probe_git_sha"):
        if not isinstance(identity.get(key), str) or not GIT_SHA.fullmatch(
            cast(str, identity[key])
        ):
            raise AttributionV2CheckError("prepared Git identity is malformed")
    for key in ("app_apk_sha256", "test_apk_sha256", "model_sha256"):
        if not isinstance(identity.get(key), str) or not SHA256.fullmatch(
            cast(str, identity[key])
        ):
            raise AttributionV2CheckError("prepared binary identity is malformed")
    for key in ("runtime_version", "backend_version", "delegate"):
        if not isinstance(identity.get(key), str) or not IDENTITY_TOKEN.fullmatch(
            cast(str, identity[key])
        ):
            raise AttributionV2CheckError("prepared runtime identity is malformed")
    if identity.get("delegate") not in {"cpu", "gpu", "nnapi"}:
        raise AttributionV2CheckError("prepared delegate is unsupported")


def _authorized_device(device: Mapping[str, object]) -> bool:
    model = device.get("model")
    return (
        device.get("device_class") == "physical_device"
        and str(device.get("manufacturer", "")).lower() == "samsung"
        and isinstance(model, str)
        and model.upper().startswith("SM-S938")
        and device.get("api_level") == 36
        and _valid_device_identity(
            device.get("device_identity", ""), device.get("manufacturer"), model
        )
        and isinstance(device.get("android_release"), str)
        and DEVICE_TOKEN.fullmatch(cast(str, device["android_release"])) is not None
        and isinstance(device.get("os_build"), str)
        and DEVICE_TOKEN.fullmatch(cast(str, device["os_build"])) is not None
    )


def _cooldown_recovered(source: Mapping[str, object]) -> bool:
    status = _nonnegative_int(source.get("thermal_status"))
    statuses = _integer_list(source.get("initial_cooldown_thermal_statuses"))
    return status is not None and bool(statuses) and status in statuses


def _assert_arm(
    arm: Mapping[str, object], expected: set[str], blockers: set[str]
) -> None:
    status = "recorded" if not expected else "blocked"
    if arm.get("status") != status or _string_list(arm.get("blocking_codes")) != sorted(
        expected
    ):
        blockers.add("BLOCKING_CODES.MISMATCH")
    blockers.update(expected)


def _assert_value(
    mapping: Mapping[str, object], key: str, expected: object, codes: set[str]
) -> None:
    if mapping.get(key) != expected:
        codes.add("BLOCKING_CODES.MISMATCH")


def _read_protocol(root: Path) -> bytes:
    path = _safe_repo_path(root, PROTOCOL_RELATIVE)
    try:
        return path.read_bytes()
    except OSError as error:
        raise AttributionV2CheckError(
            "canonical attribution v2 protocol is unavailable"
        ) from error


def _read_authorized_device_pin(root: Path) -> dict[str, object]:
    try:
        protocol = load_strict_json(_read_protocol(root).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AttributionV2CheckError(
            "canonical attribution v2 protocol is invalid"
        ) from error
    if not isinstance(protocol, Mapping):
        raise AttributionV2CheckError("canonical attribution v2 protocol is invalid")
    pin = protocol.get("authorized_device")
    if (
        not isinstance(pin, Mapping)
        or set(pin) != DEVICE_KEYS
        or not _authorized_device(pin)
    ):
        raise AttributionV2CheckError("canonical attribution v2 device pin is invalid")
    return dict(pin)


def _read_exact_bytes(path: Path, reference: Mapping[str, object]) -> bytes:
    try:
        data = path.read_bytes()
    except OSError as error:
        raise AttributionV2CheckError(
            "awaiting_recovery: transferred bytes unavailable"
        ) from error
    if len(data) != reference.get("length_bytes") or _sha256(data) != reference.get(
        "sha256"
    ):
        raise AttributionV2CheckError("exact-byte digest or length mismatch")
    return data


def _safe_repo_path(root: Path, relative: str) -> Path:
    if not _safe_relative(relative):
        raise AttributionV2CheckError("repository-relative path is unsafe")
    root_resolved = root.resolve()
    path = (root / relative).resolve()
    if path != root_resolved and root_resolved not in path.parents:
        raise AttributionV2CheckError("repository-relative path escapes root")
    return path


def _safe_relative(value: str) -> bool:
    return bool(value) and bool(SAFE_RELATIVE.fullmatch(value)) and "\\" not in value


def _fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _mapping(value: Mapping[str, object], key: str) -> Mapping[str, object]:
    nested = value.get(key)
    if not isinstance(nested, Mapping):
        raise AttributionV2CheckError(f"field {key!r} must be an object")
    return cast(Mapping[str, object], nested)


def _mapping_list(value: object) -> list[Mapping[str, object]]:
    if not isinstance(value, list) or not all(
        isinstance(item, Mapping) for item in value
    ):
        return []
    return [cast(Mapping[str, object], item) for item in value]


def _integer_list(value: object) -> list[int]:
    if not isinstance(value, list) or not all(type(item) is int for item in value):
        return []
    return cast(list[int], value)


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        return []
    return cast(list[str], value)


def _nonnegative_int(value: object) -> int | None:
    return value if type(value) is int and value >= 0 else None


def _parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--transcript", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--prepared-identity", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--subprocess-exit-code", type=int, required=True)
    return parser.parse_args(list(argv))


def main(argv: Sequence[str] | None = None) -> int:
    try:
        arguments = _parse_args(sys.argv[1:] if argv is None else argv)
    except SystemExit as error:
        return 2 if error.code else 0
    try:
        transcript = arguments.transcript.read_text(encoding="utf-8")
        prepared = load_strict_json(
            arguments.prepared_identity.read_text(encoding="utf-8")
        )
        if not isinstance(prepared, Mapping):
            raise AttributionV2CheckError("prepared identity must be an object")
        result = check_attribution_v2(
            transcript,
            subprocess_exit_code=arguments.subprocess_exit_code,
            serial=arguments.serial,
            package=arguments.package,
            prepared_identity=cast(Mapping[str, object], prepared),
            root=arguments.root,
        )
    except (
        AttributionV2CheckError,
        OSError,
        UnicodeDecodeError,
        json.JSONDecodeError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["physical_evidence_status"] == "recorded" else 3


if __name__ == "__main__":
    raise SystemExit(main())
