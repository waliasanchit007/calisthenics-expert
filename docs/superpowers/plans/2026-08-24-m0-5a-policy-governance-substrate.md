# M0.5A Policy and Governance Substrate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the non-participant Phase 0 boundary executable by adding versioned study-readiness contracts, fail-closed semantic admission, fixed live-conformance metadata, digest-bound protocols, and an explicit operating gate without creating participant data or capture authority.

**Architecture:** Keep the released observation schemas immutable and add separate tooling contracts under `contracts/tooling/`. Python remains the canonical validator and CLI; shared Kotlin prevents the Android harness from producing study-mode metadata; tracked protocol documents are bound into one synthetic readiness manifest by SHA-256. A single semantic admission module owns privacy, condition-matrix, cohort-scope, artifact-location, and protocol-digest rules.

**Tech Stack:** JSON Schema Draft 2020-12, Python 3.12+, jsonschema, pytest, Ruff, strict mypy, Kotlin Multiplatform/JVM tests, kotlinx.serialization JSON, GNU Make, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-24-m0-5-study-readiness-design.md`

## Global Constraints

- M0.5A is non-participant work. Use only synthetic metadata and committed synthetic fixtures.
- Do not change `contracts/schema/session_manifest.v1.schema.json`; absence of `session_kind` retains released `live_study` compatibility semantics.
- Every M0.5 observation admission path rejects absent `session_kind`, `live_study`, a non-`synthetic-m05-*` pseudonym, `input_class=participant`, missing no-person attestation, and non-zero detection-active fraction for `empty_scene`.
- Live detection-bearing input is exactly `displayed_synthetic_figure`; `synthetic_fixture` remains offline/replay-only. A displayed figure that does not fire detection is typed pending and cannot satisfy a detection-dependent gate.
- Human privacy decisions are `UNDECIDED`, `ACCEPTED`, or `REJECTED`. Committed M0.5A protocol state is `UNDECIDED`; participant-ready validation rejects both `UNDECIDED` and `REJECTED`.
- An analysis cohort is homogeneous in platform, device model, model digest, delegate, preprocessing version, and observation-contract version. Mixed tuples fail with `STUDY.COHORT_SCOPE_MISMATCH`.
- Every condition-matrix version is immutable by digest. Pre-lock revisions require a prior digest and reason; after athlete/venue availability is confirmed and the pilot lock is recorded, removing or demoting a required condition counts as a failed required condition.
- `instrument_failure` is viability evidence; `not_executed_resource` is a disclosed coverage gap and must never be counted as signal failure. Any required resource gap yields `INCOMPLETE_COVERAGE`; `GO` requires neither kind of unresolved required-condition gap.
- Artifact references are repository-relative paths plus lowercase SHA-256. No path may be absolute, contain `..`, or resolve outside the repository.
- Registered locations are exactly `device_export`, `device_staging`, `device_transfer_staging`, `study_store_source`, `study_store_derived`, `study_store_report`, and `tombstone`.
- No synchronized-video implementation, Android deletion port, sustained device run, annotation UI, analysis engine, cloud path, participant store, functional iOS backend, signal-quality threshold, or Phase 2 behavior belongs in this plan.
- Follow regression-first TDD for every behavior: observe the focused test fail for the intended missing behavior, make the minimum change, then rerun it green.
- Do not add routine independent reviews. Run one bounded full-range privacy/contract review at the M0.5A boundary; only Critical and Important findings block.
- Keep the ignored ledger at `.superpowers/sdd/2026-08-24-m0-5a-policy-governance/progress.md` current after every commit with branch, worktree, HEAD/base, tests, findings, and next action.

---

### Task 1: Versioned study-readiness tooling contracts

**Files:**
- Create: `contracts/tooling/study_readiness_manifest.v1.schema.json`
- Create: `contracts/tooling/study_condition_matrix.v1.schema.json`
- Modify: `contracts/tooling/validator_error_codes.v1.json`
- Modify: `reference/src/observation_contracts/schema_loader.py`
- Create: `reference/tests/test_study_readiness_contracts.py`

**Interfaces:**
- Produces: `study_readiness_schema_path() -> Path`
- Produces: `study_condition_matrix_schema_path() -> Path`
- Produces: `load_study_readiness_schema() -> Mapping[str, object]`
- Produces: `load_study_condition_matrix_schema() -> Mapping[str, object]`
- Produces: `study_readiness_schema_validator() -> Draft202012Validator`
- Produces: `study_condition_matrix_schema_validator() -> Draft202012Validator`
- Produces stable codes: `STUDY.ARTIFACT_REFERENCE`, `STUDY.PRIVACY_DECISION`, `STUDY.CONDITION_MATRIX_DRIFT`, `STUDY.PILOT_LOCK`, `STUDY.CONDITION_OUTCOME`, `STUDY.COHORT_SCOPE_MISMATCH`, `STUDY.ADMISSION_SESSION_KIND`, `STUDY.ADMISSION_INPUT_CLASS`, `STUDY.EMPTY_SCENE_DETECTION`, `STUDY.SYNTHETIC_DETECTION_PENDING`, and `STUDY.LOCATION_REGISTRY`.

- [ ] **Step 1: RED — loaders and schemas are absent**

Create `reference/tests/test_study_readiness_contracts.py`. Add tests importing all six loader/validator functions above, checking both schemas with `Draft202012Validator.check_schema`, and asserting each new stable code appears exactly once in `validator_error_codes.v1.json`.

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_study_readiness_contracts.py -v
```

Expected: import failure because the loader functions and schemas do not exist.

- [ ] **Step 2: GREEN — add closed tooling schemas**

Add dedicated tooling loaders to `schema_loader.py`; do not add these artifacts to `ArtifactKind` or `ARTIFACT_KINDS` because they are not observation artifacts.

`study_condition_matrix.v1.schema.json` must require:

```json
{
  "condition_matrix_schema_version": "1.0.0",
  "matrix_id": "phase0-flagship-android-v1",
  "conditions": [
    {
      "condition_id": "anchor-45deg-3m-chest-indoor-bright-normal",
      "classification": "required",
      "set_count": 1,
      "required_artifact_cells": ["session_manifest", "raw_pose_stream", "synchronization_evidence"],
      "required_metric_cells": ["detection_rate_curve", "dropout_runs", "landmark_jitter", "cadence_latency_thermal_battery"],
      "source": "docs/phase-0-plan.md#7-exploratory-study-allocation--28-sets"
    }
  ]
}
```

Constrain `condition_id` and `matrix_id` to lowercase slug syntax, classification to `required | exploratory`, `set_count` to a positive integer, both cell arrays to non-empty unique lowercase slugs, and require at least one required condition. Reject duplicate `condition_id` semantically in Task 2.

`study_readiness_manifest.v1.schema.json` must require these top-level properties and reject unknown top-level properties:

```text
study_readiness_schema_version = 1.0.0
readiness_id                   lowercase slug
phase_scope                    m0_5_non_participant
protocols                      capture, annotation, privacy artifact refs
condition_matrix               current/prior refs + explicit pilot lock
condition_outcomes             execution state and typed inadmissibility reason per condition
privacy_decisions              eight named decision records
platform_scope                 exact flagship-only scope object
artifact_locations             non-empty registered location array
analysis_cohorts               array of homogeneous cohort declarations
```

An artifact ref is `{path, sha256}`. Each privacy decision is `{state, decision_record}` where state is `UNDECIDED | ACCEPTED | REJECTED` and `decision_record` is null for `UNDECIDED`, otherwise a repository-relative artifact ref. Require these exact privacy keys:

```text
storage_administrator
storage_location
access_matrix
retention_period
withdrawal_and_deletion
backup_handling
incident_response
face_handling
```

The condition-matrix object is:

```json
{
  "current": {"path": "...", "sha256": "..."},
  "pilot_lock": {
    "state": "UNLOCKED",
    "locked_matrix": null,
    "resource_availability_confirmation": null
  },
  "prior": null,
  "matrix_revision_reason": null
}
```

`prior` is either null or an artifact ref. `matrix_revision_reason` is null when `prior` is null and a non-empty string otherwise. `pilot_lock.state` is `UNLOCKED | LOCKED`. Both lock references are null while unlocked; both are digest-bound artifact refs while locked. The resource confirmation names every athlete, venue, device, and other resource required by the selected matrix. A locked matrix remains the comparison baseline even when `current` later points to a revision; this prevents gradual demotion through a chain of individually small edits.

`condition_outcomes` contains exactly one row per current condition:

```json
{
  "condition_id": "anchor-45deg-3m-chest-indoor-bright-normal",
  "status": "planned",
  "inadmissibility_reason": null
}
```

Status is `planned | admissible | inadmissible`. The reason must be null unless status is inadmissible, then it is exactly `instrument_failure | not_executed_resource`. M0.5A commits only planned rows.

The platform scope requires platform, device model, OS build, camera capability ref, model digest, runtime version, delegate, requested cadence, preprocessing version, observation-contract version, input class, detection-active frame fraction, and rendered scope statement. Permit `UNMEASURED` only for the detection-active fraction when zero frames were submitted.

`artifact_locations` contains lowercase slugs structurally; Task 2 requires its set to equal the seven registered locations in Global Constraints. `analysis_cohorts` contains objects with `cohort_id`, one six-field `scope`, and non-empty `inputs`. Each input is `{artifact, scope}` where `artifact` is a digest-bound ref and `scope` has exactly the same six keys. This deliberate repetition makes the no-mixed-pooling invariant independently checkable instead of trusting a cohort label.

- [ ] **Step 3: GREEN — register stable errors and finish structural tests**

Append the eleven codes without renaming or reordering existing codes and increment `registry_version` from `1.1.0` to `1.2.0`. Add positive minimal documents and negative rows for missing required properties, bad SHA/path, unknown decision state, inconsistent pilot-lock references, invalid outcome/reason combinations, zero set count, and an unknown top-level property.

Run the focused test file. Expected: all Task 1 tests pass.

- [ ] **Step 4: Verify and commit**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_study_readiness_contracts.py reference/tests/test_structural.py -v
make verify
```

Commit only Task 1 files:

```bash
git add contracts/tooling/study_readiness_manifest.v1.schema.json \
  contracts/tooling/study_condition_matrix.v1.schema.json \
  contracts/tooling/validator_error_codes.v1.json \
  reference/src/observation_contracts/schema_loader.py \
  reference/tests/test_study_readiness_contracts.py
git commit -m "feat: define study readiness tooling contracts"
```

---

### Task 2: Canonical readiness and participant-gate semantics

**Files:**
- Create: `reference/src/observation_contracts/study_readiness.py`
- Modify: `reference/src/observation_contracts/cli.py`
- Modify: `reference/tests/test_study_readiness_contracts.py`
- Modify: `reference/tests/test_cli.py`

**Interfaces:**
- Produces: `verify_study_readiness(path: Path, *, require_participant_ready: bool = False) -> list[ValidationIssue]`
- Produces: `scope_tuple(scope: Mapping[str, object]) -> tuple[str, str, str, str, str, str]`
- Produces: `summarize_condition_outcomes(manifest: Mapping[str, object]) -> ConditionOutcomeSummary`
- Produces: frozen `ConditionOutcomeSummary(required_admissible: int, instrument_failures: int, resource_gaps: int, planned: int, decision_state: str)`
- Produces CLI: `verify-study-readiness MANIFEST`
- Produces CLI: `verify-participant-capture-gate MANIFEST`
- Consumes: strict JSON/schema loaders from Task 1.

- [ ] **Step 1: RED — referenced artifacts must exist and match their digest**

Add tests creating a temporary repository with a structurally valid readiness manifest and referenced capture, annotation, privacy, and condition-matrix files. Assert success, then mutate each reference independently and assert exactly one `STUDY.ARTIFACT_REFERENCE` issue at that reference pointer. Add traversal and absolute-path rows; neither may be opened.

Expected RED: `verify_study_readiness` does not exist.

- [ ] **Step 2: GREEN — strict root-confined artifact resolution**

Implement one resolver:

```python
def resolve_artifact(root: Path, relative: str) -> Path:
    candidate = (root / relative).resolve()
    if Path(relative).is_absolute() or candidate == root or not candidate.is_relative_to(root):
        raise ValueError("artifact reference escapes repository root")
    return candidate
```

Load every reference with `read_bytes()`, compare `hashlib.sha256(content).hexdigest()` using `hmac.compare_digest`, and emit sanitized issues that name the path/pointer but never reproduce content.

- [ ] **Step 3: RED — privacy decisions are allowed for readiness but block capture**

Create a manifest whose pilot lock is otherwise valid and whose eight privacy decisions are `UNDECIDED`. Assert:

```python
assert verify_study_readiness(path) == []
issues = verify_study_readiness(path, require_participant_ready=True)
assert {issue.code for issue in issues} == {"STUDY.PRIVACY_DECISION"}
assert len(issues) == 8
```

Add one row proving `REJECTED` also blocks and one proving eight `ACCEPTED` decisions with digest-valid decision records pass the participant-gate check. This verifies mechanism only; do not commit accepted decisions in Task 4.

- [ ] **Step 4: GREEN — derive capture eligibility from decisions**

Do not add `participant_capture_eligible` to the schema. Derive it by requiring all eight states to be `ACCEPTED` and every decision record to exist and match its digest. Keep `verify-study-readiness` non-participant-safe with `UNDECIDED`; make only `verify-participant-capture-gate` use `require_participant_ready=True`.

- [ ] **Step 5: RED — matrix drift and mixed-scope pooling fail closed**

Add tests for:

```text
duplicate condition_id                      -> STUDY.CONDITION_MATRIX_DRIFT
UNLOCKED revision with prior + reason         -> valid
UNLOCKED revision missing prior or reason      -> STUDY.CONDITION_MATRIX_DRIFT
LOCKED without both lock references            -> STUDY.PILOT_LOCK
LOCKED removal of locked required condition    -> STUDY.CONDITION_MATRIX_DRIFT
LOCKED required-to-exploratory demotion        -> STUDY.CONDITION_MATRIX_DRIFT
participant gate while UNLOCKED                -> STUDY.PILOT_LOCK
inadmissible without a typed reason            -> STUDY.CONDITION_OUTCOME
admissible/planned with an inadmissibility reason -> STUDY.CONDITION_OUTCOME
instrument_failure                             -> viability failure
not_executed_resource                          -> coverage gap, not viability failure
required resource gap                         -> decision_state INCOMPLETE_COVERAGE
two pooled inputs with differing scope key   -> STUDY.COHORT_SCOPE_MISMATCH
two separate cohorts with differing keys     -> valid comparison, no pooling
missing/duplicate/unregistered location      -> STUDY.LOCATION_REGISTRY
```

For an unlocked manifest, allow a changed matrix only when `prior` resolves to the preceding digest and `matrix_revision_reason` is non-empty. For a locked manifest, load `pilot_lock.locked_matrix` and return deterministic issues for every condition that was `required` at lock but is removed or demoted in the current matrix. Do not compare only with the immediate prior. The manifest must not contain inline condition copies.

Participant-ready validation also requires `pilot_lock.state=LOCKED` and digest-valid locked-matrix and resource-confirmation artifacts. This is in addition to, never instead of, the eight accepted privacy decisions.

- [ ] **Step 6: GREEN — implement deterministic semantic validation**

Use this exact cohort key order:

```python
_SCOPE_KEYS = (
    "platform",
    "device_model",
    "model_sha256",
    "delegate",
    "preprocessing_contract_version",
    "observation_contract_version",
)
```

Each pooled cohort requires every input artifact's scope tuple to equal the cohort scope. Separate cohort declarations may have different tuples. Sort issues by `(pointer or "", code, message)` before returning.

Expose a pure condition summary that counts admitted required conditions, instrument failures, and resource coverage gaps separately. `not_executed_resource` must not increment the instrument-failure count and sets `decision_state=INCOMPLETE_COVERAGE`; it cannot be reclassified as any viability decision.

- [ ] **Step 7: RED/GREEN — CLI exit contracts**

Add CLI tests proving both commands return `0` on valid input, `2` on semantic/structural failure, and `3` only on unexpected internal failure. JSON output remains a sorted list of `ValidationIssue.to_dict()` objects. Implement the two parsers/handlers without duplicating file loading.

- [ ] **Step 8: Verify and commit**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_study_readiness_contracts.py reference/tests/test_cli.py -v
make verify
```

Commit:

```bash
git add reference/src/observation_contracts/study_readiness.py \
  reference/src/observation_contracts/cli.py \
  reference/tests/test_study_readiness_contracts.py \
  reference/tests/test_cli.py
git commit -m "feat: enforce study readiness semantics"
```

---

### Task 3: Enumerated M0.5 live admission and empty-scene consistency

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05Admission.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/M05AdmissionTest.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidence.kt`
- Modify: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidenceTest.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`
- Modify: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/LiveCameraCompositionTest.kt`
- Modify: `tools/check_live_capture_run.py`
- Modify: `reference/tests/test_live_capture_run_check.py`
- Modify: `reference/tests/test_architecture.py`

**Interfaces:**
- Produces Kotlin: `enum class M05InputClass { EMPTY_SCENE, DISPLAYED_SYNTHETIC_FIGURE }`
- Produces Kotlin: `enum class M05DetectionEvidenceState { NOT_APPLICABLE, ACTIVE, PENDING }`
- Produces Kotlin: `data class M05AdmissionEvidence(...)`
- Produces Kotlin: `data class M05AdmissionResult(..., val detectionEvidenceState: M05DetectionEvidenceState, val blockingCodes: List<String>)`
- Produces Kotlin: `object M05Admission { fun evaluate(evidence: M05AdmissionEvidence): M05AdmissionResult }`
- Produces Python: `M05_ADMISSION_PATHS = ("live_transcript", "recorded_live_ledger")`
- Consumes the stable admission codes registered in Task 1.

- [ ] **Step 1: RED — all M0.5 admission paths reject missing or incompatible metadata**

Add one parameterized Python test over every member of `M05_ADMISSION_PATHS`. For each path, mutate a valid synthetic M0.5 observation independently and require the named stable issue:

```text
session_kind absent               -> STUDY.ADMISSION_SESSION_KIND
session_kind = live_study         -> STUDY.ADMISSION_SESSION_KIND
participant_pseudonym_id = human  -> STUDY.ADMISSION_INPUT_CLASS
input_class = participant         -> STUDY.ADMISSION_INPUT_CLASS
input_class = synthetic_fixture   -> STUDY.ADMISSION_INPUT_CLASS
no-person attestation absent/false -> STUDY.ADMISSION_INPUT_CLASS
empty_scene with fraction > 0     -> STUDY.EMPTY_SCENE_DETECTION
displayed figure with zero/UNMEASURED detection -> STUDY.SYNTHETIC_DETECTION_PENDING
```

The test must enumerate the production constant and assert its exact membership. Adding a future admission path without adding it to this table must fail the architecture test.

Expected RED: the path constant and M0.5 checks do not exist.

- [ ] **Step 2: GREEN — centralize Python admission before transcript/ledger acceptance**

Implement one helper used by both `check_transcript` and `verify_recorded_ledger`; neither public path may reproduce the field checks. The released observation schema remains untouched. Missing `session_kind` is rejected only at these M0.5 admission boundaries.

Keep `check_transcript`'s existing live-capture behavior for non-M0.5 callers. Select M0.5 validation explicitly from the evidence marker rather than guessing from a pseudonym string. Existing records without that marker stay on the legacy verification path. Return deterministic, sanitized failure data; do not throw a generic assertion for policy rejection.

- [ ] **Step 3: RED — Kotlin cannot emit caller-selected participant identity**

Add common tests for the pure admission object:

```kotlin
val result = M05Admission.evaluate(
    M05AdmissionEvidence(
        sessionId = "00000000-0000-4000-8000-000000000001",
        sessionKind = "live_conformance",
        inputClass = M05InputClass.EMPTY_SCENE,
        operatorAttestedNoPerson = true,
        submittedFrameCount = 10,
        detectionActiveFrameCount = 0,
    ),
)
assertEquals("synthetic-m05-00000000-0000-4000-8000-000000000001", result.participantPseudonymId)
assertEquals(0.0, result.detectionActiveFrameFraction)
assertEquals(M05DetectionEvidenceState.NOT_APPLICABLE, result.detectionEvidenceState)
```

Add mutation rows proving the result rejects a different session kind, false attestation, `detectionActiveFrameCount > submittedFrameCount`, `EMPTY_SCENE` with a non-zero derived fraction, and zero submitted frames when a numeric fraction would otherwise be invented. Add displayed-figure rows proving a positive derived fraction activates detection-dependent evidence while zero or `UNMEASURED` returns `STUDY.SYNTHETIC_DETECTION_PENDING`.

In the Android composition test, first prove the current caller-controlled `LiveManifestMetadata.participantPseudonymId` can produce a non-synthetic identity. This is the intended RED failure.

- [ ] **Step 4: GREEN — make identity and detection evidence derived**

`M05AdmissionEvidence` owns only source facts. `M05AdmissionResult` derives:

```text
session_kind                    live_conformance
participant_pseudonym_id        synthetic-m05-<session UUID>
input_class                     empty_scene | displayed_synthetic_figure
operator_attested_no_person     true
detection_active_frame_fraction active/submitted, or UNMEASURED when submitted=0
```

Add those six values to `LiveCaptureEvidence` as one typed nested M0.5 admission record and render them into the signed device-gate JSON consumed by both Python paths. The recorded-ledger path must never infer them from a missing manifest.

`detectionEvidenceState` is `NOT_APPLICABLE` for a valid zero-detection empty scene, `ACTIVE` for a displayed figure with at least one submitted frame and positive derived fraction, and `PENDING` for a displayed figure with zero submitted frames or zero detection. Keep the underlying pending observation and attach `STUDY.SYNTHETIC_DETECTION_PENDING`; never fabricate detection.

Remove `participantPseudonymId` from `LiveManifestMetadata`. The composition supplies its generated session ID to `M05Admission`, then uses only the returned values for both the session manifest and `LiveCaptureEvidence`. Add a cross-object test asserting the manifest and evidence record carry identical session kind and pseudonym. Do not add participant mode or a bypass flag.

Add an explicit repository regression that opens and verifies the existing recorded ledgers under `docs/evidence/live-capture/` and `docs/evidence/replay-five-run/` unchanged. Assert the existing live record has no M0.5 marker and therefore follows the legacy path. Regeneration or editing of historical evidence is forbidden.

- [ ] **Step 5: Mutation verification**

Temporarily apply and independently verify these mutations turn a focused test red, then revert each mutation:

1. permit absent `session_kind` in one Python admission path;
2. change the synthetic pseudonym prefix;
3. trust a caller-provided detection fraction instead of deriving it;
4. change the empty-scene comparison from `== 0` to `>= 0`;
5. allow zero displayed-figure detections to satisfy its detection-dependent gate;
6. remove one member from `M05_ADMISSION_PATHS` or bypass the shared helper.

Record each test name and failure in the ignored ledger. A surviving mutant is unresolved until either the test or mutation is corrected.

- [ ] **Step 6: Verify and commit**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_live_capture_run_check.py reference/tests/test_device_evidence_ledger.py reference/tests/test_architecture.py -v
make verify-harness-jvm
make verify
```

Commit only Task 3 files:

```bash
git add apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05Admission.kt \
  apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/M05AdmissionTest.kt \
  apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidence.kt \
  apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/live/LiveCaptureEvidenceTest.kt \
  apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt \
  apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/LiveCameraCompositionTest.kt \
  tools/check_live_capture_run.py \
  reference/tests/test_live_capture_run_check.py \
  reference/tests/test_architecture.py
git commit -m "feat: enforce synthetic M0.5 live admission"
```

---

### Task 4: Digest-bound protocols and candidate flagship-only condition matrix

**Files:**
- Create: `docs/protocols/capture-protocol.v1.md`
- Create: `docs/protocols/annotation-protocol.v1.md`
- Create: `docs/protocols/privacy-retention-withdrawal.v1.md`
- Create: `docs/protocols/condition-matrix.m05.v1.json`
- Create: `docs/protocols/study-readiness.m05.v1.json`
- Create: `reference/tests/test_study_readiness_repository.py`

**Artifacts:**
- The readiness manifest binds all three protocols and the condition matrix by SHA-256.
- Human-owned privacy decisions remain `UNDECIDED` with null decision records.
- The scope statement is flagship Android only and explicitly excludes iOS equivalence, mid-range behavior, participant evidence, signal-quality thresholds, and production readiness.

- [ ] **Step 1: RED — repository readiness artifacts do not exist**

Add a repository-level test that loads `docs/protocols/study-readiness.m05.v1.json` using Task 2's canonical verifier. Assert the manifest is valid for non-participant readiness and invalid for participant capture with exactly eight `STUDY.PRIVACY_DECISION` issues plus one `STUDY.PILOT_LOCK` issue.

Assert every referenced artifact exists, matches its recorded digest, and is beneath `docs/protocols/`. Expected RED: the files are absent.

- [ ] **Step 2: GREEN — write the capture protocol without authorizing capture**

The capture protocol must specify:

- approved `session_kind=live_conformance` and reserved `synthetic-m05-*` identity for M0.5;
- non-participant live input classes `empty_scene` and `displayed_synthetic_figure` only;
- operator no-person attestation before camera start;
- camera placement, full-body framing, lighting, clothing, distance, height, and angle recording rules;
- device/app/model/delegate/cadence/thermal/battery metadata;
- empty-scene detection-active fraction must equal zero, while a displayed synthetic figure must
  produce positive detection evidence before any detection-dependent gate can pass;
- stopping behavior when a person enters frame, metadata is absent, or admission disagrees with the matrix;
- no claim that the current S25 evidence generalizes to mid-range Android or iOS.

Do not add a participant-capture procedure or consent template.

- [ ] **Step 3: GREEN — write blind annotation and privacy protocols as undecided policy**

The annotation protocol must make the primary labeling component structurally blind to pose overlays, predictions, confidence, and machine-derived events. It must distinguish independent labels from adjudication and state that no annotation-dependent claim is admitted until two annotators are available. This task documents interfaces only; it does not implement annotation tooling.

The privacy/retention/withdrawal protocol must enumerate all eight human decisions and all seven registered locations. For each decision, record owner, required evidence, and effect of `UNDECIDED`, `ACCEPTED`, or `REJECTED`. State mechanically that only eight `ACCEPTED` states with digest-valid decision records can open participant capture. Leave every committed state `UNDECIDED`; do not invent storage administrators, retention periods, access roles, withdrawal channels, or face policy.

- [ ] **Step 4: RED/GREEN — pre-register the candidate 28-set matrix without false platform attribution**

Translate `docs/phase-0-plan.md` section 7 into explicit condition rows totaling 28 sets. Preserve the current allocation and these classifications:

- all named angle, distance, height, anchor-replication, clothing, environment, Athlete B, failure-induction, and on-device low-FPS cells are `required`;
- Athlete C cells and reserve/re-shoot cells are `exploratory`;
- each row cites the section-7 source anchor;
- the matrix describes one flagship-Android study cohort and does not encode the obsolete dual-phone attribution sentence as a condition identity.

Add tests asserting exact total `28`, unique condition IDs, the required/exploratory classification above, at least three separately identified anchor sessions, and the absence of cross-platform or dual-phone attribution fields.

Every row must also name its required artifact and metric cells before pilot data exists. Reuse the canonical cell slugs declared by the capture and annotation protocols; do not leave a required condition with an empty cell list or infer cells later from observed results.

This commit creates an immutable candidate matrix, not the pilot lock. Athlete B, the repeated-session athlete/venue availability, and every other resource-dependent required condition must be confirmed in a separate digest-bound resource-availability record before a later study-run manifest may lock the matrix. If availability is not confirmed, create a new pre-lock matrix revision with `prior` and `matrix_revision_reason`; do not misclassify non-recruitment as `instrument_failure`.

- [ ] **Step 5: GREEN — bind the synthetic readiness manifest**

Populate the manifest with:

```text
phase_scope                              m0_5_non_participant
platform/device                          android / SM-S938B
input_class                              empty_scene
detection_active_frame_fraction          0
all privacy decision states              UNDECIDED
condition_matrix.current.sha256           actual matrix digest
condition_matrix.pilot_lock.state         UNLOCKED
condition_matrix.pilot_lock.locked_matrix null
condition_matrix.pilot_lock.resource_availability_confirmation null
condition_matrix.prior                    null
condition_matrix.matrix_revision_reason   null
condition_outcomes                        one planned/null-reason row per condition
```

Use the actual evidence, model, runtime, delegate, preprocessing, observation-contract, OS-build, requested-cadence, and camera-capability values already tracked in the repository. Do not use `TBD`, a wildcard, or a value copied from memory when an authoritative artifact exists.

Render the platform scope as a literal manifest field and assert it includes every exclusion named above. Compute all four digests only after their referenced files are final.

- [ ] **Step 6: Mutation verification**

Temporarily mutate each item and prove the repository test fails, then restore it:

1. edit one protocol after its digest is recorded;
2. mark one privacy decision `ACCEPTED` without a decision record;
3. claim a pilot lock without both digest-valid lock references;
4. remove or demote a condition required by a locked baseline;
5. change empty-scene detection fraction from zero;
6. change one cohort scope component while retaining it in a pooled cohort;
7. classify `not_executed_resource` as an instrument failure.

- [ ] **Step 7: Verify and commit**

Run:

```bash
.venv/bin/python -m pytest reference/tests/test_study_readiness_repository.py reference/tests/test_study_readiness_contracts.py -v
.venv/bin/python -m observation_contracts verify-study-readiness docs/protocols/study-readiness.m05.v1.json
set +e
.venv/bin/python -m observation_contracts verify-participant-capture-gate docs/protocols/study-readiness.m05.v1.json
participant_gate_status=$?
set -e
test "$participant_gate_status" -eq 2
make verify
```

Commit:

```bash
git add docs/protocols/capture-protocol.v1.md \
  docs/protocols/annotation-protocol.v1.md \
  docs/protocols/privacy-retention-withdrawal.v1.md \
  docs/protocols/condition-matrix.m05.v1.json \
  docs/protocols/study-readiness.m05.v1.json \
  reference/tests/test_study_readiness_repository.py
git commit -m "docs: record synthetic M0.5 study readiness"
```

---

### Task 5: Operating gate and protected repository integration

**Files:**
- Modify: `AGENTS.md`
- Modify: `Makefile`
- Modify: `contracts/CHANGELOG.md`
- Modify: `reference/tests/test_architecture.py`
- Modify: `reference/tests/test_study_readiness_repository.py`

**Interfaces:**
- Produces Make target: `verify-study-readiness`
- Preserves GitHub required-check job identifier: `verify`
- Establishes M0.5A as the current synthetic-only operating gate.

- [ ] **Step 1: RED — the repository gate does not execute readiness validation**

Add architecture tests asserting:

1. `Makefile` defines `verify-study-readiness` and the existing `verify` recipe invokes it;
2. the target runs `verify-study-readiness` successfully;
3. the target runs `verify-participant-capture-gate` and requires exact exit status `2` for the committed manifest;
4. `AGENTS.md` names the approved M0.5 spec and keeps participant capture and participant data prohibited;
5. no GitHub Actions job ID named `verify` is renamed.

Expected RED: the target and operating-gate wording are absent.

- [ ] **Step 2: GREEN — add the Make gate without masking exit status**

Add `verify-study-readiness` to `.PHONY`. Its recipe validates the committed manifest, then captures the participant-gate command status directly and requires `2`. Do not pipe either command into `grep`, and do not treat every non-zero status as the expected policy block. Add the new target to `verify` after repository guarding so local and protected CI execute the same check.

Do not add device requirements, network calls, or model downloads to `make verify`.

- [ ] **Step 3: GREEN — re-gate agents to M0.5 synthetic-only work**

Update `AGENTS.md` to state:

- the diagnostic harness integration boundary closed at the current `main` SHA recorded when this task starts;
- M0.5 is governed by the approved study-readiness spec and ADR-0005;
- M0.5A permits tooling contracts, synthetic governance fixtures, protocols, and fail-closed admission checks;
- M0.5B/C remain separate approved slices and must not be pulled into this PR;
- participant capture remains blocked until the release owner accepts all eight privacy decisions, confirms matrix resources, locks the selected matrix digest, and a digest-valid participant gate passes;
- participant video, biometric-derived observations, consent records, identity mappings, credentials, and real participant fixtures remain prohibited in Git;
- no numeric reliability threshold, rep engine, form score, production claim, mid-range claim, or iOS-equivalence claim may be inferred.

Preserve the collaboration and release-authority sections; this task changes scope, not authority.

- [ ] **Step 4: GREEN — record the tooling-contract release**

Add a `1.2.0` tooling-contract entry to `contracts/CHANGELOG.md` naming the two schemas, eleven stable errors, canonical validator commands, M0.5 admission behavior, ADR-0005, and compatibility note: observation schemas remain at their released versions and absent `session_kind` retains its schema-level default. State that participant capture is blocked and that no tag is created by this plan.

- [ ] **Step 5: Full verification and clean-checkout proof**

Run from the feature worktree:

```bash
make verify
make verify-harness-jvm
make verify-harness-ios
make verify-kotlin-export
git diff --check origin/main...HEAD
```

Then create a temporary local clone of the exact committed HEAD using `mktemp -d` and `git clone --local --no-hardlinks`, bootstrap its Python environment, create its ignored Android `local.properties` from the detected SDK path, fetch the model asset, and rerun:

```bash
make verify
make verify-harness-jvm
make verify-harness-ios
make verify-kotlin-export
```

Delete only that explicitly resolved temporary clone after recording commands, exit codes, and final HEAD in the ignored ledger. A missing local SDK or unavailable model transport is a typed environment block, not permission to weaken a gate.

- [ ] **Step 6: Bounded M0.5A privacy/contract review**

Request one fresh review over `origin/main...HEAD`, scoped to:

- privacy decisions cannot default to capture authority;
- an unlocked matrix cannot open participant capture, while pre-lock revisions retain prior digest and reason;
- post-lock required-condition drift fails, and resource gaps are not counted as instrument failures;
- all live M0.5 admission paths are enumerated and reject missing `session_kind`;
- empty-scene detection is derived and must be zero;
- displayed-synthetic-figure evidence stays pending unless detection actually fires;
- artifact references are digest-bound and root-confined;
- condition-matrix revision and cohort-pooling rules fail closed;
- committed artifacts contain synthetic metadata only.

Only Critical and Important findings block. Apply at most one fix round and one scoped re-review. Record Minor findings as debt without expanding this slice.

- [ ] **Step 7: Final commit and PR**

Commit Task 5 files:

```bash
git add AGENTS.md Makefile contracts/CHANGELOG.md \
  reference/tests/test_architecture.py \
  reference/tests/test_study_readiness_repository.py
git commit -m "chore: gate M0.5 study readiness"
```

Push the branch and open a non-draft PR against `main`. The PR description must include scope, exclusions, exact verification evidence, bounded-review result, unresolved Minor debt, and the explicit statement that participant capture remains blocked. Wait for the protected `verify` check to become green.

Do not merge, create a tag, or move a tag. Those remain release-owner actions.

---

## Completion Criteria

M0.5A is implementation-complete only when:

- all five task commits exist on the feature branch and the worktree is clean;
- `make verify`, JVM, iOS compile, Kotlin export, and clean-checkout verification pass at the final SHA;
- the one bounded privacy/contract review has no unresolved Critical or Important finding;
- the PR is open, mergeable, and its required `verify` check is green;
- the committed readiness manifest passes non-participant validation and fails participant admission with exactly eight undecided privacy decisions plus an unlocked pilot matrix;
- no participant artifact, participant authority, tag, or merge has been created.

Record the final branch, worktree, base/head SHAs, commits, checks, review findings, Minor debt, PR URL, and next release-owner action in the ignored ledger.
