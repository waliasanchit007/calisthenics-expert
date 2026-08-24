# Full-Range Fix Report

## Scope

Implemented exactly the three validated Important fixes from `full-range-review.md`:

1. End-to-end source admission.
2. Canonical readiness detection semantics.
3. Prior matrix provenance.

No push, PR, merge, or tag was performed.

## RED

- `./gradlew :shared:jvmTest --tests 'com.calisthenics.diagnostic.study.M05AdmissionTest' --tests 'com.calisthenics.diagnostic.study.M05AdmissionHostRoundTripTest'`
  - Failed as expected.
  - `M05AdmissionHostRoundTripTest.wrongSessionAndFalseAttestationRenderAsBlockedOnEveryPythonHostPath` failed because rendered invalid admission facts were admitted by host paths.
  - `M05AdmissionTest.policyInadmissibilityReturnsTypedBlockingCodesAndRetainsUnmeasuredEvidence` failed because `M05Admission.evaluate` normalized invalid source facts to `live_conformance` and `true`.
- `./.venv/bin/python -m pytest reference/tests/test_study_readiness_contracts.py::test_participant_gate_rejects_contradictory_or_pending_detection_evidence reference/tests/test_study_readiness_contracts.py::test_unlocked_matrix_revision_rejects_digest_valid_non_matrix_prior reference/tests/test_study_readiness_contracts.py::test_unlocked_matrix_revision_rejects_prior_digest_equal_to_current -q`
  - Failed as expected: 5 failed.
  - The three detection counterexamples returned no issues.
  - The digest-valid non-matrix prior returned no issues.
  - The same-digest prior/current revision returned no issues.
- `./gradlew :androidApp:testDebugUnitTest --tests 'com.calisthenics.diagnostic.composition.LiveCameraCompositionTest.falseOperatorAttestationBlocksBeforeAnyCameraResourceIsCreated'`
  - Failed at compile time as expected because the explicit `operatorAttestedNoPerson` start parameter and `OperatorAttestationRequired` reason did not exist.
- `./gradlew :composeApp:allTests --tests 'com.calisthenics.diagnostic.ui.LiveRunStateTest'`
  - Failed at command configuration because `allTests` does not accept `--tests`; reran the actual focused coverage via `:composeApp:allTests`.

## Changes

- `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05Admission.kt`
  - Preserves source `sessionKind` and `operatorAttestedNoPerson` in `M05AdmissionResult` instead of normalizing them to admitted values.
- `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/M05AdmissionTest.kt`
  - Covers blocking codes while proving invalid source facts are retained.
- `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/study/M05AdmissionHostRoundTripTest.kt`
  - Renders Kotlin live evidence and verifies both Python M05 admission paths reject wrong-session and false-attestation records.
- `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`
  - Removes the production true attestation constant.
  - Adds explicit, non-default `operatorAttestedNoPerson` to live start.
  - Blocks false attestation before camera/inference/session resources are created.
- `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/LiveCaptureDeviceGateTest.kt`
  - Physical device gate passes `operatorAttestedNoPerson = true` explicitly.
- `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/MainActivity.kt`
  - Passes the UI attestation state into the Android live start API.
- `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/HarnessUiState.kt`
  - Adds non-default live no-person attestation state and disables live start until it is deliberately set.
- `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/HarnessApp.kt`
  - Adds the live attestation checkbox.
- `apps/diagnostic-harness/composeApp/src/commonTest/kotlin/com/calisthenics/diagnostic/ui/LiveRunStateTest.kt`
  - Covers default-false attestation and mode-change reset.
- `reference/src/observation_contracts/study_readiness.py`
  - Parses/schema-validates prior condition matrices.
  - Rejects same-digest prior/current claimed revisions.
  - Adds canonical empty-scene and displayed synthetic figure detection semantics.
- `reference/tests/test_study_readiness_contracts.py`
  - Adds participant-gate detection regressions, non-matrix prior regression, same-digest prior regression, and keeps a valid different-digest prior revision case.

## GREEN

- `./gradlew :shared:jvmTest --tests 'com.calisthenics.diagnostic.study.M05AdmissionTest' --tests 'com.calisthenics.diagnostic.study.M05AdmissionHostRoundTripTest'`
  - Passed.
- `./.venv/bin/python -m pytest reference/tests/test_study_readiness_contracts.py::test_participant_gate_rejects_contradictory_or_pending_detection_evidence reference/tests/test_study_readiness_contracts.py::test_unlocked_matrix_revision_rejects_digest_valid_non_matrix_prior reference/tests/test_study_readiness_contracts.py::test_unlocked_matrix_revision_rejects_prior_digest_equal_to_current -q`
  - 5 passed.
- `./.venv/bin/python -m pytest reference/tests/test_study_readiness_contracts.py -q`
  - 70 passed.
- `./gradlew :androidApp:testDebugUnitTest --tests 'com.calisthenics.diagnostic.composition.LiveCameraCompositionTest.falseOperatorAttestationBlocksBeforeAnyCameraResourceIsCreated'`
  - Passed.
- `./gradlew :composeApp:allTests`
  - Passed.
- `make verify`
  - Passed.
  - Ruff format/check passed.
  - Mypy passed.
  - `reference/tests`: 590 passed.
  - `verify-study-readiness` non-participant verification was valid.
  - Participant-capture gate remained blocked as expected with status 2.
- `make verify-harness-jvm`
  - Passed.
- `make verify-harness-ios`
  - Passed.
- `make verify-kotlin-export`
  - Passed.
  - `manifest sha256: afdb9fc77fde34b254dd26b1fe6c9ed9695f0940af55ed25de9dcd864f6a56e4`
  - `frames sha256: 9fe602da7f2e1e09a5c31133f7222d8ad9c39cdbf0b3342a9c231926db0ba230`
- `git diff --check origin/main...HEAD`
  - Passed.

## Historical Evidence

- `git diff --name-only -- docs/evidence docs/protocols fixtures contracts` returned no paths.
- Historical evidence/protocol/fixture/contract artifacts were unchanged.

## Unresolved Items

None.
