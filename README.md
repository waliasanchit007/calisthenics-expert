# Calisthenics Expert

## M0 observation contract foundation

This repository publishes the Phase 0 boundary for what a pose capture harness
observes. M0 freezes observation, not interpretation.

The four stable observation contracts are:

- [`SessionManifest v1`](contracts/schema/session_manifest.v1.schema.json), which
  describes one capture session and its capture/backend metadata;
- [`RawPoseFrame v1`](contracts/schema/raw_pose_frame.v1.schema.json), which
  records one completed inference result, including no-pose results;
- [`Annotation v1`](contracts/schema/annotation.v1.schema.json), which records
  human timeline observations and uncertainty; and
- [`FixtureMetadata v1`](contracts/schema/fixture_metadata.v1.schema.json),
  which identifies synthetic decoded-frame bytes and their integrity data.

The tooling contracts are [`ExpectedFailure v1`](contracts/tooling/expected_failure.v1.schema.json),
[`PreprocessingVector v1`](contracts/tooling/preprocessing_vector.v1.schema.json),
and the [`validator error-code registry`](contracts/tooling/validator_error_codes.v1.json).
The two preprocessing contracts are [`decoded-frame fixtures`](contracts/preprocessing/decoded_frame_fixture.v1.md)
and [`live capture to model input`](contracts/preprocessing/live_capture_to_model_input.v1.md).

Interpretation artifacts are intentionally deferred: `ReliablePoseFrame`,
`CanonicalPoseFrame`, `DerivedFeatures`, `MovementEvent`, `RepEvent`,
`HoldEvent`, `PullUpState`, form classification, rep counting, and product or
benchmark thresholds. They require Phase 0 evidence and a later design; they
are not hidden or experimental fields in the stable v1 observation contracts.
See [ADR-0001](docs/adr/ADR-0001-observation-before-interpretation.md).

## Setup and verification

Python 3.12 or newer is required. `make bootstrap` automatically selects the
first available supported interpreter in this order: `python3.14`, `python3.13`,
`python3.12`, then `python3`, rejecting a selected interpreter below 3.12. It
creates or reuses `.venv`, installs the editable reference validator and its
development dependencies, and configures the repository-local pre-commit hook.

```bash
make bootstrap
```

To select a supported interpreter explicitly, override `PYTHON`:

```bash
PYTHON=python3.14 make bootstrap
```

Replace `python3.14` with another installed Python >=3.12 when needed. The
override applies to environment creation and does not change the package's
minimum supported version.

Run the complete local verification suite with:

```bash
make verify
```

`make verify` checks formatting, Ruff, strict mypy, the reference tests, the
positive session and fixture, every declared-negative fixture, the preprocessing
golden vector, and the tracked-tree repository guard. The same `make bootstrap`
and `make verify` commands run in GitHub Actions with Python 3.12
([workflow](.github/workflows/verify.yml), [actions/checkout@v7](.github/workflows/verify.yml),
and [actions/setup-python@v7](.github/workflows/verify.yml)).

## Validator CLI

After setup, invoke the reference validator with `.venv/bin/python -m
observation_contracts`. The root help and all subcommand help paths are:

```bash
.venv/bin/python -m observation_contracts --help
.venv/bin/python -m observation_contracts validate-session --help
.venv/bin/python -m observation_contracts verify-fixture --help
.venv/bin/python -m observation_contracts verify-preprocessing-vector --help
.venv/bin/python -m observation_contracts guard-repository --help
```

The commands are:

```text
.venv/bin/python -m observation_contracts validate-session <manifest> <frames.ndjson> [annotation ...]
.venv/bin/python -m observation_contracts verify-fixture <fixture-metadata>
.venv/bin/python -m observation_contracts verify-preprocessing-vector <vector-metadata>
.venv/bin/python -m observation_contracts guard-repository [--staged] [repository]
```

The optional `--format json` flag may be placed before the subcommand or after
it. Human output is grouped by artifact. A successful command prints
`valid: <command>` and exits 0. A validation or usage failure exits 2: human
failures contain the artifact path, optional JSON pointer or NDJSON line, the
stable error code in brackets, and a concise message. Unexpected internal
failures exit 3 and are reported as `observation-contracts: internal failure: ...`
on stderr. JSON output is a stable array: success is `[]`; each failure object
contains `code`, `artifact_path`, `artifact_kind`, and `message`, plus any
available `pointer`, `line`, `expected`, and `actual` fields.

### Valid session

This synthetic session includes independent annotations and their adjudicated
annotation. It should print `valid: validate-session` and exit 0:

```bash
.venv/bin/python -m observation_contracts validate-session fixtures/synthetic/valid/session/manifest.json fixtures/synthetic/valid/session/frames.ndjson fixtures/synthetic/valid/session/annotation_independent_a.json fixtures/synthetic/valid/session/annotation_independent_b.json fixtures/synthetic/valid/session/annotation_adjudicated.json
```

### Declared-negative session

The timestamp-regression fixture is deliberately invalid. The command should
print a human error containing `[FRAME.TIMESTAMP_ORDER]` and exit 2. The
declared expected code is recorded in
`fixtures/synthetic/negative/timestamp_regression/expected_failure.json`.

```bash
.venv/bin/python -m observation_contracts validate-session fixtures/synthetic/negative/timestamp_regression/manifest.json fixtures/synthetic/negative/timestamp_regression/frames.ndjson
```

### Fixture, preprocessing, and guard checks

Verify the committed synthetic decoded-frame metadata and referenced bytes:

```bash
.venv/bin/python -m observation_contracts verify-fixture fixtures/synthetic/valid/session/fixture_metadata.json
```

Verify the exact RGB24 scale/pad golden vector:

```bash
.venv/bin/python -m observation_contracts verify-preprocessing-vector fixtures/synthetic/preprocessing/scale_pad_golden/vector.json
```

Guard the tracked repository contents:

```bash
.venv/bin/python -m observation_contracts guard-repository .
```

The staged form used by the hook is:

```text
.venv/bin/python -m observation_contracts guard-repository --staged .
```

## Adding synthetic fixtures

Git admits only deliberately generated fixture content:

- Positive fixtures belong below `fixtures/synthetic/valid/`. Include valid
  `FixtureMetadata` with `synthetic: true`, relative frame paths, byte lengths,
  SHA-256 values, and increasing frame indexes/timestamps.
- Negative fixtures belong below
  `fixtures/synthetic/negative/<case>/`. Add the intentionally invalid target
  artifact and an `expected_failure.json` that names the validator command,
  expected registry code, repository-relative artifact path, and a description.
  The declaration must be valid even though its target artifact is expected to
  fail.
- Preprocessing fixtures belong below `fixtures/synthetic/preprocessing/`. Add
  a `preprocessing_vector.v1` descriptor with `synthetic: true`, input/output
  dimensions, relative RGB24 paths, exact byte lengths, and lowercase SHA-256
  values. Verification is byte-for-byte with no tolerance.

These fixtures are synthetic conformance data only. Do not commit real video,
audio, participant pseudonym mappings, biometric-derived observations, consent
records, credentials, or identity keys. The repository guard rejects those
categories, and `.github/workflows/verify.yml` runs the tracked-tree guard in
CI. Synthetic-looking placeholders do not make real study data acceptable.

## Pre-commit hook boundary

`make bootstrap` sets the repository-local `core.hooksPath` to `.githooks`.
The installed hook runs the staged repository guard before a local commit.
Before bootstrap, a local commit can bypass that hook; this is the documented
pre-bootstrap residual risk. `make verify` still scans the complete tracked
tree, and GitHub Actions runs `make verify` on pushes and pull requests, so
integration remains guarded even when a local hook was not installed.

## Release policy

The v1.0.0 inventory and release notes are in
[`contracts/CHANGELOG.md`](contracts/CHANGELOG.md). The governing
[`schema release and change process`](contracts/schema-release-process.md)
defines:

- [compatibility classification](contracts/schema-release-process.md#compatibility-classification)
  for patch, minor, and major changes;
- [release rules](contracts/schema-release-process.md#release), including
  immutable major-version filenames and non-reusable validator error codes;
- [deprecation policy](contracts/schema-release-process.md#deprecation),
  including reader windows and removal dates; and
- [migration policy](contracts/schema-release-process.md#migration), including
  preserving originals and recording source/destination versions, tool version,
  Git SHA, timestamp, and information loss.

Material decisions are recorded in
[ADR-0001](docs/adr/ADR-0001-observation-before-interpretation.md),
[ADR-0002](docs/adr/ADR-0002-canonical-preprocessing.md), and
[ADR-0003](docs/adr/ADR-0003-contract-evolution-and-data-guard.md).
