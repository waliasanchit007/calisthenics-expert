"""Local, exploratory jumping-jack cycle feedback. Labels and detector inputs remain separate.

The Kotlin candidate emits the same {timestamp_ns, ..., phase, count, reason, counted} row shape
(here with arm_degrees/leg_spread_ratio instead of knee_degrees) as the squat/lunge cycle
experiments, so this reuses the squat tool's generic event-count comparison/hashing/cache/
corpus-verification helpers unchanged; only the pieces that differ (which Kotlin source files/
Gradle task/output fields to reference) are redefined here.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import time
from pathlib import Path
from typing import Any

from offline_movement_feedback import (
    compare_events,
    load_json,
    prediction_events,
    private_output,
    sha256,
    verify_cache,
    verify_corpus,
    write_json,
)


def verify_run(
    record: dict[str, Any], config: Path, poses: Path, predictions: Path
) -> None:
    for key, path in (
        ("config", config),
        ("poses", poses),
        ("predictions", predictions),
    ):
        if record.get(f"{key}_sha256") != sha256(path):
            raise ValueError(f"{key} hash does not match recorded Kotlin invocation")


def engine_snapshot(harness: Path) -> dict[str, str]:
    relative_paths = (
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/JumpingJackExperiment.kt",
        "shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/ReplayJumpingJackPose.kt",
        "shared/build.gradle.kts",
    )
    return {name: sha256(harness / name) for name in relative_paths}


def load_rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def summarize_closing_predicate_support(
    rows: list[dict[str, Any]],
    config: dict[str, Any],
    labels: dict[str, Any] | None,
) -> dict[str, Any]:
    """Summarize observed closing-predicate support within each admitted label window.

    This is a report-level diagnostic, not a reconstruction of the Kotlin endpoint state.
    """
    claim = (
        "window-local observed predicate support; not internal endpointSamples, "
        "not a simulated counter, and not definitive causality or a technique judgement"
    )
    if labels is None:
        return {"status": "UNLABELLED", "claim": claim, "windows": []}

    arm_threshold = config["arms_down_degrees"]
    leg_threshold = config["legs_together_ratio"]
    maximum_gap_ns = config["maximum_gap_ns"]
    minimum_endpoint_samples = config["minimum_endpoint_samples"]
    windows = []
    for event_index, interval in enumerate(labels["events"]):
        start_ns, end_ns = interval["start_ns"], interval["end_ns"]
        window_rows = [row for row in rows if start_ns <= row["timestamp_ns"] <= end_ns]
        usable = [
            row
            for row in window_rows
            if row.get("reason") == "NONE"
            and all(
                type(row.get(key)) in (int, float) and math.isfinite(row[key])
                for key in ("arm_degrees", "leg_spread_ratio")
            )
        ]
        arm_pass = [row for row in usable if row["arm_degrees"] <= arm_threshold]
        leg_pass = [row for row in usable if row["leg_spread_ratio"] <= leg_threshold]
        joint_pass = [
            row
            for row in usable
            if row["arm_degrees"] <= arm_threshold
            and row["leg_spread_ratio"] <= leg_threshold
        ]
        longest_run = 0
        current_run = 0
        previous_timestamp = None
        joint_timestamps = {row["timestamp_ns"] for row in joint_pass}
        for row in window_rows:
            timestamp = row["timestamp_ns"]
            continuous = (
                previous_timestamp is not None
                and timestamp - previous_timestamp <= maximum_gap_ns
            )
            is_joint = timestamp in joint_timestamps and row in usable
            current_run = current_run + 1 if is_joint and continuous else int(is_joint)
            longest_run = max(longest_run, current_run)
            previous_timestamp = timestamp
        windows.append(
            {
                "event_index": event_index,
                "start_ns": start_ns,
                "end_ns": end_ns,
                "usable_rows": len(usable),
                "minimum_arm_degrees": min(
                    (row["arm_degrees"] for row in usable), default=None
                ),
                "minimum_leg_spread_ratio": min(
                    (row["leg_spread_ratio"] for row in usable), default=None
                ),
                "arm_predicate_rows": len(arm_pass),
                "leg_predicate_rows": len(leg_pass),
                "joint_predicate_rows": len(joint_pass),
                "longest_joint_predicate_run": longest_run,
                "joint_predicate_timestamps_ns": [
                    row["timestamp_ns"] for row in joint_pass
                ],
                "minimum_endpoint_samples": minimum_endpoint_samples,
            }
        )
    return {"status": "LABELLED", "claim": claim, "windows": windows}


def _numeric_matches(value: Any, expected: Any) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    return math.isfinite(value) and value == expected


def _is_exact_integer(value: Any) -> bool:
    return type(value) is int


def _validate_causal_calibration_metadata(
    config: dict[str, Any], calibration: dict[str, Any] | None, rows: list[dict[str, Any]]
) -> None:
    search = config["practice_search"]
    required_search_keys = {
        "start_ns",
        "deadline_ns",
        "minimum_range_degrees",
        "endpoint_fraction",
    }
    if type(search) is not dict or set(search) != required_search_keys:
        raise ValueError("practice_search must have exactly the causal search fields")
    start_ns, deadline_ns = search["start_ns"], search["deadline_ns"]
    if not _is_exact_integer(start_ns) or not _is_exact_integer(deadline_ns):
        raise ValueError("practice_search start_ns and deadline_ns must be integers")
    if start_ns < 0 or deadline_ns <= start_ns:
        raise ValueError("practice_search bounds are invalid")
    for key, upper_bound in (("minimum_range_degrees", 180.0), ("endpoint_fraction", 0.5)):
        value = search[key]
        if not _numeric_matches(value, value) or not (0.0 < value < upper_bound):
            raise ValueError(f"practice_search.{key} is invalid")
    if calibration is None or type(calibration) is not dict:
        raise ValueError("config declares practice_search but the first prediction row lacks calibration metadata")
    required_calibration_keys = {
        "profile",
        "start_ns",
        "deadline_ns",
        "end_ns",
        "minimum_range_degrees",
        "endpoint_fraction",
        "arms_down_degrees",
        "arms_up_degrees",
    }
    if set(calibration) != required_calibration_keys:
        raise ValueError("causal calibration metadata fields do not exactly match the profile")
    if calibration["profile"] != "causal_first_supported_return_v1":
        raise ValueError("calibration.profile does not match the causal search profile")
    for key in ("start_ns", "deadline_ns"):
        if not _is_exact_integer(calibration[key]) or calibration[key] != search[key]:
            raise ValueError(f"calibration.{key} must exactly match practice_search")
    end_ns = calibration["end_ns"]
    if not _is_exact_integer(end_ns) or not (start_ns <= end_ns <= deadline_ns):
        raise ValueError("calibration.end_ns must be an input timestamp within search bounds")
    if not any(type(row.get("timestamp_ns")) is int and row["timestamp_ns"] == end_ns for row in rows):
        raise ValueError("calibration.end_ns is not present in the prediction input rows")
    for key in ("minimum_range_degrees", "endpoint_fraction"):
        if not _numeric_matches(calibration[key], search[key]):
            raise ValueError(f"calibration.{key} must exactly match practice_search")
    arms_up = config.get("arms_up_degrees")
    arms_down = calibration["arms_down_degrees"]
    if not _numeric_matches(calibration["arms_up_degrees"], arms_up):
        raise ValueError("calibration.arms_up_degrees must exactly match config.arms_up_degrees")
    if not _numeric_matches(arms_down, arms_down) or not _numeric_matches(arms_up, arms_up):
        raise ValueError("causal calibration thresholds must be finite numbers")
    if not (0.0 <= arms_down < arms_up):
        raise ValueError("calibration.arms_down_degrees must be in [0, arms_up_degrees)")
    for row in rows:
        timestamp = row.get("timestamp_ns")
        if type(timestamp) is not int:
            raise ValueError("causal prediction rows require integer timestamp_ns")
        expected_evaluation = timestamp > end_ns
        if row.get("evaluation") is not expected_evaluation:
            raise ValueError("row evaluation flag does not match the causal practice boundary")
        if not expected_evaluation:
            if row.get("reason") != "CALIBRATION_PRACTICE":
                raise ValueError("causal practice rows must be marked CALIBRATION_PRACTICE")
            if row.get("count") != 0 or row.get("counted") is not False:
                raise ValueError("causal practice rows must have count=0 and counted=False")


def validate_calibration_metadata(
    config: dict[str, Any], calibration: dict[str, Any] | None, rows: list[dict[str, Any]]
) -> None:
    """Reject inconsistent/spoofed calibration metadata before anything is scored or written.

    Does not (and must not) infer an expected arms_down_degrees from evaluation data; it only
    checks that the Kotlin-recorded first-row metadata self-consistently matches the config that
    was supposedly used to produce it.
    """
    practice = config.get("practice_window")
    has_practice = "practice_window" in config
    has_search = "practice_search" in config
    if any("calibration" in row for row in rows[1:]):
        raise ValueError("calibration metadata cannot appear on any row after the first")
    if has_practice and has_search:
        raise ValueError("practice_window and practice_search are mutually exclusive")
    if has_search:
        _validate_causal_calibration_metadata(config, calibration, rows)
        return
    if practice is None:
        if calibration is not None:
            raise ValueError("calibration metadata present but config has no practice_window")
        return
    if calibration is None:
        raise ValueError(
            "config declares practice_window but the first prediction row has no "
            "calibration metadata; refusing to silently score the whole clip"
        )
    if calibration.get("profile") != "supervised_arms_down_projected_range_v1":
        raise ValueError("calibration.profile does not match the expected supervised profile")
    for key in ("start_ns", "end_ns", "minimum_range_degrees", "endpoint_fraction"):
        if not _numeric_matches(calibration.get(key), practice[key]):
            raise ValueError(f"calibration.{key} does not numerically match the configured practice_window")
    if not _numeric_matches(calibration.get("arms_up_degrees"), config["arms_up_degrees"]):
        raise ValueError("calibration.arms_up_degrees must exactly match config.arms_up_degrees")
    arms_down = calibration.get("arms_down_degrees")
    arms_up = calibration["arms_up_degrees"]
    if isinstance(arms_down, bool) or not isinstance(arms_down, (int, float)):
        raise ValueError(  # noqa: TRY004 - uniform invalid external-record error
            "calibration.arms_down_degrees must be finite and in [0, arms_up)"
        )
    if not math.isfinite(arms_down) or not (0 <= arms_down < arms_up):
        raise ValueError("calibration.arms_down_degrees must be finite and in [0, arms_up)")
    end_ns = calibration["end_ns"]
    for row in rows:
        expected_evaluation = row["timestamp_ns"] > end_ns
        if row.get("evaluation") is not expected_evaluation:
            raise ValueError("row evaluation flag does not match the practice/evaluation boundary")
        if not expected_evaluation and (row.get("count") != 0 or row.get("counted") is not False):
            raise ValueError("practice rows must have count=0 and counted=False")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--predictions", type=Path)
    parser.add_argument("--run-kotlin", action="store_true")
    parser.add_argument("--labels", type=Path)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--corpus-manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    metadata = load_json(args.cache / "metadata.json")
    verify_cache(metadata, args.video, args.model, args.cache / "poses.jsonl")
    verify_corpus(
        metadata["source"],
        load_json(args.corpus_manifest) if args.corpus_manifest else None,
    )
    if args.run_kotlin:
        predictions = private_output(args.output.with_suffix(".predictions.jsonl"))
        if predictions.exists() or predictions.with_suffix(".run.json").exists():
            raise ValueError("run output exists; use a new run name")
        predictions.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        binding = {
            "config_sha256": sha256(args.config),
            "poses_sha256": sha256(args.cache / "poses.jsonl"),
        }
        harness = Path(__file__).resolve().parents[1] / "apps/diagnostic-harness"
        snapshot = engine_snapshot(harness)
        command = [
            str(harness / "gradlew"),
            "--no-daemon",
            ":shared:replayJumpingJackPose",
            f"-PposePath={(args.cache / 'poses.jsonl').resolve()}",
            f"-PjumpingJackConfigPath={args.config.resolve()}",
            f"-PjumpingJackOutputPath={predictions}",
        ]
        started = time.monotonic()
        subprocess.run(command, cwd=harness, check=True, timeout=300)
        elapsed = time.monotonic() - started
        if engine_snapshot(harness) != snapshot:
            raise ValueError(
                "Kotlin source changed during replay; rerun against a stable candidate"
            )
        binding["predictions_sha256"] = sha256(predictions)
        verify_run(binding, args.config, args.cache / "poses.jsonl", predictions)
        write_json(
            predictions.with_suffix(".run.json"),
            {
                **binding,
                "engine_sources": snapshot,
                "shared_jar_sha256": sha256(
                    harness / "shared/build/libs/shared-jvm.jar"
                ),
                "elapsed_seconds_including_gradle": elapsed,
            },
        )
        args.predictions = predictions
    if args.predictions is None:
        parser.error("provide --run-kotlin or --predictions with its .run.json binding")
    verify_run(
        load_json(args.predictions.with_suffix(".run.json")),
        args.config,
        args.cache / "poses.jsonl",
        args.predictions,
    )
    poses, rows = load_rows(args.cache / "poses.jsonl"), load_rows(args.predictions)
    events = prediction_events(rows, poses)
    admitted_labels = load_json(args.labels) if args.labels else None
    config = load_json(args.config)
    calibration = rows[0].get("calibration") if rows else None
    validate_calibration_metadata(config, calibration, rows)
    if (
        calibration is not None
        and calibration.get("profile") == "causal_first_supported_return_v1"
        and admitted_labels is not None
    ):
        label_events = admitted_labels.get("events")
        if isinstance(label_events, list) and label_events:
            first_event = label_events[0]
            first_start = first_event.get("start_ns") if isinstance(first_event, dict) else None
            if not _numeric_matches(first_start, first_start) or first_start <= calibration["end_ns"]:
                raise ValueError("first retained label must start after causal calibration end")
    result = compare_events(
        events, admitted_labels, sha256(args.video),
        evaluation_after_ns=calibration["end_ns"] if calibration else None,
    )
    effective_config = (
        {**config, "arms_down_degrees": calibration["arms_down_degrees"]}
        if calibration
        else config
    )
    result.update(
        {
            "runtime": metadata,
            "corpus_manifest_sha256": sha256(args.corpus_manifest)
            if args.corpus_manifest
            else None,
            "kotlin_run": load_json(args.predictions.with_suffix(".run.json")),
            "config": config,
            "config_sha256": sha256(args.config),
            "predictions_sha256": sha256(args.predictions),
            "labels_sha256": sha256(args.labels) if args.labels else None,
            "frame_count": len(rows),
            "unusable_frames": sum(row.get("arm_degrees") is None for row in rows),
            "events_ns": events,
            "reason_counts": {
                reason: sum(row.get("reason") == reason for row in rows)
                for reason in sorted({str(row.get("reason")) for row in rows})
            },
            "closing_predicate_support": summarize_closing_predicate_support(
                rows, effective_config, admitted_labels
            ),
        }
    )
    if calibration:
        result["calibration"] = calibration
        result["evaluation_frame_count"] = sum(row.get("evaluation", True) for row in rows)
    write_json(args.output, result)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
