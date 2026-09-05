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
