# Calisthenics Movement Intelligence — full Claude handoff

Prepared 2026-09-10. This is a continuation handoff, not a new architecture or a release.
The owner explicitly requests Claude to take over this project. Read this document,
verify the working state, and continue the highest-priority executable work. Do not
restart the project, the harness, the research, or the orchestration infrastructure.

## 1. Executive brief

The product is a general calisthenics movement-intelligence application, not only a
pull-up counter. Long term: recognize movement, count repetitions, time holds,
understand transitions, measure observable movement characteristics, and eventually
provide defensible progression/technique feedback on-device.

The current product-learning track has an Android viewer with selected pull-up and
push-up experiments. The owner reported that both counts became useful after real
self-test debugging. That is user feedback, not a population accuracy benchmark.
A growing collection of offline Kotlin movement experiments and cached video
replays exists. Most are NOT integrated into the installed app and are NOT automatic
exercise recognition. There is no demonstrated all-calisthenics support.

The owner is frustrated by excessive process, repeated research, token expenditure,
and too little visible movement progress. The next output should be a concrete
source-backed movement result, not another general roadmap. Do not require a fresh
workout from the owner for each correction. Use the offline learning loop below.

## 2. Exact workspace — do not lose the uncommitted implementation

Use these shell variables only as path conveniences:

```sh
CAL_REPO=/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/worktrees/m0-5b-task-4-synchronized-video
CAL_CORPUS=/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/offline-corpus
cd "$CAL_REPO"
```

Locally verified at handoff:

| Item | Observed state |
|---|---|
| Active branch | `codex/movement-viewer` |
| HEAD | `a690a351569cb8c76aaaece47e1398c9d0e7836c` |
| HEAD subject | `feat: add manual push-up experiment with movement-bound feedback` |
| Local main and cached origin/main | `f9addd8f1269c0a43ba8db99523ff8a5ac598eed` |
| Cached origin/main...HEAD | 0 commits only on base, 108 only on HEAD |
| Origin | `https://github.com/waliasanchit007/calisthenics-expert.git` |
| Tracked modifications | `apps/diagnostic-harness/shared/build.gradle.kts`; `docs/adr/ADR-0006-exploratory-movement-viewer.md` |
| Untracked status entries before this handoff | 98, including implementation, tests, tools, research and `third_party/` |
| Canonical main checkout | `/Users/sanchit.walia/NonWork/calisthenics-expert` |

The folder name is historical: it is NOT an instruction to resume old Task 4.
The new movement breadth mostly lives in uncommitted files. A fresh clone or merely
checking out HEAD will omit it. `git diff` alone also omits untracked files.
Inspect `git status`, tracked changes, and relevant untracked sources. Preserve all
existing work; no reset, clean, checkout-overwrite, blanket staging, or worktree removal.

No fetch, live GitHub PR/CI check, full test run or device check was performed for
this handoff. Remote numbers above are cached local refs, not verified current GitHub
state. Historical PR numbers and branch ancestry are especially unreliable after
squash merges. Fetch/recheck before any later integration, never infer merge status
solely from whether a pre-squash commit is an ancestor.

## 3. Read order and authority

All relative paths in this document resolve against CAL_REPO unless explicitly stated.

Read completely before architecture/behavior changes:
1. `AGENTS.md`
2. `docs/central-handoff.md` — canonical long-term product and technical direction.
3. `docs/adr/ADR-0006-exploratory-movement-viewer.md` — including uncommitted amendments.
4. `docs/superpowers/plans/2026-09-05-product-learning-reset.md`
5. BOTH `docs/2026-09-07-existing-movement-analysis-reuse.md` and
   `docs/2026-09-07-movement-reuse-research-followup.md`.

Then read current result summaries, not every historical transcript:
- `docs/2026-09-09-coverage-first-results.md`
- `docs/2026-09-09-pike-offline-results.md`
- `docs/2026-09-09-dand-offline-results.md`
- `docs/2026-09-10-autonomous-feedback-reset.md`
- `docs/2026-09-10-youtube-first-discovery.md`
- `docs/offline-movement-feedback.md` for tooling; some chronological sections are stale.
- `.superpowers/sdd/2026-09-10-acquisition-reset/progress.md`
- `.superpowers/sdd/2026-09-09-daytime-coverage/progress.md` for older executable receipts.

The old central handoff's Phase-0-only language and formal study restrictions coexist
with the later owner-approved exploratory viewer reset. Follow the explicit later
amendments for exploratory work; do not silently claim they admit a formal study,
production coaching, iOS equivalence or unrestricted data collection.

AGENTS.md's older provider assignment says Codex implements and Claude consults.
The owner's latest direct request is explicitly a Claude continuation handoff:
Claude may coordinate and implement this continuation. This does not grant release
authority or change the product/data boundaries. Record this precedence rather than
silently treating the historical role wording as current routing policy.

## 4. Settled product/platform decisions

- Kotlin Multiplatform with common business logic and Compose UI, platform-native
  implementations where needed. Android first. iOS target/scaffolding exists for
  future portability; functional camera/backend equivalence is not established.
- Phone: owner uses Samsung Galaxy S25 Ultra, previously SM-S938B, Android 16.
  Verify attached device and current OS before a device operation; do not reuse old
  Wi-Fi ADB IPs, ports or pairing codes. Emulator evidence is separate.
- MediaPipe Pose supplies 33 landmarks. Confidence/bounds are availability checks,
  NOT proof that a landmark is on the correct body joint. Do not switch backends or
  add hand/whole-body models merely to increase landmark count.
- Body pose is an intermediate representation. Keep image-plane measurements distinct
  from anatomical angles, support/contact, forces, grip rotation and scapular mechanics.
- Event model must allow repetitions, holds and transitions. Do not force a hold into
  a repetition or equate a pose-state match with a completed movement.
- Eventually add useful tempo, phase durations, image-plane excursion, pauses,
  consistency, hold intervals and tracking uncertainty. No arbitrary overall form score.
- Keep runtime local/offline and cheap; do not continuously call a large model to
  watch the user. AI can assist bounded offline investigation and provisional labels.
- User-facing screen should show only actionable state, count/hold and next instruction.
  Rapidly changing technical diagnostics belong in the developer feedback output.
- Front-camera preference, preview/overlay mirroring correction and overlay continuity
  were important earlier fixes. Preserve them when integrating new movements.
- Never enable FLAG_SECURE: owner explicitly wants normal system screenshots available.
  This does not itself authorize agent screenshots or recording of people.

## 5. Architecture and reusable code map

| Area | Location |
|---|---|
| Shared geometry/measurements | `apps/diagnostic-harness/shared/src/commonMain/kotlin/com/calisthenics/diagnostic/viewer/ViewerGeometry.kt`, `MovementMeasurements.kt` |
| Selected pull-up/push-up logic | same directory, `experimental/PullUpExperiment.kt` |
| New offline engines | same `experimental/` directory: `SquatCycleExperiment.kt`, `LungeCycleExperiment.kt`, `SitupCycleExperiment.kt`, `JumpingJackExperiment.kt`, `PlankHoldExperiment.kt`, `DipCycleExperiment.kt`, `HangingLegRaiseExperiment.kt`, `PikePushUpExperiment.kt`, `HinduPushUpExperiment.kt`, `StaticSkillObservation.kt` |
| Reusable endpoint timing | `experimental/ElbowCycleTiming.kt`; inspect invariants before reuse |
| Shared behavior tests | `apps/diagnostic-harness/shared/src/commonTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/` |
| Executable Kotlin replays | `apps/diagnostic-harness/shared/src/jvmTest/kotlin/com/calisthenics/diagnostic/viewer/experimental/Replay*Pose.kt`, plus `ReplayMovementTrace.kt` |
| Compose workout UI | `apps/diagnostic-harness/composeApp/src/commonMain/kotlin/com/calisthenics/diagnostic/ui/MovementViewerScreen.kt` |
| Android controller/camera/feedback | `apps/diagnostic-harness/androidApp/src/main/kotlin/com/calisthenics/diagnostic/preview/`: `MovementViewerActivity`, `MovementViewerController`, `ViewerMovementCoordinator`, `ViewerSession`, `ViewerOverlayPresenter`, `ViewerFeedbackBundle` |
| Reference schemas/validators | `contracts/`, `reference/src/observation_contracts/`, `fixtures/` |
| Desktop pose extraction | `tools/extract_offline_pose.py` |
| Cache/event matching/reporting | `tools/offline_movement_feedback.py`, family `offline_*_feedback.py` tools |
| Batch replays / coverage summaries | `tools/offline_replay_batch.py`, `tools/offline_movement_scorecard.py` |
| Existing comparative experiments | `tools/google_pose_baseline.py`, `tools/temporal_pose_baseline.py`, `tools/offline_pose_comparison.py`, `tools/offline_temporal_comparison.py` |
| Owner feedback collector | `tools/collect_movement_feedback.py` |

Do not assume every engine is wired into the generic batch runner or Gradle JavaExec
tasks. The generic batch initially supports squat/lunge/jumping_jack/plank; inspect
current code before extending it. Dip/HangingLegRaise/StaticSkill have Gradle tasks.
Pike and Hindu have standalone JVM entrypoints and private one-build replay scripts.

## 6. Honest movement status

This table summarizes saved reports inspected for the handoff, not fresh reruns.

| Movement/family | Existing result and limitation |
|---|---|
| Pull-ups / push-ups | Owner-reported live counts now useful. Preserve calibration and focused UI. No claim of general recognition or population validation. |
| Squats | Offline counter/replay and calibration work exist. Counts alone and knee flexion do not establish identity; consult saved reports for per-event results. |
| Lunges | Counter, setup/framing experiments and opt-in contralateral guard exist. Fresh positive improved; leg-lift false counts removed on specific controls. Squat confusion remains. |
| Sit-ups | Counter and setup-only side selection exist. One frozen w16 run: 9 counts/9 visual cycles, but only 4 exact-window matches and 5 early events. Do not report 100% event accuracy. |
| Jumping jacks | Counter/replay and practice calibration exist; one fresh post-practice set improved 1/9 to 9/9 matched. Other timing/calibration/confusion limits remain. |
| Bar dips | Side-view development clip: 4 candidates/4 raw cycles, 3 within frozen windows; first 3.97 ms beyond approximate window. Other view much poorer. Not all-view reliability or proof for every straight-bar variant. |
| Hanging straight-leg raises | Implemented, but 0/1 complete raw-labelled cycle on cropped material; 45/114 unavailable frames. Not successful movement coverage. |
| Pike push-ups | One GIF: 1 candidate/1 cycle, but event at 2.32 s is 360 ms early relative to raw return window. One development source, no device integration. |
| Hindu push-ups / dand | One GIF: 5 candidates/5 cycles; only 3 window matches, 2 early. Useful pike confusion control stays zero with usable geometry. Offline only. |
| Plank/body-line/static observation | Projected posture and torso-still intervals exist. Support/contact and named skill identity not established. Historical supported-plank duration labels were withdrawn; do not revive the old accuracy claim. |
| Handstand | Framing/extractor experiments and source candidates; high-confidence arm/leg identity swaps found. No admitted robust handstand analyzer. |
| Muscle-ups | Partial GIF cached; no muscle-up counter found in current source inventory. Full return/support endpoint unavailable in that old GIF. New Claude catalogue is provisional and has acquisition caveat below. |
| Planche / back lever | Licensed stills screened; nearer-side planche observations promising, back-lever ankle placed on a wooden post despite high confidence. No timed-hold validation. |
| HSPU/front lever/toes-to-bar/wall sit/L-sit/hanging knee raise | Do not infer coverage from candidate links or related engines. Priority work remains; explicitly verify any new source/code before promoting status. |

## 7. Owner's exact priority order

1. Muscle-ups (keep ring/bar and strict/kipping distinct).
2. Straight-bar dips.
3. Handstand push-ups (wall-assisted orientations and freestanding distinct).
4. Handstand.
5. Tuck planche.
6. Straddle/full planche.
7. Back lever.
8. Hanging straight-leg raises.
9. Tuck/front lever.
10. Toes-to-bar.
11. Wall sit.
12. L-sit.
13. Hanging knee raises.

Duplicate straight-bar dips in the original message is one item. Do not replace
priorities with convenient off-list movements. Process the highest unblocked item;
record a specific blocker and continue independent useful work instead of stalling.
Owner asked to establish broader offline coverage before integrating the breadth
batch into the app, then add useful measurements. No current deployment is implied.

## 8. Existing corpus and working receipts

CAL_CORPUS is private and outside Git. Preserve originals, source records, label
versions, caches, configs, predictions and failure receipts separately.

Important subpaths:
- `coverage-first-20260909/dips/`: existing dip positives/controls/configs/caches.
- `coverage-first-20260909/dynamic/`: hanging-raise sources and controls.
- `coverage-first-20260909/dynamic/pike-push-up-v1/`: one-cycle pike evidence.
- `daytime-coverage-20260909/dand-v1/`: dand source, raw labels, config, cache, results.
- `owner-priorities-20260909/muscle-up-v1/`: partial GIF, raw labels,
  `partial-phase-cache-v2/`, and `partial-phase-analysis-v2/visual-findings.json`.
- `posture-bank-20260910/`: planche/back-lever stills and IMAGE-mode outputs.
- `overnight-20260909/`: raw-source admission, dynamic reports and diagnostic cases.
- `poserac-feasibility-20260908/`, `repnet-overnight-20260908/`: comparator work;
  do not treat every downloaded/model artifact as admitted.
- `claude-video-search-20260910/`: archival source; limited relevance, not modern
  priority movement validation. Do not return to broad archive searches by default.
- `youtube-tools-venv/`: isolated yt-dlp 2026.8.19 installed during failed acquisition;
  it is not the MediaPipe environment or an app dependency.

Useful private scripts/receipts in CAL_REPO:
- `.superpowers/sdd/2026-09-09-coverage-first/`
- `.superpowers/sdd/2026-09-09-daytime-coverage/replay-pike.py`
- `.superpowers/sdd/2026-09-09-daytime-coverage/replay-dand.py`
- `.superpowers/sdd/2026-09-10-acquisition-reset/screen_muscleup.py`
- `.superpowers/sdd/2026-09-10-acquisition-reset/screen_stills.py`
- `.superpowers/sdd/2026-09-10-acquisition-reset/youtube-acquisition-checkpoint.md`

Read existing commands before rebuilding infrastructure. Some private scripts pin
an old Java executable or cached classpath; verify these and rebuild classes after
source changes. Existing immutable output directories must not be overwritten.

## 9. Claude's latest video catalogue — location, corrections, boundary

The actual scratchpad is accessible on this machine under:

`/private/tmp/claude-503/-Users-sanchit-walia-NonWork-calisthenics-expert/ec8efc5e-a34e-4f10-b09b-ce91f0a7a7c6/scratchpad/`

Metadata: `CLIP-CATALOGUE.md`, `manifest.json`, `annotations_v1_provisional.json`,
`annotations_v2_frozen.json`, `HANDOFF-LAYOUT.md`. Raw files reportedly under
`dl-api/downloads/<id>/`. Directory existence and manifest readability were directly
verified. Raw file hashes/content/annotations were NOT independently reverified by
Codex. Claude's statement that Codex cannot access this directory was incorrect for
this same-user host. It is temporary storage, not durable or automatically shared
with another machine. Do not copy the whole tree blindly: downloader work may contain
authentication caches; never transfer credentials into project artifacts.

Reported useful candidates (labels are Claude reviewer observations, not expert gold):
- `yTczZRbcfJU`: strict ring MU, 3 cycles; candidate interval 3.5–17.5 s.
- `k71s4T2_CJI`: kipping bar MU, 3 cycles; later toes-to-bar excluded.
- `AJAVu29c-NU`: kipping bar MU, 4 supports visually checked; background people and
  uncertain starts, not a clean single-athlete sample.
- `cLTvfDlhiXM`, `tz_YVynl1sk`: 5 wall-assisted HSPUs each, distinct wall orientations;
  some top endpoints unobserved. Kick-up must not count as a repetition.
- `xmrayUHhBgQ`: apparent handstand interval, wall contact unresolved.
- `btUWnzTI8yk`: wall hold, entry outside fixed-camera interval; left-censored timing.
- `gTyaLS1Xwh4`: straight-bar dip tracking challenge, not yet raw-labelled baseline.

Corrections: CrossFit `o69WaY_7k2c` was first called animation by Claude, then retracted
after closer inspection. Do not propagate the animation claim. Its keyed background
and overlaid joint markers are reported evaluation confounds. Camera flatness/phase
correlation and zero detected cuts do not by themselves prove usable real footage.
Titles and motion-mask counts are not independent labels. Refined support versus
lockout timestamps must not be conflated; unknown endpoints remain null.

Acquisition issue recorded in manifest: bot/sign-in rejections, forced alternate
clients, unsuccessful OAuth stream access, and success only after network egress
change. Codex did not admit/process these downloads as a continuation of that
workaround. The manifest's assertion that nothing was bypassed is not independent
evidence. Do not bypass access controls, delegate a bypass, hide provenance, or use
another tool merely to route around a prior block. Preserve the discovery/annotation
structure; use independently available or appropriately authorized source copies
for the executable pipeline and realign timestamps if the copy differs. Keep access
work bounded, not another project-wide licensing research loop. No credential export.

## 10. The next executable packet

Deliver a muscle-up replay result, with strict ring and kipping bar separate.

1. Confirm current source/WIP and latest receipts. No repeated discovery of known files.
2. Establish a usable local input through an allowed route. Screen raw complete phases
   and apparatus/body visibility. No new owner workout is required.
3. Define the experimental event convention explicitly: opening state, pull, transition,
   above-apparatus support, return; support and full lockout may be different observations.
   Do not assume static hang is required for a continuously swinging kipping cycle.
4. Freeze approximate raw labels, uncertainty, variant, source group and development
   split BEFORE predictions. Preserve provisional versions and corrections.
5. Reuse the current extractor, pinned model and native PTS; cache once. Inspect actual
   raw/overlay joint identity near transitions, not just confidence percentages.
6. Reuse shared geometry/timing where appropriate. Add only the smallest offline
   selected-mode analyzer justified by the observations; no arbitrary form thresholds.
7. Targeted regression-first tests: complete/incomplete cycle, repeated endpoint,
   dropout, discontinuous timestamps, false support, and MU-to-toes-to-bar transitions.
8. Report each match/miss/extra/unknown with the trace reason and event convention.
   Make a targeted correction and test on a different source where available.
9. Continue straight-bar dips then HSPU/handstand, preserving priority and variants.

If footage access remains blocked, do not fabricate accuracy or write an entire
named engine against unseen behavior. Useful independent work includes a regression
for the already cached arm-on-shorts failure, annotation consistency checks, or shared
phase-sequence behavior supported by the approved semantics. Call synthetic tests
synthetic. Do not enlarge scope into another framework or model training system.

## 11. How to execute and verify efficiently

Verified Make targets exist; commands below are instructions, not freshly run results:

```sh
cd "$CAL_REPO"
# Only bootstrap when dependencies are missing; this target also configures Git hooks.
make bootstrap
# Required before an Android delivery build in a fresh checkout:
make fetch-model-asset
# Python reference/static/fixture/repository checks:
make verify
# Shared Kotlin tests (use --tests for a focused behavior change):
cd "$CAL_REPO/apps/diagnostic-harness"
./gradlew --no-daemon :shared:jvmTest
```

At a relevant integration boundary, `make verify-harness-jvm` runs shared JVM,
Android host/unit and verification-variant assembly. `make verify-harness-ios` is
available but is not routine work for every offline movement increment. Cold Gradle
caches may need `make prepare-harness-gradle-dependencies`; do not weaken the existing
offline model-packaging negative tests to get a green build.

Environment notes:
- Python reference requires >=3.12. Homebrew executable is under
  `/Users/sanchit.walia/.local/Homebrew/bin/`, not assumed `/opt/homebrew`.
- Desktop extractor optional environment uses MediaPipe 0.10.21 and PyAV 18.1.0.
  Reuse the environment identified in existing extraction logs; do not install
  heavyweight comparators into the reference or app environment.
- Extractor arguments: `--video`, `--model`, `--model-provenance`, `--output`,
  `--source-record`. Source requires verified video hash/source/license/group/split.
  Do not fabricate provenance fields to satisfy validation.
- Model metadata: `apps/diagnostic-harness/model-assets/model_provenance.json`
  and `live_model_provenance.json`; offline evidence used `pose_landmarker_full.task`.
  Fetch and verify pinned assets, never track model binaries.
- Keep `apps/diagnostic-harness/local.properties` checkout-local and ignored;
  verify sdk.dir exists. Current bootstrap does not write this file automatically.
- Use declared Gradle tasks, not guessed `compileDebugKotlinAndroid`; the KMP
  Android-library task naming differs. Verify Java path/version from current tooling.
- Preserve TLS. System CA path `/etc/ssl/cert.pem` has worked here; do not use
  insecure TLS flags or expose tokens to troubleshoot authentication.
- Read native rational PTS; do not reconstruct timestamps from nominal 29.97/30 FPS.
  Non-square pixels/rotation metadata need explicit supported derivatives and provenance.

Build once after actual source changes, then replay many caches. Hash-bind source,
model, labels, config, runtime/code snapshot and predictions. Verify nonzero test
execution from JUnit XML: exit zero plus zero matched tests is not proof. A mutant
must fail the intended assertion, not compilation or an unrelated validator first.
Restore uncommitted/untracked mutations byte-exactly; Git checkout cannot restore
an untracked file. Keep failing observations rather than hiding them by label changes.

Historical verification, NOT fresh handoff results:
- Coverage-first: 97 focused tests; 762 shared JVM tests; make verify 1,279 passed,
  1 optional skip, Ruff/mypy/guards clean as reported in that batch.
- Pike addition: 772 JVM tests, five dip replay outputs byte-identical to prior files.
- Dand addition: 779 JVM tests in 86 suites, no failures/errors/skips reported.
- No new full suite, Android build, device test or live remote CI verification in
  this handoff. Do not relabel these historical totals as current green state.

## 12. Prior research reconciliation — do not rediscover or silently omit

| Research input | Disposition |
|---|---|
| Google embedding/kNN and temporal sample baseline | Implemented offline and compared; not adopted as universal recognition. Inspect September 7 comparison reports, not just original recommendation. |
| PoseRAC | Safe CPU adapter and fixed-action replays completed. Return-event convention differs; controls generated unwanted counts. Keep comparator, not app replacement or globally disproven model family. |
| RepNet | Worker reported smoke, but controller did not admit artifacts because rights/provenance prerequisites failed; some supposed source files were HTML errors. Do not present as validated comparator or rerun ingestion silently. |
| Static calisthenics BODY25B dataset | Relevant hold classes; access/license/representation unresolved. Never pad/rename 25 joints into MediaPipe 33. Preserve a bounded parallel research track, not a critical dependency. |
| RGB/depth study and whole-body/hand models | Future comparators only when a named failure justifies them; no current mobile equivalence or scapular/grip validity. |
| MM-Fit | Existing admitted sources and caches useful. Author labels are set-level, not per-event truth. Participant mapping exists; respect actual grouping and temporal alignment. |

Latest chronology is in `docs/2026-09-08-movement-reuse-execution.md`,
`docs/2026-09-08-overnight-movement-progress.md` and September 9 result documents.
Their explicit corrections supersede early open-item lists. Preserve these findings
without rereading whole transcripts or reopening completed comparisons every turn.

## 13. Operating model, autonomy and safety

- Claude is the receiving lead for this handoff. Use existing authorized account/tool
  access, preferably claude-work; availability and quota are NOT freshly verified.
  Do not resurrect old account permissions from chat or expose credentials. No paid
  credits, overage or plan upgrades; never prompt to buy a way out of quota exhaustion.
- Work directly in Calisthenics. Buzz is a separate orchestration repository at
  `/Users/sanchit.walia/NonWork/buzz-multiagent`, not a prerequisite for this learning
  loop. Do not spend the continuation implementing Buzz or reconstructing old agents.
- Minimal owner intervention. Decide routine coding/testing/analysis details. Escalate
  only material product, architecture, security/privacy, cost, release or destructive
  decisions. A predicted context limit is not a reason to abandon a clean resumable task.
- Use bounded specialist help only where valuable. Routine implementation does not
  require a second model review. Important architectural disagreements may justify one
  focused challenge and one scoped re-review; record minor debt instead of endless loops.
- Parallelize disjoint writes only; one controller coordinates build/extraction resources.
  Verify worker liveness, preserve interrupted WIP and do not duplicate dead/live jobs.
- Owner is currently the sole self-test user and does not want another exercise session
  as the default debugging dependency. Prior consent is scoped through the actual app/
  ADR workflow, not blanket permission to collect bystanders or upload everything.
- Keep raw people video, landmarks, consent/identity records and credentials outside
  Git. No automatic uploads. No new capture/deployment during the present offline packet.
- This handoff itself authorizes no commit, push, PR, merge, tag, cleanup or release.
  Preserve existing branches/worktrees. Obtain explicit integration authority when ready.

Scheduling snapshot: the only automation file found in this host's Codex automation
directory is `calisthenics-daytime-coverage/automation.toml`, status PAUSED, with the
old September 9 cutoff. No new schedule was created. Do not restart it with an expired
deadline or assume Codex heartbeats will run Claude. Verify other sessions' liveness
before any resume; this handoff did not inventory all external agent processes.

## 14. Lessons that must influence the next work

1. Confidence is not joint identity: back-lever ankle-on-post and cropped MU arms-on-shorts
   are cached concrete failures. Do not fix these by merely lowering confidence.
2. Counts can match while event timing is wrong. Report the definition of completion,
   annotation uncertainty, missed/extra events and unavailable intervals separately.
3. Setup/kick-up/dismount and mixed-exercise clips require explicit boundaries.
4. Still images can support posture inspection, not duration. Repeated GIF traversals
   are not independent examples. Missing endpoints cannot be synthesized into evidence.
5. A camera-motion clip may be useful challenge input without being a clean baseline.
6. Agent labels and inventories are fallible. Keep corrections, not just the latest
   confident summary; title/motion-mask/teacher-model agreement is not ground truth.
7. Do not select side, action head, threshold or best clip after seeing expected counts.
8. App UX improvements matter: preserve actionable recovery, completed calibration,
   explicit practice confirmation and the focused screen rather than debug-heavy UI.
9. A new source or a concrete correction is progress; another broad search/review loop
   or a larger test count without meaningful behavior is not.

## 15. First response and reporting contract for the receiving Claude

After reading and checking state, give a short acknowledgement containing:
- the exact active worktree/branch and whether existing WIP is preserved;
- the one next executable packet and its concrete evidence output;
- any actual blocker, with independent work that can continue.

Then work rather than asking the owner to approve routine choices. Maintain an ignored
ledger with branch/HEAD, input hashes, frozen annotation/config versions, commands,
exit codes, event-level results, worker identity/liveness, unresolved issues and next
action. At each batch boundary briefly reassess alignment with broad calisthenics
coverage and eventual useful measurements.

Final reports must distinguish implemented, synthetic-tested, real-video evaluated,
device-tested and deployed. State exactly what changed and what remains unproven.
Never say all movements are supported, the app is 100% reliable, or a study/release
is approved merely because a small batch passed.
