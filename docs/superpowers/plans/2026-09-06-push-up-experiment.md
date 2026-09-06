# Push-up experiment implementation plan

**Goal:** An installable manual push-up experiment alongside the working pull-up baseline.
**Architecture:** Typed profile over the existing common arm-cycle logic; stopped-session selector;
movement-bound local diagnostics and exact replay. No new camera/model/storage subsystem.
**Tech stack:** Kotlin Multiplatform, Compose, existing CameraX/MediaPipe adapters, Python collector.
**Spec:** `docs/superpowers/specs/2026-09-06-push-up-experiment-design.md`.

Execute directly with focused TDD and a bounded Claude design challenge; the owner's operating
policy overrides routine per-task review and repeated routine-choice approval ceremonies.

## Constraints

Preserve baseline 306323b and the existing isolated codex/movement-viewer worktree. Do not change
pull-up thresholds, recording scope or stable contracts. Never start the real camera remotely.
No push, PR, merge, tag or cleanup. Private replay remains outside Git. Exit codes and executed
test reports, not matching log text, establish software-check results.

## 1. Common profile and counting

- [x] Complete one read-only Claude design challenge and resolve Important design findings.
- [x] Add common synthetic tests next to PullUpExperimentTest using explicit below-shoulder
  wrist measurements. First prove default pull-up rejects that practice; then exercise PUSH_UP.
  Cover full practice -> READY(count0) -> confirmation -> extended/flexed/extended(count1),
  partial return(count0), loss midway(count unchanged), fresh complete cycle after loss(count+1),
  zero/wrong-sign wrist, timestamps and reset. No helper may transform real raw measurements.
- [x] Observe RED using `:shared:jvmTest --tests '*PushUpExperimentTest*'`; implement the minimal
  profile. Keep all existing pull-up tests. Record named failures and nonzero executed test count.

## 2. Selection, guidance and trace identity

- [x] Add regressions for stopped-session movement switching/reset and active-session rejection;
  rendered push-up instructions must describe lowering then returning to extended, not a bar.
  Use ViewerMovementCoordinatorTest and MovementSetupUiTest patterns.
- [x] Add logger/replay regressions: emitted PUSH_UP is replayed as PUSH_UP; absent historical
  identity remains PULL_UP; unknown/null/mixed identity fails. Real logger bytes must be checked.
- [x] Implement the selector through MovementViewerScreen/MovementMeasurementsPanel and
  MovementViewerActivity/ViewerMovementCoordinator. Keep session options and consent intact.
  Extend ViewerDiagnosticLog, ReplayMovementTrace and collector allowlists where required.
- [x] Run focused shared and Android host tests. Verify the new tests fail before production changes.

## 3. Integration and runnable handoff

- [x] Replay the latest private pull-up baseline using the actual Kotlin analyzer;
  preserve increments at seq 721,1324,1665,2085. No personal fixture is committed.
- [x] Run `make verify`; run shared JVM, shared Android host and Android app unit tests; build
  Android debug APK/test APK and both existing iOS framework targets. Inspect exact diff/scope.
- [x] Run synthetic UI selection/confirmation tests on the available emulator. Confirm physical
  S25 identity before installation; never clear app data or interrupt an active self-test.
- [x] Prepare the local implementation checkpoint and ignored ledger with exact build/test results.
  Delivery installation and the final commit SHA are recorded in that ledger after packaging.
  Actual push-up accuracy stays unverified until paired observations exist; the formal study gate
  stays blocked. No integration or release is authorized.

## Operator handoff

Select Push-up while stopped, then start the camera. Use a fixed side view with the whole body
and chosen arm visible. Wait for the practice cue; lower, pause briefly at the bottom, return
and hold extended arms. Confirm the full practice, then try 3–5 normal push-ups. Practice is
not counted. Local feedback identifies PUSH_UP so those attempts cannot be confused with pull-ups.

## Software evidence

`make verify`: 1044 Python tests, Ruff and strict mypy (21 files), exit 0. Integrated Gradle:
479 shared JVM, 496 shared Android host, 261 Android app unit tests; Android APKs and both iOS
debug frameworks built. Six synthetic emulator UI tests passed. Pull-up baseline exact count
increments retained. A deliberate per-frame wrist-sign mutation failed the named bottom-crossing
test; restoring the setup-only rule restored GREEN. Real push-up self-test remains pending.
