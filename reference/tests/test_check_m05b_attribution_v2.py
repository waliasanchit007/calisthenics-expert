from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from unittest.mock import Mock

import pytest

ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import check_m05b_attribution_v2

from observation_contracts.m05b_attribution_v2 import selector_timestamps

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


def test_check_attribution_v2_returns_sanitized_host_evidence_after_exact_reopen(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observation_bytes = json.dumps(_valid_observation(), separators=(",", ":")).encode()
    record = _reference(
        sha256=hashlib.sha256(observation_bytes).hexdigest(),
        length_bytes=len(observation_bytes),
    )

    def fake_transfer(_serial: str, _package: str, _relative: str, destination: Path) -> None:
        destination.write_bytes(observation_bytes)

    monkeypatch.setattr(check_m05b_attribution_v2, "transfer_reference", fake_transfer)
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
        "evidence_schema_version": "2.0.0",
        "record_kind": "m05b_attribution_v2_device_observation",
        "protocol_sha256": "b2cd9efec89186806b0195db1dcbfa130450973ebbb25a256696c736eaf96707",
        "sanitized": True,
        "non_participant_observation": True,
        "device": {
            "device_class": "physical_device",
            "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "api_level": 36,
            "android_release": "16",
            "os_build": "BP4A.251205.006",
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
        for callback_id in range(cadence):
            completion = 10_000_000 + callback_id * 1_000_000
            callbacks.append(
                {
                    "callback_id": callback_id,
                    "capture_timestamp_ns": callback_id,
                    "production_classification": "submit",
                    "cadence_due": True,
                    "slot_available": True,
                    "terminal_class": "admitted_completed",
                    "phase": "measured",
                    "admission_observed_session_ns": completion - 100_000,
                    "completion_timestamp_ns": completion,
                }
            )
        runs.append(
            {
                "cadence_fps": cadence,
                "session_reference": f"attribution-v2/replicate-{prefix}/run-{cadence}",
                "initial_thermal_status": 0,
                "final_thermal_status": 0,
                "measured_span_ns": 1_000_000_000,
                "delivered_callback_ids": list(range(cadence)),
                "terminal_callbacks": callbacks,
                "submitted_inference_ids": list(range(cadence)),
                "pending_inference_ids": [],
            }
        )
    return {
        "order": order,
        "status": "recorded",
        "blocking_codes": [],
        "cooldown_thermal_statuses": [0],
        "runs": runs,
    }
