# Movement measurements and provisional cycles

**Goal:** Show useful live movement measurements, then an explicitly calibrated pull-up cycle
experiment in the existing viewer. This is not automatic exercise recognition or strict-rep grading.

**Architecture:** Fresh raw observations feed common Kotlin image-plane measurements. A separate
experimental analyzer consumes those measurements, never display-held poses. Common Compose presents
measurements, calibration/phase/count and uncertainty; Android only bridges camera/session lifecycle.

**Spec:** Owner-approved next milestone in the current task; product-learning-reset sections2–3;
ADR-0006 exploratory boundary. Execute inline with regression-first tests and a single bounded
Claude-work design challenge for cycle semantics. No routine independent implementation review.

## Constraints

- Existing isolated codex/movement-viewer at853192a, clean; preserve historical branches/worktree.
- No push/PR/merge/tag, storage, recordings, uploads, stable interpretation schema or formal study.
- Measurements are projected image-plane estimates, not anatomical truth. Invert scale-pad before
  computing image geometry. Missing/nonfinite/padded/ambiguous joints must not produce measurements.
- Raw observations remain250ms capture-fresh. Display500ms hold is never an analyzer input.
- A UI measurement expiry does not alone prove a capture gap: a fresh result may still be in
  flight. Clear stale measurements immediately without advancing the analyzer; allow a pending
  result only until last accepted capture +250ms capture-gap limit +250ms freshness limit.
  Repeated UI ticks do not renew this deadline. The next fresh sample must still satisfy the
  existing <250ms capture-gap check. Explicit no-pose results invalidate immediately. This derived
  wait is not permission to analyze a 500ms-old pose or bridge actual missing detections.
- Calibration and cycle parameters are experimental and visible; no fixed anatomical top/bottom
  angle is to be invented. Count remains candidate calibrated cycles, not verified full-ROM reps.
- Tracking loss invalidates unfinished cycles. Reacquisition must begin from the calibrated extended
  endpoint. Stop/background/error/source change clears measurements, calibration and counts.
- UI/measurement abstractions remain exercise-independent; pull-up is a manual experimental mode.

## Task1 — Live projected measurements

Files: shared/commonMain/.../viewer/MovementMeasurements.kt, matching commonTest; common UI and
Android MovementViewerActivity presentation in Task3.

- [x] Add `ArmMeasurements(elbowDegrees: Double?, wristHeightFraction: Double?, minimumConfidence: Double?)`
  and `MovementMeasurements(capturedNs: Long, left: ArmMeasurements, right: ArmMeasurements,
  availableArmJoints: Int)`. No event/storage contract.
- [x] Implement `measureMovement(landmarks, width, height, capturedNs)` using ViewerGeometry,
  pixel-space shoulder/elbow/wrist vectors, atan2(abs(cross),dot), wrist height relative to shoulder
  divided by source image height. Retain confidence observations; use an explicitly experimental
  0.5 minimum for both reported presence/visibility, not a validated reliability classification.
- [x] Tests pin literal straight/right-angle/aspect-ratio vectors; missing/duplicate/nonfinite,
  confidence-unknown/low, model padding and zero-length limbs return null. Left/right independent.
- [x] Run shared jvmTest; verify missing-code RED then behavioral mutation of geometry/confidence.

## Task2 — Calibrated provisional cycles

Files: shared/commonMain/.../viewer/experimental/PullUpExperiment.kt, matching commonTest.

- [x] Resolve the single bounded challenge of calibration/phase semantics before production code.
- [x] Claude-work challenge resolved: pinned single side only; raw min/max replaced by min/max of
  a sliding median-of-three sequence to reject isolated spikes. Keep at most600 filtered samples
  for this10s calibration window. Require20-degree observed spread as an explicitly experimental
  signal-usability guard, not ROM validity. Require replayed full high-low-high calibration cycle.
  Countdown5s, calibration10s;20/80% range bands,120ms dwell plus2 distinct samples. These are
  prototype segmentation settings, not validated anatomical thresholds. UI displays them.
  Freeze range/side on confirmation. Reset clears count/range together; missing data never advances.
  Capture timestamps drive progression; UI expiry may invalidate only, never count or finish calibration.
- [x] Per-session operator practice calibration supplies the angle range; never auto-adapt during
  counting. Calibration is not evidence of strict technique. Keep chosen side fixed within a cycle.
- [x] Require ordered extended → flexed → extended sequence with endpoint dwell/hysteresis;
  no missing or stale frame can advance a phase. Count only complete candidate sequences.
- [x] Synthetic trajectories cover complete/partial/jitter/static/loss/reacquisition/duplicate and
  backwards times, source restart and calibration reset; assert event order, not just totals.

## Task3 — Installable experiment

Files: Android preview/MovementViewerActivity.kt and a small preview movement coordinator;
common UI/MovementViewerScreen.kt and movement panel; targeted Android lifecycle/device tests.

- [x] Show both elbow angles, required-joint availability, relative wrist height and caveat.
- [x] Manual pull-up mode, practice calibration instructions and explicit confirmation, candidate
  count/phase/uncertainty, reset. No audio/cloud/classifier or quality score.
- [x] Feed new raw capture timestamps once only; tick expiry independently of UI-held skeleton.
  Clear all experiment state on stop/background/error and switching camera/synthetic input.
- [x] Android/shared tests, both iOS framework builds, make verify, diff/scope inspection.
- [x] Install without clearing app data; synthetic/runtime check and authorized short self-test if
  available. Physical movement accuracy remains unverified until an actual movement is observed.
- [x] Local commit, ignored ledger checkpoint, runnable handoff. Do not reopen infrastructure work.

## Verification and remaining product evidence

The final build passed 235 Android and 436 shared JVM tests with no failures or skips, plus both
iOS framework builds. `make verify` passed 1021 Python tests, Ruff, strict mypy and repository guard.
Geometry and tracking-gap behavioral mutations failed the intended assertions and were restored.
The physical S25 packaged-synthetic test passed, including measurement delivery and background
reset without new observation files. Real calibrated movement-count accuracy remains unverified;
the next product check is an operator practice cycle and short set, not another infrastructure slice.
