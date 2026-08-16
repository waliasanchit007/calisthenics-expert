# ADR-0004: Kotlin Multiplatform Android-First Diagnostic Harness

- Status: Accepted
- Date: 2026-08-16
- Decision owners: Sanchit Walia and the coordinating orchestrator, after Claude Opus 5 architecture challenge

## Context

The original Phase 0 plan proposed independent Android and iOS native harnesses and explicitly deferred shared runtime code. Since then, M0 established platform-neutral schemas, preprocessing contracts, and an executable reference validator. The product direction now requires Kotlin Multiplatform, shared Compose UI, shared business logic, Android-first delivery, and an iOS target that is inexpensive to make functional later.

The first functional device is a Samsung Galaxy S25 Ultra running Android 16. There is no current iOS device acceptance target. The harness remains a measurement instrument, not a product application.

## Decision

- Build the harness with Kotlin Multiplatform and Compose Multiplatform.
- Keep JSON Schema and the Python reference validator authoritative.
- Share contract models, exact preprocessing, lifecycle/counter/finalization logic, capabilities, UI state, and Compose UI.
- Put CameraX, MediaPipe, filesystem, metadata, clock, and thermal operations behind platform ports.
- Deliver a functional Android application first.
- Create `iosArm64` and `iosSimulatorArm64` targets and a minimal Xcode host now, but expose unavailable adapters and disable session start until a later milestone implements iOS capture/inference/storage.
- Use separate KMP `shared` and `composeApp` libraries plus `androidApp` and `iosApp` platform shells. AGP 9 makes the separate Android application module mandatory.
- Release the additive observation-contract fields needed by a truthful harness before Kotlin producers depend on them.

This ADR supersedes the no-KMP repository layout and two-functional-native-harness sequencing in `docs/phase-0-plan.md`. It does not supersede that plan's observation boundary, comparability controls, study design, or prohibition on premature interpretation.

## Consequences

- Android and future iOS implementations share the lifecycle and export semantics most likely to corrupt cross-platform evidence if duplicated.
- iOS compilation continuously detects JVM or Android leakage in shared code without pretending an iOS observation backend exists.
- Platform-native camera and backend APIs remain isolated and replaceable.
- The build is more complex than a single Android application and requires both Gradle and Xcode evidence.
- A separate Android application shell is required; shared UI cannot own Android manifest/application concerns.
- Functional cross-platform equality is not claimed until the iOS adapters process the same fixture sequence.
- The iOS shell is not allowed to emit placeholder or synthetic success artifacts.

## Falsification criteria

Reconsider the shared boundary if one or more of these becomes true:

- platform adapters require duplicated lifecycle, counter, or finalization logic to function;
- shared code repeatedly depends on unstable platform types despite the port boundary;
- Compose Multiplatform prevents the diagnostic UI or native camera preview from meeting measurement needs;
- iOS integration cannot consume the same released contracts without platform-specific semantic exceptions;
- KMP build and release overhead materially delays evidence collection without reducing later iOS work.
