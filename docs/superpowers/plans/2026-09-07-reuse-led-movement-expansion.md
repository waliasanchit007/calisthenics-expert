# Reuse-led movement expansion implementation plan

> **For agentic workers:** Use the approved scoped Codex workhorse workflow. Use superpowers:subagent-driven-development or superpowers:executing-plans as appropriate; the owner's bounded-review policy overrides routine per-task review ceremonies. Steps use checkboxes. Do not restart completed experiments.

**Goal:** Improve and extend movement analysis through saved-input feedback, without another owner workout or silently abandoning approved reuse research.

**Architecture:** Extend existing offline reporting and Kotlin-backed replay. Keep historical evidence, fresh replay and mobile validation distinct. Reuse opportunities run alongside detector evidence, not as a replacement pose backend or a new orchestrator.

**Tech Stack:** Existing Python standard-library report tools, pytest, Ruff/mypy; existing Kotlin/JVM replay; read-only primary-source inspection for candidate admission.

**Spec / authoritative inputs:** docs/central-handoff.md; docs/adr/ADR-0006-exploratory-movement-viewer.md; docs/2026-09-07-existing-movement-analysis-reuse.md; docs/2026-09-07-movement-reuse-research-followup.md; docs/offline-movement-feedback.md. Completed results: docs/2026-09-07-example-classifier-comparison.md and docs/2026-09-07-temporal-classifier-comparison.md.

## Global constraints

- Exploratory offline track only. No app deployment, new owner workout, participant-study admission, source upload, paid credits or production/form claim.
- Preserve approved documents and existing dirty work. No push, PR, merge, tag or cleanup in this increment.
- Keep MediaPipe and common Kotlin counting logic; do not duplicate counters in Python.
- Missing labels are UNLABELLED, missing cases UNMEASURED, never fabricated zeros or passes.
- Preserve report/source/config/model/label provenance; assistant labels are not coach gold.
- Existing inspected/tuned clips remain development. Freeze new source-disjoint evaluation before tuning; uncertain identities cannot establish cross-person generalization.
- Never select a comparator action using expected counts. Never convert 25 OpenPose joints to 33 by padding, or confidence to depth.
- Tests precede behavior changes. Targeted checks per change; full verification at integration boundary. Packaging verification and model-bound replay run sequentially.
- No independent implementation review by default. Consult Claude for a consequential architecture/contract decision, not routine code.
- Stop a bounded comparator feasibility attempt at an actual rights/runtime blocker; record it and continue independent work. No silent fallback or indefinite dependency installation.

## Recommendation reconciliation

| Input recommendation | Status at plan creation | Concrete disposition |
|---|---|---|
| Google example-based comparison | Completed | Preserve static result; do not repeat or promote it |
| Temporal evidence rather than instant pose = rep | Completed narrow experiment | Our preset failed usefulness; does NOT reject learned temporal models |
| PoseRAC non-oracle comparison | Executed offline, not adopted | Task2 continuation; safe CPU loading and frozen sit-up replay on three clips; see 2026-09-08 execution report |
| RepNet independent RGB comparison | Next feasibility | Task2; pinned notebook/checkpoint/runtime, no cloud upload |
| Static calisthenics dataset | Next access check | Task2 in parallel; rights and BODY25B representation remain explicit |
| Freeze labels and grouped evaluation | Active prerequisite | Tasks1/3; no retroactive relabeling to improve scores |
| Include rest/transitions/confusions | Active | Existing cross-movement matrix plus identified missing cases |
| Sit-ups alongside dips/rows/raises | Selected and replayed offline | Task3 selected usable sit-up evidence; Task4 result has explicit timing discrepancy and no held-out claim |
| Fit3D/FLAG3D/Fitness-AQA | Deferred: access/terms | Do not sign agreements or block local work |
| Hands/whole-body/backend alternatives | Deferred: no demonstrated benefit | Reconsider only if a specific observation is unobservable with current input |
| Shared encoder / coaching scores | Deferred: insufficient evidence | Not prerequisites for this increment |

## Task 1 — Historical cross-movement scorecard

**Files:** create tools/offline_movement_scorecard.py and reference/tests/test_offline_movement_scorecard.py.
**Consumes:** manifest version1 with cases: id, engine, report absolute path, report_sha256; or id, engine, missing_reason. Engines squat/lunge/jumping_jack/plank.
**Produces:** build_scorecard(manifest: Path) -> dict and CLI --manifest --output-dir; private summary.json and scorecard.md. No corpus auto-globbing and no execution of arbitrary report content.

- [x] Write real-file tests before implementation: equal predicted/annotated totals with missed/extra events stay discrepancies; UNLABELLED is not success; absent case is UNMEASURED; hold metrics remain separate from repetitions.
- [x] Add hash mismatch, duplicate identifier, mixed missing/report, empty manifest and overwrite tests.
- [x] Complete the delegated RED/GREEN task; controller independently ran final focused suite: 20 passed. Controller also observed and resolved an intermediate Markdown test failure.
- [x] Implement only report projection, validation and escaped Markdown rendering. Preserve original metrics, timing/reasons and provenance rather than reinterpreting labels.
- [x] Run focused pytest, Ruff and strict mypy. Example invariant:
  `predicted=10, annotated=10, matched=8, missed=2, extra=2` must remain visible, never a PASS.
- [x] Create private movement-scorecard-request-v1.json with 13 selected historical reports and 3 named missing cases.
- [x] Run final-source tool to movement-scorecard-v3 (exit0,16cases), inspect Markdown and independently recheck all13 report hashes. Historical aggregation only, not a fresh detector run.

Task1 verification: make verify exit0,1198 tests passed in40.80s, reference Ruff/format and strict mypy21, repository/fixture guards passed. Targeted scorecard mypy1 and Ruff passed. Initial full command stopped on formatting; controller formatted only the two new files and reran successfully. No source/media pipeline was active during packaging tests.

## Task 2 — Bounded reusable-model/data admission check (parallel with Task1)

**Files:** create docs/2026-09-07-reuse-admission-checkpoint.md.
**Produces:** pinned source/asset inventory, concrete prerequisites, selected next comparator and explicit blockers.

- [x] Recheck PoseRAC checkpoint metadata, requirements, evaluator action-selection and device assumptions. Inspect source only; no pickle execution.
- [x] Recheck RepNet notebook revision, checkpoint URLs, runtime/decode/sampling assumptions and download sizes using metadata only.
- [x] Recheck static dataset repository tree, license/readme and LFS pointer; record whether source videos/weights/access terms are actually present.
- [x] Select PoseRAC fixed-action CPU as the next feasibility candidate by setup/data cost, not measured accuracy. Actual inference remains conditional on safe loading/runtime/reuse prerequisites.
- [x] Record prerequisites in docs/2026-09-07-reuse-admission-checkpoint.md. Neither setup failure nor our earlier comparator result establishes that a model is ineffective.
- [x] Preserve the initial inspection-only boundary: no ingestion during that checkpoint. The subsequently authorized CPU continuation below is separate; no access agreements or contact requests were made.
- [x] Continuation: inspect MIT checkpoint provenance, install one isolated CPU environment, restricted-load exact tensors, and verify synthetic inference. No unsafe pickle fallback, global environment changes or paid credits.
- [x] Run label-blind PoseRAC sit-up column5 with frozen upstream decoder on three local clips. Controller independently verifies hashes/PTS and compares frozen labels afterward. Counts10/2/1 versus Kotlin9/0/0; differing event convention explicitly reported, not corrected against labels. Fresh56 tests pass. See docs/2026-09-08-movement-reuse-execution.md.

## Task 3 — Next evidence package (after scorecard)

**Files:** private corpus manifests/annotations/reports; update docs/offline-movement-feedback.md only with sanitized aggregate conclusions.
**Consumes:** Task1 missing/confused cases and Task2 admission findings.
**Produces:** a frozen data package for one targeted correction or one new movement.

- [x] Prioritize usable lunge positive footage and knee-lift confusions; separate landmark dropout from the single-leg detector's known ambiguity. September8: w01 positive9/10 with975usable frames; separately raw-labelled realw00leglift RIGHT1/LEFT2extras with180usable frames each. Same-source development, not independent depth/contact evidence. Targeted correction remains separately gated below.
- [x] Finalize next exercise after event labels are usable. Sit-up-family selected; nine assistant-labelled reclined-raised-reclined windows frozen before predictions. The author aggregate is not the oracle. See docs/2026-09-07-situp-offline-baseline-design.md.
- [x] Inspect raw footage before predictions; record event/hold definition and unresolved boundaries. Raw4fps w01 sheets plus seated context; w19 second recording inspected and cached but remains UNLABELLED because recovery/event intent is ambiguous. Squat negative control raw-inspected separately. No labels derived from detector angles.
- [x] Separate development from an unused original recording; don't split crops of one source across train/evaluation. Preselected MM-Fit w16 author-participant5 now evaluated with frozen v2 and raw labels before extraction; no retuning. See September8 overnight result for limited scope and event-timing misses.
- [x] Reuse existing extraction/cache/replay tools. w01/w19 sit-up replay and raw-inspected w00 squat negative completed; absent held-out/intent-confusion cases remain missing, not fabricated successes.

## Task 4 — One targeted detector/comparator increment

This task is intentionally gated on an observed Task3 failure, not an invented threshold.

- [x] Explain the failure from source/pose/decision alignment: initial reclined hip130 gate was unreachable (maximum126.64) despite1080usable frames; a configuration hypothesis failed, not tracking or a proven state-machine defect.
- [x] New Kotlin behavior had test-first RED/GREEN. Characterize the later observed bent-knee configuration failure with synthetic120-degree endpoints (initial130 ->0, explicit110 ->1); forcing literal130 back into the predicate kills the named count assertion. This later characterization/mutation is NOT relabelled as a test-first production fix.
- [x] Implement the selected sit-up candidate in common Kotlin and a thin reporting adapter. Revise only private reclined-hip config130->110 after the measured failure; no additional production change, weakened trunk gates or count-search sweep.
- [x] Rerun frozen development positives AND confusions, preserving original outputs/config: w01 labelled, w19 UNLABELLED, w00 reviewed squat negative. No controller-generated annotation repair after predictions.
- [x] Report event misses/extras/timing: w01v2 9pred/9annot/8matched/1miss/1extra;67ms early event retained. w19 9unlabelled candidates; squat0. Full details in docs/2026-09-07-situp-offline-baseline-result.md.
- [x] Run unused evaluation only after configuration freezes; report all results, including rejection coverage. w16:9pred/9annot/4matched/5missed-windows/5outside-window counts; five counts67–200ms early,1350frames with no missing selected geometry. Count equality is not event accuracy; original matcher and labels preserved.
- [x] Run full verification sequentially after replay: makeverify exit0,1208Python/Ruff/strictmypy21/guards; freshfullJVM exit0,603tests across74classes,0failures/errors/skips. Explicit newtool10tests/Ruff/mypy1 also pass. No mobile support or release claim until separate device evidence.

## Execution boundary

### September8 bounded follow-up decision

The real leg-lift failure admits one offline opt-in candidate: require usable
contralateral knee geometry and a non-extended contralateral knee at the selected
flexed endpoint. Reuse the existing extended-angle parameter, not a newly fitted
threshold. Preserve original single-leg baseline as the default and retain the
known squat confusion. This is a targeted rejection, not movement identity,
grounding, form validity or app deployment. Regression-first implementation and
unchanged positive/negative replay decide whether to keep the candidate.

Completed locally: opt-in profile removes3unique leg-lift false counts while
retaining all15previously detected lunge events at identical timestamps. Baseline
predictions remain byte-identical on six replays; squat confusion remains. Full
sharedJVM620 and controllerPython1,215 pass. Keep as offline candidate, not app
support. See `docs/2026-09-08-lunge-contralateral-candidate.md` for label-version
corrections, extra opposite-leg missingness and independent-evaluation next steps.

The consultant's ankle-row aggregate proposal is deferred: it is camera-dependent
and would add a fitted threshold before independent-view evidence. Its event
arithmetic was corrected by the controller (37, not47); family aggregation must
separate the leg-lift tail inside the w00 squat-named clip, without overwriting
historical labels. Static raw-observation annotations are useful independently,
but per-side ambiguity and landmark-location claims need raw-image verification;
model confidence must not be relabelled as visible or anatomically correct.

Task1 implementation and Task2 prerequisite inspection are complete.
Task3's sit-up development evidence and Task4's initial offline implementation/replay
are complete through claude-work by explicit owner routing override. No app deployment.
Source-held-out evaluation is now recorded; lunge/hold gaps remain open. Task4's
evidence completion is not production readiness. Two additional low-trunk/hip-motion
negative cases now reject correctly with frozen v2; this is not comprehensive
transition coverage. MM-Fit w16 was selected and its protocol frozen before
download; after initial504/timeouts, a checksummed original arrived and the frozen
evaluation ran without retuning. Preserve its exact timing failures. PoseRAC safe
CPU feasibility and fixed sit-up/squat/jumping-jack comparisons are executed;
preserve limitations rather than claiming metadata-only work or rejecting the
whole model family. RepNet runtime is NOT admitted: checkpoint rights unresolved,
and a worker's retrieved-source files were corrupt. The original static dataset
still has unresolved rights/representation. Two additional licensed human-flag
clips are an observability inventory, not replacement first-skill scope or detector
support. These remain independent tracks alongside lunge/jack/plank confusions.
Progress/check commands and exact SHA live in the ignored ledger; no routine
choice needs owner input. Consult only if admission requires new authority or
a material architecture/security/product decision.
