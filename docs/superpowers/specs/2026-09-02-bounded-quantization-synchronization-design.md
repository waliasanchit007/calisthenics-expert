# Bounded-Quantization Synchronization Profile Design

**Status:** Approved on 2026-09-02 after physical S25 evidence contradicted the exact-offset-only model.

**Parent design:** `docs/superpowers/specs/2026-08-24-m0-5b-instrument-prerequisites-design.md`

## 1. Problem and observed evidence

The released `synchronization_evidence v1.0.0` profile requires every
`submitted_pts_us - demuxed_pts_us` value to be identical. That profile remains valid for artifacts
that actually have an exact transform, but it is not sufficient for an MP4 track with a finite
timescale.

On the physical Samsung SM-S938B, both tracks used a 90,000-tick-per-second timebase. Six submitted
PTS values still equalled `floor(rebased_capture_timestamp_ns / 1_000)`, and video/metadata tracks
remained paired, but submitted-minus-demuxed offsets spanned 9 microseconds. One 90 kHz tick is
11.111... microseconds. The 90 kHz rounding model reproduced all six demuxed values exactly.

This is strong evidence for deterministic container quantization rather than clock drift. A
controlled on-grid/off-grid probe is still required before the mechanism is recorded as causally
demonstrated.

## 2. Compatibility and release decision

Do not change `camera_capture_to_container_pts_constant_offset_v1` or the semantics of any released
v1.0.0 artifact. Release `synchronization_evidence v1.1.0` as a backward-compatible reader
extension with two explicit profiles:

- `exact_constant_offset`: the existing v1.0.0 behavior, selected by
  `mapping_method=camera_capture_to_container_pts_constant_offset_v1`;
- `bounded_quantization`: the new behavior, selected by
  `mapping_method=camera_capture_to_container_pts_bounded_quantization_v1` and
  `sync_profile=bounded_quantization`.

Existing exact evidence remains valid without `sync_profile`. New bounded evidence must declare the
profile. Unknown profiles and mapping methods fail closed. The stable-code registry advances
additively; no released code is renamed or reused.

## 3. Bounded profile data

Bounded evidence retains the common session, artifact, PTS, endpoint, count, provenance, and device
fields and adds these required fields:

- `container_timebases.video_ticks_per_second`: positive integer;
- `container_timebases.metadata_ticks_per_second`: positive integer;
- `container_offset_interval_ns.minimum`: minimum submitted-minus-demuxed offset;
- `container_offset_interval_ns.maximum`: maximum submitted-minus-demuxed offset;
- `container_quantization_span_bound_ns`: bound derived from the admitted common timebase.

The two track timebases must be equal. Missing, zero, negative, or inconsistent timebases fail
closed. `container_offset_ns` remains a deterministic representative for compatibility: the lower
median of all per-sample offsets. Its magnitude is never bounded. `container_drift_ns_per_s` remains
the point estimate `0`; the interval and quantization bound state the finite resolution of that
estimate for this run.

## 4. Derived bound and admission

All calculations use integer arithmetic. For common timebase `f` ticks per second:

```text
two_tick_span_ns = ceil(2_000_000_000 / f)
readout_allowance_ns = 2_000
derived_span_bound_ns = two_tick_span_ns + readout_allowance_ns
```

The two-tick term covers the difference between two samples when each timestamp can be quantized by
less than one container tick. The 2,000 ns allowance covers integer-microsecond readout at both ends
of a comparison. At 90 kHz the derived bound is 24,223 ns; because observed offsets are integral
microseconds, a 24,000 ns span is admissible and a 25,000 ns span is not.

The validator recomputes every value. Bounded evidence is admitted only when:

1. every submitted PTS equals `floor(rebased_capture_timestamp_ns / 1_000)`;
2. all submitted and demuxed sequences are strictly increasing and fully paired;
3. video and metadata tracks expose identical demuxed PTS sequences;
4. the two positive track timebases are equal;
5. the declared interval equals the actual minimum and maximum sample offsets;
6. the declared bound equals the formula above;
7. actual interval width is at most the derived bound;
8. `container_offset_ns` equals the lower median of the actual offsets;
9. endpoint, digest, count, codec, container, and callback-accounting checks continue to hold.

The bounded profile makes no claim about physical clock synchronization or offset magnitude.

## 5. Probe correction

The existing Task 3 evidence is preserved as historical v1.0.0 evidence. Its exact result was
incidental to its chosen timestamp samples and demonstrates only that the observed sequence was
exact.

A new controlled S25 observation records both track timebases and two sequences in one candidate:

- an on-grid sequence whose relative timestamps map exactly to container ticks;
- an off-grid sequence whose residuals are non-constant but remain within the derived bound.

Both must use the same authorized device, codec, container, app SHA, and probe code SHA. The checker
recomputes both verdicts. On-grid must be exact; off-grid must be non-exact and bounded. This converts
the quantization mechanism from strong inference to controlled evidence.

## 6. Host sanitizer correction

The synchronized-video host checker currently searches for an absolute-path pattern anywhere in a
value, so a valid relative path such as `exports/<session>/video.mp4` is rejected at its embedded
slash. Absolute-path detection must be anchored to the start of the value while retaining Unix,
`file://`, and drive-letter rejection. This fix lands separately with positive relative-path and
negative absolute-path regressions.

## 7. Verification and mutations

Regression-first tests must prove:

- a session-relative artifact reference is sanitized while Unix, `file://`, and drive-letter paths
  are refused;
- legacy exact v1.0.0 evidence remains valid;
- a derived sub-tick offset perturbation is accepted by the bounded profile;
- a perturbation at the first integral-microsecond value above the derived bound is rejected;
- changing a middle sample, not an endpoint, triggers the bound check;
- missing, zero, negative, inconsistent, or caller-misstated timebases fail closed;
- unknown profile and mapping method fail closed;
- changing the interval, representative offset, or derived bound fails;
- the on-grid/off-grid physical control is non-vacuous and bound to exact candidate identities.

Every mutation must name the intended failing assertion. A failure caused only by JSON Schema when
the mutation intends to exercise semantic code does not count.

## 8. Recovery sequence

1. Record the failed physical run in the ignored ledger; do not admit it as successful evidence.
2. Land the sanitizer fix independently.
3. Add RED profile/schema/semantic/Kotlin tests and committed synthetic fixtures.
4. Implement the v1.1.0 profile, stable codes, producer, MP4 timescale reader, and host verifier.
5. Run focused mutations and complete software verification.
6. Execute and record the controlled on-grid/off-grid S25 probe.
7. Rebuild the exact candidate and rerun the synchronized-video publication gate.
8. Only after that gate passes may Task 5b begin.

