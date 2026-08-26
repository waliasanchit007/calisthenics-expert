from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from check_storage_exclusion import CheckError, check_transcript

SUITE = "class=com.calisthenics.diagnostic.probe.StorageExclusionDeviceGateTest"


def _record() -> dict:
    return {
        "artifact_schema_version": "1.0.0",
        "record_kind": "storage_exclusion_device_gate_observation",
        "sanitized": True,
        "contains_landmark_coordinates": False,
        "non_participant_observation": True,
        "emitted_by": "StorageExclusionDeviceGateTest",
        "harness_git_sha": "0123456789abcdef0123456789abcdef01234567",
        "environment": {
            "device_class": "physical_device",
            "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
            "device_fingerprint": "samsung/pa3q/test:user/release-keys",
            "api_level": 36,
            "android_release": "16",
            "abi": "arm64-v8a",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "marketing_name": "",
        },
        "observation": {
            "allow_backup_disabled": True,
            "data_extraction_rules_resource": "@xml/data_extraction_rules",
            "cloud_backup_excluded_parent": "diagnostic-harness/",
            "device_transfer_excluded_parent": "diagnostic-harness/",
            "registered_android_children": ["exports", "staging", "tombstones", "transfer-staging"],
            "observed_top_level_children": ["exports", "staging", "tombstones", "transfer-staging"],
        },
        "blocking_reasons": [],
        "satisfies_s25_storage_exclusion_gate": True,
    }


def _transcript(records: list[dict] | None = None, result: str = "OK (1 test)") -> str:
    records = [_record()] if records is None else records
    lines = [f"INSTRUMENTATION_STATUS: {SUITE}"]
    lines.extend(
        "INSTRUMENTATION_STATUS: StorageExclusionEvidence=" + json.dumps(record)
        for record in records
    )
    lines.extend(["INSTRUMENTATION_STATUS_CODE: 0", "", result])
    return "\n".join(lines)


def test_accepts_exact_single_passing_observation() -> None:
    assert check_transcript(_transcript())["satisfies_s25_storage_exclusion_gate"] is True


@pytest.mark.parametrize(
    "transcript",
    [
        _transcript([]),
        _transcript([_record(), _record()]),
        _transcript(result="FAILURES!!!"),
        _transcript().replace(SUITE, "class=example.UnrelatedTest"),
    ],
)
def test_rejects_vacuous_duplicated_failing_or_wrong_suite_runs(transcript: str) -> None:
    with pytest.raises(CheckError):
        check_transcript(transcript)


def test_recomputes_gate_instead_of_trusting_declared_success() -> None:
    record = _record()
    record["observation"]["cloud_backup_excluded_parent"] = None

    with pytest.raises(CheckError):
        check_transcript(_transcript([record]))
