# M0.5B Task 5b Physical Purge Rehearsal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rehearse the existing Task 5a purge transaction on one complete, short, video-enabled synthetic S25 session and admit independently verified device/host evidence without making any participant-data or privacy-policy claim.

**Architecture:** A dedicated Android instrumentation gate creates one reserved-namespace live-conformance session with synchronized video, adds owned synthetic residues to the two non-export purge roots, and invokes the existing `AndroidSyntheticPurge` transaction. It emits one sanitized typed observation. A separate host checker treats the device as untrusted: it validates the transcript, independently enumerates all registered purge roots through `adb exec-out run-as`, pulls and validates the durable tombstone journal, verifies the permanent re-admission fence, and alone determines success. The Make target builds and installs one exact candidate, proves installed APK identity, runs the device gate, and delegates the verdict to the checker; it never edits evidence ledgers.

**Tech Stack:** Kotlin Multiplatform, Android instrumentation/JUnit, CameraX/MediaPipe live composition, MediaCodec/MediaMuxer synchronized video, Python 3.14/jsonschema, GNU Make, adb `exec-out run-as`, pytest, Ruff, strict mypy.

**Spec:** `docs/superpowers/specs/2026-08-24-m0-5b-instrument-prerequisites-design.md` section 8; parent plan `docs/superpowers/plans/2026-08-27-m0-5b-instrument-prerequisites.md` Task 5b.

## Global Constraints

- Operate only on a reserved synthetic session ID matching `^00000000-0000-4000-8000-[0-9a-f]{12}$`; never capture or delete participant data.
- A pose-only session is invalid: the export must contain non-empty `video.mp4`, `manifest.json`, `frames.ndjson`, and `synchronization-evidence.json` before purge begins.
- Resolve and delete only through `PurgeFilesystem.devicePurgeLocations`; never accept an arbitrary path, root, glob, or recursive-delete target.
- Persist and fsync `INTENT_RECORDED` before the first delete, independently scan after deletion, and persist `PURGE_COMPLETE` only when every owned post-scan is empty.
- The first physical rehearsal must be non-vacuous in all three registered purge roots: export, staging, and transfer staging.
- The tombstone root is outside the purge set and remains durable; the purged session ID is permanently refused by staging, export, transfer, and final admission.
- The host checker, not the instrumentation process or Make recipe, owns the final verdict.
- Use `adb exec-out` for exact bytes. Do not use `adb shell` to pull the tombstone or any binary artifact.
- The device target requires exactly one authorized physical Samsung Galaxy S25 Ultra and rejects emulators.
- The target prints a candidate record for a separate recording step and never writes the pending/recorded ledger.
- Every load-bearing gate conjunct ships with a mutation that makes its intended focused test fail.
- Success is an exit code, never a grep of a success-looking output line.
- `make fetch-model-asset` and checkout-local `apps/diagnostic-harness/local.properties` are required before the Android delivery build.
- Checkpoint commits and pushes are allowed; do not open a PR, merge, tag, delete branches, or remove worktrees.

---

### Task 1: Typed physical purge evidence and host validation contract

**Files:**
- Create: `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/purge/PhysicalPurgeEvidence.kt`
- Create: `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/purge/PhysicalPurgeEvidenceTest.kt`
- Create: `tools/check_m05b_physical_purge.py`
- Create: `reference/tests/test_m05b_physical_purge_check.py`

**Interfaces:**
- Consumes: `PurgeRecord.render()`, `PurgeState`, `StorageLocationId`, reserved synthetic IDs, existing synchronized-video session accounting.
- Produces: `PhysicalPurgeObservation.render(): String`; host `parse_device_record`, `verify_device_record`, `verify_independent_post_scan`, `verify_tombstone_journal`, and `verify_instrumented_run` functions; one JSON host result containing no paths or stable device serial.

- [x] **Step 1: Write RED renderer tests**

  Add tests constructing a complete observation and asserting exactly these closed fields: schema version, record kind, sanitized/non-participant flags, device class/model/API, producer SHA, app/test APK SHA values supplied by the host, video-enabled precondition, four pre-purge artifact-presence booleans, closed purge state/sequence/location counts, and tombstone journal digest/length. Assert rendering refuses a non-reserved session ID, a non-complete purge, any zero pre-inventory root, or `video_present=false`. Assert output contains no absolute path, device serial, participant identifier, landmark coordinate, wall-clock timestamp, or operator identity.

- [x] **Step 2: Run the focused common test and verify RED**

  Run `cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest --tests '*PhysicalPurgeEvidenceTest*'`.

  Expected: compilation fails because `PhysicalPurgeObservation` does not exist.

- [x] **Step 3: Implement the minimum renderer**

  Implement an immutable observation whose constructor derives `admissible` from the closed inputs. `render()` must emit one compact JSON object, sort blockers, expose only bounded counts/digests and the reserved synthetic ID, and never accept a caller-declared success boolean.

- [x] **Step 4: Write RED host-checker tests**

  Cover: non-zero instrumentation exit; missing/multiple status records; malformed JSON; wrong device/model/API/provenance; pose-only or missing video; incomplete/non-vacuous failure; unknown location; absolute path leakage; host post-scan residue; missing/invalid tombstone; tombstone hash mismatch; non-terminal journal; app/test APK mismatch; and a fully valid fixture. Patch subprocess calls and require exact argv arrays, never shell strings.

- [x] **Step 5: Run the host-checker tests and verify RED**

  Run `./.venv/bin/pytest -q reference/tests/test_m05b_physical_purge_check.py`.

  Expected: import/file failure because the checker does not exist.

- [x] **Step 6: Implement the minimum host checker**

  Parse exactly one `M05B_PHYSICAL_PURGE_RECORD=<json>` payload from the transcript. Validate device output before any adb read. Independently run fixed `adb -s SERIAL exec-out run-as PACKAGE ...` argv commands, accept only the three registry child roots `exports`, `staging`, and `transfer-staging`, and reject owned names matching the session ID, `<id>.<n>.staging`, or `<id>.transfer.bundle`. Pull `tombstones/<id>.purge-tombstone.json` byte-exactly, validate it with `validate_tombstone_journal`, require the last record to be `PURGE_COMPLETE`, recompute SHA/length, and compare them with the device observation. Emit one sanitized JSON result to stdout; exit non-zero on every mismatch.

- [x] **Step 7: Prove load-bearing mutations**

  Mutate the session-relative owned-name matcher to ignore transfer bundles and require the residue test to fail. Mutate the tombstone terminal-state check to accept `PURGE_INCOMPLETE` and require the terminal-journal test to fail. Restore byte-exactly after each mutation.

- [x] **Step 8: Run focused GREEN checks**

  Run the two focused test commands above plus `./.venv/bin/ruff check tools/check_m05b_physical_purge.py reference/tests/test_m05b_physical_purge_check.py` and `./.venv/bin/mypy --strict tools/check_m05b_physical_purge.py`.

- [x] **Step 9: Commit the contract/checker increment**

  Commit message: `feat: add physical purge evidence checker`.

---

### Task 2: Complete video-enabled Android purge rehearsal

**Files:**
- Create: `apps/diagnostic-harness/androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/purge/PhysicalPurgeDeviceGateTest.kt`
- Create: `apps/diagnostic-harness/androidApp/src/test/kotlin/com/calisthenics/diagnostic/purge/PhysicalPurgeDeviceGateContractTest.kt`
- Modify only if a test seam is required: `apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/composition/LiveCameraComposition.kt`

**Interfaces:**
- Consumes: `LiveCameraComposition.fromAndroid(..., recordVideo = true)`, `AndroidStorageRoots.from(context)`, `FilePurgeFilesystem`, `AndroidSyntheticPurge`, `PurgeSessionId.parseOrNull`, `PhysicalPurgeObservation.render()`.
- Produces: one instrumentation class that creates, validates, and purges a complete synthetic session; exactly one status payload tagged `M05B_PHYSICAL_PURGE_RECORD`.

- [x] **Step 1: Write RED structure tests**

  Assert the device gate requests video, waits for encoded frames, finalizes the session, checks all four committed artifacts before purge, creates one owned staging directory named `<id>.1.staging`, creates one owned transfer directory named `<id>.transfer.bundle`, invokes `AndroidSyntheticPurge(FilePurgeFilesystem(roots), BuildConfig.PROBE_GIT_SHA)`, requires `PURGE_COMPLETE`, verifies all three on-device post-scans are empty, verifies the tombstone exists, and calls `sendStatus` exactly once.

- [x] **Step 2: Run RED Android unit/compilation checks**

  Run `cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:testDebugUnitTest --tests '*PhysicalPurgeDeviceGateContractTest*' :androidApp:compileDebugAndroidTestKotlin`.

  Expected: structure test fails because the gate is absent.

- [x] **Step 3: Implement the short live session setup**

  Use the same authorized camera observation, bounded capture loop, live-conformance manifest derivation, and teardown sequence as `SynchronizedVideoDeviceGateTest`. Require at least one encoded/demuxed video sample and non-empty committed `manifest.json`, `video.mp4`, `frames.ndjson`, and `synchronization-evidence.json`. Do not duplicate synchronization validation; consume the already-admitted Task 4 composition result.

- [x] **Step 4: Add non-vacuous test-owned residues**

  After publication and before purge, resolve `StorageRoots` once. Create `<stagingRoot>/<id>.1.staging/owned.bin` and `<transferStagingRoot>/<id>.transfer.bundle/owned.bin` using literal synthetic bytes. Never create outside those registry-derived roots and never take a path argument from instrumentation input.

- [x] **Step 5: Execute and verify the existing purge transaction**

  Parse the generated session ID into `PurgeSessionId`, generate a lowercase UUID operation ID, invoke `AndroidSyntheticPurge`, and require `PURGE_COMPLETE`. Independently call `FilePurgeFilesystem.postScan` for all `devicePurgeLocations`, require `ownedRemaining == 0`, require the export/staging/transfer owned entries absent, and require the tombstone journal file present and non-empty. Attempt a fresh staging admission under the purged session ID and require `PURGE.SESSION_TOMBSTONED`.

- [x] **Step 6: Emit one sanitized observation**

  Hash/read the tombstone only after the transaction is terminal. Populate `PhysicalPurgeObservation` from measured preconditions and the returned purge record. Put it into one `Bundle` and call `InstrumentationRegistry.sendStatus` once. On any throwable, emit a typed blocking observation rather than a success-looking partial record; still destroy camera lifecycle resources.

- [x] **Step 7: Run focused GREEN checks**

  Run the Step 2 command and confirm the Android test APK assembles.

- [x] **Step 8: Prove device-gate mutations**

  Remove `recordVideo = true` and require the structure test to fail. Remove creation of each non-export residue in turn and require the matching non-vacuity assertion to fail. Move purge before the complete-session checks and require the ordering assertion to fail. Remove the tombstone-backed re-admission check and require the fence assertion to fail.

- [x] **Step 9: Commit the device increment**

  Commit message: `feat: rehearse complete synthetic purge on android`.

---

### Task 3: Exact-candidate Make gate and evidence ledger

**Files:**
- Modify: `Makefile`
- Create: `reference/tests/test_m05b_physical_purge_build_contract.py`
- Create: `docs/evidence/m05b-purge/s25-ultra-physical-purge-ledger.json`
- Modify: `reference/tests/test_device_evidence_ledger.py`
- Modify: `docs/superpowers/plans/2026-08-27-m0-5b-instrument-prerequisites.md`

**Interfaces:**
- Consumes: `PhysicalPurgeDeviceGateTest`, `tools/check_m05b_physical_purge.py`, exact app/test APK hashes, one S25 serial, ignored human recording step.
- Produces: `make verify-harness-s25-m05b-physical-purge`; pending/recorded ledger whose recorded branch opens and validates its referenced evidence file.

- [x] **Step 1: Write RED Make/build-contract tests**

  Require the new phony target to: use `set -eu`; count exactly one `adb devices` entry whose second column is `device`; reject emulator serials; authorize Samsung `SM-S938*`; require a clean source tree; call `fetch-model-asset`; assemble both APKs; install both; pull installed APKs with `exec-out cat`; compare both installed hashes against local candidates; run only `PhysicalPurgeDeviceGateTest`; capture the real instrumentation exit status; invoke the host checker with serial/package/transcript/app SHA/test SHA; and contain no ledger-writing command, success grep, broad `rm`, wildcard, or `shell=True` equivalent.

- [x] **Step 2: Run the focused build-contract test and verify RED**

  Run `./.venv/bin/pytest -q reference/tests/test_m05b_physical_purge_build_contract.py`.

  Expected: target-not-found failure.

- [x] **Step 3: Implement the exact-candidate target**

  Follow the synchronized-video gate's candidate-identity preamble. Create temporary host files with `mktemp`; trap cleanup of host temporary files only. Run instrumentation, print its transcript, and pass the numeric exit status to the checker. The target must never remove device data itself; only the typed device purge transaction may delete owned synthetic entries.

- [x] **Step 4: Add the pending ledger and validator**

  Initial ledger fields: `status: pending`, `reason: physical_purge_rehearsal_not_recorded`, `required_device: samsung_galaxy_s25_ultra`, and `evidence_file: null`. Extend `test_device_evidence_ledger.py` so `recorded` requires a repo-relative JSON file under `docs/evidence/m05b-purge/`, opens it, recomputes its SHA, validates the host-checker schema and non-blocking result, and rejects missing, unrelated, absolute, traversing, or digest-mismatched references.

- [x] **Step 5: Prove Make/ledger mutations**

  Remove the installed test-APK hash comparison and require the build-contract test to fail. Replace the checker exit with a grep and require failure. Point a recorded ledger at an unrelated JSON file and require the ledger test to fail. Restore byte-exactly.

- [x] **Step 6: Run focused GREEN checks and commit**

  Run the build-contract, ledger, and purge checker tests; Ruff and strict mypy on touched Python; Android unit and test-APK assembly. Commit message: `feat: add physical purge device gate`.

---

### Task 4: Physical S25 execution and durable evidence admission

**Files:**
- Create after a passing run: `docs/evidence/m05b-purge/s25-ultra-SM-S938B-api36-physical-purge.json`
- Modify after a passing run: `docs/evidence/m05b-purge/s25-ultra-physical-purge-ledger.json`
- Modify: `docs/superpowers/plans/2026-08-27-m0-5b-instrument-prerequisites.md`
- Modify ignored ledger: `.superpowers/sdd/2026-08-27-m0-5b-instrument-prerequisites/progress.md`

**Interfaces:**
- Consumes: clean exact Task 5b candidate and one attached authorized physical S25.
- Produces: recorded non-participant evidence bound to git SHA and exact APK hashes, a validated terminal tombstone, and completed Task 5b checkboxes.

- [x] **Step 1: Verify the physical preconditions**

  Confirm one authorized physical Samsung Galaxy S25 Ultra, Android API 36, clean worktree, local HEAD equals remote branch, `apps/diagnostic-harness/local.properties` exists, and the model asset is present only through the fetch task.

- [x] **Step 2: Run the physical gate without interpreting text as success**

  Run `make verify-harness-s25-m05b-physical-purge` and accept only exit code 0 from the host checker. Preserve the printed sanitized host result. Do not rerun on the same session ID; every attempt uses a fresh reserved ID.

- [x] **Step 3: Independently inspect the result before recording**

  Confirm the host result reports a video-enabled precondition, non-vacuous inventory for every purge root, zero owned residue in all three roots, terminal `PURGE_COMPLETE`, a valid exact-byte tombstone chain, and a successful tombstone-backed re-admission refusal. Confirm no participant capture or participant identifier exists.

- [x] **Step 4: Record immutable evidence**

  Add the exact sanitized host result as the evidence file. Change the ledger to `recorded`, point it to that file, and include its SHA-256. Run the ledger validator; do not edit the evidence in place after admission—any correction requires a new evidence file/version.

- [x] **Step 5: Run the complete verification boundary**

  Run `make verify`, `make verify-harness-jvm`, `make verify-harness-ios`, Android unit tests and androidTest APK assembly, repository guard, focused host-checker/build-contract/ledger tests, and inspect `git diff --check`, `git status`, and the exact branch diff against `origin/main`.

- [x] **Step 6: Commit and push the evidence checkpoint**

  Commit message: `test: record physical synthetic purge rehearsal`. Push the branch, verify local and remote HEAD match, and keep the worktree. Do not open a PR, merge, tag, or delete anything.

---

## Self-review result

- Spec coverage: Task 5b's complete video session, non-vacuous three-root purge, intent-first existing transaction, host `run-as` post-scan, typed evidence, Make gate, build-contract guard, permanent fence, pending/recorded ledger, and physical S25 execution are each owned by one task above.
- Authority boundary: the output remains proposal-only synthetic evidence and makes no claim about participant withdrawal, retention, backup propagation, or privacy readiness.
- Type consistency: the device produces `PhysicalPurgeObservation`; the host checker consumes exactly that observation and the existing tombstone journal; the Make target delegates the final verdict to the checker; the ledger consumes the checker's sanitized result.
- Placeholder scan: no deferred implementation or unspecified error-handling step remains.
