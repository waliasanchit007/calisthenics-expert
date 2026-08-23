# Slice 5 boundary-review fix report

## Status

`DONE`

Implementation commit: `ff7a2fe` (`fix: harden live Android capture boundaries`).

The physical S25 gate was intentionally not run, as required by the fix brief. No progress ledger was edited, and no branch was pushed, merged, or submitted for review.

## Files changed

- Live Android composition, CameraX source/lifecycle, Android thermal port, device gate, and activity wiring:
  - `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`
  - `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/camera/CameraXFrameSource.kt`
  - `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/thermal/AndroidLiveThermalProbe.kt`
  - `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/MainActivity.kt`
  - `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/LiveCaptureDeviceGateTest.kt`
- Shared cadence/evidence/admission/preprocessing/capability implementation:
  - `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveCaptureCadenceDiagnostics.kt`
  - `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidence.kt`
  - `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveFrameAdmission.kt`
  - `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/preprocessing/Yuv420.kt`
  - `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/capability/Capability.kt`
- UI, host checker, and regression coverage:
  - `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/HarnessApp.kt`
  - `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/LiveRunState.kt`
  - Android/shared unit tests, `reference/tests/test_harness_build_contract.py`, `reference/tests/test_live_capture_run_check.py`, and `tools/check_live_capture_run.py`.

## Implemented corrections

### C1 — truthful source geometry

`LiveCameraComposition` now adopts width, height, rotation, and mirroring from the first admitted `OwnedYuvFrame`; it uses that same geometry in the final manifest. A later geometry change is classified as `preprocessing_failed` before inference. Finalization rejects a zero-frame session with `source_geometry_not_observed`, preventing a fabricated orientation or mirror state.

### I2 — requested-versus-delivered cadence

`LiveCaptureCadenceDiagnostics` provides typed delivered callback count, first/last callback timestamps, observed span, requested 30 FPS opportunities using integer arithmetic, the explicitly named upstream estimate, and a separate delivered-callback classification gap. The manifest study context, host evidence renderer, and checker carry this record. The checker validates accounting and timestamp/opportunity consistency, permits a nonzero upstream estimate, and preserves legacy recorded evidence compatibility.

### I3 — negotiated CameraX resolution

`CameraXFrameSource` requests nominal 1280x720 through `ResolutionSelector`/`ResolutionStrategy` with `CLOSEST_HIGHER_THEN_LOWER` fallback. It reports `resolutionInfo.resolution` at the bound source-start boundary. The composition records that negotiated pre-orientation resolution and rejects frames without a matching negotiated source resolution.

### I4 — two-phase frame admission and allocation-safe conversion

`LiveFrameAdmission` now reserves and commits a candidate around preprocessing. The composition makes stopping/backpressure/cadence decisions under its monitor, converts only reserved candidates outside that monitor, and commits exactly one terminal result even if stop races conversion. `Yuv420.kt` no longer allocates a `Pair` per pixel while preserving the BT601/FULL conversion path.

### I5 — CameraX executor lifecycle

`CameraXSourceLifecycle` owns a one-session generation fence. Stop clears the analyzer, unbinds only its owned use case, fences late provider completion, and shuts the analyzer executor exactly once. The provider completion listener uses a direct executor so a late callback cannot enqueue work onto an executor that has been terminated.

### I6 — thermal capability and live telemetry

`THERMAL_API` is again a required live capability. `AndroidLiveThermalProbe` reads `PowerManager` thermal status and subscribes to status changes, recording session-relative observations. Live start fails closed when unavailable; the manifest records the actual device capability and samples. The active UI shows configured camera/inference cadence, delivered/submitted/emitted counts, latest inference latency, and latest thermal status/unavailability. The physical gate now requires at least three seconds of observed capture span and an emitted record within its existing 15-second bound.

## TDD evidence

### RED then GREEN

- C1: the R90/mirroring, geometry-change, and zero-frame regressions first failed against the prior hardcoded/fallback behavior; after implementation, their focused Android unit run passed.
- I2: the cadence manifest regression first failed because `camera_cadence_diagnostics` was absent. The host evidence regression first failed because it rendered the former `unclassified_delivered_callbacks` field. Both focused Kotlin and Python suites passed after the typed diagnostic and checker changes.
- I3: the source contract regression first failed because `ResolutionSelector.Builder()` and negotiated resolution reporting were absent; the Android host checks then passed.
- I4: the large-frame backpressure timing regression first failed on the old convert-before-admit path. The YUV structural guard first failed while `sourceCoordinate` allocated a `Pair` in the pixel loop. Backpressure, cadence, reserve/stop, and golden YUV tests pass with the two-phase path.
- I5: lifecycle/structural regressions first failed before the owned source lifecycle and analyzer shutdown existed; immediate stop, stale completion, normal stop, and repeated stop now pass.
- I6: the capability regression first failed without `THERMAL_API`; the Android structural regression first failed without the thermal port and manifest/UI fields. Thermal-unavailable and session-relative-sample composition tests now pass.

### Mutation evidence

- C1: setting final manifest orientation to `0` failed the R90 finalization regression (line 477); disabling the geometry-change check failed its rejection regression (line 500).
- I2: changing requested camera FPS to `10` failed the cadence-opportunity regression (line 431).
- I3: removing the selector marker failed `test_live_capture_gate_binds_runtime_manifest`.
- I4: bypassing in-flight backpressure failed the large-frame regression at `SessionLedger.kt:112`; the previous per-pixel `Pair` seam was rejected by the structural guard.
- I5: removing `analyzerExecutor.shutdown()` failed `test_camera_source_fences_startup_before_exactly_once_analyzer_shutdown`.
- I6: removing `THERMAL_API` failed `CapabilityTest.liveRequiresCaptureMetadataAndThermalSupportInAdditionToReplay`; forcing `thermalApiAvailable = false` failed `test_live_capture_records_real_thermal_and_cadence_diagnostics`.

All mutation edits were restored before final verification.

## Final verification

All commands below exited zero on the final implementation commit contents.

- `make verify` — 435 reference tests passed; ruff format/check, mypy, contract validation, preprocessing vector verification, and repository guard passed.
- `cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:testDebugUnitTest` — passed.
- `make verify-harness-jvm` — passed (`:shared:jvmTest` and `:androidApp:assembleJvmVerification`).
- `make verify-harness-ios` — passed after replacing JVM-only `Math` with Kotlin/Native-safe integer arithmetic; iOS device/simulator tests and frameworks linked.
- `cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest` — both APK assemblies passed.
- `git diff --check` — passed before commit.

## Self-review and concerns

The final manifest distinguishes observed delivered-callback accounting from the upstream cadence estimate, source dimensions remain pre-orientation, and no thermal quality threshold was introduced. The only observed build output concerns are pre-existing Kotlin hierarchy-template/source-set warnings and Kotlin's non-fatal unnecessary-`!!` warnings in the physical device test. Physical-device evidence remains intentionally uncollected for the controller-owned S25 gate.
