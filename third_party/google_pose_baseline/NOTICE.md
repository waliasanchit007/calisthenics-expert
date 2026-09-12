# Google ML Kit pose-classification sample attribution

This directory contains a Python adaptation of the following Apache-2.0
licensed Google sample sources:

- Repository: https://github.com/googlesamples/mlkit
- Pinned commit: `f1047837fe1e02d58063bfc30b830fad23ffbc35`
- Source directory: `android/vision-quickstart/app/src/main/java/com/google/mlkit/vision/demo/java/posedetector/classification/`
- Adapted sources: `PoseEmbedding.java`, `PoseClassifier.java`, and `Utils.java`
- Copyright: 2020 Google LLC

The adaptation is the bounded offline `tools/google_pose_baseline.py` port.
It retains the upstream embedding vector layout, normalization rule, mirror
query, weighted distances, and two-stage nearest-neighbor selection. The
Python API adds explicit finite-33-point validation and deterministic
input-order handling for equal-distance ties.

The upstream Apache License 2.0 text is included in [LICENSE](LICENSE).
