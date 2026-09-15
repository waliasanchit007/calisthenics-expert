# Results index

The dated documents below are the **evidence base**. They are experiment records, not drafts: each
one states what was frozen, what was predicted and what was measured, and other documents cite them
as provenance. They are **not** consolidated or rewritten — a result that was recorded on a given
day stays as it was recorded, and corrections are added as new entries elsewhere.

Start from `PROJECT-STATE.md`; come here when you need the underlying evidence for a specific claim.

## Current, load-bearing

| Document | What it establishes |
|---|---|
| `PROJECT-STATE.md` | Where the project is. The entry point. |
| `movement-method-ledger.md` | Append-only record of all 41 experiment units, with pre-registered parameters and predictions. |
| `2026-09-11-priority-coverage-results.md` | The main results document. Parts 1–6 cover the priority sweep; **Part 7 (2026-09-12) corrects the lens-facing/viewpoint conflation** and records the instrument rebuild. |
| `central-handoff.md` | Product and technical direction. Timeless; not a status report. |
| `offline-movement-feedback.md` | How the offline movement feedback path is wired. |
| `phase-0-plan.md` | The feasibility-study plan this phase executes against. |

## Movement results by date

| Date | Documents | Subject |
|---|---|---|
| 2026-09-07 | `existing-movement-analysis-reuse`, `movement-reuse-research-followup`, `reuse-admission-checkpoint` | Whether existing analyses could be reused for new movements |
| 2026-09-07 | `example-classifier-comparison`, `temporal-classifier-comparison` | Classifier comparisons |
| 2026-09-07 | `situp-evidence-candidate`, `situp-offline-baseline-design`, `situp-offline-baseline-result` | Sit-up baseline |
| 2026-09-08 | `movement-reuse-execution`, `frozen-movement-validation-checkpoint` | Reuse execution and validation gate |
| 2026-09-08 | `handstand-observability-and-plank-correction`, `lunge-contralateral-candidate` | Handstand observability; lunge candidate |
| 2026-09-08 | `overnight-movement-progress` | Overnight run |
| 2026-09-09 | `overnight-movement-results`, `coverage-first-results` | Overnight results; coverage-first sprint |
| 2026-09-09 | `dand-offline-results`, `pike-offline-results` | Dand and pike |
| 2026-09-09 | `framing-and-fresh-lunge-validation`, `handstand-landmark-identity-check` | Framing effects; landmark identity |
| 2026-09-09 | `next-hold-source`, `next-repetition-source` | Footage sourcing decisions |
| 2026-09-10 | `youtube-first-discovery`, `claude-video-search` | Footage discovery and its boundaries |
| 2026-09-10 | `autonomous-feedback-reset` | Feedback reset |
| 2026-09-11 | `muscle-up-offline-results` | Muscle-up |
| 2026-09-11 | `endpoint-timing-experiment` | Endpoint timing; led to the HSPU estimator adopted 2026-09-12 |

## Protocols and decisions

| Path | Status |
|---|---|
| `adr/ADR-0001` … `ADR-0006` | Settled decisions. **ADR-0006 governs the exploratory viewer**, including the debug-session control and the front-camera choice. |
| `protocols/capture-protocol.v1.md` | Capture protocol |
| `protocols/annotation-protocol.v1.md` | Annotation protocol — **every convention here marks first arrival**, which is why `estimatedDepartureNs` must not be migrated |
| `protocols/privacy-retention-withdrawal.v1.md` | Retention/withdrawal. Ceremony postponed during development; see `PROJECT-STATE.md` §4 |
| `superpowers/plans/`, `superpowers/specs/` | Historical per-slice plans and designs. Useful archaeology; not current direction. |

## Removed 2026-09-16

Four documents were deleted as superseded, not archived, because each was fully contained in what
replaced it:

- `2026-09-10-claude-project-handoff.md`, `2026-09-12-state-and-plan.md` → `PROJECT-STATE.md`
- `2026-09-12-autonomous-session-brief.md` → session-specific prompt, rules folded into `PROJECT-STATE.md` §4
- `2026-09-12-offline-movement-ledger.md` → a stale partial copy (995 of 1227 lines) → `movement-method-ledger.md`
