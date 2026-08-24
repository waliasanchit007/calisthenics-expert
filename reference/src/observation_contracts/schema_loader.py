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


def load_strict_json(serialized: str) -> object:
    """Parse JSON while rejecting constants outside the JSON grammar."""
    return json.loads(serialized, parse_constant=_reject_non_json_constant)


def _reject_non_json_constant(constant: str) -> object:
    """Raise the standard decoder error for Python-only non-finite constants."""
    raise json.JSONDecodeError(f"invalid JSON constant: {constant}", constant, 0)


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


def preprocessing_vector_schema_path() -> Path:
    """Return the released preprocessing-vector tooling schema path."""
    return repository_root() / "contracts" / "tooling" / "preprocessing_vector.v1.schema.json"


def study_readiness_schema_path() -> Path:
    """Return the released study-readiness tooling schema path."""
    return repository_root() / "contracts" / "tooling" / "study_readiness_manifest.v1.schema.json"


def study_condition_matrix_schema_path() -> Path:
    """Return the released study-condition-matrix tooling schema path."""
    return repository_root() / "contracts" / "tooling" / "study_condition_matrix.v1.schema.json"


@cache
def load_schema(kind: ArtifactKind, major: int = SUPPORTED_MAJOR) -> Mapping[str, object]:
    """Return the parsed Draft 2020-12 schema for one artifact kind and major version."""
    with schema_path(kind, major).open(encoding="utf-8") as handle:
        schema = load_strict_json(handle.read())
    if not isinstance(schema, Mapping):
        raise TypeError(f"schema is not a JSON object: {schema_path(kind, major)}")
    return schema


@cache
def load_preprocessing_vector_schema() -> Mapping[str, object]:
    """Return the parsed released preprocessing-vector tooling schema."""
    path = preprocessing_vector_schema_path()
    schema = load_strict_json(path.read_text(encoding="utf-8"))
    if not isinstance(schema, Mapping):
        raise TypeError(f"schema is not a JSON object: {path}")
    return schema


def _load_tooling_schema(path: Path) -> Mapping[str, object]:
    """Return one parsed tooling schema, preserving the loader's object contract."""
    schema = load_strict_json(path.read_text(encoding="utf-8"))
    if not isinstance(schema, Mapping):
        raise TypeError(f"schema is not a JSON object: {path}")
    return schema


@cache
def load_study_readiness_schema() -> Mapping[str, object]:
    """Return the parsed released study-readiness tooling schema."""
    return _load_tooling_schema(study_readiness_schema_path())


@cache
def load_study_condition_matrix_schema() -> Mapping[str, object]:
    """Return the parsed released study-condition-matrix tooling schema."""
    return _load_tooling_schema(study_condition_matrix_schema_path())


@cache
def schema_validator(kind: ArtifactKind, major: int = SUPPORTED_MAJOR) -> Draft202012Validator:
    """Return a cached Draft 2020-12 validator for one artifact kind and major version."""
    return Draft202012Validator(load_schema(kind, major))


@cache
def preprocessing_vector_schema_validator() -> Draft202012Validator:
    """Return a cached validator for the released preprocessing-vector tooling schema."""
    return Draft202012Validator(load_preprocessing_vector_schema())


@cache
def study_readiness_schema_validator() -> Draft202012Validator:
    """Return a cached validator for the released study-readiness tooling schema."""
    return Draft202012Validator(load_study_readiness_schema())


@cache
def study_condition_matrix_schema_validator() -> Draft202012Validator:
    """Return a cached validator for the released study-condition-matrix tooling schema."""
    return Draft202012Validator(load_study_condition_matrix_schema())
