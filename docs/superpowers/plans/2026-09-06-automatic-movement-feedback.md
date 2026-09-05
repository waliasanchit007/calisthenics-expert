# Automatic movement feedback implementation plan

**Goal:** An ordinary operator-started self-test leaves locally retrievable evidence explaining candidate-cycle decisions without manual exports.

**Architecture:** Extend the existing viewer observer, not the counter or formal-study pipeline. Pair lossy JPEGs of the actual 256x256 inference inputs and normalized landmarks with exact decision inputs using monotonic capture timestamps. A bounded app-private asynchronous bundle survives ADB disconnect; a read-only host collector produces deterministic reports, without scheduled model calls.

**Spec:** ADR-0006 local feedback amendment below. Owner requested implementation and autonomous routine decisions; no further routine approval round or routine independent implementation review.

## Scope and limits

- Debug builds only, operator starts camera after existing fresh self-test attestation; visible collection notice before start and status/disable while active. No agent camera start or background camera.
- Three minutes / 64 MiB per session; 256 MiB total retained app feedback. Stop at capacity, do not silently delete recordings. No audio, uploads, Git media, participant-study exports, or threshold changes.
- Bounded writer; explicit failures/drops and terminal metadata. Exact input RGB SHA and lossy JPEG SHA are separate. JPEG is visual context, not a pixel oracle.
- Trace records include capture and decision monotonic timestamps, precise arm inputs and controls. Preserve every fresh analyzer sample and all state transitions; duplicate UI refreshes may be suppressed.
- Session UUID pairs files. Frame path is `frames/<capture_ns>.jpg`; write image before its complete index row. Host accepts only allowlisted paths, complete JSONL rows and matching image hashes. Partial/missing evidence is never complete success.
- Retain separate raw model landmarks, derived inputs and rule decisions; these are experimental observations, not correct-technique labels.

## Task 1: App collection (controller)

Files: `preview/ViewerFeedbackBundle.kt`, `ViewerDiagnosticLog.kt`, `MovementViewerActivity.kt`, associated Android tests, shared Compose viewer notice, ADR-0006/AGENTS.

- [x] RED: real temporary bundle stores matching frame/landmarks and closes with terminal record; queue/duration/byte bounds and failure isolation; precise trace values and fresh samples bypass duplicate rate limiting.
- [x] GREEN: implement worker-owned app-private bundle, wire existing raw observations and log callback, use the same decision timestamp as coordinator calls, close logger before bundle so trailing events drain.
- [x] Verify targeted Android tests, then Android/shared suites and delivery assembly. Keep all screenshots allowed.

## Task 2: Host retrieval and report (independent workhorse)

Files: `tools/collect_movement_feedback.py`, `reference/tests/test_movement_feedback.py`.

- [x] RED: duplicate/partial rows, gaps, first failure vs later idle tracking, synthetic exclusion, multiple attempts and counted cycles, unsafe paths and invalid hashes.
- [x] GREEN: read-only ADB collector and offline report, private output outside Git; bounded watch process and retry on disconnect. No model polling.
- [x] Verify tests/static checks, then analyze the already captured numeric session; distinguish observed rejection from physical cause.

## Task 3: Integration and handoff

- [x] Remove obsolete diagnostic heartbeat (only local automation found); retain already collected logs.
- [x] Validate a synthetic bundle on device without human capture, install current APK, arm host retrieval for operator-started camera use.
- [x] Record exact build/test results, local data location, limitations and next action in ignored ledger; no push/PR/merge/tag.

## Developer operation

Run `python tools/collect_movement_feedback.py --adb <adb-executable> --output <private-directory-outside-git> --watch --seconds 3600 --poll 2`.
The collector discovers one authorized physical S25, retries read failures, and writes per-session
`report.json` and `report.md` without model calls. `--analyze-local <trace-or-bundle-path>` analyzes
existing data without a device. Recording remains operator-started; no command starts the camera.
The bounded watcher is a local process, not a scheduled AI task. If its hour ends, retained phone
bundles can be retrieved by the next invocation. A changed Wi-Fi debugging endpoint requires fresh
device discovery on invocation. Individual session paths and process IDs belong only in the private ledger.

Acceptance: 255 Android tests, 437 shared JVM tests, 1040 Python tests; Ruff and strict mypy passed.
Both Compose iOS frameworks built. Synthetic S25 instrumentation and host image/hash association
passed. Real-movement visual validation and any targeted counter correction are the next learning
step, not claimed complete by these transport tests. Existing counting rules are unchanged.
