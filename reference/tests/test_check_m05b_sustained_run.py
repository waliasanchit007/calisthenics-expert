from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

from check_m05b_sustained_run import (
    SustainedCheckError,
    check_sustained_run,
    transfer_references_from_device,
)
from reference.tests.test_m05b_envelope import (
    _envelope,
    _observed_start,
    _order,
)
from reference.tests.test_m05b_sustained import _valid_record

from observation_contracts.m05b_envelope import admit_run, canonical_sha256

SESSION_ID = "00000000-0000-4000-8000-000000000010"


def _canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _fixture(tmp_path: Path) -> tuple[dict[str, object], Path]:
    envelope = _envelope()
    order = _order()
    observed = _observed_start()
    envelope_bytes = _canonical(envelope)
    order_bytes = _canonical(order)
    frozen = {
        "frozen_run_inputs_schema_version": "1.0.0",
        "record_kind": "m05b_frozen_run_inputs",
        "envelope_path": "envelope.json",
        "envelope_sha256": _sha(envelope_bytes),
        "envelope_json": envelope_bytes.decode(),
        "order_path": "execution-order.json",
        "order_sha256": _sha(order_bytes),
        "order_json": order_bytes.decode(),
        "observed_start": observed,
    }
    frozen_bytes = _canonical(frozen)
    admission = admit_run(
        envelope=envelope,
        order=order,
        observed_start=observed,
        session_id=SESSION_ID,
        cadence_fps=10,
        attempt_sequence=1,
        predecessor_status="not_applicable",
        envelope_path="envelope.json",
        order_path="execution-order.json",
    )
    admission_bytes = _canonical(admission)
    admission_sha = _sha(admission_bytes)
    attempt = {
        "attempt_entry_schema_version": "1.0.0",
        "record_kind": "m05b_attempt_entry",
        "attempt_sequence": 1,
        "cadence_fps": 10,
        "admission_sha256": admission_sha,
        "outcome": "completed",
        "previous_entry_sha256": None,
    }
    attempt_bytes = _canonical(attempt)
    sustained = _valid_record()
    sustained["session_id"] = SESSION_ID
    sustained["artifact_refs"] = {
        "envelope": {"path": "envelope.json", "sha256": _sha(envelope_bytes)},
        "order": {"path": "execution-order.json", "sha256": _sha(order_bytes)},
        "admission": {
            "path": f"exports/{SESSION_ID}/run-admission.json",
            "sha256": admission_sha,
        },
    }
    sustained["attempt_chain"] = [{"entry": attempt, "entry_sha256": canonical_sha256(attempt)}]
    latency = sustained["latency"]
    assert isinstance(latency, dict)
    stage_counts = [
        {"stage": "CAMERA_CALLBACK", "disposition": "RECEIVED", "count": 100},
        {"stage": "YUV_OWNERSHIP", "disposition": "ACQUIRED", "count": 100},
        {"stage": "YUV_OWNERSHIP", "disposition": "RELEASED", "count": 100},
        {"stage": "PREPROCESSING", "disposition": "STARTED", "count": 100},
        {"stage": "PREPROCESSING", "disposition": "COMPLETED", "count": 100},
        {"stage": "CADENCE", "disposition": "CADENCE_SELECTION", "count": 90},
        {"stage": "INFERENCE", "disposition": "SUBMITTED", "count": 10},
        {"stage": "INFERENCE", "disposition": "COMPLETED", "count": 10},
    ]
    telemetry_records: list[dict[str, object]] = []
    for index in range(1502):
        t_session_ns = min(index * 1_000_000_000, 1_500_500_000_000)
        telemetry_records.append(
            {
                "record_type": "fixed_telemetry_sample",
                "schema_version": "2.0.0",
                "sequence": index * 2,
                "t_session_ns": t_session_ns,
                "terminal": index == 1501,
                "battery_temperature": {
                    "kind": "VALUE",
                    "value": 300 if index == 1501 else 295,
                },
            }
        )
        telemetry_records.append(
            {
                "record_type": "sustained_stage_counter",
                "schema_version": "1.0.0",
                "sequence": index * 2 + 1,
                "t_session_ns": t_session_ns,
                "fixed_telemetry_sequence": index * 2,
                "counts": stage_counts if index == 0 else [],
            }
        )
    telemetry_records.append(
        {
            "record_type": "sustained_latency_summary",
            "schema_version": "1.0.0",
            "sequence": 3004,
            "t_session_ns": 1_500_500_000_000,
            "upper_bounds_ns": latency["bucket_upper_bounds_ns"],
            "bucket_counts": latency["bucket_counts"],
            "sample_count": sum(latency["bucket_counts"]),
        }
    )
    telemetry_bytes = b"".join(_canonical(value) + b"\n" for value in telemetry_records)
    files = {
        "m05b-frozen-run-inputs.json": frozen_bytes,
        "m05b-run-state/attempt-chain/attempt-0001.json": attempt_bytes,
        f"exports/{SESSION_ID}/manifest.json": b'{"session_id":"' + SESSION_ID.encode() + b'"}',
        f"exports/{SESSION_ID}/frames.ndjson": b'{"frame":1}\n',
        f"exports/{SESSION_ID}/telemetry.ndjson": telemetry_bytes,
        f"exports/{SESSION_ID}/run-admission.json": admission_bytes,
    }
    for relative, data in files.items():
        destination = tmp_path / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    record: dict[str, object] = {
        "record_kind": "m05b_sustained_session_reference",
        "suite": "com.calisthenics.diagnostic.composition.M05BSustainedDeviceGateTest",
        "sanitized": True,
        "non_participant_observation": True,
        "session_id": SESSION_ID,
        "cadence_fps": 10,
        "export_relative_path": f"exports/{SESSION_ID}",
        "device": {
            "device_class": "physical_device",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "marketing_name": "Galaxy S25 Ultra",
            "api_level": 36,
            "android_release": "16",
        },
        "candidate": {"git_sha": "d" * 40, "app_sha256": "a" * 64},
        "frozen_inputs_ref": {
            "path": "m05b-frozen-run-inputs.json",
            "sha256": _sha(frozen_bytes),
        },
        "durable_artifacts": {
            name: {"path": path, "sha256": _sha(files[path])}
            for name, path in {
                "manifest": f"exports/{SESSION_ID}/manifest.json",
                "frames": f"exports/{SESSION_ID}/frames.ndjson",
                "telemetry": f"exports/{SESSION_ID}/telemetry.ndjson",
                "run_admission": f"exports/{SESSION_ID}/run-admission.json",
            }.items()
        },
        "attempt_entry_refs": [
            {
                "path": "m05b-run-state/attempt-chain/attempt-0001.json",
                "sha256": _sha(attempt_bytes),
            }
        ],
        "sustained_record": sustained,
    }
    return record, tmp_path


def _transcript(record: dict[str, object]) -> str:
    payload = json.dumps(record, sort_keys=True, separators=(",", ":"))
    return (
        "INSTRUMENTATION_STATUS: class=com.calisthenics.diagnostic.composition."
        "M05BSustainedDeviceGateTest\n"
        f"INSTRUMENTATION_STATUS: M05BSustainedEvidence={payload}\n"
        "INSTRUMENTATION_STATUS_CODE: 4\n"
        "OK (1 test)\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


def _set_attempt_outcome(record: dict[str, object], root: Path, outcome: str) -> None:
    sustained = record["sustained_record"]
    assert isinstance(sustained, dict)
    chain = sustained["attempt_chain"]
    assert isinstance(chain, list)
    wrapper = chain[-1]
    assert isinstance(wrapper, dict)
    entry = wrapper["entry"]
    assert isinstance(entry, dict)
    entry["outcome"] = outcome
    entry_bytes = _canonical(entry)
    wrapper["entry_sha256"] = _sha(entry_bytes)
    refs = record["attempt_entry_refs"]
    assert isinstance(refs, list)
    reference = refs[-1]
    assert isinstance(reference, dict)
    path = root / str(reference["path"])
    path.write_bytes(entry_bytes)
    reference["sha256"] = _sha(entry_bytes)


def test_checker_rehashes_every_reference_and_recomputes_sustained_status(tmp_path: Path) -> None:
    record, root = _fixture(tmp_path)

    result = check_sustained_run(_transcript(record), root, subprocess_exit_code=0)

    assert result["physical_evidence_status"] == "recorded"
    assert result["blocking_codes"] == []
    assert result["session_id"] == SESSION_ID


def test_checker_rejects_rehashed_reference_substitution(tmp_path: Path) -> None:
    record, root = _fixture(tmp_path)
    (root / f"exports/{SESSION_ID}/telemetry.ndjson").write_bytes(b"substituted\n")

    with pytest.raises(SustainedCheckError, match="digest"):
        check_sustained_run(_transcript(record), root, subprocess_exit_code=0)


def test_checker_binds_device_reported_candidate_to_the_frozen_envelope(tmp_path: Path) -> None:
    record, root = _fixture(tmp_path)
    candidate = record["candidate"]
    assert isinstance(candidate, dict)
    candidate["app_sha256"] = "f" * 64

    with pytest.raises(SustainedCheckError, match="candidate"):
        check_sustained_run(_transcript(record), root, subprocess_exit_code=0)


def test_checker_derives_latency_from_the_durable_terminal_summary(tmp_path: Path) -> None:
    record, root = _fixture(tmp_path)
    telemetry_path = root / f"exports/{SESSION_ID}/telemetry.ndjson"
    lines = telemetry_path.read_text().splitlines()
    summary = json.loads(lines[-1])
    summary["bucket_counts"][0] -= 1
    summary["bucket_counts"][1] += 1
    lines[-1] = _canonical(summary).decode()
    mutated = ("\n".join(lines) + "\n").encode()
    telemetry_path.write_bytes(mutated)
    artifacts = record["durable_artifacts"]
    assert isinstance(artifacts, dict)
    telemetry_ref = artifacts["telemetry"]
    assert isinstance(telemetry_ref, dict)
    telemetry_ref["sha256"] = _sha(mutated)

    with pytest.raises(SustainedCheckError, match="latency"):
        check_sustained_run(_transcript(record), root, subprocess_exit_code=0)


def test_checker_derives_fixed_telemetry_and_stage_counts_from_durable_bytes(
    tmp_path: Path,
) -> None:
    record, root = _fixture(tmp_path)
    telemetry_path = root / f"exports/{SESSION_ID}/telemetry.ndjson"
    lines = telemetry_path.read_text().splitlines()
    first_counter = json.loads(lines[1])
    first_counter["counts"][0]["count"] = 99
    lines[1] = _canonical(first_counter).decode()
    mutated = ("\n".join(lines) + "\n").encode()
    telemetry_path.write_bytes(mutated)
    artifacts = record["durable_artifacts"]
    assert isinstance(artifacts, dict)
    telemetry_ref = artifacts["telemetry"]
    assert isinstance(telemetry_ref, dict)
    telemetry_ref["sha256"] = _sha(mutated)

    with pytest.raises(SustainedCheckError, match="stage"):
        check_sustained_run(_transcript(record), root, subprocess_exit_code=0)


def test_checker_distinguishes_a_trustworthy_blocking_observation(tmp_path: Path) -> None:
    record, root = _fixture(tmp_path)
    sustained = record["sustained_record"]
    assert isinstance(sustained, dict)
    sustained["source_outcome"] = "failed"
    _set_attempt_outcome(record, root, "blocked")
    sustained["blocking_codes"] = ["ATTEMPT.RECOVERY_REQUIRED", "SOURCE.OUTCOME"]

    result = check_sustained_run(_transcript(record), root, subprocess_exit_code=0)

    assert result["physical_evidence_status"] == "blocked"
    assert result["blocking_codes"] == ["ATTEMPT.RECOVERY_REQUIRED", "SOURCE.OUTCOME"]


def test_checker_rejects_blocking_observation_recorded_as_completed_attempt(tmp_path: Path) -> None:
    record, root = _fixture(tmp_path)
    sustained = record["sustained_record"]
    assert isinstance(sustained, dict)
    sustained["source_outcome"] = "failed"
    sustained["blocking_codes"] = ["SOURCE.OUTCOME"]

    with pytest.raises(SustainedCheckError, match="attempt outcome"):
        check_sustained_run(_transcript(record), root, subprocess_exit_code=0)


def test_device_transfer_uses_binary_safe_exec_out_for_every_reference(tmp_path: Path) -> None:
    record, source = _fixture(tmp_path / "source")
    calls: list[list[str]] = []

    def fake_adb(command: list[str], **_: object) -> object:
        calls.append(command)
        remote = Path(command[-1]).relative_to("files")
        if remote.parts[:2] == ("diagnostic-harness", "exports"):
            remote = Path(*remote.parts[1:])
        return type(
            "Completed",
            (),
            {"returncode": 0, "stdout": (source / remote).read_bytes(), "stderr": b""},
        )()

    destination = tmp_path / "pulled"
    transfer_references_from_device(
        serial="192.0.2.1:5555",
        package="com.calisthenics.diagnostic.android",
        record=record,
        destination=destination,
        run_adb=fake_adb,
    )

    assert len(calls) == 6
    assert all(
        command[:6]
        == [
            "adb",
            "-s",
            "192.0.2.1:5555",
            "exec-out",
            "run-as",
            "com.calisthenics.diagnostic.android",
        ]
        for command in calls
    )
    assert all("shell" not in command for command in calls)
