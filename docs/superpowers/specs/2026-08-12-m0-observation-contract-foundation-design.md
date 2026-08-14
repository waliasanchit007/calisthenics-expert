# M0 Observation Contract Foundation Design

Status: approved direction, revised after independent Claude review

Date: 2026-08-12

Source of truth: `docs/central-handoff.md` and `docs/phase-0-plan.md`

## 1. Goal

Build an executable, platform-neutral contract for Phase 0 observation data before Android and iOS exporters exist. The milestone defines what the camera and pose system observed, validates those observations structurally and semantically, and prevents prohibited study data from entering Git.

The governing boundary is:

> Phase 0 freezes observation, not interpretation.

The result must be usable as the canonical acceptance target for future Android and iOS diagnostic harnesses without prematurely defining canonical poses, derived movement semantics, rep events, or product thresholds.

## 2. Scope

### Build now

- Versioned JSON Schema contracts for:
  - `SessionManifest`
  - `RawPoseFrame`
  - `Annotation`
  - `FixtureMetadata`
- Frozen preprocessing contracts for decoded-frame conformance fixtures and the live camera-to-model-input path.
- A Python 3.12 reference validator for structural and semantic checks.
- Synthetic valid and invalid fixtures containing no participant or biometric data.
- A repository guard for prohibited data and credential paths.
- Schema-evolution tests.
- A reference RGB24 scale/pad implementation with synthetic golden conformance vectors.
- A versioned validator error-code registry and schema for declared-negative fixture expectations.
- A GitHub Actions workflow that runs the same verification command as local development.
- One verification command for formatting, static checks, tests, fixture validation, and repository guarding.
- Architecture decision records for the material M0 choices made by this milestone.
- A schema release, compatibility, deprecation, and migration procedure.

### Explicitly defer

- `CanonicalPoseFrame`
- `ReliablePoseFrame` rules and `TrackingState` thresholds
- `DerivedFeatures` and movement-feature semantics
- `MovementEvent`, `RepEvent`, `HoldEvent`, and `PullUpState`
- Form classification and rep counting
- Android and iOS projects or MediaPipe integration
- Real participant data and study execution
- Product or benchmark acceptance thresholds

The reviewed M0 plan proposed placeholder `DerivedFeatures` and `MovementEvent` contracts plus an in-frame `debug_features` extension. This design deliberately supersedes that proposal: those artifacts describe interpretation, not observation, and freezing them before evidence would turn hypotheses into a public v1 contract. ADR-0001 records the deviation and changes OQ10 from "placeholder defined" to "deferred pending Phase 0 evidence." A later native-harness design may define a separate explicitly experimental diagnostic-measurement artifact; it will not be embedded in stable `RawPoseFrame v1`.

## 3. Design principles

1. **Lossless observation.** Preserve MediaPipe outputs without converting confidence values into booleans, smoothing coordinates, imputing missing landmarks, or applying product interpretations.
2. **Explicit coordinate systems.** Normalized image landmarks and world landmarks are separate fields. Both are retained when supplied by the backend.
3. **Topology-driven validation.** The declared landmark topology determines cardinality. The file format does not establish 33 landmarks as a permanent global invariant.
4. **Independent artifact versions.** Each artifact carries its own semantic schema version; app releases and schema releases are unrelated.
5. **Forward-compatible readers.** Compatible newer minor versions and unknown optional fields are tolerated. Unknown major versions fail before record processing.
6. **Structural and scientific checks are separate.** JSON Schema validates shape and primitive constraints. Python validates sequencing, consistency, topology, timing, and referenced content.
7. **No invented thresholds.** The validator detects contradictions and malformed data; it does not decide what pose confidence, FPS, tracking quality, or landmark noise is acceptable.
8. **Synthetic repository only.** Committed examples are deliberately generated and cannot be mistaken for real study data.

## 4. Repository shape

```text
contracts/
  schema/
    session_manifest.v1.schema.json
    raw_pose_frame.v1.schema.json
    annotation.v1.schema.json
    fixture_metadata.v1.schema.json
  preprocessing/
    decoded_frame_fixture.v1.md
    live_capture_to_model_input.v1.md
  tooling/
    expected_failure.v1.schema.json
    preprocessing_vector.v1.schema.json
    validator_error_codes.v1.json
  CHANGELOG.md
  schema-release-process.md
docs/
  adr/
    ADR-0001-observation-before-interpretation.md
    ADR-0002-canonical-preprocessing.md
    ADR-0003-contract-evolution-and-data-guard.md
reference/
  pyproject.toml
  src/observation_contracts/
    __init__.py
    __main__.py
    cli.py
    errors.py
    schema_loader.py
    versions.py
    topology.py
    structural.py
    semantic.py
    fixture_integrity.py
    preprocessing.py
    repository_guard.py
  tests/
fixtures/
  synthetic/
    valid/
      session/
    negative/
      timestamp_regression/
      session_switch/
      landmark_count/
      fixture_checksum/
    preprocessing/
      scale_pad_golden/
.githooks/
  pre-commit
.github/
  workflows/
    verify.yml
Makefile
```

Each Python module has one responsibility. Platform exporters depend only on the published contracts and fixtures, not on Python internals.

## 5. Stable observation contracts

### 5.1 Shared conventions

- JSON field names use `snake_case` across schemas and Python output.
- IDs use canonical lowercase UUID strings.
- Schema versions use `MAJOR.MINOR.PATCH` strings and are named per artifact, such as `raw_pose_frame_schema_version`.
- Monotonic timestamps use integer nanoseconds. Wall-clock timestamps, where needed for context, use RFC 3339 UTC strings and are never used for frame ordering.
- Unknown optional properties are retained and tolerated to permit same-major evolution.
- Required fields cannot silently change meaning within a major version.
- JSON Schema Draft 2020-12 is the structural schema dialect.

### 5.2 `SessionManifest v1`

The manifest contextualizes one exported session and is the only authoritative location for session-wide capture and backend metadata.

Required groups:

- Identity and versions:
  - `session_id`
  - `participant_pseudonym_id`
  - `session_manifest_schema_version`
  - `capture_protocol_version`
  - `harness_git_sha`
- Platform and device:
  - `platform`
  - `os_version`
  - `device_model`
  - optional `chipset`
  - `thermal_api_available`
- Pose pipeline:
  - `backend_name`
  - `backend_version`
  - `model_asset_name`
  - `model_asset_sha256`
  - `delegate`
  - `running_mode`
  - `num_poses`
  - configured confidence values, preserved as numbers rather than interpreted states
  - `landmark_topology`
- Camera and image:
  - camera facing, resolution, orientation, and mirroring
  - requested camera FPS
  - configured inference FPS
  - `model_input_width` and `model_input_height`, distinct from camera resolution
  - `inference_drain_timeout_ms`
  - live-capture preprocessing-contract version
  - fixture preprocessing-contract version when fixture replay is used
- Cadence:
  - configured inference FPS
  - achieved inference FPS time series
  - tracker-update cadence when the backend exposes it
- Clock and duration:
  - monotonic time-base name
  - `t0_monotonic_ns`
  - start and end wall-clock UTC timestamps
- Available observations:
  - normalized image landmarks available
  - world landmarks available
- Optional media or fixture references with a required role. The role set includes `session_video`, `static_reference_clip`, and `conformance_fixture`. Cross-device media synchronization records both offset and drift estimates.
- Counts with capture-path definitions:
  - `camera_frames_received`: buffers delivered by the camera source;
  - `inference_frames_submitted`: buffers submitted to the pose backend;
  - `inference_records_emitted`: NDJSON records written after completed backend calls;
  - `pose_detected_records`: emitted records with `pose_present: true`;
  - `frames_skipped_before_inference`: every received frame not submitted to inference, with reason counts for `cadence_selection`, `backpressure`, and `session_stopping`;
  - `trailing_frames_skipped_after_last_emitted_record`: skipped frames not attributable to a later emitted record at session end, or all skipped frames when no record was emitted;
  - `inference_failures`: submitted calls that failed before a `RawPoseFrame` could be emitted.
- Machine inference failures are a structured list separate from the human capture-quality failure log. Each entry records submission index, capture timestamp, `failure_source` (`harness` or `backend`), a stable harness classification (`backend_call_failed` or `session_teardown_timeout`), an optional opaque source/backend code, and optional sanitized detail. Runtime failure classifications are not validator error codes. The list length equals `inference_failures`.
- Human-observed capture-quality setup fields and a free-text failure log. These are observations, not a `CaptureQualityEngine` decision.

Study conditions remain an extensible `study_context` object instead of becoming rigid runtime fields. This keeps angle, distance, camera height, lighting, clothing, environment, and future protocol labels versioned with the study design.

### 5.3 `RawPoseFrame v1`

`RawPoseFrame` is NDJSON, one record for every completed inference call, including calls where no pose is detected. Backend calls that fail before producing a result are counted and described in the manifest failure log rather than fabricated as pose observations.

Required fields:

- `raw_pose_frame_schema_version`
- `session_id`
- `frame_index`
- `capture_timestamp_ns`
- `inference_start_ns`
- `inference_end_ns`
- `pose_present`
- `normalized_image_landmarks`
- `world_landmarks`
- `image_width`
- `image_height`
- `frames_skipped_since_last_emitted_record`

Landmarks preserve:

- topology index
- optional topology name
- `x`, `y`, and `z`
- nullable backend-provided `visibility` and `presence`, including low numeric values; `null` means the backend did not supply the value and no numeric sentinel is permitted

When `pose_present` is false, both normalized and world landmark fields are null. When a pose is present, normalized-landmark count must match the declared topology. World landmarks are either null when unavailable or a complete matching topology; partial world arrays are invalid because they cannot be aligned safely. A manifest that declares world landmarks unavailable requires every frame's `world_landmarks` to be null; a manifest that declares them available permits per-frame null only when the backend omitted the complete world result for that frame.

`frames_skipped_since_last_emitted_record` counts camera frames received but not submitted since the previous record-emitting completed call; on the first record it counts from session start. Skips before failed submissions remain accumulated until the next emitted record, so their total is not lost.

Backend, model, delegate, and topology metadata live in the manifest rather than being duplicated on every frame. The stream is joined to exactly one manifest by `session_id`. The artifact schema version must remain constant across the entire NDJSON stream.

### 5.4 `Annotation v1`

Annotation remains a deliberately small study-timeline contract rather than an exercise ontology.

Required fields:

- `annotation_schema_version`
- `annotation_id`
- `session_id`
- `annotation_protocol_version`
- `annotator_pseudonym`
- `label_tier`
- `annotation_stage`
- `input_annotation_ids`
- `events`
- `set_level`
- `media_reference`
- `created_at`

Each event contains:

- `event_id`
- `type`
- either `timestamp_ns` or a `start_timestamp_ns`/`end_timestamp_ns` range
- nullable `rep_index`
- nullable `label` with the closed values `valid`, `invalid`, and `uncertain`
- `invalid_reasons`, using the closed Phase 0 enumeration `partial_rom_top`, `partial_rom_bottom`, `kipping_or_leg_drive`, `assisted`, and `non_continuous`
- nullable `annotator_confidence` in the inclusive range 0–1
- an optional JSON scalar or object `value` for event-specific observational detail
- `source`
- optional `notes`

Initial event types are `rep_start`, `rep_top`, `rep_bottom`, `rep_end`, `rep_label`, and `tracking_failure`. A `rep_label` event requires `rep_index` and `label`. `label: invalid` requires at least one `invalid_reasons` value; every other label requires `invalid_reasons` to be empty. `label_tier` uses `gold`, `silver`, or `bronze`; Phase 0 human annotations are `gold`.

`annotation_stage` is `independent` or `adjudicated`. Independent passes require an empty `input_annotation_ids` array. An adjudicated artifact requires at least two distinct input annotation IDs, identifies the adjudicator through `annotator_pseudonym`, and is the benchmark ground-truth artifact. This preserves the independent inputs needed for agreement analysis while making consensus selection explicit. Version 1 requires `source: human`; the field exists so later majors can distinguish rule, user-feedback, teacher-model, and coach labels without redesigning the event envelope. `set_level` contains `athlete_reported_reps` and optional notes.

### 5.5 `FixtureMetadata v1`

Fixture metadata identifies a synthetic decoded-frame sequence used for repeatability and cross-platform conformance.

It records:

- `fixture_metadata_schema_version`
- fixture ID and explicit `synthetic: true`
- preprocessing-contract version
- frame width, height, pixel format, colour space, colour range, channel order, and orientation
- ordered frame entries containing frame index, timestamp, relative path, byte length, and SHA-256
- optional source-generation metadata that contains no participant information

SHA-256 protects referenced fixture bytes. This is ordinary integrity checking, not a content-addressable storage system.

## 6. Frozen decoded-frame preprocessing contract

Conformance fixtures bypass platform video decoders. The v1 feed format is:

- packed RGB24 bytes, row-major and tightly packed
- sRGB, full range, channel order R-G-B
- upright orientation with no EXIF-dependent transform
- dimensions declared in fixture metadata
- monotonic fixture timestamps mapped one-to-one by frame index

When a source image must be prepared for a fixture, the fixture generator:

1. applies orientation to produce an upright image;
2. converts to full-range sRGB RGB24;
3. scales to fit the declared target rectangle while preserving aspect ratio using bilinear interpolation;
4. rounds scaled dimensions to the nearest integer with half values rounded upward;
5. centres the image and assigns any odd padding remainder to the right or bottom;
6. fills padding with RGB `(0, 0, 0)`;
7. writes tightly packed bytes and records their SHA-256.

The first implementation validates prepared fixture bytes and metadata. A cross-platform fixture generator is not part of this milestone.

### 6.1 Live camera-to-model-input contract

The live contract governs the path the fixture bypasses. It converts each platform camera buffer into the same canonical model-input representation before inference:

1. Read and record source pixel format, colour matrix, colour range, orientation, mirroring, width, and height. A conformance session cannot start when required colour metadata is unavailable; no individual buffer is silently discarded or assigned an assumed matrix.
2. Convert the source buffer to upright, unmirrored, full-range sRGB RGB24 using the recorded matrix and range.
3. Scale, round, centre, and pad to the manifest's configured model-input dimensions using the exact algorithm in steps 3–6 of the fixture contract.
4. Feed the canonical pixel plane to the pose backend and record the live preprocessing-contract version in the manifest.

The Python package implements steps 3–6 as a reference RGB24 scale/pad function. Committed synthetic golden vectors cover aspect-ratio preservation, half-up rounding, odd right/bottom padding, and black fill; their expected bytes and hashes are validated by `make verify`. Platform adapters must match these vectors byte for byte.

Platform adapters may use optimized YUV conversion implementations only when byte-level synthetic conversion vectors for the declared source format match the reference output. Source-format-specific YUV vectors and adapters belong to the native-harness milestone; the shared RGB24 geometry, output contract, and acceptance rule are executable here.

## 7. Python reference validator

The validator is the canonical executable interpretation of the observation contracts for both future platform exporters.

### 7.1 Structural validation

- Load schemas by artifact kind and major version.
- Validate JSON manifests, annotations, and fixture metadata.
- Validate each NDJSON frame independently while retaining line numbers.
- Preserve unknown optional properties.
- Emit all structural failures that can be identified safely in one run.

### 7.2 Semantic validation

- Reject unknown schema majors before processing the artifact.
- Accept same-major newer minor and patch versions when required v1 fields remain present.
- Require one stable `session_id` across manifest, frame stream, and annotations.
- Require one stable raw-frame schema version across an NDJSON stream.
- Require strictly increasing `frame_index` values.
- Require strictly increasing capture timestamps and `capture_timestamp_ns <= inference_start_ns <= inference_end_ns`.
- Require topology-specific landmark cardinality for present poses.
- Require null landmark representations for absent poses and manifest-consistent world-landmark availability.
- Keep normalized and world observations in separate typed fields and reject exact serialized-array equality when both are present; do not impose coordinate-range assumptions or numeric tolerances in v1.
- Reconcile count identities: `camera_frames_received = inference_frames_submitted + frames_skipped_before_inference`; `inference_frames_submitted = inference_records_emitted + inference_failures`; emitted lines equal `inference_records_emitted`; detected rows equal `pose_detected_records`; the structured machine-failure list length equals `inference_failures`; and the sum of per-frame `frames_skipped_since_last_emitted_record` plus `trailing_frames_skipped_after_last_emitted_record` equals `frames_skipped_before_inference`.
- Detect missing or duplicate fixture frame indexes and timestamp regressions.
- Verify fixture byte lengths and SHA-256 values.
- Validate only structural timing impossibilities and count identities. Configured-versus-achieved FPS is reported as evidence and never produces a threshold-based validation failure.

The topology registry initially knows `mediapipe_pose_33_v1` and its 33 ordered landmarks. Unknown topologies fail with a specific unsupported-topology error rather than being misvalidated against 33.

Session shutdown stops new submissions and drains every in-flight call. A call still incomplete after the configured drain timeout becomes a structured `session_teardown_timeout` inference failure, ensuring every submitted call is classified exactly once and the count identities close.

### 7.3 CLI and errors

The package exposes:

```text
python -m observation_contracts validate-session <manifest> <frames.ndjson> [annotation ...]
python -m observation_contracts verify-fixture <fixture-metadata>
python -m observation_contracts verify-preprocessing-vector <vector-metadata>
python -m observation_contracts guard-repository [--staged] [repository]
```

Human output is concise and grouped by artifact. Successful commands print
`valid: <command>` and exit 0. `--format json` returns `[]` on success or a
stable machine-readable issue array on failure. Validation and usage failures
exit 2; unexpected internal failures exit 3 and are reported separately from
invalid user data. Every error contains:

- error code
- artifact path
- artifact kind
- JSON pointer or NDJSON line number when available
- concise message
- relevant expected and actual values when safe

Validation failures exit nonzero. Unexpected internal failures use a distinct exit code and never masquerade as invalid user data.

When multiple annotation paths are supplied, the validator resolves every adjudicated artifact's `input_annotation_ids` against the supplied independent artifacts, rejects missing or duplicate IDs, and verifies that all linked annotations share the session ID.

### 7.4 Stable error codes

`contracts/tooling/validator_error_codes.v1.json` is the registry for machine-readable validator errors. Codes are uppercase namespaced identifiers such as `FRAME.TIMESTAMP_ORDER` and `FIXTURE.CHECKSUM_MISMATCH`. Released codes are never renamed or reused for a different condition; replacement codes are added and old codes may be deprecated through the schema release process.

`contracts/tooling/expected_failure.v1.schema.json` validates each negative fixture's `expected_failure.json`. The tooling contract records its own semantic version, command, expected code, and artifact path. Unknown error codes or malformed expectation files fail the repository guard.

`contracts/tooling/preprocessing_vector.v1.schema.json` validates each reference vector descriptor. It records synthetic provenance, input/output dimensions, input and expected-output paths, byte lengths, and SHA-256 values. It is independent of `FixtureMetadata`, which describes decoded frame sequences rather than geometry-function pairs.

## 8. Repository guard

The guard examines tracked and staged paths and selected file signatures. It rejects:

- common video and audio containers
- raw pose or landmark dumps outside approved synthetic fixture paths
- consent forms and participant identity mappings
- identity-key directories and known authentication filenames
- obvious private keys or credential exports

The guard admits exactly three synthetic fixture classes:

- positive session fixtures below `fixtures/synthetic/valid/`, referenced by valid `FixtureMetadata` with `synthetic: true`;
- declared-negative fixtures below `fixtures/synthetic/negative/<case>/`, with a valid `expected_failure.json` naming the validator command, expected error code, and expected artifact path;
- preprocessing conformance vectors below `fixtures/synthetic/preprocessing/`, with a valid `preprocessing_vector.v1` descriptor, synthetic provenance, and verified input/output hashes.

The guard validates negative-fixture declarations without requiring intentionally malformed target artifacts to pass. Path checks are deterministic and explain which rule rejected a file. Tests include all three fixture classes plus allowed documentation and schema examples to constrain false positives.

The guard is callable directly and by `make verify`. A committed `.githooks/pre-commit` invokes the staged-path guard. `make bootstrap` idempotently configures `core.hooksPath=.githooks`; onboarding requires this command before write-mode work. `.github/workflows/verify.yml` runs `make verify` for pull requests and pushes, and `make verify` independently scans the tracked tree, so protection does not rely solely on local hook installation. ADR-0003 records this deliberate repository-local Git configuration and its residual pre-bootstrap local-commit risk.

## 9. Verification strategy

All behavior is implemented test-first.

Required test groups:

- Structural validity for every contract and committed example.
- Valid session and no-pose frame behavior.
- Timestamp and frame-index regressions.
- Mid-stream session-ID changes.
- Mid-stream schema-version changes.
- Participant-pseudonym presence and cross-artifact consistency where applicable.
- Topology-specific count failures and unsupported topologies.
- Nullable confidence values, complete/null world arrays, manifest availability mismatches, and invalid partial world arrays.
- Fixture missing frames, duplicate indexes, byte-length mismatch, and checksum mismatch.
- Annotation `valid`/`invalid`/`uncertain` labels, closed invalid reasons, rep indexes, confidence, tiers, and set-level fields.
- Independent and adjudicated annotation artifacts, consensus-input linkage, and invalid stage combinations.
- Count-identity reconciliation and static-reference media-role validation.
- Failed and teardown-timed-out inference accounting.
- Exact normalized/world array duplication rejection without numeric tolerances.
- Reference RGB24 scale/pad golden vectors.
- Error-code registry immutability and declared-negative expectation-schema validation.
- Preprocessing-vector descriptor validation and guard admission.
- Schema evolution:
  - known current major succeeds;
  - same-major newer minor/patch with unknown optional fields succeeds;
  - unknown major fails before semantic processing.
- Repository-guard positive and false-positive cases.
- CLI human and JSON output plus exit-code behavior.

`make verify` runs formatting checks, static analysis, the complete test suite, validation of committed positive fixtures, expected-failure execution for every declared-negative fixture, and the tracked-tree repository guard. It must be deterministic, offline after dependency installation, and safe in a clean clone.

## 10. Completion criteria

This milestone is complete when:

1. all four stable observation schemas, both preprocessing contracts, all three tooling contracts, the material ADRs, and the schema release/change procedure are versioned and documented;
2. the Python reference validator performs the structural and semantic checks above;
3. committed synthetic positive and negative fixtures exercise the contract boundaries;
4. schema-evolution behavior is proven by tests;
5. repository guarding blocks prohibited artifacts without rejecting approved synthetic fixtures or ordinary project files, and the committed GitHub Actions workflow runs that guard through `make verify`;
6. `make verify` succeeds from a clean environment;
7. Claude has implemented against the approved plan, Codex has independently reviewed the diff and tests, and all critical or important findings are resolved;
8. no real participant data, biometric-derived observations, consent records, credentials, or identity keys are tracked.

## 11. Decision and release records

The design is accompanied by:

- ADR-0001: stabilize observation contracts and explicitly defer `DerivedFeatures`, `MovementEvent`, and in-frame debug measurements;
- ADR-0002: use a canonical full-range sRGB RGB24 plane and one deterministic scale/pad algorithm for fixture and live model input;
- ADR-0003: use independent schema versions, Python as the reference validator, and an enforced repository-local data guard;
- `contracts/schema-release-process.md`: proposal, review, compatibility classification, release, deprecation, migration, and validator-error-code rules.

These records resolve the reviewed plan's D1/D2 requirements for decisions owned by this milestone. Future M0 decisions receive new ADRs rather than being appended silently to these records.

## 12. Next milestone boundary

After this foundation is accepted, the next design cycle selects one native diagnostic harness. That milestone will make the first exporter conform to these contracts and will supply the practical feedback needed before the second platform begins. Interpretation-layer contracts remain deferred until Phase 0 evidence supports them.
