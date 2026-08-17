# Slice 4a — Android Storage and Installable Composition Prerequisite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`, and use `superpowers:test-driven-development` for every behavior change. Dispatch every task through the already-dogfooded Buzz Slice 2 control plane (activate → bind → dispatch → acknowledge → start → implement/commit/push → submit → drain outbox → review). Do not re-derive that mechanism; Task 0 of `docs/superpowers/plans/2026-08-17-phase-0-shared-deterministic-core.md` already did.

**Milestone boundary:** This is **Slice 4a**, a named prerequisite to existing section 15 Slice 4 ("Android replay — pinned MediaPipe/model, VIDEO adapter, UI/export, five-run S25 evidence"). It does not renumber or rename any existing slice; see design spec §1.

**Goal:** Land a real, device-conformant `AndroidStoragePort` against the unchanged 12-method `StoragePort`, prove it with a shared fake/real conformance suite, gather non-participant storage capability evidence, wire an installable Android composition path with proven byte-identical JVM/Android export output, and land the device-gated S25 process-kill regression without blocking the milestone on hardware.

**Architecture:** Unchanged from ADR-0004 and the Slice 3 decision record. `shared/commonMain` keeps owning the six-step commit sequence, lifecycle, and counters; `shared/androidMain` supplies primitive implementations only; `androidApp` is the only place `Context` is constructed into a `StorageRoots`.

**Authority:** This plan is subordinate to `docs/superpowers/specs/2026-08-17-android-storage-composition-prerequisite-design.md`, which is itself subordinate to `docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md`, ADR-0004, and the Slice 3 numbered decision record in `docs/superpowers/plans/2026-08-17-phase-0-shared-deterministic-core.md`. It does not create a second design specification and does not revisit any settled Slice 3 decision.

**Tech Stack:** Kotlin 2.4.10, AGP 9.1.0 KMP `androidLibrary` plugin, kotlinx.serialization 1.10.0, coroutines 1.11.0, Gradle 9.3.1, JDK 21/JVM target 17, Python 3.12+ reference validator, GitHub Ubuntu CI, physical Samsung Galaxy S25 Ultra (Android 16) for device-gated evidence.

## Global Constraints

- The 12-method `StoragePort` interface does not change. No primitive is added, removed, collapsed, or coarsened.
- The six-step commit sequence (fsync stream → reconcile → write+fsync manifest → fsync staging directory → check-then-rename → fsync exports root) stays in `shared/commonMain`. `androidMain` implements primitives only — no ordering logic in `AndroidStoragePort`.
- `Context` never enters `commonMain`. It is constructed into a `StorageRoots` exactly once, in the `androidApp` composition layer (Task 4).
- No storage permission is added. Scoped storage does not affect app-private `filesDir`.
- No MediaPipe dependency, CameraX, YUV acquisition, VIDEO/LIVE_STREAM adapter, or UI screen/state expansion belongs in this slice.
- Each task is regression-first. Record the failing command and reason before production changes.
- No routine independent model review is created for Tasks 1, 3, 4, 5. Task 2 alone gets a mandatory scoped Codex Sol pre-implementation challenge (design spec §6.2, §10). One unconditional full-range review occurs at the completed milestone boundary (Task 6), assigned to a provider that implemented nothing in this milestone.
- Dual-provider routing: Codex-implemented → Claude reviews; Claude-implemented → Codex Sol reviews. Never same-tier self-review for a triggered review. Only Critical/Important findings block; one review plus one scoped re-review is the maximum; Minor findings are recorded as debt.
- Every task dispatch goes through the Buzz Slice 2 control plane. The milestone-wide recovery-cycle budget is **one**, cumulative across every task below — a second recovery cycle aborts the dogfood run for this milestone and the affected task completes outside Buzz.
- Record provider, model, account identity, tokens in/out, and wall-clock duration per routing decision in the ignored run ledger. Missing telemetry is recorded as typed `unavailable`, never estimated. No provider account/credential identity is encoded in `agent_id`, branch names, or filesystem paths.
- Never use or propose paid credits. No recurring macOS CI runner is added.

## Task Dependencies

```text
Task 0 (debt fixes)
   │
   ▼
Task 1 (AndroidStoragePort) ──┐
   │                          │
   ▼                          │
Task 2 (conformance suite,    │
        Sol pre-challenge)    │
   │                          │
   ▼                          │
Task 3 (capability probe) ◄───┘  (Task 3 needs AndroidStoragePort from Task 1; independent of Task 2's suite)
   │
   ▼
Task 4 (installable composition path, byte-identity)
   │
   ▼
Task 5 (S25 process-kill, device-gated)
   │
   ▼
Task 6 (milestone-boundary review, protected CI, squash-merge)
```

Task 0 must land before or with Task 1 — it does not block on Task 1 and can be dispatched first in isolation. Tasks 1–5 are strictly sequential: each later task's composition or evidence depends on the previous task's concrete artifact (Task 3's probe needs a real port; Task 4's composition needs the probe result wired into capability state; Task 5's process-kill test reuses Task 4's composition root).

---

### Task 0: Clear the two Minor debts

**Files:**
- Modify: `apps/diagnostic-harness/shared/build.gradle.kts`
- Modify: `apps/diagnostic-harness/gradle/libs.versions.toml`
- Modify: `reference/tests/test_harness_build_contract.py`
- Create: `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/contract/LocaleIndependentCanonicalNumberTest.kt`
- Create (added once Task 1 opens the `androidDeviceTest` source set; see Step 5 note below): `apps/diagnostic-harness/shared/src/androidDeviceTest/kotlin/com/calisthenics/diagnostic/contract/LocaleIndependentCanonicalNumberTest.kt`

**Interfaces:**
- No production API changes. `CanonicalNumber.format`, `encodeManifest`, `encodeFrameLine` keep their existing signatures.
- Adds `kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }` to `shared/build.gradle.kts`.

- [ ] **Step 1: Sub-task A — RED locale regression test on the JVM host**

Add `LocaleIndependentCanonicalNumberTest.kt` in `shared/src/jvmTest`. It must:
- load the existing `commonTest/resources/contract/number-vectors.json` RFC 8785 vectors (the same fixture `ObservationJsonTest.canonicalNumberFormatterMatchesRfc8785Vectors` already validates against);
- encode each vector through `CanonicalNumber.format` and record the byte output under the JVM's ambient default `Locale`;
- capture `Locale.getDefault()`, set it to `Locale.GERMANY`, re-encode, assert identical bytes;
- set it to `Locale.forLanguageTag("ar-SA")`, re-encode, assert identical bytes;
- restore the original default locale in a `@AfterTest` teardown unconditionally, including on assertion failure, so this test can never leak locale state into another test in the same JVM;
- also encode one full `SessionManifest`/`RawPoseFrame` pair containing at least one non-trivial double (reuse a fixture already available in `commonTest`, e.g. adapt `SessionExporterTest`'s `frame`/`manifest` builders or `SyntheticExportTest`'s fixtures) through `encodeManifest`/`encodeFrameLine` under all three locale states and assert byte-identical output.

Run `cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest --tests "*LocaleIndependentCanonicalNumberTest*"` before writing the test body (expect compilation failure: class does not exist) to confirm RED for the right reason.

- [ ] **Step 2: GREEN on the JVM host, then confirm no production change was needed**

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest --tests "*LocaleIndependentCanonicalNumberTest*"
```

Expected: passes without any change to `CanonicalNumber.kt`, `ObservationJson.kt`, or `ObservationModels.kt`, because `Double.toString()` is already JLS locale-invariant. If it fails, the failure identifies the actual locale-dependent call site; fix only that call site, re-run, and record the fix explicitly in the task commit message rather than silently folding it in as if anticipated.

- [ ] **Step 3: Sub-task B — RED toolchain-pinning regression test**

Extend `reference/tests/test_harness_build_contract.py`:

```python
EXPECTED_VERSIONS["jdk"] = "21"
...
assert "jvmToolchain(" in shared_build
```

Run `.venv/bin/python -m pytest reference/tests/test_harness_build_contract.py -q`. Expected RED: `jdk` missing from `EXPECTED_VERSIONS` comparison and `"jvmToolchain(" in shared_build` false.

- [ ] **Step 4: Implement the toolchain pin**

Add to `gradle/libs.versions.toml` `[versions]`:

```toml
jdk = "21"
```

Add to `shared/build.gradle.kts`, alongside the existing `kotlin { ... }` block (reusing the same `libs.versions.*.get().toInt()` accessor pattern already used for `compileSdk`/`minSdk`):

```kotlin
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    androidLibrary { /* unchanged */ }
    jvm { /* unchanged */ }
    ...
}
```

Do not add a toolchain pin to `androidApp` or `composeApp`; this debt is scoped to `shared`'s `jvm()` host-test target only (design spec §4.2).

- [ ] **Step 5: Prove GREEN and confirm the JVM/Android test-source-set names Task 1 will use**

```bash
.venv/bin/python -m pytest reference/tests/test_harness_build_contract.py -q
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:tasks --all | grep -i android
```

The last command exists to confirm, before Task 1 opens Android test source sets, the exact task/source-set names the installed `com.android.kotlin.multiplatform.library` 9.1.0 plugin generates when `withHostTestBuilder {}`/`withDeviceTestBuilder {}` are later added (design spec §5, §6.2 refer to `androidDeviceTest` as the expected name; confirm rather than assume before Task 1 writes files against it). Record the confirmed names in the Task 0 dogfood note so Task 1 does not re-derive them.

Because Task 0 lands before the Android test source sets exist, the `androidDeviceTest` copy of `LocaleIndependentCanonicalNumberTest.kt` (Sub-task A) is added as a small follow-up commit inside Task 1's own step sequence once `androidLibrary { withDeviceTestBuilder {} }` is wired — Task 1 Step 6 below carries this forward explicitly so the debt is not silently dropped.

- [ ] **Step 6: Full deterministic gate**

```bash
make verify
make verify-harness-jvm
git diff --check
```

- [ ] **Step 7: Commit**

Commit subject: `fix: pin locale-independent number formatting and JVM toolchain`

---

### Task 1: AndroidStoragePort

**Files:**
- Modify: `apps/diagnostic-harness/shared/build.gradle.kts` (add `withHostTestBuilder {}`/`withDeviceTestBuilder {}` to the `androidLibrary {}` block, using the exact names Task 0 Step 5 confirmed; add `androidx-test-runner`/`androidx-test-core` to the new `androidDeviceTest` source set's dependencies)
- Create: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/export/AndroidStorageRoots.kt`
- Create: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/export/AndroidStoragePort.kt`
- Create: `apps/diagnostic-harness/shared/src/androidDeviceTest/kotlin/com/calisthenics/diagnostic/export/AndroidStoragePortSmokeTest.kt`
- Create (carried forward from Task 0 Step 5): `apps/diagnostic-harness/shared/src/androidDeviceTest/kotlin/com/calisthenics/diagnostic/contract/LocaleIndependentCanonicalNumberTest.kt`
- Modify: `reference/tests/test_harness_build_contract.py` (assert the `androidLibrary` block declares both test builders, so removal is regression-guarded)

**Interfaces:**
- `AndroidStorageRoots.from(context: Context): StorageRootsResult` where `StorageRootsResult` is `sealed interface { data class Ready(val roots: StorageRoots); data class Unavailable(val reason: String) }` — see design spec §8.3 for the direct-boot/same-mount cases this return type exists to carry; Task 1 only needs the type and the same-mount check, since direct-boot detection is exercised for real in Task 4's composition context.
- `AndroidStoragePort(override val roots: StorageRoots) : StoragePort` — implements all twelve methods per design spec §5.2. No constructor parameter is `Context`.

- [ ] **Step 1: Enter the Slice 2 control-plane lifecycle for this task**

Dispatch, acknowledge, and start Task 1 through the established Buzz path. Record generation IDs, branch, base SHA in the ignored run ledger.

- [ ] **Step 2: RED — open the Android test source sets and write a failing smoke test**

Add `withHostTestBuilder {}.configure {}` and `withDeviceTestBuilder {}.configure {}` (or the confirmed equivalent names) to `androidLibrary {}` in `shared/build.gradle.kts`. Write `AndroidStoragePortSmokeTest.kt` in the new device-test source set:

```kotlin
val roots = AndroidStorageRoots.from(context).let { (it as StorageRootsResult.Ready).roots }
val port = AndroidStoragePort(roots)
val attempt = port.createStagingAttempt(Uuid("00000000-0000-4000-8000-000000000099"))
port.appendLine(attempt, """{"k":"v"}""")
port.flush(attempt)
port.fsyncFile(attempt, StorageFile.FRAMES)
val reconciled = port.reconcile(attempt)
assertEquals(1L, reconciled.lineCount)
port.writeManifest(attempt, "{}")
port.fsyncFile(attempt, StorageFile.MANIFEST)
port.fsyncDirectory(attempt.path)
val destination = "${roots.exportsRoot}/00000000-0000-4000-8000-000000000099"
assertFalse(port.destinationExists(destination))
port.renameNoReplace(attempt, destination)
assertTrue(port.destinationExists(destination))
assertTrue(port.scanCommittedRoot().isEmpty())
```

Confirm RED for the right reason: `AndroidStorageRoots`/`AndroidStoragePort` do not exist yet, so this fails to compile, not merely to pass.

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:tasks --all | grep -i device
```

- [ ] **Step 3: Implement `AndroidStorageRoots`**

Read `context.filesDir.absolutePath`; catch the specific unavailable-storage condition Android surfaces before first unlock (verify the exact exception type against the installed Android SDK/AOSP source rather than assuming; do not catch a bare `Throwable`) and return `Unavailable("direct_boot_credential_encrypted_storage_unavailable")` for it. Confirm same-mount: derive `stagingRoot`/`exportsRoot` per the design spec §5.1 layout, compare `android.system.Os.stat(path).st_dev` for `filesDir` against both derived roots' parent directories; return `Unavailable("staging_and_exports_not_same_mount")` if either `st_dev` differs. Otherwise return `Ready(StorageRoots(filesDir, stagingRoot, exportsRoot))`, which re-exercises the existing `StorageRoots.init` invariants.

- [ ] **Step 4: Implement `AndroidStoragePort`**

Implement all twelve methods exactly per design spec §5.2. Do not implement any method by calling a second `StoragePort` method on `this` to sequence behaviour — if a method's implementation needs to, stop and re-read design spec §5.2's invariant note before proceeding.

- [ ] **Step 5: GREEN**

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest :androidApp:assembleDebug
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:connectedDebugAndroidTest   # or the confirmed device-test task name; requires a connected device/emulator
```

If no device/emulator is attached, record the device-run as pending in the task's dogfood note; do not fabricate a passing result. The host-side compile/assemble checks above must still pass with no device attached.

- [ ] **Step 6: Add the carried-forward Android locale regression test**

Add the `androidDeviceTest` copy of `LocaleIndependentCanonicalNumberTest.kt` from Task 0 Step 5, now that the source set exists. Same content and assertions as the `jvmTest` version, run via the device-test task above.

- [ ] **Step 7: Full deterministic gate**

```bash
make verify
make verify-harness-jvm
git diff --check
```

- [ ] **Step 8: Commit, push, submit, drain, review**

Commit subject: `feat: implement AndroidStoragePort against the shared StoragePort contract`

Push, submit through the control plane, drain the outbox. No independent review is triggered for this task (design spec §10) — acceptance is the deterministic gate above plus diff/scope inspection.

---

### Task 2: Port conformance suite (the load-bearing task)

**Pre-implementation gate:** mandatory scoped Codex Sol challenge on the extraction design in design spec §6.2 before any test/production code is written for this task — not conditional on a proposed deviation, unlike Tasks 5/6 of the prior plan. The challenge specifically probes: whether `FaultInjectingStoragePort` wrapping a real `AndroidStoragePort` forces the identical code paths the fake's inline fault injection forced; whether `precreateDestination` as a harness-only backdoor legitimately substitutes for filesystem state the protocol itself cannot produce; and whether every probe the brief enumerates (fault sweep, destination cases, abort/crash classification, corruption scan, staging uniqueness, `filesDir` enforcement) is actually writable against the real-port harness as designed. Record the challenge transcript/outcome in the ignored run ledger before Step 1 begins. If the challenge surfaces a required design change, update design spec §6.2 in the same task branch before implementing against the revised design — do not implement against a design the challenge has already invalidated.

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/StoragePortTestHarness.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/FaultInjectingStoragePort.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/StoragePortConformanceSuite.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/FakeStorage.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/SessionExporterTest.kt`
- Create: `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/export/FakeStoragePortConformanceTest.kt`
- Create: `apps/diagnostic-harness/shared/src/androidDeviceTest/kotlin/com/calisthenics/diagnostic/export/AndroidStoragePortConformanceTest.kt`

**Interfaces:**
- `StoragePortTestHarness { fun freshPort(): StoragePort; fun precreateDestination(destinationPath: String, populated: Boolean); fun dispose() }`
- `FaultInjectingStoragePort(delegate: StoragePort, failOnOrdinal: Int? = null, failOnOrdinals: Set<Int> = emptySet(), dropAppends: Boolean = false) : StoragePort` — records `operations: List<String>` identically to the current `FakeStorage.operations` shape, then delegates.
- `abstract class StoragePortConformanceSuite { abstract fun harness(): StoragePortTestHarness; /* eleven-plus test methods */ }`

- [ ] **Step 1: RED — write the harness and decorator with failing/uncompilable suite references**

Extract `FaultInjectingStoragePort` from `FakeStorage.before()`, generalized to wrap any `StoragePort` delegate. Write `StoragePortTestHarness` and a `FakeStoragePortTestHarness` implementation. Write `StoragePortConformanceSuite` with every probe design spec §6.2 lists, referencing `harness()` — this will not compile until `FakeStoragePortConformanceTest`/`AndroidStoragePortConformanceTest` exist, which is the expected RED.

- [ ] **Step 2: Strip `FakeStorage` down to a pure in-memory port**

Remove `failOnOrdinal`/`failOnOrdinals`/`dropAppends`/`before()`/`precreateDestination`/`abortMarkerFor` from `FakeStorage`; move the ordinal/operation-log behaviour into `FaultInjectingStoragePort` and the destination-precreation behaviour into `FakeStoragePortTestHarness`. Update `SessionExporterTest` to construct `FaultInjectingStoragePort(FakeStorage(), ...)` where it previously constructed `FakeStorage(failOnOrdinal = ...)` directly, keeping only the exporter-level assertions design spec §6.2 says stay JVM/fake-only (fixed operation order, idempotent second-finalize, sanitized-error-detail). The pre-check mutation check is not part of this JVM/fake-only set — it is verified in Step 5 below against both concrete suites, per design spec §6.1 point 3/§6.2.

- [ ] **Step 3: Concrete JVM suite — GREEN on the fake**

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest
```

Expected: `FakeStoragePortConformanceTest` and the modified `SessionExporterTest` both pass; every probe in design spec §6.2 is present and green against `FakeStorage`.

- [ ] **Step 4: Concrete Android suite — GREEN on the real port**

Write `AndroidStoragePortConformanceTest : StoragePortConformanceSuite()` wired to a `AndroidStoragePortTestHarness` (implements `precreateDestination` via direct `java.io.File`/`Files` operations against a scratch subtree of the instrumented test's own `context.filesDir`, entirely outside the port under test, and `dispose()` by deleting that scratch subtree).

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:connectedDebugAndroidTest   # or confirmed device-test task name
```

If no device/emulator is attached, record pending in the task dogfood note; the JVM suite in Step 3 must still be fully green and is the CI-covered half of this task's acceptance.

- [ ] **Step 5: Confirm the deleted-pre-check mutation turns both suite variants RED**

Per design spec §6.1 point 3/§6.2, temporarily delete the `destinationExists` pre-check call in `SessionExporter.finalize` (`shared/commonMain`) and confirm the empty-destination-refused-by-precheck probe already present in `StoragePortConformanceSuite` fails on **both** concrete suites:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest --tests "*FakeStoragePortConformanceTest*"
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:connectedDebugAndroidTest --tests "*AndroidStoragePortConformanceTest*"   # or confirmed device-test task name; requires a connected device/emulator
```

Expected: both fail with the mutation applied, because each backend's underlying rename (`FakeStorage`'s in-memory move, `AndroidStoragePort.renameNoReplace`'s `Files.move`/`Os`-backed primitive) is a plain rename that silently replaces an empty destination once the pre-check is gone. Revert the mutation immediately after confirming both variants are RED — this is one temporary production edit run through both suite variants, not a standing mutation-testing framework. If no device/emulator is attached, record the device-side confirmation as pending in the task dogfood note; the JVM-side confirmation is mandatory and CI-covered, and this task's acceptance does not close until the device-side confirmation lands, even if that lands as a documented pending item.

- [ ] **Step 6: Confirm every brief-listed probe actually exercises the real port, not just the fake**

Manually cross-check the enumerated list in design spec §6.2 against `StoragePortConformanceSuite`'s method list; any probe present in the suite that a platform-conditional (`if (harness is FakeStoragePortTestHarness) ...`) would silently skip on Android is a finding to fix before proceeding, not to document as an exemption — this is exactly the failure mode this task exists to prevent.

- [ ] **Step 7: Full deterministic gate**

```bash
make verify
make verify-harness-jvm
git diff --check
```

- [ ] **Step 8: Commit, push, submit, drain**

Commit subject: `test: extract shared StoragePort conformance suite for fake and real ports`

No further independent review beyond the pre-implementation Sol challenge already completed in this task — routine acceptance is the deterministic gate plus diff/scope inspection, per design spec §10.

---

### Task 3: Storage capability probe

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/StorageCapabilityProbe.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/StorageProbeResult.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/probe/StorageProbeResultTest.kt`
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/StorageCapabilityProbeTest.kt`
- Modify: `reference/tests/test_harness_build_contract.py` (mirror the existing `test_android_probe_has_executable_instrumentation_wiring` pattern for the new probe)

**Interfaces:**
- `StorageProbeResult` data class per design spec §7.2, with `toCanonicalJson()`/`fromCanonicalJson()` mirroring `ProbeResult`'s existing convention, plus a derived `isDurabilityAdmissible(): Boolean` (not a raw claim of crash-time atomicity — see design spec §7.2's required limitation statement, which becomes a KDoc comment on this property, not a separate boolean field).
- `StorageCapabilityProbe(context: Context, probeGitSha: String)` with `fun run(): StorageProbeResult` and `fun runAndPersist(): StorageProbeResult`, mirroring `CameraCapabilityProbe`'s existing shape and its `cacheDir`-based persistence convention.

- [ ] **Step 1: RED — unit test for `StorageProbeResult`, instrumentation test for the probe**

`StorageProbeResultTest.kt` (host JVM unit test, no device needed): canonical JSON field names, `isDurabilityAdmissible()` false whenever any boolean is false, true only when all are true. `StorageCapabilityProbeTest.kt` (instrumented): constructs the probe against `AndroidStorageRoots.from(targetContext)` (Task 1) with a scratch subtree, runs it, and asserts the five behaviours in design spec §7.1. Confirm RED for missing-class compilation failure.

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:testDebugUnitTest
```

- [ ] **Step 2: Implement `StorageProbeResult` and `StorageCapabilityProbe`**

Exercise, in order: `appendLine`/`flush`/`fsyncFile` on a throwaway attempt; `fsyncDirectory` on the staging directory; `renameNoReplace` within `filesDir` to a scratch destination (success); a second attempt renaming onto a pre-populated destination (failure, destination untouched); a third attempt renaming onto a pre-existing empty destination (refused by the `destinationExists` pre-check, never reaching `renameNoReplace`). Read `filesystem_type` from `/proc/mounts` (the filesystem-type column of the entry whose mount point is the longest prefix of the resolved `filesDir` path — `android.os.StatFs` exposes block/space accounting only, not filesystem type, and is not used for this field), `null` where `/proc/mounts` is unreadable or no matching entry is found — never fabricated. Persist via the same fsync-then-rename pattern `CameraCapabilityProbe.runAndPersist()` already uses, writing to `context.cacheDir` under a new result filename (e.g. `storage-capability-probe.json`).

- [ ] **Step 3: GREEN on host unit tests; run instrumented probe when a device is attached**

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:testDebugUnitTest :androidApp:assembleDebug
cd ../.. && make verify
adb devices -l   # require exactly one authorized physical target matching the S25
cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:connectedDebugAndroidTest
```

- [ ] **Step 4: Record device evidence when available**

Copy only the sanitized JSON result into `docs/evidence/device-probes/s25-ultra-storage-capability.json`, following the exact `s25-ultra-android-16.json` precedent. If any of append/flush/fsyncFile/fsyncDirectory/rename-within-filesDir fails, or the populated-destination rename unexpectedly succeeds, or the empty-destination case is not refused by the pre-check, stop and escalate — do not weaken the gate. If no device is connected, commit probe code without fabricated evidence; the device-run ledger item is pending.

- [ ] **Step 5: Full deterministic gate**

```bash
make verify
make verify-harness-jvm
git diff --check
```

- [ ] **Step 6: Commit**

```bash
git add apps/diagnostic-harness/androidApp docs/evidence/device-probes 2>/dev/null
git commit -m "feat: add Android storage capability probe"
```

Commit subject: `feat: add Android storage capability probe`. No independent review is triggered for this task.

---

### Task 4: Installable composition path

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/conformance/SyntheticFixture.kt`
- Modify: `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/contract/GenerateSyntheticExport.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/HarnessComposition.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/MainActivity.kt`
- Modify: `apps/diagnostic-harness/androidApp/build.gradle.kts` (new `copyJvmSyntheticExportFixture` task wired ahead of the `androidTest` assets source set)
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/InstallableCompositionExportParityTest.kt`
- Modify: `Makefile` (new `verify-harness-android-export-parity` target)

**Interfaces:**
- `SyntheticFixture` (commonMain, public): `val sessionId: Uuid`, `fun manifestTemplate(): SessionManifest`, `val sourceRgb24: ByteArray`, `val sourceWidth: Int`, `val sourceHeight: Int` — identical literal values to the existing private `GenerateSyntheticExport.kt` fixture, promoted verbatim.
- `HarnessComposition(context: Context) { fun runDeterministicSession(): CompositionResult }` where `CompositionResult` is a small sealed type wrapping either a `DeterministicCoreResult` (Task 1's `AndroidStorageRoots.Ready` path) or a typed `StorageUnavailable(reason: String)` (the `Unavailable` path from design spec §8.3).

- [ ] **Step 1: RED — promote the fixture, prove nothing changed**

Move `syntheticManifest()`, `SESSION_ID`, and the fixture byte array out of `GenerateSyntheticExport.kt` into `SyntheticFixture.kt`, verbatim. Update `GenerateSyntheticExport.kt` to call `SyntheticFixture`. Run:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest :shared:generateSyntheticExport
make verify-kotlin-export
```

Expected: byte-identical output to before the extraction (this step changes no literal value, only where it lives) — this is a refactor-first regression check, not new behaviour, so "RED" here means "confirm GREEN is unchanged before building on top of it."

- [ ] **Step 2: RED — write the failing composition and parity test**

Write `HarnessComposition.kt` calling into `AndroidStorageRoots.from`/`AndroidStoragePort` (Task 1) and `DeterministicCore` (existing) with `SyntheticFixture`. Write `InstallableCompositionExportParityTest.kt` asserting byte-identical `manifest.json`/`frames.ndjson` against bundled assets that do not exist yet. Confirm RED: missing classes/assets.

- [ ] **Step 3: Wire the Gradle asset-copy task**

In `androidApp/build.gradle.kts`, register `copyJvmSyntheticExportFixture` depending on `:shared:generateSyntheticExport`, copying `shared/build/conformance/synthetic-session/{manifest.json,frames.ndjson}` into `androidApp/build/generated/androidTestAssets/synthetic-session/`; register that directory on the `androidTest` source set's `assets.srcDir`; make the relevant `connectedDebugAndroidTest`/`assembleDebugAndroidTest` task depend on `copyJvmSyntheticExportFixture`.

- [ ] **Step 4: Implement `HarnessComposition` and wire `MainActivity`**

Implement `runDeterministicSession()` per design spec §8.2–8.3: construct roots, branch on `Ready`/`Unavailable`, run `DeterministicCore` on the `Ready` path. In `MainActivity.kt`, replace the `STORAGE_DURABILITY` `Unknown` placeholder with a real `StorageCapabilityProbe` (Task 3) result mapped to `CapabilityState.Available`/`Unavailable`, and supply `HarnessApp`'s existing `onStart` parameter (`composeApp/src/commonMain/.../ui/HarnessApp.kt` already declares `onStart: () -> Unit = {}` and an enabled-by-`canStart` `Button` wired to it; `MainActivity` currently omits this parameter, so it is a no-op) with a lambda that launches `HarnessComposition.runDeterministicSession()` off the main thread — using `rememberCoroutineScope`/`Dispatchers.IO`, added here as the minimal plumbing this wiring needs, not a new screen — and toggles the existing `sessionActive` field around the call. Introduce no new `HarnessUiState` field, screen, capability ID, or navigation state.

- [ ] **Step 5: Implement the parity test and run it**

`InstallableCompositionExportParityTest.kt` reads the bundled `manifest.json`/`frames.ndjson` from instrumentation test assets, runs `HarnessComposition(targetContext).runDeterministicSession()`, reads the resulting on-device export bytes back via the port's own `roots`, and asserts byte-for-byte equality for both files.

```bash
adb devices -l   # require exactly one authorized physical target matching the S25
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:generateSyntheticExport :androidApp:connectedDebugAndroidTest
```

Any divergence is a blocking finding for this milestone (design spec §8.4, §11) — stop and escalate; do not adjust the assertion to tolerate it.

- [ ] **Step 6: Add and run the Makefile target**

Add `verify-harness-android-export-parity` to `Makefile`, gated on `adb devices -l` returning exactly one authorized target, running the same gradle invocation as Step 5. This target is not added to `.github/workflows/verify.yml` — Ubuntu CI has no attached device.

- [ ] **Step 7: Confirm the installable APK**

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug
```

Confirm the produced `androidApp/build/outputs/apk/debug/androidApp-debug.apk` installs and launches on the S25 (`adb install -r`), and that the existing capability screen now shows `STORAGE_DURABILITY` as a real `Available`/`Unavailable` result rather than the previous "storage probe pending" placeholder. This is a manual dogfood smoke step, not an automated test; record it as a recovery-cycle-free happy-path step in the run ledger.

- [ ] **Step 8: Full deterministic gate**

```bash
make verify
make verify-harness-jvm
git diff --check
```

- [ ] **Step 9: Commit**

Commit subject: `feat: wire installable Android composition with byte-identical export parity`. No independent review is triggered for this task.

---

### Task 5: S25 process-kill test (device-gated, must not block the milestone)

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/export/ProcessKillDuringExportTest.kt`
- Modify: `Makefile` (new `verify-harness-s25-process-kill` target running both invocations in sequence)
- Create: `docs/evidence/device-probes/s25-ultra-process-kill-ledger.json`
- Create: `reference/tests/test_device_evidence_ledger.py`

**Interfaces:**
- `ProcessKillDuringExportTest` has exactly two `@Test` methods, `killDuringCommit` and `verifyNoPartialEntryAndResidueClassification`, designed per design spec §9.1 to run as two separate `am instrument` invocations against the same on-device roots, never relying on in-process continuation between them.
- The ledger file schema: `{"status": "pending" | "recorded", "missing_precondition": string (required, non-empty, iff status == "pending"), "evidence_file": string (required iff status == "recorded")}`.

- [ ] **Step 1: RED — write the ledger schema test first**

`test_device_evidence_ledger.py`:

```python
def test_s25_process_kill_ledger_reports_pending_or_recorded_status() -> None:
    ledger = json.loads((ROOT / "docs/evidence/device-probes/s25-ultra-process-kill-ledger.json").read_text())
    assert ledger["status"] in {"pending", "recorded"}
    if ledger["status"] == "pending":
        assert ledger["missing_precondition"]
    else:
        assert ledger["evidence_file"]
```

Run `.venv/bin/python -m pytest reference/tests/test_device_evidence_ledger.py -q`. Expected RED: file does not exist.

- [ ] **Step 2: GREEN the ledger with an honest pending state**

Create `docs/evidence/device-probes/s25-ultra-process-kill-ledger.json`:

```json
{"status": "pending", "missing_precondition": "no S25 Ultra attached"}
```

```bash
.venv/bin/python -m pytest reference/tests/test_device_evidence_ledger.py -q
make verify
```

This makes the pending state CI-visible immediately, independent of whether a device becomes available before this task's branch merges.

- [ ] **Step 3: RED — write the two-invocation instrumented test**

Write `killDuringCommit`: runs `HarnessComposition`'s composition (Task 4) with a `FaultInjectingStoragePort`-shaped wrapper (Task 2's decorator, reused here — do not write a third fault-injection mechanism) whose injected action at a chosen ordinal is `Process.killProcess(Process.myPid())` instead of a thrown exception, targeting an ordinal after at least one primitive has run and before the commit completes. Write `verifyNoPartialEntryAndResidueClassification`: scans the same roots via `scanCommittedRoot()`/`scanStagingResidues()` and asserts no partial/corrupt exports-root entry and correct `CrashResidue` classification of the abandoned attempt. Confirm RED for missing-class compilation failure, then confirm `killDuringCommit` cannot be meaningfully asserted GREEN/RED via normal JUnit reporting (the process dies) — this is expected, not a test-authoring mistake; the driver script in Step 4 is what actually sequences and interprets both invocations.

- [ ] **Step 4: Wire the two-invocation Makefile target**

```make
verify-harness-s25-process-kill:
	@set -eu; \
	device_count=$$(adb devices -l | grep -c "^[^ ]*\sdevice "); \
	test "$$device_count" = "1"; \
	cd apps/diagnostic-harness; \
	./gradlew --no-daemon :androidApp:connectedDebugAndroidTest --tests "*ProcessKillDuringExportTest.killDuringCommit*" || true; \
	./gradlew --no-daemon :androidApp:connectedDebugAndroidTest --tests "*ProcessKillDuringExportTest.verifyNoPartialEntryAndResidueClassification*"
```

The first invocation's `|| true` is deliberate and documented inline in the Makefile: a self-kill invocation ending the instrumentation process is the expected outcome, not a probe failure; the second invocation is the one whose PASS/FAIL result is the actual test acceptance.

- [ ] **Step 5: Run when the S25 is attached; update the ledger either way**

```bash
adb devices -l   # require exactly one authorized physical target matching the S25
make verify-harness-s25-process-kill
```

If it runs and passes: copy the sanitized result into `docs/evidence/device-probes/s25-ultra-process-kill.json` and update the ledger to `{"status": "recorded", "evidence_file": "s25-ultra-process-kill.json"}`. If no device is attached, leave the ledger as `pending` from Step 2 — do not fabricate a recorded result, and do not let this block the task's commit or the milestone.

- [ ] **Step 6: Full deterministic gate**

```bash
make verify
make verify-harness-jvm
git diff --check
```

- [ ] **Step 7: Commit**

Commit subject: `feat: add device-gated S25 process-kill export regression`. No independent review is triggered for this task.

---

### Task 6: Milestone-boundary review and merge

**Files:** none (review, protected CI, and merge only).

- [ ] **Step 1: Request the one unconditional full-range review**

Request one bounded full-range review over Tasks 0–5, assigned to a provider that implemented nothing in this milestone, per dual-provider routing (design spec §10): if Codex implemented Tasks 0–5, Claude reviews; if Claude implemented, Codex Sol reviews. Scope the review to: the two cleared debts (Task 0), `AndroidStoragePort` against the unchanged 12-method interface and the commonMain/androidMain boundary (Task 1), the conformance suite's fake/real symmetry (Task 2, informed by but not a repeat of the pre-implementation Sol challenge), the capability probe's evidence-shape honesty (Task 3), the composition path's byte-identity result and direct-boot/same-mount handling (Task 4), and the process-kill test's two-invocation design plus ledger enforcement (Task 5).

- [ ] **Step 2: Resolve findings**

Only Critical/Important findings block. One scoped fix plus one scoped re-review is the maximum. Record any Minor finding as debt in the milestone report; do not fix it inline in this task.

- [ ] **Step 3: Open the milestone PR, gate on protected CI**

Open a PR covering Tasks 0–5. Confirm the required `verify` check (which now runs `make verify`, `make verify-harness-jvm`, `make verify-kotlin-export`, and — per Task 5 — the ledger schema test asserting the process-kill evidence state is always reported) is green.

- [ ] **Step 4: Squash-merge and verify**

```bash
git fetch origin
git log --oneline -1 <squash-sha>   # confirm exactly one parent
git checkout main && git pull --ff-only
make verify
make verify-harness-jvm
```

- [ ] **Step 5: Preserve worktree and branch**

Preserve the feature branch and worktree per the operating constraints; do not delete either as part of this task.

## Slice 4a Exit Criteria

- Both Minor debts are cleared with regression tests proving locale-independence (JVM and Android-instrumented) and JVM-toolchain pinning (build-contract regression test).
- `AndroidStoragePort` implements all twelve `StoragePort` methods with no ordering logic; `Context` never enters `commonMain`.
- The shared `StoragePortConformanceSuite` runs identically against `FakeStorage` (CI-covered, every push) and `AndroidStoragePort` (device-covered), with no probe silently skipped on either backend.
- The storage capability probe records non-participant evidence (or an honest pending state) with an explicit crash-time-durability limitation statement, not an implied atomicity claim.
- The installable composition path produces a debug APK whose on-device synthetic export is byte-identical to the JVM-generated reference export, verified on-device with no new permission.
- Direct-boot and same-mount hazards fail closed with typed, observable capability states, never an unhandled exception.
- No storage permission is declared.
- The S25 process-kill test is landed and either recorded or honestly, CI-visibly pending — never a silent skip and never a milestone blocker.
- MediaPipe, CameraX, replay/live capture behaviour, and UI expansion beyond composition wiring remain entirely absent from every file touched in Tasks 0–5.
- One milestone-boundary review from a non-implementing provider is complete, with only Critical/Important findings resolved and Minor findings recorded as debt.
