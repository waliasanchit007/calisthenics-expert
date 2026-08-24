# M0.5B Instrument Prerequisites Design

Status: Proposed for review

Date: 2026-08-24

Base: `main` at `798ee52c5142d5c29b76a6104724894fb55874db`

Scope: non-participant Android instrument prerequisites on the authorised S25 Ultra only

## 1. Purpose

M0.5B makes the existing Android diagnostic harness capable of producing durable, synchronized,
truthful non-participant evidence before any participant capture is considered. It adds the storage,
media, withdrawal-rehearsal, telemetry, and start-state controls required by the approved M0.5 study
readiness design.

This slice does not claim that the movement signal is reliable. It proves that the instrument can
record and preserve the evidence needed to evaluate that question later without silently losing,
mis-pairing, backing up, or misclassifying data.

The governing documents are:

- `AGENTS.md`;
- `docs/central-handoff.md`;
- `docs/superpowers/specs/2026-08-24-m0-5-study-readiness-design.md`;
- `docs/adr/ADR-0003-privacy-and-data-governance-before-human-data.md`;
- `docs/adr/ADR-0005-android-scoped-phase-0-evidence.md`;
- `docs/protocols/capture-protocol.v1.md`;
- `docs/protocols/privacy-retention-withdrawal.v1.md`.

Where this document describes tombstones or purge mechanics, it proposes a synthetic technical
contract only. It does not accept a retention, withdrawal, deletion, backup, or privacy policy.
`withdrawal_and_deletion` and `backup_handling` remain `UNDECIDED` in
`docs/protocols/study-readiness.m05.v1.json`, and participant capture remains mechanically blocked.

## 2. Current-state findings

The design is grounded in the merged implementation rather than an assumed future architecture.

1. `SessionLedger` holds every `RawPoseFrame` and inference failure in memory until finalization.
   A 20–30 minute run therefore scales heap use with emitted records.
2. `SessionExporter` creates a staging directory only at finalization, then serializes the complete
   in-memory record list. It publishes `frames.ndjson` and `manifest.json` with a single final rename.
3. `StorageRoots` contains only app-private staging and export roots. The M0.5 location names are
   duplicated in governance JSON rather than backed by one executable root registry.
4. `AndroidStoragePort` has crash-consistent frame/manifest primitives but no binary-video stream,
   transfer-staging root, purge transaction, tombstone root, or re-admission fence.
5. `SessionManifest` already supports digest-bound `MediaReference` values with optional offset and
   drift, but semantic validation defines media-role requirements for `fixture_replay` and
   `live_study` only. `live_conformance` has no required video/synchronization rule.
6. The live camera path records requested camera cadence, admitted inference cadence, callback
   accounting, and thermal status changes. It does not attribute every pipeline stage or sample
   thermal, battery, charging, and connectivity state on one fixed interval.
7. The Android manifest declares neither backup exclusion nor data-extraction rules. Current Android
   documentation states that `allowBackup=false` can still permit device-to-device migration on
   Android 12+, so explicit `dataExtractionRules` exclusions are required for both cloud backup and
   device transfer. The app has minSdk 33 and targetSdk 36; legacy Android 11 rules are not a runtime
   requirement for this app unless that supported range later changes.
8. No synchronized video writer, start-state envelope, cadence-order artifact, sustained-run record,
   device purge evidence, or tombstone implementation exists.

These are prerequisites, not defects in the completed replay/live-capture milestones.

## 3. Scope

### 3.1 In scope

- one machine-readable storage-location registry shared by export, backup exclusion, purge, tests,
  and evidence validation;
- Android cloud-backup and device-transfer exclusion for every registered app-controlled root;
- durable incremental pose-stream writing suitable for a 30-minute run;
- short-run synchronized camera video for `live_conformance` only;
- digest-bound media/synchronization admission with stable error codes;
- crash-consistent session publication containing pose stream, video, synchronization evidence,
  telemetry, and manifest;
- synthetic device-side purge and a minimal non-identifying tombstone proposal;
- per-stage cadence attribution and fixed-interval thermal/battery/connectivity telemetry;
- a frozen start-state envelope, cooldown admission, and pre-registered cadence order;
- three sustained S25 runs at configured inference cadence 10, 15, and 30 FPS while camera delivery
  remains pinned at 30 FPS;
- one final instrument-readiness record binding all required M0.5B evidence by digest;
- repository, JVM, Android, physical-device, integration, and protected-CI evidence.

### 3.2 Out of scope

- participant capture or any real participant identifier, consent record, identity mapping, video,
  pose stream, or derived biometric observation;
- acceptance of any currently `UNDECIDED` privacy decision;
- M0.5C analysis or annotation tooling;
- iOS implementation or cross-platform equivalence claims;
- mid-range-device implementation or inference from S25 behavior;
- rep counting, movement interpretation, form scoring, thresholds, coaching, or product UI;
- network/cloud upload, paid services, or automated external storage;
- changing camera cadence from the already admitted 30 FPS;
- video during the 20–30 minute sustained characterization runs.

## 4. System invariants

1. **Participant gate stays closed.** Every physical run in this slice is `live_conformance`, uses a
   reserved `synthetic-m05-*` identity, carries a positive no-person operator attestation, and records
   `empty_scene` or `displayed_synthetic_figure` as input class.
2. **One root registry.** Code, backup configuration generation/verification, purge, post-purge scan,
   and readiness validation consume one canonical registry. A new app-controlled artifact root that
   is absent from the registry fails the build.
3. **No whole-session pose buffering.** Emitted records are durably streamed. In-memory state may
   retain counters, pending submissions, and a bounded tail/checkpoint only.
4. **One attempt, one atomic publication.** Required pose, media, synchronization, telemetry, and
   manifest artifacts are staged under one attempt and become visible together through one rename.
5. **Manifest last.** The manifest is written only after all required content is finalized, hashed,
   reconciled, and fsynced. It is the commit record, not a progress file.
6. **No vacuous synchronization.** A `live_conformance` claim requiring media cannot pass without
   video bytes, both clock-mapping parameters, frame-bound evidence, dropped-frame counts, and
   matching digests.
7. **Purge is fail-closed.** A partial delete, unknown root, traversal, symlink escape, post-scan
   residue, or durability failure is `PURGE_INCOMPLETE`; it is never reported as withdrawal success.
8. **Tombstones block reuse.** Once a synthetic session ID has a durable tombstone intent, export or
   admission under that session ID fails even if purge later remains incomplete.
9. **Tombstone is not policy.** The proposal carries no participant identity and no precise completion
   timestamp. Its existence cannot make `withdrawal_and_deletion` accepted.
10. **Quota and connectivity do not alter evidence.** Wi-Fi ADB is used so the S25 remains physically
    unplugged. Connectivity is recorded in the frozen envelope and held constant across all sustained
    cadence runs.
11. **No fake unplug state.** `dumpsys battery unplug` or equivalent simulated battery state is
    prohibited as device evidence.
12. **Device runs are observations.** A command can execute successfully while emitting a typed
    blocking result. The blocker is recorded; the gate is not narrowed.

## 5. Canonical storage-location registry

### 5.1 Registry shape

Add a versioned machine-readable registry whose entries have:

- stable location ID from the existing closed vocabulary;
- owner domain: `android_app`, `host_transfer`, or `external_study_store`;
- relative root name or host/store locator class, never a machine-specific absolute path;
- allowed artifact classes;
- whether the location participates in app export publication;
- whether it participates in device purge;
- whether it participates in host rehearsal purge;
- cloud-backup classification;
- device-transfer classification;
- tombstone eligibility;
- post-purge scan requirement.

The initial registry contains all seven locations already declared by M0.5A:

| Location | Owner | M0.5B behavior |
|---|---|---|
| `device_export` | Android app | committed session root; backup/D2D excluded; purged |
| `device_staging` | Android app | in-progress attempts; backup/D2D excluded; purged |
| `device_transfer_staging` | Android app | explicit transfer preparation only; backup/D2D excluded; purged |
| `study_store_source` | external synthetic store | host rehearsal only in M0.5B |
| `study_store_derived` | external synthetic store | host rehearsal only in M0.5B |
| `study_store_report` | external synthetic store | host rehearsal only in M0.5B |
| `tombstone` | Android app | excluded from artifact purge; backup/D2D excluded; host-store mirroring is deferred |

App-relative directory names are generated from or mechanically checked against this registry. The
Kotlin `StorageRoots`, Android XML exclusions, purge scanner, and Python study-readiness validator may
have language-native representations, but tests prove exact set equality with the canonical file.
Independent hand-maintained lists are prohibited.

### 5.2 Backup and transfer behavior

The Android application sets `android:allowBackup="false"` and references an Android 12+
`android:dataExtractionRules` resource. Both `<cloud-backup>` and `<device-transfer>` exclude every
registered Android-app root. `allowBackup=false` is defense in depth, not the D2D control.

Because minSdk is 33, a legacy `fullBackupContent` file is not added merely to satisfy historical
guidance. If the supported minimum is later lowered to Android 11 or below, legacy rules become a
required registry-generated or registry-verified projection in that change.

The gate proves:

- manifest attributes are present;
- cloud and device-transfer exclusions resolve to the complete Android-app registry set;
- deleting either domain, omitting any root, adding an unregistered root, or changing a path makes
  the test fail;
- `adb shell bmgr`/transport behavior is not treated as the oracle for OEM D2D behavior;
- the device is wiped with `pm clear` before exclusion evidence is recorded so pre-rule state cannot
  be mistaken for post-rule protection.

## 6. Streaming and atomic session publication

### 6.1 Writer lifecycle

A `DurableSessionWriter` is created when a session is admitted, before the camera starts. It owns one
unique staging attempt and has these states:

```text
NEW -> OPEN -> SEALING -> PUBLISHED
              |          |
              +-> ABORTED +-> UNCERTAIN_DURABILITY
              +-> FAILED
```

- `OPEN` accepts ordered pose records, video samples, and telemetry samples.
- Pose records are encoded and appended immediately; the writer never waits for finalization to
  receive the entire session.
- Flush policy is deterministic and versioned: a bounded record count and a bounded elapsed interval
  each trigger channel flush plus `fsync` as configured. The exact constants are part of writer
  provenance and tests, not runtime tuning knobs.
- A compact checkpoint records last durable record index, counts, and digest state. Checkpoint
  replacement is temp-write, fsync, rename, parent-fsync.
- On process restart, residue is classified; it is never silently resumed or published.
- `SessionLedger` remains authoritative for state transitions and counters, but releases an emitted
  record to the writer and retains no unbounded `records` list. Tests prove bounded memory by record
  count rather than relying on a heap observation.

### 6.2 Finalization sequence

Finalization executes exactly:

1. fence new camera callbacks;
2. drain or classify pending inference;
3. stop and finalize the video encoder for a video-enabled short run;
4. append final pose/telemetry records and flush;
5. fsync the finalized pose, video, and telemetry files;
6. independently stream-read and reconcile pose line/count identities, video sample counts, and
   telemetry sequence/timestamps;
7. compute SHA-256 digests from finalized bytes;
8. create synchronization evidence from observed clocks and sample boundaries;
9. validate all required artifact relations;
10. write and fsync the synchronization-evidence file;
11. write manifest with digests and final counts;
12. fsync manifest and attempt directory;
13. verify destination absence;
14. rename the attempt once into the committed export root;
15. fsync the committed-root parent directory.

Any failure before rename leaves classified staging residue and no committed session. A failure after
rename but before parent fsync is `uncertain_durability`; it does not retry under the same session ID.

## 7. Synchronized non-participant video

### 7.1 Recording boundary

Video is enabled only for short `live_conformance` runs used to prove synchronization and purge. The
camera image feeding MediaPipe and the encoder originates from the same CameraX session. The video
writer is owned by the live composition and shares its lifecycle fence; it cannot outlive the camera
source or publish independently.

Video is excluded from 20–30 minute sustained characterization because encoding load would confound
the thermal, battery, and cadence question. The sustained records explicitly state
`video_enabled=false` and cite the separately admitted short-run synchronization evidence.

### 7.2 Additive contract

The released v1 schema remains backward compatible. Additive v1 fields define a
`synchronization_evidence` artifact and strengthen semantic rules for `session_kind=live_conformance`
only when the run declares `media_required=true`.

The record contains:

- schema version, session ID, video role/path/digest/byte count/container/codec;
- pose-stream digest and manifest session ID;
- camera timestamp domain and encoder timestamp domain;
- monotonic mapping method/version;
- offset in nanoseconds and drift in nanoseconds per second;
- first and last camera-frame timestamps;
- first and last encoded-frame presentation timestamps;
- first and last pose capture timestamps;
- delivered camera callbacks, encoded video frames, submitted inference frames, emitted pose records;
- video frames dropped before encoder, encoder failures, inference skip/failure counts;
- producer Git SHA and device/model/API identity.

Admission computes consistency; callers cannot declare a success boolean. Required relations include:

- all session IDs match;
- manifest media digest equals the finalized video bytes;
- pose digest equals the finalized NDJSON bytes;
- offset and drift appear together and are finite;
- first/last ordering is monotonic in each domain;
- mapped endpoints fall within the versioned tolerance recorded by the contract;
- callback, encoded, dropped, submitted, skipped, emitted, and failed counts reconcile through named
  identities rather than one ambiguous total;
- a media-required `live_conformance` session has exactly one `session_video` and exactly one
  synchronization-evidence reference.

The host transfer command uses binary-safe `adb exec-out`, writes to a temporary host file, verifies
byte count and SHA-256 against the manifest, then renames. Text-mode shell capture is prohibited.

### 7.3 Stable failures

Register at least these stable failures before implementation code consumes them:

- `SESSION.MEDIA_REQUIRED_MISSING`;
- `SESSION.MEDIA_DIGEST_MISMATCH`;
- `SESSION.SYNC_EVIDENCE_MISSING`;
- `SESSION.SYNC_CLOCK_MAPPING`;
- `SESSION.SYNC_ENDPOINT_MISMATCH`;
- `SESSION.VIDEO_COUNT_MISMATCH`.

Each rule ships with a negative fixture and a mutation that makes its behavioral test fail.

## 8. Synthetic purge and tombstone proposal

### 8.1 Authority boundary

This section defines only the minimum mechanism needed to rehearse deletion with synthetic data. It
does not decide retention duration, withdrawal eligibility, backup propagation, administrator,
identity mapping, or legal semantics. The readiness decision remains `UNDECIDED`, and validators
reject any claim that this proposal settles it.

### 8.2 Tombstone proposal

The tombstone is outside purge-eligible artifact roots and contains only:

- tombstone schema version and explicit `policy_status: proposal`;
- synthetic session ID;
- random purge operation ID;
- state: `INTENT_RECORDED`, `PURGE_INCOMPLETE`, or `PURGE_COMPLETE`;
- registry version/digest;
- sorted location IDs considered;
- per-location terminal result code, with no absolute path;
- post-scan result and producer Git SHA;
- monotonically increasing operation sequence.

It contains no participant identifier, wall-clock completion timestamp, exact device path, media
metadata, landmark data, reason narrative, or operator identity. If later policy requires time, it
must approve a coarse representation and update the schema; precise timestamps are not the default.

### 8.3 Purge transaction

Purge is idempotent and ordered:

1. validate session ID and require the reserved synthetic namespace;
2. resolve every target exclusively through the registry;
3. reject traversal, path-prefix partial matches, unknown roots, symlinks, and escapes;
4. atomically write and fsync `INTENT_RECORDED` tombstone before deleting data;
5. enumerate exact session ownership in every purge-participating root;
6. delete owned files/directories without following links;
7. fsync every affected parent directory;
8. independently enumerate every participating root again;
9. write and fsync `PURGE_COMPLETE` only when all post-scans are empty; otherwise write
   `PURGE_INCOMPLETE` with typed per-location results;
10. reject any future staging, export, transfer, or admission under that session ID.

Required stable results include `PURGE.INVALID_SESSION_ID`, `PURGE.UNKNOWN_ROOT`,
`PURGE.PATH_ESCAPE`, `PURGE.SYMLINK_REJECTED`, `PURGE.DELETE_FAILED`,
`PURGE.POST_SCAN_RESIDUE`, `PURGE.DURABILITY_UNCERTAIN`, and `PURGE.SESSION_TOMBSTONED`.

The device test creates data only beneath test-owned synthetic roots, proves each conjunct by
mutation, and never runs a broad recursive delete against an unresolved path.

## 9. Cadence attribution and fixed-interval telemetry

### 9.1 Stage attribution

One session-relative monotonic clock records:

- camera callback received;
- YUV ownership acquired/released;
- preprocessing started/completed;
- cadence admission or named rejection;
- inference submitted/completed/failed;
- pose record appended/durable checkpoint reached;
- encoder sample accepted/dropped/written when video is enabled.

Counts are derived from events and reconcile with the existing session counts. `cadence_selection`,
`backpressure`, `session_stopping`, source loss, preprocessing failure, inference failure, and encoder
drop remain distinct. The design does not infer upstream CameraX drops from downstream skips.

Before sustained runs, one short 10/15/30 attribution sequence confirms that configured inference
cadence changes admission while camera request remains 30 FPS. A mismatch blocks long runs.

### 9.2 Telemetry

A versioned fixed interval samples, from one scheduler and monotonic clock:

- Android thermal status;
- battery temperature;
- battery percentage or charge counter when available;
- charging state and power source;
- connectivity type, connected state, and Wi-Fi identity represented without SSID/BSSID;
- keep-awake active state;
- screen brightness mode/value;
- app foreground/lifecycle state.

Event-driven thermal callbacks may remain as supplemental detail but cannot substitute for fixed
interval samples. Missing APIs are typed `UNAVAILABLE`; they are never encoded as zero. Samples are
streamed durably and sequence-checked. Wall-clock time is not used for cadence or synchronization.

## 10. Frozen start-state envelope

The envelope is derived and committed as a non-participant evidence artifact before the first
sustained run. It contains:

- envelope schema and procedure version/digest;
- S25 model, build, API, app SHA, model SHA, runtime, delegate, and preprocessing versions;
- idle-baseline duration and sampling interval;
- admitted initial thermal status;
- battery-temperature range/variability admitted by the baseline;
- charging state fixed to unplugged;
- connectivity state fixed to Wi-Fi ADB connected;
- ambient-condition identifier and operator-recorded constancy statement;
- keep-awake behavior and brightness mode/value;
- cooldown admission rule;
- camera cadence fixed to 30 FPS;
- inference-cadence set `{10,15,30}`;
- pre-registered execution order and order-artifact digest.

No threshold is chosen after seeing a sustained result. The envelope is derived once from the fixed
idle procedure, reviewed as evidence, and then frozen. Before each run a separate admission record
references the envelope digest and contains the observed start values. A failed conjunct produces a
typed blocked observation and does not start the run. Cooling waits do not relax the envelope.

The order is recorded before any sustained run and cannot be reordered after observation. A rerun is
a new attempt linked to the original condition and reason; it does not overwrite the first result.

## 11. Sustained S25 characterization

Run exactly one 20–30 minute non-participant session for each configured inference cadence in the
pre-registered order. All use:

- the same authorised physical S25 identity;
- camera cadence 30 FPS;
- unplugged power;
- Wi-Fi ADB and the same connectivity state;
- the same envelope, ambient state, brightness, keep-awake behavior, model, delegate,
  preprocessing, and app build;
- `video_enabled=false`;
- `empty_scene` or an admitted displayed synthetic figure, never a person.

Each run records the envelope/admission/order digests, start/end device identity, full stage counts,
achieved cadence time series, latency distribution inputs and percentiles, fixed-interval telemetry,
thermal events, battery delta, source/process outcome, and typed blocking observations.

A zero-detection empty-scene run is valid for performance characterization but proves nothing about
detection. A displayed-figure run with no detection leaves detection-dependent cells pending. The
scope is always rendered as flagship Android only; mid-range and iOS remain unmeasured.

## 12. Instrument-readiness admission

The final M0.5B record is computed from referenced artifacts and contains no caller-declared ready
boolean. It binds by path and SHA-256:

- location registry and backup-exclusion evidence;
- streaming/process-kill evidence;
- short synchronized-video evidence and host rehash evidence;
- synthetic purge/tombstone rehearsal;
- stage-attribution evidence;
- frozen envelope and execution-order artifact;
- all three start-admission and sustained-run records;
- app/model/runtime/device/probe SHAs;
- declared Android flagship-only scope and mid-range/iOS gaps.

Admission is true only when every required reference exists, hashes correctly, has the expected
schema/session/device identity, and contains no blocking result. A command that emits a complete
record with a blocking result exits with the designated non-zero gate status.

This admission means only “M0.5B non-participant instrument prerequisites passed on this S25.” It
does not authorize participant capture or accept any privacy decision.

## 13. Delivery DAG and write scopes

Implementation follows regression-first tests and the dependency order below. Each task lands as one
coherent commit after targeted checks and exact diff inspection. Routine tasks do not receive an
automatic second-model review; privacy, synchronization, and final boundary contracts do.

1. **Registry and backup exclusion**
   Write scope: registry schema/data, Android manifest/resources, registry validators/tests.
   Delivers canonical roots, generated/verified exclusions, and pre-exclusion wipe evidence contract.
2. **Durable streaming writer**
   Depends on 1. Write scope: common export/session core and storage conformance tests.
   Removes unbounded pose buffering and preserves crash/process-kill semantics.
3. **Synchronization contract**
   May overlap 2 after 1 if write scopes stay disjoint. Write scope: observation schemas, Python
   semantic validator, Kotlin contract model/codec, error registry, synthetic fixtures/tests.
4. **Android video and atomic publication**
   Depends on 2 and 3. Write scope: Android camera/encoder adapter, storage binary primitives,
   composition/finalization, short device gate and transfer verifier.
5. **Synthetic purge and tombstone proposal**
   Depends on 1 and 2. Write scope: purge/tombstone contracts, Android storage purge, host rehearsal,
   device and repository tests. `withdrawal_and_deletion` remains `UNDECIDED`.
6. **Stage attribution and fixed telemetry**
   Depends on 2. Write scope: live pipeline event model, Android probes, telemetry stream and tests.
7. **Frozen envelope and order admission**
   Depends on 6. Write scope: envelope/order schemas, derivation/admission tools, device gate, evidence
   ledger validators. It must complete before Task 8 begins.
8. **Sustained characterization**
   Depends on 4, 6, and 7. Write scope: run command/evidence renderer and the three physical-device
   evidence records. No contract is weakened in response to an observation.
9. **Instrument-readiness binding and boundary**
   Depends on all prior tasks. Write scope: readiness schema/validator/CLI/ledger, integration test,
   final evidence references, and scope documentation.

Parallel work is permitted only for the explicitly disjoint branches above. One task owns a file at a
time. Device runs are serialized.

## 14. Verification and mutation requirements

Every behavioral gate is written red before production implementation. Each conjunct ships with the
mutation that must break it; surviving mutants are diagnosed rather than accepted.

Required evidence includes:

- registry set-equality and unknown-root negative tests;
- manifest/XML parser tests for cloud and D2D exclusions;
- bounded-memory streaming tests with large synthetic record counts;
- crash points before/after each durable publication step;
- media digest, mapping, endpoint, and count negative fixtures;
- binary transfer/re-hash of a short device video;
- purge traversal, prefix, symlink, unknown-root, partial-delete, fsync, post-scan, idempotency, and
  tombstoned-ID re-admission negatives;
- stage-count and fixed-sample cadence mutations;
- envelope-conjunct and pre-registered-order mutations;
- physical S25 gate refusing emulators, unauthorized devices, charging, fake-unplug evidence,
  connectivity drift, and envelope mismatch;
- a repeated process-kill test after streaming changes;
- full Python, Ruff, strict mypy, Kotlin/JVM, Android unit/instrumentation, Android assembly, iOS
  compile, repository guard, one synthetic integration run, bounded full-range review, and protected
  CI at the slice boundary.

Success is an exit code. Human-readable output is not parsed to decide a gate. Device commands emit a
typed record for an explicit human recording step and never rewrite the authoritative evidence ledger
themselves.

## 15. Stop conditions and authority

Implementation stops and records a resume point when:

- backup/D2D coverage cannot be proven for every registered Android root;
- streaming cannot preserve the existing crash-consistency and process-kill guarantees;
- video and pose clocks cannot be mapped with non-vacuous endpoint/count evidence;
- purge cannot prove post-scan absence or tombstoned-ID rejection;
- the S25 cannot meet the frozen start-state envelope without changing it;
- connectivity, model, delegate, camera cadence, preprocessing, app build, or device identity changes
  across sustained runs;
- a task would require participant data, a privacy-policy acceptance, a paid service, a new device,
  iOS scope, or a changed product/research objective.

Codex owns routine implementation and verification. `claude-work` is reserved for the bounded design
challenge and any concrete unresolved high-impact privacy/synchronization disagreement. The release
owner alone may merge, tag, accept privacy decisions, authorize participant capture, or change the
research/product direction.

## 16. Completion criteria

M0.5B is complete only when Tasks 1–9 are merged through protected CI and:

- every app-controlled root is registry-backed and excluded from cloud and device transfer;
- long live runs use bounded durable streaming rather than whole-session pose buffering;
- one short S25 non-participant run proves digest-bound video/pose synchronization and binary-safe
  host transfer;
- one synthetic purge rehearsal proves intent-first tombstone, complete registered-root deletion,
  parent durability, post-scan absence, idempotency, and re-admission rejection;
- `withdrawal_and_deletion` is still visibly `UNDECIDED` and participant capture is still blocked;
- fixed-interval telemetry and stage attribution reconcile;
- the envelope and cadence order were frozen before sustained data existed;
- 10/15/30 inference-cadence S25 runs exist under one unchanged start-state envelope, with camera at
  30 FPS, unplugged Wi-Fi ADB, no video, and no participant;
- the final readiness record verifies every evidence digest and renders flagship-only scope plus
  mid-range/iOS gaps;
- no tracked participant artifact, model binary, credential, precise tombstone timestamp, or network
  upload path was introduced.

Completion permits M0.5C synthetic rehearsal to rely on the instrument artifacts. It does not permit
participant capture and does not establish signal reliability.
