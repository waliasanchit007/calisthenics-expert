# Slice 5 Live Android Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an installable Android diagnostic path that admits the observed S25 camera configuration, converts owned `YUV_420_888` buffers to canonical RGB24 with byte-level evidence, runs MediaPipe Pose Landmarker in `LIVE_STREAM` mode, and exports a validator-clean live session.

**Architecture:** CameraX owns acquisition and immediately copies every admitted `ImageProxy` into immutable plane bytes before closing it. Pure shared Kotlin owns BT601/FULL conversion, orientation/mirroring, cadence selection, and the one-in-flight admission decision; the existing `SessionRunner` owns counters, terminal state, drain fencing, inference callbacks, and export. Android-specific factories own CameraX, MediaPipe, clocks, model verification, and app-private storage.

**Tech Stack:** Kotlin Multiplatform 2.4.10, Compose Multiplatform 1.11.1, CameraX 1.6.1, MediaPipe Tasks Vision 1.0.0, Android API 33+, Kotlin/JVM tests, Android instrumented tests, Python contract validators.

**Spec:** `docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md` sections 8-13, `contracts/preprocessing/live_capture_to_model_input.v1.md`, and `docs/adr/ADR-0002-canonical-preprocessing.md`.

## Global Constraints

- The only admitted physical configuration for this slice is the recorded S25 result: camera `0`, `YUV_420_888`, timestamp source `REALTIME`, raw DataSpace `146931712`, colour standard `BT601`, colour range `FULL`, and an available underlying-image bridge.
- A different or absent timestamp source, raw DataSpace, colour standard/range, pixel format, camera ID, or bridge result fails closed before session start. It never selects another conversion matrix silently.
- `ImageInfo.timestamp` is authoritative capture time; inference readings use `SystemClock.elapsedRealtimeNanos`; every recorded session timestamp is rebased to one session-relative `t0 = 0` domain.
- CameraX uses `STRATEGY_KEEP_ONLY_LATEST` and `OUTPUT_IMAGE_FORMAT_YUV_420_888`; RGBA output is forbidden.
- The analyzer copies all required planes and metadata into owned immutable bytes, closes `ImageProxy` immediately, and never waits for pose inference.
- Every admitted delivered callback is exactly one of submitted or skipped for `cadence_selection`, `backpressure`, `preprocessing_failed`, or `session_stopping`. CameraX replacement before analyzer delivery is upstream/unclassified cadence loss, never an invented skip.
- Exactly one MediaPipe `LIVE_STREAM` call may be in flight. The slot is released by result/error callback or drain-timeout fencing, never by `detectAsync` returning.
- Live conversion is exact BT601/FULL YUV-to-full-range-sRGB RGB24. Row stride, pixel stride, odd dimensions, rotation, and mirroring are part of conformance. BT709/LIMITED substitutions must fail tests.
- Orientation and mirroring produce an upright, unmirrored RGB24 source plane before the existing canonical scale-to-fit operation.
- Model SHA, Tasks Vision version, CPU delegate, `LIVE_STREAM` mode, task options, source camera metadata, and preprocessing contract version are recorded in each manifest.
- No crop, external ROI, smoothing, coordinate clipping, landmark imputation, confidence threshold, quality score, raw participant video, or network permission is introduced.
- Emulator checks prove host logic, lifecycle, typed failure, APK installation, and synthetic camera plumbing only. They do not establish physical DataSpace, timestamp-source, detector, thermal, or capture-quality claims.
- The physical S25 gate prints evidence for a separate human/controller recording step; it never writes its own tracked ledger.
- Every production behavior is regression-first. Record the failing command/reason before implementation, and verify the prescribed mutation turns the covering test red.

---

### Task 1: BT601/FULL YUV420 Conversion and Golden Vectors

**Files:**
- Create: `fixtures/synthetic/preprocessing/yuv420_bt601_full/vector.json`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/preprocessing/Yuv420.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/preprocessing/Yuv420Test.kt`
- Modify: `reference/tests/test_committed_fixtures.py`
- Modify: `reference/tests/test_repository_guard.py`

**Interfaces:**
- Produce `enum class YuvColorStandard { BT601 }`, `enum class YuvColorRange { FULL }`, and `enum class FrameRotation { R0, R90, R180, R270 }`.
- Produce immutable `YuvPlane(bytes: ByteArray, rowStride: Int, pixelStride: Int)` and `Yuv420Frame(width, height, y, u, v, colorStandard, colorRange, rotation, mirrored)` types that defensively copy mutable inputs.
- Produce `fun yuv420ToUprightRgb24(frame: Yuv420Frame): ByteArray` and upright dimension helpers.
- Use fixed-point integer arithmetic with explicit half-up rounding and channel clamping; do not depend on Android colour utilities or floating-point platform behavior.

- [x] **Step 1: Commit an independently derived vector before production code**

Create one textual vector containing literal Y/U/V plane bytes, dimensions, row/pixel strides, BT601/FULL metadata, rotation/mirroring cases, literal expected RGB24 bytes, byte lengths, and SHA-256 values. It must include chroma values for which BT601 and BT709 differ after rounding, padded rows, chroma pixel stride greater than one, and odd source dimensions. Expected bytes must be calculated independently of `yuv420ToUprightRgb24` and documented in the vector.

- [x] **Step 2: RED — parse the committed vector and demand exact output**

Add common tests that load the vector and assert defensive copying, plane-bound checks, exact RGB byte output, upright dimensions, rotation, and mirroring. Add Python repository tests that independently recompute each committed SHA and reject missing vector fields. Run:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest --tests '*Yuv420Test*'
```

Expected: FAIL because the YUV types/converter do not exist.

- [x] **Step 3: GREEN — implement the minimum pure converter**

Implement only the admitted BT601/FULL path. Reject unsupported matrix/range values at construction or conversion; do not add dormant BT709/LIMITED coefficients. Address Y, U, and V by their own row/pixel strides, map upright output coordinates back into source coordinates for rotation/mirroring, convert one pixel, clamp to `0..255`, and emit packed row-major RGB24.

- [x] **Step 4: Verify exact conformance and the matrix mutation**

Run the focused test, then temporarily replace the BT601 red/green/blue coefficients with the corresponding BT709 coefficients. The committed-vector test must fail on byte inequality. Revert the mutation and rerun focused tests plus:

```bash
make verify
make verify-harness-jvm
```

- [x] **Step 5: Commit**

```bash
git add fixtures/synthetic/preprocessing/yuv420_bt601_full apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/preprocessing/Yuv420.kt apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/preprocessing/Yuv420Test.kt reference/tests/test_committed_fixtures.py reference/tests/test_repository_guard.py
git commit -m "feat: add BT601 full-range YUV conformance"
```

### Task 2: Immutable CameraX Acquisition and Cadence Admission

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/camera/OwnedYuvFrame.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/camera/CameraXFrameSource.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/camera/OwnedYuvFrameTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveFrameAdmission.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/live/LiveFrameAdmissionTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/AndroidManifest.xml`

**Interfaces:**
- `OwnedYuvFrame` carries copied Y/U/V bytes, each plane's row/pixel stride, source dimensions, rotation, mirroring, raw DataSpace, decoded standard/range, and absolute CameraX timestamp.
- `CameraXFrameSource.start(onFrame, onFailure)` binds camera ID `0` explicitly (filtering by Camera2 camera ID, not generic lens facing) to lifecycle with YUV output and keep-latest backpressure; `stop()` is idempotent and unbinds only owned use cases.
- `LiveFrameAdmission(configuredFps)` produces `CADENCE_SELECTION`, `BACKPRESSURE`, `PREPROCESSING_FAILED`, `SESSION_STOPPING`, or `SUBMIT`; cadence decisions use capture timestamps and support exactly 10, 15, and 30 FPS.

- [x] **Step 1: RED — pin copying, immediate close, and admitted metadata**

Write host tests around a narrow image/plane seam showing that callbacks receive immutable bytes even after source buffers are mutated; all three plane strides survive; source metadata is copied; and the proxy close action happens before `onFrame` returns. Pin exact CameraX constants in a build-contract test.

- [x] **Step 2: RED — pin cadence and exhaustive classification**

Write common tests for 10/15/30 FPS cadence, same-timestamp behavior, stopped-session classification, occupied-slot backpressure, preprocessing failure, and the invariant that one delivered callback yields exactly one terminal admission decision.

- [x] **Step 3: GREEN — implement acquisition and pure admission**

Add the CAMERA permission only. Reuse the probe's CameraX dispatcher/lifecycle conventions, but do not reuse its one-shot timeout semantics as the session source. Decode every buffer's DataSpace and reject any value other than the recorded BT601/FULL result before exposing the frame.

- [x] **Step 4: Verify mutations**

Temporarily remove the U-plane copy, change keep-latest to block-producer, and allow a second classification for one callback. Each relevant focused test must fail. Revert each mutation and run focused JVM/Android host tests.

- [x] **Step 5: Commit**

```bash
git add apps/diagnostic-harness/androidApp/src/main/AndroidManifest.xml apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/camera apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/camera apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/live
git commit -m "feat: add immutable CameraX live acquisition"
```

### Task 3: MediaPipe LIVE_STREAM Port and One-In-Flight Controller

**Files:**
- Create: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/mediapipe/PoseLandmarkerLiveInferencePort.kt`
- Create: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/mediapipe/PoseLandmarkerLiveDetector.kt`
- Create: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/mediapipe/PoseLandmarkerLiveAdapterFactory.kt`
- Create: `apps/diagnostic-harness/shared/src/androidHostTest/kotlin/com/calisthenics/diagnostic/mediapipe/PoseLandmarkerLiveInferencePortTest.kt`
- Create: `apps/diagnostic-harness/shared/src/androidHostTest/kotlin/com/calisthenics/diagnostic/mediapipe/PoseLandmarkerLiveAdapterFactoryTest.kt`
- Modify: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/mediapipe/PoseLandmarkerConstruction.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/SessionRunner.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/session/SessionRunnerTest.kt`

**Interfaces:**
- `PoseLandmarkerLiveAdapterFactory.create()` verifies a `LIVE_STREAM` provenance/model asset and constructs one fresh landmarker per session with result and error listeners wired before construction.
- `PoseLandmarkerLiveDetector` is the host-testable native seam; result mapping and timestamp
  extraction occur at this boundary because MediaPipe 1.0.0's result factory is package-private.
- `PoseLandmarkerLiveInferencePort : InferencePort, Disposable` owns one monitor-confined
  `Empty | Occupied | Closed` slot, its canonical direct buffer, and only the `MPImage` it creates.
  It validates the result timestamp, releases ownership before notification, and enqueues runner
  notification on a port-owned executor rather than blocking MediaPipe's callback thread.
- `SessionRunner.hasPendingInference(): Boolean` is a dispatcher-confined conservative advisory
  gate: stale-occupied is allowed; stale-free is not. Port ownership is authoritative.
- LIVE_STREAM listener wiring is required iff the options running mode is LIVE_STREAM. A
  pre-construction sink is installed with the port after detector creation and records, rather
  than dereferences, any pre-install callback.

- [x] **Step 1: RED — asynchronous ownership and callback mapping**

Test that `detectAsync` returning does not release input or slot ownership; result/error releases
the slot and owned image exactly once before queued runner notification; timestamp mismatch fails
closed; duplicate/unknown/late callbacks cannot enter the runner; unattributable errors latch the
port unhealthy; cancel/drain timeout releases owned resources; callback exceptions never escape
the MediaPipe listener thread; and disposal closes a fresh detector exactly once without waiting
on a callback blocked in the session dispatcher. Never close the callback-supplied `MPImage`.

- [x] **Step 2: RED — expose the pending-slot query**

Add `SessionRunner` tests showing the advisory query is false initially, true after submission,
and false only after success, failure, or drain timeout—not after the backend call merely returns.
Pin that deadline expiry invokes `InferencePort.cancel(submissionIndex)` before recording
`session_teardown_timeout`. Remove the unused admit-time copy while retaining the copy at the
port boundary.

- [x] **Step 3: GREEN — implement the live factory/port and query**

Share construction-site evidence with the replay adapter without weakening replay's CPU/VIDEO
gate. Preserve model/task-option evidence exactly. Require a listener bundle iff running mode is
LIVE_STREAM and reject it for VIDEO/IMAGE. Use MediaPipe's `detectAsync` and native listeners; do
not emulate LIVE_STREAM with `detectForVideo`. Supply callback `nowNs` in the same rebased session
clock domain. The actual live composition uses `SingleThreadSessionDispatcher`; the port's local
monitor and callback executor remain independent of that dispatcher.

- [x] **Step 4: Verify mutations**

Temporarily clear ownership immediately after `detectAsync`, notify the runner before clearing the
slot, omit deadline cancellation, and allow creation with VIDEO provenance. The corresponding
tests must fail. Revert and run all androidHost/session tests.

- [x] **Step 5: Commit**

```bash
git add apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/mediapipe apps/diagnostic-harness/shared/src/androidHostTest/kotlin/com/calisthenics/diagnostic/mediapipe apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/SessionRunner.kt apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/session/SessionRunnerTest.kt
git commit -m "feat: add MediaPipe live-stream inference"
```

### Task 4: Live Session Composition, Manifest, and Shared UI

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/LiveCameraCompositionTest.kt`
- Create: `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/LiveRunState.kt`
- Create: `apps/diagnostic-harness/composeApp/src/commonTest/kotlin/com/calisthenics/diagnostic/ui/LiveRunStateTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/MainActivity.kt`
- Modify: `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/HarnessApp.kt`
- Modify: `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/HarnessUiState.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/capability/Capability.kt`

**Interfaces:**
- `LiveCameraComposition.start(configuredInferenceFps, onProgress)` returns a typed started/unavailable result; `stop()` fences acquisition, drains one pending inference to the configured deadline, finalizes once, and returns a typed completion.
- The composition validates the recorded camera-capability evidence before constructing the source or session, rebases CameraX and inference clocks to one `t0`, converts YUV, applies existing scale/pad, and routes each callback through exactly one `SessionRunner.submit` or `skip` call.
- The manifest records session kind `live`, camera metadata, preprocessing `1.0.0`, configured/achieved cadence, exact MediaPipe construction evidence, source dimensions before orientation, and canonical model-input dimensions after preprocessing.
- Shared UI exposes Replay/Live mode, 10/15/30 FPS, capability/failure reasons, live counters, start, and stop. Permission denial or revocation is typed and fail-closed.
- A five-second controller-owned in-flight stall fence starts with each successful submission. A
  result/error cancels it. Expiry stops acquisition and the session, exposes typed internal
  `live_inference_stalled`, and lets the existing drain deadline record
  `session_teardown_timeout`; it never relabels the drop as `backend_call_failed`.

- [x] **Step 1: RED — composition admission and timestamp domain**

Write host tests with fake acquisition/inference/storage seams for wrong camera metadata, all four
skip reasons, a shared `t0 = 0` rebase including callback-time `nowNs`, source-vs-model dimensions,
one-slot backpressure, the five-second silent-drop stall fence, stop/drain/finalize order, and exact
manifest source metadata. Prove the stall path stops the session, reports
`live_inference_stalled` internally, and records only the existing `session_teardown_timeout`
contract classification.

- [x] **Step 2: RED — UI reachability and permission behavior**

Write common UI/state tests proving Live selection requires camera/model/backend/storage capabilities, exposes only 10/15/30 FPS, disables start while active or permission-denied, and maps completion/failure without changing Replay behavior.

- [x] **Step 3: GREEN — compose the live path**

Implement the smallest composition and UI wiring. Use the existing `SessionRunnerOwnership`, `AndroidStoragePort`, `SessionExporter`, model-asset staging, and single-thread dispatcher. Do not add preview/overlay unless required to make capture lifecycle correct; section 12 says Android *may* show them.

- [x] **Step 4: Verify mutations**

Temporarily record the absolute capture timestamp instead of rebasing it and temporarily admit a BT709 value. Each focused test must fail. Revert and run focused tests, `make verify`, and `make verify-harness-jvm`.

- [x] **Step 5: Commit**

```bash
git add apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui apps/diagnostic-harness/composeApp/src/commonTest/kotlin/com/calisthenics/diagnostic/ui apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/capability
git commit -m "feat: compose live Android observation sessions"
```

### Task 5: Installable and Physical S25 Live Evidence Gate

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/LiveCaptureDeviceGateTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidence.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidenceTest.kt`
- Create: `tools/check_live_capture_run.py`
- Create: `reference/tests/test_live_capture_run_check.py`
- Create: `docs/evidence/live-capture/s25-ultra-live-capture-ledger.json`
- Modify: `reference/tests/test_device_evidence_ledger.py`
- Modify: `reference/tests/test_harness_build_contract.py`
- Modify: `Makefile`

**Interfaces:**
- The instrumented test runs a bounded 10 FPS live session on the authorized S25, stops it, emits one typed sanitized evidence record through `InstrumentationRegistry.sendStatus`, and leaves the exported manifest/NDJSON under app-private storage for validation.
- `make verify-harness-s25-live-capture` requires exactly one authorized physical S25, rejects emulators, fetches/verifies the model asset, installs the APK/test APK, runs the exact test, pulls the export, validates it with the Python reference package, and passes the transcript to `tools/check_live_capture_run.py`.
- The checker derives admission from observed identity, camera metadata, counters, validation result, and instrumentation exit status. It never trusts a caller-declared success boolean.

- [x] **Step 1: RED — typed evidence and transcript checker**

Write exhaustive common/Python tests for authorized identity, exact BT601/FULL metadata, at least one received/submitted/emitted record, zero unclassified delivered callbacks, validator success, one evidence record, and typed blocking reasons. The ledger's recorded branch must open the exact evidence file and verify its SHA.

- [x] **Step 2: RED — build/device gate contract**

Pin the exact device-counting preamble (`awk '$2 == "device"'`), emulator rejection, shared authorization helper, model fetch, install/run/pull/validate order, instrumentation status channel, and the rule that the target never writes the ledger.

- [x] **Step 3: GREEN — implement the bounded device gate**

Implement the instrumented test, renderer/checker, Make target, and pending ledger. The target is not part of Ubuntu protected CI because it requires physical hardware.

- [x] **Step 4: Verify mutations and host gates**

Mutate each gate conjunct once: identity, timestamp source, standard, range, bridge, emitted count, validation result, and instrumentation status. Each mutation must make a focused test fail. Revert and run:

```bash
make verify
make verify-harness-jvm
make verify-harness-ios
```

- [x] **Step 5: Run physical S25 evidence and record separately**

Run `make verify-harness-s25-live-capture`. A successful probe with a blocking observation is recorded honestly and stops the milestone. If admitted, write the sanitized record under `docs/evidence/live-capture/`, record its SHA and result in the ledger, rerun the host verification, and commit the evidence separately.

- [x] **Step 6: Commit implementation and evidence**

```bash
git add apps/diagnostic-harness/androidApp/src/androidTest apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/live tools/check_live_capture_run.py reference/tests/test_live_capture_run_check.py reference/tests/test_device_evidence_ledger.py reference/tests/test_harness_build_contract.py Makefile docs/evidence/live-capture
git commit -m "feat: add the S25 live-capture evidence gate"
```

## Slice Acceptance

- Run `make verify`, `make verify-harness-jvm`, and `make verify-harness-ios` at exact final HEAD.
- Run a diff/scope inspection against base `b76050d2e0f0342db5a0b4da47cbab3dd58ee3e9`.
- Run one full-range review because this slice crosses camera, colour-conversion, asynchronous native inference, lifecycle, and export boundaries. One scoped fix/re-review round maximum; only Critical/Important findings block, while Minor findings are recorded as debt.
- Push a feature branch and open a protected PR. Do not merge it without release-owner authorization.
- Preserve the feature branch and worktree.
