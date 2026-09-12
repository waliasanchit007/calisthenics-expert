# First example-based classifier comparison

## Outcome

Do not replace or gate the existing counter with a single-frame classifier.
The first source-disjoint development comparison shows why: the example-based
classifier recognizes much of a squat recording, but also labels a sustained
bent-leg overhead press as squat-like. The existing temporal squat counter
correctly produces zero cycles on that control.

This is evidence for retaining temporal movement logic, not proof that example
classification is useless. It remains a possible additional signal; an example
bank with only three labels cannot establish arbitrary exercise recognition.

## What was implemented

- `tools/google_pose_baseline.py`: stdlib Python adaptation of Google's embedding
  and two-stage nearest-neighbor sample. Hip centering, XY-derived scale, 23 vector
  features, X-mirror comparison, reduced Z weight, top-30 MAX then top-10 MEAN
  distance. No pose backend, model weights, learned network or repetition engine.
- `tools/offline_pose_comparison.py`: uses existing hash-bound pose caches,
  converts normalized X/Y/Z into width/height/width pixel units, rejects missing
  or low-confidence observations and reports unsmoothed votes. All 33 landmarks
  affect upstream scale normalization, so this run requires all 33 to pass the
  explicit 0.5 presence/visibility floor; this is conservative prototype policy,
  not a measured quality threshold.
- Example bank inputs must be development data, with declared interval-label
  provenance. Queries cannot share a declared group, parent recording hash or
  derivative video hash with the bank. The tool accepts development queries
  only; it cannot relabel inspected clips as a held-out benchmark.
- Inputs, code, selected example timestamps and predictions are hash-bound.
  Source changes during processing prevent a complete summary. Outputs are
  exclusive-create outside Git. Rejected frames remain in the output timeline.

The Python port adds finite/degenerate-input rejection and deterministic
input-order tie handling, rather than Java priority-queue tie ordering. Numeric
precision differs from Java floats. Hand-derived synthetic vectors and behavior
tests are exercised; the upstream Java implementation was not executed. The
MediaPipe-to-pixel adapter is not evidence that ML Kit and MediaPipe estimates
are interchangeable.

Upstream pinned source:
[`googlesamples/mlkit@f1047837fe1e02d58063bfc30b830fad23ffbc35`](https://github.com/googlesamples/mlkit/tree/f1047837fe1e02d58063bfc30b830fad23ffbc35/android/vision-quickstart/app/src/main/java/com/google/mlkit/vision/demo/java/posedetector/classification).
Apache-2.0 license and attribution are retained under
`third_party/google_pose_baseline/`. No upstream example imagery was imported.

## Frozen experiment

Bank: MM-Fit w00, 74 usable examples sampled at an explicit 500 ms interval:
31 squat, 15 lunge and 28 preparation/rest/other. Labels reuse the pre-existing
raw-video inspection records, not classifier predictions. These are coarse
movement labels, not independent phase or technique ground truth. Unequal class
coverage is recorded, not silently balanced after inspecting results.

Queries: three previously inspected w01 clips, separate parent recording from
the bank. Existing metadata mixes historical grouping names; checking the parent
recording hash as well prevents that naming inconsistency from admitting another
crop of the same source. These clips are still development evidence. No fresh
workout, new media, smartphone camera use or participant recruitment was needed.

| Query | Existing Kotlin squat candidates, freshly replayed | Classifier observations |
|---|---:|---|
| Ten-squat development set | 10 | 516/660 frames labelled squat; all 10 counter completion timestamps labelled squat; 40 frames rejected |
| Sustained bent-leg overhead presses | 0 | 1,326/1,650 frames labelled squat despite no squat cycles; 61 rejected, 19 ties |
| Jumping jacks | 0 | 64/405 frames labelled squat, 328 other, 10 lunge, 3 ties; no rejected frames |

Fractions above use all frames as denominator, not only accepted observations.
They are descriptive label frequencies, not frame-classification accuracy.
Preparation/rest is present in the positive clip, and no independent per-frame
class oracle was introduced. Ten candidates agree with the previously documented
set total, but that is not an independent per-repetition timing evaluation.

The classifier has no open-set distance rejection: high votes can still assign an
unseen activity to a known class. `other` is a sampled class, not a universal
unknown detector. Ties and unusable input are exposed separately. Votes must not
be presented as confidence percentages or a form score.

## Repeat locally

Private corpus root on this host:
`/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/offline-corpus`.

```sh
.venv/bin/python tools/offline_pose_comparison.py \
  --manifest /absolute/private/corpus/google-example-comparison-request-v1.json \
  --output-dir /absolute/private/corpus/new-comparison-directory

.venv/bin/python tools/offline_replay_batch.py \
  --manifest /absolute/private/corpus/google-comparator-kotlin-baseline-request-v1.json \
  --output-dir /absolute/private/corpus/new-kotlin-directory
```

Recorded outputs are `google-example-comparison-v2/summary.json` and
`google-comparator-kotlin-baseline-v1/summary.json`; per-frame predictions live
beside them. Their pose hashes match for each query. The earlier classifier v1
run is preserved; v2 reruns after a type-check-only source adjustment.
No labels, private source records, video or real landmark arrays are copied here.

## Next decision

Keep the current counters unchanged. The next bounded experiment should evaluate
movement over a time window, including rest and unfamiliar activities, rather
than using the instantaneous winner to approve/reject a rep. Start with the saved
cross-movement confusion cases and keep unsupported cases explicit. Additional
pose examples alone may not distinguish a held bent-leg posture from a squat
cycle; that is precisely what this control exposes.

Static-skill dataset access remains a separate reuse opportunity, not a blocker
for this local feedback loop. No new movement support, app accuracy, strict-rep
validation, technique assessment or release is claimed by this experiment.

## Verification

- `make verify`: exit 0; 1,139 Python tests passed (51.29 s), Ruff format/lint,
  strict mypy on 21 reference files, fixtures and repository guard passed. The
  formal participant-capture gate remains blocked as expected; that is not a
  failure of this offline experiment or permission for a study.
- Focused new tests: 36 passed (17 embedding/classifier, 19 comparison adapter).
- Targeted mypy with `--check-untyped-defs` on both new tools: passed.
- Three real Kotlin replay jobs: exit 0, counts 10/0/0. No fresh Android app build
  or device test was performed because this increment does not change app code.
- Initial RED evidence includes missing comparator functions, and behavioral
  failures proving metadata mutation and degenerate training banks were not
  rejected until corrected. The initial mirror test had only one candidate and
  was insufficient; it now has a competing candidate, and a mirror-disabled
  in-memory mutation selects the wrong candidate. MAX-versus-MEAN ranking is
  separately distinguished by competing distortions.
- Final private summary code, input and prediction hashes were rechecked against
  the actual files. `git diff --check` passed. Work remains uncommitted; existing
  branch/worktree and unrelated edits are preserved.
