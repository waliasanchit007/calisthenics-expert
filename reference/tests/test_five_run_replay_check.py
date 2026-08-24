"""Hermetic checks for the five-run S25 replay device-gate transcript wrapper (Task 5).

`satisfies_gate` is the host-side re-derivation of the same gate `renderFiveRunReplayEvidence`
(Kotlin, `shared/commonMain`) computes: exactly five runs, all published and CLI-validated, every
run agreeing on submitted indices/backend timestamps/canonical digests *and* on what the pose
pipeline returned, every run actually detecting a pose, and the observing device a physical Samsung
Galaxy S25 Ultra. It must never be satisfiable by an emulator, by a partial or inconsistent set of
runs, or by five identical runs of a pipeline that detected nothing — the last of which reproduces
every input-side determinism property perfectly, so nothing else in the record rules it out.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from check_five_run_replay import (
    FiveRunReplayCheckError,
    detection_is_non_vacuous,
    extract_record,
    require_exact_suite,
    satisfies_gate,
)

SUITE_MARKER = "class=com.calisthenics.diagnostic.composition.FiveRunReplayDeviceGateTest"


DETECTED = {
    "pose_present_per_frame": [True, True],
    "normalized_landmark_count_per_frame": [33, 33],
    "world_landmark_count_per_frame": [33, 33],
    "pose_detected_records": 2,
}

# The same two frames through a pipeline that detected nothing. Every input-side field is identical
# to DETECTED, which is exactly why the gate cannot be left to those fields alone.
UNDETECTED = {
    "pose_present_per_frame": [False, False],
    "normalized_landmark_count_per_frame": [0, 0],
    "world_landmark_count_per_frame": [0, 0],
    "pose_detected_records": 0,
}


def _run(
    index: int,
    *,
    published: bool = True,
    validated: bool = True,
    indices=(0, 1),
    pose: dict | None = None,
) -> dict:
    return {
        "run_index": index,
        "session_id": f"session-{index}",
        "published": published,
        "submitted_indices": list(indices),
        "backend_timestamps_ms": [0, 33],
        "canonical_input_sha256": ["c0", "c1"],
        **(DETECTED if pose is None else pose),
        "manifest_sha256": f"m{index}",
        "frames_sha256": f"n{index}",
        "validated_by_reference_cli": validated,
    }


def _record(
    *,
    device_class="physical_device",
    marketing_name="Galaxy S25 Ultra",
    model="SM-S938B",
    manufacturer="samsung",
    runs=None,
) -> dict:
    return {
        "harness_git_sha": "0123456789abcdef0123456789abcdef01234567",
        "runs_completed": 5,
        "runs": runs if runs is not None else [_run(i) for i in range(1, 6)],
        "environment": {
            "device_class": device_class,
            "manufacturer": manufacturer,
            "model": model,
            "marketing_name": marketing_name,
        },
    }


def _transcript(record_json: str) -> str:
    return f"INSTRUMENTATION_STATUS: class=...FiveRunReplayDeviceGateTest\n{SUITE_MARKER}\nOK (1 test)\nINSTRUMENTATION_STATUS: FiveRunReplayEvidence={record_json}\n"


def test_require_exact_suite_accepts_a_genuine_single_passing_run() -> None:
    require_exact_suite(f"{SUITE_MARKER}\nOK (1 test)\n")


def test_require_exact_suite_rejects_a_vacuous_zero_test_run() -> None:
    with pytest.raises(FiveRunReplayCheckError):
        require_exact_suite(f"{SUITE_MARKER}\nOK (0 tests)\n")


def test_extract_record_requires_exactly_five_runs() -> None:
    import json

    transcript = _transcript(json.dumps(_record(runs=[_run(i) for i in range(1, 5)])))
    with pytest.raises(FiveRunReplayCheckError):
        extract_record(transcript)


def test_extract_record_reads_a_well_formed_five_run_record() -> None:
    import json

    transcript = _transcript(json.dumps(_record()))
    record = extract_record(transcript)
    assert len(record["runs"]) == 5


def test_satisfies_gate_is_true_for_five_consistent_published_validated_runs_on_an_s25() -> None:
    assert satisfies_gate(_record()) is True


def test_satisfies_gate_is_false_for_an_emulator() -> None:
    assert satisfies_gate(_record(device_class="emulator")) is False


def test_satisfies_gate_is_false_for_a_non_s25_physical_device() -> None:
    assert satisfies_gate(_record(marketing_name="Galaxy A15", model="SM-A156B")) is False


def test_satisfies_gate_is_false_when_a_run_is_not_published() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[2] = _run(3, published=False)
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_a_run_is_not_validated() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[4] = _run(5, validated=False)
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_runs_diverge() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[1] = _run(2, indices=(0,))
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_for_five_identical_runs_that_detected_no_pose() -> None:
    # The finding: an empty pose pipeline agrees with itself on every input-side field, so the gate
    # would otherwise certify five runs of a pipeline that observed nothing.
    runs = [_run(i, pose=UNDETECTED) for i in range(1, 6)]
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_one_run_detected_no_pose() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[3] = _run(4, pose=UNDETECTED)
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_for_a_detected_count_no_record_supports() -> None:
    claimed = dict(UNDETECTED, pose_detected_records=2)
    runs = [_run(i, pose=claimed) for i in range(1, 6)]
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_a_detected_frame_has_no_landmarks_behind_it() -> None:
    hollow = dict(DETECTED, normalized_landmark_count_per_frame=[33, 0])
    runs = [_run(i, pose=hollow) for i in range(1, 6)]
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_records_do_not_cover_every_submitted_frame() -> None:
    short = {
        "pose_present_per_frame": [True],
        "normalized_landmark_count_per_frame": [33],
        "world_landmark_count_per_frame": [33],
        "pose_detected_records": 1,
    }
    runs = [_run(i, pose=short) for i in range(1, 6)]
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_the_detection_pattern_diverges_across_runs() -> None:
    diverged = {
        "pose_present_per_frame": [True, False],
        "normalized_landmark_count_per_frame": [33, 0],
        "world_landmark_count_per_frame": [33, 0],
        "pose_detected_records": 1,
    }
    runs = [_run(i) for i in range(1, 6)]
    runs[2] = _run(3, pose=diverged)
    assert satisfies_gate(_record(runs=runs)) is False


def test_satisfies_gate_is_false_when_landmark_cardinality_diverges_across_runs() -> None:
    runs = [_run(i) for i in range(1, 6)]
    runs[1] = _run(2, pose=dict(DETECTED, world_landmark_count_per_frame=[33, 32]))
    assert satisfies_gate(_record(runs=runs)) is False


def test_detection_is_non_vacuous_fails_closed_on_a_record_without_pose_fields() -> None:
    bare = {"submitted_indices": [0, 1]}
    assert detection_is_non_vacuous(bare) is False


def test_extract_record_rejects_a_record_whose_runs_carry_no_pose_observation() -> None:
    import json

    runs = [_run(i) for i in range(1, 6)]
    for run in runs:
        del run["pose_detected_records"]
    with pytest.raises(FiveRunReplayCheckError):
        extract_record(_transcript(json.dumps(_record(runs=runs))))


def test_authorizes_the_s25_ultra_by_model_when_marketing_name_is_absent() -> None:
    """Retail S25 Ultra firmware may report ro.config.marketing_name as empty.

    A marketing-name-only rule rejected the very hardware this gate exists to authorize.
    Mirrors isAuthorizedFiveRunGateTarget in FiveRunReplayEvidence.kt.
    """
    assert satisfies_gate(_record(marketing_name="", model="SM-S938B")) is True


def test_authorizes_every_s25_ultra_regional_model() -> None:
    for model in ("SM-S938B", "SM-S938U", "SM-S938N", "SM-S938W", "sm-s938b"):
        assert satisfies_gate(_record(marketing_name="", model=model)) is True, model


def test_still_authorizes_on_marketing_name_when_the_model_is_unrecognized() -> None:
    assert satisfies_gate(_record(marketing_name="Galaxy S25 Ultra", model="")) is True


def test_rejects_an_emulator_regardless_of_model_or_marketing_name() -> None:
    assert (
        satisfies_gate(
            _record(device_class="emulator", marketing_name="Galaxy S25 Ultra", model="SM-S938B")
        )
        is False
    )


def test_rejects_another_samsung_physical_device() -> None:
    assert satisfies_gate(_record(marketing_name="", model="SM-S931B")) is False


def test_rejects_a_non_samsung_device_even_when_the_model_prefix_matches() -> None:
    assert (
        satisfies_gate(_record(marketing_name="", model="SM-S938B", manufacturer="notsamsung"))
        is False
    )
