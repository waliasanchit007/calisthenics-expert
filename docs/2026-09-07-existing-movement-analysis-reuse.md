# Existing movement-analysis reuse: research and next experiment

Research date: 2026-09-07. Source inspection only: no external model was executed,
no weights or datasets downloaded, no app/dependency changes. This is a shortlist,
not an exhaustive survey or an accuracy claim. Owner approved broader offline
movement coverage and asked to check existing work before implementing it.

## Decision

Keep the current MediaPipe backend and Kotlin replay baseline. Before writing more
exercise-specific rules, compare an existing example-based classifier against the
same frozen development inputs. Google supplies the most practical first baseline;
PoseRAC is a second research comparator, not a ready-made automatic recognizer.
Do not delay other permissioned dataset preparation for gated academic datasets.

## Reusable candidates

### Google: embedding, nearest-neighbour classification, repetition counting

[Official guide](https://developers.google.com/ml-kit/vision/pose-detection/classifying-poses)
provides sample-driven classification and counting, with push-up/squat examples.
The [sample repository](https://github.com/googlesamples/mlkit) uses Apache-2.0;
retain attribution/license and inspect any applicable notices before copying.
Code terms do not establish source-image rights for bundled training examples.

Observed master: `f1047837fe1e02d58063bfc30b830fad23ffbc35`.
The inspected [PoseEmbedding.java](https://github.com/googlesamples/mlkit/blob/f1047837fe1e02d58063bfc30b830fad23ffbc35/android/vision-quickstart/app/src/main/java/com/google/mlkit/vision/demo/java/posedetector/classification/PoseEmbedding.java)
centres hips, scales by a torso/body-size rule and computes joint-pair vectors.
It does not perform rotation normalization; do not infer that from the guide's
broader wording. The [classifier](https://github.com/googlesamples/mlkit/blob/f1047837fe1e02d58063bfc30b830fad23ffbc35/android/vision-quickstart/app/src/main/java/com/google/mlkit/vision/demo/java/posedetector/classification/PoseClassifier.java)
uses mirrored comparisons, two-stage nearest-neighbour filtering and reduced Z
weight. Its votes are not calibrated probabilities or an unknown-action detector.
Recommendation: adapt this reference algorithm offline, without adopting ML Kit
as another pose backend. Preserve missingness and test degenerate geometry.

### PoseRAC: learned pose-state outputs and released checkpoint

[Official repository](https://github.com/MiracleDance/PoseRAC), observed main
`469590b611bde3595eaf163b517263da634e2096`, has MIT software licensing.
The README links pre-extracted test poses, annotations and a checkpoint. Data and
weight provenance/permissions require their own checks before admission.
[Class inventory](https://github.com/MiracleDance/PoseRAC/blob/469590b611bde3595eaf163b517263da634e2096/all_action.csv):
pull-up, squat, jumping jack, sit-up, push-up, front raise, bench pressing,
pommel horse. It does not supply dips, rows, L-sit or handstand support.

[Preprocessing](https://github.com/MiracleDance/PoseRAC/blob/469590b611bde3595eaf163b517263da634e2096/pre_test.py)
uses legacy MediaPipe Pose, 33 XYZ points, frame-wise per-axis min/max normalization,
and a zero vector for missing poses. This is not our raw cache representation;
confidence is not part of that 99-value input. No equivalence to current Android
inference is established. The reference evaluator explicitly calls CUDA, so it is
not an unmodified runnable Mac benchmark.

Critical evaluation caveat: [eval.py](https://github.com/MiracleDance/PoseRAC/blob/469590b611bde3595eaf163b517263da634e2096/eval.py#L50-L121)
reads the ground-truth count, evaluates every action output, and reports the lowest
error. This is oracle output selection, not deployable class selection. The
visualization script also selects using the known count. This observation applies
to the inspected scripts, not a blanket allegation about every paper experiment.
For our comparison, freeze the selected action before inference, or separately
evaluate a label-blind recognizer. Never pick a model output using expected counts.

### Existing labelled skeleton resources

- [Fit3D](https://fit3d.imar.ro/fit3d): 47 exercises, 25-joint 3D skeletons,
  multi-view recordings and repetition intervals. [Download](https://fit3d.imar.ro/download)
  requires an account. Full applicable terms and access not cleared in this pass.
- [FLAG3D](https://andytang15.github.io/FLAG3D/): 60 fitness categories, motion-capture
  skeletons, rendered and natural-scene videos. The authors require a signed
  organizational/advisor agreement to obtain data. Do not sign or email on the
  owner's behalf. Not an immediate dependency.
- PoseRAC's pre-extracted poses are estimates, not landmark ground truth. RepCount
  event labels and action labels are distinct from those estimates. The original
  [RepCount description](https://github.com/SvipRepetitionCounting/TransRAC)
  identifies YouTube-derived Part A and school-recorded Part B; availability does
  not by itself settle reuse rights. Do not automatically ingest those videos.

## Do we need more landmarks?

[MediaPipe Pose](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)
already supplies 33 body points. Landmark identities are exercise-independent;
movement classes and phase examples are additional information, not new body points.
[MediaPipe Hands](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker)
and [MMPose whole-body models](https://github.com/open-mmlab/mmpose) are possible
future comparators. More points are not demonstrated improvements on our clips.
Do not change backend/topology just to increase the count, or claim grip/scapular
measurements without independent evidence. Model comparisons need matched inputs,
documented topology/coordinates and separate desktop/mobile results.

## Next bounded experiment

1. Pin the Google reference; isolate its adaptation from production code. Test
   embedding parity using synthetic inputs before any benchmark interpretation.
2. Build a small example bank from permissioned development footage. Label terminal
   states before predictions; split by original recording/person, not random frames.
   Include rest, transitions and confusing other exercises, not just target endpoints.
3. Run the reference and existing Kotlin counters on the same saved inputs. Keep
   original configurations and labels. Report class confusion, unknown/rejected
   coverage, completion timing, misses/extras and final counts separately.
4. Only then decide whether to use example-based classification as an extra signal.
   Keep uninterrupted temporal evidence for counts; a pose-state match is not a rep.
5. Inspect PoseRAC checkpoint/data provenance for a second comparator without oracle
   selection. Do not train a large model or import upstream dependency environments
   into the app. Never execute untrusted pickle checkpoint code to make it load.

New exercise priority follows available evidence. Sit-ups now deserve consideration
alongside the planned batch because existing public work explicitly covers them.
Dips/rows/raises and holds remain on the roadmap; research has not made them supported.
No self-test, app deployment, automatic recognition claim, upload or release is needed
for this experiment. No running background work is implied by this document.
