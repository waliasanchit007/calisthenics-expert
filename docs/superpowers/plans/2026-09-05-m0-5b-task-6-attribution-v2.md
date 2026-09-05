# M0.5B Task 6 Attribution Protocol v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ambiguous blocked Task 6 experiment with an additive, preregistered protocol
that independently identifies camera-source, backend, selector, and end-to-end cadence limitations
on one authorized Samsung Galaxy S25 Ultra.

**Architecture:** New v2 tooling contracts and focused Kotlin/Python modules leave v1 evidence and
validation immutable. Pure common code derives source capacity, backend capacity, selector
conformance, callback conservation, and the final verdict; a narrow optional observer and admission
factory expose the existing live pipeline without changing its default behavior. Android produces
bounded app-private artifacts and references, while an independent host checker reopens exact bytes,
recomputes every predicate, and alone admits the pending/recorded/blocked ledger.

**Tech Stack:** Kotlin Multiplatform, Android instrumentation/JUnit, CameraX, MediaPipe Tasks Vision
1.0.0, kotlinx.serialization, Python 3.14, JSON Schema Draft 2020-12, pytest, Ruff, strict mypy,
GNU Make, and binary-safe `adb exec-out run-as` transfer.

**Spec:** `docs/superpowers/specs/2026-09-05-m0-5b-task-6-attribution-v2-design.md`

## Global Constraints

- Preserve v1 evidence and its blocked ledger byte-for-byte. Never rerun, overwrite, migrate, or
  reinterpret `docs/evidence/m05b-attribution/`.
- Use protocol version `2.0.0`, evidence version `2.0.0`, target cadences `[10, 15, 30]`, source
  warm-up `30`, measured source callbacks `300`, backend warm-up `20` completed inferences plus
  `2_000_000_000 ns`, measured backend completions `100`, and per-arm cap `60_000_000_000 ns`.
- Reuse the released cooldown schedule exactly: sample every `1_000_000_000 ns` for at most
  `600_000_000_000 ns`, and require equality with the first arm's Android thermal-status value.
- Use replicate orders `[10, 15, 30]` and `[30, 15, 10]`; never pool away an order disagreement.
- The production `LiveFrameAdmission` behavior remains the default and remains ordered: stopping,
  single-flight slot, cadence interval, submission.
- Source capacity uses delivered callbacks and closed-interval expectations only. Selector behavior
  cannot enter `source_supports`.
- Backend capacity uses 100 observed service durations against 101 boundaries spaced by
  `floor(1_000_000_000 / cadence_fps)`; no percentile or guessed percentage substitutes for the
  exact sequence.
- The selector-isolation arm must use the production `LiveFrameAdmission` object and Arm 0A's exact
  physical timestamps. Its evidence is non-authoritative for Task 7.
- Every delivered callback receives exactly one terminal class, and all submitted inferences reach
  exactly one completion/failure terminal before finalization.
- No participant or participant-derived data is captured. Every physical session uses the reserved
  synthetic namespace and an explicit no-person operator attestation.
- Physical execution requires exactly one authorized unplugged S25 over Wi-Fi ADB, one frozen exact
  candidate, stable identity/configuration, and thermal-status-class recovery before each arm.
- Device output is untrusted. Instrumentation emits references; the host reopens, hashes, validates,
  and recomputes all verdict fields.
- A valid blocker is final for its protocol/run identity. Only an attempt proven aborted or failed
  before durable completion may be rerun with a new identity.
- Every gate conjunct ships with a named mutation that fails its intended behavioral oracle rather
  than an unrelated schema, digest, or build check.
- Success is an exit code, never a grep of output.
- Run `make fetch-model-asset` and create checkout-local
  `apps/diagnostic-harness/local.properties` before an Android delivery build.
- Local commits are permitted. Push, PR, merge, tag, branch deletion, worktree removal, participant
  capture, and paid credits require authority outside this plan.

---

### Task 1: Release the v2 protocol and tooling contracts

**Files:**
- Create: `contracts/tooling/m05b_attribution_protocol.v2.schema.json`
- Create: `contracts/tooling/m05b_attribution_observation.v2.schema.json`
- Create: `docs/protocols/m05b-attribution-v2.protocol.json`
- Create: `docs/evidence/m05b-attribution-v2/s25-attribution-v2-ledger.json`
- Modify: `reference/src/observation_contracts/schema_loader.py`
- Create: `reference/src/observation_contracts/m05b_attribution_v2_codes.py`
- Modify: `reference/src/observation_contracts/validator_codes.py`
- Create: `reference/tests/test_m05b_attribution_v2_contract.py`
- Modify: `reference/tests/test_validator_code_emission.py`

**Interfaces:**
- Consumes: `_load_tooling_schema`, Draft 2020-12 validation, stable-code release process.
- Produces: `load_m05b_attribution_protocol_v2_schema()` and
  `load_m05b_attribution_observation_v2_schema()`; canonical protocol bytes; the finalized v2 code
  vocabulary classified as draft until Task 5 supplies behavioral oracles; a pending v2 ledger.

- [x] **Step 1: Write RED schema-loader and canonical-protocol tests**

  Add tests that import both new loaders, run `Draft202012Validator.check_schema`, validate the
  canonical protocol, and pin these exact values:

  ```python
  assert protocol["protocol_version"] == "2.0.0"
  assert protocol["target_cadences_fps"] == [10, 15, 30]
  assert protocol["source"] == {"warmup_callbacks": 30, "measured_callbacks": 300}
  assert protocol["backend"]["warmup_completed"] == 20
  assert protocol["backend"]["warmup_elapsed_ns"] == 2_000_000_000
  assert protocol["backend"]["measured_completions"] == 100
  assert protocol["arm_cap_ns"] == 60_000_000_000
  assert protocol["cooldown"] == {
      "sampling_interval_ns": 1_000_000_000,
      "maximum_duration_ns": 600_000_000_000,
  }
  assert protocol["replicate_orders"] == [[10, 15, 30], [30, 15, 10]]
  ```

  Require `terminal_classes` to equal the closed set from the design, including
  `admission_state_divergence`, and require the canonical compact UTF-8 bytes to end with LF.

- [x] **Step 2: Run the focused test and capture RED**

  Run:

  ```bash
  .venv/bin/python -m pytest -q reference/tests/test_m05b_attribution_v2_contract.py
  ```

  Expected: import failure for the absent loader functions and absent schema/protocol files.

- [x] **Step 3: Add the two closed schemas and loader functions**

  Add exact path/load functions following the existing tooling pattern:

  ```python
  def m05b_attribution_protocol_v2_schema_path() -> Path:
      return repository_root() / "contracts" / "tooling" / "m05b_attribution_protocol.v2.schema.json"

  @cache
  def load_m05b_attribution_protocol_v2_schema() -> Mapping[str, object]:
      return _load_tooling_schema(m05b_attribution_protocol_v2_schema_path())
  ```

  Add the equivalent observation-v2 pair. Both schemas use `additionalProperties: false`, closed
  enums, non-negative integer nanoseconds, lowercase SHA-256 patterns, reserved synthetic UUIDs,
  relative paths, and `const` for protocol/evidence versions and non-participant/sanitized flags.

- [x] **Step 4: Commit the canonical protocol and pending ledger**

  Canonicalize with `json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n"`. Construct
  the pending ledger from the computed protocol bytes:

  ```python
  protocol_bytes = protocol_path.read_bytes()
  ledger = {
      "evidence_file": None,
      "evidence_sha256": None,
      "ledger_schema_version": "2.0.0",
      "missing_precondition": "attribution_v2_physical_evidence_not_recorded",
      "protocol_file": "docs/protocols/m05b-attribution-v2.protocol.json",
      "protocol_sha256": hashlib.sha256(protocol_bytes).hexdigest(),
      "record_kind": "s25_attribution_v2_ledger",
      "status": "pending",
  }
  ```

  Serialize the ledger canonically with LF termination and commit its actual digest value.

- [x] **Step 5: Classify the finalized v2 blocker-code vocabulary as draft**

  Add the complete set below to `DRAFT_EMITTED_CODES`. It remains absent from the released registry
  until Task 5 adds every behavioral oracle and promotes the complete set atomically:

  ```text
  ATTRIBUTION.ADMISSION_STATE_DIVERGENCE
  ATTRIBUTION.BACKEND_LIMITED
  ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT
  ATTRIBUTION.CALLBACK_CONSERVATION
  ATTRIBUTION.CANDIDATE_IDENTITY
  ATTRIBUTION.END_TO_END_NOT_DISTINCT
  ATTRIBUTION.ENVIRONMENT_MISMATCH
  ATTRIBUTION.INFERENCE_FAILURE
  ATTRIBUTION.PROTOCOL_DIGEST
  ATTRIBUTION.REPLICATE_DISAGREEMENT
  ATTRIBUTION.SELECTOR_DIVERGENCE
  ATTRIBUTION.SELECTOR_ONLY
  ATTRIBUTION.SOURCE_CONFIGURATION
  ATTRIBUTION.SOURCE_LIMITED
  ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT
  ATTRIBUTION.SOURCE_TIMESTAMP_INVALID
  ATTRIBUTION.TERMINAL_CLOSURE
  ATTRIBUTION.THERMAL_STATE_MISMATCH
  ```

  Extend the source-emission classification test so every new literal must appear in this draft set.
  Do not add a subset to the registry.

- [x] **Step 6: Mutation-check the closed protocol**

  Change `measured_completions` from 100 to 99 and require
  `test_canonical_protocol_pins_all_preregistered_values` to fail. Restore byte-exactly. Add an
  unknown terminal class and require schema validation to fail.

- [x] **Step 7: Run focused GREEN checks**

  Run:

  ```bash
  .venv/bin/python -m pytest -q \
    reference/tests/test_m05b_attribution_v2_contract.py \
    reference/tests/test_validator_code_emission.py
  .venv/bin/python -m ruff check reference
  .venv/bin/python -m mypy --config-file reference/pyproject.toml reference/src
  ```

- [x] **Step 8: Commit the contract increment**

  ```bash
  git add contracts reference docs/protocols docs/evidence/m05b-attribution-v2
  git commit -m "feat: define M0.5B attribution v2 contracts"
  ```

---

### Task 2: Implement pure source, backend, selector, and verdict derivation

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2Protocol.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2Test.kt`
- Create: `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2ProtocolParityTest.kt`
- Create: `reference/src/observation_contracts/m05b_attribution_v2.py`
- Create: `reference/tests/test_m05b_attribution_v2.py`

**Interfaces:**
- Consumes: `LiveFrameAdmission`, exact protocol constants, stable v2 codes.
- Produces: Kotlin and Python implementations of `source_supports`, `backend_supports`, selector
  conformance, callback conservation, replicate comparison, the derived final verdict, and a JVM
  proof that executable Kotlin constants equal the canonical protocol JSON.

- [x] **Step 1: Write RED Kotlin tests for exact integer derivations**

  Pin these public Kotlin types and calls:

  ```kotlin
  enum class AttributionV2TerminalClass {
      ADMITTED_COMPLETED, ADMITTED_INFERENCE_FAILED, CADENCE_ONLY,
      BACKPRESSURE_ONLY, CADENCE_AND_BACKPRESSURE, PREPROCESSING_FAILED,
      SESSION_STOPPING, ADMISSION_STATE_DIVERGENCE,
  }

  data class AttributionV2Callback(
      val callbackId: Long,
      val captureTimestampNs: Long,
      val productionClassification: LiveFrameClassification,
      val cadenceDue: Boolean,
      val slotAvailable: Boolean,
      val terminalClass: AttributionV2TerminalClass,
  )

  fun sourceSupports(timestampsNs: List<Long>, cadenceFps: Int): Boolean
  fun backendSupports(serviceDurationsNs: List<Long>, cadenceFps: Int): Boolean
  fun productionSelectorTimestamps(timestampsNs: List<Long>, cadenceFps: Int): List<Long>
  fun validateAttributionV2(evidence: M05BAttributionV2Evidence?): M05BAttributionV2Validation
  ```

  `M05BAttributionV2Evidence` is the closed aggregate consumed later by the renderer: protocol,
  device/candidate identity, source arm, backend arm, optional selector arm, zero or two end-to-end
  replicates, environmental boundaries, and device-proposed blocking codes. The validation result
  contains only derived status and sorted blockers.

  Define one executable constant object used by every Android arm:

  ```kotlin
  object M05BAttributionV2Protocol {
      const val VERSION = "2.0.0"
      val TARGET_CADENCES_FPS = listOf(10, 15, 30)
      const val SOURCE_WARMUP_CALLBACKS = 30
      const val SOURCE_MEASURED_CALLBACKS = 300
      const val BACKEND_WARMUP_COMPLETIONS = 20
      const val BACKEND_WARMUP_ELAPSED_NS = 2_000_000_000L
      const val BACKEND_MEASURED_COMPLETIONS = 100
      const val ARM_CAP_NS = 60_000_000_000L
      const val COOLDOWN_SAMPLE_NS = 1_000_000_000L
      const val COOLDOWN_MAX_NS = 600_000_000_000L
      val REPLICATE_ORDERS = listOf(listOf(10, 15, 30), listOf(30, 15, 10))
  }
  ```

  Cover exact 10/15/30 boundaries, duplicate/regressing source timestamps, 299 versus 300 measured
  callbacks, 99 versus 100 backend durations, a duration equal to the interval, a duration one
  nanosecond above it, selector threshold collapse, every terminal class, duplicate/missing IDs,
  non-zero inference failures, replicate disagreement, and selector-only evidence.

- [x] **Step 2: Run Kotlin RED**

  Run:

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :shared:jvmTest --tests '*M05BAttributionV2Test*'
  ```

  Expected: compilation failure because the v2 types and functions do not exist.

- [x] **Step 3: Implement the minimum pure Kotlin model**

  Use integer-only formulas:

  ```kotlin
  fun sourceSupports(timestampsNs: List<Long>, cadenceFps: Int): Boolean {
      if (timestampsNs.size != 300 || timestampsNs.zipWithNext().any { (a, b) -> b <= a }) return false
      val span = timestampsNs.last() - timestampsNs.first()
      return timestampsNs.size.toLong() >= closedIntervalOpportunities(span, cadenceFps)
  }

  fun closedIntervalOpportunities(spanNs: Long, fps: Int): Long =
      (spanNs / 1_000_000_000L) * fps +
          ((spanNs % 1_000_000_000L) * fps) / 1_000_000_000L + 1L

  fun backendSupports(serviceDurationsNs: List<Long>, cadenceFps: Int): Boolean {
      if (serviceDurationsNs.size != 100 || serviceDurationsNs.any { it < 0L }) return false
      val interval = 1_000_000_000L / cadenceFps
      return serviceDurationsNs.all { it <= interval }
  }
  ```

  `productionSelectorTimestamps` must instantiate `LiveFrameAdmission`, call `classify` for every
  timestamp, and immediately call `completeSubmission` after every `SUBMIT`. It must not duplicate
  the interval arithmetic. Validation derives sorted blockers and never accepts a caller-provided
  success Boolean.

- [x] **Step 4: Write and run Python RED parity tests**

  Create table-driven fixtures shared as literal JSON-compatible values. Require Python to produce
  the same source/backend/selector/verdict outputs as Kotlin's pinned cases. Run:

  ```bash
  .venv/bin/python -m pytest -q reference/tests/test_m05b_attribution_v2.py
  ```

  Expected: import failure for `observation_contracts.m05b_attribution_v2`.

- [x] **Step 5: Add the canonical-protocol JVM parity test**

  Follow `StorageLocationRegistryProjectionTest.repositoryRoot()` to locate
  `docs/protocols/m05b-attribution-v2.protocol.json`. Parse it strictly and assert every field above,
  including terminal classes and replicate orders, equals `M05BAttributionV2Protocol`. Mutating any
  executable constant without updating the canonical protocol must fail this test.

- [x] **Step 6: Implement the Python reference derivation**

  Expose these exact functions:

  ```python
  def closed_interval_opportunities(span_ns: int, fps: int) -> int:
      return (span_ns // 1_000_000_000) * fps + (span_ns % 1_000_000_000) * fps // 1_000_000_000 + 1

  def source_supports(timestamps_ns: Sequence[int], cadence_fps: int) -> bool:
      return source_capacity(timestamps_ns, cadence_fps).supported

  def backend_supports(service_durations_ns: Sequence[int], cadence_fps: int) -> bool:
      return backend_capacity(service_durations_ns, cadence_fps).supported

  def selector_timestamps(timestamps_ns: Sequence[int], cadence_fps: int) -> list[int]:
      return derive_selector_trace(timestamps_ns, cadence_fps)

  def validate_attribution_v2(document: Mapping[str, object], path: str) -> list[ValidationIssue]:
      return AttributionV2Validator(document, path).issues()
  ```

  The Python selector independently applies the documented integer interval rule; it does not call
  or translate Kotlin output. This gives the host an independent oracle for Arm 1.

- [x] **Step 7: Prove focused mutations**

  Mutation A changes source expectation from `>=` to `>` and must fail the exact-boundary source
  test. Mutation B changes backend acceptance from `<= interval` to `< interval` and must fail the
  equality test. Mutation C changes one selector interval to the neighboring cadence and must fail
  the corresponding sequence test. Mutation D drops `ADMISSION_STATE_DIVERGENCE` from conservation
  and must fail the exact callback-closure test. Restore byte-exactly after every mutation.

- [x] **Step 8: Run parity GREEN checks and commit**

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :shared:jvmTest \
    --tests '*M05BAttributionV2Test*' \
    --tests '*M05BAttributionV2ProtocolParityTest*'
  cd ../..
  .venv/bin/python -m pytest -q reference/tests/test_m05b_attribution_v2.py
  .venv/bin/python -m ruff check reference/src/observation_contracts/m05b_attribution_v2.py \
    reference/tests/test_m05b_attribution_v2.py
  .venv/bin/python -m mypy --config-file reference/pyproject.toml reference/src
  git add apps/diagnostic-harness/shared reference
  git commit -m "feat: derive M0.5B attribution v2 verdicts"
  ```

---

### Task 3: Add the non-invasive live admission observation seam

**Files:**
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveFrameAdmission.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/live/LiveFrameAdmissionTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/camera/OwnedYuvFrame.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/camera/CameraXFrameSource.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/camera/OwnedYuvFrameTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/camera/CameraXFrameSourceOwnershipTest.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/AttributionV2Observation.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/LiveCameraCompositionTest.kt`

**Interfaces:**
- Consumes: existing `LiveFrameAdmission` state machine, callback IDs, stage recorder, inference
  start/end timestamps.
- Produces: `LiveFrameAdmissionPort`, read-only `LiveAdmissionPredicates`, optional
  `AttributionV2CallbackObserver`, captured Camera2 AE target FPS metadata, and an injectable
  admission factory whose default is unchanged.

- [x] **Step 1: Write RED admission-port tests**

  Pin these interfaces:

  ```kotlin
  data class LiveAdmissionPredicates(val cadenceDue: Boolean, val slotAvailable: Boolean)

  interface LiveFrameAdmissionPort {
      fun predicates(captureTimestampNs: Long): LiveAdmissionPredicates
      fun reserve(captureTimestampNs: Long): LiveFrameClassification
      fun commitCandidate(preprocessingSucceeded: Boolean): LiveFrameClassification
      fun completeSubmission()
      fun stop()
  }
  ```

  Require `predicates` to be read-only: calling it repeatedly cannot change the next `reserve`
  result. Require the existing class to preserve backpressure-before-cadence ordering.

- [x] **Step 2: Run admission RED**

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :shared:jvmTest --tests '*LiveFrameAdmissionTest*'
  ```

  Expected: compilation failure for the absent interface and predicate snapshot.

- [x] **Step 3: Implement the port without changing default behavior**

  Make `LiveFrameAdmission` implement the interface. `predicates` computes:

  ```kotlin
  LiveAdmissionPredicates(
      cadenceDue = lastSelectedTimestampNs == null ||
          captureTimestampNs - lastSelectedTimestampNs!! >= minimumIntervalNs,
      slotAvailable = !stopping && !submissionInFlight && candidateTimestampNs == null,
  )
  ```

  Preserve every existing `reserve`, commit, completion, and stop branch byte-for-byte except the
  interface annotations needed to compile.

- [x] **Step 4: Write RED composition-observer tests**

  Pin this optional interface:

  ```kotlin
  interface AttributionV2CallbackObserver {
      fun onAdmission(
          callbackId: Long,
          captureTimestampNs: Long,
          observedSessionNs: Long,
          productionClassification: LiveFrameClassification,
          productionPredicates: LiveAdmissionPredicates,
      )
      fun onTerminal(callbackId: Long, terminal: CallbackTerminalDisposition, completedNs: Long)
  }
  ```

  The concrete v2 observer owns a separate `LiveFrameAdmission` per cadence as its shadow selector.
  For each physical timestamp it snapshots the shadow cadence predicate, calls the shadow selector,
  and immediately completes a shadow submission. It never receives or mutates the production
  admission object.

  Add `frameAdmissionFactory: (Int) -> LiveFrameAdmissionPort = ::LiveFrameAdmission` and
  `attributionV2Observer: AttributionV2CallbackObserver? = null` to `Dependencies`. Tests require:
  default traces remain byte-identical; one admission callback precedes one terminal callback; async
  inference completion may close out of callback order; stop/preprocessing/source-loss paths close;
  and an observer exception fails the session rather than changing admission silently.

- [x] **Step 5: Expose observed AE target FPS at the owned-frame boundary**

  Add the closed value type below and a nullable field to `OwnedYuvFrame` and `OwnedImage`:

  ```kotlin
  data class SourceFpsRange(val lower: Int, val upper: Int) {
      init {
          require(lower > 0 && upper >= lower)
      }
  }
  ```

  In `CameraXOwnedImage`, read `CaptureResult.CONTROL_AE_TARGET_FPS_RANGE` through
  `Camera2CameraCaptureResult.from(proxy.imageInfo.cameraCaptureResult).captureResult`. Preserve null
  when Camera2 supplies no value; never substitute the requested range. Tests require 30/30 to
  survive the owned copy, null to remain null, and a changed value to remain observable.

- [x] **Step 6: Run composition RED**

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :androidApp:testDebugUnitTest \
    --tests '*LiveCameraCompositionTest*attributionV2*' \
    --tests '*OwnedYuvFrameTest*' \
    --tests '*CameraXFrameSourceOwnershipTest*'
  ```

  Expected: compilation failure for the absent dependencies and observer callbacks.

- [x] **Step 7: Wire the observer at the single admission boundary**

  Snapshot production predicates immediately before `reserve`, record the returned production
  classification, and include `relativeNowNs()` as `observedSessionNs`. Emit the terminal only from
  `closeDeliveredCallbackLocked`. A measured backend service duration begins at this admission
  observation and ends at the actual completion/failure timestamp, so it includes selected
  preprocessing plus MediaPipe. Do not create a second admission decision or let the observer mutate
  the production object.

- [x] **Step 8: Add the test-only backend admission policy**

  Define `SingleFlightAttributionAdmission` in the Android test source set. It implements
  `LiveFrameAdmissionPort`, always reports `cadenceDue=true`, and preserves only stop, candidate,
  and one-in-flight semantics. Production construction cannot select it; only the v2 device gate's
  internal test factory may inject it.

- [x] **Step 9: Prove seam mutations**

  Remove the default `::LiveFrameAdmission` and require the byte-identical default trace test to
  fail. Move the predicate snapshot after `reserve` and require the reserved-slot predicate test to
  fail. Omit an async terminal and require conservation/closure to fail. Restore each mutation.

- [x] **Step 10: Run GREEN gates and commit**

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :shared:jvmTest :androidApp:testDebugUnitTest
  cd ../..
  git add apps/diagnostic-harness
  git commit -m "feat: expose attribution observation seam"
  ```

---

### Task 4: Build bounded Android protocol arms and reference-only output

**Files:**
- Modify: `contracts/tooling/m05b_attribution_observation.v2.schema.json`
- Modify: `reference/tests/test_m05b_attribution_v2_contract.py`
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2DeviceGateTest.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2Runner.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2RunnerTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2Json.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2JsonTest.kt`

**Interfaces:**
- Consumes: protocol constants, `CameraXFrameSource`, live composition, single-flight test policy,
  production selector, thermal probe, exact candidate identity, app-private storage.
- Produces: `renderM05BAttributionV2Evidence(M05BAttributionV2DeviceObservation): String`, one bounded
  `m05b_attribution_v2_device_observation` artifact, and exactly one status record under the key
  `M05BAttributionV2Evidence`.

- [x] **Step 1: Write RED evidence-renderer tests**

  Implementation correction: Task 2 already uses `M05BAttributionV2Evidence` as the normalized
  pure-validation input, and Android unit tests cannot see an `androidTest` runner. Preserve that
  single derivation type, name the serialized DTO `M05BAttributionV2DeviceObservation`, and place
  the runner in `main` with all construction restricted to the internal device-gate path. The
  observation schema must carry every raw callback, terminal, thermal, and replicate input that
  Task 5 independently recomputes; a summary-only device record is not admissible.

  Require an immutable record with these top-level fields:

  ```kotlin
  data class M05BAttributionV2DeviceObservation(
      val protocolSha256: String,
      val device: AttributionV2DeviceSource,
      val identity: AttributionV2RunIdentity,
      val sourceArm: AttributionV2SourceObservation,
      val backendArm: AttributionV2BackendObservation?,
      val selectorArm: AttributionV2SelectorObservation?,
      val endToEndReplicates: List<AttributionV2ReplicateObservation>,
      val blockingCodes: List<String>,
  )
  ```

  Rendering derives blocker consistency, emits `sanitized=true` and
  `non_participant_observation=true`, bounds every list by protocol maxima, sorts blockers, and
  refuses absolute paths, device serials, wall-clock fields, participant fields, image bytes, or
  landmark coordinates.

- [x] **Step 2: Run evidence RED**

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :shared:jvmTest --tests '*M05BAttributionV2JsonTest*'
  ```

  Expected: compilation failure for the absent evidence model.

- [x] **Step 3: Implement the minimum closed renderer**

  Use `kotlinx.serialization.json.buildJsonObject`; do not accept a success Boolean. Include exact
  source timestamps, backend service durations, selector-selected timestamps, per-callback terminal
  observations, thermal boundary classes, arm order, measured counts, session references, and
  protocol/candidate identities needed for independent host recomputation.

- [x] **Step 4: Write RED runner tests for stopping and arm dependencies**

  With fake source, inference, clock, thermal status, and storage, require:

  - Arm 0A discards exactly 30 callbacks, records exactly 300, closes every frame, and caps at 60s;
  - Arm 0B waits for both warm-up conditions, records exactly 100 later completions, and caps at 60s;
  - an Arm 0 blocker prevents Arm 1 and Arm 2;
  - Arm 1 uses the exact Arm 0A timestamp list and production selector;
  - Arm 2 runs exactly the two preregistered orders and stops unless the exact first-arm Android
    thermal-status value returns within the 600-second, one-second-sample cooldown schedule;
  - every composition is fresh and every submitted inference reaches terminal closure;
  - the artifact is written once through temp/write/fsync/rename/directory-fsync before status output.

- [x] **Step 5: Run runner RED**

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :androidApp:testDebugUnitTest \
    --tests '*M05BAttributionV2RunnerTest*'
  ```

  Expected: compilation failure because the runner and gate are absent.

- [x] **Step 6: Implement Arm 0A and Arm 0B**

  Arm 0A owns `CameraXFrameSource` directly. `captureOwnedYuvFrame` has already closed the underlying
  CameraX image before delivering each immutable `OwnedYuvFrame`; Arm 0A records only timestamp and
  source metadata, including the observed AE target FPS range, and does not retain plane bytes.
  Missing or changing AE target range, or any measured value other than 30/30, emits
  `ATTRIBUTION.SOURCE_CONFIGURATION`. Arm 0B creates a fresh real composition with
  `SingleFlightAttributionAdmission` and the observer. Warm-up records remain in the durable artifact
  with `phase="warmup"`; measured aggregates consume only `phase="measured"`.

- [x] **Step 7: Implement Arm 1 and conditional Arm 2**

  Arm 1 calls `productionSelectorTimestamps(sourceArm.measuredTimestampsNs, cadence)` for each target
  and records the result as `authoritative=false`. Arm 2 runs only after source/backend support for
  all cadences and valid Arm 1 conformance, creates a fresh composition per cadence, and enforces
  thermal-class recovery before starting each run.

- [x] **Step 8: Implement reference-only instrumentation output**

  The device writes the complete bounded artifact below the app-private diagnostic root, computes
  its SHA-256 after reopening it, and emits only:

  ```kotlin
  buildJsonObject {
      put("record_kind", "m05b_attribution_v2_reference")
      put("protocol_sha256", protocolSha256)
      put("relative_path", "attribution-v2/$runId/observation.json")
      put("sha256", observationSha256)
      put("length_bytes", observationBytes.size)
  }
  ```

  Use `InstrumentationRegistry.sendStatus`; never `println` as the routing channel.

- [x] **Step 9: Prove runner mutations**

  Make the 30th warm-up callback measured; stop backend warm-up after only one condition; permit Arm
  2 after an Arm 0 blocker; reuse one composition; omit the reverse replicate; remove thermal
  recovery; emit before directory fsync. Require the correspondingly named test to fail for each.

- [x] **Step 10: Run GREEN checks and commit**

  ```bash
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :shared:jvmTest :androidApp:testDebugUnitTest \
    :androidApp:compileDebugAndroidTestKotlin
  cd ../..
  git add apps/diagnostic-harness
  git commit -m "feat: add Android attribution v2 protocol arms"
  ```

---

### Task 5: Implement independent host admission and durable-ledger verification

**Files:**
- Create: `tools/check_m05b_attribution_v2.py`
- Create: `reference/tests/test_check_m05b_attribution_v2.py`
- Modify: `reference/tests/test_device_evidence_ledger.py`
- Modify: `contracts/tooling/validator_error_codes.v1.json`
- Modify: `reference/src/observation_contracts/validator_codes.py`
- Modify: `reference/tests/test_validator_code_emission.py`
- Modify: `contracts/CHANGELOG.md`

**Interfaces:**
- Consumes: exact instrumentation transcript, subprocess exit code, serial/package, prepared
  candidate identity, canonical protocol bytes, referenced app-private artifact and session exports.
- Produces: `check_attribution_v2`, `verify_recorded_attribution_v2_ledger`, sanitized host evidence,
  registry version `1.6.0`, and exit classes 0 admitted, 1 malformed/infrastructure, 2 CLI usage,
  and 3 trustworthy blocker.

- [x] **Step 1: Write RED transcript and transfer tests**

  Cover missing/multiple status records, wrong suite, malformed JSON, non-zero instrumentation,
  unsafe relative path, digest/length mismatch, `adb` transport loss, unknown record kind/version,
  and absolute-path/device-serial leakage. Mock subprocesses and assert fixed argv arrays containing
  `adb -s SERIAL exec-out run-as PACKAGE cat RELATIVE_PATH`; never shell strings.

- [x] **Step 2: Run checker RED**

  ```bash
  .venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py
  ```

  Expected: import/file failure because `check_m05b_attribution_v2.py` is absent.

- [x] **Step 3: Implement strict extraction and binary-safe transfer**

  Expose:

  ```python
  def extract_reference(transcript: str) -> Mapping[str, object]:
      return validate_single_status_record(transcript, STATUS_TAG, SUITE)

  def transfer_reference(serial: str, package: str, relative_path: str, destination: Path) -> None:
      transfer_app_private_file(["adb", "-s", serial, "exec-out", "run-as", package, "cat", relative_path], destination)

  def check_attribution_v2(
      transcript: str,
      *,
      subprocess_exit_code: int,
      serial: str,
      package: str,
      prepared_identity: Mapping[str, object],
      root: Path,
  ) -> dict[str, object]:
      return AttributionV2HostCheck(root).run(
          transcript, subprocess_exit_code, serial, package, prepared_identity
      )
  ```

  Transfer to a temporary file, fsync, hash/length-check, rename, and directory-fsync before parsing.
  Treat a transport loss after device publication as `awaiting_recovery`, not permission to rerun.

- [x] **Step 4: Write RED recomputation tests**

  Provide valid synthetic v2 documents, then mutate each independent predicate: protocol digest,
  candidate identity, source sample count/configuration/timestamps, backend sample count/duration,
  selector sequence, callback class, conservation, terminal IDs, inference failures, arm dependency,
  replicate order/rates, thermal class, and device authorization. Assert the exact stable code.

- [x] **Step 5: Implement host recomputation**

  Validate the observation schema, reopen the canonical protocol, recompute its digest, and call
  `validate_attribution_v2`. Recompute every count and rate from exact arrays; reject device-declared
  derived values that differ. Derive `recorded` only when the complete final predicate is true;
  otherwise derive `blocked` with exact sorted codes.

- [x] **Step 6: Implement ledger reopening**

  `pending` requires its named precondition and null evidence fields. `recorded` or `blocked` must
  open a safe repo-relative file under `docs/evidence/m05b-attribution-v2/`, hash exact bytes, validate
  the host evidence schema, recompute the verdict, and require status/blockers/identity/protocol
  fields to agree. A `recorded` ledger with any blocker fails validation.

- [x] **Step 7: Prove host mutations**

  Remove protocol rehashing, drop `admission_state_divergence` from conservation, replace exact
  backend durations with a median, accept one replicate, allow selector-only admission, and skip
  evidence digest comparison. Each mutation must fail its named focused test; restore byte-exactly.

- [x] **Step 8: Promote the complete v2 code family atomically**

  Move every v2 code from `DRAFT_EMITTED_CODES` to `STABLE_EMITTED_CODES`, advance the registry from
  `1.5.0` to `1.6.0`, append the complete family in sorted order, and add the tooling release to
  `contracts/CHANGELOG.md`. Rename the test fixture prefix to `PRE_1_6_RELEASED_CODES`, populate it
  with the complete exact 1.5 registry list, require the 1.6 registry to retain that prefix
  byte-for-byte, and require only the newly appended 1.6 suffix to equal the sorted v2 family. The
  promotion test must require the complete set as a subset of the released registry and the
  behavioral-oracle test must find every code in the focused tests. A partial release remains
  failing.

- [x] **Step 9: Run GREEN static/focused checks and commit**

  ```bash
  .venv/bin/python -m pytest -q \
    reference/tests/test_check_m05b_attribution_v2.py \
    reference/tests/test_device_evidence_ledger.py \
    reference/tests/test_m05b_attribution_v2.py \
    reference/tests/test_validator_code_emission.py
  .venv/bin/python -m ruff format --check reference tools/check_m05b_attribution_v2.py
  .venv/bin/python -m ruff check reference tools/check_m05b_attribution_v2.py
  MYPYPATH=reference/src .venv/bin/python -m mypy --strict tools/check_m05b_attribution_v2.py
  git add tools reference contracts
  git commit -m "feat: verify M0.5B attribution v2 evidence"
  ```

---

### Task 6: Add the exact-candidate gate and rewire Task 7 admission

**Files:**
- Modify: `Makefile`
- Create: `reference/tests/test_m05b_attribution_v2_build_contract.py`
- Modify: `reference/tests/test_m05b_sustained_build_contract.py`
- Modify: `docs/superpowers/plans/2026-08-27-m0-5b-instrument-prerequisites.md`

**Interfaces:**
- Consumes: prepared app/test APK identity, canonical protocol digest, v2 device suite/checker, v2
  pending ledger.
- Produces: `make verify-harness-s25-m05b-attribution-v2`; Task 7/sustained prerequisite bound only
  to a host-verified recorded v2 ledger.

- [x] **Step 1: Write RED Make-contract tests**

  Require the new target to count exactly one `adb devices` row whose second field is `device`,
  reject emulator and USB serials, authorize S25 identity, require battery power, require clean HEAD
  equal to prepared Git SHA, compare installed app and test APK bytes, hash the canonical protocol,
  pass that digest into exactly `M05BAttributionV2DeviceGateTest`, capture the real instrumentation
  exit code, and invoke only the v2 host checker. Reject build/install/`pm clear`, ledger writes,
  success grep, wildcard device selection, or broad deletion in the target.

- [x] **Step 2: Run build-contract RED**

  ```bash
  .venv/bin/python -m pytest -q reference/tests/test_m05b_attribution_v2_build_contract.py
  ```

  Expected: target-not-found failure.

- [x] **Step 3: Implement the exact-candidate target**

  Clone the proven preamble from `verify-harness-s25-m05b-attribution`, but use new temporary names,
  new suite/checker, protocol digest, and v2 evidence paths. The recipe consumes the candidate; it
  never prepares or changes it.

- [x] **Step 4: Write RED Task 7 prerequisite tests**

  Assert `verify-harness-s25-m05b-sustained` no longer reads the v1 ledger, opens the v2 ledger
  through `verify_recorded_attribution_v2_ledger`, and rejects `pending`, `blocked`, digest-mismatched,
  selector-only, or device-declared-success evidence.

- [x] **Step 5: Rewire the prerequisite and parent plan**

  Replace only the Task 6 prerequisite with the v2 verifier. Keep the existing envelope ledger and
  all exact-candidate safeguards. Amend the parent plan to reference the accepted v2 design and this
  implementation plan; preserve the v1 checkbox/evidence as an immutable blocked attempt.

- [x] **Step 6: Prove Make mutations**

  Remove installed test-APK comparison, admit USB, omit the protocol digest, call the v1 suite, and
  replace the v2 ledger verifier with `status == recorded`. Each change must fail a named build-
  contract test; restore each one.

- [x] **Step 7: Run GREEN checks and commit**

  ```bash
  .venv/bin/python -m pytest -q \
    reference/tests/test_m05b_attribution_v2_build_contract.py \
    reference/tests/test_m05b_sustained_build_contract.py \
    reference/tests/test_device_evidence_ledger.py
  git diff --check
  git add Makefile reference/tests docs/superpowers/plans
  git commit -m "feat: gate Task 7 on attribution v2"
  ```

---

### Task 7: Complete the software boundary and bounded review

**Files:**
- Modify: `docs/superpowers/plans/2026-09-05-m0-5b-task-6-attribution-v2.md` (checkboxes only)
- Modify ignored ledger: `.superpowers/sdd/2026-09-05-m0-5b-task-6-attribution-v2/progress.md`

**Interfaces:**
- Consumes: Tasks 1-6 commits.
- Produces: one clean exact software candidate ready for physical preparation; no physical verdict.

- [x] **Step 1: Run the complete local verification boundary**

  ```bash
  make verify
  make verify-harness-jvm
  make verify-harness-ios
  cd apps/diagnostic-harness
  ./gradlew --no-daemon :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest
  ```

  Record exact exits, test counts, compiler versions, and framework/APK outputs in the ignored
  ledger. A success-looking line cannot override a non-zero command exit.

- [x] **Step 2: Run all named mutations as one audit**

  Re-run every mutation named in Tasks 1-6 against its focused test. Record mutation, intended
  oracle, failing test name, exit code, byte-exact restoration proof, and post-restoration GREEN.

- [x] **Step 3: Inspect exact scope and invariants**

  ```bash
  git diff --check origin/main...HEAD
  git status --short
  git diff --stat origin/main...HEAD
  rg -n "m05b-attribution|M05BAttribution|ATTRIBUTION\." \
    contracts docs apps reference tools Makefile
  ```

  Confirm v1 evidence hashes are unchanged, no participant artifacts/model binaries are tracked,
  default live admission tests remain green, and the sustained gate has exactly one v2 prerequisite.

- [x] **Step 4: Request one bounded full-range review**

  Review only the Task 6 v2 range against the accepted specification. Critical/Important findings
  block; Minor findings are recorded as debt. Permit at most one scoped re-review after fixes. The
  reviewer must independently inspect the diff and may not rely on this ledger's claims.

- [x] **Step 5: Resolve blocking findings regression-first**

  For every Critical/Important finding, add a failing regression, observe the intended failure,
  make the minimum correction, rerun focused/full gates, and use the one scoped re-review. Do not
  implement unrelated hardening from the review.

- [x] **Step 6: Commit the verified software checkpoint**

  ```bash
  git add docs/superpowers/plans/2026-09-05-m0-5b-task-6-attribution-v2.md
  git commit -m "test: verify M0.5B attribution v2 software gate"
  ```

  Keep the branch local until separately authorized to push. Do not open a PR, merge, or tag.

---

### Task 8: Execute the physical v2 protocol and admit immutable evidence

**Files:**
- Create after an admitted execution: `docs/evidence/m05b-attribution-v2/s25-ultra-SM-S938B-api36-attribution-v2-recorded.json`
- Create after a blocking execution: `docs/evidence/m05b-attribution-v2/s25-ultra-SM-S938B-api36-attribution-v2-blocked.json`
- Modify after execution: `docs/evidence/m05b-attribution-v2/s25-attribution-v2-ledger.json`
- Modify: `docs/superpowers/plans/2026-08-27-m0-5b-instrument-prerequisites.md`
- Modify ignored ledger: `.superpowers/sdd/2026-08-27-m0-5b-instrument-prerequisites/progress.md`

**Interfaces:**
- Consumes: clean reviewed software checkpoint, one authorized S25, exact candidate preparation,
  canonical protocol, and v2 Make gate.
- Produces: one immutable recorded or blocked v2 observation; Task 7 is unblocked only by recorded.

- [ ] **Step 1: Prepare one exact candidate**

  Verify clean local HEAD, fetch the model asset, create `local.properties`, run the existing exact
  preparation target once, and record app/test APK, Git, model, runtime, backend, and delegate
  identities. No source change, rebuild, reinstall, or package clear is allowed afterward.

- [ ] **Step 2: Establish the physical boundary**

  Require exactly one authorized S25 over Wi-Fi ADB, physically unplug USB, verify every battery
  power-source flag is false, record the boundary thermal-status class, and attest no person is in
  the camera field. If any precondition fails, do not start an arm.

- [ ] **Step 3: Run the protocol exactly once**

  ```bash
  make verify-harness-s25-m05b-attribution-v2
  ```

  Preserve the numeric exit and exact host output. Exit 0 is admitted; exit 3 is a trustworthy
  blocker; exit 1 is malformed/infrastructure and enters recovery analysis without an automatic
  rerun; exit 2 is CLI usage failure.

- [ ] **Step 4: Independently validate the durable result**

  Reopen the host evidence, canonical protocol, all referenced device/session artifacts, and exact
  candidate identities. Recompute the evidence SHA and run the ledger verifier before editing the
  ledger. Confirm the evidence is sanitized and contains no participant fields, paths, serial, media,
  pose coordinates, or image bytes.

- [ ] **Step 5: Record the immutable result**

  Write the exact sanitized host result once using a filename ending in `recorded` or `blocked` that
  matches the recomputed verdict. Advance the ledger with exact evidence/protocol hashes and derived
  blockers. Never edit the admitted evidence in place.

- [ ] **Step 6: Enforce the branch outcome**

  If recorded, mark Task 6 v2 complete and permit Task 7 envelope/order freeze on the same unchanged
  candidate. If blocked, leave Task 7 and sustained execution blocked and record the limiting
  subsystem. A backend/source/cadence change requires a new reviewed design and protocol version.

- [ ] **Step 7: Run the recorded-state verification boundary**

  Run focused checker/ledger tests, `make verify`, JVM/iOS/Android gates, repository guard,
  `git diff --check`, and exact scope inspection. Record all exits and test counts.

- [ ] **Step 8: Commit the evidence checkpoint**

  ```bash
  git add docs/evidence/m05b-attribution-v2 \
    docs/superpowers/plans/2026-08-27-m0-5b-instrument-prerequisites.md
  git commit -m "test: record M0.5B attribution v2 evidence"
  ```

  Do not push, open a PR, merge, tag, delete branches/worktrees, or begin participant capture without
  separate authority.

---

## Self-review checklist

- Specification coverage: Tasks 1-8 cover protocol pre-registration, exact source/backend/selector
  derivation, dual-predicate observation, callback conservation, thermal/order controls, independent
  host admission, Task 7 gating, bounded review, and immutable physical evidence.
- Separation: v1 files remain historical; v2 contracts, code, checker, command, and ledger use new
  names and versions.
- Type consistency: `M05BAttributionV2Evidence` is rendered by Android, transferred as one
  reference, validated by `check_attribution_v2`, and admitted only through
  `verify_recorded_attribution_v2_ledger`.
- Causal consistency: source support excludes selector behavior; backend support consumes exactly
  100 durations; selector conformance uses production Kotlin and independent Python arithmetic;
  end-to-end admission requires both replicate orders.
- Authority: software implementation and synthetic device evidence cannot merge, tag, authorize
  participant capture, or change product/research cadence without the release owner.
- Placeholder scan: every task names concrete files, interfaces, tests, commands, expected RED,
  implementation behavior, mutations, GREEN gates, and commit messages.
