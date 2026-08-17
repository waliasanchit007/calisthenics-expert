# Phase 0 Diagnostic Harness Design

Status: approved after bounded Claude Opus 5 architecture challenge and orchestrator reconciliation

Date: 2026-08-16

Source of truth: `docs/central-handoff.md`, the released M0 contracts, and ADRs 0001–0004

## 1. Goal

Build the first producer of the released observation contracts: a diagnostic harness that measures MediaPipe Pose Landmarker behaviour without introducing movement interpretation.

The milestone has two platform outcomes:

- Android is functional and is accepted on a physical Samsung Galaxy S25 Ultra running Android 16.
- iOS is compile-valid, link-valid, and architecturally ready for later native adapters. It does not capture, infer, or export observations in this milestone.

The deliverable is repeatable evidence about capture, preprocessing, pose output, cadence, failures, and thermals. It is not a rep counter, form grader, capture-quality engine, or production application.

## 2. Scope

### Build now

- Kotlin Multiplatform shared business logic.
- Compose Multiplatform shared UI and state.
- A separate Android application with CameraX and MediaPipe Tasks Vision adapters.
- A minimal iOS Xcode host for `iosArm64` and `iosSimulatorArm64`.
- Deterministic fixture replay before live capture.
- Exact implementation of the released preprocessing contracts.
- Raw normalized and world landmark export when supplied by MediaPipe.
- Typed lifecycle, capability, admission, runtime, drain, storage, and finalization failures.
- Device, camera, model, backend, delegate, cadence, latency, counter, and thermal observations.
- App-private crash-consistent session export validated by the Python reference CLI.
- SessionManifest and RawPoseFrame 1.1.0 additive releases required for a truthful producer.

### Explicitly defer

- Functional iOS camera, MediaPipe, storage, metadata, and thermal adapters.
- Synchronized participant video capture.
- Study execution and participant-data handling workflows.
- Reliable/canonical pose, smoothing, dropout interpolation, tracking-quality thresholds, movement events, reps, holds, scores, coaching, or custom ML.
- Persisted joint angles or other derived measurements. A debug overlay may calculate ephemeral geometry, but it cannot enter `RawPoseFrame`.
- Claims about optimal FPS, thermal acceptability, landmark quality, camera placement, or product readiness.

## 3. Architecture decision

Use Kotlin Multiplatform and Compose Multiplatform now, with one shared domain implementation and platform adapters behind narrow ports. Android supplies the functional adapters. iOS supplies explicit unavailable adapters and the same UI/state shell.

This supersedes the no-KMP and two-independent-native-harness sections of `docs/phase-0-plan.md`. The reason is recorded in ADR-0004. JSON Schema and the Python validator remain the language-neutral authority; KMP is an implementation-sharing decision, not a replacement contract.

## 4. Repository and module shape

```text
apps/diagnostic-harness/
  shared/                 # KMP library: contracts, SessionRunner, preprocessing, ports
  composeApp/             # KMP UI library: shared Compose UI/state and platform UI hooks
  androidApp/             # Android application plugin: manifest, Activity, permissions, DI root
  iosApp/                 # minimal Xcode host embedding the Compose controller
  model-assets/           # tracked provenance/checksum manifest, not participant data
```

The four-module split is required by AGP 9: a KMP module uses Google's Android-KMP library plugin and cannot also be the Android application. `androidApp` is a platform shell, not a third domain implementation.

### `shared/commonMain`

- Kotlin representations of released contract fields.
- `SessionRunner`, lifecycle state, counters, skip attribution, failure ledger, and finalization protocol.
- exact RGB24 scale-to-fit/letterbox transform and byte-level golden-vector support.
- ports for observation sources, pose execution, clocks, metadata, storage, and thermal sampling.
- capability and error types.

### `shared/androidMain`

- MediaPipe VIDEO and LIVE_STREAM adapters.
- camera metadata, clock, storage, filesystem durability, model-asset verification, and thermal adapters.
- source YUV-to-RGB conversion under admitted colour metadata.

### `composeApp/commonMain`

- shared screen state, actions, navigation, capability rendering, telemetry rendering, and export summary.

### `composeApp/androidMain`

- native camera-preview host and platform UI bridges only.

### `composeApp/iosMain`

- Compose entry controller and unavailable preview host.

### `androidApp`

- Android manifest, Activity, runtime permission integration, application lifecycle, and composition root.

### `iosApp`

- Xcode project and minimal application lifecycle that embeds the shared Compose controller.

Use ordinary interfaces and constructor injection. Use `expect`/`actual` only where a platform symbol or entry point cannot be expressed through an injected interface.

## 5. Authoritative session state

`SessionRunner` is the only component allowed to advance session lifecycle, mutate counters, classify a received frame, assign submission indices, append authoritative records or failures, begin drain, or publish a completed export.

All mutable `SessionRunner` state is confined to one single-threaded coroutine dispatcher. CameraX, MediaPipe, UI, storage, and timer callbacks post immutable events to that dispatcher. They never mutate session state directly.

Each submission index has exactly one terminal state:

```text
pending -> emitted
pending -> failed
pending -> timed_out
```

A terminal state cannot transition again. At drain timeout, `SessionRunner` installs a fence and classifies every unresolved submission as `timed_out`. A callback arriving after that fence is discarded as a non-event: it cannot change a counter, append NDJSON, or rewrite a failure. Manifest assembly starts only after the writer and event queue are quiescent. Fence state is monotonic: `Running -> Draining(deadline) -> Sealed -> Assembling -> Published | Failed`; all transitions occur on the single dispatcher, the only fence check is at event admission before dispatch to a handler, post-Sealed events are dropped with a non-contract diagnostic count, and deadline expiry is itself a posted event.

Terminal states are single-assignment. Reassignment throws and fails the session. Counters increment at ACCEPT time, and finalization performs a pre-publish self-check after NDJSON close and fsync: it re-reads the staged stream and requires line count to equal sealed `inference_records_emitted` and pose-present count to equal sealed `pose_detected_records`. Any mismatch, or any earlier write/flush/fsync failure, produces `Failed` and no publish. The finalization result is an immutable sealed snapshot containing sealed counters and the terminal map.

These rules preserve the released identities:

```text
camera_frames_received
  = inference_frames_submitted + frames_skipped_before_inference

inference_frames_submitted
  = inference_records_emitted + inference_failures

inference_records_emitted
  = number of NDJSON records

inference_failures
  = number of machine_inference_failures
```

Common property tests cover stop during an in-flight call, backend failure, missing callback, timeout, late callback after timeout, no-result pose output, and repeated stop requests.

## 6. Contract release 1.1.0

The current v1.0.0 manifest cannot describe fixture replay truthfully and cannot retain the colour/timestamp provenance required by ADR-0002. The harness must not work around this with invented observations. Before any Kotlin producer is admitted, release backward-compatible v1.1.0 changes through `contracts/schema-release-process.md`.

### SessionManifest additions

- Optional `session_kind` with values `live_study`, `live_conformance`, and `fixture_replay`. A missing value retains v1.0.0 `live_study` semantics.
- Add `fixture` to the camera source/facing vocabulary for fixture replay.
- Optional camera provenance fields:
  - `source_pixel_format`
  - `source_color_matrix`
  - `source_color_range`
  - `source_dataspace_raw`
  - `timestamp_source`
  - `delivery_semantics`
- `fixture_replay` requires exactly one `conformance_fixture` media reference and does not require `static_reference_clip`.
- `live_study` retains the exactly-one-`static_reference_clip` rule.
- `live_conformance` records only media that actually exists and does not inherit study-only media requirements.
- Stable validator code `SESSION.MEDIA_ROLE` distinguishes an invalid session-kind/media-role combination from generic count failures.

For `fixture_replay`, required v1 camera fields describe the fixture source: `facing=fixture`, source dimensions, the declared orientation/mirroring, and requested replay cadence derived from fixture timestamps. Capture-quality fields are human-observed properties of the synthetic fixture and use explicit `synthetic_*` labels; they are not copied from a participant session. The participant pseudonym follows a `synthetic-*` convention.

### RawPoseFrame addition

- Optional `backend_timestamp_ms`, recording the monotonic timestamp passed to MediaPipe after millisecond quantization.

The timestamp is:

```text
max(floor(capture_timestamp_ns / 1_000_000), previous_backend_timestamp_ms + 1)
```

Validator semantics are intentionally weaker than this producer construction formula. For
emitted records, `backend_timestamp_ms` is optional: missing values are skipped, and carried
values are compared with the previous carried value across any intervening missing records.
Every carried value must be strictly greater than that previous carried value and must be at
least `floor(capture_timestamp_ns / 1_000_000)` for its own record. Mixed presence is accepted;
the validator does not require all-or-none presence, exact equality to the capture-time floor
or previous value plus one, or first-value equality to the capture-time floor. A failed
submission may consume an unrecorded backend timestamp. Harness-side replay or producer
conformance checks may enforce all-or-none presence separately, but that is not a validator
rule.

### Release gate

The release follows the existing checklist: regression first, schema and validator updates, a positive replay fixture, declared-negative session-kind/media fixture, error-code registry update, changelog, independent non-implementing review, clean `make verify`, clean-clone verification, and an immutable Git tag. Kotlin models target 1.1.0 only after the release is complete.

Generated Kotlin observation artifacts stay in untracked build output and are validated there by the Python CLI. Any committed golden observation must live under `fixtures/synthetic/valid/<group>/`, be declared by conforming fixture metadata, and use the repository guard's allowed filenames.

## 7. Deterministic replay

Replay is implemented and accepted before live capture. It consumes a synthetic RGB24 fixture through the released decoded-frame and scale/pad contracts and invokes MediaPipe in VIDEO mode synchronously.

Fixture timestamps are rebased onto the session monotonic clock. The pacer waits until each rebased capture instant and never drops because processing is late; it may fall behind while preserving the submitted sequence. `inference_start_ns` must be at or after the rebased `capture_timestamp_ns`.

“Deterministic replay” means:

- identical ordered fixture frame indices are submitted;
- every submitted model-input RGB24 plane has the same SHA-256 across runs;
- preprocessing metadata and backend timestamps are identical for the same fixture timeline;
- model, backend, options, and delegate are pinned;
- output topology and cardinality are compared per frame;
- exact landmark hashes and numeric deltas are reported as observations, not turned into an invented pass threshold.

Session IDs, wall-clock times, rebased monotonic timestamps, and inference latency differ by construction and are excluded from equality. The initial replay delegate is CPU to minimize undeclared device-specific GPU variation. No cross-platform numeric-equivalence claim is made until a functional iOS backend processes the same input sequence.

The Android device gate is at least five complete runs of the same fixture on the S25 Ultra. Every export must validate. Five runs are evidence collection, not an assertion that five proves statistical stability.

## 8. Live Android capture

### Early capability probe

Slice 1 includes a physical-device probe before the live adapter is built:

- read `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE` for the selected camera;
- acquire one YUV analysis buffer and read its `android.media.Image.dataSpace` through CameraX's nullable experimental underlying-image bridge;
- record camera ID, timestamp source, raw DataSpace value, decoded colour standard/range, device model, OS build, CameraX version, and probe code SHA as non-participant evidence.

Live admission requires `REALTIME` timestamps and known colour metadata. If the S25 reports `UNKNOWN` or the underlying image/DataSpace is unavailable, the harness remains fail-closed and the milestone stops for a material scope/contract decision. It does not silently weaken admission or claim replay-only completion of the approved live target.

### CameraX boundary

- Output is YUV_420_888; CameraX RGBA conversion is forbidden because it does not expose the required conversion provenance.
- Backpressure is `STRATEGY_KEEP_ONLY_LATEST`.
- `camera_frames_received` counts buffers delivered to the analyzer callback whose callback events are admitted before the fence; callbacks arriving after the fence are dropped and do not increment the counter. This fence-relative definition prevents post-Sealed delivery from changing the sealed snapshot.
- CameraX may replace a buffer before analyzer delivery. That upstream loss is not observable and is never invented as a skip reason. The gap between requested and delivered callback cadence is reported explicitly as upstream/unclassified cadence loss.
- Within the observable boundary, every admitted delivered callback is exactly one of submitted or skipped for `cadence_selection`, `backpressure`, `preprocessing_failed`, or `session_stopping`.
- The analyzer copies all required planes and metadata into owned immutable bytes, then closes `ImageProxy` immediately. It never waits for pose inference.

Using `STRATEGY_BLOCK_PRODUCER` would move delay into the camera pipeline without making sensor-side frame production observable, so it is rejected for this milestone.

### Clock and colour admission

- `ImageInfo.timestamp` is the authoritative capture timestamp.
- When the timestamp source is `REALTIME`, inference timing uses `SystemClock.elapsedRealtimeNanos` so values share a comparable domain.
- `UNKNOWN` is not remapped to uptime or another clock.
- Before session start, record source format, colour standard, colour range, raw DataSpace, orientation, mirroring, source width, and source height.
- Capture width/height describe the source buffer before orientation. Canonical scale/pad operates on the upright post-rotation plane.

### MediaPipe boundary

- Exactly one LIVE_STREAM call may be in flight.
- The in-flight slot stores submission index, capture timestamp, backend timestamp, inference start, and the immutable input.
- The slot is released only by a result/error callback or the drain timeout fence, never by `detectAsync` returning.
- MediaPipe's internal detector resize and tracker-derived ROI are backend behaviour, not harness-controlled canonical preprocessing. Pin and record model SHA, Tasks Vision version, delegate, running mode, and all task options.
- No external ROI is supplied in this milestone.
- A backend error becomes `backend_call_failed`. Missing completion at the drain deadline becomes `session_teardown_timeout`; the harness does not fabricate a backend error.
- `inference_frames_submitted` increments only after preprocessing succeeds and immediately before the inference call is dispatched. A frame whose preprocessing fails is not a submission and is attributed to the `preprocessing_failed` skip reason.

## 9. Preprocessing

The released decoded-frame and live-capture preprocessing contracts are normative.

- Fixture replay starts from tightly packed RGB24 sRGB full-range bytes.
- Live conversion uses the admitted YUV colour standard/range and source plane layout.
- Orientation and mirroring are applied before canonical scale-to-fit.
- Scale-to-fit uses center-of-pixel mapping, exact-fraction bilinear sampling with half-up channel rounding, and exact black padding as released.
- Source-format YUV byte vectors cover row stride, pixel stride, odd dimensions, rotation, mirroring, matrix, and range cases.
- Every record asserts `image_width` and `image_height` equal the declared model-input dimensions.

No crop, detector ROI, smoothing, coordinate clipping, landmark imputation, or confidence threshold is added.

## 10. Export and crash consistency

All session construction occurs in app-private staging outside the directory scanned as valid exports. The layout is `Context.filesDir/diagnostic-harness/staging/<session_id>.<nonce>.staging/` for attempts and `Context.filesDir/diagnostic-harness/exports/<session_id>/` for committed sessions. A committed session directory contains `manifest.json` and `frames.ndjson`; the exports-root scanner reports any directory lacking `manifest.json` as corruption and never skips it silently. An empty directory in the exports root is therefore corruption evidence: the protocol never creates one there, and cleanup cannot turn it into a valid session.

Commit sequence:

1. close and flush the NDJSON writer;
2. fsync NDJSON;
3. reconcile the staged stream against the sealed counters;
4. write and fsync the manifest;
5. fsync the staging directory;
6. check that the destination does not exist, then rename within the same filesystem into the exports root;
7. fsync the exports root.

Android directory sync uses `android.system.Os.open(path, O_RDONLY, 0)` and `Os.fsync`. The storage port exposes exactly these primitives: `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`. `renameNoReplace` is implemented honestly as check-then-rename; POSIX rename replaces an empty destination directory and only fails for a populated one, so the residual race is documented and accepted because publish is single-process and dispatcher-confined. A capability probe verifies the primitives on the target filesystem. The five primitives' API shape exists so probes 5 and 8 are writable without moving the invariant into `androidMain`.

The commit unit is the session directory. The fixed sequence is fsync stream, reconcile, write and fsync manifest, fsync staging directory, publish, then fsync the exports root. Each finalize attempt uses a unique staging directory. Any failure fsyncs an abort marker and permanently abandons that directory; it is never re-entered. A deliberate abort writes an fsynced abort marker containing a sanitized reason and sealed counters, so crash residue is classifiable by marker absence. A second finalize joins the first result; publish occurs only inside the single `Assembling -> Published` transition. Staging and exports remain below `Context.filesDir`, never external storage.

Zero-frame sessions are valid diagnostic sessions: if admission, sealing, stream reconciliation, manifest validation, and the commit sequence all succeed with zero records, the session publishes with zero-frame counters. A zero-frame session is not silently treated as missing or failed input.

Probe 8 covers both destination cases: a pre-existing non-empty target causes rename to fail with the target untouched; a pre-existing empty target is refused by the existence pre-check, because bare rename would silently clobber it. A mutation deleting that pre-check must make the empty-target test RED. The scanner must report the resulting empty directory as corruption.

### Numbered Slice-3 decisions

These decisions are normative for the shared deterministic core and its diagnostic probes:

1. Fence is a monotonic state transition (`Running -> Draining(deadline) -> Sealed -> Assembling -> Published | Failed`), all transitions occur on the single dispatcher, the only fence check is at event admission before handler dispatch, post-Sealed events are dropped with a non-contract diagnostic count, and drain-deadline expiry is a posted event.
2. Terminal states are single-assignment. Reassignment throws and fails the session.
3. Counters increment at ACCEPT time. Finalization performs a pre-publish self-check after NDJSON close and fsync, requiring line count to equal sealed `inference_records_emitted` and pose-present count to equal sealed `pose_detected_records`; any mismatch or earlier write/flush/fsync failure means `Failed` with no publish.
4. The sealed snapshot is an API: finalization returns sealed counters and the terminal map.
5. The commit unit is the session directory. Per-attempt staging is `<session_id>.<nonce>.staging` under the same `filesDir` mount as the exports root, and the sequence is fsync stream, reconcile, write+fsync manifest, fsync staging directory, publish, fsync exports root.
6. The storage port exposes `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`. `renameNoReplace` is implemented as check-then-rename; the residual race is documented and accepted because publish is single-process and dispatcher-confined.
7. An empty directory in the exports root is corruption evidence. The protocol never mkdirs there, rename creates the entry, cleanup cannot delete it, and the scanner reports a directory lacking `manifest.json` rather than skipping it.
8. Second finalize is a join, not a rerun. Publish executes only inside the single `Assembling -> Published` transition.
9. Staging is unique per attempt. Any failure permanently abandons that directory after an fsynced abort marker; a staging directory is never re-entered.
10. Deliberate abort writes an fsynced abort marker with a sanitized reason and sealed counters, so crash residue is classifiable by marker absence.
11. Staging and exports root are both under `Context.filesDir`; external storage is never used.

### Mandatory Slice-3 RED/GREEN probes

The decisions above and the probes below have separate numbering. Every probe is mandatory evidence:

1. Monotonic fence transitions, the admission-only fence check, post-Sealed diagnostic drops, and posted drain-deadline expiry.
2. Single-assignment terminal map; attempted reassignment throws and fails the session.
3. ACCEPT-time counters plus staged-stream line-count and pose-present reconciliation, with mutations of each sealed counter making the probe RED.
4. Immutable finalization result carrying the sealed counters and terminal map.
5. Fixed commit order and ordinal fault injection at every operation/boundary across `appendLine`, `flush`, `fsyncFile`, `fsyncDirectory`, and logical `renameNoReplace`.
6. Exports-root corruption scanning reports an empty directory or any directory missing `manifest.json`.
7. A second finalize joins the first result and publish executes only once.
8. Destination collision: a pre-existing non-empty target produces `Failed` and remains untouched; a pre-existing empty target is refused by the existence pre-check; deleting that pre-check makes the empty-target test RED.
9. Unique staging per attempt and permanent abandonment after any failure.
10. Deliberate abort writes an fsynced sanitized marker with sealed counters, while marker absence identifies crash residue.
11. Staging and exports remain under `Context.filesDir`, and a valid zero-frame session publishes.

The five primitives' API shape exists so probes 5 and 8 are writable. The sealed-snapshot API exists so probe 4 is writable. The single-assignment terminal map exists so probe 2 is writable. This boundary is required because a coarse port makes probes 1, 2, and 7 unwritable and pushes the invariant into `androidMain`, which ADR-0004 exists to prevent.

A crash before rename can leave staging residue but cannot create a partially valid export. A startup cleanup operation may identify stale staging entries, but cannot publish or silently delete them. The valid-export reader only scans the committed exports root.

No network permission is declared. Exports remain app-private until an explicit user-selected system share/export action. Error details are sanitized and contain no frame bytes, landmarks, participant identifiers beyond the pseudonym, or filesystem secrets.

## 11. Capabilities and failure model

Capability state is typed, observable, and fail-closed.

Android capabilities include camera permission, compatible camera, timestamp comparability, colour metadata, model asset integrity, storage durability, MediaPipe availability, and thermal API availability. Permission denial/revocation, initialization, admission, preprocessing, backend, drain, storage, and finalization failures have distinct internal types.

iOS adapters return `CapabilityUnavailable` with a stable reason. Session start is disabled. iOS never emits placeholder observations or synthetic success.

Only completed, validated sessions appear as exports. An incomplete or invalid session may produce a diagnostic failure summary in app state, but it is not presented as a valid observation artifact.

## 12. UI

Shared Compose UI owns four states:

1. diagnostics and capability matrix;
2. Replay/Live configuration;
3. active session monitor;
4. completion/export summary.

The Android monitor may show camera preview and MediaPipe skeleton overlay. It also shows configured and delivered/submitted/emitted cadence, received/submitted/emitted/skipped/failure counters, inference latency, and thermal observations. These values are diagnostics, not quality scores.

iOS renders the same shell, shows its unavailable capabilities, and has no enabled start action. Camera preview remains a native platform host inside shared UI chrome.

Inference choices are 10, 15, and 30 FPS. Android requests a nominal 30 FPS camera stream at 1280x720 YUV where supported. None is labelled optimal.

## 13. Model asset and dependency provenance

Use one Pose Landmarker Full model initially. A tracked provenance manifest records the immutable source URL, upstream licence, asset filename, byte length, and SHA-256. A deterministic build task fetches the asset into ignored local cache when absent and rejects any checksum mismatch. The packaged asset is verified again at runtime before a session can start. The binary is not committed to ordinary Git history or fetched through an unpinned `latest` URL.

All Gradle and library versions are exact and centralized in a version catalog. The initial compatible baseline, verified against official matrices on 2026-08-16, is:

- Kotlin 2.4.10;
- Compose Multiplatform 1.11.1;
- Android Gradle Plugin 9.1.0;
- Gradle 9.3.1;
- JDK 21 build runtime with JVM/Android bytecode target 17;
- CameraX 1.6.1;
- Android compile/target SDK 36 and minimum SDK 33;
- iOS deployment target 16.0.

The MediaPipe Tasks Vision exact release is pinned only after its current stable artifact and Pose Landmarker API compile in the foundation spike; the selected version and evidence are committed before dependent code. Dynamic dependency versions are forbidden.

## 14. Verification

### Common deterministic checks

- golden preprocessing vectors byte-for-byte;
- lifecycle and counter property tests;
- failure, drain fence, and late-callback tests;
- export commit-order tests with fault injection;
- Kotlin-generated synthetic exports validated by the Python reference CLI;
- schema-version and unknown-major behaviour.

### Android checks

- camera capability probe on the S25 Ultra;
- YUV source-format vectors;
- timestamp and colour admission;
- model provenance and runtime checksum;
- VIDEO replay and LIVE_STREAM callback/slot behaviour;
- application assembly and instrumentation smoke;
- process-kill during export leaves no partial entry in the valid exports root.

### iOS checks

- `iosArm64` framework link;
- `iosSimulatorArm64` framework link;
- Xcode host build and simulator shell smoke;
- common UI renders unavailable capabilities and cannot start a session.

### Integration evidence

- at least five same-fixture replay runs on the S25 Ultra;
- identical submitted indices and model-input hashes;
- output hashes/deltas reported without invented tolerance;
- every export passes the Python validator;
- one short live session after capability admission, using an empty scene or displayed synthetic fixture rather than participant data, with reconciled counters and valid export;
- local iOS build evidence from the existing Mac.

Existing protected Ubuntu CI runs Python verification plus compatible KMP/Android unit and assembly checks. No recurring macOS GitHub runner is added in this milestone because that introduces billed external cost; local iOS evidence is recorded in the PR. Adding paid CI or release infrastructure remains a human decision.

## 15. Delivery slices

1. **Foundation and device probe** — contract proposal, ADR/spec/gate, four-module Gradle/Xcode shell, Android and iOS compile/link, pinned toolchain, S25 timestamp/DataSpace/filesystem probe.
2. **Contract release 1.1.0** — regression-first schema/validator/fixtures/error-code/changelog, independent release review, tag.
3. **Shared deterministic core** — 1.1.0 Kotlin models, preprocessing, single-dispatcher `SessionRunner`, export protocol, build-output conformance tests.
4. **Android replay** — pinned MediaPipe/model, VIDEO adapter, UI/export, five-run S25 evidence.
5. **Android live** — CameraX/YUV, admission, LIVE_STREAM slot, telemetry/finalization, short S25 evidence.
6. **Slice boundary** — full deterministic verification, one integration run, one bounded full-range review, protected CI, and integration handoff.

No routine task receives an independent model review. Critical/Important review is mandatory for the contract release and the final slice boundary. A second-model consultation during implementation occurs only for a concrete unresolved architecture, trust/concurrency, persistence, or data-semantics issue.

## 16. Stop and falsification conditions

- If the S25 cannot provide comparable camera timestamps or known colour metadata, do not weaken the contract. Stop for a material scope/architecture decision.
- If exact live preprocessing cannot match committed source-format vectors, live capture is not admitted.
- If SessionRunner property tests find a submitted call that is unclassified or classified twice, no export is published.
- If an interrupted export appears in the valid exports root, the storage design is rejected.
- If deterministic replay cannot preserve the submitted sequence and input hashes, live integration does not start.
- If KMP/Compose introduces platform divergence that cannot be isolated behind the declared ports, reconsider the shared boundary before adding iOS functionality.
- If MediaPipe output is not useful under reasonable guided capture conditions, Phase 0 reports that evidence; it does not hide failure by building interpretation layers.
