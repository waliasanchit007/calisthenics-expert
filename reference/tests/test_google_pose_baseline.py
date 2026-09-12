from __future__ import annotations

import importlib.util
import math
import sys
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "google_pose_baseline", Path(__file__).parents[2] / "tools/google_pose_baseline.py"
)
assert SPEC and SPEC.loader
google_pose_baseline = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = google_pose_baseline
try:
    SPEC.loader.exec_module(google_pose_baseline)
except FileNotFoundError:

    class _MissingImplementation:
        @staticmethod
        def embedding(points: list[tuple[float, float, float]]) -> list[tuple[float, float, float]]:
            pytest.fail("tools/google_pose_baseline.py is missing")

        @staticmethod
        def classify(*args: object, **kwargs: object) -> dict[str, int]:
            pytest.fail("tools/google_pose_baseline.py is missing")

    google_pose_baseline = _MissingImplementation()

Point = tuple[float, float, float]


def _pose() -> list[Point]:
    points = [(0.0, 0.0, 0.0) for _ in range(33)]
    points[11] = (0.0, 4.0, 1.0)  # left shoulder
    points[12] = (2.0, 4.0, 3.0)  # right shoulder
    points[13] = (-1.0, 5.0, 2.0)  # left elbow
    points[14] = (3.0, 5.0, 2.0)  # right elbow
    points[15] = (-2.0, 6.0, 3.0)  # left wrist
    points[16] = (4.0, 6.0, 1.0)  # right wrist
    points[23] = (0.0, 0.0, 0.0)  # left hip
    points[24] = (2.0, 0.0, 0.0)  # right hip
    points[25] = (0.0, -4.0, -1.0)  # left knee
    points[26] = (2.0, -4.0, 3.0)  # right knee
    points[27] = (0.0, -8.0, -2.0)  # left ankle
    points[28] = (2.0, -8.0, 4.0)  # right ankle
    return points


def _translated_scaled(points: list[Point], scale: float, offset: Point) -> list[Point]:
    return [
        (
            x * scale + offset[0],
            y * scale + offset[1],
            z * scale + offset[2],
        )
        for x, y, z in points
    ]


def _mirror_x(points: list[Point]) -> list[Point]:
    return [(-x, y, z) for x, y, z in points]


def _changed(points: list[Point], index: int, delta: Point) -> list[Point]:
    changed = list(points)
    x, y, z = changed[index]
    changed[index] = (x + delta[0], y + delta[1], z + delta[2])
    return changed


def test_embedding_matches_hand_derived_pinned_google_vectors() -> None:
    assert google_pose_baseline.embedding(_pose()) == [
        (0.0, 40.0, 20.0),
        (-10.0, 10.0, 10.0),
        (10.0, 10.0, -10.0),
        (-10.0, 10.0, 10.0),
        (10.0, 10.0, -10.0),
        (0.0, -40.0, -10.0),
        (0.0, -40.0, 30.0),
        (0.0, -40.0, -10.0),
        (0.0, -40.0, 10.0),
        (-20.0, 20.0, 20.0),
        (20.0, 20.0, -20.0),
        (0.0, -80.0, -20.0),
        (0.0, -80.0, 40.0),
        (-20.0, 60.0, 30.0),
        (20.0, 60.0, 10.0),
        (0.0, -120.0, -30.0),
        (0.0, -120.0, 10.0),
        (-20.0, 60.0, 30.0),
        (20.0, 60.0, 10.0),
        (40.0, 0.0, 0.0),
        (20.0, 0.0, 40.0),
        (60.0, 0.0, -20.0),
        (20.0, 0.0, 60.0),
    ]


def test_embedding_is_translation_and_uniform_scale_invariant() -> None:
    original = google_pose_baseline.embedding(_pose())
    transformed = google_pose_baseline.embedding(
        _translated_scaled(_pose(), 3.5, (100.0, -20.0, 7.0))
    )
    assert transformed == pytest.approx(original)


def test_classifier_matches_an_x_mirrored_query() -> None:
    query = _mirror_x(_pose())
    nonmatching_without_mirror = _changed(query, 15, (0.1, 0.0, 0.0))

    assert google_pose_baseline.classify(
        query,
        [("fixture", _pose()), ("nonmatching_without_mirror", nonmatching_without_mirror)],
        max_neighbors=1,
        mean_neighbors=1,
    ) == {"fixture": 1}


def test_z_weight_prefers_large_z_error_over_small_xy_error() -> None:
    query = _pose()
    z_variant = _changed(query, 15, (0.0, 0.0, 2.0))
    xy_variant = _changed(query, 15, (1.0, 0.0, 0.0))

    assert google_pose_baseline.classify(
        query,
        [("z", z_variant), ("xy", xy_variant)],
        max_neighbors=1,
        mean_neighbors=1,
    ) == {"z": 1}


def test_max_and_mean_neighbor_bounds_are_applied_in_order() -> None:
    query = _pose()
    max_friendly = _changed(query, 11, (0.2, 0.0, 0.0))
    mean_friendly = _changed(query, 13, (0.2, 0.0, 0.0))
    examples = [("max_friendly", max_friendly), ("mean_friendly", mean_friendly)]

    assert google_pose_baseline.classify(query, examples, max_neighbors=1, mean_neighbors=1) == {
        "max_friendly": 1
    }
    assert google_pose_baseline.classify(query, examples, max_neighbors=2, mean_neighbors=1) == {
        "mean_friendly": 1
    }


def test_equal_distance_neighbors_keep_input_order() -> None:
    query = _pose()
    examples = [("first", query), ("second", query), ("third", query)]

    assert google_pose_baseline.classify(query, examples, max_neighbors=2, mean_neighbors=2) == {
        "first": 1,
        "second": 1,
    }


@pytest.mark.parametrize(
    "points",
    [
        [],
        _pose()[:32],
        _pose() + [(0.0, 0.0, 0.0)],
        [(0.0, 0.0, 0.0)] * 33,
        _changed(_pose(), 0, (math.nan, 0.0, 0.0)),
        _changed(_pose(), 0, (math.inf, 0.0, 0.0)),
    ],
)
def test_embedding_rejects_no_pose_wrong_size_nonfinite_and_degenerate_pose(
    points: list[Point],
) -> None:
    with pytest.raises(ValueError):
        google_pose_baseline.embedding(points)


def test_classifier_rejects_invalid_query_and_example_pose() -> None:
    with pytest.raises(ValueError):
        google_pose_baseline.classify([], [])
    with pytest.raises(ValueError):
        google_pose_baseline.classify(_pose(), [("invalid", _pose()[:32])])


@pytest.mark.parametrize(
    "max_neighbors,mean_neighbors",
    [(0, 1), (1, 0), (-1, 1), (1, -1)],
)
def test_classifier_rejects_nonpositive_neighbor_bounds(
    max_neighbors: int, mean_neighbors: int
) -> None:
    with pytest.raises(ValueError):
        google_pose_baseline.classify(
            _pose(),
            [("fixture", _pose())],
            max_neighbors=max_neighbors,
            mean_neighbors=mean_neighbors,
        )
