# Endpoint-timing experiment — HSPU, bounded

Specification frozen before implementation: `.superpowers/sdd/2026-09-11-muscle-up/endpoint-timing-spec-v1.md`.
Scope: one variant (HSPU strict wall-supported, back-to-wall `cLTvfDlhiXM`), with the wall-facing
recording `tz_YVynl1sk` used once, afterwards, as a no-retuning check. Nothing was deployed,
migrated or overwritten.

## 1. What the annotation actually marks — settled before the estimator was designed

From `annotations_v2_frozen.json`, by structure, not by score:

- every rep carries `top_start_s`, `bottom_s`, `top_end_s`;
- `top_end_s(n)` is **numerically identical** to `top_start_s(n+1)` (4.83, 6.50, 8.17, 9.83);
- the null-reason text calls a missing `top_start_s` "rep 1 **descent onset**".

**The annotated instant is departure from the top (descent onset)** — not first arrival, not the
centre of the plateau. So an extremum must not be scored as though it were this event, and the
estimator matched to the annotation is a *departure* estimator.

### The annotation is coarser than the feature, and that caps what this can decide

`sampling_step_s = 0.333`, `uncertainty_s = 0.333`. The measured plateau is 0.03–0.27 s wide.

- **Decidable**: threshold-crossing vs plateau-region — they differ by ~0.6 s.
- **NOT decidable**: extremum vs departure — they differ by ~0.1 s, far inside ±0.333 s.

This was written down before scoring, and the results below respect it.

## 2. What was built

`EndpointTimingEstimate.kt`, additive by construction: it consumes a repetition an engine has
**already** decided and never participates in counting. The existing crossing and confirmation
instants are preserved and reported alongside; nothing is renamed or overwritten.

No new threshold. The excursion's admission level (`extendedSpanRatio = 0.80`) doubles as its
reversal level. The one new parameter, `plateauBandFraction = 0.05`, was fixed from **signal noise
only** — per-frame |Δ| on near-top frames measured median 0.0024, p90 0.0099, and 5% of a 0.1996
excursion is 0.0100, the smallest band ordinary jitter will not fragment. No annotation was
consulted in choosing it, and it was not changed afterwards.

Refusals are first-class: `UNKNOWN_DROPOUT`, `UNKNOWN_NO_REVERSAL`, `UNKNOWN_DISCONTINUITY`,
`UNKNOWN_DOUBLE_PEAK`, `UNKNOWN_TOO_FEW_SAMPLES`. Nothing is interpolated across a gap, and the
search never spans two repetitions. 13 synthetic trajectories pin plateau noise, shallow vs deep
dips, double peaks, dropouts, partial reps, truncation and ties. Suite **860 tests / 93 suites, 0
failures**.

## 3. Results — accuracy and latency reported separately

### `cLTvfDlhiXM` back-to-wall, against the frozen v2 annotation (±0.333 s)

| rep | annot | crossing err | extremum err | departure err | plateau width | confirmation delay |
|---|---|---|---|---|---|---|
| 1 | 4.968 | −0.597 | −0.163 | −0.030 | 0.234 | 0.434 |
| 2 | 6.638 | −0.532 | −0.098 | +0.102 | 0.234 | 0.300 |
| 3 | 8.308 | −0.634 | −0.200 | −0.100 | 0.100 | 0.500 |
| 4 | 9.968 | −0.692 | +0.009 | +0.075 | 0.267 | 0.434 |
| 5 | 11.308 | −0.130 | — | **REFUSED** (dropout) | — | — |

mean: crossing **−0.517**, extremum −0.113, departure **+0.012**.

### `tz_YVynl1sk` wall-facing — identical config file, no retuning

| rep | annot | crossing err | extremum err | departure err | confirmation delay |
|---|---|---|---|---|---|
| 1 | 7.971 | −0.564 | +0.304 | +0.337 | 0.300 |
| 2 | — | — | — | — | — (annotation records this top as NOT observed) |
| 3 | 12.746 | −0.701 | −0.334 | −0.200 | 0.500 |
| 4 | 14.950 | −0.736 | +0.065 | +0.065 | 0.267 |
| 5 | 17.150 | −0.600 | — | **REFUSED** (dropout) | — |

mean: crossing **−0.650**, extremum +0.012, departure **+0.067**.

## 4. Reading

1. **The crossing is wrong by more than the annotation's own uncertainty, on 8 of 8 scorable
   repetitions across both recordings.** That is the one claim this annotation can support, and it
   supports it consistently.
2. **Extremum and departure are indistinguishable here** and neither is claimed better. The proof is
   in the data: on the v1 timebase the extremum looks best (mean +0.025) and on the v2 timebase the
   departure looks best (mean +0.012). A 0.138 s timebase correction flips the winner — so the
   annotation is not adjudicating between them, it is adjudicating noise.
3. **Latency is real and is not netted off.** The estimate finalises only at reversal, 0.27–0.77 s
   after the instant it describes. A live system can emit the crossing immediately and this estimate
   only later; that is a property of the definition.
4. **The refusals behaved.** Two repetitions were refused for a dropout rather than given a number,
   and one had no labelled event because the annotator had recorded that top as unobserved.
5. **Counts and cycle identities are byte-identical** to the frozen run on both recordings — the
   counted-frame timestamp lists match exactly.

## 5. Proposal

**Adopt for HSPU only**, as an additive reported field (`estimated_departure_ns` plus its status and
uncertainty), offline, alongside the existing crossing and confirmation instants. It transferred to
a second recording with no retuning, and it refuses rather than guesses.

**Do not** migrate other movements yet, rewrite historical outputs, or ship anything. Each movement
needs its own definition check first: the annotation convention must be read before the estimator is
pointed at it, exactly as in section 1.

**To choose between extremum and departure, a finer annotation is required** — sampling at ≤0.1 s on
a handful of repetitions. Until then the two are reported together and the plateau width is carried
as the uncertainty. Moving the existing labels to settle it is not an option.

---

## 6. ADOPTED — 2026-09-12, HSPU only

Owner approved adoption. Scope, exactly as proposed and no wider:

**What is adopted.** A second, OFFLINE pass over an HSPU prediction file, emitting
`estimated_departure_ns` with its `status`, `uncertainty_ns` and `confirmation_delay_ns` alongside
the preserved `crossing_ns` and `confirmation_ns`. Entry point
`ReplayEndpointTiming` (`--predictionsPath --endpointConfigPath --endpointOutputPath`), frozen
config `offline-corpus/endpoint-timing-20260911/endpoint-config-hspu-v1.json`
(sha256 `5b6fbb7045fae2271c3f3910bbd9b02389eabbeadd8234030b1e6838040d198a`).

**Why it stays a second pass.** The estimate needs frames after the endpoint. It cannot be emitted
inline by a per-frame writer, and pretending otherwise would hide the 0.27–0.77 s lookahead. A live
system emits the crossing immediately and this estimate only after reversal.

**Added on adoption.** `ReplayEndpointTimingTest` (7 tests) covering strict config parsing, one
estimate per counted repetition, the guarantee that one repetition's search never reaches into the
next, pass-through of the engine's own instants, dropout and capture-gap refusals, and refusal to
overwrite an existing output. Suite **867 tests / 94 suites, 0 failures**.

**Explicitly NOT adopted.** No other movement. No historical output rewritten. No annotation moved.
No app change and no deployment. The extremum-vs-departure question stays open and unclaimed.
