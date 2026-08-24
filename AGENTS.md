# Agent Instructions

## Source of truth

Read [`docs/central-handoff.md`](docs/central-handoff.md) completely before proposing architecture, planning work, or modifying the project. It is the canonical product and technical direction for Calisthenics Movement Intelligence.

The immediate scope is Phase 0: determine whether an ordinary phone camera and MediaPipe Pose Landmarker can produce a reliable canonical representation of calisthenics movement. The deliverable is measurement evidence, not a production coaching application.

Do not silently turn hypotheses into decisions. Record open questions, assumptions, evidence, and architecture decisions explicitly.

## Current operating gate

The diagnostic-harness integration boundary closed at live `origin/main` commit
`ca623d32837dec99d8229083c9159bfd140b1843`. The M0 observation-contract foundation closed at
`60097f6ff0dbbb1b9623739e1216e17a70f915a9`.

The active operating gate is the approved M0.5 study-readiness design in
`docs/superpowers/specs/2026-08-24-m0-5-study-readiness-design.md`, governed by
`docs/adr/ADR-0005-android-scoped-phase-0-evidence.md`. M0.5A permits tooling contracts,
synthetic governance fixtures, protocols, and fail-closed admission checks. M0.5B and M0.5C
remain separate approved slices and must not be pulled into this change.

The M0.5A gate is synthetic-only. Participant capture remains blocked until the release owner
accepts all eight privacy decisions with digest-valid records, confirms athlete, venue, device,
and other matrix resources, locks the selected matrix digest, and a digest-valid participant gate
passes. This repository must not contain participant video, biometric-derived observations,
consent records, identity mappings, credentials, or real participant fixtures.

M0.5A does not authorize M0.5B/C work, participant data, a numeric reliability threshold, a
rep/form engine, a production claim, a mid-range claim, or an iOS-equivalence claim. Functional
iOS capture, mid-range admission, participant study execution, and production application
features remain separately governed.

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

## Release authority

- The coordinating controller owns decomposition, acceptance, integration, and escalation.
- The release owner alone authorizes participant capture, merges, tags, and release transitions.
- This operating gate creates no tag and does not grant participant-capture authority.
