# Movement method ledger

**Canonical, append-only.** Every offline experiment, with the parameters and predicted outcomes
that were frozen *before* the run that tested them. The results documents in `docs/` cite this as
their provenance; `docs/PROJECT-STATE.md` summarises its conclusions.

Append a new `## Update N` per unit of work. **Never edit an earlier entry** — corrections are new
entries that say what was wrong and why, which is how the record stays trustworthy. Several entries
below correct earlier ones; that is the mechanism working, not a defect.

A working scratch copy may exist at `.superpowers/sdd/2026-09-11-muscle-up/progress.md` inside a
worktree. It is unversioned and is **not** the source of truth. This file is.

---


Worktree `/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/worktrees/m0-5b-task-4-synchronized-video`
Branch `codex/movement-viewer` HEAD `a690a351569cb8c76aaaece47e1398c9d0e7836c` (unchanged).
WIP preserved: 2 tracked-modified + 99 untracked at start; no reset/clean/checkout/stage.
No commit, push, PR, tag, device or app action. Buzz untouched.

## Inputs (hash-bound)
- source `File:Muscle-up.gif` video_sha256 `c6950b64b4a1c82c294d597ec7e8b787f0085eddf8a97fbef4521f0814411577`
- pose cache `poses_sha256 99e936c1155a1566420e154edba32611c87f7a35c3068176cf6a0f4d1376a6c2` (31 rows, 25 with pose)
- model `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1`
- frozen labels `ec44ab5ff92db6aeb435dfa901dc17b4dc2cdaee545e8986e2b4339c6a865fce` (all `complete_cycle_label: null`, frozen before inference on 2026-09-10)
- frozen config `offline-corpus/muscle-up-20260911/config-bar-kipping-right-v1.json` (v1, written before the real replay)

## Commands and exit codes
- side selection by joint availability — RIGHT 21/31, LEFT 18/31 — exit 0
- synthetic parity, python mirror — 13/13 PASS — exit 0
- real replay, python mirror — exit 0 — `count=0 unresolved_excursions=1`
- mutation check (grip gate disabled) — count flips 0→1 — gate load-bearing
- `./gradlew --no-daemon :shared:jvmTest --tests '*MuscleUpCycleExperimentTest*'` — **FAILED, exit non-zero**, wrapper could not fetch the distribution

## Event-level result
One excursion: left hang 500 ms, support observed 700 ms, return **never observed** →
`SUPPORT_UNRESOLVED`. Zero counted repetitions. Reasons: NONE 16,
BODY_SCALE_INCONSISTENT 5 (1100–1500 ms), LOW_CONFIDENCE 4, NO_POSE 6.
Independently reproduces the pre-frozen null complete-cycle labels.

## Files added (all untracked, nothing staged)
- `apps/diagnostic-harness/shared/src/commonMain/kotlin/.../experimental/MuscleUpCycleExperiment.kt` — UNCOMPILED
- `apps/diagnostic-harness/shared/src/commonTest/kotlin/.../experimental/MuscleUpCycleExperimentTest.kt` — UNRUN
- `.superpowers/sdd/2026-09-11-muscle-up/reference_muscleup.py` — verification harness, not an engine
- `docs/2026-09-11-muscle-up-offline-results.md`
- `offline-corpus/muscle-up-20260911/` (outside Git): frozen config + `replay-reference-v1.json`

No existing engine, threshold, test or cached prediction was modified.

## Unresolved
1. **BLOCKER — no Kotlin toolchain.** `~/.gradle/caches` absent; Gradle 9.3.1 dist download
   307s to `release-assets.githubusercontent.com`, proxy returns 502. Kotlin engine and
   tests unverified. Needs a network that can reach that host.
2. `GRIP_DRIFT` validated synthetically only. On this source the migrated-skeleton frames
   were caught by confidence instead. Needs a source where a migrated skeleton passes confidence.
3. `minimum_body_scale_ratio = 0.6` calibrated on this development source; disclosed as non-blind.
4. No admitted **ring** muscle-up source; strict-ring result not started.
5. No JVM entrypoint / Gradle task / `offline_muscleup_feedback.py` yet — deferred until the
   engine compiles.

## Next action
Run the Kotlin tests once a network allows the Gradle distribution; fix whatever the mirror
missed; then add `ReplayMuscleUpPose.kt` (top-level `fun main` shim required for a Gradle
task, per the Pike/Hindu precedent) and the Gradle loop entry. Then priority 2, straight-bar
dips, reusing the existing dip engine and the `gTyaLS1Xwh4` tracking-challenge case as a
hard negative rather than a baseline.

---

## Update — owner-authorized clip run (2026-09-11)

Owner authorized internal-assessment-only use of the already-downloaded YouTube copies.
No re-download, no repeat of the 2026-09-10 acquisition workaround; local SHA-256s verified
against the frozen manifest first. Licence position recorded, not erased.

Extracted with pinned model 5134a3aa (same as GIF), MediaPipe 0.10.21 / PyAV 18.1.0:
- yTczZRbcfJU 583 rows, strict ring, side RIGHT (583/583 vs LEFT 368/583)
- k71s4T2_CJI 617 rows, kipping bar, side RIGHT (617/617 vs LEFT 616/617)
Labels frozen to completion windows from annotations_v2_frozen.json BEFORE prediction.

**Defect found:** v1 grip reference was captured during the MOUNT (bar ref at t=0.27s
wrist y=0.447 vs gripped y=0.22 -> 1.85 torso lengths). Caused GRIP_DRIFT on 572/617 bar
frames and lost 2/3 ring reps. Fixed in v2 by requiring an already-stationary grip
(minimum_reference_samples + maximum_reference_wrist_spread_torso_lengths). Ported to
Kotlin engine + a new regression test. **Correction is NON-BLIND** (made after seeing v1).
v1 configs/reports retained unchanged.

v2: ring counted=3 unresolved=1 matched=0/3 (all early); bar counted=3 matched=3/3 extra=0.
Toes-to-bar after ~9.5s produced ZERO extra events on real footage.

**Systematic early completion:** ring mean -416ms, bar mean -172ms vs annotated
"arms extended in hang". Engine completion = regime re-entry; label completion = arms
extended. Different definitions. The bar 3/3 is partly its wider +/-0.5s window absorbing
the error, NOT better timing. Windows were NOT moved.

Artifacts (outside Git): muscle-up-20260911/{ring,bar}-source-record.json, -labels-v1.json,
-cache-v1/, -report-v1.json, config-*-v1/v2.json, timing-analysis-v2.json.

### Open
6. Completion-definition mismatch: decide between measuring elbow extension in hang (second
   observation) or re-annotating to regime crossing. Do not silently move windows.
7. Kotlin v2 changes still UNCOMPILED (toolchain blocker unchanged).
8. AJAVu29c-NU deliberately not run: same group as k71s4T2_CJI, background people.

## Update 2 — v3 completion definition
Changed completion to "below + arms straight" (hang_lockout_elbow_degrees=150.0, dand
precedent). Elbow joint costs no availability (583/583, 617/617).
Offset moved only: ring -416 -> -371ms, bar -172 -> -161ms. HYPOTHESIS MOSTLY WRONG,
recorded. Raw trace shows descent is continuous: -0.05 crossing at 7.441s (elbow 164deg)
but hang settles ~-0.13 near 8.0s; reviewer mark 7.75s is mid-descent. The disagreement is
threshold placement on a continuous trajectory, not arm extension.
NOT tuned further: fitting the detector to its own labels is forbidden. Needs blind
re-annotation with an explicit "descent stops" rule; current +/-0.25s understates ambiguity.
Counts remain 3/3 both variants, 0 extra, T2B rejected, dismount unresolved.

## Update 3 — toolchain resolved; priorities 1-4 on real Kotlin (2026-09-11)
Gradle 9.7.1 (brew) + JDK21 via -Porg.gradle.java.installations.paths; wrapper files untouched;
deviation from pinned 9.3.1 recorded. Full suite 815/89, 0 fail/err/skip (JUnit XML).
Kotlin mutation checks fail exactly the intended tests; byte-exact restore verified.
Mirror bug found by Kotlin cross-check (grip ref read elbow idx[1]); fixed; parity 0/1231 frames.
Results: MU ring 3/3 counted (0/3 windows, early), MU bar 3/3 (3/3), HSPU back 5/5, facing 5/5
(early 346-470ms), straight-bar dip existing engine 5/6 detected 0 FP (2/6 windows, late 30-130ms),
handstand holds entry 0.00s/exit +0.90s and -0.77/-1.23s. Details: docs/2026-09-11-priority-coverage-results.md
Corrections: dips corpus is parallel-bar; tutorial downloads truncated at ~61s.
Next: toes-to-bar (priority 10) has real footage in k71s4T2_CJI + AJAVu29c-NU; priorities 5-9 blocked on footage.

## Update 4 — handstand hold engine + toes-to-bar (2026-09-11)
HandstandHoldExperiment: stacked rule (hip>shoulder AND both ankles>hip), declared before running.
xmray entry 0.00s exit +0.90s; btUW 4 fragments, never stitched. Support for xmray = UNKNOWN.
Straight-bar dip: existing engine 5/6 detected, 0 FP, 2/6 in windows; per-segment side (C flipped).
T2B: labels for BOTH recordings frozen before a T2B engine existed; designed on k71 only.
  design k71: HLR existing 4/7; new T2B 5/7 (+1 early 50ms, rep1 missed); 0 in MU block.
  held-out AJA: HLR 1/10; new T2B 0/10 -- far ankle usable 86/420, both-ankle rule failed to transfer.
  NOT tuned on held-out. v2 hypothesis recorded. T2B NOT closed.
Full suite 824/90, 0 fail/err/skip. Priorities 5-7,9 blocked: stills only. 11-13 no footage.

## Update 5 — CC corpus + annotation timebase correction (2026-09-11 overnight)
MEASURED DEFECT in my own labelling: `ffmpeg -ss S -t D -i f -vf fps=F` sheets show frames LATER
than nominal S+i/F: +0.10s @F=4, +0.13-0.16s @F=3, +0.24s @F=2 (sheet_offset.py, pixel-matched vs
native PTS). Found via an annotator's burned-in-stopwatch cross-check. Additive *-timebase labels
written; v1 preserved. Counts UNCHANGED; window-match unstable (T2B 5/7->2/7, dips 2/6->4/6);
engine earliness grows to -0.4/-0.5s (MU, HSPU); dips +0.13s late. Do NOT trust +/-0.25s window
rates; report counts.
CC corpus: 15 fetched (14 usable, all Creative Commons, all fully decoding), fetch_youtube.py stops
if YouTube demands sign-in. Fixed acquisition.json recording YouTube's best format instead of the
downloaded one (all 14 corrected from ffprobe).
PostureAdmission.kt (+10 tests) + ReplayPostureHoldPose reuse StaticSkillObservation unchanged.
Suite 834/91, 0 fail. Wall sit detected (exit -0.08s, 3 fragments). Front lever 0 detections:
posture admitted 152/3384, low upward camera angle. L-sit clip unusable (hip clearance unverifiable).
PENDING: 6 clips downloaded+extracted+configs frozen but UNLABELLED (labelling agents hit a session
usage limit): tuck planche, planche, back lever, HSLR, knee raise, new-athlete T2B.

## Update 6 — T2B generalisation fix + label error (overnight)
Split-screen discovered in wZ6bjrOjbCI and C0RXOvy609I (same athlete twice per frame). Right-half
derivatives created (parent hash + crop recorded), re-extracted.
Far-ankle availability 86/420 and 116/1051 vs near 349 and 882 -> v1 both-ankle rule gave 0 detections
on both. Added explicit requireBothAnkles key; observable-ankle fallback. NON-BLIND (motivated by
held-out failures). Cost tested: single-leg kick can count. Suite 836/91, 0 fail.
Detected: k71 6->7 (7 labelled), AJA 0->5 (10), wZ 0->6 (7 corrected).
LABEL ERROR FOUND BY THE ENGINE: wZ 24.0-31.7s is a sustained toes-to-bar HOLD; v1 labelled 4 reps
inside it. Corrected to 7 reps + 1 hold (v2, disclosed). T2B needs a hold representation too.
T2B still NOT closed; no clean held-out recording remains.

## Update 7 — holds diagnosed (overnight)
Back lever raAQB7ad24s: posture admitted 111/111 inside labelled hold (shape gate SOUND, existing
plank measure unchanged); both endpoints censored (fades in/out mid-hold). Hold fragmented into 4.
Torso-point speed in hold: wall sit median 0.05, only 6/540 over 0.30 -> isolated single-frame
spikes close intervals (BRITTLENESS). Back lever median 0.21, 29/110 over -> genuine near-threshold
motion/jitter (DIFFERENT problem).
NEXT (specified, not implemented): require N consecutive over-threshold frames before closing a
hold (mirrors minimumEndpointSamples). Changes published numbers -> needs own tests + clean rerun.

## Update 8 — tuck planche + overnight stop point
2U8zGzbpJ84: admitted 335/481 in labelled hold, 9 fragments, longest 3.93s vs 16s label. VARIANT
UNRESOLVED (tuck planche vs frog stand indistinguishable at this resolution) -> may be a NEGATIVE.
Pose used only to aim crop, never to label.
STOP POINT. Unlabelled but fully prepared (downloaded, extracted, configs frozen BEFORE labels):
  I9wbS757cvM (planche), TcU7wtLdIx4 (HSLR, FRONT view - expect trouble), C0RXOvy609I-right (knee raise).
Unscreened spare: 84WplmAbszc (2nd L-sit), GSOfcbwbd-g, KqUMAIQn0K8, 0xKuIvU6lVM, fAlucQoAD1o.
Repo unchanged: HEAD a690a35, 2 tracked mods (pre-existing), nothing staged, no media in Git.

## Update 9 — hold persistence: PRE-REGISTRATION (written before any re-run)
minimumMovingSamplesToClose added to StaticSkillConfig (required key; no default in the data class).
N = 3 DECLARED NOW, before seeing any new output. Rationale, from Update 7's measurements only:
  - clips run 24-30 fps, so N=3 is 0.10-0.125 s;
  - the wall-sit spikes that fragmented a steady hold were ISOLATED single frames (6/540);
  - real hold exits in this corpus last far longer (wall-sit exit ~0.5 s), so 0.1 s of persistence
    cannot hide a real exit;
  - it mirrors minimumEndpointSamples, already used for endpoint debouncing in this codebase.
N is NOT to be re-tuned against the results below; if 3 is wrong the finding is recorded, not fitted.
The speed threshold itself (0.30 torso-lengths/s) is UNCHANGED.
Two frozen configs per clip: -hold-config-v2-n1.json (N=1, must reproduce the v1 output byte-for-byte,
since the strict key check can no longer parse a v1 config) and -hold-config-v2-n3.json (the fix).
Expectation stated in advance: N=3 should merge the wall-sit fragments (isolated spikes) and should
NOT fully merge the back lever (29/110 frames over threshold = genuine motion, a different problem).

## Update 9b — hold persistence: RESULTS (N=3 as pre-registered, not re-tuned)
SECOND DEFECT FOUND BY THE FIRST RUN, then fixed: the capture-gap check keyed off lastGoodNs, which
the debouncer deliberately holds back. maximumGapNs is 100ms and the clips are 24-30fps, so absorbing
two transients made the NEXT on-time frame look like a dropout. First N=3 run: merged holds ended as
CAPTURE_GAP and I9wbS757cvM went 15 -> 22 fragments. Added lastAliveNs (capture continuity) separate
from lastGoodNs (last still frame); a genuine dropout is still CAPTURE_GAP. Test added.
Regression, after BOTH changes: ReplayStaticSkillPose (pinned N=1) reproduces its pre-change output
BYTE-IDENTICALLY on y6RrKS76SAg and raAQB7ad24s; the posture-hold N=1 configs reproduce all five
pre-change *-hold-v1.jsonl files BYTE-IDENTICALLY. Suite 840/91, 0 fail/err/skip.

clip          movement      N=1 frag / longest -> N=3 frag / longest   covered s   vs label
y6RrKS76SAg   wall sit         3 / 10.52  ->  1 / 21.52               21.3->21.5   7.60-29.12 vs 7.6-29.2 (entry 0.00, exit -0.08)
W92o2JmiTNI   l-sit cand.      9 /  1.63  ->  1 /  9.41                8.3-> 9.4   0.20-9.61 vs 0.17-9.68 (clip itself unusable)
raAQB7ad24s   back lever       4 /  0.67  ->  2 /  4.00                2.0-> 4.7   6.58-10.58 vs 5.667-10.333
2U8zGzbpJ84   tuck planche     9 /  3.93  ->  6 /  4.60               10.0->10.4   label 2-18; limit is ADMISSION (337/604)
RTg8r4EV0Pc   front lever      0 /  0     ->  0 /  0                   0  -> 0     admission 152/3384, low upward angle
I9wbS757cvM   planche         15 /  3.07  -> 18 /  4.64               13.2->20.7   NO LABELS YET

Reading: the wall-sit brittleness is FIXED exactly as predicted (isolated spikes). The back lever is
NOT fixed to one interval, also as predicted - 29/110 frames genuinely exceed the gate; persistence
is the wrong tool for that and it was not re-tuned to force a merge. Tuck planche and front lever are
limited by PostureAdmission, not by stillness: a separate problem, left separate.
I9wbS757cvM gains fragments while nearly doubling covered time: candidates that used to die under
minimumStillNs now survive long enough to be reportable. Not a regression; it has no labels yet.

## Update 10 — the three remaining clips closed (overnight)
1) TcU7wtLdIx4 (hanging straight-leg raise, FRONT view). Labels frozen 02:17, config frozen earlier,
   BLIND. Result 0/6, exactly as predicted. NOT a blind spot - a clean diagnostic zero: the engine
   opens ONE candidate cycle per labelled rep (departures 0.88/3.75/6.67/9.55/12.64/15.43/18.27 s,
   i.e. 6 reps + the masked opening rep) and REJECTS every one: KNEE_FLEXION_REJECTED (the shin is
   foreshortened along the optical axis, so projected knee angle falls under 140 deg) or ABANDONED.
   156/520 frames are LOW_CONFIDENCE, breaking the 3-consecutive-sample endpoint requirement.
   Hip angle does reach 0.1-5 deg at each top, but that is DEGENERATE, not flexion: from the front
   the raised limb projects onto the hip. Conclusion: side-view geometry cannot be rescued by
   thresholds on a frontal recording. Do not tune for it; the clip is a documented negative.
2) C0RXOvy609I-right-half (hanging knee raise, side panel of the split screen). Labels DERIVED from
   the parent's (frozen 02:17, timed off this same side panel; crop is spatial with PTS passthrough)
   - not re-annotated, and said so in the file. side=LEFT frozen on joint availability ALONE
   (LEFT 919 vs RIGHT 592 usable frames) before running. Result 6 labelled / 6 detected, one per rep,
   ZERO false positives over 53.7 s. Window match 0/6 because every detection lands +0.28 to +0.48 s
   after the labelled return, i.e. just outside +/-0.25 s - the same systematic lateness already on
   record. Counts are the trustworthy metric here, as previously concluded.
   NOTE: compare_events refuses labels whose annotation_status is not "visually_reviewed". I did NOT
   weaken that gate; the derivative keeps the status (the reviewed pixels are literally these pixels)
   and carries label_provenance=derived_from_parent_labels_not_re_annotated plus the parent's method.
3) I9wbS757cvM (planche). Labelled from raw frames tonight. LABELS ARE NOT BLIND and the file says so
   in blindness_disclosure: I had already seen this clip's N=1/N=3 interval boundaries while
   diagnosing fragmentation. Development material only; never held-out evidence.
   The clip is a SEVEN-SHOT tutorial montage (hard cut 6.540; further cuts 21.855, 27.761, 48.615,
   52.953, 64.264). Three planche holds, ALL right-censored by a cut: 1.95-6.54, 24.00-27.76,
   50.75-52.95 (parallettes, parallettes, hex dumbbells). Three negative spans labelled: two
   press-to-handstand drills (which pass THROUGH horizontal and hold a HANDSTAND) and 11.6 s of the
   athlete sitting and talking. Planche LEVEL unresolved - hips sit above the shoulders in all three.
   Scored: N=1 -> hold 1 in 4 fragments, entry +2.05 s late; N=3 -> 1 fragment, entry -0.08 s.
   Hold 2 3->2 fragments (entry +0.66 s). Hold 3 1->2 fragments (entry +0.57 s): slightly WORSE, and
   it is recorded as worse, not explained away. Intervals outside labelled holds: 7 (N=1) and 13
   (N=3); 6 and 10 of those fall inside the labelled press-to-handstand negatives, where the body
   really does pass through horizontal. The rest are the pike/tuck phases just before each entry.
   So the N=3 fragment INCREASE reported in Update 9b is now explained: it is mostly the engine
   holding onto transition spans in shots I have now labelled as negatives.

## Update 11 — handstand-hold persistence: PRE-REGISTRATION (before any re-run)
Measured first, from the EXISTING v1 outputs, so the fix targets a real cause:
  xmrayUHhBgQ: ONE interval already (2.70-32.20 vs label 2.7-31.3). NOT fragmented. Nothing to fix.
  btUWnzTI8yk: 4 intervals. Non-holding run lengths inside the clip are 222, 117, 32, 8, 2, 1 frames.
    The 222/117/32-frame runs are the pre-hold block and two LOW_CONFIDENCE blocks during the camera
    repositioning before 15.5 s - those MUST stay separate. The 51.67/51.77 split is a 1-2 frame
    LEGS_NOT_STACKED blip inside a steady hold.
So the only fragmentation persistence should remove on this corpus is that one blip.
DECLARED NOW, before running: minimumNonHoldingSamplesToClose, N = 3 (0.10-0.125 s at 24-30 fps),
and it absorbs ONLY the geometric non-holding reasons (NOT_INVERTED, LEGS_NOT_STACKED,
HANDS_NOT_BELOW_SHOULDERS) measured from an otherwise valid frame. Pose failures (NO_POSE,
LOW_CONFIDENCE, MISSING/INVALID landmark), capture gaps and timestamp faults still break the hold
IMMEDIATELY, exactly as today - an unmeasurable frame is not evidence that the hold continued.
Predicted, in advance: xmrayUHhBgQ unchanged at 1 interval; btUWnzTI8yk 4 -> 3, merging 14.73-51.67
and 51.77-52.97 into roughly 14.73-52.97. Any other change is a surprise and gets reported as one.

## Update 11b — handstand-hold persistence: RESULTS (exactly as pre-registered)
N=1 configs reproduce BOTH pre-change outputs BYTE-IDENTICALLY. Suite 843/91, 0 fail/err/skip.
Mutation A (persistence branch never taken) fails exactly the two persistence tests; mutation B
(every non-holding reason treated as geometric) fails exactly anUnmeasurableFrameStillEndsTheHold-
Immediately. Byte-exact restore verified by sha256 both times.
  xmrayUHhBgQ  1 -> 1 interval, 2.70-32.20 UNCHANGED (label 2.7-31.3: entry 0.00 s, exit +0.90 s).
  btUWnzTI8yk  4 -> 3 intervals. The two early fragments (7.40-9.63, 10.73-14.43, both ended by
    LOW_CONFIDENCE during the camera repositioning) are correctly left separate. The 1-2 frame blip
    at 51.67/51.77 is gone: 36.93 s + 1.20 s became one 38.30 s hold, 14.73-53.03.
    Against the label (observation of the hold continuous 15.5-52.9; exit begins 53.0): exit error
    moved from -1.23 s to +0.13 s. Entry stays -0.77 s, which is NOT an error - the label's 15.5 is
    where observation begins after the last camera move, not where the hold begins (~7 s).
No surprises; nothing was re-tuned.

## Update 12 — event-timestamp lag: I WAS MOSTLY WRONG, and here is the measurement
I proposed the debounce lag as the main cause of the systematic event-timing error. Checked before
building. It is NOT. Measured, not argued:
  - The lag is exactly (minimum_endpoint_samples - 1) frame intervals. Verified on
    C0RXOvy609I-right: 3 samples at 30.02 fps = 0.067 s, on all 6 events, to the frame.
  - HangingLegRaiseExperiment ALREADY reports the uncontaminated instant as
    closed_cycle.return_timestamp_ns (it records endpointStart). The engine was right.
    MY SCORER threw that field away and scored the confirming frame instead. That is a measurement
    bug of mine, now fixed in replay_reps_one.py. Effect: knee raise window match 0/6 -> 1/6.
    Residual error after the fix is +0.22 to +0.42 s. So the lag was ~1/5 of the error.
  - Every ElbowCycleTiming engine (dip, T2B, HSPU, muscle-up, pike/hindu push-up) uses
    minimum_endpoint_samples = 2, so its lag is ONE frame: 0.033-0.042 s. That cannot explain a
    -0.4/-0.5 s error, and the SIGN IS WRONG for it: muscle-up and HSPU fire EARLY, while a debounce
    lag can only make an event LATE.
CONCLUSION: the systematic timing error is essentially ALL definitional, not a reporting lag, and it
does not even share a sign across movements (MU/HSPU -0.4/-0.5 s early; knee raise +0.3 s late;
dips +0.13 s late). There is no single bias to subtract.

### Still doing, and why - stated before running
ElbowCycleTiming will report the first-endpoint timestamp, as HangingLegRaiseExperiment already
does. NOT because it fixes the error - it is worth one frame - but because right now "the event
timestamp" means "when it happened" in one engine and "when it was confirmed" in five others, and
that inconsistency is exactly what misled me above. It is a correction toward an existing in-repo
precedent, derivable with zero reference to any label.
PREDICTED IN ADVANCE, so it cannot be claimed as a win afterwards: every reported event moves
EARLIER by exactly one frame interval. Dips (+0.13 s late) improve by ~0.04 s. Muscle-up and HSPU
(already early) get ~0.04 s WORSE. Counts do not change anywhere. If counts change, that is a bug.

## Update 12b — RESULTS, and the real cause found
Debounce correction implemented in ElbowCycleTiming (5 engines) + emitted as event_timestamp_ns.
Suite 847/92, 0 fail/err/skip. New ElbowCycleTimingTest pins the property from the machine's own
definition (no recording, no label involved). Two replay row-schema guards caught the new column, as
designed, and were updated.
EXACTLY AS PRE-REGISTERED: counts unchanged everywhere, and window matches unchanged everywhere
(T2B k71 3->3, AJA 3->3, wZ-right 4->4; HSPU back 1->1, facing 0->0). One frame is too small to flip
anything. The correction is right, and it is NOT the fix. Claimed as neither.

### Residual after the artefact is removed - consistent per movement, and EARLY
  T2B k71     -0.19 .. -0.44 s      HSPU back   -0.53 .. -0.69 s
  T2B AJA     -0.17 .. -0.36 s      HSPU facing -0.56 .. -0.74 s
### CAUSE, measured on a raw trajectory (cLTvfDlhiXM rep 1)
extended_span_ratio = 0.80. The engine fires at 4.371 s, the first frame the ratio exceeds 0.80 -
but the athlete is still RISING: 0.84, 0.86, 0.88 ... plateau ~0.995 at 4.77-4.81 s. The annotator's
return is 4.968 s, ON the plateau. The 0.60 s gap IS the ascent time from the detection threshold to
the top of the rep.
So the engine reports WHERE IT DETECTED the endpoint; the annotator reports WHERE THE MOVEMENT
ENDED. Two different instants on one continuous trajectory. This is neither an engine bug nor a
label bug, and it is the same thing Update 2 found for the muscle-up ("threshold placement on a
continuous trajectory, not arm extension") - it generalises to every threshold-crossing engine.
NOT a per-movement offset to subtract: subtracting an offset measured from labels is fitting to
labels and is forbidden here.

### Proposed fix (NOT implemented - changes every published timing number, so it is escalated)
Report the event at the EXTREMUM of the endpoint signal within the confirming excursion (the frame
of maximum span ratio / minimum feet-below-hands), not at the threshold crossing. The detection
threshold keeps its job - deciding THAT a cycle happened; the extremum decides WHEN it happened.
The extremum is a property of the signal, definable with zero reference to any label, exactly like
the debounce correction. Thresholds stay untouched, so counts should not move.

## Update 13 — bounded endpoint-timing experiment (HSPU only)
Spec frozen BEFORE implementation: endpoint-timing-spec-v1.md. Full report:
docs/2026-09-11-endpoint-timing-experiment.md.
KEY FINDING FIRST, because it constrains everything: the HSPU annotation marks DEPARTURE (descent
onset), established from annotation STRUCTURE before the estimator was designed - top_end_s(n) is
numerically identical to top_start_s(n+1), and the null-reason text calls top_start_s "descent
onset". An extremum must not be scored against it.
Its sampling step is 0.333 s and the plateau is 0.03-0.27 s, so the annotation is COARSER THAN THE
FEATURE. Decidable: crossing vs plateau-region (~0.6 s apart). NOT decidable: extremum vs departure
(~0.1 s apart). Recorded in advance and respected.
Built EndpointTimingEstimate.kt: additive, consumes an already-decided rep, cannot touch counts.
No new threshold (admission level doubles as reversal level). One new parameter,
plateau_band_fraction = 0.05, fixed from SIGNAL NOISE ONLY (near-top per-frame |delta| median
0.0024, p90 0.0099; 5% of a 0.1996 excursion = 0.0100) with no annotation consulted, then frozen.
Refusals are first-class; nothing is interpolated across a gap; the search never spans two reps.
13 synthetic trajectories (plateau noise, shallow vs deep dip, double peak, dropout, partial rep,
truncation, ties). Suite 860/93, 0 fail.
RESULTS (mean event-time error vs frozen v2 annotation, +/-0.333 s):
  cLTvfDlhiXM back-to-wall : crossing -0.517, extremum -0.113, departure +0.012; 1 rep REFUSED
  tz_YVynl1sk wall-facing  : crossing -0.650, extremum +0.012, departure +0.067; 1 rep REFUSED
  SAME config file, no retuning on the second recording.
  Confirmation delay (latency, reported separately, never netted off): 0.27-0.77 s.
  Counts and cycle identities BYTE-IDENTICAL to the frozen run on both clips.
The crossing is outside the annotation's own uncertainty on 8/8 scorable reps - that is the one
claim this annotation supports. Extremum vs departure is NOT claimed: on the v1 timebase the
extremum wins, on v2 the departure wins, so a 0.138 s timebase shift flips the winner. That is
noise, not evidence.
PROPOSED: adopt for HSPU only, additive and offline. No global migration, no historical overwrite,
no deployment. Choosing extremum vs departure needs a <=0.1 s annotation on a few reps; moving the
existing labels to settle it is not an option.

## Update 14 — state-and-plan handoff written (2026-09-12)
docs/2026-09-12-state-and-plan.md. Written before a context compaction so resumption does not depend
on the transcript: absolute paths, the Gradle/JDK workaround, the runtime classpath, the eight method
rules, per-movement status, the two generalising findings, the one open decision, and an ordered plan
(A screen 5 spares -> B per-movement annotation-definition audit -> C adopt-or-drop HSPU endpoint ->
D back-lever mechanism -> E admission diagnosis). Verified on disk 2026-09-12: suite 860/93 0 fail,
HEAD a690a35, all five spare clips present, corpus intact.

## Update 15 — HSPU endpoint estimate ADOPTED (2026-09-12)
Owner approved. Adopted for HSPU ONLY, additive and offline, as a SECOND PASS - it needs lookahead,
so emitting it inline per-frame would hide the 0.27-0.77 s latency. Entry point ReplayEndpointTiming,
frozen config endpoint-config-hspu-v1.json (sha 5b6fbb70...).
Added on adoption because the entry point had none: ReplayEndpointTimingTest, 7 tests - strict config
parsing, one estimate per counted rep, one rep's search never reaching into the next, pass-through of
the engine's own crossing/confirmation, dropout and capture-gap refusals, refusal to overwrite.
Suite 867/94, 0 fail/err. NOT adopted: any other movement, any historical rewrite, any annotation
move, any deployment. Extremum vs departure remains open and unclaimed.

## Update 16 — footage downloads authorised (owner, 2026-09-12)
Owner lifted the "no new downloads" bound: any number of YouTube clips may be fetched, to improve an
existing movement or add a new one. Standing conditions unchanged and still the owner's: never bypass
an access restriction (fetch_youtube.py stops at a sign-in wall - leave it stopped and record the
refusal), prefer Creative Commons, record licence/attribution/source/acquisition per download, record
the REAL format from ffprobe not YouTube's metadata, internal assessment only, media outside Git.
Consequence: front lever (needs SIDE-ON footage) and toes-to-bar (needs a third athlete) move from
BLOCKED to actionable search tasks. Unchanged: extremum vs departure still cannot be settled by new
footage - it needs a <=0.1 s annotation of footage already held.

## Update 17 — spare-clip screening (plan item A), 2026-09-12
Screened all five spares. **A correction to §7 of the state-and-plan first: it claimed these five
were "the only route to closing priority 9 (front lever) and priority 12 (L-sit)". That is wrong
about front lever — none of the five is a front lever.** Their subjects are: L-sit, hanging leg
raise + toes-to-bar, hanging leg raise, wall sit, muscle-up. Front lever can only be closed by a
download, which is now authorised.

Screening used raw frames via exact_sheet.sh, plus a new tool, `camera_motion.py`, that answers one
question: do background patches at different depths move as ONE 2D similarity (an editing pan/zoom
or a rotation-dominated handheld wobble, both harmless to image-plane relative geometry), or do they
disagree (parallax = the physical camera translated, which changes the projection the engines
assume)? It prints UNKNOWN for a patch with too little texture rather than guessing.

| clip | subject | verdict |
|---|---|---|
| `84WplmAbszc` | floor L-sit, side view | **USABLE** — best of the five |
| `0xKuIvU6lVM` | wall sit, side view | **USABLE with caveats** — both endpoints censored |
| `GSOfcbwbd-g` | HLR + T2B instructional | REJECT — cuts, camera pitched steeply up, feet leave frame at the hang |
| `KqUMAIQn0K8` | hanging knee raise demo | REJECT — camera dollies (parallax) |
| `fAlucQoAD1o` | muscle-up | REJECT — usable window ~3 s, then a reframe and a long body-out-of-frame close-up |

`84WplmAbszc`: single athlete, one continuous shot ~3.4-21.9 s, side-on. Hip clearance IS verifiable
here (hand flat on floor, buttocks visibly clear of it, a phone lying on the floor *under* the raised
legs) — the specific reason the first L-sit clip was unusable. The countdown/branding overlays sit
entirely at x<=429 while the pillarboxed content strip is x in [437,842], so **no overlay ever touches
the athlete**. Camera: six patches at different depths agree to 0.6-3.3 px per step, so no parallax;
there is a slow global drift totalling ~36 px horizontally over the hold (~3 px/s on a 406 px-wide
content strip). NOTE the countdown graphic is the video's own claim and is NOT ground truth; any
label must come from reviewing pixels.

`0xKuIvU6lVM`: wall sit, side view, one shot, full lower body in frame. Handheld: whole-frame motion
up to ~40 px between 1 s samples, but the well-locked steps fit one similarity to 0.7-5 px, so again
no parallax. The athlete is ALREADY in the hold at t=0 and still in it at the last frame, so both
endpoints are censored and no entry/exit accuracy can be claimed. Its remaining value is a
fragmentation/robustness test on a new athlete, a new scene and a moving camera.

## Update 18 — back-lever fragmentation (plan item D): PRE-REGISTRATION, written before the run
Two new offline tools, both diagnostic, neither changes any engine:
`camera_motion.py` (above) and `hold_motion_breakdown.py`, which splits the hold gate's value into a
camera term (measured from background pixels) and a body term.

FAITHFULNESS CHECK FIRST. The python mirror of the Kotlin gate reproduces the published back-lever
numbers exactly: median 0.209 vs 0.21, p90 0.396 vs 0.40, max 0.664 vs 0.66, 29/110 over threshold.
So what follows is measured against the real gate, not an approximation of it.

A HYPOTHESIS OF MINE WAS WRONG AND IS RECORDED AS WRONG. I expected camera motion to explain the
back-lever exceedances. It does not: the camera term is median 0.000, max 0.087 torso-lengths/s. The
labels-v1 claim "static side view" is correct and my hypothesis was not.

WHAT THE MEASUREMENT ACTUALLY SHOWS. Over the whole 4.67 s back-lever hold the torso centroid moves
0.105 torso-lengths horizontally and 0.075 vertically, NET, with std 0.026/0.019. The athlete is
nearly motionless. The gate nevertheless reports median 0.209 and 29/110 over 0.30, because it is a
FRAME-TO-FRAME speed: it multiplies displacement by the frame rate, so a fixed spatial jitter
produces a speed proportional to fps. Measured local landmark jitter (offset from a 5-frame local
mean) is 0.0029 torso-lengths on the back lever versus 0.0006 on the wall sit — 0.90 px versus
0.06 px in absolute terms, consistent with the labels' own note about background clutter and
overlapping far-side limbs degrading pose quality.

Across all six frozen hold clips the gate's median tracks jitter amplitude, not athlete motion:

| clip | movement | fps | torso px | jitter (tl) | gate median | over/n |
|---|---|---|---|---|---|---|
| y6RrKS76SAg | wall sit | 25.00 | 138.8 | 0.0006 | 0.053 | 6/540 |
| 2U8zGzbpJ84 | tuck planche | 30.00 | 118.3 | 0.0005 | 0.066 | 26/480 |
| I9wbS757cvM | planche | 29.97 | 204.2 | 0.0008 | 0.126 | 8/137 |
| W92o2JmiTNI | l-sit cand. | 29.97 | 143.6 | 0.0012 | 0.128 | 17/284 |
| raAQB7ad24s | back lever | 24.00 | 310.8 | 0.0029 | 0.210 | 29/110 |
| RTg8r4EV0Pc | front lever | 24.00 | 76.0 | 0.0022 | 0.331 | 34/60 |

TWO STRUCTURAL CONSEQUENCES, stated as findings, not as fixes:
1. The gate is NOT frame-rate invariant. The same athlete holding equally still scores differently
   at 24 and 60 fps, so 0.30 torso-lengths/s does not mean the same thing on any two clips.
2. The gate is NOT pose-quality invariant. Background clutter alone triples it with no change in
   the athlete.
This explains the wall-sit brittleness AND the back-lever fragmentation with ONE mechanism, and it
explains why persistence fixed the first (isolated spikes) and not the second (sustained jitter).

PRE-REGISTERED TEST, parameters and predictions declared NOW, before running:
Replace the one-frame baseline with a FIXED TIME baseline W: compare each frame's torso landmarks
against the most recent earlier frame at least W seconds back, dividing by the actual elapsed time.
W = 0.25 s DECLARED NOW. Rationale, from the measurements above only, with no reference to any
score: jitter contributes roughly jitter/W, so W=0.25 s cuts the jitter term ~6x at 24 fps; and the
real hold exits in this corpus last far longer than 0.25 s (the wall-sit exit is ~0.5 s), so a
0.25 s baseline cannot straddle a real exit. W is NOT to be re-tuned against the results.
The threshold itself (0.30 torso-lengths/s) stays UNCHANGED, as in Update 9.

Predictions:
  (a) back lever: over-threshold count falls from 29/110 to <= 3, and the median falls to 0.04-0.09
      (its measured smoothed centroid speed is 0.051).
  (b) wall sit: 6/540 -> 0, median <= 0.02.
  (c) front lever: improves but does NOT reach zero — it is limited by ADMISSION (152/3384), which
      this change does not touch, and its torso is only 76 px.
  (d) The cost, stated in advance so it cannot be discovered later and presented as a surprise:
      a fixed baseline cannot report motion sooner than W, so a genuine hold EXIT will be detected
      up to 0.25 s late. The wall-sit exit error should move from -0.08 s toward +0.1..+0.25 s.
      If (a)-(c) hold and (d) is worse than +0.25 s, that is a finding against the change.

## Update 19 — back-lever fragmentation: RESULTS (W = 0.25 s exactly as pre-registered)
`motionBaselineNs` added to `StaticSkillConfig` (required key, no default, `require(>= 0)`). Only the
MEASUREMENT reaches back; coverage, candidate start and capture continuity still key off the
immediately previous frame, so at 0 nothing changes at all. When the baseline is not yet available
the engine REFUSES to measure rather than falling back to a one-frame reading, which would be the
very quantity the baseline exists to avoid.

REGRESSION, both directions, all six clips:
  - `n1b0` (N=1, baseline 0) reproduces every pre-change `*-hold-v1.jsonl` BYTE-FOR-BYTE (5 clips;
    I9wbS757cvM never had a v1 and is reported as absent, not as a pass).
  - `n3b0` (N=3, baseline 0) reproduces the currently-adopted hold-persistence-20260911-v2 output
    BYTE-FOR-BYTE on all 6. The new parameter is provably inert at 0.
Re-run after the final compile to make sure the proof describes the shipped classes, not an earlier
build. Suite 874 tests / 94 suites, 0 failures/errors/skipped (was 867/94).

MUTATION CHECK — four mutants, all killed, file restored to sha256 53b0052d916022348026b5624365bd1d97eb1099ac6dd837ad2875cae3ed5b9a:
  1. baseline selection ignores the cutoff (collapses to one-frame) -> killed by 3
  2. fall back to the previous frame instead of refusing -> killed by 3
  3. prune discards the baseline frame -> killed by 2
  4. negative-baseline guard removed -> killed by 1

I WROTE TWO VACUOUS TESTS AND THE MUTATION CHECK CAUGHT THEM. My first versions of
`aFixedBaselineDividesByElapsedTime...` and `...SameAnswerAtTwoDifferentFrameRates` used UNIFORM
motion. A one-frame measure and a fixed-baseline measure give identical answers on uniform motion,
so mutant 1 survived both. Rewritten around JITTER, which is the phenomenon actually at issue; both
now kill it. Recorded because the lesson generalises: a test for this defect that does not jitter is
not testing anything.

| clip | movement | N=3 b=0 frags / longest / covered | N=3 b=0.25 frags / longest / covered |
|---|---|---|---|
| raAQB7ad24s | back lever | 2 / 4.00 / 3.04 | **1 / 4.96 / 4.96** |
| y6RrKS76SAg | wall sit | 1 / 21.52 / 21.24 | 1 / 21.56 / 21.56 |
| W92o2JmiTNI | l-sit cand. | 1 / 9.41 / 8.41 | 1 / 9.34 / 9.34 |
| 2U8zGzbpJ84 | tuck planche | 6 / 4.60 / 10.00 | 5 / 4.53 / 10.53 |
| I9wbS757cvM | planche | 18 / 4.64 / 16.48 | 18 / 4.57 / 22.69 |
| RTg8r4EV0Pc | front lever | 0 / 0 / 0 | 0 / 0 / 0 |

AGAINST THE PRE-REGISTERED PREDICTIONS, including the ones I got wrong:
 (a) back lever over-threshold 29/110 -> 0/105. Predicted <= 3: HELD. Predicted median 0.04-0.09,
     actual **0.097**: MISSED, narrowly and in the direction of less improvement. Recorded, not
     re-fitted. The clip now yields ONE interval, 5.62-10.58 s, covering 4.96 s against a labelled
     4.666 s hold whose BOTH ends are censored with 0.333 s uncertainty. **Item D is closed.** No
     entry or exit accuracy is claimed from it: the clip fades in and out with the lever held.
 (b) wall sit 6/540 -> 0/534: HELD. Predicted median <= 0.02, actual **0.039**: MISSED by 2x. The
     prediction assumed jitter dominated the residual; it does not. Subtracting the jitter term
     leaves ~0.031, which matches this clip's independently measured smoothed centroid speed of
     0.021 — i.e. what remains is real slow motion, and no baseline can or should remove it.
 (c) front lever improves but does not reach zero: HELD in the gate values (33/60 -> 22/55) and the
     engine output is unchanged at 0 intervals, because it is ADMISSION-limited. See Update 20.
 (d) THE COST, and I over-estimated it. Predicted the wall-sit exit would move from -0.08 s to
     +0.10..+0.25 s. Actual: entry 7.60 -> 7.68 (+0.08 s), exit 29.12 -> 29.24, i.e. exit error
     **-0.08 s -> +0.04 s**. The absolute error got SMALLER, and both endpoints stay inside the
     label's own 0.2 s uncertainty. Why the over-estimate: the baseline is an upper bound on
     latency, not typical latency. A genuine exit moves far more than jitter, so it crosses the gate
     long before the full 0.25 s has elapsed. The engine test
     `aGenuineSustainedMotionStillClosesTheHoldUnderAFixedBaseline` pins that bound so it cannot
     silently regress.

NOT ADOPTED BEYOND THIS. The six frozen clips were re-run additively into
`offline-corpus/hold-baseline-20260912/`; no historical output was rewritten, the v1 and v2 configs
and outputs are untouched, and nothing outside the posture-hold replay path uses a non-zero baseline.
`ReplayStaticSkillPose` stays pinned at baseline 0 as the frozen regression path.

## Update 20 — admission diagnosis (plan item E), 2026-09-12
E asked which gate rejects on the 146 tuck-planche frames: horizontality or straightness. Answered
without changing anything, by mirroring `measurePlankBodyLine` offline — `admitPosture` collapses
every distinct cause to the single value `BODY_LINE_NOT_ADMITTED`, so the existing outputs cannot
answer it and the instrumentation to answer it does not exist.

FIRST, A FINDING THAT MAKES THE QUESTION PARTLY MOOT: on all three HORIZONTAL_BODY clips the frozen
configs set `straight_min_degrees: 0.0, straight_max_degrees: 180.0`. That band is the entire range,
so **the straightness gate cannot reject anything at all**. Admission for these movements is, in
effect, only "did the plank measurement refuse?".

It is horizontality in both cases, and the two cases are NOT alike:

| clip | rejected inside labelled holds | dominant cause | shoulder-ankle angle from horizontal (gate 30 deg) |
|---|---|---|---|
| RTg8r4EV0Pc front lever | 184/185 | UNSUPPORTED_ORIENTATION 178 | min 29.8, **median 53.3**, max 89.9 |
| 2U8zGzbpJ84 tuck planche | 146/481 | UNSUPPORTED_ORIENTATION 146 | min 20.7, **median 27.1**, max 32.9 |
| raAQB7ad24s back lever | 0/111 | — (all admitted) | min 5.3, median 9.3, max 18.1 |

- **Front lever is not a threshold problem.** At a median of 53 deg the body line reads as nearly
  vertical in the image. Admitting it would need a gate above ~55 deg, which would also admit a
  standing person. This is the camera geometry, exactly as already recorded, and no parameter
  rescues it. It needs the side-on footage.
- **Tuck planche sits ON the gate** — the whole distribution, 20.7 to 32.9 deg, straddles 30. But the
  more useful observation is that the measure is the wrong one for this movement: HORIZONTAL_BODY
  measures shoulder->ankle, and in a TUCK the ankle is drawn up under the hips, so that vector
  describes the tuck rather than the hold. Back lever, with a genuinely extended body, sits at a
  median of 9.3 deg and admits 111/111 through the identical gate. So the tuck planche's near-gate
  behaviour looks like an artefact of applying an extended-body measure to a tucked body, which also
  fits the standing note that its variant is unresolved (possibly a frog stand).
DELIBERATELY NOT CHANGED. Widening the gate or adding a tuck-specific body line would change
published numbers across several movements, which the brief reserves for the owner. Recorded as a
design finding with the evidence attached.

## Update 21 — annotation-definition audit (plan item B), 2026-09-12
Read what every frozen annotation convention actually marks, before pointing the endpoint estimator
anywhere else. 14 label files across 9 movements.

**The convention is uniform across the whole corpus, and it is FIRST ARRIVAL, not extremum and not
departure.** Every rep-based annotation scores the RETURN: the first frame back at the reference
configuration after the extreme.
  muscle-up      `end_s`     first frame back in hang
  toes-to-bar    RETURN      first frame legs back below hips after the touch
  hanging leg raise / knee raise  RETURN   first frame legs back below hips after the extreme
  straight-bar dip           first frame back at the top, arms extended, after the bottom
  HSPU           `top_end_s` return to arms-extended overhead after the bottom
Hold-based annotations (`hold_start_s`/`hold_end_s`) mark the first and last frames of the held
shape, with explicit `left_censored`/`right_censored` and a per-clip `uncertainty_s`.

CONSEQUENCE, AND IT ARGUES AGAINST A MIGRATION I MIGHT OTHERWISE HAVE MADE:
The endpoint estimator reports `plateauArrivalNs` (first in band) and `estimatedDepartureNs` (last in
band). The stated convention everywhere in this corpus is the ARRIVAL analogue. On HSPU the measured
fit favoured DEPARTURE (+0.012/+0.067 s versus extremum -0.113/+0.012), but §6 of the endpoint report
already records that a 0.138 s timebase shift flips that winner and that 0.333 s sampling cannot
separate the two. So on HSPU the numbers point one way and the annotation's own words point the
other, and the data cannot adjudicate.
**Therefore: do not migrate `estimatedDepartureNs` to any other movement.** Doing so would carry an
HSPU-specific numerical result across a convention that explicitly says first-arrival — which is
fitting to a score, the thing rule 3 forbids. If the estimator is pointed at another movement, the
quantity to compare is `plateauArrivalNs`, and the claim to make is about the CROSSING being outside
the annotation window, which is the part that is robust.

## Update 22 — L-sit `84WplmAbszc` (plan item F): LABEL AND CONFIG FROZEN, predictions declared
Label frozen FIRST, from raw frames only, with no pose data used for any part of it — not even to
aim a crop. `offline-corpus/youtube-cc-20260911/84WplmAbszc/labels-v1.json`,
sha256 1fce0676dbc1f4df9bbc3a50158745948085c78927494dac13e1dc748829eefa. Video sha256 verified
against the acquisition record. The declared criterion was fixed before the endpoints were read:
buttocks clear of the floor AND both legs straight at the knee AND ankle not below hip, in side view.
Sampling was every frame (0.04 s at 25 fps) at both endpoints; uncertainty_s is 0.12 and is limited
by judging hip clearance in a 406 px-wide strip, not by the sampling step.
  hold 5.88-16.08 s, entry_start 5.72, exit_end 16.32, neither end censored.
**The video's on-screen countdown graphic was ignored.** It is the uploader's claim, not an
observation, and no label value derives from it.

Config frozen before any run: `84WplmAbszc-hold-config-v1-n3b250.json`. Every parameter value is
copied unchanged from the existing L_SIT config; only `side` is set from this recording (the athlete
sits facing image-left, so his left side faces the camera). Nothing here is tuned.

TOOLCHAIN NOTE: no MediaPipe environment or pose model existed on this machine any more, so the
existing pose caches could not be extended. Rebuilt at
`offline-corpus/pose-extractor-20260912/`: mediapipe 0.10.21 and av 18.1.0, matching the versions
recorded in the existing caches' metadata exactly. **The pose model was verified by sha256 against
the value pinned in the existing caches** (5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1
= pose_landmarker_full/float16/1); had it not matched, the new cache would not have been comparable
with the corpus and the right answer would have been to stop.

PREDICTIONS, declared before the pose extraction and the run:
  (a) ONE hold interval, not several.
  (b) Entry within +/-0.25 s of 5.88 and exit within +/-0.25 s of 16.08.
  (c) At least 80% of frames inside the labelled hold admitted by the L_SIT posture gate. Reasoning
      from the shape alone: trunk near vertical (gate 35 deg), hip angle near 90 deg (gate 60-125),
      knees straight (gate >=145), legs near horizontal (gate <=30), hands flat on the floor far
      below the shoulders.
  (d) The slow ~3 px/s camera drift should NOT fragment the hold, because `motionBaselineNs` divides
      by real elapsed time: 3 px/s on a torso of roughly 100 px is about 0.03 torso-lengths/s against
      a 0.30 gate.
If (a)-(c) fail, that is the finding and it gets recorded. Nothing will be re-tuned.

## Update 23 — L-sit `84WplmAbszc`: RESULTS. A clean negative, and the reason is the gate, not the clip.
Run against the frozen label and the frozen config. Nothing was re-tuned. Output at
`offline-corpus/lsit-84WplmAbszc-20260912/`.

Engine reported **4 fragments**: 6.28-8.88, 9.08-10.20, 11.12-15.76, and 16.36-16.76.

Against the predictions in Update 22:
 (a) ONE interval — **FAILED**, four.
 (b) entry within +/-0.25 s of 5.88 and exit within +/-0.25 s of 16.08 — **FAILED**. First interval
     starts 6.28 (+0.40 s); the last real one ends 15.76 (-0.32 s).
 (c) at least 80% of frames inside the labelled hold admitted — **HELD, 256/256**, every one
     `posture_reason: NONE`. Pose was detected on all 256.
 (d) the camera drift should not fragment the hold — **HELD**. The 41 MOVING frames are not spread
     thinly across the hold the way a constant drift would be; they sit in four tight clusters
     (5.88-6.28, 8.92-9.08, 10.24-11.12, 15.80-16.04), i.e. real shifts by the athlete.

### THE FINDING: the L_SIT admission gate never tests hip clearance, so it admits ordinary sitting.

Checked directly, and this is the important part of the session's L-sit work. Over 5.00-5.72 s the
athlete is plainly SEATED ON THE FLOOR with his legs resting flat on it — the frames are
unambiguous — and the gate ADMITS every frame:

| t (s) | what he is doing | hip deg (gate 60-125) | knee deg (gate >=145) | admitted |
|---|---|---|---|---|
| 5.00-5.72 | sitting on the floor, legs flat on the floor | 89-95 | 172-180 | **yes, 19/19** |
| 7.00-7.40 | a real L-sit | 66-69 | 158-161 | yes |
| 16.20-16.80 | sitting again after the drop | 74-77 | 146-153 | **yes, 16/16** |

Sitting upright on the floor with straight legs has the SAME joint angles as an L-sit. The only
difference is elevation, and `admitPosture`'s L_SIT branch measures hip angle, knee angle, leg
angle from horizontal, trunk angle from vertical and wrist-below-shoulder — **not one of which is a
clearance test**. Worse: plain sitting scores closer to an ideal L-sit (hip ~90, knee ~178) than the
real hold does (hip ~67, knee ~160), so no tightening of these bands separates them; it would reject
the real hold first. The spurious fourth interval, 16.36-16.76, is the athlete sitting down.

CONSEQUENCE FOR SOMETHING ALREADY RECORDED: this is why the earlier L-sit clip `W92o2JmiTNI` was
filed as "hip clearance unverifiable". The gate never verified clearance on that clip either, so its
1-interval / 9.34 s result must not be read as an L-sit detection. Nothing published is invalidated,
because that clip is already marked `usable_for_evaluation: false` — but the reason recorded for it
was the clip, and the real reason is the gate.

**Priority 12 is NOT closed, and the blocker has moved.** It is no longer footage: `84WplmAbszc` is a
good recording, correctly labelled, with pose on 100% of the hold. What is missing is a clearance
measurement. Designing one is a contract change to `PostureAdmission` that would move published
numbers, so per the brief it is recorded for the owner rather than done. The obvious candidate — hip
height above the supporting hand/wrist, or above the ankle line, in torso lengths — is written down
here as a starting point, NOT as an adopted design, and it was chosen from the geometry of the
movement, not from any score.

SECOND, SMALLER FINDING, consistent with what Part 3 already established about events: the entry and
exit errors have opposite signs and both are definitional. My label marks the frame where the SHAPE
is first achieved (5.88) and last held (16.08); the engine marks where MOTION settles (6.28) and
where it resumes (15.80). The athlete's lift overshoots and settles, which the label notes
independently. This is the same shape-versus-motion distinction as the crossing-versus-endpoint one,
not a lag to be tuned away.

## Update 24 — session close (2026-09-12 autonomous session)
Plan items A, B, D, E all done; F done as far as it can go without an owner decision.
Final state: suite **874 tests / 94 suites, 0 failures / 0 errors / 0 skipped**.
`StaticSkillObservation.kt` sha256 53b0052d916022348026b5624365bd1d97eb1099ac6dd837ad2875cae3ed5b9a
(matches the post-mutation restore). Repo: the same 2 pre-existing tracked modifications, nothing
staged, no media or person-derived data anywhere in Git.

New tooling, all outside Git media rules and all diagnostic:
  `<sdd>/camera_motion.py`            is the camera fixed / panned / physically moved (parallax test)
  `<sdd>/hold_motion_breakdown.py`    splits the hold gate into a camera term and a body term
  `<sdd>/replay_holds_baseline.py`    three-way n1b0 / n3b0 / n3b250 comparison with byte-identity checks
  `offline-corpus/pose-extractor-20260912/`  rebuilt mediapipe 0.10.21 + av 18.1.0 + sha-pinned model
The first two need numpy: use `offline-corpus/poserac-feasibility-20260908/venv312/bin/python`.

New corpus artefacts (all additive, nothing overwritten):
  `offline-corpus/hold-baseline-20260912/`, `-confirm/`   the baseline runs and their regression proof
  `offline-corpus/lsit-84WplmAbszc-20260912/`             the L-sit run
  `youtube-cc-20260911/84WplmAbszc/labels-v1.json`        frozen label
  `youtube-cc-20260911-caches/84WplmAbszc-cache-v1/`      pose cache + metadata
  `youtube-cc-20260911-caches/*-hold-config-v3-*.json`    18 frozen configs

## Update 25 — wall sit `0xKuIvU6lVM` (held-out, MOVING camera): PRE-REGISTRATION
Purpose: `motionBaselineNs` was justified on the same six clips it was designed against. This is a
new athlete, a new scene, and the camera case most likely to break it — handheld.

CORRECTION TO UPDATE 17, made before any run. I screened this clip as having BOTH endpoints
censored. That is wrong: **the exit is observed** — he stands up at the end. Only the entry is
censored. Found by looking at every frame of the last 1.4 s, which the screening pass had not done.
The clip is therefore more valuable than I recorded, not less: it gives a real, scorable exit.

Label frozen before pose extraction and before any run:
`youtube-cc-20260911/0xKuIvU6lVM/labels-v1.json`, sha256
0caf9075c17f8ba09ad0cf536e0b3f5b6a53f58cb22438c829676e043250c856.
  hold 0.0-19.12 s, left_censored (opens mid-hold), exit_end 19.82, uncertainty 0.10.
An ambiguity is recorded in the label rather than resolved: the trunk starts pitching forward at
~18.95 s, ~0.17 s before the thigh leaves horizontal at 19.12 s. The thigh reading was DECLARED
primary in advance and is used; both numbers are written down so a scorer can see them.

`side` = LEFT, from the raw video (the athlete faces image-left, so his left side is toward the
camera). The convention itself was verified independently against all six existing clips: the
configured `side` matches the side MediaPipe sees with higher landmark visibility in every one, and
it also confirms the LEFT already used for 84WplmAbszc (0.988 vs 0.779). This will be re-checked
after extraction and reported if it disagrees.

Two configs frozen, identical but for the baseline: `-hold-config-v1-n3b0.json` and `-n3b250.json`.

PREDICTIONS, declared before extraction:
 (a) This clip fragments MORE than the fixed-camera wall sit `y6RrKS76SAg` (1 interval) under the
     same config, because the camera moves and the gate cannot tell camera translation from athlete
     translation using pose landmarks alone.
 (b) **W=0.25 will improve on W=0 but will NOT eliminate the fragmentation.** Stated plainly because
     it is a limit of the change, not a success of it: a fixed baseline suppresses JITTER, whose
     amplitude is fixed in space, and camera drift is SMOOTH, so it passes through both a one-frame
     and a fixed-baseline speed at roughly the same magnitude. Rough arithmetic from the measured
     ~40 px/s whole-frame motion on a torso of order 200 px: about 0.2 torso-lengths/s of purely
     camera-induced signal against a 0.30 gate, under either baseline.
 (c) Exit detected within +/-0.25 s of 19.12 s.
 (d) At least 80% of frames inside the labelled hold admitted by the WALL_SIT gate.
If (b) holds, the honest conclusion is that camera motion is a SEPARATE unsolved problem from jitter,
and one that pose landmarks alone cannot solve. Nothing will be re-tuned either way.

## Update 26 — the held-out run found a DIFFERENT defect, and my predictions were not tested
Run of `0xKuIvU6lVM` against the frozen label and configs. Output in
`offline-corpus/wallsit-0xKuIvU6lVM-20260912/`. Result: 2 fragments at W=0, 4 at W=0.25, longest
0.70 s, against a labelled 19.12 s hold. Far worse than anything in the corpus.

**The cause is not camera motion and not jitter. It is an over-broad validity check.**
Inside the labelled hold, the stillness path reports `INVALID_LANDMARK` on **472 of 574 frames**,
while posture admission accepts 462 of the same 574. The two components disagree about the same
frames, which is itself the smell.

`StaticSkillObservation.validate()` rejects the WHOLE frame if ANY of the 33 landmarks has x or y
outside [0,1] — including the 29 landmarks it never reads. It measures torso points 11/12/23/24
only. Measured over the frames with a pose:

| clip | frames with a pose | any landmark outside [0,1] | a TORSO landmark is the offender |
|---|---|---|---|
| 0xKuIvU6lVM | 600 | **498 (83%)** | 10 |
| y6RrKS76SAg | 752 | 0 | 0 |
| 84WplmAbszc | 299 | 0 | 0 |

The offenders on `0xKuIvU6lVM` are the near foot leaving the bottom edge: left foot-index 490,
left heel 439, left ankle 55. The torso is fine in 488 of the 498 frames.

WHY THIS WAS INVISIBLE UNTIL NOW: every clip in the existing corpus has the athlete entirely inside
the frame, so 0% of their frames trip it. It could only surface on new footage with a tighter crop.
That is the argument for held-out material in one line.

MY PREDICTIONS IN UPDATE 25 WERE THEREFORE NOT TESTED, and I am not claiming them:
 (a),(b),(c) are dominated by the defect and say nothing about `motionBaselineNs` or camera motion.
 (d) at least 80% admitted: 462/574 = 80.5%. HELD, but barely, and only for the admission path.
The intended experiment — does the baseline change survive a moving camera — still has not run.

### PRE-REGISTRATION of the fix, written before touching any code
Restrict the coordinate RANGE check to the landmarks the engine actually measures, matching what the
private `torsoPoints()` helper in the same file already does — the inconsistency is between two
functions in one file. Keep the duplicate-index and negative-index checks global, because those are
structural properties of the frame. Keep the finiteness check global too: a non-finite coordinate is
corruption, not a limb leaving the frame, and this change is deliberately the minimal one that
addresses the defect found.

Predictions:
 (e) Byte-identical output on ALL SEVEN clips that currently have one (the six frozen hold clips and
     84WplmAbszc), because 0% of their frames have an out-of-range landmark. If any one of them
     changes, the change is wrong and gets reverted, not rationalised.
 (f) On 0xKuIvU6lVM, `INVALID_LANDMARK` inside the labelled hold falls from 472 to about 10 — the
     frames where a torso landmark is itself out of range. It should NOT fall to 0.
 (g) Only after (e) and (f) hold can predictions (a)-(c) be tested. They are re-declared unchanged.

## Update 27 — PREDICTION (e) FAILED, and the failure was my measurement error
(e) said all seven clips with existing output would be byte-identical, "because 0% of their frames
have an out-of-range landmark", and committed to reverting if any changed.

**Two changed: RTg8r4EV0Pc (front lever) and I9wbS757cvM (planche).** The premise was wrong, and it
was wrong because **I measured three clips and asserted seven.** Recording that plainly: the number I
put in a pre-registration was not one I had measured.

Actual out-of-range rates, now measured on all eight:

| clip | posed frames | any landmark out of [0,1] | torso is the offender |
|---|---|---|---|
| y6RrKS76SAg, 2U8zGzbpJ84, W92o2JmiTNI, raAQB7ad24s, 84WplmAbszc | — | **0%** | 0 |
| I9wbS757cvM planche | 1897 | 331 (17%) | 0 |
| RTg8r4EV0Pc front lever | 3023 | 1383 (46%) | 524 |
| 0xKuIvU6lVM wall sit | 600 | 498 (83%) | 10 |

WHAT ACTUALLY CHANGED ON THE TWO CLIPS — reported before deciding anything:
  - **Front lever: the OUTCOME is identical.** 0 intervals before, 0 after; 0.00 s covered before and
    after; posture admission unchanged at 152. Only the REASON on already-rejected frames moved:
    INVALID_LANDMARK 1383 -> 524, with those frames now reported as BODY_LINE_NOT_ADMITTED (+447)
    and LOW_CONFIDENCE (+412). The engine rejects the same frames, for the true reason instead of a
    spurious one.
  - **Planche: a small real change**, and only at W=0.25: 18 -> 17 fragments, covered 22.69 -> 22.36 s.
    At W=0 it is unchanged (18 fragments, 16.48 s). This clip is labelled non-blind (disclosed) and
    is dev material; no evaluated claim rests on it.
So no published result on any evaluated movement changes. That is a fact worth stating, and it is
NOT a reason to say the prediction passed. It did not.

HOW I AM RESOLVING MY OWN STOPPING RULE, rather than explaining it away: the change stays in the
tree because it is correct on the merits and now has tests and a mutation check, but **I am not
declaring it adopted.** My pre-registered rule tripped, so the adoption decision goes to the owner
with the numbers above attached. See §9 of the state-and-plan.

Tests added: `aLimbOutsideTheFrameDoesNotDiscardAnOtherwiseUsableTorso`,
`aTorsoLandmarkOutsideTheFrameStillDiscardsTheFrame`, `aNonFiniteCoordinateAnywhereStillDiscardsTheFrame`.
Suite **877 / 94, 0 failures**. Mutation check, three mutants, each killed by exactly the test written
for it, source restored to sha256 3c5f5eb12f4db0bbef66f02446b7e9c829d024ca366207dbed606adcae9d7596:
  M5 drop the torso bounds check -> killed;  M6 restore the old global check (the defect) -> killed;
  M7 drop the global finiteness check -> killed.

## Update 28 — the held-out moving-camera test finally RAN, and (b) was right
Re-run of `0xKuIvU6lVM` on the fixed engine. Output in
`offline-corpus/wallsit-0xKuIvU6lVM-20260912-postfix/` (the pre-fix run is kept alongside it).

| baseline | fragments | longest | covered, of a 19.12 s labelled hold |
|---|---|---|---|
| W = 0 | 7 | 4.04 s | 6.47 s |
| W = 0.25 | **5** | **7.54 s** | **10.11 s** |

Against Update 25's predictions:
 (a) fragments more than the fixed-camera wall sit — **HELD** (5 versus 1).
 (b) W=0.25 improves but does NOT eliminate the fragmentation — **HELD**, and this was the point of
     declaring it in advance. Coverage rises 6.47 -> 10.11 s and the longest run nearly doubles, yet
     it is still 5 fragments and only 53% of the hold.
 (c) exit within +/-0.25 s of 19.12 s — **FAILED**. The last interval ends at 15.52 s, 3.6 s early.
     The hold is lost long before the athlete stands up, so this clip yields NO exit measurement.
 (d) at least 80% admitted — HELD at 462/574 = 80.5%, barely, and only on the admission path.

WHY, measured rather than argued. `hold_motion_breakdown.py` over 6.0-14.0 s, same units as the gate:

| term | median | p90 | over 0.30 |
|---|---|---|---|
| engine (absolute landmark speed, what the gate uses) | 0.177 | 0.501 | 53/239 |
| **camera alone, from background pixels** | **0.135** | 0.398 | **44/239** |
| deform (centroid-relative, translation-invariant) | 0.097 | 0.235 | 12/239 |

The camera by itself contributes a median of **0.135 torso-lengths/s — 45% of the 0.30 gate — and
puts 44 frames over threshold on its own**. Contrast the back lever's static camera: median 0.000,
max 0.087.

**CONCLUSION, and it is a limit of the work, not a win.** `motionBaselineNs` fixes JITTER, whose
amplitude is fixed in space, so a longer baseline divides it down. It cannot fix CAMERA MOTION, which
is smooth and therefore passes through any speed measure at roughly its true magnitude. These are two
different problems and only the first is solved. **Pose landmarks alone cannot separate camera
translation from athlete translation** — they are the same image motion — so no change confined to
the landmark stream can fix it.

The `deform` column shows what a translation-invariant measure would buy (53 -> 12 over threshold),
but it is NOT a drop-in replacement and I have not proposed it as one: a hold that fails by the whole
body sliding down the wall is a real failure, and a deformation-only measure would stop seeing it.
Separating "the shape changed" from "the whole body moved" needs the second signal to come from
somewhere other than the landmarks. Owner decision; recorded in §9.

## Update 29 — front-lever footage: BLOCKED, refusal recorded, not worked around
Search worked: yt-dlp flat-playlist over YouTube search with the Creative Commons filter
(sp=EgIwAQ%3D%3D) across four queries, and per-video metadata also worked. Five candidates chosen —
UJpP-PPsPRw, W9QCwWuJFKw, kepkpZlfBxQ, HkdFu1fZ8tY, _uJMBS3i6kg — all short, which is the best odds
of a fixed camera, one athlete and an uncut sequence. Three obvious hits (g0VMRY8IipA, 01IW58alJGI,
AdjQfYjVgW8) were DELIBERATELY SKIPPED: same channel as the already-failed RTg8r4EV0Pc, so almost
certainly the same rig and the same extreme upward angle, which would test nothing.

**YouTube demanded sign-in / cookie authentication on the first download. `fetch_youtube.py` stopped
by itself and was LEFT stopped.** No cookies exported, no browser profile read, no client spoofing,
no PO-token work, no network change, no retry loop. Nothing was downloaded.
Recorded at `offline-corpus/youtube-cc-20260912/blocked-attempt-record.json`, including the note that
the candidate titles are uploader claims kept only to identify the videos — nothing about angle,
count or quality is inferred from them, and every candidate would still need raw-frame screening.

ESCALATION: priority 9 (front lever) and the toes-to-bar third athlete stay blocked, and the
authorisation to download does not by itself unblock them. Only the owner can get past a sign-in wall.

## Update 30 — camera compensation: PRE-REGISTRATION, written before the measurement
Update 28 concluded that pose landmarks alone cannot separate camera translation from athlete
translation, and that a fix needs a signal from outside the landmark stream. `camera_motion.py`
already IS such a signal, measured from background pixels. This quantifies what it would buy.
DIAGNOSTIC ONLY — no engine change, nothing adopted.

Quantity: subtract the measured whole-frame camera displacement from each torso landmark's
displacement, then take the same max-over-four-points speed the gate uses. Unlike the `deform` column
this preserves GENUINE whole-body translation — an athlete sliding down the wall still registers —
which is the property a hold gate must not lose.

Sign convention pinned independently, by shifting a synthetic image a known amount rather than by
looking at any result: `phase_shift(prev, cur)` returns (-dy, -dx) of the true scene motion, so the
compensated displacement is (cur - prev) + (med[1], med[0]).

Predictions:
 (h) On the handheld wall sit 0xKuIvU6lVM, over-threshold falls a lot from the engine's 53/239, and
     lands ABOVE `deform`'s 12/239 — compensation removes the camera but keeps real athlete
     translation, which deform throws away.
 (i) THE CONTROL, and the falsifier. On the static-camera back lever raAQB7ad24s, compensated must
     be ~equal to engine (median within 0.02), because there is no camera motion to remove. **If
     compensation also "improves" the static clip, it is smoothing rather than compensating and the
     idea is wrong** — that would be the finding, and it would be reported as such.

## Update 31 — camera compensation: (h) FAILED, (i) HELD. A clean negative.
Pixel-derived camera compensation does NOT recover the handheld wall sit.

| variant | median | over 0.30 |
|---|---|---|
| engine (no compensation) | 0.177 | 53/239 |
| compensated, lag 0 | 0.204 | 63/239 |
| compensated, lag +1 | 0.184 | 57/238 |
| deform (translation-invariant) | 0.097 | 12/239 |

 (h) FAILED at both lags — compensation makes it WORSE, not better.
 (i) HELD. The static-camera control is untouched: back lever engine 29/110 -> compensated 27/110,
     median 0.209 -> 0.206. So it is genuinely compensating, not smoothing. The control is what makes
     the negative trustworthy.

TWO INSTRUMENT PROBLEMS FOUND AND FIXED ALONG THE WAY, neither of which rescued the result:
 1. `decode_gray` used `-ss t0 -to t1` and assumed decoded frame k paired with pose record k. It now
    uses select+showinfo and pairs BY TIMESTAMP, refusing to pair a record with no frame within half
    a frame interval. Same lesson as the contact-sheet rule. Re-running changed nothing here
    (240/240 paired identically), so it was not the cause.
 2. A column-index bug in the summary (`r[4]` still read as the note after `compensated` was
    inserted) crashed the per-frame print. Fixed.

THE LAG IS IN THE LANDMARK STREAM, NOT THE DECODER. Cross-correlating measured camera motion against
common-mode landmark motion peaks at **lag +1** (0.761/0.621) rather than 0 (0.689/0.619), and it
still does with pts-paired frames. The landmark stream trails true image motion by about a frame,
which is what MediaPipe's VIDEO-mode temporal tracking would do. The lag used above was chosen by
CORRELATION — an independent criterion with no reference to the threshold or any score — and not by
which value made the result look better. Compensating at it still failed.

WHY IT FAILS, stated as a limit: the camera estimate is integer-pixel, only ~0.7 correlated with the
common-mode landmark motion, and occasionally grossly wrong (max 9.1 px). Subtracting it injects more
error than it removes, and because the gate is a MAX over four torso points, one bad camera estimate
inflates all four at once and the max picks it up.

**This strengthens Update 28 rather than softening it.** Camera motion is a real, separate, unsolved
problem, and it is not cheaply solvable from background pixels at this precision. The signal that
would solve it has to be better than what the image itself gives — on a phone, the IMU is exactly
that: gyroscope gives camera rotation directly, at high rate, with no dependence on scene texture.
Recorded as the natural next avenue, NOT as an adopted design.

## Update 32 — a claim of mine did not survive checking, and the timing story is weaker than counts
Before wiring the offline engines into the app I re-read the underlying reports rather than the
summary table. **The state-and-plan row for straight-bar dip said "5/6 detected, 0 false positives".
The "0 false positives" is supported by nothing I can find.** `XTDc47D1G_I-report-v3-per-segment-side.json`
records matched 2, missed 4, **extra 3**, precision 0.40, recall 0.33 against the v2 labels. The 5/6
is a COUNT comparison (5 predicted vs 6 annotated) and counts are the sanctioned metric under rule 7;
the false-positive claim was an overstatement that had propagated into the summary. Row corrected.

A second thing the summary hides, visible in the timebase-corrected table in the results doc:

| clip | counts | match +/-0.25 s | +/-0.35 s | +/-0.5 s |
|---|---|---|---|---|
| MU ring | 3/3 | 0/3 | 0/3 | 2/3 |
| MU bar | 3/3 | 0/3 | 1/3 | 3/3 |
| HSPU back-to-wall | 5/5 | 1/5 | 1/5 | 2/5 |
| HSPU wall-facing | 5/4 | 0/4 | 0/4 | 0/4 |
| toes-to-bar | 6/7 | 2/7 | 5/7 | 6/7 |
| straight-bar dip | 5/6 | 4/6 | 5/6 | 5/6 |

**These engines count acceptably and place events poorly.** Muscle-up matches 0/3 at +/-0.25 s and
HSPU wall-facing 0/4 at every window tested. That is consistent with the definitional finding
already recorded — a threshold-crossing engine reports where it DETECTED an endpoint, roughly 0.3-0.5 s
before the movement actually ended. Any live UI must therefore surface counts and durations, and must
not present a repetition instant as accurate to better than about half a second. The adapter layer
carries this in its own KDoc so a caller cannot miss it.

## Update 33 — offline engines wired for live use (shared layer)
`OfflineMovementAdapters.kt`: one `OfflineMovementEngine` interface over seven engines, so a live
caller drives any of them with `(timestampNs, width, height, landmarks)` — which is exactly what
`ViewerMovementCoordinator` already holds before it narrows to arm measurements.

Offered: DIP, TOES_TO_BAR, HANGING_KNEE_RAISE, MUSCLE_UP, HANDSTAND_PUSH_UP (repetitions);
HANDSTAND_HOLD, BACK_LEVER (holds).

Every config is transcribed VERBATIM from the frozen file that produced the recorded result, each
cited in the KDoc by path. Nothing was invented or re-tuned for live use. `side` and `variant` are
exposed as parameters because they are properties of the RECORDING each config was frozen against,
not of the movement.

**Withheld, in code rather than only in a document, each with its measured reason**: L_SIT (gate
admits ordinary sitting), FRONT_LEVER (median 53.3 deg against a 30 deg gate), HANGING_STRAIGHT_LEG_RAISE
(documented 0/6 on a front view), TUCK_PLANCHE (measures the tuck, not the hold), PLANCHE (disclosed
dev material), WALL_SIT (failed the held-out handheld clip). A test asserts the offered and withheld
sets are disjoint and that every withheld entry carries a substantive reason, so promoting one
requires deleting its reason.

THE STANDING RISK, recorded so it cannot be discovered later as a surprise: these are FIXED-THRESHOLD
engines and the app's own arm-cycle engine instead CALIBRATES per person. The offline thresholds have
never been shown to transfer to another camera, athlete or frame rate, and the hold thresholds are
known not to be frame-rate invariant. Live output is an unvalidated port until it is measured against
a frozen label on this device.

8 tests. Mutation check, 3 mutants, all killed, file restored to sha256
2cd371a6409106e7171c5c59ae5000bc761d6a91df64d55ad1b2702a55f61b92:
  M1 swallow every rejection reason -> killed by 3;  M2 back lever claims REPETITION -> killed by 2;
  M3 quietly drop L_SIT from the withheld list -> killed by 1.
Suite 885 / 95, 0 failures. No app behaviour changed yet: this is the shared layer only.

## Update 34 — the Gradle wrapper was never proxy-blocked. The JDK did not trust the proxy.
Found while chasing seven Robolectric test failures. `curl` fetched the missing artifact with status
200; Java could not, failing with `PKIX path building failed`. curl uses the system trust store and
the JDK uses its own `cacerts`, which lacked the corporate root.

**This invalidates a diagnosis this project has carried since 2026-09-11 and used all session.** The
note in `docs/2026-09-12-state-and-plan.md` says "The Gradle wrapper (9.3.1) is blocked by a
corporate proxy. Use Homebrew Gradle with an explicit JDK; the wrapper files are deliberately
untouched." The proxy blocks nothing. Every Java HTTPS fetch was failing for want of a trusted root:
the wrapper distribution, and Robolectric's runtime `android-all-instrumented` fetch.

Fix, verified: build a truststore from JDK 21 `cacerts` plus the 176 certificates in the existing
`ca-bundle.pem`, and point JVMs at it through **JAVA_TOOL_OPTIONS** — not GRADLE_OPTS, because
Gradle forks test JVMs and Robolectric's fetch happens inside the fork.

    ./gradlew --version           -> Gradle 9.3.1, distribution downloaded to ~/.gradle/wrapper/dists
    :androidApp:testDebugUnitTest -> 268 tests, 0 failures (was 7 failing, all "Failed to fetch")

Stored at `offline-corpus/toolchain-20260912/` with a README. Outside Git: corporate certificates and
environment configuration, not source. `make verify-*` targets, which all call `./gradlew`, should
now work as written rather than needing the Homebrew substitution.

TWO SMALLER TOOLCHAIN FACTS from the same session, both previously unrecorded:
  * `--offline` fails for anything not already cached — the androidApp dependency graph and
    Robolectric were not. It was only ever safe for `:shared:jvmTest`.
  * `-Porg.gradle.java.installations.paths` is NOT enough to build the Android app. The JdkImageTransform
    runs `jlink` on the DAEMON's JVM, and Homebrew's default JDK 26 cannot transform Android's
    `core-for-system-modules.jar`. `JAVA_HOME` must also point at JDK 21.
Also installed SDK Build Tools 36.0.0, verified by size and SHA-1 against Google's `repository2-3.xml`
(79,121,749 bytes, sha1 199ae0047ee61e842f8ee0c6d3918e44fb9a1f83). 35.0.0, 36.1.0 and 37.0.0 were
present but AGP 9.1.0 wants exactly 36.0.0.

## Update 35 — offline movements wired into the live viewer (app layer)
`ViewerMovementSelection` makes the analyser choice explicit: `Calibrated` (the per-person pull-up
/push-up engine) or `Offline` (a fixed-threshold engine). They are separate types rather than one
list because that difference IS the risk.

The coordinator feeds whichever is selected, and **the calibrated experiment is not fed at all while
an offline movement is selected**, so its count cannot drift in the background and be misread as the
offline movement's. Gap and backwards-timestamp notifications are deliberately NOT forwarded to the
offline engines: each enforces its own `maximumGapNs` and timestamp ordering internally, so
forwarding would double-count a rejection the engine already makes.

Muscle-up carries its variant because the owner has both apparatus: BAR_KIPPING (grip drift 1.0) and
RING_STRICT (2.0), the two frozen configs differing only there.

APPARATUS CAVEAT RECORDED IN THE ENUM, not just here: the DIP config was frozen on a STRAIGHT-BAR dip
(`XTDc47D1G_I`). Ring dips are a different geometry — the rings rotate and the torso carries more
lean — so running it on rings is an untested transfer. It may work; nothing says it does.

7 coordinator tests, all passing. Full androidApp suite 268/36, 0 failures. Shared suite 885/95.

## Update 36 — picker UI: the two analyser families are labelled, not blended
`MovementViewerScreen` now offers both, under separate headings rather than as one list of nine:

  "Calibrated to you"   Pull-up, Push-up
  "Exploratory · fixed thresholds, not calibrated to you"
                        Dip (straight bar), Knee raise, Toes to bar, Muscle-up,
                        HSPU (wall), Handstand hold, Back lever

The heading is the honest part. These engines carry thresholds frozen from recorded footage of other
people and have never been fitted to this athlete, camera or frame rate. Selecting one shows a
caption in the error colour saying counts and durations only, and that repetition instants are not
accurate to better than about half a second — which is what the corpus measured (muscle-up 0/3 at
+/-0.25 s, HSPU wall-facing 0/4 at every window).

DIP NAMES ITS APPARATUS IN THE CHIP — "Dip (straight bar)" — and selecting it swaps the caption for
one saying the config was frozen on a straight bar and that rings are an untested transfer. The
owner has rings, so a chip reading just "Dip" would have been quietly misleading.

The two families are mutually exclusive: `offlineMovement != null` deselects the calibrated chips, so
the screen can never show both as active while only one analyser is fed.

Built and tested through **`./gradlew`**, the wrapper, for the first time in this workstream — see
Update 34. Shared 885/95, androidApp 268/36, both 0 failures. Installed with `adb install -r`; the
22 existing feedback sessions survived.

## Update 37 — DEFECT FOUND BY THE OWNER: the selection reached the analyser and never reached the UI
Reported: "on selecting knee raises, i am still shown pull ups in internal flow". Correct, and it was
my defect from Update 36. I wired which engine is FED and did not wire what the screen DISPLAYS.

Everything downstream of the chip read `movementState.experiment` — the CalibratedArmCycleExperiment
— which stays at PULL_UP/IDLE while an offline movement is selected because it is deliberately not
fed. So the chip highlighted "Knee raise" while the panel ran the calibrated flow underneath:
"Let's set up", "Raise your selected arm", "Practice one pull-up", arm choice, confirm-practice. The
summary line said "Setup / practice · counting is OFF" for the same reason.

That is worse than a cosmetic bug. It instructed the athlete to perform a pull-up practice rep for an
engine that has no practice stage and was measuring knee raises.

FIX, in three parts:
 1. `MovementPanelState` carries `offlineMovement` and `offline`; `MovementMeasurementsPanel` returns
    early into a separate `OfflineMovementPanel`. The exploratory engines have no calibration, no
    practice rep and no arm choice, so none of that flow applies and none of it is shown. The panel
    shows count or hold duration, phase, the engine's own rejection enum, and a reset.
 2. `movementSummary` no longer borrows the calibrated stage wording when an offline movement is
    selected.
 3. All THREE `MovementPanelState` construction sites now go through one `selectedOfflineMovement()`
    helper. The bug existed because the sites were written separately and one was missed; a single
    source is why it cannot recur site-by-site.

REGRESSION TEST, and verified to actually catch it: `OfflineMovementPanelRoutingTest`, 3 Robolectric
tests. Reverting the fix (dropping the two arguments from `refreshMovementPanel`) makes **all three
fail**; restoring it makes all three pass, with the activity file restored to
sha256 d560a5344b256ae9524f4ad777c556345a24131fa6eb3952948316e58afeb4ce. One of the three walks every
OfflineMovement rather than a single example, because the defect was per-construction-site.

Shared 885/95, androidApp 271/37, 0 failures. Installed; the 22 feedback sessions survived.

LESSON WORTH KEEPING: the adapter tests, the coordinator tests and the mutation checks all passed
while this was broken, because every one of them stopped at the coordinator boundary. Nothing tested
that the selection was RENDERED. A seam is not wired until something asserts the far side of it.

## Update 41 — a wrong diagnosis, and the instrument that would have prevented it (2026-09-12)

The owner reported knee raises not counting. I answered with a mechanism instead of evidence, and
the mechanism was wrong.

CORRECTION 1. I claimed the viewer feeds a "front view" to a side-view engine because
`MovementViewerController` requests `LENS_FACING_FRONT`. Lens facing selects a sensor; it says
nothing about where the phone stands or which way the athlete faces. A front-facing camera propped
to the athlete's side records a side view. Two unrelated senses of "front", collapsed into one. The
front camera is kept, per ADR-0006, which never made a viewpoint claim in the first place.

CORRECTION 2. I carried the Priority 8 negative (hanging straight-leg raise, 0/6) across to explain
this. That negative's dominant cause was projected knee angle under a 140 deg gate. The frozen
knee-raise config sets `raisedKneeMinDegrees = 0.0`, so the knee test is vacuous and
KNEE_FLEXION_REJECTED is unreachable: it discriminates on hip angle alone. Straight-leg evidence
cannot explain a bent-knee miss under a config with no knee gate. Worse for my hypothesis, Priority
8 records the hip angle *falling to 0.1-5 deg* from the front through projective degeneracy -- under
a hip-only config that points toward spurious passing, not silence. The hypothesis did not even
predict the observed symptom.

WHY THERE WAS NO EVIDENCE. Two failures, both mine.
  (a) Two clocks bound a session and only the longer was visible. The 30-minute marker had lapsed;
      separately `startFeedback` sets the recorder's deadline to 180 s, so a status line reading the
      30-minute window alone can say "on" while nothing is written.
  (b) The diagnostic log recorded only `PullUpExperimentState`, which is deliberately not fed while
      an offline engine runs. Every row said PULL_UP/IDLE regardless of the movement. This is the
      SAME defect as the panel-routing bug in Update 40, one layer over: I fixed what the screen
      displayed and never looked at what the log wrote. Update 40's lesson -- a seam is not wired
      until something asserts the far side of it -- had a second far side I did not check.

DONE.
  - In-app "Start 30-minute debug session" with ACTIVE/EXPIRED/OFF, both deadlines, session id,
    dropped-row count, and Stop. Writes the same private marker as `adb run-as`, same 30 minutes,
    debug builds only. Expiry preserved; no automatic renewal anywhere.
  - `OfflineMovementUpdate` gains `cycleOutcome` and `metrics` (projected geometry, by the engine's
    own field names; absent rather than zero when unmeasured). All seven adapters populate it.
  - Log rows carry `offline_movement`, `offline_phase`, `offline_reason`, `offline_cycle_outcome`,
    `offline_reps`, `offline_counted`, hold durations and `off_*` geometry. Offline state joins the
    duplicate-suppression signature -- otherwise a changing rejection under a static pull-up state is
    dropped as a repeat. Angles quantize at 0.5 deg, ratios at 0.005 (0.5 would erase a 0.35 gate).
  - Framing instruction on all seven exploratory movements: "Place the phone to your side. Keep your
    full body and the bar visible." All seven, because each config was frozen from one viewpoint and
    none has been checked from another -- handstand hold included, which must not be called
    view-agnostic without evidence.

TESTS. shared 887 (+2), androidApp 273 (+2), 0 failures, counts read from JUnit XML. Two mutants:
dropping offline state from the dedup signature kills `offlineRejectionChangeSurvivesTheRateSuppressor`;
emptying `metricsOf` kills `aMeasuredKneeRaiseFrameReportsTheGeometryItsGatesUse`. Both sources
restored byte-identical (`f8f9efaf...`, `dd7472a2...`).

NOT DONE, EXTERNAL BLOCKER. The capture itself. The phone dropped off wireless debugging mid-work
(`adb connect 192.168.1.12:42475` refused, `adb mdns services` empty), so the new build is not
installed and no session has been observed arriving at the collector. NOTHING is claimed about why
the knee raises did not count.

NEXT ACTION. Reconnect the phone, install, press Start, confirm a NEW session id is reaching the
collector BEFORE asking for an attempt, then capture one short authorized set and read
`offline_reason`, joint availability, `off_hip_deg` / `off_support_rise` / `off_trunk_elevation_deg`
and `offline_cycle_outcome` to separate tracking loss, viewpoint limitation, configuration mismatch
and cycle-logic failure. No threshold moves before that.

## Update 42 — decimation study: PRE-REGISTRATION (2026-09-16, written before the run)

Declared before any decimated replay is executed, per method rule 2. The commit carrying this entry
precedes the commit carrying results.

**Question.** Every frozen config was derived from footage at its clip's native rate. The device pose
pipeline delivers ~10 fps. How much of the live 0-count failure on 2026-09-15 is explained by that
gap alone, and which engines are structurally fragile rather than merely mis-tuned?

**CORRECTION to `PROJECT-STATE.md` §7, made before running.** That section says "every frozen config
was derived from 30 fps footage". Measured from the caches, that is not true of all of them:

| clip | movement | frames | source rate |
|---|---|---|---|
| `yTczZRbcfJU` | muscle-up ring | 583 | 30.0 fps |
| `k71s4T2_CJI` | muscle-up bar | 617 | 30.0 fps |
| `cLTvfDlhiXM` | HSPU back-to-wall | 443 | 30.0 fps |
| `tz_YVynl1sk` | HSPU wall-facing | 626 | 30.0 fps |
| `C0RXOvy609I` | hanging knee raise | 1611 | 30.0 fps |
| `wZ6bjrOjbCI` | toes-to-bar | 1556 | 30.0 fps |
| `TcU7wtLdIx4` | straight-leg raise | 520 | **24.0 fps** |
| `XTDc47D1G_I` | straight-bar dip | 1536 | **25.0 fps** |

So the live gap is 3x for most movements but **2.5x for dip** and 2.4x for the straight-leg raise.
The qualitative claim stands; the arithmetic in that section is corrected here rather than edited
there, and `PROJECT-STATE.md` will be updated to point at this entry.

**Method.** No engine, config or label changes. Only the INPUT is decimated. Each cache's
`poses.jsonl` is resampled to a target interval by keeping a frame when its timestamp is at least
`interval - tolerance` after the last kept frame, tolerance = half the source interval. From a 30 fps
grid a 20 fps target therefore yields an alternating 33/67 ms pattern averaging 50 ms — which is what
a 20 fps sampler over a 30 fps source actually produces, not an idealised uniform 50 ms. Frozen v1
configs are used unchanged and hash-bound, as are labels and the Kotlin sources.

**Rates.** Native (control), 20, 15, 10 fps.

**Predictions, declared now.**

(a) **CONTROL.** At the native rate every job reproduces its recorded count exactly. If any does not,
    the harness is wrong, the run is void, and nothing else in it may be interpreted. This is a
    stopping rule, not a caveat.
(b) Counts fall or stay equal as the rate falls. **No movement gains counts at a lower rate.** A gain
    would be a genuine surprise and must be investigated, not explained away.
(c) Fragility follows movement speed: hanging knee raise and muscle-up degrade most; HSPU and dip
    least.
(d) At 10 fps, specifically:
    - hanging knee raise `C0RXOvy609I`: 6 counted natively -> **<= 3**
    - muscle-up, both clips: 3 counted natively -> **<= 2** each
    - HSPU, both clips: 5 counted natively -> **>= 4** each
    - dip `XTDc47D1G_I`: 5 counted natively -> **>= 4**
    - toes-to-bar `wZ6bjrOjbCI`: **<= 4**
    - straight-leg raise `TcU7wtLdIx4`: 0 counted natively -> **0 at every rate**, because it fails
      on a knee-angle gate and foreshortening, which sampling neither causes nor cures. This is the
      negative control: if it moves, the study is measuring something other than what it claims.
(e) **Mechanism.** Degradation appears as missed endpoints and, for the hanging knee raise, as
    `ABANDONED` cycle outcomes — not as false positives. If extras rise, the explanation is wrong.

**What this cannot settle.** It says nothing about whether the live device failures had additional
causes beyond rate; the device sessions are separate evidence and are deliberately not used to fit
anything here. It also cannot distinguish "the config is wrong for this athlete" from "the rate is
too low" on any clip the athlete did not perform.

## Update 43 — decimation study: run 01 VOID, amendment before re-running (2026-09-16)

**Run 01 is void.** The pre-registered control in Update 42(a) failed, and that entry made failure a
stopping rule rather than a caveat, so none of its rate numbers are interpreted here or anywhere.

| job | native count | recorded | control |
|---|---|---|---|
| `mu-ring-yTczZRbcfJU` | 3 | 3 | **pass** |
| `mu-bar-k71s4T2_CJI` | 3 | 3 | **pass** |
| `hspu-back-cLTvfDlhiXM` | 5 | 5 | **pass** |
| `hspu-facing-tz_YVynl1sk` | 5 | 5 | **pass** |
| `kneeraise-C0RXOvy609I` | 0 | 6 | **FAIL** |
| `slr-TcU7wtLdIx4` | 0 | 0 | pass (negative control) |
| `t2b-wZ6bjrOjbCI` | crash | 6 | **FAIL** |
| `dip-XTDc47D1G_I` | 3 | 5 | **FAIL** |

Four controls passed, which says the harness mechanism — decimation, JVM replay, scoring — is sound.
The three failures are all **my asset selection**, not the engines:

1. **Knee raise and toes-to-bar: I used the parent clips.** Both recorded results were obtained on a
   **right-half crop** (`C0RXOvy609I-right-cache-v1`, `wZ6bjrOjbCI-right-cache-v1`) because the
   parent frame shows the same movement twice — front view left, side view right — and a
   single-pose extractor cannot be trusted on it. That is already recorded in the results document,
   and I picked the parent anyway. Corrected to the derived panels and their matching configs
   (`C0RXOvy609I-right-hlr-config-v1.json`, `wZ6bjrOjbCI-right-t2b-config-v2observable.json`).
   The toes-to-bar crash was the same mistake: a config schema mismatch from the wrong file.

2. **Dip cannot be controlled by this harness at all, and that is a protocol fact, not a bug.** Its
   published count comes from a **per-segment side rule** — segments A and B scored LEFT, segment C
   RIGHT, availability decided per uncut segment. One config over the whole clip cannot reproduce it
   and should not be expected to.

**Amendment, declared before the corrected run.** Dip is retained, but scored against a
**study-local native baseline** — the count this single-config protocol produces at the native rate —
and **not** compared to labels or to the published 5/6. The question it answers is narrowed to "is
dip count rate-sensitive under a fixed protocol", which is the study's actual question. Any dip
number from this study must never be quoted as the dip result; they are different protocols.

**Every prediction in Update 42 stands unchanged**, except that 42(d)'s dip line is now read against
that study-local baseline rather than the published 5. No threshold, config or engine is touched. The
corrected run is run 02.

## Update 44 — decimation study: RESULTS (2026-09-16, run 03)

Predictions in Update 42, amended in Update 43, both committed before this run. Artefacts:
`offline-corpus/decimation-study-20260916/run-03/` (poses, predictions, logs, `summary.json`).

**Run 02 also had a defect and was superseded by run 03.** Its decimator kept a frame once
`interval - tolerance` had elapsed, tolerance = half the source interval. That admits *every* frame
whenever the target interval is under 1.5 source intervals, so the "20 fps" column was silently the
native stream, and dip's "10 fps" was really 12.5 fps. Run 03 uses grid sampling — take the first
frame at or after each target grid time — and now **reports the achieved mean rate per cell**, so
this cannot hide again. Achieved rates in run 03 are 19.98-20.00, 14.99-15.00 and 9.99-10.00.

**Control (42a): PASSES.** Every scoreable job reproduces its recorded count at its native rate:
muscle-up 3 and 3, HSPU 5 and 5, hanging knee raise 6, toes-to-bar 6, straight-leg raise 0.

### Counts by rate

| job | config sample params | native | 20 fps | 15 fps | 10 fps |
|---|---|---|---|---|---|
| muscle-up ring | endpoint 2, ref 5, gap 250 ms | 3 | 3 | 3 | 3 |
| muscle-up bar | endpoint 2, ref 5, gap 250 ms | 3 | 3 | 3 | 3 |
| toes-to-bar | endpoint 2, gap 250 ms | 6 | 6 | 6 | 6 |
| dip *(study-local)* | endpoint 2, gap 250 ms | 3 | 3 | 3 | 3 |
| HSPU back-to-wall | **ref 15**, gap 250 ms | 5 | 3 | 4 | **0** |
| HSPU wall-facing | **ref 15**, gap 250 ms | 5 | 4 | 5 | **1** |
| hanging knee raise | **endpoint 3**, **gap 100 ms** | 6 | 2 | 2 | **0** |
| straight-leg raise *(negative control)* | endpoint 3, gap 100 ms | 0 | 0 | 0 | 0 |

### The central prediction was WRONG

**42(c) said fragility follows movement speed. It does not.** The muscle-up is the most explosive
movement in the set and is *completely immune* — identical counts at every rate. Toes-to-bar is fast
and immune. The HSPU is a slow press and collapses hardest. Speed predicts nothing here.

**What actually predicts fragility is the config's sample-count parameters**, which are frame-rate
dependent by construction:

- `minimum_endpoint_samples = 2`, `minimum_reference_samples = 5` -> **immune** (4 of 4 jobs).
- `minimum_reference_samples = 15` -> **collapses**. `REFERENCE_UNAVAILABLE` rises from 5% of frames
  natively to **85%** at 10 fps on the back-to-wall clip. Fifteen consecutive valid frames is 0.5 s
  at 30 fps and 1.5 s at 10 fps, and with the clip's existing `NO_POSE`/`INVALID_LANDMARK` dropouts
  a 1.5 s clean run is simply unattainable.
- `minimum_endpoint_samples = 3` -> **collapses**. Three consecutive endpoint samples is 100 ms at
  30 fps and 300 ms at 10 fps, so a raise that does not pause at the top is never confirmed.

Prediction scorecard: 42(a) control **pass**; 42(d) hanging knee raise **pass** (predicted <= 3, got
0) and straight-leg raise **pass** (0 at every rate, negative control holds); 42(c), and 42(d) for
muscle-up (predicted <= 2, got 3), HSPU (predicted >= 4, got 0 and 1) and toes-to-bar (predicted
<= 4, got 6) all **fail**. Two of six specific predictions correct.

**42(b) is half wrong and the half that fails matters.** No rate ever exceeds the native count, as
predicted. But the relationship is **not monotonic**: HSPU back-to-wall runs 5 -> 3 -> 4 -> 0 and
wall-facing 5 -> 4 -> 5 -> 1. Which particular frames survive decimation interacts with where the
reference window happens to land, so a single lower rate can score better than a higher one.
**Consequence: a one-rate measurement can mislead about an engine's rate sensitivity**, and any
future check of this kind must sweep rather than sample one point.

**42(e) holds.** Extras never rise as counts fall — degradation is missed endpoints, never false
positives. Knee raise extras go 6 -> 2 -> 2 -> 0 alongside its counts.

### A fragility the table hides

`CAPTURE_GAP` never fires for the hanging knee raise at any rate, because 3 x 33.33 ms = 99.999 ms
sits just under its 100 ms limit. The straight-leg raise, whose source is 24 fps rather than 30, trips
it **57 times** at 10 fps. So the 100 ms gap limit is not merely tight at 10 fps — it is on the wrong
side of the boundary for any source whose rate is not a clean multiple. That is luck, not design.

### What this means for the plan

**Raising the device frame rate alone does not fix this.** At 20 fps — roughly the best the 38 ms
inference budget allows — the hanging knee raise still counts **2 of 6**, and HSPU back-to-wall 3 of
5. Plan step 4 was necessary but is nowhere near sufficient, and had the study not been run it would
have been attempted first and judged a failure for the wrong reason.

**Plan step 5 is now the primary fix and is precisely targeted.** `minimumEndpointSamples` and
`minimumReferenceSamples` must become durations; that single change addresses both collapse modes.
`maximumGapNs` needs review for the same reason. Four engines need nothing.

**Not done here:** no threshold, config or engine was changed, and the 2026-09-15 device sessions
were not used to fit anything. Whether the live dip and knee-raise failures have causes beyond rate
remains open — the dip failure in particular was a `shoulder_above_wrist` admission failure, which
this study says nothing about, and dip is rate-immune.

## Update 45 — frame-rate independence: PRE-REGISTRATION (2026-09-21, written before the change)

Declared before any engine edit, per method rule 2. The commit carrying this entry precedes the
commit carrying the code and the run that tests it.

**Question.** Update 44 established that fragility is set by the configs' sample-count parameters,
not by movement speed. A sample count is a frame-rate-dependent way of expressing a duration. Can
those parameters be read as the durations they were always expressing, without moving a single
published native-rate number?

**Audit — every sample-count parameter in the seven offline movements.**

| parameter | engines | frozen value | the duration it expresses at 30 fps |
|---|---|---|---|
| `minimumEndpointSamples` | Dip, ToesToBar, MuscleUp, HSPU (4 via `ElbowCycleTiming`) | 2 | 33.333 ms |
| `minimumEndpointSamples` | HangingLegRaise | 3 | 66.667 ms |
| `minimumReferenceSamples` | MuscleUp | 5 | 133.333 ms |
| `minimumReferenceSamples` | HSPU | 15 | 466.667 ms |
| `minimumNonHoldingSamplesToClose` | HandstandHold | 3 | 66.667 ms |

**The change.** One transformation, applied five times. No config file is touched and no config
field is added or removed, so every frozen v1 config stays byte-identical:

    internal const val REFERENCE_FRAME_INTERVAL_NS = 33_333_333L        // 30 fps
    internal fun sampleCountAsDurationNs(samples: Int) = (samples - 1) * REFERENCE_FRAME_INTERVAL_NS

- **Endpoint runs** confirm when `samples >= 2 && (t - runStart) >= holdNs` instead of
  `samples >= N`. The 2-sample floor keeps the debounce: a single frame can still never confirm an
  endpoint.
- **Reference windows** keep the *minimal covering window* — evict the oldest sample only while the
  window would still span `>= referenceNs` without it — and fire on `size >= 2 && span >= referenceNs`
  instead of `size == N`. The spread gate is unchanged and is computed over whatever the window holds.

**Why 30 fps is the conversion rate, stated as a choice rather than a discovery.** Update 42
measured the corpus: native rates run 23.98–30.00 fps, so 30 is the fastest. Converting at the
fastest rate yields the *shortest* duration, which is the most permissive reading — it is the choice
that cannot make any engine stricter than it is today on any corpus clip. It is not a claim that
every frozen count was tuned on 30 fps footage; dip was tuned at 25 and the straight-leg raise at
23.98.

**Deliberately NOT changed, with reasons.**

1. **`maximumGapNs`.** Already a duration; nothing to convert. Its knee-raise value of 100 ms is
   nonetheless unprincipled — at 10 fps from a 30 fps grid the step is 99,999,999 ns, under the
   limit by 1 ns, while the 23.98 fps straight-leg clip trips it 57 times. That is luck, not design.
   Changing the value is a v1 config edit and belongs to step 6, not here.
2. **The `LOWERED` -> raised one-frame `ABANDONED` guard.** `PROJECT-STATE.md` §9 step 5 proposed
   using the capture gap here — "if no frames were dropped, the motion really was that fast". That is
   wrong for this step, and recording why is the point of this entry: at the native rate frames are
   also not dropped, so the same relaxation fires there too and moves published numbers by
   construction. It is a change of meaning, not a change of units, and it cannot be done under a
   byte-identity gate. Deferred to step 6 with labelled data.
3. **`JumpingJack`, `Squat`, `Lunge`, `Situp`.** Outside the frozen offline set, not covered by the
   decimation study, and their practice-search helpers reason in sample counts. Changing them would
   be unevidenced. The codebase therefore carries two semantics until they are covered; that is a
   known, deliberate debt rather than an oversight.

**Verification method.** Run 04 of `decimate_study.py`, same eight jobs, same four rates, same
hash-bound caches, labels and configs as run 03. Only the Kotlin engines differ.

**Predictions, declared now.**

(a) **CONTROL — byte identity.** At the native rate, every job's `predictions_sha256` equals run
    03's, for all eight jobs. Not the counts: the full prediction stream. If any differs, the change
    is not the pure generalisation claimed here, the run is void, and no other number in it may be
    interpreted. This is a stopping rule.
(b) **The four `minimumEndpointSamples = 2` jobs are unchanged at every rate**, not just at native —
    dip 3/3/3/3, toes-to-bar 6/6/6/6, muscle-up 3/3/3/3 both clips. Two samples already span a full
    frame interval at any rate at or below 30 fps, so the rule is a no-op for them. If any of these
    twelve cells moves, the transformation is not equivalence-preserving where it claims to be.
(c) **Hanging knee raise `C0RXOvy609I`** — natively 6; run 03 gave 2 / 2 / 0 at 20 / 15 / 10 fps.
    Predicted now: **>= 4 at 15 fps, >= 3 at 10 fps, >= 3 at 20 fps.** 20 fps is predicted lowest of
    the three because a 20 fps grid over a 30 fps source alternates 33/67 ms steps, so only half its
    two-sample runs clear 66.667 ms.
(d) **HSPU, both clips** — natively 5; run 03 gave 3 / 4 / 0 (back) and 4 / 5 / 1 (facing).
    Predicted now: **>= 3 at every rate on both clips**, and `REFERENCE_UNAVAILABLE` at 10 fps falls
    from ~85% of frames to **< 30%**.
(e) **Negative control.** Straight-leg raise `TcU7wtLdIx4` stays **0 at every rate**. It fails on a
    knee-angle gate and foreshortening; units of debounce neither cause nor cure that.
(f) **No cell exceeds its native count.** This relaxes a gate, so over-counting is the risk. A count
    above native at any reduced rate falsifies the claim that this only restores lost repetitions.
(g) **Mechanism for whatever is still lost.** Any repetition the knee raise still misses at 10 fps
    appears as an `ABANDONED` cycle outcome — the unfixed guard of exclusion 2 — and **not** as a
    missed endpoint. If the residue is missed endpoints instead, the diagnosis in Update 44 was
    incomplete and the duration conversion is not the whole fix.

**What this cannot settle.** It does not touch the live dip failure, which was a
`shoulder_above_wrist` admission failure on a rate-immune engine and needs its own explanation. It
says nothing about whether any frozen threshold is *right* — only that its units are now consistent.
`HandstandHold` is included in the transformation but has **no** scoreable job in this study, so its
only evidence is unit tests; that is weaker and is not to be reported as study-verified.

## Update 46 — frame-rate independence: RESULTS (2026-09-21, run 04)

Tests the change pre-registered in Update 45. Run 04 of `decimate_study.py`, same eight jobs, four
rates, same hash-bound caches, configs and labels as run 03 — every `config_sha256` and
`cache_poses_sha256` compared equal. Only the Kotlin engines differ.

**(a) CONTROL — PASSED, and this is the result that matters most.** At the native rate all eight
jobs produced a **byte-identical `predictions_sha256`** to run 03. Not merely the same counts: the
same prediction stream, frame for frame. Converting five sample counts into the durations they
express is therefore a pure generalisation at the rate the configs were frozen at. No published
number moved.

| job | native | 20 fps | 15 fps | 10 fps |
|---|---|---|---|---|
| muscle-up ring `yTczZRbcfJU` | 3 -> 3 | 3 -> 3 | 3 -> 3 | 3 -> 3 |
| muscle-up bar `k71s4T2_CJI` | 3 -> 3 | 3 -> 3 | 3 -> 3 | 3 -> 3 |
| toes-to-bar `wZ6bjrOjbCI` | 6 -> 6 | 6 -> 6 | 6 -> 6 | 6 -> 6 |
| dip `XTDc47D1G_I` *(study-local)* | 3 -> 3 | 3 -> 3 | 3 -> 3 | 3 -> 3 |
| HSPU back `cLTvfDlhiXM` | 5 -> 5 | 3 -> 3 | 4 -> 4 | 0 -> **2** |
| HSPU facing `tz_YVynl1sk` | 5 -> 5 | 4 -> **5** | 5 -> 5 | 1 -> **0** |
| knee raise `C0RXOvy609I` | 6 -> 6 | 2 -> 2 | 2 -> **3** | 0 -> **1** |
| straight-leg raise `TcU7wtLdIx4` | 0 -> 0 | 0 -> 0 | 0 -> 0 | 0 -> 0 |

Four cells improved, one regressed, nineteen unchanged.

**Scorecard against Update 45.**

- **(a) control — HELD**, byte-identically, 8 of 8.
- **(b) the four `minimumEndpointSamples = 2` jobs unchanged at every rate — HELD, 12 of 12 cells.**
  The transformation is exactly a no-op where it was claimed to be one.
- **(c) knee raise >= 4 at 15 fps, >= 3 at 10 and 20 fps — FAILED on all three.** Actual 3, 1 and 2.
- **(d) HSPU >= 3 at every rate on both clips — FAILED.** Back-to-wall gives 2 at 10 fps; wall-facing
  gives 0. The *mechanism* half of (d) held emphatically: `REFERENCE_UNAVAILABLE` at 10 fps
  back-to-wall fell from **85% of frames to 5%** (predicted < 30%).
- **(e) negative control — HELD.** Straight-leg raise stayed 0 at all four rates.
- **(f) no cell exceeds its native count — HELD.** Nothing was over-counted.
- **(g) the residue appears as `ABANDONED` — FALSIFIED**, and the falsification is the useful part.
  At 10 fps the knee raise closes **no** rejected cycles at all: outcomes are `{COUNTED: 1}` and
  nothing else. The engine never opens a candidate, because it never confirms the lowered endpoint
  it must depart from. Phase census at 10 fps, run 03 -> run 04: `RAISING` 0 -> 4, `RAISED` 0 -> 17,
  `LOWERING` 0 -> 4. The machine now moves where it previously could not, and still stalls earlier
  than an `ABANDONED` outcome would require.

**The reference conversion is quantitatively right.** Reconstructing the fixed reference span from
each stream (`span / span_ratio`):

| clip | native reference | 10 fps, run 03 | 10 fps, run 04 |
|---|---|---|---|
| HSPU back | 0.2852 at 1.8 s | **never established** | 0.2846 at 1.9 s (**0.2%** from native) |
| HSPU facing | 0.2766 at 4.0 s | 0.2504 at 5.61 s (9.5% low) | 0.2744 at 4.1 s (**0.8%** from native) |

A quantity that used to be unobtainable or 9.5% wrong at 10 fps is now within 1% of its native value
and settles at the same instant. That is the whole of what the units change claimed to do.

**Why the counts still fall — measured, not inferred.** The knee raise's two endpoint dwells are not
symmetric. Measured on the native stream: the **raised** dwell has a median of 1750 ms, the
**lowered** dwell a median of **200 ms**, a lower quartile of 100 ms and a minimum of 67 ms. Counting
the usable observations each lowered dwell yields:

| | dwells yielding a single sample |
|---|---|
| native (30 fps) | 2 of 15, both in the final 0.4 s of the clip |
| 10 fps | **5 of 13** |

A cycle must confirm a lowered endpoint before departure and again on return. Five of thirteen
chances at 10 fps produce exactly **one** observation, and one observation is indistinguishable from
a single-frame noise spike — which is precisely what the retained two-sample floor exists to reject.

**This is an information limit, not a threshold to retune.** Debouncing against single-frame noise
and resolving a 67 ms dwell are the same measurement, and at 10 fps they cannot be told apart by any
choice of units or constants. Dropping the floor to one sample would make every noise spike an
endpoint, and would move native-rate results.

**CORRECTION to Update 44's implication, and to `PROJECT-STATE.md` §9.** Update 44 concluded that
raising the device rate "will not fix it" and demoted that work to secondary. Half of that stands —
rate alone was never the whole story, and the HSPU reference proves units were a real and separable
defect. But the half that said the units change would be sufficient is **wrong**. The short lowered
endpoint cannot be resolved at 10 fps at all, so step 4 is load-bearing again, and the ordering in
§9 is revised accordingly rather than edited in place here.

**Not explained.** HSPU wall-facing at 10 fps fell from 1 to 0 even though its reference is now
within 0.8% of native and it has *more* endpoint frames than before (extended 79 -> 84, lowered
51 -> 61). The reference is no longer the constraint there and the new one has not been isolated. It
is recorded as open rather than given a plausible-sounding cause.

**Verification carried out alongside the run.** `shared` 892 tests and `androidApp` 280 tests, 0
failures, counted from the JUnit XML rather than from "BUILD SUCCESSFUL". Five new tests in
`FrameRateIndependenceTest` pin the property directly — one knee raise counted exactly once at 30,
25, 24, 20, 15, 12 and 10 fps; the endpoint hold still suppressing the count at every rate when
widened past the dwell; a lone frame never confirming an endpoint however long the pause before it;
and the handstand reference settling inside the same 466.7 ms window at 30 and 10 fps. A mutation of
`sampleCountAsDurationNs` to `0L` fails three of those five; the file was restored and re-hashed
byte-exact (`bd845554...f1af795`).

**A defect in the tests, exposed rather than introduced.** All 47 initially failing tests were
synthetic fixtures clocked at **100 ns per frame** — a spacing no camera produces. They could never
have caught a frame-rate dependence, because in them time carried no meaning. They now address
frames in ticks of one 30 fps interval. Every sequence and every assertion is unchanged; only the
unit is physical. The one fixture that is a byte-for-byte copy of a corpus config file was
deliberately left alone.

## Update 47 — the HSPU regression explained: counts turn on a 5%-wide band (2026-09-21)

Closes the item Update 46 left open. This is analysis of run 03 and run 04 artefacts already on
disk — no new run, no engine change, nothing re-tuned.

**Question.** HSPU wall-facing at 10 fps fell 1 -> 0 between runs although its reference moved from
9.5% wrong to 0.8% from native, and it gained endpoint frames.

**Method.** Classify every usable frame by the two gates the engine already applies: `S` when
`span_ratio >= extendedSpanRatio` (0.80), `L` when `<= loweredSpanRatio` (0.75), and `m` for the
band between them. Then count, for each ascent (an `L` run followed by an `S` run), whether any `m`
frame sits between the two.

**Result. The count is the number of ascents that pass through an `m` frame — in 14 of 16 cells.**

| clip | rate | run 03 count / via-`m` / direct | run 04 count / via-`m` / direct |
|---|---|---|---|
| back | native | 5 / **5** / 0 | 5 / **5** / 0 |
| back | 20 fps | 3 / **3** / 2 | 3 / **3** / 2 |
| back | 15 fps | 4 / **4** / 1 | 4 / **4** / 1 |
| back | 10 fps | 0 / **0** / 0 | 2 / **2** / 3 |
| facing | native | 5 / **5** / 1 | 5 / **5** / 1 |
| facing | 20 fps | 4 / **4** / 1 | 5 / **5** / 1 |
| facing | 15 fps | 5 / **5** / 0 | 5 / **5** / 0 |
| facing | 10 fps | 1 / 2 / 3 | **0** / 2 / 3 |

**Mechanism.** `ElbowCycleTiming` reaches `RAISING` from `LOWERED` only on a frame that is neither
lowered nor supported. A frame that goes straight from `LOWERED` to supported takes the
`isSupported -> WAITING` branch, which **silently discards a completed repetition**. So a repetition
is counted only if the sampler happens to catch the press mid-travel, inside a window that is just
5% of the reference span wide.

At 30 fps the press is sampled often enough that every genuine ascent lands at least one frame in
that band. At 10 fps only 2 of 5 do. Whether a given repetition counts is then decided by sampling
phase — which is why run 04's *more accurate* reference made wall-facing worse: shifting every ratio
by 0.8% moved the one lucky frame out of the band.

**This is the deferred guard, in a second engine.** Update 45 excluded the hanging leg raise's
`LOWERED` -> raised one-frame `ABANDONED` guard on the grounds that relaxing it would also fire at
the native rate. The same argument applies here and the evidence now makes it concrete: wall-facing
has **one direct ascent at the native rate**, and its native count of 5 matches its label of 5. That
direct ascent is a tracking artefact and discarding it is correct. Counting direct ascents would
make the native count 6 — a false positive against the label.

So the rule is right at 30 fps and wrong at 10 fps, for the same reason the knee-raise guard is:
**a state machine that infers a transition from the presence of an intermediate observation cannot
work at a rate that does not supply one.** Three engines, one root cause.

**Not fully explained.** Wall-facing at 10 fps has two via-`m` ascents but counts 1 (run 03) and 0
(run 04), so a second constraint operates on that clip alone — most likely the two-sample
confirmation of the `S` run that follows. Fourteen of sixteen cells are exact; this one is not, and
it is left stated rather than smoothed over.

**Consequence for the plan.** This strengthens the step 4 case from Update 46 rather than adding a
new item. The band-crossing evidence is another thing more frames buy directly: at 20 fps both clips
already recover to 3 and 5. It also means the eventual v2 work should reconsider the gap between
`loweredSpanRatio` and `extendedSpanRatio`, which is currently narrow enough that a single sample
decides a repetition.
