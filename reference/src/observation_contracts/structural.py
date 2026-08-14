"""Structural validation of observation documents against the released JSON Schemas.

Structural validation answers one question: does this document have the declared shape and
primitive constraints? Sequencing, cross-artifact consistency, topology cardinality, count
identities, and referenced-content integrity are semantic concerns handled elsewhere.

An unknown artifact major is rejected before any structural processing, because a document
written under a later major cannot be meaningfully judged against this reader's schema.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping
from dataclasses import replace
from pathlib import Path
from typing import Any

from jsonschema.exceptions import ValidationError

from observation_contracts.errors import ValidationFailure, ValidationIssue
from observation_contracts.schema_loader import (
    SCHEMA_VERSION_FIELDS,
    SUPPORTED_MAJOR,
    ArtifactKind,
    load_strict_json,
    schema_validator,
)
from observation_contracts.versions import ensure_supported_major

_SCHEMA_INVALID = "SCHEMA.INVALID"
_MAX_CONSTRAINT_CHARS = 120


def json_pointer(parts: Iterable[object]) -> str:
    """Return the RFC 6901 pointer for a path, escaping ``~`` as ``~0`` and ``/`` as ``~1``."""
    return "".join(f"/{_escape_token(str(part))}" for part in parts)


def validate_document(document: object, kind: ArtifactKind, path: str) -> list[ValidationIssue]:
    """Return every structural issue in one document, sorted by location.

    An unsupported artifact major short-circuits with a single version issue carrying the
    real artifact path, so an unreadable document is never also reported as malformed.
    """
    version_issues = _version_issues(document, kind, path)
    if version_issues:
        return version_issues

    located: dict[tuple[tuple[object, ...], str], ValidationIssue] = {}
    for error in schema_validator(kind).iter_errors(document):
        for parts, message in _locate(error):
            located.setdefault(
                (parts, message),
                ValidationIssue(
                    code=_SCHEMA_INVALID,
                    artifact_path=path,
                    artifact_kind=kind,
                    message=message,
                    pointer=json_pointer(parts),
                ),
            )
    return [located[key] for key in sorted(located, key=_sort_key)]


def validate_ndjson(
    path: Path,
    kind: ArtifactKind = "raw_pose_frame",
) -> tuple[list[dict[str, object]], list[ValidationIssue]]:
    """Validate one NDJSON stream line by line, keeping line numbers on every issue.

    Every line that parses as a JSON object is returned even when it fails validation, so a
    later semantic pass and a human reader keep the full diagnostic picture of a broken
    stream instead of only its first good prefix. Blank lines are ignored.
    """
    records: list[dict[str, object]] = []
    issues: list[ValidationIssue] = []
    artifact_path = str(path)

    with Path(path).open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                record = load_strict_json(line)
            except json.JSONDecodeError as error:
                issues.append(
                    ValidationIssue(
                        code=_SCHEMA_INVALID,
                        artifact_path=artifact_path,
                        artifact_kind=kind,
                        message=f"line is not valid JSON: {error.msg}",
                        line=line_number,
                    )
                )
                continue
            if not isinstance(record, dict):
                issues.append(
                    ValidationIssue(
                        code=_SCHEMA_INVALID,
                        artifact_path=artifact_path,
                        artifact_kind=kind,
                        message="line is not a JSON object",
                        line=line_number,
                    )
                )
                continue
            records.append(record)
            issues.extend(
                replace(issue, line=line_number)
                for issue in validate_document(record, kind, artifact_path)
            )

    return records, issues


def _escape_token(token: str) -> str:
    """Escape one JSON pointer reference token. ``~`` is replaced first, then ``/``."""
    return token.replace("~", "~0").replace("/", "~1")


def _version_issues(document: object, kind: ArtifactKind, path: str) -> list[ValidationIssue]:
    """Return the unknown-major issue for this document, carrying its real artifact path.

    A malformed version string is left to the schema's pattern constraint so it is reported
    once, as a structural failure, at the version field's own pointer.
    """
    if not isinstance(document, Mapping):
        return []
    declared = document.get(SCHEMA_VERSION_FIELDS[kind])
    if not isinstance(declared, str):
        return []
    try:
        ensure_supported_major(declared, SUPPORTED_MAJOR, kind)
    except ValidationFailure as failure:
        return [replace(issue, artifact_path=path) for issue in failure.issues]
    except ValueError:
        pass
    return []


def _locate(error: ValidationError) -> list[tuple[tuple[object, ...], str]]:
    """Return the located messages for one schema error.

    A ``required`` failure is reported at the missing property rather than at the object
    that should have contained it, which is what makes the pointer actionable.
    """
    base = tuple(error.absolute_path)
    if error.validator == "required" and isinstance(error.instance, dict):
        names: Any = error.validator_value
        if isinstance(names, list):
            missing = [
                name for name in names if isinstance(name, str) and name not in error.instance
            ]
            if missing:
                return [
                    ((*base, name), f"required property {name!r} is missing") for name in missing
                ]
    return [(base, _message(error))]


def _message(error: ValidationError) -> str:
    """Return a concise message that never reproduces a container instance.

    ``jsonschema`` renders the failing instance into its message. For an object or array
    that means an entire landmark set or event list, which is both unreadable and a way for
    biometric-derived observations to leak into logs and CLI output. Containers are
    therefore described by shape and the constraint they failed; scalars are reported
    verbatim because their value is the diagnosis.
    """
    instance = error.instance
    if isinstance(instance, dict):
        shape = f"object with {len(instance)} properties"
    elif isinstance(instance, list):
        shape = f"array of {len(instance)} items"
    else:
        return error.message
    constraint = json.dumps(error.validator_value, default=str)
    if len(constraint) > _MAX_CONSTRAINT_CHARS:
        constraint = f"{constraint[:_MAX_CONSTRAINT_CHARS]}..."
    return f"{shape} fails the {str(error.validator)!r} constraint: {constraint}"


def _sort_key(key: tuple[tuple[object, ...], str]) -> tuple[tuple[tuple[int, int, str], ...], str]:
    """Order issues by document location, keeping array indexes in numeric order."""
    parts, message = key
    return tuple(_part_key(part) for part in parts), message


def _part_key(part: object) -> tuple[int, int, str]:
    """Sort integer path segments numerically and ahead of property names at the same depth."""
    if isinstance(part, int):
        return (0, part, "")
    return (1, 0, str(part))
