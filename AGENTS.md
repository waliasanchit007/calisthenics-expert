# Agent Instructions

## Source of truth

Read [`docs/central-handoff.md`](docs/central-handoff.md) completely before proposing architecture, planning work, or modifying the project. It is the canonical product and technical direction for Calisthenics Movement Intelligence.

The immediate scope is Phase 0: determine whether an ordinary phone camera and MediaPipe Pose Landmarker can produce a reliable canonical representation of calisthenics movement. The deliverable is measurement evidence, not a production coaching application.

Do not silently turn hypotheses into decisions. Record open questions, assumptions, evidence, and architecture decisions explicitly.

## Current operating gate

Sanchit enabled write-mode implementation on 2026-08-12. The active implementation scope is the Phase 0 observation-contract foundation described in `docs/superpowers/specs/2026-08-12-m0-observation-contract-foundation-design.md` and its approved implementation plan.

Within that milestone, language-neutral observation schemas, the Python reference validator, synthetic fixtures, repository/data guards, dependency manifests, and verification tooling are allowed. Android/iOS harnesses, MediaPipe integration, canonicalization, movement interpretation, rep counting, custom ML, acceptance thresholds, and real participant data remain out of scope until the next milestone is designed and approved.

## Collaboration

- `claude-engineer` is the primary architect and implementer.
- `codex-reviewer` independently inspects assumptions, plans, diffs, and tests.
- Neither agent should treat the other agent's summary as evidence; verify against repository artifacts and source material.
- Material decisions and unresolved disagreements belong in durable repository documents, not only model context or chat history.

## Data and safety

- Never commit raw participant video, biometric-derived samples, consent records, credentials, or identity keys.
- Keep raw observations, canonical poses, derived features, human labels, and predictions as separately versioned artifacts.
- Prefer `UNCERTAIN` over an unsupported confident result.
- Do not invent benchmark acceptance thresholds before baseline data exists.
