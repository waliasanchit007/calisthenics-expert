# M0.5 Non-Participant Study Readiness Design

- Status: Approved
- Date: 2026-08-24
- Base: `ca623d32837dec99d8229083c9159bfd140b1843`
- Decision record: `docs/adr/ADR-0005-android-scoped-phase-0-evidence.md`

## 1. Objective

Turn the completed Android diagnostic harness into a study-ready instrument and rehearse the full
evidence lifecycle without recording a participant.

M0.5 exists to discover protocol, privacy, synchronization, sustained-performance, annotation,
analysis, and withdrawal defects while every artifact remains synthetic or empty-scene. It does not
measure pull-up reliability and does not authorise study execution.

## 2. Claim boundary

All M0.5 device evidence is specific to the admitted Samsung Galaxy S25 Ultra configuration. Every
generated report records and displays:

- platform and application version;
- device manufacturer/model and OS build;
- selected camera and admitted colour/timestamp capability record;
- MediaPipe model digest and runtime version;
- inference delegate and requested cadence;
- preprocessing and observation-contract versions;
- input class: `synthetic_fixture`, `displayed_synthetic_figure`, `empty_scene`, or `participant`;
- detection-active frame fraction, computed as submitted frames with a non-empty landmark result
  divided by all submitted frames, or `UNMEASURED` when no frame was submitted;
- the statement `scope: flagship Android only, non-participant <input class>; no cross-device,
  cross-platform, or participant-workload claim`.

The report generator, not a human author, supplies the scope statement. Missing scope metadata makes
the report invalid.

The Samsung A12/API 31 cannot install the minSdk-33 harness. A compatible mid-range device would
still require its own capability, preprocessing, and sustained-performance admission. M0.5 does not
lower minSdk. Mid-range admission is deferred as a binding prerequisite for any cross-device claim.
Functional iOS replay conformance remains required for cross-platform claims, pooled datasets,
platform comparisons, and Phase 2 entry.

## 3. Non-participant safety boundary

Permitted inputs are committed synthetic fixtures, a displayed synthetic figure, or an empty scene
with no person visible. A test operator may interact with controls while remaining outside every
captured frame. Every device run records an operator attestation that no person appeared in the
captured media. No participant pseudonym, consent record, identity mapping, human video, or
biometric-derived human observation may be created during M0.5.

The M0.5 build exposes no study-mode selector. Every live run is fixed to
`session_kind = live_conformance`, uses a reserved `synthetic-m05-*` pseudonym, and records a
non-participant input class. Device-evidence checkers reject `live_study`, an absent session kind, a
non-reserved pseudonym, `input_class = participant`, or a missing operator attestation. This
mechanically prevents a session from being admitted as participant-study evidence; the no-person-in-
frame rule remains a separately attested physical protocol because software cannot prove identity
absence from arbitrary pixels.

The released v1 manifest semantics remain immutable: an absent `session_kind` continues to mean
`live_study` for backwards compatibility. M0.5 therefore maintains a mechanically enumerated
registry of every admission entry point and proves that each rejects an absent `session_kind` before
any M0.5 artifact can be admitted. Adding an admission path without registering the corresponding
negative test fails repository verification.

For `input_class = empty_scene`, `detection_active_frame_fraction` must equal zero. A non-zero value
does not prove a participant was present, but it contradicts the declared empty-scene condition and
makes the run inadmissible rather than silently accepting the attestation.

Live non-participant capture intended to exercise detection uses
`input_class = displayed_synthetic_figure`. It is distinct from `synthetic_fixture`, which is an
offline/replay input. A displayed-figure run with zero submitted frames, `UNMEASURED` detection, or
zero detection-active fraction remains a truthful non-participant observation but cannot satisfy a
detection-dependent gate; it returns a typed pending/blocking result rather than a vacuous green.

The repository guard remains fail-closed. Study artifacts live outside Git in an owner-approved,
encrypted study store once participant capture is later authorised. M0.5 uses a synthetic store with
the same directory and access model so deletion and supersession can be rehearsed without sensitive
data.

`AGENTS.md` is re-gated during M0.5A. It must name this specification as active scope and preserve an
explicit prohibition on participant capture until a later decision record changes that gate.

## 4. Milestone decomposition

M0.5 is intentionally split into three independently reviewable sub-milestones. Each receives its
own implementation plan and protected-CI boundary. M0.5B depends on M0.5A; M0.5C depends on the
artifact and governance contracts from M0.5A but may overlap with non-device portions of M0.5B when
write scopes are disjoint.

### 4.1 M0.5A — Policy and governance substrate

Deliver tracked protocols and machine-checkable metadata definitions before new capture machinery:

1. **Capture protocol**
   - measured camera/bar geometry and setup tolerances as recorded observations, not quality claims;
   - focus, exposure, lighting, clothing, occlusion, and static-reference procedures;
   - visible/audible slate and clock-offset/drift procedure for future participant video;
   - named operating conditions for the later pilot before results exist;
   - a versioned condition matrix whose candidate revisions are immutable artifacts;
   - explicit confirmation of athlete and venue availability before a matrix digest is locked into
     the study-run manifest immediately before the pilot;
   - pre-lock revisions are permitted only as new digest-bound artifacts with a recorded reason;
   - one permitted protocol-correction pilot, after which unresolved material failure forces stop;
   - post-pilot removal or required-to-exploratory demotion is recorded as deviation and the affected
     required condition remains failed for the final decision.
2. **Annotation protocol**
   - video-only primary labeling view that cannot display pose overlays or predictions;
   - visibly checkable labels, `uncertain` as first-class, and reason codes;
   - two independent annotators and adjudication before any Gold label or split freeze;
   - no annotation-dependent claim when two annotators are unavailable.
3. **Consent, retention, and withdrawal protocol**
   - separate opt-in by artifact class and separate training-use permission;
   - storage administrator/location, access roles, retention periods, withdrawal channels, deletion
     propagation, backup handling, incident response, and minimal tombstone semantics;
   - no-face-blur acceptance is a named human privacy decision, never an implementation default;
   - every human-owned privacy decision is represented as `UNDECIDED`, `ACCEPTED`, or `REJECTED`.
     Protocol documents merge with `UNDECIDED`; participant-capture admission rejects every
     `UNDECIDED` or `REJECTED` required decision.
4. **Governance contracts**
   - a versioned study-run manifest referencing source, annotation, analysis, split, report, and
     supersession artifacts by digest;
   - append-only provenance and immutable admitted artifacts; revisions use new versions and hashes;
   - a platform-scope object that the report renderer must include;
   - condition execution status records that distinguish `instrument_failure` from
     `not_executed_resource`; the latter is a disclosed coverage gap, never evidence that the signal
     is unviable;
   - a location registry covering device exports/staging, transfer staging, study-store source,
     derived artifacts, reports, and tombstones;
   - homogeneous analysis cohorts keyed by platform, device model, model digest, delegate,
     preprocessing version, and observation-contract version; mixed tuples may be compared but
     pooling them fails with a stable error code;
   - synthetic identities that cannot be confused with real participants.
5. **Mechanical guardrails**
   - repository checks for protocol presence, scope metadata, and synthetic-only fixtures;
   - device-evidence checks for fixed `live_conformance`, reserved synthetic identity, non-participant
     input class, and operator attestation;
   - an enumerated admission-path registry whose tests reject absent `session_kind` on every path;
   - an empty-scene consistency check requiring zero detection-active fraction;
   - `AGENTS.md` operating gate updated without weakening participant-data prohibitions;
   - no network upload path and no automated paid service.

M0.5A does not create a consent-record store or real participant identity system. It specifies the
future flow and proves its schemas using synthetic records only.

### 4.2 M0.5B — Instrument prerequisites

Deliver the missing device and media evidence using non-participant inputs:

1. **Backup and transfer exclusion**
   - set manifest-level backup behavior explicitly;
   - add Android 12+ `dataExtractionRules` that exclude all diagnostic/study exports from cloud
     backup and device-to-device transfer;
   - retain backward-compatible backup rules where Android tooling requires them;
   - test both configuration files and their coverage of every export root.
2. **Synchronized video**
   - record a camera video stream associated with the exact observation session;
   - bind video, pose stream, manifest, and synchronization evidence by digest;
   - record monotonic clock mapping, offset, drift estimate, first/last frame relation, and dropped
     video/analysis frames;
   - fail finalization when required media or synchronization evidence is missing or inconsistent;
   - verify only with synthetic-display or empty-scene input.
3. **Device-side withdrawal capability**
   - add a narrow storage interface that enumerates session-owned files and directories and purges a
     named session from app-private export, staging, and transfer-staging roots;
   - reject traversal, symlink escape, partial identity, and roots outside the registered location
     set;
   - fsync affected parent directories and verify by a second enumeration that no session-owned
     artifact remains;
   - retain a minimal tombstone outside the purged artifact roots and never treat deletion failure as
     successful withdrawal.
4. **Sustained S25 characterization**
   - run 20–30 minute non-participant sessions at requested 10, 15, and 30 FPS conditions;
   - before the first run, derive and freeze a start-state envelope from a fixed idle-baseline
     procedure: Android thermal status, battery temperature variability, charging state, and ambient
     condition; record the procedure version and digest;
   - start every measured run only after cooldown returns the device to that frozen envelope;
     otherwise record the condition as blocked rather than relaxing the envelope;
   - pre-register and record the cadence execution order so results cannot be reordered after
     observation;
   - record delivered callbacks, submitted/emitted frames, every skip reason, inference latency
     percentiles, achieved-cadence time series, thermal status time series, battery start/end and
     charging state, app/device/model/delegate identity, and process/source outcome;
   - never reduce check scope when detections are absent; detection-dependent fields remain pending
     unless the admitted synthetic figure actually fires the detector;
   - interpret the emulator only as composition/build evidence, never as device-performance evidence.
5. **Mid-range boundary**
   - record the absence of an admitted mid-range device in every M0.5 report;
   - do not install the current app on API 31, lower minSdk, or infer behavior from the S25;
   - a future mid-range admission slice requires its own capability probe, camera/YUV evidence,
     sustained characterization, and release-owner approval.

If backup exclusion, synchronization integrity, or sustained measurement fails, M0.5B records the
failure and remains incomplete. A successful command with a blocking observation is not converted to
green evidence.

### 4.3 M0.5C — Synthetic study rehearsal

Build the study pipeline around synthetic artifacts before Gold labels or participant data exist:

1. **Family-A signal analysis**
   - detection-rate curves over declared presence cutoffs;
   - dropout-run distributions;
   - torso-normalized landmark jitter on quasi-static synthetic input;
   - normalized-versus-world landmark availability and stability;
   - achieved cadence, inference latency, thermal state, and battery observations;
   - `UNMEASURED` for unsupported cells, never zero or an inferred estimate.
2. **Blind annotation tooling**
   - primary annotation surface receives video and protocol only;
   - pose overlays, predictions, confidence values, and machine-derived events are not available to
     the labeling component, not merely hidden by a toggle;
   - independent labels and adjudication remain distinct immutable artifacts.
3. **One-command reproducibility**
   - validate artifacts, run analysis, and render the report from pinned inputs in one command;
   - report exact input digests, software versions, exclusions, warnings, and platform scope;
   - deterministic reruns produce byte-identical machine-readable results apart from explicitly
     excluded invocation metadata, whose field list and exclusion-rule version are themselves
     digest-pinned in the report.
4. **Governance rehearsal**
   - create a synthetic source session, annotations, analysis, split, and report;
   - enumerate every registered location for the synthetic session: device app-private exports,
     device staging, transfer staging, synthetic study-store source, and derived artifacts;
   - simulate withdrawal by purging every eligible copy and running a post-purge verification scan on
     each location;
   - retain only the allowed minimal tombstone;
   - create split-v2 without the withdrawn subject/session;
   - mark dependent analysis and reports superseded without rewriting history;
   - prove a stale report cannot be presented as current.

No Family-B movement interpretation, rep counting, joint-angle acceptance, form score, or Gold label
claim belongs in M0.5. Those require synchronized participant video and two independent annotators.

## 5. Artifact model

Every admitted artifact has:

- stable artifact type and schema version;
- logical study/run/session identifier;
- immutable content digest;
- producer code SHA and tool version;
- creation timestamp separated from measurement timestamps;
- input artifact digests;
- participant class fixed to `synthetic` during M0.5;
- non-participant input class, operator attestation, and detection-active frame fraction;
- registered location set for every session-owned copy;
- platform scope and supersession status where applicable.

An admitted artifact is immutable. A revision creates a new artifact ID/version/hash and an explicit
supersedes edge. Deletion never rewrites an admitted report; it creates a tombstone and a new split or
report version while mechanically marking affected predecessors as superseded.

## 6. Error and stop behavior

- Invalid or incomplete artifacts never enter an admitted/current root.
- Privacy, backup, synchronization, scope, and provenance failures are typed and fail closed.
- An `empty_scene` run with non-zero detection-active frame fraction fails scope admission.
- A missing optional measurement is rendered `UNMEASURED`; a missing required measurement makes that
  operating condition inadmissible.
- Tools return meaningful exit codes. Human-facing output is not parsed to decide success.
- Device evidence commands print typed records for an explicit recording step and never write the
  authoritative ledger themselves.
- No retry may silently change camera, cadence, delegate, preprocessing, or operating condition.

## 7. Pre-registered decision shape

Before participant capture, the capture protocol names the operating-condition matrix and marks each
condition required or exploratory. It also names the required artifact and metric cells for each
condition. Candidate matrices may be revised before the pilot only by creating a new immutable
artifact, linking its prior digest, and recording a reason. Athlete and venue availability for every
resource-dependent required condition is confirmed before the selected matrix version and digest are
locked into the study-run manifest. An unlocked readiness manifest cannot pass the participant-capture
gate. After that lock, removing a required condition or demoting it to exploratory counts as failure
of that original required condition.

Every condition execution record is `planned`, `admissible`, or `inadmissible`. An inadmissible record
must name exactly one reason:

- `instrument_failure` means the condition was attempted but failed a pre-registered artifact,
  metric, synchronization, independence, or validity requirement; it is evidence about viability;
- `not_executed_resource` means the condition was not run because a required athlete, venue, device,
  or other declared resource was unavailable; it is a disclosed coverage gap and is not evidence
  that the signal failed.

After a separately authorised Phase 0 pilot:

- any attempted condition with an invalid artifact or required `UNMEASURED` cell is inadmissible with
  `instrument_failure`;
- any required condition not attempted because a declared resource was unavailable is recorded as
  `not_executed_resource` and reported as missing coverage;
- any unresolved required `not_executed_resource` leaves the final decision
  `INCOMPLETE_COVERAGE`; it is not converted into `GO`, `CONDITIONAL-GO`, or `NO-GO`;
- `NO-GO` applies when no attempted pre-registered **required** condition remains viable because of
  `instrument_failure`, or when a material privacy,
  synchronization, independence, or validity defect remains after the single correction pilot;
- `CONDITIONAL-GO` applies when a strict, non-empty subset of attempted pre-registered **required**
  conditions remains after instrument failures, and its restricted envelope is exactly that subset;
- `GO` applies only when every locked required condition was executed and admitted, with no resource
  coverage gap, and candidate Phase 2 thresholds can be derived without an unresolved material
  validity finding.

Surviving exploratory conditions are reported but cannot by themselves constitute an operating
envelope.

The report may propose evidence-derived numeric thresholds, with uncertainty, but cannot approve
them. Threshold acceptance and Phase 2 entry are later human decisions. Functional iOS conformance
and mid-range admission remain binding before Phase 2.

## 8. Verification strategy

Each sub-milestone uses regression-first tests and one protected-CI boundary.

- Schema and semantic-validator tests prove accepted and rejected governance artifacts with stable
  error codes.
- Repository tests prove participant artifacts, identity/consent files, credentials, model binaries,
  and unscoped reports cannot enter Git.
- Android tests mutate every backup-exclusion and synchronization-admission conjunct and require the
  relevant test to turn red.
- Synthetic end-to-end tests prove artifact immutability, independent annotation layers,
  deterministic analysis, withdrawal, split revision, and report supersession.
- Synthetic negative tests prove that mixed platform/device/model/delegate/preprocessing/contract
  tuples cannot be pooled and return the registered stable error code.
- Device withdrawal tests prove enumerate, purge, directory durability, and post-purge absence across
  every app-controlled root; each purge/admission conjunct has a mutation that must turn red.
- Device gates record exact S25 identity and refuse emulators or unauthorized models.
- The slice boundary runs Python verification, Kotlin/JVM tests, Android assembly/instrumentation,
  iOS compile, repository guard, one synthetic integration run, bounded full-range review, and
  protected CI.

Only Critical and Important findings block. Review is mandatory at privacy/contract boundaries and
the final M0.5 integration boundary; routine implementation relies on tests, static checks, exact
diff inspection, and CI.

## 9. Human gates

M0.5 implementation does not consume these gates automatically:

1. approval of this written specification;
2. privacy model acceptance, including storage administrator/location, access matrix, retention,
   deletion/backups, incident handling, and no-face-blur risk; every required decision must be
   `ACCEPTED`, because `UNDECIDED` and `REJECTED` fail participant-capture admission;
3. confirmation of athlete, venue, device, and other matrix resources before the selected candidate
   matrix digest is pilot-locked;
4. availability of two independent annotators before Gold labels or split freeze;
5. explicit participant-capture authorization after M0.5 evidence is reviewed;
6. approval of any numeric Phase 2 threshold and the final GO/NO-GO/CONDITIONAL-GO result;
7. admission of a mid-range device and functional iOS conformance before Phase 2.

No implementation may use paid credits, create a cloud upload path, or purchase devices/services.
Those remain separate human cost decisions.

## 10. Completion criteria

M0.5 is complete only when:

- M0.5A, B, and C each have a merged protected-CI boundary;
- the three protocols and governance contracts are versioned and mechanically validated;
- study-mode artifact admission remains mechanically prohibited, and every physical capture carries
  the no-person operator attestation;
- every registered admission path rejects absent `session_kind`, and empty-scene evidence rejects a
  non-zero detection-active frame fraction;
- displayed-synthetic-figure evidence cannot satisfy a detection-dependent gate unless detection
  actually fires;
- pilot-lock and condition-outcome contracts preserve pre-lock revision provenance and distinguish
  instrument failure from unexecuted resource coverage;
- backup/cloud/device-transfer exclusions cover every export root;
- synchronized non-participant video and observation artifacts carry valid digest-bound sync evidence;
- device withdrawal rehearsal removes the session from export, staging, transfer staging, study
  store, and derived roots, preserves only the tombstone, and verifies post-purge absence;
- sustained S25 measurements exist for requested 10, 15, and 30 FPS with truthful time-series and
  latency/thermal/battery evidence, the same frozen start-state envelope, cooldown admission, and a
  pre-registered execution order;
- the synthetic study pipeline regenerates its report in one command;
- overlay-blind annotation is structural;
- withdrawal creates a tombstone, split-v2, and superseded prior outputs without rewriting history;
- every report carries the flagship-only scope statement;
- unsupported cells print `UNMEASURED`;
- all repository, Python, Kotlin/JVM, Android, iOS compile, device, and CI gates required by the
  affected sub-milestone pass;
- no participant data was created.

Completion makes the repository ready for a human privacy and first-capture decision. It does not
mean the signal is reliable, the study is approved, iOS is functional, or Phase 2 may begin.
