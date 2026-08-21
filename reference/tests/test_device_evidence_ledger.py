import json
from pathlib import Path

ROOT = Path(__file__).parents[2]


def test_s25_process_kill_ledger_reports_pending_or_recorded_status() -> None:
    ledger = json.loads(
        (ROOT / "docs/evidence/device-probes/s25-ultra-process-kill-ledger.json").read_text()
    )
    assert ledger["status"] in {"pending", "recorded"}
    if ledger["status"] == "pending":
        assert ledger["missing_precondition"]
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
