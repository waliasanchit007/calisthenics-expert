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
upload, retention or agent inspection of human frames, except for the subsequently approved
automatic creator feedback amendment below. The operator starts/stops the camera locally;
fresh scope confirmation is required after stop/background/error. This is not a formal study.

### Opt-in local debugging exception

#### Automatic creator feedback amendment (2026-09-06)

The owner authorized implementing automatic local collection for the adult-operated self-test,
including images, without a separate debug-attempt or manual export step. This supersedes the
numeric-only marker requirement below **for the debug-build camera viewer only**. Fresh in-app
self-test attestation and operator camera start remain required; the pre-start notice explicitly
states that model-input images and movement observations are retained. Release builds retain the
RAM-only default. Formal study/participant gates are unchanged.

During camera use retain up to three minutes / 64 MiB of exact analyzer inputs, controls and
decisions, raw normalized model landmarks and lossy JPEGs of the corresponding 256x256 model RGB
input. Session UUID, monotonic capture timestamp and input RGB digest bind the streams; JPEG has its
own digest and is visual context, not a lossless oracle. Do not equate missing images with missing
pose input. This is a sampled image sequence, not full-rate video, audio or camera-roll capture.
Formatting/compression/I/O use bounded workers. Report queue loss, sequence gaps, limits and terminal
reason explicitly; an absent terminal artifact means unfinalized, not complete. No held overlay
feeds evidence or analysis. No rule/threshold changes are part of this amendment.

Files stay in private no-backup app storage, distinct from study exports. Refuse new capture when
the retained store cannot reserve a session within 256 MiB; never silently delete old sessions.
Stop/background/disable ends collection and drains accepted writer work. A visible status names
capacity/error conditions. Provide explicit local-record deletion, explaining that already copied
Mac files remain. The host read-only ADB collector validates paths, hashes, complete rows and
counters, stores files outside Git with private permissions, and produces local attempt reports.
No automatic cloud upload or media in Git is needed or added, even where owner sharing is permitted.
Individual authorization and personal evidence remain separate from source/policy history.

The historical marker/logcat mode below remains available for numeric-only transport testing.

The owner approved a direct diagnostic feedback loop for separately authorized adult self-tests.
Only debug builds may enable it, through a fresh marker in the app's private no-backup directory
created by an authorized ADB operator. An exported intent extra is not sufficient. Authorization
expires after 30 minutes and is not automatically renewed; a visible status and disable button are
required. The mode does not start the camera or waive the existing fresh self-test confirmation.

The allowlisted trace contains relative timestamps, build/session identifiers, frame counters,
input-rejection and calibration-failure enums, phases/counts, and quantized image-plane arm angles,
wrist heights and confidence. These are derived movement observations, not anonymous performance
metrics. No images, landmark coordinate arrays, audio, video, identities or arbitrary exception
messages are logged. Routine rows are limited to 10 Hz, with control/state transitions retained;
formatting and logging run on a bounded background writer, whose drop counter is distinct from
pipeline skips. The observer must not change recognition rules.

Rows use a dedicated Android logcat tag and its bounded system buffer, not study exports or an
app-owned recording file. Authorized ADB collection may retain a bounded host trace outside every
Git worktree for debugging; it is never committed or uploaded. Turning off the mode stops new rows,
but previously emitted rows may remain in the system buffer or host trace. Logging has gaps on
disconnect, buffer overwrite, process death or writer overflow; session/sequence/drop fields must
be checked before treating an interval as complete. Do not clear the entire device log to erase
this tag. Individual authorization remains outside Git; formal study gates remain unchanged.

Current developer procedure: the private marker is `no_backup/movement-diagnostics.enabled`;
the tag is `CalisthenicsTrace`. Enable/read using debug `adb shell run-as` and `adb logcat`, never
an app-network permission or exported enable intent. Collector limits are 32 MiB and one hour;
the app's shorter 30-minute authorization still governs emission. Review retained host logs only
for the explicitly authorized debugging purpose and remove them when that purpose ends.

The owner-approved screen-capture policy supersedes the original screenshot prohibition: do not
enable Android `FLAG_SECURE` anywhere in the app, including launch, live camera, stop and error
states. User-controlled system screenshots and screen recordings are allowed and can retain camera
images outside the app; the in-app notice states this limitation. This screen policy alone does not introduce app-owned
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

### Brief-top counting experiment (2026-09-06)

The owner approved replay-driven correction of missed candidate cycles. During confirmed counting
only, retain the hold-based path and additionally admit a supported brief top reversal: two
consecutive decreasing observations in the calibrated middle band, two consecutive observations in
the top band, then two consecutive increasing observations in the middle band. A return to the
extended band with the existing120ms/multiple-sample dwell is still required before incrementing.
The sequence uses actual fresh sample timestamps, never interpolated poses or invented dwell time.
All existing confidence, wrist, capture-gap and stale-input gates remain in force; invalid input
clears pending reversal evidence. Single-frame spikes, unsupported jumps and partial-band excursions
are not sufficient. Calibration/confirmation behavior is unchanged in this increment.
Expose the last candidate outcome (counted, top not confirmed, or tracking interrupted) in the
existing status line and as a typed diagnostic field. Label it as the last attempt, not a verdict
on the current motion. Reset clears it; do not log arbitrary UI text or call it a form assessment.

This is a prototype hypothesis addressing fixed-dwell sensitivity to sampling, not a definition of
valid technique or a validated general-purpose counter. Compare exact private traces using the real
Kotlin analyzer, retain negative synthetic regressions, and report event timing as well as totals.
Personal recordings remain outside Git; an improved result on a motivating trace is not held-out
benchmark evidence. The subsequent guided-UI experiment remains separate.

### Bounded calibration landmark recovery (2026-09-06, superseded below)

The owner approved fixing calibration aborts caused by brief unusable landmark observations.
This supersedes immediate calibration failure for rejected arm geometry/confidence only. A
fresh, ordered frame with unusable selected-arm measurements first checks whether the previously
accepted practice prefix already satisfies the existing full-cycle/spread/endpoint checks. If so,
freeze that prefix's range and show READY for explicit operator confirmation, without including
the rejected observation or counting any rep. This reuses the same completion check as the deadline;
it does not introduce a second interpretation of a completed practice. Otherwise clear all
accumulated practice samples and the median window, expose an UNCERTAIN/reacquiring message, and
leave the original 10-second calibration deadline unchanged. Do not interpolate rejected input.
Three new usable samples rebuild the existing median window before showing tracking recovered.
A complete, uninterrupted hold-based practice cycle must then be observed and confirmed as before.
An incomplete prefix is never joined to recovered samples. Recovery late in the window can still
require a retry. This adds completion on a landmark-rejection boundary only, not a new continuously
adaptive calibration, automatic side switching or a broader guided-UI redesign.

Recovery must occur strictly within the existing 250 ms capture-gap limit measured from the last
usable calibration capture, not the latest rejected frame. Duplicates never renew that budget.
Confirmed no-pose/timeout, stale samples, invalid/backwards timestamps and capture gaps retain
their blocking behavior. Sustained unusable landmarks fail on the next observation reaching that
limit. No timer or display-held pose can finish calibration. Stop/reset/new calibration clears
recovery state. Counting thresholds, side selection, model, privacy and formal study gates are
unchanged. Verify rejected-input recovery and no stitching across gaps with synthetic regressions
and exact private trace replay; do not relabel replay results as prospective movement accuracy.

### Separate setup reacquisition from counting continuity (2026-09-06)

The owner approved this correction after prospective self-test feedback showed that the previous
250 ms terminal calibration recovery budget still aborted otherwise usable setup. That cutoff
protects motion continuity, not the amount of time an operator may need to regain arm tracking.
This amendment supersedes the recovery deadline and fixed post-loss practice window above.

During calibration, rejected landmarks, no-pose/timeout, stale observations and capture gaps first
offer any already complete accepted practice prefix for explicit confirmation using the existing
completion validator. Otherwise discard the entire unfinished practice and median, set UNCERTAIN,
and clear the practice deadline. No missing motion is interpolated or joined across the gap.
Invalid/negative or backwards timestamps remain terminal errors, rather than resetting the clock.

Reacquisition requires at least three distinct fresh, usable, ordered selected-arm observations
spanning the existing 120 ms endpoint dwell, with every inter-capture gap below 250 ms. Rejection
or a gap resets warmup; duplicate frames cannot advance it. Warmup stores at most three angles and
one starting timestamp. Once stable tracking returns, announce a NEW full practice and start one
fresh 10-second window. Warmup motion is excluded from practice; the final warmup frame seeds the
new median. Subsequent good frames do not renew the deadline. Waiting itself has no timeout and
accumulates no practice; reset/stop remains available. A continuously tracked but incomplete practice
still fails the existing range/full-cycle checks at its deadline.

The UI exposes the paused state without a countdown and permits an explicit arm change; it never
changes sides automatically. The same camera/model/confidence/freshness rules and counting gap
policy remain intact. A valid practice still requires operator confirmation and counts zero reps.
Verify both setup recovery and no-stitching behavior, plus unchanged successful counting, using
synthetic tests and private exact Kotlin replay. Replay is development evidence, not a prospective
accuracy claim. This is a bounded calibration correction, not a formal study or contract release.

### Actionable framing and confirmation (2026-09-06)

The owner approved a framing/diagnostics correction after paired images showed hands cropped
above the image and the UI incorrectly reported missing elbows. Measurement now retains a typed
unavailable joint (shoulder/elbow/wrist) and reason (missing, low confidence, invalid position,
outside image). Numeric availability, geometry bounds, confidence limits and counting semantics
are unchanged: missing or off-image joints never become fabricated angles. The experiment and
local diagnostics consume that cause rather than treating every absent arm angle as a lost elbow.
Exact debug traces add optional joint/reason pairs; historical traces remain replayable without
inventing detail they never contained. These are exploratory diagnostics, not stable contract codes.

Setup shows current per-arm framing guidance before practice. The existing countdown cannot
begin practice until a fresh usable chosen-arm measurement exists, and now explains why it is
waiting rather than displaying an unexplained zero-second timer. Bar visibility and full-motion
head/hand framing remain an operator preview check; the app does not claim to detect a bar or
certify future frames. No extra confidence threshold, extrapolation, automatic arm switch or
additional confirmation gate is introduced. Automatic setup recovery remains as approved above.

Move active experiment controls directly beneath the preview and put technical signal detail
behind a disclosure. READY explicitly means practice accepted with counting OFF; a prominent
"Confirm full practice & start counting" action retains the required operator confirmation.
COUNTING is explicitly ON, with candidate count visible. Practice itself remains uncounted.
Verify cause propagation with regressions and saved paired frames, unchanged counting with exact
trace replay, and actual confirmation-button behavior with a synthetic no-camera UI test.

### Operator-first screen (2026-09-06)

Owner requested a UI overhaul: leave fast-changing measurements, joint counts, pipeline timing,
raw state messages and diagnostic implementation details in the existing local logs. The normal
screen contains the preview, a single short stage instruction, explicit practice confirmation,
an experimental count when counting, and an always-visible Stop action. Session options hold
synthetic input, recording controls and deletion. A short recording-state indicator remains visible;
do not disguise recording failure or remove the operator's ability to stop collection.

Move fresh self-test attestation and disclosure into a pre-start dialog, retaining camera permission,
explicit per-session consent, local-only collection, no bystanders/audio/uploads and deletion confirmation.
Transient framing/paused cues settle for 600ms in the presentation layer only. Actual measurement
admission, log timing, experiment state, counts and READY/confirmation are not delayed or changed.
This UI change does not resolve the separately diagnosed completed-practice finalization defect.
Tests exercise rendered controls, consent before start, hidden volatile telemetry, brief versus
persistent tracking loss and unchanged explicit confirmation. Use synthetic UI inspection, not
remote activation of the user's camera.

### Preserve the first completed practice (2026-09-06)

Owner approved fixing calibration that unnecessarily required another practice or lost an already
completed one after releasing the bar. Evaluate the existing median-filtered full-cycle acceptance
on each new accepted calibration sample; the ten-second window is an upper limit, not a mandatory
wait. At the first qualifying return to extended, freeze that prefix's observed range and offer
READY immediately. Later extra movement, tracking loss or releasing the bar cannot alter it.
The operator still explicitly confirms that practice was full; no automatic confirmation or rep
credit. Confidence, freshness, gap recovery, minimum spread, top/extended dwell and post-confirmation
counting rules are unchanged. An incomplete prefix neither fails early nor crosses a tracking gap.
Reset or choosing a new calibration clears the preserved result. This remains an exploratory
observed-range estimate, not a validated physiological calibration or strict-rep claim.

### Contrasting manual push-up experiment (2026-09-06)

The owner approved proceeding to product-learning-reset section4 after the pull-up variation
self-test. Add a manual push-up profile to the same provisional common arm-cycle mechanics,
not a new classifier, stable rep contract or form-assessment engine. Select the movement only
while the camera is stopped; clear practice/count when changing it. Push-up setup uses a side
view and a selected wrist below its shoulder before practice begins. During motion finite wrist
height may cross zero near the bottom; do not fabricate tracking loss from its sign. Missing,
nonfinite, low-confidence, stale and discontinuous observations retain the existing safety gates.
Pull-up rules and event timings remain unchanged. Per-session full-practice confirmation remains
required; a counted cycle is not proof of depth, floor support or correct body alignment.

Keep movement identity on every new debug row and exact replay. Missing historical identity is
pull-up only; explicit null, unknown and mixed identities are invalid. Preserve historical outcome
names for compatibility but use bottom/flexed-endpoint wording for push-up operator guidance.
No extra recording/upload privilege, automatic camera start, release or study admission is granted.
See the 2026-09-06 push-up design/plan for tests and the pending real-movement check.

ADR-0005's formal study and
participant gates remain intact. This software policy does not itself grant an individual human
capture authorization, upload, retention, merge or tag. Individual authorization stays outside Git.

Direct Codex work and bounded Claude consultation replace Buzz as a prerequisite for this track.
Preserve the existing branch and worktree; new viewer work branches from a7f2015 and inherits its
unmerged adapter history. Integration must account for those dependencies rather than claiming a
small independent main-based change.
