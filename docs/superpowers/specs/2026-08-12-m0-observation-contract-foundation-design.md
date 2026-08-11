# M0 Observation Contract Foundation Design

Status: approved direction, revised after review

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
- A frozen preprocessing contract for decoded-frame conformance fixtures.
- A Python 3.12 reference validator for structural and semantic checks.
- Synthetic valid and invalid fixtures containing no participant or biometric data.
- A repository guard for prohibited data and credential paths.
- Schema-evolution tests.
- One verification command for formatting, static checks, tests, fixture validation, and repository guarding.

### Explicitly defer

- `CanonicalPoseFrame`
- `ReliablePoseFrame` rules and `TrackingState` thresholds
- `DerivedFeatures` and movement-feature semantics
- `MovementEvent`, `RepEvent`, `HoldEvent`, and `PullUpState`
- Form classification and rep counting
- Android and iOS projects or MediaPipe integration
- Real participant data and study execution
- Product or benchmark acceptance thresholds

An optional `debug_features` extension may carry non-authoritative on-device measurements in later harness exports. This milestone does not define or validate its movement semantics, and analysis code must not consume it as authoritative data.

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
  CHANGELOG.md
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
    repository_guard.py
  tests/
fixtures/
  synthetic/
    valid_session/
    invalid_timestamp_regression/
    invalid_session_switch/
    invalid_landmark_count/
    invalid_fixture_checksum/
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
  - input dimensions and preprocessing-contract version
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
- Optional media or fixture references with SHA-256 and synchronization metadata.
- Counts for attempted inference frames, emitted records, detected poses, and dropped frames.
- Human-observed capture-quality setup fields and a free-text failure log. These are observations, not a `CaptureQualityEngine` decision.

Study conditions remain an extensible `study_context` object instead of becoming rigid runtime fields. This keeps angle, distance, camera height, lighting, clothing, environment, and future protocol labels versioned with the study design.

### 5.3 `RawPoseFrame v1`

`RawPoseFrame` is NDJSON, one record for every attempted inference, including attempts where no pose is detected.

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
- `frames_dropped_since_last`

Landmarks preserve:

- topology index
- optional topology name
- `x`, `y`, and `z`
- backend-provided `visibility` and `presence`, including low numeric values

When `pose_present` is false, normalized landmarks are empty and world landmarks are null. When a pose is present, normalized-landmark count must match the declared topology. World landmarks are either null when unavailable or a complete matching topology; partial world arrays are invalid because they cannot be aligned safely.

Backend, model, delegate, and topology metadata live in the manifest rather than being duplicated on every frame. The stream is joined to exactly one manifest by `session_id`.

An optional `debug_features` object is an opaque extension point for diagnostic on-device measurements required by the later harness. Its description marks it non-authoritative, provisional, and excluded from reference semantic validation.

### 5.4 `Annotation v1`

Annotation remains a deliberately small study-timeline contract rather than an exercise ontology.

Required fields:

- `annotation_schema_version`
- `session_id`
- `annotation_protocol_version`
- `annotator_pseudonym`
- `events`
- `media_reference`
- `created_at`

Each event contains:

- `event_id`
- `type`
- either `timestamp_ns` or a `start_timestamp_ns`/`end_timestamp_ns` range
- a JSON scalar or object `value`
- `source`
- optional `notes`

Initial event types are `rep_start`, `rep_top`, `rep_bottom`, `rep_end`, `valid_rep`, `invalid_rep`, and `tracking_failure`. Version 1 requires `source: human`; the field exists so later majors can distinguish rule, user-feedback, teacher-model, and coach labels without redesigning the event envelope.

### 5.5 `FixtureMetadata v1`

Fixture metadata identifies a synthetic decoded-frame sequence used for repeatability and cross-platform conformance.

It records:

- `fixture_metadata_schema_version`
- fixture ID and explicit `synthetic: true`
- preprocessing-contract version
- frame width, height, byte stride, pixel format, colour space, colour range, channel order, and orientation
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
- Require strictly increasing `frame_index` values.
- Require nondecreasing capture timestamps and valid inference timing order.
- Require topology-specific landmark cardinality for present poses.
- Require empty/null landmark representations for absent poses.
- Require normalized and world coordinate spaces to remain distinct.
- Reconcile emitted-record, detected-pose, and dropped-frame counts with the manifest.
- Detect missing or duplicate fixture frame indexes and timestamp regressions.
- Verify fixture byte lengths and SHA-256 values.
- Compare configured FPS metadata with observed timestamps only for contradictions such as impossible or corrupt timing. It does not impose a performance threshold or fail ordinary variance.

The topology registry initially knows `mediapipe_pose_33_v1` and its 33 ordered landmarks. Unknown topologies fail with a specific unsupported-topology error rather than being misvalidated against 33.

### 7.3 CLI and errors

The package exposes:

```text
python -m observation_contracts validate-session <manifest> <frames.ndjson> [annotation]
python -m observation_contracts verify-fixture <fixture-metadata>
python -m observation_contracts guard-repository [repository]
```

Human output is concise and grouped by artifact. `--format json` returns a stable machine-readable array. Every error contains:

- error code
- artifact path
- artifact kind
- JSON pointer or NDJSON line number when available
- concise message
- relevant expected and actual values when safe

Validation failures exit nonzero. Unexpected internal failures use a distinct exit code and never masquerade as invalid user data.

## 8. Repository guard

The guard examines tracked and staged paths and selected file signatures. It rejects:

- common video and audio containers
- raw pose or landmark dumps outside approved synthetic fixture paths
- consent forms and participant identity mappings
- identity-key directories and known authentication filenames
- obvious private keys or credential exports

Synthetic fixtures are allowed only below `fixtures/synthetic/`, must be referenced by valid fixture metadata, and must declare `synthetic: true`. Path checks are deterministic and explain which rule rejected a file. Tests include allowed documentation and schema examples to constrain false positives.

The guard is callable directly and by `make verify`. Installing it as a local Git pre-commit hook is documented but not automatic; repository verification must work in fresh clones without mutating developer Git configuration.

## 9. Verification strategy

All behavior is implemented test-first.

Required test groups:

- Structural validity for every contract and committed example.
- Valid session and no-pose frame behavior.
- Timestamp and frame-index regressions.
- Mid-stream session-ID changes.
- Topology-specific count failures and unsupported topologies.
- Complete, null, and invalid partial world-landmark arrays.
- Fixture missing frames, duplicate indexes, byte-length mismatch, and checksum mismatch.
- Schema evolution:
  - known current major succeeds;
  - same-major newer minor/patch with unknown optional fields succeeds;
  - unknown major fails before semantic processing.
- Repository-guard positive and false-positive cases.
- CLI human and JSON output plus exit-code behavior.

`make verify` runs formatting checks, static analysis, the complete test suite, validation of committed valid fixtures, and the repository guard. It must be deterministic, offline after dependency installation, and safe in a clean clone.

## 10. Completion criteria

This milestone is complete when:

1. all four stable schemas and the preprocessing contract are versioned and documented;
2. the Python reference validator performs the structural and semantic checks above;
3. committed synthetic positive and negative fixtures exercise the contract boundaries;
4. schema-evolution behavior is proven by tests;
5. repository guarding blocks prohibited artifacts without rejecting approved synthetic fixtures or ordinary project files;
6. `make verify` succeeds from a clean environment;
7. Claude has implemented against the approved plan, Codex has independently reviewed the diff and tests, and all critical or important findings are resolved;
8. no real participant data, biometric-derived observations, consent records, credentials, or identity keys are tracked.

## 11. Next milestone boundary

After this foundation is accepted, the next design cycle selects one native diagnostic harness. That milestone will make the first exporter conform to these contracts and will supply the practical feedback needed before the second platform begins. Interpretation-layer contracts remain deferred until Phase 0 evidence supports them.
