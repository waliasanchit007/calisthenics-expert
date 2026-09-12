# Coverage-first sprint: new offline repetitions and hold observations

Owner correction applied: prioritize new movement breadth from usable footage, defer app integration, then add useful measurements beyond counting. This supersedes the old overnight refinement queue, whose automation is now paused. No app deployment, Git integration, tag, upload, or new workout occurred.

## Actual new behavior

| Family | Implemented | Real footage result | Current claim |
| --- | --- | --- | --- |
| Bar dips | Shared selected-side counter, geometry gates, Kotlin replay | Clear side-view: four candidates for four raw-labelled cycles. Three match the frozen timing windows; first is 3.97ms beyond the approximate window. Other view: one of three on RIGHT, zero on LEFT. Pull-up control: zero. | Useful new development coverage, not all-angle reliability. Both sides reported, not selected retrospectively to hide failure. |
| Hanging straight-leg raises | Shared selected-mode hip/knee/support cycle experiment, Kotlin replay | Zero of the one complete raw-labelled cycle. 45/114 frames unavailable by required-joint validity/confidence. Bent-knee tuck and pull-up controls also zero, but substantially unobservable. | Implemented and evaluated, NOT successful footage coverage. Needs uncropped usable endpoint footage; zero controls do not independently establish discrimination. |
| Torso-still horizontal posture | New per-torso-point motion gate and elapsed intervals, composing existing body-line admission | Eight compatible intervals in the existing human-flag clip; no interval on the pull-up control. | An observable hold ingredient, NOT automatic human-flag identity, whole-body stillness, support force, or validated hold-duration accuracy. Fragmentation remains visible. |

Nothing above is a newly deployed movement. Existing pull-up/push-up work and the squat/lunge/sit-up/jumping-jack/plank experiments remain intact; their prior limitations were not reclassified as successes.

## Faster feedback loop

1. Inspect raw footage and required-joint availability before investing in another full engine. This sprint's hanging-raise crop failure is a concrete reason to move this screen earlier.
2. Freeze raw event windows and developmental settings before predictions. Keep unrelated-motion controls and report unavailable frames alongside misses/extras.
3. Build Kotlin once, replay all saved clips directly, reuse pose caches. The current ten-case batch no longer starts a build per video.
4. Fix executable-behavior bugs; do not tune one clip repeatedly to manufacture a green result. Move to the next source-ready family when the blocker is footage or representation.
5. Use one combined test/integration boundary, not automatic model reviews for every implementation. Reserve consultation for consequential design uncertainty.

Claude-work supplied source admission, raw labels, tests and dip implementation. Its actual session quota exhausted; Codex Luna workers completed the remaining disjoint implementation. No paid credits or unauthorized account switching. Build execution remained controller-owned. Initial missing-API RED evidence exists, but some later diagnostic regressions were implemented before their separate RED run; the ledger explicitly records that deviation rather than claiming perfect TDD chronology.

## Reproducibility and evidence

The shared Gradle replay entrypoints are `replayDipPose`, `replayHangingLegRaisePose`, and `replayStaticSkillPose`. Inputs and outputs remain private, outside the repository.

Private corpus root: `~/.buzz-multiagent/calisthenics-expert/offline-corpus/coverage-first-20260909/`.

- `dips/`: per-video wger license/attribution, raw-labelled windows, frozen LEFT/RIGHT configurations, model-bound caches.
- `dynamic/`: Commons FitnessScape source checksums and attribution, raw labels, frozen configuration, three clip caches. The short positive and full reel contain the same example, not two independent positives.
- `static/config-v1.json`: explicit torso-still configuration. Existing provisional torso-only labels are not promoted to whole-skill gold.
- `breadth-final-v1/`: ten prediction files, per-run receipts, summary, timing scores, unchanged source/config/pose hashes. All ten invocations exited zero; this means execution, not accuracy.
- `controller-observations.json`: qualifications to source-worker claims, including crop, view and editing uncertainty.

Ignored controller ledger and reusable private batch commands: `.superpowers/sdd/2026-09-09-coverage-first/`. Use the recorded source snapshot, not branch HEAD alone: this sprint's changes are uncommitted.

## Next breadth batch

- First inspect the already admitted hanging-knee-tuck material for usable endpoints; reuse the existing geometry/replay machinery if it supports a positive experiment. Do not count the previous straight-leg negative result as knee-raise validation.
- Acquire usable uncropped hanging-raise footage before further threshold work. Original crop loss cannot be repaired by pretending missing joints were observed.
- Keep inverted rows and pike push-ups queued by footage readiness, not by name alone. A previously inaccessible source is not authorization to bypass access controls.
- Continue hold breadth through visible posture and stationarity primitives; keep front/back lever, planche, and handstand identity claims separate from generic body-line geometry. Rights/representation work remains necessary for those sources.
- Integrate the breadth batch into the app only afterwards, as requested. Next measurement layer: phase durations/tempo, observed image-plane excursion, pauses/hold duration, consistency and tracking uncertainty. Do not claim anatomical range, scapular mechanics, grip or forces from inadequate landmarks.

Both September 7 reuse documents remain inputs. Google/PoseRAC work remains completed but not adopted; RepNet and BODY25B retain their recorded access/rights/representation blockers. They were not silently removed, nor repeatedly re-investigated during this sprint.

## Verification

Focused new-family suites: **97 tests, zero failures, zero skipped**, observed from JUnit XML after the final focused build exited zero.

One full shared JVM integration run then passed **762 tests across 82 suites, zero failures/errors/skips** (including the 97 above). `make verify` exited zero: **1,279 passed, one optional skip**, Ruff clean, strict mypy clean across 21 source files, fixture and repository checks passed. The participant-study gate remains blocked as expected; no participant-study approval is implied. Tracked diff whitespace check passed. Existing branch/HEAD remain `codex/movement-viewer` at `a690a351569cb8c76aaaece47e1398c9d0e7836c`; new work is uncommitted and existing WIP is preserved.
