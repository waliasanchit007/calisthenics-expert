#!/usr/bin/env python3
"""Check one bounded live-capture instrumentation transcript and pulled export."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

_RECORD_PATTERN = re.compile(
    r"^INSTRUMENTATION_STATUS: LiveCaptureEvidence=(?P<json>\{.*\})$", re.MULTILINE
)
_SUITE_MARKER = "class=com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest"
_EXPECTED_RECORD_KIND = "live_capture_device_gate_observation"
_S25_MODEL_PREFIX = "SM-S938"
_S25_MARKER = "S25 Ultra"
_AUTHORIZED_MANUFACTURER = "samsung"
_ADMITTED_DATASPACE = 146931712


class LiveCaptureCheckError(Exception):
    pass


def is_authorized_live_capture_target(environment: dict) -> bool:
    return (
        environment.get("device_class") == "physical_device"
        and environment.get("manufacturer", "").lower() == _AUTHORIZED_MANUFACTURER
        and (
            environment.get("model", "").upper().startswith(_S25_MODEL_PREFIX)
            or _S25_MARKER in environment.get("marketing_name", "")
        )
    )


def _require_exact_suite(transcript: str) -> None:
    if _SUITE_MARKER not in transcript:
        raise LiveCaptureCheckError("transcript does not show LiveCaptureDeviceGateTest")
    if not re.search(r"^OK \(1 test\)$", transcript, re.MULTILINE):
        raise LiveCaptureCheckError('transcript does not report exactly one passing test ("OK (1 test)")')


def extract_record(transcript: str) -> dict:
    matches = _RECORD_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise LiveCaptureCheckError(f"expected exactly one LiveCaptureEvidence record, found {len(matches)}")
    try:
        record = json.loads(matches[0])
    except json.JSONDecodeError as error:
        raise LiveCaptureCheckError(f"LiveCaptureEvidence is not valid JSON: {error}") from error
    if record.get("record_kind") != _EXPECTED_RECORD_KIND:
        raise LiveCaptureCheckError("LiveCaptureEvidence record_kind is not the live-capture gate kind")
    if record.get("sanitized") is not True or record.get("contains_landmark_coordinates") is not False:
        raise LiveCaptureCheckError("LiveCaptureEvidence is not an exact sanitized record")
    return record


def _blocking_reasons(record: dict, manifest: dict, validation_succeeded: bool, instrumentation_exit_code: int) -> list[str]:
    reasons: list[str] = []
    environment = record.get("environment", {})
    if not is_authorized_live_capture_target(environment):
        reasons.append("unauthorized_device_target")

    manifest_device = record.get("manifest_device", {})
    actual_device = manifest.get("device", {})
    for key in ("platform", "os_version", "device_model", "chipset"):
        if actual_device.get(key) != manifest_device.get(key):
            reasons.append("manifest_device_mismatch")
            break
    expected_device = {
        "platform": "android",
        "os_version": environment.get("android_release"),
        "device_model": environment.get("model"),
        "chipset": environment.get("chipset"),
    }
    if any(manifest_device.get(key) != value for key, value in expected_device.items()):
        reasons.append("manifest_device_mismatch")

    camera = record.get("camera", {})
    expected_camera = {
        "camera_id": "0",
        "timestamp_source": "REALTIME",
        "source_dataspace_raw": _ADMITTED_DATASPACE,
        "source_color_standard": "BT601",
        "source_color_range": "FULL",
        "underlying_image_available": True,
        "pixel_format": "YUV_420_888",
    }
    camera_reason = {
        "camera_id": "camera_id_not_zero",
        "timestamp_source": "timestamp_source_not_realtime",
        "source_dataspace_raw": "raw_dataspace_not_admitted",
        "source_color_standard": "colour_standard_not_bt601",
        "source_color_range": "colour_range_not_full",
        "underlying_image_available": "underlying_image_bridge_unavailable",
        "pixel_format": "pixel_format_not_yuv_420_888",
    }
    for key, expected in expected_camera.items():
        if camera.get(key) != expected:
            reasons.append(camera_reason[key])

    counts = record.get("counts", {})
    count_reason = {
        "camera_frames_received": "camera_frames_not_received",
        "inference_frames_submitted": "inference_frames_not_submitted",
        "inference_records_emitted": "inference_records_not_emitted",
    }
    for key, reason in count_reason.items():
        if not isinstance(counts.get(key), int) or counts[key] <= 0:
            reasons.append(reason)
    if counts.get("unclassified_delivered_callbacks") != 0:
        reasons.append("unclassified_delivered_callbacks")

    manifest_counts = manifest.get("counts", {})
    for key in count_reason:
        if manifest_counts.get(key) != counts.get(key):
            reasons.append("manifest_counts_mismatch")
            break
    if not validation_succeeded:
        reasons.append("reference_validation_failed")
    if record.get("run_failure_code") in {"live_capture_timeout", "live_inference_stalled", "live_capture_failed"}:
        reasons.append(record["run_failure_code"])
    if instrumentation_exit_code != 0:
        reasons.append("instrumentation_failed")
    return list(dict.fromkeys(reasons))


def check_transcript(
    transcript: str,
    manifest: dict,
    validation_succeeded: bool,
    instrumentation_exit_code: int,
) -> dict:
    _require_exact_suite(transcript)
    record = extract_record(transcript)
    reasons = _blocking_reasons(record, manifest, validation_succeeded, instrumentation_exit_code)
    result = dict(record)
    result["reference_validation_succeeded"] = validation_succeeded
    result["instrumentation_succeeded"] = instrumentation_exit_code == 0
    result["blocking_reasons"] = reasons
    result["satisfies_s25_live_capture"] = not reasons
    return result


def _pull(serial: str, package: str, session_id: str, filename: str, destination: Path) -> None:
    export = f"/data/user/0/{package}/files/diagnostic-harness/exports/{session_id}/{filename}"
    result = subprocess.run(
        ["adb", "-s", serial, "shell", "run-as", package, "cat", export],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0 or not result.stdout:
        raise LiveCaptureCheckError(f"failed to pull {filename}: {result.stderr.decode(errors='replace')}")
    destination.write_bytes(result.stdout)


def _validate_export(manifest_path: Path, frames_path: Path) -> bool:
    result = subprocess.run(
        [sys.executable, "-m", "observation_contracts", "validate-session", str(manifest_path), str(frames_path)],
        capture_output=True,
        check=False,
    )
    return result.returncode == 0


def verify_recorded_ledger(root: Path, ledger_relative: str) -> dict:
    ledger_path = root / ledger_relative
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    if ledger.get("status") == "pending":
        if not ledger.get("missing_precondition"):
            raise LiveCaptureCheckError("pending ledger must name its missing physical precondition")
        return ledger
    if ledger.get("status") != "recorded":
        raise LiveCaptureCheckError("live-capture ledger status must be pending or recorded")
    evidence_file = ledger.get("evidence_file")
    if not evidence_file:
        raise LiveCaptureCheckError("recorded ledger must reference evidence_file")
    evidence_path = root / evidence_file
    payload = evidence_path.read_bytes()
    actual_sha = hashlib.sha256(payload).hexdigest()
    if ledger.get("evidence_sha256") != actual_sha:
        raise LiveCaptureCheckError("recorded evidence_sha256 does not match evidence file sha256")
    record = json.loads(payload)
    if ledger.get("live_admission") != ("admitted" if record.get("satisfies_s25_live_capture") else "blocked"):
        raise LiveCaptureCheckError("ledger live_admission disagrees with evidence verdict")
    return ledger


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print(f"usage: {Path(argv[0]).name} <serial> <package> <transcript-file> <instrumentation-status>", file=sys.stderr)
        return 2
    serial, package, transcript_arg, status_arg = argv[1:]
    transcript = sys.stdin.read() if transcript_arg == "-" else Path(transcript_arg).read_text(encoding="utf-8")
    try:
        status = int(status_arg)
        record = extract_record(transcript)
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.json"
            frames_path = Path(directory) / "frames.ndjson"
            _pull(serial, package, record["session_id"], "manifest.json", manifest_path)
            _pull(serial, package, record["session_id"], "frames.ndjson", frames_path)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            checked = check_transcript(transcript, manifest, _validate_export(manifest_path, frames_path), status)
    except (LiveCaptureCheckError, ValueError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(checked, sort_keys=True))
    return 0 if checked["satisfies_s25_live_capture"] else 3


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
