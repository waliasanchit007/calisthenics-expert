"""Causal short-window development comparison, not an app or exercise verdict."""

from __future__ import annotations

import argparse
import json
import sys
import time
from bisect import bisect_right
from collections import Counter
from collections.abc import Iterator
from itertools import pairwise
from pathlib import Path
from typing import Any, cast

import google_pose_baseline as static
from offline_movement_feedback import (
    integer_ns,
    load_json,
    ordered_times,
    private_output,
    sha256,
    write_json,
)
from offline_pose_comparison import (
    check_separation,
    pixel_points,
    read_cache,
    vote_label,
)

Point = tuple[float, float, float]
Samples = list[tuple[int, list[Point] | None]]


def causal_window(
    samples: Samples, end_ns: int, config: dict[str, int]
) -> tuple[list[list[Point]] | None, list[int], str | None]:
    """Select observed past samples; gaps anywhere in the interval invalidate it."""
    for key in ("window_ns", "grid_ns", "maximum_gap_ns", "sample_tolerance_ns"):
        if integer_ns(config[key]) == 0:
            raise ValueError("window sampling parameters must be positive")
    duration, grid = config["window_ns"], config["grid_ns"]
    if duration % grid or duration < grid:
        raise ValueError("window must be an exact positive multiple of grid")
    integer_ns(end_ns)
    times = ordered_times([t for t, _ in samples])
    start = end_ns - duration
    if not times or start < times[0]:
        return None, [], "warming_up"
    left, right = bisect_right(times, start) - 1, bisect_right(times, end_ns)
    region = samples[left:right]
    if any(points is None for _, points in region) or any(
        b[0] - a[0] >= config["maximum_gap_ns"] for a, b in pairwise(region)
    ):
        return None, [], "tracking_gap"
    indices = [
        bisect_right(times, target) - 1 for target in range(start, end_ns + 1, grid)
    ]
    targets = range(start, end_ns + 1, grid)
    if len(set(indices)) != len(indices) or any(
        target - times[index] > config["sample_tolerance_ns"]
        for target, index in zip(targets, indices)
    ):
        return None, [], "sampling_gap"
    return (
        [cast(list[Point], samples[i][1]) for i in indices],
        [times[i] for i in indices],
        None,
    )


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    paired = [row for row in rows if row["reason"] is None]
    return {
        "attempted_windows": len(rows),
        "evaluated_windows": len(paired),
        "static_labels": dict(Counter(row["static_label"] or "TIE" for row in paired)),
        "temporal_labels": dict(
            Counter(row["temporal_label"] or "TIE" for row in paired)
        ),
        "rejections": dict(Counter(row["reason"] for row in rows if row["reason"])),
        "disagreements": sum(
            row["static_label"] != row["temporal_label"] for row in paired
        ),
    }


def _samples(rows: list[dict[str, Any]], confidence: float) -> Samples:
    result = []
    for row in rows:
        points, _ = pixel_points(row, confidence)
        if points is not None:
            try:
                static.embedding(points)
            except ValueError:
                points = None
        result.append((row["timestamp_ns"], points))
    return result


def _endpoints(samples: Samples, period: int) -> Iterator[int]:
    if integer_ns(period) == 0:
        raise ValueError("endpoint period must be positive")
    next_time = -1
    for timestamp, _ in samples:
        if timestamp >= next_time:
            yield timestamp
            next_time = timestamp + period


def run_temporal(manifest: Path, output: Path) -> dict[str, Any]:
    import temporal_pose_baseline as temporal

    output = private_output(output)
    if output.exists():
        raise FileExistsError("fresh output directory required")
    request = load_json(manifest)
    if type(request.get("version")) is not int or request["version"] != 1:
        raise ValueError("request version must be 1")
    if not request.get("bank") or not request.get("queries"):
        raise ValueError("bank and queries must be nonempty")
    model, confidence, cfg = (
        Path(request["model"]),
        request["minimum_confidence"],
        request["temporal"],
    )
    input_paths = {manifest, model}
    for item in [*request["bank"], *request["queries"]]:
        input_paths.update(
            (
                Path(item["video"]),
                Path(item["cache"]) / "poses.jsonl",
                Path(item["cache"]) / "metadata.json",
            )
        )
    hashes = {str(path): sha256(path) for path in input_paths}
    code_paths = [
        Path(__file__),
        Path(temporal.__file__),
        Path(static.__file__),
        Path(__file__).with_name("offline_pose_comparison.py"),
        Path(__file__).with_name("offline_movement_feedback.py"),
    ]
    code = {path.name: sha256(path) for path in code_paths}
    started = time.monotonic()
    examples, static_examples, sources, evidence = [], [], [], []
    labels = set()
    for item in request["bank"]:
        metadata, rows = read_cache(item, model)
        if metadata["source"].get("split") != "development":
            raise ValueError("bank must be development only")
        if not item.get("annotation_method") or not item.get("segments"):
            raise ValueError("annotated bank intervals required")
        sources.append(metadata["source"])
        samples = _samples(rows, confidence)
        previous = -1
        for segment in item["segments"]:
            lo, hi = integer_ns(segment["start_ns"]), integer_ns(segment["end_ns"])
            label = segment["label"]
            if not isinstance(label, str) or not label.strip():
                raise ValueError("class label required")
            labels.add(label)
            if lo <= previous or hi < lo or hi > samples[-1][0]:
                raise ValueError("invalid/overlapping example intervals")
            previous = hi
            # Crop first: no window may borrow frames from the preceding class.
            region = [(t, p) for t, p in samples if lo <= t <= hi]
            accepted = []
            rejected: Counter[str] = Counter()
            for end in _endpoints(region, request["sample_period_ns"]):
                frames, selected, reason = causal_window(region, end, cfg)
                if reason:
                    rejected[reason] += 1
                    continue
                assert frames is not None
                features = temporal.temporal_embedding(frames)
                examples.append((label, features))
                static_examples.append((label, frames[-1]))
                accepted.append(selected)
            evidence.append(
                {
                    "label": label,
                    "source": metadata["source"],
                    "selected_timestamps_ns": accepted,
                    "rejections": dict(rejected),
                    "annotation_method": item["annotation_method"],
                }
            )
    missing = labels - {label for label, _ in examples}
    if missing or not examples:
        raise ValueError(
            f"no usable windows for required example classes: {sorted(missing)}"
        )
    prepared = []
    for item in request["queries"]:
        metadata, rows = read_cache(item, model)
        check_separation(sources, metadata["source"])
        if metadata["source"].get("split") != "development":
            raise ValueError("queries must remain development evidence")
        prepared.append((metadata, _samples(rows, confidence)))
    output.mkdir(parents=True, mode=0o700)
    write_json(output / "request.json", request)
    result = {
        "version": 1,
        "status": "DEVELOPMENT_DIAGNOSTIC",
        "request": request,
        "input_sha256": hashes,
        "code_sha256": code,
        "bank_class_counts": dict(Counter(label for label, _ in examples)),
        "example_evidence": evidence,
        "queries": [],
        "interpretation": "Paired closed-set development votes, not probabilities, reps or accuracy. No time warping, interpolation, future input, or app changes.",
    }
    for index, (metadata, samples) in enumerate(prepared):
        predictions = []
        for end in _endpoints(samples, request["query_period_ns"]):
            frames, selected, reason = causal_window(samples, end, cfg)
            temporal_votes, static_votes = {}, {}
            if reason is None:
                assert frames is not None
                temporal_votes = temporal.classify_temporal(
                    temporal.temporal_embedding(frames), examples
                )
                static_votes = static.classify(frames[-1], static_examples)
            predictions.append(
                {
                    "timestamp_ns": end,
                    "selected_timestamps_ns": selected,
                    "temporal_votes": temporal_votes,
                    "static_votes": static_votes,
                    "temporal_label": vote_label(temporal_votes),
                    "static_label": vote_label(static_votes),
                    "reason": reason,
                }
            )
        path = output / f"query-{index}.jsonl"
        with path.open("x") as handle:
            for row in predictions:
                handle.write(json.dumps(row, allow_nan=False) + "\n")
        path.chmod(0o600)
        result["queries"].append(
            {
                **summarize(predictions),
                "runtime": metadata,
                "predictions_file": path.name,
                "predictions_sha256": sha256(path),
            }
        )
    if hashes != {str(path): sha256(path) for path in input_paths} or code != {
        p.name: sha256(p) for p in code_paths
    }:
        raise ValueError("inputs or code changed during comparison")
    result["elapsed_seconds"] = time.monotonic() - started
    write_json(output / "summary.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_temporal(args.manifest, args.output_dir)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps({"status": result["status"], "queries": len(result["queries"])}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
