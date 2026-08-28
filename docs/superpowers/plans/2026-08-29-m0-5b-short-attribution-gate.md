# M0.5B Short 10/15/30 Attribution Gate — Implementation Plan

> **For agentic workers:** use `superpowers:test-driven-development` for every behavior change. Do not collect physical evidence until the Task 8 controller is complete and the exact final APK has been built.

**Goal:** Close Task 6 Step 5 with a non-vacuous, device-gated proof that one authorized S25, requesting 30 camera FPS throughout, admits strictly increasing proportions of delivered callbacks at configured inference cadences 10, 15, and 30 FPS.

**Architecture:** The Android instrumentation test owns three bounded, sequential, non-participant live sessions and emits only typed session references and device/build identity. Each session's full stage stream remains authoritative in its durable `telemetry.ndjson`; the host checker transfers exact bytes, validates the session export, derives callback accounting and cadence admission from telemetry, and passes the reconstructed runs to the existing common `validateAttributionEvidence`. Neither instrumentation output nor a caller-provided Boolean can authorize the gate. The committed ledger is human-recorded and content-addressed; commands never edit it.

**Final-binary rule:** Land the software gate before physical execution, but leave its ledger `pending`. Physical execution occurs only after Tasks 3–8 software are complete on one exact app SHA. After that build: run short attribution, derive/freeze Task 7 envelope/order, then run sustained 10/15/30 without rebuilding or clearing app data.

**Tech stack:** Kotlin Multiplatform/Compose, Android instrumentation, CameraX, MediaPipe Tasks Vision, durable NDJSON export, Python 3.12 reference tooling, `adb exec-out`/`run-as`, Samsung Galaxy S25 Ultra.

## Software status

- [x] Task 1: typed host/device attribution evidence contract
- [x] Task 2: exact-byte host checker and transfer/recovery path
- [x] Task 3: bounded Android 10/15/30 producer with fresh live composition and durable publication
- [x] Task 4: exact-candidate Wi-Fi ADB command and pending evidence ledger
- [ ] Task 5: physical S25 execution and manual ledger recording

---

## Task 1: Freeze the typed host/device evidence contract

**Files:**
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/AttributionEvidence.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/AttributionEvidenceTest.kt`

### Step 1: Write failing contract tests

Add tests requiring each cadence run to bind:

- exact session ID and committed export-relative path;
- app/probe Git SHA, model SHA-256, runtime/backend/delegate identity;
- requested camera FPS `30` and configured inference FPS;
- delivered callback accounting derived from stage events;
- strict cadence order `[10, 15, 30]`;
- one shared authorized physical-device identity across all three runs;
- one shared app/model/runtime identity across all three runs;
- a sanitized renderer that includes the three content digests and computed verdict, never raw landmark data.

Mutations that must fail: reorder cadences, change requested camera FPS, change one app/model/runtime identity, remove one terminal stage, duplicate/regress a sequence, make two admission ratios equal, and supply an emulator identity.

### Step 2: Observe RED

Run:

```bash
cd apps/diagnostic-harness
./gradlew --no-daemon :shared:jvmTest --tests '*AttributionEvidenceTest'
```

Confirm failure is due to the missing typed fields/cross-run bindings, not fixture setup.

### Step 3: Implement the minimum common contract

Extend the existing types and validator. Keep verdict derivation entirely inside `validateAttributionEvidence`; do not add a caller-declared pass field. Compare rates with integer cross-multiplication and reject overflow-prone or negative counts before comparing.

### Step 4: Verify GREEN and mutation sensitivity

Re-run the focused suite. Apply each named mutation one at a time, observe RED, then restore production code.

---

## Task 2: Add the exact-byte host checker

**Files:**
- Create: `tools/check_m05b_attribution.py`
- Create: `reference/tests/test_check_m05b_attribution.py`
- Modify if required: `reference/tests/test_device_evidence_ledger.py`

### Step 1: Write failing Python tests

Use literal instrumentation transcripts and temporary export trees. Require the checker to:

- accept exactly one typed status record from the exact attribution test suite;
- reject `OK (0 tests)`, multiple records, malformed JSON, wrong suite, and non-zero instrumentation status;
- transfer/read `manifest.json`, `frames.ndjson`, and `telemetry.ndjson` for exactly three distinct session IDs;
- hash the exact transferred bytes before parsing and retain those SHA-256 values in evidence;
- run the Python session validator for every export;
- parse only complete LF-terminated telemetry records;
- derive stage events and `DeliveredCallbackAccounting` from those records rather than instrumentation summaries;
- require contiguous per-session telemetry sequence, monotonic session time, terminal accounting closure, and exactly one terminal disposition per delivered callback;
- bind all three manifests to the same device/app/model/runtime identity and requested camera FPS 30;
- derive and validate the strict 10/15/30 admission-rate increase;
- output one sanitized JSON record and return 0 only for a complete non-blocking observation, 3 for a well-formed blocking observation, and 1/2 for invalid evidence/usage.

Add transfer mutations: truncate bytes, append bytes, substitute another session, change a manifest after hashing, and simulate a transport error. A transport error after the device has durably committed a session is `awaiting_recovery`, not authorization to rerun.

### Step 2: Observe RED

```bash
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution.py
```

### Step 3: Implement the checker

Reuse existing authorization and session-validation semantics; do not copy a divergent S25 rule. Transfer binary data to a temporary host file, fsync it, verify byte count/digest, then atomically rename. Never decode the byte stream in the shell.

### Step 4: Verify GREEN and mutations

Run the focused Python suite and each transfer/identity/accounting mutation.

---

## Task 3: Add the bounded Android producer

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionDeviceGateTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt` only if a narrow session-reference seam is missing
- Create/modify focused Android unit tests under `androidApp/src/test/.../composition/`

### Step 1: Write failing lifecycle tests

Require one instrumentation invocation to:

- create a fresh `LiveCameraComposition` per cadence;
- run cadences exactly 10, 15, then 30 with camera request fixed at 30;
- use `PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION`;
- stop and publish each bounded session before starting the next;
- retain app data between runs and never invoke `pm clear`;
- emit exactly one structured status record after all three durable sessions exist;
- include session IDs and immutable app/model/runtime/device identity, but no raw events or caller-declared verdict;
- fail closed if any run is unavailable, unpublished, unclosed, or lacks a real session ID.

Mutations that must fail: reuse a composition, reorder cadence, use `SUSTAINED`, emit before publication, skip the middle run, or clear state between runs.

### Step 2: Observe RED

```bash
cd apps/diagnostic-harness
./gradlew --no-daemon :androidApp:testDebugUnitTest --tests '*M05BAttribution*'
```

### Step 3: Implement the minimum producer

Use the existing live composition and durable exporter. Do not retain the full event stream in memory: the durable telemetry artifact is authoritative. Keep each run short and bounded by a fixed monotonic timeout. The test represents only an empty-scene or displayed-synthetic non-participant observation.

### Step 4: Verify host/JVM compilation

```bash
cd apps/diagnostic-harness
./gradlew --no-daemon :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:compileDebugAndroidTestKotlin
```

Physical execution remains pending.

---

## Task 4: Add the device command and immutable evidence ledger

**Files:**
- Modify: `Makefile`
- Create: `docs/evidence/m05b-attribution/s25-short-attribution-ledger.json`
- Create when physically recorded: `docs/evidence/m05b-attribution/s25-short-attribution-record.json`
- Modify: `reference/tests/test_device_evidence_ledger.py`
- Modify: repository/build contract tests that enumerate physical targets

### Step 1: Write failing Make/ledger tests

Require `verify-harness-s25-m05b-attribution` to:

- count exactly one `adb` target whose state is exactly `device`;
- reject emulator serials and resolve authorization through the shared S25 definition's matching semantics;
- require Wi-Fi ADB for the final physical run;
- require an already-installed, separately prepared exact candidate and verify its app/Git identity;
- never assemble, reinstall, or otherwise replace that candidate inside the attribution command;
- never call `pm clear` during or between the three runs;
- run only `M05BAttributionDeviceGateTest` and require structured status plus a non-zero-safe exit code;
- invoke the host checker with the exact serial/package/transcript/status;
- print evidence but never modify the ledger.

The ledger test must accept only:

- `pending` with an explicit missing precondition; or
- `recorded` with a relative evidence path, SHA-256 of exact bytes, exact app/model/runtime/device identities, and a recomputed non-blocking verdict.

Mutation tests must reject an emulator, multiple devices, USB transport, `pm clear`, a transcript-only verdict, missing evidence, wrong evidence digest, and a blocking evidence record labelled admitted.

Add one mechanical build-contract test that fails if this target gains Gradle assemble/package tasks,
`adb install`, or `pm clear`. Candidate assembly and installation belong to one separately audited
preparation command that runs once before the entire attribution -> envelope freeze -> sustained
sequence.

### Step 2: Implement the command and pending ledger

Keep the ledger pending in source until the final binary sequence is ready and a physical S25 is attached. No emulator substitutes.

### Step 3: Run software verification

```bash
make verify
cd apps/diagnostic-harness
./gradlew --no-daemon :shared:jvmTest :shared:compileKotlinIosArm64 :androidApp:testDebugUnitTest :androidApp:compileDebugAndroidTestKotlin
cd ../..
.venv/bin/python -m observation_contracts guard-repository .
git diff --check
```

Inspect the diff for scope, secret/media leakage, and any duplicated authorization rule.

---

## Task 5: Physical execution after the final APK is frozen

**Preconditions:** Tasks 3–8 software complete; exact candidate SHA clean/pushed; model asset fetched and verified; the separately audited candidate-preparation command has built and installed that exact candidate once; one authorized S25 is connected over Wi-Fi ADB; no participant is present; app data has not been cleared after candidate installation.

### Step 1: Run short attribution exactly once

```bash
make verify-harness-s25-m05b-attribution
```

If transport fails after any durable session is committed, recover/pull that session; do not rerun it. A rerun is allowed only for an attempt proven aborted/failed before durable completion, and the failed attempt remains visible.

### Step 2: Record exact evidence manually

Copy the sanitized checker output into the evidence record, compute its SHA-256, update the ledger, and run its validator. The command itself never writes tracked evidence.

### Step 3: Freeze Task 7 immediately

Without rebuilding or clearing app data, derive and record the Task 7 envelope/order from the same binary and device state.

### Step 4: Proceed directly to Task 8

Run the sustained 10/15/30 sequence in its pre-registered order. A committed blocking observation is never overwritten by a retry.

---

## Acceptance

- Software gate is test-first, mutation-sensitive, and green locally.
- Physical ledger is honestly pending until the exact-final-binary run occurs.
- Full stage evidence comes from exact durable bytes, not instrumentation summaries.
- Camera request remains 30 FPS and admitted/delivered ratios strictly increase at 10, 15, 30.
- One app/model/runtime/device identity binds all three sessions.
- No participant data, no raw landmarks in the evidence record, no `pm clear`, and no rebuild between attribution, envelope freeze, and sustained runs.
- No merge, tag, participant release, iOS equivalence, or reliability claim is authorized by this gate.
