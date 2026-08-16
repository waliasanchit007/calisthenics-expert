# Agent Instructions

## Source of truth

Read [`docs/central-handoff.md`](docs/central-handoff.md) completely before proposing architecture, planning work, or modifying the project. It is the canonical product and technical direction for Calisthenics Movement Intelligence.

The immediate scope is Phase 0: determine whether an ordinary phone camera and MediaPipe Pose Landmarker can produce a reliable canonical representation of calisthenics movement. The deliverable is measurement evidence, not a production coaching application.

Do not silently turn hypotheses into decisions. Record open questions, assumptions, evidence, and architecture decisions explicitly.

## Current operating gate

The M0 observation-contract foundation closed at `60097f6ff0dbbb1b9623739e1216e17a70f915a9`.

The active implementation scope is the Phase 0 diagnostic harness in `docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md` and its approved implementation plan. Within that milestone, the additive 1.1.0 contract release, Kotlin Multiplatform shared logic, Compose Multiplatform UI, Android CameraX/MediaPipe integration, Android replay/live evidence, and compile-valid iOS shell are allowed.

Canonicalization, reliability thresholds, movement interpretation, rep counting, form scoring, custom ML, study execution, real participant data, functional iOS capture, and production application features remain out of scope.

## Collaboration

- The current Codex controller is the orchestrator. It owns decomposition, risk, routing, acceptance, integration, and escalation.
- Codex is the implementation provider. Use the default workhorse model and escalate model strength only after concrete evidence of inadequacy.
- Claude Opus is a consulting architect for consequential design, cross-component contracts, irreversible data semantics, trust/concurrency boundaries, or a concrete unresolved high-impact disagreement. Claude is not the routine implementer or default code reviewer.
- Routine implementation acceptance comes from regression-first tests, static checks, exact diff/scope inspection, one slice integration run, and protected CI. Do not create an independent review merely because code was produced.
- At a triggered boundary, allow at most one initial review and one scoped re-review. Only Critical and Important findings block; record Minor findings as debt.
- Work autonomously within the approved design. Escalate to Sanchit only for a material product-direction, architecture, privacy/security acceptance, destructive, external-cost, release-authority, or unresolved high-impact decision.
- No agent may use, enable, or ask about paid credits or overage.
- Neither agent should treat another agent's summary as evidence; verify repository artifacts and source material.
- Material decisions and unresolved disagreements belong in durable repository documents, not only model context or chat history.

## Data and safety

- Never commit raw participant video, biometric-derived samples, consent records, credentials, or identity keys.
- Keep raw observations, canonical poses, derived features, human labels, and predictions as separately versioned artifacts.
- Prefer `UNCERTAIN` over an unsupported confident result.
- Do not invent benchmark acceptance thresholds before baseline data exists.
- iOS unavailable adapters must fail closed and must never emit placeholder observations.
- A partially written or validator-invalid session must never appear under the valid exports root.
