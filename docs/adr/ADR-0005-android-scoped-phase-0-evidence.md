# ADR-0005: Android-Scoped Phase 0 Evidence Before Cross-Platform Claims

- Status: Accepted
- Date: 2026-08-24
- Decision owners: Sanchit Walia and the coordinating orchestrator, after Claude Opus architecture challenge

## Context

The original Phase 0 plan made Android/iOS replay conformance a prerequisite for study capture.
ADR-0004 later changed the diagnostic-harness sequence to Kotlin Multiplatform with a functional
Android instrument and a compile-valid, fail-closed iOS shell. That decision deliberately deferred
functional iOS capture and inference, while retaining the rule that no cross-platform equivalence
claim can be made until both platforms process the same input sequence.

The Android harness has now completed its mandatory integration boundary on a Samsung Galaxy S25
Ultra. Building functional iOS before checking whether the Android signal and study machinery are
useful would reverse the project's falsification order. Silently treating Android evidence as
cross-platform evidence would be equally invalid.

The original plan also requires a mid-range Android device from the beginning. The currently known
mid-range device is a Samsung A12 on API 31, which cannot install a minSdk-33 application; even a
compatible mid-range device would remain inadmissible until it passed its own camera, preprocessing,
and sustained-performance evidence. Lowering minSdk or generalising the S25 evidence would each be a
separate product and instrumentation decision.

## Decision

Phase 0 may proceed through non-participant study-readiness work and a separately authorised
Android-only participant pilot before functional iOS conformance, subject to all of these limits:

1. Phase 0 evidence is explicitly scoped to the Samsung Galaxy S25 Ultra configuration admitted by
   `docs/evidence/device-probes/s25-ultra-SM-S938B-api36-camera-capability.json` at SHA-256
   `475bc9592b702f75c7890fb62a520e1ebe2e32e961d8de6cd15c9436ed51fde7`. It is not evidence for
   Android generally, for a device class, or for iOS.
2. Mid-range coverage is a material known gap. Admission of a mid-range Android device is a binding
   condition before any cross-device claim. The harness minSdk remains 33; this ADR does not
   authorise lowering it.
3. Functional Android/iOS replay conformance remains mandatory before any cross-platform claim,
   pooled Android/iOS dataset, platform comparison, or Phase 2 entry.
4. M0.5 is non-participant work. It may build governance, synchronized-video, sustained-measurement,
   annotation, analysis, and withdrawal-rehearsal machinery using only synthetic or empty-scene
   inputs. Live detection-bearing input is named `displayed_synthetic_figure`; `synthetic_fixture`
   remains the offline/replay class. A displayed-figure observation that does not fire the detector
   remains pending for detection-dependent gates rather than passing vacuously. M0.5 does not
   authorise participant capture.
5. Participant capture requires a later explicit release-owner gate after privacy, consent,
   retention, storage, deletion, annotation, and instrument-readiness evidence has been reviewed.
6. Any later Android-only report must mechanically state its device, OS, camera, model, delegate,
   platform scope, input class, and detection-active frame fraction. A report missing that scope
   statement is invalid rather than implicitly generalisable. Empty-scene and synthetic-figure
   measurements never imply participant-workload performance.
7. An analysis cohort is homogeneous in platform, device model, model digest, delegate,
   preprocessing version, and observation-contract version. Differing tuples may be compared as
   separate cohorts but never pooled. Mixed-scope pooling fails closed with a stable error code.
8. Released manifest compatibility is not rewritten: absent `session_kind` retains its v1
   `live_study` meaning. M0.5 instead enumerates every artifact-admission path and mechanically proves
   each path rejects absence before accepting non-participant evidence.

## Decision rule

Phase 0 does not invent signal-quality thresholds before baseline data exists. It does, however,
pre-register the shape of its falsification decision:

- An attempted condition is **inadmissible** with reason `instrument_failure` when any required
  artifact or metric is invalid or `UNMEASURED`; it can never be included in a successful operating
  envelope.
- A condition not attempted because a declared athlete, venue, device, or other resource was
  unavailable is recorded separately as `not_executed_resource`. It is a disclosed coverage gap,
  not evidence that the instrument or signal failed.
- Any unresolved required `not_executed_resource` leaves the decision `INCOMPLETE_COVERAGE`; it is
  not converted into `GO`, `CONDITIONAL-GO`, or `NO-GO`.
- `NO-GO` means no attempted prospectively named **required** operating condition remains admissible
  because of instrument failure, or a
  material privacy, synchronization, annotation-independence, or measurement-validity failure
  remains after the one protocol-correction pilot allowed by the study design.
- `CONDITIONAL-GO` means a strict, non-empty subset of attempted prospectively named **required**
  conditions remains admissible after instrument failures. The restricted envelope is exactly that
  subset; it cannot be expanded after seeing results.
- `GO` means every locked required condition was executed and admitted, no resource coverage gap
  remains, and the evidence supports deriving candidate Phase 2 thresholds without an unresolved
  material validity finding.

Candidate condition matrices are immutable digest-bound artifacts. Before the pilot lock, a matrix
may be revised by creating a new artifact linked to its prior digest with a reason. Athlete and venue
availability for every resource-dependent required condition is confirmed before the selected
version and digest are locked into the study-run manifest immediately before the correction pilot.
An unlocked manifest cannot authorise capture. After the lock, a removal or
required-to-exploratory demotion is a disclosed deviation and the affected required condition still
counts as failed. Exploratory conditions may be reported but cannot alone constitute an operating
envelope.

Numeric signal-quality thresholds are proposed from Phase 0 baseline evidence and require their own
recorded approval before Phase 2. This decision rule prevents post-hoc redefinition of a failed
condition while preserving the project's prohibition on invented thresholds.

## Consequences

- Android can test signal viability and study machinery before the cost of a functional iOS backend.
- Every Android-only conclusion carries an explicit flagship-only scope.
- Functional iOS work is deferred, not removed; it remains a hard boundary for cross-platform claims
  and Phase 2.
- Mid-range evidence is deferred as a visible gap rather than silently simulated by the S25 or
  addressed through an unreviewed minSdk change.
- Study-readiness implementation can proceed without participant data. The first human capture stays
  behind a separate privacy and release-authority gate.

## Superseded guidance

This ADR narrows the D5/E1 sequencing in `docs/phase-0-plan.md`: cross-platform conformance is no
longer a prerequisite for Android-scoped non-participant readiness or a separately authorised
Android-only Phase 0 pilot. It remains a prerequisite for the claims and transitions listed above.

ADR-0004 remains authoritative for the KMP Android-first harness architecture. This ADR does not
change observation contracts, preprocessing semantics, participant-data prohibitions, or release
authority.
