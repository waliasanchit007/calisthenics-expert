"""Reference RGB24 scale-to-fit preprocessing and executable golden-vector checks."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import replace
from fractions import Fraction
from json import JSONDecodeError
from math import floor
from pathlib import Path

from observation_contracts.errors import ValidationFailure, ValidationIssue
from observation_contracts.fixture_integrity import resolve_fixture_path, sha256_file
from observation_contracts.schema_loader import (
    SUPPORTED_MAJOR,
    load_strict_json,
    preprocessing_vector_schema_validator,
)
from observation_contracts.structural import json_pointer
from observation_contracts.versions import ensure_supported_major

_VECTOR_KIND = "preprocessing_vector"
_VECTOR_INVALID = "PREPROCESSING.VECTOR_INVALID"


def scale_pad_rgb24(
    source: bytes,
    source_width: int,
    source_height: int,
    target_width: int,
    target_height: int,
) -> bytes:
    """Scale packed RGB24 to fit a target rectangle and fill its padding with black."""
    if source_width < 1 or source_height < 1 or target_width < 1 or target_height < 1:
        raise ValueError("dimensions must be positive")
    expected_length = source_width * source_height * 3
    if len(source) != expected_length:
        raise ValueError(f"source byte length must equal {expected_length}")

    scale = min(
        Fraction(target_width, source_width),
        Fraction(target_height, source_height),
    )
    scaled_width = max(1, _round_half_up(Fraction(source_width) * scale))
    scaled_height = max(1, _round_half_up(Fraction(source_height) * scale))
    left = (target_width - scaled_width) // 2
    top = (target_height - scaled_height) // 2
    output = bytearray(target_width * target_height * 3)

    for destination_y in range(scaled_height):
        source_y = _source_coordinate(destination_y, source_height, scaled_height)
        y0 = source_y.numerator // source_y.denominator
        y1 = min(y0 + 1, source_height - 1)
        wy = source_y - y0
        for destination_x in range(scaled_width):
            source_x = _source_coordinate(destination_x, source_width, scaled_width)
            x0 = source_x.numerator // source_x.denominator
            x1 = min(x0 + 1, source_width - 1)
            wx = source_x - x0
            output_offset = ((destination_y + top) * target_width + destination_x + left) * 3
            for channel in range(3):
                top_left = source[(y0 * source_width + x0) * 3 + channel]
                top_right = source[(y0 * source_width + x1) * 3 + channel]
                bottom_left = source[(y1 * source_width + x0) * 3 + channel]
                bottom_right = source[(y1 * source_width + x1) * 3 + channel]
                top_value = (1 - wx) * top_left + wx * top_right
                bottom_value = (1 - wx) * bottom_left + wx * bottom_right
                value = (1 - wy) * top_value + wy * bottom_value
                output[output_offset + channel] = _round_half_up(value)
    return bytes(output)


def verify_preprocessing_vector(descriptor_path: Path) -> list[ValidationIssue]:
    """Verify descriptor hashes and that the reference transform matches expected output bytes."""
    artifact_path = str(descriptor_path)
    try:
        descriptor = load_strict_json(descriptor_path.read_text(encoding="utf-8"))
    except OSError as error:
        return [_vector_issue(artifact_path, f"could not read vector descriptor: {error}")]
    except JSONDecodeError as error:
        return [_schema_issue(artifact_path, f"could not read vector descriptor: {error}")]

    schema_issues = _schema_issues(descriptor, artifact_path)
    if schema_issues:
        return schema_issues
    if not isinstance(descriptor, Mapping):
        return [_schema_issue(artifact_path, "vector descriptor must be a JSON object")]
    tooling_schema_version = descriptor.get("tooling_schema_version")
    if not isinstance(tooling_schema_version, str):
        return [_schema_issue(artifact_path, "tooling_schema_version must be a string")]
    try:
        ensure_supported_major(tooling_schema_version, SUPPORTED_MAJOR, _VECTOR_KIND)
    except ValidationFailure as failure:
        return [
            replace(issue, artifact_path=artifact_path, artifact_kind=_VECTOR_KIND)
            for issue in failure.issues
        ]

    input_path = descriptor.get("input_path")
    output_path = descriptor.get("expected_output_path")
    if not isinstance(input_path, str) or not isinstance(output_path, str):
        return [_vector_issue(artifact_path, "vector paths must be strings")]
    root = descriptor_path.parent.resolve()
    input_file = resolve_fixture_path(root, input_path)
    output_file = resolve_fixture_path(root, output_path)
    if input_file is None or output_file is None:
        return [
            _vector_issue(
                artifact_path, "vector paths must remain beneath the descriptor directory"
            )
        ]
    if not input_file.is_file() or not output_file.is_file():
        return [_vector_issue(artifact_path, "vector paths must resolve to regular files")]

    issues: list[ValidationIssue] = []
    input_bytes = input_file.read_bytes()
    output_bytes = output_file.read_bytes()
    if descriptor["input_byte_length"] != len(input_bytes):
        issues.append(_vector_issue(artifact_path, "input byte length does not match descriptor"))
    if descriptor["expected_output_byte_length"] != len(output_bytes):
        issues.append(
            _vector_issue(artifact_path, "expected output byte length does not match descriptor")
        )
    if descriptor["input_sha256"] != sha256_file(input_file):
        issues.append(_vector_issue(artifact_path, "input SHA-256 does not match descriptor"))
    if descriptor["expected_output_sha256"] != sha256_file(output_file):
        issues.append(
            _vector_issue(artifact_path, "expected output SHA-256 does not match descriptor")
        )

    dimensions = (
        descriptor["input_width"],
        descriptor["input_height"],
        descriptor["output_width"],
        descriptor["output_height"],
    )
    if not all(isinstance(value, int) and value > 0 for value in dimensions):
        issues.append(_vector_issue(artifact_path, "vector dimensions must be positive integers"))
    else:
        try:
            actual_output = scale_pad_rgb24(
                input_bytes,
                dimensions[0],
                dimensions[1],
                dimensions[2],
                dimensions[3],
            )
        except ValueError as error:
            issues.append(_vector_issue(artifact_path, str(error)))
        else:
            if actual_output != output_bytes:
                issues.append(
                    _vector_issue(artifact_path, "reference output differs from expected bytes")
                )
    return issues


def _source_coordinate(destination: int, source_size: int, destination_size: int) -> Fraction:
    coordinate = Fraction(2 * destination + 1, 2) * Fraction(source_size, destination_size)
    coordinate -= Fraction(1, 2)
    return max(Fraction(0), min(Fraction(source_size - 1), coordinate))


def _round_half_up(value: Fraction) -> int:
    return floor(value + Fraction(1, 2))


def _vector_issue(artifact_path: str, message: str) -> ValidationIssue:
    return ValidationIssue(
        code=_VECTOR_INVALID,
        artifact_path=artifact_path,
        artifact_kind=_VECTOR_KIND,
        message=message,
    )


def _schema_issue(artifact_path: str, message: str) -> ValidationIssue:
    return ValidationIssue(
        code="SCHEMA.INVALID",
        artifact_path=artifact_path,
        artifact_kind=_VECTOR_KIND,
        message=message,
    )


def _schema_issues(descriptor: object, artifact_path: str) -> list[ValidationIssue]:
    """Return released tooling-schema failures before vector-specific verification."""
    return [
        ValidationIssue(
            code="SCHEMA.INVALID",
            artifact_path=artifact_path,
            artifact_kind=_VECTOR_KIND,
            message=error.message,
            pointer=json_pointer(error.absolute_path),
        )
        for error in preprocessing_vector_schema_validator().iter_errors(descriptor)
    ]
