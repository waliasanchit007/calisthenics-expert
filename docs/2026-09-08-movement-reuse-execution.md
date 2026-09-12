# Movement reuse execution — 2026-09-08

Continuation of the approved reuse-led plan, not a new roadmap. MediaPipe and the
shared Kotlin counter remain unchanged. Both September7 research notes continue
to govern the dynamic and static-skill tracks. No app deployment or owner workout.

## New negative evidence with frozen sit-up v2

| Saved source | Frames | Missing selected geometry | Sit-up candidates |
| --- | ---: | ---: | ---: |
| Side-lying, brief propping/lowering, leg lifts;65seconds | 1949 | 0 | 0 |
| Supine alternating leg tucks/extensions, small curls;30seconds | 900 | 0 | 0 |

The controller inspected raw1fps/2fps sheets and froze explicit zero-event labels
before sit-up predictions. Both cases use the existing Core session2 source and
remain development evidence, not held-out. Fixed RIGHT was selected from usable
shoulder/hip/knee coverage before predictions; all geometric v2 parameters are
unchanged. Outputs/config/labels/source/cache hashes remain separate outside Git.

The leg-tuck case is informative: hip angles range36.50-121.03degrees and650frames
enter the closed-hip band, while trunk elevation never exceeds23.90degrees.
The counter enters RISING but never RAISED. Thus substantial hip movement alone
does not become a sit-up under the frozen trunk gate. This is a narrow successful
rejection, not proof that arbitrary non-exercise transitions are recognized.

One inherited content summary overstated how long the side-plank posture was held.
The new annotation follows the raw footage rather than copying that description;
the original record is preserved. Neither case validates form or ground contact.

## Untouched evaluation: selected, not completed

The first sit-up set in MM-Fit w16 was selected before predictions: the smallest
unused original available in the inspected [release inventory](https://zenodo.org/records/7672767).
The author's [workout mapping](https://github.com/KDMStromback/mm-fit/blob/e4b6b7e1fff68e3d93130ed087d23593c9016c94/EDA.ipynb)
assigns participant5, separate from the previously used MM-Fit groups0/2/8.
This is source selection, not a generalization result.

The protocol freezes v2, the raw-label-first method, observed-side selection,
first-set selection and a no-retuning rule. Author frame interval45035-45777
only locates a padded raw inspection; nominal FPS is not assumed exact PTS
alignment and author aggregate count is not a per-event oracle.

Zenodo API/export/direct-file requests returned504 or timed out with0bytes.
An alternative official file-content/range endpoint also timed out. No video
payload was admitted, no labels or predictions produced. This case remains
UNMEASURED. Repeated familiar clips have not been substituted as held-out evidence.

## PoseRAC now passes actual safe CPU inference

Claude-work Opus5 completed the bounded feasibility implementation and smoke.
Pinned [source and checkpoint](https://github.com/MiracleDance/PoseRAC/tree/469590b611bde3595eaf163b517263da634e2096)
were inspected under the repository's MIT notice; no conflicting checkpoint
notice was found. The checkpoint is repository content, not the external linked
RepCount-pose dataset. No rights for those external videos were inferred.

Controller independently checked the downloaded checkpoint:

- Size10,772,679bytes; Git blob9ca16b650fed5d908e30bd8367de8a62d5a9ac59 matches the pin.
- SHA25621afc8334333e5f5751f0e5d765376a778415285c0ac3078d8358ccb83b2b34a.
- An isolated Python3.12.14 environment uses torch2.14.0 and NumPy2.3.5, with
  wheel URLs/hashes recorded. Existing MediaPipe and app environments are unchanged.
- Loading uses only `weights_only=True` and `map_location='cpu'`, with no unsafe
  fallback or safe-global additions. No Lightning/training-module import.
- Audited Transformer/linear constructor:74tensor entries,2,686,682parameters,
  no key/shape mismatches. The checkpoint tensors observed are allfloat32.
- Synthetic240-frame output has shape240x8, finite and bitwise equal across
  three repeated CPU runs. `situp` column5 was fixed before inference. Missing
  or zero-range input is rejected, not zero-filled.

The initial smoke established runtime compatibility, not counting quality. Before
real replay, the controller found that PyTorch strict loading can silently convert
dtypes. Claude-work added the missing-behavior regression and explicit dtype
rejection before loading. The controller then ran the final tests directly:
**56 tests passed, exit 0** (25 adapter and 31 replay tests). Float64/float16
checkpoints are rejected without changing live parameters; the pinned float32
checkpoint still loads. This corrects a narrow acceptance gap, not the model.

The next bounded run uses our existing rights-cleared clips, not RepCount-pose.
Its external dataset permissions are therefore not a prerequisite for this local
comparison. It preserves the published fixed-column decoder settings and full
sequence context; no oracle best-action choice or count-driven retuning is allowed.
Full-sequence Transformer inference is noncausal offline work, not live latency
or streaming equivalence.

## Actual fixed-action comparison

Claude-work ran the complete three-clip comparison without reading labels, previous
predictions or expected counts. The controller compared the saved outputs only
afterward, and independently checked input/video/label/code hashes, frame counts,
original PTS alignment and event frame indices. All bindings passed.

| Case | Reviewed full sit-up cycles | Kotlin v2 count | PoseRAC fixed sit-up count |
| --- | ---: | ---: | ---: |
| MM-Fit w01 development | 9 | 9 | 10 |
| Side-lying/propping/leg lifts | 0 | 0 | 2 |
| Supine leg tucks/small curls | 0 | 0 | 1 |

These are shared clips, not a held-out benchmark. Both counters were asked to
count sit-ups; neither result establishes automatic exercise recognition. The
two negative clips expose three unwanted counts in the frozen PoseRAC decoder.
All frames passed its input gate, so this is not a missing-frame rejection.
No threshold sweep, best-action oracle or count-based polarity selection was used.

The w01 totals alone are misleading. Kotlin's existing report has 8 matched return
events, 1 miss and 1 extra against the nine frozen windows. PoseRAC's upstream
decoder closes on an alternating salient-pose exit, not a confirmed reclined
return. The same mechanical matcher yields 0 matches, 9 misses and 10 extras, but
that is an **event-definition mismatch**, not evidence of zero movement-detection
accuracy. No timing shift or annotation rewrite was used to manufacture agreement.
An endpoint-alignment experiment would need its own predeclared protocol.

**Decision:** retain Kotlin v2 as the current exploratory sit-up baseline. Keep
PoseRAC as an executed offline comparator, not an app replacement or a failed
model family. Its published decoder does not currently satisfy our return-event
and negative-rejection requirements. Do not spend further cycles tuning it on
these same three clips. RepNet remains a separate, untested RGB comparison.

Verification in this continuation: the two Kotlin negative replays exited 0;
56 isolated PoseRAC tests passed in a fresh controller run; 10 focused reporting
tests and repository guard passed. The preceding full integration result remains
1208 Python and 603 JVM tests, not a newly rerun full suite. No application source
or live counter configuration was changed in this continuation.

## Still open

RepNet remains the independent RGB comparator. Static-skill data still need
rights/access and a BODY25B-compatible representation; no padding toMediaPipe33.
The old lunge/jack confusions and plank false hold are not closed by new sit-up
negatives. The untouched-video cell must be completed before any generalization
claim. Exact private paths, process status and commands remain in the ignored
implementation ledger; no push, PR, merge, tag, data upload or device action.

## Later overnight follow-up

The frozen w16 evaluation subsequently completed without retuning:9count/9visual
cycles,4exact-window matches and5early counts. The missing cell above describes
this report's earlier checkpoint, not current state. PoseRAC squat/jack matrix,
RepNet non-admission, lunge evidence and static observability also advanced. See
[the overnight result](2026-09-08-overnight-movement-progress.md) for current evidence
and limitations; do not re-run completed work from the earlier open-items list.
