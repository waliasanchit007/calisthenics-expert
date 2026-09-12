# Offline selected sit-up-family baseline

Exploratory common-Kotlin replay only; no app deployment, automatic recognition,
anatomical ROM or form claim. Both September7 reuse notes remain inputs. PoseRAC
and RepNet inference still require their recorded admission prerequisites;
this small baseline does not establish that either comparator failed.

## Frozen event convention

Count a continuously observed reclined -> raised trunk -> reclined cycle on an
explicit, fixed side. Initial seated-to-reclined descent and final unfinished rise
are boundary fragments, not complete cycles. Independent assistant raw-video
labels were frozen before predictions; all w01 data remain development.

Use shoulder/hip/knee projected into source pixels, preserving aspect ratio:
hip angle plus unsigned trunk elevation above image horizontal. Reclined requires
the open hip band AND low trunk elevation; raised requires closed hip band AND
high trunk elevation AND rise relative to the reclined anchor. This guards a
standing hinge and a leg-only tuck; it cannot establish ground contact or reject
every sit-down/stand-up transition. The far side is not required or fabricated.

## Initial hypothesis, not validated thresholds

Explicit private config: LEFT, reclined hip >=130deg, raised hip <=85deg,
reclined trunk <=35deg, raised trunk >=45deg, anchor-relative rise >=25deg,
confidence >=0.5, maximum gap100ms, three consecutive endpoint samples.
These geometric starting values are proposed by Claude without inspecting raw
landmarks or prediction output. Freeze config before replay. Never fit it using
known counts; record any later hypothesis revision separately.

WAITING -> RECLINED -> RISING -> RAISED -> LOWERING -> RECLINED emits one count.
Direct endpoint jumps do not count. Unusable observations or invalid timing discard
unfinished evidence; counts already emitted survive. Emit typed reasons and a
closed-candidate outcome with timestamps. finish() censors an open candidate once,
never invents a completion. No practice calibration or side switching in v1.

## Review and controller rulings

Claude-work Opus5 design challenge completed session
b0a78acb-7d49-4e81-9117-92c8414e7740. Its useful recommendations are adopted:
single-side fixed config, trunk-motion gates, explicit incomplete cycles,
raw-event labels rather than author aggregate, and rejection diagnostics.

One mathematical assertion is corrected: subtracting two unsigned
atan2(abs(dy),abs(dx)) elevations is NOT generally camera-roll invariant across
quadrant boundaries. Neither angle nor its difference is claimed roll invariant.
Test mirror symmetry and pixel aspect; record roll sensitivity as a limitation.
Likewise fixed endpoint-sample counts are cadence-sensitive, not time-normalized.

Integration spelling is SitupCycleExperiment / ReplaySitupPose / replaySitupPose
with situpConfigPath and situpOutputPath, matching the reporting brief. JSON rows
include timestamp_ns, phase, count, reason, counted, hip_degrees,
torso_elevation_degrees and optional closed_cycle. Counting lives only in Kotlin.

Synthetic behavioral tests precede code: complete/repeated cycles, partial and
edge cycles, no bridging gaps/missingness, endpoint support, standing hinge,
leg-only motion, side lock, pixel aspect and invalid geometry/timing. Replay tests
exercise actual parsers/files, non-overwrite, row parity and final censoring.
Controller runs frozen footage and existing confusions after implementation,
then one sequential integration verification. No routine second implementation
review. Known lunge/hold gaps and gated static-skill data remain pending.

## Observed hypothesis revision (not a silent design replacement)

The initial130-degree reclined gate was unreachable on w01 (observed maximum
126.64degrees, with usable geometry throughout). Config v2 changes only that
minimum to110, based on a visibly reclined excluded initial segment with bent
knees. Other gates and the Kotlin implementation remain unchanged. Preserve v1
and all frozen labels; the revised result is development evidence, not a new
validated standard. See the companion baseline result for exact misses/extras,
the second unlabelled recording and the squat negative control.
