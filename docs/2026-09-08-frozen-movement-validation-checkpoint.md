# Frozen movement validation: first batch

Date: 2026-09-08. Branch: `codex/movement-viewer`, HEAD `a690a351569cb8c76aaaece47e1398c9d0e7836c`.
Status: first offline evidence batch complete; independent-source validation and trustworthy static labels remain open. No app behavior changed or deployed.

Later continuation: see [handstand observability and plank correction](2026-09-08-handstand-observability-and-plank-correction.md). The historical supported-plank labels discussed below were subsequently found to contradict raw footage; their47.09s difference is not an accepted accuracy metric. The current scorecard withdraws that comparison while retaining its original artifacts.

## Accepted results

- Replayed an additional 48-second, 1,440-frame nonfrontal lunge segment through the existing extractor and Kotlin replay pipeline. Left/right baseline and contralateral-admission configurations each produced 10 events. Within each side, baseline and candidate event timestamps were identical. These are four configurations of one clip, not four independent recordings.
- The left candidate recorded seven additional opposite-knee-unavailable frames without losing an event. Right-side runs each recorded seven low-confidence frames. This is not a claim that every frame was usable.
- Video, model, extractor, cache, configuration, prediction and engine bindings were independently checked. All 1,440 cached timestamps matched decoded source timestamps. All 38 dynamic artifact-manifest entries verified after worker completion.
- All 271 frozen source/test files remained byte-identical. Fresh scorecard tests: 27 passed. Broad historical suite totals were not rerun or presented as fresh evidence.
- Extended the historical scorecard with four explicitly UNLABELLED canonical reports; prior scorecards were preserved.

The clip comes from the already-used w19 source. Independent-source downloads timed out. It is explicitly development/non-fresh evidence, not held-out evidence or a new-athlete accuracy result. Provisional whole-body visual annotations were not used to score per-side predictions. Ten predictions do not establish ten correct repetitions.

## Coverage corrections that affect priorities

- Pull-up and push-up owner-reported live success remains recognized. Absence of an offline scorecard row does not erase that history; no new phone verification occurred here.
- Jumping-jack count agreement (10/10) hides two unmatched annotated windows and two predictions outside matching windows: eight events match. Count totals alone are insufficient.
- Plank detection overlaps the annotated holds, but includes approximately 47.09 seconds outside labelled holds plus an extra short candidate. Reliable hold duration is not established.
- The exploratory squat-as-lunge probe still emits 11 events with either configuration. The contralateral candidate does not solve general movement discrimination.

## Static evidence admission

Two additional Commons compilation videos were downloaded with independently checked per-file license metadata, byte counts and SHA1. Bounded inspection did not establish suitable sustained front-lever, back-lever or planche intervals.

Ninety native boundary frames from an existing human-flag clip were prepared and hashed. Proposed interval labels were NOT admitted: raw images contradicted confident horizontal-posture labels, the revised labels followed pose-cache exposure while claiming independence, and boundary-only inspection cannot establish the full interior hold duration. Preserve these as provisional development material; do not score models against them. The private `static-phase2-20260908/controller-admission.json` records the controlling rejection and exact label hashes.

## Research continuity and next implementation boundary

Both `2026-09-07-existing-movement-analysis-reuse.md` and `2026-09-07-movement-reuse-research-followup.md` remain inputs. Completed Google/PoseRAC comparisons are not reopened or generalized into rejection of untested alternatives. RepNet checkpoint/runtime admission and the original static dataset's rights/representation work remain open. Human-flag material supplements rather than replaces lever/planche priorities.

Next sequence:

1. Obtain a genuinely unused, rights-admitted source with lunge positives and squat/leg-lift negatives. Freeze raw labels before predictions; use the existing pipeline, not another extractor.
2. Use exact event timing and negative controls to choose the next narrow correction. Do not tune to the unlabelled w19 clip or treat the candidate as an automatic movement classifier.
3. In parallel, find clean priority static-hold footage and establish inspectable entry, interior and exit intervals. Disputed or post-prediction annotations remain development-only, not independent benchmark labels.
4. Admit a targeted behavior change only when a replay regression exposes its intended defect. No new owner workout is required for these steps.

## Execution and artifacts

Claude work handled dynamic replay, Claude gaurav static evidence, and Claude sanchit the coverage inventory in parallel. Controller acceptance required a canonical-pipeline correction and rejected disputed static labels; no open-ended review loop was added. All workers completed. No paid credits were enabled or requested.

Private artifacts remain under the existing offline corpus in `frozen-dynamic-phase2-20260908/`, `static-phase2-20260908/`, and `coverage-phase2-20260908/`. The latter contains `scorecard-expanded-v1/`. Detailed progress is in the ignored `.superpowers/sdd/2026-09-08-frozen-movement-validation/progress.md`.

No commits, pushes, PRs, merges, tags, branch/worktree cleanup, device operations or automation changes occurred. Existing uncommitted implementation work was preserved.
