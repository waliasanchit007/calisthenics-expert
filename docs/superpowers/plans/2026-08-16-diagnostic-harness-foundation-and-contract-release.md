# Diagnostic Harness Foundation and Contract Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the buildable Android/iOS KMP harness shell, fail-closed capability UI, executable S25 capability probe, and released SessionManifest/RawPoseFrame 1.1.0 contract required by later producers.

**Architecture:** Two KMP libraries (`shared`, `composeApp`) hold domain and UI code; `androidApp` and `iosApp` are platform entry-point shells. The Python reference package remains the contract authority. This plan ends at a protected-branch-ready foundation and 1.1.0 release candidate; pose execution, SessionRunner, replay, and live capture follow in separate plans.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.11.1, AGP 9.1.0, Gradle 9.3.1, JDK 21/JVM target 17, CameraX 1.6.1, Swift/Xcode 26.4, Python 3.14 local reference environment.

## Global Constraints

- Work only in `/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/worktrees/diagnostic-harness-foundation` on `codex/diagnostic-harness-foundation`.
- JSON Schema and the Python validator are authoritative; Kotlin code cannot redefine their meaning.
- Android compile/target SDK is 36 and minimum SDK is 33.
- iOS targets are `iosArm64` and `iosSimulatorArm64` with deployment target 16.0.
- Use Google's `com.android.kotlin.multiplatform.library` plugin in KMP modules and a separate `com.android.application` module.
- Use exact dependency versions; no `latest`, ranges, snapshots, or unverified binary assets.
- iOS is fail-closed and cannot start or export a session.
- No real participant data, pose streams, credentials, consent records, or model binaries enter Git.
- Each production change is regression-first. Record the failing command and failure reason before implementation.
- Routine acceptance is deterministic checks plus diff/scope inspection. The 1.1.0 data-semantics boundary receives one independent review; only Critical/Important findings block.

---

## Planned file map

```text
apps/diagnostic-harness/
  settings.gradle.kts                         # isolated Gradle build includes four modules
  build.gradle.kts                            # centralized plugin declarations
  gradle.properties                           # deterministic Gradle/Kotlin settings
  gradle/libs.versions.toml                   # exact dependency and plugin versions
  gradle/wrapper/*                            # Gradle 9.3.1 wrapper
  shared/build.gradle.kts                     # domain KMP library
  shared/src/commonMain/.../Capability.kt     # typed platform capabilities
  shared/src/commonTest/.../CapabilityTest.kt # capability invariants
  composeApp/build.gradle.kts                 # Compose KMP UI library
  composeApp/src/commonMain/.../HarnessApp.kt # shared capability shell
  composeApp/src/commonTest/.../HarnessAppTest.kt
  composeApp/src/iosMain/.../MainViewController.kt
  androidApp/build.gradle.kts                 # Android application entry point
  androidApp/src/main/AndroidManifest.xml
  androidApp/src/main/.../MainActivity.kt
  androidApp/src/main/.../probe/CameraCapabilityProbe.kt
  androidApp/src/androidTest/.../CameraCapabilityProbeTest.kt
  iosApp/                                     # minimal Xcode host
contracts/schema/session_manifest.v1.schema.json
contracts/schema/raw_pose_frame.v1.schema.json
contracts/tooling/validator_error_codes.v1.json
contracts/CHANGELOG.md
reference/src/observation_contracts/semantic.py
reference/tests/test_semantic_session.py
reference/tests/test_structural.py
reference/tests/test_errors.py
fixtures/synthetic/valid/fixture_replay/*
fixtures/synthetic/negative/replay_media_role/*
docs/evidence/device-probes/                   # sanitized, non-participant probe output only
```

---

### Task 1: Reproducible four-module build shell

**Files:**
- Create: `apps/diagnostic-harness/settings.gradle.kts`
- Create: `apps/diagnostic-harness/build.gradle.kts`
- Create: `apps/diagnostic-harness/gradle.properties`
- Create: `apps/diagnostic-harness/gradle/libs.versions.toml`
- Create: `apps/diagnostic-harness/gradle/wrapper/gradle-wrapper.properties`
- Create: `apps/diagnostic-harness/gradlew`
- Create: `apps/diagnostic-harness/gradlew.bat`
- Create: `apps/diagnostic-harness/gradle/wrapper/gradle-wrapper.jar`
- Create: `apps/diagnostic-harness/shared/build.gradle.kts`
- Create: `apps/diagnostic-harness/composeApp/build.gradle.kts`
- Create: `apps/diagnostic-harness/androidApp/build.gradle.kts`
- Create: `apps/diagnostic-harness/androidApp/src/main/AndroidManifest.xml`
- Create: `apps/diagnostic-harness/iosApp/Configuration/Config.xcconfig`
- Create: `apps/diagnostic-harness/iosApp/iosApp.xcodeproj/project.pbxproj`
- Create: `apps/diagnostic-harness/iosApp/iosApp/Info.plist`
- Create: `apps/diagnostic-harness/iosApp/iosApp/iOSApp.swift`
- Create: `apps/diagnostic-harness/iosApp/iosApp/ContentView.swift`
- Modify: `.gitignore`
- Modify: `Makefile`

**Interfaces:**
- Consumes: official AGP 9 KMP module structure and the versions fixed by the design.
- Produces: Gradle projects `:shared`, `:composeApp`, and `:androidApp`; static iOS framework `DiagnosticHarness`; `make verify-harness-foundation`.

- [ ] **Step 1: Add the failing build-contract test**

Create `reference/tests/test_harness_build_contract.py` with a table asserting the exact version catalog entries and module includes. Its key assertions are:

```python
assert versions["kotlin"] == "2.4.10"
assert versions["composeMultiplatform"] == "1.11.1"
assert versions["agp"] == "9.1.0"
assert versions["android-compileSdk"] == "36"
assert versions["android-minSdk"] == "33"
assert includes == {":shared", ":composeApp", ":androidApp"}
assert "latest" not in all_gradle_text.lower()
```

- [ ] **Step 2: Observe RED**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_harness_build_contract.py -q
```

Expected: failure because `apps/diagnostic-harness/gradle/libs.versions.toml` does not exist.

- [ ] **Step 3: Add the Gradle build**

Use these exact versions in `libs.versions.toml`: Kotlin 2.4.10, Compose 1.11.1, AGP 9.1.0, AndroidX Activity 1.13.0, AndroidX Core 1.18.0, CameraX 1.6.1, Kotlin coroutines 1.11.0, Kotlin serialization 1.10.0, Kotlin test 2.4.10. Declare `androidApplication`, `androidMultiplatformLibrary`, `composeMultiplatform`, `composeCompiler`, `kotlinMultiplatform`, and `kotlinSerialization` plugins.

Both KMP modules configure:

```kotlin
kotlin {
    androidLibrary {
        compileSdk = 36
        minSdk = 33
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    iosArm64()
    iosSimulatorArm64()
}
```

`composeApp` exports a static framework named `DiagnosticHarness`. `androidApp` applies only the Android application and Compose plugins, depends on `projects.shared` and `projects.composeApp`, and targets SDK 36/minimum 33. Do not enable legacy variant APIs.

Generate the Gradle 9.3.1 wrapper from the official distribution and verify the wrapper JAR checksum against Gradle's published checksum before staging it.

- [ ] **Step 4: Add build commands**

Add `verify-harness-foundation` to `Makefile`:

```make
verify-harness-foundation:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:allTests :composeApp:allTests :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64
```

If generated Gradle task names differ, use `./gradlew tasks --all` as evidence and commit the exact valid link tasks; do not suppress a missing target.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_harness_build_contract.py -q
make verify-harness-foundation
make verify
```

Expected: build-contract test passes; common tests and Android assembly pass; both iOS frameworks link; existing 188 Python tests and guards remain green.

- [ ] **Step 6: Inspect scope and commit**

Run `git diff --check`, inspect every generated file, confirm no IDE state or downloaded SDK/model artifacts are tracked, then commit:

```bash
git add .gitignore Makefile reference/tests/test_harness_build_contract.py apps/diagnostic-harness
git commit -m "build: scaffold multiplatform diagnostic harness"
```

---

### Task 2: Fail-closed shared capability shell

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/capability/Capability.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/capability/CapabilityTest.kt`
- Create: `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/HarnessApp.kt`
- Create: `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/HarnessUiState.kt`
- Create: `apps/diagnostic-harness/composeApp/src/commonTest/kotlin/com/calisthenics/diagnostic/ui/HarnessUiStateTest.kt`
- Create: `apps/diagnostic-harness/composeApp/src/iosMain/kotlin/com/calisthenics/diagnostic/ui/MainViewController.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/MainActivity.kt`

**Interfaces:**
- Produces:

```kotlin
enum class CapabilityId { CAMERA_PERMISSION, CAMERA_SOURCE, TIMESTAMP_COMPARABILITY, COLOR_METADATA, MODEL_ASSET, STORAGE_DURABILITY, POSE_BACKEND, THERMAL_API }
sealed interface CapabilityState { data object Available; data class Unavailable(val reason: String); data class Unknown(val reason: String) }
data class Capability(val id: CapabilityId, val state: CapabilityState)
enum class HarnessMode { REPLAY, LIVE }
data class HarnessUiState(val platform: String, val capabilities: List<Capability>, val selectedMode: HarnessMode, val sessionActive: Boolean)
val HarnessUiState.canStart: Boolean
```

- [ ] **Step 1: Write RED capability tests**

Tests must prove: unknown/unavailable mandatory capabilities disable start; iOS has no available capture/backend/storage capabilities; selecting Replay does not bypass iOS unavailability; and an active session cannot be started again.

- [ ] **Step 2: Observe RED**

Run:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:allTests :composeApp:allTests
```

Expected: compilation failure because the declared types do not exist.

- [ ] **Step 3: Implement the minimum domain and UI**

`canStart` is true only when no session is active and every capability required by the selected mode is `Available`. The iOS composition root constructs explicit `Unavailable` values and never exposes an enabled start callback. `HarnessApp` renders diagnostics, Replay/Live selection, and disabled reason text; it does not implement session execution.

- [ ] **Step 4: Verify and commit**

Run:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:allTests :composeApp:allTests :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64
cd ../.. && make verify
```

Commit:

```bash
git add apps/diagnostic-harness
git commit -m "feat: add fail-closed harness capability shell"
```

---

### Task 3: Executable Android capability probe

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/CameraCapabilityProbe.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/ProbeResult.kt`
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/CameraCapabilityProbeTest.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/probe/ProbeResultTest.kt`
- Create when a device run succeeds: `docs/evidence/device-probes/s25-ultra-android-16.json`

**Interfaces:**
- Produces:

```kotlin
@Serializable
data class ProbeResult(
    val deviceModel: String,
    val osBuild: String,
    val cameraId: String,
    val timestampSource: String,
    val sourceDataspaceRaw: Int?,
    val sourceColorMatrix: String?,
    val sourceColorRange: String?,
    val pixelFormat: String,
    val cameraxVersion: String,
    val probeGitSha: String,
    val directoryFsyncSupported: Boolean,
    val atomicRenameSupported: Boolean,
)
```

- [ ] **Step 1: Write RED serialization/admission tests**

Assert canonical JSON field names, that `timestampSource != "REALTIME"` is not live-admissible, that null matrix/range is not live-admissible, and that failed directory fsync/atomic rename is not storage-admissible.

- [ ] **Step 2: Observe RED**

Run `cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:testDebugUnitTest`.

Expected: compilation failure because `ProbeResult` does not exist.

- [ ] **Step 3: Implement probe without capture persistence**

The instrumentation probe selects the back camera, reads `SENSOR_INFO_TIMESTAMP_SOURCE`, binds one YUV_420_888 analysis buffer, accesses nullable `ImageProxy.image.dataSpace`, records raw DataSpace and decoded matrix/range, closes the proxy in `finally`, then exercises `android.system.Os.open`/`Os.fsync` and same-filesystem atomic rename in app cache. It writes only the sanitized `ProbeResult`; it never writes frame bytes.

- [ ] **Step 4: Run deterministic checks**

Run:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:testDebugUnitTest :androidApp:assembleDebug
cd ../.. && make verify
```

- [ ] **Step 5: Run physical probe when the S25 is connected**

Run `adb devices -l` and require exactly one authorized physical target matching the S25. Then run:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:connectedDebugAndroidTest
```

Copy only the sanitized JSON result into `docs/evidence/device-probes/s25-ultra-android-16.json`, validate it with the unit parser, and inspect it for identifiers beyond the declared device/build/camera fields. If REALTIME, known matrix/range, directory fsync, or atomic rename is false, record the exact evidence and stop the live milestone for material direction; do not weaken the gate.

- [ ] **Step 6: Commit code and any successful evidence**

```bash
git add apps/diagnostic-harness/androidApp docs/evidence/device-probes
git commit -m "feat: add Android capture capability probe"
```

If no device is connected, commit probe code without fabricated evidence and mark only the device-run ledger item pending.

---

### Task 4: SessionManifest and RawPoseFrame 1.1.0 semantics

**Files:**
- Modify: `contracts/schema/session_manifest.v1.schema.json`
- Modify: `contracts/schema/raw_pose_frame.v1.schema.json`
- Modify: `contracts/tooling/validator_error_codes.v1.json`
- Modify: `reference/src/observation_contracts/semantic.py`
- Modify: `reference/tests/test_semantic_session.py`
- Modify: `reference/tests/test_structural.py`
- Modify: `reference/tests/test_errors.py`

**Interfaces:**
- Produces: optional `session_kind`; `camera.facing=fixture`; camera provenance fields; `backend_timestamp_ms`; stable `SESSION.MEDIA_ROLE` semantics.

- [ ] **Step 1: Write RED semantic tests**

Add tests proving:

```python
# Existing 1.0 artifact: missing session_kind behaves as live_study.
# fixture_replay + exactly one conformance_fixture: accepted.
# fixture_replay + static_reference_clip or missing conformance_fixture: SESSION.MEDIA_ROLE.
# live_study without exactly one static_reference_clip: SESSION.MEDIA_ROLE.
# live_conformance without a static clip: accepted.
```

Add structural tests proving accepted 1.1 camera provenance and integer `backend_timestamp_ms`, and rejection of unknown `session_kind` or non-integer backend timestamp.

- [ ] **Step 2: Observe RED**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_semantic_session.py reference/tests/test_structural.py reference/tests/test_errors.py -q
```

Expected: failures because the enum fields/code and conditional semantics do not exist.

- [ ] **Step 3: Implement minimal compatible schema/validator change**

Add optional schema fields exactly as designed. Existing missing `session_kind` resolves to `live_study` in the semantic validator. Emit one `SESSION.MEDIA_ROLE` issue at `/media_references` for the first violated role rule. Do not rename or reuse `SESSION.COUNT_MISMATCH` elsewhere.

Camera provenance fields are optional in the schema for same-major compatibility; the harness requires them for live admission. `source_dataspace_raw` is an integer. `backend_timestamp_ms` is an integer with minimum zero.

- [ ] **Step 4: Verify focused GREEN**

Run the focused test command from Step 2 and require all pass.

- [ ] **Step 5: Commit**

```bash
git add contracts/schema contracts/tooling/validator_error_codes.v1.json reference/src/observation_contracts/semantic.py reference/tests
git commit -m "feat: add diagnostic session contract semantics"
```

---

### Task 5: 1.1.0 fixtures, release evidence, and review gate

**Files:**
- Create: `fixtures/synthetic/valid/fixture_replay/fixture_metadata.json`
- Create: `fixtures/synthetic/valid/fixture_replay/manifest.json`
- Create: `fixtures/synthetic/valid/fixture_replay/frames.ndjson`
- Create: synthetic fixture media declared by `fixture_metadata.json`
- Create: `fixtures/synthetic/negative/replay_media_role/manifest.json`
- Create: `fixtures/synthetic/negative/replay_media_role/frames.ndjson`
- Create: `fixtures/synthetic/negative/replay_media_role/expected_failure.json`
- Modify: `contracts/CHANGELOG.md`
- Modify: `contracts/schema-release-process.md` only if the release inventory needs clarification, without weakening it.
- Modify: tests that enumerate committed fixtures.

**Interfaces:**
- Produces: validator-accepted synthetic `fixture_replay` artifact and one declared-negative `SESSION.MEDIA_ROLE` case; 1.1.0 changelog entry.

- [ ] **Step 1: Add fixture discovery expectations and observe RED**

Extend committed-fixture tests to require the new positive group and negative code. Run the focused tests and observe missing-fixture failures.

- [ ] **Step 2: Add only synthetic artifacts**

The positive manifest uses `session_manifest_schema_version=1.1.0`, `raw_pose_frame_schema_version=1.1.0`, `participant_pseudonym_id=synthetic-fixture-replay-01`, `session_kind=fixture_replay`, `camera.facing=fixture`, and exactly one `conformance_fixture` media reference. Every hash and byte length is computed from committed bytes. Capture-quality labels use `synthetic_*` wording.

The negative fixture differs by one defect only: its required conformance role is absent or replaced, and `expected_failure.json` names `SESSION.MEDIA_ROLE`.

- [ ] **Step 3: Update release notes**

Record SessionManifest 1.1.0 and RawPoseFrame 1.1.0 as backward-compatible minor releases, list every added field/enum/code, state that missing `session_kind` preserves live-study semantics, and link ADR-0004 plus the harness design.

- [ ] **Step 4: Run complete deterministic gate**

Run:

```bash
make verify
make verify-harness-foundation
git diff --check
```

Then perform a clean-clone verification of `make bootstrap && make verify` at the exact candidate commit.

- [ ] **Step 5: One independent contract review**

Give the non-implementing reviewer only the contract-release range and ask for Critical/Important findings about compatibility, session-kind/media truthfulness, provenance fields, error-code stability, and fixture validity. Permit one scoped re-review after fixes. Record Minors as debt.

- [ ] **Step 6: Commit release candidate**

```bash
git add contracts fixtures reference docs
git commit -m "feat: release observation contracts 1.1.0"
```

Do not create the immutable release tag on a feature-branch commit that may be squashed. The tag is created on the final protected `main` integration commit after required CI passes and release authority is exercised.

---

## Plan completion gate

Before opening the foundation PR, require:

- clean worktree;
- exact base/head SHAs in the external ledger;
- `make verify` green;
- `make verify-harness-foundation` green;
- Android APK assembled;
- both iOS frameworks linked locally;
- contract review has zero unresolved Critical/Important findings;
- physical probe evidence recorded if the S25 was connected, otherwise a typed pending external-device checkpoint with no impact on replay/shared-core planning;
- PR description states that SessionRunner, MediaPipe execution, replay, live capture, study data, and release tagging remain out of scope.
