# Agent Instructions

## Source of truth

Read [`docs/PROJECT-STATE.md`](docs/PROJECT-STATE.md) **first**. It is the entry point: where the
project is, what works, what is broken, and what to do next. It also carries the read order for
everything else.

Then read [`docs/central-handoff.md`](docs/central-handoff.md) before proposing architecture,
planning work, or modifying the project. It is the canonical product and technical direction for
Calisthenics Movement Intelligence — the north star, not the status report.

The immediate scope is Phase 0: determine whether an ordinary phone camera and MediaPipe Pose Landmarker can produce a reliable canonical representation of calisthenics movement. The deliverable is measurement evidence, not a production coaching application.

Do not silently turn hypotheses into decisions. Record open questions, assumptions, evidence, and architecture decisions explicitly.

## Current operating gate

The owner approved the product-learning reset and exercise-independent viewer implementation on
2026-09-05. For the exploratory viewer track, follow
`docs/adr/ADR-0006-exploratory-movement-viewer.md` and
`docs/superpowers/plans/2026-09-05-product-learning-reset.md`. It permits RAM-only diagnostic
visualization using synthetic fixtures, attested non-participant camera scenes, or an explicitly
authorized adult-operated self-test with fresh in-app confirmation and no bystanders. Only RAM-only
processing by the app is the default: no app-retained observations, agent screenshots of people,
uploads, or formal study admission. The owner-approved ADR-0006 debug-diagnostics exception permits
explicitly authorized, time-limited numeric movement/state traces through local ADB, outside Git;
the 2026-09-06 automatic-creator-feedback amendment additionally permits debug-build operator-started
self-test bundles containing model-input images, normalized landmarks and exact decision traces,
with the limits and local-only retrieval in ADR-0006. It does not permit audio or automatic uploads.
The owner-approved screen-capture amendment allows user-controlled
system screenshots/screen recordings throughout the app; never enable `FLAG_SECURE`. That screen
policy alone does not authorize agent capture; the feedback amendment is a separate scoped path.
Keep individual authorization/consent records
outside tracked files. Shared components must remain exercise-independent.
Work directly in Calisthenics; Buzz is not a prerequisite. The formal study gate below remains
authoritative for study artifacts and is not satisfied by exploratory viewer results.

The diagnostic-harness integration boundary closed at live `origin/main` commit
`ca623d32837dec99d8229083c9159bfd140b1843`. The M0 observation-contract foundation closed at
`60097f6ff0dbbb1b9623739e1216e17a70f915a9`.

The active operating gate is the approved M0.5B instrument-prerequisites design at merged
`main` commit `789af8eddc0cdb81a3e4ad96ad119dd28111fa7b` in
`docs/superpowers/specs/2026-08-24-m0-5b-instrument-prerequisites-design.md`, subordinate to the
M0.5 study-readiness design in
`docs/superpowers/specs/2026-08-24-m0-5-study-readiness-design.md` and governed by
`docs/adr/ADR-0005-android-scoped-phase-0-evidence.md`.
M0.5B permits only the non-participant Android instrument work and synthetic evidence defined by
that design. M0.5C remains a separate approved slice and must not be pulled into this change.

The M0.5B gate remains synthetic/non-participant only. Participant capture remains blocked until
the release owner accepts all eight privacy decisions with digest-valid records, confirms athlete,
venue, device, and other matrix resources, locks the selected matrix digest, and a digest-valid
participant gate passes. This repository must not contain participant video, biometric-derived
observations, consent records, identity mappings, credentials, or real participant fixtures.

M0.5B does not authorize M0.5C work, participant data, a numeric reliability threshold, a
rep/form engine, a production claim, a mid-range claim, or an iOS-equivalence claim. Functional
iOS capture, mid-range admission, participant study execution, and production application
features remain separately governed.

## Development-phase amendment (owner, 2026-09-16)

This project is private, shared with no one, and has a single recorded subject: the owner, recording
himself. Only the **final parameters** that survive testing ever ship. On that basis the owner has
postponed — **not abandoned** — the following until pre-launch:

- The eight privacy decisions and the participant gate described under *Current operating gate*.
  The formal gate text below is left unedited as the record of what was approved; this amendment
  states what is in force during development.
- The retention/withdrawal ceremony in `docs/protocols/privacy-retention-withdrawal.v1.md`.
- Study-release formalities for internal experiments.

The owner has given standing permission to record and log his own movements, including camera-input
images in debug self-test bundles, under the ADR-0006 limits.

**If training data involving anyone else is needed, permission is obtained first.** That is the
owner's stated position; this amendment does not widen it, and it does not authorize recording any
other person.

Three things are **kept regardless**, because they are not the project's to waive:

- **Never bypass a third party's access restriction.** `fetch_youtube.py` stops at a sign-in wall;
  leave it stopped and record the refusal. This is someone else's access control, not an internal
  process rule, so a development-phase relaxation does not reach it.
- **Creative Commons attribution and licence recording** — a condition set by the people whose
  footage is used.
- **Media and person-derived data stay outside Git.** Now engineering hygiene rather than policy:
  history is permanent and large binaries are unpleasant to remove.

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
