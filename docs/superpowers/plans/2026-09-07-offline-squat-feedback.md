# Offline squat feedback implementation plan

**Goal:** Explain candidate squat cycles on reusable local video without requesting another workout.

**Approved scope:** Owner approved the small offline loop in chat on 2026-09-07. Extend the exploratory track, not formal study admission. Push-ups remain unchanged. Squats first; plank follows after this loop works. No release, upload, automatic camera start or media/landmarks in Git.

**Architecture:** Local source video -> timestamp-preserving pose cache -> Kotlin common measurement/cycle logic -> event comparison against separately authored labels -> local annotated report. Cache metadata binds video and model SHA, extractor version, frame timestamps and coordinate convention. Desktop extraction is reference evidence, not Android runtime equivalence. Use original frame presentation timestamps; reject invalid timing rather than invent FPS. Evaluation labels never enter the detector. Record all prototype configuration explicitly; none is a validated anatomical/form threshold.

**Implementation:** Use regression-first tests, narrow diff inspection and one integration run. No routine independent review round. Reuse the existing isolated movement-viewer worktree. Source edits and synthetic unit tests only in Git; corpus, annotations and reports outside it.

## Tasks

- [x] Add common Kotlin lower-body measurement and explicit-config candidate cycle baseline, without changing arm logic/UI. Add JVM JSONL replay entry point. Test real counting, partial excursions, missing/duplicate/backwards input and geometry; observe RED before implementation.
- [x] Add local corpus/label validation and deterministic event matching/reporting in `tools/offline_movement_feedback.py` and behavioral tests. Enforce source/hash/coordinate binding and development/held-out group separation. Missing annotations means unlabelled, never zero-error success.
- [x] Acquire and independently annotate one verified development sample outside Git (MMFit w00; CC BY 4.0). Inspect actual metadata and frames rather than trust claimed timestamps.
- [ ] Expand to two development clips and a distinct untouched evaluation source before broader parameter tuning. Only one real clip currently exists; no held-out performance claim is permitted.
- [x] Add local extraction/replay command with optional isolated media dependencies, preserve source PTS, and generate overlay/report from cache and Kotlin output. Synthetic unit tests preceded source replay; a full generated-black-video negative control followed it.
- [x] Run focused JVM/Python/static checks, one existing-suite integration run, inspect scope and record exact remaining gaps. Python: 1069 passed; fresh shared JVM: 497 passed; 25 Python and 18 JVM tests for this loop. Android JVM/build gate, Ruff, strict mypy and repository guard passed. Do not claim three verified clips, a held-out score or deployable squat support until actually evidenced.

## Concrete interfaces

Replay input: one JSON object per frame with `timestamp_ns`, `width`, `height`, `landmarks` (list of indexed x/y/z/visibility/presence values in `upright_source_normalized` coordinates). No pose is an empty list. Configuration is a separate JSON object: side (`LEFT`/`RIGHT`), `extended_degrees`, `flexed_degrees`, `minimum_confidence`, `maximum_gap_ns`, `minimum_endpoint_samples`. These parameters are development hypotheses, never supplied by held-out labels.

Replay output: JSONL per input frame with timestamp, usable knee angle or null, phase, count, reason and counted-event flag. Common Kotlin code computes image-plane geometry using source aspect ratio (not square-normalized geometry). The replay adapter must not copy the counter into Python.

Labels: source video SHA and version, explicit annotation provenance/status, ordered independent rep-completion intervals in source nanoseconds. Match prediction completion times one-to-one to intervals; report missed intervals and unmatched predictions. A correct aggregate count can still have both a false positive and a miss.

## Acceptance probes

Synthetic event test: labels at [1s,1.1s] and [3s,3.1s], predictions at 1.05s and 2s => one match, one miss, one extra despite equal totals. Duplicate predictions cannot both match one label. Empty/unverified labels cannot produce a benchmark pass. Cross-split same source group is rejected. Geometry test uses a non-square image so forgetting aspect correction changes a known knee angle. An interrupted squat never stitches across missing input.

## First empirical correction

The initial single-knee baseline counted a unilateral knee lift after the squat set. Both
chains were visible and usable; this was movement ambiguity, not a tracking failure. Add a
regression for a flexing selected leg with a straight opposite leg. Require bilateral flexion
evidence at the bottom, using the same explicit flexion/confidence/sample parameters. Missing
opposite-leg observations invalidate the candidate rather than fabricate support. This baseline
therefore requires both leg chains visible; no side-view/general-recognition claim is made.
Replay the frozen clip/config/annotations unchanged. Preserve the separate first-event timing
finding rather than widen labels after seeing predictions.
