from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).parents[2]


def _recipe(target: str) -> str:
    makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
    match = re.search(
        rf"(?m)^{re.escape(target)}:\s*\n(?P<body>(?:\t[^\n]*\n|[ \t]*\n)+)",
        makefile,
    )
    assert match is not None, f"missing Make target {target}"
    return match.group("body")


def test_v2_gate_consumes_only_an_exact_prepared_candidate() -> None:
    recipe = _recipe("verify-harness-s25-m05b-attribution-v2")
    lowered = recipe.lower()

    for forbidden in ("assemble", "pm clear", "fetch-model-asset", "rm -rf", "rm -r "):
        assert forbidden not in lowered
    assert re.search(r"(?m)^\s*adb\b.*\binstall(?:-multiple)?\b", recipe) is None
    assert "M05B_CANDIDATE_IDENTITY_FILE" in recipe
    assert "git rev-parse HEAD" in recipe
    assert "git status --porcelain --untracked-files=normal" in recipe
    assert "shell pm path com.calisthenics.diagnostic.android" in recipe
    assert "shell pm path com.calisthenics.diagnostic.android.test" in recipe
    assert recipe.count("exec-out cat") >= 2
    assert "actual_app_sha256" in recipe
    assert "actual_test_sha256" in recipe
    assert "expected_app_sha256" in recipe
    assert "expected_test_sha256" in recipe
    assert "installed candidate bytes differ" in recipe
    assert re.search(
        r'if \[ "\$\$actual_app_sha256" != "\$\$expected_app_sha256" \] '
        r'\|\| \[ "\$\$actual_test_sha256" != "\$\$expected_test_sha256" \]',
        recipe,
    )


def test_v2_gate_requires_one_authorized_wifi_s25_on_battery_power() -> None:
    recipe = _recipe("verify-harness-s25-m05b-attribution-v2")

    assert "adb devices -l" in recipe
    assert "awk '$$2 == \"device\"'" in recipe
    assert "emulator-" in recipe
    assert re.search(r"case \"\$\$serial\" in .*\*:.*;;", recipe) is not None
    assert "is_authorized_five_run_gate_target" in recipe
    assert "dumpsys battery" in recipe
    assert "AC|USB|Wireless|Dock" in recipe
    assert "powered): true" in recipe


def test_v2_gate_binds_protocol_digest_and_invokes_only_the_v2_suite_and_checker() -> None:
    recipe = _recipe("verify-harness-s25-m05b-attribution-v2")

    assert "docs/protocols/m05b-attribution-v2.protocol.json" in recipe
    assert "protocol_sha256=$$(shasum -a 256" in recipe
    assert '-e protocol_sha256 "$$protocol_sha256"' in recipe
    assert recipe.count("M05BAttributionV2DeviceGateTest") == 1
    assert "M05BAttributionDeviceGateTest" not in recipe
    assert recipe.count("tools/check_m05b_attribution_v2.py") == 1
    assert "tools/check_m05b_attribution.py" not in recipe
    assert "--serial" in recipe
    assert "--package" in recipe
    assert "--transcript" in recipe
    assert '--subprocess-exit-code "$$instrument_status"' in recipe
    assert "--prepared-identity" in recipe
    assert "--root" in recipe
    assert re.search(r"grep[^\n]*(?:OK|success|recorded)", recipe, re.IGNORECASE) is None


def test_v2_gate_preserves_the_real_instrumentation_exit_code() -> None:
    recipe = _recipe("verify-harness-s25-m05b-attribution-v2")

    invocation = recipe.index("am instrument")
    capture = recipe.index("instrument_status=$$?", invocation)
    checker = recipe.index("tools/check_m05b_attribution_v2.py", capture)

    assert "set +e; adb" in recipe
    assert capture > invocation
    assert "set -e" in recipe[capture:checker]
    assert '--subprocess-exit-code "$$instrument_status"' in recipe[checker:]


def test_v2_gate_uses_unique_v2_temporary_names_without_wildcard_selection() -> None:
    recipe = _recipe("verify-harness-s25-m05b-attribution-v2")

    assert "m05b-attribution-v2" in recipe
    assert "candidate_tmp=$$(mktemp" in recipe
    assert "transcript=$$(mktemp" in recipe
    assert "identity_tmp=$$(mktemp" in recipe
    assert re.search(r"adb\s+-s\s+['\"]?\*", recipe) is None
    assert re.search(r"adb devices[^\n]*\*", recipe) is None
