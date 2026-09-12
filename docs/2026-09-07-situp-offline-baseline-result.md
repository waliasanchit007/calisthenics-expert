# Sit-up-family offline baseline: a measured development increment

## Outcome and limits

The shared Kotlin candidate now runs on saved MediaPipe observations through a
hash-bound replay/report entrypoint. No further owner workout was needed. This is
selected-mode cycle analysis, not automatic exercise recognition, strict rep
validation, coaching, a mobile release, or an accuracy claim across people.

| Saved input | Initial configuration | Revised configuration | Evidence meaning |
| --- | --- | --- | --- |
| w01 sit-up-family, 36 seconds | 0 predicted / 9 annotated | 9 predicted; 8 matched, 1 missed, 1 extra | Development agreement with frozen assistant event windows |
| w19 sit-up-family, 42 seconds | 6 candidates | 9 candidates | UNLABELLED; recovery motion is ambiguous, so no accuracy score |
| w00 squat negative control, 36 seconds | Not run | 0 sit-up candidates | One raw-inspected negative clip, not a general false-positive rate |

Equal counts on w01 do NOT close its timing discrepancy. The unmatched prediction
is at 20.933333333 seconds, 66.666667 ms before the frozen event window starts at
21 seconds. The window and detector output remain unchanged. Quarter-second raw
inspection, projected endpoint definitions and confirmation latency can differ;
these data do not establish which convention is preferable. No post-hoc window
widening or label shift was used to obtain a better score.

## Why the initial hypothesis failed

All 1,080 w01 frames had usable selected-side shoulder/hip/knee geometry, but every
frame stayed WAITING. The observed hip angle ranged from 46.98 to 126.64 degrees;
the initial reclined gate required at least 130 degrees. It was unreachable.
The fault was a geometric configuration hypothesis, not landmark dropout or a
demonstrated state-machine defect. A bent-knee reclined position need not present
an image-plane hip angle near 180 degrees.

An excluded initial reclined segment showed roughly 110-121 degrees of hip angle
with low trunk elevation. Configuration v2 changes ONLY the reclined hip minimum
from 130 to 110 degrees. It preserves the raised hip/trunk bands, anchor-relative
trunk rise, confidence, maximum gap and endpoint support. This is one documented
development retune from observed geometry, not an anatomical threshold or a sweep
optimizing expected counts. w01 uses fixed LEFT; w19 uses fixed RIGHT, chosen from
landmark observability before predictions, not count agreement.

The w19 retest and squat control used that same geometric revision. All three
recordings already belong to development; different author participant groups do
not turn previously inspected recordings into an untouched evaluation set.

## Implementation and reproducibility

- `SitupCycleExperiment.kt` measures hip angle and trunk elevation in source pixels
  and tracks reclined -> rising -> raised -> lowering -> reclined. It records
  counted, abandoned, tracking-interrupted and end-censored candidates.
- `ReplaySitupPose.kt` and `:shared:replaySitupPose` consume explicit configuration,
  create a new output exclusively, and emit one result row per input frame.
- `tools/offline_situp_feedback.py` reuses existing event matching, cache integrity
  and provenance helpers. It does not implement another counter. The snapshot
  binds the counter, replay, Gradle wiring, reused ArmSide/Landmark definitions,
  built JAR, poses, configuration, predictions and optional labels.
- This entrypoint is currently standalone. The existing four-engine batch runner
  and historical scorecard have NOT silently acquired sit-up support.

Use fresh private output names, for example:

```sh
.venv/bin/python tools/offline_situp_feedback.py \
  --video /absolute/private/clip.mp4 \
  --model apps/diagnostic-harness/model-assets/pose_landmarker_full.task \
  --cache /absolute/private/cache \
  --config /absolute/private/config.json \
  --labels /absolute/private/labels.json \
  --run-kotlin --output /absolute/private/new-report.json
```

Omit labels when genuinely unlabelled. Never infer a zero-event reference from
their absence. Original failed and revised outputs remain separate outside Git.
Desktop CPU MediaPipe evidence is not proof of Android LIVE_STREAM equivalence.

## Remaining evidence gaps and next useful work

1. Freeze event labels for an unused original recording before seeing predictions,
   then replay the unchanged v2 configuration. Do not retune and call it held-out.
2. Add genuine non-exercise sit-down/lie-down transitions and seated leg motion.
   Hip/trunk geometry cannot establish ground contact or exercise intent; the w19
   recovery tail makes this limitation concrete.
3. The per-frame reason currently describes geometry/timestamp usability, not every
   posture predicate. `NONE` does not mean a frame qualified as an endpoint; phase,
   angles and explicit config were necessary to explain the initial zero result.
   Named posture-gate diagnostics are useful bounded follow-up work.
4. Fixed endpoint sample counts are cadence-sensitive; the unsigned image-plane
   trunk angle is roll-sensitive. Neither was validated on varied viewpoints here.
5. Lunge knee-lift confusion, plank flat-lying false holds and jumping-jack
   single-leg-step ambiguity remain unresolved; this increment does not erase them.

Both reuse research documents remain authoritative inputs. The completed Google
static and in-house temporal experiments are not rerun or generalized into a
rejection of other models. PoseRAC fixed-action CPU feasibility remains the next
qualified comparator, subject to its recorded rights/safe-loading/runtime checks;
RepNet remains a separate RGB comparator and static calisthenics data remain
rights/representation gated. See the reuse admission checkpoint and implementation
plan for their explicit status.

## Collaboration and verification record

The owner requested extensive claude-work use. A fresh account-scoped wrapper ran
Opus 5 for the design challenge and Kotlin implementation, and Sonnet 5 for the
reporting tool. The controller checked the diff, corrected the proposed general
roll-invariance claim, inspected raw footage before labels, froze inputs and ran
the actual media replays. No new orchestrator or routine per-task review loop was
introduced.

Kotlin test-first cycles and the reporting acceptance corrections have recorded
RED/GREEN evidence. The reporting agent initially wrote production before tests;
removing the module afterwards was explicitly rejected as a TDD claim. Its later
engine/dependency regressions and hash/overwrite mutations were genuine targeted
checks. The ignored ledger preserves that deviation rather than rewriting history.

Controller-observed final verification:

- `make verify`: exit0; 1,208 Python tests passed in46.10s, Ruff/format clean,
  strict mypy clean across21 reference source files, fixture/repository guards
  valid. The formal participant-capture gate remains blocked as expected; that
  expected negative is not an exploratory replay failure or capture permission.
- `./gradlew --no-daemon :shared:jvmTest --rerun-tasks`: exit0, all6tasks executed,
  603 tests across74classes; JUnit XML confirms0failures/0errors/0skips, including
  30 sit-up tests (23common +7replay).
- The new Python tool's10focused tests, explicit Ruffcheck/format and strict mypy
  also passed; it is outside the reference package's default mypy source list.
- The bent-knee characterization counts0 with130 and1 with110 on an independently
  constructed synthetic sequence. Forcing the predicate back to literal130 fails
  the named count assertion. The complementary test preserves the trunk-rise
  gate. Production source was restored byte-exactly and its hash matches every
  recorded real replay. This is characterization, not a retroactive TDD claim.
- `git diff --check`: exit0. All real replay processes were finished before the
  integration commands; no packaging/model-asset race was introduced.

No push, PR, merge, tag, app installation, device capture or raw data upload was
performed. Existing dirty work and the branch/worktree are preserved.
