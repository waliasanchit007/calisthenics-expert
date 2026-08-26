# Task 6 report — DONE_WITH_CONCERNS (coherent first commit)

## Implementation summary

This first commit establishes versioned common `PipelineStageEvent` and `FixedTelemetrySample` NDJSON contracts, typed `UNAVAILABLE(code)` values, bounded per-stage/disposition counts, a one-second sampler abstraction with cancel/join/terminal-sample ordering, an Android snapshot probe, and a `SessionExporter` telemetry sink that writes directly to Task 2's durable telemetry artifact.

It deliberately does not change `SessionManifest`: Task 3's contract release remains hardware-gated. Task 6 artifacts must be bound by Tasks 7/9.

## Exact RED/GREEN evidence

- RED: `./gradlew --no-daemon :shared:jvmTest --tests com.calisthenics.diagnostic.telemetry.PipelineTelemetryTest` failed with unresolved telemetry contracts/recorder/sampler.
- GREEN: the same command passed after adding `PipelineTelemetry.kt`.
- RED: `./gradlew --no-daemon :shared:jvmTest --tests com.calisthenics.diagnostic.export.SessionExporterTest.typedTelemetryIsAppendedToTheDurableTelemetryStreamImmediately` failed with `ClassCastException` because `SessionExporter` was not a `TelemetrySink`.
- GREEN: the same command passed after wiring `SessionExporter.append` to `DurableSessionWriter.appendTelemetry`.
- RED: `./gradlew --no-daemon :androidApp:testDebugUnitTest --tests com.calisthenics.diagnostic.telemetry.LiveTelemetryControllerTest` failed with unresolved controller/probe types.
- GREEN: the same command passed after adding `LiveTelemetryController`.

## Mutation evidence

Executed in the common telemetry test suite:

- clock regression mutation: lowering the clock from `2` to `1` makes the sampler throw rather than append a reordered sample;
- unavailable-as-zero mutation: encoded unavailable thermal/battery fields are asserted not to become `VALUE(0)`.

Not yet executed: required mutations for every closed callback disposition, timestamp tolerance, durable telemetry sequence/timestamp reconciliation, concurrent append-vs-seal, post-dispose thermal callback, sustained-mode bounded counters, and 10/15/30 attribution.

## Verification commands/results

- `make verify` — passed: Ruff format/check, mypy, 611 Python tests, fixture/guard checks, and expected participant-capture blocking result.
- `make verify-harness-jvm` — passed: shared JVM/Android-host tests, Android unit tests, and `assembleJvmVerification`.
- No participant capture and no physical S25 run were performed.

## Files changed

- `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/telemetry/PipelineTelemetry.kt`
- `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/telemetry/PipelineTelemetryTest.kt`
- `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/SessionExporter.kt`
- `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/SessionExporterTest.kt`
- `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveTelemetryController.kt`
- `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/telemetry/AndroidFixedTelemetryProbe.kt`
- `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/telemetry/LiveTelemetryControllerTest.kt`

## Self-review

The contracts keep stage-count memory bounded and persist records through the existing durable telemetry artifact. The Android probe does not substitute zero, false, null, or negative values when an API is unavailable. Camera request is untouched, so `CameraXFrameSource.TARGET_CAMERA_FPS` remains 30.

## Concerns

This is not the complete binding-ruling implementation. It lacks: the closed delivered-callback lattice in `LiveCameraComposition`; a dedicated sampler thread; callback/inference/Task-4 encoder/sampler finalization fence; thread-safe telemetry append versus sealing with periodic flush/fsync and streaming reconciliation; post-dispose thermal callback fencing; short-versus-sustained attribution modes; timestamp-tolerance conformance; and 10/15/30 attribution evidence. Those gaps make this a coherent first commit only, not acceptance of Task 6.
