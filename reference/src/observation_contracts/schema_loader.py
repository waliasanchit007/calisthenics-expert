"""Loading of the released JSON Schema observation contracts.

Schemas are the language-neutral source of truth under ``contracts/schema/``. They are
addressed by artifact kind and major version, and released files are immutable, so a loaded
schema can be cached for the life of the process.

The repository root is derived from this module's location rather than from the process
working directory, so validation behaves identically whichever directory a caller runs from.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from functools import cache
from pathlib import Path
from typing import Literal, get_args

from jsonschema import Draft202012Validator

ArtifactKind = Literal[
    "session_manifest",
    "raw_pose_frame",
    "annotation",
    "fixture_metadata",
]

ARTIFACT_KINDS: tuple[ArtifactKind, ...] = get_args(ArtifactKind)

SUPPORTED_MAJOR = 1
"""The one observation-contract major this reader implements."""

SCHEMA_VERSION_FIELDS: Mapping[ArtifactKind, str] = {
    "session_manifest": "session_manifest_schema_version",
    "raw_pose_frame": "raw_pose_frame_schema_version",
    "annotation": "annotation_schema_version",
    "fixture_metadata": "fixture_metadata_schema_version",
}
"""Per-artifact version field. Each artifact family is versioned independently."""


def repository_root() -> Path:
    """Return the repository root resolved from this file, never from the working directory."""
    return Path(__file__).parents[3]


def schema_path(kind: ArtifactKind, major: int = SUPPORTED_MAJOR) -> Path:
    """Return the immutable released schema path for one artifact kind and major version."""
    if kind not in ARTIFACT_KINDS:
        raise ValueError(f"unknown artifact kind: {kind!r}")
    if major != SUPPORTED_MAJOR:
        raise ValueError(
            f"no released {kind} schema for major {major}; "
            f"this reader publishes major {SUPPORTED_MAJOR}"
        )
    return repository_root() / "contracts" / "schema" / f"{kind}.v{major}.schema.json"


@cache
def load_schema(kind: ArtifactKind, major: int = SUPPORTED_MAJOR) -> Mapping[str, object]:
    """Return the parsed Draft 2020-12 schema for one artifact kind and major version."""
    with schema_path(kind, major).open(encoding="utf-8") as handle:
        schema: Mapping[str, object] = json.load(handle)
    return schema


@cache
def schema_validator(kind: ArtifactKind, major: int = SUPPORTED_MAJOR) -> Draft202012Validator:
    """Return a cached Draft 2020-12 validator for one artifact kind and major version."""
    return Draft202012Validator(load_schema(kind, major))
