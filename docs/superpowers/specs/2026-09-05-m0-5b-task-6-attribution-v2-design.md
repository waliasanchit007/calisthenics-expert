# M0.5B Task 6 Attribution Protocol v2 Design

**Status:** Accepted on 2026-09-05. The design direction was independently reviewed through
`claude-work` using Claude Opus 5, corrected through self-review, and accepted by the release owner.

**Parent design:**
`docs/superpowers/specs/2026-08-24-m0-5b-instrument-prerequisites-design.md`

**Superseded execution plan:**
`docs/superpowers/plans/2026-08-29-m0-5b-short-attribution-gate.md` remains authoritative for the
immutable v1 observation only. It must not be edited to make that observation pass.

## 1. Decision

Task 6 will use a new additive attribution protocol rather than retrying or weakening the blocked
v1 protocol.

Protocol v2 separates three questions that v1 combined:

1. can the physical camera deliver timestamps that make each configured cadence observable;
2. can the selected preprocessing and MediaPipe backend sustain each configured cadence; and
3. when both prerequisites hold, does the complete live pipeline admit distinct 10/15/30 rates?

The protocol reports source, cadence, and backend limits independently. A selector-only success is
never sufficient for Task 7 or the sustained sequence.

## 2. Immutable v1 evidence

The v1 record at
`docs/evidence/m05b-attribution/s25-ultra-SM-S938B-api36-short-attribution-blocked.json` is final.
Its SHA-256 is `a5c9fb4b31d7b75ef24305f7df3c7eeaf3adf11fbd2e83c6876f957c73d1ebdb`.

On the exact S25 Ultra CPU candidate at Git commit
`b03bb71dd91914940b08bdd141c580bd1db6a83f`, the admitted/delivered counts were:

| Configured inference FPS | Admitted | Delivered | Ratio |
|---:|---:|---:|---:|
| 10 | 2 | 8 | 0.250000 |
| 15 | 3 | 13 | 0.230769... |
| 30 | 3 | 10 | 0.300000 |

The approved strict increase did not hold. The record therefore correctly contains
`TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT` and remains blocking.

V1 proves that the exact end-to-end candidate did not satisfy its declared attribution gate. It
does not prove which of source delivery, cadence selection, or backend throughput caused the
result. Each v1 run stopped after a capture span of at least one second and completed only two or
three inferences, so it also does not provide a stable capacity estimate.

No v2 result may change, replace, overwrite, or retroactively reinterpret the v1 verdict.

## 3. Root cause of the v1 ambiguity

`LiveFrameAdmission.reserve` checks the single in-flight inference slot before the cadence interval:

```text
stopping
  -> in-flight or reserved slot
  -> configured cadence interval
  -> submission
```

This ordering is correct for production ownership and must not be changed merely to improve an
experiment. It means a delivered callback that is both too early for the cadence and blocked by an
in-flight inference is recorded only as backpressure. Consequently:

- recorded backpressure is an upper bound on backend-only rejection;
- recorded cadence selection is a lower bound on cadence-only rejection; and
- admitted/delivered ratios cannot identify the limiting subsystem by themselves.

The low v1 delivery counts also leave open a source limitation. CameraX requested 30 FPS, but the
short runs observed only 8, 13, and 10 delivered callbacks. A requested source rate is not evidence
of an achieved source rate.

## 4. Scope and non-goals

### 4.1 In scope

- one authorized Samsung Galaxy S25 Ultra on Android 16/API 36;
- the existing CameraX source, preprocessing path, MediaPipe model, CPU delegate, and single-flight
  inference semantics;
- source-only, backend-capacity, selector-isolation, and conditional end-to-end observations;
- exact-byte durable telemetry, typed evidence, host recomputation, and content-addressed ledgers;
- a new protocol version, evidence schema/profile, stable blocking codes, device command, and tests;
- explicit Task 7 admission from a non-blocking v2 ledger only.

### 4.2 Out of scope

- participant capture or participant-derived data;
- changing the model, delegate, camera resolution, preprocessing, or single-flight ownership;
- changing the target cadences from 10/15/30;
- increasing concurrent inference slots;
- optimizing MediaPipe or selecting a different backend;
- M0.5C, iOS equivalence, mid-range claims, reliability thresholds, or production claims;
- rerunning v1 or relabelling its blocked evidence.

Any later backend optimization, delegate change, concurrency change, or cadence-set change requires
a separately reviewed design and new versioned evidence.

## 5. Protocol identity and preregistration

Before any v2 physical run, commit a canonical protocol record containing:

- protocol and evidence schema versions;
- exact arm definitions and execution order;
- target cadences `[10, 15, 30]`;
- source warm-up minimum `30` delivered callbacks;
- source callback minimum `300`;
- backend completion minimum `100`;
- warm-up minimum `20` completed inferences and minimum elapsed time `2_000_000_000 ns`;
- per-arm maximum elapsed time `60_000_000_000 ns`;
- cooldown maximum elapsed time `600_000_000_000 ns` and sampling interval `1_000_000_000 ns`;
- replicate orders `[10, 15, 30]` and `[30, 15, 10]`;
- one monotonic clock and timestamp field used for every interval/rate calculation;
- terminal accounting vocabulary and exact verdict predicates;
- required app, test APK, model, runtime, backend, delegate, camera, and device identities.

The canonical protocol bytes have a SHA-256. Every physical record embeds that digest. A changed
protocol requires a new digest and a new evidence record; an observed result never edits the
protocol that judged it.

The numeric minima above are instrument sufficiency rules, not product-quality thresholds:

- 30 discarded source callbacks separate camera startup from the measured source sequence;
- 300 source callbacks represent ten seconds at the requested 30 FPS and expose source-rate and
  inter-arrival behavior beyond startup;
- 20 warm-up completions exclude model construction/JIT/allocation transients while the two-second
  floor prevents a fast backend from making warm-up instantaneous;
- 100 measured completions support an actual latency distribution rather than a two- or
  three-sample anecdote; and
- 60 seconds bounds every short arm. Reaching the cap is recorded as insufficient evidence, never
  converted into a negative or positive capacity claim.

These values may be changed only in a future protocol version, never after seeing v2 output.

## 6. Shared environmental preconditions

All arms use one exact prepared candidate and one authorized physical S25. The device must remain:

- unplugged and not charging;
- connected through exactly one Wi-Fi ADB transport;
- on the same OS build and device identity;
- on the same app/test APK, model, runtime, backend, delegate, camera configuration, and
  preprocessing versions;
- free of participant presence and participant data.

Each arm records Android thermal status, battery temperature/level, power source, connectivity,
screen/keep-awake state, negotiated CameraX FPS range, delivered image dimensions/format, and the
arm's boundary timestamps.

The first arm establishes the exact Android boundary thermal-status value. Before every later
measured arm, the device must return to that same value. Cooldown samples once per
`1_000_000_000 ns` for at most `600_000_000_000 ns`, reusing the released M0.5B schedule. If the
status does not return within that period, v2 records a thermal-state blocker and stops. No battery-
temperature threshold is invented here.

No rebuild, reinstall, package clear, model replacement, configuration change, or evidence-file
edit may occur between the first physical arm and the final verdict.

## 7. Observation vocabulary and conservation

For every delivered callback, v2 evaluates two predicates without changing production behavior:

- `cadence_due`: a shadow observer, driven by the exact physical callback timestamp sequence,
  determines whether the configured minimum interval permits selection;
- `slot_available`: the actual single inference slot is neither reserved nor in flight.

The shadow observer advances according to its own cadence-selected timestamps even when production
is busy. It exists only to expose the counterfactual cadence predicate. It cannot authorize an
actual inference, update production admission state, or satisfy the Task 6 gate by itself.

The record retains the production admission result as a separate field. Terminal classification
then uses this precedence: session stop, preprocessing failure, submitted-inference outcome, and
finally the two independently observed predicates. Each delivered callback has exactly one terminal
class:

- `admitted_completed`;
- `admitted_inference_failed`;
- `cadence_only` (`!cadence_due && slot_available`);
- `backpressure_only` (`cadence_due && !slot_available`);
- `cadence_and_backpressure` (`!cadence_due && !slot_available`);
- `preprocessing_failed` (production reserved the candidate and preprocessing failed);
- `session_stopping`.

If a non-terminal production admission result cannot be reconciled with the independently observed
predicate pair, the callback is `admission_state_divergence` and the entire arm is invalid. It is not
silently forced into one of the three rejection classes.

For every arm and replicate:

```text
delivered_callbacks
  == admitted_completed
   + admitted_inference_failed
   + cadence_only
   + backpressure_only
   + cadence_and_backpressure
   + preprocessing_failed
   + session_stopping
   + admission_state_divergence

submitted_inferences
  == admitted_completed + admitted_inference_failed
```

No pending inference may remain at finalization. Duplicate, missing, foreign, or multiply terminal
callback IDs invalidate the evidence rather than creating a performance verdict.

## 8. Arm 0A: physical source capacity

Arm 0A runs the real CameraX source without preprocessing or inference. Each owned image is closed
after its timestamp and source metadata are durably recorded.

Warm-up ends only after the source is started and at least 30 callbacks have been discarded. The
measured arm ends when 300 callbacks are durable or the 60-second cap is reached.

The host derives from the exact callback timestamp sequence:

- measured span and delivered callback rate;
- inter-arrival minimum, lower median, upper median, and maximum;
- callback regressions or duplicates;
- the existing exact closed-interval callback expectation for 10, 15, and 30 FPS; and
- production-selector opportunity counts for 10, 15, and 30 FPS with a permanently free slot,
  retained as diagnostic input for Arm 1 rather than source-capacity admission.

For cadence `c`, `source_supports(c)` is true only when the delivered callback count is at least the
closed-interval callback expectation for `c` over the measured span. A shortfall is
`source_limited(c)`. This predicate uses the finite-window opportunity formula and introduces no
percentage margin. It deliberately does not use the production selector: selector behavior is a
different question owned by Arm 1.

Fewer than 300 callbacks, timestamp regression/duplication, or an unknown/changed negotiated source
configuration produces a typed blocking result.

## 9. Arm 0B: physical backend capacity

Arm 0B runs the real source, preprocessing, and MediaPipe backend with the cadence ceiling disabled
for this arm only. The production single-flight slot, callback ownership, preprocessing, inference,
and terminal semantics remain unchanged. Every slot-free callback is submitted.

Warm-up ends only after both 20 completed inferences and two session-clock seconds. Warm-up events
remain durable but are explicitly outside measured aggregates. The measured arm ends when 100
additional inferences are terminal or the 60-second cap is reached.

The host derives:

- measured source and completion rates;
- every measured inference latency plus lower median, upper median, maximum, and existing fixed
  histogram representation;
- time spent with the single-flight slot available and occupied;
- source, preprocessing, submission, completion, failure, and terminal accounting;
- whether the measured service-duration sequence can serve exact 10/15/30 cadence opportunities
  without an overlapping in-flight interval.

For cadence `c`, the host computes the production interval `floor(1_000_000_000 / c)` and constructs
101 boundaries at `k * interval` for `k = 0..100`. It starts each of the 100 measured Arm 0B service
durations, in observed order, at boundaries 0 through 99. `backend_supports(c)` is true only when
every service completes no later than its next boundary, so every one of the 100 opportunities can
be served by the single-flight backend. A shortfall is `backend_limited(c)`. This is an exact replay
of observed service times, not an arbitrary utilization percentage or a percentile substituted for
the sequence. Arm 0A separately establishes whether the physical source can supply cadence `c`.

Any inference failure, incomplete terminal accounting, fewer than 100 measured completions, or
changed identity/configuration blocks v2. If Arm 0A or Arm 0B does not support all three target
cadences, the end-to-end arms do not run.

## 10. Arm 1: selector isolation

Arm 1 replays the exact Arm 0A physical callback timestamps through the production cadence
admission object with a controlled free slot. It must not reimplement the interval arithmetic.

The arm is falsifiable: for each cadence its selected timestamp sequence must equal the sequence
derived independently by the host from the protocol's integer interval rule. The three selected
counts must be strictly ordered for the same source trace. Collapsing a threshold, using synthetic
timestamps, changing an input timestamp, or diverging from the host derivation fails the arm.

Arm 1 evidence uses an explicitly non-authoritative selector-conformance record kind. Neither its
schema nor any `selector_distinct` field is accepted by Task 7 or the sustained-run prerequisite.

## 11. Arm 2: conditional end-to-end attribution

Arm 2 runs only when Arms 0A and 0B support all of 10, 15, and 30 FPS and Arm 1 is valid.

It executes two replicate orders without rebuilding or clearing state:

```text
replicate A: 10 -> 15 -> 30
replicate B: 30 -> 15 -> 10
```

Every cadence run uses the same warm-up and measured stopping rules as Arm 0B. The device must
return to the shared boundary thermal-status class before each measured run. If it cannot, the
protocol stops rather than comparing thermally confounded runs.

The host derives all §7 classifications, measured spans, rates, latency distributions, source and
backend support, and identity bindings. The per-run predicate `cadence_limited(c)` is true exactly
when `source_supports(c)`, `backend_supports(c)`, and at least one callback is classified
`cadence_only` or `cadence_and_backpressure`. It does not by itself assert that cadence was the sole
limiter or satisfy the final gate. Any `admission_state_divergence` invalidates the run.

`end_to_end_distinct` is true only when, independently in both replicate orders:

1. all three runs satisfy sample, identity, closure, failure, and environmental checks;
2. admitted-completion rates computed from exact measured spans are strictly ordered
   `10 < 15 < 30`;
3. cadence-selected opportunity rates are strictly ordered `10 < 15 < 30`; and
4. no run is classified source-limited or backend-limited at its configured cadence.

The pooled result is descriptive only and cannot override a replicate disagreement. If either
replicate lacks the ordering, v2 records a blocking end-to-end result. This avoids treating
serially dependent callbacks as independent Bernoulli trials and avoids inventing a statistical
confidence threshold.

## 12. Verdict model

V2 exposes derived per-cadence states, not one overloaded saturation Boolean:

- `source_limited`;
- `backend_limited`;
- `cadence_limited`;
- `supported_and_distinct`;
- `sample_insufficient`;
- `invalid_evidence`.

The run-level fields are:

- `selector_distinct`;
- `end_to_end_distinct`;
- `all_target_cadences_supported`;
- `satisfies_s25_attribution_v2_gate`.

The final gate is true only when:

```text
selector_distinct
&& end_to_end_distinct
&& all_target_cadences_supported
&& every measured arm is sample-sufficient
&& every identity/environment binding matches
&& every conservation and terminal-closure check holds
&& inference_failures == 0
```

An Arm 0 blocker prevents later arms and remains a valid, trustworthy physical observation. It is
not an infrastructure failure and is not retried. Invalid or interrupted evidence follows the
existing durable-session recovery rules and never becomes a performance verdict.

## 13. Stable error-code families

Implementation will release additive v2 codes covering at least:

- source sample insufficiency;
- backend sample insufficiency;
- source timestamp/configuration invalidity;
- source cadence limitation;
- backend cadence limitation;
- selector divergence;
- production/shadow admission-state divergence;
- callback conservation failure;
- terminal closure failure;
- inference failure;
- replicate disagreement;
- thermal-state mismatch;
- exact-candidate or protocol-digest mismatch;
- selector-only evidence presented as Task 6 admission.

Names are finalized in the implementation plan before registry release. No v1 code is renamed or
reused, and no generic code may hide which subsystem limited the run.

## 14. Evidence and ledger model

V2 uses new files beneath `docs/evidence/m05b-attribution-v2/`. It never edits the v1 evidence file
or v1 ledger.

The device emits references only. The host transfers exact durable bytes, fsyncs and hashes them,
reopens every referenced artifact, validates schemas, recomputes every count/rate/classification,
and emits one sanitized result. The device cannot declare the final verdict.

The v2 ledger supports:

- `pending`: protocol/candidate/device preconditions not yet satisfied;
- `recorded`: all v2 admission predicates recompute true;
- `blocked`: valid evidence recomputes one or more typed blockers.

A recorded or blocked ledger binds the exact evidence SHA-256, protocol SHA-256, candidate app/test
APK digests, Git SHA, model digest, runtime/backend/delegate, camera configuration, device identity,
OS build, arm order, and every inherited prerequisite evidence digest.

Evidence files are immutable after admission. A new observation uses a new file and protocol/run
identity; it never overwrites an earlier record.

## 15. Task 7 and sustained-run interaction

The existing v1 blocked ledger cannot satisfy Task 7. Task 7 may proceed only when the new v2 ledger
is `recorded` and the host verifier recomputes `satisfies_s25_attribution_v2_gate=true`.

If v2 reports source or backend limitation at any target cadence, M0.5B remains blocked. The release
owner may later choose one of three separately versioned directions:

1. optimize the existing backend/delegate while preserving 10/15/30;
2. review a different backend/delegate/concurrency architecture; or
3. define a new target cadence set.

That decision is a new product/research record referencing all immutable evidence hashes. It cannot
retro-green v1 or v2, and any selected change requires a new exact candidate and protocol version.

## 16. Verification and required mutations

Every behavior change is regression-first. Each mutation must fail the named predicate it intends
to exercise, not an unrelated schema or digest check.

Required software and fixture mutations include:

- collapse two cadence thresholds and fail selector distinction;
- replace production selector replay with divergent arithmetic and fail host equality;
- substitute synthetic timestamps for recorded device timestamps;
- omit negotiated source FPS/configuration;
- remove source or backend sample minima;
- count warm-up records inside measured aggregates;
- remove the 60-second cap or mislabel a capped arm as sufficient;
- change predicate evaluation so `cadence_and_backpressure` is lost;
- create a production/shadow predicate combination with no valid terminal classification;
- remove one terminal class from the conservation identity;
- duplicate, omit, or substitute a callback/terminal ID;
- label a source-limited run as backend-limited or vice versa;
- allow selector-only evidence to satisfy Task 7;
- run Arm 2 after an Arm 0 blocker;
- run only one order, silently reorder a replicate, or pool away a disagreement;
- change thermal status, charging state, transport, candidate identity, or protocol digest;
- label a trustworthy blocking record as admitted;
- mutate recorded evidence bytes without changing the ledger digest.

The implementation boundary requires focused Kotlin/Python tests, full `make verify`, JVM/Android
host tests, Android instrumentation assembly, iOS compile preservation, repository guard, exact
diff/scope inspection, one bounded full-range review, and protected CI before integration.

## 17. Physical execution and recovery

1. Commit and protect the v2 protocol, schemas, checker, device producer, and pending ledger.
2. Build/install one exact candidate and verify installed app/test APK bytes.
3. Establish exactly one authorized unplugged Wi-Fi S25 transport.
4. Run Arms 0A and 0B once under their preregistered rules.
5. If either arm blocks, emit and record the blocking result; do not run later arms.
6. If both support all target cadences, run Arm 1 and then the two Arm 2 replicate orders.
7. Record the exact sanitized result and independently verify its ledger.
8. Proceed to Task 7 only from a non-blocking verified v2 ledger and without rebuilding or clearing
   app data.

A transport loss after durable publication triggers recovery of that exact run. A rerun is allowed
only for an attempt proven `ABORTED` or `FAILED` before durable completion, and the failed attempt
remains visible. A trustworthy blocked observation is final for its protocol/run identity.

## 18. Expected interpretation

The likely result, stated before v2 implementation, is that the CPU backend is slower than the
lowest 10 FPS target, the achieved camera source is below its requested 30 FPS during startup, or
both. V2 is successful if it identifies the limiting subsystem with valid evidence, even when the
final Task 6 gate remains blocked.

The purpose is not to manufacture a green gate. It is to decide whether 10/15/30 sustained
characterization is a meaningful experiment on this exact device/backend configuration.
