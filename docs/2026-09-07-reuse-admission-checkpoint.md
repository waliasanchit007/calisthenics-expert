# Reuse admission checkpoint — 2026-09-07

This is a prerequisite inspection, not model execution, accuracy evidence or
permission to redistribute data. It supplements both approved research notes;
it does not replace either. No weights, source videos or dataset were downloaded.

## Decision

Prioritize PoseRAC's safe, fixed-action CPU feasibility over RepNet setup because
it consumes pose data already cached here and its advertised checkpoint is much
smaller. This is an engineering-cost inference, not a performance ranking.
Keep RepNet as the independent RGB comparator; keep static-skill access as a
parallel unresolved prerequisite. Our local nearest-example experiments tested
neither of these learned models.

## PoseRAC

Pinned repository revision:
`469590b611bde3595eaf163b517263da634e2096`.

- [Checkpoint metadata](https://api.github.com/repos/MiracleDance/PoseRAC/contents/best_weights_PoseRAC.pth?ref=469590b611bde3595eaf163b517263da634e2096):
  10,772,679 bytes; Git blob `9ca16b650fed5d908e30bd8367de8a62d5a9ac59`.
  No payload download or SHA256 admission occurred.
- [Evaluator](https://github.com/MiracleDance/PoseRAC/blob/469590b611bde3595eaf163b517263da634e2096/eval.py)
  loads a state dictionary with torch.load, forces model/input CUDA, then tests
  every action output against gt_count and keeps the minimum error. That script
  is not our benchmark or an automatic recognizer.
- [Preprocessor](https://github.com/MiracleDance/PoseRAC/blob/469590b611bde3595eaf163b517263da634e2096/pre_test.py)
  converts coordinates to width/height/width units then normalizes each axis per
  frame. Missing poses become 99 zeros. Neither zero-range handling nor our
  explicit missingness semantics may be silently replaced with this convention.
- [Requirements](https://github.com/MiracleDance/PoseRAC/blob/469590b611bde3595eaf163b517263da634e2096/requirements.txt):
  Lightning1.9.2 plus metric-learning, pandas, MediaPipe, OpenCV and YAML.
  Importing the training module would bring training dependencies into inference.
- MIT software license verified in the pinned repository. No separate
  checkpoint/data permissions statement was found in the inspected README/license;
  do not infer rights for its external training videos.

Next inference preconditions: establish checkpoint reuse scope, fetch outside
Git with explicit digest binding, load using a restricted weights-only loader
without unsafe fallback, construct the audited inference architecture on CPU,
and verify parameter keys/shapes and preprocessing with synthetic fixtures.
Freeze the selected action before inference. Use original PTS and preserve gaps;
a gap reset is an explicit adaptation, not claimed upstream parity. Inspect the
existing environment before installing anything. If safe loading/compatibility
fails, record the actual blocker rather than unpickling arbitrary objects.

## RepNet

Pinned latest commit touching repnet returned by the GitHub API:
`60d48fc1b71b6126462136cad441dd76da7460d0`, 2025-06-30.
[Official directory](https://github.com/google-research/google-research/tree/60d48fc1b71b6126462136cad441dd76da7460d0/repnet).

The reference notebook imports TensorFlow, OpenCV, scipy, plotting and Colab
helpers. It includes a checkpoint variable-name mapping for Keras compatibility,
not a ready standalone replay adapter. It uses temporal strides and video FPS;
any local extraction must state its PTS-to-model-grid mapping and preserve which
observations were sampled. Do not adopt the notebook's downloading/upload UI.

HEAD inspection of the official checkpoint data URL returned HTTP200:
`https://storage.googleapis.com/semantic_repetitions/repnet_ckpt/ckpt-70.data-00000-of-00001`

- Content-Length: 309,204,174 bytes.
- Generation: 1747092903683100.
- ETag: 7d97aa53df6817737a84a4b1c4a10204.
- Separate checkpoint manifest and index are also required.
- Headers establish reachability/advertised size, not validated payload integrity
  or a successfully loaded model.

Next: inspect isolated runtime availability and checkpoint reuse scope; only then
perform one pinned local synthetic constructor/inference smoke test. If it needs
substantial environment repair, defer it while the pose/counter track proceeds.
It counts periodic activity; it is not an exercise identity or technique oracle.

## Static calisthenics skills

The full, non-truncated GitHub tree still resolves to
`480918a02ce1339859e4c432a92b5ed6548901a5`:
[repository](https://github.com/fpv-iplab/calisthenics-skills-segmentation/tree/480918a02ce1339859e4c432a92b5ed6548901a5).

The tree has README, requirements, code and one keypoint CSV entry, no LICENSE,
source video or trained weight file. README supplies installation directions but
no reuse grant. The CSV entry remains an LFS pointer:
62,498,465 bytes, SHA256
`4adec3915627cf06f7fd6b5f142c9325569813cc704f2c9f93137332da9a9f93`.

Status: access/terms unresolved, not silently abandoned and not ready to train.
Do not download/admit or map BODY25B to MediaPipe33 by padding. The existing
research report's front-lever/back-lever/planche subset plus NONE remains the
proposed first comparison if rights and representation are resolved. No contact
message or agreement was sent/signed on the owner's behalf.

## What remains actionable without these prerequisites

Read-only package metadata in the existing offline Python3.12.14 environment
shows MediaPipe0.10.21 and NumPy1.26.4, but no torch, TensorFlow or Lightning.
Neither candidate can run unchanged in that environment. No packages were
installed and no model constructor was attempted. This is a concrete setup
prerequisite, not a model-quality result.

The historical scorecard and existing rights-cleared replay clips are independent.
Use them to identify the next highest-value positive/negative data package.
Sit-ups remain a candidate alongside dips/rows/raises; do not choose only from
a preferred exercise list while ignoring available labels. The next new exercise
is not decided by this prerequisite check.

## 2026-09-08 execution addendum

The preceding sections describe the original metadata-only checkpoint. They are
not the current PoseRAC execution status: the authorized continuation inspected
checkpoint provenance, built an isolated restricted-loading CPU adapter and ran
a fixed-action, label-blind comparison over three local clips. See
[the execution report](2026-09-08-movement-reuse-execution.md) for actual counts,
test evidence and the event-definition limitation. This does not establish rights
to RepCount-pose, reproduce its published benchmark, or close the distinct RepNet
and static-skill prerequisites. The existing MediaPipe environment was unchanged.
