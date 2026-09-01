"""Mechanical contract for the Task 5b exact-candidate physical purge gate."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAKEFILE = ROOT / "Makefile"
LEDGER = ROOT / "docs/evidence/m05b-purge/s25-ultra-physical-purge-ledger.json"
_SPEC = importlib.util.spec_from_file_location(
    "check_m05b_physical_purge_build",
    ROOT / "tools/check_m05b_physical_purge.py",
)
assert _SPEC is not None and _SPEC.loader is not None
checker = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(checker)


def _recipe() -> str:
    makefile = MAKEFILE.read_text(encoding="utf-8")
    return makefile.split("verify-harness-s25-m05b-physical-purge:", 1)[1].split(
        "\nverify-harness-s25-live-capture:", 1
    )[0]


def test_physical_purge_target_is_exact_candidate_checker_owned_and_never_writes_ledger() -> None:
    makefile = MAKEFILE.read_text(encoding="utf-8")
    recipe = _recipe()
    phony = makefile.split(".PHONY:", 1)[1].splitlines()[0]

    assert "verify-harness-s25-m05b-physical-purge" in phony
    assert recipe.lstrip().startswith("@set -eu")
    assert "awk '$$2==\"device\"'" in recipe
    assert "emulator-*)" in recipe
    assert "SM-S938*" in recipe
    assert "git status --porcelain" in recipe
    assert "fetch-model-asset" in recipe
    assert ":androidApp:assembleDebug :androidApp:assembleDebugAndroidTest" in recipe
    assert recipe.count("exec-out cat") >= 2
    assert "actual_app_sha256" in recipe and "expected_app_sha256" in recipe
    assert "actual_test_sha256" in recipe and "expected_test_sha256" in recipe
    assert '[ "$$actual_test_sha256" != "$$expected_test_sha256" ]' in recipe
    assert "PhysicalPurgeDeviceGateTest" in recipe
    assert "instrument_status" in recipe
    assert "tools/check_m05b_physical_purge.py" in recipe
    assert "--app-apk-sha256" in recipe and "--test-apk-sha256" in recipe
    assert "OK (" not in recipe
    assert "s25-ultra-physical-purge-ledger.json" not in recipe
    assert "rm -rf" not in recipe


def test_pending_ledger_is_closed_and_cannot_claim_recorded_evidence() -> None:
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))

    assert ledger == {
        "evidence_file": None,
        "evidence_sha256": None,
        "reason": "physical_purge_rehearsal_not_recorded",
        "required_device": "samsung_galaxy_s25_ultra",
        "status": "pending",
    }


def test_recorded_ledger_helper_opens_and_hashes_the_exact_evidence(tmp_path: Path) -> None:
    evidence = {
        "record_kind": "m05b_physical_purge_host_evidence",
        "sanitized": True,
        "non_participant_observation": True,
        "independent_host_post_scan_empty": True,
        "tombstone_independently_validated": True,
        "blocking_reasons": [],
        "admitted": True,
    }
    relative = "docs/evidence/m05b-purge/record.json"
    path = tmp_path / relative
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps(evidence), encoding="utf-8")
    ledger = {
        "status": "recorded",
        "required_device": "samsung_galaxy_s25_ultra",
        "reason": None,
        "evidence_file": relative,
        "evidence_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }

    assert checker.verify_recorded_ledger(tmp_path, ledger) == []
    path.write_text("{}", encoding="utf-8")
    assert "physical_purge_evidence_digest_mismatch" in checker.verify_recorded_ledger(
        tmp_path, ledger
    )
