"""Hermetic checks for the host-side container timestamp probe verifier.

The checker is deliberately tested independently of Android.  A green ``am instrument`` exit
status is not evidence by itself: the transcript must name the exact one-test suite and carry one
sanitized observation.  The admission result is recomputed from the submitted and demuxed PTS
sequences, environment, and provenance rather than trusted from fields emitted in the record.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from check_container_timestamp_probe import (
    BLOCK_COUNT_MISMATCH,
    BLOCK_DEMUXED_ORDER,
    BLOCK_DEMUXED_PTS_NEGATIVE,
    BLOCK_NON_CONSTANT_TRANSFORM,
    BLOCK_SUBMITTED_ORDER,
    BLOCK_SUBMITTED_PTS_EMPTY,
    BLOCK_SUBMITTED_PTS_NOT_POSITIVE,
    BLOCK_UNAUTHORIZED_TARGET,
    EXIT_BLOCKING,
    EXIT_OK,
    EXIT_UNTRUSTED,
    EXIT_USAGE,
    ContainerTimestampProbeCheckError,
    container_timestamp_blocking_reasons,
    derive_constant_offset_us,
    extract_record,
    is_authorized_container_timestamp_target,
    main,
    require_exact_suite,
    verify_recorded_ledger,
)

SUITE = "com.calisthenics.diagnostic.probe.ContainerTimestampProbeTest"
SUITE_LINE = f"class={SUITE}"


def _environment(
    *,
    device_class: str = "physical_device",
    manufacturer: str = "samsung",
    model: str = "SM-S938B",
    marketing_name: str = "",
) -> dict:
    return {
        "device_class": device_class,
        "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
        "device_fingerprint": "samsung/pa3qxins/pa3q:16/BP4A.251205.006/x:user/release-keys",
        "api_level": 36,
        "android_release": "16",
        "abi": "arm64-v8a",
        "manufacturer": manufacturer,
        "model": model,
        "marketing_name": marketing_name,
    }


def _observation(
    *,
    submitted: list[int] | None = None,
    demuxed: list[int] | None = None,
) -> dict:
    submitted = [1_000_000, 1_033_333, 1_066_666] if submitted is None else submitted
    demuxed = [999_000, 1_032_333, 1_065_666] if demuxed is None else demuxed
    offset = derive_constant_offset_us({"submitted_pts_us": submitted, "demuxed_pts_us": demuxed})
    return {
        "submitted_pts_us": submitted,
        "demuxed_pts_us": demuxed,
        "constant_offset_us": offset,
    }


def _record(
    *,
    environment: dict | None = None,
    observation: dict | None = None,
) -> dict:
    environment = _environment() if environment is None else environment
    observation = _observation() if observation is None else observation
    record = {
        "artifact_schema_version": "1.0.0",
        "record_kind": "container_timestamp_probe_device_gate_observation",
        "sanitized": True,
        "contains_landmark_coordinates": False,
        "non_participant_observation": True,
        "observation_subject": "device_container_timestamp_transform",
        "emitted_by": SUITE,
        "environment": environment,
        "provenance": {
            "harness_git_sha": "0123456789abcdef0123456789abcdef01234567",
            "container": "MPEG-4",
            "codec": "video/avc",
        },
        "observation": observation,
    }
    reasons = container_timestamp_blocking_reasons(record)
    record["blocking_reasons"] = reasons
    record["blocking_result"] = bool(reasons)
    return record


def _transcript(
    record: dict | None = None,
    *,
    suite: str = SUITE_LINE,
    result_line: str = "OK (1 test)",
    records: list[dict] | None = None,
) -> str:
    if records is None:
        records = [] if record is None else [record]
    lines = [f"INSTRUMENTATION_STATUS: {suite}"]
    lines += [
        "INSTRUMENTATION_STATUS: ContainerTimestampEvidence=" + json.dumps(item, sort_keys=True)
        for item in records
    ]
    lines += ["INSTRUMENTATION_STATUS_CODE: 0", "", result_line, ""]
    return "\n".join(lines)


def test_exact_suite_accepts_one_passing_test() -> None:
    require_exact_suite(_transcript(_record()))


def test_exact_suite_accepts_android_runner_start_and_completion_markers() -> None:
    transcript = _transcript(_record())
    transcript += f"INSTRUMENTATION_STATUS: class={SUITE}\n"

    require_exact_suite(transcript)


@pytest.mark.parametrize(
    "result_line",
    ["OK (0 tests)", "FAILURES!!!", "OK (2 tests)"],
)
def test_exact_suite_rejects_zero_failing_or_non_exact_test_counts(result_line: str) -> None:
    with pytest.raises(ContainerTimestampProbeCheckError):
        require_exact_suite(_transcript(_record(), result_line=result_line))


def test_exact_suite_rejects_a_wrong_suite() -> None:
    with pytest.raises(ContainerTimestampProbeCheckError):
        require_exact_suite(
            _transcript(
                _record(),
                suite="class=com.calisthenics.diagnostic.probe.StorageExclusionDeviceGateTest",
            )
        )


def test_exact_suite_rejects_an_extra_suite_marker() -> None:
    transcript = _transcript(_record()) + f"\nINSTRUMENTATION_STATUS: class={SUITE}.Other\n"
    with pytest.raises(ContainerTimestampProbeCheckError):
        require_exact_suite(transcript)


def test_extract_record_requires_exactly_one_evidence_record() -> None:
    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(records=[]))
    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(records=[_record(), _record()]))


def test_extract_record_accepts_a_complete_record() -> None:
    record = _record()
    assert extract_record(_transcript(record)) == record


@pytest.mark.parametrize(
    "path",
    [
        ("record_kind",),
        ("sanitized",),
        ("contains_landmark_coordinates",),
        ("non_participant_observation",),
        ("emitted_by",),
        ("environment", "device_class"),
        ("environment", "device_identity"),
        ("environment", "device_fingerprint"),
        ("environment", "api_level"),
        ("environment", "android_release"),
        ("environment", "abi"),
        ("environment", "manufacturer"),
        ("environment", "model"),
        ("environment", "marketing_name"),
        ("provenance", "harness_git_sha"),
        ("provenance", "container"),
        ("provenance", "codec"),
        ("observation", "submitted_pts_us"),
        ("observation", "demuxed_pts_us"),
        ("observation", "constant_offset_us"),
        ("blocking_reasons",),
        ("blocking_result",),
    ],
)
def test_extract_record_rejects_missing_required_fields(path: tuple[str, ...]) -> None:
    record = _record()
    holder = record
    for key in path[:-1]:
        holder = holder[key]
    del holder[path[-1]]

    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(record))


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("sanitized", False),
        ("contains_landmark_coordinates", True),
        ("non_participant_observation", False),
    ],
)
def test_extract_record_rejects_non_sanitized_or_participant_bearing_records(
    key: str, value: object
) -> None:
    record = _record()
    record[key] = value
    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(record))


@pytest.mark.parametrize(
    "key",
    [
        "normalized_image_landmarks",
        "world_landmarks",
        "landmark_x",
        "landmark_y",
        "landmark_z",
        "participant_id",
    ],
)
def test_extract_record_rejects_participant_or_coordinate_keys(key: str) -> None:
    record = _record()
    record["observation"][key] = []
    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_an_invalid_harness_sha() -> None:
    record = _record()
    record["provenance"]["harness_git_sha"] = "A" * 40
    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(record))


# --- recomputed transform and admission --------------------------------------------------


def test_derive_constant_offset_requires_one_offset_for_every_pair() -> None:
    assert derive_constant_offset_us(_observation()) == 1_000
    assert derive_constant_offset_us(_observation(submitted=[], demuxed=[])) is None
    assert derive_constant_offset_us(_observation(submitted=[10], demuxed=[9, 8])) is None
    assert (
        derive_constant_offset_us(
            _observation(submitted=[1_000_000, 1_033_333], demuxed=[999_000, 1_032_332])
        )
        is None
    )


@pytest.mark.parametrize(
    ("observation", "expected"),
    [
        (_observation(submitted=[], demuxed=[]), [BLOCK_SUBMITTED_PTS_EMPTY]),
        (
            _observation(
                submitted=[0, 1_033_333, 1_066_666],
                demuxed=[0, 1_033_333, 1_066_666],
            ),
            [BLOCK_SUBMITTED_PTS_NOT_POSITIVE],
        ),
        (
            _observation(
                submitted=[1, 1_033_333, 1_066_666],
                demuxed=[-999, 1_032_333, 1_065_666],
            ),
            [BLOCK_DEMUXED_PTS_NEGATIVE],
        ),
        (
            _observation(submitted=[1_000_000, 1_033_333], demuxed=[999_000]),
            [BLOCK_COUNT_MISMATCH],
        ),
        (
            _observation(
                submitted=[1_000_000, 1_033_333, 1_066_666],
                demuxed=[999_000, 1_032_332, 1_065_666],
            ),
            [BLOCK_NON_CONSTANT_TRANSFORM],
        ),
    ],
)
def test_each_timestamp_gate_conjunct_is_load_bearing(
    observation: dict, expected: list[str]
) -> None:
    record = _record(observation=observation)
    assert container_timestamp_blocking_reasons(record) == expected


def test_submitted_pts_must_be_strictly_increasing() -> None:
    record = _record(
        observation=_observation(
            submitted=[1_000_000, 1_000_000, 1_066_666],
            demuxed=[999_000, 999_000, 1_065_666],
        )
    )
    assert BLOCK_SUBMITTED_ORDER in container_timestamp_blocking_reasons(record)


def test_demuxed_pts_must_be_strictly_increasing() -> None:
    record = _record(
        observation=_observation(
            submitted=[1_000_000, 1_033_333, 1_066_666],
            demuxed=[999_000, 999_000, 1_065_666],
        )
    )
    assert BLOCK_DEMUXED_ORDER in container_timestamp_blocking_reasons(record)


def test_unauthorized_device_is_a_blocking_observation() -> None:
    record = _record(environment=_environment(model="SM-S931B"))
    assert container_timestamp_blocking_reasons(record) == [BLOCK_UNAUTHORIZED_TARGET]
    assert not is_authorized_container_timestamp_target(record["environment"])


def test_s25_model_family_and_marketing_name_authorization_are_explicit() -> None:
    assert is_authorized_container_timestamp_target(_environment(model="sm-s938u"))
    assert is_authorized_container_timestamp_target(
        _environment(model="unknown", marketing_name="Galaxy S25 Ultra")
    )
    assert not is_authorized_container_timestamp_target(
        _environment(device_class="emulator", model="SM-S938B")
    )


def test_verdict_and_offset_are_recomputed_not_trusted() -> None:
    record = _record()
    record["observation"]["constant_offset_us"] = 999
    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(record))

    record = _record()
    record["blocking_result"] = True
    record["blocking_reasons"] = [BLOCK_UNAUTHORIZED_TARGET]
    with pytest.raises(ContainerTimestampProbeCheckError):
        extract_record(_transcript(record))


def test_blocking_result_is_valid_for_a_truthful_nonconstant_observation() -> None:
    record = _record(
        observation=_observation(
            submitted=[1_000_000, 1_033_333, 1_066_666],
            demuxed=[999_000, 1_032_332, 1_065_666],
        )
    )
    assert record["blocking_result"] is True
    assert extract_record(_transcript(record)) == record


# --- command status and non-mutating output ------------------------------------------------


def _write(tmp_path: Path, transcript: str) -> str:
    path = tmp_path / "transcript.txt"
    path.write_text(transcript, encoding="utf-8")
    return str(path)


def test_main_returns_usage_without_a_transcript_argument() -> None:
    assert main(["check_container_timestamp_probe.py"]) == EXIT_USAGE


def test_main_returns_zero_and_prints_only_the_record(tmp_path: Path, capsys) -> None:
    record = _record()
    status = main(["check", _write(tmp_path, _transcript(record))])
    captured = capsys.readouterr()

    assert status == EXIT_OK
    assert json.loads(captured.out) == record
    assert captured.err == ""


def test_main_returns_three_and_prints_a_truthful_blocking_record(tmp_path: Path, capsys) -> None:
    record = _record(observation=_observation(submitted=[0, 1_033_333, 1_066_666]))
    status = main(["check", _write(tmp_path, _transcript(record))])
    captured = capsys.readouterr()

    assert status == EXIT_BLOCKING
    assert json.loads(captured.out) == record
    assert BLOCK_SUBMITTED_PTS_NOT_POSITIVE in captured.err


@pytest.mark.parametrize(
    "transcript",
    [_transcript(records=[]), _transcript(_record(), result_line="OK (0 tests)")],
)
def test_main_returns_one_for_untrustworthy_transcripts(tmp_path: Path, transcript: str) -> None:
    assert main(["check", _write(tmp_path, transcript)]) == EXIT_UNTRUSTED


def test_main_never_writes_the_committed_ledger(tmp_path: Path) -> None:
    ledger = (
        Path(__file__).parents[2]
        / "docs/evidence/device-probes/s25-ultra-container-timestamp-ledger.json"
    )
    before = ledger.read_bytes() if ledger.exists() else None

    main(["check", _write(tmp_path, _transcript(_record()))])

    assert (ledger.read_bytes() if ledger.exists() else None) == before


# --- recorded ledger ---------------------------------------------------------------------


def _recorded_repo(tmp_path: Path, record: dict, **ledger_overrides) -> tuple[Path, str]:
    evidence_relative = (
        "docs/evidence/device-probes/s25-ultra-SM-S938B-api36-container-timestamp.json"
    )
    ledger_relative = "docs/evidence/device-probes/s25-ultra-container-timestamp-ledger.json"
    evidence_path = tmp_path / evidence_relative
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(record, indent=2, sort_keys=True) + "\n"
    evidence_path.write_text(payload, encoding="utf-8")
    ledger = {
        "status": "recorded",
        "record_kind": "container_timestamp_probe_device_gate_observation",
        "evidence_file": evidence_relative,
        "evidence_sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
        "device_class": record["environment"]["device_class"],
        "device_identity": record["environment"]["device_identity"],
        "api_level": record["environment"]["api_level"],
        "android_release": record["environment"]["android_release"],
        "timestamp_admission": "admitted" if not record["blocking_result"] else "blocked",
        "recorded_on_utc_date": "2026-08-27",
    }
    ledger.update(ledger_overrides)
    ledger_path = tmp_path / ledger_relative
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    ledger_path.write_text(json.dumps(ledger, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return tmp_path, ledger_relative


def test_verify_recorded_ledger_opens_and_verifies_the_named_evidence(tmp_path: Path) -> None:
    root, relative = _recorded_repo(tmp_path, _record())
    assert verify_recorded_ledger(root, relative)["status"] == "recorded"


def test_verify_recorded_ledger_rejects_a_digest_mutation(tmp_path: Path) -> None:
    root, relative = _recorded_repo(tmp_path, _record())
    ledger = json.loads((root / relative).read_text(encoding="utf-8"))
    evidence = root / ledger["evidence_file"]
    evidence.write_bytes(evidence.read_bytes() + b"tampered")

    with pytest.raises(ContainerTimestampProbeCheckError, match="sha256"):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_wrong_device_identity_api_or_release(
    tmp_path: Path,
) -> None:
    for override in (
        {"device_identity": "samsung/SM-S931B/device/product"},
        {"api_level": 35},
        {"android_release": "15"},
    ):
        root, relative = _recorded_repo(tmp_path / str(len(override)), _record(), **override)
        with pytest.raises(ContainerTimestampProbeCheckError):
            verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_wrong_admission_classification(tmp_path: Path) -> None:
    root, relative = _recorded_repo(tmp_path, _record(), timestamp_admission="blocked")
    with pytest.raises(ContainerTimestampProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_pending_ledger_requires_a_missing_precondition(tmp_path: Path) -> None:
    relative = "ledger.json"
    (tmp_path / relative).write_text(json.dumps({"status": "pending"}), encoding="utf-8")
    with pytest.raises(ContainerTimestampProbeCheckError):
        verify_recorded_ledger(tmp_path, relative)

    (tmp_path / relative).write_text(
        json.dumps(
            {
                "status": "pending",
                "record_kind": "container_timestamp_probe_device_gate_observation",
                "missing_precondition": "no authorized physical S25 attached",
            }
        ),
        encoding="utf-8",
    )
    assert verify_recorded_ledger(tmp_path, relative)["status"] == "pending"
