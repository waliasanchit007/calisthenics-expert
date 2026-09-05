# ADR-0006: Exercise-independent exploratory movement viewer

- Status: Accepted for software implementation by the owner's instruction to proceed, 2026-09-05.
- Scope: Android S25 viewer; actual human input still requires separate explicit authorization.

The product covers repetitions, holds and transitions across calisthenics. Pull-ups are the first
movement experiment, not a constraint on shared pose, display, timing or geometry components.

Build an exploratory RAM-only viewer alongside the formal study harness. Reuse CameraX ownership,
admitted preprocessing and MediaPipe primitives. Do not route it through empty-scene study admission,
construct study manifests/exports, or alter historical blocked evidence. Initially allow only
synthetic fixture input and explicitly attested non-participant camera scenes.

The first preview displays the upright camera image paired with the corresponding inference result,
at inference throughput. There is no second CameraX Preview stream in this increment. This bounds
buffering and avoids displaying old landmarks on a newer frame. Geometry must reverse model padding
using the same dimensions/rounding as preprocessing. Stale results are cleared using an explicitly
labelled UI freshness timeout, not a validated pose-quality threshold.

Use a small controller independent of storage, telemetry recording and study publication. The UI
shows observations (pose present, missing, stale), not reliable/unreliable medical or form claims.
Stop/background/error clears displayed image/landmarks and releases the camera and model.

The central handoff's exercise-general product scope and privacy principles remain authoritative.
ADR-0001's stable interpretation releases remain deferred. A later experimental analyzer may follow
usable movement evidence, but it is not part of this viewer increment. ADR-0005's formal study and
participant gates remain intact. No human capture, upload, retention, merge or tag is authorized by
this software scope amendment.

Direct Codex work and bounded Claude consultation replace Buzz as a prerequisite for this track.
Preserve the existing branch and worktree; new viewer work branches from a7f2015 and inherits its
unmerged adapter history. Integration must account for those dependencies rather than claiming a
small independent main-based change.
