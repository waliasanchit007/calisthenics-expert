# M0 Observation Contract Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver executable, versioned Phase 0 observation contracts, reference validation, synthetic conformance fixtures, and preventive repository controls that future Android and iOS diagnostic harnesses must satisfy.

**Architecture:** Four language-neutral JSON Schema observation contracts define artifact shape, while a focused Python 3.12 package provides schema loading, stable version/error handling, topology rules, cross-artifact semantic validation, fixture integrity, reference preprocessing, and repository guarding. A single Make target runs the same checks locally, in the committed Git hook, and in GitHub Actions.

**Tech Stack:** Python 3.12, JSON Schema Draft 2020-12, jsonschema 4.25.x, pytest 8.4.x, Ruff 0.12.x, mypy 1.17.x, GNU/Apple Make, GitHub Actions.

## Global Constraints

- Read `AGENTS.md`, `docs/central-handoff.md`, `docs/phase-0-plan.md`, and the approved design before changing files.
- Stable v1 artifacts are only `SessionManifest`, `RawPoseFrame`, `Annotation`, and `FixtureMetadata`.
- Do not create `DerivedFeatures`, `MovementEvent`, `RepEvent`, `HoldEvent`, `ReliablePoseFrame`, or `CanonicalPoseFrame` schemas.
- Preserve normalized image landmarks and world landmarks separately and losslessly.
- Never convert backend confidence values to booleans, smooth coordinates, impute landmarks, or invent numeric acceptance thresholds.
- Commit synthetic data only. Never commit participant media, real landmark streams, consent records, identity mappings, credentials, or Buzz identities.
- JSON fields use `snake_case`; IDs are lowercase canonical UUIDs; monotonic time values are integer nanoseconds.
- Every behavior-changing Python function is introduced by a failing test, observed failing for the expected reason, followed by the minimum passing implementation.
- Each task is committed separately and reviewed before the next task starts.

## File map

- `contracts/schema/*.json`: four stable observation schemas.
- `contracts/preprocessing/*.md`: exact fixture and live model-input preprocessing rules.
- `contracts/tooling/*.json`: expected-failure schema, preprocessing-vector schema, and validator error registry.
- `reference/src/observation_contracts/*.py`: one module per validation responsibility.
- `reference/tests/*.py`: unit and integration tests mirroring package modules.
- `fixtures/synthetic/valid/`: schema-valid positive session.
- `fixtures/synthetic/negative/`: malformed artifacts with explicit expected failures.
- `fixtures/synthetic/preprocessing/`: RGB24 input/output golden vectors.
- `.githooks/pre-commit`: staged-path guard entrypoint.
- `.github/workflows/verify.yml`: clean-clone verification.
- `Makefile`: bootstrap and complete verification entrypoints.

---

### Task 1: Python package, version policy, and stable errors

**Files:**
- Create: `.gitignore`
- Create: `reference/pyproject.toml`
- Create: `reference/src/observation_contracts/__init__.py`
- Create: `reference/src/observation_contracts/errors.py`
- Create: `reference/src/observation_contracts/versions.py`
- Create: `reference/tests/test_versions.py`
- Create: `reference/tests/test_errors.py`
- Create: `contracts/tooling/validator_error_codes.v1.json`
- Create: `contracts/tooling/expected_failure.v1.schema.json`
- Create: `contracts/tooling/preprocessing_vector.v1.schema.json`

**Interfaces:**
- Produces: `ArtifactVersion.parse(value: str) -> ArtifactVersion`
- Produces: `ensure_supported_major(value: str, supported_major: int, artifact: str) -> ArtifactVersion`
- Produces: immutable `ValidationIssue(code, artifact_path, artifact_kind, message, pointer=None, line=None, expected=None, actual=None)`
- Produces: `ValidationFailure(issues: Sequence[ValidationIssue])`
- Produces: registry codes used by every later task.

- [ ] **Step 1: Add package metadata and failing version/error tests**

`reference/pyproject.toml` must declare Python `>=3.12`, runtime dependency `jsonschema>=4.25,<5`, and development dependencies `pytest>=8.4,<9`, `ruff>=0.12,<1`, and `mypy>=1.17,<2`. Configure setuptools with `src` layout, Ruff line length 100, and strict mypy for `observation_contracts`.

`reference/tests/test_versions.py`:

~~~python
import pytest

from observation_contracts.errors import ValidationFailure
from observation_contracts.versions import ArtifactVersion, ensure_supported_major


def test_parses_semantic_version() -> None:
    assert ArtifactVersion.parse("1.2.3") == ArtifactVersion(1, 2, 3)


@pytest.mark.parametrize("value", ["1", "1.2", "v1.2.3", "1.2.3.4", "-1.2.3"])
def test_rejects_non_semantic_versions(value: str) -> None:
    with pytest.raises(ValueError):
        ArtifactVersion.parse(value)


def test_accepts_newer_minor_within_supported_major() -> None:
    assert ensure_supported_major("1.9.0", 1, "raw_pose_frame").minor == 9


def test_rejects_unknown_major_with_stable_error() -> None:
    with pytest.raises(ValidationFailure) as caught:
        ensure_supported_major("2.0.0", 1, "raw_pose_frame")
    assert caught.value.issues[0].code == "VERSION.UNKNOWN_MAJOR"
~~~

`reference/tests/test_errors.py`:

~~~python
from observation_contracts.errors import ValidationIssue


def test_issue_json_omits_unset_locations() -> None:
    issue = ValidationIssue(
        code="SCHEMA.INVALID",
        artifact_path="sample.json",
        artifact_kind="session_manifest",
        message="required property missing",
    )
    assert issue.to_dict() == {
        "code": "SCHEMA.INVALID",
        "artifact_path": "sample.json",
        "artifact_kind": "session_manifest",
        "message": "required property missing",
    }
~~~

- [ ] **Step 2: Run tests and observe the missing-package failure**

Run:

~~~bash
python3.12 -m venv .venv
.venv/bin/pip install -e 'reference[dev]'
.venv/bin/python -m pytest reference/tests/test_versions.py reference/tests/test_errors.py -v
~~~

Expected: collection fails because `observation_contracts.errors` and `observation_contracts.versions` do not exist.

- [ ] **Step 3: Implement version and error primitives**

Implement an ordered frozen `ArtifactVersion` dataclass with a full-match regex `(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)`. Implement frozen `ValidationIssue`, stable `to_dict()`, and `ValidationFailure` whose message reports the issue count.

Create the error registry with at least:

~~~json
{
  "registry_version": "1.0.0",
  "codes": [
    "VERSION.INVALID",
    "VERSION.UNKNOWN_MAJOR",
    "SCHEMA.INVALID",
    "STREAM.SCHEMA_VERSION_CHANGED",
    "STREAM.SESSION_CHANGED",
    "FRAME.INDEX_ORDER",
    "FRAME.TIMESTAMP_ORDER",
    "FRAME.INFERENCE_TIME_ORDER",
    "FRAME.TOPOLOGY_UNSUPPORTED",
    "FRAME.LANDMARK_COUNT",
    "FRAME.ABSENT_POSE_HAS_LANDMARKS",
    "FRAME.WORLD_AVAILABILITY",
    "FRAME.COORDINATE_ARRAYS_IDENTICAL",
    "SESSION.COUNT_MISMATCH",
    "ANNOTATION.INVALID_STAGE",
    "ANNOTATION.LINK_MISSING",
    "ANNOTATION.LINK_DUPLICATE",
    "ANNOTATION.INVALID_LABEL",
    "FIXTURE.FRAME_INDEX",
    "FIXTURE.TIMESTAMP_ORDER",
    "FIXTURE.BYTE_LENGTH",
    "FIXTURE.CHECKSUM_MISMATCH",
    "PREPROCESSING.VECTOR_INVALID",
    "REPOSITORY.PROHIBITED_PATH",
    "REPOSITORY.PROHIBITED_CONTENT",
    "REPOSITORY.INVALID_FIXTURE_DECLARATION"
  ]
}
~~~

The expected-failure tooling schema requires `tooling_schema_version`, `command`, `expected_error_code`, and `artifact_path`. The preprocessing-vector schema requires `tooling_schema_version`, `synthetic: true`, input/output dimensions, paths, byte lengths, and lowercase 64-character SHA-256 values. Both allow unknown optional fields.

- [ ] **Step 4: Run focused tests and static checks**

Run:

~~~bash
.venv/bin/python -m pytest reference/tests/test_versions.py reference/tests/test_errors.py -v
.venv/bin/ruff check reference
.venv/bin/mypy reference/src
~~~

Expected: all tests pass and both static checks exit 0.

- [ ] **Step 5: Commit Task 1**

~~~bash
git add .gitignore reference contracts/tooling
git commit -m "feat: add contract tooling foundations"
~~~

---

### Task 2: Stable JSON Schema observation contracts

**Files:**
- Create: `contracts/schema/session_manifest.v1.schema.json`
- Create: `contracts/schema/raw_pose_frame.v1.schema.json`
- Create: `contracts/schema/annotation.v1.schema.json`
- Create: `contracts/schema/fixture_metadata.v1.schema.json`
- Create: `reference/src/observation_contracts/schema_loader.py`
- Create: `reference/src/observation_contracts/structural.py`
- Create: `reference/tests/test_structural.py`
- Create: `reference/tests/__init__.py`
- Create: `reference/tests/samples.py`

**Interfaces:**
- Consumes: `ValidationIssue`, `ValidationFailure`, `ensure_supported_major`.
- Produces: `ArtifactKind` literal values.
- Produces: `load_schema(kind: ArtifactKind, major: int = 1) -> Mapping[str, object]`
- Produces: `validate_document(document, kind, path) -> list[ValidationIssue]`
- Produces: `validate_ndjson(path, kind="raw_pose_frame") -> tuple[list[dict[str, object]], list[ValidationIssue]]`

- [ ] **Step 1: Write structural tests against wished-for schemas**

Tests must construct minimal valid documents through named helpers and assert:

~~~python
def test_minimal_documents_match_v1_schemas() -> None:
    for kind, document in minimal_documents().items():
        assert validate_document(document, kind, f"{kind}.json") == []


def test_unknown_optional_field_is_tolerated() -> None:
    manifest = minimal_manifest()
    manifest["future_optional_field"] = {"value": 1}
    assert validate_document(manifest, "session_manifest", "manifest.json") == []


def test_missing_required_field_reports_json_pointer() -> None:
    frame = minimal_pose_frame()
    del frame["capture_timestamp_ns"]
    issues = validate_document(frame, "raw_pose_frame", "frames.ndjson")
    assert issues[0].code == "SCHEMA.INVALID"
    assert issues[0].pointer == "/capture_timestamp_ns"
~~~

Add a malformed-NDJSON test asserting line 2 is reported while valid line 1 remains available for diagnostics.

- [ ] **Step 2: Run the structural tests and observe missing schemas**

Run:

~~~bash
.venv/bin/python -m pytest reference/tests/test_structural.py -v
~~~

Expected: failure because schema files and `schema_loader` are absent.

- [ ] **Step 3: Implement all four Draft 2020-12 schemas**

Use `$defs` for SHA-256, UUID, semantic version, landmark, media reference, cadence sample, annotation event, and fixture frame. Preserve unknown optional fields with `additionalProperties: true`.

Required contract details:

- Manifest: participant pseudonym, independent versions, platform/device/backend/model/delegate/topology, camera and distinct model-input dimensions, both preprocessing versions, drain timeout, cadence time series, clock, availability flags, media roles including static reference, offset and drift, all six counts plus trailing skips, structured machine failures, capture-quality observations, and extensible study context.
- Raw frame: nullable normalized/world arrays, nullable visibility/presence, strict nonnegative indexes/times/dimensions, and `frames_skipped_since_last_emitted_record`.
- Annotation: artifact ID, stage, linked input IDs, source, tier, event timing union, closed labels and invalid reasons, confidence, set-level count, and media hash.
- Fixture metadata: synthetic marker, exact RGB24 metadata, ordered frame descriptors, byte lengths, hashes, and generation metadata.

JSON Schema conditionals enforce local rules such as absent-pose null arrays, invalid-label reasons, independent empty inputs, and adjudicated minimum input count. Cross-artifact and ordering rules remain Python responsibilities.

- [ ] **Step 4: Implement schema loading and structural issue conversion**

Resolve the repository root from `Path(__file__).parents[3]`, never from the process working directory. Use `Draft202012Validator.iter_errors`, sort by absolute path, and convert each error to `ValidationIssue(code="SCHEMA.INVALID", ...)`. The JSON pointer must escape `~` as `~0` and `/` as `~1`.

- [ ] **Step 5: Run structural tests and all current checks**

~~~bash
.venv/bin/python -m pytest reference/tests -v
.venv/bin/ruff check reference
.venv/bin/mypy reference/src
~~~

Expected: all tests and checks pass.

- [ ] **Step 6: Commit Task 2**

~~~bash
git add contracts/schema reference/src/observation_contracts/schema_loader.py reference/src/observation_contracts/structural.py reference/tests
git commit -m "feat: define observation schemas"
~~~

---

### Task 3: Topology and raw-stream semantic validation

**Files:**
- Create: `reference/src/observation_contracts/topology.py`
- Create: `reference/src/observation_contracts/semantic.py`
- Create: `reference/tests/test_semantic_frames.py`

**Interfaces:**
- Produces: `LandmarkTopology(name: str, landmark_names: tuple[str, ...])`
- Produces: `get_topology(name: str) -> LandmarkTopology`
- Produces: `validate_frame_stream(manifest, frames, manifest_path, frame_path) -> list[ValidationIssue]`

- [ ] **Step 1: Write failing frame-stream tests**

Cover one behavior per test:

~~~python
def test_rejects_timestamp_regression() -> None:
    manifest = minimal_manifest()
    frames = [minimal_pose_frame(index=0, capture_ns=100), minimal_pose_frame(index=1, capture_ns=99)]
    assert validate_frame_stream(manifest, frames, "m.json", "f.ndjson")[0].code == "FRAME.TIMESTAMP_ORDER"


def test_rejects_unknown_topology_without_assuming_33() -> None:
    manifest = minimal_manifest()
    manifest["pose_pipeline"]["landmark_topology"] = "future_pose_42_v1"
    issues = validate_frame_stream(manifest, [minimal_pose_frame()], "m.json", "f.ndjson")
    assert issues[0].code == "FRAME.TOPOLOGY_UNSUPPORTED"


def test_accepts_backend_omitted_confidence_as_null() -> None:
    frame = minimal_pose_frame()
    frame["normalized_image_landmarks"][0]["visibility"] = None
    assert validate_frame_stream(minimal_manifest(), [frame], "m.json", "f.ndjson") == []
~~~

Also test schema-version change, session change, duplicate/decreasing frame index, inference timing inequality, absent-pose landmarks, 32/34 landmark arrays, partial world arrays, manifest availability mismatch, and exact normalized/world array duplication.

- [ ] **Step 2: Run tests and observe missing semantic implementation**

~~~bash
.venv/bin/python -m pytest reference/tests/test_semantic_frames.py -v
~~~

Expected: import or attribute failure for `validate_frame_stream`.

- [ ] **Step 3: Implement MediaPipe topology and frame invariants**

Define the ordered 33-name MediaPipe Pose topology as one tuple. Validation must collect deterministic issues without reading beyond an unsupported topology. Strict rules:

- frame index and capture time increase;
- schema version and session ID remain constant;
- capture time ≤ inference start ≤ inference end;
- present normalized arrays match topology;
- world arrays are null or complete;
- absent pose has both arrays null;
- manifest world-availability flag is reconciled;
- exact serialized equality of normalized and world arrays is rejected;
- no numeric coordinate ranges or confidence thresholds are imposed.

- [ ] **Step 4: Run focused and regression tests**

~~~bash
.venv/bin/python -m pytest reference/tests/test_semantic_frames.py -v
.venv/bin/python -m pytest reference/tests -v
~~~

Expected: all tests pass.

- [ ] **Step 5: Commit Task 3**

~~~bash
git add reference/src/observation_contracts/topology.py reference/src/observation_contracts/semantic.py reference/tests/test_semantic_frames.py
git commit -m "feat: validate raw pose streams"
~~~

---

### Task 4: Session counts, failures, and media semantics

**Files:**
- Modify: `reference/src/observation_contracts/semantic.py`
- Create: `reference/tests/test_semantic_session.py`

**Interfaces:**
- Extends: `validate_frame_stream(...)` with exact session reconciliation.
- Produces: `validate_session_manifest_semantics(manifest, frames, path) -> list[ValidationIssue]`

- [ ] **Step 1: Write failing count-closure tests**

Create tests for:

~~~python
def test_counts_close_across_failed_call_and_trailing_skips() -> None:
    manifest = minimal_manifest(
        camera_frames_received=9,
        inference_frames_submitted=3,
        inference_records_emitted=2,
        pose_detected_records=2,
        frames_skipped_before_inference=6,
        trailing_frames_skipped_after_last_emitted_record=1,
        inference_failures=1,
    )
    manifest["machine_inference_failures"] = [{
        "submission_index": 1,
        "capture_timestamp_ns": 200,
        "failure_source": "backend",
        "classification": "backend_call_failed",
        "source_error_code": "MEDIAPIPE_STATUS_13",
    }]
    frames = [
        minimal_pose_frame(index=0, capture_ns=100, skipped=0),
        minimal_pose_frame(index=1, capture_ns=300, skipped=5),
    ]
    assert validate_session_manifest_semantics(manifest, frames, "manifest.json") == []
~~~

Add failures for every broken identity, failure-list length mismatch, unknown harness classification, missing static reference role, duplicate media role, missing sync drift when offset exists, and nonpositive drain timeout.

- [ ] **Step 2: Run focused tests and observe missing reconciliation**

~~~bash
.venv/bin/python -m pytest reference/tests/test_semantic_session.py -v
~~~

Expected: failures because count and media semantics are not implemented.

- [ ] **Step 3: Implement exact count and media checks**

Implement the equations verbatim from the approved design. Sum `frames_skipped_since_last_emitted_record` plus the manifest trailing term. Verify each submitted call is either emitted or in the structured failure list. Require one `static_reference_clip` role for study sessions, and require both sync offset and drift together for synchronized cross-device media.

- [ ] **Step 4: Run focused and full tests**

~~~bash
.venv/bin/python -m pytest reference/tests/test_semantic_session.py -v
.venv/bin/python -m pytest reference/tests -v
~~~

Expected: all tests pass.

- [ ] **Step 5: Commit Task 4**

~~~bash
git add reference/src/observation_contracts/semantic.py reference/tests/test_semantic_session.py
git commit -m "feat: reconcile session observations"
~~~

---

### Task 5: Annotation semantics and adjudication provenance

**Files:**
- Modify: `reference/src/observation_contracts/semantic.py`
- Create: `reference/tests/test_annotations.py`

**Interfaces:**
- Produces: `validate_annotations(manifest, annotations, paths) -> list[ValidationIssue]`

- [ ] **Step 1: Write failing annotation tests**

Test:

~~~python
def test_adjudicated_annotation_resolves_two_independent_inputs() -> None:
    first = minimal_annotation(annotation_id=UUID_A, stage="independent")
    second = minimal_annotation(annotation_id=UUID_B, stage="independent")
    consensus = minimal_annotation(
        annotation_id=UUID_C,
        stage="adjudicated",
        input_annotation_ids=[UUID_A, UUID_B],
    )
    assert validate_annotations(minimal_manifest(), [first, second, consensus], ["a.json", "b.json", "c.json"]) == []
~~~

Add failures for missing input ID, duplicate annotation ID, adjudicated-to-adjudicated linkage, cross-session linkage, independent nonempty inputs, uncertain label with invalid reasons, invalid label without reasons, valid label with reasons, and rep label without rep index.

- [ ] **Step 2: Run focused tests and observe missing annotation validation**

~~~bash
.venv/bin/python -m pytest reference/tests/test_annotations.py -v
~~~

Expected: import or assertion failures for annotation semantics.

- [ ] **Step 3: Implement annotation graph validation**

Index artifacts by `annotation_id`, reject duplicates before resolving links, require all linked inputs to exist and have stage `independent`, require one session ID across manifest and annotations, and keep event labels as observations rather than movement predictions.

- [ ] **Step 4: Run focused and full tests**

~~~bash
.venv/bin/python -m pytest reference/tests/test_annotations.py -v
.venv/bin/python -m pytest reference/tests -v
~~~

Expected: all tests pass.

- [ ] **Step 5: Commit Task 5**

~~~bash
git add reference/src/observation_contracts/semantic.py reference/tests/test_annotations.py
git commit -m "feat: validate annotation provenance"
~~~

---

### Task 6: Fixture integrity and executable preprocessing vectors

**Files:**
- Create: `contracts/preprocessing/decoded_frame_fixture.v1.md`
- Create: `contracts/preprocessing/live_capture_to_model_input.v1.md`
- Create: `reference/src/observation_contracts/fixture_integrity.py`
- Create: `reference/src/observation_contracts/preprocessing.py`
- Create: `reference/tests/test_fixture_integrity.py`
- Create: `reference/tests/test_preprocessing.py`
- Create: `fixtures/synthetic/preprocessing/scale_pad_golden/vector.json`
- Create: synthetic RGB24 input and expected output bytes under the same directory.

**Interfaces:**
- Produces: `sha256_file(path: Path) -> str`
- Produces: `verify_fixture(metadata_path: Path) -> list[ValidationIssue]`
- Produces: `scale_pad_rgb24(source: bytes, source_width: int, source_height: int, target_width: int, target_height: int) -> bytes`
- Produces: `verify_preprocessing_vector(descriptor_path: Path) -> list[ValidationIssue]`

- [ ] **Step 1: Write failing fixture and preprocessing tests**

Tests cover good hash, altered byte, byte-length mismatch, missing/duplicate frame indexes, timestamp regression, aspect-ratio preservation, odd right/bottom padding, black padding, and vector descriptor hash verification.

The bilinear reference uses centre-of-pixel mapping:

~~~text
source_x = (destination_x + 0.5) * source_width / destination_width - 0.5
source_y = (destination_y + 0.5) * source_height / destination_height - 0.5
~~~

Clamp source coordinates to edge pixels. Compute weights with `fractions.Fraction` and round output channels with `floor(value + 1/2)` so golden generation is deterministic.

- [ ] **Step 2: Run focused tests and observe missing functions**

~~~bash
.venv/bin/python -m pytest reference/tests/test_fixture_integrity.py reference/tests/test_preprocessing.py -v
~~~

Expected: collection fails for missing modules.

- [ ] **Step 3: Implement integrity and reference geometry**

Resolve every referenced path beneath the descriptor directory and reject traversal outside the fixture root. Stream file hashing in 64 KiB chunks. Implement scale-to-fit dimensions with half-up rounding, centre placement, odd remainder on right/bottom, and RGB zero fill. Keep YUV conversion out of this function.

- [ ] **Step 4: Generate and verify committed golden bytes**

Use a deterministic 2×2 RGB24 source with four distinct corner colours and a target shape that produces odd padding. The checked-in descriptor contains exact lengths and hashes. Generate bytes once through a small test helper, then freeze the resulting files; production verification reads rather than rewrites them.

- [ ] **Step 5: Run focused and full tests**

~~~bash
.venv/bin/python -m pytest reference/tests/test_fixture_integrity.py reference/tests/test_preprocessing.py -v
.venv/bin/python -m pytest reference/tests -v
~~~

Expected: all tests pass.

- [ ] **Step 6: Commit Task 6**

~~~bash
git add contracts/preprocessing reference/src/observation_contracts/fixture_integrity.py reference/src/observation_contracts/preprocessing.py reference/tests fixtures/synthetic/preprocessing
git commit -m "feat: verify fixture preprocessing"
~~~

---

### Task 7: Positive and declared-negative contract fixtures

**Files:**
- Create: `fixtures/synthetic/valid/session/` manifest, NDJSON frames, independent annotations, adjudicated annotation, static-reference synthetic bytes, decoded-frame bytes, and fixture metadata.
- Create: `fixtures/synthetic/negative/timestamp_regression/`
- Create: `fixtures/synthetic/negative/session_switch/`
- Create: `fixtures/synthetic/negative/landmark_count/`
- Create: `fixtures/synthetic/negative/fixture_checksum/`
- Modify: `reference/src/observation_contracts/fixture_integrity.py`
- Create: `reference/tests/test_committed_fixtures.py`

**Interfaces:**
- Consumes: structural, semantic, fixture, and annotation validators.
- Produces: `discover_negative_fixtures(root: Path) -> list[NegativeFixtureCase]`
- Produces: `run_declared_validation(case: NegativeFixtureCase) -> ValidationResult`
- Produces: repository examples and executable expected failures.

- [ ] **Step 1: Write a failing committed-fixture discovery test**

~~~python
def test_every_negative_fixture_fails_with_declared_code() -> None:
    cases = discover_negative_fixtures(repository_root())
    assert {case.name for case in cases} == {
        "timestamp_regression",
        "session_switch",
        "landmark_count",
        "fixture_checksum",
    }
    for case in cases:
        result = run_declared_validation(case)
        assert result.codes == [case.expected_error_code]
~~~

Also assert the complete positive session validates and every committed fixture declares `synthetic: true`.

- [ ] **Step 2: Run the test and observe missing fixtures**

~~~bash
.venv/bin/python -m pytest reference/tests/test_committed_fixtures.py -v
~~~

Expected: fixture discovery assertion fails with an empty set.

- [ ] **Step 3: Add the minimal positive fixture**

Use fixed UUIDs, tiny synthetic RGB frames, one no-pose raw record and one present-pose record with generated zero-valued topology entries whose normalized/world arrays are deliberately not identical. Include two independent annotations plus one adjudicated artifact linked by IDs. No name, face, participant image, or real movement sample appears.

- [ ] **Step 4: Add one-purpose negative fixtures**

Each case differs from the positive fixture by exactly one behavior and includes a valid `expected_failure.json` with one registered error code. The checksum case changes expected SHA-256 rather than sharing malformed metadata with another case.

- [ ] **Step 5: Run fixture and full tests**

~~~bash
.venv/bin/python -m pytest reference/tests/test_committed_fixtures.py -v
.venv/bin/python -m pytest reference/tests -v
~~~

Expected: all positive artifacts pass and every negative case fails with exactly its declared code.

- [ ] **Step 6: Commit Task 7**

~~~bash
git add fixtures/synthetic reference/tests/test_committed_fixtures.py
git commit -m "test: add synthetic contract fixtures"
~~~

---

### Task 8: Repository guard and preventive hook

**Files:**
- Create: `reference/src/observation_contracts/repository_guard.py`
- Create: `reference/tests/test_repository_guard.py`
- Create: `.githooks/pre-commit`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `guard_repository(root: Path, mode: Literal["tracked", "staged"]) -> list[ValidationIssue]`
- Produces: `installable pre-commit hook that invokes staged mode`.

- [ ] **Step 1: Write failing guard tests**

Use temporary Git repositories and real index entries. Test rejection of video/audio suffixes, pose dumps outside fixture roots, consent/identity filenames, `.env`, `auth.json`, PEM private keys, malformed fixture declarations, and path traversal. Test acceptance of ordinary Markdown, schemas, all three approved fixture classes, and benign words such as “consent” inside documentation.

- [ ] **Step 2: Run tests and observe missing guard**

~~~bash
.venv/bin/python -m pytest reference/tests/test_repository_guard.py -v
~~~

Expected: import failure for `repository_guard`.

- [ ] **Step 3: Implement path-first and bounded content checks**

Obtain paths with:

~~~bash
git ls-files -z
git diff --cached --name-only --diff-filter=ACMR -z
~~~

Never follow symlinks outside the repository. Read at most 64 KiB for signatures. Apply the exclusive three-class fixture policy before generic pose-dump rejection. Return deterministic issues sorted by path and code.

- [ ] **Step 4: Add the committed hook**

`.githooks/pre-commit`:

~~~sh
#!/bin/sh
set -eu
exec .venv/bin/python -m observation_contracts guard-repository --staged .
~~~

The later `make bootstrap` target configures repository-local `core.hooksPath=.githooks` and executable mode is committed.

- [ ] **Step 5: Run guard and full tests**

~~~bash
.venv/bin/python -m pytest reference/tests/test_repository_guard.py -v
.venv/bin/python -m pytest reference/tests -v
~~~

Expected: all tests pass and the current tracked tree has no guard findings.

- [ ] **Step 6: Commit Task 8**

~~~bash
chmod +x .githooks/pre-commit
git add .gitignore .githooks/pre-commit reference/src/observation_contracts/repository_guard.py reference/tests/test_repository_guard.py
git commit -m "feat: guard repository data"
~~~

---

### Task 9: CLI, one-command verification, and CI

**Files:**
- Create: `reference/src/observation_contracts/cli.py`
- Create: `reference/src/observation_contracts/__main__.py`
- Create: `reference/tests/test_cli.py`
- Create: `Makefile`
- Create: `.github/workflows/verify.yml`

**Interfaces:**
- Produces CLI commands:
  - `validate-session MANIFEST FRAMES [ANNOTATION ...]`
  - `verify-fixture FIXTURE_METADATA`
  - `verify-preprocessing-vector VECTOR_METADATA`
  - `guard-repository [--staged] [REPOSITORY]`
- Produces exit codes: 0 valid, 2 validation failure, 3 unexpected internal failure.
- Produces human output and stable JSON issue arrays through `--format json`.

- [ ] **Step 1: Write failing CLI tests**

Invoke `cli.main(argv)` without subprocess mocks. Verify valid session exit 0, malformed frame exit 2 with line number, JSON output exact keys, multiple annotation linkage, unknown command usage, and an injected internal exception mapped to exit 3 without being reported as invalid data.

- [ ] **Step 2: Run CLI tests and observe missing entrypoint**

~~~bash
.venv/bin/python -m pytest reference/tests/test_cli.py -v
~~~

Expected: import failure for `observation_contracts.cli`.

- [ ] **Step 3: Implement argparse CLI**

Keep command handlers thin: load files, call module interfaces, render issues. Never duplicate validation logic in the CLI. Human output groups by artifact; JSON output is a single array of `ValidationIssue.to_dict()`.

- [ ] **Step 4: Add bootstrap and verify targets**

`make bootstrap`:

~~~make
bootstrap:
	python3.12 -m venv .venv
	.venv/bin/pip install -e 'reference[dev]'
	git config --local core.hooksPath .githooks
~~~

`make verify` runs Ruff format check, Ruff lint, mypy, full pytest, positive fixture validation, all declared-negative expectations, preprocessing vectors, and tracked-tree guard. It never rewrites source or fixtures.

- [ ] **Step 5: Add GitHub Actions**

Use `actions/checkout@v4` and `actions/setup-python@v5` with Python 3.12 and pip cache. Install `reference[dev]` and run `make verify`. Grant read-only repository contents and no other permissions.

- [ ] **Step 6: Run the complete clean command**

~~~bash
make bootstrap
make verify
~~~

Expected: every formatter, linter, type check, test, fixture check, expected-failure check, preprocessing-vector check, and repository guard exits 0.

- [ ] **Step 7: Commit Task 9**

~~~bash
git add Makefile .github/workflows/verify.yml reference/src/observation_contracts/cli.py reference/src/observation_contracts/__main__.py reference/tests/test_cli.py
git commit -m "feat: add contract verification command"
~~~

---

### Task 10: Release documentation and clean-clone verification

**Files:**
- Create: `contracts/CHANGELOG.md`
- Modify: `README.md`
- Modify: `contracts/schema-release-process.md`
- Modify: `docs/superpowers/specs/2026-08-12-m0-observation-contract-foundation-design.md` only if implementation produced an approved factual correction.

**Interfaces:**
- Documents the v1 release, commands, outputs, compatibility policy, and synthetic-data boundary.

- [ ] **Step 1: Write a documentation acceptance test**

Add `reference/tests/test_documentation.py` that asserts README contains `make bootstrap`, `make verify`, all four schema names, all four CLI commands, the synthetic-only warning, and a link to the schema release process.

- [ ] **Step 2: Run the documentation test and observe missing content**

~~~bash
.venv/bin/python -m pytest reference/tests/test_documentation.py -v
~~~

Expected: assertion failure against the current one-line README.

- [ ] **Step 3: Write release and usage documentation**

Document:

- supported and deferred artifacts;
- local setup and all CLI examples;
- human and JSON error output;
- how to add positive, negative, and preprocessing fixtures;
- why no real data belongs in Git;
- hook installation and pre-bootstrap residual risk;
- schema compatibility and release links.

`contracts/CHANGELOG.md` records v1.0.0 for all four observation schemas and three tooling contracts, validator package version, preprocessing contracts, fixture set, and ADRs.

- [ ] **Step 4: Verify from a separate clean clone**

Create a temporary clone outside the repository, then run:

~~~bash
make bootstrap
make verify
git status --short
~~~

Expected: verification exits 0 and `git status --short` is empty. Remove the temporary clone after recording the result.

- [ ] **Step 5: Run final repository verification**

~~~bash
make verify
git diff --check
git status -sb
~~~

Expected: all checks exit 0 and the only branch difference is the intentional task commits.

- [ ] **Step 6: Commit Task 10**

~~~bash
git add README.md contracts/CHANGELOG.md contracts/schema-release-process.md reference/tests/test_documentation.py
git commit -m "docs: release observation contracts"
~~~

---

## Review and integration gates

After every task:

1. Claude posts the commit SHA and exact red/green commands to Buzz.
2. Codex independently reviews the task commit against this plan and the approved design.
3. Claude resolves every Critical or Important finding and reruns the focused plus full tests.
4. Codex confirms the correction before the next task starts.

After Task 10:

1. Run `make verify` in Claude's clone and a clean temporary clone.
2. Compare the implementation branch to the pre-implementation base.
3. Run a final Codex review across the complete range.
4. Merge only after no Critical or Important findings remain.
5. Push the integrated `main` commit and verify local and remote SHAs match.
