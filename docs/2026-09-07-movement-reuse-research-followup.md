# Movement reuse research: calisthenics-specific follow-up

Date: 2026-09-07. Source and code inspection only. No external models executed,
weights or datasets fetched, training performed, or app behavior changed. Source
availability, reuse permission, benchmark accuracy, and mobile validation are
separate claims. This extends the earlier reuse report rather than replacing it.

## Recommendation

Keep MediaPipe and the current offline replay path. Reuse labelled movement
examples and compare existing algorithms before inventing more per-exercise
thresholds. Prioritize two evidence tracks: dynamic repetitions on already
permissioned recordings, and static skills using the newly identified dataset
if its access and reuse terms can be established. Do not let a gated dataset
hold up the first track.

The product goal remains movement identity, repetitions/hold duration/transitions,
and eventually defensible technique feedback. A class label is not a form score;
a correct final count does not prove correct event timing.

## 1. The strongest new match: calisthenics holds

[Calisthenics Skills Temporal Video Segmentation](https://arxiv.org/html/2507.12245v1)
reports 839 clips with manually labelled skill intervals and extracted OpenPose
keypoints. Classes are back lever, front lever, human flag, iron cross, Maltese,
one-arm front lever, one-arm handstand, planche and V-sit, plus NONE. Clips contain
one skill with possible NONE intervals, not unrestricted multi-skill routines.
The representation is BODY_25B: 25 joints with normalized X/Y and confidence.
This is neither MediaPipe's topology nor 3D ground-truth motion capture.

The [official repository](https://github.com/fpv-iplab/calisthenics-skills-segmentation)
was inspected at `480918a02ce1339859e4c432a92b5ed6548901a5`:

- The [keypoint CSV entry](https://github.com/fpv-iplab/calisthenics-skills-segmentation/blob/480918a02ce1339859e4c432a92b5ed6548901a5/data/keypoints_dataset.csv)
  is a Git LFS pointer declaring 62,498,465 bytes and SHA-256
  `4adec3915627cf06f7fd6b5f142c9325569813cc704f2c9f93137332da9a9f93`.
  The object itself was not downloaded or hash-verified.
- No explicit LICENSE file, trained weights, or source videos appeared in the
  complete inspected tree. Public hosting does not establish reuse permission.
- The [training code](https://github.com/fpv-iplab/calisthenics-skills-segmentation/blob/480918a02ce1339859e4c432a92b5ed6548901a5/src/model/mlp.py)
  groups the split by video name, not athlete identity. This prevents the simplest
  random-frame split but does not establish unseen-athlete generalization.

Use this as the leading static-skill data candidate, not nine supported features.
Do not rename/pad 25 joints into 33 inputs. Either evaluate its original
representation separately, or explicitly define shared joints and retrain on that
representation; source-video access would allow a matched MediaPipe extraction.
Keep confidence separate from depth and missing points separate from valid zeros.

## 2. RGB is a useful comparison, not a mandated backend replacement

A [follow-up study](https://arxiv.org/html/2507.12292v1) compares person-cropped
RGB/depth classification on the calisthenics dataset. It reports competitive
classification without the OpenPose pipeline, but its performance measurements
use a Tesla V100S GPU, not our S25 or MediaPipe implementation. It motivates
testing visual context, not importing its full depth stack into the app.

The [code repository](https://github.com/antof27/rgb-based-pose-classification)
at `4a3615ad13c2f822a2091659e64216d63aa571f4` requests contact for image data;
its README's weights link points to a Dropbox home path, not a verified public
download. No LICENSE appeared in its complete tree. These are unresolved reuse
and reproducibility prerequisites, not a ready-to-run model package.

For repetitions, [Google RepNet](https://github.com/google-research/google-research/tree/master/repnet)
is an independent RGB-based comparator. It estimates repetition rather than
exercise identity or technique. The authors' [evaluation clarification](https://arxiv.org/html/2411.08878v1)
documents important differences between implementations, temporal sampling, and
counting metrics. Use a pinned implementation with declared sampling; do not
choose from headline accuracy tables. Agreement with our counter is diagnostic
evidence, never a replacement for independently labelled events.

## 3. Other candidates and why they are not first

| Candidate | Potential value | Current limitation |
|---|---|---|
| [Fitness AI Trainer](https://arxiv.org/html/2411.11548v1) | Temporal MediaPipe features and an exercise-recognition baseline | Four classes: squat, push-up, shoulder press, biceps curl. Thirty-frame windows and dropped invalid frames need explicit timing/missingness adaptation. Classification accuracy is not counting accuracy. |
| [MotionBench](https://github.com/johnamit/motionbench) | CPU temporal-model comparison | Inspected split code stratifies sequence-table rows by class; it does not enforce source-person/video grouping. Upstream grouping must be established before trusting generalization. |
| InfiniteRep | Historical synthetic exercise/keypoint resource used by the Fitness AI Trainer paper | The cited `toinfinityai/InfiniteRep` GitHub endpoint returned 404. Current official distribution and terms were not verified; do not make it a dependency. |
| [Fitness-AQA](https://github.com/ParitoshParmar/Fitness-AQA) | Annotated quality-assessment examples | Noncommercial, request-gated, and focused on back squat, overhead press and barbell row—not bodyweight inverted rows. |
| [UCF101](https://www.crcv.ucf.edu/research/data-sets/ucf101/) | Broader action-video categories including handstand push-ups | Action labels are not repetition boundaries or technique labels. ParallelBars and Rowing cannot be relabelled as dips and inverted rows. |

MotionBench code inspected at `d7c2b34106b069bd0fbf1b976b5e493176578902`, specifically
[`create_fixed_splits.py`](https://github.com/johnamit/motionbench/blob/d7c2b34106b069bd0fbf1b976b5e493176578902/scripts/preprocess/create_fixed_splits.py).
Its README's MIT claim was not accompanied by a LICENSE file in the inspected
tree. No claim is made that every possible upstream split is invalid.

No inspected resource established an immediately reusable, rights-cleared package
covering dips, inverted rows, pike push-ups and hanging raises with both compatible
landmarks and independently labelled repetition boundaries. This is a bounded
search finding, not a claim that such resources do not exist.

## 4. The practical next experiment

1. Finish one reusable comparison on already permissioned clips: existing Kotlin
   counter versus the Google example-based pose classifier described in the first
   report. Keep class selection label-blind, or explicitly call it selected-mode
   counting. Do not silently compare these different tasks.
2. Freeze labels and source-disjoint evaluation clips before tuning. Include rest,
   setup, transitions and confusing movements. Existing repeatedly tuned clips
   remain regression/development evidence, not a fresh accuracy estimate.
3. Add RepNet as an offline comparator only if its pinned environment and weights
   pass a small smoke test without substantial infrastructure work. Defer it if
   setup costs exceed the diagnostic value; do not block the first comparison.
4. In parallel, resolve the static-skill dataset's rights/access and representation.
   Start with a small subset such as front lever, back lever and planche plus NONE
   and confusions. Do not represent the closed class set as arbitrary-action support.
5. Report per-event misses/extras and timing, hold-duration error, class confusion,
   unknown/rejected coverage and latency. Classification confidence is not a
   technique score. Report offline and target-device results separately.

This avoids another workout dependency without claiming public video benchmarks
fully replace eventual phone validation. It also avoids a new model per exercise:
shared features and timing can serve multiple movement families, with specific
exceptions justified by failed replay cases rather than anticipated complexity.

## Evidence boundary

No new movement is implemented or validated by this research. No source footage,
weights, or participant material was ingested; no access agreement was accepted.
Existing worktree edits are preserved. No commit, push, PR, merge, tag, or device
operation was performed for this report.
