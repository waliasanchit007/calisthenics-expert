"""Local, exploratory lunge cycle feedback. Labels and detector inputs remain separate.

The Kotlin candidate emits the same {timestamp_ns, knee_degrees, phase, count, reason, counted}
row shape as the squat cycle experiment, so this reuses the squat tool's generic
event-count comparison/hashing/cache/corpus-verification helpers unchanged; only the pieces that
differ (which Kotlin source files/Gradle task/output fields to reference) are redefined here.
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
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/LungeCycleExperiment.kt",
        "shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/ReplayLungePose.kt",
        "shared/build.gradle.kts",
    )
    return {name: sha256(harness / name) for name in relative_paths}


def load_rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


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
            ":shared:replayLungePose",
            f"-PposePath={(args.cache / 'poses.jsonl').resolve()}",
            f"-PlungeConfigPath={args.config.resolve()}",
            f"-PlungeOutputPath={predictions}",
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
    result = compare_events(
        events, load_json(args.labels) if args.labels else None, sha256(args.video)
    )
    result.update(
        {
            "runtime": metadata,
            "corpus_manifest_sha256": sha256(args.corpus_manifest)
            if args.corpus_manifest
            else None,
            "kotlin_run": load_json(args.predictions.with_suffix(".run.json")),
            "config": load_json(args.config),
            "config_sha256": sha256(args.config),
            "predictions_sha256": sha256(args.predictions),
            "labels_sha256": sha256(args.labels) if args.labels else None,
            "frame_count": len(rows),
            "unusable_frames": sum(row.get("knee_degrees") is None for row in rows),
            "events_ns": events,
            "reason_counts": {
                reason: sum(row.get("reason") == reason for row in rows)
                for reason in sorted({str(row.get("reason")) for row in rows})
            },
        }
    )
    write_json(args.output, result)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
