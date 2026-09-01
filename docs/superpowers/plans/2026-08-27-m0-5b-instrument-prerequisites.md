# M0.5B Instrument Prerequisites Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Use `superpowers:test-driven-development` for every behavior change. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce durable, synchronized, purge-rehearsable, fixed-envelope non-participant instrument evidence on the authorized S25 Ultra while participant capture remains mechanically blocked.

**Architecture:** A canonical registry defines every storage location. A bounded common-Kotlin writer streams session artifacts into one Android staging attempt and publishes them atomically; short runs add in-app video and synchronization evidence, while sustained runs add fixed-interval telemetry without video. Python contracts and validators admit only digest-bound evidence, and physical-device commands emit typed records for explicit ledger recording.

**Tech Stack:** Kotlin 2.4.10, Kotlin Multiplatform, Compose Multiplatform, AGP 9.1.0, Gradle 9.3.1, JDK 21/JVM target 17, CameraX 1.5.3, MediaPipe Tasks Vision 1.0.0, Android MediaCodec/MediaMuxer/MediaExtractor, Python 3.12+, JSON Schema Draft 2020-12, pytest, Ruff, strict mypy, Samsung Galaxy S25 Ultra SM-S938 family on Android 16/API 36.

**Spec:** `docs/superpowers/specs/2026-08-24-m0-5b-instrument-prerequisites-design.md`

## Global Constraints

- Every physical run is `live_conformance`, uses the reserved UUID form `^00000000-0000-4000-8000-[0-9a-f]{12}$`, carries the `synthetic-m05-` pseudonym class, a positive no-person attestation, and an admitted non-person input class.
- Participant capture, privacy-decision acceptance, M0.5C, iOS implementation, mid-range implementation, thresholds, rep counting, network upload, paid services, and tags are out of scope.
- Camera cadence remains 30 FPS. Sustained inference cadences are exactly 10, 15, and 30 FPS for exactly 1,500 session-clock seconds, with at most one second of stop-request overshoot.
- Every per-frame or per-submission in-memory collection is bounded independently of session length.
- The manifest is the final commit record. No committed session appears before every payload is reconciled, hashed, re-read, fsynced, and renamed once.
- Every artifact digest is SHA-256 over the exact referenced file bytes. Canonical JSON writers define those bytes; parsed-object reserialization is never a digest substitute, and an artifact does not hash a digest field inside itself.
- Video is enabled only for short synchronization/purge runs. Sustained characterization records `video_enabled=false`.
- The sustained-run target never runs `pm clear` between attempts. A durably committed session remains authoritative even if its Wi-Fi transcript is lost; only a proven `ABORTED` or `FAILED` attempt permits a same-cadence retry.
- Device runs are observations. A command may exit non-zero after successfully emitting a typed blocking record; no contract is narrowed to force green.
- Every gate conjunct ships with a mutation that must turn its behavioral test red. Success is an exit code, never grepped output.
- Run `make fetch-model-asset` in every fresh worktree before an Android delivery build. Keep `apps/diagnostic-harness/local.properties` checkout-local and ignored.
- Device tasks are serialized. Parallel work is allowed only for disjoint write scopes in the accepted DAG.

---

### Task 1: Canonical storage registry and Android backup exclusion

**Files:**
- Create: `contracts/tooling/storage_location_registry.v1.schema.json`
- Create: `docs/protocols/storage-location-registry.m05b.v1.json`
- Create: `reference/src/observation_contracts/storage_registry.py`
- Create: `reference/tests/test_storage_registry.py`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/StorageLocationRegistry.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/StorageLocationRegistryTest.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/res/xml/data_extraction_rules.xml`
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/StorageExclusionDeviceGateTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/StorageExclusionEvidence.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/StorageExclusionEvidenceTest.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/StoragePort.kt`
- Modify: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/export/AndroidStorageRoots.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/AndroidManifest.xml`
- Modify: `reference/src/observation_contracts/repository_guard.py`
- Modify: `reference/tests/test_repository_guard.py`
- Modify: `reference/src/observation_contracts/schema_loader.py`
- Modify: `reference/tests/test_structural.py`
- Modify: `Makefile`

**Interfaces:**
- Produces:

```kotlin
enum class StorageLocationId {
    DEVICE_EXPORT, DEVICE_STAGING, DEVICE_TRANSFER_STAGING,
    STUDY_STORE_SOURCE, STUDY_STORE_DERIVED, STUDY_STORE_REPORT, TOMBSTONE,
}

data class StorageLocation(
    val id: StorageLocationId,
    val owner: StorageOwner,
    val relativeRoot: String,
    val artifactClasses: Set<ArtifactClass>,
    val publishable: Boolean,
    val devicePurge: Boolean,
    val hostPurge: Boolean,
    val cloudBackup: BackupDisposition,
    val deviceTransfer: BackupDisposition,
    val tombstoneEligible: Boolean,
    val postPurgeScan: Boolean,
)

object StorageLocationRegistry {
    const val VERSION: String = "1.0.0"
    val entries: List<StorageLocation>
    fun androidChild(id: StorageLocationId): String
}
```

- Extends `StorageRoots` with one normalized `diagnosticHarnessRoot` plus registry-derived staging, export, transfer-staging, and tombstone roots.
- Python `load_storage_registry(path: Path) -> StorageRegistry` validates schema, exact seven-location identity, owner/disposition vocabulary, and canonical SHA-256.
- `renderStorageExclusionEvidence` derives admissibility from observed manifest/resource/device fields; callers cannot pass a success boolean.

- [ ] **Step 1: Write RED contract and registry tests**

Add literal seven-entry fixtures. Assert exact ID equality, unique normalized relative roots, all Android roots beneath `diagnostic-harness`, tombstone excluded from purge, and cloud/D2D exclusion for every Android root.

- [ ] **Step 2: Verify RED**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_storage_registry.py reference/tests/test_structural.py -q
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest --tests '*StorageLocationRegistryTest'
```

Expected: Python fails because the schema/module is absent; Kotlin fails because `StorageLocationRegistry` is unresolved.

- [ ] **Step 3: Implement the canonical schema, data, and language projections**

Implement the interfaces above. Keep the JSON registry authoritative; Python reads it directly, while Kotlin's closed projection is checked against a packaged canonical JSON fixture byte-for-byte by ID and field value.

- [ ] **Step 4: Add backup/root-factory behavioral gates**

Parse `AndroidManifest.xml` and `data_extraction_rules.xml`; prove both cloud and device-transfer domains exclude the complete `diagnostic-harness` parent. Extend the repository guard so direct `filesDir`, `getDir`, or literal `diagnostic-harness` construction outside `AndroidStorageRoots.kt` fails.

- [ ] **Step 5: Add the physical exclusion evidence command**

Add `make verify-harness-s25-storage-exclusion`. It requires exactly one authorized non-emulator S25, executes `pm clear` before the probe, emits one typed record through instrumentation status, prints it, and never edits the ledger.

- [ ] **Step 6: Run mutation checks**

Temporarily remove each XML domain, narrow the parent path, add an eighth unregistered Android root, bypass the factory, and mark tombstones purgeable. Each targeted test must fail for the named reason; restore after each mutation.

- [ ] **Step 7: Verify and commit**

Run `make verify`, `make fetch-model-asset && make verify-harness-jvm`, `git diff --check`, then commit:

```bash
git add contracts/tooling/storage_location_registry.v1.schema.json docs/protocols/storage-location-registry.m05b.v1.json reference apps/diagnostic-harness Makefile
git commit -m "feat: register and exclude M0.5B storage roots"
```

---

### Task 2: Bounded durable session streaming

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/DurableSessionWriter.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/DurableWriterTypes.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/export/DurableSessionWriterTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/session/BoundedSessionStateTest.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/StoragePort.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/export/SessionExporter.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/SessionLedger.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/session/SessionRunner.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveAchievedCadence.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`
- Modify: all storage test harnesses and conformance suites under `shared/src/*Test/.../export/`

**Interfaces:**
- Consumes Task 1 registry-derived roots.
- Produces:

```kotlin
enum class DurableWriterState { NEW, OPEN, SEALING, PUBLISHED, ABORTED, FAILED, UNCERTAIN_DURABILITY }

data class DurableCheckpoint(
    val lastDurableRecordIndex: Long,
    val counts: Counts,
    val posePresentCount: Long,
    val digestState: Sha256State,
)

interface DurableSessionWriter {
    val state: DurableWriterState
    fun open()
    fun appendPose(record: RawPoseFrame)
    fun appendTelemetry(encodedRecord: String)
    fun seal(finalAggregates: FinalSessionAggregates): SealedArtifacts
    fun abort(reason: String, counts: Counts)
}
```

- Storage adds named binary/text artifact append, checkpoint replacement, byte-length/digest reconciliation, and fsync operations without exposing platform paths to common code.
- `SessionRunnerSnapshot` and `SealedSessionSnapshot` retain counts, monotone availability, one failure, bounded cadence buckets, current submission, and high-water marks—never full record/terminal collections.

- [ ] **Step 1: Write RED equivalence and boundedness tests**

Use the existing committed fixtures as literal oracles for availability and achieved-cadence bytes. Feed 1,000 and 100,000 synthetic frames and assert identical collection cardinalities, one in-flight submission, one failure maximum, and no `records`, `terminalMap`, `completedSubmissionIndices`, or full callback timestamp list.

- [ ] **Step 2: Verify RED**

Run:

```bash
cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest --tests '*BoundedSessionStateTest' --tests '*DurableSessionWriterTest'
```

Expected: unresolved writer types and current unbounded state assertions fail.

- [ ] **Step 3: Implement writer state and deterministic flush policy**

Open before camera start. Flush and fsync after 150 pose records or five session-clock seconds, whichever occurs first. Replace checkpoints by temp-write, fsync, rename, parent-fsync. Classify restart residue; never resume or publish it.

- [ ] **Step 4: Refactor ledger/runner incrementally**

Replace lists/maps with monotone latches, one failure, current submission, submitted/terminal high-water marks, and completed one-second cadence buckets. Abort on the first inference failure. Preserve duplicate/out-of-order callback rejection.

- [ ] **Step 5: Integrate composition/finalizer**

Fence callbacks, drain/classify the pending inference, stop/join producers, reject post-seal appends, reconcile every payload, hash/re-read, write manifest last, fsync, rename once, fsync parent. Map post-rename parent-fsync failure to `UNCERTAIN_DURABILITY`.

- [ ] **Step 6: Run crash and mutation matrix**

Fault-inject before/after every write, flush, file fsync, checkpoint rename, manifest fsync, attempt rename, and parent fsync. Mutate the 150-record and five-second branches, reintroduce a record list, remove the telemetry join fence, and remove the post-seal rejection; each test must turn red.

- [ ] **Step 7: Verify and commit**

Run focused common/JVM tests, `make verify`, `make fetch-model-asset && make verify-harness-jvm`, the existing process-kill device gate, and commit:

```bash
git add apps/diagnostic-harness/shared apps/diagnostic-harness/androidApp
git commit -m "feat: stream M0.5B sessions durably"
```

---

### Task 3: S25 timestamp probe and synchronization contract release

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/ContainerTimestampProbe.kt`
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/probe/ContainerTimestampProbeTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/ContainerTimestampEvidence.kt`
- Create: `docs/evidence/device-probes/s25-ultra-container-timestamp-ledger.json`
- Create after a non-blocking probe: `contracts/schema/synchronization_evidence.v1.schema.json`
- Create after a non-blocking probe: `fixtures/synthetic/valid/synchronization/`
- Create after a non-blocking probe: negative synchronization fixtures under `fixtures/synthetic/invalid/`
- Modify after a non-blocking probe: `contracts/schema/session_manifest.v1.schema.json`
- Modify after a non-blocking probe: `contracts/tooling/validator_error_codes.v1.json`
- Modify after a non-blocking probe: `contracts/CHANGELOG.md`
- Modify after a non-blocking probe: `reference/src/observation_contracts/semantic.py`
- Modify after a non-blocking probe: `reference/tests/test_semantic_session.py`
- Modify after a non-blocking probe: Kotlin observation models/codecs and tests
- Modify: `Makefile`

**Interfaces:**
- Probe record contains submitted non-zero PTS values, demuxed PTS values, one derived constant offset, and `blocking_result` computed as any non-constant transform.
- Contract release adds optional `raw_pose_stream`, `synchronization_evidence`, and `media_required` to SessionManifest 1.2.0.
- Stable failures: `SESSION.MEDIA_REQUIRED_MISSING`, `SESSION.MEDIA_REQUIREMENT_CONTRADICTED`, `SESSION.MEDIA_DIGEST_MISMATCH`, `SESSION.SYNC_EVIDENCE_MISSING`, `SESSION.SYNC_CLOCK_MAPPING`, `SESSION.SYNC_ENDPOINT_MISMATCH`, `SESSION.VIDEO_COUNT_MISMATCH`.

- [x] **Step 1: Write and verify the probe RED tests**

Assert non-zero input sequence, exact demux count/order, one constant offset, device/API/app SHA binding, and a blocking typed result for varying transforms.

- [x] **Step 2: Implement and commit the probe only**

Add `make verify-harness-s25-container-timestamp-probe`; emit via instrumentation status and never write the ledger. Commit:

```bash
git add apps/diagnostic-harness Makefile docs/evidence/device-probes/s25-ultra-container-timestamp-ledger.json
git commit -m "feat: probe S25 container timestamps"
```

- [ ] **Step 3: Run on the authorized S25 and record evidence**

Require exactly one authorized physical S25. Record the sanitized typed output manually. If transformation is non-constant, checkpoint and stop Task 3; do not create SessionManifest 1.2.

- [ ] **Step 4: Write RED contract fixtures**

Add one valid media-required manifest and one negative fixture per stable failure, including absent/false `media_required` with video bytes/reference.

- [ ] **Step 5: Implement the additive contract**

Advance SessionManifest to 1.2.0 and error codes to 1.3.0. Derive authority from artifact presence: any video/sync reference or published video enters full validation regardless of the flag.

- [ ] **Step 6: Mutation and compatibility verification**

Swap submitted/demuxed endpoints, alter one digest/count/offset, remove each required top-level reference, and bypass the absent-flag contradiction. Each designated fixture must fail with exactly one stable code. All pre-1.2 valid fixtures must remain valid.

- [ ] **Step 7: Verify and commit the contract release**

Run `make verify`, full Kotlin/JVM contract tests, `make verify-kotlin-export`, then commit separately:

```bash
git add contracts fixtures reference apps/diagnostic-harness/shared contracts/CHANGELOG.md
git commit -m "feat: release synchronized session contracts"
```

---

### Task 4: Android in-app video and atomic multi-artifact publication

**2026-09-02 physical correction:** The first complete S25 run proved that both MP4 tracks use a
90 kHz timebase and that legitimate off-grid timestamps produce a 9 us submitted-minus-demuxed
offset span. Exact-constant-only admission therefore rejects valid container quantization. Apply the
approved additive profile and recovery plan in
`docs/superpowers/plans/2026-09-02-bounded-quantization-synchronization.md` before completing this
task. Preserve the released exact profile and historical Task 3 record; do not hand-pick a tolerance.

**Registry ownership:** Task 4 does not release sustained error codes and does not advance the
validator error-code registry. SessionManifest remains 1.2.0, and the synchronized `SESSION.*` failures
already released in registry 1.3.0 remain authoritative. Task 8 owns sustained semantics and their
release; see Task 8 Step 4.

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/video/LiveVideoWriter.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/video/MediaCodecVideoWriter.kt`
- Create: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/video/VideoDemuxVerifier.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/video/LiveVideoWriterTest.kt`
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/SynchronizedVideoDeviceGateTest.kt`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/SynchronizationEvidence.kt`
- Create: `tools/check_synchronized_video_run.py`
- Modify: `CameraXFrameSource.kt`, `OwnedYuvFrame.kt`, `LiveCameraComposition.kt`, `StoragePort.kt`, `AndroidStoragePort.kt`, storage conformance suites, `Makefile`

**Interfaces:**
- `LiveVideoWriter.accept(frameIndex, rebasedCaptureNs, ownedFrame): VideoDisposition`
- `VideoDisposition` is `Encoded(submittedPtsUs)`, `DroppedBeforeEncoder(code)`, or `EncoderFailure(code)`.
- Metadata samples carry frame index, rebased capture ns, and submitted PTS us at the same muxer PTS as the video sample.
- `VideoDemuxVerifier.verify(path): DemuxedVideoEvidence` returns video/metadata PTS sequences, metadata payloads, codec/container, byte count, and SHA-256.

- [ ] **Step 1: Write RED fan-out, lifecycle, and pairing tests**

Prove one owned frame feeds preprocessing and encoder without use-after-close, writer construction is per run, encoder/muxer cannot outlive source teardown, PTS is `floor(rebasedCaptureNs / 1_000)`, and video/metadata samples pair exactly.

- [ ] **Step 2: Implement the writer and owned fan-out**

Use MediaCodec/MediaMuxer, not CameraX Recorder. Write directly into Task 2's staging attempt. Close every owned frame exactly once.

- [ ] **Step 3: Extend atomic publication**

Finalize video before sealing; stop/join telemetry; reconcile pose/video/pairing/telemetry; create and fsync synchronization evidence; hash it; re-read all payloads; write manifest last; rename once.

- [ ] **Step 4: Add binary-safe host transfer and short S25 gate**

Use `adb exec-out` into a temporary host file, verify manifest byte count/SHA-256, then rename. Add `make verify-harness-s25-synchronized-video`; it prints a typed record and never edits its ledger.

- [ ] **Step 5: Run mutations**

Swap BT601 handling, perturb one PTS, omit metadata, change one endpoint/count/digest, close the source before encoder quiescence, use text-mode transfer, and remove one final fsync/re-read. Each behavioral gate must fail.

- [ ] **Step 6: Verify and commit**

Run JVM, Android unit/instrumentation, short S25 gate, host rehash validator, `make verify`, and commit:

```bash
git add apps/diagnostic-harness tools Makefile docs/evidence
git commit -m "feat: publish synchronized Android video sessions"
```

---

### Task 5a: Synthetic purge and tombstone software safety boundary

**Files:**
- Create: `contracts/tooling/purge_tombstone.v1.schema.json`
- Create: `reference/src/observation_contracts/purge.py`
- Create: `reference/tests/test_purge.py`
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/purge/PurgeContracts.kt`
- Create: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/purge/AndroidSyntheticPurge.kt`
- Create: common/JVM and thin Android adapter tests under `.../purge/`
- Modify: storage registry, root factory, M05 admission, repository guard

**Interfaces:**
- `PurgeState`: `INTENT_RECORDED`, `PURGE_INCOMPLETE`, `PURGE_COMPLETE`.
- `purge(sessionId, operationId): PurgeRecord` resolves targets only through Task 1's registry.
- Tombstone carries proposal status, synthetic session ID, pseudonym class only, operation ID/sequence, registry digest, location IDs/results, post-scan result, and producer SHA—no precise timestamp or identity value.

- [x] **Step 1: Amend the tracked design/plan and write RED schema, traversal, transaction, fence, root-invariant, and session-ID tests**
- [x] **Step 2: Verify RED with Python, JVM, and Android compilation-focused commands**
- [x] **Step 3: Implement intent-first append-only tombstone, registry-only deletion, exact inventory/post-scan, and idempotent recovery through a narrow `PurgeFilesystem` port**
- [x] **Step 4: Add a permanent tombstone-backed writer-open/final-rename admission fence, typed refusal, canonical registry digest binding, safe storage layout, and secure 48-bit reserved-ID generation**
- [x] **Step 5: Mutate ordering/crash, traversal/absolute/prefix/symlink/unknown-root/type-change, non-vacuity/post-scan, fence, registry digest, reserved-ID/random-source, sanitization/bounds, and recursive-delete guard conjuncts; each must turn its focused test red**
- [x] **Step 6: Run focused Python/JVM/Android compilation, Ruff/mypy, iOS common compilation, full `make verify` when feasible, repository guard, and exact diff inspection. Do not commit in this task.**

### Task 5b: Complete short video-enabled physical purge rehearsal (blocked)

**Dependency:** Task 4 contract release, Android synchronized publication, and an authorized physical
S25. This is not authorized by Task 5a.

- [ ] Create and rehearse one complete short video-enabled synthetic session; a pose-only run is invalid.
- [ ] Independently inspect the app-private post-scan through host `run-as`, then add the host checker,
  final physical Make target/build-contract, and pending/recorded evidence ledger validation.
- [ ] Record physical evidence only after the exact final binary and Task 4 artifacts are available; never
  substitute emulator evidence or claim Task 5a as a rehearsal.

---

### Task 6: Pipeline stage attribution and fixed telemetry

**Files:**
- Create: common stage-event and telemetry contracts under `shared/src/commonMain/.../telemetry/`
- Create: Android thermal/battery/connectivity/display/lifecycle probes under `androidApp/src/main/.../telemetry/`
- Create: common and Android tests under matching `*Test/.../telemetry/`
- Create: short 10/15/30 attribution device gate, host checker, and pending/recorded ledger under `docs/evidence/m05b-attribution/`
- Modify: `LiveCameraComposition.kt`, `CameraXFrameSource.kt`, inference/preprocessing bridge, `DurableSessionWriter.kt`, manifest derivation

**Interfaces:**
- `PipelineStageEvent(sequence, tSessionNs, frameIndex, stage, disposition)`
- `FixedTelemetrySample(sequence, tSessionNs, thermal, batteryTemperature, batteryLevelOrCharge, charging, powerSource, connectivity, keepAwake, brightness, lifecycle)`
- Missing APIs are typed `UNAVAILABLE(code)`; zero is never a substitute.

- [x] **Step 1: Write RED stage-count and one-second sampler tests**
- [x] **Step 2: Prove current code lacks full attribution and fixed sampling**
- [x] **Step 3: Implement one monotonic-clock event stream and fixed one-second sampler**
- [x] **Step 4: Fence finalization by cancel, join, terminal sample, then seal**
- [ ] **Step 5: Add a device gate for one short 10/15/30 attribution sequence proving each configured inference cadence changes admission while the camera request remains 30 FPS. Emit one typed record per cadence, use a pending/recorded ledger under `docs/evidence/m05b-attribution/`, and never let the command write the ledger. This physical step runs only after the Task 8 controller is built on the exact final app version; no app rebuild may occur afterward.**
- [x] **Step 6: Mutate each stage disposition, sample sequence, unavailable encoding, and join fence**
- [x] **Step 7: Run full local gates and commit `feat: record fixed M0.5B pipeline telemetry`**

---

### Task 7: Frozen start envelope and execution-order admission

**Files:**
- Create: `contracts/tooling/m05b_start_envelope.v1.schema.json`
- Create: `contracts/tooling/m05b_run_admission.v1.schema.json`
- Create: `contracts/tooling/m05b_execution_order.v1.schema.json`
- Create: Python derivation/admission module and tests
- Create: Kotlin evidence/rendering types and tests under `shared/.../study/`
- Create: S25 device gate and pending/recorded ledgers under `docs/evidence/m05b-envelope/`
- Modify: CLI, schema loader, device-evidence ledger tests, `Makefile`

**Interfaces:**
- Envelope fixes device/build/model/runtime/delegate/preprocessing, unplugged power, power-save false,
  Wi-Fi ADB, ambient ID, keep-awake/brightness, camera 30, inference set 10/15/30, duration 1,500
  seconds, one-second overshoot, cooldown rule, and pre-registered order digest. Battery level or
  charge is recorded as a tagged, non-blocking observation.
- Admission is computed from observed start values and exact envelope/order bytes; callers cannot pass
  success, order position, or predecessor status. Cooldown uses a required scalar battery temperature
  with no passing default.
- Attempts are immutable append-only, hash-chained entries containing sequence, cadence, admission
  digest, outcome, and predecessor digest. Exact artifact bytes are the only digest source.

- [x] **Step 1: Write RED schema/derivation/admission tests with literal envelopes**
- [x] **Step 2: Implement idle-baseline derivation and freeze-by-digest**
- [x] **Step 3: Implement per-run admission and typed blocking observations**
- [x] **Step 4: Add S25 gate that rejects emulator, charging, fake-unplug evidence, connectivity drift, device/build/model mismatch, and reordered cadence**
- [ ] **Step 5: On the exact final app build, after the Task 8 controller and all other code are
  complete and after Task 6's short attribution records are non-blocking, run the non-participant idle
  derivation on the S25 and record/freeze the envelope and order immediately before Task 8's sustained
  runs. Do not rebuild or change source between attribution, freeze, and sustained capture.**
- [x] **Step 6: Mutate every conjunct and require non-zero admission**
- [x] **Step 7: Verify and commit `feat: freeze M0.5B run admission envelope`**

---

### Task 8: Three sustained S25 characterizations

**Dependencies:** Tasks 3, 4, 6, and 7. Task 7's software/admission contract is consumed during
controller implementation; its physical envelope/order evidence and Task 6's recorded attribution
ledger are required before any sustained device run.

**Files:**
- Create: sustained-run contract/schema, Python validator, Kotlin record/renderer, host checker, device gate, and three evidence ledgers under `docs/evidence/m05b-sustained/`
- Modify: live composition/controller and `Makefile`

**Interfaces:**
- One run command consumes the frozen envelope/order and cadence through the same process boundary that
  opens the staging attempt, appends and fsyncs the exact admission artifact, and only then starts
  capture. It requests stop at 1,500,000,000,000 ns on the session clock and blocks if the request
  exceeds 1,501,000,000,000 ns.
- Records video disabled, camera 30 FPS, requested/achieved inference cadence, full stage accounting,
  fixed-bucket latency inputs and nearest-rank percentile bounds, fixed telemetry with gap/end
  coverage, battery delta, process/source outcome, and typed blockers. The latency edges and percentile
  rule are frozen before physical runs.

- [ ] **Step 1: Write RED duration/order/accounting tests. Prove the same-attempt admission-before-capture
  boundary, scalar cooldown temperature with no defaults, immutable attempt-chain order/predecessor
  derivation, fixed latency histogram protocol, and telemetry gap/end coverage.**
- [ ] **Step 2: Implement the sustained controller and typed evidence renderer**
- [ ] **Step 3: Mutation-check 1,500 seconds, one-second overshoot, video false, camera 30, envelope/order identity, and all stage equations**
- [ ] **Step 4: Registry stabilization checkpoint — stabilize the emitted-code contract before any
  physical run.** Task 8 owns sustained semantics and owns this release; Task 4 does not. Run this only
  after Steps 1-3 are complete, so that no code name is frozen while its semantics can still change
  (`errors.py`: released codes are never renamed or reused for a different condition). Required:
  - classify every currently unregistered dotted code emitted from `reference/src/observation_contracts`
    by reachability. Roughly 110 such codes exist today; the audit covers all of them, not only the 34
    associated with Task 8;
  - a code reachable from an admitted validator must be registered and covered by a negative fixture or
    an equivalent behavioral oracle;
  - a code reachable only from guarded WIP remains explicitly draft and unreleased;
  - declare explicit stable and draft emitted-code sets used by validator issue construction, fully
    enumerating any dynamically generated code;
  - add an emission-side coverage check so validator output cannot silently drift from the registry.
    Negative fixtures are currently the only enforcement, which is precisely how this gap opened; a
    fixture-side check alone will let it reopen;
  - add a repository test asserting that every stable emitted code is present in the released registry
    and that every guarded draft set remains unreleased;
  - release the finalized sustained set as registry `1.4.0` with changelog, compatibility evidence,
    mutation evidence, and the review requirements in `contracts/schema-release-process.md`;
  - replace the temporary disjointness guard in `reference/tests/test_m05b_sustained.py` with the
    complete subset requirement `draft_task8_codes <= released_codes`. Never weaken it to
    `not draft_task8_codes.isdisjoint(released_codes)`, which passes when only one of the 34 codes is
    released.

- [ ] **Step 5: After the Task 6 short attribution gate and Task 7 envelope/order are recorded on the
  exact final app build, execute exactly one 25-minute run per 10/15/30 cadence in pre-registered
  order on the authorized unplugged S25 over Wi-Fi ADB. Do not rebuild between those physical gates
  and the sustained runs.**
- [ ] **Step 6: Record every result honestly; a blocked observation remains blocking and is never
  overwritten by a rerun. Never run `pm clear` between runs. Recover and rehash each committed durable
  session artifact before the next cadence; the durable artifact is authoritative over the transcript.
  A transport-lost but durably committed session awaits recovery and cannot be rerun. Only a proven
  `ABORTED` or `FAILED` attempt permits another same-cadence attempt, as a new hash-chained entry.**
- [ ] **Step 7: Run validators and commit `feat: record S25 sustained instrument evidence`**

---

### Task 9: Digest-bound instrument-readiness admission and slice boundary

**Files:**
- Create: `contracts/tooling/m05b_instrument_readiness.v1.schema.json`
- Create: `reference/src/observation_contracts/instrument_readiness.py`
- Create: `reference/tests/test_instrument_readiness.py`
- Create: `docs/evidence/m05b-instrument-readiness.json`
- Modify: CLI/schema loader/version tests, device-evidence ledger tests, scope docs, `Makefile`

**Interfaces:**
- `verify-instrument-readiness RECORD` rehashes and validates registry/exclusion, streaming/process-kill, synchronized video/host transfer, purge/tombstone, stage attribution, envelope/order, three admissions/runs, and app/model/runtime/device/probe identities.
- Readiness also requires the recorded short 10/15/30 attribution ledger, exact-byte digest
  validation, and every immutable attempt entry, including blocked, aborted, failed, and transport-lost
  entries. A durably committed transport-lost session must be recovered rather than replaced by a
  same-cadence rerun.
- Readiness is derived and scoped only to “M0.5B non-participant instrument prerequisites passed on this S25”; it cannot authorize participant capture.

- [ ] **Step 1: Write RED binding tests**

Use literal references and mutate each path, digest, schema, session/device identity, blocking result, and scope claim.

- [ ] **Step 2: Implement validator and CLI**

Return zero only for a complete non-blocking record. Return the designated non-zero gate status for a well-formed blocking record and usage status 2 for malformed invocation.

- [ ] **Step 3: Bind the final record**

Reference every admitted artifact by relative path and SHA-256. Render flagship Android only plus explicit mid-range/iOS gaps and keep all eight privacy decisions `UNDECIDED`.

- [ ] **Step 4: Run the slice integration boundary**

Run `make verify`, full JVM/Android/iOS compile gates, repository guard, one synthetic integration run, repeated process-kill, all physical S25 evidence validators, and `make verify-instrument-readiness`.

- [ ] **Step 5: Perform one bounded full-range review**

Inspect the complete M0.5B range for Critical/Important contract, privacy, synchronization, boundedness, lifecycle, and evidence-admission defects. Permit one scoped re-review only if blocking findings are fixed; record Minor findings as debt.

- [ ] **Step 6: Commit and open the slice PR**

```bash
git add contracts reference docs apps/diagnostic-harness Makefile tools
git commit -m "feat: admit M0.5B instrument readiness"
```

Require protected CI green. Preserve branches/worktrees. Merge and tag remain release-owner decisions; this plan creates no tag and participant capture remains blocked.

---

## Plan self-review

- **Spec coverage:** Tasks 1–9 map one-to-one to design section 13 and cover every invariant, verification requirement, stop condition, and completion criterion.
- **Write-scope check:** Task 6 may run in parallel with Tasks 3–5 only after Task 2, because its new telemetry package is disjoint; any shared edit to `LiveCameraComposition.kt` serializes the final composition commit. Device tasks are always serialized.
- **Type consistency:** Task 1 registry feeds Tasks 2/5/9; Task 2 sealed artifacts feed Tasks 3/4/6; Task 3 contracts feed Task 4; Task 6 feeds Tasks 7/8; Task 7 envelope/order feeds Task 8; all feed Task 9.
- **Authority check:** No task accepts privacy policy, enables participant capture, moves a tag, adds paid/network services, or claims iOS/mid-range equivalence.
- **Execution order:** Start Task 1 only after this plan is merged. Do not begin Task 3 contract release until its physical probe is recorded and non-blocking. Complete all implementation code through the Task 8 controller first; then, on that exact final app build, run Task 6's short 10/15/30 attribution gate, derive and freeze Task 7's envelope/order, and immediately execute Task 8's sustained runs. No app rebuild or source change may occur between those physical steps. Task 9 follows recovery and validation of every durable artifact and immutable attempt entry.
