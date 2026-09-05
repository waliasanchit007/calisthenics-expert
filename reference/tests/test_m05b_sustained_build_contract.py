from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).parents[2]
ENVELOPE_GATE = ROOT / (
    "apps/diagnostic-harness/androidApp/src/androidTest/kotlin/"
    "com/calisthenics/diagnostic/probe/M05BEnvelopeDeviceGateTest.kt"
)
RUN_STATE_STORE = ROOT / (
    "apps/diagnostic-harness/androidApp/src/androidTest/kotlin/"
    "com/calisthenics/diagnostic/composition/M05BDeviceRunStateStore.kt"
)
SUSTAINED_GATE = ROOT / (
    "apps/diagnostic-harness/androidApp/src/androidTest/kotlin/"
    "com/calisthenics/diagnostic/composition/M05BSustainedDeviceGateTest.kt"
)


def _recipe(target: str) -> str:
    makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
    match = re.search(
        rf"(?m)^{re.escape(target)}:\s*\n(?P<body>(?:\t[^\n]*\n|[ \t]*\n)+)",
        makefile,
    )
    assert match is not None, f"missing Make target {target}"
    return match.group("body")


def test_envelope_gate_atomically_persists_exact_frozen_inputs_only_after_admission() -> None:
    source = ENVELOPE_GATE.read_text(encoding="utf-8")

    assert "M05BFrozenRunInputs.bind" in source
    assert "persistFrozenInputs" in source
    assert source.index("if (admitted)") < source.index("persistFrozenInputs")
    assert "output.fd.sync()" in source
    assert "Os.rename" in source
    assert "fsyncDirectory" in source
    assert "removeStaleFrozenInputs" in source


def test_sustained_gate_consumes_exact_candidate_and_never_mutates_device_state() -> None:
    recipe = _recipe("verify-harness-s25-m05b-sustained")
    lowered = recipe.lower()

    for forbidden in ("assemble", "pm clear", "fetch-model-asset"):
        assert forbidden not in lowered
    assert re.search(r"\badb\b[^\n]*\binstall(?:-multiple)?\b", lowered) is None
    assert "awk '$$2 == \"device\"'" in recipe
    assert "emulator-" in recipe
    assert "*:*" in recipe
    assert "exec-out" in recipe
    assert "check_m05b_sustained_run.py" in recipe
    assert "M05BSustainedDeviceGateTest" in recipe
    assert "M05B_CANDIDATE_IDENTITY_FILE" in recipe
    assert "git status --porcelain" in recipe


def test_sustained_gate_requires_host_verified_recorded_attribution_v2() -> None:
    recipe = _recipe("verify-harness-s25-m05b-sustained")
    attribution_start = recipe.index("verify_recorded_attribution_v2_ledger")
    envelope_start = recipe.index("$(VENV_PYTHON) -c 'import json", attribution_start)
    attribution_recipe = recipe[attribution_start:envelope_start]

    assert "docs/evidence/m05b-attribution-v2/s25-attribution-v2-ledger.json" in recipe
    assert "verify_recorded_attribution_v2_ledger" in recipe
    assert "satisfies_s25_attribution_v2_gate" in recipe
    assert "docs/evidence/m05b-attribution/s25-short-attribution-ledger.json" not in recipe
    assert 'value.get("status")' not in attribution_recipe


def test_sustained_gate_does_not_accept_device_declared_attribution_success() -> None:
    recipe = _recipe("verify-harness-s25-m05b-sustained")
    attribution_start = recipe.index("verify_recorded_attribution_v2_ledger")

    assert "physical_evidence_status" in recipe[attribution_start:]
    assert 'status == "recorded"' not in recipe[attribution_start:]
    assert "satisfies_s25_attribution_v2_gate" in recipe[attribution_start:]


def test_device_owns_attempt_recovery_from_published_exports_before_new_admission() -> None:
    source = RUN_STATE_STORE.read_text(encoding="utf-8")

    assert "M05BFrozenRunInputs.decode" in source
    assert "roots.exportsRoot" in source
    assert "M05BPublishedAttemptRecovery.recover" in source
    assert "run-admission.json" in source
    assert "output.fd.sync()" in source
    assert "Os.rename" in source
    assert "fsyncDirectory" in source
    assert "transport_lost" not in source.lower()
    assert "outcome = M05BAttemptOutcome.BLOCKED" in source
    assert "recordedOutcome ?: M05BAttemptOutcome.BLOCKED" in source


def test_sustained_device_gate_recovers_before_admission_and_emits_only_durable_references() -> (
    None
):
    source = SUSTAINED_GATE.read_text(encoding="utf-8")

    assert source.index("recoverPublishedAttempts()") < source.index(
        "LiveCameraComposition.fromAndroid"
    )
    assert "M05BSustainedRunController.forComposition" in source
    assert "M05BLiveRunAdmission" in source
    assert "recordPublishedAttempt" in source
    assert source.index("validateM05BSustainedObservation(observation") < source.index(
        "state.recordPublishedAttempt"
    )
    assert "published.copy(outcome = outcome)" in source
    assert "renderM05BSustainedObservation" in source
    assert "run-admission.json" in source
    assert "manifest.json" in source
    assert "frames.ndjson" in source
    assert "telemetry.ndjson" in source
    assert "InstrumentationRegistry.sendStatus" not in source
    assert "instrumentation.sendStatus" in source
    assert "satisfies_s25" not in source
