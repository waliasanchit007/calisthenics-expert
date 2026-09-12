# Short-sequence comparator: development result

## Decision

Do not ship or use this comparator to gate repetitions. This fixed-window
nearest-example hypothesis does not resolve the saved confusion cases. It
removes squat labels from the jumping-jack control but loses many squat labels
on the real squat recording and retains most on the sustained bent-leg press.

This rejects this specific preset, not temporal modeling generally. No mobile
code, counters, confidence thresholds or exercise-support claims changed.

## Preset and matched comparison

The original request declared squat, lunge and other examples from MM-Fit w00
and three previously inspected w01 development queries. A causal 1.5-second
window selects 16 past observations at a 100 ms grid, with at most 50 ms sample
age. No interpolation, future observations or label-boundary borrowing.
A missing observation anywhere in the interval or a capture gap of at least
250 ms invalidates the window. All 33 points must pass the pre-existing 0.5
visibility/presence policy. These are prototype settings, not validated limits.

The representation concatenates time-ordered Google-derived pose embeddings
and their differences from the first embedding. Differences are not velocities.
It uses the existing MAX-then-MEAN nearest-example voting with a consistent
whole-window X-mirror alternative. This temporal extension is our hypothesis,
not an upstream Google algorithm. It does not learn weights, align tempo,
preserve global hip translation, or provide open-set rejection.

For each admitted training window, the static comparator receives its final
frame. Both methods therefore receive the same 50 examples (28 squat, 22 other)
and are evaluated at identical timestamps. Training endpoints are sampled every
500 ms; query endpoints every 100 ms. Both remain closed-set voters.

## Required-class failure preserved

Three-class request v1 exited 1 before output creation:

`no usable windows for required example classes: ['lunge']`

The existing lunge source does not supply a complete accepted window under this
preset. The tool refuses to silently remove a declared class. Request v2 is an
explicitly narrower squat/other experiment, using only the original squat bank
source, unchanged timing/confidence and unchanged queries. Lunge recognition
was not evaluated. Sparse tracking is a corpus limitation to address, not
permission to invent landmarks or lower a threshold until a run passes.

## Results

| Previously inspected development clip | Attempted / admitted windows | Static squat labels | Temporal squat labels | Temporal ties |
|---|---:|---:|---:|---:|
| Ten-squat set, including preparation/rest | 220 / 192 | 172 / 192 | 51 / 192 | 50 |
| Sustained bent-leg overhead presses | 550 / 484 | 424 / 484 | 399 / 484 | 4 |
| Jumping jacks | 135 / 120 | 19 / 120 | 0 / 120 | 0 |

Each clip has 15 warm-up rejections. Tracking rejects a further 13, 51 and 0
windows respectively. These are label frequencies, not accuracy, repetitions
or technique scores: no independent frame-level oracle was added. The negative
press file still carries an obsolete squat-like source description; content
interpretation follows the prior raw-video review documented in the static
comparison. The source record was preserved rather than silently rewritten.

These queries were already used in development, and overlapping windows are
not independent observations. Source-parent separation from the bank prevents
same-recording leakage, but does not establish held-out or cross-person
generalization. Ties remain visible; they are not counted as correct rejection.
No settings were retuned against these results.

## Reproducibility and scope

Private corpus root:
`/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/offline-corpus`.

- Original request: `temporal-example-comparison-request-v1.json`.
- Narrowed request: `temporal-example-comparison-request-v2-two-class.json`.
- Initial completed run: `temporal-example-comparison-v2-two-class/summary.json`.
- Final-source rerun: `temporal-example-comparison-v4-two-class/summary.json`.
- Each completed summary binds request, source/cache/model and implementation
  hashes, selected training timestamps, per-query prediction hashes and runtime.
- Output is private, exclusive-create and outside Git. Source/implementation
  mutation during processing prevents a completed summary.
- The final rerun follows type-annotation additions; no algorithm change was
  made after observing the results.

The v3 rerun failed with exit 1 because a concurrent packaging test temporarily
removed the model asset. It has no completed summary and is not evidence.
Run full packaging verification and model-bound comparisons sequentially;
the v4 rerun occurs after full verification has finished and restored the asset.

New code: `tools/temporal_pose_baseline.py` and
`tools/offline_temporal_comparison.py`, with corresponding reference tests.
The temporal core was delegated to a bounded Codex workhorse task; the controller
implemented causal sampling, paired comparison and the real corpus run.
No extra architecture/review loop was created for this experiment.

## Next practical increment

Use the existing squat, lunge, jumping-jack and plank replay entry points as an
exercise-specific regression matrix. For each, retain positive sequences,
confusing activities, missing-tracking cases and explicit unmeasured cells.
Prioritize filling the missing usable lunge sequence from rights-cleared sources
and independently annotating event/hold boundaries before tuning any detector.

Keep movement selection explicit while evaluating counters. Automatic movement
identification and technique coaching need separate evidence; neither should
be smuggled into a rep counter through a nearest-label vote. Reuse existing pose
estimation and replay infrastructure. Do not build a trained model or add more
phone UI until a candidate improves paired offline evidence.

## Verification

- `make verify`: exit 0, 1,178 Python tests passed in 37.10 seconds; reference
  formatting/lint, strict mypy on 21 source files, fixture checks and repository
  guard passed. The separate participant-study gate remains blocked as expected.
- Focused final-source tests: 39 passed in 0.07 seconds. Tests cover time ordering,
  causal selection, off-grid missing frames, recovery, label-boundary cropping,
  required-class absence, parent-source separation and actual file-based replay.
- Ruff check passed on both new tools and both test modules; strict mypy passed
  on both new tools. The controller observed these results directly.
- Final-source corpus run exited 0 in 36.38 seconds. All 22 recorded input,
  code and prediction hashes were independently rechecked after completion;
  predictions exactly match the earlier completed run.
- No Android build, phone deployment, device test or fresh CI run is claimed.
  Changes remain local and uncommitted alongside preserved prior work.
