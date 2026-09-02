# M0.5B Task 8 Sustained Controller Implementation Plan

> Execute regression-first. This plan implements Task 8 Steps 1-4 from
> `2026-08-27-m0-5b-instrument-prerequisites.md`; physical Steps 5-7 remain gated on the exact-final-build
> Task 6 attribution run and Task 7 envelope/order freeze.

## Fixed composition decision

The Task 7 admission boundary and the live session must share one `DurableSessionWriter`. The live
composition supplies the writer owned by its `SessionExporter` to a decision-returning start gate,
and that gate owns the only callback capable of invoking the real camera source start. A second
writer/exporter and the existing fire-and-forget `beforeSourceStart` hook are prohibited.

Admission append, flush, and fsync precede telemetry startup and camera capture. This makes the
admission artifact the first durable run write and pins the session clock used by the 1,500-second
controller to admitted capture startup rather than construction/setup time.

## Task 1: Structural admission/start boundary

- [x] Add RED composition tests for one staging attempt, referential writer identity, exact admission
  bytes in the sealed attempt, admission fsync before telemetry and source start, blocked admission
  never starting capture, a gate that does not invoke its thunk being unable to bypass admission,
  and source-start failure producing a non-completed attempt.
- [x] Expose the exporter-owned writer through a narrow admission-host interface.
- [x] Replace `beforeSourceStart` with a typed `LiveStartAdmission` gate whose decision is admitted or
  blocked with exact codes. Move the sole source-start call and telemetry startup into its thunk.
- [x] Preserve the existing abandon/start race fence and all non-M0.5B default behavior.
- [x] Mutation-check a direct source-start bypass and a different-writer substitution.

## Task 2: Sustained record and controller

- [ ] Add RED common tests for the exact 1,500-second stop request, one-second hard overshoot bound,
  video disabled, camera request 30 FPS, frozen cadence/order identity, immutable attempt-chain
  derivation, scalar battery temperatures with no defaults, stage equations, fixed latency histogram,
  and telemetry start/gap/end coverage.
- [ ] Add a typed sustained observation and renderer. All verdicts and blocking codes are derived;
  callers cannot supply an admitted boolean.
- [ ] Add a bounded controller that consumes exact envelope/order bytes and history, performs cooldown
  and observed-start admission, starts the real composition through the structural gate, requests
  stop from the admitted session clock at exactly 1,500,000,000,000 ns, and records the actual request
  time without normalizing overshoot.
- [ ] Build stage, latency, telemetry, battery, source, and process results from durable session
  artifacts plus completion state; never retain per-frame sustained telemetry in memory.
- [ ] Mutation-check the target duration, overshoot, video/camera constants, artifact identities,
  each stage equation, telemetry coverage, and a caller-forged blocker list.

## Task 3: Host/device gate software

- [ ] Add an instrumentation gate that consumes prepared exact candidate, envelope, order, and attempt
  history without rebuilding or installing. It emits a sanitized non-participant reference record;
  durable session artifacts remain authoritative.
- [ ] Add a host checker that transfers exact artifacts with binary-safe `adb exec-out`, rehashes every
  reference, validates the admission and sustained record, and distinguishes admitted, blocking, and
  malformed outcomes by exit status.
- [ ] Add `verify-harness-s25-m05b-sustained` and pending ledgers for 10/15/30 FPS. Require one
  authorized unplugged S25 over Wi-Fi ADB and exact installed candidate identity. The target never
  builds, installs, clears app state, retries a blocked observation, or writes a ledger.
- [ ] Verify focused tests, `make verify`, JVM/Android/iOS gates, and exact diff scope.

## Task 4: Error-code stabilization

- [ ] Enumerate every dotted code emitted by admitted validators and every guarded draft set.
- [ ] Add emission-side coverage so stable emitted codes must be registered and draft codes must be
  absent. Use behavioral or negative-fixture oracles for every stable code.
- [ ] Release the final sustained code set under registry 1.4.0 using the schema release process,
  changelog, compatibility, and mutation evidence.
- [ ] Replace Task 8's temporary disjointness assertion with the complete subset assertion
  `draft_task8_codes <= released_codes`.

## Exact-final-build boundary

After Tasks 1-4 are committed and all software gates pass: prepare exactly one final candidate, run
Task 6 attribution, freeze Task 7 envelope/order, then run 10/15/30 sustained observations in frozen
order without rebuilding. Physical evidence and Task 9 are separate later checkpoints. No participant
capture, PR, merge, tag, branch deletion, or worktree cleanup is authorized by this plan.
