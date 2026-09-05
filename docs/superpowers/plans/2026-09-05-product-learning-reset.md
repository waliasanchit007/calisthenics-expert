# Calisthenics product-learning direction

Date: 2026-09-05

Status: approved for viewer implementation on 2026-09-05, scoped by ADR-0006. Human capture and
integration retain separate authority.

## Objective

Build movement intelligence for repetitions, holds and transitions across calisthenics. Make the
S25 show useful movement observations as early as possible; pull-ups are the first experiment.
First demonstrate
the pose signal, then an experimental movement cycle, then evaluate whether either is useful and
repeatable. The longer-term goal remains on-device movement intelligence, including progression
and coaching supported by validated measurements.

The first implementation unit is a visible skeleton viewer. Completion means an installable screen
with correctly aligned, current pose observations, not another schema or instrumentation command.

## Verified starting point

- Existing Calisthenics worktree: `codex/m0-5b-task-4-synchronized-video` at
  `a7f201560955ca77567fba7a9e7e117031452abe`.
- Camera acquisition, canonical preprocessing, MediaPipe inference, replay and durable export exist.
- `HarnessApp.kt` currently displays controls and status text; it has no camera preview or skeleton.
- `InferencePort` returns `InferenceResult.Success` containing normalized/world landmarks without
  requiring a storage dependency. `PoseLandmarkerLiveInferencePort` owns its asynchronous image slot.
- `LiveCameraComposition` couples live capture to session writing, attribution and publication.
- `CameraXFrameSource` currently binds an ImageAnalysis use case without a Preview use case.
- The existing study composition declares `EMPTY_SCENE`; its admission logic rejects detected
  poses. Reusing that path for people would conflict with its intended semantics.
- Attribution v2 is immutably blocked on missing observed AE target FPS. This does not establish
  pose-tracking failure on a pull-up. The admitted observation and original study gates stay intact.

## Sequence and visible acceptance

### 0. Set the experimental scope once

- [x] Prepare one concise ADR/AGENTS amendment defining an exploratory Android viewer alongside
  the formal study track. It must explicitly permit diagnostic visualization and, subsequently,
  provisional movement interpretation without releasing stable interpretation contracts.
- [x] Preserve the current branch and evidence. Select a feature branch from a verified code base
  containing the reusable adapters; record inherited versus new changes. Do not merge the large
  historical branch merely to obtain a new demo, and do not claim it is independently mergeable
  from main without checking dependencies.
- [x] Keep one work plan and a short ignored progress record. Park Buzz development and make direct
  Codex/Claude work the default. No repository relocation or branch cleanup is required.

This is a planning/setup step attached to the viewer deliverable, not another platform milestone.

### 1. See the signal — Android skeleton viewer

Deliver one screen with rear-camera preview, aligned skeleton, required-joint availability,
pose-present/no-pose/stale status, and Start/Stop. Share UI/state and geometry through KMP; keep
CameraX preview integration in Android. Retain the current S25 and admitted preprocessing scope.
These are display observations, not released GOOD/DEGRADED tracking-quality classifications.

- [x] Reuse camera ownership, YUV conversion, scale/pad and MediaPipe inference primitives in a
  small preview controller. Do not run the durable session/export controller with a pretend writer.
- [x] Pair the first preview image with its inference result (no extra camera stream). Map model-normalized
  coordinates through inverse preprocessing, crop, rotation, mirror and displayed-preview transforms.
  Preserve transform metadata instead of reconstructing it from guesses. Preview updates at inference
  throughput. A later independent Preview stream requires its own crop/viewport and timing binding.
- [x] Keep a bounded latest-result state. Associate pose and frame timing; clear stale overlays,
  serialize single-flight inference and close resources on stop/background/error.
- [x] Keep images/landmarks transient. No pose/video file, study session, export, analytics or
  landmark log. Inspect the built manifest and reachable dependencies for unintended data paths.
- [x] Exercise geometry with synthetic corner/joint vectors and runtime with existing synthetic
  inputs before human use. The packaged synthetic input produced 33 landmarks and a visible
  overlay on physical SM-S938B; Stop/background cleared observations without new observation files.
- [ ] Validate the actual rear-camera path with a freshly confirmed non-participant scene. An
  empty scene must show no pose. This remains distinct from the packaged synthetic demo.

Acceptance: installable S25 screen, visible detection on the existing detection-bearing synthetic
input, correct overlay mapping, honest pose loss, working stop/restart, and no observation files.
Synthetic success is only software/device evidence; it says nothing about pull-up reliability.

Implementation checkpoint (2026-09-05): the new launcher is `MovementViewerActivity`, using shared
Compose `MovementViewerScreen` and `ViewerGeometry`. The previous study activity remains present
but is not the launcher. Inputs are paired upright frames at inference throughput, with a 1.5-second
display-staleness timeout and 5-second callback timeout (UI recovery settings, not quality thresholds).
Failure revokes camera attestation; stop/background closes transient state. Dependency-inherited
network permissions are explicitly removed from the merged manifest. Software verification and
the synthetic S25 demo do not authorize the next human-input experiment or release integration.

No 25-minute run, exact 30 FPS proof, new contract release or functional iOS adapter is a prerequisite.
Use USB for ordinary development; preserve Wi-Fi/unplugged requirements for formal power studies.
Report observed frame rate and latency without claiming a validated performance envelope.

### 2. Observe a real pull-up — explicitly authorized short self-test

- [x] Implement and verify fresh in-app confirmation for a separately authorized self-test: one
  consenting adult operator, no bystanders, on-device transient processing, no recording/upload/
  retention, user-controlled stop. Individual authorization/consent is kept outside tracked files.
  This is human input and must never be labelled synthetic or admitted as a formal study.
- [ ] Use one practical camera placement and a short set. Show diagnostic elbow-angle traces and
  landmark availability, without calling image-plane angles anatomical ground truth.
- [ ] Observe bottom/ascent/top/descent, wrist occlusion near the bar and tracking loss. Record only
  the separately agreed qualitative outcome; retaining screenshots, video or derived body data
  requires an explicit retention decision.
- [ ] End with one concrete conclusion: useful motion signal in this setup; placement needs change;
  or tracking is inadequate. Change one variable per follow-up experiment and document limitations.

Acceptance: the owner can see their motion represented; we can identify usable joints and concrete
failure modes. No claim of correct form, reliable rep counting, broad usability or generalization.

### 3. Demonstrate a provisional pull-up cycle

This follows usable motion evidence. The user selects pull-up; automatic exercise recognition,
form grading and canonicalization across arbitrary camera angles are outside this unit.

- [ ] Build the smallest common-Kotlin phase/cycle analyzer supported by the observations. Define
  experimental parameter provenance and units; do not publish arbitrary constants as validated
  thresholds or release a stable RepEvent contract.
- [ ] Show candidate cycle count and phase with uncertain/unavailable states. Pose loss must not
  bridge an unseen movement into a confidently completed cycle.
- [ ] Test ordered synthetic trajectories, jitter at a boundary, partial cycles, stop/restart,
  missing joints and loss/reacquisition. Compare actual event sequence as well as total count.
- [ ] Demonstrate a short authorized set, a partial cycle, stationary pose and leaving the frame.
  Keep independent examples out of tuning once retention of real examples is explicitly authorized.

Acceptance: visible provisional cycles in the declared setup and honest failures on the tested
counterexamples. This is a technical demonstration, not a strict-rep verifier or accuracy benchmark.

### 4. Validate the useful experience

Before stabilizing analyzer interfaces, test a contrasting movement: a push-up for a different body
orientation or an L-sit for a hold. Shared types must not require an elbow threshold, upright body,
bar, rep count or exercise-specific phase. Do not generalize pull-up results to other movements.

Only after the above demonstration, prioritize what its failures reveal: placement guidance,
selected temporal filtering, cadence choice, or another pose configuration. Establish a small
separately authorized held-out evaluation before extensive tuning. Define acceptance targets from
observed evidence and product needs; disclose limited coverage.

Long-session thermals, formal privacy/retention and study tooling, additional athletes/devices,
functional iOS, broader movement types and coaching follow the claims/features that need them.
The formal attribution blocker remains unresolved until corrected and tested under a new protocol
and candidate; an exploratory demonstration cannot clear it.

## Engineering approach and initial code map

Extend UI/state under `apps/diagnostic-harness/composeApp/src/commonMain/.../ui/`.
Place the Android preview host/controller under `androidApp/src/main/.../preview/` and reuse
`camera/CameraXFrameSource.kt` with a narrowly tested preview binding change if feasible.
Use `shared/.../preprocessing/` and `shared/src/androidMain/.../mediapipe/` unchanged where possible.
Keep display geometry in common Kotlin. Do not redesign the existing study controller to serve
the demo. Exact interfaces belong in the first implementation brief after the scope amendment.

## Workflow and progress rules

- One lead Codex session may implement directly. Use a subagent only for bounded work that improves
  throughput; concurrent writers require disjoint scopes/worktrees.
- Claude-work is the first consulting account; Claude-personal is the authorized fallback when
  work is unavailable. Consult for consequential design questions, not every implementation commit.
- One bounded challenge of this new data/lifecycle boundary; no routine two-model review loop.
- Behavioral regressions for geometry, stale results, ownership/lifecycle and data-path boundaries.
  Focused tests while iterating; full required checks at integration. Reuse valid unchanged results.
- Maintain iOS compilation when shared interfaces change; do not demand functional iOS evidence for
  the Android demonstration. Build/install checks and actual device behavior remain distinct.
- Show a runnable intermediate every implementation session when feasible. If a full working session
  produces only prerequisite work, explicitly reassess scope before expanding the plan.
- Report: what works visibly, what failed on device, what was learned about movement, next smallest
  result, and any decision needed from the owner. Counts of tests/commits are supporting evidence.
- Pause optional hardening if it does not protect the current experiment or unblock its next result.
- Git commits preserve small increments. Push/PR/merge/tag follow the owner's existing authority;
  this planning request does not grant new publication authority. Paid credits remain prohibited.

## Human decisions retained

The owner accepts the experimental operating-gate change and separately authorizes actual human
capture. Retention/upload, recruiting other people, product claims and releases remain explicit
decisions. Routine geometry, UI structure, test selection and task routing belong to the lead agent.

## Direct Claude-work challenge

A read-only direct `claude-work` CLI consultation completed successfully during planning; Buzz was
not used. It independently found the absent Preview binding, empty-scene admission, memory-resident
landmarks, and discarded preprocessing transform. Its consequential points are incorporated above.

Controller judgment on alternatives: a sibling factory with an in-memory finalizer is technically
possible through existing dependency injection, but it still requires a deliberate boundary around
study manifests, admission and telemetry. Prefer the small preview controller consuming reusable
primitives; revisit only if inspecting the concrete implementation shows substantially less coupling
with a proven no-storage factory. Do not turn this into a generalized framework.

The consultation proposed a screen playing pull-ups and a 15-FPS/30-second acceptance target.
Neither is adopted: real-person footage needs authorization/provenance, and those numeric targets
have no established basis here. Use the existing detection-bearing synthetic input and report
observed behavior. The subsequent human self-test stays explicitly authorized.

## Next action

Prepare the concise experimental operating-gate amendment and the viewer's implementation brief.
Then build Milestone 1. Do not resume attribution v3 by default, and do not begin human capture
until its narrow boundary has been explicitly accepted.
