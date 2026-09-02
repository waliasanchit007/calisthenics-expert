#!/usr/bin/env python3
"""Validate one exact M0.5B sustained run from device references and durable bytes."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

from check_five_run_replay import is_authorized_five_run_gate_target
from observation_contracts.m05b_envelope import canonical_sha256, validate_m05b_document
from observation_contracts.m05b_sustained import validate_sustained_record
from observation_contracts.schema_loader import load_strict_json


APP_PACKAGE = "com.calisthenics.diagnostic.android"
STATUS_TAG = "M05BSustainedEvidence"
SUITE = "com.calisthenics.diagnostic.composition.M05BSustainedDeviceGateTest"
RECORD_KIND = "m05b_sustained_session_reference"
SHA256 = re.compile(r"\A[0-9a-f]{64}\Z")
STATUS_PATTERN = re.compile(
    rf"^INSTRUMENTATION_STATUS: {STATUS_TAG}=(?P<json>\{{.*\}})$", re.MULTILINE
)


class SustainedCheckError(Exception):
    """The observation is malformed or its durable bytes are not yet recoverable."""


def transfer_references_from_device(
    *,
    serial: str,
    package: str,
    record: Mapping[str, Any],
    destination: Path,
    run_adb: Callable[..., subprocess.CompletedProcess[bytes]] = subprocess.run,
) -> Path:
    """Pull every exact reference with binary-safe exec-out and verify before rename."""
    if not serial or any(character.isspace() for character in serial):
        raise SustainedCheckError("device serial is missing or malformed")
    if package != APP_PACKAGE:
        raise SustainedCheckError("unexpected sustained-run package")
    references = _all_references(record)
    destination.mkdir(parents=True, exist_ok=True)
    for reference in references:
        relative = _reference_path(reference)
        expected = _reference_digest(reference)
        remote_relative = (
            f"diagnostic-harness/{relative}" if relative.startswith("exports/") else relative
        )
        try:
            completed = run_adb(
                ["adb", "-s", serial, "exec-out", "run-as", package, "cat", f"files/{remote_relative}"],
                capture_output=True,
                check=False,
            )
        except OSError as error:
            raise SustainedCheckError(f"awaiting_recovery: adb transport failed: {error}") from error
        if completed.returncode != 0:
            detail = completed.stderr.decode("utf-8", errors="replace").strip()
            raise SustainedCheckError(
                "awaiting_recovery: adb could not pull referenced bytes"
                + (f": {detail}" if detail else "")
            )
        data = completed.stdout
        if _sha256(data) != expected:
            raise SustainedCheckError(f"exact-byte digest mismatch for {relative}")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(f".{target.name}.transfer")
        try:
            with temporary.open("wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            if _sha256(temporary.read_bytes()) != expected:
                raise SustainedCheckError(f"host transfer rehash failed for {relative}")
            os.replace(temporary, target)
            _fsync_directory(target.parent)
        finally:
            temporary.unlink(missing_ok=True)
    return destination


def check_sustained_run(
    transcript: str,
    artifact_root: Path,
    *,
    subprocess_exit_code: int,
) -> dict[str, Any]:
    if type(subprocess_exit_code) is not int or subprocess_exit_code != 0:
        raise SustainedCheckError("instrumentation subprocess exited non-zero")
    record = _extract_status_record(transcript)
    local_bytes = _read_and_rehash_references(record, artifact_root)
    frozen_ref = _require_mapping(record, "frozen_inputs_ref")
    frozen = _load_json(local_bytes[_reference_path(frozen_ref)], "frozen inputs")
    if _canonical_bytes(frozen) != local_bytes[_reference_path(frozen_ref)]:
        raise SustainedCheckError("frozen inputs are not canonical exact bytes")
    if frozen.get("record_kind") != "m05b_frozen_run_inputs":
        raise SustainedCheckError("frozen inputs have the wrong record kind")

    envelope_bytes = _require_string(frozen, "envelope_json").encode()
    order_bytes = _require_string(frozen, "order_json").encode()
    envelope = _load_json(envelope_bytes, "envelope")
    order = _load_json(order_bytes, "execution order")
    if _sha256(envelope_bytes) != frozen.get("envelope_sha256") or _sha256(order_bytes) != frozen.get(
        "order_sha256"
    ):
        raise SustainedCheckError("frozen envelope/order digest mismatch")
    if validate_m05b_document(envelope, "start_envelope") or validate_m05b_document(
        order, "execution_order"
    ):
        raise SustainedCheckError("frozen envelope/order schema validation failed")
    candidate = _require_mapping(record, "candidate")
    envelope_device = _require_mapping(envelope, "device")
    if (
        candidate.get("app_sha256") != envelope_device.get("app_sha256")
        or candidate.get("git_sha") != envelope.get("producer_git_sha")
    ):
        raise SustainedCheckError("device-reported candidate does not match the frozen envelope")

    artifacts = _require_mapping(record, "durable_artifacts")
    admission_ref = _require_mapping(artifacts, "run_admission")
    admission = _load_json(local_bytes[_reference_path(admission_ref)], "run admission")
    if validate_m05b_document(admission, "run_admission"):
        raise SustainedCheckError("run admission schema validation failed")
    if admission.get("admission_status") != "admitted":
        raise SustainedCheckError("sustained session does not have an admitted durable boundary")

    sustained = _require_mapping(record, "sustained_record")
    telemetry_ref = _require_mapping(artifacts, "telemetry")
    _validate_durable_telemetry(
        local_bytes[_reference_path(telemetry_ref)],
        sustained,
    )
    issues = validate_sustained_record(sustained)
    issue_codes = sorted({issue.code for issue in issues})
    if "SCHEMA.INVALID" in issue_codes or "BLOCKING_CODES.MISMATCH" in issue_codes:
        raise SustainedCheckError("sustained record is malformed or carries forged blockers")
    supplied = sustained.get("blocking_codes")
    if not isinstance(supplied, list) or supplied != issue_codes:
        raise SustainedCheckError("sustained blockers do not equal independently derived blockers")

    session_id = _require_string(record, "session_id")
    cadence = record.get("cadence_fps")
    if (
        sustained.get("session_id") != session_id
        or sustained.get("cadence_fps") != cadence
        or admission.get("session_id") != session_id
        or admission.get("cadence_fps") != cadence
    ):
        raise SustainedCheckError("session/cadence binding mismatch")
    sustained_refs = _require_mapping(sustained, "artifact_refs")
    if _require_mapping(sustained_refs, "envelope").get("sha256") != frozen.get("envelope_sha256"):
        raise SustainedCheckError("sustained envelope reference mismatch")
    if _require_mapping(sustained_refs, "order").get("sha256") != frozen.get("order_sha256"):
        raise SustainedCheckError("sustained order reference mismatch")
    if _require_mapping(sustained_refs, "admission").get("sha256") != _reference_digest(admission_ref):
        raise SustainedCheckError("sustained admission reference mismatch")

    attempt_refs = record.get("attempt_entry_refs")
    chain = sustained.get("attempt_chain")
    if not isinstance(attempt_refs, list) or not isinstance(chain, list) or len(attempt_refs) != len(chain):
        raise SustainedCheckError("attempt-chain reference count mismatch")
    final_attempt_outcome: str | None = None
    for reference, wrapper in zip(attempt_refs, chain, strict=True):
        if not isinstance(reference, Mapping) or not isinstance(wrapper, Mapping):
            raise SustainedCheckError("attempt-chain reference is malformed")
        entry = _load_json(local_bytes[_reference_path(reference)], "attempt entry")
        if validate_m05b_document(entry, "attempt_entry"):
            raise SustainedCheckError("attempt entry schema validation failed")
        if wrapper.get("entry") != entry or wrapper.get("entry_sha256") != canonical_sha256(entry):
            raise SustainedCheckError("attempt chain does not match device-owned exact entry")
        final_attempt_outcome = entry.get("outcome") if isinstance(entry.get("outcome"), str) else None
    expected_outcome = "blocked" if issue_codes else "completed"
    if final_attempt_outcome != expected_outcome:
        raise SustainedCheckError("terminal attempt outcome does not match the sustained result")

    device = _require_mapping(record, "device")
    blocking = list(issue_codes)
    if not is_authorized_five_run_gate_target(dict(device)):
        blocking.append("TELEMETRY.UNAUTHORIZED_S25_TARGET")
    blocking = sorted(set(blocking))
    return {
        "artifact_schema_version": "1.0.0",
        "record_kind": "s25_m05b_sustained_observation",
        "physical_evidence_status": "recorded" if not blocking else "blocked",
        "session_id": session_id,
        "cadence_fps": cadence,
        "device": dict(device),
        "candidate": dict(candidate),
        "blocking_codes": blocking,
        "sustained_record": dict(sustained),
    }


def _extract_status_record(transcript: str) -> dict[str, Any]:
    matches = STATUS_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise SustainedCheckError(f"expected exactly one sustained status record, found {len(matches)}")
    try:
        value = load_strict_json(matches[0])
    except json.JSONDecodeError as error:
        raise SustainedCheckError(f"sustained status is not strict JSON: {error}") from error
    if not isinstance(value, dict) or value.get("record_kind") != RECORD_KIND:
        raise SustainedCheckError("sustained status has the wrong record kind")
    if value.get("suite") != SUITE or f"class={SUITE}" not in transcript:
        raise SustainedCheckError("transcript does not show the exact sustained suite")
    if not re.search(r"^OK \(1 test\)$", transcript, re.MULTILINE):
        raise SustainedCheckError("transcript does not report exactly one passing test")
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)$", transcript, re.MULTILINE)
    if codes != ["-1"]:
        raise SustainedCheckError("instrumentation completion status is not successful")
    if value.get("sanitized") is not True or value.get("non_participant_observation") is not True:
        raise SustainedCheckError("sustained status is not sanitized non-participant evidence")
    _all_references(value)
    return value


def _all_references(record: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    references = [_require_mapping(record, "frozen_inputs_ref")]
    artifacts = _require_mapping(record, "durable_artifacts")
    for key in ("manifest", "frames", "telemetry", "run_admission"):
        references.append(_require_mapping(artifacts, key))
    attempts = record.get("attempt_entry_refs")
    if not isinstance(attempts, list) or not attempts:
        raise SustainedCheckError("attempt-entry references are missing")
    for attempt in attempts:
        if not isinstance(attempt, Mapping):
            raise SustainedCheckError("attempt-entry reference must be an object")
        references.append(attempt)
    for reference in references:
        _reference_path(reference)
        _reference_digest(reference)
    paths = [_reference_path(reference) for reference in references]
    if len(paths) != len(set(paths)):
        raise SustainedCheckError("duplicate sustained artifact reference")
    return references


def _read_and_rehash_references(record: Mapping[str, Any], root: Path) -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    for reference in _all_references(record):
        relative = _reference_path(reference)
        path = root / relative
        try:
            data = path.read_bytes()
        except OSError as error:
            raise SustainedCheckError(f"awaiting_recovery: referenced bytes unavailable: {relative}") from error
        if _sha256(data) != _reference_digest(reference):
            raise SustainedCheckError(f"exact-byte digest mismatch for {relative}")
        result[relative] = data
    return result


def _reference_path(reference: Mapping[str, Any]) -> str:
    value = reference.get("path")
    if not isinstance(value, str) or not _safe_relative(value):
        raise SustainedCheckError("artifact path is not a safe relative path")
    return value


def _reference_digest(reference: Mapping[str, Any]) -> str:
    value = reference.get("sha256")
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise SustainedCheckError("artifact digest is not lowercase SHA-256")
    return value


def _require_mapping(value: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    nested = value.get(key)
    if not isinstance(nested, Mapping):
        raise SustainedCheckError(f"field {key!r} must be an object")
    return nested


def _require_string(value: Mapping[str, Any], key: str) -> str:
    nested = value.get(key)
    if not isinstance(nested, str) or not nested:
        raise SustainedCheckError(f"field {key!r} must be a non-empty string")
    return nested


def _load_json(data: bytes, label: str) -> dict[str, Any]:
    try:
        value = load_strict_json(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SustainedCheckError(f"{label} is not strict UTF-8 JSON: {error}") from error
    if not isinstance(value, dict):
        raise SustainedCheckError(f"{label} must be a JSON object")
    return value


def _validate_durable_telemetry(data: bytes, sustained: Mapping[str, Any]) -> None:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SustainedCheckError(f"telemetry is not strict UTF-8: {error}") from error
    if not text.endswith("\n"):
        raise SustainedCheckError("telemetry is not newline-terminated NDJSON")
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        try:
            value = load_strict_json(line)
        except json.JSONDecodeError as error:
            raise SustainedCheckError(
                f"telemetry line {line_number} is not strict JSON: {error}"
            ) from error
        if not isinstance(value, dict):
            raise SustainedCheckError(f"telemetry line {line_number} must be an object")
        records.append(value)
    summaries = [
        value for value in records if value.get("record_type") == "sustained_latency_summary"
    ]
    if len(summaries) != 1 or not records or records[-1] is not summaries[0]:
        raise SustainedCheckError("durable latency summary must be the single final telemetry record")
    summary = summaries[0]
    if summary.get("schema_version") != "1.0.0":
        raise SustainedCheckError("durable latency summary has an unsupported schema version")
    for field in ("sequence", "t_session_ns", "sample_count"):
        value = summary.get(field)
        if type(value) is not int or value < 0:
            raise SustainedCheckError(f"durable latency {field} must be a non-negative integer")
    bounds = summary.get("upper_bounds_ns")
    counts = summary.get("bucket_counts")
    if not isinstance(bounds, list) or not isinstance(counts, list) or len(bounds) != len(counts):
        raise SustainedCheckError("durable latency histogram shape is malformed")
    if not counts or any(type(value) is not int or value < 0 for value in counts):
        raise SustainedCheckError("durable latency bucket counts are malformed")
    if summary["sample_count"] != sum(counts):
        raise SustainedCheckError("durable latency sample count does not equal its buckets")
    claimed_latency = _require_mapping(sustained, "latency")
    if (
        bounds != claimed_latency.get("bucket_upper_bounds_ns")
        or counts != claimed_latency.get("bucket_counts")
    ):
        raise SustainedCheckError("claimed latency histogram does not match durable telemetry")

    body = records[:-1]
    if not body or len(body) % 2 != 0:
        raise SustainedCheckError("durable sustained telemetry must contain fixed/counter pairs")
    fixed_samples: list[Mapping[str, Any]] = []
    stage_totals: dict[tuple[str, str], int] = {}
    for pair_index in range(0, len(body), 2):
        fixed = body[pair_index]
        counter = body[pair_index + 1]
        if fixed.get("record_type") != "fixed_telemetry_sample" or fixed.get("schema_version") != "2.0.0":
            raise SustainedCheckError("durable fixed telemetry record is malformed")
        if counter.get("record_type") != "sustained_stage_counter" or counter.get("schema_version") != "1.0.0":
            raise SustainedCheckError("durable sustained stage counter is malformed")
        fixed_sequence = fixed.get("sequence")
        fixed_time = fixed.get("t_session_ns")
        counter_sequence = counter.get("sequence")
        if (
            type(fixed_sequence) is not int
            or type(counter_sequence) is not int
            or type(fixed_time) is not int
            or fixed_sequence != pair_index
            or counter_sequence != pair_index + 1
            or fixed_time < 0
            or counter.get("t_session_ns") != fixed_time
            or counter.get("fixed_telemetry_sequence") != fixed_sequence
        ):
            raise SustainedCheckError("durable telemetry sequence/time alignment is malformed")
        if type(fixed.get("terminal")) is not bool:
            raise SustainedCheckError("durable fixed telemetry terminal flag is malformed")
        entries = counter.get("counts")
        if not isinstance(entries, list):
            raise SustainedCheckError("durable sustained stage counts are malformed")
        for entry in entries:
            if not isinstance(entry, Mapping):
                raise SustainedCheckError("durable sustained stage count must be an object")
            stage = entry.get("stage")
            disposition = entry.get("disposition")
            count = entry.get("count")
            if not isinstance(stage, str) or not isinstance(disposition, str) or type(count) is not int or count <= 0:
                raise SustainedCheckError("durable sustained stage count is malformed")
            key = (stage, disposition)
            stage_totals[key] = stage_totals.get(key, 0) + count
        fixed_samples.append(fixed)

    times = [int(value["t_session_ns"]) for value in fixed_samples]
    derived_telemetry = {
        "fixed_sample_count": len(fixed_samples),
        "first_t_session_ns": times[0],
        "last_t_session_ns": times[-1],
        "max_inter_sample_gap_ns": max(
            (right - left for left, right in zip(times, times[1:], strict=False)),
            default=0,
        ),
        "terminal_sample": fixed_samples[-1]["terminal"],
    }
    if any(bool(value["terminal"]) for value in fixed_samples[:-1]):
        raise SustainedCheckError("durable telemetry has a premature terminal sample")
    if dict(_require_mapping(sustained, "telemetry")) != derived_telemetry:
        raise SustainedCheckError("claimed telemetry summary does not match durable fixed samples")

    start_temperature = _temperature_c(fixed_samples[0].get("battery_temperature"))
    end_temperature = _temperature_c(fixed_samples[-1].get("battery_temperature"))
    derived_battery = {
        "start_temperature_c": start_temperature,
        "end_temperature_c": end_temperature,
        "delta_temperature_c": (
            end_temperature - start_temperature
            if start_temperature is not None and end_temperature is not None
            else None
        ),
    }
    if dict(_require_mapping(sustained, "battery")) != derived_battery:
        raise SustainedCheckError("claimed battery summary does not match durable fixed samples")

    def stage(stage_name: str, disposition: str) -> int:
        return stage_totals.get((stage_name, disposition), 0)

    claimed_stages = _require_mapping(sustained, "stage_accounting")
    derived_stages = {
        "camera_callbacks_received": stage("CAMERA_CALLBACK", "RECEIVED"),
        "yuv_frames_acquired": stage("YUV_OWNERSHIP", "ACQUIRED"),
        "yuv_frames_released": stage("YUV_OWNERSHIP", "RELEASED"),
        "preprocessing_started": stage("PREPROCESSING", "STARTED"),
        "preprocessing_completed": stage("PREPROCESSING", "COMPLETED"),
        "cadence_admitted": stage("INFERENCE", "SUBMITTED"),
        "cadence_rejected": sum(
            stage("CADENCE", disposition)
            for disposition in ("CADENCE_SELECTION", "BACKPRESSURE", "SESSION_STOPPING")
        ),
        "inference_submitted": stage("INFERENCE", "SUBMITTED"),
        "inference_completed": stage("INFERENCE", "COMPLETED"),
        "inference_failed": stage("INFERENCE", "FAILED"),
    }
    if any(claimed_stages.get(key) != value for key, value in derived_stages.items()):
        raise SustainedCheckError("claimed stage accounting does not match durable counters")


def _temperature_c(value: object) -> float | None:
    if not isinstance(value, Mapping):
        raise SustainedCheckError("durable battery-temperature reading is malformed")
    kind = value.get("kind")
    if kind == "UNAVAILABLE":
        return None
    raw = value.get("value")
    if kind != "VALUE" or type(raw) is not int:
        raise SustainedCheckError("durable battery-temperature reading is malformed")
    return raw / 10.0


def _canonical_bytes(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def _safe_relative(path: str) -> bool:
    return bool(path) and not path.startswith("/") and "\\" not in path and all(
        part not in {"", ".", ".."} for part in path.split("/")
    )


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--transcript", type=Path, required=True)
    parser.add_argument("--artifact-root", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--package", default=APP_PACKAGE)
    parser.add_argument("--subprocess-exit-code", type=int, required=True)
    parser.add_argument("--output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    try:
        arguments = _parse_args(sys.argv[1:] if argv is None else argv)
    except SystemExit as error:
        return 2 if error.code else 0
    temporary: tempfile.TemporaryDirectory[str] | None = None
    try:
        transcript = arguments.transcript.read_text(encoding="utf-8")
        record = _extract_status_record(transcript)
        if (arguments.serial is None) == (arguments.artifact_root is None):
            raise SustainedCheckError("provide exactly one of --serial or --artifact-root")
        root = arguments.artifact_root
        if arguments.serial is not None:
            temporary = tempfile.TemporaryDirectory(prefix="m05b-sustained-")
            root = transfer_references_from_device(
                serial=arguments.serial,
                package=arguments.package,
                record=record,
                destination=Path(temporary.name),
            )
        result = check_sustained_run(
            transcript,
            Path(root),
            subprocess_exit_code=arguments.subprocess_exit_code,
        )
        encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
        if arguments.output is not None:
            arguments.output.write_text(encoded, encoding="utf-8")
        else:
            print(encoded, end="")
        return 0 if result["physical_evidence_status"] == "recorded" else 3
    except (OSError, SustainedCheckError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    finally:
        if temporary is not None:
            temporary.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
