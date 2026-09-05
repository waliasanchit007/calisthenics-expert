from __future__ import annotations

from copy import deepcopy

from observation_contracts.m05b_attribution_v2 import (
    backend_supports,
    closed_interval_opportunities,
    selector_timestamps,
    source_supports,
    validate_attribution_v2,
)


def test_closed_interval_source_capacity_pins_exact_integer_boundary() -> None:
    exact = [index * 100_000_000 for index in range(300)]

    assert closed_interval_opportunities(exact[-1] - exact[0], 10) == 300
    assert source_supports(exact, 10)
    assert not source_supports(exact[:-1], 10)
    assert not source_supports([*exact[:150], exact[149], *exact[151:]], 10)
    assert not source_supports(exact, 15)
    assert not source_supports(exact, 30)


def test_backend_capacity_pins_count_and_inclusive_interval() -> None:
    exact = [100_000_000] * 100

    assert backend_supports(exact, 10)
    assert not backend_supports(exact[:-1], 10)
    assert not backend_supports([*exact[:50], 100_000_001, *exact[51:]], 10)
    assert not backend_supports([*exact[:50], -1, *exact[51:]], 10)


def test_selector_oracle_matches_exact_ten_fifteen_thirty_sequences() -> None:
    timestamps = [0, 33_333_333, 66_666_666, 99_999_999, 100_000_000, 133_333_333]

    assert selector_timestamps(timestamps, 10) == [0, 100_000_000]
    assert selector_timestamps(timestamps, 15) == [0, 66_666_666, 133_333_333]
    assert selector_timestamps(timestamps, 30) == [
        0,
        33_333_333,
        66_666_666,
        99_999_999,
        133_333_333,
    ]


def test_valid_two_order_document_derives_no_issues() -> None:
    assert validate_attribution_v2(_valid_document(), "observation.json") == []


def test_validator_recomputes_selector_closure_failures_and_replicate_disagreement() -> None:
    selector = _valid_document()
    selector["selector_arm"]["production_selections"]["15"] = []  # type: ignore[index]
    assert _codes(selector) == ["ATTRIBUTION.SELECTOR_DIVERGENCE"]

    closure = _valid_document()
    closure["callback_arm"]["terminal_callbacks"] = []  # type: ignore[index]
    assert _codes(closure) == [
        "ATTRIBUTION.CALLBACK_CONSERVATION",
        "ATTRIBUTION.TERMINAL_CLOSURE",
    ]

    disagreement = _valid_document()
    disagreement["replicates"] = disagreement["replicates"][:1]  # type: ignore[index]
    assert _codes(disagreement) == ["ATTRIBUTION.REPLICATE_DISAGREEMENT"]


def test_selector_only_and_inference_failure_are_blocking() -> None:
    selector_only = _valid_document()
    selector_only["source_arm"] = None
    selector_only["backend_arm"] = None
    selector_only["replicates"] = []
    assert "ATTRIBUTION.SELECTOR_ONLY" in _codes(selector_only)

    failed = _valid_document()
    failed["backend_arm"]["inference_failures"] = 1  # type: ignore[index]
    assert "ATTRIBUTION.INFERENCE_FAILURE" in _codes(failed)


def _valid_document() -> dict[str, object]:
    timestamps = [index * 20_000_000 for index in range(300)]
    selections = {str(fps): selector_timestamps(timestamps, fps) for fps in [10, 15, 30]}
    replicate = {
        "admitted_completion_rates": {"10": 9, "15": 14, "30": 29},
        "backend_supported": {"10": True, "15": True, "30": True},
        "cadence_opportunity_rates": {"10": 10, "15": 15, "30": 30},
        "source_supported": {"10": True, "15": True, "30": True},
    }
    return {
        "protocol_version": "2.1.0",
        "identity_matches": True,
        "environment_matches": True,
        "device_blocking_codes": [],
        "source_arm": {"measured_timestamps_ns": timestamps},
        "backend_arm": {
            "measured_service_durations_ns": [10_000_000] * 100,
            "inference_failures": 0,
        },
        "selector_arm": {
            "production_selections": deepcopy(selections),
            "host_selections": deepcopy(selections),
        },
        "callback_arm": {
            "delivered_callback_ids": [0],
            "submitted_inference_ids": [0],
            "pending_inference_ids": [],
            "terminal_callbacks": [
                {
                    "callback_id": 0,
                    "capture_timestamp_ns": 0,
                    "production_classification": "submit",
                    "cadence_due": True,
                    "slot_available": True,
                    "terminal_class": "admitted_completed",
                }
            ],
        },
        "replicates": [
            {**deepcopy(replicate), "cadence_order": [10, 15, 30]},
            {**deepcopy(replicate), "cadence_order": [30, 15, 10]},
        ],
    }


def _codes(document: dict[str, object]) -> list[str]:
    return [issue.code for issue in validate_attribution_v2(document, "observation.json")]
