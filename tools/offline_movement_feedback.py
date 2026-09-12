"""Local, exploratory video feedback. Labels and detector inputs remain separate.

Media dependencies are optional and imported only by extraction/overlay commands.
Desktop MediaPipe is a reference runtime, not evidence of Android equivalence.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import os
import subprocess
import time
from collections.abc import Iterable
from fractions import Fraction
from itertools import pairwise
from pathlib import Path
from typing import Any


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def private_output(path: Path) -> Path:
    path = path.resolve()
    if any((parent / ".git").exists() for parent in (path, *path.parents)):
        raise ValueError("private corpus/output must remain outside every Git worktree")
    return path


def integer_ns(value: Any) -> int:
    if type(value) is not int or value < 0:
        raise ValueError("timestamp must be a nonnegative integer")
    return value


def pts_ns(pts: int | None, time_base: Fraction) -> int:
    if pts is None or time_base <= 0:
        raise ValueError(
            "missing PTS or invalid timebase; do not substitute frame index/FPS"
        )
    return integer_ns(int(pts * time_base * 1_000_000_000))


def ordered_times(values: list[Any]) -> list[int]:
    times = [integer_ns(value) for value in values]
    if any(b <= a for a, b in pairwise(times)):
        raise ValueError("timestamps must be strictly increasing")
    return times


def compare_events(
    predictions: list[int], labels: dict[str, Any] | None, video_sha256: str,
    *, evaluation_after_ns: int | None = None,
) -> dict[str, Any]:
    predictions = ordered_times(predictions)
    if evaluation_after_ns is not None:
        integer_ns(evaluation_after_ns)
        if any(t <= evaluation_after_ns for t in predictions):
            raise ValueError("practice predictions cannot be scored as evaluation")
    if labels is None:
        return {"status": "UNLABELLED", "predicted": len(predictions)}
    if (
        labels.get("version") != 1
        or labels.get("video_sha256") != video_sha256
        or labels.get("annotation_status") != "visually_reviewed"
        or not labels.get("annotation_method")
    ):
        raise ValueError(
            "labels require matching video hash and independent reviewed provenance"
        )
    intervals = labels.get("events")
    if not isinstance(intervals, list):
        raise ValueError(  # noqa: TRY004 - uniform invalid external-record error
            "labels require explicit events (empty means reviewed zero events)"
        )
    previous_end = -1
    for interval in intervals:
        start, end = integer_ns(interval["start_ns"]), integer_ns(interval["end_ns"])
        if evaluation_after_ns is not None and start <= evaluation_after_ns:
            raise ValueError("labels overlap practice; provide separately versioned evaluation-only labels")
        if end < start or start <= previous_end:
            raise ValueError("annotation intervals must be ordered and nonoverlapping")
        previous_end = end
    matches: list[dict[str, int]] = []
    extras: list[int] = []
    matched: set[int] = set()
    for prediction in predictions:
        eligible = next(
            (
                i
                for i, interval in enumerate(intervals)
                if i not in matched
                and interval["start_ns"] <= prediction <= interval["end_ns"]
            ),
            None,
        )
        if eligible is None:
            extras.append(prediction)
        else:
            matched.add(eligible)
            matches.append({"event_index": eligible, "prediction_ns": prediction})
    missed = [i for i in range(len(intervals)) if i not in matched]
    diagnostics = []
    for i in missed:
        start, end = intervals[i]["start_ns"], intervals[i]["end_ns"]
        nearest = min(
            predictions, key=lambda t: max(start - t, t - end, 0), default=None
        )
        diagnostics.append(
            {
                "event_index": i,
                "nearest_prediction_ns": nearest,
                "distance_to_window_ns": max(start - nearest, nearest - end, 0)
                if nearest is not None
                else None,
            }
        )
    return {
        "status": "EXPLORATORY_COMPARISON",
        "annotation_method": labels["annotation_method"],
        "predicted": len(predictions),
        "annotated": len(intervals),
        "matched": len(matches),
        "missed": len(missed),
        "extra": len(extras),
        "precision": len(matches) / len(predictions) if predictions else None,
        "recall": len(matches) / len(intervals) if intervals else None,
        "matches": matches,
        "missed_event_indices": missed,
        "extra_timestamps_ns": extras,
        "miss_diagnostics": diagnostics,
        "claim": "candidate motion cycles, not strict repetition or form validity",
    }


def diagnose_landmark_reason(
    landmarks: list[dict[str, Any]],
    joint_indices: tuple[int, ...],
    minimum_confidence: float,
) -> dict[str, Any]:
    """Explain, from raw cached landmarks, exactly why a movement's shared joint-validity check
    would reject a frame, at the precision the Kotlin candidate engines deliberately do not report.

    Squat/Lunge/PlankCycleExperiment's `measure*` functions all share one ordered check (index
    validity -> duplicate index -> missing joint -> finite-and-in-[0,1] coordinate -> confidence)
    and collapse every coordinate failure -- a NaN/Inf value and a finite value just outside the
    normalized [0, 1] frame are geometrically very different (a decode/model defect vs. a joint
    that briefly left the visible frame during fast motion) -- into one `INVALID_LANDMARK` reason
    with no joint index. That coarseness is intentional in the engine (it keeps the state machine's
    behavior simple and deterministic); this is a report-level, read-only re-derivation of the same
    ordered check against the cached raw landmarks, for evidence/diagnostics only. It must never be
    used to change engine behavior, and mirrors the engine's check order exactly so its verdict
    frontier (usable vs not) always agrees with the Kotlin engine's.
    """
    by_index: dict[int, dict[str, Any]] = {}
    for landmark in landmarks:
        index = landmark["index"]
        if index < 0:
            return {
                "reason": "INVALID_LANDMARK",
                "joint_index": index,
                "cause": "negative_index",
            }
        if index in by_index:
            return {
                "reason": "DUPLICATE_LANDMARK_INDEX",
                "joint_index": index,
                "cause": "duplicate_index",
            }
        by_index[index] = landmark
    for index in joint_indices:
        if index not in by_index:
            return {
                "reason": "MISSING_LANDMARK",
                "joint_index": index,
                "cause": "missing",
            }
    for index in joint_indices:
        landmark = by_index[index]
        for axis in ("x", "y", "z"):
            value = landmark[axis]
            if not math.isfinite(value):
                return {
                    "reason": "INVALID_LANDMARK",
                    "joint_index": index,
                    "cause": f"nonfinite_{axis}",
                    "value": value,
                }
        for axis in ("x", "y"):
            value = landmark[axis]
            if not (0.0 <= value <= 1.0):
                return {
                    "reason": "INVALID_LANDMARK",
                    "joint_index": index,
                    "cause": f"{axis}_out_of_view",
                    "value": value,
                }
    for index in joint_indices:
        landmark = by_index[index]
        visibility, presence = landmark.get("visibility"), landmark.get("presence")
        for name, value in (("visibility", visibility), ("presence", presence)):
            if (
                value is None
                or not math.isfinite(value)
                or not (0.0 <= value <= 1.0)
                or value < minimum_confidence
            ):
                return {
                    "reason": "LOW_CONFIDENCE",
                    "joint_index": index,
                    "cause": name,
                    "visibility": visibility,
                    "presence": presence,
                }
    return {"reason": "NONE"}


def diagnose_unusable_frames(
    poses: list[dict[str, Any]],
    joint_indices: tuple[int, ...],
    minimum_confidence: float,
) -> list[dict[str, Any]]:
    """Scan a cached pose sequence and report only the frames a shared joint-validity check would
    reject, each with its precise cause. See `diagnose_landmark_reason` for scope/limits."""
    diagnoses = []
    for pose in poses:
        landmarks = pose["landmarks"]
        if not landmarks:
            diagnoses.append(
                {"timestamp_ns": pose["timestamp_ns"], "reason": "NO_POSE"}
            )
            continue
        diagnosis = diagnose_landmark_reason(
            landmarks, joint_indices, minimum_confidence
        )
        if diagnosis["reason"] != "NONE":
            diagnoses.append({"timestamp_ns": pose["timestamp_ns"], **diagnosis})
    return diagnoses


def validate_splits(clips: list[dict[str, Any]]) -> None:
    ids: set[str] = set()
    groups: dict[str, str] = {}
    hashes: dict[str, str] = {}
    for clip in clips:
        identifier, group, split, digest = (
            clip[k] for k in ("id", "group", "split", "video_sha256")
        )
        if identifier in ids or not identifier or not group:
            raise ValueError("duplicate clip ID or missing group")
        ids.add(identifier)
        if split not in ("development", "held_out"):
            raise ValueError("unknown split")
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("invalid video hash")
        for value, mapping, name in (
            (group, groups, "group"),
            (digest, hashes, "hash"),
        ):
            if value in mapping and mapping[value] != split:
                raise ValueError(f"cross-split {name} leakage: {value}")
            mapping[value] = split


def verify_corpus(
    source: dict[str, Any], manifest: list[dict[str, Any]] | None
) -> None:
    validate_splits([source])
    if manifest is None:
        if source["split"] == "held_out":
            raise ValueError("held-out replay requires the complete corpus manifest")
        return
    validate_splits(manifest)
    matches = [clip for clip in manifest if clip["id"] == source["id"]]
    if len(matches) != 1 or any(
        matches[0][key] != source[key] for key in ("group", "split", "video_sha256")
    ):
        raise ValueError("current source does not match corpus manifest")


def prediction_events(
    rows: list[dict[str, Any]], poses: list[dict[str, Any]]
) -> list[int]:
    if not rows or len(rows) != len(poses):
        raise ValueError("prediction/pose rows must be nonempty and one-to-one")
    times = ordered_times([row["timestamp_ns"] for row in rows])
    if times != ordered_times([row["timestamp_ns"] for row in poses]):
        raise ValueError("prediction timestamps do not match pose cache")
    events: list[int] = []
    previous = 0
    for row in rows:
        if type(row["counted"]) is not bool or type(row["count"]) is not int:
            raise ValueError("count fields have invalid types")
        expected = previous + int(row["counted"])
        if row["count"] != expected:
            raise ValueError("count does not agree with event sequence")
        previous = expected
        if row["counted"]:
            events.append(row["timestamp_ns"])
    return events


def verify_cache(
    metadata: dict[str, Any], video: Path, model: Path, poses: Path
) -> None:
    if metadata.get("coordinate_space") != "upright_source_normalized":
        raise ValueError("unsupported pose coordinate convention")
    for key, path in (("video", video), ("model", model), ("poses", poses)):
        if metadata.get(f"{key}_sha256") != sha256(path):
            raise ValueError(f"{key} hash does not match cache metadata")


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
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/SquatCycleExperiment.kt",
        "shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/ReplaySquatPose.kt",
        "shared/build.gradle.kts",
    )
    return {name: sha256(harness / name) for name in relative_paths}


def write_overlay(
    target: Path,
    video: Path,
    poses: list[dict[str, Any]],
    rows: list[dict[str, Any]],
    report: dict[str, Any],
) -> None:
    prediction_events(rows, poses)
    target = private_output(target)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    data = json.dumps(
        {"poses": poses, "rows": rows, "report": report}, allow_nan=False
    ).replace("</", "<\\/")
    source = html.escape(os.path.relpath(video.resolve(), target.parent), quote=True)
    document = """<!doctype html><meta charset="utf-8"><title>Offline squat feedback</title>
<style>body{background:#111820;color:#e8eef2;font:16px system-ui;margin:24px auto;max-width:1000px}
h1{font-size:26px}p{color:#b8c8d4}.viewer{position:relative}video{width:100%;display:block}
canvas{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}
pre{white-space:pre-wrap;background:#1e2b36;padding:16px;border-radius:10px}
button{background:#24384a;color:white;border:1px solid #60809a;padding:8px;margin:4px;cursor:pointer}</style>
<h1>Squat replay / developer feedback</h1><p>Local prerecorded video · desktop pose reference · shared Kotlin candidate cycles. Not a form verdict.</p>
<div class="viewer"><video id="video" controls src="VIDEO_SOURCE"></video><canvas id="overlay"></canvas></div>
<pre id="state"></pre><div id="events"></div><details><summary>Evidence and comparison</summary><pre id="report"></pre></details>
<script id="data" type="application/json">DATA_JSON</script><script>
const d=JSON.parse(document.querySelector('#data').textContent), v=document.querySelector('#video'),
c=document.querySelector('#overlay'), ctx=c.getContext('2d'), s=document.querySelector('#state');
document.querySelector('#report').textContent=JSON.stringify(d.report,null,2);
const origin=d.poses[0].timestamp_ns;
for(const row of d.rows.filter(r=>r.counted)){
 const b=document.createElement('button'); b.textContent='Candidate '+row.count+' · '+((row.timestamp_ns-origin)/1e9).toFixed(2)+'s';
 b.onclick=()=>{v.currentTime=Math.max(0,(row.timestamp_ns-origin)/1e9-0.6);}; document.querySelector('#events').append(b);
}
function draw(){
 const time=origin+v.currentTime*1e9; let lo=0,hi=d.poses.length-1;
 while(lo<hi){const m=Math.ceil((lo+hi)/2);if(d.poses[m].timestamp_ns<=time)lo=m;else hi=m-1;}
 const pose=d.poses[lo], row=d.rows[lo]; c.width=pose.width;c.height=pose.height;
 ctx.clearRect(0,0,c.width,c.height);ctx.lineWidth=3;ctx.strokeStyle='#54e7b4';
 const pts=new Map(pose.landmarks.map(p=>[p.index,p]));
 for(const [a,b] of [[11,12],[11,23],[12,24],[23,24],[23,25],[25,27],[24,26],[26,28],[11,13],[13,15],[12,14],[14,16]]){
  const x=pts.get(a),y=pts.get(b);if(!x||!y||x.visibility<0.5||y.visibility<0.5)continue;
  ctx.beginPath();ctx.moveTo(x.x*c.width,x.y*c.height);ctx.lineTo(y.x*c.width,y.y*c.height);ctx.stroke();
 }
 s.textContent='Time '+(v.currentTime).toFixed(2)+'s | candidates '+row.count+' | '+row.phase+' | '+row.reason+' | knee '+(row.knee_degrees==null?'unavailable':row.knee_degrees.toFixed(1)+'°');
 requestAnimationFrame(draw);
}draw();</script>"""
    with target.open("x") as output:
        output.write(
            document.replace("VIDEO_SOURCE", source).replace("DATA_JSON", data)
        )
    target.chmod(0o600)


def load_json(path: Path) -> Any:
    return json.loads(
        path.read_text(),
        parse_constant=lambda value: (_ for _ in ()).throw(
            ValueError(f"nonfinite JSON value: {value}")
        ),
    )


def load_rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def write_json(path: Path, value: Any) -> None:
    path = private_output(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with path.open("x") as output:
        output.write(json.dumps(value, indent=2, allow_nan=False) + "\n")
    path.chmod(0o600)


def write_pose_cache(
    directory: Path, frames: Iterable[dict[str, Any]], metadata: dict[str, Any]
) -> None:
    directory = private_output(directory)
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    pose_path = directory / "poses.jsonl"
    previous_ms = -1
    count = 0
    with pose_path.open("x") as output:
        for row in frames:
            timestamp = integer_ns(row["timestamp_ns"])
            if timestamp // 1_000_000 <= previous_ms:
                raise ValueError("duplicate/backwards millisecond inference timestamp")
            previous_ms = timestamp // 1_000_000
            if any(type(row[k]) is not int or row[k] <= 0 for k in ("width", "height")):
                raise ValueError("invalid source dimensions")
            output.write(json.dumps(row, allow_nan=False) + "\n")
            count += 1
    pose_path.chmod(0o600)
    if not count:
        raise ValueError("no decoded frames; cache is not admitted")
    # Completion metadata is last: exceptions never leave an admitted cache.
    write_json(
        directory / "metadata.json",
        {
            **metadata,
            "frame_count": count,
            "poses_sha256": sha256(pose_path),
            "coordinate_space": "upright_source_normalized",
        },
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--predictions", type=Path)
    parser.add_argument("--run-kotlin", action="store_true")
    parser.add_argument("--overlay", type=Path)
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
            ":shared:replaySquatPose",
            f"-PposePath={(args.cache / 'poses.jsonl').resolve()}",
            f"-PsquatConfigPath={args.config.resolve()}",
            f"-PsquatOutputPath={predictions}",
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
    calibration = rows[0].get("calibration")
    result = compare_events(
        events, load_json(args.labels) if args.labels else None, sha256(args.video),
        evaluation_after_ns=calibration["end_ns"] if calibration else None,
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
            "calibration": calibration,
            "evaluation_frame_count": sum(row.get("evaluation", True) for row in rows),
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
    if args.overlay:
        write_overlay(args.overlay, args.video, poses, rows, result)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
