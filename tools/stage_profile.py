#!/usr/bin/env python3
"""Read the per-stage latency profile out of a viewer feedback trace.

Usage: stage_profile.py <session-dir-or-trace.jsonl>

The reading rules below are FIXED IN ADVANCE (ledger Update 50) so the first real profile is not
interpreted after the fact. Two different questions, kept apart deliberately:

  LATENCY  - where does capture-to-result go? The five stages partition it exactly.
  RATE     - what limits the pose rate? Only one frame is in flight at a time, so the per-frame
             BUSY time (admission + preparation + submit gap + inference) caps throughput.
             capture_to_offer is camera-pipeline latency running concurrently with our work: it
             delays every result but does not by itself lower the rate.

Verdicts:
  * a stage "dominates" the non-inference latency if its median is >= 50% of
    (median total - median inference).
  * busy-bound if the observed pose interval (1000 / pose rate) is at most median(busy) plus one
    offer interval (1000 / offer rate), with 5% tolerance: after a completion the pipeline waits at
    most one camera frame for the next offer. Otherwise the rate is limited upstream of our work
    and shrinking busy time will not raise it.
  * 20 fps is reachable WITH ONE FRAME IN FLIGHT only if median(busy) <= 50 ms and the offer rate
    >= 20 fps.
  * overlap ceiling: if frame N+1 is prepared while frame N infers, the rate is capped by the
    slower of the two halves instead of their sum: 1000 / max(median(admission + preparation +
    submit gap), median(inference)). This is the number step 4 is designing towards.

Trace rows are sampled every 100 ms, so above ~10 fps the stage statistics come from a sample of
completed frames, not all of them. Rates are always taken from the cumulative counters instead.

OVERLAPPED sessions (ADR-0007, `pipeline_mode` on every row) are judged differently, because the
serial rules above assume one frame is prepared and inferred before the next is admitted:
  * the ceiling is 1000 / max(median(front half), median(inference)) and the session is bound by
    whichever of the two is slower;
  * `submit_gap_ms` includes the mailbox wait, so the partition still has to sum exactly;
  * `superseded`, `analysis_late`, `analysis_expired` and `completions_dropped` are reported, because
    they are what the two ADR-0007 decisions cost.
"""
from __future__ import annotations
import json, statistics, sys
from pathlib import Path

STAGES = ["capture_to_offer_ms", "admission_ms", "preparation_ms", "submit_gap_ms", "inference_ms"]
BUSY = ["admission_ms", "preparation_ms", "submit_gap_ms", "inference_ms"]


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))]


def main(arg: str) -> int:
    path = Path(arg)
    trace = path / "trace.jsonl" if path.is_dir() else path
    rows = [json.loads(l) for l in trace.open() if l.strip()]
    samples = [r for r in rows if r.get("event") == "SAMPLE"]
    if not samples:
        print("no SAMPLE rows"); return 1

    # One entry per COMPLETED frame: rows repeat the latest completion until the next one lands.
    frames, seen = [], set()
    for r in samples:
        key = r.get("observation_capture_ns")
        if key is None or key in seen or r.get("capture_to_result_ms") is None:
            continue
        seen.add(key); frames.append(r)

    first, last = samples[0], samples[-1]
    mode = last.get("pipeline_mode") or "SERIAL (no pipeline_mode field)"
    seconds = (last["elapsed_ms"] - first["elapsed_ms"]) / 1000.0
    done = last["completed"] - first["completed"]
    sup = lambda r: r.get("superseded") or 0
    offers = (last["submitted"] + last["pipeline_skipped"] + sup(last)) - \
             (first["submitted"] + first["pipeline_skipped"] + sup(first))
    pose_fps = done / seconds if seconds > 0 else float("nan")
    offer_fps = offers / seconds if seconds > 0 else float("nan")
    print(f"trace {trace}")
    print(f"  pipeline mode {mode}")
    print(f"  {len(samples)} sampled rows over {seconds:.1f} s, {len(frames)} distinct completed frames")
    print(f"  offer rate {offer_fps:.1f} fps   pose rate {pose_fps:.1f} fps")

    have = [f for f in frames if all(f.get(k) is not None for k in STAGES)]
    if not have:
        print("  stage fields ABSENT - this trace predates the stage instrument (ledger Update 48)")
        return 0

    bad = [f for f in have if abs(sum(f[k] for k in STAGES) - f["capture_to_result_ms"]) > 1e-6]
    print(f"  partition check: {len(have) - len(bad)} of {len(have)} frames sum exactly to capture_to_result_ms")
    if bad:
        print("  PARTITION BROKEN - the instrument is wrong; do not interpret the profile below")

    total = [f["capture_to_result_ms"] for f in have]
    inf = [f["inference_ms"] for f in have]
    non_inf = statistics.median(total) - statistics.median(inf)
    print(f"\n  {'stage':22s} {'median':>8s} {'p90':>8s}  share of non-inference")
    for k in STAGES:
        v = [f[k] for f in have]
        share = "" if k == "inference_ms" else f"{100 * statistics.median(v) / non_inf:5.0f}%" if non_inf > 0 else ""
        print(f"  {k:22s} {statistics.median(v):8.1f} {pct(v, .9):8.1f}  {share}")
    print(f"  {'capture_to_result_ms':22s} {statistics.median(total):8.1f} {pct(total, .9):8.1f}")

    if mode == "OVERLAPPED":
        front = [f["admission_ms"] + f["preparation_ms"] for f in have]
        wait = [f["submit_gap_ms"] for f in have]
        cf, ci = statistics.median(front), statistics.median(inf)
        print(f"\n  front half median {cf:.1f} ms, inference median {ci:.1f} ms, mailbox wait median "
              f"{statistics.median(wait):.1f} ms (p90 {pct(wait, .9):.1f})")
        print(f"  ceiling {1000.0 / max(cf, ci):.1f} fps, {'inference' if ci >= cf else 'preparation'}-bound;"
              f" observed {pose_fps:.1f} fps")
        print(f"  superseded {sup(last) - sup(first)} of {offers} offers"
              f" ({100 * (sup(last) - sup(first)) / max(offers, 1):.1f}%)")
        for k in ("analysis_late", "analysis_expired", "completions_dropped"):
            print(f"  {k:20s} {last.get(k, 'absent')}")
        age = [f["capture_to_result_ms"] for f in have]
        print(f"  capture_to_result p50 {statistics.median(age):.1f} p90 {pct(age, .9):.1f} p99 {pct(age, .99):.1f} ms;"
              f" >= 250 ms: {100 * sum(a >= 250 for a in age) / len(age):.2f}%")
        return 0

    busy = [sum(f[k] for k in BUSY) for f in have]
    ceiling = 1000.0 / statistics.median(busy)
    print(f"\n  busy per frame median {statistics.median(busy):.1f} ms -> throughput ceiling {ceiling:.1f} fps")

    print("\n  VERDICTS (rules fixed in advance, ledger Update 50)")
    dom = [k for k in STAGES if k != "inference_ms" and non_inf > 0
           and statistics.median([f[k] for f in have]) >= .5 * non_inf]
    print(f"  latency: {'dominated by ' + dom[0] if dom else 'DIFFUSE - no stage is >= 50% of the non-inference cost'}")
    pose_interval = 1000.0 / pose_fps
    allowed = (statistics.median(busy) + 1000.0 / offer_fps) * 1.05
    bound = pose_interval <= allowed
    print(f"  rate:    {'BUSY-BOUND' if bound else 'UPSTREAM-BOUND'} - pose interval {pose_interval:.1f} ms vs "
          f"busy + one offer interval {allowed / 1.05:.1f} ms"
          + ("; shrinking our own per-frame work raises the rate" if bound else
             "; the rate is limited before our work, so shrinking busy time will not raise it"))
    front = [f["admission_ms"] + f["preparation_ms"] + f["submit_gap_ms"] for f in have]
    overlap = 1000.0 / max(statistics.median(front), statistics.median(inf))
    print(f"  overlap: ceiling {overlap:.1f} fps if preparation overlaps inference "
          f"(front half {statistics.median(front):.1f} ms, inference {statistics.median(inf):.1f} ms)")
    ok20 = statistics.median(busy) <= 50 and offer_fps >= 20
    print(f"  20 fps:  {'reachable' if ok20 else 'NOT reachable'} with one frame in flight"
          f" (busy {statistics.median(busy):.1f} ms vs 50, offers {offer_fps:.1f} vs 20 fps)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
