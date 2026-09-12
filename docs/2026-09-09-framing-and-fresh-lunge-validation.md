# Fixed framing and fresh lunge validation

Local development evidence, not an app release. Continues both September7
movement-reuse research documents and the September9 landmark-identity check.

## Decisions

- The existing opt-in lunge correction generalizes to one fresh side-view
  recording: all10 visible cycles retained, one post-set knee-lift false count
  removed. Keep it offline; this is not general lunge identity or form validation.
- Fixed tighter framing is a stronger handstand lead than always rotating the
  full image. It restored a pose on every inverted frame and corrected obvious
  arm/leg-class confusion at inspected failure points. It is not yet a validated
  crop-selection algorithm or a handstand feature.
- A decoder buffer compatibility bug was fixed with regression-first evidence.
  No counters, confidence settings, model, mobile behavior or device state changed.

## Fresh MM-Fit recording

The formerly blocked w02 download completed. Publisher MD5 and size agree:
`fc468d615d6924afc94650f92fe4dbca`,2,503,286,677bytes.
SHA256:`f2554340253fed8e7911bdc6034c33954d356efbf7918cebcdc9765eb1531a44`.
Source: MM-Fit RGB record7672767, CC-BY4.0, David Stromback/Sangxia Huang/Valentin
Radu. Source/license metadata are retained in the private admission record.

Two native-PTS excerpts preserve every decoded pixel against their parent:
1146frames at796.800–834.966666666s (lunges),906frames at78.800–108.966666666s
(squats). Stream-copy keyframe preroll0.2s remains; fresh MediaPipe VIDEO state
starts at each excerpt, not at the beginning of the45-minute workout.

Before inference, controller inspected raw0.5s sheets over797–835s and79–109s
and froze10 lunge return windows plus a zero-lunge squat control. These are
whole-cycle labels, not leading-leg identity, anatomical range or strict form
labels. The lunges carry dumbbells; squats are front-facing/bodyweight. New
recording is established, athlete-disjointness is not. Development split only.

| Input and fixed side | Baseline matched / missed / extra | Opt-in matched / missed / extra |
| --- | --- | --- |
| w02 lunges, LEFT | 10 / 0 / 1 | 10 / 0 / 0 |
| w02 lunges, RIGHT | 10 / 0 / 0 | 10 / 0 / 0 |
| w02 squats, LEFT (zero lunge labels) | 0 / 0 / 0 | 0 / 0 / 0 |
| w02 squats, RIGHT (zero lunge labels) | 0 / 0 / 0 | 0 / 0 / 0 |

All10 lunge event timestamps are identical between profiles for each fixed side.
Do not sum sides or choose a side retrospectively. All four predefined configs
ran on both excerpts; nothing was tuned after results.

The removed LEFT extra was at832.333333333s. Raw footage shows a knee lift after
the weights are put down. At831.900s the tracked knee is99.227degrees and the
opposite knee173.866degrees; the opt-in emits `CONTRALATERAL_EXTENDED`, resetting
that candidate. This tests the intended rejection rather than a lucky count match.

The squat control does **not** prove the new gate distinguishes squat identity:
even the baseline produces zero. Its projected knee minima are113.639degrees
LEFT and140.949degrees RIGHT, above the unchanged100degree flexed endpoint.
Earlier admitted squat-confusion examples still exist and are not superseded.

## Handstand fixed-factor experiment

Protocol frozen before candidate inference: crop x420,y0,width1080,height1080
from the1920x1080 original; full height, fixed for all1499frames. Original and
180degree cropped orientations run separately, retaining both earlier full-image
controls. No adaptive/person-tracked crop, manual frame selection, threshold
change or per-frame best-result selection.

Both FFV1 derivatives passed all1499 decoded RGB and rational-PTS comparisons
against the specified original slice/rotation before inference. Coordinates map
back to the original image before visual inspection; no left/right relabeling.

During the pre-labelled9–44.5s inverted interval (1065frames):

| Fixed input | Any pose | Both arms pass existing validity/confidence checks | All12 body joints pass |
| --- | ---: | ---: | ---: |
| Full original | 901 | 692 | 644 |
| Full180 | 974 | 766 | 738 |
| Crop original | 1065 | 877 | 805 |
| Crop180 | 1065 | 874 | 801 |

These are availability/completeness counts, **not accuracy scores**. All-frame
coverage uses frozen intervals; the21 diagnostic panels use the prior sample set,
including its three explicitly gap-enriched samples. Controller directly examined
six paired raw/overlay controls, not all21 or every video frame.

At12.236,15.470 and18.670s the original controls have clear arm/leg confusion or
missing poses. Both crops restore arms toward supporting hands and legs toward
elevated feet. At28.370s both crops avoid the original wrist-on-leg/ankle-on-hand
failure. At25.136s they retain the pose missing from the original full image.
At46.503s cropped poses remain broadly upright while full180 is mislocated.

Residual uncertainty matters: at12.236s the unrotated crop places wrists too
close together, and anatomical left/right assignments differ between variants.
No exact joint-location oracle, population accuracy, hold-duration acceptance,
Android performance or cross-device equivalence is established. Fixed framing
is now a supported hypothesis for another independent clip, not a shipping rule.

## Decoder fix and evidence handling

The1080wide decoder array had RGB strides(3264,3,1), not packed(3240,3,1).
Actual MediaPipe Image rejected it despite valid uint8 pixels. The extractor now
copies only non-contiguous arrays into C-contiguous storage. Pixel values,
dimensions, model settings and timestamps are unchanged.

The optional media-environment regression runs the real PyAV decoder and actual
MediaPipe Image constructor, replacing only costly pose inference. It failed at
the padded-image boundary before the fix, then passed for both1080 and1920wide
synthetic clips, checking the delivered RGB and PTS. Media dependencies remain
optional in the normal reference environment; a skip there is not the evidence
for this fix. The separate media test actually ran. Upstream protobuf deprecation
warnings remain; they are not assertion failures.

270of271 frozen source/test files remain byte-identical. Only the canonical
extractor changed (old hash c5f775b6..., new397f9ecc...); a new regression was
added. Model/counter/config freezes remain intact. Old admitted caches are
preserved with their original extractor provenance; failed crop-v1 directories
have no admission metadata and were not reused as successful caches.

Claude-work performed one bounded raw-only framing consultation. Its coarse
framing conclusion was usable; inaccurate numeric joint boxes and some anatomical
prose were not admitted as truth. Controller raw inspection governs the claims
above. No extra review loop or other Claude account was used.

## Artifacts and next action

Fresh verification: `make verify` exit0,1,215passed/1skipped in37.62s, Ruff
check/format clean82files, strict mypy clean21source files, repository and fixture
checks valid. The one skip is the optional media regression; it separately ran
and passed in0.848s with both width subcases (no media-test skip there). Explicit
extractor mypy also passed. Eight real Kotlin replay invocations exited0; all
report/config/cache/video/model/engine/JAR/event bindings rechecked independently
by the controller. No fresh full Kotlin test-suite or Android build is claimed.
Formal participant-study admission remains blocked as expected by the existing
gate; this batch used licensed source videos only.

Private root: `~/.buzz-multiagent/calisthenics-expert/offline-corpus/`.
Handstand records/panels: `handstand-framing-20260909/`.
Fresh recording, raw labels, caches and all8 Kotlin invocation reports:
`frozen-validation-next-20260908/`, especially `w02-frozen-lunge-results-v1/`.
The ignored `2026-09-08-frozen-validation-next` ledger carries exact resumption
state and test logs. Licensed media and derived personal observations remain
outside Git; no new owner workout is needed.

Next: retain this fresh lunge/knee-lift regression; test the same framing
hypothesis on another suitable licensed inverted clip before adapting live
preprocessing. Do not tune to these results or silently make handstand the whole
static roadmap. Front/back lever and planche representation/access work remains
open. Google/PoseRAC comparators remain evaluated but not adopted; RepNet remains
conditional. No production classifier or technique score is promoted here.
