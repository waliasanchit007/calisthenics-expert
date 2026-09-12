from __future__ import annotations

import importlib.util
import math
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[2]


def _load_module(name: str, path: Path) -> object:
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
    except FileNotFoundError:
        return None
    return module


google_pose_baseline = _load_module("google_pose_baseline", ROOT / "tools/google_pose_baseline.py")
temporal_pose_baseline = _load_module(
    "temporal_pose_baseline", ROOT / "tools/temporal_pose_baseline.py"
)

Point = tuple[float, float, float]


def _pose() -> list[Point]:
    points = [(0.0, 0.0, 0.0) for _ in range(33)]
    points[11] = (0.0, 4.0, 1.0)
    points[12] = (2.0, 4.0, 3.0)
    points[13] = (-1.0, 5.0, 2.0)
    points[14] = (3.0, 5.0, 2.0)
    points[15] = (-2.0, 6.0, 3.0)
    points[16] = (4.0, 6.0, 1.0)
    points[23] = (0.0, 0.0, 0.0)
    points[24] = (2.0, 0.0, 0.0)
    points[25] = (0.0, -4.0, -1.0)
    points[26] = (2.0, -4.0, 3.0)
    points[27] = (0.0, -8.0, -2.0)
    points[28] = (2.0, -8.0, 4.0)
    return points


def _changed(points: list[Point], index: int, delta: Point) -> list[Point]:
    changed = list(points)
    x, y, z = changed[index]
    changed[index] = (x + delta[0], y + delta[1], z + delta[2])
    return changed


def _mirror_features(features: list[Point]) -> list[Point]:
    return [(-x, y, z) for x, y, z in features]


def test_temporal_embedding_keeps_same_endpoint_histories_distinct() -> None:
    first = _pose()
    middle = _changed(first, 15, (0.0, 1.0, 0.0))
    static = temporal_pose_baseline.temporal_embedding([first, first, first])
    moving = temporal_pose_baseline.temporal_embedding([first, middle, first])

    assert static != moving
    assert temporal_pose_baseline.classify_temporal(
        moving,
        [("static", static), ("moving", moving)],
        max_neighbors=1,
        mean_neighbors=1,
    ) == {"moving": 1}


def test_temporal_classifier_distinguishes_reversed_frame_order() -> None:
    first = _pose()
    second = _changed(first, 15, (0.0, 1.0, 0.0))
    forward = temporal_pose_baseline.temporal_embedding([first, second])
    reverse = temporal_pose_baseline.temporal_embedding([second, first])

    assert temporal_pose_baseline.classify_temporal(
        forward,
        [("forward", forward), ("reverse", reverse)],
        max_neighbors=1,
        mean_neighbors=1,
    ) == {"forward": 1}
    assert temporal_pose_baseline.classify_temporal(
        reverse,
        [("forward", forward), ("reverse", reverse)],
        max_neighbors=1,
        mean_neighbors=1,
    ) == {"reverse": 1}


def test_static_temporal_window_has_hand_expected_zero_change_blocks() -> None:
    frame = _pose()
    absolute = google_pose_baseline.embedding(frame)
    features = temporal_pose_baseline.temporal_embedding([frame, frame])
    expected_zero_block = [(0.0, 0.0, 0.0) for _ in range(23)]

    assert features[:23] == absolute
    assert features[23:46] == absolute
    assert features[46:] == expected_zero_block + expected_zero_block


def test_temporal_classifier_accepts_whole_window_x_mirror() -> None:
    frame = _pose()
    changed = _changed(frame, 15, (0.0, 1.0, 0.0))
    query = temporal_pose_baseline.temporal_embedding([frame, changed])
    mirrored = _mirror_features(query)
    competing = list(mirrored)
    competing[-1] = (competing[-1][0], competing[-1][1] + 0.5, competing[-1][2])

    assert temporal_pose_baseline.classify_temporal(
        query,
        [("mirrored", mirrored), ("competing", competing)],
        max_neighbors=1,
        mean_neighbors=1,
    ) == {"mirrored": 1}


def test_temporal_classifier_applies_max_then_mean_bounds() -> None:
    query = [(0.0, 0.0, 0.0), (0.0, 0.0, 0.0)]
    max_friendly = [(0.05, 0.0, 0.0), (0.05, 0.0, 0.0)]
    mean_friendly = [(0.06, 0.0, 0.0), (0.0, 0.0, 0.0)]
    examples = [("max", max_friendly), ("mean", mean_friendly)]

    assert temporal_pose_baseline.classify_temporal(
        query, examples, max_neighbors=1, mean_neighbors=1
    ) == {"max": 1}
    assert temporal_pose_baseline.classify_temporal(
        query, examples, max_neighbors=2, mean_neighbors=1
    ) == {"mean": 1}


@pytest.mark.parametrize("frames", [[], [_pose()], [_pose(), _pose()[:32]]])
def test_temporal_embedding_rejects_missing_or_malformed_frames(
    frames: list[list[Point]],
) -> None:
    with pytest.raises(ValueError):
        temporal_pose_baseline.temporal_embedding(frames)


def test_temporal_embedding_rejects_nonfinite_landmarks() -> None:
    invalid = _pose()
    invalid[15] = (math.nan, 6.0, 3.0)

    with pytest.raises(ValueError):
        temporal_pose_baseline.temporal_embedding([_pose(), invalid])


@pytest.mark.parametrize(
    "query,examples",
    [
        ([], [("label", [(0.0, 0.0, 0.0)])]),
        ([(0.0, 0.0, 0.0)], []),
        ([(0.0, 0.0, 0.0)], [("label", [])]),
        ([(0.0, 0.0, 0.0)], [("label", [(0.0, 0.0, 0.0), (0.0, 0.0, 0.0)])]),
        ([(math.inf, 0.0, 0.0)], [("label", [(0.0, 0.0, 0.0)])]),
        ([(0.0, 0.0, 0.0)], [("", [(0.0, 0.0, 0.0)])]),
    ],
)
def test_temporal_classifier_rejects_invalid_feature_bank(
    query: list[Point], examples: list[tuple[str, list[Point]]]
) -> None:
    with pytest.raises((TypeError, ValueError)):
        temporal_pose_baseline.classify_temporal(query, examples)


@pytest.mark.parametrize(
    "max_neighbors,mean_neighbors",
    [(0, 1), (1, 0), (-1, 1), (1, -1), (True, 1), (1, False), (1.0, 1)],
)
def test_temporal_classifier_rejects_nonpositive_or_noninteger_bounds(
    max_neighbors: object, mean_neighbors: object
) -> None:
    with pytest.raises((TypeError, ValueError)):
        temporal_pose_baseline.classify_temporal(
            [(0.0, 0.0, 0.0)],
            [("label", [(0.0, 0.0, 0.0)])],
            max_neighbors=max_neighbors,
            mean_neighbors=mean_neighbors,
        )


def test_temporal_classifier_keeps_equal_distance_input_order() -> None:
    query = [(1.0, 2.0, 3.0)]
    examples = [("first", query), ("second", query), ("third", query)]

    assert temporal_pose_baseline.classify_temporal(
        query, examples, max_neighbors=2, mean_neighbors=2
    ) == {"first": 1, "second": 1}
