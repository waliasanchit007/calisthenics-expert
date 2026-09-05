from __future__ import annotations

import hashlib
import json
import sys
from copy import deepcopy
from pathlib import Path
from unittest.mock import Mock

import pytest

ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import check_m05b_attribution_v2

from observation_contracts.m05b_attribution_v2 import selector_timestamps
from tests.samples import minimal_manifest, minimal_pose_frame

STATUS_TAG = check_m05b_attribution_v2.STATUS_TAG
SUITE = check_m05b_attribution_v2.SUITE


def _reference(**overrides: object) -> dict[str, object]:
    record = {
        "record_kind": "m05b_attribution_v2_reference",
        "protocol_sha256": "a" * 64,
        "relative_path": "attribution-v2/run/observation.json",
        "sha256": "b" * 64,
        "length_bytes": 7,
    }
    record.update(overrides)
    return record


def _transcript(record: dict[str, object] | None = None, *, suite: str = SUITE) -> str:
    payload = json.dumps(record or _reference(), separators=(",", ":"))
    return (
        f"INSTRUMENTATION_STATUS: class={suite}\n"
        f"INSTRUMENTATION_STATUS: {STATUS_TAG}={payload}\n"
        "INSTRUMENTATION_STATUS_CODE: 4\n"
        "OK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


def test_extract_reference_requires_one_exact_status_record() -> None:
    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="exactly one"):
        check_m05b_attribution_v2.extract_reference(_transcript().replace(STATUS_TAG, "OTHER"))

    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="exactly one"):
        check_m05b_attribution_v2.extract_reference(
            _transcript() + _transcript().split("INSTRUMENTATION_STATUS_CODE")[0]
        )


@pytest.mark.parametrize(
    "transcript, message",
    [
        (
            _transcript(
                record={
                    "record_kind": "m05b_attribution_v2_reference",
                    "protocol_sha256": "a" * 64,
                    "relative_path": "attribution-v2/run/observation.json",
                    "sha256": "b" * 64,
                    "length_bytes": "bad",
                }
            ).replace('"length_bytes":"bad"', "{not-json}"),
            "JSON",
        ),
        (_transcript(suite="com.example.OtherTest"), "suite"),
    ],
)
def test_extract_reference_rejects_malformed_or_foreign_records(
    transcript: str, message: str
) -> None:
    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match=message):
        check_m05b_attribution_v2.extract_reference(transcript)


def test_transfer_reference_uses_fixed_binary_safe_run_as_argv_and_publishes_exact_bytes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    completed = Mock(returncode=0, stdout=b"\x00\xffpayload", stderr=b"")
    run = Mock(return_value=completed)
    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", run)
    destination = tmp_path / "observation.json"

    check_m05b_attribution_v2.transfer_reference(
        "SERIAL-PRIVATE", "com.example.app", "attribution-v2/run/observation.json", destination
    )

    assert run.call_args.args[0] == [
        "adb",
        "-s",
        "SERIAL-PRIVATE",
        "exec-out",
        "run-as",
        "com.example.app",
        "cat",
        "attribution-v2/run/observation.json",
    ]
    assert run.call_args.kwargs["shell"] is False
    assert run.call_args.kwargs["check"] is False
    assert destination.read_bytes() == b"\x00\xffpayload"


def test_transfer_reference_rejects_unsafe_relative_path_before_subprocess(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    run = Mock()
    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", run)

    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="relative"):
        check_m05b_attribution_v2.transfer_reference(
            "SERIAL-PRIVATE", "com.example.app", "../observation.json", tmp_path / "out"
        )
    run.assert_not_called()


def test_transfer_reference_marks_post_publication_transport_loss_as_awaiting_recovery(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        check_m05b_attribution_v2.subprocess,
        "run",
        Mock(return_value=Mock(returncode=1, stdout=b"published-prefix", stderr=b"lost")),
    )

    with pytest.raises(
        check_m05b_attribution_v2.AttributionV2CheckError, match="awaiting_recovery"
    ):
        check_m05b_attribution_v2.transfer_reference(
            "SERIAL-PRIVATE",
            "com.example.app",
            "attribution-v2/run/observation.json",
            tmp_path / "out",
        )


def test_transfer_reference_verifies_expected_bytes_before_final_rename(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    payload = b"exact-payload"
    destination = tmp_path / "observation.json"
    replace_events: list[str] = []
    original_replace = check_m05b_attribution_v2.os.replace

    monkeypatch.setattr(
        check_m05b_attribution_v2.subprocess,
        "run",
        Mock(return_value=Mock(returncode=0, stdout=payload, stderr=b"")),
    )

    def recording_replace(source: str | bytes, target: str | bytes) -> None:
        replace_events.append("rename")
        original_replace(source, target)

    monkeypatch.setattr(check_m05b_attribution_v2.os, "replace", recording_replace)

    check_m05b_attribution_v2.transfer_reference(
        "SERIAL-PRIVATE",
        "com.example.app",
        "attribution-v2/run/observation.json",
        destination,
        expected_length=len(payload),
        expected_sha256=hashlib.sha256(payload).hexdigest(),
    )

    assert replace_events == ["rename"]
    assert destination.read_bytes() == payload


def test_transfer_reference_leaves_no_destination_when_expected_digest_is_wrong(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    destination = tmp_path / "observation.json"
    replace = Mock()
    monkeypatch.setattr(
        check_m05b_attribution_v2.subprocess,
        "run",
        Mock(return_value=Mock(returncode=0, stdout=b"corrupt", stderr=b"")),
    )
    monkeypatch.setattr(check_m05b_attribution_v2.os, "replace", replace)

    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="digest"):
        check_m05b_attribution_v2.transfer_reference(
            "SERIAL-PRIVATE",
            "com.example.app",
            "attribution-v2/run/observation.json",
            destination,
            expected_length=7,
            expected_sha256="0" * 64,
        )

    replace.assert_not_called()
    assert not destination.exists()


def _realistic_session_bundle(
    session_id: str,
) -> tuple[dict[str, object], dict[str, bytes]]:
    identity = _valid_identity()
    manifest = minimal_manifest(
        session_id=session_id,
        schema_version="1.1.0",
        camera_frames_received=2,
        inference_frames_submitted=2,
        inference_records_emitted=2,
        pose_detected_records=2,
    )
    manifest["session_kind"] = "live_conformance"
    manifest["device"]["device_model"] = "SM-S938B"
    manifest["device"]["os_version"] = identity["runtime_version"]
    manifest["harness_git_sha"] = identity["app_git_sha"]
    manifest["pose_pipeline"]["model_asset_sha256"] = identity["model_sha256"]
    manifest["pose_pipeline"]["backend_version"] = identity["backend_version"]
    manifest["pose_pipeline"]["delegate"] = identity["delegate"]
    manifest["study_context"]["m05b_attribution_identity"] = identity
    frames = [
        minimal_pose_frame(index=index, capture_ns=(index + 1) * 100, session_id=session_id)
        for index in range(2)
    ]
    telemetry_records = []
    sequence = 0
    for frame_index in range(2):
        telemetry_records.extend(
            [
                {
                    "record_type": "pipeline_stage_event",
                    "schema_version": "1.0.0",
                    "sequence": sequence,
                    "t_session_ns": sequence,
                    "frame_index": frame_index,
                    "stage": "CAMERA_CALLBACK",
                    "disposition": "RECEIVED",
                },
                {
                    "record_type": "pipeline_stage_event",
                    "schema_version": "1.0.0",
                    "sequence": sequence + 1,
                    "t_session_ns": sequence + 1,
                    "frame_index": frame_index,
                    "stage": "INFERENCE",
                    "disposition": "SUBMITTED",
                },
                {
                    "record_type": "pipeline_stage_event",
                    "schema_version": "1.0.0",
                    "sequence": sequence + 2,
                    "t_session_ns": sequence + 2,
                    "frame_index": frame_index,
                    "stage": "INFERENCE",
                    "disposition": "COMPLETED",
                },
            ]
        )
        sequence += 3
    payloads = {
        f"exports/{session_id}/manifest.json": json.dumps(
            manifest, separators=(",", ":"), sort_keys=True
        ).encode(),
        f"exports/{session_id}/frames.ndjson": b"".join(
            json.dumps(frame, separators=(",", ":"), sort_keys=True).encode() + b"\n"
            for frame in frames
        ),
        f"exports/{session_id}/telemetry.ndjson": b"".join(
            json.dumps(record, separators=(",", ":"), sort_keys=True).encode() + b"\n"
            for record in telemetry_records
        ),
    }
    bundle = {
        "session_reference": f"exports/{session_id}",
        **{
            name: {
                "relative_path": path,
                "length_bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
            for name, suffix in (
                ("manifest", "manifest.json"),
                ("frames", "frames.ndjson"),
                ("telemetry", "telemetry.ndjson"),
            )
            for path, data in [
                (f"exports/{session_id}/{suffix}", payloads[f"exports/{session_id}/{suffix}"])
            ]
        },
    }
    return bundle, payloads


def _patch_bundle_payload(
    bundle: dict[str, object],
    payloads: dict[str, bytes],
    name: str,
    data: bytes,
) -> None:
    path = bundle[name]["relative_path"]
    payloads[path] = data
    bundle[name]["length_bytes"] = len(data)
    bundle[name]["sha256"] = hashlib.sha256(data).hexdigest()


def test_transfer_session_bundle_reopens_manifest_frames_and_telemetry_before_publish(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    session_id = "00000000-0000-4000-8000-000000000001"
    bundle, payloads = _realistic_session_bundle(session_id)

    def fake_run(argv: list[str], **_kwargs: object) -> object:
        path = argv[-1]
        return type("Completed", (), {"returncode": 0, "stdout": payloads[path]})()

    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", fake_run)

    result = check_m05b_attribution_v2.transfer_session_bundle(
        "SERIAL",
        "com.example.app",
        bundle,
        tmp_path / "exports",
        expected_identity=_valid_identity(),
        expected_cadence=30,
    )

    assert result == {
        "manifest": len(payloads[f"exports/{session_id}/manifest.json"]),
        "frames": 2,
        "telemetry": 6,
    }
    assert (tmp_path / f"exports/{session_id}/manifest.json").read_bytes() == payloads[
        f"exports/{session_id}/manifest.json"
    ]

    forged_digest = deepcopy(bundle)
    forged_digest["frames"]["sha256"] = "0" * 64
    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="digest"):
        check_m05b_attribution_v2.transfer_session_bundle(
            "SERIAL", "com.example.app", forged_digest, tmp_path / "forged"
        )


def test_transfer_session_bundle_binds_expected_counts_only_to_telemetry(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    session_id = "00000000-0000-4000-8000-000000000002"
    bundle, payloads = _realistic_session_bundle(session_id)

    def fake_run(argv: list[str], **_kwargs: object) -> object:
        path = argv[-1]
        return type("Completed", (), {"returncode": 0, "stdout": payloads[path]})()

    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", fake_run)

    result = check_m05b_attribution_v2.transfer_session_bundle(
        "SERIAL",
        "com.example.app",
        bundle,
        tmp_path / "exports",
        expected_counts={"delivered": 2, "submitted": 2, "terminal": 2},
    )

    assert result == {
        "manifest": len(payloads[f"exports/{session_id}/manifest.json"]),
        "frames": 2,
        "telemetry": 6,
    }


def test_transfer_session_bundle_rejects_mismatched_telemetry_counts(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    session_id = "00000000-0000-4000-8000-000000000003"
    bundle, payloads = _realistic_session_bundle(session_id)

    def fake_run(argv: list[str], **_kwargs: object) -> object:
        path = argv[-1]
        return type("Completed", (), {"returncode": 0, "stdout": payloads[path]})()

    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", fake_run)

    with pytest.raises(
        check_m05b_attribution_v2.AttributionV2CheckError,
        match="session telemetry counts do not bind",
    ):
        check_m05b_attribution_v2.transfer_session_bundle(
            "SERIAL",
            "com.example.app",
            bundle,
            tmp_path / "exports",
            expected_counts={"delivered": 2, "submitted": 3, "terminal": 2},
        )


def test_transfer_session_bundle_rejects_manifest_schema_violation(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    session_id = "00000000-0000-4000-8000-000000000004"
    bundle, payloads = _realistic_session_bundle(session_id)
    manifest = json.loads(payloads[f"exports/{session_id}/manifest.json"])
    manifest.pop("camera")
    _patch_bundle_payload(
        bundle,
        payloads,
        "manifest",
        json.dumps(manifest, separators=(",", ":"), sort_keys=True).encode(),
    )

    def fake_run(argv: list[str], **_kwargs: object) -> object:
        path = argv[-1]
        return type("Completed", (), {"returncode": 0, "stdout": payloads[path]})()

    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", fake_run)

    with pytest.raises(
        check_m05b_attribution_v2.AttributionV2CheckError,
        match="session manifest schema validation failed",
    ):
        check_m05b_attribution_v2.transfer_session_bundle(
            "SERIAL", "com.example.app", bundle, tmp_path / "exports"
        )


def test_transfer_session_bundle_rejects_raw_frame_schema_violation(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    session_id = "00000000-0000-4000-8000-000000000005"
    bundle, payloads = _realistic_session_bundle(session_id)
    frames = [
        json.loads(line) for line in payloads[f"exports/{session_id}/frames.ndjson"].splitlines()
    ]
    frames[0].pop("frame_index")
    _patch_bundle_payload(
        bundle,
        payloads,
        "frames",
        b"".join(
            json.dumps(frame, separators=(",", ":"), sort_keys=True).encode() + b"\n"
            for frame in frames
        ),
    )

    def fake_run(argv: list[str], **_kwargs: object) -> object:
        path = argv[-1]
        return type("Completed", (), {"returncode": 0, "stdout": payloads[path]})()

    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", fake_run)

    with pytest.raises(
        check_m05b_attribution_v2.AttributionV2CheckError,
        match="session frames validation failed",
    ):
        check_m05b_attribution_v2.transfer_session_bundle(
            "SERIAL", "com.example.app", bundle, tmp_path / "exports"
        )


def test_transfer_session_bundle_rejects_unknown_telemetry_record(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    session_id = "00000000-0000-4000-8000-000000000006"
    bundle, payloads = _realistic_session_bundle(session_id)
    telemetry = [
        json.loads(line) for line in payloads[f"exports/{session_id}/telemetry.ndjson"].splitlines()
    ]
    telemetry[0]["record_type"] = "unknown_telemetry_record"
    _patch_bundle_payload(
        bundle,
        payloads,
        "telemetry",
        b"".join(
            json.dumps(record, separators=(",", ":"), sort_keys=True).encode() + b"\n"
            for record in telemetry
        ),
    )

    def fake_run(argv: list[str], **_kwargs: object) -> object:
        path = argv[-1]
        return type("Completed", (), {"returncode": 0, "stdout": payloads[path]})()

    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", fake_run)

    with pytest.raises(
        check_m05b_attribution_v2.AttributionV2CheckError,
        match="session telemetry validation failed",
    ):
        check_m05b_attribution_v2.transfer_session_bundle(
            "SERIAL",
            "com.example.app",
            bundle,
            tmp_path / "exports",
            expected_counts={"delivered": 2, "submitted": 2, "terminal": 2},
        )


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("pose_pipeline", "model_asset_sha256"), "6" * 64),
        (("pose_pipeline", "backend_version"), "other-backend"),
        (("pose_pipeline", "delegate"), "gpu"),
        (("device", "os_version"), "15"),
    ],
)
def test_transfer_session_bundle_binds_manifest_candidate_identity(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    path: tuple[str, str],
    value: str,
) -> None:
    session_id = f"00000000-0000-4000-8000-{int(value != '15'):012d}"
    bundle, payloads = _realistic_session_bundle(session_id)
    manifest = json.loads(payloads[f"exports/{session_id}/manifest.json"])
    manifest[path[0]][path[1]] = value
    _patch_bundle_payload(
        bundle,
        payloads,
        "manifest",
        json.dumps(manifest, separators=(",", ":"), sort_keys=True).encode(),
    )

    def fake_run(argv: list[str], **_kwargs: object) -> object:
        path_arg = argv[-1]
        return type("Completed", (), {"returncode": 0, "stdout": payloads[path_arg]})()

    monkeypatch.setattr(check_m05b_attribution_v2.subprocess, "run", fake_run)

    with pytest.raises(
        check_m05b_attribution_v2.AttributionV2CheckError,
        match="session manifest candidate identity mismatch",
    ):
        check_m05b_attribution_v2.transfer_session_bundle(
            "SERIAL",
            "com.example.app",
            bundle,
            tmp_path / "exports",
            expected_identity=_valid_identity(),
            expected_cadence=30,
        )


def test_check_attribution_v2_returns_sanitized_host_evidence_after_exact_reopen(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observation_bytes = json.dumps(_valid_observation(), separators=(",", ":")).encode()
    record = _reference(
        sha256=hashlib.sha256(observation_bytes).hexdigest(),
        length_bytes=len(observation_bytes),
    )

    def fake_transfer(
        _serial: str,
        _package: str,
        _relative: str,
        destination: Path,
        **_expected: object,
    ) -> None:
        destination.write_bytes(observation_bytes)

    monkeypatch.setattr(check_m05b_attribution_v2, "transfer_reference", fake_transfer)
    bundle_transfers: list[tuple[object, ...]] = []

    def fake_bundle_transfer(*args: object, **_kwargs: object) -> dict[str, int]:
        bundle_transfers.append(args)
        return {"manifest": 1, "frames": 1, "telemetry": 1}

    monkeypatch.setattr(
        check_m05b_attribution_v2,
        "transfer_session_bundle",
        fake_bundle_transfer,
    )
    result = check_m05b_attribution_v2.check_attribution_v2(
        _transcript(record),
        subprocess_exit_code=0,
        serial="SERIAL-PRIVATE",
        package="com.example.app",
        prepared_identity=_valid_identity(),
        root=ROOT,
    )
    serialized = json.dumps(result, sort_keys=True)

    assert result["physical_evidence_status"] == "recorded"
    assert "SERIAL-PRIVATE" not in serialized
    assert "/data/user/0/" not in serialized
    assert "landmark" not in serialized
    assert len(bundle_transfers) == 7


def test_extract_reference_rejects_absolute_reference_and_unknown_record_kind() -> None:
    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="path"):
        check_m05b_attribution_v2.extract_reference(
            _transcript(_reference(relative_path="/data/user/0/private/observation.json"))
        )
    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="record_kind"):
        check_m05b_attribution_v2.extract_reference(
            _transcript(_reference(record_kind="m05b_attribution_v3_reference"))
        )


def test_check_attribution_v2_rejects_nonzero_instrumentation_without_rerun() -> None:
    with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="non-zero"):
        check_m05b_attribution_v2.check_attribution_v2(
            _transcript(),
            subprocess_exit_code=1,
            serial="SERIAL-PRIVATE",
            package="com.example.app",
            prepared_identity={},
            root=ROOT,
        )


def test_valid_synthetic_observation_is_recorded_by_host_recomputation() -> None:
    result = _classify(_valid_observation())

    assert result["physical_evidence_status"] == "recorded"
    assert result["blocking_codes"] == []
    assert result["satisfies_s25_attribution_v2_gate"] is True


def test_host_replays_arm_two_cadence_from_capture_timestamps_not_device_booleans() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    run = observation["end_to_end_replicates"][0]["runs"][0]
    callbacks = run["terminal_callbacks"]
    for index, callback in enumerate(callbacks):
        callback["capture_timestamp_ns"] = index * 33_333_333
        callback["cadence_due"] = True
    run["measured_span_ns"] = (
        callbacks[-1]["capture_timestamp_ns"] - callbacks[20]["capture_timestamp_ns"]
    )

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE" in result["blocking_codes"]


def test_host_orders_arm_two_callbacks_by_callback_id_before_terminal_chronology() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    for replicate in observation["end_to_end_replicates"]:
        for run in replicate["runs"]:
            run["terminal_callbacks"] = list(reversed(run["terminal_callbacks"]))

    result = _classify(observation)

    assert result["physical_evidence_status"] == "recorded"
    assert result["blocking_codes"] == []


def test_host_recomputes_arm_two_terminal_class_from_predicates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from copy import deepcopy

    monkeypatch.setattr(check_m05b_attribution_v2, "_terminal_matches", lambda _callback: True)
    observation = deepcopy(_valid_observation())
    run = observation["end_to_end_replicates"][0]["runs"][0]
    callbacks = run["terminal_callbacks"]
    captures = [index * 33_333_333 for index in range(len(callbacks))]
    selected = check_m05b_attribution_v2.selector_timestamps(captures, 10)
    for callback, capture in zip(callbacks, captures):
        callback["capture_timestamp_ns"] = capture
        callback["cadence_due"] = capture in selected
    run["measured_span_ns"] = captures[-1] - captures[20]
    run["submitted_inference_ids"] = [
        callback_id for callback_id in run["submitted_inference_ids"] if callback_id != 21
    ]
    callback = callbacks[21]
    callback["production_classification"] = "cadence_selection"
    callback["cadence_due"] = False
    callback["slot_available"] = True
    callback["terminal_class"] = "backpressure_only"

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE" in result["blocking_codes"]


def test_host_requires_closed_per_arm_environment_predicates() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    observation["end_to_end_replicates"][0]["runs"][0]["environment"]["start"]["charging"] = True

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.ENVIRONMENT_MISMATCH" in result["blocking_codes"]


def test_host_missing_environment_operand_is_total_and_uses_environment_mismatch() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    del observation["end_to_end_replicates"][0]["runs"][0]["environment"]["start"]["wifi_transport"]

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.ENVIRONMENT_MISMATCH" in result["blocking_codes"]


def test_host_rejects_each_committed_environment_mutation() -> None:
    from copy import deepcopy

    mutations = (
        lambda value: value["start"].update(power_source="usb"),
        lambda value: value["start"].update(charging=True),
        lambda value: value["start"].update(wifi_transport=False),
        lambda value: value["start"].update(screen_keep_awake=False),
        lambda value: value["start"].update(device_idle=True),
        lambda value: value.update(arm_started_ns=2, arm_ended_ns=1),
    )
    for mutation in mutations:
        observation = deepcopy(_valid_observation())
        mutation(observation["end_to_end_replicates"][0]["runs"][0]["environment"])
        result = _classify(observation)
        assert "ATTRIBUTION.ENVIRONMENT_MISMATCH" in result["blocking_codes"]

    observation = deepcopy(_valid_observation())
    observation["end_to_end_replicates"][0]["runs"][1]["environment"]["start"][
        "delivered_width"
    ] = 640
    result = _classify(observation)
    assert "ATTRIBUTION.ENVIRONMENT_MISMATCH" in result["blocking_codes"]


def test_host_blocks_undersampled_arm_two_runs_before_admission() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    for replicate in observation["end_to_end_replicates"]:
        for run in replicate["runs"]:
            limit = run["cadence_fps"]
            run["terminal_callbacks"] = run["terminal_callbacks"][:limit]
            run["delivered_callback_ids"] = list(range(limit))
            run["submitted_inference_ids"] = list(range(limit))

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT" in result["blocking_codes"]


def test_host_requires_exactly_one_hundred_measured_arm_two_completions() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    for replicate in observation["end_to_end_replicates"]:
        for run in replicate["runs"]:
            callbacks = run["terminal_callbacks"][:-1]
            run["terminal_callbacks"] = callbacks
            run["delivered_callback_ids"] = list(range(len(callbacks)))
            run["submitted_inference_ids"] = list(range(len(callbacks)))
            run["measured_span_ns"] = callbacks[-1]["capture_timestamp_ns"]

    result = _classify(observation)

    assert "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT" in result["blocking_codes"]


def test_host_replays_arm_two_phase_and_span_assertions_from_raw_terminals() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    run = observation["end_to_end_replicates"][0]["runs"][0]
    run["terminal_callbacks"][20]["phase"] = "warmup"

    result = _classify(observation)

    assert "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE" in result["blocking_codes"]


def test_host_rejects_arm_two_terminal_completion_before_arm_start() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    for replicate in observation["end_to_end_replicates"]:
        for run in replicate["runs"]:
            run["arm_started_ns"] = 2
            callback = run["terminal_callbacks"][20]
            callback["admission_observed_session_ns"] = 0
            callback["completion_timestamp_ns"] = 1

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE" in result["blocking_codes"]


def test_host_recomputes_arm_two_measured_span_from_ordered_capture_timestamps() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    observation["end_to_end_replicates"][0]["runs"][0]["measured_span_ns"] = 2_000_000_000

    result = _classify(observation)

    assert "ATTRIBUTION.END_TO_END_NOT_DISTINCT" in result["blocking_codes"]


def test_host_excludes_warmup_capture_window_from_measured_span_and_rates() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    for replicate in observation["end_to_end_replicates"]:
        for run in replicate["runs"]:
            callbacks = run["terminal_callbacks"]
            measured = callbacks[20:]
        for index, callback in enumerate(callbacks[:20]):
            callback["capture_timestamp_ns"] = 500_000_000 + index * 1_000_000
        captures = [callback["capture_timestamp_ns"] for callback in callbacks]
        selected = check_m05b_attribution_v2.selector_timestamps(captures, run["cadence_fps"])
        for callback in callbacks:
            callback["cadence_due"] = callback["capture_timestamp_ns"] in selected
            run["measured_span_ns"] = (
                measured[-1]["capture_timestamp_ns"] - measured[0]["capture_timestamp_ns"]
            )

    result = _classify(observation)

    assert result["physical_evidence_status"] == "recorded"
    assert result["blocking_codes"] == []

    for replicate in observation["end_to_end_replicates"]:
        for run in replicate["runs"]:
            callbacks = run["terminal_callbacks"]
            run["measured_span_ns"] = (
                callbacks[-1]["capture_timestamp_ns"] - callbacks[0]["capture_timestamp_ns"]
            )

    forged = _classify(observation)

    assert "ATTRIBUTION.END_TO_END_NOT_DISTINCT" in forged["blocking_codes"]


def test_host_blocks_any_arm_two_inference_failure_from_raw_terminals() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    observation["end_to_end_replicates"][0]["runs"][0]["terminal_callbacks"][0][
        "terminal_class"
    ] = "admitted_inference_failed"

    result = _classify(observation)

    assert "ATTRIBUTION.INFERENCE_FAILURE" in result["blocking_codes"]


def test_host_evidence_is_structurally_sanitized_against_free_form_leakage() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    observation["device"]["device_identity"] = "SERIAL-PRIVATE/participant/media/landmark"
    observation["identity"]["backend_version"] = "participant-media-landmark"
    observation["end_to_end_replicates"][0]["runs"][0]["session_reference"] = (
        "exports/00000000-0000-4000-8000-000000000099"
    )

    result = _classify(observation)
    serialized = json.dumps(result, sort_keys=True)

    assert "SERIAL-PRIVATE" not in serialized
    assert "participant-media-landmark" not in serialized
    assert "participant/media/landmark" not in serialized
    assert result["observation"]["device"]["device_identity"].count("/") == 3


def test_host_sanitizes_authorized_shaped_device_identity_and_free_text() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    observation["device"] = {
        **observation["device"],
        "device_identity": "samsung/SM-S938B/SERIAL-PRIVATE/participant-media-landmark",
        "android_release": "16-PRIVATE-PATH-PARTICIPANT",
        "os_build": "BP4A.PARTICIPANT.MEDIA.LANDMARK",
    }

    result = _classify(observation)
    serialized = json.dumps(result, sort_keys=True)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.CANDIDATE_IDENTITY" in result["blocking_codes"]
    assert "SERIAL-PRIVATE" not in serialized
    assert "participant-media-landmark" not in serialized
    assert "PRIVATE-PATH-PARTICIPANT" not in serialized
    assert "PARTICIPANT.MEDIA.LANDMARK" not in serialized


def test_host_requires_exact_protocol_device_pin_and_sanitizes_mismatch() -> None:
    from copy import deepcopy

    protocol = json.loads(
        (ROOT / "docs/protocols/m05b-attribution-v2.protocol.json").read_text(encoding="utf-8")
    )
    observation = deepcopy(_valid_observation())
    pin = protocol["authorized_device"]
    observation["device"]["os_build"] = pin["os_build"][:-1] + "4"

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.CANDIDATE_IDENTITY" in result["blocking_codes"]
    assert result["device"] == check_m05b_attribution_v2._invalid_device_sentinel()  # type: ignore[attr-defined]
    assert result["device"] != pin


def test_host_blocks_shape_authorized_other_product_and_uses_sentinel() -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    observation["device"]["device_identity"] = "samsung/SM-S938B/pa3q/otherproduct"

    result = _classify(observation)

    assert result["physical_evidence_status"] == "blocked"
    assert "ATTRIBUTION.CANDIDATE_IDENTITY" in result["blocking_codes"]
    assert result["device"] == check_m05b_attribution_v2._invalid_device_sentinel()  # type: ignore[attr-defined]


def test_host_preserves_exact_protocol_device_pin_and_is_a_classification_fixed_point() -> None:
    result = _classify(_valid_observation())
    pin = json.loads(
        (ROOT / "docs/protocols/m05b-attribution-v2.protocol.json").read_text(encoding="utf-8")
    )["authorized_device"]

    assert result["physical_evidence_status"] == "recorded"
    assert result["device"] == pin
    assert result["observation"]["device"] == pin
    assert (
        check_m05b_attribution_v2.AttributionV2HostCheck(ROOT).classify(
            result["observation"], _valid_identity()
        )
        == result
    )


def test_android_attribution_gate_reads_display_build_not_entropy_losing_id() -> None:
    gate = (
        ROOT
        / "apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2DeviceGateTest.kt"
    ).read_text(encoding="utf-8")

    assert "osBuild = Build.DISPLAY" in gate
    assert "osBuild = Build.ID" not in gate


def test_host_rejects_absolute_or_non_export_session_references() -> None:
    from copy import deepcopy

    for reference in ("/data/user/0/private/observation.json", "private/media.json"):
        observation = deepcopy(_valid_observation())
        observation["end_to_end_replicates"][0]["runs"][0]["session_reference"] = reference
        with pytest.raises(check_m05b_attribution_v2.AttributionV2CheckError, match="schema"):
            _classify(observation)


def test_host_adapter_preserves_fractional_rate_ordering_without_flooring() -> None:
    from copy import deepcopy

    observation = _valid_observation()
    replicates = deepcopy(observation["end_to_end_replicates"])
    spans = {10: 1_100_000_000, 15: 1_600_000_000, 30: 2_100_000_000}
    counts = {10: 2, 15: 3, 30: 4}
    for replicate in replicates:
        for run in replicate["runs"]:
            cadence = run["cadence_fps"]
            callback_count = 20 + counts[cadence]
            run["terminal_callbacks"] = run["terminal_callbacks"][:callback_count]
            run["terminal_callbacks"] = [
                dict(
                    callback,
                    capture_timestamp_ns=(
                        index
                        if index < 20
                        else 1_000_000_000 + (index - 20) * spans[cadence] // (counts[cadence] - 1)
                    ),
                )
                for index, callback in enumerate(run["terminal_callbacks"])
            ]
            run["measured_span_ns"] = spans[cadence]

    adapter = check_m05b_attribution_v2._validation_adapter(  # type: ignore[attr-defined]
        observation,
        observation["source_arm"],
        observation["backend_arm"],
        observation["selector_arm"],
        replicates,
        [10_000_000] * 100,
        True,
    )
    issues = check_m05b_attribution_v2.validate_attribution_v2(adapter, "fractional.json")

    assert "ATTRIBUTION.END_TO_END_NOT_DISTINCT" not in {issue.code for issue in issues}


def test_host_rehashes_the_reopened_canonical_protocol(tmp_path: Path) -> None:
    protocol = tmp_path / "docs/protocols/m05b-attribution-v2.protocol.json"
    protocol.parent.mkdir(parents=True)
    protocol.write_bytes(
        (ROOT / "docs/protocols/m05b-attribution-v2.protocol.json").read_bytes() + b" "
    )

    result = check_m05b_attribution_v2.AttributionV2HostCheck(tmp_path).classify(
        _valid_observation(), _valid_identity()
    )

    assert "ATTRIBUTION.PROTOCOL_DIGEST" in result["blocking_codes"]


def test_callback_recomputation_retains_admission_state_divergence() -> None:
    callback = _valid_observation()["backend_arm"]["terminal_callbacks"][0]  # type: ignore[index]
    callback["production_classification"] = "backpressure"  # type: ignore[index]

    assert "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE" in check_m05b_attribution_v2._callback_blockers(  # type: ignore[attr-defined]
        [0],
        [0],
        [],
        [callback],
        False,  # type: ignore[list-item]
    )


def test_host_replicate_recomputation_requires_both_preregistered_orders() -> None:
    observation = _valid_observation()
    source = observation["source_arm"]
    backend = observation["backend_arm"]
    selector = observation["selector_arm"]
    source_codes = check_m05b_attribution_v2._derive_source(source)  # type: ignore[arg-type,attr-defined]
    backend_codes, durations = check_m05b_attribution_v2._derive_backend(backend)  # type: ignore[arg-type,attr-defined]
    selector_codes = check_m05b_attribution_v2._derive_selector(  # type: ignore[attr-defined]
        selector,
        source["timestamps_ns"],  # type: ignore[index]
    )

    assert (
        "ATTRIBUTION.REPLICATE_DISAGREEMENT"
        in check_m05b_attribution_v2._derive_replicates(  # type: ignore[attr-defined]
            observation["end_to_end_replicates"][:1],  # type: ignore[index]
            source,  # type: ignore[arg-type]
            backend,  # type: ignore[arg-type]
            source_codes,
            backend_codes,
            selector_codes,
            durations,
        )
    )


def test_selector_only_evidence_can_never_be_admitted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observation = _valid_observation()
    observation["backend_arm"] = None
    observation["end_to_end_replicates"] = []
    monkeypatch.setattr(
        check_m05b_attribution_v2,
        "validate_attribution_v2",
        lambda _document, _path: [],
    )

    result = _classify(observation)

    assert "ATTRIBUTION.SELECTOR_ONLY" in result["blocking_codes"]


def test_host_recomputes_environment_mismatch_without_pure_validator(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observation = _valid_observation()
    observation["end_to_end_replicates"][0]["runs"][0]["final_thermal_status"] = 1  # type: ignore[index]
    monkeypatch.setattr(
        check_m05b_attribution_v2,
        "validate_attribution_v2",
        lambda _document, _path: [],
    )

    result = _classify(observation)

    assert "ATTRIBUTION.ENVIRONMENT_MISMATCH" in result["blocking_codes"]


def test_host_recomputes_service_duration_from_admission_and_completion_not_capture_time() -> None:
    observation = _valid_observation()
    for callback in observation["backend_arm"]["terminal_callbacks"]:  # type: ignore[index]
        callback["capture_timestamp_ns"] = 0  # type: ignore[index]

    result = _classify(observation)

    assert result["physical_evidence_status"] == "recorded"


@pytest.mark.parametrize(
    ("mutator", "code"),
    [
        (
            lambda value: value["backend_arm"]["terminal_callbacks"][25].update(phase=None),
            "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE",
        ),
        (
            lambda value: value["backend_arm"]["terminal_callbacks"][25].update(
                completion_timestamp_ns=None
            ),
            "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE",
        ),
        (
            lambda value: value["backend_arm"]["terminal_callbacks"][25].update(
                completion_timestamp_ns=2_000_000_000
            ),
            "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE",
        ),
        (
            lambda value: value["backend_arm"].update(raw_evidence_overflow=True),
            "ATTRIBUTION.CALLBACK_CONSERVATION",
        ),
        (
            lambda value: value["backend_arm"]["warmup_predicates"].update(elapsed_ns=1),
            "BLOCKING_CODES.MISMATCH",
        ),
        (lambda value: value["backend_arm"].update(warmup_count=19), "BLOCKING_CODES.MISMATCH"),
    ],
)
def test_host_fail_closes_raw_callback_and_warmup_assertions(mutator: object, code: str) -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    mutator(observation)  # type: ignore[operator]

    assert code in _classify(observation)["blocking_codes"]


def test_host_reports_source_limitation_from_recorded_timestamps() -> None:
    observation = _valid_observation()
    observation["source_arm"]["timestamps_ns"] = [  # type: ignore[index]
        index * 1_000_000_000 for index in range(300)
    ]

    assert "ATTRIBUTION.SOURCE_LIMITED" in _classify(observation)["blocking_codes"]


def test_host_source_limitation_matches_any_target_rule_without_pure_validator(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observation = _valid_observation()
    observation["source_arm"]["timestamps_ns"] = [  # type: ignore[index]
        index * 100_000_000 for index in range(300)
    ]
    monkeypatch.setattr(
        check_m05b_attribution_v2,
        "validate_attribution_v2",
        lambda _document, _path: [],
    )

    assert "ATTRIBUTION.SOURCE_LIMITED" in _classify(observation)["blocking_codes"]


def test_host_reports_source_sample_insufficiency_from_exact_count() -> None:
    observation = _valid_observation()
    observation["source_arm"]["timestamps_ns"] = observation["source_arm"]["timestamps_ns"][:-1]  # type: ignore[index]

    assert "ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT" in _classify(observation)["blocking_codes"]


def test_host_reports_backend_limitation_from_raw_duration_operands() -> None:
    observation = _valid_observation()
    measured = observation["backend_arm"]["terminal_callbacks"][20]  # type: ignore[index]
    measured["completion_timestamp_ns"] = measured["admission_observed_session_ns"] + 100_000_001  # type: ignore[index]

    assert "ATTRIBUTION.BACKEND_LIMITED" in _classify(observation)["blocking_codes"]


@pytest.mark.parametrize(
    ("mutator", "code"),
    [
        (lambda value: value.update(protocol_sha256="0" * 64), "ATTRIBUTION.PROTOCOL_DIGEST"),
        (lambda value: value["identity"].update(delegate="gpu"), "ATTRIBUTION.CANDIDATE_IDENTITY"),
        (lambda value: value["device"].update(model="SM-S931B"), "ATTRIBUTION.CANDIDATE_IDENTITY"),
        (lambda value: value["source_arm"].update(measured_count=299), "BLOCKING_CODES.MISMATCH"),
        (
            lambda value: value["source_arm"].update(observed_ae_target_fps=None),
            "ATTRIBUTION.SOURCE_CONFIGURATION",
        ),
        (
            lambda value: value["source_arm"]["timestamps_ns"].__setitem__(1, 0),
            "ATTRIBUTION.SOURCE_TIMESTAMP_INVALID",
        ),
        (lambda value: value["backend_arm"].update(measured_count=99), "BLOCKING_CODES.MISMATCH"),
        (
            lambda value: value["backend_arm"]["service_durations_ns"].__setitem__(0, 100_000_001),
            "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT",
        ),
        (
            lambda value: value["selector_arm"]["runs"][1]["host_selected_timestamps_ns"].pop(),
            "ATTRIBUTION.SELECTOR_DIVERGENCE",
        ),
        (
            lambda value: value["backend_arm"]["terminal_callbacks"][0].update(
                production_classification="backpressure"
            ),
            "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE",
        ),
        (
            lambda value: value["backend_arm"]["delivered_callback_ids"].append(999),
            "ATTRIBUTION.TERMINAL_CLOSURE",
        ),
        (
            lambda value: value["backend_arm"].update(inference_failures=1),
            "ATTRIBUTION.INFERENCE_FAILURE",
        ),
        (
            lambda value: value["source_arm"].update(initial_cooldown_recovered=False),
            "BLOCKING_CODES.MISMATCH",
        ),
        (
            lambda value: value["end_to_end_replicates"][0]["runs"][0].update(
                final_thermal_status=1
            ),
            "ATTRIBUTION.THERMAL_STATE_MISMATCH",
        ),
        (lambda value: value["end_to_end_replicates"].pop(), "ATTRIBUTION.REPLICATE_DISAGREEMENT"),
        (
            lambda value: value["end_to_end_replicates"][0]["runs"][2][
                "terminal_callbacks"
            ].__delitem__(slice(15, 30)),
            "ATTRIBUTION.END_TO_END_NOT_DISTINCT",
        ),
    ],
)
def test_each_raw_or_derived_predicate_has_a_named_host_blocker(mutator: object, code: str) -> None:
    from copy import deepcopy

    observation = deepcopy(_valid_observation())
    mutator(observation)  # type: ignore[operator]
    result = _classify(observation)

    assert code in result["blocking_codes"]
    if code == "ATTRIBUTION.THERMAL_STATE_MISMATCH":
        assert "ATTRIBUTION.ENVIRONMENT_MISMATCH" in result["blocking_codes"]
    assert result["physical_evidence_status"] == "blocked"


def _classify(observation: dict[str, object]) -> dict[str, object]:
    return check_m05b_attribution_v2.AttributionV2HostCheck(ROOT).classify(
        observation, _valid_identity()
    )


def _valid_identity() -> dict[str, object]:
    return {
        "app_git_sha": "1" * 40,
        "probe_git_sha": "2" * 40,
        "app_apk_sha256": "3" * 64,
        "test_apk_sha256": "4" * 64,
        "model_sha256": "5" * 64,
        "runtime_version": "16",
        "backend_version": "tasks-vision",
        "delegate": "cpu",
    }


def _valid_observation() -> dict[str, object]:
    timestamps = [index * 20_000_000 for index in range(300)]
    source = {
        "status": "recorded",
        "blocking_codes": [],
        "warmup_count": 30,
        "measured_count": 300,
        "timestamps_ns": timestamps,
        "observed_ae_target_fps": {"lower": 30, "upper": 30},
        "thermal_status": 0,
        "initial_cooldown_thermal_statuses": [0],
        "initial_cooldown_recovered": True,
        "environment": _valid_environment(),
    }
    backend_callbacks = []
    for callback_id in range(120):
        phase = "warmup" if callback_id < 20 else "measured"
        completion = (
            100_000_000 + callback_id * 100_000_000
            if callback_id < 20
            else 3_000_000_000 + (callback_id - 20) * 10_000_000
        )
        admission = completion - 10_000_000
        backend_callbacks.append(
            {
                "callback_id": callback_id,
                "capture_timestamp_ns": callback_id,
                "production_classification": "submit",
                "cadence_due": True,
                "slot_available": True,
                "terminal_class": "admitted_completed",
                "phase": phase,
                "admission_observed_session_ns": admission,
                "completion_timestamp_ns": completion,
            }
        )
    backend = {
        "status": "recorded",
        "blocking_codes": [],
        "warmup_count": 20,
        "warmup_elapsed_ns": 2_000_000_000,
        "measured_count": 100,
        "service_durations_ns": [10_000_000] * 100,
        "inference_failures": 0,
        "thermal_status": 0,
        "arm_started_ns": 0,
        "warmup_predicates": {
            "completion_count": 20,
            "elapsed_ns": 2_000_000_000,
            "completion_count_satisfied": True,
            "elapsed_satisfied": True,
        },
        "delivered_callback_ids": list(range(120)),
        "terminal_callbacks": backend_callbacks,
        "submitted_inference_ids": list(range(120)),
        "pending_inference_ids": [],
        "raw_evidence_overflow": False,
        "environment": _valid_environment(),
        "session_bundle": _valid_session_bundle("00000000-0000-4000-8000-000000000000"),
    }
    selector = {
        "status": "recorded",
        "authoritative": False,
        "blocking_codes": [],
        "runs": [
            {
                "cadence_fps": cadence,
                "production_selected_timestamps_ns": selector_timestamps(timestamps, cadence),
                "host_selected_timestamps_ns": selector_timestamps(timestamps, cadence),
            }
            for cadence in [10, 15, 30]
        ],
    }
    replicates = [
        _valid_replicate(order, prefix) for prefix, order in enumerate([[10, 15, 30], [30, 15, 10]])
    ]
    return {
        "evidence_schema_version": "2.1.0",
        "record_kind": "m05b_attribution_v2_device_observation",
        "protocol_sha256": hashlib.sha256(
            (ROOT / "docs/protocols/m05b-attribution-v2.protocol.json").read_bytes()
        ).hexdigest(),
        "sanitized": True,
        "non_participant_observation": True,
        "device": {
            "device_class": "physical_device",
            "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "api_level": 36,
            "android_release": "16",
            "os_build": "BP4A.251205.006.S938BXXSBCZG3",
        },
        "identity": _valid_identity(),
        "source_arm": source,
        "backend_arm": backend,
        "selector_arm": selector,
        "end_to_end_replicates": replicates,
        "blocking_codes": [],
    }


def _valid_replicate(order: list[int], prefix: int) -> dict[str, object]:
    runs = []
    for index, cadence in enumerate(order):
        callbacks = []
        all_span = {10: 20_000_000_000, 15: 10_000_000_000, 30: 5_000_000_000}[cadence]
        captures = [callback_id * all_span // 119 for callback_id in range(120)]
        selected = selector_timestamps(captures, cadence)
        for callback_id in range(120):
            warmup = callback_id < 20
            completion = (
                1_000_000_000 + callback_id * 100_000_000
                if warmup
                else 4_000_000_000 + (callback_id - 20) * 10_000_000
            )
            callbacks.append(
                {
                    "callback_id": callback_id,
                    "capture_timestamp_ns": captures[callback_id],
                    "production_classification": "submit",
                    "cadence_due": captures[callback_id] in selected,
                    "slot_available": True,
                    "terminal_class": "admitted_completed",
                    "phase": "warmup" if warmup else "measured",
                    "admission_observed_session_ns": completion - 100_000,
                    "completion_timestamp_ns": completion,
                }
            )
        runs.append(
            {
                "cadence_fps": cadence,
                "session_reference": (
                    f"exports/00000000-0000-4000-8000-{prefix * 3 + index + 1:012d}"
                ),
                "arm_started_ns": 0,
                "initial_thermal_status": 0,
                "final_thermal_status": 0,
                "measured_span_ns": all_span - (20 * all_span // 119),
                "delivered_callback_ids": list(range(120)),
                "terminal_callbacks": callbacks,
                "submitted_inference_ids": list(range(120)),
                "pending_inference_ids": [],
                "environment": _valid_environment(),
                "session_bundle": _valid_session_bundle(
                    f"00000000-0000-4000-8000-{prefix * 3 + index + 1:012d}"
                ),
            }
        )
    return {
        "order": order,
        "status": "recorded",
        "blocking_codes": [],
        "cooldown_thermal_statuses": [0],
        "runs": runs,
    }


def _valid_environment() -> dict[str, object]:
    snapshot = {
        "thermal_status": 0,
        "battery_temperature_c": 25.0,
        "battery_level_percent": 80.0,
        "power_source": "battery",
        "charging": False,
        "wifi_transport": True,
        "screen_keep_awake": True,
        "device_idle": False,
        "negotiated_camera_fps_range": {"lower": 30, "upper": 30},
        "delivered_width": 1280,
        "delivered_height": 720,
        "delivered_format": "YUV_420_888",
    }
    return {
        "arm_started_ns": 0,
        "arm_ended_ns": 60_000_000_000,
        "start": snapshot,
        "end": dict(snapshot),
    }


def _valid_session_bundle(session_id: str) -> dict[str, object]:
    return {
        "session_reference": f"exports/{session_id}",
        "manifest": {
            "relative_path": f"exports/{session_id}/manifest.json",
            "length_bytes": 1,
            "sha256": "a" * 64,
        },
        "frames": {
            "relative_path": f"exports/{session_id}/frames.ndjson",
            "length_bytes": 1,
            "sha256": "b" * 64,
        },
        "telemetry": {
            "relative_path": f"exports/{session_id}/telemetry.ndjson",
            "length_bytes": 1,
            "sha256": "c" * 64,
        },
    }
