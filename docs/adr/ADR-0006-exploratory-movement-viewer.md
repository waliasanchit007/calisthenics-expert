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
one consenting adult operator transiently on-device, without bystanders, app-managed recording,
upload, retention or agent inspection of human frames. The operator starts/stops the camera locally;
fresh scope confirmation is required after stop/background/error. This is not a formal study.

The owner-approved screen-capture policy supersedes the original screenshot prohibition: do not
enable Android `FLAG_SECURE` anywhere in the app, including launch, live camera, stop and error
states. User-controlled system screenshots and screen recordings are allowed and can retain camera
images outside the app; the in-app notice states this limitation. This does not introduce app-owned
recording/upload or authorize agent capture/retention of human frames or a formal study release.

The original paired-image mode remains for synthetic debugging. The owner-approved smooth-viewer
increment uses a native CameraX Preview alongside ImageAnalysis, bound to the same ViewPort.
The live overlay is the latest available pose, not an exact match to the displayed video frame.
Map its own upright full-buffer transform into the current preview transform after reversing model
padding with preprocessing's dimensions/rounding. Missing transforms suppress the overlay. Bound
result age from capture, expose timing, and never queue old frames to simulate smoothness.
The raw live observation retains its 250 ms freshness cutoff. Following operator-reported flicker,
the display alone bridges short misses: hold the last detected pose at full opacity through 350 ms
from its capture, then fade to nothing by 500 ms. Misses and repeated reads never renew that deadline.
New detected results replace it immediately; no interpolation or anatomical prediction is added.
The UI labels held poses as a tracking gap; raw missing/stale observations remain missing/stale and
must not be replaced by the held display pose for analysis. Stop/background/error clears both states.
These are tunable UI limits, not validated pose-quality thresholds. Synthetic paired mode is unchanged.

The exploratory viewer selects the front-facing camera for operator-visible self-tests. It keeps
the source image unmirrored and pairs the overlay to it. Formal study callers retain exact camera
ID 0; rear-camera device evidence does not validate the front camera. The same REALTIME timestamp
and observed YUV DataSpace admission checks remain mandatory; unavailable front input fails closed.

Use a small controller independent of storage, telemetry recording and study publication. The UI
shows observations (pose present, missing, stale), not reliable/unreliable medical or form claims.
Stop/background/error clears displayed image/landmarks and releases the camera and model.

The central handoff's exercise-general product scope and privacy principles remain authoritative.
ADR-0001's stable interpretation releases remain deferred. The owner-approved follow-on adds live
projected arm measurements and a manually selected, per-session calibrated pull-up cycle experiment.
Its purpose is visible movement learning, not an assertion that the formal study has passed.
Calibration requires a practice motion and explicit operator confirmation; frozen observed angle
endpoints are not definitions of correct technique. Count only candidate calibrated motion cycles,
never verified reps. Raw fresh observations alone feed the analyzer; missing joints and confirmed
tracking gaps invalidate unfinished cycles. Stale display measurements clear immediately, but UI
expiry alone is not a confirmed capture gap while a fresh result can still arrive within the
existing capture-gap and freshness limits. No timer or held display pose advances the analyzer.
State remains transient and clears on stop.
The concrete experimental parameters and limits are in the movement-measurements-and-cycles plan.
ADR-0005's formal study and
participant gates remain intact. This software policy does not itself grant an individual human
capture authorization, upload, retention, merge or tag. Individual authorization stays outside Git.

Direct Codex work and bounded Claude consultation replace Buzz as a prerequisite for this track.
Preserve the existing branch and worktree; new viewer work branches from a7f2015 and inherits its
unmerged adapter history. Integration must account for those dependencies rather than claiming a
small independent main-based change.
