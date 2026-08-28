from __future__ import annotations

import re
from pathlib import Path

import check_m05b_attribution

ROOT = Path(__file__).parents[2]


def _recipe(target: str) -> str:
    makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
    match = re.search(
        rf"(?m)^{re.escape(target)}:\s*\n(?P<body>(?:\t[^\n]*\n|[ \t]*\n)+)",
        makefile,
    )
    assert match is not None, f"missing Make target {target}"
    return match.group("body")


def test_attribution_gate_consumes_exact_candidate_and_never_mutates_device_state() -> None:
    recipe = _recipe("verify-harness-s25-m05b-attribution")
    lowered = recipe.lower()
    for forbidden in ("assemble", "pm clear", "fetch-model-asset"):
        assert forbidden not in lowered
    assert re.search(r"\badb\b[^\n]*\binstall(?:-multiple)?\b", lowered) is None
    assert "awk '$$2 == \"device\"'" in recipe
    assert "emulator-" in recipe
    assert ":" in recipe
    assert "exec-out cat" in recipe
    assert "shasum -a 256" in recipe
    assert "--serial" in recipe
    assert "--package" in recipe
    assert "--subprocess-exit-code" in recipe
    assert "check_m05b_attribution.py" in recipe
    assert "M05BAttributionDeviceGateTest" in recipe
    assert "is_authorized_five_run_gate_target" in recipe


def test_attribution_producer_is_bounded_fresh_and_reference_only() -> None:
    source = (
        ROOT
        / "apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionDeviceGateTest.kt"
    ).read_text(encoding="utf-8")
    assert "for (cadence in CADENCES)" in source
    assert source.count("LiveCameraComposition.fromAndroid") == 1
    assert "PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION" in source
    assert "FinalizationResult.Published" in source
    assert "export_relative_path" in source
    assert "admitted_callbacks" not in source
    assert "satisfies_s25_attribution_gate" not in source
    assert "pm clear" not in source.lower()
    assert source.count("CADENCES") >= 2


def test_pending_attribution_ledger_is_explicit_and_recorded_verifier_opens_evidence() -> None:
    ledger = ROOT / "docs/evidence/m05b-attribution/s25-short-attribution-ledger.json"
    assert (
        check_m05b_attribution.verify_recorded_ledger(ROOT, str(ledger.relative_to(ROOT)))["status"]
        == "pending"
    )
