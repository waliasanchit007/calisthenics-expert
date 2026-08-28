from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _recipe(target: str) -> str:
    makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
    match = re.search(
        rf"(?m)^{re.escape(target)}:\s*\n(?P<body>(?:\t[^\n]*\n|[ \t]*\n)+)",
        makefile,
    )
    assert match is not None, f"missing Make target {target}"
    return match.group("body")


def test_final_envelope_gate_only_uses_an_exact_already_installed_candidate() -> None:
    recipe = _recipe("verify-harness-s25-m05b-envelope")
    lowered = recipe.lower()

    for forbidden in (
        "assemble",
        "installdebug",
        "install-multiple",
        "pm clear",
        "fetch-model-asset",
    ):
        assert forbidden not in lowered
    assert re.search(r"(?i):(?:androidApp:)?(?:package|bundle)[A-Za-z0-9_-]*", recipe) is None
    assert re.search(r"(?m)^\s*adb\b.*\binstall(?:-multiple)?\b", recipe) is None
    assert "M05B_CANDIDATE_IDENTITY_FILE" in recipe
    assert "M05B_AMBIENT_TOKEN" in recipe
    assert "M05B_AMBIENT_CONSTANCY_ATTESTATION" in recipe
    assert "shell pm path com.calisthenics.diagnostic.android" in recipe
    assert "shell pm path com.calisthenics.diagnostic.android.test" in recipe
    assert "exec-out cat" in recipe
    assert re.search(r"exec-out cat.*>.*\.apk", recipe)
    assert "shasum -a 256" in recipe
    assert re.search(r"(?:app|test)_bytes\s*=\s*\$\$\(adb", recipe) is None


def test_final_gate_requires_post_baseline_operator_action_and_rechecks_candidate() -> None:
    recipe = _recipe("verify-harness-s25-m05b-envelope")
    baseline = recipe.index("M05BEnvelopeDeviceGateTest#observeBaseline")
    baseline_check = recipe.index("check_m05b_baseline_phase.py", baseline)
    terminal_read = recipe.index("/dev/tty", baseline_check)
    attestation = recipe.index("M05BEnvelopeDeviceGateTest#attestAndEmit", terminal_read)

    assert baseline < baseline_check < terminal_read < attestation
    assert "-e m05b_ambient_constancy_attestation" not in recipe[baseline:terminal_read]
    assert "AMBIENT.ATTESTATION_PRE_SUPPLIED" in recipe
    assert recipe.count("exec-out cat") >= 4
    assert recipe.count("shasum -a 256") >= 4
    assert "-e m05b_ambient_token" not in recipe[attestation:]


def test_cross_process_baseline_handoff_is_durable_and_consumed_once() -> None:
    source = (
        ROOT
        / "apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/M05BEnvelopeDeviceGateTest.kt"
    ).read_text(encoding="utf-8")

    assert "output.fd.sync()" in source
    assert "Os.rename(" in source
    assert "fsyncDirectory(" in source
    assert ".renameTo(" not in source
    assert source.index("stale pending baseline") < source.index("M05BIdleBaselineSampler.collect")
    assert source.index("consumed pending baseline") < source.index("M05BBaselineReducer.reduce")
    assert "ambientToken = samples.lastOrNull()?.ambientToken" in source


def test_candidate_preparation_is_a_separate_explicit_one_time_target() -> None:
    recipe = _recipe("prepare-harness-s25-m05b-envelope-candidate")

    assert ":androidApp:assembleDebug" in recipe
    assert re.search(r"(?m)^\s*adb\b.*\binstall(?:-multiple)?\b", recipe)
    assert "pm clear" in recipe
    assert "M05B_CANDIDATE_IDENTITY_FILE" in recipe
    assert "exec-out cat" in recipe
    assert "shasum -a 256" in recipe


def test_kotlin_export_round_trips_exact_durable_admission_through_python_schema() -> None:
    generator = (
        ROOT
        / "apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/contract/GenerateSyntheticExport.kt"
    ).read_text(encoding="utf-8")
    recipe = _recipe("verify-kotlin-export")

    assert "M05BDurableAdmissionBoundary" in generator
    assert "start-envelope.json" in generator
    assert "execution-order.json" in generator
    assert "run-admission.json" in generator
    assert "verify-m05b-envelope start_envelope" in recipe
    assert "verify-m05b-envelope execution_order" in recipe
    assert "run-admission.json" in recipe
    assert "verify-m05b-envelope run_admission" in recipe


def test_admission_records_conjuncts_as_checks_execute_instead_of_returning_the_constant() -> None:
    source = (
        ROOT
        / "apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05BEnvelopeEvidence.kt"
    ).read_text(encoding="utf-8")

    assert "evaluatedConjunctIds = REQUIRED_CONJUNCT_IDS" not in source
    assert 'evaluate("POWER.SAVE_MODE")' in source
    assert 'evaluate("AMBIENT.CONSTANCY")' in source
    assert 'evaluate("ORDER.PERMITTED")' in source
    assert "blocking.distinct(), REQUIRED_CONJUNCT_IDS" not in source

    python_source = (ROOT / "reference/src/observation_contracts/m05b_envelope.py").read_text(
        encoding="utf-8"
    )
    assert re.search(r'evaluate\(\s*"POWER\.SAVE_MODE"', python_source)
    assert re.search(r'evaluate\(\s*"AMBIENT\.CONSTANCY"', python_source)
    assert re.search(r'evaluate\(\s*"ORDER\.PERMITTED"', python_source)
    assert '"evaluated_conjunct_ids": list(REQUIRED_CONJUNCT_IDS)' not in python_source
