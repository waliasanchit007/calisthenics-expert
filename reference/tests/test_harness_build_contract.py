import tomllib
from pathlib import Path

ROOT = Path(__file__).parents[2]
HARNESS = ROOT / "apps" / "diagnostic-harness"
EXPECTED_VERSIONS = {
    "kotlin": "2.4.10",
    "composeMultiplatform": "1.11.1",
    "agp": "9.1.0",
    "android-compileSdk": "36",
    "android-minSdk": "33",
    "android-targetSdk": "36",
    "androidx-activity": "1.13.0",
    "androidx-core": "1.18.0",
    "camerax": "1.6.1",
    "coroutines": "1.11.0",
    "serialization": "1.10.0",
    "kotlin-test": "2.4.10",
}


def test_harness_build_contract() -> None:
    versions = tomllib.loads((HARNESS / "gradle" / "libs.versions.toml").read_text())["versions"]
    settings = (HARNESS / "settings.gradle.kts").read_text()
    compose_build = (HARNESS / "composeApp" / "build.gradle.kts").read_text()
    all_gradle_text = "\n".join(
        path.read_text()
        for path in HARNESS.rglob("*")
        if path.is_file() and path.suffix in {".gradle.kts", ".properties", ".toml"}
    )

    includes = {
        line.split('"', 2)[1]
        for line in settings.splitlines()
        if line.strip().startswith('include("')
    }

    assert {key: versions[key] for key in EXPECTED_VERSIONS} == EXPECTED_VERSIONS
    assert includes == {":shared", ":composeApp", ":androidApp"}
    assert 'baseName = "DiagnosticHarness"' in compose_build
    marker = (
        HARNESS
        / "composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/foundation/FrameworkCompileMarker.kt"
    )
    assert marker.is_file()
    assert "public object FrameworkCompileMarker" in marker.read_text()
    assert "latest" not in all_gradle_text.lower()
