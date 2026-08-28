#!/usr/bin/env python3
"""Validate the bounded M0.5B short 10/15/30 attribution observation.

The device test supplies references only.  The durable session files are the authority for
accounting and admission; this checker hashes their exact bytes before decoding them.
"""

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
from itertools import pairwise
from pathlib import Path
from typing import Any

from check_five_run_replay import is_authorized_five_run_gate_target
from observation_contracts.fixture_integrity import resolve_fixture_path
from observation_contracts.schema_loader import load_strict_json
from observation_contracts.semantic import (
    validate_frame_stream,
    validate_session_manifest_semantics,
)
from observation_contracts.structural import validate_document, validate_ndjson

STATUS_PATTERN = re.compile(
    r"^INSTRUMENTATION_STATUS: M05BAttributionEvidence=(?P<json>\{.*\})$",
    re.MULTILINE,
)
SUITE = "com.calisthenics.diagnostic.composition.M05BAttributionDeviceGateTest"
RECORD_KIND = "m05b_short_attribution_session_references"
CADENCES = (10, 15, 30)
SHA256 = re.compile(r"\A[0-9a-f]{64}\Z")
GIT_SHA = re.compile(r"\A[0-9a-f]{40}\Z")
UUID = re.compile(r"\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
SYNTHETIC_SESSION = re.compile(r"\A00000000-0000-4000-8000-[0-9a-f]{12}\Z")
MAX_INT64 = (1 << 63) - 1
PIPELINE_STAGES = {
    "CAMERA_CALLBACK",
    "YUV_OWNERSHIP",
    "PREPROCESSING",
    "CADENCE",
    "INFERENCE",
    "POSE_STREAM",
    "ENCODER",
}
PIPELINE_DISPOSITIONS = {
    "RECEIVED",
    "SOURCE_NOT_READY",
    "CLOCK_FAULT",
    "ACQUIRED",
    "RELEASED",
    "STARTED",
    "COMPLETED",
    "CADENCE_SELECTION",
    "BACKPRESSURE",
    "SESSION_STOPPING",
    "SOURCE_LOSS",
    "SUBMITTED",
    "FAILED",
    "APPENDED",
    "DURABLE_CHECKPOINT",
    "ENCODER_ACCEPTED",
    "ENCODER_DROPPED",
    "ENCODER_WRITTEN",
}

_TERMINAL_EVENTS: dict[tuple[str, str], str] = {
    ("CAMERA_CALLBACK", "SOURCE_NOT_READY"): "SOURCE_NOT_READY",
    ("CAMERA_CALLBACK", "CLOCK_FAULT"): "CLOCK_FAULT",
    ("YUV_OWNERSHIP", "SOURCE_LOSS"): "SOURCE_LOSS",
    ("PREPROCESSING", "FAILED"): "PREPROCESSING_FAILED",
    ("CADENCE", "CADENCE_SELECTION"): "CADENCE_SELECTION",
    ("CADENCE", "BACKPRESSURE"): "BACKPRESSURE",
    ("CADENCE", "SESSION_STOPPING"): "SESSION_STOPPING",
    ("INFERENCE", "FAILED"): "INFERENCE_FAILED",
    ("INFERENCE", "COMPLETED"): "INFERENCE_COMPLETED",
    ("ENCODER", "ENCODER_ACCEPTED"): "ENCODER_ACCEPTED",
    ("ENCODER", "ENCODER_DROPPED"): "ENCODER_DROPPED",
    ("ENCODER", "ENCODER_WRITTEN"): "ENCODER_WRITTEN",
}


class AttributionCheckError(Exception):
    """Evidence is invalid or cannot yet be recovered from the device."""


APP_PACKAGE = "com.calisthenics.diagnostic.android"
REMOTE_EXPORT_ROOT = "files/diagnostic-harness"


def transfer_exports_from_device(
    *,
    serial: str,
    package: str,
    references: list[Mapping[str, Any]],
    destination: Path,
    run_adb: Callable[..., subprocess.CompletedProcess[bytes]] = subprocess.run,
) -> Path:
    """Pull exactly the referenced exports through ``run-as`` without shell decoding.

    Each response is written to a host temporary file, flushed and fsynced, rehashed against the
    device-emitted reference, and atomically renamed into the temporary export tree.  A transport
    failure is deliberately recoverable only: a session may already have been durably committed,
    so callers must not silently rerun it.
    """
    if not serial or any(character.isspace() for character in serial):
        raise AttributionCheckError("device serial is missing or malformed")
    if package != APP_PACKAGE:
        raise AttributionCheckError(f"unexpected attribution package: {package}")
    if not references:
        raise AttributionCheckError("attribution transfer has no session references")
    destination.mkdir(parents=True, exist_ok=True)
    for reference in references:
        session_id = str(reference.get("session_id", ""))
        relative = str(reference.get("export_relative_path", ""))
        if not SYNTHETIC_SESSION.fullmatch(session_id) or not _safe_relative(relative):
            raise AttributionCheckError("invalid device export reference")
        if session_id not in Path(relative).parts:
            raise AttributionCheckError(
                "device export reference is not bound to its session"
            )
        session_root = destination / relative
        session_root.mkdir(parents=True, exist_ok=True)
        for name, suffix in (
            ("manifest", "json"),
            ("frames", "ndjson"),
            ("telemetry", "ndjson"),
        ):
            remote = f"{REMOTE_EXPORT_ROOT}/{relative}/{name}.{suffix}"
            try:
                completed = run_adb(
                    ["adb", "-s", serial, "exec-out", "run-as", package, "cat", remote],
                    capture_output=True,
                    check=False,
                )
            except OSError as error:
                raise AttributionCheckError(
                    f"awaiting_recovery: adb transport failed for {session_id} {name}: {error}"
                ) from error
            if completed.returncode != 0:
                detail = completed.stderr.decode("utf-8", errors="replace").strip()
                raise AttributionCheckError(
                    f"awaiting_recovery: adb could not pull {session_id} {name}"
                    + (f": {detail}" if detail else "")
                )
            data = completed.stdout
            expected = str(reference.get(f"{name}_sha256", ""))
            if _sha256(data) != expected:
                raise AttributionCheckError(
                    f"exact-byte digest mismatch for {session_id} {name}"
                )
            target = session_root / f"{name}.{suffix}"
            temporary = target.with_name(f".{target.name}.transfer")
            try:
                with temporary.open("wb") as handle:
                    handle.write(data)
                    handle.flush()
                    os.fsync(handle.fileno())
                if _sha256(temporary.read_bytes()) != expected:
                    raise AttributionCheckError(
                        f"host transfer rehash failed for {session_id} {name}"
                    )
                os.replace(temporary, target)
                _fsync_directory(session_root)
            finally:
                temporary.unlink(missing_ok=True)
    return destination


def _fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def extract_status_record(transcript: str) -> dict[str, Any]:
    """Extract exactly one typed status record from one exact instrumentation transcript."""
    matches = STATUS_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise AttributionCheckError(
            f"expected exactly one M05B status record, found {len(matches)}"
        )
    try:
        record = load_strict_json(matches[0])
    except json.JSONDecodeError as error:
        raise AttributionCheckError(
            f"M05B status record is not valid JSON: {error}"
        ) from error
    if not isinstance(record, dict):
        raise AttributionCheckError("M05B status record must be a JSON object")
    if record.get("record_kind") != RECORD_KIND:
        raise AttributionCheckError("M05B status record has the wrong record_kind")
    if record.get("suite") != SUITE or f"class={SUITE}" not in transcript:
        raise AttributionCheckError(
            "transcript does not show the exact M05B attribution suite"
        )
    completion_codes = re.findall(
        r"^INSTRUMENTATION_CODE:\s*(-?\d+)$", transcript, re.MULTILINE
    )
    if len(completion_codes) != 1:
        raise AttributionCheckError(
            "transcript has no instrumentation completion status"
        )
    if completion_codes[0] != "-1":
        raise AttributionCheckError("instrumentation reported a non-zero status code")
    if not re.search(r"^OK \(1 test\)$", transcript, re.MULTILINE):
        raise AttributionCheckError(
            'transcript does not report exactly one passing test ("OK (1 test)")'
        )
    _validate_reference_shape(record)
    return record


def check_attribution(
    transcript: str,
    export_root: Path,
    *,
    subprocess_exit_code: int,
    prepared_identity: Mapping[str, Any],
) -> dict[str, Any]:
    """Validate three local exports and return one sanitized, recomputed JSON-compatible record."""
    if type(subprocess_exit_code) is not int or subprocess_exit_code != 0:
        raise AttributionCheckError("instrumentation subprocess exited non-zero")
    record = extract_status_record(transcript)
    _validate_prepared_identity(prepared_identity)
    device = _require_mapping(record, "device")
    identity = _require_mapping(record, "identity")
    if dict(identity) != dict(prepared_identity):
        raise AttributionCheckError(
            "instrumentation identity does not match prepared candidate"
        )
    if not is_authorized_five_run_gate_target(dict(device)):
        blocking_codes = ["TELEMETRY.UNAUTHORIZED_S25_TARGET"]
    else:
        blocking_codes = []

    runs: list[dict[str, Any]] = []
    for reference in record["sessions"]:
        runs.append(_check_export(export_root, reference, device, identity))

    if runs and any(run["identity"] != runs[0]["identity"] for run in runs[1:]):
        blocking_codes.append("TELEMETRY.RUN_IDENTITY_MISMATCH")
    if [run["configured_inference_fps"] for run in runs] != list(CADENCES):
        blocking_codes.append("TELEMETRY.CADENCE_SEQUENCE_INVALID")
    if any(run["requested_camera_fps"] != 30 for run in runs):
        blocking_codes.append("TELEMETRY.CAMERA_REQUEST_NOT_30_FPS")

    rates = [(run["admitted_callbacks"], run["delivered_callbacks"]) for run in runs]
    if (
        len(rates) != 3
        or any(denominator <= 0 for _, denominator in rates)
        or (
            len(rates) == 3
            and (
                not rates[0][0] * rates[1][1] < rates[1][0] * rates[0][1]
                or not rates[1][0] * rates[2][1] < rates[2][0] * rates[1][1]
            )
        )
    ):
        blocking_codes.append("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT")

    blocking_codes = sorted(set(blocking_codes))
    return {
        "artifact_schema_version": "1.0.0",
        "record_kind": "s25_short_cadence_attribution_observation",
        "physical_evidence_status": "recorded" if not blocking_codes else "blocked",
        "satisfies_s25_attribution_gate": not blocking_codes,
        "device": dict(device),
        "identity": dict(identity),
        "runs": runs,
        "blocking_codes": blocking_codes,
    }


def verify_recorded_ledger(root: Path, ledger_relative: str) -> dict[str, Any]:
    """Open and validate a committed pending/recorded attribution ledger.

    A recorded ledger is never trusted merely because it names an evidence file: the referenced
    bytes, sanitized result, device authorization, cadence order, and derived rate verdict are all
    checked again.
    """
    ledger_path = resolve_fixture_path(root, ledger_relative)
    if ledger_path is None or not ledger_path.is_file():
        raise AttributionCheckError("attribution ledger is unavailable")
    ledger = load_strict_json(ledger_path.read_text(encoding="utf-8"))
    if not isinstance(ledger, Mapping) or ledger.get("status") not in {
        "pending",
        "recorded",
    }:
        raise AttributionCheckError("attribution ledger has an invalid status")
    if ledger["status"] == "pending":
        if (
            not isinstance(ledger.get("missing_precondition"), str)
            or not ledger["missing_precondition"]
        ):
            raise AttributionCheckError(
                "pending attribution ledger must name its missing precondition"
            )
        return dict(ledger)
    evidence_relative = ledger.get("evidence_file")
    if not isinstance(evidence_relative, str) or not _safe_relative(evidence_relative):
        raise AttributionCheckError(
            "recorded attribution ledger must name a relative evidence file"
        )
    evidence_path = resolve_fixture_path(root, evidence_relative)
    if evidence_path is None or not evidence_path.is_file():
        raise AttributionCheckError("recorded attribution evidence is unavailable")
    payload = evidence_path.read_bytes()
    if ledger.get("evidence_sha256") != _sha256(payload):
        raise AttributionCheckError(
            "recorded attribution evidence sha256 does not match"
        )
    evidence = load_strict_json(payload.decode("utf-8"))
    if (
        not isinstance(evidence, Mapping)
        or evidence.get("record_kind") != "s25_short_cadence_attribution_observation"
    ):
        raise AttributionCheckError(
            "recorded attribution evidence has the wrong record kind"
        )
    device = evidence.get("device")
    if not isinstance(device, Mapping) or not is_authorized_five_run_gate_target(
        dict(device)
    ):
        raise AttributionCheckError(
            "recorded attribution evidence is not authorized S25 hardware"
        )
    identity = evidence.get("identity")
    if not isinstance(identity, Mapping):
        raise AttributionCheckError(
            "recorded attribution evidence has no identity binding"
        )
    for ledger_key, record_value in (
        ("device_identity", device.get("device_identity")),
        ("api_level", device.get("api_level")),
        ("android_release", device.get("android_release")),
        ("app_git_sha", identity.get("app_git_sha")),
        ("probe_git_sha", identity.get("probe_git_sha")),
        ("apk_sha256", identity.get("apk_sha256")),
        ("model_sha256", identity.get("model_sha256")),
        ("runtime_version", identity.get("runtime_version")),
        ("backend_version", identity.get("backend_version")),
        ("delegate", identity.get("delegate")),
    ):
        if ledger.get(ledger_key) != record_value:
            raise AttributionCheckError(
                f"attribution ledger identity mismatch for {ledger_key}"
            )
    runs = evidence.get("runs")
    if not isinstance(runs, list) or [
        run.get("configured_inference_fps") for run in runs if isinstance(run, Mapping)
    ] != list(CADENCES):
        raise AttributionCheckError("recorded attribution cadence order is invalid")
    if any(not isinstance(run, Mapping) for run in runs):
        raise AttributionCheckError("recorded attribution run is not an object")
    rates = [
        (run.get("admitted_callbacks"), run.get("delivered_callbacks")) for run in runs
    ]
    derived_blocking = []
    if any(run.get("requested_camera_fps") != 30 for run in runs):
        derived_blocking.append("TELEMETRY.CAMERA_REQUEST_NOT_30_FPS")
    if (
        len(rates) != 3
        or any(
            type(numerator) is not int
            or type(denominator) is not int
            or denominator <= 0
            for numerator, denominator in rates
        )
        or not (
            rates[0][0] * rates[1][1] < rates[1][0] * rates[0][1]
            and rates[1][0] * rates[2][1] < rates[2][0] * rates[1][1]
        )
    ):
        derived_blocking.append("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT")
    expected_blocking = sorted(set(derived_blocking))
    if evidence.get("blocking_codes") != expected_blocking:
        raise AttributionCheckError(
            "recorded attribution blocking verdict was not recomputed"
        )
    expected_status = "recorded" if not expected_blocking else "blocked"
    if evidence.get("physical_evidence_status") != expected_status or evidence.get(
        "satisfies_s25_attribution_gate"
    ) is not (not expected_blocking):
        raise AttributionCheckError("recorded attribution verdict is inconsistent")
    if ledger.get("status") != expected_status:
        raise AttributionCheckError("attribution ledger status disagrees with evidence")
    return dict(evidence)


def _validate_reference_shape(record: Mapping[str, Any]) -> None:
    sessions = record.get("sessions")
    if not isinstance(sessions, list) or len(sessions) != 3:
        raise AttributionCheckError(
            "M05B status record must contain exactly three sessions"
        )
    if any(not isinstance(session, Mapping) for session in sessions):
        raise AttributionCheckError("M05B session reference must be an object")
    if [session.get("configured_inference_fps") for session in sessions] != list(
        CADENCES
    ):
        raise AttributionCheckError(
            "M05B status record must list cadences in 10/15/30 order"
        )
    for session in sessions:
        session_id = session.get("session_id")
        path = session.get("export_relative_path")
        if (
            not isinstance(session_id, str)
            or UUID.fullmatch(session_id) is None
            or SYNTHETIC_SESSION.fullmatch(session_id) is None
        ):
            raise AttributionCheckError(
                "M05B session_id must use the reserved synthetic UUID namespace"
            )
        if (
            not isinstance(path, str)
            or not _safe_relative(path)
            or session_id not in Path(path).parts
        ):
            raise AttributionCheckError(
                "M05B export path must be relative and contain its exact session_id"
            )
        for key in ("manifest_sha256", "frames_sha256", "telemetry_sha256"):
            value = session.get(key)
            if not isinstance(value, str) or SHA256.fullmatch(value) is None:
                raise AttributionCheckError(f"M05B {key} must be a lowercase SHA-256")
    device = record.get("device")
    if not isinstance(device, Mapping) or not isinstance(
        record.get("identity"), Mapping
    ):
        raise AttributionCheckError(
            "M05B status record must include device and identity bindings"
        )
    identity = record["identity"]
    _validate_prepared_identity(identity)


def _validate_prepared_identity(identity: Mapping[str, Any]) -> None:
    if (
        not isinstance(identity.get("app_git_sha"), str)
        or GIT_SHA.fullmatch(identity["app_git_sha"]) is None
        or not isinstance(identity.get("probe_git_sha"), str)
        or GIT_SHA.fullmatch(identity["probe_git_sha"]) is None
        or not isinstance(identity.get("apk_sha256"), str)
        or SHA256.fullmatch(identity["apk_sha256"]) is None
        or not isinstance(identity.get("model_sha256"), str)
        or SHA256.fullmatch(identity["model_sha256"]) is None
        or not isinstance(identity.get("runtime_version"), str)
        or not identity["runtime_version"]
        or not isinstance(identity.get("backend_version"), str)
        or not identity["backend_version"]
        or not isinstance(identity.get("delegate"), str)
        or not identity["delegate"]
    ):
        raise AttributionCheckError("M05B identity binding is incomplete or malformed")


def _manifest_build_identity(
    manifest: Mapping[str, Any], session_id: str
) -> Mapping[str, Any]:
    build_identity = manifest.get("build_identity")
    if not isinstance(build_identity, Mapping):
        study_context = manifest.get("study_context")
        if isinstance(study_context, Mapping):
            build_identity = study_context.get("m05b_attribution_identity")
    if not isinstance(build_identity, Mapping):
        raise AttributionCheckError(
            f"manifest candidate identity is missing for {session_id}"
        )
    _validate_prepared_identity(build_identity)
    return build_identity


def _check_export(
    export_root: Path,
    reference: Mapping[str, Any],
    device: Mapping[str, Any],
    identity: Mapping[str, Any],
) -> dict[str, Any]:
    session_id = str(reference["session_id"])
    relative = str(reference["export_relative_path"])
    session_root = resolve_fixture_path(export_root, relative)
    if session_root is None or not session_root.is_dir():
        raise AttributionCheckError(
            f"awaiting_recovery: durable export is unavailable for {session_id}"
        )

    files: dict[str, bytes] = {}
    for name in ("manifest", "frames", "telemetry"):
        path = session_root / f"{name}.{'json' if name == 'manifest' else 'ndjson'}"
        try:
            files[name] = path.read_bytes()
        except OSError as error:
            raise AttributionCheckError(
                f"awaiting_recovery: could not pull {name} for {session_id}: {error}"
            ) from error
        actual = _sha256(files[name])
        expected = str(reference[f"{name}_sha256"])
        if actual != expected:
            raise AttributionCheckError(
                f"exact-byte digest mismatch for {session_id} {name}"
            )

    try:
        manifest = load_strict_json(files["manifest"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AttributionCheckError(
            f"manifest is not strict UTF-8 JSON for {session_id}: {error}"
        ) from error
    if not isinstance(manifest, Mapping):
        raise AttributionCheckError(f"manifest must be an object for {session_id}")
    manifest_issues = validate_document(
        manifest, "session_manifest", str(session_root / "manifest.json")
    )
    if manifest_issues:
        raise AttributionCheckError(
            f"session manifest schema validation failed for {session_id}"
        )

    with tempfile.TemporaryDirectory(prefix="m05b-attribution-") as temp:
        immutable_frames = Path(temp) / "frames.ndjson"
        immutable_frames.write_bytes(files["frames"])
        frames, frame_issues = validate_ndjson(immutable_frames)
        semantic_issues = validate_frame_stream(
            manifest, frames, str(session_root / "manifest.json"), str(immutable_frames)
        )
        semantic_issues.extend(
            validate_session_manifest_semantics(
                manifest, frames, str(session_root / "manifest.json")
            )
        )
    if frame_issues or semantic_issues:
        raise AttributionCheckError(
            f"session export validation failed for {session_id}"
        )

    telemetry = _derive_telemetry(files["telemetry"], session_id)
    _bind_manifest(
        manifest,
        session_id,
        int(reference["configured_inference_fps"]),
        device,
        identity,
        telemetry,
    )
    return {
        "session_id": session_id,
        "export_relative_path": relative,
        "configured_inference_fps": int(reference["configured_inference_fps"]),
        "requested_camera_fps": int(manifest["camera"]["requested_camera_fps"]),
        "identity": {
            "app_git_sha": str(identity["app_git_sha"]),
            "probe_git_sha": str(identity["probe_git_sha"]),
            "apk_sha256": str(identity["apk_sha256"]),
            "model_sha256": str(manifest["pose_pipeline"]["model_asset_sha256"]),
            "runtime_version": str(manifest["pose_pipeline"]["runtime_version"]),
            "backend_version": str(manifest["pose_pipeline"]["backend_version"]),
            "delegate": str(manifest["pose_pipeline"]["delegate"]),
        },
        "digests": {
            "manifest_sha256": _sha256(files["manifest"]),
            "frames_sha256": _sha256(files["frames"]),
            "telemetry_sha256": _sha256(files["telemetry"]),
        },
        "delivered_callbacks": telemetry["delivered_callbacks"],
        "admitted_callbacks": telemetry["admitted_callbacks"],
        "terminal_counts": telemetry["terminal_counts"],
    }


def _derive_telemetry(data: bytes, session_id: str) -> dict[str, Any]:
    if not data or not data.endswith(b"\n"):
        raise AttributionCheckError(
            f"telemetry is not a complete LF-terminated stream for {session_id}"
        )
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(data.splitlines(keepends=True), 1):
        if not line.endswith(b"\n") or line.endswith(b"\r\n"):
            raise AttributionCheckError(
                f"telemetry line {line_number} is not LF-terminated for {session_id}"
            )
        try:
            record = load_strict_json(line[:-1].decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AttributionCheckError(
                f"telemetry line {line_number} is invalid for {session_id}: {error}"
            ) from error
        if (
            not isinstance(record, dict)
            or record.get("record_type") != "pipeline_stage_event"
        ):
            raise AttributionCheckError(
                f"telemetry line {line_number} is not a pipeline stage event"
            )
        if record.get("schema_version") != "1.0.0":
            raise AttributionCheckError(
                f"telemetry line {line_number} has an unsupported schema version"
            )
        _validate_telemetry_event(record, line_number, session_id)
        records.append(record)
    sequences = [record["sequence"] for record in records]
    times = [record["t_session_ns"] for record in records]
    if sequences != list(range(len(records))) or any(value < 0 for value in times):
        raise AttributionCheckError(
            f"telemetry sequence is not contiguous for {session_id}"
        )
    if any(second < first for first, second in pairwise(times)):
        raise AttributionCheckError(
            f"telemetry session time regressed for {session_id}"
        )
    callbacks = [
        record
        for record in records
        if (record.get("stage"), record.get("disposition"))
        == ("CAMERA_CALLBACK", "RECEIVED")
    ]
    callback_frames = [record.get("frame_index") for record in callbacks]
    terminal = [
        record
        for record in records
        if (record.get("stage"), record.get("disposition")) in _TERMINAL_EVENTS
    ]
    terminal_frames = [record.get("frame_index") for record in terminal]
    if (
        len(callback_frames) != len(set(callback_frames))
        or len(terminal_frames) != len(set(terminal_frames))
        or callback_frames != terminal_frames
    ):
        raise AttributionCheckError(
            f"telemetry terminal disposition closure failed for {session_id}"
        )
    counts: dict[str, int] = {}
    for record in terminal:
        name = _TERMINAL_EVENTS[(str(record["stage"]), str(record["disposition"]))]
        counts[name] = counts.get(name, 0) + 1
    submitted = sum(
        1
        for record in records
        if (record.get("stage"), record.get("disposition"))
        == ("INFERENCE", "SUBMITTED")
    )
    admitted = counts.get("INFERENCE_COMPLETED", 0) + counts.get("INFERENCE_FAILED", 0)
    if submitted != admitted:
        raise AttributionCheckError(
            f"telemetry inference admission closure failed for {session_id}"
        )
    return {
        "delivered_callbacks": len(callbacks),
        "admitted_callbacks": admitted,
        "terminal_counts": counts,
    }


def _validate_telemetry_event(
    record: Mapping[str, Any], line_number: int, session_id: str
) -> None:
    for field in ("sequence", "t_session_ns", "frame_index"):
        value = record.get(field)
        if type(value) is not int or value < 0 or value > MAX_INT64:
            raise AttributionCheckError(
                f"telemetry line {line_number} has an invalid {field} for {session_id}"
            )
    stage = record.get("stage")
    disposition = record.get("disposition")
    if not isinstance(stage, str) or stage not in PIPELINE_STAGES:
        raise AttributionCheckError(
            f"telemetry line {line_number} has an invalid stage"
        )
    if not isinstance(disposition, str) or disposition not in PIPELINE_DISPOSITIONS:
        raise AttributionCheckError(
            f"telemetry line {line_number} has an invalid disposition"
        )


def _bind_manifest(
    manifest: Mapping[str, Any],
    session_id: str,
    cadence: int,
    device: Mapping[str, Any],
    identity: Mapping[str, Any],
    telemetry: Mapping[str, Any],
) -> None:
    if (
        manifest.get("session_id") != session_id
        or manifest.get("session_kind") != "live_conformance"
    ):
        raise AttributionCheckError(f"manifest session binding failed for {session_id}")
    manifest_device = _require_mapping(manifest, "device")
    pipeline = _require_mapping(manifest, "pose_pipeline")
    camera = _require_mapping(manifest, "camera")
    if manifest_device.get("platform") != "android" or manifest_device.get(
        "device_model"
    ) != device.get("model"):
        raise AttributionCheckError(
            f"manifest device identity mismatch for {session_id}"
        )
    build_identity = _manifest_build_identity(manifest, session_id)
    if manifest.get("harness_git_sha") != identity.get("app_git_sha"):
        raise AttributionCheckError(
            f"manifest app Git identity mismatch for {session_id}"
        )
    if dict(build_identity) != dict(identity):
        raise AttributionCheckError(
            f"manifest prepared-candidate identity mismatch for {session_id}"
        )
    cadence_record = _require_mapping(manifest, "cadence")
    if (
        camera.get("requested_camera_fps") != 30
        or cadence_record.get("configured_inference_fps") != cadence
    ):
        raise AttributionCheckError(f"manifest cadence binding failed for {session_id}")
    counts = _require_mapping(manifest, "counts")
    if (
        counts.get("camera_frames_received") != telemetry["delivered_callbacks"]
        or counts.get("inference_frames_submitted") != telemetry["admitted_callbacks"]
    ):
        raise AttributionCheckError(
            f"manifest and telemetry accounting mismatch for {session_id}"
        )
    if (
        pipeline.get("model_asset_sha256") != identity.get("model_sha256")
        or pipeline.get("runtime_version") != identity.get("runtime_version")
        or pipeline.get("backend_version") != identity.get("backend_version")
        or pipeline.get("delegate") != identity.get("delegate")
        or not isinstance(pipeline.get("model_asset_sha256"), str)
        or SHA256.fullmatch(pipeline["model_asset_sha256"]) is None
    ):
        raise AttributionCheckError(
            f"manifest model identity is invalid for {session_id}"
        )


def _require_mapping(value: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    nested = value.get(key)
    if not isinstance(nested, Mapping):
        raise AttributionCheckError(f"M05B record field {key!r} must be an object")
    return nested


def _safe_relative(path: str) -> bool:
    return (
        bool(path)
        and not path.startswith("/")
        and "\\" not in path
        and all(part not in {"", ".", ".."} for part in path.split("/"))
    )


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--transcript", type=Path, required=True)
    parser.add_argument("--export-root", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--package", default=APP_PACKAGE)
    parser.add_argument("--subprocess-exit-code", type=int, required=True)
    parser.add_argument("--prepared-identity", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    try:
        arguments = _parse_args(sys.argv[1:] if argv is None else argv)
    except SystemExit as error:
        return 2 if error.code else 0
    try:
        if (arguments.serial is None) == (arguments.export_root is None):
            raise AttributionCheckError(
                "provide exactly one of --export-root or --serial"
            )
        prepared_identity = load_strict_json(
            arguments.prepared_identity.read_text(encoding="utf-8")
        )
        if not isinstance(prepared_identity, Mapping):
            raise AttributionCheckError("prepared identity must be a JSON object")
        transcript = arguments.transcript.read_text(encoding="utf-8")
        if arguments.serial is not None:
            record = extract_status_record(transcript)
            with tempfile.TemporaryDirectory(
                prefix="m05b-attribution-transfer-"
            ) as temporary:
                export_root = transfer_exports_from_device(
                    serial=arguments.serial,
                    package=arguments.package,
                    references=record["sessions"],
                    destination=Path(temporary),
                )
                result = check_attribution(
                    transcript,
                    export_root,
                    subprocess_exit_code=arguments.subprocess_exit_code,
                    prepared_identity=prepared_identity,
                )
        else:
            result = check_attribution(
                transcript,
                arguments.export_root,
                subprocess_exit_code=arguments.subprocess_exit_code,
                prepared_identity=prepared_identity,
            )
    except (
        AttributionCheckError,
        OSError,
        ValueError,
        UnicodeDecodeError,
        json.JSONDecodeError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["satisfies_s25_attribution_gate"] else 3


if __name__ == "__main__":
    raise SystemExit(main())
