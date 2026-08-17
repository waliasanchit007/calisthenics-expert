# Phase 0 Shared Deterministic Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`, and use `superpowers:test-driven-development` for every behavior change. Task 0 is first dogfood of orchestration v2 Slice 2 and must follow its manual lifecycle exactly.

**Goal:** Implement the deterministic Kotlin Multiplatform core that produces observation-contract 1.1.0 artifacts identically across supported targets, while keeping Python/JSON Schema authoritative and preserving crash-safe export and single-owner session semantics.

**Architecture:** `shared` owns contract DTOs, strict serialization, exact RGB24 preprocessing, session accounting, drain fencing, and export composition. Camera, inference, clocks, and durable storage remain ports. A JVM target exists only as a Linux-compatible test host; Android remains the first delivery target and iOS remains compile/link-valid but non-functional. Kotlin-generated artifacts are admitted only when the existing Python CLI accepts them.

**Authority:** This plan is subordinate to `docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md`, `docs/adr/ADR-0002-canonical-preprocessing.md`, `docs/adr/ADR-0003-contract-evolution-and-data-guard.md`, and `docs/adr/ADR-0004-kmp-android-first-diagnostic-harness.md`. It does not create a second design specification.

**Tech Stack:** Kotlin 2.4.10, kotlinx.serialization 1.10.0, coroutines 1.11.0, Gradle 9.3.1, JDK 21/JVM target 17, Python 3.12+ reference validator, GitHub Ubuntu CI.

## Global Constraints

- JSON Schema and the Python semantic validator are authoritative. Kotlin mirrors released 1.1.0 meaning; it does not reinterpret it.
- Unknown contract major versions are rejected. A newer minor within major 1 is accepted only when the known required fields and semantics remain valid.
- Contract output is deterministic: fixed UTF-8, LF newlines, one object per NDJSON line, a trailing newline, declared key order, and canonical finite IEEE-754 number tokens. NaN and infinities are rejected.
- The canonical number formatter follows the RFC 8785/ECMAScript number representation vectors while preserving the contracts' declared object-key order; it must not depend on platform `toString()` behavior.
- Every state mutation, counter update, callback classification, writer append, drain transition, and manifest snapshot is owned by one injected session dispatcher.
- Every submitted inference call has exactly one terminal classification: emitted record, backend failure, or drain-timeout failure. A late callback after the drain fence is a no-op.
- No partially written or validator-invalid session may appear under the valid exports root.
- No MediaPipe dependency, CameraX live path, replay adapter, product UI, functional iOS adapter, participant data, or physical-device evidence belongs in this slice.
- Each task is regression-first. Record the failing command and reason before production changes.
- Routine acceptance is deterministic tests, static checks, and diff/scope inspection. No routine independent model review is created. The adopted Task 5/6 decision record is settled: Sol is consulted only when an implementer proposes a deviation from it. One bounded full-range review occurs only at the completed slice boundary.

## Delivery and Review Protocol

- Tasks 1, 2, and 3 are parallel-eligible because their write scopes are disjoint. Tasks 4–7 remain sequential and consume the preceding contracts and evidence.
- Task 0 is implemented and reviewed through the real manual orchestration v2 Slice 2 path: activate generation, bind role, dispatch, acknowledge, start, implement/commit/push, submit, drain outbox, reviewer fetch/review, review submit, drain outbox.
- A **recovery cycle** is any manual intervention outside the documented happy path above: reaching into database state, manually repairing a stuck outbox row, re-activating a stale generation, or making any manual database edit. Re-running a documented step with corrected inputs is not a recovery cycle. Abort Task 0 dogfood after more than one recovery cycle and preserve exact database state, command, output, branch, HEAD, and next safe action; complete Task 0 outside Buzz so Calisthenics is never stranded behind the dogfood.
- The first-dogfood ledger records `claude-work` when Claude is consulted. Account identity is not added to the orchestration schema in this plan. If `claude-work` is actually exhausted and `claude-personal` is used, record that switch and its quota evidence in the ignored ledger.
- Each run-ledger routing record includes provider, model, account identity, tokens in, tokens out, and wall-clock duration in milliseconds; unavailable token telemetry is recorded explicitly rather than inferred.
- No provider account or credential identity may be encoded in `agent_id`, branch names, or filesystem paths.
- Tasks 1–7 start only after Task 0 is merged and protected CI proves the JVM test host on Ubuntu.

---

### Task 0: Add the JVM test host and Linux CI gate

**Files:**
- Modify: `apps/diagnostic-harness/shared/build.gradle.kts`
- Modify: `Makefile`
- Modify: `.github/workflows/verify.yml`
- Modify: `reference/tests/test_harness_build_contract.py`
- Modify: `docs/adr/ADR-0004-kmp-android-first-diagnostic-harness.md`
- Create locally only: `.superpowers/sdd/2026-08-17-phase-0-shared-deterministic-core/dogfood-task-0.md`

**Interfaces:**
- Produces a `jvm()` target in `shared` with JVM bytecode target 17. It is a host-test target, not a desktop product target.
- Produces `make verify-harness-jvm` for Ubuntu-compatible shared tests plus Android debug assembly.
- Produces `make verify-harness-ios` for local macOS iOS tests/framework links.
- Preserves `make verify-harness-foundation` as the aggregate local compatibility target.
- Adds a protected-CI job step using `actions/setup-java@v5`, Temurin JDK 21, and `make verify-harness-jvm`.

- [ ] **Step 1: Enter the manual Slice 2 lifecycle**

Create the run/task in the Buzz control-plane database, activate the selected implementer and reviewer process generations, bind their run roles, dispatch Task 0, acknowledge it, and start it. Record every command, state transition, generation ID, agent ID, branch, base SHA, and outbox result in the ignored dogfood report.

- [ ] **Step 2: Add behavior-level failing tests**

Extend `reference/tests/test_harness_build_contract.py` so it asserts:

```python
assert "jvm {" in shared_build
assert "jvmTarget = JvmTarget.JVM_17" in shared_build
assert "verify-harness-jvm:" in makefile
assert ":shared:jvmTest" in makefile
assert "verify-harness-ios:" in makefile
assert "actions/setup-java@v5" in workflow
assert 'java-version: "21"' in workflow
assert "make verify-harness-jvm" in workflow
```

Also run the public command before adding it:

```bash
make verify-harness-jvm
```

Expected RED: Make has no `verify-harness-jvm` target and the build-contract test reports the missing JVM/CI contract.

- [ ] **Step 3: Add the minimal host target and split gates**

Inside `kotlin {}` add:

```kotlin
jvm {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
```

Do not add a JVM application, desktop Compose target, or target-specific production source set.

Define:

```make
verify-harness-jvm:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest :androidApp:assembleDebug

verify-harness-ios:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:iosArm64Test :shared:iosSimulatorArm64Test :composeApp:iosArm64Test :composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64
	test -d apps/diagnostic-harness/composeApp/build/bin/iosArm64/debugFramework/DiagnosticHarness.framework
	test -d apps/diagnostic-harness/composeApp/build/bin/iosSimulatorArm64/debugFramework/DiagnosticHarness.framework

verify-harness-foundation: verify-harness-jvm verify-harness-ios
```

If Gradle does not expose a declared iOS test task, replace only that unavailable task with its exact generated target test task after confirming `./gradlew tasks --all`; do not weaken framework-link evidence.

Update CI with Temurin JDK 21 before the Kotlin command. Keep the existing `verify` job ID so branch protection remains satisfiable. Update ADR-0004 to state that `shared` has a JVM host-test target used by Linux CI and that it is not a delivery platform.

- [ ] **Step 4: Prove GREEN locally**

```bash
.venv/bin/python -m pytest reference/tests/test_harness_build_contract.py -q
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" make verify-harness-jvm
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" make verify-harness-ios
make verify
git diff --check
```

Expected: focused test passes; JVM/common tests and Android APK pass; both iOS targets compile/test and frameworks link on macOS; Python verification passes; diff check is clean.

- [ ] **Step 5: Commit, push, and submit through the control plane**

Commit with subject `ci: enforce shared Kotlin tests on Linux`, push the exact task branch, and call `agentctl task submit` with the pushed SHA and immutable report artifact. The task submission must atomically admit the commit/check evidence and enqueue the review handoff; a duplicate submit must return the original result without duplicate transitions or outbox rows.

- [ ] **Step 6: Drain and complete the real reviewer path**

Drain the implementer outbox. In the reviewer clone fetch the exact pushed SHA, detach or create the prescribed review ref, and inspect only Task 0 requirements, tests, and scope. Submit one review artifact through `agentctl review submit`, drain the resulting outbox, and verify the durable terminal state and transition history. Only Critical/Important findings block; one scoped fix and one scoped re-review are the maximum.

- [ ] **Step 7: Record dogfood evidence and merge**

The ignored dogfood report must contain:

- orchestration repo SHA and database path;
- run/task/attempt IDs and both process-generation IDs;
- all lifecycle commands and resulting states;
- task branch, base, commit, pushed remote SHA, and check evidence;
- implementer submission artifact/hash and review artifact/hash;
- outbox rows and delivery results for both handoffs;
- idempotency/stale-generation observations;
- recovery-cycle count and any friction to feed into Slice 3;
- final PR URL, required check result, squash SHA, and post-merge verification.

Open a ready PR, wait for the required `verify` check to be green, squash-merge, verify the squash has one parent, fast-forward local `main`, and rerun `make verify` plus `make verify-harness-jvm` on merged `main`. Preserve the feature branch and worktree.

---

### Task 1: Mirror observation-contract 1.1.0 models and strict serialization

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/contract/ObservationModels.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/contract/ObservationJson.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/contract/CanonicalNumber.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/contract/ObservationJsonTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/resources/contract/number-vectors.json`

**Interfaces:**
- Produces immutable DTOs matching `SessionManifest v1.1.x`, `RawPoseFrame v1.1.x`, landmarks, counts, failures, cadence, device, pipeline, camera, model-input, availability, media, capture-quality, and study context.
- Produces strict `decodeManifest`, `encodeManifest`, `decodeFrame`, and `encodeFrameLine` entry points.
- Produces one canonical finite-number formatter shared by JSON and future export hashing.

- [ ] Add tests first for exact required key sets, required nullable landmark fields, snake-case names, unknown-major rejection, compatible-newer-minor admission, unknown-field tolerance, missing-required-field rejection, NaN/infinity rejection, UUID/SHA/version wrappers, exact LF/trailing-newline behavior, and RFC 8785 number vectors.
- [ ] Observe RED with `:shared:jvmTest` because the DTOs/codecs do not exist.
- [ ] Implement only released observation fields. Use custom serializers where kotlinx serialization would omit a required explicit `null`. Preserve declared schema key order; do not sort arbitrary `study_context` keys except in the canonical writer's deterministic map policy.
- [ ] Run `:shared:jvmTest`, `make verify`, and `git diff --check`; inspect generated JSON against the schemas without adding interpretation fields.
- [ ] Commit `feat: mirror observation contract models in Kotlin`.

---

### Task 2: Generate a synthetic Kotlin export and admit it with Python

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/contract/SyntheticExportTest.kt`
- Create: `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/contract/GenerateSyntheticExport.kt`
- Modify: `apps/diagnostic-harness/shared/build.gradle.kts`
- Modify: `Makefile`
- Modify: `reference/tests/test_harness_build_contract.py`

**Interfaces:**
- Produces a Gradle task that writes a deterministic synthetic session only under `apps/diagnostic-harness/shared/build/conformance/`.
- Produces `make verify-kotlin-export`, which regenerates the build artifact and validates it through `python -m observation_contracts validate-session`.

- [ ] First add a failing build-contract test and a failing `make verify-kotlin-export` invocation.
- [ ] Generate a manifest and frames stream from Task 1 APIs; do not copy a committed Python fixture or write outside Gradle build output.
- [ ] Validate exact generated paths through the reference CLI. Run generation twice and assert byte-identical hashes.
- [ ] Run JVM tests, Python validation, `make verify`, and diff/scope inspection.
- [ ] Commit `test: validate Kotlin exports with Python authority`.

---

### Task 3: Implement exact canonical RGB24 scale/pad

Task 3 is parallel-eligible with Tasks 1–2 because it has a disjoint write scope. Tasks 4–7 are sequential.

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/preprocessing/Rgb24.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/preprocessing/ScalePad.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/preprocessing/ScalePadTest.kt`
- Copy as test resources only: committed preprocessing vector metadata and RGB24 input/expected bytes from `fixtures/synthetic/preprocessing/scale_pad_golden/`

**Interfaces:**
- Consumes upright, unmirrored, packed full-range sRGB RGB24 bytes plus source/target dimensions.
- Produces packed RGB24 bytes and deterministic scale/pad geometry matching ADR-0002 byte for byte.

- [ ] Add RED vector tests for byte length, SHA-256, dimensions, pad fill, scale geometry, invalid dimensions, and input-length mismatch.
- [ ] Implement exact-fraction bilinear scale/pad behavior exactly as the Python reference specifies; no bitmap, Android, or native platform types may enter common code.
- [ ] Verify every committed vector and a table of edge dimensions on JVM plus iOS compilation.
- [ ] Commit `feat: implement canonical RGB24 preprocessing`.

---

### Task 4: Implement the single-owner session ledger and counter identities

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/SessionTypes.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/SessionLedger.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/session/SessionLedgerTest.kt`

**Interfaces:**
- Consumes received-frame, skip, submission, callback-success, callback-failure, stop, and timeout events on one injected dispatcher.
- Produces immutable snapshots, emitted frame records, structured machine failures, and final manifest counts.

- [ ] Add RED table/property tests for these identities:
  - camera frames = submitted + skipped;
  - submitted = emitted + failures;
  - emitted = record count;
  - pose-detected = records with `pose_present`;
  - skip reasons sum to skipped;
  - failure entries have unique in-range submission indices and length equals failure count;
  - per-record skipped counts plus trailing skipped equal total skipped, including skips carried across failed calls;
  - frame indices strictly increase; session/version IDs remain constant; image dimensions match;
  - capture <= inference start <= inference end;
  - backend timestamp is `max(floor(capture_ns / 1_000_000), previous + 1)`.
- [ ] Implement the smallest event reducer/ledger that satisfies the properties. Never expose mutable counters.
- [ ] Commit `feat: add deterministic session accounting`.

---

### Task 5: Add drain fencing and exactly-once terminal classification

**Pre-implementation gate:** The drain/fence design is settled by the adopted Slice-3 decision record. Do not re-derive it with a cold Sol challenge. Request a scoped Sol challenge only if the implementer proposes deviating from one of the numbered acceptance criteria below.

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/SessionRunner.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/InferencePort.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/session/SessionRunnerTest.kt`

**Interfaces:**
- `SessionRunner` is the sole lifecycle authority.
- `InferencePort` receives submission index, canonical input, and backend timestamp and returns success/failure asynchronously.
- Stop closes admission, awaits in-flight calls, installs one timeout fence at the deadline, and waits for writer/event-queue quiescence before producing a final snapshot.

**Settled acceptance criteria (decision-record commitments):**

1. Fence state is monotonic: `Running -> Draining(deadline) -> Sealed -> Assembling -> Published | Failed`; every transition runs on the single dispatcher, the only fence check is at event admission before handler dispatch, post-Sealed events are dropped with a non-contract diagnostic count, and deadline expiry is a posted event.
2. Terminal states are single-assignment; reassignment throws and fails the session.
3. Counters increment at ACCEPT time. Before publish, close and fsync NDJSON, re-read the staged stream, and require line count to equal sealed `inference_records_emitted` and pose-present count to equal sealed `pose_detected_records`; any mismatch or earlier write/flush/fsync failure fails the session without publish.
4. Finalization returns the sealed counters and terminal map as an immutable sealed snapshot API.
5. The commit unit is the session directory, using per-attempt `<session_id>.<nonce>.staging` under the same `filesDir` mount as the exports root, with the fixed sequence: fsync stream, reconcile, write+fsync manifest, fsync staging directory, publish, fsync exports root.
6. The storage port exposes `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`; the platform implementation performs check-then-rename, documents the residual race, and relies on single-process dispatcher confinement within the threat model.
7. An empty exports-root directory is corruption evidence. The protocol never creates one there, the pre-check must surface it, and the scanner reports every directory without `manifest.json` rather than skipping it.
8. A second finalize joins the first result; publish executes only inside the single `Assembling -> Published` transition.
9. Every finalize attempt gets unique staging; any failure fsyncs an abort marker and permanently abandons that directory, which is never re-entered.
10. Deliberate abort writes an fsynced abort marker containing a sanitized reason and sealed counters; marker absence classifies crash residue.
11. Staging and exports root are both below `Context.filesDir`; external storage is never used.

**Mandatory RED/GREEN probes:**

1. Monotonic fence transitions, the admission-only fence check, post-Sealed diagnostic drops, and posted drain-deadline expiry.
2. Single-assignment terminal map; attempted reassignment throws and fails the session.
3. ACCEPT-time counters plus staged-stream line-count and pose-present reconciliation, with mutations of each sealed counter making the probe RED.
4. Immutable finalization result carrying the sealed counters and terminal map.
5. Fixed commit order and ordinal fault injection at every operation/boundary across `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`.
6. Exports-root corruption scanning reports an empty directory or any directory missing `manifest.json`.
7. A second finalize joins the first result and publish executes only once.
8. Destination collision: a pre-existing non-empty target produces `Failed` and remains untouched; a pre-existing empty target is refused by the existence pre-check; deleting that pre-check makes the empty-target test RED.
9. Unique staging per attempt and permanent abandonment after any failure.
10. Deliberate abort writes an fsynced sanitized marker with sealed counters, while marker absence identifies crash residue.
11. Staging and exports remain under `Context.filesDir`, and a valid zero-frame session publishes.

Every probe is required evidence; a skipped probe is not acceptance.

- [ ] Add RED deterministic virtual-time tests for the eleven mandatory probes above, including callbacks before/at/after deadline, simultaneous failure/timeout races, repeated stop, callback duplication, cancellation, zero in-flight calls, skipped frames during stopping, and the required storage mutations.
- [ ] Prove one terminal classification per submitted index and no counter/record/failure mutation after the fence.
- [ ] Use injected dispatcher/clock and structured concurrency; no sleeps or wall-clock tests.
- [ ] Commit `feat: fence session drain deterministically`.

---

### Task 6: Implement crash-safe export ordering with fault injection

**Pre-implementation gate:** Persistence order, same-filesystem assumptions, and residue policy are settled by the adopted Slice-3 decision record. Do not re-derive them with a cold Sol challenge. Request a scoped Sol challenge only if the implementer proposes deviating from the self-contained acceptance criteria below.

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/StoragePort.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/SessionExporter.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/FakeStorage.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/SessionExporterTest.kt`

**Interfaces:**
- `StoragePort` exposes the five explicit primitives `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`, plus staging creation, validation handoff, committed-root scan, and residue reporting.
- `SessionExporter` executes one fixed order: finish frame appends; fsync NDJSON; reconcile; write manifest; fsync manifest; fsync staging directory; check the destination, then plain rename within the same filesystem into the valid root; fsync destination parent. The logical `renameNoReplace` operation is check-then-plain-rename with a documented residual race, not an atomic no-replace primitive.

**Settled acceptance criteria (decision-record commitments):**

1. Fence state is monotonic and dispatcher-confined; admission performs the only fence check, post-Sealed events are dropped diagnostically, and deadline expiry is a posted event.
2. Terminal states are single-assignment; reassignment throws and fails the session.
3. Counters increment at ACCEPT time; post-close/fsync reconciliation requires NDJSON line count and pose-present count to equal the sealed counters, with any mismatch or earlier write/flush/fsync failure causing `Failed` and no publish.
4. Finalization returns an immutable sealed snapshot containing sealed counters and the terminal map.
5. The commit unit is the session directory, with unique `<session_id>.<nonce>.staging` under the same `filesDir` mount and the fixed fsync/reconcile/manifest/staging-dir/publish/exports-root order.
6. The port exposes `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`; it checks the destination and then performs plain rename, documenting the residual race.
7. An empty exports-root directory is corruption evidence, and the scanner reports every directory lacking `manifest.json`.
8. Second finalize joins the first result; publish occurs only in the single `Assembling -> Published` transition.
9. Each attempt has unique staging; failures fsync an abort marker and permanently abandon the directory.
10. Deliberate abort fsyncs a sanitized abort marker containing the sealed counters.
11. Staging and exports are both under `Context.filesDir`; external storage is forbidden.

**Mandatory RED/GREEN probes:**

1. Monotonic fence transitions, the admission-only fence check, post-Sealed diagnostic drops, and posted drain-deadline expiry.
2. Single-assignment terminal map; attempted reassignment throws and fails the session.
3. ACCEPT-time counters plus staged-stream line-count and pose-present reconciliation, with mutations of each sealed counter making the probe RED.
4. Immutable finalization result carrying the sealed counters and terminal map.
5. Fixed commit order and ordinal fault injection at every operation/boundary across `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`.
6. Exports-root corruption scanning reports an empty directory or any directory missing `manifest.json`.
7. A second finalize joins the first result and publish executes only once.
8. Destination collision: a pre-existing non-empty target produces `Failed` and remains untouched; a pre-existing empty target is refused by the existence pre-check; deleting that pre-check makes the empty-target test RED.
9. Unique staging per attempt and permanent abandonment after any failure.
10. Deliberate abort writes an fsynced sanitized marker with sealed counters, while marker absence identifies crash residue.
11. Staging and exports remain under `Context.filesDir`, and a valid zero-frame session publishes.

Every probe is required evidence; a skipped probe is not acceptance.

- [ ] Add RED ordinal fault-injection tests for the eleven mandatory probes and at every operation/boundary in the fixed commit sequence.
- [ ] Assert no failure before rename creates a valid export; rename success followed by parent-fsync failure reports uncertain durability without silently deleting the committed entry; stale staging is identifiable but never auto-published or silently removed.
- [ ] Assert validation failure cannot rename and error detail contains no frame bytes, landmarks, identifiers beyond pseudonym, or filesystem secrets.
- [ ] Commit `feat: publish sessions with crash-safe ordering`.

---

### Task 7: Compose and validate the complete deterministic core

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/DeterministicCore.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/session/DeterministicCoreTest.kt`
- Modify: `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/contract/GenerateSyntheticExport.kt`
- Modify: `Makefile`
- Modify: `.github/workflows/verify.yml`
- Modify: `reference/tests/test_harness_build_contract.py`

**Interfaces:**
- Composes preprocessing, session lifecycle, fake inference, strict serialization, and fake/JVM storage without importing Android, iOS, MediaPipe, CameraX, or Compose.
- Produces a deterministic complete fixture-replay export from synthetic RGB24 input under Gradle build output.

- [ ] Add an end-to-end RED scenario covering selected frames, backpressure skips, pose/no-pose results, one backend failure, one drain-timeout failure, one late callback, final manifest reconciliation, and atomic export.
- [ ] Run the same event script repeatedly and assert byte-identical artifacts and stable hashes.
- [ ] Extend `make verify-kotlin-export` and protected Ubuntu CI to run all JVM tests, generate the export, and admit it with the Python CLI.
- [ ] Run the full deterministic gate:

```bash
make verify
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" make verify-harness-jvm
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" make verify-harness-ios
make verify-kotlin-export
git diff --check
```

- [ ] Request one bounded full-range review over Tasks 1–7 from a provider that did not implement in this slice. If both providers implemented, assign Claude Opus with an instance that performed no implementation in the slice. Only Critical/Important findings block; allow one scoped fix/re-review. Record Minor debt.
- [ ] Open the slice PR, wait for protected CI, squash-merge, verify one parent, fast-forward `main`, and rerun the full deterministic gate.
- [ ] Commit subject before PR: `feat: complete shared deterministic observation core`.

## Slice Exit Criteria

- Ubuntu protected CI runs `:shared:jvmTest`, Android assembly, Kotlin export generation, and Python contract admission.
- Local macOS evidence proves shared and Compose iOS targets compile/test/link without claiming functional iOS support.
- Kotlin output matches released schema key sets, strict nullable behavior, semantic-version policy, canonical numeric tokens, and deterministic NDJSON bytes.
- RGB24 preprocessing matches committed Python vectors byte for byte.
- Property tests prove all counter identities and exactly one terminal classification per submitted call.
- Drain fencing makes late callbacks inert and manifest assembly waits for quiescence.
- Fault injection proves no partial or invalid session appears as a valid export.
- Generated synthetic exports are byte-stable and pass the Python reference validator.
- MediaPipe, replay/live capture, UI expansion, functional iOS, physical-device evidence, and interpretation remain absent.
