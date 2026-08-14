"""Artifact version parsing and the supported-major policy.

Each observation artifact carries its own ``MAJOR.MINOR.PATCH`` version. Readers accept any
newer minor or patch release inside a supported major and reject unknown majors before
processing records.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from observation_contracts.errors import ValidationFailure, ValidationIssue

_SEMANTIC_VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


@dataclass(frozen=True, order=True)
class ArtifactVersion:
    """An artifact's semantic schema version."""

    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, value: str) -> ArtifactVersion:
        """Parse a ``MAJOR.MINOR.PATCH`` string, rejecting anything else with ``ValueError``."""
        match = _SEMANTIC_VERSION.fullmatch(value)
        if match is None:
            raise ValueError(f"not a MAJOR.MINOR.PATCH version: {value!r}")
        return cls(int(match.group(1)), int(match.group(2)), int(match.group(3)))

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


def ensure_supported_major(value: str, supported_major: int, artifact: str) -> ArtifactVersion:
    """Return the parsed version, or raise ``ValidationFailure`` for an unsupported major."""
    version = ArtifactVersion.parse(value)
    if version.major != supported_major:
        raise ValidationFailure(
            [
                ValidationIssue(
                    code="VERSION.UNKNOWN_MAJOR",
                    artifact_path=artifact,
                    artifact_kind=artifact,
                    message=(
                        f"unsupported {artifact} schema major {version.major}; "
                        f"this reader supports major {supported_major}"
                    ),
                    expected=supported_major,
                    actual=version.major,
                )
            ]
        )
    return version
