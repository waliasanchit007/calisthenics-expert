# Schema Release and Change Process

## Purpose

This procedure governs observation schemas under `contracts/schema/` and validator tooling contracts under `contracts/tooling/`. Artifact and tooling versions are independent of application, harness, model, and analysis-package versions.

M0 v1.0.0 contains four stable observation contracts: `SessionManifest`,
`RawPoseFrame`, `Annotation`, and `FixtureMetadata`. It also contains three
tooling contracts: `ExpectedFailure`, `PreprocessingVector`, and the validator
error-code registry. The decoded-frame fixture and live-capture preprocessing
documents are companion contracts. Interpretation artifacts such as
`ReliablePoseFrame`, `CanonicalPoseFrame`, `DerivedFeatures`, `MovementEvent`,
`RepEvent`, and `HoldEvent` are explicitly deferred; they are not v1 aliases or
placeholder schemas. See [`contracts/CHANGELOG.md`](CHANGELOG.md) and
[ADR-0001](../docs/adr/ADR-0001-observation-before-interpretation.md).

## Proposal

Every schema change starts as a reviewed change containing:

- the affected artifact and current version;
- the proposed version;
- motivation and supporting evidence;
- compatibility classification;
- schema, fixture, validator, and documentation changes;
- migration impact for existing artifacts;
- an ADR when the change alters meaning or architecture.

## Compatibility classification

Semantic versioning is applied per artifact:

- Patch: documentation or constraint clarification that does not change the accepted instance set or field meaning.
- Minor: backward-compatible optional fields or newly recognized enum values that v1 readers are explicitly designed to tolerate.
- Major: removed or renamed fields, newly required fields, changed field meaning or units, incompatible enum changes, or stricter rules that reject previously valid artifacts.

Unknown optional properties are retained and tolerated within a known major. A reader rejects an unknown major before processing records. A stream cannot mix schema versions.

## Review requirements

A release requires:

1. a failing compatibility or behavior test demonstrating the proposed change;
2. updated JSON Schema and Python reference validation;
3. positive fixtures for accepted behavior and declared-negative fixtures for rejected behavior;
4. independent review by the non-implementing agent;
5. resolution of all critical and important findings;
6. a successful `make verify` run from a clean environment.

Changes affecting raw-data retention, participant linkage, consent, deletion, or security require explicit human approval before release.

## Release

- Released schemas use immutable filenames containing the major version, such as `raw_pose_frame.v1.schema.json`.
- The schema's internal semantic version records the complete `MAJOR.MINOR.PATCH` release.
- `contracts/CHANGELOG.md` records the release date, compatibility class, changed fields, validator version, fixtures, and ADR links.
- A Git tag identifies the repository commit containing the release.
- Existing released schema content is never edited in place to represent an incompatible meaning; a new major schema is added instead.
- Released validator error codes are never renamed, removed without deprecation, or reused for a different condition. New conditions receive new codes; replacement codes retain a deprecation link to the old code.

The v1.0.0 release inventory, fixture set, validator package version, and
decision-record links are maintained in [`contracts/CHANGELOG.md`](CHANGELOG.md).
The release commit is identified by an immutable Git tag. A release checklist
must confirm that `make verify` succeeds locally and from a clean clone, that
the positive and declared-negative synthetic fixtures exercise the published
contracts, and that no real study data is included.

## Deprecation

Deprecation is announced in the changelog and documentation with:

- the deprecated artifact versions;
- the supported reader window;
- the replacement version;
- migration instructions;
- the earliest removal date for validator support.

Deprecation does not invalidate stored observations. Readers preserve access long enough to migrate or explicitly archive those artifacts.

## Migration

Migration tools write new artifacts and never overwrite originals. The output records:

- source artifact hash and schema version;
- destination schema version;
- migration tool version and Git SHA;
- timestamp;
- warnings or information loss.

Lossy migration requires a new artifact family or explicit human approval and must retain the original observation artifact.
