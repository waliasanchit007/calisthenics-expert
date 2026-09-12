# Offline movement feedback

## Historical evidence scorecard

`tools/offline_movement_scorecard.py --manifest /absolute/private/request.json
--output-dir /absolute/private/new-scorecard` projects explicitly selected,
hash-bound historical reports into `summary.json` and `scorecard.md`. It does
not rerun inference or counting. Manifest version1 has `cases` containing unique
`id`, an `engine` (squat/lunge/jumping_jack/plank), absolute `report` and
`report_sha256`; a case without evidence instead has a nonempty `missing_reason`
and no report fields. Missing evidence is not a zero-count success.

Repetition misses/extras remain separate even when totals agree. Hold overlap,
out-of-label duration and censoring stay separate from repetition metrics.
Full original reports and annotation provenance remain in the private JSON;
historical measurements do not establish current-source or mobile performance.
Never call all completed invocations an accuracy pass.

The next-work reconciliation and tracked status are in
`docs/superpowers/plans/2026-09-07-reuse-led-movement-expansion.md`.

This is a local exploratory learning loop, not an app release or a form benchmark.
The owner remains the only live app operator. No new workout is needed to rerun a saved clip.
Push-up/pull-up logic is unchanged. Squat, lunge, jumping-jack and plank prototypes have
offline replay entry points; their existence is not evidence of reliable movement recognition.

## Optional supervised squat practice (development only)

The fixed baseline remains the default. For the explicit per-side range experiment, add
`practice_window` to a NEW config, keeping the original config and reports intact:

```json
"practice_window": {
  "start_ns": 2000000000,
  "end_ns": 4000000000,
  "minimum_range_degrees": 20,
  "endpoint_fraction": 0.2
}
```

These are clip-relative presentation timestamps, selected by reviewing raw video before
running the calibrated detector. The interval must contain a complete supervised practice
cycle and finish standing. It is not automatic exercise identification. All observations
through `end_ns` are excluded from evaluation and count zero; do not subtract practice only
in a chart while allowing it to train and score itself. The replay fits only the marked
interval, freezes the parameters, and emits the actual selected-leg flexed/extended and
opposite-leg flexed thresholds in the first prediction row and report's `calibration` field.

Each leg must have continuous usable observations, consistent source dimensions, a supported
range and extended endpoints. The low extreme requires consecutive samples at or below it;
the high extreme requires consecutive samples at or above it. A single-frame spike cannot
define either extreme. Both legs must jointly reach their respective bottom bands. Missing
or inadequate practice fails the command before creating predictions; there is no silent
fallback. The last verified standing endpoint seeds the counter with its original timestamps,
not a count or an unfinished rep. A subsequent capture gap still invalidates that endpoint.

20-degree minimum excursion and 20%/80% bands are explicit experimental parameters, not
anatomical range requirements or a technique standard. Partial execution must not become
"correct technique" through fitting. Camera/stance changes and the far leg's projection can
still defeat this profile. Do not install or promote it based on one repaired recording.

If event labels are supplied, use a separately versioned evaluation-only label artifact:
any prediction or label interval on/before the practice end is rejected, never silently
discarded. A ten-rep set with one excluded practice has nine evaluation reps, not ten.
Author-provided aggregate set counts do not establish event precision/recall. Existing
development recordings remain development after this split; they do not become held-out.

### Optional opposite-motion ablation — not promoted

`practice_window.opposite_minimum_excursion_degrees` opts into
`supervised_per_side_motion_witness_v1`; omit it to retain the absolute-endpoint profile above.
It has its own name and explicit value. The practice minimum-range parameter does **not**
validate a runtime motion threshold, even when their numeric values happen to match.

The selected knee still has to reach its fitted bottom. For the opposite knee, this ablation
requires a fresh excursion from its own current-candidate standing reference, concurrently
supported by the configured consecutive samples. The reference is established while the
selected knee is standing, from usable opposite observations (minimum of the supported run),
not an anatomical declaration that both legs are straight. It updates during standing,
freezes during descent and is cleared by missing/invalid/gapped input. A newly reacquired
standing run cannot borrow the previous reference. Bent-static and straight-static opposite
legs do not corroborate a cycle just because their absolute angle is low.

This repaired the remaining development count and preserved the tested knee-lift negatives,
but a frozen synthetic probe with two sustained 25-degree erroneous opposite observations
produced a false cycle at the experimental 20-degree setting. The underlying synthetic leg
was stationary; the error was injected into the observed pose. Its frequency in real pose
output is unmeasured. This counterexample is retained, not solved by moving a cutoff above25.
The ablation remains opt-in and **not recommended as the phone default**. It is not a pose
reliability layer, automatic exercise recognizer, strict-rep validator or form assessment.

## What runs where

1. A permissioned source video stays outside Git with source/license, hash and derivation evidence.
2. `tools/extract_offline_pose.py` decodes actual PTS using PyAV and runs the pinned model through
   desktop MediaPipe VIDEO once. It preserves missing observations and writes a hash-bound pose cache.
3. `tools/offline_movement_feedback.py --run-kotlin` invokes `:shared:replaySquatPose` against the
   common Kotlin squat experiment. There is no Python copy of counting logic.
4. The report compares completion events one-to-one against separately authored visual intervals.
   Equal counts are not sufficient. A local HTML overlay pairs source footage, landmarks and decisions.

Desktop extraction differs from Android preprocessing/runtime and is labelled accordingly. A green
offline replay is not a claim about camera throughput, mobile inference or live accuracy. Real source
frames and annotations are not synthetic fixtures and must not be copied into the repository.

## Optional environment

Keep media dependencies separate from the reference validator's environment:

```bash
python3.12 -m venv /absolute/private/corpus/.venv
/absolute/private/corpus/.venv/bin/python -m pip install -r tools/offline-media-requirements.txt
```

On this host retain TLS and use the system CA bundle if needed (`--cert /etc/ssl/cert.pem`).
MediaPipe 1.0.1 aborted in a standalone macOS PoseLandmarker CPU constructor; 0.10.21 processed the
same pinned Full model successfully. The cache records the actual package versions and model hash.
No Android dependency was changed. Decoder rotation/non-square pixel aspect ratio is rejected in
this first version rather than silently misinterpreted. Make an explicitly documented upright,
square-pixel derivative when necessary. Do not replace timestamps with frame-index/FPS arithmetic.

## Prepare inputs

The source record is JSON with `id`, `source_url`, `license_url`, `video_sha256`, `group`, and
`split` (`development` or `held_out`). Record attribution and any crop/resize/time rebase too.
Every derivative of the same source/recording/person belongs to one split. When identity is
unknown, do not claim person-independent results. `validate_splits` checks declared group/hash
separation; it cannot infer undeclared shared people or filming sessions.
Pass `--corpus-manifest /absolute/private/corpus/manifest.json` to replay for corpus-wide
validation. It is a JSON list of those source records. The runner checks group/hash isolation
and that the current source matches its entry before invoking Kotlin. Held-out replay requires
this manifest; a standalone development clip is allowed without one and proves no split isolation.

```bash
/absolute/private/corpus/.venv/bin/python tools/extract_offline_pose.py \
  --video /absolute/private/corpus/clip.mp4 \
  --model apps/diagnostic-harness/model-assets/pose_landmarker_full.task \
  --model-provenance apps/diagnostic-harness/model-assets/model_provenance.json \
  --source-record /absolute/private/corpus/source.json \
  --output /absolute/private/corpus/cache-v1
```

Outputs are exclusive-create. A failed extraction lacks completion metadata and cannot be admitted.
Use a new cache directory after a failed run; preserved partial files are not valid evidence.

Config JSON requires `side` (`LEFT`/`RIGHT`), `extended_degrees`, `flexed_degrees`,
`minimum_confidence`, `maximum_gap_ns`, and `minimum_endpoint_samples`. No defaults are silently
selected. These are provisional image-plane cycle parameters, not technique standards. The
initial development hypothesis uses 150/110 degrees, confidence 0.5, 250ms maximum capture gap and
two consecutive endpoint samples. The corrected baseline requires opposite-leg flexion too; both
leg chains must be visible. A partial excursion, unsupported jump or tracking interruption must not
be joined into a count. This is manually selected bilateral squat analysis, not automatic movement
recognition, not single-leg squat support and not an assertion of correct form.

Annotations require `version: 1`, matching `video_sha256`, `annotation_status: visually_reviewed`,
an explicit `annotation_method`, and ordered nonoverlapping `events` of `{start_ns, end_ns}`
completion windows. An explicit empty events list means a reviewed no-squat clip; absent labels
mean UNLABELLED, not success. Assistant-inspected labels must identify that provenance and are not
human/coach gold labels. Freeze labels before inspecting predictions; do not move windows to
improve a score. A near-window event remains a mismatch with its distance reported.

## Replay and inspect

```bash
.venv/bin/python tools/offline_movement_feedback.py \
  --video /absolute/private/corpus/clip.mp4 \
  --model apps/diagnostic-harness/model-assets/pose_landmarker_full.task \
  --cache /absolute/private/corpus/cache-v1 \
  --config /absolute/private/corpus/config-v1.json \
  --labels /absolute/private/corpus/labels-v1.json \
  --run-kotlin \
  --output /absolute/private/corpus/run-v1.json \
  --overlay /absolute/private/corpus/run-v1.html
```

Omit `--labels` for an explicitly unlabelled diagnostic run. The runner records config, pose,
prediction and Kotlin source hashes; changing config after replay cannot relabel stale predictions.
Use a new run name for each revision. Source changes during an invocation invalidate that run.
The overlay uses source timestamps and cached landmarks, with no network or new model inference.
It is a developer diagnostic screen and does not alter the operator's simplified app UI.

Open the generated HTML locally, or serve only the private corpus from its directory with
`/absolute/private/corpus/.venv/bin/python -m RangeHTTPServer 8878 --bind 127.0.0.1`.
Use loopback only, never all network interfaces. The basic Python HTTP server lacks byte-range
support here, causing event buttons to leave video playback at zero; the range-capable server
supports seeking. Stop the server when inspection ends. No remote hosting or upload is needed.

## Development discipline

First explain a failure from paired frames/decisions. Add a small synthetic behavioral regression,
observe it fail, make one targeted correction, rerun the same frozen inputs and inspect extra/missed
events. Do not paste real landmarks into committed fixtures. Run separate held-out material only
after configuration/logic is frozen and retain failures honestly. Additional source clips and an
untouched evaluation set are required before any reliability/generalization claim.

## First development result (not held-out accuracy)

MMFit w00's explicitly derived 36-second development clip contains ten visually annotated squat
cycles followed by unilateral knee lifts. The initial selected-knee baseline predicted eleven
cycles. A synthetic regression exposed the knee-lift ambiguity; bilateral bottom confirmation
reduced the same frozen-input replay to ten candidates without changing configuration or labels.
Nine completion windows match exactly. The remaining candidate arrives about 33 ms before its
frozen window, so event matching still records one miss and one extra, despite correct totals.
This is an unresolved timing/annotation-boundary discrepancy, not proof of a missed physical rep.

Cached replay took approximately 18 seconds including Gradle startup on this host. Labels are
assistant-reviewed reference annotations, not independent human/coach ground truth. Both legs
were visible in this clip; side views, other people and mobile runtime equivalence remain untested.
Media, labels and reports remain in the private corpus, not this repository.

Run `make verify` and `make verify-harness-jvm` sequentially in this checkout: the Python
packaging-negative tests temporarily move the real model asset away and restore it. Running an
Android delivery build concurrently can therefore fail at the model prerequisite even when
the asset was originally present. Do not bypass asset verification to mask that collision.

## Evidence caveat: pose-correlated labels are not independent ground truth

Later squat corpus expansion (second w00 development segment, and the w19 held-out clip) derived
labels from the raw pose cache itself — hip-landmark local minima and/or a bilateral knee-angle
threshold that is the *same* 150-degree threshold the candidate detector uses. Comparing the
detector against those labels is a useful, real diagnostic (it caught a genuine calibration
mistake in one case and a genuine depth-threshold generalization gap in the other), but it is
**pose-derived-label comparison, not independent visual ground truth**: both share MediaPipe's own
sensor errors and the same threshold assumption the detector is built on. Recomputing the geometry
independently in Python does not change this; an independent *implementation* of a *shared*
assumption is not an independent *label source*. Do not read any "N/N matched" count from that
corpus expansion as an accuracy or reliability number, and do not describe that labeling process as
blind or independent without this qualification, even where no detector run preceded the label
freeze — the labels can still be uninformative about the exact failure mode they'd need to catch
(they were built from the same signal the detector reads). The original first squat clip's
one-pass, pre-prediction visual labels (assistant-reviewed, not pose-derived) remain the strongest
labels in this corpus; later clips trade some of that independence for corpus size and are weaker
evidence, not stronger, despite being newer.

## A genuinely different source that correctly predicted zero events

A second, independently downloaded MM-Fit recording (w01, Zenodo 7672767, published MD5 verified)
was searched for a new squat development segment. A coarse-then-fine visual scan initially
identified a 55-second block (source 729-784s) that looked squat-like: bent knees, dumbbells
alternating between shoulder height and overhead. Denser sequential-frame inspection (0.3s step,
three independent 10-second windows across the block) overturned that first impression: the legs
never straighten anywhere in the clip — it is a sustained wide-stance isometric hold with a
repeated dumbbell overhead press, not a stand-squat-stand rep cycle. This was recorded as an
explicit reviewed empty-events label (zero genuine squat completions), not left unlabelled, per
this project's own definition of a "reviewed no-squat clip."

Replaying this clip through the same frozen `squat-baseline-v1.json` config and the real,
unmodified `SquatCycleExperiment` predicted **zero events**, matching the zero-event label exactly
— useful specificity evidence, not just a null result. The raw knee-angle trace shows why: 861 of
1650 frames (52%) read at or below the 110-degree flexed threshold (consistent with the sustained
bent stance), but only a single isolated frame (12.933s) ever crosses the 150-degree extended
threshold, and a direct frame check at that timestamp shows no visible leg motion — a one-frame
pose-estimation noise spike, not a real stand-up. Because the state machine requires 2 *consecutive*
extended samples before it will even leave `WAITING` for `STANDING`, this single-frame spike could
never start a candidate, so the detector correctly never produced a false rep from 55 seconds of
sustained bent-knee motion. This is real, different-recording evidence (a genuinely new download
and person, per this project's per-workout group-isolation convention) even though it produced no
positive predictions — development-only, one clip, one person, dumbbells, front-on view only.

## Plank body-line hold (exploratory, distinct from squat cycles)

`PlankHoldExperiment` measures a `HoldEvent`-shaped side-view shoulder-hip-ankle image-plane
angle against a declared straight band — duration/entry/exit/coverage diagnostics, not a rep count
or a plank-detection verdict. Its config additionally requires
`maximum_orientation_degrees_from_horizontal`: the shoulder-to-ankle vector's admissible angle
from horizontal, which rules out a near-vertical body line (e.g. a standing subject) reading as
"straight" purely by chance geometry. It is explicitly *not* able to tell a genuinely elevated,
supported hold apart from a straight body resting flat on a floor or mat — both read identically
under this geometry. `:shared:replayPlankPose` mirrors `:shared:replaySquatPose`'s hash-bound
JVM replay; `tools/offline_plank_feedback.py` mirrors the squat tool's comparison/overlay, using
overlap/coverage against a labelled interval rather than one-to-one event matching.

## Lunge cycle (exploratory, single-leg-tracked, distinct from squat's bilateral requirement)

`LungeCycleExperiment` mirrors `SquatCycleExperiment`'s phase-cycle shape
(WAITING/STANDING/FLEXING/FLEXED/EXTENDING) exactly, but deliberately does not port squat's
bilateral-opposite-leg confirmation: a lunge is inherently asymmetric (one leg leads and flexes
deeply, the other trails), so only the configured side's hip-knee-ankle angle is measured. This
means it is a single-leg-tracked candidate cycle, not an alternating-leg-aware counter: if a
subject alternates which leg leads, only reps where the configured side leads are expected to
register. `:shared:replayLungePose` and `tools/offline_lunge_feedback.py` reuse the squat replay
framing directly (the output row shape is identical: `timestamp_ns`, `knee_degrees`, `phase`,
`count`, `reason`, `counted`), including the same one-to-one event-count comparison logic.

A real clip was verified by visual frame inspection BEFORE any pose extraction (MM-Fit w00's own
source, already downloaded for the squat clips, contains an unrelated dumbbell-lunge block later
in the same recording). Nine standing forward lunges, all led by the same leg, were visually
identified. A first label pass (v1) centered windows on the visible bottom of each lunge and scored
0/9 direct matches — not because the candidate failed, but because completion is defined (matching
this project's own squat convention) as the return-to-standing after the bottom, not the bottom
itself. Rather than move the frozen v1 windows, an additive v2 label file was authored with the
corrected event definition, independently re-estimating each return-to-standing moment from fresh
zoomed frames (not copied from the detector's predicted timestamps), disclosed as non-blind since
it was written after seeing v1's result. Against v2: 6 of 9 matched, precision 1.0, recall 0.667,
zero extras. The 3 misses are exact and explained: a brief landmark-validity dropout lands at the
bottom-to-recovery transition each time, resetting the state machine's memory of having reached the
bottom, so the later visible return to standing is correctly not credited — the same
no-stitching-across-invalidation principle already used elsewhere, not a new bug. A follow-up
report-level diagnostic (`diagnose_landmark_reason`/`diagnose_unusable_frames` in
`tools/offline_movement_feedback.py`, reusable across squat/lunge/plank since all three share the
same ordered joint-validity check) re-derived the exact cause from the cached raw landmarks: all
three dropouts are the ankle or knee's `y` coordinate finitely exceeding the normalized `[0, 1]`
frame (values 1.0007-1.0467), never a NaN/Inf value, and two of the three windows are several
intermittent short excursions spanning up to ~500ms rather than one clean ~150ms gap. Full
mechanism-by-mechanism detail, and both label versions, stay in the private corpus
(`lunge-baseline-v1-diagnostic-note.md`, its precision-correcting `-v2.md` addendum, and
`lunge-baseline-v{1,2}-report.json`), not this repository. One clip, one person, dumbbells (not
bodyweight), one leg observed leading; no generalization claim, no alternating-lunge claim, and the
recall/precision numbers above are development-only (no held-out lunge evaluation exists).

Single-leg tracking has a second, disclosed confound distinct from the landmark-dropout misses
above: a deep knee lift/high-march (hip fixed, thigh raised toward horizontal, relaxed shin hanging
below the raised knee, foot never touching the ground) reaches the same knee-angle range as a real
lunge bottom on this single tracked leg, because the measurement has no ankle-height or
ground-contact signal to tell a lifted foot from a planted one. A hand-derived synthetic test
(`LungeCycleExperimentTest.deepKneeLiftMarchIsIndistinguishableFromAGroundedLungeOnThisSingleLegMeasurement`)
demonstrates the candidate counts this deep knee lift exactly like a completed lunge. This is
disclosed rather than patched: distinguishing them would need an ankle-height/grounding threshold,
and no real deep-knee-lift footage exists yet to validate where that threshold should sit, so adding
one now would be an unvalidated hidden threshold. `LungeCycleExperiment` therefore remains a generic
single-leg candidate-cycle detector, not verified lunge-specific recognition.

September8 continuation: the blanket absence of real leg-lift material is now
outdated. A separately frozen six-second crop of the existing w00 development
recording contains standing/unilateral leg lifts and zero visually labelled
lunges. The unchanged counter emits one extra on RIGHT and two on LEFT, with
all180frames usable on both predeclared sides. It confirms a real confusion, not
anatomical depth or ground-contact accuracy. Independent deep-lift/support-view
coverage remains missing. No grounding threshold has been added. See
`2026-09-08-overnight-movement-progress.md` for the current evidence and next task.

A real permissioned side-plank clip (Internet Archive, Public Domain Mark 1.0, "Core session #2")
was replayed with independently, visually authored labels (two propped bouts, separated by a
flat-lying leg-lift segment the labeler explicitly flagged as geometrically indistinguishable from
a hold under this measurement). The first replay silently dropped the entire second labeled bout:
the clip ends while the detector is still in a `HOLDING` state, and the engine had no end-of-input
closure path at all, so nothing was ever reported for that span. Fixed by reporting an explicit
`CENSORED_AT_END_OF_INPUT` closure at end of input, at the last confirmed-good sample, never
inventing an exit past what was observed. After the fix, the same clip/labels/config (unchanged)
produced a single ~54-second hold covering both the genuine second bout (full label coverage) and
the intervening flat-lying segment (87% of that hold's duration is outside any labelled interval).
This is the anticipated flat-vs-elevated limitation, now measured rather than only asserted, not a
new problem introduced by the fix. No plank-detection or generalization claim is made from one
clip, one side selection (chosen by near-symmetric left/right landmark visibility, not verified
against the source video), and no held-out evaluation.

## Jumping jack (exploratory, synchronized bilateral arm+leg cycle)

`JumpingJackExperiment` needs two independent signals to agree before a candidate "open" position
counts: averaged left/right hip-shoulder-wrist arm-elevation angle, and ankle-distance/hip-width
leg-spread ratio. Averaging both arms means a single-arm raise cannot alone cross either arm
threshold (proven by a synthetic regression, not just asserted), so an arm-only motion never
completes a cycle. The leg-spread ratio has no equivalent protection: it is a single symmetric
number, so a bilateral arm raise combined with only ONE leg stepping out can, on its own, cross the
leg threshold and be counted as a completed jack — a disclosed, unfixed confound (see
`singleLegStepDoesNotHaveTheSameBilateralProtectionAsArms`), for the same reason the lunge
deep-knee-lift confound is left unfixed: no real single-leg-step footage exists yet to validate an
asymmetry threshold against.

A real clip was located by visual inspection of w01's own recording (the same source already
downloaded for the squat search): a coarse scan of the tail of the recording flagged possible
jumping-jack motion, but denser inspection showed that region is mostly shadow-boxing with one short
genuine jumping-jack burst embedded in it (source 2425.0-2438.5s, 13.5s). Manual visual annotation
at this pace was harder than for squats/lunges: an initial 0.1s-step scan mis-split one real rep
into two reps, caught and corrected by a targeted every-frame re-check, then the whole clip was
independently re-scanned at 15fps to cross-validate. Inspecting the raw extracted geometry
afterward (to calibrate window WIDTH only, never to move a label's center) showed manual visual
timing can be off by up to roughly 250ms at this cadence — wider than the ~100ms tolerance that has
sufficed for slower squat/lunge motion — so labels use an honestly-widened +-250ms window.

Config thresholds (110/25 degrees, 2.0/1.35 spread ratio) were set by directly inspecting the raw
angle/ratio trace's real achievable range on this clip (peaks 117-143 degrees and 2.8-3.6 ratio;
troughs 6-17 degrees but a *ratio* trough that drifts from 0.69 early to 1.0-1.3 later in the clip,
apparently because the subject stops fully closing his stance between reps as the pace continues) --
not copied from the squat/lunge synthetic-test numbers, which used unreachably high synthetic angles
for this real camera's geometry. Replaying the frozen clip/config/labels: **10 predicted, 10
annotated, 8 matched, 2 missed, 2 extra** (precision 0.8, recall 0.8) — equal totals hiding a real
miss and a real extra, the exact failure mode this project's event-matching exists to catch. One
miss is a genuine early completion (detector fired ~217ms before the labeled window); the other is
the trailing partial rep visually judged (in the label file, before this replay) as not completing
before the clip ends. A detector event is not independent evidence that this visual judgment was
wrong: the trailing disagreement remains unresolved until a separate raw-frame review. The numeric
precision/recall above measure agreement with the current assistant annotations, not verified
movement accuracy. One clip, one recording with unverified identity grouping,
front-on view, first-loop development evidence only; no generalization or held-out claim.

## Repeatable cross-movement batches

`tools/offline_replay_batch.py --manifest /absolute/private/manifest.json --output-dir
/absolute/private/new-run` serially invokes the existing Kotlin-backed entrypoints. It neither
extracts poses again nor reimplements counters. Manifest version1 contains an absolute `model`
path and nonempty `jobs`: each has a unique `id`, `engine` (`squat`, `lunge`, `jumping_jack`, or
`plank`), and absolute `video`, `cache`, `config` paths. `labels` and `corpus_manifest` are optional
absolute paths; held-out inputs still require the corpus manifest through the existing tools.

Use a NEW private output directory outside Git every time. The runner preserves the input manifest
and its hash, per-case invocation/log/report hashes, immutable intermediate checkpoints, and a
final summary. It stops with nonzero exit on a failed/timed-out child or missing report. COMPLETE
means all replay invocations completed, never that all movements were correctly identified.
Missing labels stay UNLABELLED, not an implicit zero-event ground truth. A changed configuration
needs a new run directory; do not overwrite prior predictions or move labels to match them.

The initial seven-case cross-movement diagnostic batch reproduced six squat candidates on the
lunge clip, eleven lunge candidates on the squat clip, and one jack candidate on the squat clip.
These are unscored cross-movement observations, not false-positive-rate estimates. A raw-frame
inspection around the jack event showed the end of squats followed by standing leg movements;
the state machine connected an earlier projected-arm signal to a later stance closure. This
limits movement-identity claims: all four tools remain selected-geometry experiments, not a
classifier. No arbitrary temporal threshold was added to hide that sequence.

## Author annotations and identity grouping: source correction

The official MM-Fit [EDA notebook](https://github.com/KDMStromback/mm-fit/blob/e4b6b7e1fff68e3d93130ed087d23593c9016c94/EDA.ipynb)
documents per-workout CSV annotations with Start Frame, End Frame, Repetition Count, and Activity
Class. These are **exercise-set** annotations, not per-repetition completion timestamps. They live
in the original dataset download; the later Zenodo RGB-only listing is not the full annotation
inventory. Earlier exploratory notes that inferred unavailable author labels from that video
listing were incomplete. Import requires verified frame-to-video alignment; never divide frame
numbers by nominal FPS and assume the released video shares its origin.

The same notebook publishes a workout-to-pseudonymous-participant mapping: w00=2, w01=0, w19=8.
Thus identity grouping is not wholly undocumented, contrary to some early exploratory notes.
Existing frozen manifests/reports retain their original provenance; a future version should use
the author mapping. Different participant IDs do not make already inspected recordings an
untouched test set, prove a new camera viewpoint, or validate technique. Author set totals can
support count comparisons after alignment, but cannot resolve individual completion timing or
cancel the missed/extra-event problem hidden by equal totals.

## Sit-up-family selected-mode replay

The shared Kotlin sit-up candidate and standalone `tools/offline_situp_feedback.py`
now use the same saved-input, hash-bound feedback pattern. One labelled development
clip produced9 candidates with8 matched/1 missed/1 extra after a single documented
configuration revision; a second recording remains UNLABELLED, and one squat
negative control produced0 sit-up candidates. These are not automatic movement
recognition or mobile-validation claims. The four-engine batch runner is unchanged.

The detailed failure, preserved timing discrepancy, provenance boundaries and next
evidence needs are in `docs/2026-09-07-situp-offline-baseline-result.md`. Both reuse
research tracks remain in `docs/superpowers/plans/2026-09-07-reuse-led-movement-expansion.md`;
the new baseline does not replace PoseRAC/RepNet feasibility or close older movement
confusions.
