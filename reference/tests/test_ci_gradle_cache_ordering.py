"""The CI ordering contract that makes the offline hermetic build-gate tests runnable at all.

`reference/tests/test_model_asset_build_gate.py` invokes the real Gradle task graph with
`--offline`. That flag is load-bearing: it is what proves the delivery gate rejects a missing model
asset with no network path able to fetch one. It also forbids resolving the Android Gradle Plugin
itself, so on a cold runner with an empty Gradle cache the build dies at

    Plugin [id: 'com.android.application', version: '9.1.0'] was not found in any of the
    following sources

before `verifyModelAsset` can run, and every one of those tests fails for a reason that has nothing
to do with the gate. `make verify` — which runs those tests — is therefore red on a clean GitHub
runner until something earlier in the job has populated the Gradle caches.

These checks pin that ordering as a contract rather than a habit:

- the offline tests must still be offline (the failure must never be "fixed" by weakening them);
- the workflow must run an *online* Gradle preparation step before `make verify`;
- that preparation must not fetch the pinned model asset or pass any skip-shaped property, so the
  delivery gate a cold runner faces is exactly the gate a developer faces.

They are deliberately behavioural rather than name-based: the check is "some step before
`make verify` runs `./gradlew` without `--offline`", so renaming the target cannot silently drop
the guarantee.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "verify.yml"
MAKEFILE = ROOT / "Makefile"
TESTS = Path(__file__).parent

_RUN_MAKE = re.compile(r"^\s*-\s*run:\s*make\s+(?P<targets>[A-Za-z0-9_. -]+?)\s*$")


def workflow_make_steps() -> list[str]:
    """The `make` targets the verify workflow runs, in job order."""
    steps: list[str] = []
    for line in WORKFLOW.read_text(encoding="utf-8").splitlines():
        match = _RUN_MAKE.match(line)
        if match:
            steps.extend(match.group("targets").split())
    return steps


def makefile_recipe(target: str) -> list[str]:
    """The recipe lines of one Makefile target (tab-indented lines under `target:`)."""
    recipe: list[str] = []
    collecting = False
    for line in MAKEFILE.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{target}:"):
            collecting = True
            continue
        if collecting:
            if line.startswith("\t"):
                recipe.append(line)
                continue
            if line.strip() == "" or line.lstrip().startswith("#"):
                continue
            break
    return recipe


def _tests_invoking_offline_gradle() -> list[Path]:
    return sorted(
        path
        for path in TESTS.glob("test_*.py")
        if '"--offline"' in path.read_text(encoding="utf-8")
    )


def test_the_hermetic_build_gate_tests_still_invoke_gradle_offline() -> None:
    """Vacuity guard: everything below is meaningless if no test runs Gradle offline any more."""
    offline_tests = _tests_invoking_offline_gradle()
    assert offline_tests, (
        "no reference test invokes Gradle with --offline any more; the model-asset build gate must "
        "stay hermetic, so the cold-runner ordering problem must not be resolved by dropping it"
    )
    assert (TESTS / "test_model_asset_build_gate.py") in offline_tests


def test_make_verify_runs_the_offline_hermetic_tests() -> None:
    """The offline tests are inside `make verify`, which is what makes the ordering load-bearing."""
    recipe = makefile_recipe("verify")
    assert recipe, "make verify has no recipe"
    assert any("pytest reference/tests" in line for line in recipe), (
        "make verify no longer runs the reference test suite; the ordering contract below assumes "
        "the offline Gradle tests execute inside it"
    )


def test_the_workflow_prepares_gradle_dependencies_before_make_verify() -> None:
    """A cold runner must resolve plugins and dependencies before the offline tests run."""
    steps = workflow_make_steps()
    assert "verify" in steps, f"the verify workflow no longer runs `make verify`: {steps}"

    preparing_steps = [
        target
        for target in steps[: steps.index("verify")]
        if any("./gradlew" in line and "--offline" not in line for line in makefile_recipe(target))
    ]
    assert preparing_steps, (
        "no workflow step before `make verify` runs an online Gradle invocation, so a clean GitHub "
        "runner reaches the --offline model-asset build-gate tests with an empty Gradle cache and "
        "they fail at plugin resolution instead of exercising the gate"
    )


def test_the_gradle_preparation_step_never_fetches_or_skips_the_model_asset() -> None:
    """Preparation warms caches only; it must not hand the delivery gate its asset or a bypass."""
    steps = workflow_make_steps()
    for target in steps[: steps.index("verify")]:
        recipe = makefile_recipe(target)
        if not any("./gradlew" in line for line in recipe):
            continue
        joined = "\n".join(recipe)
        assert "fetch-model-asset" not in joined, (
            f"make {target} fetches the pinned model asset; preparing the Gradle cache must never "
            "supply the asset the delivery gate is meant to require"
        )
        assert "skipModelAsset" not in joined and "-Pskip" not in joined, (
            f"make {target} passes a skip-shaped property; the model-asset gate is unconditional"
        )
        assert ":androidApp:assembleDebug" not in joined and ":androidApp:package" not in joined, (
            f"make {target} assembles a delivery variant, which cannot succeed without the asset; "
            "preparation must use the non-delivery jvmVerification variant"
        )


def test_workflow_fetches_the_verified_model_only_after_hermetic_tests_and_before_app_tests() -> (
    None
):
    """Android app unit tests compile debug resources, so the unconditional model gate applies."""
    steps = workflow_make_steps()

    assert "fetch-model-asset" in steps, (
        "verify-harness-jvm runs Android app unit tests through the debug resource graph; a clean "
        "runner must fetch and SHA-verify the pinned model before that protected gate"
    )
    assert (
        steps.index("verify") < steps.index("fetch-model-asset") < steps.index("verify-harness-jvm")
    ), (
        "the model must remain absent while make verify proves the missing-asset gate offline, "
        "then be fetched before Android app unit tests compile debug resources"
    )
