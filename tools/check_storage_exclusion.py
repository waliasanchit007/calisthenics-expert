#!/usr/bin/env python3
"""Verify one physical-S25 storage-exclusion instrumentation transcript."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

RECORD_KIND = "storage_exclusion_device_gate_observation"
SUITE = "class=com.calisthenics.diagnostic.probe.StorageExclusionDeviceGateTest"
RECORD_PATTERN = re.compile(r"StorageExclusionEvidence=(?P<json>\{.*\})")
PASSING_RUN = re.compile(r"^OK \(1 test\)$", re.MULTILINE)
SHA = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_CHILDREN = ["exports", "staging", "tombstones", "transfer-staging"]


class CheckError(Exception):
    """Raised when the transcript cannot prove the storage-exclusion gate."""


def _authorized(environment: dict[str, Any]) -> bool:
    return (
        environment.get("device_class") == "physical_device"
        and str(environment.get("manufacturer", "")).lower() == "samsung"
        and (
            str(environment.get("model", "")).upper().startswith("SM-S938")
            or "S25 Ultra" in str(environment.get("marketing_name", ""))
        )
    )


def _blocking_reasons(record: dict[str, Any]) -> list[str]:
    observation = record.get("observation")
    environment = record.get("environment")
    if not isinstance(observation, dict) or not isinstance(environment, dict):
        raise CheckError("record lacks typed observation or environment")
    reasons: list[str] = []
    if observation.get("allow_backup_disabled") is not True:
        reasons.append("backup_enabled")
    if observation.get("data_extraction_rules_resource") != "@xml/data_extraction_rules":
        reasons.append("data_extraction_rules_missing")
    if observation.get("cloud_backup_excluded_parent") != "diagnostic-harness/":
        reasons.append("cloud_backup_parent_not_excluded")
    if observation.get("device_transfer_excluded_parent") != "diagnostic-harness/":
        reasons.append("device_transfer_parent_not_excluded")
    if sorted(observation.get("registered_android_children", [])) != EXPECTED_CHILDREN:
        reasons.append("registry_projection_mismatch")
    if sorted(observation.get("observed_top_level_children", [])) != EXPECTED_CHILDREN:
        reasons.append("unregistered_top_level_child")
    if not _authorized(environment):
        reasons.append("unauthorized_device_target")
    return reasons


def check_transcript(transcript: str) -> dict[str, Any]:
    if transcript.count(SUITE) != 1 or PASSING_RUN.search(transcript) is None:
        raise CheckError("expected exactly the named suite and OK (1 test)")
    matches = list(RECORD_PATTERN.finditer(transcript))
    if len(matches) != 1:
        raise CheckError("expected exactly one StorageExclusionEvidence record")
    try:
        record = json.loads(matches[0].group("json"))
    except json.JSONDecodeError as error:
        raise CheckError("evidence is not valid JSON") from error
    if not isinstance(record, dict):
        raise CheckError("evidence is not a JSON object")
    if record.get("record_kind") != RECORD_KIND:
        raise CheckError("unexpected record kind")
    if record.get("sanitized") is not True or record.get("non_participant_observation") is not True:
        raise CheckError("record is not explicitly sanitized non-participant evidence")
    if record.get("contains_landmark_coordinates") is not False:
        raise CheckError("record may contain landmark coordinates")
    if SHA.fullmatch(str(record.get("harness_git_sha", ""))) is None:
        raise CheckError("invalid harness Git SHA")
    reasons = _blocking_reasons(record)
    if record.get("blocking_reasons") != reasons:
        raise CheckError("declared blocking reasons disagree with observations")
    if record.get("satisfies_s25_storage_exclusion_gate") is not (not reasons):
        raise CheckError("declared gate verdict disagrees with observations")
    if reasons:
        raise CheckError("storage-exclusion observations are blocking: " + ",".join(reasons))
    return record


def main(arguments: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if arguments is None else arguments
    if len(arguments) != 1:
        print("usage: check_storage_exclusion.py <transcript-file>", file=sys.stderr)
        return 2
    path = Path(arguments[0])
    transcript = sys.stdin.read() if str(path) == "-" else path.read_text(encoding="utf-8")
    try:
        record = check_transcript(transcript)
    except (CheckError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(record, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
