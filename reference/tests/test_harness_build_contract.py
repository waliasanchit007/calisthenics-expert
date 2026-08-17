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
    "jdk": "21",
}


def test_harness_build_contract() -> None:
    versions = tomllib.loads((HARNESS / "gradle" / "libs.versions.toml").read_text())["versions"]
    settings = (HARNESS / "settings.gradle.kts").read_text()
    compose_build = (HARNESS / "composeApp" / "build.gradle.kts").read_text()
    shared_build = (HARNESS / "shared" / "build.gradle.kts").read_text()
    makefile = (ROOT / "Makefile").read_text()
    workflow = (ROOT / ".github" / "workflows" / "verify.yml").read_text()
    adr = (ROOT / "docs" / "adr" / "ADR-0004-kmp-android-first-diagnostic-harness.md").read_text()
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
    assert "jvm {" in shared_build
    assert "jvmTarget = JvmTarget.JVM_17" in shared_build
    assert "jvmToolchain(" in shared_build
    assert "verify-harness-jvm:" in makefile, "catches removal/renaming of the JVM host-test gate"
    assert ":shared:jvmTest" in makefile, (
        "catches removal of shared JVM tests from the host-test gate"
    )
    assert "verify-harness-ios:" in makefile, (
        "catches removal/renaming of the iOS compile/link gate"
    )
    assert "verify-harness-foundation:" in makefile, (
        "catches removal/renaming of the aggregate compatibility target"
    )
    assert "$(MAKE) verify-harness-jvm verify-harness-ios" in makefile, (
        "catches aggregate-target invocation drift that could omit a platform gate"
    )
    assert "actions/setup-java@v5" in workflow
    assert 'java-version: "21"' in workflow
    assert "make verify-harness-jvm" in workflow
    assert "jobs:\n  verify:" in workflow, (
        "catches protected-check job ID drift that would strand branch protection"
    )
    assert "not a delivery platform" in adr, (
        "catches ADR drift that could misclassify the JVM test host"
    )


def test_kotlin_export_contract_is_wired_to_python_authority() -> None:
    generator = (
        HARNESS
        / "shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/contract/GenerateSyntheticExport.kt"
    )
    synthetic_test = (
        HARNESS
        / "shared/src/commonTest/kotlin/com/calisthenics/diagnostic/contract/SyntheticExportTest.kt"
    )
    shared_build = (HARNESS / "shared/build.gradle.kts").read_text()
    makefile = (ROOT / "Makefile").read_text()
    workflow = (ROOT / ".github" / "workflows" / "verify.yml").read_text()

    assert generator.is_file()
    assert synthetic_test.is_file()
    assert "generateSyntheticExport" in shared_build
    assert "GenerateSyntheticExportKt" in shared_build
    assert "DeterministicCore" in generator.read_text()
    assert "verify-kotlin-export:" in makefile
    assert ":shared:jvmTest" in makefile
    assert "build/conformance" in makefile
    assert "shasum -a 256" in makefile
    assert "validate-session" in makefile
    assert "make verify-kotlin-export" in workflow


def test_android_shell_has_exported_launcher_activity() -> None:
    manifest = (HARNESS / "androidApp/src/main/AndroidManifest.xml").read_text()

    assert 'android:name="com.calisthenics.diagnostic.MainActivity"' in manifest
    assert 'android:exported="true"' in manifest
    assert '<action android:name="android.intent.action.MAIN" />' in manifest
    assert '<category android:name="android.intent.category.LAUNCHER" />' in manifest


def test_android_probe_has_executable_instrumentation_wiring() -> None:
    probe_test = (
        HARNESS
        / "androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/CameraCapabilityProbeTest.kt"
    ).read_text()

    assert (
        'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"'
        in (HARNESS / "androidApp/build.gradle.kts").read_text()
    )
    assert (
        'buildConfigField("String", "PROBE_GIT_SHA"'
        in (HARNESS / "androidApp/build.gradle.kts").read_text()
    )
    assert "CameraCapabilityProbe(" in probe_test
    assert ".runAndPersist()" in probe_test
    assert "cacheDir" in probe_test
    assert "CameraCapabilityProbe.RESULT_FILE" in probe_test
    assert "ProbeResult(" not in probe_test
    assert "BuildConfig.PROBE_GIT_SHA" in probe_test


def test_ios_host_embeds_shared_compose_controller() -> None:
    app = (HARNESS / "iosApp/iosApp/iOSApp.swift").read_text()
    project = (HARNESS / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text()

    assert "import DiagnosticHarness" in app
    assert "MainViewControllerKt.MainViewController()" in app
    assert "ContentView()" not in app
    assert "DiagnosticHarness.framework" in project
    assert "ContentView.swift in Sources" not in project
