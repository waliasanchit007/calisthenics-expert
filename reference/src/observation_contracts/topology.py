"""The landmark topologies this reader is able to interpret.

A topology is an ordered landmark vocabulary: a landmark's index is its position in
``landmark_names``. Cardinality belongs to the declared topology and is never a global
constant of the file format, so a stream is judged against the topology its manifest
declares and an unrecognized topology is reported rather than measured against 33.

Registered topologies are immutable. Changing the name or the order of a released topology
is a new topology name, not an edit, because landmark indexes are already recorded in
exported observations.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class LandmarkTopology:
    """One ordered landmark vocabulary that a pose backend can emit."""

    name: str
    landmark_names: tuple[str, ...]

    @property
    def landmark_count(self) -> int:
        """Return how many landmarks a complete array in this topology carries."""
        return len(self.landmark_names)


class UnknownTopologyError(LookupError):
    """Raised when a declared topology is not one this reader can interpret."""

    def __init__(self, name: str) -> None:
        self.name = name
        super().__init__(f"unsupported landmark topology: {name!r}")


MEDIAPIPE_POSE_33_V1 = LandmarkTopology(
    name="mediapipe_pose_33_v1",
    landmark_names=(
        "nose",
        "left_eye_inner",
        "left_eye",
        "left_eye_outer",
        "right_eye_inner",
        "right_eye",
        "right_eye_outer",
        "left_ear",
        "right_ear",
        "mouth_left",
        "mouth_right",
        "left_shoulder",
        "right_shoulder",
        "left_elbow",
        "right_elbow",
        "left_wrist",
        "right_wrist",
        "left_pinky",
        "right_pinky",
        "left_index",
        "right_index",
        "left_thumb",
        "right_thumb",
        "left_hip",
        "right_hip",
        "left_knee",
        "right_knee",
        "left_ankle",
        "right_ankle",
        "left_heel",
        "right_heel",
        "left_foot_index",
        "right_foot_index",
    ),
)
"""The MediaPipe Pose Landmarker vocabulary, in the backend's own emission order."""

_TOPOLOGIES: Mapping[str, LandmarkTopology] = {
    MEDIAPIPE_POSE_33_V1.name: MEDIAPIPE_POSE_33_V1,
}


def get_topology(name: str) -> LandmarkTopology:
    """Return the registered topology, raising :class:`UnknownTopologyError` for any other name."""
    try:
        return _TOPOLOGIES[name]
    except KeyError:
        raise UnknownTopologyError(name) from None


def supported_topology_names() -> tuple[str, ...]:
    """Return every registered topology name in sorted order, for reporting."""
    return tuple(sorted(_TOPOLOGIES))
