from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from pathlib import Path

from jsonschema import Draft202012Validator

from observation_contracts.m05b_attribution_v2_codes import ATTRIBUTION_V2_DRAFT_CODES
from observation_contracts.schema_loader import (
    load_m05b_attribution_observation_v2_schema,
    load_m05b_attribution_protocol_v2_schema,
)
from observation_contracts.validator_codes import STABLE_EMITTED_CODES

ROOT = Path(__file__).parents[2]
PROTOCOL_PATH = ROOT / "docs/protocols/m05b-attribution-v2.protocol.json"
LEDGER_PATH = ROOT / "docs/evidence/m05b-attribution-v2/s25-attribution-v2-ledger.json"

TERMINAL_CLASSES = [
    "admitted_completed",
    "admitted_inference_failed",
    "cadence_only",
    "backpressure_only",
    "cadence_and_backpressure",
    "preprocessing_failed",
    "session_stopping",
    "admission_state_divergence",
]

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


def test_v2_tooling_schemas_are_valid_draft_2020_12_contracts() -> None:
    Draft202012Validator.check_schema(load_m05b_attribution_protocol_v2_schema())
    Draft202012Validator.check_schema(load_m05b_attribution_observation_v2_schema())


def test_canonical_protocol_pins_all_preregistered_values() -> None:
    raw = PROTOCOL_PATH.read_bytes()
    protocol = json.loads(raw)

    assert raw.endswith(b"\n")
    assert raw == (json.dumps(protocol, sort_keys=True, separators=(",", ":")) + "\n").encode()
    assert protocol["protocol_version"] == "2.0.0"
    assert protocol["record_kind"] == "m05b_attribution_v2_protocol"
    assert protocol["target_cadences_fps"] == [10, 15, 30]
    assert protocol["source"] == {
        "measured_callbacks": 300,
        "warmup_callbacks": 30,
    }
    assert protocol["backend"] == {
        "measured_completions": 100,
        "warmup_completed": 20,
        "warmup_elapsed_ns": 2_000_000_000,
    }
    assert protocol["arm_cap_ns"] == 60_000_000_000
    assert protocol["cooldown"] == {
        "maximum_duration_ns": 600_000_000_000,
        "sampling_interval_ns": 1_000_000_000,
    }
    assert protocol["replicate_orders"] == [[10, 15, 30], [30, 15, 10]]
    assert protocol["terminal_classes"] == TERMINAL_CLASSES
    assert protocol["clock"] == {
        "domain": "session_relative_monotonic",
        "unit": "nanoseconds",
    }


def test_canonical_protocol_validates_against_the_released_v2_schema() -> None:
    protocol = json.loads(PROTOCOL_PATH.read_text(encoding="utf-8"))
    validator = Draft202012Validator(load_m05b_attribution_protocol_v2_schema())

    assert list(validator.iter_errors(protocol)) == []


def test_protocol_schema_rejects_changed_sample_count_and_unknown_terminal_class() -> None:
    protocol = json.loads(PROTOCOL_PATH.read_text(encoding="utf-8"))
    validator = Draft202012Validator(load_m05b_attribution_protocol_v2_schema())

    changed_count = deepcopy(protocol)
    changed_count["backend"]["measured_completions"] = 99
    unknown_terminal = deepcopy(protocol)
    unknown_terminal["terminal_classes"].append("unclassified")

    assert list(validator.iter_errors(changed_count))
    assert list(validator.iter_errors(unknown_terminal))


def test_observation_schema_is_closed_and_requires_non_participant_sanitization() -> None:
    validator = Draft202012Validator(load_m05b_attribution_observation_v2_schema())
    observation = {
        "evidence_schema_version": "2.0.0",
        "record_kind": "m05b_attribution_v2_device_observation",
        "protocol_sha256": "a" * 64,
        "sanitized": True,
        "non_participant_observation": True,
        "device": {
            "device_class": "physical_device",
            "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "api_level": 36,
            "android_release": "16",
            "os_build": "BP1A.250605.004",
        },
        "identity": {
            "app_git_sha": "b" * 40,
            "probe_git_sha": "b" * 40,
            "app_apk_sha256": "c" * 64,
            "test_apk_sha256": "d" * 64,
            "model_sha256": "e" * 64,
            "runtime_version": "16",
            "backend_version": "1.0.0",
            "delegate": "cpu",
        },
        "source_arm": {
            "status": "recorded",
            "blocking_codes": [],
            "warmup_count": 30,
            "measured_count": 300,
            "timestamps_ns": [index * 33_333_333 for index in range(300)],
            "observed_ae_target_fps": {"lower": 30, "upper": 30},
            "thermal_status": 0,
            "initial_cooldown_thermal_statuses": [],
            "initial_cooldown_recovered": None,
        },
        "backend_arm": None,
        "selector_arm": None,
        "end_to_end_replicates": [
            {
                "order": [10, 15, 30],
                "status": "blocked",
                "blocking_codes": ["ATTRIBUTION.REPLICATE_DISAGREEMENT"],
                "cooldown_thermal_statuses": [0],
                "runs": [
                    {
                        "cadence_fps": 10,
                        "session_reference": "exports/00000000-0000-4000-8000-000000000001",
                        "arm_started_ns": 0,
                        "initial_thermal_status": 0,
                        "final_thermal_status": 0,
                        "measured_span_ns": 1_000_000_000,
                        "delivered_callback_ids": [7],
                        "terminal_callbacks": [
                            {
                                "callback_id": 7,
                                "capture_timestamp_ns": 123_000_000,
                                "production_classification": "cadence_selection",
                                "cadence_due": False,
                                "slot_available": True,
                                "terminal_class": "cadence_only",
                                "phase": "measured",
                                "admission_observed_session_ns": None,
                                "completion_timestamp_ns": None,
                            }
                        ],
                        "submitted_inference_ids": [],
                        "pending_inference_ids": [],
                    }
                ],
            }
        ],
        "blocking_codes": [
            "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT",
            "ATTRIBUTION.REPLICATE_DISAGREEMENT",
            "ATTRIBUTION.SELECTOR_DIVERGENCE",
        ],
    }

    assert list(validator.iter_errors(observation)) == []
    missing_terminals = deepcopy(observation)
    del missing_terminals["end_to_end_replicates"][0]["runs"][0]["terminal_callbacks"]
    assert list(validator.iter_errors(missing_terminals))
    missing_admission_timestamp = deepcopy(observation)
    del missing_admission_timestamp["end_to_end_replicates"][0]["runs"][0]["terminal_callbacks"][0][
        "admission_observed_session_ns"
    ]
    assert list(validator.iter_errors(missing_admission_timestamp))
    absolute_reference = deepcopy(observation)
    absolute_reference["end_to_end_replicates"][0]["runs"][0]["session_reference"] = (
        "/data/user/0/private"
    )
    assert list(validator.iter_errors(absolute_reference))
    observation["participant_id"] = "forbidden"
    assert list(validator.iter_errors(observation))
    del observation["participant_id"]
    observation["sanitized"] = False
    assert list(validator.iter_errors(observation))


def test_pending_ledger_binds_the_exact_canonical_protocol_bytes() -> None:
    protocol_bytes = PROTOCOL_PATH.read_bytes()
    ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))

    assert ledger == {
        "evidence_file": None,
        "evidence_sha256": None,
        "ledger_schema_version": "2.0.0",
        "missing_precondition": "attribution_v2_physical_evidence_not_recorded",
        "protocol_file": "docs/protocols/m05b-attribution-v2.protocol.json",
        "protocol_sha256": hashlib.sha256(protocol_bytes).hexdigest(),
        "record_kind": "s25_attribution_v2_ledger",
        "status": "pending",
    }


def test_v2_codes_are_promoted_as_one_complete_family() -> None:
    assert ATTRIBUTION_V2_DRAFT_CODES == V2_CODES
    assert V2_CODES <= STABLE_EMITTED_CODES
