# ADR-0006: Exercise-independent exploratory movement viewer

- Status: Accepted for software implementation by the owner's instruction to proceed, 2026-09-05.
- Scope: Android S25 viewer; actual human input requires separate explicit authorization and fresh
  in-app confirmation. This document is operating policy, not an individual consent record.

The product covers repetitions, holds and transitions across calisthenics. Pull-ups are the first
movement experiment, not a constraint on shared pose, display, timing or geometry components.

Build an exploratory RAM-only viewer alongside the formal study harness. Reuse CameraX ownership,
admitted preprocessing and MediaPipe primitives. Do not route it through empty-scene study admission,
construct study manifests/exports, or alter historical blocked evidence. Allow synthetic fixture
input and attested non-participant camera scenes. Separately authorized self-tests may process
one consenting adult operator transiently on-device, without bystanders, recording, screenshots,
upload, retention or agent inspection of human frames. The operator starts/stops the camera locally;
fresh scope confirmation is required after stop/background/error. This is not a formal study.

The original paired-image mode remains for synthetic debugging. The owner-approved smooth-viewer
increment uses a native CameraX Preview alongside ImageAnalysis, bound to the same ViewPort.
The live overlay is the latest available pose, not an exact match to the displayed video frame.
Map its own upright full-buffer transform into the current preview transform after reversing model
padding with preprocessing's dimensions/rounding. Missing transforms suppress the overlay. Bound
result age from capture (initial live display timeout 250 ms), expose timing, and never queue old
frames to simulate smoothness. This UI setting is not a validated pose-quality threshold.

The exploratory viewer selects the front-facing camera for operator-visible self-tests. It keeps
the source image unmirrored and pairs the overlay to it. Formal study callers retain exact camera
ID 0; rear-camera device evidence does not validate the front camera. The same REALTIME timestamp
and observed YUV DataSpace admission checks remain mandatory; unavailable front input fails closed.

Use a small controller independent of storage, telemetry recording and study publication. The UI
shows observations (pose present, missing, stale), not reliable/unreliable medical or form claims.
Stop/background/error clears displayed image/landmarks and releases the camera and model.

The central handoff's exercise-general product scope and privacy principles remain authoritative.
ADR-0001's stable interpretation releases remain deferred. A later experimental analyzer may follow
usable movement evidence, but it is not part of this viewer increment. ADR-0005's formal study and
participant gates remain intact. This software policy does not itself grant an individual human
capture authorization, upload, retention, merge or tag. Individual authorization stays outside Git.

Direct Codex work and bounded Claude consultation replace Buzz as a prerequisite for this track.
Preserve the existing branch and worktree; new viewer work branches from a7f2015 and inherits its
unmerged adapter history. Integration must account for those dependencies rather than claiming a
small independent main-based change.
