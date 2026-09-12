# Muscle-up offline packet — 2026-09-11

Owner priority #1. Strict ring and kipping bar are kept separate throughout.

Read in order: the first sections cover the Commons GIF (public domain, cropped, no complete
cycle). **Addendum 1** adds the owner-authorized strict-ring and kipping-bar clips and
supersedes the "YouTube clips were not used" statement below. **Addendum 2** records the
completion-definition work and a hypothesis that turned out to be mostly wrong.

## What this is and is not

| Stage | State |
|---|---|
| Implemented (Kotlin) | `MuscleUpCycleExperiment.kt` + `MuscleUpCycleExperimentTest.kt` written |
| Compiled / Kotlin tests run | **NO — blocked, see below** |
| Synthetic-tested | Yes, via a Python reference mirror: 9/9 behaviours incl. the v2 mount regression |
| Real-video evaluated | Yes — three sources: Commons GIF, one strict-ring clip, one kipping-bar clip |
| Device-tested | No |
| Deployed / integrated into the app | No. No app selector wiring, no commit, no push |

## Blocker: no Kotlin toolchain on this host

`./gradlew :shared:jvmTest` cannot run.

- `~/.gradle/caches` does not exist (no dependency cache, no Kotlin compiler).
- `~/.gradle/wrapper/dists/gradle-9.3.1-bin/…` contains only a zero-byte `.zip.part`.
- `services.gradle.org/distributions/gradle-9.3.1-bin.zip` 307-redirects to
  `release-assets.githubusercontent.com`, which the corporate proxy answers with
  `502 Bad Gateway — failed to connect` (observed from egress `163.116.212.42`).

No workaround was attempted beyond retrying the official URL. The Kotlin engine and its
tests are therefore **written but unverified**. They must be compiled and run before any
claim about the Kotlin implementation is made.

To unblock: run `make prepare-harness-gradle-dependencies` (or any Gradle invocation)
from a network that can reach `release-assets.githubusercontent.com`.

## Event convention (stated explicitly, per handoff §10.3)

The measurement is a **regime crossing**, not an elbow angle:

- `BELOW_BAR` — wrist at least 0.05 image-height above the shoulder. Purely geometric:
  **no stillness is required**, so a continuously swinging kipping cycle is admissible.
- `ASCENDING` — the hang regime has been left.
- `SUPPORTED` — above-apparatus support: shoulder at least 0.05 image-height above the
  wrist, sustained for `minimum_endpoint_samples` frames.
- `RETURNING` → `BELOW_BAR` — the completion event, consistent with the existing
  `compare_events` convention where an event is the return after the extreme.

**Support and full elbow lockout are different observations.** This experiment measures
support only and makes no lockout claim.

A cycle is counted **only if above-apparatus support was actually observed on usable
frames**. An excursion that left and returned to the hang without a usable support
observation is reported as `SUPPORT_UNRESOLVED` and counted in `unresolved_excursions`,
never in `count`. Unknown endpoints stay unknown; they are never interpolated.

## Two joint-identity gates (not confidence changes)

Cached development footage showed MediaPipe returning confident-but-wrong arm skeletons
while the real upper body was cropped (`visual-findings.json`, frames 12 and 15). The
response is physical consistency, not a higher confidence threshold:

- `GRIP_DRIFT` — the grip point may not travel more than `maximum_grip_drift_torso_lengths`
  from its hang reference. The apparatus does not move under a fixed camera, so a wrist
  that migrates down the frame is not a wrist.
- `BODY_SCALE_INCONSISTENT` — the shoulder-to-hip segment may not leave
  `[minimum_body_scale_ratio, maximum_body_scale_ratio]` of its hang reference. A real
  torso does not halve between frames.

Both mark a frame unusable. Neither can turn an unusable frame into a repetition.

**Threshold provenance, disclosed:** `minimum_body_scale_ratio = 0.6` was chosen after
inspecting the per-frame torso trace of this development source, so it is calibrated, not
blind. It is not tuned to an expected count — the frozen labels carried
`complete_cycle_label: null` on every keyframe before any inference, and the analyzer's
result is likewise "no complete cycle".

## Source

`$CAL_CORPUS/owner-priorities-20260909/muscle-up-v1/` — Commons `File:Muscle-up.gif`,
author public-domain dedication, attribution Extremistpullup,
`video_sha256 c6950b64…`, cache `poses_sha256 99e936c1…`, model `5134a3aa…`.
31 frames, 151×151, time base 1/100, native PTS at 100 ms steps. Already admitted for
visible phases only; `source-screen.json`'s rejection of complete-cycle evidence stands.

The YouTube clips catalogued on 2026-09-10 were not used *for this section*. **Superseded by
Addendum 1**: the owner subsequently authorized them for internal assessment only. The
licence position is unchanged and recorded there.

Side = **RIGHT**, selected on joint availability before any geometry or count was read:
RIGHT 21/31 frames pass confidence and bounds for (shoulder, wrist, hip), LEFT 18/31.
Disclosure: both sides' geometry traces had already been viewed during the joint-identity
investigation, so this selection is not blind.

Frozen config: `$CAL_CORPUS/muscle-up-20260911/config-bar-kipping-right-v1.json`.

## Result

`count = 0`, `unresolved_excursions = 1`.

| Interval | Observation |
|---|---|
| 0–400 ms | below-bar hang; reference grip and body scale established |
| 500 ms | leaves hang regime — excursion opens |
| 700–1000 ms | above-apparatus support **observed** (scale 0.92→0.70, drift ≤0.24) |
| 1100–1500 ms | `BODY_SCALE_INCONSISTENT` — torso collapses to 0.50→0.41 of reference |
| 1600, 2200–2400 ms | `LOW_CONFIDENCE` |
| 1700–2100, 2500 ms | `NO_POSE` |
| 2600–3000 ms | below-bar hang again, but continuity was already lost |

Reason counts: `NONE 16, BODY_SCALE_INCONSISTENT 5, LOW_CONFIDENCE 4, NO_POSE 6`.

The single excursion is `SUPPORT_UNRESOLVED`: support was observed at 700 ms but the
**return was never observed**, so no complete cycle is claimed. This independently
reproduces the frozen pre-inference labels, which left every `complete_cycle_label` null.

The 1100–1500 ms rejection is the cached arms-on-shorts failure being caught by geometry
rather than by confidence.

**Honest limitation:** on this source the 2200–2400 ms frames — the ones whose skeleton
migrated to the bottom of the frame and would satisfy a naive shoulder-above-wrist test —
were rejected by the **confidence** gate on the RIGHT side, not by `GRIP_DRIFT`. The grip
gate is validated only synthetically so far. It needs a source where a migrated skeleton
still passes confidence.

## Verification actually performed

- Synthetic parity, Python mirror, 13/13: cycle counted only on observed support;
  excursion endpoints; body-scale collapse; collapse not counted; grip drift not a false
  support; toes-to-bar never leaves the hang regime; single support frame not counted;
  dropout after support leaves the return unseen; duplicate, backwards and gapped
  timestamps; low confidence.
- Mutation check: disabling the grip gate flips the false-support fixture from
  `count=0, unresolved=1` to `count=1, unresolved=0`. The gate is load-bearing.
- Real replay receipt: `$CAL_CORPUS/muscle-up-20260911/replay-reference-v1.json`.

The Python mirror is a verification harness, **not a second engine**. It exists only
because the Kotlin toolchain is unavailable, and it does not verify the Kotlin code.

## Addendum — owner-authorized clips (strict ring and kipping bar)

**Authorization.** The project owner explicitly authorized, on 2026-09-11, use of the
already-downloaded YouTube copies for **internal assessment only**: no redistribution, no
reuse for other purposes, no upload, kept outside Git. The licence position is unchanged
and recorded in each source record: standard YouTube licence, **not** Creative Commons,
retrieval was contrary to YouTube ToS. Nothing was re-downloaded and the 2026-09-10
acquisition workaround was **not** repeated — only local copies were read, and their
SHA-256s were verified against the frozen manifest before use.

Extraction reused `tools/extract_offline_pose.py` with the pinned model `5134a3aa…`
(same model as the GIF), MediaPipe 0.10.21 / PyAV 18.1.0, native rational PTS, square SAR,
rotation 0, single video stream. Side = RIGHT on both, selected on availability before any
geometry: ring 583/583 vs 368/583; bar 617/617 vs 616/617. Labels were frozen into scorable
completion windows from `annotations_v2_frozen.json` **before** any prediction.

`k71s4T2_CJI` and `AJAVu29c-NU` share group `invictus-travis-ewart-bar-2024` — same coach
and gym, so they are **not** independent athletes and neither is held-out evidence.

### Defect found and corrected (v1 → v2, disclosed as non-blind)

v1 produced `GRIP_DRIFT` on 572/617 bar frames and lost 2 of 3 ring reps. Cause was a
design defect, not a threshold: the grip reference was taken from the first frames merely
satisfying "wrist above shoulder", which during a **mount** is the athlete reaching upward
before the hands are on the apparatus (bar reference captured at t=0.27 s, wrist y=0.447,
while the gripped wrist sits at y≈0.22 — 1.85 torso lengths away).

v2 requires the reference to come from an already-stationary grip
(`minimum_reference_samples` consecutive below-bar frames with wrist spread within
`maximum_reference_wrist_spread_torso_lengths`). This follows from the invariant already
claimed — the apparatus does not move — so a genuine grip is stationary and a mount is not.
**This correction was made after seeing v1 results and is therefore not blind.** v1 configs
and reports are retained unchanged.

### v2 results

| Clip | variant | counted | unresolved | matched | missed | extra |
|---|---|---|---|---|---|---|
| `yTczZRbcfJU` | strict ring | 3 | 1 | **0** | 3 | 3 |
| `k71s4T2_CJI` | kipping bar | 3 | 0 | **3** | 0 | 0 |
| Commons GIF | bar, cropped | 0 | 1 | — | — | — |

The ring's 4th excursion is the dismount at 18.55 s; it never reaches support and is
correctly left `SUPPORT_UNRESOLVED` rather than counted.

**Toes-to-bar guard held on real footage.** `k71s4T2_CJI` continues into toes-to-bar from
~9.5 s; it produced **zero extra events** across the remaining ~11 s, because the shoulders
never rise above the hands.

### The completion timing is systematically early — read this before trusting the 3/3

| Clip | rep 1 | rep 2 | rep 3 | mean |
|---|---|---|---|---|
| `yTczZRbcfJU` ring | −343 ms | −388 ms | −516 ms | **−416 ms** |
| `k71s4T2_CJI` bar | −130 ms | −194 ms | −192 ms | **−172 ms** |

The engine's completion is **re-entry to the below-bar geometric regime**; the frozen label's
completion is **"first frame back in hang with arms extended"**. These are different
definitions, and the offset is systematic and negative on *both* variants.

The kipping-bar `3/3` is therefore **not** evidence of better timing than the ring — it is
partly an artifact of that clip's wider ±0.5 s annotation uncertainty absorbing a −172 ms
error. The ring's ±0.25 s windows do not absorb −416 ms, so the same underlying behaviour
scores 0/3. Counts matched on both clips while timing was wrong on both.

The windows were **not** moved to fix this. Resolving it requires either measuring elbow
extension in the hang (a second observation, as support-versus-lockout already is) or
re-annotating to the regime-crossing definition — both are open decisions, not silent edits.

### Honest limits of this addendum

- Two clips, one gym, one coach for the bar variant, one athlete for the ring. Not
  population evidence, not held-out generalization.
- Labels are Claude reviewer frame-sampled observations, not expert gold.
- Desktop MediaPipe reference runtime only; no Android or device equivalence.
- `AJAVu29c-NU` was extracted-ready but **not** run: same group as `k71s4T2_CJI`, so it
  would add no independent evidence, and its background contains other people.

## Not done

- Kotlin compile and `:shared:jvmTest` (blocked).
- `ReplayMuscleUpPose.kt` JVM entrypoint, Gradle task, and `offline_muscleup_feedback.py`.
  Deliberately deferred: writing a strict CLI parser against an engine that has never
  compiled would be building on unverified behavior.
- Strict **ring** muscle-up: no admitted ring source. Not started.
- `compare_events` scoring: the frozen labels carry no scorable `events[]` windows, by
  design — there is no complete cycle in this source to score against.
- Straight-bar dips, HSPU, handstand (priorities 2–4).

## Addendum 2 — completion definition (v3), and why the gap is NOT what I assumed

I changed the completion rule to "below the apparatus **and arms straight**"
(`hang_lockout_elbow_degrees = 150.0`, matching the existing dand precedent). Adding the
elbow joint cost no availability: 583/583 and 617/617.

**It did not explain the gap.** Mean completion offset moved only:

| Clip | v2 (regime crossing) | v3 (+ arms straight) |
|---|---|---|
| ring `yTczZRbcfJU` | −416 ms | **−371 ms** |
| bar `k71s4T2_CJI` | −172 ms | **−161 ms** |

Arm extension accounts for ~45 ms of a ~400 ms discrepancy. The hypothesis was mostly wrong
and is recorded as such.

**Actual cause, from the raw trace of ring rep 1** (engine fired 7.441 s, reviewer marked
7.75 s): the descent is *continuous*. `shoulder_above_wrist_fraction` crosses the −0.05
threshold at 7.441 s with the elbow already at 164°, but keeps falling and only settles
around −0.13 near 8.0 s. The reviewer's mark sits mid-descent. So the disagreement is about
**where on a continuous trajectory the line is drawn**, not about arm extension.

**Not fixed, deliberately.** Lowering the threshold or adding a settle criterion until the
predictions land inside my own windows would be fitting the detector to its own labels — the
exact practice this project forbids. Closing this properly needs a re-annotation with an
explicit stated rule (e.g. "first frame at which descent stops"), performed blind, and the
±0.25 s uncertainty currently recorded understates the real ambiguity on a slow descent.

v3 is kept because "back below with arms straight" is the more meaningful endpoint for the
tempo and phase-duration measurements that are eventually wanted, not because it improved a
score. v1 and v2 configs and reports are retained unchanged.

**What is solid across v2 and v3, on both variants:** 3 counted cycles each, zero extra
events, toes-to-bar rejected across ~11 s of real following footage, and the ring dismount
correctly left unresolved instead of counted. **What is not solid:** the completion
timestamp, which is systematically 160–370 ms early against the current labels.
