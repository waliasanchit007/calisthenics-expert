"""Integrity checks for decoded RGB24 fixture metadata and referenced bytes."""

from __future__ import annotations

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from json import JSONDecodeError
from pathlib import Path
from typing import Any

from observation_contracts.errors import ValidationIssue
from observation_contracts.schema_loader import load_strict_json
from observation_contracts.semantic import (
    validate_frame_stream,
    validate_session_manifest_semantics,
)
from observation_contracts.structural import validate_document, validate_ndjson

_FIXTURE_KIND = "fixture_metadata"
_INVALID_REFERENCE = "PREPROCESSING.VECTOR_INVALID"


@dataclass(frozen=True)
class NegativeFixtureCase:
    """One declared synthetic negative fixture and the validator it exercises."""

    name: str
    root: Path
    repository_root: Path
    command: str
    expected_error_code: str
    artifact_path: str


@dataclass(frozen=True)
class ValidationResult:
    """Stable validation output used by committed declared-negative fixtures."""

    issues: tuple[ValidationIssue, ...]

    @property
    def codes(self) -> list[str]:
        """Return issue codes in validator order."""
        return [issue.code for issue in self.issues]


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
        document = load_strict_json(metadata_path.read_text(encoding="utf-8"))
    except OSError as error:
        return [_invalid_issue(artifact_path, f"could not read fixture metadata: {error}")]
    except JSONDecodeError as error:
        return [_schema_issue(artifact_path, f"could not read fixture metadata: {error}")]

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
    frame_width = document.get("frame_width")
    frame_height = document.get("frame_height")
    expected_frame_length = (
        frame_width * frame_height * 3
        if isinstance(frame_width, int) and isinstance(frame_height, int)
        else None
    )
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
        if expected_frame_length is None:
            byte_length_mismatch = declared_length != actual_length
            expected_length: object = declared_length
            actual_length_value: object = actual_length
            message = "referenced frame byte length does not match metadata"
        else:
            byte_length_mismatch = (
                declared_length != expected_frame_length or actual_length != expected_frame_length
            )
            expected_length = expected_frame_length
            actual_length_value = {"declared": declared_length, "actual": actual_length}
            message = "declared and referenced frame byte lengths must match RGB24 geometry"
        if byte_length_mismatch:
            issues.append(
                ValidationIssue(
                    code="FIXTURE.BYTE_LENGTH",
                    artifact_path=artifact_path,
                    artifact_kind=_FIXTURE_KIND,
                    message=message,
                    pointer=f"/frames/{position}/byte_length",
                    expected=expected_length,
                    actual=actual_length_value,
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


def discover_negative_fixtures(root: Path) -> list[NegativeFixtureCase]:
    """Discover every committed negative case declared by ``expected_failure.json``."""
    negative_root = root / "fixtures" / "synthetic" / "negative"
    if not negative_root.is_dir():
        return []

    cases: list[NegativeFixtureCase] = []
    for case_root in sorted(path for path in negative_root.iterdir() if path.is_dir()):
        declaration_path = case_root / "expected_failure.json"
        if not declaration_path.is_file():
            continue
        document = _read_object(declaration_path)
        command = document.get("command")
        expected_code = document.get("expected_error_code")
        artifact_path = document.get("artifact_path")
        if (
            not isinstance(command, str)
            or not isinstance(expected_code, str)
            or not isinstance(artifact_path, str)
        ):
            raise TypeError(f"malformed expected failure declaration: {declaration_path}")
        cases.append(
            NegativeFixtureCase(
                name=case_root.name,
                root=case_root,
                repository_root=root,
                command=command,
                expected_error_code=expected_code,
                artifact_path=artifact_path,
            )
        )
    return cases


def run_declared_validation(case: NegativeFixtureCase) -> ValidationResult:
    """Execute the validator named by one committed negative fixture declaration."""
    artifact = case.repository_root / Path(*case.artifact_path.split("/"))
    if case.command == "verify-fixture":
        issues = verify_fixture(artifact)
    elif case.command == "validate-session":
        issues = _validate_negative_session(case)
    else:
        raise ValueError(f"unsupported declared validation command: {case.command!r}")
    return ValidationResult(tuple(issues))


def _validate_negative_session(case: NegativeFixtureCase) -> list[ValidationIssue]:
    manifest_path = case.root / "manifest.json"
    frames_path = case.root / "frames.ndjson"
    manifest = _read_object(manifest_path)
    issues = validate_document(manifest, "session_manifest", str(manifest_path))
    frames, frame_issues = validate_ndjson(frames_path)
    issues.extend(frame_issues)
    issues.extend(validate_frame_stream(manifest, frames, str(manifest_path), str(frames_path)))
    issues.extend(validate_session_manifest_semantics(manifest, frames, str(manifest_path)))
    return issues


def _read_object(path: Path) -> Mapping[str, Any]:
    document = load_strict_json(path.read_text(encoding="utf-8"))
    if not isinstance(document, Mapping):
        raise TypeError(f"expected a JSON object: {path}")
    return document


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


def _schema_issue(artifact_path: str, message: str) -> ValidationIssue:
    return ValidationIssue(
        code="SCHEMA.INVALID",
        artifact_path=artifact_path,
        artifact_kind=_FIXTURE_KIND,
        message=message,
    )
