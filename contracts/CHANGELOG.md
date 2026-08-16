# Observation Contract Changelog

This changelog records releases of the observation schemas and their supporting
tooling contracts. Application, harness, model, and analysis-package versions
are independent of these contracts.

## [1.1.0] - 2026-08-16

Backward-compatible minor release of the observation contracts required by the
Phase 0 diagnostic harness. Existing 1.0.0 artifacts remain valid; the
released schemas remain under their immutable v1 filenames.

### Observation contract changes

- `SessionManifest v1.1.0` adds optional `session_kind`, with the recognized
  values `live_study`, `live_conformance`, and `fixture_replay`. When
  `session_kind` is absent, readers preserve the 1.0.0 `live_study` semantics.
- `SessionManifest v1.1.0` adds `fixture` to the `camera.facing` vocabulary.
- `SessionManifest v1.1.0` adds these optional camera provenance fields:
  `source_pixel_format`, `source_color_matrix`, `source_color_range`,
  `source_dataspace_raw`, `timestamp_source`, and `delivery_semantics`.
- `RawPoseFrame v1.1.0` adds optional non-negative integer
  `backend_timestamp_ms`, the monotonic millisecond timestamp passed to the
  pose backend after quantization.
- `SessionManifest v1.1.0` adds session-kind-dependent semantics for the
  existing `media_references.role=conformance_fixture` value. A
  `fixture_replay` session must contain exactly one `conformance_fixture` and
  no `static_reference_clip`; a `live_study` session retains its exactly-one
  `static_reference_clip` rule; `live_conformance` has no study-only media
  requirement. The role vocabulary itself is unchanged by 1.1.0.

### Validator tooling

- `SESSION.MEDIA_ROLE` is the stable validator error code for an invalid
  session-kind/media-role combination. It is distinct from
  `SESSION.COUNT_MISMATCH` and is exercised by the declared-negative
  `replay_media_role` fixture.
- In the older live-study behavior, the missing or invalid static-reference
  media-role condition was reported as `SESSION.COUNT_MISMATCH`; 1.1.0
  replaces that media-role use with `SESSION.MEDIA_ROLE`. The legacy code
  remains active for numeric/count accounting and is not removed or reused.
  Machine consumers supporting both validator behaviors must accept
  `SESSION.COUNT_MISMATCH` for the legacy media-role condition and
  `SESSION.MEDIA_ROLE` for the 1.1.0 condition, while continuing to treat
  `SESSION.COUNT_MISMATCH` as the count-accounting code elsewhere. See the
  [deprecation policy](schema-release-process.md#deprecation) and
  [migration policy](schema-release-process.md#migration).
- The reference validator package remains version `0.1.0` for this release.

### Fixtures and provenance

- The positive synthetic replay fixture is
  [`fixtures/synthetic/valid/fixture_replay`](../fixtures/synthetic/valid/fixture_replay/).
- The declared-negative synthetic replay fixture is
  [`fixtures/synthetic/negative/replay_media_role`](../fixtures/synthetic/negative/replay_media_role/)
  and must produce only `SESSION.MEDIA_ROLE` through `validate-session`.
- The existing synthetic session, four earlier declared-negative cases, and
  preprocessing vector remain part of the release inventory. No real
  participant data, video, audio, identifiers, or downloaded media is added.

### Decision records

- [`ADR-0004`](../docs/adr/ADR-0004-kmp-android-first-diagnostic-harness.md)
  records the diagnostic-harness architecture that consumes this release.
- The [locked Phase 0 diagnostic harness design](../docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md)
  defines the 1.1.0 contract boundary and replay semantics.

## [1.0.0] - 2026-08-14

Initial stable observation-contract release for the Phase 0 observation boundary.

### Stable observation contracts

- [`SessionManifest v1`](schema/session_manifest.v1.schema.json)
- [`RawPoseFrame v1`](schema/raw_pose_frame.v1.schema.json)
- [`Annotation v1`](schema/annotation.v1.schema.json)
- [`FixtureMetadata v1`](schema/fixture_metadata.v1.schema.json)

Each schema is independently versioned with an immutable major-version filename.
This release freezes lossless observation and annotation evidence; it does not
freeze movement interpretation.

### Deferred interpretation artifacts

`ReliablePoseFrame`, `CanonicalPoseFrame`, `DerivedFeatures`, `MovementEvent`,
`RepEvent`, `HoldEvent`, `PullUpState`, form classification, rep counting, and
product or benchmark thresholds remain deferred until Phase 0 evidence supports
their semantics. They will require a later design and release decision. See
[`ADR-0001`](../docs/adr/ADR-0001-observation-before-interpretation.md).

### Tooling contracts

- [`ExpectedFailure v1`](tooling/expected_failure.v1.schema.json) declares the
  expected error for a synthetic negative fixture.
- [`PreprocessingVector v1`](tooling/preprocessing_vector.v1.schema.json)
  describes an exact synthetic RGB24 scale/pad vector.
- [`Validator error-code registry v1`](tooling/validator_error_codes.v1.json)
  provides stable machine-readable validation codes.

The reference validator package is version `0.1.0` in
[`reference/pyproject.toml`](../reference/pyproject.toml) and requires Python
>=3.12. It emits human output by default and a stable JSON issue array with
`--format json`; exit 0 means valid, exit 2 means validation/usage failure, and
exit 3 means an unexpected internal failure.

### Preprocessing contracts

- [`Decoded-Frame Fixture Contract v1`](preprocessing/decoded_frame_fixture.v1.md)
  defines upright, unmirrored, full-range sRGB RGB24 fixture bytes and exact
  scale/pad geometry.
- [`Live Capture-to-Model-Input Contract v1`](preprocessing/live_capture_to_model_input.v1.md)
  defines the live camera-buffer boundary and requires the same canonical
  output geometry.

### Fixture set

The committed fixture set is synthetic-only:

- one positive session under
  [`fixtures/synthetic/valid/session`](../fixtures/synthetic/valid/session/);
- four declared-negative cases under
  [`fixtures/synthetic/negative`](../fixtures/synthetic/negative/):
  timestamp regression (`FRAME.TIMESTAMP_ORDER`), session switch
  (`STREAM.SESSION_CHANGED`), landmark count (`FRAME.LANDMARK_COUNT`), and
  fixture checksum (`FIXTURE.CHECKSUM_MISMATCH`); and
- one RGB24 scale/pad golden vector under
  [`fixtures/synthetic/preprocessing/scale_pad_golden`](../fixtures/synthetic/preprocessing/scale_pad_golden/).

No real participant video, audio, biometric-derived observation, consent record,
identity mapping, credential, or identity key is part of this release.

### Decision records and policy

- [`ADR-0001`](../docs/adr/ADR-0001-observation-before-interpretation.md)
  stabilizes observation before interpretation.
- [`ADR-0002`](../docs/adr/ADR-0002-canonical-preprocessing.md) freezes the
  canonical RGB24 model-input preprocessing.
- [`ADR-0003`](../docs/adr/ADR-0003-contract-evolution-and-data-guard.md)
  defines reference validation, evolution, and repository guarding.
- The [`schema release and change process`](schema-release-process.md) is the
  source for [compatibility classification](schema-release-process.md#compatibility-classification),
  [release](schema-release-process.md#release),
  [deprecation](schema-release-process.md#deprecation), and
  [migration](schema-release-process.md#migration) policy.
