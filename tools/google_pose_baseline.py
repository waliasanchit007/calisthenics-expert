"""Bounded stdlib port of Google's pinned pose embedding classifier sample.

Adapted from googlesamples/mlkit at commit
f1047837fe1e02d58063bfc30b830fad23ffbc35, specifically PoseEmbedding.java,
PoseClassifier.java, and Utils.java.  This module accepts the caller's 33
MediaPipe/ML Kit landmarks in common pixel units; it does not infer confidence,
impute missing landmarks, or perform pose detection.

The classifier keeps the upstream two-stage nearest-neighbor comparison and
mirror-query behavior.  Unlike Java's PriorityQueue iteration, equal distances
are resolved by original example order so this offline port is deterministic.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from typing import TypeAlias

Point: TypeAlias = tuple[float, float, float]
Example: TypeAlias = tuple[str, list[Point]]

_LANDMARK_COUNT = 33
_LEFT_SHOULDER = 11
_RIGHT_SHOULDER = 12
_LEFT_ELBOW = 13
_RIGHT_ELBOW = 14
_LEFT_WRIST = 15
_RIGHT_WRIST = 16
_LEFT_HIP = 23
_RIGHT_HIP = 24
_LEFT_KNEE = 25
_RIGHT_KNEE = 26
_LEFT_ANKLE = 27
_RIGHT_ANKLE = 28
_TORSO_MULTIPLIER = 2.5
_AXES_WEIGHTS: Point = (1.0, 1.0, 0.2)
_Endpoint: TypeAlias = int | tuple[int, int]

# The pair list follows PoseEmbedding.getEmbedding exactly, including the
# repeated hip-to-wrist offsets in Google's five-joint group.
_VECTOR_PAIRS: tuple[tuple[_Endpoint, _Endpoint], ...] = (
    # One joint.
    ((_LEFT_HIP, _RIGHT_HIP), (_LEFT_SHOULDER, _RIGHT_SHOULDER)),
    (_LEFT_SHOULDER, _LEFT_ELBOW),
    (_RIGHT_SHOULDER, _RIGHT_ELBOW),
    (_LEFT_ELBOW, _LEFT_WRIST),
    (_RIGHT_ELBOW, _RIGHT_WRIST),
    (_LEFT_HIP, _LEFT_KNEE),
    (_RIGHT_HIP, _RIGHT_KNEE),
    (_LEFT_KNEE, _LEFT_ANKLE),
    (_RIGHT_KNEE, _RIGHT_ANKLE),
    # Two joints.
    (_LEFT_SHOULDER, _LEFT_WRIST),
    (_RIGHT_SHOULDER, _RIGHT_WRIST),
    (_LEFT_HIP, _LEFT_ANKLE),
    (_RIGHT_HIP, _RIGHT_ANKLE),
    # Four joints.
    (_LEFT_HIP, _LEFT_WRIST),
    (_RIGHT_HIP, _RIGHT_WRIST),
    # Five joints.
    (_LEFT_SHOULDER, _LEFT_ANKLE),
    (_RIGHT_SHOULDER, _RIGHT_ANKLE),
    (_LEFT_HIP, _LEFT_WRIST),
    (_RIGHT_HIP, _RIGHT_WRIST),
    # Cross body.
    (_LEFT_ELBOW, _RIGHT_ELBOW),
    (_LEFT_KNEE, _RIGHT_KNEE),
    (_LEFT_WRIST, _RIGHT_WRIST),
    (_LEFT_ANKLE, _RIGHT_ANKLE),
)


def _point(value: Sequence[float], position: int) -> Point:
    try:
        coordinate_count = len(value)
    except TypeError as error:
        raise ValueError(f"point {position} must have exactly three coordinates") from error
    if coordinate_count != 3:
        raise ValueError(f"point {position} must have exactly three coordinates")
    try:
        point = (float(value[0]), float(value[1]), float(value[2]))
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError(f"point {position} must contain finite numbers") from error
    if not all(math.isfinite(coordinate) for coordinate in point):
        raise ValueError(f"point {position} must contain finite numbers")
    return point


def _validate_points(points: Iterable[Sequence[float]], name: str) -> list[Point]:
    try:
        raw_points = list(points)
    except TypeError as error:
        raise ValueError(f"{name} must contain exactly 33 points") from error
    if len(raw_points) != _LANDMARK_COUNT:
        raise ValueError(f"{name} must contain exactly 33 points")
    return [_point(value, index) for index, value in enumerate(raw_points)]


def _average(first: Point, second: Point) -> Point:
    return (
        (first[0] + second[0]) * 0.5,
        (first[1] + second[1]) * 0.5,
        (first[2] + second[2]) * 0.5,
    )


def _subtract(first: Point, second: Point) -> Point:
    """Return ``second - first``, matching Utils.subtract(b, a)."""

    return (second[0] - first[0], second[1] - first[1], second[2] - first[2])


def _multiply(point: Point, scalar: float) -> Point:
    return (point[0] * scalar, point[1] * scalar, point[2] * scalar)


def _pose_size(points: list[Point]) -> float:
    hips_center = _average(points[_LEFT_HIP], points[_RIGHT_HIP])
    shoulders_center = _average(points[_LEFT_SHOULDER], points[_RIGHT_SHOULDER])
    torso_size = math.hypot(
        hips_center[0] - shoulders_center[0], hips_center[1] - shoulders_center[1]
    )
    maximum = torso_size * _TORSO_MULTIPLIER
    for landmark in points:
        distance = math.hypot(hips_center[0] - landmark[0], hips_center[1] - landmark[1])
        maximum = max(maximum, distance)
    if not math.isfinite(maximum) or maximum <= 0.0:
        raise ValueError("pose is degenerate: normalized pose size must be positive")
    return maximum


def _normalize(points: list[Point]) -> list[Point]:
    center = _average(points[_LEFT_HIP], points[_RIGHT_HIP])
    translated = [_subtract(center, point) for point in points]
    scale = 100.0 / _pose_size(translated)
    return [_multiply(point, scale) for point in translated]


def _endpoint(points: list[Point], endpoint: _Endpoint) -> Point:
    if isinstance(endpoint, tuple):
        return _average(points[endpoint[0]], points[endpoint[1]])
    return points[endpoint]


def embedding(points: list[Point]) -> list[Point]:
    """Return Google's 23-vector embedding for one validated 33-point pose."""

    normalized = _normalize(_validate_points(points, "points"))
    return [
        _subtract(_endpoint(normalized, first), _endpoint(normalized, second))
        for first, second in _VECTOR_PAIRS
    ]


def _weighted_max_distance(first: list[Point], second: list[Point]) -> float:
    return max(
        max(
            abs((left[axis] - right[axis]) * _AXES_WEIGHTS[axis])
            for axis in range(3)
        )
        for left, right in zip(first, second)
    )


def _weighted_sum_distance(first: list[Point], second: list[Point]) -> float:
    return sum(
        sum(abs((left[axis] - right[axis]) * _AXES_WEIGHTS[axis]) for axis in range(3))
        for left, right in zip(first, second)
    )


def classify(
    points: list[Point],
    examples: list[Example],
    *,
    max_neighbors: int = 30,
    mean_neighbors: int = 10,
) -> dict[str, int]:
    """Vote for example labels using Google's bounded two-stage classifier.

    The query is compared both as supplied and after negating every X
    coordinate.  Each stage keeps the smallest distances; equal distances
    retain input order.  There is no confidence threshold or unknown class.
    """

    if max_neighbors <= 0 or mean_neighbors <= 0:
        raise ValueError("neighbor bounds must be positive")

    query = _validate_points(points, "points")
    original_embedding = embedding(query)
    mirrored_embedding = embedding([(-x, y, z) for x, y, z in query])

    distances: list[tuple[int, str, list[Point], float]] = []
    for index, example in enumerate(examples):
        try:
            label, sample_points = example
        except (TypeError, ValueError) as error:
            raise ValueError("each example must be a (label, points) pair") from error
        if not isinstance(label, str):
            raise TypeError("example labels must be strings")
        sample_embedding = embedding(_validate_points(sample_points, f"example {index}"))
        original_max = _weighted_max_distance(original_embedding, sample_embedding)
        mirrored_max = _weighted_max_distance(mirrored_embedding, sample_embedding)
        distances.append((index, label, sample_embedding, min(original_max, mirrored_max)))

    max_stage = sorted(distances, key=lambda item: (item[3], item[0]))[:max_neighbors]
    mean_stage: list[tuple[int, str, float]] = []
    for index, label, sample_embedding, _ in max_stage:
        original_sum = _weighted_sum_distance(original_embedding, sample_embedding)
        mirrored_sum = _weighted_sum_distance(mirrored_embedding, sample_embedding)
        mean_distance = min(original_sum, mirrored_sum) / (len(original_embedding) * 2.0)
        mean_stage.append((index, label, mean_distance))

    votes: dict[str, int] = {}
    for _index, label, _ in sorted(mean_stage, key=lambda item: (item[2], item[0]))[:mean_neighbors]:
        votes[label] = votes.get(label, 0) + 1
    return votes
