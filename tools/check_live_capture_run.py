#!/usr/bin/env python3
"""Check one bounded live-capture instrumentation transcript and pulled export."""

from __future__ import annotations

import hashlib
import json
import math
import re
import subprocess
import sys
import tempfile
from pathlib import Path

_RECORD_PATTERN = re.compile(
    r"^INSTRUMENTATION_STATUS: LiveCaptureEvidence=(?P<json>\{.*\})$", re.MULTILINE
)
_SUITE_MARKER = (
    "class=com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest"
)
_EXPECTED_RECORD_KIND = "live_capture_device_gate_observation"
M05_ADMISSION_PATHS = ("live_transcript", "recorded_live_ledger")
_LEGACY_SCHEMA_VERSION = "1.0.0"
_M05_SCHEMA_VERSION = "1.1.0"
_S25_MODEL_PREFIX = "SM-S938"
_S25_MARKER = "S25 Ultra"
_AUTHORIZED_MANUFACTURER = "samsung"
_ADMITTED_DATASPACE = 146931712
_NO_EXPORT_REASON = "live_capture_export_unavailable"
_UNSANITIZED_FAILURE_DETAIL = "run_failure_detail_not_sanitized"
_HARNESS_GIT_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
_ABSOLUTE_PATH = re.compile(r"(?:file://)?(?:/|[A-Za-z]:[\\/])\S+")


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
        raise LiveCaptureCheckError(
            "transcript does not show LiveCaptureDeviceGateTest"
        )
    if not re.search(r"^OK \(1 test\)$", transcript, re.MULTILINE):
        raise LiveCaptureCheckError(
            'transcript does not report exactly one passing test ("OK (1 test)")'
        )


def extract_record(transcript: str) -> dict:
    matches = _RECORD_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise LiveCaptureCheckError(
            f"expected exactly one LiveCaptureEvidence record, found {len(matches)}"
        )
    try:
        record = json.loads(matches[0])
    except json.JSONDecodeError as error:
        raise LiveCaptureCheckError(
            f"LiveCaptureEvidence is not valid JSON: {error}"
        ) from error
    if record.get("record_kind") != _EXPECTED_RECORD_KIND:
        raise LiveCaptureCheckError(
            "LiveCaptureEvidence record_kind is not the live-capture gate kind"
        )
    _require_record_schema_marker_consistency(record)
    if (
        record.get("sanitized") is not True
        or record.get("contains_landmark_coordinates") is not False
    ):
        raise LiveCaptureCheckError(
            "LiveCaptureEvidence is not an exact sanitized record"
        )
    harness_git_sha = record.get("harness_git_sha")
    if (
        not isinstance(harness_git_sha, str)
        or _HARNESS_GIT_SHA_PATTERN.fullmatch(harness_git_sha) is None
    ):
        raise LiveCaptureCheckError(
            "LiveCaptureEvidence harness_git_sha must be a lowercase 40-hex commit SHA"
        )
    return record


def _require_record_schema_marker_consistency(record: dict) -> None:
    has_m05_admission = "m05_admission" in record
    expected_schema_version = (
        _M05_SCHEMA_VERSION if has_m05_admission else _LEGACY_SCHEMA_VERSION
    )
    if record.get("artifact_schema_version") != expected_schema_version:
        raise LiveCaptureCheckError(
            "LiveCaptureEvidence schema version does not match its explicit M0.5 admission marker"
        )


def _m05_admission_blocking_codes(record: dict) -> list[str]:
    admission = record.get("m05_admission")
    if admission is None:
        return []
    if not isinstance(admission, dict):
        return ["STUDY.ADMISSION_INPUT_CLASS"]

    codes: list[str] = []
    if admission.get("session_kind") != "live_conformance":
        codes.append("STUDY.ADMISSION_SESSION_KIND")
    pseudonym = admission.get("participant_pseudonym_id")
    input_class = admission.get("input_class")
    session_id = record.get("session_id")
    if (
        not isinstance(pseudonym, str)
        or not isinstance(session_id, str)
        or pseudonym != f"synthetic-m05-{session_id}"
        or input_class not in {"empty_scene", "displayed_synthetic_figure"}
        or admission.get("operator_attested_no_person") is not True
    ):
        codes.append("STUDY.ADMISSION_INPUT_CLASS")

    fraction = admission.get("detection_active_frame_fraction")
    if input_class == "empty_scene":
        if (
            not isinstance(fraction, (int, float))
            or isinstance(fraction, bool)
            or not math.isfinite(fraction)
            or fraction != 0
            or admission.get("detection_evidence_state") != "not_applicable"
        ):
            codes.append("STUDY.EMPTY_SCENE_DETECTION")
    elif input_class == "displayed_synthetic_figure":
        if (
            not isinstance(fraction, (int, float))
            or isinstance(fraction, bool)
            or not math.isfinite(fraction)
            or fraction <= 0
            or fraction > 1
            or admission.get("detection_evidence_state") != "active"
        ):
            codes.append("STUDY.SYNTHETIC_DETECTION_PENDING")
    return list(dict.fromkeys(codes))


def _intrinsic_blocking_reasons(record: dict) -> list[str]:
    reasons: list[str] = []
    environment = record.get("environment", {})
    if not is_authorized_live_capture_target(environment):
        reasons.append("unauthorized_device_target")

    manifest_device = record.get("manifest_device", {})
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
    modern_typed_evidence = "delivered_callback_classification_gap" in counts
    if modern_typed_evidence:
        capture_width = camera.get("capture_width")
        capture_height = camera.get("capture_height")
        orientation_degrees = camera.get("orientation_degrees")
        mirrored = camera.get("mirrored")
        if (
            not _is_positive_int(capture_width)
            or not _is_positive_int(capture_height)
            or orientation_degrees not in {0, 90, 180, 270}
            or isinstance(orientation_degrees, bool)
            or not isinstance(mirrored, bool)
        ):
            reasons.append("camera_geometry_invalid")

        thermal = record.get("thermal")
        if not _thermal_evidence_is_valid(thermal):
            reasons.append("thermal_evidence_invalid")

    count_reason = {
        "camera_frames_received": "camera_frames_not_received",
        "inference_frames_submitted": "inference_frames_not_submitted",
        "inference_records_emitted": "inference_records_not_emitted",
    }
    for key, reason in count_reason.items():
        if not isinstance(counts.get(key), int) or counts[key] <= 0:
            reasons.append(reason)
    # New records distinguish exact accounting of delivered analyzer callbacks from the
    # requested-camera-rate estimate. Old, already-recorded evidence remains readable.
    if "delivered_callback_classification_gap" in counts:
        if counts.get("delivered_callback_classification_gap") != 0:
            reasons.append("delivered_callback_classification_gap")
        if not _cadence_diagnostics_are_consistent(counts):
            reasons.append("camera_cadence_diagnostics_invalid")
    elif counts.get("unclassified_delivered_callbacks") != 0:
        reasons.append("unclassified_delivered_callbacks")

    failure_code = record.get("run_failure_code")
    if failure_code is not None:
        reasons.append(str(failure_code))
    failure_detail = record.get("run_failure_detail")
    if failure_detail is not None and (
        not isinstance(failure_detail, str)
        or "\n" in failure_detail
        or "\r" in failure_detail
        or len(failure_detail) > 256
        or _ABSOLUTE_PATH.search(failure_detail) is not None
    ):
        reasons.append(_UNSANITIZED_FAILURE_DETAIL)
    return list(dict.fromkeys(reasons))


def _is_nonnegative_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _is_positive_int(value: object) -> bool:
    return _is_nonnegative_int(value) and value > 0


def _thermal_evidence_is_valid(value: object) -> bool:
    if not isinstance(value, dict):
        return False
    samples = value.get("samples")
    if (
        value.get("api_available") is not True
        or value.get("unavailable_reason") is not None
        or not isinstance(samples, list)
        or not samples
    ):
        return False
    return all(
        isinstance(sample, dict)
        and _is_nonnegative_int(sample.get("t_monotonic_ns"))
        and _is_nonnegative_int(sample.get("status"))
        for sample in samples
    )


def _cadence_diagnostics_are_consistent(counts: dict) -> bool:
    diagnostics = counts.get("cadence_diagnostics")
    if not isinstance(diagnostics, dict):
        return False
    delivered = diagnostics.get("delivered_callback_count")
    requested_fps = diagnostics.get("requested_camera_fps")
    opportunities = diagnostics.get("requested_callback_opportunities")
    upstream_gap = diagnostics.get("upstream_unclassified_cadence_gap_estimate")
    classification_gap = diagnostics.get("delivered_callback_classification_gap")
    if (
        not _is_nonnegative_int(delivered)
        or not isinstance(requested_fps, int)
        or isinstance(requested_fps, bool)
        or requested_fps != 30
        or not _is_nonnegative_int(opportunities)
        or not _is_nonnegative_int(upstream_gap)
        or not _is_nonnegative_int(classification_gap)
        or delivered != counts.get("camera_frames_received")
        or classification_gap != counts.get("delivered_callback_classification_gap")
    ):
        return False

    first = diagnostics.get("first_delivered_capture_timestamp_ns")
    last = diagnostics.get("last_delivered_capture_timestamp_ns")
    span = diagnostics.get("observed_capture_span_ns")
    if delivered == 0:
        return (
            first is None
            and last is None
            and span is None
            and opportunities == 0
            and upstream_gap == 0
        )
    if not all(_is_nonnegative_int(value) for value in (first, last, span)):
        return False
    if last < first or span != last - first:
        return False
    expected_opportunities = (span * requested_fps) // 1_000_000_000 + 1
    return (
        opportunities == expected_opportunities
        and upstream_gap == max(opportunities - delivered, 0)
    )


def _manifest_blocking_reasons(record: dict, manifest: dict) -> list[str]:
    reasons: list[str] = []
    manifest_device = record.get("manifest_device", {})
    actual_device = manifest.get("device", {})
    if any(
        actual_device.get(key) != manifest_device.get(key)
        for key in ("platform", "os_version", "device_model", "chipset")
    ):
        reasons.append("manifest_device_mismatch")
    if manifest.get("session_id") != record.get("session_id"):
        reasons.append("manifest_session_mismatch")
    if manifest.get("session_kind") != "live_conformance":
        reasons.append("manifest_session_kind_mismatch")
    admission = record.get("m05_admission")
    if isinstance(admission, dict):
        if manifest.get("participant_pseudonym_id") != admission.get(
            "participant_pseudonym_id"
        ):
            reasons.append("manifest_participant_pseudonym_mismatch")
        if manifest.get("study_context", {}).get("m05_admission") != admission:
            reasons.append("manifest_m05_admission_mismatch")
    if manifest.get("pose_pipeline", {}).get("running_mode") != "live_stream":
        reasons.append("manifest_running_mode_mismatch")

    camera = record.get("camera", {})
    actual_camera = manifest.get("camera", {})
    camera_fields = {
        "source_pixel_format": "pixel_format",
        "source_color_matrix": "source_color_standard",
        "source_color_range": "source_color_range",
        "source_dataspace_raw": "source_dataspace_raw",
        "timestamp_source": "timestamp_source",
    }
    for manifest_key, evidence_key in camera_fields.items():
        if actual_camera.get(manifest_key) != camera.get(evidence_key):
            reasons.append("manifest_camera_mismatch")
            break
    counts = record.get("counts", {})
    modern_typed_evidence = "delivered_callback_classification_gap" in counts
    if modern_typed_evidence:
        for key in ("capture_width", "capture_height", "orientation_degrees", "mirrored"):
            if actual_camera.get(key) != camera.get(key):
                reasons.append("manifest_camera_geometry_mismatch")
                break
    if actual_camera.get("requested_camera_fps") != 30.0:
        reasons.append("manifest_camera_fps_mismatch")

    if modern_typed_evidence:
        thermal = record.get("thermal")
        if not isinstance(thermal, dict):
            thermal = {}
        if actual_device.get("thermal_api_available") != thermal.get("api_available"):
            reasons.append("manifest_thermal_device_mismatch")
        if manifest.get("study_context", {}).get("thermal_observations") != thermal:
            reasons.append("manifest_thermal_observations_mismatch")

    diagnostics = counts.get("cadence_diagnostics")
    if diagnostics is not None and manifest.get("study_context", {}).get(
        "camera_cadence_diagnostics"
    ) != diagnostics:
        reasons.append("manifest_cadence_diagnostics_mismatch")

    cadence = manifest.get("cadence", {})
    if cadence.get("configured_inference_fps") != 10.0:
        reasons.append("manifest_configured_fps_mismatch")
    timeseries = cadence.get("achieved_inference_fps_timeseries")
    if not isinstance(timeseries, list) or not timeseries or timeseries == [
        {"t_monotonic_ns": 0, "achieved_inference_fps": 0.0}
    ]:
        reasons.append("manifest_cadence_invalid")
    else:
        for sample in timeseries:
            t_value = sample.get("t_monotonic_ns") if isinstance(sample, dict) else None
            fps_value = (
                sample.get("achieved_inference_fps")
                if isinstance(sample, dict)
                else None
            )
            if (
                isinstance(t_value, bool)
                or not isinstance(t_value, int)
                or t_value < 0
                or isinstance(fps_value, bool)
                or not isinstance(fps_value, (int, float))
                or not math.isfinite(fps_value)
                or fps_value < 0
            ):
                reasons.append("manifest_cadence_invalid")
                break

    manifest_counts = manifest.get("counts", {})
    count_fields = (
        "camera_frames_received",
        "inference_frames_submitted",
        "inference_records_emitted",
        "inference_failures",
        "frames_skipped_before_inference",
        "frames_skipped_before_inference_by_reason",
    )
    if any(manifest_counts.get(key) != counts.get(key) for key in count_fields):
        reasons.append("manifest_counts_mismatch")
    failures = manifest.get("machine_inference_failures")
    if not isinstance(failures, list) or len(failures) != counts.get(
        "inference_failures"
    ):
        reasons.append("manifest_counts_mismatch")
    else:
        projected_failures = [
            {
                "submission_index": failure.get("submission_index"),
                "failure_source": failure.get("failure_source"),
                "classification": failure.get("classification"),
                "source_error_code": failure.get("source_error_code"),
            }
            for failure in failures
        ]
        if projected_failures != record.get("inference_failure_identities", []):
            reasons.append("manifest_failure_identity_mismatch")

    return list(dict.fromkeys(reasons))


def _blocking_reasons(
    record: dict,
    manifest: dict | None,
    validation_succeeded: bool,
    instrumentation_exit_code: int,
    no_export: bool = False,
) -> list[str]:
    reasons = _intrinsic_blocking_reasons(record)
    reasons.extend(_m05_admission_blocking_codes(record))
    if manifest is not None:
        reasons.extend(_manifest_blocking_reasons(record, manifest))
    if not validation_succeeded:
        reasons.append("reference_validation_failed")
    if instrumentation_exit_code != 0:
        reasons.append("instrumentation_failed")
    if no_export:
        reasons.append(_NO_EXPORT_REASON)
    return list(dict.fromkeys(reasons))


def _recorded_blocking_reasons(record: dict) -> list[str]:
    reasons = _intrinsic_blocking_reasons(record)
    reasons.extend(_m05_admission_blocking_codes(record))
    if record.get("reference_validation_succeeded") is not True:
        reasons.append("reference_validation_failed")
    if record.get("instrumentation_succeeded") is not True:
        reasons.append("instrumentation_failed")
    return list(dict.fromkeys(reasons))


def check_transcript(
    transcript: str,
    manifest: dict | None,
    validation_succeeded: bool,
    instrumentation_exit_code: int,
    no_export: bool = False,
) -> dict:
    _require_exact_suite(transcript)
    record = extract_record(transcript)
    reasons = _blocking_reasons(
        record, manifest, validation_succeeded, instrumentation_exit_code, no_export
    )
    result = dict(record)
    result["reference_validation_succeeded"] = validation_succeeded
    result["instrumentation_succeeded"] = instrumentation_exit_code == 0
    result["blocking_reasons"] = reasons
    result["satisfies_s25_live_capture"] = not reasons
    return result


def _pull(
    serial: str, package: str, session_id: str, filename: str, destination: Path
) -> None:
    export = f"/data/user/0/{package}/files/diagnostic-harness/exports/{session_id}/{filename}"
    result = subprocess.run(
        ["adb", "-s", serial, "shell", "run-as", package, "cat", export],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0 or not result.stdout:
        raise LiveCaptureCheckError(
            f"failed to pull {filename}: {result.stderr.decode(errors='replace')}"
        )
    destination.write_bytes(result.stdout)


def _validate_export(manifest_path: Path, frames_path: Path) -> bool:
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "observation_contracts",
            "validate-session",
            str(manifest_path),
            str(frames_path),
        ],
        capture_output=True,
        check=False,
    )
    return result.returncode == 0


def verify_recorded_ledger(root: Path, ledger_relative: str) -> dict:
    ledger_path = root / ledger_relative
    ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    if ledger.get("status") == "pending":
        if not ledger.get("missing_precondition"):
            raise LiveCaptureCheckError(
                "pending ledger must name its missing physical precondition"
            )
        return ledger
    if ledger.get("status") != "recorded":
        raise LiveCaptureCheckError(
            "live-capture ledger status must be pending or recorded"
        )
    evidence_file = ledger.get("evidence_file")
    if not evidence_file:
        raise LiveCaptureCheckError("recorded ledger must reference evidence_file")
    evidence_path = root / evidence_file
    payload = evidence_path.read_bytes()
    actual_sha = hashlib.sha256(payload).hexdigest()
    if ledger.get("evidence_sha256") != actual_sha:
        raise LiveCaptureCheckError(
            "recorded evidence_sha256 does not match evidence file sha256"
        )
    record = json.loads(payload)
    _require_record_schema_marker_consistency(record)
    harness_git_sha = record.get("harness_git_sha")
    if (
        not isinstance(harness_git_sha, str)
        or _HARNESS_GIT_SHA_PATTERN.fullmatch(harness_git_sha) is None
    ):
        raise LiveCaptureCheckError(
            "recorded evidence harness_git_sha must be a lowercase 40-hex commit SHA"
        )
    recomputed_reasons = _recorded_blocking_reasons(record)
    if record.get("blocking_reasons") != recomputed_reasons:
        raise LiveCaptureCheckError(
            "recorded blocking_reasons disagree with recomputed observations"
        )
    recomputed_verdict = not recomputed_reasons
    if record.get("satisfies_s25_live_capture") is not recomputed_verdict:
        raise LiveCaptureCheckError(
            "recorded evidence verdict disagrees with recomputed observations"
        )
    if ledger.get("live_admission") != (
        "admitted" if recomputed_verdict else "blocked"
    ):
        raise LiveCaptureCheckError(
            "ledger live_admission disagrees with evidence verdict"
        )
    return ledger


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print(
            f"usage: {Path(argv[0]).name} <serial> <package> <transcript-file> <instrumentation-status>",
            file=sys.stderr,
        )
        return 2
    serial, package, transcript_arg, status_arg = argv[1:]
    transcript = (
        sys.stdin.read()
        if transcript_arg == "-"
        else Path(transcript_arg).read_text(encoding="utf-8")
    )
    try:
        status = int(status_arg)
        record = extract_record(transcript)
        intrinsic_reasons = _intrinsic_blocking_reasons(record)
        no_real_session = (
            not record.get("session_id")
            or record.get("session_id") == "00000000-0000-4000-8000-000000000000"
        )
        if (
            intrinsic_reasons
            or record.get("run_failure_code") is not None
            or no_real_session
            or status != 0
        ):
            checked = check_transcript(transcript, None, False, status, no_export=True)
            print(json.dumps(checked, sort_keys=True))
            return 3
        with tempfile.TemporaryDirectory() as directory:
            manifest_path = Path(directory) / "manifest.json"
            frames_path = Path(directory) / "frames.ndjson"
            _pull(serial, package, record["session_id"], "manifest.json", manifest_path)
            _pull(serial, package, record["session_id"], "frames.ndjson", frames_path)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            checked = check_transcript(
                transcript,
                manifest,
                _validate_export(manifest_path, frames_path),
                status,
            )
    except (LiveCaptureCheckError, ValueError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(checked, sort_keys=True))
    return 0 if checked["satisfies_s25_live_capture"] else 3


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
