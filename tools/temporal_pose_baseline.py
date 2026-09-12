"""Deterministic offline temporal extension of the Google pose baseline.

This is an explicit preset hypothesis for short-sequence comparison, not a
claimed implementation of Google's upstream algorithm.  Each frame keeps its
23-vector Google embedding, followed by its change from the first frame.  The
change block is a temporal feature, not velocity: it is not divided by FPS.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from typing import TypeAlias

from google_pose_baseline import embedding

Point: TypeAlias = tuple[float, float, float]
Example: TypeAlias = tuple[str, list[Point]]

_AXES_WEIGHTS: Point = (1.0, 1.0, 0.2)


def _point(value: Sequence[float], position: int, name: str) -> Point:
    try:
        if len(value) != 3:
            raise ValueError
        point = (float(value[0]), float(value[1]), float(value[2]))
    except (TypeError, ValueError, IndexError, OverflowError) as error:
        raise ValueError(
            f"{name} point {position} must have three finite coordinates"
        ) from error
    if not all(math.isfinite(coordinate) for coordinate in point):
        raise ValueError(f"{name} point {position} must have three finite coordinates")
    return point


def _validate_features(features: Iterable[Sequence[float]], name: str) -> list[Point]:
    try:
        raw_features = list(features)
    except TypeError as error:
        raise ValueError(f"{name} must be a nonempty feature array") from error
    if not raw_features:
        raise ValueError(f"{name} must be a nonempty feature array")
    return [_point(value, index, name) for index, value in enumerate(raw_features)]


def temporal_embedding(frames: list[list[Point]]) -> list[Point]:
    """Flatten absolute and first-frame-relative embeddings in time order."""

    try:
        raw_frames = list(frames)
    except TypeError as error:
        raise ValueError("frames must contain at least two poses") from error
    if len(raw_frames) < 2:
        raise ValueError("frames must contain at least two poses")

    embeddings = [embedding(frame) for frame in raw_frames]
    first = embeddings[0]
    relative = [
        (current[0] - initial[0], current[1] - initial[1], current[2] - initial[2])
        for current_embedding in embeddings
        for initial, current in zip(first, current_embedding)
    ]
    absolute = [point for frame_embedding in embeddings for point in frame_embedding]
    return absolute + relative


def _neighbor_bound(value: int, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    return value


def _weighted_max_distance(first: list[Point], second: list[Point]) -> float:
    return max(
        max(abs((left[axis] - right[axis]) * _AXES_WEIGHTS[axis]) for axis in range(3))
        for left, right in zip(first, second)
    )


def _weighted_sum_distance(first: list[Point], second: list[Point]) -> float:
    return sum(
        sum(abs((left[axis] - right[axis]) * _AXES_WEIGHTS[axis]) for axis in range(3))
        for left, right in zip(first, second)
    )


def classify_temporal(
    query_features: list[Point],
    examples: list[Example],
    *,
    max_neighbors: int = 30,
    mean_neighbors: int = 10,
) -> dict[str, int]:
    """Vote over already-computed temporal features using bounded MAX then MEAN.

    The query is compared as supplied and once with every X coordinate
    negated.  Mirroring applies to the complete feature array, including both
    temporal blocks.  Equal distances retain example input order.
    """

    max_neighbors = _neighbor_bound(max_neighbors, "max_neighbors")
    mean_neighbors = _neighbor_bound(mean_neighbors, "mean_neighbors")
    query = _validate_features(query_features, "query_features")
    if not examples:
        raise ValueError("examples must be nonempty")

    mirrored_query = [(-x, y, z) for x, y, z in query]
    distances: list[tuple[int, str, list[Point], float]] = []
    for index, example in enumerate(examples):
        try:
            label, raw_features = example
        except (TypeError, ValueError) as error:
            raise ValueError("each example must be a (label, features) pair") from error
        if not isinstance(label, str):
            raise TypeError("example labels must be strings")
        if not label:
            raise ValueError("example labels must be nonempty")
        features = _validate_features(raw_features, f"example {index}")
        if len(features) != len(query):
            raise ValueError("query and example feature arrays must have equal lengths")
        original_max = _weighted_max_distance(query, features)
        mirrored_max = _weighted_max_distance(mirrored_query, features)
        distances.append((index, label, features, min(original_max, mirrored_max)))

    max_stage = sorted(distances, key=lambda item: (item[3], item[0]))[:max_neighbors]
    mean_stage: list[tuple[int, str, float]] = []
    for index, label, features, _ in max_stage:
        original_sum = _weighted_sum_distance(query, features)
        mirrored_sum = _weighted_sum_distance(mirrored_query, features)
        mean_distance = min(original_sum, mirrored_sum) / (len(query) * 2.0)
        mean_stage.append((index, label, mean_distance))

    votes: dict[str, int] = {}
    for _index, label, _distance in sorted(
        mean_stage, key=lambda item: (item[2], item[0])
    )[:mean_neighbors]:
        votes[label] = votes.get(label, 0) + 1
    return votes
