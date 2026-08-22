"""Hermetic checks for the S25 camera-capability probe transcript wrapper (Slice 5, Task 1).

No device, no `adb`, no emulator. Two vacuity holes are closed here.

The first is the wrapper's verdict. `am instrument` reports test failure in its output while still
exiting 0, so `make verify-harness-s25-camera-probe` has to read the transcript — and reading it
for a generic success line accepts `OK (0 tests)`, accepts a run of some unrelated suite, and
accepts a run that emitted no observation at all. The verdict belongs to this checker's exit
status.

The second is the meaning of "the probe succeeded". A probe that observes `UNKNOWN` timestamps, an
unknown DataSpace, or no underlying image at all has succeeded perfectly: it made a real
observation. What it has not done is authorize live capture. Design section 8 stops the milestone
on exactly that outcome, so the checker distinguishes "could not be trusted" from "trusted, and
blocking", and returns a different non-zero status for each.

`live_admission_blocking_reasons` is the host-side re-derivation of what
`cameraLiveAdmissionBlockingReasons` (Kotlin, `shared/commonMain`) computes. Neither side may ever
take the record's own `satisfies_s25_camera_live_admission` field as an input.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from check_camera_capability_probe import (
    BLOCK_COLOUR_RANGE,
    BLOCK_COLOUR_STANDARD,
    BLOCK_RAW_DATASPACE,
    BLOCK_TIMESTAMP_SOURCE,
    BLOCK_UNAUTHORIZED_TARGET,
    BLOCK_UNDERLYING_IMAGE,
    DATASPACE_UNKNOWN,
    EXIT_BLOCKING,
    EXIT_OK,
    EXIT_UNTRUSTED,
    EXIT_USAGE,
    CameraProbeCheckError,
    extract_record,
    is_authorized_camera_probe_target,
    live_admission_blocking_reasons,
    main,
    require_exact_suite,
    satisfies_live_admission,
    verify_recorded_ledger,
)

SUITE_MARKER = "class=com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest"


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
    timestamp_source: str = "REALTIME",
    source_dataspace_raw: int | None = 146931712,
    source_color_standard: str | None = "BT601",
    source_color_range: str | None = "LIMITED",
    underlying_image_available: bool = True,
) -> dict:
    return {
        "camera_id": "0",
        "timestamp_source": timestamp_source,
        "source_dataspace_raw": source_dataspace_raw,
        "source_color_standard": source_color_standard,
        "source_color_range": source_color_range,
        "underlying_image_available": underlying_image_available,
        "pixel_format": "YUV_420_888",
    }


def _record(*, environment: dict | None = None, observation: dict | None = None) -> dict:
    environment = _environment() if environment is None else environment
    observation = _observation() if observation is None else observation
    record = {
        "artifact_schema_version": "1.0.0",
        "record_kind": "camera_capability_probe_device_gate_observation",
        "sanitized": True,
        "contains_landmark_coordinates": False,
        "non_participant_observation": True,
        "observation_subject": "device_camera_capability",
        "emitted_by": "com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest",
        "environment": environment,
        "provenance": {
            "os_build": "BP4A.251205.006",
            "camerax_version": "1.6.1",
            "probe_git_sha": "0123456789abcdef0123456789abcdef01234567",
        },
        "observation": observation,
    }
    # The device emits a record whose flag the Kotlin renderer already derived; the checker
    # recomputes it and refuses a record whose declared verdict disagrees, so the fixtures here
    # must be self-consistent to be about anything else.
    reasons = live_admission_blocking_reasons(record)
    record["authorized_s25_target"] = is_authorized_camera_probe_target(environment)
    record["live_admission_blocking_reasons"] = reasons
    record["satisfies_s25_camera_live_admission"] = not reasons
    return record


def _transcript(
    record: dict | None = None,
    *,
    suite: str = SUITE_MARKER,
    result_line: str = "OK (1 test)",
    records: list[dict] | None = None,
) -> str:
    if records is None:
        records = [] if record is None else [record]
    lines = [f"INSTRUMENTATION_STATUS: {suite}"]
    lines += [
        "INSTRUMENTATION_STATUS: CameraCapabilityEvidence=" + json.dumps(item, sort_keys=True)
        for item in records
    ]
    lines += ["INSTRUMENTATION_STATUS_CODE: 0", "", result_line, ""]
    return "\n".join(lines)


# --- transcript trust ---------------------------------------------------------------------


def test_require_exact_suite_accepts_a_genuine_single_passing_run() -> None:
    require_exact_suite(_transcript(_record()))


def test_require_exact_suite_rejects_a_vacuous_zero_test_run() -> None:
    with pytest.raises(CameraProbeCheckError):
        require_exact_suite(_transcript(_record(), result_line="OK (0 tests)"))


def test_require_exact_suite_rejects_an_unrelated_suite() -> None:
    with pytest.raises(CameraProbeCheckError):
        require_exact_suite(
            _transcript(
                _record(),
                suite="class=com.calisthenics.diagnostic.probe.StorageCapabilityProbeTest",
            )
        )


def test_require_exact_suite_rejects_a_failing_run() -> None:
    with pytest.raises(CameraProbeCheckError):
        require_exact_suite(_transcript(_record(), result_line="FAILURES!!!"))


def test_extract_record_requires_exactly_one_record() -> None:
    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(records=[]))
    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(records=[_record(), _record()]))


def test_extract_record_reads_a_well_formed_record() -> None:
    record = _record()

    assert extract_record(_transcript(record)) == record


@pytest.mark.parametrize(
    "path",
    [
        ("record_kind",),
        ("emitted_by",),
        ("environment", "device_identity"),
        ("environment", "device_fingerprint"),
        ("environment", "api_level"),
        ("environment", "android_release"),
        ("environment", "abi"),
        ("environment", "manufacturer"),
        ("environment", "model"),
        ("environment", "marketing_name"),
        ("environment", "device_class"),
        ("provenance", "os_build"),
        ("provenance", "camerax_version"),
        ("provenance", "probe_git_sha"),
        ("observation", "camera_id"),
        ("observation", "timestamp_source"),
        ("observation", "source_dataspace_raw"),
        ("observation", "source_color_standard"),
        ("observation", "source_color_range"),
        ("observation", "underlying_image_available"),
        ("observation", "pixel_format"),
    ],
)
def test_extract_record_rejects_a_record_missing_any_required_field(path: tuple[str, ...]) -> None:
    record = _record()
    holder = record
    for key in path[:-1]:
        holder = holder[key]
    del holder[path[-1]]

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_a_foreign_record_kind() -> None:
    record = _record()
    record["record_kind"] = "process_kill_during_export_device_gate_observation"

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_an_unsanitized_or_participant_bearing_record() -> None:
    for key, value in (
        ("sanitized", False),
        ("contains_landmark_coordinates", True),
        ("non_participant_observation", False),
    ):
        record = _record()
        record[key] = value
        with pytest.raises(CameraProbeCheckError):
            extract_record(_transcript(record))


def test_extract_record_rejects_a_record_carrying_landmark_coordinates() -> None:
    record = _record()
    record["observation"]["normalized_image_landmarks"] = [[0.1, 0.2]]

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_a_probe_sha_that_is_not_a_commit() -> None:
    record = _record()
    record["provenance"]["probe_git_sha"] = "not-a-sha"

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_a_declared_verdict_that_disagrees_with_the_observations() -> None:
    # The record's own flag is never an input to the verdict, and a record whose flag contradicts
    # its observations has been edited between the device and here.
    record = _record()
    record["satisfies_s25_camera_live_admission"] = True
    record["live_admission_blocking_reasons"] = []
    record["observation"]["timestamp_source"] = "UNKNOWN"

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_a_flipped_verdict_whose_reason_list_still_matches() -> None:
    # Only the verdict is edited; the reason list still agrees with the observations. Checking the
    # reasons alone would accept this, and the verdict is the field every downstream reader keys on.
    record = _record(observation=_observation(timestamp_source="UNKNOWN"))
    record["satisfies_s25_camera_live_admission"] = True

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_an_emptied_reason_list_whose_verdict_still_matches() -> None:
    # The mirror image: only the reasons are edited. A record that blocks without saying why hides
    # the finding design section 8 stops the milestone on.
    record = _record(observation=_observation(timestamp_source="UNKNOWN"))
    record["live_admission_blocking_reasons"] = []

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_extract_record_rejects_a_declared_authorization_disagreeing_with_the_environment() -> None:
    record = _record()
    record["authorized_s25_target"] = False

    with pytest.raises(CameraProbeCheckError):
        extract_record(_transcript(record))


def test_satisfies_live_admission_ignores_the_records_own_declared_verdict() -> None:
    # Read as a claim rather than derived, this record admits live capture on an UNKNOWN clock.
    record = _record(observation=_observation(timestamp_source="UNKNOWN"))
    record["satisfies_s25_camera_live_admission"] = True
    record["live_admission_blocking_reasons"] = []

    assert satisfies_live_admission(record) is False
    assert live_admission_blocking_reasons(record) == [BLOCK_TIMESTAMP_SOURCE]


# --- the gate: one named case per load-bearing conjunct -----------------------------------


def test_live_admission_holds_for_realtime_known_colour_on_the_authorized_s25() -> None:
    record = _record()

    assert live_admission_blocking_reasons(record) == []
    assert satisfies_live_admission(record) is True


def test_live_admission_is_blocked_by_a_non_realtime_timestamp_source() -> None:
    record = _record(observation=_observation(timestamp_source="UNKNOWN"))

    assert live_admission_blocking_reasons(record) == [BLOCK_TIMESTAMP_SOURCE]
    assert satisfies_live_admission(record) is False


def test_live_admission_is_blocked_by_an_unavailable_underlying_image_bridge() -> None:
    record = _record(
        observation=_observation(
            underlying_image_available=False,
            source_dataspace_raw=None,
            source_color_standard=None,
            source_color_range=None,
        )
    )

    assert BLOCK_UNDERLYING_IMAGE in live_admission_blocking_reasons(record)
    assert satisfies_live_admission(record) is False


def test_live_admission_is_blocked_by_an_unavailable_bridge_even_with_colour_metadata() -> None:
    record = _record(observation=_observation(underlying_image_available=False))

    assert live_admission_blocking_reasons(record) == [BLOCK_UNDERLYING_IMAGE]
    assert satisfies_live_admission(record) is False


def test_live_admission_is_blocked_by_the_unknown_raw_dataspace_sentinel() -> None:
    record = _record(observation=_observation(source_dataspace_raw=DATASPACE_UNKNOWN))

    assert live_admission_blocking_reasons(record) == [BLOCK_RAW_DATASPACE]
    assert satisfies_live_admission(record) is False


def test_live_admission_is_blocked_by_an_absent_raw_dataspace() -> None:
    record = _record(observation=_observation(source_dataspace_raw=None))

    assert live_admission_blocking_reasons(record) == [BLOCK_RAW_DATASPACE]


def test_live_admission_is_blocked_by_an_undecoded_colour_standard() -> None:
    record = _record(observation=_observation(source_color_standard=None))

    assert live_admission_blocking_reasons(record) == [BLOCK_COLOUR_STANDARD]


def test_live_admission_is_blocked_by_an_undecoded_colour_range() -> None:
    record = _record(observation=_observation(source_color_range=None))

    assert live_admission_blocking_reasons(record) == [BLOCK_COLOUR_RANGE]


def test_live_admission_is_blocked_on_an_emulator() -> None:
    record = _record(environment=_environment(device_class="emulator"))

    assert live_admission_blocking_reasons(record) == [BLOCK_UNAUTHORIZED_TARGET]


def test_live_admission_is_blocked_on_another_samsung_physical_device() -> None:
    record = _record(environment=_environment(model="SM-S931B"))

    assert live_admission_blocking_reasons(record) == [BLOCK_UNAUTHORIZED_TARGET]


def test_live_admission_is_blocked_on_a_non_samsung_device_with_the_s25_model_prefix() -> None:
    record = _record(environment=_environment(manufacturer="notsamsung"))

    assert live_admission_blocking_reasons(record) == [BLOCK_UNAUTHORIZED_TARGET]


def test_every_blocking_reason_is_reported_together_in_the_kotlin_order() -> None:
    record = _record(
        environment=_environment(device_class="emulator"),
        observation=_observation(
            timestamp_source="UNKNOWN",
            underlying_image_available=False,
            source_dataspace_raw=None,
            source_color_standard=None,
            source_color_range=None,
        ),
    )

    assert live_admission_blocking_reasons(record) == [
        BLOCK_TIMESTAMP_SOURCE,
        BLOCK_UNDERLYING_IMAGE,
        BLOCK_RAW_DATASPACE,
        BLOCK_COLOUR_STANDARD,
        BLOCK_COLOUR_RANGE,
        BLOCK_UNAUTHORIZED_TARGET,
    ]


# --- authorization, delegated to the one rule ---------------------------------------------


def test_authorizes_the_s25_ultra_by_model_when_marketing_name_is_absent() -> None:
    assert is_authorized_camera_probe_target(_environment(marketing_name="", model="SM-S938B"))


def test_authorizes_every_s25_ultra_regional_model() -> None:
    for model in ("SM-S938B", "SM-S938U", "SM-S938N", "SM-S938W", "sm-s938b"):
        assert is_authorized_camera_probe_target(_environment(model=model)), model


def test_still_authorizes_on_marketing_name_when_the_model_is_unrecognized() -> None:
    assert is_authorized_camera_probe_target(
        _environment(model="unknown", marketing_name="Galaxy S25 Ultra")
    )


def test_rejects_an_emulator_regardless_of_model_or_marketing_name() -> None:
    assert not is_authorized_camera_probe_target(
        _environment(device_class="emulator", model="SM-S938B", marketing_name="Galaxy S25 Ultra")
    )


# --- exit status --------------------------------------------------------------------------


def _write(tmp_path: Path, transcript: str) -> str:
    path = tmp_path / "transcript.txt"
    path.write_text(transcript, encoding="utf-8")
    return str(path)


def test_main_returns_usage_status_without_a_transcript_argument() -> None:
    assert main(["check_camera_capability_probe.py"]) == EXIT_USAGE


def test_main_returns_zero_for_an_admissible_observation(tmp_path: Path, capsys) -> None:
    status = main(["check", _write(tmp_path, _transcript(_record()))])
    printed = capsys.readouterr().out

    assert status == EXIT_OK
    # The record must be printed verbatim for the separate human recording step, exactly as the
    # process-kill and five-run checkers do; this checker never writes the ledger itself.
    assert json.loads(printed.strip().splitlines()[-1]) == _record()
    assert "docs/evidence" in printed


def test_main_returns_a_distinct_status_for_a_successful_but_blocking_observation(
    tmp_path: Path, capsys
) -> None:
    record = _record(observation=_observation(timestamp_source="UNKNOWN"))
    status = main(["check", _write(tmp_path, _transcript(record))])
    printed = capsys.readouterr()

    assert status == EXIT_BLOCKING
    assert status != EXIT_UNTRUSTED
    # A blocking observation is still evidence and must still be printed for recording; design
    # section 8 stops the milestone on it rather than discarding it.
    assert json.loads(printed.out.strip().splitlines()[-1]) == record
    assert BLOCK_TIMESTAMP_SOURCE in printed.out + printed.err


def test_main_returns_the_untrusted_status_for_a_transcript_it_cannot_verify(
    tmp_path: Path,
) -> None:
    assert main(["check", _write(tmp_path, _transcript(records=[]))]) == EXIT_UNTRUSTED


def test_main_never_writes_the_committed_ledger(tmp_path: Path) -> None:
    ledger = (
        Path(__file__).parents[2]
        / "docs/evidence/device-probes/s25-ultra-camera-capability-ledger.json"
    )
    before = ledger.read_bytes()

    main(["check", _write(tmp_path, _transcript(_record()))])

    assert ledger.read_bytes() == before


# --- the recorded-ledger branch ------------------------------------------------------------


def _recorded_repo(tmp_path: Path, record: dict, **ledger_overrides) -> tuple[Path, str]:
    """A hermetic repo root holding one recorded ledger and the record it points at."""
    evidence_relative = (
        "docs/evidence/device-probes/s25-ultra-SM-S938B-api36-camera-capability.json"
    )
    ledger_relative = "docs/evidence/device-probes/s25-ultra-camera-capability-ledger.json"
    payload = json.dumps(record, indent=2, sort_keys=True) + "\n"
    (tmp_path / "docs/evidence/device-probes").mkdir(parents=True, exist_ok=True)
    (tmp_path / evidence_relative).write_text(payload, encoding="utf-8")
    ledger = {
        "status": "recorded",
        "evidence_file": evidence_relative,
        "evidence_sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
        "live_admission": "admitted"
        if record["satisfies_s25_camera_live_admission"]
        else "blocked",
        "device_class": record["environment"]["device_class"],
        "device_identity": record["environment"]["device_identity"],
        "api_level": record["environment"]["api_level"],
        "android_release": record["environment"]["android_release"],
        "recorded_on_utc_date": "2026-08-21",
        "note": "camera capability probe observation",
    }
    ledger.update(ledger_overrides)
    (tmp_path / ledger_relative).write_text(
        json.dumps(ledger, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return tmp_path, ledger_relative


def test_verify_recorded_ledger_accepts_an_admitted_observation(tmp_path: Path) -> None:
    root, relative = _recorded_repo(tmp_path, _record())

    assert verify_recorded_ledger(root, relative)["live_admission"] == "admitted"


def test_verify_recorded_ledger_accepts_an_honestly_blocked_observation(tmp_path: Path) -> None:
    # A blocking observation is a legitimate recorded result. What it must never be is recorded as
    # admitted.
    root, relative = _recorded_repo(
        tmp_path, _record(observation=_observation(timestamp_source="UNKNOWN"))
    )

    assert verify_recorded_ledger(root, relative)["live_admission"] == "blocked"


def test_verify_recorded_ledger_rejects_a_blocked_observation_recorded_as_admitted(
    tmp_path: Path,
) -> None:
    root, relative = _recorded_repo(
        tmp_path,
        _record(observation=_observation(timestamp_source="UNKNOWN")),
        live_admission="admitted",
    )

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_an_admitted_observation_recorded_as_blocked(
    tmp_path: Path,
) -> None:
    root, relative = _recorded_repo(tmp_path, _record(), live_admission="blocked")

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_a_digest_that_does_not_match_the_record(
    tmp_path: Path,
) -> None:
    root, relative = _recorded_repo(tmp_path, _record())
    ledger_path = root / relative
    ledger = json.loads(ledger_path.read_text())
    ledger["evidence_sha256"] = "0" * 64
    ledger_path.write_text(json.dumps(ledger, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_a_record_edited_after_its_digest_was_taken(
    tmp_path: Path,
) -> None:
    root, relative = _recorded_repo(
        tmp_path, _record(observation=_observation(timestamp_source="UNKNOWN"))
    )
    evidence_path = root / json.loads((root / relative).read_text())["evidence_file"]
    record = json.loads(evidence_path.read_text())
    record["observation"]["timestamp_source"] = "REALTIME"
    evidence_path.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_an_unauthorized_observing_device(tmp_path: Path) -> None:
    root, relative = _recorded_repo(
        tmp_path, _record(environment=_environment(device_class="emulator"))
    )

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_identity_fields_that_disagree_with_the_record(
    tmp_path: Path,
) -> None:
    root, relative = _recorded_repo(
        tmp_path, _record(), device_identity="samsung/SM-S931B/dm3q/dm3qxx"
    )

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_rejects_a_missing_evidence_file(tmp_path: Path) -> None:
    root, relative = _recorded_repo(tmp_path, _record())
    (root / json.loads((root / relative).read_text())["evidence_file"]).unlink()

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(root, relative)


def test_verify_recorded_ledger_requires_a_pending_entry_to_name_its_missing_observation(
    tmp_path: Path,
) -> None:
    relative = "docs/evidence/device-probes/s25-ultra-camera-capability-ledger.json"
    (tmp_path / "docs/evidence/device-probes").mkdir(parents=True, exist_ok=True)
    (tmp_path / relative).write_text(json.dumps({"status": "pending"}), encoding="utf-8")

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(tmp_path, relative)

    (tmp_path / relative).write_text(
        json.dumps({"status": "pending", "missing_precondition": "no physical S25 attached"}),
        encoding="utf-8",
    )

    assert verify_recorded_ledger(tmp_path, relative)["status"] == "pending"


def test_verify_recorded_ledger_rejects_an_unknown_status(tmp_path: Path) -> None:
    relative = "docs/evidence/device-probes/s25-ultra-camera-capability-ledger.json"
    (tmp_path / "docs/evidence/device-probes").mkdir(parents=True, exist_ok=True)
    (tmp_path / relative).write_text(json.dumps({"status": "verified"}), encoding="utf-8")

    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(tmp_path, relative)
