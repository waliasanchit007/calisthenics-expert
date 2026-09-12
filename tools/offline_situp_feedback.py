"""Local, exploratory sit-up cycle feedback. Labels and detector inputs remain separate.

The Kotlin candidate emits the same {timestamp_ns, ..., phase, count, reason, counted} row shape
(here with torso_elevation_degrees instead of knee_degrees/arm_degrees) as the squat/lunge/jumping
-jack cycle experiments, so this reuses the squat tool's generic event-count comparison/hashing/
cache/corpus-verification helpers unchanged; only the pieces that differ (which Kotlin source
files/Gradle task/output fields to reference) are redefined here.
"""

from __future__ import annotations

import argparse
import json
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
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/SitupCycleExperiment.kt",
        "shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/ReplaySitupPose.kt",
        "shared/build.gradle.kts",
        # Reused, not authored here: SitupCycleExperiment shares ArmSide from PullUpExperiment.kt
        # and Landmark from ObservationModels.kt; no other experimental parser is reused.
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/PullUpExperiment.kt",
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/contract/ObservationModels.kt",
    )
    return {name: sha256(harness / name) for name in relative_paths}


def load_rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


_SIDE_ADMISSION_KEYS = {
    "profile",
    "start_ns",
    "end_ns",
    "preferred_side",
    "selected_side",
    "minimum_samples",
    "observed_samples",
    "left_usable_samples",
    "right_usable_samples",
}


def validate_side_admission(
    config: dict[str, Any], rows: list[dict[str, Any]], poses: list[dict[str, Any]]
) -> dict[str, Any] | None:
    """Validate the opt-in `side_admission_window` metadata BEFORE any scoring/writing.

    Returns the validated first-row `side_admission` metadata, or None when the config has no
    admission window (in which case no row may carry `side_admission`/`evaluation` fields either).
    """
    if "side_admission_window" not in config:
        if any("side_admission" in row or "evaluation" in row for row in rows):
            raise ValueError(
                "evaluation/side_admission fields present without a configured side_admission_window"
            )
        return None

    raw_window = config["side_admission_window"]
    if not isinstance(raw_window, dict) or set(raw_window) != {"start_ns", "end_ns"}:
        raise ValueError(
            "side_admission_window must be present as exactly {start_ns, end_ns}"
        )
    if type(raw_window["start_ns"]) is not int or type(raw_window["end_ns"]) is not int:
        raise ValueError("side_admission_window bounds must be nonboolean integers")
    if not (0 <= raw_window["start_ns"] < raw_window["end_ns"]):
        raise ValueError("side_admission_window requires 0 <= start_ns < end_ns")
    window = raw_window

    if config.get("side") not in ("LEFT", "RIGHT"):
        raise ValueError("side_admission_window requires a configured LEFT/RIGHT side")
    if (
        type(config.get("minimum_endpoint_samples")) is not int
        or config["minimum_endpoint_samples"] <= 0
    ):
        raise ValueError(
            "side_admission_window requires a positive integer minimum_endpoint_samples"
        )

    if "side_admission" not in rows[0]:
        raise ValueError(
            "configured side_admission_window requires first-row side_admission metadata"
        )
    for row in rows[1:]:
        if "side_admission" in row:
            raise ValueError("side_admission metadata may only appear on the first row")

    metadata = rows[0]["side_admission"]
    if not isinstance(metadata, dict) or set(metadata) != _SIDE_ADMISSION_KEYS:
        raise ValueError("side_admission metadata has the wrong shape")
    if metadata["profile"] != "tracking_only_fixed_side_v1":
        raise ValueError("side_admission.profile must be tracking_only_fixed_side_v1")
    if type(metadata["start_ns"]) is not int or type(metadata["end_ns"]) is not int:
        raise ValueError("side_admission.start_ns/end_ns must be nonboolean integers")
    if (
        metadata["start_ns"] != window["start_ns"]
        or metadata["end_ns"] != window["end_ns"]
    ):
        raise ValueError(
            "side_admission start_ns/end_ns must match the configured window exactly"
        )
    if metadata["preferred_side"] != config["side"]:
        raise ValueError("side_admission.preferred_side must match the configured side")
    if metadata["preferred_side"] not in ("LEFT", "RIGHT") or metadata[
        "selected_side"
    ] not in ("LEFT", "RIGHT"):
        raise ValueError(
            "side_admission preferred_side/selected_side must be LEFT or RIGHT"
        )
    for key in (
        "minimum_samples",
        "observed_samples",
        "left_usable_samples",
        "right_usable_samples",
    ):
        if type(metadata[key]) is not int:
            raise ValueError(f"side_admission.{key} must be a nonboolean integer")

    expected_minimum = max(2, config["minimum_endpoint_samples"])
    if metadata["minimum_samples"] != expected_minimum:
        raise ValueError(
            "side_admission.minimum_samples disagrees with the configured minimum"
        )
    if metadata["observed_samples"] < metadata["minimum_samples"]:
        raise ValueError("side_admission.observed_samples is below minimum_samples")
    actual_window_rows = sum(
        window["start_ns"] <= pose["timestamp_ns"] <= window["end_ns"] for pose in poses
    )
    if metadata["observed_samples"] != actual_window_rows:
        raise ValueError(
            "side_admission.observed_samples does not match the declared setup window"
        )
    for key in ("left_usable_samples", "right_usable_samples"):
        if not (0 <= metadata[key] <= metadata["observed_samples"]):
            raise ValueError(
                f"side_admission.{key} must be within [0, observed_samples]"
            )

    left_all_usable = metadata["left_usable_samples"] == metadata["observed_samples"]
    right_all_usable = metadata["right_usable_samples"] == metadata["observed_samples"]
    if left_all_usable and right_all_usable:
        expected_selected = metadata["preferred_side"]
    elif left_all_usable:
        expected_selected = "LEFT"
    elif right_all_usable:
        expected_selected = "RIGHT"
    else:
        raise ValueError(
            "side_admission claims a selection but neither side is fully usable"
        )
    if metadata["selected_side"] != expected_selected:
        raise ValueError(
            "side_admission.selected_side does not follow the tie/eligibility rule"
        )

    end_ns = window["end_ns"]
    for row in rows:
        if type(row.get("evaluation")) is not bool:
            raise ValueError("every row requires a boolean evaluation flag")
        if row["evaluation"] != (row["timestamp_ns"] > end_ns):
            raise ValueError("evaluation flag must be exactly timestamp_ns > end_ns")
        if not row["evaluation"] and (
            row["count"] != 0
            or row["counted"] is not False
            or row.get("closed_cycle") is not None
        ):
            raise ValueError("prefix rows must stay uncounted with no closed_cycle")

    return metadata


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
            ":shared:replaySitupPose",
            f"-PposePath={(args.cache / 'poses.jsonl').resolve()}",
            f"-PsitupConfigPath={args.config.resolve()}",
            f"-PsitupOutputPath={predictions}",
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
    config_dict = load_json(args.config)
    admission = validate_side_admission(config_dict, rows, poses)
    events = prediction_events(rows, poses)
    result = compare_events(
        events,
        load_json(args.labels) if args.labels else None,
        sha256(args.video),
        evaluation_after_ns=admission["end_ns"] if admission else None,
    )
    result.update(
        {
            "engine": "situp",
            "runtime": metadata,
            "corpus_manifest_sha256": sha256(args.corpus_manifest)
            if args.corpus_manifest
            else None,
            "kotlin_run": load_json(args.predictions.with_suffix(".run.json")),
            "config": config_dict,
            "config_sha256": sha256(args.config),
            "predictions_sha256": sha256(args.predictions),
            "labels_sha256": sha256(args.labels) if args.labels else None,
            "frame_count": len(rows),
            # A frame missing torso_elevation_degrees means the engine had no torso-elevation
            # geometry for that frame (a geometry-availability metric), not that the frame's
            # `reason` was "unusable": several non-NONE reasons (e.g. temporal/state reasons)
            # are legitimate diagnostics on frames that may still carry this geometry.
            "frames_missing_torso_elevation_geometry": sum(
                row.get("torso_elevation_degrees") is None for row in rows
            ),
            "events_ns": events,
            "reason_counts": {
                reason: sum(row.get("reason") == reason for row in rows)
                for reason in sorted({str(row.get("reason")) for row in rows})
            },
        }
    )
    if admission is not None:
        result["side_admission"] = admission
        result["evaluation_frame_count"] = sum(row["evaluation"] for row in rows)
    write_json(args.output, result)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
