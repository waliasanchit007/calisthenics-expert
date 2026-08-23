# Harness Slice 6 Integration Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the six Important findings from the mandatory final full-range diagnostic-harness review so a pose-bearing live session is truthful, runtime failures remain typed, physical evidence is revision-bound, protected CI covers the implemented Kotlin surface, and the repository guard has no 64 KiB content bypass.

**Architecture:** Preserve the approved observation contracts and existing KMP boundaries. Derive manifest availability from sealed records at finalization; carry CameraX source failures as typed completion state; make recorded evidence revalidation use the same complete predicates as live transcript admission and bind every physical observation to its producer SHA; expand the existing protected `verify` job rather than creating a second job; scan prohibited content incrementally across every byte while retaining bounded memory.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, CameraX, MediaPipe Tasks Vision, Python 3.12+, pytest, Ruff, strict mypy, GNU Make, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md`

## Global Constraints

- Stay inside the Phase 0 observation-only boundary. Do not add reliability thresholds, canonicalization, movement events, rep counting, form scoring, participant data, functional iOS capture, or study execution.
- Follow regression-first TDD for every behavior change: add the test, run it and observe the intended failure, implement the minimum change, then rerun it green.
- `SessionRunner` remains the only authority for lifecycle, counters, frame classification, submission indices, records, inference failures, drain, and sealing.
- A source-contract failure must be visible as a typed internal failure and must make the S25 live evidence gate block; it must not be converted into ordinary user stop.
- `available_observations` is derived from the sealed records written in the same export. It is never caller-declared independently of those records.
- Evidence records contain no landmark coordinates, participant data, credentials, or identity mappings.
- Physical evidence must name the exact 40-lowercase-hex producer Git SHA. Evidence commits may follow that producer commit; never use the later evidence commit as a self-reference.
- Never retroactively invent a producer SHA for an old device observation. Mark evidence pending until the relevant gate is rerun.
- Every recorded ledger opens its referenced evidence file and verifies the exact SHA-256 before trusting fields from it.
- Protected CI retains one required job id, `verify`, so existing branch protection remains satisfiable.
- Full-content guarding must use bounded-memory streaming and must detect a prohibited signature split across chunk boundaries.
- Model binaries remain fetched and SHA-verified; none enter Git.
- Only Critical and Important findings block. Carry the two review Minors as explicit debt rather than broadening this fix wave.

---

### Task 1: Truthful live manifests and typed CameraX source failures

**Files:**
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/LiveCameraCompositionTest.kt`
- Modify only if the existing fake cannot express pose output: the nearest existing live test fixture in the same test source set

**Interfaces:**
- Consumes: `SealedSessionSnapshot.records`, whose records carry nullable `normalizedImageLandmarks` and `worldLandmarks`.
- Produces: `SessionManifest.availableObservations` derived as `records.any { family != null }` for each family.
- Produces: `LiveInternalFailure.SourceFailure(code: String)` or an equivalently narrow typed variant, surfaced by both `LiveCameraComposition.internalFailure` and `LiveCameraCompletion.internalFailure`.

- [x] **Step 1: RED — pose-bearing live export reconciles availability**

Add a focused `LiveCameraCompositionTest` that drives one successful live inference result containing non-null normalized and world landmark lists, stops the session, captures the finalized manifest through the existing fake finalizer, and asserts:

```kotlin
assertTrue(finalizedManifest.availableObservations.normalizedImageLandmarksAvailable)
assertTrue(finalizedManifest.availableObservations.worldLandmarksAvailable)
```

Also retain a no-pose case asserting both remain false. Run the two focused tests and observe the pose-bearing case fail because the manifest currently hardcodes `false, false`.

- [x] **Step 2: GREEN — derive availability from sealed records**

Inside the existing `manifestAtFinalization` callback, set:

```kotlin
availableObservations = AvailableObservations(
    normalizedImageLandmarksAvailable = sealed.records.any { it.normalizedImageLandmarks != null },
    worldLandmarksAvailable = sealed.records.any { it.worldLandmarks != null },
)
```

Do not infer availability from construction metadata or from `posePresent` alone. Rerun the focused tests green.

- [x] **Step 3: RED — CameraX runtime failure remains typed**

Add a focused test that starts successfully, supplies source geometry, invokes the source's `onFailure` callback with a stable test exception, completes drain/finalization, and asserts both the composition and completion expose the stable typed code `live_source_failed`. Observe it fail because `handleSourceFailure` currently discards the throwable and requests a normal stop.

- [x] **Step 4: GREEN — store and publish the source failure identity**

Add one monitor-confined source-failure field. The first runtime source failure wins; duplicate callbacks do not overwrite it. `handleSourceFailure` records `LiveInternalFailure.SourceFailure("live_source_failed")` before requesting stop. The existing inference-stall identity remains unchanged and no throwable message enters evidence. `internalFailure` and final completion return the recorded source failure, otherwise the existing stall failure, otherwise null.

- [x] **Step 5: Verify and commit**

Run the focused Android unit tests, then:

```bash
cd apps/diagnostic-harness
./gradlew --no-daemon :androidApp:testDebugUnitTest
```

Commit only Task 1 files with subject `fix: make live session outcomes truthful`.

### Task 2: Complete and revision-bound physical evidence admission

**Files:**
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidence.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/replay/FiveRunReplayEvidence.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/replay/ProcessKillEvidence.kt`
- Modify: their existing `commonTest` evidence tests
- Modify: Android instrumentation call sites that construct these three records
- Modify: `tools/check_live_capture_run.py`
- Modify: `reference/tests/test_live_capture_run_check.py`
- Modify: `reference/tests/test_device_evidence_ledger.py`
- Modify: replay/process-kill checker modules only where needed to validate the producer SHA
- Modify: `docs/evidence/live-capture/s25-ultra-live-capture-ledger.json`
- Modify: `docs/evidence/replay-five-run/s25-ultra-five-run-replay-ledger.json`
- Modify: `docs/evidence/device-probes/s25-ultra-process-kill-ledger.json`
- Modify after physical reruns: the three referenced evidence JSON records and ledgers above

**Interfaces:**
- Consumes: `BuildConfig.PROBE_GIT_SHA`, already produced as a 40-hex commit SHA by the Android build.
- Produces: top-level `harness_git_sha` in live, five-run replay, and process-kill evidence records.
- Produces: recorded-ledger verification that recomputes every intrinsic and external gate from persisted fields.

- [x] **Step 1: RED — recorded live evidence rechecks external gates**

Add two parametrized tests that write an otherwise admitted record with, respectively, `reference_validation_succeeded=false` and `instrumentation_succeeded=false`, recompute the file digest in the ledger, and assert `verify_recorded_ledger` rejects the record because its persisted blocking reasons/verdict no longer match. Observe both fail because recorded verification currently calls only `_intrinsic_blocking_reasons`.

- [x] **Step 2: GREEN — one persisted-record predicate**

Add a helper that recomputes persisted blocking reasons from `_intrinsic_blocking_reasons(record)` plus:

```python
if record.get("reference_validation_succeeded") is not True:
    reasons.append("reference_validation_failed")
if record.get("instrumentation_succeeded") is not True:
    reasons.append("instrumentation_failed")
```

Use it in `verify_recorded_ledger`; preserve deterministic de-duplication order. Rerun the focused Python tests green.

- [x] **Step 3: RED — every physical record and ledger is producer-bound**

Add Kotlin render tests requiring `harness_git_sha` in all three evidence records and rejecting values outside `[0-9a-f]{40}`. Add Python tests that reject a recorded evidence file with a missing/malformed SHA. Add ledger tests for replay and process-kill that mutate the referenced evidence bytes without updating `evidence_sha256` and assert failure. Observe the tests fail against the current records/checkers.

- [x] **Step 4: GREEN — propagate and verify producer SHA**

Add `harnessGitSha: String` to the three evidence types, validate it at construction, render `harness_git_sha`, and pass `BuildConfig.PROBE_GIT_SHA` from each instrumentation producer. Make each checker require the exact 40-hex field. Make replay and process-kill ledger tests compute and compare the referenced file's actual SHA-256, matching the already-correct camera/live pattern.

- [x] **Step 5: Invalidate old unbound observations without inventing provenance**

Before committing the producer change, change the three affected ledgers to `status: "pending"`, retain a precise `missing_precondition` naming the S25 rerun, and remove recorded-only verdict/hash fields as required by their existing pending schema. Do not edit the old evidence files to insert guessed SHAs.

- [x] **Step 6: Verify and commit the host-side gate**

Run the focused Kotlin evidence tests, focused Python evidence/ledger tests, `make verify`, and `make verify-harness-jvm`. Commit with subject `fix: bind device evidence to its producer`.

### Task 3: Put the implemented Kotlin surface under protected CI

**Files:**
- Modify: `Makefile`
- Modify: `.github/workflows/verify.yml` only if the Make target cannot preserve the existing workflow shape
- Modify: `reference/tests/test_ci_gradle_cache_ordering.py`
- Modify: `reference/tests/test_harness_build_contract.py`

**Interfaces:**
- Consumes: the existing protected job id `verify` and `make verify-harness-jvm` workflow step.
- Produces: a Linux-compatible JVM/Android gate containing shared JVM tests, shared Android host tests, Android app unit tests, and non-delivery Android assembly.

- [x] **Step 1: RED — pin protected Kotlin coverage mechanically**

Add repository-contract tests asserting that `verify-harness-jvm` invokes all of:

```text
:shared:jvmTest
:shared:testAndroidHostTest
:androidApp:testDebugUnitTest
:androidApp:assembleJvmVerification
```

and that `.github/workflows/verify.yml` still runs `make verify-harness-jvm` inside job `verify`. Observe failure for the two missing unit-test tasks.

- [x] **Step 2: GREEN — expand the existing Make target**

Add the two missing Gradle tasks to `verify-harness-jvm`. Do not rename the Make target, workflow, or job. Do not add iOS to Ubuntu. Rerun the contract tests and execute `make verify-harness-jvm` locally.

- [x] **Step 3: Commit**

Commit Task 3 files with subject `ci: protect Android harness unit tests`.

### Task 4: Remove the repository guard's 64 KiB content bypass

**Files:**
- Modify: `reference/src/observation_contracts/repository_guard.py`
- Modify: `reference/tests/test_repository_guard.py`

**Interfaces:**
- Consumes: `_RepositoryContent.iter_bytes(relative_path)`, which already streams tracked files and staged Git blobs in 64 KiB chunks.
- Produces: bounded-memory prohibited-content scanning over every byte, preserving enough overlap to match a signature split across adjacent chunks.

- [x] **Step 1: RED — signatures after and across the old cutoff are rejected**

Replace the existing test that permits a private-key marker after 64 KiB with two parametrized staged/tracked regression cases:

1. benign bytes longer than 64 KiB followed by a complete private-key marker;
2. a private-key or `client_secret` marker split across the 64 KiB chunk boundary.

Assert `REPOSITORY.PROHIBITED_CONTENT` for both. Observe failure against prefix-only scanning.

- [x] **Step 2: GREEN — stream all content with bounded overlap**

Keep `read_prefix` for strict JSON descriptors and symlink targets. For ordinary non-fixture content, add a streaming scanner over `iter_bytes`. Carry a tail at least as long as the longest prohibited literal/regex span needed by `_PRIVATE_KEY_RE`, `_SECRET_CONTENT_RE`, and `_POSE_CONTENT_RE`; scan `tail + chunk`, then retain only that bounded tail. Return on the first finding. Do not accumulate entire files and do not scan admitted raw fixture payloads as secrets.

- [x] **Step 3: Verify and commit**

Run `reference/tests/test_repository_guard.py`, then `make verify`. Commit with subject `fix: scan complete repository blobs for secrets`.

## Slice acceptance and scoped re-review

- [x] At the final implementation producer SHA, rerun the S25 process-kill, five-run replay, and live-capture gates. Record sanitized outputs exactly as emitted, set each ledger back to `recorded`, compute each evidence SHA-256 from bytes, and keep `harness_git_sha` equal to the producer commit—not the later evidence commit. If the S25 is unavailable, stop with the three ledgers honestly pending; do not weaken the gate.
- [x] Run `make verify`.
- [x] Run `make verify-harness-jvm`.
- [x] Run `make verify-harness-ios`.
- [x] Run `make verify-kotlin-export`.
- [x] Run `cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:allTests :androidApp:testDebugUnitTest`.
- [x] Inspect `git diff --check 4618bfa..HEAD`, changed-file scope, and tracked-tree guard.
- [x] Run exactly one scoped re-review against the six Important findings. It must verdict each `ADDRESSED` or `NOT ADDRESSED` and may report new Critical/Important breakage only inside the fix diff.
- [x] Record the two existing Minors as deferred debt: zero-frame live finalization disagrees with design; pre-base capture timestamp is collapsed to generic preprocessing failure.
- [ ] Push the branch and open a protected PR only after host gates and scoped re-review pass.
- [ ] Wait for required `verify` to be green. Merge remains release-owner authority.
