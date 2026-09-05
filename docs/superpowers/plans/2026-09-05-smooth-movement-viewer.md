# Smooth movement viewer implementation plan

**Goal:** Remove inference-throughput video playback and show measurable, bounded-latency pose feedback on the S25 front camera.

**Architecture:** CameraX Preview and ImageAnalysis share a ViewPort and lifecycle. Each analysis result carries an immutable sensor-to-buffer matrix plus rotation and full-buffer dimensions; the UI reverses model padding and rotation, then maps buffer to sensor to current PreviewView pixels. Preview is continuous, pose is latest-result and explicitly asynchronous. The existing paired synthetic mode remains available.

**Tech stack:** Existing Kotlin/Compose/CameraX 1.6.1 and CPU Pose Full. No new model, recording, cloud or formal-study work.

**Spec:** Owner-approved 2026-09-05 chat design and ADR-0006 amendment below. Execute inline with TDD and one bounded Claude design challenge, per the approved minimal-review operating policy.

## Constraints

- Work in existing isolated `codex/movement-viewer`; preserve historical branches and tags. No push/PR/merge.
- Keep formal camera source behavior unchanged when no preview binding is supplied.
- Keep acquisition single-flight; no image queues. Stop/background/error clears observations and releases both use cases before disposing inference.
- Frame transform uses full buffer (not crop-relative) and applied rotation, matching preprocessing. Bind both streams to one ViewPort; null transform means no overlay, never guessed alignment.
- Live pose expiration starts at capture, not callback; initial 250 ms display setting is not a movement-accuracy threshold. Synthetic paired mode retains its existing timeout.
- Report capture-to-result age, preparation, inference, observed analysis callback FPS and completed-pose FPS. Do not call callback FPS preview FPS.
- No imagery/body data persistence, screenshots or uploads for this increment. Expanded owner permission is recorded only in ignored ledger; a recording feature needs explicit destination/retention policy before external transfer.

## Task 1 — Observable, event-driven session

Files: `androidApp/.../preview/ViewerSession.kt`, new `ViewerPerformance.kt`, corresponding unit tests.

- [x] Inject monotonic clock while preserving existing constructor arguments; use a conflated `StateFlow<ViewerSnapshot>`.
- [x] Regression-first: a controlled callback immediately publishes a result; close/late callback never republishes a frame. Fake clock pins preparation/inference/capture-to-result durations independently; unavailable metrics remain null.
- [x] Run `./gradlew :androidApp:testDebugUnitTest --tests '*Viewer*' --console=plain`; inspect actual test XML and exit code. No test-only storage or metrics history.

## Task 2 — Independent preview and geometry

Files: `camera/CameraXFrameSource.kt`, new `camera/CameraPreviewBinding.kt`, `preview/MovementViewerController.kt`, `MovementViewerActivity.kt`, new `PreviewOverlayTransform.kt`, common `MovementViewerScreen.kt`, Android Gradle dependency.

- [x] Add the existing version-catalog camera-view dependency. Write transform tests first: nonzero crop offset, 90-degree rotation, mirror transform, padding rejection and unavailable target.
- [x] Copy `ImageInfo.sensorToBufferTransformMatrix` before ImageProxy closes. Map `ViewerGeometry.modelToSource` to upright pixels, undo rotation, invert sensor-to-buffer, then apply `PreviewView.sensorToViewTransform`; test literal expected pixel positions. This uses stable sensor-space APIs instead of experimental output-transform APIs, following the bounded Claude challenge.
- [x] Bind Preview plus Analysis in `UseCaseGroup` using `PreviewView.viewPort`. Stop unbinds both, including startup failure/stale generation. Native surface is scoped to the active camera viewer, destroyed on stop.
- [x] Collect session updates on UI thread; timer handles expiry only. Native preview replaces camera bitmap creation; paired synthetic path retains bitmap display. Camera preview remains unmirrored via explicit mirror mode.
- [x] Run focused geometry/lifecycle tests, synthetic real-model device test, and build delivery/test APKs. Model asset and local SDK prerequisites already exist and packaging must verify them.

### Measured preprocessing follow-up (same increment)

First native-preview S25 check observed preparation210.9ms, inference19.0ms and4.33 completed callbacks/s.
The native preview streamed and stopped successfully. Optimize the measured preparation path before
handoff: a viewer-only fused YUV-to-scalePad converter must be byte-identical to the full converter
followed by the existing rational bilinear scaler. Use integer rational bilinear weights, not nearest
neighbor or float approximations. Keep formal/reference scaler unchanged. Regression tests compare
all rotations/mirroring/odd dimensions/strides and independent literal interpolation/padding vectors.
Do not retain a full-resolution RGB preview buffer when native preview displays it independently.

Software complete: parity and independent-vector tests pass; replacing half-up with floor fails both
odd/oriented and even/downsampling comparisons. The wrong-rounding mutation was restored before
the final passing build. A short unlocked S25 check subsequently observed preview28.65FPS,
pose16.17FPS, preparation26.88ms and capture-to-result135.90ms (last-sample timings, not p95).
The earlier full-preparation run observed pose4.33FPS, preparation210.91ms and308.90ms
capture-to-result. These are development observations, not a sustained performance envelope.

## Task 3 — Physical check and product handoff

- [x] Run Android unit/shared UI integration gates and Python repository verification; compile both iOS frameworks if shared UI changes.
- [x] Install without clearing data. Run synthetic and privacy/lifecycle instrument tests; test camera binding/transform and performance on authorized S25 without retaining frames. Report observed rates separately from targets.
- [x] Recheck scope, commit local changes, update ignored ledger. Present smoother installable viewer and next movement experiment; do not resume formal study or expand into a generic telemetry platform.

Final verification:223 Android unit tests,417 shared JVM tests,1021 Python tests; Ruff/strict mypy
and repository guard pass; both iOS frameworks build. Physical native preview test passed separately
with explicit self-test authorization. Default device suite passed four tests and correctly skipped
the authorization-required live test; synthetic detection still produced33 landmarks and Stop cleared
observations without new files. No recording/upload feature or study integration was added.
