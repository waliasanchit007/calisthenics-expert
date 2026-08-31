#!/usr/bin/env python3
"""Verify and print one S25 container-timestamp probe observation.

The Android test proves that a real container round trip was exercised; this host-side checker
decides whether its transcript is trustworthy and whether the observed mapping can admit the
future synchronization contract.  It never edits the device-evidence ledger.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from itertools import pairwise
from pathlib import Path
from typing import Any

EXIT_OK = 0
EXIT_UNTRUSTED = 1
EXIT_USAGE = 2
EXIT_BLOCKING = 3

RECORD_KIND = "container_timestamp_probe_device_gate_observation"
SUITE = "com.calisthenics.diagnostic.probe.ContainerTimestampProbeTest"
LEDGER_PATH = "docs/evidence/device-probes/s25-ultra-container-timestamp-ledger.json"

BLOCK_SUBMITTED_PTS_EMPTY = "submitted_pts_empty"
BLOCK_SUBMITTED_PTS_NOT_POSITIVE = "submitted_pts_not_strictly_positive"
BLOCK_DEMUXED_PTS_NEGATIVE = "demuxed_pts_negative"
BLOCK_SUBMITTED_ORDER = "submitted_pts_not_strictly_increasing"
BLOCK_DEMUXED_ORDER = "demuxed_pts_not_strictly_increasing"
BLOCK_COUNT_MISMATCH = "submitted_demuxed_count_mismatch"
BLOCK_NON_CONSTANT_TRANSFORM = "container_timestamp_transform_non_constant"
BLOCK_UNAUTHORIZED_TARGET = "unauthorized_device_target"
BLOCK_PROVENANCE = "invalid_probe_git_sha"

_SUITE_LINE = re.compile(
    r"^INSTRUMENTATION_STATUS:\s+class=(?P<suite>[^\r\n]+)$", re.MULTILINE
)
_PASSING_RUN = re.compile(r"^OK \(1 test\)$", re.MULTILINE)
_RECORD_LINE = re.compile(
    r"^INSTRUMENTATION_STATUS:\s+ContainerTimestampEvidence=(?P<json>\{.*\})$",
    re.MULTILINE,
)
_SHA = re.compile(r"^[0-9a-f]{40}$")

_ENVIRONMENT_FIELDS = (
    "device_class",
    "device_identity",
    "device_fingerprint",
    "api_level",
    "android_release",
    "abi",
    "manufacturer",
    "model",
    "marketing_name",
)
_PROVENANCE_FIELDS = ("harness_git_sha", "container", "codec")
_OBSERVATION_FIELDS = ("submitted_pts_us", "demuxed_pts_us", "constant_offset_us")
_FORBIDDEN_KEYS = {
    "normalized_image_landmarks",
    "world_landmarks",
    "landmark_x",
    "landmark_y",
    "landmark_z",
    "participant_id",
    "participant_pseudonym_id",
    "consent_record",
    "identity_mapping",
}


class ContainerTimestampProbeCheckError(Exception):
    """Raised when a transcript or recorded evidence entry cannot be trusted."""


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _walk_keys(value: object) -> list[str]:
    keys: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str):
                keys.append(key)
            keys.extend(_walk_keys(child))
    elif isinstance(value, list):
        for child in value:
            keys.extend(_walk_keys(child))
    return keys


def is_authorized_container_timestamp_target(environment: dict[str, Any]) -> bool:
    """Apply the single physical Samsung S25 Ultra target rule."""
    if environment.get("device_class") != "physical_device":
        return False
    manufacturer = environment.get("manufacturer")
    if not isinstance(manufacturer, str) or manufacturer.lower() != "samsung":
        return False
    model = environment.get("model")
    marketing_name = environment.get("marketing_name")
    model = model if isinstance(model, str) else ""
    marketing_name = marketing_name if isinstance(marketing_name, str) else ""
    return model.upper().startswith("SM-S938") or "S25 Ultra" in marketing_name


def derive_constant_offset_us(observation: dict[str, Any]) -> int | None:
    """Return submitted-minus-demuxed when every paired sample has one offset."""
    submitted = observation.get("submitted_pts_us")
    demuxed = observation.get("demuxed_pts_us")
    if not isinstance(submitted, list) or not isinstance(demuxed, list):
        return None
    if not submitted or len(submitted) != len(demuxed):
        return None
    if not all(_is_int(item) for item in submitted + demuxed):
        return None
    offsets = {
        submitted_item - demuxed_item
        for submitted_item, demuxed_item in zip(submitted, demuxed)
    }
    return offsets.pop() if len(offsets) == 1 else None


def container_timestamp_blocking_reasons(record: dict[str, Any]) -> list[str]:
    """Recompute all admission blockers from observations and environment."""
    observation = record.get("observation")
    environment = record.get("environment")
    if not isinstance(observation, dict) or not isinstance(environment, dict):
        return [BLOCK_COUNT_MISMATCH]

    submitted = observation.get("submitted_pts_us")
    demuxed = observation.get("demuxed_pts_us")
    submitted = submitted if isinstance(submitted, list) else []
    demuxed = demuxed if isinstance(demuxed, list) else []
    reasons: list[str] = []
    if not submitted:
        reasons.append(BLOCK_SUBMITTED_PTS_EMPTY)
    if any(not _is_int(value) or value <= 0 for value in submitted):
        reasons.append(BLOCK_SUBMITTED_PTS_NOT_POSITIVE)
    if any(not _is_int(value) or value < 0 for value in demuxed):
        reasons.append(BLOCK_DEMUXED_PTS_NEGATIVE)
    if not all(before < after for before, after in pairwise(submitted)):
        reasons.append(BLOCK_SUBMITTED_ORDER)
    if not all(before < after for before, after in pairwise(demuxed)):
        reasons.append(BLOCK_DEMUXED_ORDER)
    if len(submitted) != len(demuxed):
        reasons.append(BLOCK_COUNT_MISMATCH)
    elif submitted and derive_constant_offset_us(observation) is None:
        reasons.append(BLOCK_NON_CONSTANT_TRANSFORM)
    if not is_authorized_container_timestamp_target(environment):
        reasons.append(BLOCK_UNAUTHORIZED_TARGET)
    provenance = record.get("provenance")
    sha = provenance.get("harness_git_sha") if isinstance(provenance, dict) else None
    if not isinstance(sha, str) or _SHA.fullmatch(sha) is None:
        reasons.append(BLOCK_PROVENANCE)
    return reasons


def _require(condition: bool, detail: str) -> None:
    if not condition:
        raise ContainerTimestampProbeCheckError(detail)


def require_exact_suite(transcript: str) -> None:
    suites = _SUITE_LINE.findall(transcript)
    _require(
        len(suites) in {1, 2} and set(suites) == {SUITE},
        (
            "transcript must contain only the requested suite class "
            f"{SUITE!r} in one runner marker or its start/completion pair, found {suites!r}"
        ),
    )
    _require(
        len(_PASSING_RUN.findall(transcript)) == 1,
        'transcript must report exactly one passing test ("OK (1 test)")',
    )


def validate_record(record: dict[str, Any]) -> None:
    """Validate the record envelope and compare, but never use, its declared verdict."""
    _require(
        record.get("artifact_schema_version") == "1.0.0",
        "unexpected evidence schema version",
    )
    _require(
        record.get("record_kind") == RECORD_KIND, "unexpected evidence record kind"
    )
    _require(
        record.get("emitted_by") == SUITE, "evidence was emitted by the wrong suite"
    )
    _require(record.get("sanitized") is True, "evidence is not marked sanitized")
    _require(
        record.get("non_participant_observation") is True,
        "evidence is not marked as non-participant observation",
    )
    _require(
        record.get("contains_landmark_coordinates") is False,
        "evidence does not explicitly exclude landmark coordinates",
    )
    forbidden = sorted(_FORBIDDEN_KEYS.intersection(_walk_keys(record)))
    _require(
        not forbidden, f"evidence contains forbidden participant keys: {forbidden}"
    )

    for section, required in (
        ("environment", _ENVIRONMENT_FIELDS),
        ("provenance", _PROVENANCE_FIELDS),
        ("observation", _OBSERVATION_FIELDS),
    ):
        block = record.get(section)
        _require(isinstance(block, dict), f"evidence has no {section} object")
        assert isinstance(block, dict)
        missing = [field for field in required if field not in block]
        _require(not missing, f"evidence {section} is missing {missing}")

    environment = record["environment"]
    assert isinstance(environment, dict)
    _require(
        _is_int(environment["api_level"]) and environment["api_level"] > 0,
        "invalid API level",
    )
    for field in _ENVIRONMENT_FIELDS:
        if field != "api_level":
            _require(
                isinstance(environment[field], str),
                f"environment {field} is not a string",
            )
    for field in _ENVIRONMENT_FIELDS:
        if field != "marketing_name":
            _require(bool(environment[field]), f"environment {field} is empty")

    provenance = record["provenance"]
    assert isinstance(provenance, dict)
    for field in ("container", "codec"):
        _require(
            isinstance(provenance[field], str) and bool(provenance[field]),
            f"provenance {field} is empty",
        )
    _require(
        isinstance(provenance["harness_git_sha"], str)
        and _SHA.fullmatch(provenance["harness_git_sha"]) is not None,
        "provenance harness_git_sha must be a lowercase 40-hex SHA",
    )

    observation = record["observation"]
    assert isinstance(observation, dict)
    submitted = observation["submitted_pts_us"]
    demuxed = observation["demuxed_pts_us"]
    _require(isinstance(submitted, list), "submitted_pts_us is not an array")
    _require(isinstance(demuxed, list), "demuxed_pts_us is not an array")
    _require(
        all(_is_int(value) for value in submitted),
        "submitted_pts_us contains a non-integer",
    )
    _require(
        all(_is_int(value) for value in demuxed),
        "demuxed_pts_us contains a non-integer",
    )
    declared_offset = observation["constant_offset_us"]
    _require(
        declared_offset is None or _is_int(declared_offset),
        "constant_offset_us is not an integer or null",
    )
    _require(
        declared_offset == derive_constant_offset_us(observation),
        "declared constant_offset_us disagrees with the paired observations",
    )

    reasons = container_timestamp_blocking_reasons(record)
    _require(
        record.get("blocking_reasons") == reasons,
        "declared blocking reasons disagree with observations",
    )
    _require(
        isinstance(record.get("blocking_result"), bool)
        and record["blocking_result"] is bool(reasons),
        "declared blocking_result disagrees with observations",
    )


def extract_record(transcript: str) -> dict[str, Any]:
    matches = _RECORD_LINE.findall(transcript)
    _require(
        len(matches) == 1,
        f"expected exactly one ContainerTimestampEvidence record, found {len(matches)}",
    )
    try:
        record = json.loads(matches[0])
    except json.JSONDecodeError as error:
        raise ContainerTimestampProbeCheckError("evidence is not valid JSON") from error
    _require(isinstance(record, dict), "evidence is not a JSON object")
    assert isinstance(record, dict)
    validate_record(record)
    return record


def _read_json(path: Path) -> dict[str, Any]:
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ContainerTimestampProbeCheckError(
            f"cannot read JSON {path}: {error}"
        ) from error
    _require(isinstance(loaded, dict), f"{path} is not a JSON object")
    assert isinstance(loaded, dict)
    return loaded


def _evidence_path(root: Path, relative: str) -> Path:
    path = Path(relative)
    _require(not path.is_absolute(), "recorded evidence_file must be relative")
    root_resolved = root.resolve()
    candidate = (root / path).resolve()
    _require(
        candidate == root_resolved or root_resolved in candidate.parents,
        "recorded evidence_file escapes the repository root",
    )
    return candidate


def verify_recorded_ledger(
    root: Path, ledger_relative: str = LEDGER_PATH
) -> dict[str, Any]:
    """Validate pending or recorded ledger state without writing either file."""
    ledger = _read_json(root / ledger_relative)
    _require(
        ledger.get("record_kind") == RECORD_KIND, "ledger has the wrong record kind"
    )
    status = ledger.get("status")
    _require(
        status in {"pending", "recorded"}, "ledger status must be pending or recorded"
    )
    if status == "pending":
        _require(
            bool(ledger.get("missing_precondition")),
            "pending ledger must name its missing precondition",
        )
        return ledger

    evidence_relative = ledger.get("evidence_file")
    _require(
        isinstance(evidence_relative, str) and bool(evidence_relative),
        "recorded ledger must name evidence_file",
    )
    assert isinstance(evidence_relative, str)
    evidence_path = _evidence_path(root, evidence_relative)
    _require(
        evidence_path.is_file(),
        f"recorded evidence file is missing: {evidence_relative}",
    )
    payload = evidence_path.read_bytes()
    _require(
        ledger.get("evidence_sha256") == hashlib.sha256(payload).hexdigest(),
        "recorded evidence_sha256 does not match the named evidence file",
    )
    record = _read_json(evidence_path)
    validate_record(record)
    environment = record["environment"]
    assert isinstance(environment, dict)
    _require(
        is_authorized_container_timestamp_target(environment),
        "recorded evidence is not from the authorized physical Samsung S25 Ultra",
    )
    _require(
        ledger.get("timestamp_admission")
        == (
            "admitted"
            if not container_timestamp_blocking_reasons(record)
            else "blocked"
        ),
        "ledger timestamp_admission disagrees with recomputed evidence",
    )
    for field in ("device_class", "device_identity", "api_level", "android_release"):
        _require(
            ledger.get(field) == environment[field],
            f"ledger {field} disagrees with the named evidence",
        )
    return ledger


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {Path(argv[0]).name} <transcript-file>", file=sys.stderr)
        return EXIT_USAGE
    try:
        transcript = (
            sys.stdin.read()
            if argv[1] == "-"
            else Path(argv[1]).read_text(encoding="utf-8")
        )
        require_exact_suite(transcript)
        record = extract_record(transcript)
    except (ContainerTimestampProbeCheckError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_UNTRUSTED

    print(json.dumps(record, sort_keys=True, separators=(",", ":")))
    reasons = container_timestamp_blocking_reasons(record)
    if reasons:
        print(
            "error: trustworthy container timestamp observation is blocking: "
            + ",".join(reasons),
            file=sys.stderr,
        )
        return EXIT_BLOCKING
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
