# Bounded-Quantization Synchronization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover the physical Task 4 synchronized-video gate by adding a timebase-derived bounded-quantization profile without changing released exact-offset semantics.

**Architecture:** Keep the v1.0 exact profile intact and add a discriminated v1.1 bounded profile. Android reads both MP4 track timescales from finalized bytes, Kotlin emits derived evidence, Python independently recomputes admission, and a controlled S25 probe distinguishes on-grid exactness from off-grid quantization.

**Tech Stack:** JSON Schema Draft 2020-12, Python 3.14/pytest, Kotlin Multiplatform, Android MediaCodec/MediaMuxer/MediaExtractor, ISO BMFF `mdhd`, ADB device gates.

**Spec:** `docs/superpowers/specs/2026-09-02-bounded-quantization-synchronization-design.md`

## Global Constraints

- Preserve all released v1.0.0 exact-profile artifacts and semantics.
- Derive every tolerance from a recorded positive track timebase using integer arithmetic.
- Never bound absolute offset magnitude.
- Physical evidence must be non-participant, sanitized, device/app/model/probe bound, and emitted through instrumentation status.
- The device command prints typed evidence and never edits its ledger.
- No PR, merge, tag, deletion, or cleanup is part of this recovery plan.

---

### Task 1: Correct session-relative path sanitization

**Files:**
- Modify: `reference/tests/test_synchronized_video_run_check.py`
- Modify: `tools/check_synchronized_video_run.py`

**Interfaces:**
- Consumes: artifact `*_path_ref` strings from the typed device record.
- Produces: `assert_sanitized(record)` that accepts relative session paths and rejects absolute paths.

- [x] Add a failing test that accepts `exports/<uuid>/video.mp4` and parameterized failures for `/data/...`, `file:///data/...`, `C:\\data\\...`, and `C:/data/...`.
- [x] Run only the sanitizer tests and observe the embedded-slash case fail with the current unanchored search.
- [x] Anchor the absolute-path matcher to the start of the value; do not weaken forbidden absolute forms.
- [x] Run focused checker tests and Ruff; commit separately. Direct strict mypy remains blocked by the checker's pre-existing unparameterized-`dict` debt and is deferred to the authoritative repository gate.

### Task 2: Add bounded-profile structural and semantic RED tests

**Files:**
- Modify: `reference/tests/test_synchronization_contract.py`
- Modify: `reference/tests/test_committed_fixtures.py`
- Create: `fixtures/synthetic/valid/synchronization_bounded/`
- Create: one-purpose negative fixtures under `fixtures/synthetic/negative/`

**Interfaces:**
- Consumes: `validate_document` and `_synchronization_issues` through public session validation.
- Produces: executable examples of exact legacy and bounded v1.1 behavior.

- [x] Add a valid 90 kHz off-grid fixture with a 9,000 ns interval and a 24,223 ns derived bound.
- [x] Preserve and assert the existing exact v1.0 fixture unchanged.
- [x] Add a sub-tick middle-sample perturbation derived from `1_000_000 / f` that remains valid.
- [x] Add the first integral-microsecond perturbation above the formula-derived bound and assert `SESSION.SYNC_CLOCK_MAPPING`.
- [x] Add missing/zero/inconsistent-timebase and unknown-profile tests with new stable codes.
- [x] Run focused tests and observe failures caused by absent bounded-profile support.

### Task 3: Release synchronization evidence v1.1.0

**Files:**
- Modify: `contracts/schema/synchronization_evidence.v1.schema.json`
- Modify: `contracts/tooling/validator_error_codes.v1.json`
- Modify: `contracts/CHANGELOG.md`
- Modify: `reference/src/observation_contracts/semantic.py`

**Interfaces:**
- Consumes: exact or bounded synchronization evidence.
- Produces: profile-dispatched structural and semantic admission while retaining exact v1.0 behavior.

- [x] Add `sync_profile`, track timebases, offset interval, and derived span bound to the v1 schema using profile-specific conditional requirements.
- [x] Register `SESSION.SYNC_PROFILE_UNSUPPORTED` and `SESSION.SYNC_TIMEBASE_INVALID`; retain `SESSION.SYNC_CLOCK_MAPPING` for a mathematically invalid mapping.
- [x] Implement `ceil_div`, lower-median offset, and the integer derived-bound formula.
- [x] Dispatch exact evidence through the unchanged equality rule and bounded evidence through the new interval/bound rule.
- [x] Run focused structural/semantic tests, committed fixtures, registry guards, Ruff, and strict mypy; commit.

### Task 4: Add Android MP4 timescale observation and bounded evidence

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/video/Mp4TrackTimescaleReader.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/video/Mp4TrackTimescaleReaderTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/video/VideoDemuxVerifier.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/SynchronizationEvidence.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/SynchronizationEvidenceTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`

**Interfaces:**
- Produces: `TrackTimescales(videoTicksPerSecond, metadataTicksPerSecond)` from finalized MP4 `mdhd` boxes and bounded evidence derived from those values.
- Consumes: finalized video descriptor/bytes and full demuxed sample pairs.

- [x] Add RED parser tests for version-0/version-1 `mdhd`, two equal tracks, truncation, missing track, zero, and inconsistent timescales.
- [x] Implement a bounded ISO BMFF box reader; it must not scan beyond declared file/box lengths.
- [x] Replace the literal 1 us mid-sequence drift test with derived sub-tick acceptance and derived over-bound rejection.
- [x] Add Kotlin bounded-profile derivation/rendering and keep legacy exact helpers available for v1.0 tests.
- [x] Wire finalized track timescales into the Task 4 evidence builder; fail before publication when unavailable or inconsistent.
- [x] Run focused JVM/Android tests and named mutations; commit.

### Task 5: Add controlled S25 on-grid/off-grid probe

**Files:**
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/ContainerTimestampProbe.kt`
- Modify: its Android instrumentation test and host checker tests.
- Modify: `tools/check_container_timestamp_probe.py`
- Modify: the Task 3 probe ledger/evidence under `docs/evidence/device-probes/` only after a successful run.

**Interfaces:**
- Produces: one typed observation containing common track timescales, exact on-grid samples, and bounded non-exact off-grid samples.

- [x] Add RED host and Kotlin tests requiring both controlled sequences and exact identity binding.
- [x] Emit on-grid timestamps aligned to the observed track tick grid and deliberately off-grid timestamps.
- [x] Recompute exact/on-grid and bounded/off-grid verdicts on the host; reject vacuous exact off-grid output.
- [x] Run the physical S25 control, validate exit status, manually record the typed result, and verify its ledger.

### Task 6: Recover the synchronized publication gate

**Files:**
- Modify only files required by evidence formatting/checking discovered through RED tests.
- Update: ignored SDD ledger after every atomic step.

**Interfaces:**
- Consumes: admitted controlled probe and exact candidate application/test APKs.
- Produces: one published four-artifact synchronized session accepted by the host validator.

- [ ] Run the complete mutation matrix; each intended semantic mutation must fail through the named semantic assertion.
- [ ] Run `make verify`, `make verify-harness-jvm`, Android instrumentation assembly, and `make verify-harness-ios`.
- [ ] Inspect the exact diff and verify clean local/remote checkpoint identity.
- [ ] Run `make verify-harness-s25-synchronized-video` on exactly one authorized physical S25.
- [ ] Require successful binary-safe transfer, four artifact digest/length matches, probe binding, and released session validation.
- [ ] Record typed physical evidence and only then mark Task 4 complete and begin Task 5b.
