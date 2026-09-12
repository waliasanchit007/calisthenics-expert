"""Local, exploratory plank HOLD feedback. Labels and detector inputs remain separate.

Compares closed hold intervals (entry/exit/duration/observed coverage), not rep counts. Reuses
the squat tool's hashing/cache/corpus-verification helpers; the shared Kotlin PlankHoldExperiment
is invoked exactly like the squat cycle experiment, without a Python copy of the hold logic.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import subprocess
import time
from pathlib import Path
from typing import Any

from offline_movement_feedback import (
    load_json,
    ordered_times,
    private_output,
    sha256,
    verify_cache,
    verify_corpus,
    write_json,
)


def compare_holds(
    predicted: list[dict[str, Any]], labels: dict[str, Any] | None, video_sha256: str
) -> dict[str, Any]:
    for hold in predicted:
        if hold["exit_timestamp_ns"] < hold["entry_timestamp_ns"]:
            raise ValueError("predicted hold exit precedes its entry")
        if hold["observed_ns"] > hold["exit_timestamp_ns"] - hold["entry_timestamp_ns"]:
            raise ValueError(
                "predicted observed_ns exceeds its own wall-clock duration"
            )
    ordered_times([hold["entry_timestamp_ns"] for hold in predicted])
    if labels is None:
        return {"status": "UNLABELLED", "predicted_holds": len(predicted)}
    if (
        labels.get("version") != 1
        or labels.get("video_sha256") != video_sha256
        or labels.get("annotation_status") != "visually_reviewed"
        or not labels.get("annotation_method")
    ):
        raise ValueError(
            "labels require matching video hash and independent reviewed provenance"
        )
    intervals = labels.get("intervals")
    if not isinstance(intervals, list):
        raise ValueError(  # noqa: TRY004 - uniform invalid external-record error
            "labels require explicit intervals (empty means reviewed zero-hold clip)"
        )
    previous_end = -1
    for interval in intervals:
        start, end = interval["start_ns"], interval["end_ns"]
        if end <= start or start <= previous_end:
            raise ValueError(
                "label intervals must be ordered, nonoverlapping and nonempty"
            )
        previous_end = end

    def overlap_ns(a_start: int, a_end: int, b_start: int, b_end: int) -> int:
        return max(0, min(a_end, b_end) - max(a_start, b_start))

    label_matches: list[dict[str, Any]] = []
    for index, interval in enumerate(intervals):
        duration = interval["end_ns"] - interval["start_ns"]
        overlapping = [
            hold
            for hold in predicted
            if overlap_ns(
                interval["start_ns"],
                interval["end_ns"],
                hold["entry_timestamp_ns"],
                hold["exit_timestamp_ns"],
            )
            > 0
        ]
        covered = sum(
            overlap_ns(
                interval["start_ns"],
                interval["end_ns"],
                hold["entry_timestamp_ns"],
                hold["exit_timestamp_ns"],
            )
            for hold in overlapping
        )
        label_matches.append(
            {
                "label_index": index,
                "label_duration_ns": duration,
                "overlapping_predicted_holds": len(overlapping),
                "covered_ns": covered,
                "coverage_ratio": covered / duration,
            }
        )
    missed_label_indices = [
        match["label_index"]
        for match in label_matches
        if match["overlapping_predicted_holds"] == 0
    ]

    hold_diagnostics: list[dict[str, Any]] = []
    for hold in predicted:
        entry, exit_ = hold["entry_timestamp_ns"], hold["exit_timestamp_ns"]
        overlapping_labels = [
            i
            for i, interval in enumerate(intervals)
            if overlap_ns(entry, exit_, interval["start_ns"], interval["end_ns"]) > 0
        ]
        outside = (exit_ - entry) - sum(
            overlap_ns(entry, exit_, intervals[i]["start_ns"], intervals[i]["end_ns"])
            for i in overlapping_labels
        )
        hold_diagnostics.append(
            {
                "entry_timestamp_ns": entry,
                "exit_timestamp_ns": exit_,
                "duration_ns": exit_ - entry,
                "observed_ns": hold["observed_ns"],
                "end_reason": hold["end_reason"],
                "overlapping_label_indices": overlapping_labels,
                "unlabelled_ns": outside,
                "claim": "candidate hold interval, not a form-valid or fully-supported plank verdict",
            }
        )
    extra_holds = [h for h in hold_diagnostics if not h["overlapping_label_indices"]]
    censored_hold_count = sum(
        h["end_reason"] == "CENSORED_AT_END_OF_INPUT" for h in hold_diagnostics
    )

    return {
        "status": "EXPLORATORY_COMPARISON",
        "annotation_method": labels["annotation_method"],
        "predicted_holds": len(predicted),
        "annotated_intervals": len(intervals),
        "missed_label_indices": missed_label_indices,
        "label_matches": label_matches,
        "hold_diagnostics": hold_diagnostics,
        "extra_hold_count": len(extra_holds),
        "censored_hold_count": censored_hold_count,
        "claim": "hold interval overlap/coverage, not strict repetition or form validity; a"
        " censored hold's true exit is unknown (input ended while still open), not a"
        " confirmed OUT_OF_BAND/CAPTURE_GAP boundary",
    }


def prediction_holds(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not rows:
        raise ValueError("prediction rows must be nonempty")
    ordered_times([row["timestamp_ns"] for row in rows])
    holds: list[dict[str, Any]] = []
    for row in rows:
        closed = row.get("closed_hold")
        if closed is not None:
            holds.append(closed)
    return holds


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
        "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/experimental/PlankHoldExperiment.kt",
        "shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/ReplayPlankPose.kt",
        "shared/build.gradle.kts",
    )
    return {name: sha256(harness / name) for name in relative_paths}


def load_rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def write_overlay(
    target: Path,
    video: Path,
    poses: list[dict[str, Any]],
    rows: list[dict[str, Any]],
    report: dict[str, Any],
) -> None:
    target = private_output(target)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    data = json.dumps(
        {"poses": poses, "rows": rows, "report": report}, allow_nan=False
    ).replace("</", "<\\/")
    source = html.escape(os.path.relpath(video.resolve(), target.parent), quote=True)
    document = """<!doctype html><meta charset="utf-8"><title>Offline plank feedback</title>
<style>body{background:#111820;color:#e8eef2;font:16px system-ui;margin:24px auto;max-width:1000px}
h1{font-size:26px}p{color:#b8c8d4}.viewer{position:relative}video{width:100%;display:block}
canvas{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}
pre{white-space:pre-wrap;background:#1e2b36;padding:16px;border-radius:10px}
button{background:#24384a;color:white;border:1px solid #60809a;padding:8px;margin:4px;cursor:pointer}</style>
<h1>Plank hold replay / developer feedback</h1><p>Local prerecorded video · desktop pose reference · shared Kotlin candidate hold. Not a form verdict.</p>
<div class="viewer"><video id="video" controls src="VIDEO_SOURCE"></video><canvas id="overlay"></canvas></div>
<pre id="state"></pre><div id="events"></div><details><summary>Evidence and comparison</summary><pre id="report"></pre></details>
<script id="data" type="application/json">DATA_JSON</script><script>
const d=JSON.parse(document.querySelector('#data').textContent), v=document.querySelector('#video'),
c=document.querySelector('#overlay'), ctx=c.getContext('2d'), s=document.querySelector('#state');
document.querySelector('#report').textContent=JSON.stringify(d.report,null,2);
const origin=d.poses[0].timestamp_ns;
for(const row of d.rows.filter(r=>r.closed_hold)){
 const h=row.closed_hold; const b=document.createElement('button');
 b.textContent='Hold '+((h.entry_timestamp_ns-origin)/1e9).toFixed(2)+'s-'+((h.exit_timestamp_ns-origin)/1e9).toFixed(2)+'s';
 b.onclick=()=>{v.currentTime=Math.max(0,(h.entry_timestamp_ns-origin)/1e9-0.6);}; document.querySelector('#events').append(b);
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
 s.textContent='Time '+(v.currentTime).toFixed(2)+'s | state '+row.state+' | '+row.reason+' | body-line '+(row.body_line_degrees==null?'unavailable':row.body_line_degrees.toFixed(1)+'°');
 requestAnimationFrame(draw);
}draw();</script>"""
    with target.open("x") as output:
        output.write(
            document.replace("VIDEO_SOURCE", source).replace("DATA_JSON", data)
        )
    target.chmod(0o600)


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
            ":shared:replayPlankPose",
            f"-PposePath={(args.cache / 'poses.jsonl').resolve()}",
            f"-PplankConfigPath={args.config.resolve()}",
            f"-PplankOutputPath={predictions}",
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
    if len(rows) != len(poses):
        raise ValueError("prediction/pose rows must be one-to-one")
    holds = prediction_holds(rows)
    result = compare_holds(
        holds, load_json(args.labels) if args.labels else None, sha256(args.video)
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
            "unusable_frames": sum(
                row.get("body_line_degrees") is None for row in rows
            ),
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
