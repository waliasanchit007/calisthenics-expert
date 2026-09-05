# Calisthenics M0.5B attribution-v2 Task 5 report

## Scope and baseline

- Worktree: `/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/worktrees/m0-5b-task-4-synchronized-video`
- Branch: `codex/m0-5b-task-4-synchronized-video`
- Parent: `6a6b82b365535dc48a98482f8537c7d79cfa9887`
- Task: independent host admission and durable-ledger verification for attribution v2
- Evidence: synthetic fixtures only; no participant data, physical device, adb run, paid credits, push, PR, merge, tag, or Task 6 work

## TDD evidence

Each behavioral increment was tested before its production implementation, run to a named failure, then implemented minimally and rerun focused.

| Increment | RED command and result | GREEN result |
| --- | --- | --- |
| Strict checker surface | `.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py` -> exit 2; collection failed with `ModuleNotFoundError: No module named 'check_m05b_attribution_v2'` | Checker extraction and transfer tests pass in the final focused suite |
| Protocol rehash | `... -k rehashes` -> exit 1; expected `ATTRIBUTION.PROTOCOL_DIGEST` was absent after the digest was made device-declared | `1 passed` after restoring host hashing |
| Callback admission closure | `... -k callback_recomputation_retains` -> exit 1; expected `ATTRIBUTION.ADMISSION_STATE_DIVERGENCE` was absent after terminal matching was bypassed | `1 passed` after restoring callback matching |
| Exact backend duration derivation | `... -k reports_backend_limitation` -> exit 1; expected `ATTRIBUTION.BACKEND_LIMITED` was absent after durations were collapsed to a median | `1 passed` after restoring the ordered exact duration vector |
| Both replicate orders | `... -k requires_both_preregistered` -> exit 1; expected `ATTRIBUTION.REPLICATE_DISAGREEMENT` was absent after one replicate was accepted | `1 passed` after restoring the two-order requirement |
| Selector-only exclusion | `... -k selector_only_evidence` -> exit 1; expected `ATTRIBUTION.SELECTOR_ONLY` was absent after the blocker was removed | `1 passed` after restoring the blocker |
| Ledger evidence integrity | `.venv/bin/python -m pytest -q reference/tests/test_device_evidence_ledger.py -k attribution_v2_ledger_reopens_recorded_host_evidence` -> exit 1; forged evidence digest did not raise after comparison was bypassed | `1 passed` after restoring exact-byte SHA verification |
| Host thermal recomputation | Focused test was strengthened to monkeypatch the pure validator and then ran RED with missing `ATTRIBUTION.ENVIRONMENT_MISMATCH` | Host recomputes environment mismatch directly; focused test passes |

## Implemented behavior

- Strictly extracts exactly one `M05BAttributionV2Evidence` record from the exact approved instrumentation suite and completion transcript.
- Uses fixed-argv binary-safe `adb -s SERIAL exec-out run-as PACKAGE cat RELATIVE_PATH`, with safe relative-path validation, temporary transfer, file fsync, atomic rename, directory fsync, exact length/SHA verification, and `awaiting_recovery` on transport failure.
- Validates the closed v2 observation schema, reopens and hashes the canonical protocol, binds prepared candidate identity and authorized device identity, and emits sanitized host evidence.
- Recomputes source count/configuration/timestamps/capacity, backend warm-up predicates and service durations from `completion_timestamp_ns - admission_observed_session_ns`, selector sequences, callback terminal classes/conservation/closure, inference failures, thermal recovery, replicate order/rates/support, and final status/blocker assertions.
- Treats null phase and invalid duration operands as fail-closed divergence; ignores `capture_timestamp_ns` and device-declared service durations as authorities.
- Reopens pending, recorded, and blocked ledger states, re-hashes exact host evidence bytes, validates evidence schema and bindings, and recomputes status/blockers.
- Promotes the complete 18-code attribution-v2 family atomically to registry `1.6.0`, retains the exact 1.5 prefix, and records the tooling changelog entry.
- CLI classes are implemented as 0 admitted, 1 malformed/infrastructure, 2 usage, and 3 trustworthy blocker.

## Mutation proof

All six required mutations failed their named behavioral test and were restored byte-for-byte before proceeding. The first four were restored to `a8b0c93df42da5ad97ad116579bff7dbc619bb668c6e8ad9e6c065f7c7aaba39`; the final two were restored to `2478958e731d39a137a48b0afabe9b0d39257d6b` (the implementation hash after the direct environment-recomputation increment).

| Mutation | Focused failure | Exit |
| --- | --- | ---: |
| Remove protocol rehash | `-k rehashes`: expected `ATTRIBUTION.PROTOCOL_DIGEST` absent | 1 |
| Drop callback admission-state divergence | `-k callback_recomputation_retains`: expected code absent | 1 |
| Replace exact backend durations with median | `-k reports_backend_limitation`: expected `ATTRIBUTION.BACKEND_LIMITED` absent | 1 |
| Accept one replicate | `-k requires_both_preregistered`: expected `ATTRIBUTION.REPLICATE_DISAGREEMENT` absent | 1 |
| Allow selector-only admission | `-k selector_only_evidence`: expected `ATTRIBUTION.SELECTOR_ONLY` absent | 1 |
| Skip evidence digest comparison | `-k attribution_v2_ledger_reopens_recorded_host_evidence`: forged digest did not raise | 1 |

## Required verification

Exact Task 5 Step 9 commands completed successfully:

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py reference/tests/test_device_evidence_ledger.py reference/tests/test_m05b_attribution_v2.py reference/tests/test_validator_code_emission.py
73 passed in 0.76s

.venv/bin/python -m ruff format --check reference tools/check_m05b_attribution_v2.py
71 files already formatted

.venv/bin/python -m ruff check reference tools/check_m05b_attribution_v2.py
All checks passed!

MYPYPATH=reference/src .venv/bin/python -m mypy --strict tools/check_m05b_attribution_v2.py
Success: no issues found in 1 source file

git diff --check
success
```

Additional promotion-contract verification:

```text
.venv/bin/python -m pytest -q reference/tests/test_m05b_attribution_v2_contract.py
7 passed in 0.07s
```

## Changed files

- `tools/check_m05b_attribution_v2.py`
- `reference/tests/test_check_m05b_attribution_v2.py`
- `reference/tests/test_device_evidence_ledger.py`
- `reference/tests/test_m05b_attribution_v2_contract.py`
- `reference/tests/test_validator_code_emission.py`
- `reference/src/observation_contracts/validator_codes.py`
- `contracts/tooling/validator_error_codes.v1.json`
- `contracts/CHANGELOG.md`
- `.superpowers/sdd/2026-09-05-m0-5b-task-6-attribution-v2/task-5-report.md`

## Self-review and concerns

- No Critical or Important concern identified.
- No shell command strings or `shell=True` are used; adb is fixed argv only.
- Sanitized host output does not include the device serial, app-private absolute paths, image/video bytes, landmarks, or wall-clock fields.
- Device-declared status, blockers, summaries, durations, counts, rates, and predicates are assertions checked against host recomputation.
- The existing v2 pending ledger remains pending; this task does not manufacture physical evidence or promote it to recorded.
- The promotion-contract test was updated because the approved Task 5 plan explicitly requires the complete v2 family to move from draft to stable in registry 1.6.0.

## Fix round: review findings

Fix base: `a636d87f72ca5e0c2e7c631fa03389e782672323`. This bounded round addressed the substantiated review findings only; Task 6 was not started.

### Regression-first RED evidence

Focused Python regressions were added before implementation. The initial command

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py -k 'undersampled_arm_two or arm_two_phase or structurally_sanitized or non_export_session or fractional_rate_ordering or verifies_expected_bytes or wrong'
```

exited `1` with seven intended failures: undersampled Arm 2 was admitted, phase replay was not enforced, raw sanitized strings leaked, a non-export session reference was accepted, fractional rate ordering collapsed, and the transfer interface lacked expected byte arguments. The raw Arm 2 inference-failure regression also exited `1` because `ATTRIBUTION.INFERENCE_FAILURE` was absent. The ledger protocol-field regression exited `1` because a forged `0*64` evidence digest was not rejected. The top-level forged identity/device regressions each exited `1` before their validation was implemented.

Producer regressions were RED before the model change:

```text
./gradlew --no-daemon :shared:jvmTest --tests '*M05BAttributionV2JsonTest*' :androidApp:testDebugUnitTest --tests '*M05BAttributionV2RunnerTest*'
exit 1: No parameter with name 'armStartedNs' found

./gradlew --no-daemon :shared:jvmTest --tests '*M05BAttributionV2Test.exactRateOrderingUsesCrossProductsWhenIntegerFloorsCollide'
exit 1: unresolved reference: attributionV2ExactRateOrderValues
```

### Implemented fix

- Arm 2 synthetic runs now contain 120 ordered raw terminal callbacks: 20 warm-up completions followed by exactly 100 measured completed/failed terminals, with raw `arm_started_ns`, phase, admission, completion, terminal class, and capture timestamps. The producer records arm start and recomputes measured span from ordered terminal captures.
- The host replays the dual warm-up boundary from `arm_started_ns`, requires the two warm-up predicates, compares every declared phase, rejects null/invalid operands, requires exact measured completion/failure cardinality, zero raw inference failures, closure, and exact raw capture span. Run and replicate summaries remain assertions only; every verdict and replicate status is recomputed.
- Sanitized host evidence now uses a closed structural projection. Device identity is copied only for an authorized four-token Build tuple with manufacturer/model agreement; invalid identities become a fixed placeholder. Prepared identity fields are validated formats/enums and are not copied from observation alternatives. Session references are UUIDv4-derived export references or deterministic SHA references. Adversarial coverage includes serial-like identity, private/absolute paths, participant/media/landmark text, forged top-level identity, and forged top-level device fields.
- Ledger reopening validates the evidence protocol SHA and requires equality with the canonical protocol digest, ledger digest, and recomputed result. Pending, recorded, and blocked paths remain independently checked.
- Completion and opportunity rates use exact cross-products. The validator adapter uses common-denominator scaled integers, avoiding integer-floor ties.
- Transfer writes and fsyncs a temporary file, validates expected length/SHA before `os.replace`, then fsyncs the destination directory. Corrupt bytes never reach the final destination; transport loss remains `awaiting_recovery`.
- `_derive_source` now follows the pure validator's any-target `SOURCE_LIMITED` rule.

### Fix-round GREEN evidence

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py reference/tests/test_device_evidence_ledger.py reference/tests/test_m05b_attribution_v2.py reference/tests/test_validator_code_emission.py
84 passed in 2.32s

.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py reference/tests/test_device_evidence_ledger.py reference/tests/test_m05b_attribution_v2.py reference/tests/test_validator_code_emission.py reference/tests/test_m05b_attribution_v2_contract.py
91 passed in 2.26s

.venv/bin/python -m ruff format --check reference tools/check_m05b_attribution_v2.py
71 files already formatted

.venv/bin/python -m ruff check reference tools/check_m05b_attribution_v2.py
All checks passed!

MYPYPATH=reference/src .venv/bin/python -m mypy --strict tools/check_m05b_attribution_v2.py
Success: no issues found in 1 source file

./gradlew --no-daemon :shared:jvmTest --tests '*M05BAttributionV2JsonTest*' --tests '*M05BAttributionV2Test*' :androidApp:testDebugUnitTest --tests '*M05BAttributionV2RunnerTest*'
BUILD SUCCESSFUL in 18s; 48 actionable tasks

git diff --check
success
```

The Gradle run emitted only the existing Kotlin hierarchy-template warnings. No adb or physical-device tests were run.

### Fix-round mutation proof

Each mutation was run against its named focused test, exited `1` for the expected missing behavior, and was restored before continuing. The direct implementation hash after each restore was `9459e5f99e380b789af76e6076acfa8510149e4e`; the final formatter-only production hash is `2aaeedd26e4ccfc43acef12076d656990afe3f32`.

| Mutation | Focused command and named failure | Restored |
| --- | --- | --- |
| Remove exact 100 measured-terminal check | `-k exactly_one_hundred`; expected `ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT` absent | yes |
| Remove Arm 2 phase replay comparison | `-k arm_two_phase`; expected `ATTRIBUTION.ADMISSION_STATE_DIVERGENCE` absent | yes |
| Remove raw capture-span equality | `-k measured_span`; expected `ATTRIBUTION.END_TO_END_NOT_DISTINCT` absent | yes |
| Ignore raw Arm 2 inference failure | `-k any_arm_two_inference_failure`; expected `ATTRIBUTION.INFERENCE_FAILURE` absent | yes |
| Replace sanitized observation with raw observation | `-k structurally_sanitized`; serial-like raw string present | yes |
| Disable evidence protocol binding | `-k attribution_v2_ledger_reopens_recorded_host_evidence`; forged `0*64` protocol field was not rejected | yes |
| Floor fractional adapter rates | `-k fractional_rate_ordering`; exact ordering regression emitted `ATTRIBUTION.END_TO_END_NOT_DISTINCT` | yes |
| Rename before expected-byte verification | `-k 'verifies_expected_bytes or wrong'`; final rename occurred before validation / temp disappeared | yes |
| Skip reopened prepared-identity validation | `-k attribution_v2_ledger_reopens_recorded_host_evidence`; forged private identity reached blocker recomputation | yes |
| Skip reopened device projection validation | same focused ledger test; forged serial-like device identity did not raise | yes |

### Fix-round changed files

- `tools/check_m05b_attribution_v2.py`
- `reference/tests/test_check_m05b_attribution_v2.py`
- `reference/tests/test_device_evidence_ledger.py`
- `reference/tests/test_m05b_attribution_v2_contract.py`
- `contracts/tooling/m05b_attribution_observation.v2.schema.json`
- `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2Runner.kt`
- `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2RunnerTest.kt`
- `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2Json.kt`
- `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2JsonTest.kt`
- `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2Test.kt`

### Fix-round self-review

No unresolved Critical or Important concern remains from the supplied review. The changes preserve fixed argv, binary-safe transfer, fail-closed admission, sanitized non-participant evidence, independent host recomputation, and durable ledger binding. The existing pending physical ledger was not manufactured into recorded evidence. No participant data, paid credits, adb/device execution, push, PR, merge, tag, branch/worktree deletion, or Task 6 work was performed.

## Final regression-first correction: chronology, measured span, and device projection

Base: `9634eab4d9625e477f3abe3d904618dff6538905`. This was one bounded final correction round; Task 6 remains unstarted.

### RED evidence

The new Python regressions were run before production edits:

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py -k 'terminal_completion_before_arm_start or excludes_warmup_capture_window or authorized_shaped_device_identity'
exit 1: 3 failures
- pre-arm completion was still recorded;
- warm-up-only capture shift was blocked because the host used the all-callback span;
- an authorized-shaped serial/private identity and adversarial device strings were serialized.
```

The producer regression was also run before the helper existed:

```text
./gradlew --no-daemon :androidApp:testDebugUnitTest --tests '*M05BAttributionV2RunnerTest.endToEndRunDeclaresMeasuredOnlyCaptureSpan'
exit 1: unresolved reference 'attributionV2MeasuredSpanNs'
```

### Correction

- Arm 2 chronology now requires every raw terminal completion timestamp to be present, at or after `arm_started_ns`, and monotonic nondecreasing in event-array order. Equal timestamps remain valid. Invalid chronology derives `ATTRIBUTION.ADMISSION_STATE_DIVERGENCE`.
- Host measured span now uses only callbacks independently replayed as measured. Synthetic valid fixtures and fractional-rate fixtures were updated to declare measured-only spans.
- The Android producer now computes `measuredSpanNs` from the first and last measured-phase callback captures through the shared `attributionV2MeasuredSpanNs` helper.
- Authorized device observations are projected to fixed canonical non-sensitive values (`samsung/SM-S938B/attribution-v2/verified`, fixed release/build), never copied verbatim. Authorization and ledger recomputation remain valid while serial-like, private-path, participant, media, and landmark strings are excluded.

### GREEN evidence

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py -k 'terminal_completion_before_arm_start or excludes_warmup_capture_window or authorized_shaped_device_identity'
3 passed

.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py reference/tests/test_device_evidence_ledger.py reference/tests/test_m05b_attribution_v2.py reference/tests/test_validator_code_emission.py reference/tests/test_m05b_attribution_v2_contract.py
94 passed in 2.52s

.venv/bin/python -m ruff format --check reference tools/check_m05b_attribution_v2.py
71 files already formatted

.venv/bin/python -m ruff check reference tools/check_m05b_attribution_v2.py
All checks passed!

MYPYPATH=reference/src .venv/bin/python -m mypy --strict tools/check_m05b_attribution_v2.py
Success: no issues found in 1 source file

./gradlew --no-daemon :shared:jvmTest --tests '*M05BAttributionV2JsonTest*' --tests '*M05BAttributionV2Test*' :androidApp:testDebugUnitTest --tests '*M05BAttributionV2RunnerTest*'
BUILD SUCCESSFUL in 6s; 48 actionable tasks

git diff --check
success
```

Gradle emitted only the pre-existing Kotlin hierarchy-template warnings. No adb or physical-device tests were run.

### Targeted mutation proof

Each mutation was applied after GREEN, killed by its named regression with exit `1`, and restored byte-for-byte. The restored host checker hash was `01c8913f555b294cf05b41911813a8939a82e9f9`; the restored producer hash was `613b54dbbf7246033e128b063d4b4db5f3a263c8`.

| Mutation | Named command/failure | Restore |
| --- | --- | --- |
| Remove Arm 2 chronology enforcement | `-k terminal_completion_before_arm_start`; invalid pre-arm completion was admitted | yes |
| Restore host all-callback span | `-k excludes_warmup_capture_window`; valid warm-up-only shift was rejected | yes |
| Restore raw authorized-device copying | `-k authorized_shaped_device_identity`; serial/private string appeared in serialized evidence | yes |

### Changed files

- `tools/check_m05b_attribution_v2.py`
- `reference/tests/test_check_m05b_attribution_v2.py`
- `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2Runner.kt`
- `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2RunnerTest.kt`
- `.superpowers/sdd/2026-09-05-m0-5b-task-6-attribution-v2/task-5-report.md`

### Self-review

No Critical or Important concern remains in the supplied rereview scope. Previously closed transfer ordering, protocol binding, exact rational rates, source any-target semantics, fixed argv, and device-summary non-authority behavior were preserved. No unrelated files were modified; no Task 6, device/ADB execution, push, PR, merge, tag, branch/worktree deletion, participant data, or paid credits were used.

## Final correction: protocol-pinned device provenance

Base: `554d42a77fb34661062d1893a3048b16e2ef1feb`. This was one bounded regression-first correction round; Task 6 remains unstarted.

### RED evidence before production edits

The exact-pin host regressions were added and run before the implementation change:

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py -k 'exact_protocol_device_pin or shape_authorized_other_product or preserves_exact_protocol_device_pin or display_build'
exit 1: 4 failures
- the protocol had no authorized_device pin (KeyError: authorized_device) for the mismatch and exact-pin cases;
- a shape-authorized other product was recorded instead of blocked;
- the Android gate still referenced Build.ID instead of Build.DISPLAY.

.venv/bin/python -m pytest -q reference/tests/test_m05b_attribution_v2_contract.py -k 'canonical_protocol_pins or protocol_device_pin_matches'
exit 1: 2 failures
- both protocol pin tests failed with KeyError: authorized_device.

.venv/bin/python -m pytest -q reference/tests/test_device_evidence_ledger.py -k attribution_v2_ledger_reopens_recorded_host_evidence
exit 1: the null top-level ledger device binding was accepted (DID NOT RAISE).
```

### Correction

- Added the closed, deterministic seven-field authorized-device pin to the canonical protocol:
  `physical_device; samsung/SM-S938B/pa3q/pa3qxins; samsung; SM-S938B; API 36; Android 16; BP4A.251205.006.S938BXXSBCZG3`.
- Updated the v2 schema, canonical Kotlin protocol digest, pending ledger digest, and parity assertions. The canonical protocol digest is `b37ba96527e958e993d9ccc73dde9579aa28af22c19f37590675a04a5a8c7822`.
- The Android gate now reads `Build.DISPLAY` for `osBuild`, retaining the exact reviewed build suffix.
- Host admission now reads the pin from the canonical protocol and requires exact raw-object equality in addition to shape authorization. Mismatches use existing `ATTRIBUTION.CANDIDATE_IDENTITY` blocking behavior.
- Sanitization now publishes the exact reviewed pin only when that exact raw pin was observed; all mismatch/garbage paths publish the existing invalid sentinel. No plausible device value is fabricated.
- Reopened evidence accepts only the exact pin or exact sentinel at both top-level and nested device locations, requires ledger/evidence device binding, and recomputes the device projection for fixed-point/idempotent verification.
- Added bounded regressions for one-character build mismatch, authorized-shaped alternate product, exact-pin preservation, pin/sentinel ledger fixed points, protocol/study-readiness parity, Kotlin `Build.DISPLAY`, and null durable device binding.

### GREEN evidence

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py reference/tests/test_device_evidence_ledger.py reference/tests/test_m05b_attribution_v2.py reference/tests/test_validator_code_emission.py reference/tests/test_m05b_attribution_v2_contract.py
100 passed in 3.18s

.venv/bin/python -m ruff format --check reference tools/check_m05b_attribution_v2.py
71 files already formatted

.venv/bin/python -m ruff check reference tools/check_m05b_attribution_v2.py
All checks passed!

MYPYPATH=reference/src .venv/bin/python -m mypy --strict tools/check_m05b_attribution_v2.py
Success: no issues found in 1 source file

./gradlew --no-daemon :shared:jvmTest --tests '*M05BAttributionV2ProtocolParityTest*' --tests '*M05BAttributionV2JsonTest*' --tests '*M05BAttributionV2Test*' :androidApp:testDebugUnitTest --tests '*M05BAttributionV2RunnerTest*' :androidApp:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 13s; 56 actionable tasks

git diff --check
success
```

The Gradle invocation produced only the existing Kotlin hierarchy-template and compiler warnings. It did not run adb or physical-device tests.

### Targeted mutation proof

After GREEN, the exact-pin comparison was removed from `_derive_blockers` while retaining shape authorization. The named regression was then run:

```text
.venv/bin/python -m pytest -q reference/tests/test_check_m05b_attribution_v2.py -k shape_authorized_other_product
exit 1: expected a blocked candidate-identity result, but the shape-authorized alternate product was recorded.
```

The production comparison was restored and the restored checker hash was `0f419a04581f1b9e1c25c3cbdcbbef7c34bf524e`.

### Changed files

- `tools/check_m05b_attribution_v2.py`
- `reference/tests/test_check_m05b_attribution_v2.py`
- `reference/tests/test_device_evidence_ledger.py`
- `reference/tests/test_m05b_attribution_v2_contract.py`
- `contracts/tooling/m05b_attribution_protocol.v2.schema.json`
- `docs/protocols/m05b-attribution-v2.protocol.json`
- `docs/evidence/m05b-attribution-v2/s25-attribution-v2-ledger.json`
- `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/M05BAttributionV2DeviceGateTest.kt`
- `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/study/M05BAttributionV2ProtocolParityTest.kt`
- `.superpowers/sdd/2026-09-05-m0-5b-task-6-attribution-v2/task-5-report.md`

### Self-review

No Critical or Important concern remains from the supplied provenance review. Chronology, measured-only span, verify-before-rename, protocol binding, exact rational rates, source any-target semantics, fixed argv, and device-declared summary non-authority remain preserved. No unrelated cleanup, participant data, paid credits, adb/device execution, push, PR, merge, tag, branch/worktree deletion, or Task 6 work was performed.
