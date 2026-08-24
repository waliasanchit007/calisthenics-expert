"""Command-line entry points for validating observation-contract artifacts."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, NoReturn

from observation_contracts.errors import ValidationIssue
from observation_contracts.fixture_integrity import verify_fixture
from observation_contracts.preprocessing import verify_preprocessing_vector
from observation_contracts.repository_guard import GuardMode, guard_repository
from observation_contracts.schema_loader import ArtifactKind, load_strict_json
from observation_contracts.semantic import (
    validate_annotations,
    validate_frame_stream,
    validate_session_manifest_semantics,
)
from observation_contracts.structural import validate_document, validate_ndjson
from observation_contracts.study_readiness import verify_study_readiness

_VALIDATION_FAILURE = 2
_INTERNAL_FAILURE = 3


class _UsageError(Exception):
    """Raised for argparse usage errors that should be returned from ``main``."""


def main(argv: Sequence[str] | None = None) -> int:
    """Run one contract command and return its stable process exit code."""
    parser = _build_parser()
    try:
        arguments = parser.parse_args(argv)
    except _UsageError as error:
        parser.print_usage(sys.stderr)
        print(f"{parser.prog}: error: {error}", file=sys.stderr)
        return _VALIDATION_FAILURE

    try:
        issues = arguments.handler(arguments)
    except Exception as error:  # noqa: BLE001 - the CLI boundary maps all unexpected failures.
        print(f"{parser.prog}: internal failure: {error}", file=sys.stderr)
        return _INTERNAL_FAILURE

    _render(issues, arguments.output_format, arguments.command)
    return _VALIDATION_FAILURE if issues else 0


class _ArgumentParser(argparse.ArgumentParser):
    """Argument parser that lets ``main`` own the CLI exit contract."""

    def error(self, message: str) -> NoReturn:
        raise _UsageError(message)


def _build_parser() -> _ArgumentParser:
    parser = _ArgumentParser(
        prog="observation-contracts",
        description="Validate observation-contract artifacts and repository contents.",
    )
    parser.add_argument(
        "--format",
        dest="output_format",
        choices=("human", "json"),
        default="human",
        help="output format (default: human)",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    validate_session = commands.add_parser(
        "validate-session", help="validate a session manifest, frame stream, and annotations"
    )
    _add_format_option(validate_session)
    validate_session.add_argument("manifest", type=Path)
    validate_session.add_argument("frames", type=Path)
    validate_session.add_argument("annotations", type=Path, nargs="*")
    validate_session.set_defaults(handler=_validate_session)

    verify_fixture_parser = commands.add_parser(
        "verify-fixture", help="verify fixture metadata and referenced frame bytes"
    )
    _add_format_option(verify_fixture_parser)
    verify_fixture_parser.add_argument("fixture_metadata", type=Path)
    verify_fixture_parser.set_defaults(handler=_verify_fixture)

    verify_vector = commands.add_parser(
        "verify-preprocessing-vector", help="verify a preprocessing golden vector"
    )
    _add_format_option(verify_vector)
    verify_vector.add_argument("vector_metadata", type=Path)
    verify_vector.set_defaults(handler=_verify_preprocessing_vector)

    guard = commands.add_parser(
        "guard-repository", help="guard tracked or staged repository contents"
    )
    _add_format_option(guard)
    guard.add_argument("--staged", action="store_true", help="inspect the staged index")
    guard.add_argument("repository", type=Path, nargs="?", default=Path("."))
    guard.set_defaults(handler=_guard_repository)

    verify_readiness = commands.add_parser(
        "verify-study-readiness", help="verify canonical study-readiness semantics"
    )
    _add_format_option(verify_readiness)
    verify_readiness.add_argument("manifest", type=Path)
    verify_readiness.set_defaults(handler=_verify_study_readiness)

    participant_gate = commands.add_parser(
        "verify-participant-capture-gate",
        help="verify canonical readiness and the participant-capture privacy gate",
    )
    _add_format_option(participant_gate)
    participant_gate.add_argument("manifest", type=Path)
    participant_gate.set_defaults(handler=_verify_participant_capture_gate)
    return parser


def _add_format_option(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--format",
        dest="output_format",
        choices=("human", "json"),
        default=argparse.SUPPRESS,
        help="output format (default: human)",
    )


def _validate_session(arguments: argparse.Namespace) -> list[ValidationIssue]:
    manifest_path: Path = arguments.manifest
    frames_path: Path = arguments.frames
    manifest, issues = _load_json(manifest_path, "session_manifest")
    manifest_unknown_major = _has_unknown_major(issues)
    frames, frame_issues = _load_frames(frames_path)
    issues.extend(frame_issues)
    frames_unknown_major = _has_unknown_major(frame_issues)

    annotations: list[Mapping[str, Any]] = []
    annotation_paths: list[str] = []
    annotations_unknown_major = False
    for annotation_path in arguments.annotations:
        annotation, annotation_issues = _load_json(annotation_path, "annotation")
        issues.extend(annotation_issues)
        annotations_unknown_major = annotations_unknown_major or _has_unknown_major(
            annotation_issues
        )
        if isinstance(annotation, Mapping):
            annotations.append(annotation)
            annotation_paths.append(str(annotation_path))

    if isinstance(manifest, Mapping) and not manifest_unknown_major:
        if not frames_unknown_major:
            issues.extend(
                validate_frame_stream(manifest, frames, str(manifest_path), str(frames_path))
            )
            issues.extend(validate_session_manifest_semantics(manifest, frames, str(manifest_path)))
        if not annotations_unknown_major:
            issues.extend(validate_annotations(manifest, annotations, annotation_paths))
    return issues


def _verify_fixture(arguments: argparse.Namespace) -> list[ValidationIssue]:
    path: Path = arguments.fixture_metadata
    document, issues = _load_json(path, "fixture_metadata")
    if document is not None:
        issues.extend(validate_document(document, "fixture_metadata", str(path)))
        issues.extend(verify_fixture(path))
    return issues


def _verify_preprocessing_vector(arguments: argparse.Namespace) -> list[ValidationIssue]:
    return verify_preprocessing_vector(arguments.vector_metadata)


def _guard_repository(arguments: argparse.Namespace) -> list[ValidationIssue]:
    mode: GuardMode = "staged" if arguments.staged else "tracked"
    return guard_repository(arguments.repository, mode=mode)


def _verify_study_readiness(arguments: argparse.Namespace) -> list[ValidationIssue]:
    return verify_study_readiness(arguments.manifest)


def _verify_participant_capture_gate(arguments: argparse.Namespace) -> list[ValidationIssue]:
    return verify_study_readiness(arguments.manifest, require_participant_ready=True)


def _load_json(path: Path, kind: ArtifactKind) -> tuple[object | None, list[ValidationIssue]]:
    try:
        document = load_strict_json(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return None, [_input_issue(path, kind, f"could not read JSON: {error}")]
    return document, list(validate_document(document, kind, str(path)))


def _has_unknown_major(issues: list[ValidationIssue]) -> bool:
    return any(issue.code == "VERSION.UNKNOWN_MAJOR" for issue in issues)


def _load_frames(path: Path) -> tuple[list[dict[str, object]], list[ValidationIssue]]:
    try:
        return validate_ndjson(path)
    except OSError as error:
        return [], [_input_issue(path, "raw_pose_frame", f"could not read NDJSON: {error}")]


def _input_issue(path: Path, kind: ArtifactKind, message: str) -> ValidationIssue:
    return ValidationIssue(
        code="SCHEMA.INVALID",
        artifact_path=str(path),
        artifact_kind=kind,
        message=message,
    )


def _render(issues: list[ValidationIssue], output_format: str, command: str) -> None:
    if output_format == "json":
        print(json.dumps([issue.to_dict() for issue in issues], sort_keys=True))
        return
    if not issues:
        print(f"valid: {command}")
        return

    current_artifact: str | None = None
    for issue in issues:
        if issue.artifact_path != current_artifact:
            current_artifact = issue.artifact_path
            print(f"{current_artifact}:")
        location = issue.artifact_path
        if issue.line is not None:
            location += f":{issue.line}"
        if issue.pointer is not None:
            location += issue.pointer
        print(f"  {location} [{issue.code}] {issue.message}")
