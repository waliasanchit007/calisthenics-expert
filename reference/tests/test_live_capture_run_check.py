import copy
import hashlib
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import check_live_capture_run as live_checker
from check_live_capture_run import (
    LiveCaptureCheckError,
    check_transcript,
    verify_recorded_ledger,
)


def exact_record() -> dict:
    return {
        "artifact_schema_version": "1.0.0",
        "record_kind": "live_capture_device_gate_observation",
        "sanitized": True,
        "contains_landmark_coordinates": False,
        "emitted_by": "com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest",
        "session_id": "session-1",
        "environment": {
            "device_class": "physical_device",
            "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
            "device_fingerprint": "fingerprint",
            "api_level": 36,
            "android_release": "16",
            "abi": "arm64-v8a",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "marketing_name": "",
            "chipset": "s5e9955",
        },
        "manifest_device": {
            "platform": "android",
            "os_version": "16",
            "device_model": "SM-S938B",
            "chipset": "s5e9955",
        },
        "camera": {
            "camera_id": "0",
            "timestamp_source": "REALTIME",
            "source_dataspace_raw": 146931712,
            "source_color_standard": "BT601",
            "source_color_range": "FULL",
            "underlying_image_available": True,
            "pixel_format": "YUV_420_888",
        },
        "counts": {
            "camera_frames_received": 10,
            "inference_frames_submitted": 2,
            "inference_records_emitted": 2,
            "inference_failures": 0,
            "frames_skipped_before_inference": 8,
            "frames_skipped_before_inference_by_reason": {"cadence_selection": 8},
            "unclassified_delivered_callbacks": 0,
        },
        "inference_failure_identities": [],
        "reference_validation_succeeded": True,
        "instrumentation_succeeded": True,
        "run_failure_code": None,
        "blocking_reasons": [],
        "satisfies_s25_live_capture": True,
    }


def transcript(record: dict) -> str:
    return "\n".join(
        [
            "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest",
            "INSTRUMENTATION_STATUS: LiveCaptureEvidence=" + json.dumps(record, sort_keys=True),
            "INSTRUMENTATION_STATUS_CODE: 0",
            "OK (1 test)",
        ]
    )


def manifest() -> dict:
    return {
        "session_id": "session-1",
        "session_kind": "live_conformance",
        "device": {
            "platform": "android",
            "os_version": "16",
            "device_model": "SM-S938B",
            "chipset": "s5e9955",
        },
        "pose_pipeline": {"running_mode": "live_stream"},
        "camera": {
            "requested_camera_fps": 30.0,
            "source_pixel_format": "YUV_420_888",
            "source_color_matrix": "BT601",
            "source_color_range": "FULL",
            "source_dataspace_raw": 146931712,
            "timestamp_source": "REALTIME",
        },
        "counts": {
            "camera_frames_received": 10,
            "inference_frames_submitted": 2,
            "inference_records_emitted": 2,
            "inference_failures": 0,
            "frames_skipped_before_inference": 8,
            "frames_skipped_before_inference_by_reason": {"cadence_selection": 8},
        },
        "machine_inference_failures": [],
        "cadence": {
            "configured_inference_fps": 10.0,
            "achieved_inference_fps_timeseries": [
                {"t_monotonic_ns": 1_000_000_000, "achieved_inference_fps": 1.0}
            ],
        },
    }


def test_admitted_transcript_requires_manifest_and_all_observed_conjuncts() -> None:
    result = check_transcript(
        transcript(exact_record()),
        manifest(),
        validation_succeeded=True,
        instrumentation_exit_code=0,
    )
    assert result["satisfies_s25_live_capture"] is True
    assert result["blocking_reasons"] == []


@pytest.mark.parametrize(
    ("path", "expected"),
    [
        (("environment", "model"), "unauthorized_device_target"),
        (("environment", "manufacturer"), "unauthorized_device_target"),
        (("environment", "device_class"), "unauthorized_device_target"),
        (("manifest_device", "device_model"), "manifest_device_mismatch"),
        (("camera", "timestamp_source"), "timestamp_source_not_realtime"),
        (("camera", "camera_id"), "camera_id_not_zero"),
        (("camera", "source_dataspace_raw"), "raw_dataspace_not_admitted"),
        (("camera", "source_color_standard"), "colour_standard_not_bt601"),
        (("camera", "source_color_range"), "colour_range_not_full"),
        (("camera", "underlying_image_available"), "underlying_image_bridge_unavailable"),
        (("camera", "pixel_format"), "pixel_format_not_yuv_420_888"),
        (("counts", "camera_frames_received"), "camera_frames_not_received"),
        (("counts", "inference_frames_submitted"), "inference_frames_not_submitted"),
        (("counts", "inference_records_emitted"), "inference_records_not_emitted"),
        (("counts", "unclassified_delivered_callbacks"), "unclassified_delivered_callbacks"),
    ],
)
def test_each_gate_mutation_blocks(path: tuple[str, str], expected: str) -> None:
    record = exact_record()
    mutated = copy.deepcopy(record)
    if path == ("environment", "model"):
        mutated[path[0]][path[1]] = "SM-S918B"
    elif path == ("environment", "manufacturer"):
        mutated[path[0]][path[1]] = "google"
    elif path == ("environment", "device_class"):
        mutated[path[0]][path[1]] = "emulator"
    elif path == ("manifest_device", "device_model"):
        mutated[path[0]][path[1]] = "unknown"
    elif path == ("camera", "underlying_image_available"):
        mutated[path[0]][path[1]] = False
    elif path == ("counts", "unclassified_delivered_callbacks"):
        mutated[path[0]][path[1]] = 1
    elif path[1] == "timestamp_source":
        mutated[path[0]][path[1]] = "UNKNOWN"
    elif path[1] == "camera_id":
        mutated[path[0]][path[1]] = "1"
    elif path[1] == "source_dataspace_raw":
        mutated[path[0]][path[1]] = 0
    elif path[1] == "source_color_standard":
        mutated[path[0]][path[1]] = "BT709"
    elif path[1] == "source_color_range":
        mutated[path[0]][path[1]] = "LIMITED"
    elif path[1] == "pixel_format":
        mutated[path[0]][path[1]] = "RGBA_8888"
    else:
        mutated[path[0]][path[1]] = 0

    checked = check_transcript(
        transcript(mutated), manifest(), validation_succeeded=True, instrumentation_exit_code=0
    )
    assert expected in checked["blocking_reasons"]
    assert checked["satisfies_s25_live_capture"] is False


def test_validation_and_instrumentation_are_observations_not_caller_verdict() -> None:
    blocked_validation = check_transcript(
        transcript(exact_record()),
        manifest(),
        validation_succeeded=False,
        instrumentation_exit_code=0,
    )
    assert "reference_validation_failed" in blocked_validation["blocking_reasons"]
    assert blocked_validation["satisfies_s25_live_capture"] is False
    blocked = check_transcript(
        transcript(exact_record()),
        manifest(),
        validation_succeeded=True,
        instrumentation_exit_code=1,
    )
    assert "instrumentation_failed" in blocked["blocking_reasons"]
    assert blocked["satisfies_s25_live_capture"] is False


@pytest.mark.parametrize(
    "failure_code", ["live_capture_timeout", "live_inference_stalled", "live_capture_failed"]
)
def test_typed_run_failure_blocks(failure_code: str) -> None:
    record = exact_record()
    record["run_failure_code"] = failure_code
    checked = check_transcript(
        transcript(record), manifest(), validation_succeeded=True, instrumentation_exit_code=0
    )
    assert failure_code in checked["blocking_reasons"]
    assert checked["satisfies_s25_live_capture"] is False


def test_pulled_manifest_identity_is_checked_against_observed_record() -> None:
    for field, value in (
        ("platform", "ios"),
        ("os_version", "15"),
        ("device_model", "SM-S918B"),
        ("chipset", "other"),
    ):
        pulled = manifest()
        pulled["device"][field] = value
        checked = check_transcript(
            transcript(exact_record()),
            pulled,
            validation_succeeded=True,
            instrumentation_exit_code=0,
        )
        assert "manifest_device_mismatch" in checked["blocking_reasons"]
        assert checked["satisfies_s25_live_capture"] is False


@pytest.mark.parametrize(
    ("path", "value", "expected"),
    [
        (("session_id",), "other", "manifest_session_mismatch"),
        (("session_kind",), "replay", "manifest_session_kind_mismatch"),
        (("pose_pipeline", "running_mode"), "image", "manifest_running_mode_mismatch"),
        (("camera", "source_pixel_format"), "RGBA_8888", "manifest_camera_mismatch"),
        (("camera", "source_color_matrix"), "BT709", "manifest_camera_mismatch"),
        (("camera", "source_color_range"), "LIMITED", "manifest_camera_mismatch"),
        (("camera", "source_dataspace_raw"), 0, "manifest_camera_mismatch"),
        (("camera", "requested_camera_fps"), 15.0, "manifest_camera_fps_mismatch"),
        (("cadence", "configured_inference_fps"), 30.0, "manifest_configured_fps_mismatch"),
        (("counts", "inference_failures"), 1, "manifest_counts_mismatch"),
        (("counts", "frames_skipped_before_inference"), 7, "manifest_counts_mismatch"),
    ],
)
def test_each_pulled_manifest_boundary_mutation_blocks(
    path: tuple[str, ...], value: object, expected: str
) -> None:
    pulled = manifest()
    target = pulled
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value
    checked = check_transcript(transcript(exact_record()), pulled, True, 0)
    assert expected in checked["blocking_reasons"]
    assert checked["satisfies_s25_live_capture"] is False


def test_manifest_cadence_rejects_synthetic_zero_sample_and_invalid_values() -> None:
    for timeseries in (
        [{"t_monotonic_ns": 0, "achieved_inference_fps": 0.0}],
        [{"t_monotonic_ns": -1, "achieved_inference_fps": 1.0}],
        [{"t_monotonic_ns": 1, "achieved_inference_fps": -1.0}],
        [{"t_monotonic_ns": 1, "achieved_inference_fps": float("nan")}],
    ):
        pulled = manifest()
        pulled["cadence"]["achieved_inference_fps_timeseries"] = timeseries
        checked = check_transcript(transcript(exact_record()), pulled, True, 0)
        assert "manifest_cadence_invalid" in checked["blocking_reasons"]
        assert checked["satisfies_s25_live_capture"] is False


def test_pulled_manifest_failure_identity_mutation_blocks() -> None:
    record = exact_record()
    record["counts"]["inference_failures"] = 1
    record["inference_failure_identities"] = [
        {
            "submission_index": 0,
            "failure_source": "backend",
            "classification": "backend_call_failed",
            "source_error_code": "E1",
        }
    ]
    pulled = manifest()
    pulled["counts"]["inference_failures"] = 1
    pulled["machine_inference_failures"] = [
        {
            "submission_index": 0,
            "capture_timestamp_ns": 1,
            "failure_source": "backend",
            "classification": "different",
            "source_error_code": "E1",
            "detail": None,
        }
    ]
    checked = check_transcript(transcript(record), pulled, True, 0)
    assert "manifest_failure_identity_mismatch" in checked["blocking_reasons"]
    assert checked["satisfies_s25_live_capture"] is False


def test_exactly_one_record_and_exact_suite_are_required() -> None:
    with pytest.raises(LiveCaptureCheckError, match="exactly one"):
        check_transcript(
            transcript(exact_record()) + "\n" + transcript(exact_record()), manifest(), True, 0
        )
    with pytest.raises(LiveCaptureCheckError, match="exactly one passing"):
        check_transcript(
            transcript(exact_record()).replace("OK (1 test)", "OK (0 tests)"), manifest(), True, 0
        )


def test_recorded_ledger_opens_exact_evidence_and_verifies_sha(tmp_path: Path) -> None:
    evidence_path = tmp_path / "evidence.json"
    evidence_path.write_text(json.dumps(exact_record(), sort_keys=True) + "\n", encoding="utf-8")
    ledger_path = tmp_path / "ledger.json"
    ledger_path.write_text(
        json.dumps(
            {
                "status": "recorded",
                "evidence_file": "evidence.json",
                "evidence_sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
                "live_admission": "admitted",
            }
        ),
        encoding="utf-8",
    )
    assert verify_recorded_ledger(tmp_path, "ledger.json")["status"] == "recorded"
    evidence_path.write_text(evidence_path.read_text() + "tampered", encoding="utf-8")
    with pytest.raises(LiveCaptureCheckError, match="sha256"):
        verify_recorded_ledger(tmp_path, "ledger.json")


def test_recorded_ledger_recomputes_blocking_reasons_before_accepting_verdict(
    tmp_path: Path,
) -> None:
    evidence_path = tmp_path / "evidence.json"
    blocked = exact_record()
    blocked["camera"]["timestamp_source"] = "UNKNOWN"
    blocked["blocking_reasons"] = ["timestamp_source_not_realtime"]
    blocked["satisfies_s25_live_capture"] = True
    payload = json.dumps(blocked, sort_keys=True) + "\n"
    evidence_path.write_text(payload, encoding="utf-8")
    ledger_path = tmp_path / "ledger.json"
    ledger_path.write_text(
        json.dumps(
            {
                "status": "recorded",
                "evidence_file": "evidence.json",
                "evidence_sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
                "live_admission": "admitted",
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(LiveCaptureCheckError, match="blocking_reasons|verdict|admission"):
        verify_recorded_ledger(tmp_path, "ledger.json")


def test_typed_no_export_is_printed_as_blocked_without_pull(
    tmp_path: Path, monkeypatch, capsys
) -> None:
    record = exact_record()
    record["session_id"] = "00000000-0000-4000-8000-000000000000"
    record["run_failure_code"] = "live_capture_timeout"
    transcript_path = tmp_path / "transcript.txt"
    transcript_path.write_text(transcript(record), encoding="utf-8")

    def fail_pull(*args, **kwargs):
        raise AssertionError("blocked no-export evidence must not pull")

    monkeypatch.setattr(live_checker, "_pull", fail_pull)
    exit_code = live_checker.main(["checker", "serial", "package", str(transcript_path), "0"])
    assert exit_code == 3
    output = json.loads(capsys.readouterr().out)
    assert output["satisfies_s25_live_capture"] is False
    assert "live_capture_timeout" in output["blocking_reasons"]
    assert output["reference_validation_succeeded"] is False
