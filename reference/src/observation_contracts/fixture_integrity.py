"""Integrity checks for decoded RGB24 fixture metadata and referenced bytes."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from pathlib import Path

from observation_contracts.errors import ValidationIssue

_FIXTURE_KIND = "fixture_metadata"
_INVALID_REFERENCE = "PREPROCESSING.VECTOR_INVALID"


def sha256_file(path: Path) -> str:
    """Return a file's SHA-256 digest, reading it in bounded chunks."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(64 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify_fixture(metadata_path: Path) -> list[ValidationIssue]:
    """Verify fixture ordering and the declared integrity of every referenced frame."""
    artifact_path = str(metadata_path)
    try:
        document = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [_invalid_issue(artifact_path, f"could not read fixture metadata: {error}")]

    if not isinstance(document, Mapping):
        return [_invalid_issue(artifact_path, "fixture metadata must be a JSON object")]
    frames = document.get("frames")
    if not isinstance(frames, list):
        return [_invalid_issue(artifact_path, "fixture metadata frames must be an array")]

    issues: list[ValidationIssue] = []
    indexes = [frame.get("frame_index") for frame in frames if isinstance(frame, Mapping)]
    if indexes != list(range(len(frames))):
        issues.append(
            ValidationIssue(
                code="FIXTURE.FRAME_INDEX",
                artifact_path=artifact_path,
                artifact_kind=_FIXTURE_KIND,
                message="frame indexes must be unique and contiguous from zero",
                pointer="/frames",
                expected=list(range(len(frames))),
                actual=indexes,
            )
        )

    previous_timestamp: int | None = None
    root = metadata_path.parent.resolve()
    for position, frame in enumerate(frames):
        if not isinstance(frame, Mapping):
            issues.append(
                _invalid_issue(artifact_path, "frame descriptor must be an object", position)
            )
            continue

        timestamp = frame.get("timestamp_ns")
        if isinstance(timestamp, int):
            if previous_timestamp is not None and timestamp <= previous_timestamp:
                issues.append(
                    ValidationIssue(
                        code="FIXTURE.TIMESTAMP_ORDER",
                        artifact_path=artifact_path,
                        artifact_kind=_FIXTURE_KIND,
                        message="fixture timestamps must be strictly increasing",
                        pointer=f"/frames/{position}/timestamp_ns",
                        expected=f"> {previous_timestamp}",
                        actual=timestamp,
                    )
                )
            previous_timestamp = timestamp

        raw_path = frame.get("path")
        if not isinstance(raw_path, str) or not raw_path:
            issues.append(
                _invalid_issue(
                    artifact_path, "frame path must be a non-empty relative path", position
                )
            )
            continue
        referenced = _resolve_beneath(root, raw_path)
        if referenced is None or not referenced.is_file():
            issues.append(
                _invalid_issue(
                    artifact_path,
                    "frame path must resolve to a regular file beneath the metadata directory",
                    position,
                )
            )
            continue

        declared_length = frame.get("byte_length")
        actual_length = referenced.stat().st_size
        if declared_length != actual_length:
            issues.append(
                ValidationIssue(
                    code="FIXTURE.BYTE_LENGTH",
                    artifact_path=artifact_path,
                    artifact_kind=_FIXTURE_KIND,
                    message="referenced frame byte length does not match metadata",
                    pointer=f"/frames/{position}/byte_length",
                    expected=declared_length,
                    actual=actual_length,
                )
            )

        declared_hash = frame.get("sha256")
        actual_hash = sha256_file(referenced)
        if declared_hash != actual_hash:
            issues.append(
                ValidationIssue(
                    code="FIXTURE.CHECKSUM_MISMATCH",
                    artifact_path=artifact_path,
                    artifact_kind=_FIXTURE_KIND,
                    message="referenced frame SHA-256 does not match metadata",
                    pointer=f"/frames/{position}/sha256",
                    expected=declared_hash,
                    actual=actual_hash,
                )
            )
    return issues


def resolve_fixture_path(root: Path, raw_path: str) -> Path | None:
    """Resolve a relative fixture path only when it remains below ``root``."""
    return _resolve_beneath(root.resolve(), raw_path)


def _resolve_beneath(root: Path, raw_path: str) -> Path | None:
    candidate = (root / raw_path).resolve()
    if Path(raw_path).is_absolute():
        return None
    try:
        candidate.relative_to(root)
    except ValueError:
        return None
    return candidate


def _invalid_issue(
    artifact_path: str, message: str, position: int | None = None
) -> ValidationIssue:
    pointer = f"/frames/{position}" if position is not None else None
    return ValidationIssue(
        code=_INVALID_REFERENCE,
        artifact_path=artifact_path,
        artifact_kind=_FIXTURE_KIND,
        message=message,
        pointer=pointer,
    )
