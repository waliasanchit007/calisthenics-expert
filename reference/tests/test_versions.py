import pytest

from observation_contracts.errors import ValidationFailure
from observation_contracts.versions import ArtifactVersion, ensure_supported_major


def test_parses_semantic_version() -> None:
    assert ArtifactVersion.parse("1.2.3") == ArtifactVersion(1, 2, 3)


@pytest.mark.parametrize("value", ["1", "1.2", "v1.2.3", "1.2.3.4", "-1.2.3"])
def test_rejects_non_semantic_versions(value: str) -> None:
    with pytest.raises(ValueError):
        ArtifactVersion.parse(value)


def test_accepts_newer_minor_within_supported_major() -> None:
    assert ensure_supported_major("1.9.0", 1, "raw_pose_frame").minor == 9


def test_rejects_unknown_major_with_stable_error() -> None:
    with pytest.raises(ValidationFailure) as caught:
        ensure_supported_major("2.0.0", 1, "raw_pose_frame")
    assert caught.value.issues[0].code == "VERSION.UNKNOWN_MAJOR"
