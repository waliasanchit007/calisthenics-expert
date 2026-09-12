# Owner-priority coverage — 2026-09-11

Offline, desktop-reference results for owner priorities 1–4 and 10. Everything here is **real Kotlin
engine output on cached real footage**, scored with the project's own `compare_events`, unless
stated otherwise. Nothing is device-tested, integrated into the app, committed or deployed.

Labels are Claude reviewer observations from raw frames, frozen before each detector run where
stated. They are not expert gold and not biomechanical ground truth.

## Toolchain (previous blocker resolved)

The Gradle 9.3.1 wrapper distribution is blocked by the corporate proxy
(`release-assets.githubusercontent.com` → 502). Resolved without touching the wrapper files:

- `brew install gradle` → Gradle 9.7.1 (Kotlin 2.4.0); it also installed JDK 21.
- Builds run as `gradle -Porg.gradle.java.installations.paths=<jdk21>,<jdk17>
  -Porg.gradle.java.installations.auto-download=false …` with `JAVA_HOME=<jdk21>`.
- **Deviation recorded:** 9.7.1 is not the pinned 9.3.1. `gradle-wrapper.properties` was not
  modified. `./gradlew` and `make verify-harness-*` remain unverified on this host.

**Full shared JVM suite: 824 tests / 90 suites, 0 failures, 0 errors, 0 skipped** (JUnit XML).
Historical baseline was 779 / 86; the +45 are the four new engines' tests. No existing test
changed or failed.

Mutation checks on the Kotlin engines (each restored byte-exactly, verified by SHA-256):
disabling the muscle-up grip gate fails exactly `rejectsFalseSupportWhenTheGripPointMigrates`;
disabling the HSPU inversion gate fails exactly the dip, push-up and dismount rejection tests.

## Engines added (all untracked, uncommitted)

| File | Movement | Tests |
|---|---|---|
| `MuscleUpCycleExperiment.kt` | muscle-up, ring/bar, strict/kipping as recorded variants | 14 |
| `HandstandPushUpExperiment.kt` | HSPU, back-to-wall / wall-facing / freestanding as recorded variants | 11 |
| `HandstandHoldExperiment.kt` | handstand hold intervals, support recorded not inferred | 11 |
| `ToesToBarExperiment.kt` | kipping toes-to-bar (feet reach hand height) | 9 |
| `ReplayMuscleUpPose.kt`, `ReplayHspuPose.kt`, `ReplayHandstandHoldPose.kt`, `ReplayToesToBarPose.kt` | JVM replay entrypoints (strict config keys, no-overwrite output) | — |

No existing engine, threshold or cached prediction was modified. The existing
`DipCycleExperiment` was reused unchanged for straight-bar dips.

Python mirror `reference_muscleup.py` vs Kotlin: **0 diverging frames across 1,231 real frames**
on all three muscle-up caches — after fixing a mirror bug (below).

## Results

### 1. Muscle-ups — strict ring and kipping bar kept separate

| Clip | Variant | Counted | Unresolved | matched / missed / extra |
|---|---|---|---|---|
| `yTczZRbcfJU` | strict ring | 3 of 3 | 1 (dismount) | 0 / 3 / 3 |
| `k71s4T2_CJI` | kipping bar | 3 of 3 | 0 | 3 / 0 / 0 |
| Commons GIF | bar, cropped | 0 | 1 | unscored — no complete cycle exists |

Toes-to-bar following the bar reps produced **zero** extra events over ~11 s of real footage.

### 2. Straight-bar dips — existing parallel-bar engine, unchanged

Source `XTDc47D1G_I` (garden rig, handheld, edited). Six reps labelled across three uncut
segments, frozen before scoring (v1 for segment A, additive v2 for B and C).

| Rep | Labelled top | Engine | Result |
|---|---|---|---|
| A1 | 3.50 | 3.60 | match |
| A2 | 6.00 | 6.28 | 30 ms outside window |
| B1 | 32.65 | 32.76 | match |
| C1 | 40.82 | 41.20 | 130 ms outside window |
| C2 | 43.82 | 44.12 | 50 ms outside window |
| C3 | 46.57 | — | missed (0.15 s before a cut) |

**5 of 6 reps detected, no false reps; 2 of 6 inside the strict ±0.25 s windows.**

- The camera changed sides between cuts; segment C was 0/211 usable on LEFT and 207/211 on
  RIGHT. Side was therefore selected by availability **per uncut segment**, a decision made
  after C failed on LEFT (the rule is availability-only, never geometry or counts).
- The engine is not cut-aware. Only labelled uncut segments are scored.
- Torso tilt was 2–13° from this angle, so the parallel-bar 45° tilt gate never came close to
  rejecting the straight-bar forward lean. This is one angle, not a general finding.
- Title says straight bar; perspective prevents fully excluding parallel bars.

### 3. HSPU — wall orientations kept separate

| Clip | Variant | Counted | matched / missed / extra |
|---|---|---|---|
| `cLTvfDlhiXM` | back-to-wall | **5 of 5** | 1 / 4 / 4 |
| `tz_YVynl1sk` | wall-facing | **5 of 5** | 0 / 4 / 5 |

One `tz_YVynl1sk` "extra" (9.78 s) is a correct rep whose top endpoint was never labelled —
a label gap, not a false positive. Kick-up and dismount were never counted.

Depth is measured as shoulder-to-wrist span, not elbow angle: on `cLTvfDlhiXM` the elbow angle
did not separate the endpoints at all (bottoms 154–169°, tops 163–179°).

### 4. Handstand holds — intervals, not repetitions

| Clip | Support (recorded) | Longest interval | Reviewer | Entry / exit error |
|---|---|---|---|---|
| `xmrayUHhBgQ` | **UNKNOWN** (wall contact unresolved) | 2.70 → 32.20 s | 2.7 → 31.3 s | 0.00 / +0.90 s |
| `btUWnzTI8yk` | wall-supported | 14.73 → 51.67 s | 15.5 → 52.9 s | −0.77 / −1.23 s |

`btUWnzTI8yk` yields four fragments (7.40–9.63, 10.73–14.43, 14.73–51.67, 51.77–52.97). The
first two end on `LOW_CONFIDENCE` exactly at the camera repositions measured on 2026-09-10.
**Fragments are never stitched.** The 7.40 s entry corroborates the annotation note that the
hold began ~7 s but was left-censored by the camera moves.

### 10. Toes-to-bar — designed on one recording, tested once on a second

Both recordings are the same coach and gym, so the second is a separate recording, not an
independent athlete. Labels for the held-out recording were frozen before the engine existed.
Each clip's muscle-up block serves as a zero-toes-to-bar negative control.

| Engine | Clip | Labelled | matched / missed / extra | Events in muscle-up block |
|---|---|---|---|---|
| existing hanging-leg-raise, unchanged | `k71s4T2_CJI` (design) | 7 | 4 / 3 / 0 | 0 |
| **new** `ToesToBarExperiment` | `k71s4T2_CJI` (design) | 7 | 5 / 2 / 1 | 0 |
| existing hanging-leg-raise, unchanged | `AJAVu29c-NU` (held-out) | 10 | 1 / 9 / 0 | 0 |
| **new** `ToesToBarExperiment` | `AJAVu29c-NU` (held-out) | 10 | **0 / 10 / 0** | 0 |

- Hanging-leg-raise misses come from its arming rule: it needs a sustained legs-down dwell
  (hip ≥150° for 3 frames) before each raise; kipping never provides one. Correct for a strict
  hanging leg raise, wrong model for kipping toes-to-bar.
- The new engine measures what defines the movement: both feet reaching hand height, in torso
  lengths. On the design clip touches measured 0.21–0.34 torso lengths below the hands and the
  muscle-ups never came closer than 1.37.
- **Held-out failure cause:** the new engine requires BOTH ankles. In the held-out wide shot the
  far ankle is usable in only 86 of 420 frames (a rig upright passes in front of the athlete);
  the near ankle is usable in 349. The design choice that was correct on the design clip did
  not transfer. **Not tuned on the held-out clip.** "Near-side ankle when the far ankle is
  unavailable" is recorded as a v2 hypothesis that needs a fresh recording to test.
- **Toes-to-bar is not closed.** No engine produced a false event anywhere.

## The one systematic issue: completion timing

Counts are right on every muscle-up and HSPU clip (dips: 5 of 6; toes-to-bar: see above), but
completion timestamps disagree with my labels in a consistent, engine-dependent direction:

| Engine | Direction | Magnitude |
|---|---|---|
| Muscle-up (new) | early | 161–371 ms mean |
| HSPU (new) | early | 346–470 ms mean |
| Dip (existing) | late | 30–130 ms |

Each engine fires when a threshold is crossed; I labelled the fully-settled endpoint. On the
ring muscle-up the descent is continuous, so there is no sharp endpoint to mark. **This is a
labelling-convention question, not four separate bugs.** Windows were not moved to hide it.
Closing it needs a blind re-annotation to an explicit rule (e.g. "first frame the descent
stops") with uncertainty that reflects slow endpoints.

## Corrections to earlier reports

1. **Mirror bug, found by the Kotlin cross-check.** When the mirror's joint tuple was widened to
   include the elbow, its reference code kept reading index `[1]` and anchored the grip on the
   *elbow*. Kotlin was correct. Timing numbers were unaffected; the ring's `unresolved` value
   was (reported 0, actually 1 — the dismount).
2. **Existing "dip" evidence is parallel-bar, not straight-bar.** The wger CC-BY-SA dip corpus
   shows dip-station handles. Straight-bar dips had no real positive before today.
3. **Several 2026-09-10 tutorial downloads are truncated.** `HqTAwkzqL-g`, `lQZD-fauVmU`,
   `XTDc47D1G_I` and others decode only ~61 s despite containers reporting 230–437 s. Their
   "long uncut segments" in the 09-10 triage were artifacts of the truncation.
4. **HSPU side selection.** A pre-check ran `tz_YVynl1sk` on RIGHT; the availability rule selects
   LEFT (586 vs 561). The Kotlin replay uses LEFT; counts were identical (5 of 5).
5. **HSPU reference window** (15 frames, spread ≤4 %) was chosen after a 5-frame window failed on
   the kick-up. Non-blind, disclosed; the underlying rule ("a settled inversion") was the same
   lesson as the muscle-up mount defect.

## Provenance

YouTube clips are used under the owner's 2026-09-11 authorization for **internal assessment
only**: no redistribution, no other reuse, no upload, outside Git. Standard YouTube licence,
**not** Creative Commons; retrieval was contrary to YouTube ToS. No re-downloads and no repeat
of the 2026-09-10 acquisition workaround. Local SHA-256s were verified against the frozen
manifest before use. The Commons GIF is public domain; the wger dips are CC-BY-SA 4.0.

Artifacts (outside Git): `offline-corpus/{muscle-up,hspu-handstand,straight-bar-dip}-20260911/`
and `offline-corpus/kotlin-replay-20260911-v1/` (receipts, predictions, hash-bound summary).

## Not done / blocked

- **Priorities 5–7 and 9** (tuck, straddle/full planche, back lever, tuck/front lever): no
  video exists in the corpus; only licensed stills. Hold timing cannot come from stills.
- **Priority 8** (hanging straight-leg raises): no new footage.
- **Priority 10** (toes-to-bar): see above — needs a fresh recording to test the v2 hypothesis.
- **Priorities 11–13** (wall sit, L-sit, hanging knee raise): not attempted; no footage in the corpus.
- **Strict bar muscle-up, freestanding HSPU:** no admitted real footage.
- One athlete / one gym per variant almost everywhere. Not population evidence, not held-out.
- No device run. An SM-A125F (Galaxy A12) is attached over Wi-Fi ADB; it was only listed
  read-only. It differs from the owner's S25 Ultra and is reserved for later integration checks.

---

# Part 2 — new Creative Commons corpus, and a correction to my own annotation timebase

## Correction: contact-sheet timestamps were systematically early

Every label I wrote today, and the 2026-09-10 annotations, came from contact sheets built with
`ffmpeg -ss S -t D -i f -vf fps=F`, assuming tile *i* shows time `S + i/F`. **That assumption is
wrong.** The frame shown is LATER than the nominal time. Measured by pixel-matching sheet frames
against natively decoded frames carrying true PTS:

| sheet rate | measured offset |
|---|---|
| F = 4 | **+0.10 to +0.11 s** |
| F = 3 | **+0.13 to +0.16 s** |
| F = 2 | **+0.24 s** |

Found because an independent annotator cross-checked against a burned-in stopwatch in one clip.
Tool: `.superpowers/sdd/2026-09-11-muscle-up/sheet_offset.py`.

Corrected label versions were written additively (`*-v2-timebase.json`, dips `-v3-timebase.json`);
v1 files are unchanged. This corrects the annotation TIMEBASE only — no judgement about what
happens in any video was revised.

### Effect: counts unchanged, endpoint timing worse, window matching unstable

| Clip | detected / labelled | offset vs corrected label centre | match ±0.25 s | ±0.35 s | ±0.5 s |
|---|---|---|---|---|---|
| MU ring | 3 / 3 | −482 ms | 0/3 | 0/3 | 2/3 |
| MU bar | 3 / 3 | −397 ms | 0/3 | 1/3 | 3/3 |
| HSPU back-to-wall | 5 / 5 | −484 ms | 1/5 | 1/5 | 2/5 |
| HSPU wall-facing | 5 / 4 | (pairing unreliable — one label gap) | 0/4 | 0/4 | 0/4 |
| Toes-to-bar (design) | 6 / 7 | −262 ms | 2/7 | 5/7 | 6/7 |
| Straight-bar dips | 5 / 6 | **+129 ms** | 4/6 | 5/6 | 5/6 |

Three things follow, and the second is uncomfortable:

1. **Detection counts did not move at all.** How many repetitions each engine finds is robust to
   this correction. That is the durable result.
2. **The ±0.25 s windows are narrower than the real annotation uncertainty.** Toes-to-bar went
   from 5/7 to 2/7 and dips from 2/6 to 4/6 purely from a ~0.1 s timebase shift, because events sat
   within ~0.1 s of window edges. Earlier window-match numbers on both sides were partly luck, and
   my previous "5 of 7" for toes-to-bar should not be trusted as a stable score.
3. The systematic earliness of the new engines is now **larger**, not smaller: roughly −0.4 to
   −0.5 s for muscle-up and HSPU. The dip engine remains late, now +0.13 s.

Windows were not re-widened to improve any score. The honest conclusion is that completion-time
evaluation needs a blind re-annotation with an explicit endpoint rule and an uncertainty that
reflects slow endpoints; until then, report counts, not window-match rates.

## New corpus: 15 Creative Commons clips

Fetched with `fetch_youtube.py` (normal yt-dlp, node JS runtime, corporate CA bundle). No bot
block was encountered and no workaround was used; the script is written to STOP if YouTube asks
for sign-in. 14 usable, 1 unavailable on YouTube. **All 14 report a Creative Commons licence** —
better provenance than the standard-licence clips used earlier — and all decode fully (the
truncation problem from 2026-09-10 did not recur). Owner authorization (2026-09-11) still applies:
internal assessment only, outside Git.

**Correction to my own tooling:** `acquisition.json` initially recorded YouTube's *best available*
format rather than the format actually downloaded (e.g. "1080p" for a 640p file). Caught by an
annotator comparing against the local file. All 14 records corrected from `ffprobe`; the original
claim is retained as `format_reported_by_youtube_metadata`.

## Results on the new corpus

No new engine was needed for planche or the levers: the existing `StaticSkillObservation` supplies
stillness/interval/censoring, and a new posture-admission layer (`PostureAdmission.kt`, 10 tests)
supplies the shape rule for L-sit and wall sit. Full suite **834 tests / 91 suites, 0 failures**.

| Priority | Clip | Outcome |
|---|---|---|
| 11 wall sit | `y6RrKS76SAg` | **Detected.** Labelled hold 7.60→29.20 s; exit error **−0.08 s**. Split into 3 fragments (ends `MOVING`) — fragments are never stitched, so entry error against the longest fragment is +11.0 s. |
| 9 front lever | `RTg8r4EV0Pc` | **Failed, cause identified.** Posture admitted on only 152/3384 frames: the camera sits on the floor pointing steeply up, so the body never reads as near-horizontal. All 5 labelled holds missed. The clip is a camera-angle failure, not an engine-logic failure. |
| 12 L-sit | `W92o2JmiTNI` | **Unusable, correctly flagged by the annotator**: from an oblique view it cannot be confirmed the hips leave the floor, so it may be ordinary sitting. Not scored as success. |

The front-lever annotator also found **cuts my detector missed** (hard cuts at 7.1, 48.8, 71.6 s
plus several cross-dissolves), and restricted labels to three verified uncut segments.

## Still pending

Six clips are downloaded, pose-extracted and config-frozen but **not yet labelled**: tuck planche,
planche, back lever, hanging straight-leg raise, hanging knee raise, and the new-athlete
toes-to-bar. Two labelling agents were terminated by a session usage limit (not a service outage).
Their configs were frozen before any labels existed, so blindness is preserved for whenever the
labelling happens.

## Toes-to-bar, continued: a real generalisation fix, and a label error the engine caught

### Split-screen discovery

`wZ6bjrOjbCI` and `C0RXOvy609I` (same channel) are **split-screen**: the same athlete is shown
twice in one frame (front view left, side view right). A single-pose extractor cannot be trusted
on such a frame. Side-view half derivatives were created (`crop=W/2:H:W/2:0`, libx264 crf16, PTS
passthrough), with parent hash and crop recorded in each source record, and re-extracted.

### The both-ankle rule failed on every side view, and is now configurable

v1 required both ankles. Far-ankle availability, measured:

| recording | far ankle | near ankle |
|---|---|---|
| `AJAVu29c-NU` | 86 / 420 | 349 / 420 |
| `wZ6bjrOjbCI` (new athlete, side view) | 116 / 1051 | 882 / 1051 |

Both produced **zero** detections. The rule is wrong for side-view filming, where the far leg is
routinely occluded — that is a property of the camera, not of one clip. `requireBothAnkles` is now
an explicit config key; with it false the lower of whichever ankles are observable is used. **The
cost is stated and tested**: a one-legged kick on the observable side can then count
(`withoutRequireBothAnklesASingleLegKickCanCount`).

**This change is NON-BLIND** — it was motivated by failures on two clips that were meant to be
held out. It is a fix to a measurement-availability defect, not a threshold tuned to a score, but
it does mean those two clips are no longer clean held-out evidence for toes-to-bar.

| recording | detected (both-ankle) | detected (observable-ankle) | labelled |
|---|---|---|---|
| `k71s4T2_CJI` (design) | 6 | **7** | 7 |
| `AJAVu29c-NU` | **0** | 5 | 10 |
| `wZ6bjrOjbCI` (new athlete) | **0** | 6 | 7 (corrected) |

### The engine was right and my labels were wrong

On `wZ6bjrOjbCI` the engine reported the feet never returning below hand height between 24.0 and
31.7 s. That is impossible for repeated repetitions, so I re-inspected the raw frames: **the
athlete holds the toes-to-bar position continuously for ~7.7 s.** My v1 labels had invented four
separate repetitions inside that hold. Labels corrected to 7 repetitions plus one sustained hold
(v2, v1 preserved, correction disclosed).

Two lessons worth keeping:
- A detector disagreeing with a label is a reason to re-examine the label, not only the detector.
- A toes-to-bar engine that only counts repetitions mis-describes this clip. The movement needs a
  hold representation as well as a rep representation — the same distinction already made for
  handstands.

**Toes-to-bar remains open**: best case 6 detected of 7 labelled on the new athlete, timing still
loose, and no clean held-out recording is left. A fresh recording is needed to re-establish one.

## Holds: shape recognition works, interval stability does not

| Priority | Clip | Shape admitted | Hold reported |
|---|---|---|---|
| 7 back lever | `raAQB7ad24s` | **111 / 111 frames inside the labelled hold** | 4 fragments (0.33–0.67 s) instead of one 4.67 s hold |
| 11 wall sit | `y6RrKS76SAg` | 574 / 752 overall | 3 fragments; exit error −0.08 s |
| 9 front lever | `RTg8r4EV0Pc` | 152 / 3384 | none — camera angle, see above |

The back lever is the clearest signal so far that **posture admission is sound**: every single
frame of the labelled hold was admitted as a horizontal body line, using the existing plank
measure unchanged. The clip fades in with the lever already held and fades out still held, so both
endpoints are censored and no entry/exit accuracy can be claimed from it.

What fails is interval stability. Measured torso-point speed inside each labelled hold
(threshold 0.30 torso-lengths/s):

| clip | median | p90 | max | frames over threshold |
|---|---|---|---|---|
| wall sit | 0.05 | 0.16 | 0.71 | **6 / 540** |
| back lever | 0.21 | 0.40 | 0.66 | 29 / 110 |

These are two different failures:

- **Wall sit is brittleness.** The athlete is almost perfectly still, but six isolated
  single-frame spikes each close the interval, because any one frame over threshold ends a hold.
- **Back lever is genuine near-threshold motion** (or landmark jitter against a cluttered
  background) sustained throughout — a different problem, not fixed by the same change.

**Clearly-specified next step (not implemented tonight, deliberately):** require N consecutive
over-threshold frames before closing a hold, mirroring `minimumEndpointSamples` used elsewhere in
this codebase. That is a robustness rule of the same family the project already uses, not a
threshold fitted to a score — but it changes published numbers, so it should be added with its own
tests and re-run cleanly rather than at the end of a long session.

### Tuck planche candidate (priority 5)

`2U8zGzbpJ84`. Shape admitted on 335/481 frames inside the labelled hold (146 rejected as
`BODY_LINE_NOT_ADMITTED`); 9 fragments, longest 3.93 s against a labelled 16 s hold.

**The variant is unresolved and the clip may be a negative.** At this resolution a tuck planche
cannot be separated from a frog stand (knees resting on the upper arms). If it is a frog stand, the
clip is a NEGATIVE for tuck planche, not a positive, and nothing here should be read as tuck-planche
coverage. Pose data was used only to aim the crop, never to decide the label.

## Where the new corpus leaves each priority

| # | Movement | State after tonight |
|---|---|---|
| 5 | tuck planche | Clip labelled; **variant unresolved** (possible frog stand). Hold fragmented. Not closed. |
| 6 | planche | Downloaded, extracted, config frozen; **not yet labelled**. |
| 7 | back lever | Shape gate perfect (111/111); hold fragmented; both endpoints censored. Not closed. |
| 8 | hanging straight-leg raise | Downloaded, extracted, config frozen; **not yet labelled**. Known risk: the clip is a FRONT view and the engine measures side-view hip/knee geometry. |
| 9 | front lever | **Failed** — camera angle (152/3384 frames admitted). |
| 10 | toes-to-bar | Improved from 0 to 6-of-7 on a new athlete after the ankle fix; label error found and corrected; no clean held-out recording left. Not closed. |
| 11 | wall sit | **Detected**, exit error −0.08 s; 3 fragments from 6 spike frames. Closest to usable of all the holds. |
| 12 | L-sit | Clip unusable (hip clearance unverifiable). A second candidate (`84WplmAbszc`) is downloaded but not screened. |
| 13 | hanging knee raise | Split-screen; side-view derivative made and extracted; **not yet labelled**. |

---

# Part 3 — hold fragmentation fixed, and the last three clips closed

Everything below was run after Part 2, with the same discipline: parameter values declared in the
ledger **before** any re-run, N never re-tuned against the results, and every pre-change output
reproduced byte-for-byte before anything new was published.

## The fix: require persistence before closing a hold

Part 2 specified the next step and deliberately did not implement it. It is implemented now, as a
required config key in two places:

- `StaticSkillConfig.minimumMovingSamplesToClose` — consecutive over-speed frames before a still
  hold is closed;
- `HandstandHoldConfig.minimumNonHoldingSamplesToClose` — consecutive non-holding frames before a
  handstand hold is closed, and it absorbs **only** the three geometric non-holding reasons. A pose
  failure, capture gap or timestamp fault still breaks the hold on the first frame: an unmeasurable
  frame is not evidence that the hold continued.

**N = 3 was declared in advance** (0.10–0.125 s at the corpus's 24–30 fps), on the Part 2
measurements alone: the wall-sit spikes were isolated single frames, real exits in this corpus last
~0.5 s, and the rule mirrors `minimumEndpointSamples`. The speed threshold itself is unchanged.

### A second defect the first re-run exposed

The first N=3 run made things *worse* on one clip and ended merged holds with `CAPTURE_GAP`. Cause:
the capture-gap check keyed off `lastGoodNs`, which the debouncer deliberately holds back. With
`maximum_gap_ns` at 100 ms and clips at 24–30 fps, absorbing two transients made the *next on-time
frame* look like a dropout. Capture continuity and stillness are now tracked separately
(`lastAliveNs` vs `lastGoodNs`); a genuine dropout is still `CAPTURE_GAP`. This is covered by its
own regression test.

### Regression evidence

- `ReplayStaticSkillPose` is pinned to N=1 and reproduces its pre-change output **byte-identically**
  on `y6RrKS76SAg` and `raAQB7ad24s`.
- N=1 posture-hold configs reproduce **all five** pre-change `*-hold-v1.jsonl` files byte-identically.
- N=1 handstand configs reproduce **both** pre-change outputs byte-identically.
- Suite **843 tests / 91 suites, 0 failures, 0 errors, 0 skipped** (JUnit XML).
- Four mutation checks (two per engine) each fail exactly the intended tests; both files restored
  byte-exactly, verified by SHA-256.

### Results, before vs after

| Clip | Movement | N=1 fragments / longest | N=3 fragments / longest | Against label |
|---|---|---|---|---|
| `y6RrKS76SAg` | wall sit | 3 / 10.52 s | **1 / 21.52 s** | 7.60–29.12 vs 7.6–29.2 → entry 0.00 s, exit −0.08 s |
| `W92o2JmiTNI` | L-sit candidate | 9 / 1.63 s | **1 / 9.41 s** | 0.20–9.61 vs 0.17–9.68 (clip itself remains unusable) |
| `raAQB7ad24s` | back lever | 4 / 0.67 s | 2 / 4.00 s | longest 6.58–10.58 vs 5.667–10.333 |
| `2U8zGzbpJ84` | tuck planche | 9 / 3.93 s | 6 / 4.60 s | limited by **admission** (337/604), not stillness |
| `RTg8r4EV0Pc` | front lever | 0 | 0 | unchanged; 152/3384 admitted, camera angle |
| `I9wbS757cvM` | planche | 15 / 3.07 s | 18 / 4.64 s | see below — covered time 13.2 s → 20.7 s |
| `xmrayUHhBgQ` | handstand | 1 / 29.50 s | **1 / 29.50 s** (unchanged) | entry 0.00 s, exit +0.90 s |
| `btUWnzTI8yk` | handstand | 4 / 36.93 s | **3 / 38.30 s** | exit error **−1.23 s → +0.13 s** |

Read honestly:

- **The wall-sit brittleness is fixed**, exactly as predicted from the 6/540 measurement.
- **The back lever is not fixed to one interval, also as predicted** — 29/110 frames genuinely
  exceed the gate there. Persistence is the wrong tool for that and it was not re-tuned to force a
  merge.
- **Tuck planche and front lever are limited by `PostureAdmission`, not by stillness.** Separate
  problem, left separate.
- **`btUWnzTI8yk` improved only where it should**: the 1–2 frame blip inside a steady hold merged;
  the two early fragments, separated by 32–222 frames of `LOW_CONFIDENCE` during camera
  repositioning, were correctly left apart.
- **The planche clip gains fragments while nearly doubling covered time.** Candidates that used to
  die under `minimum_still_ns` now survive long enough to be reportable. Most of the extra intervals
  fall inside spans I have now labelled as negatives (see below), where the body really does pass
  through horizontal.

## The three remaining clips

### Priority 8 — hanging straight-leg raise, FRONT view (`TcU7wtLdIx4`): a clean diagnostic zero

Blind: labels and config were both frozen before this run. Result **0 / 6**, as predicted.

This is not a blind spot. The engine opens **exactly one candidate cycle per labelled repetition**
(departures at 0.88, 3.75, 6.67, 9.55, 12.64, 15.43, 18.27 s — the six reps plus the masked opening
rep) and rejects every one, with `KNEE_FLEXION_REJECTED` or `ABANDONED`. Two independent causes:

1. **Foreshortening.** The shin points at the camera, so projected knee angle drops below the
   140° gate even with a straight leg. The hip angle *does* fall to 0.1–5° at each top, but that is
   projective degeneracy, not flexion — from the front the raised limb projects onto the hip.
2. **Availability.** 156 / 520 frames are `LOW_CONFIDENCE`, which repeatedly breaks the
   three-consecutive-sample endpoint requirement.

**Conclusion: side-view geometry cannot be rescued by thresholds on a frontal recording.** This clip
is kept as a documented negative and must not be tuned for.

### Priority 13 — hanging knee raise, side panel (`C0RXOvy609I-right-half`): 6 / 6, zero false positives

Labels **derived, not re-annotated**: the parent's labels were timed off this very side panel before
any detector run, and the crop is spatial with PTS passthrough, so the reviewed pixels and the
timestamps carry over unchanged. The file records this in `label_provenance` and repeats the
parent's method verbatim. `side = LEFT` was frozen on joint availability alone (LEFT 919 vs RIGHT
592 usable frames) before running.

**6 labelled, 6 detected, one per repetition, zero false positives across 53.7 s.** Window match is
0/6 only because every detection lands +0.28 to +0.48 s after the labelled return, just outside
±0.25 s — the same systematic lateness Part 2 documented. Counts are the metric to trust here.

One process note: `compare_events` refuses labels whose `annotation_status` is not
`visually_reviewed`. That gate was **not** weakened; the derivative keeps the status honestly,
because the reviewed frames are literally these frames, and carries the derivation in its own field.

### Priority 6 — planche (`I9wbS757cvM`): labelled, but the labels are NOT blind

**Disclosure, recorded in the label file itself:** before writing these labels I had already seen
this clip's N=1/N=3 interval boundaries while diagnosing fragmentation. The labels were read off raw
frames, but that exposure cannot be undone. **Development material only — never held-out evidence.**

The clip is a **seven-shot tutorial montage**, not a single take (hard cut at 6.540 s; further cuts
at 21.855, 27.761, 48.615, 52.953, 64.264 s). Labelled:

- **Three planche holds, every one right-censored by a cut** — 1.95–6.54, 24.00–27.76 (parallettes)
  and 50.75–52.95 s (hex dumbbells). All durations are lower bounds; no exit is ever observable.
- **Three negative spans** — two press-to-handstand drills that pass *through* horizontal and then
  hold a **handstand**, and 11.6 s of the athlete sitting on the floor talking.
- **Planche level is unresolved**: the hips sit visibly above the shoulders in all three holds, so
  full vs straddle-adjacent cannot be decided from this single side view.

Scored against those labels: hold 1 went from **4 fragments with a +2.05 s late entry (N=1) to one
fragment with a −0.08 s entry (N=3)**; hold 2 from 3 fragments to 2 (entry +0.66 s); hold 3 from 1
fragment to 2 (entry +0.57 s) — **slightly worse, and recorded as worse**. Of the intervals outside
labelled holds, 6 of 7 (N=1) and 10 of 13 (N=3) fall inside the labelled negative spans; the rest
are the pike/tuck phases immediately before each entry.

## Where this leaves each priority now

| # | Movement | State |
|---|---|---|
| 5 | tuck planche | Variant still unresolved (possible frog stand). Fragments 9 → 6; the limit is admission, not stillness. Not closed. |
| 6 | planche | **Labelled (non-blind, disclosed).** Three right-censored holds + three negative spans. Hold 1 now detected as a single interval with a −0.08 s entry. Development material only. |
| 7 | back lever | Shape gate perfect (111/111). Fragments 4 → 2, longest 0.67 s → 4.00 s. Genuine near-threshold motion remains. Not closed. |
| 8 | hanging straight-leg raise | **Closed as a documented negative**: front view, 0/6, with the rejection mechanism identified per repetition. |
| 9 | front lever | Still failed — camera angle. Needs side-on footage; I cannot fix this. |
| 10 | toes-to-bar | Unchanged from Part 2. No clean held-out recording left. Not closed. |
| 11 | wall sit | **Closed**: one interval, entry 0.00 s, exit −0.08 s. |
| 12 | L-sit | Clip still unusable (hip clearance unverifiable), though it is now one clean interval. `84WplmAbszc` remains unscreened. |
| 13 | hanging knee raise | **Closed on counts**: 6/6, zero false positives over 53.7 s, with the known +0.3–0.5 s lateness. |
| — | handstand hold | `xmrayUHhBgQ` unchanged (exit +0.90 s); `btUWnzTI8yk` exit error −1.23 s → **+0.13 s**. |

Nothing here is biomechanical ground truth. These are reviewer annotations and candidate-cycle
detections on desktop replays, and the label files carry their own censoring, uncertainty and
blindness caveats.

---

# Part 4 — the hold gate measures pose jitter, not stillness (2026-09-12)

## What was actually wrong

Part 3 left the back lever as "genuine near-threshold motion ... a different problem". That reading
was wrong, and the correction matters more than the fix.

The gate is `max over the four torso landmarks of |p_t - p_{t-1}| / torsoLength / dt`, taken between
**consecutive frames**. That multiplies displacement by the frame rate, so a fixed spatial landmark
jitter produces a speed proportional to fps. Measured over the whole 4.67 s back-lever hold, the
torso centroid moves **0.105 torso-lengths horizontally and 0.075 vertically, net**, with a standard
deviation of 0.026/0.019. The athlete is very nearly motionless. The gate nonetheless reported a
median of 0.209 and put 29 of 110 frames over 0.30.

A camera hypothesis was tested first and refuted: measured from background pixels, the camera term is
median 0.000 and max 0.087 torso-lengths/s. The `labels-v1` description "static side view" is
correct.

Across all six frozen hold clips the gate's median tracks landmark jitter, not athlete motion:

| clip | movement | fps | torso px | jitter (torso-lengths) | gate median | over/n |
|---|---|---|---|---|---|---|
| y6RrKS76SAg | wall sit | 25.00 | 138.8 | 0.0006 | 0.053 | 6/540 |
| 2U8zGzbpJ84 | tuck planche | 30.00 | 118.3 | 0.0005 | 0.066 | 26/480 |
| I9wbS757cvM | planche | 29.97 | 204.2 | 0.0008 | 0.126 | 8/137 |
| W92o2JmiTNI | l-sit candidate | 29.97 | 143.6 | 0.0012 | 0.128 | 17/284 |
| raAQB7ad24s | back lever | 24.00 | 310.8 | 0.0029 | 0.210 | 29/110 |
| RTg8r4EV0Pc | front lever | 24.00 | 76.0 | 0.0022 | 0.331 | 34/60 |

Two structural consequences of the one-frame form, independent of any threshold:

1. **It is not frame-rate invariant.** The same athlete holding equally still scores differently at
   24 and 60 fps, so `0.30 torso-lengths/s` does not mean the same thing on any two clips.
2. **It is not pose-quality invariant.** Background clutter alone triples it with no change in the
   athlete — consistent with the back-lever labels' own note about a bicycle, a fence and
   overlapping far-side limbs.

This explains the wall-sit brittleness and the back-lever fragmentation with one mechanism, and it
explains why persistence fixed the first (isolated spikes) and not the second (sustained jitter).

## The change, and what it cost

`motionBaselineNs`: measure against the most recent valid frame at least W back, divided by the
actual elapsed time. W = 0.25 s, declared in the ledger with its predictions before the run. The
threshold itself is unchanged. Only the measurement reaches back — coverage, candidate start and
capture continuity still key off the immediately previous frame — so at W = 0 the engine is
byte-identical, which is proved rather than asserted (below).

| clip | N=3, W=0 frags / covered | N=3, W=0.25 frags / covered |
|---|---|---|
| raAQB7ad24s back lever | 2 / 3.04 s | **1 / 4.96 s** |
| y6RrKS76SAg wall sit | 1 / 21.24 s | 1 / 21.56 s |
| W92o2JmiTNI l-sit cand. | 1 / 8.41 s | 1 / 9.34 s |
| 2U8zGzbpJ84 tuck planche | 6 / 10.00 s | 5 / 10.53 s |
| I9wbS757cvM planche | 18 / 16.48 s | 18 / 22.69 s |
| RTg8r4EV0Pc front lever | 0 / 0 s | 0 / 0 s |

The back lever becomes **one interval, 5.62-10.58 s, covering 4.96 s** against a labelled 4.666 s
hold. Both of that clip's endpoints are censored, so no entry or exit accuracy is claimed from it.

**The cost was pre-registered and came in smaller than predicted.** On the wall sit, the only clip
with both endpoints observed and a real exit, entry moved 7.60 -> 7.68 s (+0.08) and the exit error
moved from **-0.08 s to +0.04 s** — the absolute error got smaller, and both stay inside the label's
own 0.2 s uncertainty. The prediction of +0.10..+0.25 s over-estimated it because the baseline bounds
the worst-case latency, not the typical case: a genuine exit crosses the gate long before 0.25 s has
elapsed.

Verification, per the project's standing rule for engine changes: 874 tests / 94 suites, 0 failures;
`n1b0` reproduces every pre-change `*-hold-v1.jsonl` byte-for-byte and `n3b0` reproduces the adopted
`hold-persistence-20260911-v2` output byte-for-byte on all six clips; four mutants killed with the
source restored to a matching sha256. Two of the new tests were initially vacuous — they used uniform
motion, which the one-frame and fixed-baseline measures score identically — and the mutation check is
what exposed that. They were rewritten around jitter.

## Admission, split for the first time

`admitPosture` collapses every cause to `BODY_LINE_NOT_ADMITTED`, so which gate rejects could not be
read off any existing output. Mirroring `measurePlankBodyLine` offline splits it. On all three
HORIZONTAL_BODY clips the frozen configs set the straight band to 0-180 degrees, so **straightness
cannot reject anything**; admission is the plank measurement alone.

| clip | rejected inside labelled holds | cause | shoulder-ankle angle from horizontal (gate 30 deg) |
|---|---|---|---|
| front lever | 184/185 | UNSUPPORTED_ORIENTATION 178 | min 29.8, median **53.3**, max 89.9 |
| tuck planche | 146/481 | UNSUPPORTED_ORIENTATION 146 | min 20.7, median **27.1**, max 32.9 |
| back lever | 0/111 | all admitted | min 5.3, median 9.3, max 18.1 |

Front lever is not a threshold problem: at a median of 53 degrees the body line reads as nearly
vertical, and a gate loose enough to admit it would admit a standing person. Tuck planche straddles
the gate, but the more useful point is that HORIZONTAL_BODY measures shoulder->ankle, and in a tuck
the ankle sits under the hips, so the vector describes the tuck rather than the hold. The back lever,
extended, clears the identical gate at a median of 9.3 degrees. Neither was changed: both would move
published numbers across movements.

---

# Part 5 — the L-sit gate admits ordinary sitting (2026-09-12)

`84WplmAbszc` was screened, labelled from raw frames with a criterion declared before the endpoints
were read, and run against a config frozen beforehand. Pose was detected on 256/256 frames inside the
labelled hold and the posture gate admitted every one of them. The engine still reported four
fragments rather than one, and one of those four is the athlete sitting on the floor.

**The L_SIT branch of `admitPosture` has no clearance test.** It measures hip angle, knee angle, leg
angle from horizontal, trunk angle from vertical, and wrist-below-shoulder. None of those separates
an L-sit from sitting on the floor with the legs straight out, because the two postures have the same
joint angles and differ only in elevation.

| t (s) | what the athlete is doing | hip° (gate 60–125) | knee° (gate ≥145) | admitted |
|---|---|---|---|---|
| 5.00–5.72 | sitting on the floor, legs flat on it | 89–95 | 172–180 | **yes, 19/19** |
| 7.00–7.40 | a real L-sit | 66–69 | 158–161 | yes |
| 16.20–16.80 | sitting again after the drop | 74–77 | 146–153 | **yes, 16/16** |

Note the direction: plain sitting reads *closer to an ideal L-sit* (hip ≈ 90°, knee ≈ 178°) than the
real hold does (hip ≈ 67°, knee ≈ 160°). So no tightening of these bands can separate them — it would
reject the genuine hold first. A clearance measurement is required, not a better threshold.

This also re-explains an earlier entry. `W92o2JmiTNI` was recorded as "hip clearance unverifiable",
attributed to the clip. The gate never verified clearance on any clip, so that attribution was wrong,
and its 1-interval / 9.34 s result must not be read as an L-sit detection. Nothing published is
invalidated: that clip is already marked `usable_for_evaluation: false`.

**Priority 12 is not closed and its blocker has moved off footage.** `84WplmAbszc` is a good
recording — side view, hip clearance plainly visible to a human reviewer, overlays clear of the
athlete, pose on 100% of the hold. What is missing is a clearance measurement in `PostureAdmission`.
That is a contract change affecting published numbers and is left for the owner. A starting point,
written down as a candidate and not as an adopted design: hip height above the supporting wrist, or
above the ankle line, expressed in torso lengths.

Separately, the entry and exit errors are +0.40 s and −0.32 s — opposite signs, and both definitional
rather than lag. The label marks where the *shape* is first achieved and last held; the engine marks
where *motion* settles and resumes. The lift visibly overshoots and settles, which the label records
independently. This is the same shape-versus-motion distinction as crossing-versus-endpoint in Part 3.

---

# Part 6 — held-out validation on a moving camera (2026-09-12)

Part 4's `motionBaselineNs` was justified on the same six clips it was designed against. `0xKuIvU6lVM`
is a new athlete, a new gym and a handheld camera — the case most likely to break it. Label and both
configs were frozen before pose extraction; predictions were declared before the run.

Screening had recorded this clip as having both endpoints censored. **That was wrong** — the exit is
observed, he stands up. Found by looking at every frame of the last 1.4 s, which the screening pass
had not done.

## The first run found a different defect

`StaticSkillObservation.validate()` rejected the whole frame if **any** of the 33 landmarks fell
outside [0,1] — including the 29 it never reads. It measures torso points 11/12/23/24 only. On this
clip the near foot sits at the bottom edge, so 498 of 600 posed frames were discarded, only 10 of them
for a torso landmark. Posture admission, which scopes its checks to the joints it uses, accepted the
same frames — the two components disagreed about identical data.

Every clip in the existing corpus has the athlete wholly inside the frame (0% out-of-range), which is
why this was invisible until new footage arrived. The bounds check is now scoped to the landmarks
actually measured; finiteness and structural checks stay global.

**A pre-registered prediction failed here and is reported as failed.** It said all seven clips with
existing output would be byte-identical "because 0% of their frames have an out-of-range landmark" —
a figure measured on three clips and asserted for seven. Front lever (46% out-of-range) and planche
(17%) changed. What changed:

- **Front lever: outcome identical.** 0 intervals before and after, 0.00 s covered, admission
  unchanged at 152. Only the rejection *reason* moved — INVALID_LANDMARK 1383 → 524, reclassified as
  BODY_LINE_NOT_ADMITTED and LOW_CONFIDENCE. Same frames rejected, true reason reported.
- **Planche: 18 → 17 fragments at W=0.25, coverage 22.69 → 22.36 s**; unchanged at W=0. That clip is
  non-blind (disclosed) dev material and carries no evaluated claim.

No published result on an evaluated movement changes. The change is nonetheless **not adopted** — the
pre-registered stopping rule tripped, so the decision goes to the owner.

## The intended experiment, on the fixed engine

| baseline | fragments | longest | covered, of a 19.12 s labelled hold |
|---|---|---|---|
| W = 0 | 7 | 4.04 s | 6.47 s |
| W = 0.25 | **5** | **7.54 s** | **10.11 s** |

The baseline change helps substantially and **does not solve it** — still 5 fragments and 53% of the
hold. The exit is missed entirely: the last interval ends 3.6 s early, so this clip yields no exit
measurement. Both outcomes were predicted in advance.

Measured over 6.0–14.0 s, in the gate's own units:

| term | median | p90 | over 0.30 |
|---|---|---|---|
| engine (absolute landmark speed — what the gate uses) | 0.177 | 0.501 | 53/239 |
| **camera alone, from background pixels** | **0.135** | 0.398 | **44/239** |
| deform (centroid-relative, translation-invariant) | 0.097 | 0.235 | 12/239 |

**The camera alone eats 45% of the gate and puts 44 frames over threshold by itself.** The back
lever's static camera, for contrast: median 0.000, max 0.087.

`motionBaselineNs` fixes jitter, whose amplitude is fixed in space, so a longer baseline divides it
down. It cannot fix camera motion, which is smooth and passes through any speed measure at roughly
its true magnitude. **Pose landmarks alone cannot separate camera translation from athlete
translation** — they are the same image motion — so no change confined to the landmark stream will
fix it. The `deform` column shows what a translation-invariant measure would buy (53 → 12), but it is
not a drop-in replacement: a hold that fails by the body sliding down the wall is a real failure a
deformation-only measure would stop seeing.

---

## Part 7 — Correction: lens facing is not viewpoint (2026-09-12)

A live knee-raise attempt on the phone counted nothing. I diagnosed it as the Priority 8 negative
recurring, on the grounds that `MovementViewerController` requests `LENS_FACING_FRONT` while the
knee-raise config declares `SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED`. **That inference was wrong twice
over, and neither error is in the record above — both are mine, made on 2026-09-12.**

**1. A camera's lens facing does not establish the athlete's viewpoint.** `LENS_FACING_FRONT`
selects which physical sensor the app opens. Whether the recording is a front view or a side view of
the body is decided by where the phone is standing and which way the athlete faces — nothing else.
A front-facing camera on a phone propped to the athlete's side records a side view. The two senses
of "front" are unrelated, and I collapsed them. ADR-0006's front-camera decision is about operator
self-visibility; it carries no claim about viewpoint and never did. **The front camera is kept.**

**2. The Priority 8 mechanism does not transfer to this config.** That negative's dominant cause was
projected knee angle falling below a **140°** knee gate. The frozen hanging-knee-raise config
(`C0RXOvy609I-hlr-config-v1.json`) sets `raisedKneeMinDegrees = 0.0`. Since projected knee angle is
in 0–180°, `knee >= 0.0` always holds: the knee test is **vacuous** and `KNEE_FLEXION_REJECTED` is
unreachable. This config discriminates on hip angle alone. Evidence from a straight-leg raise, whose
failure was a knee-angle gate, cannot explain a bent-knee miss under a config with no knee gate.
`OfflineMovementAdaptersTest.theFrozenKneeRaiseConfigImposesNoKneeAngleRequirement` now pins this.

Worth noting against my own hypothesis: Priority 8's cause (1) records that from the front the hip angle
*falls to 0.1–5°* at each top through projective degeneracy. Under a hip-only config that is a
direction toward **spurious passing**, not toward silence. So a frontal view would not obviously
produce the zero that was observed. The viewpoint hypothesis does not even predict the symptom.

**What actually explains the zero is not yet known, and no threshold should move until it is.** The
live attempt produced no trace at all (below), so there is no evidence either way. Four candidate
causes remain open and must be told apart from data, not from reasoning:

| Candidate | What the trace would show |
| --- | --- |
| Tracking loss | `offline_reason` = `LOW_CONFIDENCE` / `MISSING_LANDMARK` / `NO_POSE`, or capture gaps |
| Viewpoint limitation | Frames measured cleanly, but `off_hip_deg` never closing by 45° from its lowered anchor |
| Configuration mismatch | `off_support_rise` < 0.35 or `off_trunk_elevation_deg` < 55° — i.e. never admitted as hanging at all |
| Cycle logic | Cycles opening and closing as `SUPPORT_TRAVEL_REJECTED` / `ABANDONED` / `TRACKING_INTERRUPTED` |

### Why there was no trace, and what changed

Two clocks bound a debug session, and only the longer one was ever visible. The 30-minute marker
authorization had lapsed before the attempt; separately, `startFeedback` sets the recorder's own
deadline to **180 s**, so even inside a valid authorization the recorder stops after three minutes.
A status line reporting only the 30-minute window can therefore read "on" while nothing is written.

Changes made (debug builds only, no production behaviour touched):

- **An explicit in-app "Start 30-minute debug session" action**, with ACTIVE / EXPIRED / OFF status
  and a Stop button, showing **both** deadlines and the session id. It refreshes the same private
  marker `adb run-as` writes and grants the same 30 minutes. Expiry is preserved and **nothing
  renews itself**; the only way back is pressing start again.
- **The diagnostic log now records the offline engine.** It previously logged only
  `PullUpExperimentState`, which is deliberately not fed while an offline engine runs — so every row
  said `PULL_UP` / `IDLE` regardless of what the athlete did. This is the same defect as the panel
  routing bug fixed earlier the same day: I fixed what the screen displayed and left the log alone.
  Rows now carry `offline_movement`, `offline_phase`, `offline_reason`, `offline_cycle_outcome`,
  `offline_reps`, and the engine's own projected geometry under `off_*`. Offline state is part of
  the duplicate-suppression signature, so a rejection that changes while pull-up state sits still is
  no longer dropped as a repeat.
- **A framing instruction** on every exploratory movement: *"Place the phone to your side. Keep your
  full body and the bar visible."* It is shown for all seven, because each config was frozen from
  one recorded viewpoint and none has been checked from another — including handstand hold, which
  must not be called view-agnostic without evidence.

Rear-camera selection is a separate, optional usability question. It is **not** a prerequisite for
side-view testing, and it is not being pursued as one.
