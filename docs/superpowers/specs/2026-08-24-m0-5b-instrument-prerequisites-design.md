# M0.5B Instrument Prerequisites Design

Status: Accepted for implementation at merged commit `789af8eddc0cdb81a3e4ad96ad119dd28111fa7b`

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
- `docs/adr/ADR-0003-contract-evolution-and-data-guard.md`;
- `docs/adr/ADR-0005-android-scoped-phase-0-evidence.md`;
- `docs/protocols/capture-protocol.v1.md`;
- `docs/protocols/privacy-retention-withdrawal.v1.md`.

Where this document describes tombstones or purge mechanics, it proposes a synthetic technical
contract only. It does not accept a retention, withdrawal, deletion, backup, or privacy policy.
`withdrawal_and_deletion` and `backup_handling` remain `UNDECIDED` in
`docs/protocols/study-readiness.m05.v1.json`, and participant capture remains mechanically blocked.

## 2. Current-state findings

The design is grounded in the merged implementation rather than an assumed future architecture.

1. `SessionLedger` holds every `RawPoseFrame`, inference failure, and completed submission identity
   in memory until finalization. `SessionRunner` duplicates the terminal map, while
   `LiveCameraComposition` retains every admitted callback timestamp and terminal submission index.
   A sustained run therefore scales multiple heap collections with delivered or emitted frames.
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
- video during the 25-minute sustained characterization runs.

## 4. System invariants

1. **Participant gate stays closed.** Every physical run in this slice is `live_conformance`, uses a
   reserved `synthetic-m05-*` identity, carries a positive no-person operator attestation, and records
   `empty_scene` or `displayed_synthetic_figure` as input class.
2. **One root registry.** Code, backup configuration generation/verification, purge, post-purge scan,
   and readiness validation consume one canonical registry. A new app-controlled artifact root that
   is absent from the registry fails the build.
3. **Bound every session collection.** Emitted records are durably streamed. In-memory state may
   retain counters, the single in-flight submission, one failure, fixed-window aggregates, and a
   bounded tail/checkpoint only. No collection grows with delivered or emitted frame count.
4. **One attempt, one atomic publication.** Required pose, media, synchronization, telemetry, admission,
   and manifest artifacts are staged under one attempt and become visible together through one rename.
5. **Manifest last and exact-byte binding.** The manifest is written only after all required content is
   finalized, hashed, reconciled, and fsynced. It is the commit record, not a progress file. Every
   digest in an envelope, order, admission, session, or ledger record is SHA-256 over the exact bytes
   of the referenced artifact. Canonical JSON writers produce those bytes deterministically; a parsed
   object or a re-serialization with different whitespace/key ordering is never a digest substitute.
   Canonical JSON artifacts must satisfy `path.read_bytes() == canonical_json_bytes(parse(path))` at
   validation time, and an artifact never hashes a digest field contained within itself.
6. **No vacuous synchronization.** A `live_conformance` claim requiring media cannot pass without
   video bytes, both clock-mapping parameters, frame-bound evidence, dropped-frame counts, and
   matching digests.
7. **Purge is fail-closed.** A partial delete, unknown root, traversal, symlink escape, post-scan
   residue, or durability failure is `PURGE_INCOMPLETE`; it is never reported as withdrawal success.
8. **Tombstones block reuse.** Once a reserved-namespace synthetic session ID has a durable tombstone intent, export or
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

All Android roots sit below one app-private `diagnostic-harness` parent. App-relative directory names
are constructed only through a registry-backed factory. The Kotlin `StorageRoots`, purge scanner, and
Python study-readiness validator may have language-native representations, but tests prove exact set
equality with the canonical file. A static architecture guard rejects direct `filesDir`, `getDir`, or
`diagnostic-harness` path construction outside the factory, and a post-run scan rejects an
unregistered top-level child. Independent hand-maintained lists are prohibited.

### 5.2 Backup and transfer behavior

The Android application sets `android:allowBackup="false"` and references an Android 12+
`android:dataExtractionRules` resource. Both `<cloud-backup>` and `<device-transfer>` exclude the
single `diagnostic-harness` parent recursively, so a mistakenly unregistered future child remains
excluded by construction. Tests additionally project every registered Android child beneath that
parent and prove per-root registry equality. `allowBackup=false` is defense in depth, not the D2D
control.

Because minSdk is 33, a legacy `fullBackupContent` file is not added merely to satisfy historical
guidance. If the supported minimum is later lowered to Android 11 or below, legacy rules become a
required registry-generated or registry-verified projection in that change.

The gate proves:

- manifest attributes are present;
- cloud and device-transfer exclusions each cover the complete `diagnostic-harness` parent;
- deleting either domain, narrowing the parent exclusion, bypassing the root factory, or creating an
  unregistered top-level child makes the test fail;
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

- `OPEN` accepts ordered pose records, encoded-video bytes/frame-pairing records, and telemetry
  samples from separately fenced producers.
- For a sustained run, the same process boundary opens the attempt, derives admission from the exact
  envelope/order bytes and observed start values, appends exactly one admission artifact, flushes and
  fsyncs it, and only then starts capture. The admission artifact carries the attempt/session
  association and exact envelope/order digests; a blocked or failed admission never starts capture.
- Pose records are encoded and appended immediately; the writer never waits for finalization to
  receive the entire session.
- Flush policy is deterministic and versioned: 150 pose records or five session-clock seconds,
  whichever arrives first, triggers channel flush plus `fsync`. The constants are writer provenance,
  apply identically at 10/15/30 inference cadence, and are not runtime tuning knobs.
- A compact checkpoint records last durable record index, counts, and digest state. Checkpoint
  replacement is temp-write, fsync, rename, parent-fsync.
- On process restart, residue is classified; it is never silently resumed or published.
- `SessionLedger` remains authoritative for state transitions and counters, but releases an emitted
  record to the writer and retains no `records` list. The streaming replacement also preserves the
  released manifest semantics:
  - normalized/world availability become monotone latches updated per emitted record;
  - achieved cadence uses a single-pass session-relative one-second-window accumulator, including
    zero-valued completed windows, omitting the final partial window, and returning an empty list when
    no full window completed, byte-identical to `achievedInferenceFpsTimeseriesOf`;
  - M0.5B aborts on the first inference failure, so the required inline
    `machine_inference_failures` array is bounded to one and remains count-consistent;
  - the one-in-flight invariant replaces `completedSubmissionIndices`, `terminalMap`, and
    `terminalSubmissionIndices` with a current submission plus monotone submitted/terminal
    high-water marks; duplicate or out-of-order terminal callbacks still fail;
  - admitted callback diagnostics retain first, last, count, and completed one-second buckets rather
    than every timestamp; all window lists are bounded by the fixed maximum session duration.

Tests compare old and incremental availability/cadence output byte-for-byte on the existing fixtures,
then prove constant collection cardinality across increasing synthetic frame counts. Changing the
released cadence-window semantics is a contract revision, not a streaming refactor.

### 6.2 Finalization sequence

Finalization executes exactly:

1. fence new camera callbacks;
2. drain or classify the single pending inference;
3. stop and finalize the video encoder for a video-enabled short run;
4. cancel the fixed-interval sampler, join its writer thread, then synchronously capture and append
   one terminal telemetry sample;
5. transition the writer to `SEALING`, after which every producer append is rejected;
6. append final aggregate records and flush;
7. fsync the finalized pose, video, frame-pairing, and telemetry files;
8. independently stream-read and reconcile pose line/count identities, video sample counts, and
   telemetry sequence/timestamps;
9. compute SHA-256 digests and byte lengths from finalized bytes;
10. create synchronization evidence from observed clocks and sample boundaries;
11. validate all required artifact relations;
12. write and fsync the synchronization-evidence file;
13. compute its SHA-256 and byte length;
14. re-read every payload and require both SHA-256 and byte length to equal the sealed value;
15. write manifest with all digests and final counts;
16. fsync manifest and attempt directory;
17. verify destination absence;
18. rename the attempt once into the committed export root;
19. fsync the committed-root parent directory.

Any failure before rename leaves classified staging residue and no committed session. A failure after
rename but before parent fsync is `uncertain_durability`; it does not retry under the same session ID.
A regression holds a live telemetry sampler across finalization and must fail until the stop/join
fence prevents every post-seal append.

The committed durable session artifact is authoritative for a sustained run. The Wi-Fi ADB
`am instrument -w` transcript is a convenience transport and display channel only; losing it after
the device has durably committed an attempt is a typed transport-loss observation awaiting binary-safe
recovery of the committed artifact, not evidence that the run failed or permission to rerun it.

## 7. Synchronized non-participant video

### 7.1 Recording boundary

Video is enabled only for short `live_conformance` runs used to prove synchronization and purge. The
implementation uses in-app `MediaCodec`/`MediaMuxer` encoding from the same `ImageAnalysis` frames
that feed preprocessing; CameraX `VideoCapture`/`Recorder` is not used. The muxer writes directly into
the session staging attempt and carries an application metadata track. Each metadata sample contains
the frame index, rebased camera-capture nanoseconds, and submitted encoder PTS, and is submitted with
the same PTS as its video sample. Encoder input presentation time is
`floor(rebased_camera_capture_timestamp_ns / 1_000)` microseconds. The video writer is owned by the
live composition and shares its lifecycle fence; it cannot outlive the camera source or publish
independently. Task 4 therefore includes `CameraXFrameSource`, `CameraXSourceLifecycle`, and the owned
frame fan-out contract in its write scope.

Video is excluded from 25-minute sustained characterization because encoding load would confound
the thermal, battery, and cadence question. The sustained records explicitly state
`video_enabled=false` and cite the separately admitted short-run synchronization evidence.

### 7.2 Additive contract

The released major-v1 schema remains backward compatible. `SessionManifest 1.2.0` adds three optional
top-level properties without extending the closed `media_reference.role` enum:

- `raw_pose_stream`: `{path_ref, sha256, schema_version}`;
- `synchronization_evidence`: `{path_ref, sha256, schema_version}`;
- `media_required`: boolean.

Before these contract changes are released, Task 3 runs a minimal write-demux round trip on the S25
using a known non-zero timestamp sequence. It records whether `MediaExtractor` returns identity PTS
or a single constant-offset transform. A non-constant transform is a blocking observation and stops
the contract release; it is not accommodated with a fitted tolerance.

After that prerequisite passes, the error-code registry advances to `1.3.0`, and
`contracts/CHANGELOG.md` records both changes. New M0.5B producers always emit `raw_pose_stream`. A
short synchronization run emits
`media_required=true`, exactly one existing-role `session_video`, and one top-level
`synchronization_evidence` reference. A sustained run emits `media_required=false`, no video, and no
synchronization reference.

`media_required` is not authoritative. Any `live_conformance` manifest containing a `session_video`
reference, any synchronization reference, or any published video bytes enters the full validation
path regardless of the flag. `true` without the required artifacts fails; false/absent with any video
or synchronization artifact also fails. The final instrument-readiness validator identifies the
short synchronization artifact by its evidence type and requires the media-enabled form; a producer
cannot skip the gate by changing the flag.

The record contains:

- schema version, session ID, video role/path/digest/byte count/container/codec;
- pose-stream digest and manifest session ID;
- camera timestamp domain and encoder timestamp domain;
- mapping method fixed to `camera_capture_to_container_pts_constant_offset_v1`;
- observed container offset in nanoseconds and exact drift `0 ns/s`;
- demuxed application-metadata samples containing frame index, rebased-capture nanoseconds, and
  submitted-PTS microseconds, bound by the synchronization-evidence digest;
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
- offset and drift appear together, the offset is derived rather than caller-declared, and drift is
  exactly zero;
- first/last ordering is monotonic in each domain;
- video and application-metadata tracks have identical demuxed PTS sequences;
- `container_offset_us = submitted_pts_us - demuxed_pts_us` is identical for every metadata sample,
  and each submitted PTS equals `floor(rebased_capture_ns / 1_000)` from that sample's payload;
- first/last metadata pairs match the observed first/last encoded samples;
- callback, encoded, dropped, submitted, skipped, emitted, and failed counts reconcile through named
  identities rather than one ambiguous total;
- `delivered_callbacks = encoded_video_frames + dropped_before_encoder + encoder_failures`;
- a media-required `live_conformance` session has exactly one `session_video`, one top-level
  `synchronization_evidence` reference, and one top-level `raw_pose_stream` reference.

The constant offset is container timestamp-origin behavior, not a quality threshold. Non-vacuity
comes from demuxing both published tracks, checking every metadata payload against the independently
recorded camera event stream, proving identical video/metadata PTS, proving one constant transform,
and reconciling all delivered frames. A varying transform is a failure, whatever its magnitude.
Estimated physical clock offset/drift remains deferred until a genuinely independent capture clock
exists, such as the later second-device slate protocol. No residual-magnitude acceptance threshold is
introduced in M0.5B.

The host transfer command uses binary-safe `adb exec-out`, writes to a temporary host file, verifies
byte count and SHA-256 against the manifest, then renames. Text-mode shell capture is prohibited.

### 7.3 Stable failures

Register at least these stable failures before implementation code consumes them:

- `SESSION.MEDIA_REQUIRED_MISSING`;
- `SESSION.MEDIA_REQUIREMENT_CONTRADICTED`;
- `SESSION.MEDIA_DIGEST_MISMATCH`;
- `SESSION.SYNC_EVIDENCE_MISSING`;
- `SESSION.SYNC_CLOCK_MAPPING`;
- `SESSION.SYNC_ENDPOINT_MISMATCH`;
- `SESSION.VIDEO_COUNT_MISMATCH`.

Each rule ships with a negative fixture and a mutation that makes its behavioral test fail, including
the specific case where video bytes/reference exist while `media_required` is absent or false.

## 8. Synthetic purge and tombstone proposal

### 8.0 Delivery split

Task 5 is deliberately split at the Task 4 synchronized-video dependency. **Task 5a** is
landable software safety work: the registry-only purge protocol, durable tombstones, permanent
re-admission fence, exact ownership predicates, storage-root invariants, registry digest binding,
and cryptographically random reserved synthetic session IDs. It may operate only on synthetic
fixtures and test-owned storage adapters; it makes no physical deletion claim.

**Task 5b** remains blocked on Task 4 and on the physical S25 gate. It is the only stage allowed to
rehearse a complete short video-enabled synthetic session, independently inspect the app-private
post-scan through host `run-as`, emit a device/host evidence record, provide its Make target and
build-contract guard, and change the pending ledger to recorded. A pose-only run is not a substitute
for this rehearsal. No Task 5a result may be represented as physical evidence or as a decision about
withdrawal, retention, backup propagation, or participant data.

### 8.1 Authority boundary

This section defines only the minimum mechanism needed to rehearse deletion with synthetic data. It
does not decide retention duration, withdrawal eligibility, backup propagation, administrator,
identity mapping, or legal semantics. The readiness decision remains `UNDECIDED`, and validators
reject any claim that this proposal settles it.

### 8.2 Tombstone proposal

The tombstone is outside purge-eligible artifact roots and contains only:

- tombstone schema version and explicit `policy_status: proposal`;
- reserved-namespace synthetic session ID;
- pseudonym class fixed to `synthetic_m05`, never the pseudonym value;
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

1. validate the session ID against the reserved synthetic UUID namespace
   `^00000000-0000-4000-8000-[0-9a-f]{12}$`;
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

M0.5B session generation uses cryptographically random 48-bit suffixes in that namespace and retains
the existing destination/no-replace collision check. `M05Admission` rejects any non-matching session
ID before capture. For a committed export, purge also reads and validates the manifest before any
deletion and requires `session_kind=live_conformance` plus the `synthetic-m05-` pseudonym class. A
crash residue with no manifest remains purgeable only because its directory name is in the reserved
UUID namespace and it is under a registry-resolved staging root. Participant-mode UUIDs are expressly
outside this namespace in any future implementation.

Required stable results include `PURGE.INVALID_SESSION_ID`, `PURGE.UNKNOWN_ROOT`,
`PURGE.PATH_ESCAPE`, `PURGE.SYMLINK_REJECTED`, `PURGE.DELETE_FAILED`,
`PURGE.POST_SCAN_RESIDUE`, `PURGE.DURABILITY_UNCERTAIN`, and `PURGE.SESSION_TOMBSTONED`.

The device test creates data only beneath test-owned synthetic roots, proves each conjunct by
mutation, and never runs a broad recursive delete against an unresolved path.

Task 5a's app-private implementation is serialized against capture. It rechecks the resolved
entry's device/inode immediately before each unlink and never follows a link, but it does not claim
immunity from a malicious concurrent filesystem writer: the residual assumption is the Android
app-private single-writer model. The physical rehearsal and independent host post-scan belong to
Task 5b.

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
cadence changes admission while camera request remains 30 FPS. The device gate emits one typed record
per cadence and a read-only host checker validates the records. A pending/recorded ledger under
`docs/evidence/m05b-attribution/` is updated only by the explicit human recording step; a missing,
pending, or blocking ledger blocks the sustained runs. A mismatch blocks long runs.

### 9.2 Telemetry

A versioned one-second fixed interval samples, from one scheduler and monotonic clock:

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
streamed durably and sequence-checked. For every sustained run, validation also derives and records
`fixed_sample_count`, first and last session-clock timestamps, and `max_inter_sample_gap_ns`. The
count must satisfy `abs(fixed_sample_count - (floor(actual_span_s) + 2)) <= 1`, the maximum gap must
not exceed two seconds, and an exceeded gap or missing end coverage is a typed blocking observation.
Production enforces the fixed upper bound for 1,500 seconds; it never retains a collection that grows
with frame count or elapsed time. Wall-clock time is not used for cadence or synchronization.

## 10. Frozen start-state envelope

The envelope is derived and committed as a non-participant evidence artifact before the first
sustained run. It contains:

- envelope schema and procedure version/digest;
- S25 model, build, API, app SHA, model SHA, runtime, delegate, and preprocessing versions;
- idle-baseline duration and sampling interval;
- admitted initial thermal status;
- battery-temperature range/variability admitted by the baseline;
- charging state fixed to unplugged;
- power-save mode fixed to `false`;
- observed battery level or charge counter recorded as a tagged, non-blocking start observation;
- connectivity state fixed to Wi-Fi ADB connected;
- ambient-condition identifier and operator-recorded constancy statement;
- keep-awake behavior and brightness mode/value;
- cooldown admission rule;
- camera cadence fixed to 30 FPS;
- inference-cadence set `{10,15,30}`;
- sustained run duration fixed to 1,500 session-clock seconds (25 minutes);
- stop-request overshoot limited to one telemetry interval (one second), with actual capture span
  always recorded;
- pre-registered execution order and order-artifact digest.

No threshold is chosen after seeing a sustained result. The envelope is derived once from the fixed
idle procedure, reviewed as evidence, and then frozen. Before each run a separate admission record
references the exact envelope and order digests and contains the observed start values. A failed
conjunct produces a typed blocked observation and does not start the run. Cooling waits do not relax
the envelope. The per-run cooldown observation is a required scalar
`battery_temperature_c`, with no default: admission requires it to be within the frozen baseline
minimum/maximum and requires the observed thermal status to equal the admitted initial status. Missing
or invalid values are blocking, never passing defaults.
Neither the envelope nor the observed-start wire object supplies synthetic defaults for load-bearing
thermal fields.

The order is recorded before any sustained run and cannot be reordered after observation. Every attempt
is an immutable append-only entry in a hash chain containing `attempt_sequence`, `cadence_fps`,
`admission_sha256`, `outcome`, and `previous_entry_sha256`. Admission derives the order position and
predecessor status from that chain; callers cannot supply either value. Only a completed, non-blocking
entry advances to the next cadence. Blocked attempts remain visible and a later attempt stays on the
same cadence. A rerun is a new chained attempt linked to the original condition and reason; it never
overwrites or replaces an earlier entry.

## 11. Sustained S25 characterization

All implementation code, including the sustained-run controller, must be complete and built as one
final app version before physical evidence is frozen. On that exact final build, execute the short
10/15/30 attribution gate, derive and freeze the Task 7 envelope/order, and immediately execute the
sustained runs. No app rebuild or source change may occur between attribution, envelope derivation,
and sustained capture because the app SHA is part of the frozen envelope.

Run exactly one 25-minute non-participant session for each configured inference cadence in the
pre-registered order. The same process boundary opens the staging attempt, writes and fsyncs the
admission artifact, then starts capture using the session-relative monotonic clock. The controller
requests stop at exactly 1,500 seconds on that clock. A stop request later than 1,501 seconds is a
typed blocking observation rather than a shorter/longer accepted run. All use:

- the same authorised physical S25 identity;
- camera cadence 30 FPS;
- unplugged power;
- Wi-Fi ADB and the same connectivity state;
- the same envelope, ambient state, brightness, keep-awake behavior, model, delegate,
  preprocessing, and app build;
- `video_enabled=false`;
- `empty_scene` or an admitted displayed synthetic figure, never a person.

Each run records the envelope/admission/order digests, start/end device identity, full stage counts,
target and actual monotonic duration, achieved cadence time series, latency histogram inputs and
percentiles, fixed-interval telemetry and coverage, thermal events, battery delta, source/process
outcome, and typed blocking observations. Latency uses a frozen bounded histogram with nanosecond
upper bounds `[1000000,2000000,4000000,8000000,16000000,32000000,64000000,128000000,256000000,
512000000,1024000000,2048000000,4096000000]` plus overflow, percentile ranks 50/95/99, and
nearest-rank selection. Each percentile reports its containing lower/upper bucket bounds; no
interpolated point estimate is reported. These are measurement-resolution constants, not acceptance
thresholds, and are frozen before the first sustained run.

The target must not run `pm clear` between sustained attempts. Before starting the next cadence it
must recover and verify the previous committed durable artifact (binary-safe transfer, byte count, and
SHA-256) and classify any remaining staging residue or unpublished attempt as a recorded blocked
attempt. A transport-lost run whose session was durably committed remains awaiting recovery and cannot
be rerun for that cadence. Only a proven `ABORTED` or `FAILED` attempt permits another attempt at the
same cadence, recorded as a new immutable hash-chained entry.

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
- recorded short 10/15/30 attribution evidence;
- frozen envelope and execution-order artifact;
- every immutable attempt entry, including blocked, aborted, failed, and transport-lost entries, plus
  all three completed start-admission and sustained-run records;
- app/model/runtime/device/probe SHAs;
- declared Android flagship-only scope and mid-range/iOS gaps.

Admission is true only when every required reference exists, hashes correctly, has the expected
schema/session/device identity, and contains no blocking result. A command that emits a complete
record with a blocking result exits with the designated non-zero gate status.

This admission means only “M0.5B non-participant instrument prerequisites passed on this S25.” It
does not authorize participant capture or accept any privacy decision.

## 13. Delivery DAG and write scopes

Implementation follows regression-first tests and the dependency order below. Each task lands as one
coherent commit after targeted checks and exact diff inspection, except Task 3's deliberately
separated observation-then-contract pair. Routine tasks do not receive an automatic second-model
review; privacy, synchronization, and final boundary contracts do.

1. **Registry and backup exclusion**
   Write scope: registry schema/data, Android manifest/resources, registry validators/tests.
   Delivers canonical roots, generated/verified exclusions, and pre-exclusion wipe evidence contract.
2. **Durable streaming writer**
   Depends on 1. Write scope: `SessionLedger`, `SessionRunner`, `LiveAchievedCadence`,
   `LiveCameraComposition`, common export/session core, Kotlin manifest derivation, and storage
   conformance tests. Removes every unbounded per-frame/per-submission collection, introduces the
   abort-on-first-failure rule, and preserves crash/process-kill and byte-identical cadence semantics.
3. **Synchronization contract**
   Depends on 2 because it extends the streamed publication contract. This task has two atomic
   commits: first a minimal S25 muxer/demuxer timestamp probe and its typed evidence; only after that
   proves one constant offset does the second commit release SessionManifest 1.2 additions
   (`raw_pose_stream`, `synchronization_evidence`, `media_required`), the Python semantic validator,
   Kotlin contract model/codec, error registry 1.3, changelog, and synthetic fixtures/tests. The
   contract-release commit is prohibited while the probe is missing or blocking.
4. **Android video and atomic publication**
   Depends on 2 and 3. Write scope: `CameraXFrameSource`, `CameraXSourceLifecycle`, owned-frame
   fan-out, Android in-app encoder/muxer adapter, storage binary primitives,
   composition/finalization, pairing sidecar, short device gate, demux verifier, and transfer verifier.
5. **Synthetic purge and tombstone proposal**
   **5a** depends on 1 and 2: purge/tombstone contracts, registry-only Android storage purge,
   permanent admission fencing, deterministic JVM/Android adapter tests, and repository guards.
   **5b** depends on 4: the complete short video-enabled physical rehearsal, independent host
   post-scan/checker, device Make target/build-contract, and recorded ledger. `withdrawal_and_deletion`
   remains `UNDECIDED` throughout both stages.
6. **Stage attribution and fixed telemetry**
   Depends on 2. Write scope: live pipeline event model, Android probes, telemetry stream, short
   10/15/30 attribution device gate, pending/recorded attribution ledger, and tests.
7. **Frozen envelope and order admission**
   Depends on 6. Write scope: envelope/order schemas, derivation/admission tools, device gate, evidence
   ledger validators. Its software/admission contract must be available before Task 8 controller
   integration consumes it; its physical envelope/order freeze occurs after the Task 8 controller is
   built and before Task 8's physical runs.
8. **Sustained characterization**
   Depends on 3, 4, 6, and 7 for physical characterization. Its software controller and evidence
   renderer are implemented before the physical Task 6 attribution gate and Task 7 envelope freeze;
   its three physical-device runs happen only after those gates pass on the exact final app build. No
   contract is weakened in response to an observation.
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
- bounded-memory streaming tests that inspect every named record, failure, callback, submission, and
  terminal collection at two large synthetic frame counts;
- crash points before/after each durable publication step;
- identity-clock, frame-pairing, media digest, endpoint, and count negative fixtures;
- a pre-release S25 muxer/demuxer probe using non-zero input PTS, blocking any non-constant
  container transform;
- a live-sampler finalization race proving telemetry is stopped and joined before sealing/digest;
- binary transfer/re-hash of a short device video;
- purge traversal, prefix, symlink, unknown-root, partial-delete, fsync, post-scan, idempotency, and
  tombstoned-ID re-admission negatives;
- stage-count and fixed-sample cadence mutations;
- fixed telemetry gap/end-coverage and bounded-sample mutations;
- envelope-conjunct and pre-registered-order mutations;
- scalar cooldown-temperature/default, power-save, hash-chain predecessor, and exact-byte digest
  mutations;
- fixed latency bucket-edge and percentile-rule mutations;
- admission-before-capture ordering, durable-artifact recovery, no-`pm clear`, and transport-loss
  retry-policy mutations;
- short attribution gate and recorded-ledger mutations;
- fixed 1,500-second duration and one-second stop-overshoot mutations;
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
- a committed durable session artifact is not recoverable after transport loss;
- a same-cadence rerun is proposed without a proven `ABORTED` or `FAILED` predecessor;
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
- the short 10/15/30 attribution gate is recorded and non-blocking on the exact final app build;
- the envelope and cadence order were frozen before sustained data existed;
- 10/15/30 inference-cadence S25 runs each target exactly 1,500 session-clock seconds under one
  unchanged start-state envelope, with camera at 30 FPS, unplugged Wi-Fi ADB, no video, and no
  participant;
- the final readiness record verifies every evidence digest and renders flagship-only scope plus
  mid-range/iOS gaps;
- no tracked participant artifact, model binary, credential, precise tombstone timestamp, or network
  upload path was introduced.

Completion permits M0.5C synthetic rehearsal to rely on the instrument artifacts. It does not permit
participant capture and does not establish signal reliability.
