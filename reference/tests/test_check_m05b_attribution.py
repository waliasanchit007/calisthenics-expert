"""Executable contract for the exact-byte short attribution checker."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

import check_m05b_attribution
from check_m05b_attribution import (
    AttributionCheckError,
    check_attribution,
    main,
    transfer_exports_from_device,
)

ROOT = Path(__file__).parents[2]
S25 = {
    "device_class": "physical_device",
    "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
    "device_fingerprint": "samsung/pa3qxins/pa3q:16/build:user/release-keys",
    "api_level": 36,
    "android_release": "16",
    "abi": "arm64-v8a",
    "manufacturer": "samsung",
    "model": "SM-S938B",
    "marketing_name": "",
}
IDENTITY = {
    "app_git_sha": "a" * 40,
    "probe_git_sha": "b" * 40,
    "apk_sha256": "c" * 64,
    "model_sha256": "d" * 64,
    "runtime_version": "16",
    "backend_version": "0.10.14",
    "delegate": "cpu",
}


def test_checker_reconstructs_three_runs_from_durable_bytes(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path)

    result = check_attribution(
        transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
    )

    assert result["physical_evidence_status"] == "recorded"
    assert result["satisfies_s25_attribution_gate"] is True
    assert [run["configured_inference_fps"] for run in result["runs"]] == [10, 15, 30]
    assert all(
        set(run["digests"]) == {"manifest_sha256", "frames_sha256", "telemetry_sha256"}
        for run in result["runs"]
    )
    assert "CAMERA_CALLBACK" not in json.dumps(result)
    assert "landmark" not in json.dumps(result).lower()


def test_checker_accepts_fixed_samples_interleaved_with_pipeline_events(
    tmp_path: Path,
) -> None:
    record, paths = make_fixture_tree(tmp_path)
    for index, path_set in enumerate(paths):
        pipeline = [
            json.loads(line)
            for line in path_set["telemetry"].read_text(encoding="utf-8").splitlines()
        ]
        records = [fixed_sample(0, 0, terminal=False), *pipeline]
        records.append(fixed_sample(len(records), len(records), terminal=True))
        for sequence, item in enumerate(records):
            item["sequence"] = sequence
            item["t_session_ns"] = sequence
        path_set["telemetry"].write_bytes(
            b"".join(json.dumps(item, sort_keys=True).encode() + b"\n" for item in records)
        )
        record["sessions"][index]["telemetry_sha256"] = sha(path_set["telemetry"])

    result = check_attribution(
        transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
    )

    assert result["satisfies_s25_attribution_gate"] is True
    assert [run["admitted_callbacks"] for run in result["runs"]] == [1, 2, 5]


def test_checker_accepts_unique_terminal_events_in_async_completion_order(
    tmp_path: Path,
) -> None:
    record, paths = make_fixture_tree(tmp_path)
    telemetry = [
        json.loads(line) for line in paths[0]["telemetry"].read_text(encoding="utf-8").splitlines()
    ]
    completed = telemetry.pop(2)
    telemetry.insert(4, completed)
    for sequence, item in enumerate(telemetry):
        item["sequence"] = sequence
        item["t_session_ns"] = sequence
    paths[0]["telemetry"].write_bytes(
        b"".join(json.dumps(item, sort_keys=True).encode() + b"\n" for item in telemetry)
    )
    record["sessions"][0]["telemetry_sha256"] = sha(paths[0]["telemetry"])

    result = check_attribution(
        transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
    )

    assert result["satisfies_s25_attribution_gate"] is True
    assert result["runs"][0]["delivered_callbacks"] == 10
    assert result["runs"][0]["admitted_callbacks"] == 1


def test_checker_rejects_unknown_interleaved_telemetry_record(tmp_path: Path) -> None:
    record, paths = make_fixture_tree(tmp_path)
    telemetry = [
        json.loads(line) for line in paths[0]["telemetry"].read_text(encoding="utf-8").splitlines()
    ]
    telemetry.insert(0, fixed_sample(0, 0, terminal=False))
    telemetry[0]["record_type"] = "unknown_telemetry_record"
    for sequence, item in enumerate(telemetry):
        item["sequence"] = sequence
        item["t_session_ns"] = sequence
    paths[0]["telemetry"].write_bytes(
        b"".join(json.dumps(item, sort_keys=True).encode() + b"\n" for item in telemetry)
    )
    record["sessions"][0]["telemetry_sha256"] = sha(paths[0]["telemetry"])

    with pytest.raises(AttributionCheckError, match="unsupported record_type"):
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
        )


def test_checker_uses_transcript_status_not_caller_declared_exit_field(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path)
    record["instrumentation_exit_code"] = -1

    result = check_attribution(
        transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
    )

    assert result["satisfies_s25_attribution_gate"] is True


def test_checker_requires_external_subprocess_status(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path)

    with pytest.raises(AttributionCheckError):
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=1, prepared_identity=IDENTITY
        )


def test_checker_requires_candidate_identity_in_every_manifest(tmp_path: Path) -> None:
    record, paths = make_fixture_tree(tmp_path)
    manifest = json.loads(paths[0]["manifest"].read_text(encoding="utf-8"))
    manifest.pop("build_identity")
    paths[0]["manifest"].write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
    record["sessions"][0]["manifest_sha256"] = sha(paths[0]["manifest"])

    with pytest.raises(AttributionCheckError, match="candidate identity"):
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
        )


def test_checker_binds_runtime_version_to_device_os_not_pose_pipeline(
    tmp_path: Path,
) -> None:
    record, paths = make_fixture_tree(tmp_path)
    for index, path_set in enumerate(paths):
        manifest = json.loads(path_set["manifest"].read_text(encoding="utf-8"))
        manifest["pose_pipeline"].pop("runtime_version")
        manifest["device"]["os_version"] = IDENTITY["runtime_version"]
        path_set["manifest"].write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
        record["sessions"][index]["manifest_sha256"] = sha(path_set["manifest"])

    result = check_attribution(
        transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
    )

    assert result["satisfies_s25_attribution_gate"] is True


def test_checker_rejects_device_os_that_differs_from_prepared_runtime(
    tmp_path: Path,
) -> None:
    record, paths = make_fixture_tree(tmp_path)
    manifest = json.loads(paths[0]["manifest"].read_text(encoding="utf-8"))
    manifest["pose_pipeline"].pop("runtime_version")
    manifest["device"]["os_version"] = "15"
    paths[0]["manifest"].write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
    record["sessions"][0]["manifest_sha256"] = sha(paths[0]["manifest"])

    with pytest.raises(AttributionCheckError, match="runtime identity"):
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
        )


def test_checker_accepts_the_live_composition_study_context_identity_binding(
    tmp_path: Path,
) -> None:
    record, paths = make_fixture_tree(tmp_path)
    for index, path_set in enumerate(paths):
        manifest = json.loads(path_set["manifest"].read_text(encoding="utf-8"))
        manifest.pop("build_identity")
        manifest["study_context"]["m05b_attribution_identity"] = dict(IDENTITY)
        path_set["manifest"].write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
        record["sessions"][index]["manifest_sha256"] = sha(path_set["manifest"])

    result = check_attribution(
        transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
    )
    assert result["satisfies_s25_attribution_gate"] is True


def test_checker_accepts_any_reserved_synthetic_session_id(tmp_path: Path) -> None:
    record, paths = make_fixture_tree(tmp_path)
    old_root = paths[0]["manifest"].parent
    new_id = "00000000-0000-4000-8000-deadbeefcafe"
    new_root = old_root.parent / new_id
    old_root.rename(new_root)
    paths[0] = {
        "manifest": new_root / "manifest.json",
        "frames": new_root / "frames.ndjson",
        "telemetry": new_root / "telemetry.ndjson",
    }
    manifest = json.loads(paths[0]["manifest"].read_text(encoding="utf-8"))
    manifest["session_id"] = new_id
    paths[0]["manifest"].write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
    frames = [
        json.loads(line) for line in paths[0]["frames"].read_text(encoding="utf-8").splitlines()
    ]
    for frame in frames:
        frame["session_id"] = new_id
    paths[0]["frames"].write_bytes(
        b"".join(json.dumps(frame, sort_keys=True).encode() + b"\n" for frame in frames)
    )
    record["sessions"][0]["session_id"] = new_id
    record["sessions"][0]["export_relative_path"] = f"exports/{new_id}"
    record["sessions"][0]["manifest_sha256"] = sha(paths[0]["manifest"])
    record["sessions"][0]["frames_sha256"] = sha(paths[0]["frames"])

    result = check_attribution(
        transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
    )

    assert result["satisfies_s25_attribution_gate"] is True


def test_checker_rejects_manifest_identity_mutation_even_with_updated_file_digest(
    tmp_path: Path,
) -> None:
    record, paths = make_fixture_tree(tmp_path)
    manifest = json.loads(paths[1]["manifest"].read_text(encoding="utf-8"))
    manifest["build_identity"]["apk_sha256"] = "e" * 64
    paths[1]["manifest"].write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
    record["sessions"][1]["manifest_sha256"] = sha(paths[1]["manifest"])

    with pytest.raises(AttributionCheckError, match="prepared-candidate identity"):
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
        )


def test_checker_rejects_unknown_telemetry_enum_after_rehash(tmp_path: Path) -> None:
    record, paths = make_fixture_tree(tmp_path)
    telemetry = [
        json.loads(line) for line in paths[0]["telemetry"].read_text(encoding="utf-8").splitlines()
    ]
    telemetry[0]["stage"] = "NOT_A_STAGE"
    paths[0]["telemetry"].write_bytes(
        b"".join(json.dumps(item, sort_keys=True).encode() + b"\n" for item in telemetry)
    )
    record["sessions"][0]["telemetry_sha256"] = sha(paths[0]["telemetry"])

    with pytest.raises(AttributionCheckError, match="invalid stage"):
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
        )


def test_cli_separates_usage_malformed_and_blocking_statuses(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path)
    transcript_path = tmp_path / "instrumentation.txt"
    identity_path = tmp_path / "identity.json"
    transcript_path.write_text(transcript(record), encoding="utf-8")
    identity_path.write_text(json.dumps(IDENTITY), encoding="utf-8")

    assert main([]) == 2
    assert (
        main(
            [
                "--transcript",
                str(transcript_path),
                "--export-root",
                str(tmp_path),
                "--subprocess-exit-code",
                "0",
                "--prepared-identity",
                str(identity_path),
            ]
        )
        == 0
    )
    assert (
        main(
            [
                "--transcript",
                str(transcript_path),
                "--export-root",
                str(tmp_path),
                "--subprocess-exit-code",
                "1",
                "--prepared-identity",
                str(identity_path),
            ]
        )
        == 1
    )


@pytest.mark.parametrize(
    "mutation",
    ["zero_tests", "multiple_records", "bad_json", "wrong_suite", "nonzero_status"],
)
def test_checker_rejects_invalid_instrumentation_transcript(tmp_path: Path, mutation: str) -> None:
    record, _ = make_fixture_tree(tmp_path)
    text = transcript(record)
    if mutation == "zero_tests":
        text = text.replace("OK (1 test)", "OK (0 tests)")
    elif mutation == "multiple_records":
        text = text.replace(
            "INSTRUMENTATION_STATUS_CODE: 4",
            "INSTRUMENTATION_STATUS: M05BAttributionEvidence="
            + json.dumps(record)
            + "\nINSTRUMENTATION_STATUS_CODE: 4",
        )
    elif mutation == "bad_json":
        text = text.replace(json.dumps(record, sort_keys=True), "{not-json}")
    elif mutation == "wrong_suite":
        text = text.replace("M05BAttributionDeviceGateTest", "OtherTest")
    elif mutation == "nonzero_status":
        text = text.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0")

    with pytest.raises(AttributionCheckError):
        check_attribution(text, tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY)


def test_checker_rejects_a_missing_middle_cadence_run(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path)
    record["sessions"].pop(1)

    with pytest.raises(AttributionCheckError, match="exactly three sessions"):
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
        )


@pytest.mark.parametrize(
    "mutation",
    ["truncated", "appended", "substituted", "manifest_after_hash", "transport_error"],
)
def test_checker_rejects_or_defers_exact_byte_transfer_mutations(
    tmp_path: Path, mutation: str
) -> None:
    record, paths = make_fixture_tree(tmp_path)
    if mutation == "truncated":
        paths[0]["telemetry"].write_bytes(paths[0]["telemetry"].read_bytes()[:-1])
    elif mutation == "appended":
        paths[1]["telemetry"].write_bytes(paths[1]["telemetry"].read_bytes() + b"x")
    elif mutation == "substituted":
        paths[2]["telemetry"].write_bytes(paths[0]["telemetry"].read_bytes())
    elif mutation == "manifest_after_hash":
        record["sessions"][0]["manifest_sha256"] = "d" * 64
    elif mutation == "transport_error":
        paths[1]["telemetry"].unlink()

    with pytest.raises(AttributionCheckError) as error:
        check_attribution(
            transcript(record), tmp_path, subprocess_exit_code=0, prepared_identity=IDENTITY
        )
    if mutation == "transport_error":
        assert "awaiting_recovery" in str(error.value)


def test_device_transfer_uses_exact_adb_identity_and_fsyncs_verified_bytes(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path / "source")
    calls: list[list[str]] = []

    def fake_adb(command: list[str], **_: object) -> object:
        calls.append(command)
        remote = Path(command[-1])
        source = tmp_path / "source" / remote.relative_to("files/diagnostic-harness")
        return type(
            "Completed", (), {"returncode": 0, "stdout": source.read_bytes(), "stderr": b""}
        )()

    destination = tmp_path / "pulled"
    pulled = transfer_exports_from_device(
        serial="192.0.2.10:5555",
        package="com.calisthenics.diagnostic.android",
        references=record["sessions"],
        destination=destination,
        run_adb=fake_adb,
    )

    assert pulled == destination
    assert (
        destination / "exports" / record["sessions"][0]["session_id"] / "manifest.json"
    ).is_file()
    assert all(
        command[:6]
        == [
            "adb",
            "-s",
            "192.0.2.10:5555",
            "exec-out",
            "run-as",
            "com.calisthenics.diagnostic.android",
        ]
        for command in calls
    )
    assert all("shell" not in command for command in calls)


def test_device_transfer_classifies_adb_failure_as_awaiting_recovery(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path / "source")

    def failed_adb(_: list[str], **__: object) -> object:
        return type(
            "Completed", (), {"returncode": 1, "stdout": b"", "stderr": b"transport lost"}
        )()

    with pytest.raises(AttributionCheckError, match="awaiting_recovery"):
        transfer_exports_from_device(
            serial="192.0.2.10:5555",
            package="com.calisthenics.diagnostic.android",
            references=record["sessions"],
            destination=tmp_path / "pulled",
            run_adb=failed_adb,
        )


def test_recorded_ledger_reopens_evidence_and_recomputes_verdict(tmp_path: Path) -> None:
    record, _ = make_fixture_tree(tmp_path / "exports")
    evidence = check_attribution(
        transcript(record),
        tmp_path / "exports",
        subprocess_exit_code=0,
        prepared_identity=IDENTITY,
    )
    evidence_path = tmp_path / "evidence.json"
    evidence_path.write_text(json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8")
    ledger = {
        "status": "recorded",
        "evidence_file": "evidence.json",
        "evidence_sha256": sha(evidence_path),
        "device_identity": S25["device_identity"],
        "api_level": S25["api_level"],
        "android_release": S25["android_release"],
        **IDENTITY,
    }
    (tmp_path / "ledger.json").write_text(json.dumps(ledger), encoding="utf-8")
    assert (
        check_m05b_attribution.verify_recorded_ledger(tmp_path, "ledger.json")["record_kind"]
        == "s25_short_cadence_attribution_observation"
    )

    tampered = dict(evidence)
    tampered["satisfies_s25_attribution_gate"] = False
    evidence_path.write_text(json.dumps(tampered, sort_keys=True) + "\n", encoding="utf-8")
    ledger["evidence_sha256"] = sha(evidence_path)
    (tmp_path / "ledger.json").write_text(json.dumps(ledger), encoding="utf-8")
    with pytest.raises(AttributionCheckError, match="verdict"):
        check_m05b_attribution.verify_recorded_ledger(tmp_path, "ledger.json")


def test_blocked_ledger_reopens_evidence_and_recomputes_blocking_verdict(
    tmp_path: Path,
) -> None:
    record, _ = make_fixture_tree(tmp_path / "exports")
    evidence = check_attribution(
        transcript(record),
        tmp_path / "exports",
        subprocess_exit_code=0,
        prepared_identity=IDENTITY,
    )
    evidence["runs"][2]["admitted_callbacks"] = 2
    evidence["blocking_codes"] = ["TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT"]
    evidence["physical_evidence_status"] = "blocked"
    evidence["satisfies_s25_attribution_gate"] = False
    evidence_path = tmp_path / "blocked-evidence.json"
    evidence_path.write_text(json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8")
    ledger = {
        "status": "blocked",
        "evidence_file": "blocked-evidence.json",
        "evidence_sha256": sha(evidence_path),
        "device_identity": S25["device_identity"],
        "api_level": S25["api_level"],
        "android_release": S25["android_release"],
        **IDENTITY,
    }
    (tmp_path / "ledger.json").write_text(json.dumps(ledger), encoding="utf-8")

    verified = check_m05b_attribution.verify_recorded_ledger(tmp_path, "ledger.json")

    assert verified["physical_evidence_status"] == "blocked"
    assert verified["satisfies_s25_attribution_gate"] is False


def transcript(record: dict) -> str:
    return "\n".join(
        [
            "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.composition.M05BAttributionDeviceGateTest",
            "INSTRUMENTATION_STATUS: M05BAttributionEvidence=" + json.dumps(record, sort_keys=True),
            "INSTRUMENTATION_STATUS_CODE: 4",
            "INSTRUMENTATION_CODE: -1",
            "OK (1 test)",
        ]
    )


def make_fixture_tree(tmp_path: Path) -> tuple[dict, list[dict[str, Path]]]:
    sessions: list[dict] = []
    paths: list[dict[str, Path]] = []
    for index, (fps, admitted, delivered) in enumerate(((10, 1, 10), (15, 2, 15), (30, 5, 30)), 1):
        session_id = f"00000000-0000-4000-8000-00000000000{index}"
        session_root = tmp_path / f"exports/{session_id}"
        session_root.mkdir(parents=True)
        manifest = manifest_for(session_id, fps, admitted, delivered)
        frames = frames_for(session_id, admitted, delivered)
        telemetry = telemetry_for(admitted, delivered)
        manifest_path = session_root / "manifest.json"
        frames_path = session_root / "frames.ndjson"
        telemetry_path = session_root / "telemetry.ndjson"
        manifest_path.write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
        frames_path.write_text(
            "\n".join(json.dumps(frame, sort_keys=True) for frame in frames) + "\n",
            encoding="utf-8",
        )
        telemetry_path.write_bytes(telemetry)
        sessions.append(
            {
                "session_id": session_id,
                "export_relative_path": f"exports/{session_id}",
                "configured_inference_fps": fps,
                "manifest_sha256": sha(manifest_path),
                "frames_sha256": sha(frames_path),
                "telemetry_sha256": sha(telemetry_path),
            }
        )
        paths.append(
            {"manifest": manifest_path, "frames": frames_path, "telemetry": telemetry_path}
        )
    return {
        "record_kind": "m05b_short_attribution_session_references",
        "suite": "com.calisthenics.diagnostic.composition.M05BAttributionDeviceGateTest",
        "instrumentation_exit_code": 0,
        "device": S25,
        "identity": IDENTITY,
        "sessions": sessions,
    }, paths


def manifest_for(session_id: str, fps: int, admitted: int, delivered: int) -> dict:
    from tests.samples import minimal_manifest

    manifest = minimal_manifest(
        session_id=session_id,
        schema_version="1.1.0",
        camera_frames_received=delivered,
        inference_frames_submitted=admitted,
        inference_records_emitted=admitted,
        pose_detected_records=admitted,
        frames_skipped_before_inference=delivered - admitted,
    )
    manifest["session_kind"] = "live_conformance"
    manifest["participant_pseudonym_id"] = f"synthetic-m05-{session_id}"
    manifest["cadence"]["configured_inference_fps"] = fps
    manifest["camera"]["requested_camera_fps"] = 30
    manifest["device"]["device_model"] = "SM-S938B"
    manifest["device"]["os_version"] = IDENTITY["runtime_version"]
    manifest["harness_git_sha"] = IDENTITY["app_git_sha"]
    manifest["build_identity"] = dict(IDENTITY)
    manifest["pose_pipeline"]["model_asset_sha256"] = IDENTITY["model_sha256"]
    manifest["pose_pipeline"]["backend_version"] = IDENTITY["backend_version"]
    manifest["pose_pipeline"]["delegate"] = IDENTITY["delegate"]
    manifest["pose_pipeline"]["runtime_version"] = IDENTITY["runtime_version"]
    return manifest


def frames_for(session_id: str, admitted: int, delivered: int) -> list[dict]:
    from tests.samples import minimal_pose_frame

    frames = [
        minimal_pose_frame(index=i, session_id=session_id, capture_ns=i + 1)
        for i in range(admitted)
    ]
    frames[0]["frames_skipped_since_last_emitted_record"] = delivered - admitted
    return frames


def telemetry_for(admitted: int, delivered: int) -> bytes:
    records: list[dict] = []
    sequence = 0
    for frame_index in range(delivered):
        records.append(event(sequence, frame_index, "CAMERA_CALLBACK", "RECEIVED"))
        sequence += 1
        if frame_index < admitted:
            records.append(event(sequence, frame_index, "INFERENCE", "SUBMITTED"))
            sequence += 1
            records.append(event(sequence, frame_index, "INFERENCE", "COMPLETED"))
        else:
            records.append(event(sequence, frame_index, "CADENCE", "CADENCE_SELECTION"))
        sequence += 1
    return b"".join(json.dumps(record, sort_keys=True).encode() + b"\n" for record in records)


def event(sequence: int, frame_index: int, stage: str, disposition: str) -> dict:
    return {
        "record_type": "pipeline_stage_event",
        "schema_version": "1.0.0",
        "sequence": sequence,
        "t_session_ns": sequence,
        "frame_index": frame_index,
        "stage": stage,
        "disposition": disposition,
    }


def fixed_sample(sequence: int, t_session_ns: int, *, terminal: bool) -> dict:
    return {
        "record_type": "fixed_telemetry_sample",
        "schema_version": "2.0.0",
        "sequence": sequence,
        "t_session_ns": t_session_ns,
        "terminal": terminal,
        "thermal": {"kind": "VALUE", "value": 1},
        "battery_temperature": {"kind": "VALUE", "value": 360},
        "battery_level_or_charge": {"kind": "VALUE", "unit": "percent", "value": 42},
        "charging": {"kind": "VALUE", "value": False},
        "power_source": {"kind": "VALUE", "value": "battery"},
        "connectivity": {"kind": "VALUE", "transport": "wifi", "connected": True},
        "keep_awake": {"kind": "UNAVAILABLE", "code": "keep_awake_unavailable"},
        "brightness": {"kind": "UNAVAILABLE", "code": "brightness_unavailable"},
        "lifecycle": {"kind": "VALUE", "value": "foreground"},
    }


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
