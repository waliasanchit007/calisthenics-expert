# Calisthenics movement-analysis harness — state and plan (2026-09-12, rev 2)

Written to survive a context compaction. Everything needed to resume is here or is reachable from
the paths below. Nothing in this file is inferred; every number was read back off disk on 2026-09-12.

---

## 1. Where everything is (absolute paths — none of this is in the repo)

| What | Path |
|---|---|
| Worktree (code, docs) | `~/.buzz-multiagent/calisthenics-expert/worktrees/m0-5b-task-4-synchronized-video` |
| Working ledger (read this first) | `<worktree>/.superpowers/sdd/2026-09-11-muscle-up/progress.md` — Updates 1–28 |
| Corpus, caches, predictions (outside Git, always) | `~/.buzz-multiagent/calisthenics-expert/offline-corpus` |
| Main results | `<worktree>/docs/2026-09-11-priority-coverage-results.md` (Parts 1–3) |
| Endpoint experiment | `<worktree>/docs/2026-09-11-endpoint-timing-experiment.md` |
| Frozen endpoint spec | `<worktree>/.superpowers/sdd/2026-09-11-muscle-up/endpoint-timing-spec-v1.md` |

Repo state: `HEAD a690a35`, 2 pre-existing tracked modifications, everything else untracked.
**No video or person-derived data has ever been staged in Git and none should be.**

## 2. How to build and run (the toolchain has a permanent workaround)

The Gradle wrapper (9.3.1) is blocked by a corporate proxy. Use Homebrew Gradle with an explicit JDK;
the wrapper files are deliberately untouched:

```
J=/Users/sanchit.walia/.local/Homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home
cd <worktree>/apps/diagnostic-harness
gradle --offline :shared:jvmTest -Porg.gradle.java.installations.paths="$J"
```

Offline replays run from a saved classpath, not through Gradle:
`<sdd>/runtime-classpath-20260911.log`, line `COVERAGE_CLASSPATH=…`, invoked as
`java -cp "$CP" com.calisthenics.diagnostic.viewer.experimental.Replay<X>Kt --…`.

Helper scripts in `<sdd>/`: `replay_reps_one.py` (score one rep clip), `replay_holds_persistence.py`,
`replay_cc_batch.py`, `exact_sheet.sh` (**contact sheets with exact PTS — always use this, never the
fps filter**), `sheet_offset.py`, `fetch_youtube.py`, `replay_holds_baseline.py` (the three-way
baseline comparison), and two screening/diagnostic tools added 2026-09-12: `camera_motion.py` (is the
camera fixed, or panned/zoomed, or physically moved — it distinguishes them by whether background
patches at different depths fit ONE 2D similarity) and `hold_motion_breakdown.py` (splits the hold
gate's value into a camera term measured from background pixels and a body term). Both need numpy:
use `offline-corpus/poserac-feasibility-20260908/venv312/bin/python`, not the host python3.

**Current suite: 877 tests / 94 suites, 0 failures, 0 errors, 0 skipped.**

## 3. Non-negotiable method rules (these are why the results are trustworthy)

1. **Labels are frozen before predictions.** Corrections are additive new versions; v1 is never edited.
2. **Configs are frozen before labels are read back.** Parameters are declared in the ledger *before*
   any re-run, with a rationale, and are never re-tuned against the result.
3. **Never fit to labels.** Subtracting a per-movement offset measured from annotations is forbidden.
4. **Pose data may aim a crop; it may never decide a label.**
5. **Contact sheets:** `ffmpeg -ss S -t D -vf fps=F` shows frames *later* than nominal (measured
   +0.10 s at F=4, +0.13–0.16 at F=3, +0.24 at F=2). Use `exact_sheet.sh`.
6. **Refuse rather than infer.** `null`/UNKNOWN means not observed, never interpolated.
7. **Counts are the trustworthy metric; ±0.25 s window rates are unstable** (a 0.1 s shift once
   flipped T2B 5/7→2/7).
8. Every engine change ships with tests, a mutation check, and a byte-identical reproduction of the
   pre-change output on a pinned path.

## 4. Where each movement stands

| # | Movement | State |
|---|---|---|
| 1 | Muscle-up (ring strict / bar kipping) | Counts 3/3 both. Timing early. Separate regime-crossing engine. |
| 2 | Straight-bar dip | 5/6 detected, 0 false positives. Tracking-challenge clip kept separate. |
| 3 | HSPU (back-to-wall / wall-facing) | Counts 5/5 both. **Endpoint estimator built and validated — awaiting adoption decision.** |
| 4 | Handstand hold | `xmray` entry 0.00 s / exit +0.90 s. `btUW` exit error −1.23 s → **+0.13 s** after persistence fix. |
| 5 | Tuck planche | Variant unresolved. Fragments 6→5. **Admission cause now identified**: 146/146 rejections are UNSUPPORTED_ORIENTATION, median 27.1° against a 30° gate — but the measure is shoulder→ankle, wrong for a tuck. Not closed. |
| 6 | Planche | Labelled **non-blind (disclosed)**. 3 right-censored holds + 3 negative spans. Dev material only. |
| 7 | Back lever | **CLOSED 2026-09-12.** Shape gate 111/111. One interval, 5.62–10.58 s, covering 4.96 s of a 4.666 s labelled hold. Both endpoints censored, so no entry/exit accuracy is claimed. |
| 8 | Hanging straight-leg raise | **Closed as a documented negative** — front view, 0/6, rejection mechanism identified per rep. |
| 9 | Front lever | **Failed — camera angle**, now quantified: 178/185 rejections are UNSUPPORTED_ORIENTATION at a median of 53.3° against a 30° gate. Not a threshold that can be loosened. Needs side-on footage. |
| 10 | Toes-to-bar | Ankle fix took a new athlete 0→6/7. No clean held-out recording left. Not closed. |
| 11 | Wall sit | Closed on the original clip (one interval, entry 0.00 s, exit −0.08 s). **Held-out `0xKuIvU6lVM` does NOT generalise**: 5 fragments, 53% coverage, exit missed by 3.6 s. Cause measured: the handheld camera alone contributes a median 0.135 torso-lengths/s against a 0.30 gate. |
| 12 | L-sit | **Not closed; blocker moved off footage.** `84WplmAbszc` screened, labelled and run 2026-09-12: pose on 256/256 hold frames, posture admitted 256/256, but 4 fragments. Cause: **the L_SIT gate has no hip-clearance test and admits ordinary sitting** (seated frames read hip 89–95°, knee 172–180°, admitted). Needs a clearance measurement, not footage. |
| 13 | Hanging knee raise | **Closed on counts**: 6/6, zero false positives over 53.7 s. |

## 5. The two findings that generalise

**Hold fragmentation** — two mechanisms, both now fixed. (1) Requiring N consecutive contrary frames
before closing a hold (`minimumMovingSamplesToClose`, `minimumNonHoldingSamplesToClose`, both N=3,
pre-registered), which handled isolated spikes. This also exposed a second defect: the capture-gap
check keyed off the last *still* frame, so absorbing transients made on-time frames look like
dropouts; capture continuity is now tracked separately. (2) **The gate was measuring pose jitter, not
stillness** — see below.

**The hold stillness gate was a frame-rate-dependent jitter meter.** `|p_t − p_{t−1}| / torsoLength /
dt` between *consecutive* frames multiplies displacement by the capture rate, so a fixed spatial
landmark jitter scores proportionally to fps. Over a 4.67 s back lever the torso centroid moved 0.105
torso-lengths NET while the gate reported a median of 0.209/s and 29/110 frames over 0.30. Across all
six hold clips the gate's median tracked jitter amplitude, not athlete motion. So the gate was
neither frame-rate invariant nor pose-quality invariant, and `0.30` did not mean the same thing on
any two clips. Fixed by `motionBaselineNs` (W = 0.25 s, pre-registered): measure against the most
recent valid frame at least W back, divided by real elapsed time. Byte-identical at W = 0.

**Event timing is definitional, not a lag.** The debounce artefact is real but tiny (one frame on
most engines) and has the wrong sign for the dominant error. The actual cause: a threshold-crossing
engine reports *where it detected* the endpoint; an annotator reports *where the movement ended*.
On HSPU those are 0.6 s apart. Confirmed on a raw trajectory.

## 6. HSPU endpoint estimate — ADOPTED 2026-09-12

**Adopt the HSPU endpoint estimator?** Built as `EndpointTimingEstimate.kt`, additive by
construction (consumes an already-decided rep; counts verified byte-identical). 13 synthetic tests.
Validated on two recordings with **no retuning**:

| Recording | crossing err | extremum err | departure err |
|---|---|---|---|
| `cLTvfDlhiXM` back-to-wall | −0.517 | −0.113 | **+0.012** |
| `tz_YVynl1sk` wall-facing | −0.650 | +0.012 | **+0.067** |

Supported claim: the crossing is outside the annotation's own ±0.333 s on **8/8** scorable reps.
**Not** supported: extremum vs departure — 0.1 s apart, and a 0.138 s timebase shift flips which
wins. Confirmation delay (live latency, reported separately): 0.27–0.77 s.

**ADOPTED 2026-09-12, HSPU only**, additive and offline, as a second pass (`ReplayEndpointTiming`,
frozen config `endpoint-timing-20260911/endpoint-config-hspu-v1.json`). 7 entry-point tests added on
adoption; suite 867/94. NOT adopted: any other movement, any historical rewrite, any deployment.
Extremum vs departure stays open and unclaimed.

## 7. Plan — status after the 2026-09-12 autonomous session

**A. Screen the five spare clips — DONE.** Results in ledger Update 17.
`84WplmAbszc` (floor L-sit) is **usable** and is the best of the five. `0xKuIvU6lVM` (wall sit) is
usable with both endpoints censored. `GSOfcbwbd-g`, `KqUMAIQn0K8`, `fAlucQoAD1o` are rejected, with
the reason recorded per clip. **Correction to the previous revision of this file:** it claimed these
five were the only route to closing priority 9 (front lever). That was wrong — none of the five is a
front lever. Front lever can only be closed by a download.

**B. Per-movement annotation-definition audit — DONE.** Ledger Update 21. The convention is uniform
across all 14 label files: rep events mark the **RETURN, i.e. first arrival** at the reference
configuration after the extreme; hold labels mark first/last frame of the held shape with explicit
censoring and per-clip uncertainty. Consequence: **do not migrate `estimatedDepartureNs` to another
movement.** On HSPU the numbers favoured departure but the annotation's own words say first-arrival,
and 0.333 s sampling cannot adjudicate. Carrying the HSPU result across would be fitting to a score.

**C. Adopt-or-drop HSPU endpoint estimate — DONE 2026-09-12.** Adopted for HSPU only (§6).

**D. Back lever fragmentation — DONE.** Ledger Updates 18 (pre-registration) and 19 (results),
results doc Part 4. `motionBaselineNs`, W = 0.25 s. Back lever 2 fragments → 1, covering 4.96 s.
Byte-identical at W = 0 on all six clips; four mutants killed; suite 874/94.

**E. Tuck planche / front lever admission — DONE (diagnosed, deliberately not changed).** Ledger
Update 20, results doc Part 4. Straightness cannot reject anything (band is 0–180°); both are
`UNSUPPORTED_ORIENTATION`. Front lever median 53.3° — not a loosenable threshold. Tuck planche median
27.1° against a 30° gate, but the measure is shoulder→ankle, which describes the tuck rather than the
hold.

### What to do next, in order

**F. Label `84WplmAbszc` — DONE; priority 12 NOT closed, and the blocker moved.** Ledger Updates 22
(label + config + predictions, frozen) and 23 (results), results doc Part 5. The clip is good and the
label holds; the **L_SIT admission gate has no hip-clearance test and admits sitting on the floor**.
No tightening of the existing angle bands can fix it — plain sitting scores closer to an ideal L-sit
than the real hold does. What priority 12 now needs is a clearance measurement in `PostureAdmission`,
which is a contract change affecting published numbers: **owner decision, see §9.**

**G. Decide the two admission questions (E and F).** Both need an owner, because both move published
numbers: (i) a tuck-appropriate body line for HORIZONTAL_BODY, and (ii) a hip-clearance measurement
for L_SIT. Candidate for (ii), written down as a starting point and not an adopted design: hip height
above the supporting wrist, or above the ankle line, in torso lengths.

**H. Consider whether `motionBaselineNs` should be non-zero anywhere else.** It is currently used
only in the posture-hold replay path. `ReplayStaticSkillPose` stays pinned at 0 as the frozen
regression path. Any migration needs its own pre-registration.

**I. Front-lever and toes-to-bar footage.** Now search tasks, not blockers — see §8.

## 8. Footage — downloads authorised (owner, 2026-09-12)

Downloading any number of YouTube clips is authorised, for improving an existing movement or adding
a new one. Items 1 and 2 below were blocked on footage and are now **actionable without the owner**:
go and find them. Conditions: never bypass an access restriction (`fetch_youtube.py` stops at a
sign-in wall — leave it stopped and record the refusal), prefer Creative Commons, record licence and
provenance per download, record the real format from `ffprobe` rather than YouTube's metadata,
internal assessment only, media stays outside Git.

## 9. Still needs the owner

1. **Front-lever footage shot from the side** — now a search task, not a blocker. Current clip is
   unusable for the lever itself (extreme upward angle, feet out of frame).
2. **One clean held-out toes-to-bar recording** from an athlete outside the two already used — now a
   search task too.
3. **The admission gate for tucked and steeply-angled bodies** (plan item G-i). Diagnosed 2026-09-12;
   changing it would move published numbers across several movements, which is the owner's call.
4. **A hip-clearance measurement for L_SIT** (plan item G-ii). The gate currently admits sitting on
   the floor, so priority 12 cannot be closed without it. Also a published-numbers change.
5. **Adopt-or-revert the landmark-bounds scoping** (ledger Updates 26–27). The fix is correct, tested
   and mutation-checked, and no evaluated movement's result changes — but a pre-registered stopping
   rule tripped (I asserted an out-of-range rate for seven clips having measured three), so I did not
   self-adopt it. Front lever's outcome is identical and only its rejection reasons become truthful;
   planche moves 18→17 fragments on disclosed dev material.
6. **Camera motion is a separate unsolved problem** (ledger Update 28). `motionBaselineNs` fixes
   jitter and cannot fix camera drift; pose landmarks alone cannot separate camera translation from
   athlete translation. Any fix needs a signal from outside the landmark stream. Holds shot handheld
   are currently not measurable end-to-end.

**The one thing downloads cannot solve:** a ≤0.1 s annotation on a handful of HSPU reps, if you want
extremum vs departure settled. The existing 0.333 s annotation cannot decide it, and moving the
existing labels is not an option. New footage does not help — this needs finer annotation of
footage already held. Note that plan item B strengthened the case for leaving it alone: every written
annotation convention in the corpus says first-arrival, so departure currently has the numbers on its
side and the conventions against it.
