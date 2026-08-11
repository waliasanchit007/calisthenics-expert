# ADR-0001: Stabilize Observation Before Interpretation

- Status: Accepted
- Date: 2026-08-12
- Decision owners: Sanchit Walia, `claude-engineer`, and the coordinating agent

## Context

The reviewed M0 plan proposed stable raw-observation schemas plus placeholder `DerivedFeatures` and `MovementEvent` schemas. It also proposed an opaque `debug_features` block inside `RawPoseFrame`. Subsequent design review identified that Phase 0 exists to determine whether the raw signal is usable and which interpretations are defensible. Releasing interpretation-shaped v1 contracts before collecting evidence would make hypotheses expensive compatibility commitments.

The project still requires uncertainty to be represented explicitly in human annotation. That is an observation about an annotator's judgement, not a machine movement interpretation.

## Decision

M0's first stable contract release contains only `SessionManifest`, `RawPoseFrame`, `Annotation`, and `FixtureMetadata`.

- `Annotation` represents `valid`, `invalid`, and `uncertain` labels, closed invalid reasons, rep indexes, confidence, and label tier.
- `DerivedFeatures`, `MovementEvent`, `RepEvent`, `HoldEvent`, `ReliablePoseFrame`, and `CanonicalPoseFrame` remain deferred until evidence supports their semantics.
- `RawPoseFrame v1` contains no `debug_features` object. A native harness may display diagnostic joint calculations, but any exported debug measurements require a separate experimental artifact designed in that milestone.
- OQ10 is recorded as deferred pending Phase 0 evidence rather than answered by a placeholder `MovementEvent v1`.

## Consequences

- Android and iOS can converge on a lossless observation boundary without inheriting premature product semantics.
- Human uncertainty remains measurable for inter-annotator agreement.
- The project intentionally deviates from the placeholder contracts in the reviewed plan; this ADR makes the deviation explicit.
- Interpretation contracts require a later ADR and design review.
