"""Private development-only pose classifier comparison; never a rep/form verdict."""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter
from pathlib import Path
from typing import Any

from offline_movement_feedback import (
    integer_ns,
    load_json,
    load_rows,
    ordered_times,
    private_output,
    sha256,
    verify_cache,
    write_json,
)


def pixel_points(row: dict[str, Any], minimum_confidence: float):
    """Use width-scaled X/Z and height-scaled Y; no imputation or pose holding.

    All 33 points affect upstream normalization, so all must pass the explicit
    confidence floor. Pixel units do not establish MediaPipe/ML Kit equivalence.
    """
    if not 0 <= minimum_confidence <= 1 or not math.isfinite(minimum_confidence):
        raise ValueError("invalid confidence floor")
    width, height = row.get("width"), row.get("height")
    if any(type(value) is not int or value <= 0 for value in (width, height)):
        raise ValueError("invalid source dimensions")
    landmarks = row.get("landmarks")
    if not isinstance(landmarks, list) or len(landmarks) != 33:
        return None, "missing_landmarks"
    indexed = {}
    for point in landmarks:
        if not isinstance(point, dict):
            return None, "invalid_landmarks"
        index = point.get("index")
        if type(index) is not int or not 0 <= index < 33 or index in indexed:
            return None, "invalid_landmark_indices"
        for key in ("x", "y", "z", "visibility", "presence"):
            value = point.get(key)
            if (
                isinstance(value, bool)
                or not isinstance(value, (float, int))
                or not math.isfinite(value)
            ):
                return None, "invalid_landmark_value"
        if any(
            not minimum_confidence <= point[key] <= 1
            for key in ("visibility", "presence")
        ):
            return None, "low_confidence"
        indexed[index] = (point["x"] * width, point["y"] * height, point["z"] * width)
    return [indexed[index] for index in range(33)], None


def check_separation(bank_sources: list[dict[str, Any]], query: dict[str, Any]) -> None:
    for source in [*bank_sources, query]:
        if not all(
            isinstance(source.get(key), str) and source[key]
            for key in ("group", "source_sha256", "video_sha256")
        ):
            raise ValueError("explicit source/group identity required")
    for source in bank_sources:
        for key in ("group", "source_sha256", "video_sha256"):
            if source[key] == query[key]:
                raise ValueError(f"example/query overlap: {key}")


def vote_label(votes: dict[str, int]) -> str | None:
    if not votes:
        return None
    winners = [label for label, count in votes.items() if count == max(votes.values())]
    return winners[0] if len(winners) == 1 else None


def read_cache(item: dict[str, Any], model: Path):
    cache = Path(item["cache"])
    metadata = load_json(cache / "metadata.json")
    verify_cache(metadata, Path(item["video"]), model, cache / "poses.jsonl")
    rows = load_rows(cache / "poses.jsonl")
    if not rows or metadata.get("frame_count") != len(rows):
        raise ValueError("cache frame count mismatch")
    ordered_times([row["timestamp_ns"] for row in rows])
    source = metadata["source"]
    if source.get("video_sha256") != metadata["video_sha256"]:
        raise ValueError("source/cache video hash mismatch")
    if not source.get("source_url") or not source.get("license_url"):
        raise ValueError("source/license provenance required")
    return metadata, rows


def build_bank(
    items: list[dict[str, Any]],
    model: Path,
    *,
    minimum_confidence: float,
    sample_period_ns: int,
):
    if not items or integer_ns(sample_period_ns) == 0:
        raise ValueError("nonempty example bank and positive sample period required")
    examples = []
    sources = []
    evidence = []
    for item in items:
        metadata, rows = read_cache(item, model)
        if metadata["source"].get("split") != "development":
            raise ValueError("example bank must use development data only")
        if (
            not isinstance(item.get("annotation_method"), str)
            or not item["annotation_method"]
        ):
            raise ValueError("example annotation provenance required")
        sources.append(metadata["source"])
        segments = item.get("segments")
        if not isinstance(segments, list) or not segments:
            raise ValueError("explicit nonempty example intervals required")
        previous_end = -1
        for segment in segments:
            start, end = integer_ns(segment["start_ns"]), integer_ns(segment["end_ns"])
            if start <= previous_end or end < start or end > rows[-1]["timestamp_ns"]:
                raise ValueError(
                    "example intervals must be ordered, nonoverlapping and in cache"
                )
            previous_end = end
            label = segment.get("label")
            if not isinstance(label, str) or not label.strip():
                raise ValueError("example label required")
            chosen = []
            rejected: Counter[str] = Counter()
            next_time = start
            for row in rows:
                timestamp = row["timestamp_ns"]
                if not start <= timestamp <= end or timestamp < next_time:
                    continue
                next_time = timestamp + sample_period_ns
                points, reason = pixel_points(row, minimum_confidence)
                if points is None:
                    rejected[reason] += 1
                    continue
                examples.append((label, points))
                chosen.append(timestamp)
            if not chosen:
                raise ValueError(f"no usable examples in interval: {label}")
            evidence.append(
                {
                    "label": label,
                    "source": metadata["source"],
                    "poses_sha256": metadata["poses_sha256"],
                    "selected_timestamps_ns": chosen,
                    "rejected": dict(rejected),
                    "annotation_method": item["annotation_method"],
                }
            )
    return examples, sources, evidence


def run_comparison(manifest: Path, output: Path) -> dict[str, Any]:
    # Lazy dependency keeps cache/coordinate validation independently testable.
    import google_pose_baseline as baseline

    output = private_output(output)
    if output.exists():
        raise FileExistsError("use a fresh comparison output directory")
    request_digest = sha256(manifest)
    request = load_json(manifest)
    if type(request.get("version")) is not int or request["version"] != 1:
        raise ValueError("comparison request version must be 1")
    queries = request.get("queries")
    if not isinstance(queries, list) or not queries:
        raise ValueError("nonempty query list required")
    model = Path(request["model"])
    input_paths = {model}
    for item in [*request["bank"], *queries]:
        input_paths.update(
            (
                Path(item["video"]),
                Path(item["cache"]) / "metadata.json",
                Path(item["cache"]) / "poses.jsonl",
            )
        )
    input_hashes = {str(path): sha256(path) for path in input_paths}
    confidence = request["minimum_confidence"]
    examples, sources, evidence = build_bank(
        request["bank"],
        model,
        minimum_confidence=confidence,
        sample_period_ns=request["sample_period_ns"],
    )
    for _, points in examples:
        baseline.embedding(points)
    prepared = []
    for query in queries:
        metadata, rows = read_cache(query, model)
        check_separation(sources, metadata["source"])
        # Previously inspected clips remain development. A separate future
        # frozen-test protocol is needed; this command cannot mint a benchmark.
        if metadata["source"].get("split") != "development":
            raise ValueError("this comparator accepts development queries only")
        prepared.append((query, metadata, rows))
    code_paths = [
        Path(__file__),
        Path(baseline.__file__),
        Path(__file__).with_name("offline_movement_feedback.py"),
    ]
    code = {path.name: sha256(path) for path in code_paths}
    result: dict[str, Any] = {
        "version": 1,
        "status": "DEVELOPMENT_DIAGNOSTIC",
        "request_sha256": request_digest,
        "request": request,
        "code_sha256": code,
        "input_sha256": input_hashes,
        "example_evidence": evidence,
        "bank_class_counts": dict(Counter(label for label, _ in examples)),
        "interpretation": (
            "Closed-set unsmoothed pose votes, not probabilities or rep/form verdicts. "
            "Unknown actions may receive a known label. No app changes. "
            "Disjoint sources do not make previously inspected clips held-out."
        ),
        "queries": [],
    }
    output.mkdir(parents=True, mode=0o700)
    write_json(output / "request.json", request)
    for index, (query, metadata, rows) in enumerate(prepared):
        predictions = []
        for row in rows:
            points, reason = pixel_points(row, confidence)
            votes = {}
            if points is not None:
                try:
                    votes = baseline.classify(points, examples)
                except ValueError as error:
                    # Degenerate finite geometry is missing evidence, not a
                    # forced closest match. Preserve the cause explicitly.
                    reason = f"embedding_rejected:{error}"
            predictions.append(
                {
                    "timestamp_ns": row["timestamp_ns"],
                    "votes": votes,
                    "label": vote_label(votes),
                    "reason": reason,
                }
            )
        prediction_path = output / f"query-{index}.jsonl"
        with prediction_path.open("x") as handle:
            for prediction in predictions:
                handle.write(json.dumps(prediction, allow_nan=False) + "\n")
        prediction_path.chmod(0o600)
        result["queries"].append(
            {
                "source": metadata["source"],
                "runtime": metadata,
                "frame_count": len(rows),
                "predictions_sha256": sha256(prediction_path),
                "predictions_file": prediction_path.name,
                "label_counts": dict(
                    Counter(p["label"] for p in predictions if p["label"])
                ),
                "rejection_counts": dict(
                    Counter(p["reason"] for p in predictions if p["reason"])
                ),
                "tied_frames": sum(
                    bool(p["votes"]) and p["label"] is None for p in predictions
                ),
            }
        )
    # A changed input or code during processing cannot produce a complete summary.
    if request_digest != sha256(manifest) or code != {
        p.name: sha256(p) for p in code_paths
    }:
        raise ValueError("request/code changed during comparison")
    if input_hashes != {str(path): sha256(path) for path in input_paths}:
        raise ValueError("input changed during comparison")
    for item in [*request["bank"], *queries]:
        read_cache(item, model)
    write_json(output / "summary.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_comparison(args.manifest, args.output_dir)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps({"status": result["status"], "queries": len(result["queries"])}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
