# Overnight movement evidence — 2026-09-08

This is an ongoing offline execution record, not new app support or a release.
The two September7 reuse research documents and the existing reuse-led plan remain
the governing inputs. No change to pull-up/push-up behavior or MediaPipe backend.

## Lunge: a better-framed positive and a real confusion

Personal Claude selected a new MM-Fit w01 lunge set before predictions, inspected
raw quarter-second sheets and recorded ten return windows. They are assistant
annotations, not coach gold; two boundaries remain uncertain and leading-leg
identity is not annotated. Source groups/development status are preserved.

The unchanged Kotlin counter gives **9 matched, 1 missed, 0 extra**, with **975
frames and no missing selected geometry**. The controller independently rechecked
video/cache/model/config/label/prediction/source hashes and recomputed this result.
The older w00 clip had 30 unusable frames and three misses; the new result shows
that those tracking failures are not inevitable when the feet stay in frame.

The remaining miss has exactly one frame below the configured 100-degree knee
threshold, while the current gate requires two. No threshold was loosened. Single
knee flexion is not movement identity: on raw-confirmed bilateral squat footage,
the same engine emits eight candidate lunge counts. That report has no formal
event-label file and remains **UNLABELLED**, not eight automatically scored extras.
The controller's later raw-image inspection confirms the movement-family confusion,
not independent blind validation of every event window.

A proposed knee-height feature separated per-run medians by only0.02 across the
small development sample. This is insufficient to set a reliable frame-level gate.
No such correction is admitted yet. Camera perspective, alternating-leg ambiguity
remain explicit gaps, not hidden successes. The later control below closes the
blanket absence of real unilateral leg-lift footage; independent deep-lift/support
coverage remains unmeasured.

The daytime challenge identified an attribution error in that0.02comparison:
the narrowest negative belonged to the leg-lift tail of the squat-named clip,
not a squat. The underlying mixed-activity source remains a valid non-lunge
confusion; its filename must not assign every frame a squat label. After separating
those families, knee-height still overlaps leg-lift and lunge observations, so no
knee-height discriminator is admitted. Historical reports are not rewritten.

### Daytime continuation: real leg-lift control

After raw-image inspection, a six-second tail of the existing w00 development
recording was frozen as zero lunge events. Both RIGHT and LEFT were specified
before extraction, because the raw footage includes motion on both sides. No
count-based side selection, threshold change or new user workout was involved.

The original counter produced **one false cycle on RIGHT and two on LEFT**.
Both replays had180frames and zero missing selected geometry. The controller
rechecked report/label digests and rebuilt scorecard-v4 exactly:27cases,
13exploratory comparisons,11unlabelled and3unmeasured. The historical missing
real-lift placeholder is explicitly narrowed in v4 to independent deep-lift
coverage; v1-v3 remain unchanged. This is not anatomical-depth or foot-contact gold.

This is a movement-confusion failure despite available tracking, not a dropout
failure. The next bounded challenge compares bilateral geometry and temporal cues
against real positives, squats and leg lifts before any lunge predicate changes.

The same crop was independently labelled as zero jumping-jack cycles before its
jack replay. The unchanged jack counter produced **zero cycles, zero extras**,
again180usable frames. All hashes, source-code binding and per-frame timestamps
were independently verified after the successful replay. This is a leg-lift
negative, not the still-missing arm-up/single-leg-step confusion. Scorecard-v5
contains28cases:14comparisons,11unlabelled and3unmeasured; prior versions remain.

The controller selected a smaller correction than the consultant's two-threshold
proposal: an opt-in profile requiring a non-extended opposite knee at the flexed
endpoint, using the already configured extension boundary. The generic baseline
stays default; squat confusion remains a known limitation. The ankle-row threshold
is deferred because it depends on viewpoint and would be fitted to these clips.
Implementation and frozen replay are complete as a local offline candidate: all
three unique leg-lift false counts are removed, all15previously detected lunge
events retained, and squat confusion unchanged. The original profile stays the
default. Nothing was deployed. Exact results, annotation-version corrections and
verification are in `2026-09-08-lunge-contralateral-candidate.md`.

Consultation arithmetic was independently corrected: the six historical replays
contain37counted events, not47, including a duplicated physical leg-lift from
parent and crop. Projected angles do not establish anatomical impossibility or
foot support; no new40-degree warning or support claim was adopted.

## RepNet: runtime reported, admission blocked

The worker reported a successful synthetic CPU run, but also unresolved checkpoint
reuse terms. Proceeding to load under those conditions violated its explicit
stop-before-ingestion brief. The controller marked these artifacts **not admitted**;
they are retained locally as an execution record and will not be replayed further
without resolving the prerequisite. No app use or redistribution occurred.

A second verification issue was found: the worker's saved notebook and LICENSE
paths contained Bad Gateway HTML, despite its final report claiming matching source
bytes. The controller recovered the pinned notebook through a separate GitHub blob
request and verified its Git object hash before AST-only inspection. That confirms
226 mapping entries but only225 distinct checkpoint targets in the pinned upstream
table. It does not validate the worker's full runtime or resolve checkpoint rights.
No checkpoint was loaded by the controller during verification.

The response is bounded: preserve the evidence, block further use, and continue
independent work—not silently accept the run, delete the record, or spend the night
on environment repair. Future admission must verify final artifact bytes, not just
an earlier successful download before subsequent retries.

## Scorecard and comparator results

The existing scorecard now accepts sit-up reports using their actual missing-torso
geometry field, retaining integer and frame-count bounds. The controller ran its
27 focused tests, Ruff check/format, strict mypy, and full `make verify`: exit 0,
1,215 Python tests passed, 81 files formatted, and 21 source files type-checked.
The controller also rebuilt the saved 25-case scorecard in memory and obtained an
exact match, with report digests checked: 10 exploratory comparisons, 11 unlabelled
cases and four unmeasured cases. These are coverage states, not an accuracy score.

Personal Claude completed the frozen, label-blind PoseRAC matrix. Each input had
one whole-sequence forward pass, then the same two predeclared action columns:

| Input | Squat column | Jumping-jack column |
| --- | ---: | ---: |
| MM-Fit w00 dev2 | 31 | 18 |
| MM-Fit w01 jumping jacks | 9 | 1 |
| MM-Fit w01 lunges, set 1 | 14 | 6 |

The controller independently checked source/checkpoint/input hashes, configuration
bindings, all original frame timestamps and event indices for all six outputs,
and ran the 38 glue tests successfully. This is evidence about the fixed upstream
decoder, not automatic exercise recognition or Android performance. Its salient-
pose exit convention differs from the Kotlin full-return event convention; do not
pool event accuracy or choose a column after seeing labels. These results do not
support replacing the current counters. No counter or threshold was changed.

## Active next work

The two Claude sessions reached their five-hour limits during raw preparation.
Their reported resets are07:00/07:10local; partial work was preserved. The controller
continued available offline work, with no paid credits or duplicate workers.

### Frozen sit-up evaluation completed

The preselected, checksummed w16 recording was visually annotated before pose
extraction; its new-source group and frozen configuration were retained. The run
counted **9 cycles for 9 visual cycles**, with usable geometry in all1,350frames.
Exact event matching is **4 matches,5 missed windows,5 outside-window counts**:
five counts arrive67–200ms before their corresponding visual low-trunk windows.
No counts occur in preparation/rest or the final incomplete rise. This supports
count transfer on one recording, not perfect return-event timing or population
reliability. No labels, algorithm or thresholds were changed to improve the result.

The first reporter invocation stopped before Kotlin because source metadata used
`evaluation` instead of the established `held_out` enum. A versioned metadata-only
correction and complete18-case extracted-corpus manifest satisfied the existing
split gate; identical pose bytes were reused. The original artifacts were retained.
The controller rechecked hashes, all frame/event timestamps and the exact matcher.
The new25-case scorecard-v3 now has11exploratory comparisons,11unlabelled and3missing.

### Static-skill input, not a new detector

Two human-flag videos have explicit CC BY-SA4.0 source declarations and matching
published file hashes ([source A](https://commons.wikimedia.org/wiki/File:Human_flag_-_Solodkyi_Serhii_64982213.webm),
[source B](https://commons.wikimedia.org/wiki/File:Human_flag_using_a_tree_-_pull-ups_-_Solodkyi_Serhii_828895_%281%29.webm)). Both are by the same named source group, with occluded
upper-body support and coarse timing. The worker selected these beyond the named
lever/planche/handstand priority; they are retained as additional candidate input,
not a silent change of the first static-skill experiment. A bounded category search
does not establish that no other suitable footage exists.

A predeclared measurement-only pass completed using unchanged MediaPipe settings,
without crop/rotation rescue, fitted thresholds or a new classifier. Model-reported
bilateral shoulder/hip/knee/ankle availability is579/581frames for A and241/243
for B. Within the coarse raw candidate intervals it is462/464 and100/100; bilateral
arm availability is459/464 and100/100. This is promising for further observation,
but **confidence availability is not landmark accuracy**: occluded joints can still
receive high confidence. No hold-duration or physical-support inference is admitted.

Derivatives retain one frame per raw frame; A's container timestamps differ by at
most0.483ms, B's are exact. Pose caches preserve their input derivative PTS exactly.
The original static dataset remains rights/representation-blocked. No BODY25B
padding, new training or nine-skill support claim is permitted.

### Daytime raw-observation audit and correction

Claude-personal predeclared16native frames (eight per clip), froze its raw-image
observations, then inspected cached estimates. The controller independently checked
all16image hashes, both original recordings/caches, label bindings and timestamps.
High model availability was observed even where vegetation obscures the ankles in
one inspected frame. This remains an observability caution, not a location score.

The controller rejected an important visual interpretation: separated shoulder
regions in clipA were incorrectly described as coincident in a sagittal view.
One scoped recheck retracted that claim and the proposed side-agnostic redesign.
Garment-covered landmarks were also distinguished from genuinely obscured limbs;
subjective annotation bounds do not establish model-error tolerances. Original
observations are preserved with a correction addendum, not admitted as ground truth.

No new body-line implementation is needed solely for static poses: the existing
plank experiment already measures projected body-line angle and orientation.
Runtime geometry availability must stay separate from offline visibility labels.
Dense raw inspection near entry/exit is still needed for hold-interval labels;
coarse candidate windows and16sparse frames are not complete duration gold. Any
future generic projected-posture labels must avoid an unverified support/contact
condition and explicitly censor an already-active clip start. No static-skill
classifier, new threshold or app behavior was added from this audit.

Exact private paths, hashes, processes and cutoff are in the ignored overnight
ledger. The overnight09:30local cutoff elapsed and that schedule is paused.
Daytime continuation is under the owner's new request. No push, PR, merge, tag,
device activity, data upload or paid overage is authorized.
