# Project state — read this first

**Updated 2026-09-16.** This is the single entry point. It supersedes
`2026-09-10-claude-project-handoff.md`, `2026-09-12-state-and-plan.md` and
`2026-09-12-autonomous-session-brief.md`, all removed in the same commit.

Read order for someone picking this up:

1. **This file** — where we are, what works, what is broken, what to do next.
2. `AGENTS.md` — operating rules and the current gate.
3. `docs/central-handoff.md` — product and technical direction. Long, and deliberately so; it is
   the north star, not the status report. Read sections 0–3 and 24 first.
4. `docs/movement-method-ledger.md` — the append-only record of every offline experiment, with the
   parameters and predictions that were frozen *before* each run. This is the provenance every
   results document cites.
5. `docs/adr/` — the decisions that are settled. ADR-0006 governs the exploratory viewer.

---

## 1. What this is

A calisthenics movement-analysis system. The long-term product is coaching intelligence; the
current phase is **feasibility and measurement** — can we count and time repetitions and holds
reliably enough, from a phone camera, to build anything on top?

The deliberate stance, which explains most of the design: **pose is an intermediate representation,
not the product.** Everything downstream works on torso-length-normalised image-plane geometry, and
refuses to answer when it has not observed enough. `UNCERTAIN` is a valid and preferred output.

Only the **final parameters** that survive this process ever ship. The corpus, the traces and the
scaffolding are development instruments.

## 2. Where everything is

| What | Where |
|---|---|
| Repository | `/Users/sanchit.walia/NonWork/calisthenics-expert` (GitHub `waliasanchit007/calisthenics-expert`, **private**) |
| App + engines | `apps/diagnostic-harness/` — KMP: `shared/`, `composeApp/`, `androidApp/` |
| Offline corpus | `~/.buzz-multiagent/calisthenics-expert/offline-corpus/` — **never in Git** |
| Device traces | `offline-corpus/device-traces-20260915/` |
| Pulled device recordings | `offline-corpus/device-feedback-20260915/` |
| Pose extractor venv | `offline-corpus/pose-extractor-20260912/` (mediapipe 0.10.21, model sha `5134a3aa…`) |
| Corp truststore | `offline-corpus/toolchain-20260912/corp-truststore.jks` |

`~/.buzz-multiagent/…/worktrees/*` are **git worktrees of this same repository**, not a separate
clone. They are scratch checkouts. Nothing lives only there.

## 3. How to build and run

The JDK needs both a version pin and the corporate truststore. `JAVA_HOME` must point at JDK 21 —
setting `-Porg.gradle.java.installations.paths` alone is not enough, because `jlink` will still run
on whatever JDK launched Gradle.

```sh
cd apps/diagnostic-harness
export JAVA_HOME=~/.local/Homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.buzz-multiagent/calisthenics-expert/offline-corpus/toolchain-20260912/corp-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug && adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

`JAVA_TOOL_OPTIONS`, not `GRADLE_OPTS` — test JVMs are forked and would not inherit it.

**Correction worth knowing:** for several days this was recorded as "a corporate proxy blocks the
Gradle wrapper". That was wrong. `curl` returned 200 while Java failed with `PKIX path building
failed`; the JDK truststore simply lacked the corporate root. Do not re-diagnose it as a proxy.

Current suite: **shared 887, androidApp 273, 0 failures.** Read counts from the JUnit XML, not from
Gradle's "BUILD SUCCESSFUL" — an up-to-date task prints success while running nothing.

## 4. Method rules — why any of these numbers can be believed

These are load-bearing. Most of the project's value is that its negative results are trustworthy.

1. **Labels frozen before predictions. Configs frozen before labels are read back.** Corrections are
   additive new versions; v1 is never edited.
2. **Parameters and predicted outcomes are declared in the ledger BEFORE the run that tests them.**
   Never re-tune a parameter against the result it produced.
3. **Never fit to labels.** Subtracting an offset measured from annotations is forbidden.
4. **Pose data may aim a crop. It may never decide a label.**
5. **Contact sheets: use `exact_sheet.sh`.** The `fps` filter shows frames +0.10 to +0.24 s later
   than nominal.
6. **Refuse rather than infer.** `null`/UNKNOWN means not observed.
7. **Counts are the trustworthy metric; ±0.25 s window rates are unstable** — a 0.1 s shift once
   flipped toes-to-bar 5/7 → 2/7.
8. **Every engine change ships with tests, a mutation check, and a byte-identical reproduction of
   the pre-change output on a pinned path.** Run the full suite before claiming anything.

### What development mode relaxes (owner, 2026-09-16)

This is a private, unshared, single-subject development project. The owner is the only person
recorded. Accordingly, **postponed until pre-launch, not abandoned**:

- The participant-consent gate and its eight decisions. The owner records himself and has given
  standing permission to record and log his own movements.
- Formal retention/withdrawal ceremony in `docs/protocols/privacy-retention-withdrawal.v1.md`.
- Study-release formalities for internal experiments.

**If and when real training data from other people is needed, permission is obtained first.** That
is the owner's stated position and it is not being quietly widened here.

**Kept regardless, because they are not ours to waive:**

- **Never bypass a third party's access restriction.** `fetch_youtube.py` stops at a sign-in wall;
  leave it stopped and record the refusal. This is not an internal process rule that dev mode can
  relax — it is someone else's access control, and the project's relaxations do not reach it.
- **Creative Commons attribution and licence recording.** A licence condition set by the people
  whose footage we use.
- **Media and person-derived data stay outside Git.** Now engineering hygiene rather than policy: a
  225 MB tar in history is permanent and unpleasant to remove.
- Record what a file **actually is** from `ffprobe`, never what the source claimed. That error was
  made once and corrected across 14 records.

## 5. What works — offline, on the labelled corpus

| # | Movement | State |
|---|---|---|
| 1 | Muscle-up (ring strict / bar kipping) | Counts 3/3 both. Timing early. |
| 2 | Straight-bar dip | Counts 5/6. **The previously recorded "0 false positives" was unsupported**: the per-segment-side report gives matched 2, missed 4, **extra 3**, precision 0.40. Counts are the sanctioned metric; the window rates are poor. |
| 3 | HSPU | Counts 5/5 both. Endpoint estimator **adopted 2026-09-12, HSPU only**, additive and offline. |
| 4 | Handstand hold | `btUW` exit error −1.23 s → **+0.13 s** after the persistence fix. |
| 7 | Back lever | **Closed 2026-09-12.** Shape gate 111/111, one interval covering 4.96 s of a 4.67 s hold. Both endpoints censored, so no entry/exit accuracy claimed. |
| 13 | Hanging knee raise | **Closed on counts**: 6/6, zero false positives over 53.7 s — on a 30 fps side-view clip. |

### Not closed, with the cause identified

| # | Movement | Blocker |
|---|---|---|
| 5 | Tuck planche | 146/146 rejections are `UNSUPPORTED_ORIENTATION`, median 27.1° against a 30° gate — but the measure is shoulder→ankle, which describes the tuck, not the hold. Needs an owner decision. |
| 8 | Hanging straight-leg raise | **Documented negative**, front view, 0/6, mechanism identified per rep. Keep; do not tune for it. |
| 9 | Front lever | 178/185 rejections `UNSUPPORTED_ORIENTATION` at median 53.3°. Not a loosenable threshold — needs side-on footage. |
| 10 | Toes-to-bar | Needs one clean held-out recording from a third athlete. |
| 11 | Wall sit | Held-out clip does not generalise. Cause measured: **handheld camera alone contributes 0.135 torso-lengths/s against a 0.30 gate.** |
| 12 | L-sit | **The gate has no hip-clearance test and admits ordinary sitting.** Seated frames read hip 89–95°, knee 172–180° and are admitted. No tightening of existing bands fixes this. |

## 6. Findings that generalise

**The hold stillness gate was a frame-rate-dependent jitter meter.** `|p_t − p_{t−1}| / torsoLength
/ dt` between *consecutive* frames multiplies displacement by capture rate, so fixed landmark jitter
scores proportionally to fps. Fixed by `motionBaselineNs` (W = 0.25 s): measure against the most
recent valid frame at least W back, over real elapsed time. Byte-identical at W = 0.

**Event timing is definitional, not a lag.** A threshold-crossing engine reports *where it detected*
the endpoint; an annotator reports *where the movement ended*. On HSPU those are 0.6 s apart. Every
annotation convention in the corpus marks **first arrival** — so `estimatedDepartureNs` must not be
migrated to another movement, however good its numbers look.

**Camera motion is unsolved and is not jitter.** Pose landmarks alone cannot separate camera
translation from athlete translation — they are the same image motion. Compensation attempted from
background phase correlation made results worse (63/239 vs 53 baseline). Any fix needs a signal from
outside the landmark stream; the phone IMU is the candidate. **Holds shot handheld are currently not
measurable end-to-end.**

**Admission gates fail for reasons other than the one you assume.** Three separate movements
(planche, front lever, L-sit) were each diagnosed as a threshold problem and each turned out to be a
*measurement* problem — the wrong quantity was being gated. Diagnose the measure before the number.

## 7. Where we are failing now — live on device (2026-09-15)

This is the newest and most important section. Four live sessions on an SM-S938B, all captured
cleanly (0 dropped rows). **Every movement performed was counted 0.**

| Session | Movement | Apparatus | Reps done | Counted | Why |
|---|---|---|---|---|---|
| `8a2f89f2` | Dip | bar | 5 | 0 | `shoulder_above_wrist` fell under the 0.05 gate at the bottom of every rep |
| `7fe115b9` | Dip | bar | 2 past depth gate | 0 | most reps never reached elbow ≤ 100°; the one that did (26°) was cut by a tracking rejection |
| `63ad6747` | Hanging knee raise | **dip bars, support position** | 5 | 0 | `support_rise` −1.16 against a required ≥ +0.35; never admitted as hanging on any of 738 frames |
| `e9675325` | Hanging knee raise | **pull-up bar, hanging** | ~5 | 0 | hip crossed the whole 150°→110° band inside one 100 ms sample; cycles `ABANDONED` |

### The systemic cause: 30 fps configs running at 10 fps

Every frozen config was derived from **30 fps** footage. The device pose pipeline delivers **10 fps**.
Everything scaled by sample count shifts 3×:

- The knee-raise RAISING band is 150°→110°, i.e. **40° wide**. A measured raise runs ~890°/s, so it
  is crossed in **45 ms**. At 33 ms/frame ≈ 1.3 samples land inside it (usually caught); at
  100 ms/frame ≈ 0.4 samples (usually skipped). When skipped, the engine sees a jump straight from
  lowered to raised, treats it as implausible, and discards the cycle.
- `minimumEndpointSamples = 3` meant a 100 ms hold offline. Live it demands **300 ms**.

**The 10 fps is not a device limit.** Measured on device: inference **38 ms** (p90 48 ms), so ~26 fps
of capability; end-to-end **171 ms**; **770 of ~1186 camera frames dropped**. `CameraXFrameSource`
uses `STRATEGY_KEEP_ONLY_LATEST` on a single-thread analyser feeding a single-thread dispatcher —
fully serialised, so ~133 ms per frame goes somewhere that is not inference. That 133 ms has **not**
been profiled yet; assume nothing about where it goes.

### Second live problem: framing

**54% of captures rejected** in the pull-up-bar knee-raise session (69 `INVALID_LANDMARK`, 39
`LOW_CONFIDENCE` of 347). `INVALID_LANDMARK` means a required joint was outside the frame. It cost
at least one complete rep — a cycle with 159.9° of hip closure died as `TRACKING_INTERRUPTED`.

### Corrections made during that session — do not re-derive them

- **Lens facing is not viewpoint.** `LENS_FACING_FRONT` selects a sensor; where the phone stands
  decides the view. A front-facing camera propped to the athlete's side records a side view. The
  front camera is kept. ADR-0006 never made a viewpoint claim.
- **The straight-leg-raise negative does not transfer to knee raises.** That failure was a 140° knee
  gate; the frozen knee-raise config sets `raisedKneeMinDegrees = 0.0`, making the knee test vacuous
  and `KNEE_FLEXION_REJECTED` unreachable. It gates on hip angle alone.
- **The dip failure is not a rings-vs-bar transfer.** The dips were on the bar, the same apparatus
  the config was frozen on. The athlete simply descends deeper than the gate allows.

## 8. Instrument defects found on 2026-09-15 — fix these before trusting another capture

Three separate silent failures cost real data. The pattern is the same each time: **the status said
fine while nothing was recorded.**

1. **Fixed.** The diagnostic log recorded only `PullUpExperimentState`, which is deliberately not fed
   while an offline engine runs — so every row read `PULL_UP`/`IDLE` regardless of the movement.
   Rows now carry `offline_movement`, `offline_phase`, `offline_reason`, `offline_cycle_outcome`,
   `offline_reps` and `off_*` projected geometry.
2. **Fixed.** Two clocks bound a session — the 30-minute marker and the recorder's own 180 s — and
   only the longer was shown. There is now an in-app **Start 30-minute debug session** control with
   ACTIVE/EXPIRED/OFF, both deadlines, session id and a Stop button. Expiry preserved, no automatic
   renewal.
3. **OPEN.** `ViewerFeedbackBundle` refuses to write once `movement-feedback` exceeds
   `totalBytes − maxBytes` (256 − 64 = **192 MiB**), calls `stopAccepting("total_cap")`, and the UI
   renders that as a plain "Recording stopped" — indistinguishable from a normal stop. 220 MiB of old
   sessions silently ate a whole attempt. **Surface the reason and warn before the cap.**
4. **OPEN.** `input_issue` reports `TRACKING_GAP` on essentially every row when an offline engine is
   selected, because the calibrated experiment is not being fed. A red herring in every trace.
5. **OPEN.** Only `HANGING_KNEE_RAISE` populates `cycleOutcome`. The other six should, or dip cycle
   rejections stay invisible.

**Lesson, recorded twice now:** a seam is not wired until something asserts the far side of it. The
panel-routing bug and the diagnostic-log bug were the same defect one layer apart.

## 9. The plan

Ordered. Steps 3 and 6 need no device.

1. **Cheap instrument fixes** — items 3–5 in §8, plus a live "joints out of frame" indicator.
   Unblocks every future capture.
2. **Profile the 133 ms.** Add stage timings (capture→preprocess→submit→infer→result→UI) to the
   trace. One session answers whether there is one fat stage or diffuse cost.
3. **Decimation study — the honest measurement.** The offline corpus is 30 fps and labelled.
   Decimate every labelled clip to 20/15/10 fps, re-run all seven engines on the v1 configs,
   pre-register the prediction first. Yields a per-movement fps floor, sets the target for step 4,
   and separates structurally fragile engines from merely mis-tuned ones. **Never touches device
   data**, so the four live sessions stay as independent confirmation.
4. **Raise the device rate.** Overlap the pipeline stages; target ~20–25 fps against a 38 ms
   inference budget. App behaviour change — needs ADR coverage. May hit a real ceiling (thermal,
   camera output format).
5. **Make the engines frame-rate-independent** where step 3 says it matters.
   `minimumEndpointSamples: Int` should be a duration. The LOWERED→RAISED guard should use the
   capture gap: if no frames were dropped, the motion really was that fast. Each with tests, a
   mutation check, and byte-identical reproduction of the 30 fps results.
6. **Only then, v2 configs** frozen against labelled device footage at the real rate. Additive,
   labels frozen first, never edits to v1. This is where the dip depth gate is addressed, with the
   owner's actual depth in the labelled set.

**Do not** nudge the 0.05 dip gate, the 40° band, or `minimumEndpointSamples` against the 2026-09-15
runs. Three of four failures trace to one systemic cause; tuning around it would bury that.

## 10. Still needs the owner

1. **A tuck-appropriate body line for `HORIZONTAL_BODY`** (priority 5). Moves published numbers.
2. **A hip-clearance measurement for L_SIT** (priority 12). Moves published numbers. Candidate,
   written down as a starting point and not an adopted design: hip height above the supporting wrist
   or the ankle line, in torso lengths.
3. **Adopt-or-revert the landmark-bounds scoping.** Correct, tested, mutation-checked, and no
   evaluated movement's result changes — but a pre-registered stopping rule tripped (an
   out-of-range rate was asserted for seven clips having measured three), so it was not self-adopted.
4. **A camera-motion signal from outside the landmark stream.** Phone IMU is the candidate.
5. **Front-lever side-on footage and a third-athlete toes-to-bar recording.** Search tasks now, not
   blockers — downloads are authorised.
6. **Apparatus scope for knee raises.** A support-position knee raise on dip bars is a different
   movement from a hanging knee raise. Either add a variant or state the requirement in the UI.

**The one thing downloads cannot solve:** extremum-vs-departure needs ≤0.1 s annotation of footage
already held. The existing 0.333 s annotation cannot adjudicate it, and moving existing labels is
not an option.
