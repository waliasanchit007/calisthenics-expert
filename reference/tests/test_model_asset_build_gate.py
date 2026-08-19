"""Hermetic checks that default Android delivery assembly cannot package an unverified model.

These invoke the real Gradle task graph with `--offline`, so no network path can run. The
model-asset cache is forced absent for the duration of each test via `_model_asset_forced_absent`,
which moves any real fetched binary aside and restores it afterwards (success or failure), so these
tests behave identically whether or not a developer previously ran `make fetch-model-asset`.
"""

from __future__ import annotations

import contextlib
import shutil
import subprocess
import tempfile
from collections.abc import Iterator
from pathlib import Path

ROOT = Path(__file__).parents[2]
HARNESS = ROOT / "apps" / "diagnostic-harness"
MODEL_ASSET = HARNESS / "model-assets" / "pose_landmarker_full.task"


def _gradlew(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["./gradlew", "--offline", "--no-daemon", *arguments],
        cwd=HARNESS,
        capture_output=True,
        text=True,
        timeout=600,
        check=False,
    )


@contextlib.contextmanager
def _model_asset_forced_absent() -> Iterator[None]:
    """Guarantee `MODEL_ASSET` does not exist for the duration of the block.

    If a developer already ran `make fetch-model-asset`, the real binary is moved to a temporary
    staging directory and restored in a `finally`, so the fetched cache survives the test whether
    it passes or fails. If the asset was already absent, this is a no-op.
    """
    if not MODEL_ASSET.is_file():
        yield
        return

    with tempfile.TemporaryDirectory() as staging:
        backup = Path(staging) / MODEL_ASSET.name
        shutil.move(str(MODEL_ASSET), str(backup))
        try:
            yield
        finally:
            shutil.move(str(backup), str(MODEL_ASSET))


def test_default_assembly_fails_closed_without_the_verified_model_asset() -> None:
    with _model_asset_forced_absent():
        assert not MODEL_ASSET.is_file()

        result = _gradlew(":androidApp:assembleDebug")

    assert result.returncode != 0, (
        "default assembly must fail at build time when the model asset is missing, "
        "not silently package the app without it"
    )
    assert "model asset missing" in result.stdout + result.stderr
    assert "verifyModelAsset" in result.stdout + result.stderr


def test_direct_delivery_invocation_cannot_bypass_verification_via_a_property() -> None:
    """The gate must not be a caller-controlled global property.

    A Gradle project property applies to the whole build regardless of which task requested it, so
    if verifyModelAsset ever reads one to decide whether to skip, a direct delivery command that
    tacks the same property on defeats the gate. This asserts that no such property exists.
    """
    with _model_asset_forced_absent():
        assert not MODEL_ASSET.is_file()

        result = _gradlew("-PskipModelAssetVerification=true", ":androidApp:assembleDebug")

    assert result.returncode != 0, (
        "a direct delivery invocation of assembleDebug must fail without the verified asset "
        "even when a caller passes an unrecognized skip-shaped property"
    )
    assert "model asset missing" in result.stdout + result.stderr


def test_jvm_verification_task_assembles_without_the_asset() -> None:
    """The JVM/CI host uses a dedicated, non-delivery task path instead of a skip property."""
    with _model_asset_forced_absent():
        assert not MODEL_ASSET.is_file()

        result = _gradlew(":androidApp:assembleDebugForJvmVerification")

    assert result.returncode == 0, (
        "the dedicated JVM/CI verification task must assemble without the asset, since this host "
        "is not a delivery platform and never runs on a device"
    )
