# Phase 0 (M0) Plan of Record

Status: plan of record for M0. Write-mode implementation was enabled on 2026-08-12; `AGENTS.md` and the approved milestone design define the active implementation boundary.

Source of truth: `docs/central-handoff.md` at commit `b08f521`.

Review provenance: Claude draft, independent Codex review, and Claude consolidation are preserved in the Buzz channel. The consolidated v2 amendments below supersede conflicting language in the baseline draft.

## Baseline draft
# M0 (Phase 0) — Plan of Record, Draft 1 of 4: Structure, Harness Boundaries, Contracts

Read `AGENTS.md` and `docs/central-handoff.md` in full from the clone (`b08f521`). Planning only — no files created or modified. Everything below is a **proposal for ratification**, not an applied change. Decisions / hypotheses / open questions are separated in Part 4.

**M0 scope definition.** M0 = the whole of Phase 0 as one milestone: build the diagnostic harness on both platforms, run the 25–30-set exploratory study, annotate it, analyse it, freeze the held-out benchmark, and produce a go / no-go with evidence. M0 produces **no rep engine, no thresholds, no scores.** Its deliverable is measurement evidence about the raw signal.

---

## 1. Repository structure (proposed)

Single monorepo. Data never enters git — only manifests, checksums, schemas, code and reports.

```text
docs/
  central-handoff.md                 # canonical direction (exists)
  adr/ADR-0001-*.md                  # one ADR per M0 decision
  protocols/capture-protocol.md
  protocols/annotation-protocol.md
  protocols/consent-and-retention.md
  reports/m0-findings.md
contracts/                           # language-neutral source of truth
  schema/session_manifest.v1.schema.json
  schema/raw_pose_frame.v1.schema.json
  schema/annotation.v1.schema.json
  schema/derived_features.v1.schema.json
  CHANGELOG.md
apps/android-harness/                # Kotlin, CameraX + MediaPipe Tasks Vision
apps/ios-harness/                    # Swift, AVFoundation + MediaPipe Tasks Vision
analysis/                            # Python; ingest / validate / metrics / report
benchmark/
  splits/split-v1.json               # session IDs + SHA-256 only
  evaluation-log.md                  # every held-out scoring run, appended
tools/                               # schema codegen, validators, CI guards
```

**Deliberate omission:** no shared cross-platform runtime code in M0 — no KMP, no Flutter, no shared C++ core. Rationale: M0 measures the pose signal, not code reuse; two thin native harnesses conforming to one schema is faster and removes a large failure surface. The shared-code decision belongs to Phase 1 and should be made with M0 evidence in hand.

---

## 2. Android / iOS diagnostic-harness boundaries

Identical responsibility split on both platforms, so divergence is attributable:

| Module | Android | iOS | Responsibility |
|---|---|---|---|
| `CameraSource` | CameraX `ImageAnalysis` | `AVCaptureSession` | fixed, locked capture config |
| `FrameClock` | monotonic capture ts | monotonic capture ts | single time base per session |
| `PoseRunner` | MediaPipe Tasks Vision `PoseLandmarker`, LIVE_STREAM | same | inference only, no post-processing |
| `SessionRecorder` | NDJSON + manifest | same | append-only, no smoothing |
| `VideoRecorder` | optional, synced | optional, synced | annotation source |
| `ReplaySource` | file input | file input | **cross-platform control (see below)** |
| `OverlayView` | debug skeleton | debug skeleton | display only, never persisted |
| `MetadataCollector` | device/thermal/model/versions | same | fills the manifest |

**In scope for M0:** capture, inference, record, export, FPS configuration, replay, metadata.
**Explicitly out of scope for M0:** reliability layer, canonicalization, state machine, rep counting, any score. Named concepts (`RawPoseFrame`, `ReliablePoseFrame`, `CanonicalPoseFrame`, `TrackingState`, `MovementEvent`) exist in M0 only as *contract placeholders* — `RawPoseFrame` is the only one populated.

**All derived features are computed offline in `analysis/`, from stored raw landmarks.** On-device joint angles are debug-display only and are never persisted as authoritative. This is a comparability decision: if angles were computed on-device, an Android/iOS difference could come from two feature implementations rather than from the pose signal. One Python implementation removes that confound entirely.

**Cross-platform comparability controls** (each recorded per session, so any divergence is attributable):
1. Same `.task` model asset file, **pinned by SHA-256**, same Tasks Vision version.
2. Same running mode, `numPoses=1`, same `minPoseDetection/Presence/TrackingConfidence`.
3. Delegate (CPU/GPU/ANE) recorded per session; delegate equivalence is a *hypothesis to test*, not an assumption.
4. **Identical input-resolution and letterbox/crop rule, written explicitly in the protocol.** This is the single largest silent-divergence risk between CameraX and AVFoundation and must be specified numerically, not left to platform defaults.
5. Same colour-space conversion and orientation normalisation before inference.
6. **`ReplaySource` file-input mode.** Both harnesses run the *same recorded clip* through inference. Identical pixels in, so any output difference is purely platform/delegate numerics. This is the strongest cross-platform comparability control available to us and it is cheap; it is a required M0 deliverable, not an optional one.

---

## 3. Shared versioned data contracts

JSON Schema is the source of truth; Kotlin/Swift/Python types are generated from it. `schema_version` is semver; breaking change = major; a reader **must reject** an unknown major. A released schema is never mutated.

**`SessionManifest v1`** — `session_id` (UUID), `schema_version`, `participant_pseudonym_id`, `capture_protocol_version`, `condition{angle_deg_nominal, distance_m_nominal, height_class, environment, lighting_class, clothing_class}`, `device{platform, os_version, model, chipset, thermal_api_available}`, `pose{backend, task_version, model_asset_name, model_asset_sha256, delegate, running_mode, num_poses, min_*_confidence, input_width, input_height, letterbox_rule}`, `capture{camera_facing, preview_fps_nominal, inference_fps_target, exposure_locked, focus_locked, resolution}`, `clock{time_base, t0_monotonic_ns, wall_clock_utc}`, `video{present, path_ref, sha256, sync_offset_ns}`, `harness_git_sha`, `counts`, `notes`.

**`RawPoseFrame v1`** — NDJSON, one line per inference: `session_id`, `frame_index`, `capture_timestamp_ns`, `inference_start_ns`, `inference_end_ns`, `pose_present`, `landmarks[33]{x,y,z,visibility,presence}`, `world_landmarks[33]{x,y,z}` (nullable), `image_width`, `image_height`, `frames_dropped_since_last`.

Four contract decisions that matter more than they look:
- **Timestamps are capture-time monotonic**, with inference latency stored separately. Using inference completion time would corrupt every FPS and tempo comparison.
- **Frames with no detected pose are still emitted**, with `pose_present=false`. Dropout must be an observable row, not an absent one — otherwise tracking loss is invisible in the data and unmeasurable.
- **No smoothing, filtering or interpolation at capture time.** Filtering is an analysis-stage choice we need to be able to vary.
- **Both normalized and world landmarks are stored whenever the API provides both**, because Open Question 1 is undecided and a capture-time choice would foreclose it.

**`Annotation v1`** — `session_id`, `annotator_pseudonym`, `annotation_protocol_version`, `events[{event_id, type, t_ns, rep_index, label, invalid_reasons[], annotator_confidence}]`, `set_level{athlete_reported_reps, notes}`, `media_reference{video_sha256}`, `created_at`.

**`DerivedFeatures v1`** — offline only; always records `feature_extractor_version` and the `raw_pose_frame` schema version it consumed.

**Artifact separation rule** (from `AGENTS.md`): raw observations / canonical poses / derived features / human labels / predictions are **five separately versioned artifact families**, joined by `session_id` plus a version tuple. Never pooled across differing `model_asset_sha256` or pipeline version.
# M0 Plan of Record — Draft 2 of 4: Capture Protocol, Consent & Retention, Annotation Protocol

## 4. Capture protocol

**The budget does not permit a factorial design, and the plan must say so.** 5 angles × 3 distances × 3 heights × 2 environments × 2 lighting × 3 clothing = 540 cells. With 25–30 sets we get a **one-factor-at-a-time sweep from an anchor cell, plus targeted robustness cells, plus replication.** Per-cell statistics are not available; condition *effects* relative to the anchor are.

**Anchor cell:** 45°, 3 m, chest height, indoor, bright, normal clothing, athlete A. Chosen because §4 of the handoff proposes 30–45°/3–4 m as the candidate V1 envelope — the anchor should sit inside the envelope we most want to validate.

**Per-set procedure:**
- Tripod-mounted phone; distance measured with a tape; azimuth from floor markings; lens height measured. Nominal *and measured* values both recorded.
- Exposure and focus locked after framing (autofocus hunting is a confound).
- Clap/slate at set start for video↔pose sync; the sync offset goes in the manifest.
- Full body visible at the bottom hang before the set starts.
- Target ~8 reps per set, realistic execution — tempo is **not** metronome-enforced. We want the movement distribution the product will actually see.
- Lighting recorded as measured lux if a meter is available, otherwise as a class plus a photo of the scene.

**Two structural choices worth flagging:**

**(a) FPS is varied primarily by offline decimation, not by spending sets.** Capture at the highest inference rate the device sustains, then decimate the same stream to 15 / 12 / 10 FPS offline and recompute every metric. This is an exactly *paired* comparison and costs zero sets — spending sets on an FPS axis would consume the entire budget. **Caveat, stated as a hypothesis, not an assumption:** MediaPipe's tracking is stateful across frames, so decimating a 30 FPS stream is not identical to running inference at 10 FPS live. We therefore also capture 2 confirmatory on-device low-FPS sets at the anchor cell and test whether decimation is a valid proxy. If it is not, the FPS conclusion is downgraded to "measured only at captured rate" and the question stays open.

**(b) Dual-phone simultaneous capture.** 8 of the sets are recorded by an Android and an iOS device **on the same take**, mounted vertically stacked on one tripod (not side by side — stacking keeps azimuth identical and varies only height by a recorded ~15 cm offset). This yields 8 paired cross-platform comparisons without spending any set budget, and paired data is where the statistical power in a small study comes from.

**Failure conditions are captured deliberately.** At least 2 sets intentionally violate the envelope (body partly out of frame; strong backlight). Without them, tracking-loss detection has no positive examples and its precision/recall are unmeasurable.

---

## 5. Consent and retention

- **Written informed consent per participant before any recording.** Covers: what is recorded (video *and* pose landmarks), purpose (internal feasibility study), retention period, storage location, who has access, right to withdraw, and — as a **separate opt-in checkbox** — whether the data may be used for future model training. Separating product use from training-data contribution is a §21 requirement, and it is much cheaper to do at first consent than to re-consent later.
- **Pseudonymous participant IDs.** The ID↔identity mapping lives in one access-controlled location, outside the repo and outside the dataset store.
- **Storage:** encrypted-at-rest private store. Repo holds manifests, checksums, schemas, code and aggregate metrics only. Enforced by `.gitignore` plus a pre-commit guard that blocks video extensions and landmark-data patterns — the guard is a deliverable, not a convention, because `AGENTS.md` makes this a hard rule.
- **Retention:** video is the highest-risk artifact and exists only to support annotation. Retain until benchmark annotation is complete and QC'd, then review for deletion; default cap 12 months with explicit re-consent to extend. Landmark streams are retained for the study lifetime — §12 requires reprocessing old sessions with newer pipelines, which is impossible if they are deleted.
- **Withdrawal has a benchmark consequence and needs a mechanism, not a promise.** A withdrawal deletes that participant's video and landmarks, which removes sets from a frozen split. Because splits reference `session_id` and hashes, this produces a **documented, versioned split amendment** with a written justification — never a silent re-freeze.
- **No face blurring during the study.** Faces are not needed for pull-up analysis, but blurring degrades annotation quality; strict access control is the better trade here. Flagged as a decision open to reversal.
- **No cloud upload of study data at M0.** Transfer is by direct wired export from the device. No third-party analytics or crash-reporting SDKs in the harness build.
- Pose landmarks are treated as potentially sensitive body/movement data, per §21 — converting video to landmarks is *not* treated as anonymisation.

---

## 6. Annotation protocol

- **Annotators label video only, with the pose overlay and any predicted events hidden.** If the annotator sees the skeleton, the labels are contaminated by the system being evaluated and the benchmark silently measures agreement-with-itself. Overlay-visible review is a separate later pass, used for failure diagnosis only and never written back to ground truth.
- **Definitions are written before labelling.** `rep_start`, `top`, `bottom`, `rep_end`, `tracking_failure`, plus `valid` / `invalid` / `uncertain`. Each criterion must be stated in *visually checkable* terms (e.g. top defined by a named body landmark crossing the bar plane; bottom defined by visibly straight arms at hang) — not by joint-angle numbers, since angles are the thing under test. `invalid_reasons` is a closed enumeration: partial ROM top, partial ROM bottom, kipping/leg drive, assisted, non-continuous. `uncertain` is a **first-class label** meaning the annotator cannot tell from the video, and it is reported, not discarded.
- **Frame-accurate timing:** step at ≥30 fps; the event time is the first frame at which the criterion is met.
- **Two independent annotators on 100% of the frozen held-out sets**, with a third adjudicating disagreements. Exploratory-only sets may be single-annotated.
- **Inter-annotator agreement is measured and reported before any label is used**: event-level agreement within the matching tolerance, Cohen's κ for valid/invalid/uncertain, and median absolute time difference on matched events. This gives the **human noise floor**, which is the ceiling on any accuracy we could measure — and it is a large part of why no numeric accuracy threshold can honestly be set before baseline.
- **Label tier:** these are Gold labels (§15). Any rule-engine or heuristic output is Bronze and never enters benchmark ground truth.
- Annotations are stored as separate versioned artifacts keyed by `session_id`; annotator identity is pseudonymised.
# M0 Plan of Record — Draft 3 of 4: Study Allocation, Frozen Split, Metrics & Statistics, Device/FPS/Thermal

## 7. Exploratory study allocation — 28 sets

| Block | Cells | Sets |
|---|---|---|
| Angle sweep (athlete A, 3 m, chest, indoor bright, normal) | frontal, 30°, **45° = anchor**, 60°, side-biased | 5 |
| Distance sweep (45°, chest, indoor) | 2 m, 4 m | 2 |
| Height sweep (45°, 3 m, indoor) | floor/low, waist | 2 |
| Anchor replication (athlete A, 3 separate sessions/days) | anchor ×3 | 3 |
| Clothing (anchor otherwise) | tight, baggy | 2 |
| Environment / lighting | outdoor bright, outdoor dusk/low, indoor low light | 3 |
| Athlete B | anchor, 30°, baggy | 3 |
| Athlete C (if available; else → reserve) | anchor, 60° | 2 |
| Failure induction | body partly out of frame, strong backlight | 2 |
| On-device low-FPS confirmation (anchor) | configured 15 FPS, configured 10 FPS | 2 |
| Reserve / re-shoot contingency | — | 2 |
| **Total** | | **28** |

**Dual-phone simultaneous capture** on the 5 angle-sweep sets + 3 anchor replicates = **8 paired Android/iOS recordings** at no extra set cost.
**Replication is non-negotiable:** the 3 anchor replicates across different days are the only way to estimate session-to-session variance. Without them, every condition effect is confounded with "we set the tripod up again."

**Statistical honesty about size.** ~8 reps × 28 sets ≈ **220 annotated reps**, but the independent unit is the *set*, so effective N ≈ 28 (and ≈ 3 athletes). **This study is powered to characterise failure modes and to detect large condition effects. It is not powered to produce precise per-condition accuracy estimates, and no such estimate should be quoted from it.** If that is unacceptable, the fix is more sets, not more analysis.

---

## 8. Frozen held-out split

- **Split at set/session level**, grouped by athlete and condition cell. Never at rep or frame level — frames within a set are near-duplicates and rep-level splitting leaks trivially.
- **Freeze at the end of M0**, before any Phase 1/2 tuning begins. M0 itself trains nothing and tunes nothing, so the split's whole job is to exist untouched before the first threshold is fitted.
- **Composition:** ~1/3 of sets (≈9 of 28), chosen to include the anchor cell (one replicate), one off-anchor angle, one distance extreme, one adverse-lighting set, one clothing-adverse set, one athlete-B set, and one failure-induction set. Held-out must share at least one condition *and* at least one athlete with dev, so condition effects can be separated from athlete effects.
- **Known gap, stated rather than papered over:** with only 2–3 athletes we cannot afford to reserve an entirely unseen athlete. **Unseen-athlete generalisation is therefore unmeasured at M0** and is scheduled as a required expansion of the Phase 2 benchmark. This is a real limitation of the plan, not an oversight.
- **Mechanics:** `benchmark/splits/split-v1.json` lists `session_id`s plus SHA-256 of each session's raw NDJSON and annotation file; a CI check recomputes hashes and fails on drift. The split file is committed; the data is not. Any change produces `split-v2` with a written justification — splits are never edited in place.
- **Usage rule:** held-out data is scored only in explicitly logged evaluation runs, each appended to `benchmark/evaluation-log.md` with the pipeline version. No iterating against held-out results.

---

## 9. Benchmark metrics and statistical analysis

M0 has no rep engine, so M0 metrics are about **signal reliability**, not rep accuracy.

**A. Pose-signal reliability (no ground truth required)**
- **Per-joint detection rate** as a function of a *swept* visibility/presence cutoff — we report the curve, we do not fix a cutoff.
- **Dropout structure:** distribution of consecutive-missing-frame gaps per joint (wrist near the bar especially), longest gap per set.
- **Jitter:** per-joint residual after a zero-phase low-pass at a stated cutoff, reported in **torso-length-normalised units**, not pixels, so it is comparable across camera distance.
- **Static-hang noise floor:** jitter measured during the bottom-hang segment, where true motion ≈ 0, so the residual is close to pure measurement noise. This is the cleanest noise estimate obtainable without lab ground truth, and it is the reference for every other jitter number.
- **Joint-angle trajectories** (elbow/shoulder/hip) per set, plus their noise band during static hang.
- **Bilateral asymmetry as a perspective-error probe:** a strict two-arm pull-up is close to symmetric, so residual left/right angle asymmetry that *varies systematically with camera azimuth* is perspective distortion rather than movement. This gives us a ground-truth-free handle on Open Question 5.
- **Normalized vs world landmarks:** every metric above computed both ways → this is the direct answer to Open Question 1.
- **Pipeline sanity:** inference latency distribution, achieved vs configured FPS, dropped-frame counts.

**B. Event-referenced metrics (against Gold annotations)**
- **Signal quality *at the moments that matter*:** confidence/visibility and tracking state evaluated specifically at annotated top and bottom instants. If the signal degrades exactly at the top of the pull-up or when the wrists occlude at the bar, that is the single most decision-relevant result M0 can produce.
- **Rep-cycle consistency:** resample each annotated rep interval to 0–100 % of cycle; measure within-condition and across-condition dispersion of the elbow-angle trajectory. Large across-angle dispersion tells us how much work canonicalisation must do — or whether it is achievable at all — and directly sizes Phase 1.
- **Top/bottom separability:** distribution overlap and effect size between annotated top and bottom frames. **We report the observed separation; we do not convert it into a threshold.**
- **Tracking-loss detectability:** annotator-marked obvious tracking failures vs measurable confidence drops → precision/recall of "confidence predicts failure," which is what a future `TRACKING_DEGRADED`/`LOST` rule would be built on.

**Rep precision / recall / F1 with temporal tolerance** is *defined and implemented* in M0 and exercised against a trivial placeholder detector purely to prove the evaluation harness works. The real numbers belong to Phase 2. Tolerance is reported as a **curve over tolerance windows (≈100–500 ms)**, never a single hand-picked value.

**Statistical analysis**
- **Unit of analysis is the set.** Reps within a set and frames within a rep are strongly correlated; treating them as independent would inflate confidence by roughly an order of magnitude. Use per-set summaries, or mixed-effects models with random intercepts for athlete and session.
- **Cluster bootstrap resampling sets** (not frames, not reps) for all confidence intervals.
- **Report effect sizes with CIs; do not gate on p-values.** With N≈28 sets and dozens of condition×joint comparisons, significance testing would be almost entirely noise. M0 is explicitly an **estimation/exploratory** study.
- **Pre-registered analysis:** metric definitions and the comparison list are written before the data is analysed. Anything added afterwards is labelled post-hoc in the report.
- **Paired analysis wherever possible** — dual-phone same-take, offline FPS decimation of the same stream, delegate A/B on the same replay clip. Pairing is where the usable power in this study comes from.
- **Every metric is reported broken down by condition**; overall numbers appear only alongside the breakdown (§10 of the handoff).
- **No numeric acceptance threshold is proposed anywhere in M0.** The go/no-go is a written judgement against qualitative criteria and the measured annotator noise floor. Producing an *evidence-derived proposed* threshold set for Phase 2 is an **output** of M0, not an input.

---

## 10. Device, FPS, thermal and battery plan

- **Devices:** minimum one mid-range Android, one flagship Android, one iPhone. The mid-range Android is the binding constraint and must be in the study from the start, not added after conclusions are drawn on a flagship.
- **FPS:** capture at max sustained rate; offline-decimate to 15/12/10 and recompute all metrics paired; confirm with the 2 on-device low-FPS sets (§4a).
- **Delegate:** CPU vs GPU on Android and CPU vs GPU/ANE on iOS, compared by feeding the **same recorded clip** through `ReplaySource` on each configuration — identical pixels isolate delegate numerics from capture variability.
- **Thermal/battery runs:** 20–30 minute continuous sessions per device class, at max FPS and at one reduced FPS. Sample every 10 s: thermal state (`PowerManager` thermal status / `getThermalHeadroom` on Android, `ProcessInfo.thermalState` on iOS), battery %, achieved inference FPS, inference latency percentiles, and any OS-level camera or clock throttling. Controls: not charging, fixed screen brightness, airplane mode, ambient temperature recorded.
- **Reported as:** achieved-FPS-over-time curves, time-to-first-thermal-throttle, % battery per 30 min, and p50/p95 latency drift. These are the inputs to the FPS decision, which **stays open until measured** (§11 of the handoff).
# M0 Plan of Record — Draft 4 of 4: Deliverables, Sequencing, Exit Criteria, Decisions / Hypotheses / Open Questions

## 11. Deliverables

| ID | Deliverable |
|---|---|
| D1 | Protocol docs: capture, annotation, consent & retention + ADRs for every M0 decision |
| D2 | `contracts/` v1 schemas + validators + Kotlin/Swift/Python codegen |
| D3 | Android harness: capture, live pose, record, export, replay mode, FPS config, metadata |
| D4 | iOS harness: same surface |
| D5 | **Cross-platform conformance report** — both harnesses replay the same clip; outputs compared field-by-field |
| D6 | Executed consent forms + encrypted storage + pre-commit data guard |
| D7 | Annotation tooling/workflow, annotator guide, pilot annotation on 2 sets with agreement measured |
| D8 | Pilot capture: 2 sets end-to-end through the full pipeline |
| D9 | Full study capture: 28 sets |
| D10 | Annotation of all sets; dual annotation + adjudication on the held-out subset |
| D11 | Offline analysis pipeline + reproducible report |
| D12 | Frozen `split-v1` with hashes |
| D13 | Thermal / battery / FPS report |
| D14 | **M0 findings report**: answers to the open questions, evidence-derived proposed Phase 2 thresholds, go/no-go with stated confidence |
| D15 | Updated `central-handoff.md` + decision log + open-questions register |

## 12. Sequencing and dependencies

```text
Stage A  D1, D2                     docs + contracts — blocks everything, no code
Stage B  D3 ∥ D4  → D5              harnesses, then conformance
Stage C  D6 ∥ (D1→D7)  → D8         consent + annotation readiness, then pilot
Stage D  D8 → D9 ;  D13 ∥ D9        full capture; thermal runs in parallel
Stage E  D9 → D10 → D11 → D12       labels, analysis, freeze
Stage F  D14 → D15                  report, then handoff update
```

**Hard gates:** no study capture before D5 conformance passes *and* D6 consent is executed. No analytical conclusion before D7 agreement is measured. No split freeze before annotation QC.
**Critical path:** D2 → D3/D4 → D5 → D8 → D9 → D10 → D11 → D14.
**Long poles:** manual annotation (~220 reps × 2 annotators on the held-out portion) and athlete/venue availability.
**No calendar dates proposed** — sequencing is dependency-ordered only, because staffing and athlete availability are unknown to me (see Q3/Q4).

**Blocking dependency on the operating gate:** `AGENTS.md` forbids application code, dependency manifests and generated projects until Sanchit enables write-mode. D3–D5 and D8 onward cannot start. D1 is executable today; **whether D2 (schema files + validators) is permitted under the current gate is Q1 below.**

## 13. Exit criteria for M0

Process criteria — deliberately binary and free of invented accuracy numbers:

- **E1** Both harnesses pass field-by-field replay conformance within a tolerance **derived from observed run-to-run nondeterminism** (measured, not invented).
- **E2** ≥25 sets captured; 100 % schema-valid; complete manifests; zero sessions missing metadata.
- **E3** 100 % of held-out sets dual-annotated; inter-annotator agreement measured and reported.
- **E4** Analysis pipeline regenerates the full report from raw artifacts in one command with pinned versions.
- **E5** `split-v1` frozen with hashes and committed; hash check green in CI.
- **E6** Thermal/battery/FPS report exists for ≥2 device classes × ≥2 FPS settings.
- **E7** Every metric reported with per-condition breakdown and cluster-bootstrap CIs.
- **E8** Written answers — or an explicit "unmeasured" — for handoff Open Questions 1–9, 11, 12.
- **E9** An evidence-derived **proposed** threshold set for Phase 2, plus a go / no-go / conditional-go recommendation with stated confidence.
- **E10** Open-questions register updated; every M0 decision has an ADR.

**Decision exit:** `GO` / `NO-GO` / `CONDITIONAL-GO (restricted capture envelope)`, recorded with evidence. A conditional-go — "the signal is reliable only within a narrow envelope" — is the most likely outcome and the plan treats it as a first-class result, per §26's capture-premise failure mode.

---

## 14. Decisions vs hypotheses vs unresolved questions

### Decisions (proposed, awaiting ratification — each gets an ADR)
1. Monorepo with the layout in Part 1; data never in git.
2. JSON Schema as the contract source of truth, with codegen.
3. **No shared cross-platform runtime code in M0**; decision revisited in Phase 1.
4. **All derived features computed offline**; on-device angles are debug-only.
5. Capture-time monotonic timestamps; inference latency stored separately.
6. Frames emitted even when no pose is detected.
7. No smoothing/interpolation at capture.
8. Both normalized and world landmarks stored.
9. `ReplaySource` file-input mode is a required deliverable and the primary cross-platform control.
10. Annotation on video with the overlay hidden; two annotators + adjudicator on held-out.
11. `uncertain` is a first-class annotation label and is reported, not dropped.
12. Split at set level; frozen at end of M0; amended by version, never edited.
13. FPS varied primarily by offline decimation, with an on-device confirmation check.
14. Dual-phone stacked simultaneous capture for 8 sets.
15. No cloud upload at M0; wired export only; no analytics SDKs.
16. No face blurring during the study; access control instead.
17. **No numeric accuracy threshold defined anywhere in M0** — thresholds are an output, derived from baseline.
18. No unseen-athlete holdout at M0; recorded as a known, scheduled gap.

### Hypotheses (to be tested — must not be quoted as conclusions)
- H1 World landmarks are more camera-angle-robust than normalized landmarks.
- H2 Wrist is the dominant dropout joint near the bar; elbow is the dominant jitter joint.
- H3 Jitter grows with camera distance (worse at 4 m than 2 m) in normalised units.
- H4 15 FPS inference preserves rep-phase information adequately.
- H5 Offline decimation is a valid proxy for on-device low-FPS operation.
- H6 CPU vs GPU/ANE delegate does not materially change landmark numerics.
- H7 Residual bilateral asymmetry is a usable proxy for perspective error.
- H8 The 30–45° / 3–4 m envelope produces the strongest pull-up signal.
- H9 Baggy clothing degrades hip and shoulder landmarks more than elbow.

### Unresolved questions — need Sanchit's input
- **Q1** Does the current planning-only gate permit creating `contracts/` schema files and `docs/protocols/` now, or is even D2 blocked? This determines whether anything at all can start.
- **Q2** Platform priority: is Android or iOS the primary harness, and do we have a mid-range Android, a flagship Android and an iPhone available?
- **Q3** How many athletes are genuinely available, and is a fixed-geometry bar available for repeated sessions across days (required for the anchor replicates)?
- **Q4** **Who annotates?** Two independent annotators is the largest resource ask in this plan, and they need enough calisthenics competence to judge kipping and partial ROM. If only one annotator exists, the agreement measurement — and therefore E3 and the noise floor — collapses.
- **Q5** Is training-data-use opt-in in scope for the consent form now, or is consent scoped strictly to the internal study?
- **Q6** Where does the encrypted dataset store live, and who administers access?
- **Q7** Is measurement equipment available (tripod, tape measure, floor angle markings, ideally a lux meter)? The protocol assumes measured geometry, not estimated.
- **Q8** Reps per set, and should athletes work to failure? Fatigue is valuable signal but changes form and would confound condition comparisons.
- **Q9** Any timeline expectation I should plan against? I have deliberately proposed no dates.

**Handoff rule preserved:** no numeric accuracy threshold is invented anywhere above. Every quantitative gate in M0 is either process-binary (E1–E8, E10) or explicitly deferred until baseline data exists.

## Consolidated v2 review disposition and amendments
# M0 Plan of Record v2 — Consolidated (1 of 3): Disposition of codex-reviewer findings

18 findings. **13 accepted in full, 4 accepted with modification, 1 partially rejected.** Reasons below; the resulting plan changes are in messages 2 and 3.

## Blocking

**B1 — "Field-by-field conformance" is undefined for float outputs (E1 hides an invented tolerance). ACCEPTED IN FULL.**
This is the strongest finding in the review and it is correct. My E1 said "within a tolerance derived from observed run-to-run nondeterminism," which still resolves to a pass/fail number I would have had to invent. Replaced with a **ratio-based conformance report** (see §C1 in the next message): exact equality where exactness is meaningful, and for float landmarks, cross-platform difference reported *relative to a first-measured within-platform repeat-run variance baseline*. No epsilon, no pass/fail gate.

**B2 — This is exploratory characterisation, not a production benchmark; E7/D14 overreach. ACCEPTED IN FULL.**
I stated the study was underpowered and then wrote an exit criterion (E7) demanding cluster-bootstrap CIs for *every* metric, which implies estimability I had just denied. Corrected: intervals only where the cell supports one, automatic minimum-cell-size warnings, and `UNMEASURED` as a required report value. The unseen-athlete gap is accepted for M0 **only** by making an unseen-athlete holdout a binding entry condition for Phase 2 acceptance, written into D15.

## Accepted in full

**A1 — OQ coverage overstated.** Correct. Replaced my prose claim with an explicit per-question status table (message 3). OQ7 in particular exposed a real gap: I specified a *zero-phase* filter for offline noise estimation, which is non-causal and unusable at runtime — so OQ7 ("smoothing that doesn't delay rep events") had no evaluation path at all. Added a causal-filter latency-vs-noise sweep.

**A2 — Immediate Instruction item 6 (on-device joint measurements) not reconciled.** Correct, and repo-verified. Resolution: the harness *does* compute and display debug joint angles on-device and records them in a clearly separated `debug_features` block that no analysis consumes. Capability demonstrated; comparability preserved.

**A3 — CaptureQuality observations in the manifest.** Accepted as human-checked setup booleans + a failure log. Cheap, and it seeds Phase 1 §4.

**A4 — Critical path omits ratification, device acquisition, replay fixtures, annotation-tool readiness, export/QC; D3/D4 are not parallel until the preprocessing contract is frozen; consent is not parallel with protocol work.** All correct. New Stage 0 added and the preprocessing/letterbox rule promoted out of a prose protocol into a frozen contract artifact.

**A5 — Annotation workload arithmetic is wrong.** Correct and repo-verified against my own text. I wrote "~220 reps × 2 annotators on the held-out portion," conflating the total with the held-out subset. Actual: ~224 reps single-annotated overall, of which the ~9 held-out sets ≈ **72 reps dual-annotated plus adjudication**. Materially smaller ask; Q4 stands but is less severe.

**A6 — Replay only isolates platforms if both consume identical *decoded* pixels.** Correct, and it invalidates my design as written. Replaying an `.mp4` routes through two different platform video decoders. Fixed: the fixture is **pre-decoded raw frames with per-frame checksums**, fed directly to the inference input path, with pixel format, colour range, channel order, rotation, resize rounding and padding all specified.

**A7 — The stacked 15 cm offset confounds the paired comparison.** Correct. Dual-phone capture is demoted from "paired cross-platform comparison" to **near-simultaneous different-viewpoint evidence**. Platform attribution now rests entirely on the decoded-frame replay (A6), which is the right place for it.

**A8 — Monotonic timestamps are device-local; delegate comparison needs repeat-run variance not bitwise equality.** Correct on both counts.

**A9 — Access/data-flow matrix required.** Accepted.

**A10 — Withdrawal needs tombstones, superseded-report marking and a rerun policy; training opt-in must enumerate artifact types.** Accepted; this closes a real reproducibility hole — without superseded-marking, a withdrawal silently makes an already-published result unreproducible.

**A11 — Static hang is not a clean noise floor.** Correct. Sway, grip and scapular motion, breathing and vibration are all present. Renamed to **quasi-static baseline (measurement noise + residual athlete motion)** and a genuine near-zero-motion control added (see §C4).

**A12 — Bilateral asymmetry is diagnostic, not causal perspective ground truth.** Correct; H7 downgraded.

**A13 — Two live-FPS sets can only expose a large contradiction; keep live and decimated separate; distinguish configured / achieved / tracker cadence.** Accepted; the three cadences are now separate recorded fields.

**A14 — "Passes," "strongest," "adequately" can become de facto thresholds.** Accepted; each is either operationalised or explicitly labelled descriptive.

## Accepted with modification

**M1 — No face blurring (minor).** Accepted, reframed exactly as suggested: **documented risk acceptance**, valid only while the study stays controlled, no-cloud, access-restricted and under a deletion deadline. I did not adopt masked annotation exports: masking degrades judgement of body line and kipping, which are the labels we most need. Revisit if the data ever leaves the controlled setting.

**M2 — OQ8 (mandatory joints) and OQ9 (DEGRADED/LOST) need decision rules.** Accepted that the gap is real; **modified in what M0 delivers.** Producing an actual mandatory-joint list or a DEGRADED/LOST rule in M0 would be inventing thresholds before baseline, which `AGENTS.md` forbids. M0 therefore pre-specifies the **derivation procedure** — the inputs, the estimator, and the decision form — and the rule itself is instantiated from the frozen data at the M0→Phase 1 boundary. This is a substantive difference and I want it visible rather than resolved silently.

**M3 — Same-device/same-position control for platform attribution.** Accepted but deprioritised to the reserve block, because A6's decoded-frame replay already provides platform attribution on identical pixels. The physical control is a nice-to-have, not a dependency.

**M4 — Distinguish configured vs achieved vs tracker cadence (part of A13).** Accepted, and extended: achieved FPS is also reported as a *time series*, not a mean, because thermal throttling makes the mean actively misleading.

## Partially rejected

**R1 — "Full codegen, CI hash enforcement and broad pre-commit machinery are over-engineered."**
**Accepted for codegen and CI enforcement, rejected for the raw-data commit guard.**
Dropped: schema codegen (hand-written types validated against the schema is sufficient at this size) and enforced CI hash checking (replaced by recorded hashes plus a manual verify script). Kept: the pre-commit guard blocking video and landmark data. `AGENTS.md` makes "never commit raw participant video, biometric-derived samples, consent records, credentials, or identity keys" a hard rule, the guard is a few lines, and the failure it prevents is irreversible — an accidental commit of participant video cannot be undone by deleting the file. The reviewer's own wording ("no-raw-data guard are enough") suggests we are not actually in disagreement here.

# M0 Plan of Record v2 — Consolidated (2 of 3): Amended plan

Parts 1–4 stand except where amended here. Superseded text is marked.

## C1. Cross-platform conformance — replaces D5/E1 (B1, A6, A7, A8)

> **Sequencing amendment:** ADR-0005 permits Android-scoped non-participant study readiness and a
> separately authorised, flagship-only Android Phase 0 pilot before D5/E1. D5/E1 remains mandatory
> before cross-platform claims, pooled Android/iOS datasets, platform comparisons, or Phase 2 entry.
> Mid-range admission remains mandatory before cross-device claims. See
> `docs/adr/ADR-0005-android-scoped-phase-0-evidence.md`.

**Fixture.** Not a video file. A **pre-decoded raw frame sequence** (numbered raw RGB/NV12 dumps with per-frame SHA-256), fed directly into each harness's inference input, bypassing both platform video decoders. The frozen preprocessing contract specifies: pixel format, colour range (full vs video), channel order, rotation handling, resize algorithm and rounding, letterbox padding value and placement, and frame-index→timestamp mapping. This is a **contract artifact in `contracts/`**, not prose in a protocol doc, because two harnesses must implement it identically.

**Procedure, in order:**
1. **Within-platform repeat-run baseline first.** Run the same fixture N≥5 times on the same device/delegate. This measures inference nondeterminism and is the reference for everything after.
2. **Exact checks** (these are legitimately binary): schema validity, metadata completeness, frame count and correspondence, `pose_present` agreement, dropout-run agreement.
3. **Float landmark comparison**, reported as distributions — per-landmark absolute difference at p50/p95/p99/max, in torso-normalised units — and **expressed as a ratio to the step-1 within-platform variance**.
4. **Downstream agreement:** the offline feature pipeline run on both outputs, compared the same way.

**Revised E1:** the conformance report exists, step 1 precedes steps 2–4, exact checks pass, and cross-platform float differences are characterised *relative to* the within-platform baseline. Whether the observed ratio is acceptable is a **written judgement recorded in an ADR**, not a numeric gate. No epsilon is invented.

**Dual-phone capture is reclassified.** It is near-simultaneous different-viewpoint evidence, not a platform comparison — the 15 cm stack confounds body scale, occlusion and perspective. Platform attribution comes solely from C1. Live paired sets additionally require an audible/visible sync event plus offset **and drift** estimation, since monotonic clocks are device-local. Delegate A/B is judged against repeat-run variance, never bitwise equality.

## C2. Contracts — amendments (A2, A3, R1, OQ10, OQ15)

- **Dropped:** schema codegen; enforced CI hash checking. **Kept:** hand-written types validated against schema, recorded hashes + manual verify script, and the pre-commit raw-data guard.
- **Added to `RawPoseFrame v1`:** a separate `debug_features` block holding on-device-computed joint angles. Populated and displayed (satisfying Immediate Instruction item 6), never consumed by analysis, and marked non-authoritative in the schema description itself.
- **Added to `SessionManifest v1`:** a `capture_quality_observed` block of human-checked setup booleans — full body visible at hang, required joints visible, subject scale acceptable, lighting class, visible occlusion — plus a free-text setup failure log. Observations only; no CaptureQualityEngine is built in M0.
- **Added:** `cadence{configured_inference_fps, achieved_inference_fps_timeseries, tracker_update_cadence}` as three distinct fields. Achieved FPS is a time series, not a mean — a mean hides thermal throttling entirely.
- **Added:** `MovementEvent v1` placeholder contract carrying `VALID | INVALID | UNCERTAIN` and a tracking-quality field, unpopulated in M0. This is what OQ10 will eventually be answered against; defining it now costs nothing and prevents the uncertain-event representation from being invented later by whoever writes the UI.
- **Added to D2:** a written schema release/change procedure — how a version is proposed, reviewed, released and deprecated (OQ15's missing half).

## C3. Sequencing — replaces §12 (A4, A5)

```text
Stage 0  ratification of D1+D2; device/model acquisition; decoded-frame fixture set
Stage A  D1 protocols  →  D2 contracts (incl. frozen preprocessing contract)
Stage B  D3 ∥ D4  — parallel ONLY after the preprocessing contract is frozen
Stage C  D5 conformance  →  gate
Stage D  D1 → D6 consent (NOT parallel: wording depends on retention/storage/withdrawal/training-use)
         D1 → D7 annotation tooling + annotator calibration
Stage E  D8 pilot (needs D5 + D6 + D7)  →  D9 capture ;  D13 thermal ∥ D9
Stage F  D9 → export/QC → D10 annotation → D11 analysis → D12 freeze
Stage G  D14 report → D15 handoff update
```

**Corrected critical path:** Stage 0 → D1 → D2 → D3/D4 → D5 → D8 → D9 → export/QC → D10 → D11 → D14.
**Corrected annotation workload:** ~224 reps single-annotated overall; **~72 reps (≈9 held-out sets) dual-annotated + adjudicated.**
**Most-likely-to-slip, in order:** D5 conformance (agreed with the reviewer — it is the deliverable with the least precedent and the most platform surface), then D10/export-QC.

## C4. Metrics — amendments (A11, A12, A13, A1/OQ7)

- **"Static-hang noise floor" is withdrawn.** Replaced by **quasi-static baseline (measurement noise + residual athlete motion)**, computed on a short explicitly-selected stationary window, and never described as pure measurement noise.
- **New near-zero-motion control, at zero set cost:** every set is preceded by a **10-second static reference clip** — subject standing still, same framing, same lighting. This gives a genuine low-motion reference per condition and per device, which is what the hang segment was wrongly being asked to provide. Highest-value change in this revision relative to its cost.
- **Bilateral asymmetry is diagnostic only.** True asymmetry, grip, torso rotation, occlusion, clothing, handedness and landmark bias all contribute. It flags candidate perspective effects for inspection; it does not attribute them.
- **Live vs decimated FPS results are reported in separate tables and never pooled.** The 2 live low-FPS sets can only expose a large contradiction with H5 — that is their stated purpose, and H5 remains open regardless of outcome.
- **New (OQ7):** a **causal** filter sweep — EMA, One-Euro, causal Savitzky-Golay, simple Kalman — over the noise-reduction vs induced-latency trade-off, measured against annotated event times. My original zero-phase filter is retained **only** for offline noise estimation and is explicitly not a runtime candidate.

## C5. Privacy — amendments (A9, A10, M1)

- **Access / data-flow matrix** covering identity mapping, video, landmarks, annotations and aggregates: who can access, where it is stored, encryption at rest and in transit, export protection, audit logging, backup handling, deletion propagation (including backups), and incident handling.
- **Withdrawal mechanism, fully specified:** deletion of source artifacts; a **tombstone** record retaining `session_id` and hashes only; a versioned split amendment; **explicit superseded-marking of any already-published report** that used the withdrawn data; and a stated rerun policy. Without superseded-marking a withdrawal silently makes a published result irreproducible.
- **Training opt-in enumerates artifact types** — video, landmarks, annotations, derived features — and covers future reprocessing, any external sharing, revocation, and the consequences of revocation.
- **No face blurring is recorded as documented risk acceptance**, valid only under: controlled study, no cloud, restricted access, deletion deadline. Reassessed if any of those four stop holding.

# M0 Plan of Record v2 — Consolidated (3 of 3): Open-question status, revised exit criteria, what I need from you

## C6. Open-questions register — explicit status (replaces my earlier prose claim, A1)

| # | Question | M0 status |
|---|---|---|
| 1 | Normalized vs world landmarks | **Answered** — every metric computed both ways |
| 2 | Best camera angle | **Characterised**, not decided — effect estimates only, ranking is descriptive |
| 3 | V1 distance/height | **Characterised**, not decided |
| 4 | Is depth information useful | **Answered** — world-landmark z examined against the same movement |
| 5 | Achievable angle normalisation | **Partially** — bounded by the diagnostic asymmetry probe (C4), not resolved |
| 6 | Required inference FPS | **Characterised** — decimated + 2 live sets; H5 unresolved either way |
| 7 | Smoothing without event delay | **Now addressed** — causal filter latency/noise sweep (C4). Was a genuine gap. |
| 8 | Mandatory joints for a pull-up | **Derivation procedure pre-specified; rule NOT produced in M0** (M2) |
| 9 | When tracking is DEGRADED / LOST | **Derivation procedure + representation pre-specified; rule NOT produced in M0** (M2) |
| 10 | Uncertain reps in the UI | **Deferred** — `MovementEvent v1` placeholder defines the artifact shape only |
| 11 | What raw data to retain locally | **Answered** by the retention section |
| 12 | What may be uploaded, consent model | **Answered** — nothing uploaded at M0; opt-in scope enumerated |
| 13 | Acceptable MVP benchmark metrics | **Metric protocol defined; threshold explicitly deferred** to post-baseline |
| 14 | HoldEvent metrics | **Deferred** — out of M0 scope |
| 15 | Pipeline/model versioning | **Answered** — version tuples + schema release/change procedure (C2) |

OQ8 and OQ9 are the two where I have deliberately declined the reviewer's request for a decision rule. Producing one in M0 would be inventing a threshold before baseline exists, which `AGENTS.md` forbids. M0 fixes the procedure; the M0→Phase 1 boundary instantiates the rule from frozen data.

## C7. Revised exit criteria

- **E1** *(rewritten, B1)* Conformance report exists; within-platform repeat-run baseline measured **first**; exact checks pass; cross-platform float differences characterised as a ratio to that baseline; acceptability recorded as a written ADR judgement, not a numeric gate.
- **E2** ≥25 sets captured, 100 % schema-valid, complete manifests, each with its static reference clip.
- **E3** 100 % of held-out sets dual-annotated (~72 reps) + adjudicated; inter-annotator agreement measured and reported.
- **E4** Analysis pipeline regenerates the report from raw artifacts in one command with pinned versions.
- **E5** `split-v1` frozen; hashes recorded; manual verify script green.
- **E6** Thermal/battery/FPS report for ≥2 device classes × ≥2 FPS settings, with achieved-FPS **time series**.
- **E7** *(rewritten, B2)* Per-condition breakdown for every metric; set-clustered intervals **only where the cell supports one**; automatic minimum-cell-size warnings; `UNMEASURED` printed rather than an unsupported estimate.
- **E8** Written answer or explicit `UNMEASURED` for every row of the C6 table.
- **E9** Evidence-derived **proposed** Phase 2 threshold set + `GO` / `NO-GO` / `CONDITIONAL-GO (restricted envelope)` with stated confidence.
- **E10** Open-questions register updated; every decision has an ADR.
- **E11** *(new, B2)* D15 records an **unseen-athlete holdout as a binding entry condition for Phase 2 acceptance**. This is what makes the M0 gap acceptable rather than permanent.
- **E12** *(new, A14)* Every "passes / strongest / adequate" in the report is either operationalised or labelled descriptive.

## C8. Net effect on scope

Cut: schema codegen, enforced CI hash machinery. Added: decoded-frame fixtures, within-platform variance baseline, static reference clips, causal filter sweep, access/data-flow matrix, withdrawal tombstones, `MovementEvent` placeholder, schema change procedure, Stage 0. **Set count unchanged at 28.** Annotation ask **reduced** — ~72 dual-annotated reps, not ~220. Net: modestly more upfront rigour, materially less downstream machinery, and one genuine correctness fix (decoded-frame replay) that would otherwise have silently invalidated every cross-platform number.

## C9. What I need from you, @sanchit

Ranked by how much they block:

1. **Q1 — RESOLVED.** The instruction to start with the plan authorizes `docs/protocols/` and language-neutral `contracts/` schema design. Application code, dependency manifests, generated projects, validators, and platform builds remain gated until Sanchit explicitly enables implementation.
2. **Q4 — annotators.** Two independent people with enough calisthenics competence to judge kipping and partial ROM. Smaller ask than I first stated (~72 dual-annotated reps), but with only one annotator, E3 and the human noise floor collapse and the benchmark loses its ceiling.
3. **Q2 — devices.** Mid-range Android + flagship Android + iPhone. The mid-range unit must be present from the start.
4. **Q3 — athletes and a fixed-geometry bar** available across multiple days (the anchor replicates need it).
5. **Q5–Q8** — training-data opt-in scope, dataset store location and administrator, measurement equipment (tripod, tape, floor markings, ideally a lux meter), and reps-per-set / to-failure policy.
6. **Q9** — any timeline expectation. I have deliberately proposed no dates.

**Handoff rule preserved throughout: no numeric accuracy threshold is invented anywhere in v1 or v2.** The two places a threshold could have crept in — E1's conformance tolerance and OQ8/OQ9's decision rules — are the two places the review pushed hardest, and both are now resolved without inventing a number.

This reviewed plan is now persisted as the durable basis for Stage 0 and Stage A planning artifacts.

## Post-plan decision amendments

The following accepted ADRs supersede conflicting v2 language for the observation-contract foundation:

- `docs/adr/ADR-0001-observation-before-interpretation.md` defers stable `DerivedFeatures`, `MovementEvent`, and in-frame `debug_features` contracts. OQ10 is therefore **deferred pending Phase 0 evidence**, not answered by a placeholder `MovementEvent v1`. Human annotation still represents `valid`, `invalid`, and `uncertain` explicitly.
- `docs/adr/ADR-0002-canonical-preprocessing.md` freezes the canonical decoded-fixture and live model-input representation while leaving source-format-specific platform vectors to each native-harness milestone.
- `docs/adr/ADR-0003-contract-evolution-and-data-guard.md` establishes independent artifact versioning, Python reference validation, declared-negative fixtures, and repository-local preventive Git hooks.

These amendments narrow interpretation scope without changing M0's study objective, set allocation, evidence requirements, privacy rules, or no-invented-threshold constraint.
