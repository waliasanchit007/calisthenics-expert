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
    android_app_build = (HARNESS / "androidApp" / "build.gradle.kts").read_text()
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
    assert "withHostTestBuilder {}" in shared_build, (
        "catches removal of the androidHostTest source set from the androidLibrary block"
    )
    assert "withDeviceTestBuilder {}" in shared_build, (
        "catches removal of the androidDeviceTest source set from the androidLibrary block"
    )
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
    assert (
        'tasks.matching { it.name.startsWith("package") && "JvmVerification" !in it.name }'
        in android_app_build
    ), (
        "catches the model-asset gate no longer wired onto every real package-producing task, "
        "which would let a direct :androidApp:packageDebug invocation bypass verification"
    )
    assert "dependsOn(verifyModelAsset)" in android_app_build, (
        "catches default delivery assembly no longer requiring the verified model asset"
    )
    assert "skipModelAssetVerification" not in android_app_build, (
        "catches the model-asset gate regaining a caller-controlled property bypass, which a "
        "direct delivery invocation could tack onto packageDebug/packageRelease to skip "
        "verification"
    )
    assert 'create("jvmVerification")' in android_app_build, (
        "catches the JVM/CI host losing its mechanically distinct, non-delivery build type"
    )
    jvm_verification_build_type = android_app_build.split('create("jvmVerification")', 1)[1].split(
        "\n    }", 1
    )[0]
    assert "applicationIdSuffix" in jvm_verification_build_type, (
        "catches the JVM/CI verification variant losing its distinct application identity, which "
        "is what keeps it mechanically separate from the delivery app"
    )
    assert "versionNameSuffix" in jvm_verification_build_type, (
        "catches the JVM/CI verification variant losing its distinct version identity"
    )
    assert (
        ":androidApp:assembleJvmVerification"
        in makefile.split("verify-harness-jvm:", 1)[1].split("\n\n", 1)[0]
    ), (
        "catches the JVM/CI verification target reverting to the delivery assembleDebug/"
        "packageDebug task graph, which would once again require the model asset on a host that "
        "is not a delivery platform"
    )
    assert (
        ":androidApp:packageDebug"
        not in makefile.split("verify-harness-jvm:", 1)[1].split("\n\n", 1)[0]
    ), "catches the JVM/CI verification target reintroducing the delivery packaging task"
    for delivery_target in (
        "verify-harness-android-export-parity:",
        "verify-harness-s25-process-kill:",
    ):
        delivery_block = makefile.split(delivery_target, 1)[1].split("\nverify-", 1)[0]
        assert "JvmVerification" not in delivery_block, (
            f"catches the non-delivery JVM verification variant leaking into the device-delivery "
            f"target {delivery_target.rstrip(':')}"
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

    # Design section 8 evidence. `println` never reaches `am instrument -w` output, so a record
    # printed that way is a record the transcript checker can never see; the run would look
    # successful and produce nothing to record.
    assert "renderCameraCapabilityEvidence(" in probe_test
    assert "println(" not in probe_test, (
        "catches the record being printed to stdout, which am instrument -w never reports"
    )
    assert probe_test.count("sendStatus(") == 1, (
        "catches the probe emitting zero records (nothing to record) or more than one (the checker "
        "refuses to guess which belongs to this run)"
    )
    assert "CameraCapabilityEvidence" in probe_test, (
        "catches drift in the status-bundle key tools/check_camera_capability_probe.py greps for"
    )
    # The renderer computes admission; the test must not decide it and must not gate its own pass
    # on the outcome. A blocking observation is a successful probe whose record still gets emitted.
    assert "satisfies_s25_camera_live_admission" not in probe_test


def test_camera_probe_admission_rule_has_exactly_one_home() -> None:
    """The device-authorization rule existed as four divergent copies once. Not a fifth, and not a
    second copy of the camera admission rule beside it."""
    evidence = (
        HARNESS
        / "shared/src/commonMain/kotlin/com/calisthenics/diagnostic/replay/CameraCapabilityEvidence.kt"
    ).read_text()
    probe_result = (
        HARNESS / "androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/ProbeResult.kt"
    ).read_text()

    assert "isAuthorizedS25Target(" in evidence, (
        "catches the camera renderer restating the device-authorization rule instead of delegating"
    )
    assert "SM-S938" not in evidence and "samsung" not in evidence.lower(), (
        "catches a fifth copy of the S25 authorization rule appearing in the camera renderer"
    )
    assert "cameraObservationBlockingReasons(" in probe_result, (
        "catches ProbeResult.isLiveAdmissible regaining its own copy of the admission rule, which "
        "already diverged once by omitting the underlying-image bridge and the UNKNOWN DataSpace"
    )
    assert '"REALTIME"' not in probe_result, (
        "catches the admission rule being restated in ProbeResult"
    )
    # commonMain cannot see android.hardware.DataSpace, so the sentinel is restated there and
    # pinned to the platform constant by exactly one unit test.
    assert "const val CAMERA_DATASPACE_UNKNOWN: Int = 0" in evidence
    sentinel_test = (
        HARNESS
        / "androidApp/src/test/kotlin/com/calisthenics/diagnostic/probe/CameraProbeDataSpaceSentinelTest.kt"
    ).read_text()
    assert "DataSpace.DATASPACE_UNKNOWN" in sentinel_test
    assert "CAMERA_DATASPACE_UNKNOWN" in sentinel_test


def test_s25_camera_probe_target_delegates_its_verdict_to_the_checker() -> None:
    """The camera-probe wrapper must decide nothing by grepping a success line.

    `am instrument` exits 0 even when tests fail, and `^OK (` matches `OK (0 tests)` and any other
    suite. Worse, "the probe ran" and "the probe observed something admissible" are different
    questions here: `UNKNOWN` timestamps or an unavailable underlying-image bridge are real
    observations that must block. Both verdicts belong to the checker's exit status, which is
    host-tested in reference/tests/test_camera_capability_probe_check.py.
    """
    makefile = (ROOT / "Makefile").read_text()
    probe_test = (
        HARNESS
        / "androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/CameraCapabilityProbeTest.kt"
    )
    target = makefile.split("verify-harness-s25-camera-probe:", 1)[1].split("\nverify:", 1)[0]

    assert probe_test.is_file()
    phony_line = makefile.split("\n.PHONY", 1)[1].split("\n", 1)[0]
    assert "verify-harness-s25-camera-probe" in phony_line, (
        "catches the new device gate never being declared phony alongside the others"
    )
    assert target.lstrip().startswith("@set -eu"), (
        "catches the wrapper losing errexit, which would let a failing step be stepped over"
    )
    assert "awk '$$2==\"device\"'" in target, (
        "catches the wrapper counting attached targets by any means other than the established "
        "authorized-device column match"
    )
    assert 'device_count" -ne 1' in target, (
        "catches the wrapper accepting zero or several attached targets, either of which makes the "
        "recorded serial meaningless"
    )
    assert "emulator-*)" in target, (
        "catches the wrapper accepting an emulator; an emulated camera says nothing about what the "
        "S25 sensor reports"
    )
    assert 'manufacturer_lower" != "samsung"' in target
    assert "SM-S938*)" in target, (
        "catches the wrapper losing the authoritative model-number match; retail S25 Ultra "
        "firmware may report ro.config.marketing_name as empty, and a marketing-name-only rule "
        "rejects the genuine device"
    )
    assert '*"S25 Ultra"*)' in target, (
        "catches the wrapper losing the marketing-name fallback isAuthorizedS25Target also allows"
    )
    assert (
        ":androidApp:assembleDebug" in target and ":androidApp:assembleDebugAndroidTest" in target
    )
    assert target.count('adb -s "$$serial" install') == 2, (
        "catches the wrapper running against a stale installed APK"
    )
    assert "com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest" in target
    assert "am instrument -w -r" in target, (
        "catches the loss of raw instrumentation results, without which the transcript carries no "
        "per-test class/name/status blocks and the emitted record never appears at all"
    )
    instrument_line = next(line for line in target.splitlines() if "am instrument" in line)
    assert "|| true" not in instrument_line, (
        "catches the wrapper swallowing the instrumentation status"
    )
    assert "instrument_status" in target, (
        "catches the wrapper discarding the instrumentation exit status instead of preserving it"
    )
    assert "tools/check_camera_capability_probe.py" in target, (
        "catches the wrapper losing the checker that requires the exact suite, its one passing "
        "test, and exactly one parseable sanitized record"
    )
    checker_line = next(
        line for line in target.splitlines() if "check_camera_capability_probe.py" in line
    )
    assert "||" not in checker_line and "; true" not in checker_line, (
        f"catches the checker's exit status being swallowed on the way to make: {checker_line!r}"
    )
    assert "grep -q '^OK ('" not in target, (
        "catches the wrapper reverting to a generic success-line grep, which accepts OK (0 tests) "
        "and a run of any other suite"
    )
    assert "s25-ultra-camera-capability-ledger.json" not in target, (
        "catches the camera gate mutating the pending evidence ledger itself; the ledger is only "
        "ever updated by a human copying the printed sanitized record"
    )


def test_android_storage_probe_has_executable_instrumentation_wiring() -> None:
    probe_test = (
        HARNESS
        / "androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/StorageCapabilityProbeTest.kt"
    ).read_text()

    assert (
        'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"'
        in (HARNESS / "androidApp/build.gradle.kts").read_text()
    )
    assert (
        'buildConfigField("String", "PROBE_GIT_SHA"'
        in (HARNESS / "androidApp/build.gradle.kts").read_text()
    )
    assert "StorageCapabilityProbe(" in probe_test
    assert ".runAndPersist()" in probe_test
    assert "cacheDir" in probe_test
    assert "StorageCapabilityProbe.RESULT_FILE" in probe_test
    assert "StorageProbeResult(" not in probe_test
    assert "BuildConfig.PROBE_GIT_SHA" in probe_test


def test_android_export_parity_target_is_class_isolated() -> None:
    makefile = (ROOT / "Makefile").read_text()
    parity_test = (
        HARNESS
        / "androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/InstallableCompositionExportParityTest.kt"
    )
    process_kill_test = (
        HARNESS
        / "androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/export/ProcessKillDuringExportTest.kt"
    )
    parity_target = makefile.split("verify-harness-android-export-parity:", 1)[1].split(
        "\nverify-harness-s25-process-kill:", 1
    )[0]

    assert parity_test.is_file()
    assert process_kill_test.is_file()
    assert "com.calisthenics.diagnostic.composition.InstallableCompositionExportParityTest" in (
        parity_target
    )
    assert (
        "-Pandroid.testInstrumentationRunnerArguments.class="
        "com.calisthenics.diagnostic.composition.InstallableCompositionExportParityTest"
        in parity_target
    ), "catches the parity gate reverting to running the whole connected suite"
    assert "com.calisthenics.diagnostic.export.ProcessKillDuringExportTest" not in parity_target, (
        "catches the parity gate regaining a dependency on the destructive S25 process-kill class"
    )
    assert ":shared:generateSyntheticExport" in parity_target, (
        "catches the parity gate losing the JVM fixture it diffs against"
    )
    assert "device_count" in parity_target, "catches the parity gate losing the one-device gate"
    assert "connectedDebugAndroidTest" in parity_target, (
        "catches the parity gate no longer building/installing/running via the connected task"
    )


def test_s25_process_kill_target_requires_fresh_run_correlated_evidence() -> None:
    makefile = (ROOT / "Makefile").read_text()
    kill_target = makefile.split("verify-harness-s25-process-kill:", 1)[1].split(
        "\nverify-kotlin-export:", 1
    )[0]

    assert "pm clear com.calisthenics.diagnostic.android" in kill_target, (
        "catches the process-kill gate losing the pre-invocation private-data clear that keeps "
        "residue from a stale run out of fresh evidence"
    )
    assert "Success" in kill_target, (
        "catches the process-kill gate no longer requiring pm clear to report Success"
    )
    kill_invocation_line = next(
        line
        for line in kill_target.splitlines()
        if "killDuringCommit" in line and "am instrument" in line
    )
    assert "|| true" not in kill_invocation_line, (
        "catches the process-kill gate silently swallowing the first invocation's failure again"
    )
    assert "Process crashed" in kill_target, (
        "catches the process-kill gate no longer requiring the first invocation's output to "
        "identify an instrumentation process crash before verification is trusted"
    )
    assert "killDuringCommit" in kill_target
    assert kill_target.count("verifyNoPartialEntryAndResidueClassification") == 1, (
        "catches the second invocation moving earlier than the checked first invocation, or "
        "being duplicated"
    )
    assert "docs/evidence/device-probes/s25-ultra-process-kill-ledger.json" not in kill_target, (
        "catches the process-kill gate mutating the pending evidence ledger itself"
    )


def test_s25_process_kill_target_accepts_zero_status_with_process_crashed_signal() -> None:
    makefile = (ROOT / "Makefile").read_text()
    kill_target = makefile.split("verify-harness-s25-process-kill:", 1)[1].split(
        "\nverify-kotlin-export:", 1
    )[0]

    assert "first_exit_code" in kill_target, (
        "catches the process-kill gate losing the captured first invocation status needed for "
        "diagnostics"
    )
    assert 'first_exit_code" -eq 0' not in kill_target, (
        "catches the process-kill gate reinstating a fatal non-zero-status requirement on the "
        "first invocation; AOSP's am instrument can report the instrumented process's death "
        "through the watcher while the command itself exits 0, so gating on a non-zero shell "
        "status can reject genuine expected-kill evidence"
    )
    assert 'first_output" | grep -q "Process crashed"' in kill_target, (
        "catches the process-kill gate losing the exact 'Process crashed' signal match, which "
        "must remain the load-bearing evidence of a genuine kill regardless of shell status"
    )


def test_detection_replay_target_delegates_its_verdict_to_the_run_checker() -> None:
    """The device wrapper must not decide passage by grepping a generic success line.

    `am instrument` exits 0 even when tests fail, so the output is load-bearing — but a `^OK (`
    match accepts `OK (0 tests)`, accepts a different suite, and accepts a run that emitted no
    observation. The verdict belongs to the checker's exit status, which is host-tested in
    `reference/tests/test_detection_replay_evidence.py`.
    """
    makefile = (ROOT / "Makefile").read_text()
    target = makefile.split("verify-harness-detection-replay:", 1)[1].split(
        "\nverify-harness-s25-process-kill:", 1
    )[0]

    assert "am instrument -w -r" in target, (
        "catches the loss of raw instrumentation results, without which the transcript carries no "
        "per-test class/name/status blocks and the suite cannot be verified at all"
    )
    assert "tools/check_detection_replay_run.py" in target, (
        "catches the device wrapper losing the run checker that requires the exact suite, all "
        "three of its tests, and the sanitized evidence record"
    )
    assert "grep -q '^OK ('" not in target, (
        "catches the device wrapper reverting to a generic success-line grep, which accepts "
        "OK (0 tests) and a run of any other suite"
    )
    assert target.lstrip().startswith("@set -eu"), (
        "catches the device wrapper losing errexit, which would let a failing step be stepped over"
    )
    checker_line = next(
        line for line in target.splitlines() if "check_detection_replay_run.py" in line
    )
    assert "||" not in checker_line and "; true" not in checker_line, (
        f"catches the checker's exit status being swallowed on the way to make: {checker_line!r}"
    )
    checker = (ROOT / "tools/check_detection_replay_run.py").read_text()
    assert "verify_instrumented_run" in checker
    assert "verify_committed_evidence" in checker


def test_ios_host_embeds_shared_compose_controller() -> None:
    app = (HARNESS / "iosApp/iosApp/iOSApp.swift").read_text()
    project = (HARNESS / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text()

    assert "import DiagnosticHarness" in app
    assert "MainViewControllerKt.MainViewController()" in app
    assert "ContentView()" not in app
    assert "DiagnosticHarness.framework" in project
    assert "ContentView.swift in Sources" not in project
