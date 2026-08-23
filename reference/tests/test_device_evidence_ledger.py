import hashlib
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[2]

sys.path.insert(0, str(ROOT / "tools"))

import check_five_run_replay
import record_process_kill_evidence
from check_camera_capability_probe import (
    CameraProbeCheckError,
    is_authorized_camera_probe_target,
    satisfies_live_admission,
    verify_recorded_ledger,
)

CAMERA_LEDGER = "docs/evidence/device-probes/s25-ultra-camera-capability-ledger.json"
LIVE_CAPTURE_LEDGER = "docs/evidence/live-capture/s25-ultra-live-capture-ledger.json"


def test_recorded_replay_ledger_rejects_mutated_evidence_digest(tmp_path: Path) -> None:
    evidence = {
        "harness_git_sha": "0123456789abcdef0123456789abcdef01234567",
        "runs_completed": 5,
        "runs": [],
        "environment": {},
        "satisfies_s25_device_gate": False,
    }
    evidence_path = tmp_path / "replay.json"
    evidence_path.write_text(json.dumps(evidence) + "\n", encoding="utf-8")
    ledger_path = tmp_path / "ledger.json"
    ledger_path.write_text(
        json.dumps(
            {
                "status": "recorded",
                "evidence_file": "replay.json",
                "evidence_sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
            }
        ),
        encoding="utf-8",
    )
    evidence_path.write_text(evidence_path.read_text() + "tampered", encoding="utf-8")

    verifier = getattr(check_five_run_replay, "verify_recorded_ledger", None)
    assert callable(verifier), "replay checker must expose recorded-ledger verification"
    with pytest.raises(check_five_run_replay.FiveRunReplayCheckError, match="sha256"):
        verifier(tmp_path, "ledger.json")


def test_recorded_process_kill_ledger_rejects_mutated_evidence_digest(tmp_path: Path) -> None:
    evidence = {
        "harness_git_sha": "0123456789abcdef0123456789abcdef01234567",
        "environment": {},
        "observation": {},
        "satisfies_s25_process_kill_gate": False,
    }
    evidence_path = tmp_path / "process-kill.json"
    evidence_path.write_text(json.dumps(evidence) + "\n", encoding="utf-8")
    ledger_path = tmp_path / "ledger.json"
    ledger_path.write_text(
        json.dumps(
            {
                "status": "recorded",
                "evidence_file": "process-kill.json",
                "evidence_sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
            }
        ),
        encoding="utf-8",
    )
    evidence_path.write_text(evidence_path.read_text() + "tampered", encoding="utf-8")

    verifier = getattr(record_process_kill_evidence, "verify_recorded_ledger", None)
    assert callable(verifier), "process-kill checker must expose recorded-ledger verification"
    with pytest.raises(record_process_kill_evidence.ProcessKillRecordError, match="sha256"):
        verifier(tmp_path, "ledger.json")


@pytest.mark.parametrize("sha", [None, "not-a-commit-sha"])
def test_recorded_replay_ledger_requires_a_well_formed_harness_git_sha(
    tmp_path: Path, sha: str | None
) -> None:
    evidence = {
        "runs_completed": 5,
        "runs": [],
        "environment": {},
        "satisfies_s25_device_gate": False,
    }
    if sha is not None:
        evidence["harness_git_sha"] = sha
    evidence_path = tmp_path / "replay.json"
    evidence_path.write_text(json.dumps(evidence) + "\n", encoding="utf-8")
    (tmp_path / "ledger.json").write_text(
        json.dumps(
            {
                "status": "recorded",
                "evidence_file": "replay.json",
                "evidence_sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
            }
        ),
        encoding="utf-8",
    )

    verifier = check_five_run_replay.verify_recorded_ledger
    with pytest.raises(check_five_run_replay.FiveRunReplayCheckError, match="harness_git_sha"):
        verifier(tmp_path, "ledger.json")


@pytest.mark.parametrize("sha", [None, "not-a-commit-sha"])
def test_recorded_process_kill_ledger_requires_a_well_formed_harness_git_sha(
    tmp_path: Path, sha: str | None
) -> None:
    evidence = {
        "environment": {},
        "observation": {},
        "satisfies_s25_process_kill_gate": False,
    }
    if sha is not None:
        evidence["harness_git_sha"] = sha
    evidence_path = tmp_path / "process-kill.json"
    evidence_path.write_text(json.dumps(evidence) + "\n", encoding="utf-8")
    (tmp_path / "ledger.json").write_text(
        json.dumps(
            {
                "status": "recorded",
                "evidence_file": "process-kill.json",
                "evidence_sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
            }
        ),
        encoding="utf-8",
    )

    verifier = record_process_kill_evidence.verify_recorded_ledger
    with pytest.raises(
        record_process_kill_evidence.ProcessKillRecordError, match="harness_git_sha"
    ):
        verifier(tmp_path, "ledger.json")


def test_s25_process_kill_ledger_reports_pending_or_recorded_status() -> None:
    ledger = json.loads(
        (ROOT / "docs/evidence/device-probes/s25-ultra-process-kill-ledger.json").read_text()
    )
    assert ledger["status"] in {"pending", "recorded"}
    if ledger["status"] == "pending":
        assert ledger["missing_precondition"]
        record_process_kill_evidence.verify_recorded_ledger(
            ROOT, "docs/evidence/device-probes/s25-ultra-process-kill-ledger.json"
        )
        return

    # A recorded ledger must be checked against the record it points at, to the same standard as
    # the five-run gate. Asserting only that an evidence_file key exists would let a "recorded"
    # status point at anything at all.
    assert ledger["evidence_file"]
    recorded = json.loads((ROOT / ledger["evidence_file"]).read_text())
    observation = recorded["observation"]

    # Without a real process death every other condition is trivially satisfied by a session that
    # simply exported cleanly, and the gate would certify crash safety it never tested.
    assert observation["process_crash_confirmed"] is True
    assert observation["export_root_corruption_count"] == 0
    assert observation["staging_residue_count"] == 1
    # A killed process cannot have written a deliberate abort marker.
    assert observation["staging_residue_classification"] == "CrashResidue"
    assert recorded["satisfies_s25_process_kill_gate"] is True

    environment = recorded["environment"]
    assert environment["device_class"] == "physical_device"
    assert environment["manufacturer"].lower() == "samsung"
    assert environment.get("model", "").upper().startswith("SM-S938") or (
        "S25 Ultra" in environment.get("marketing_name", "")
    )


def test_s25_five_run_replay_ledger_reports_pending_or_recorded_status() -> None:
    # Task 5: the five-run S25 device gate. `make verify` runs this in CI, so an absent physical
    # S25 must always surface as an honest "pending" ledger entry here — never silently omitted,
    # and never a fabricated "recorded" status the CI run itself has no device to have earned.
    ledger = json.loads(
        (ROOT / "docs/evidence/replay-five-run/s25-ultra-five-run-replay-ledger.json").read_text()
    )
    assert ledger["status"] in {"pending", "recorded"}
    if ledger["status"] == "pending":
        assert ledger["missing_precondition"]
        check_five_run_replay.verify_recorded_ledger(
            ROOT, "docs/evidence/replay-five-run/s25-ultra-five-run-replay-ledger.json"
        )
    else:
        assert ledger["evidence_file"]
        recorded = json.loads((ROOT / ledger["evidence_file"]).read_text())
        assert recorded["runs_completed"] == 5
        assert len(recorded["runs"]) == 5
        assert all(
            run["published"] and run["validated_by_reference_cli"] for run in recorded["runs"]
        )
        # A recorded gate must be evidence about a pose pipeline that actually detected something.
        # Five identical runs of an empty pipeline agree on every input-side field, so without this
        # a "recorded" ledger could point at evidence of nothing.
        assert all(run["pose_detected_records"] > 0 for run in recorded["runs"])
        assert all(
            sum(1 for present in run["pose_present_per_frame"] if present)
            == run["pose_detected_records"]
            for run in recorded["runs"]
        )
        assert recorded["satisfies_s25_device_gate"] is True
        assert recorded["environment"]["device_class"] == "physical_device"
        # Same authorization rule as isAuthorizedFiveRunGateTarget and
        # tools/check_five_run_replay.py: model number is authoritative, because retail S25 Ultra
        # firmware may report ro.config.marketing_name as empty. Asserting marketing name alone
        # rejected the genuine device.
        environment = recorded["environment"]
        assert environment["manufacturer"].lower() == "samsung"
        assert environment.get("model", "").upper().startswith("SM-S938") or (
            "S25 Ultra" in environment.get("marketing_name", "")
        )


def test_s25_camera_capability_ledger_reports_pending_or_recorded_status() -> None:
    # Slice 5 precondition, design section 8. `make verify` runs this in CI, so an absent physical
    # S25 must always surface as an honest "pending" entry naming what was never observed — never
    # silently omitted, and never a fabricated "recorded" status a CI run had no device to earn.
    ledger = json.loads((ROOT / CAMERA_LEDGER).read_text())
    assert ledger["status"] in {"pending", "recorded"}
    if ledger["status"] == "pending":
        assert ledger["missing_precondition"]
        return

    # A recorded entry is checked all the way through to the record it points at: the digest it
    # pinned, every required observation, the authorization rule re-applied, and — the reason this
    # branch exists — the admitted/blocked classification recomputed from the observations rather
    # than read off the ledger. An honest "blocked" recording is valid; a blocking observation
    # recorded as admitted is the failure being caught.
    assert ledger["evidence_file"]
    recorded = json.loads((ROOT / ledger["evidence_file"]).read_text())
    payload = (ROOT / ledger["evidence_file"]).read_bytes()
    assert ledger["evidence_sha256"] == hashlib.sha256(payload).hexdigest()

    observation = recorded["observation"]
    for field in (
        "camera_id",
        "timestamp_source",
        "source_dataspace_raw",
        "source_color_standard",
        "source_color_range",
        "underlying_image_available",
        "pixel_format",
    ):
        assert field in observation

    assert recorded["sanitized"] is True
    assert recorded["contains_landmark_coordinates"] is False
    assert recorded["non_participant_observation"] is True
    assert is_authorized_camera_probe_target(recorded["environment"])
    assert ledger["live_admission"] == (
        "admitted" if satisfies_live_admission(recorded) else "blocked"
    )

    # The same entry, checked by the shared implementation the Make target's checker uses, so the
    # ledger and the wrapper can never disagree about what a recorded entry has to satisfy.
    verify_recorded_ledger(ROOT, CAMERA_LEDGER)


def test_s25_live_capture_ledger_reports_pending_or_verifies_recorded_content() -> None:
    from check_live_capture_run import verify_recorded_ledger as verify_live_ledger

    ledger = json.loads((ROOT / LIVE_CAPTURE_LEDGER).read_text())
    assert ledger["status"] in {"pending", "recorded"}
    if ledger["status"] == "pending":
        assert ledger["missing_precondition"]
    else:
        verified = verify_live_ledger(ROOT, LIVE_CAPTURE_LEDGER)
        assert verified["live_admission"] == "admitted"


def test_the_committed_camera_ledger_passes_the_shared_recorded_ledger_check() -> None:
    verify_recorded_ledger(ROOT, CAMERA_LEDGER)


def test_a_camera_ledger_recording_a_blocking_observation_as_admitted_is_rejected(
    tmp_path: Path,
) -> None:
    """The recorded branch's whole purpose, exercised while the real ledger is still pending.

    `verify_recorded_ledger` is the implementation the branch above delegates to, so mutating a
    synthetic recorded entry here is what proves that branch is not vacuous — a real S25 has not
    been attached yet, so nothing else would ever execute it.
    """
    record = {
        "artifact_schema_version": "1.0.0",
        "record_kind": "camera_capability_probe_device_gate_observation",
        "sanitized": True,
        "contains_landmark_coordinates": False,
        "non_participant_observation": True,
        "observation_subject": "device_camera_capability",
        "emitted_by": "com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest",
        "environment": {
            "device_class": "physical_device",
            "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
            "device_fingerprint": "samsung/pa3qxins/pa3q:16/BP4A.251205.006/x:user/release-keys",
            "api_level": 36,
            "android_release": "16",
            "abi": "arm64-v8a",
            "manufacturer": "samsung",
            "model": "SM-S938B",
            "marketing_name": "",
        },
        "provenance": {
            "os_build": "BP4A.251205.006",
            "camerax_version": "1.6.1",
            "probe_git_sha": "0123456789abcdef0123456789abcdef01234567",
        },
        "observation": {
            "camera_id": "0",
            "timestamp_source": "UNKNOWN",
            "source_dataspace_raw": None,
            "source_color_standard": None,
            "source_color_range": None,
            "underlying_image_available": False,
            "pixel_format": "YUV_420_888",
        },
        "authorized_s25_target": True,
        "live_admission_blocking_reasons": [
            "timestamp_source_not_realtime",
            "underlying_image_bridge_unavailable",
            "raw_dataspace_unknown",
            "colour_standard_undecoded",
            "colour_range_undecoded",
        ],
        "satisfies_s25_camera_live_admission": False,
    }
    evidence_relative = "docs/evidence/device-probes/observed.json"
    ledger_relative = "docs/evidence/device-probes/ledger.json"
    (tmp_path / "docs/evidence/device-probes").mkdir(parents=True)
    payload = json.dumps(record, indent=2, sort_keys=True) + "\n"
    (tmp_path / evidence_relative).write_text(payload, encoding="utf-8")

    def write_ledger(live_admission: str) -> None:
        (tmp_path / ledger_relative).write_text(
            json.dumps(
                {
                    "status": "recorded",
                    "evidence_file": evidence_relative,
                    "evidence_sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
                    "live_admission": live_admission,
                    "device_class": "physical_device",
                    "device_identity": "samsung/SM-S938B/pa3q/pa3qxins",
                    "api_level": 36,
                    "android_release": "16",
                    "recorded_on_utc_date": "2026-08-21",
                },
                indent=2,
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )

    write_ledger("blocked")
    assert verify_recorded_ledger(tmp_path, ledger_relative)["live_admission"] == "blocked"

    write_ledger("admitted")
    with pytest.raises(CameraProbeCheckError):
        verify_recorded_ledger(tmp_path, ledger_relative)
