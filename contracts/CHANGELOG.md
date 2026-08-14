# Observation Contract Changelog

This changelog records releases of the observation schemas and their supporting
tooling contracts. Application, harness, model, and analysis-package versions
are independent of these contracts.

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
