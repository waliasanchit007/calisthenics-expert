# Slice 4 — Android Replay — Implementation Plan

> **For agentic workers:** use `superpowers:test-driven-development` for every behaviour change, and dispatch through the dogfooded Buzz Slice 2 control plane (activate → bind → dispatch → acknowledge → start → implement/commit/push → submit → drain outbox → review).

**Milestone boundary:** This is section 15 **Slice 4** ("Android replay — pinned MediaPipe/model, VIDEO adapter, UI/export, five-run S25 evidence"), following Slice 4a (`2026-08-17-android-storage-composition-prerequisite.md`). It does not renumber or rename any existing slice.

**Goal:** Make one deterministic replay pass authoritative: pinned model provenance, a synchronous MediaPipe VIDEO adapter, fixture rebasing and pacing, the six section 7 determinism checks each shipped with the mutation that must break it, replay UI and export, and the five-run S25 gate landed device-gated without blocking the milestone on hardware.

**Architecture:** Unchanged from ADR-0004 and the Slice 3 decision record. `shared/commonMain` owns timeline, pacing, preprocessing, backend-timestamp construction, and the determinism report; `shared/androidMain` supplies the MediaPipe adapter only. No invariant moves into `androidMain`.

**Authority:** Subordinate to `docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md` section 7 **as amended on 2026-08-18** (session-relative time base, equality set, fresh instance per run, replay fixture tiers). It creates no second design specification.

**Tech Stack:** Kotlin 2.4.10, AGP 9.1.0 KMP `androidLibrary` plugin, MediaPipe Tasks Vision 1.0.0, Gradle 9.3.1, JDK 21/JVM target 17, Python 3.12+ reference validator, GitHub Ubuntu CI, Samsung Galaxy S25 Ultra for the device gate.

**Plan-state convention — deliberate deviation.** This plan maintains its checkboxes. The four earlier plans carry 175 unchecked boxes across three fully delivered milestones, so a reader cannot distinguish "not started" from "done but unticked". Committed progress state is otherwise not derivable from the repository. Recorded here as a change, not applied silently.

## Global Constraints

- Replay pins a **session-relative monotonic base at `t0 = 0`**, applied to every session timestamp — capture rebase *and* inference clock readings. Rebasing capture alone makes `FRAME.INFERENCE_TIME_ORDER` vacuously true.
- Rebased capture timestamps are **inside** the equality set. Session IDs, wall-clock times, and inference latency remain excluded.
- A **fresh `PoseLandmarker` per run**. Otherwise cross-run topology comparison is contaminated by construction.
- Landmark hashes and deltas are **observations**. No tolerance, no invented pass threshold.
- Model binaries are **fetched, never tracked**. The repository guard prohibits `.task`/`.tflite`/`.onnx` anywhere; `.gitignore` alone is directory-scoped and `git add -f` bypasses it.
- Transport is **untrusted by construction**; integrity comes from the pinned SHA-256 and verify-before-move, never from TLS.
- The emulator is never an S25 substitute for the five-run gate.
- Every determinism check ships with the mutation that must break it, verified red.

## The six checks and what each must fail on

| # | Check | Must fail on | Vacuity trap |
|---|---|---|---|
| 1 | identical ordered frame indices submitted | drop-when-late, reorder, dedupe | cross-run comparison is tautological; oracle is the fixture's **declared** list |
| 2 | identical canonical RGB24 plane SHA-256 | buffer reuse, uninitialised padding, float variance | all-zero planes hash identically; needs an **absolute golden** plus non-degeneracy |
| 3 | preprocessing metadata and backend timestamps identical | wall-clock-derived timestamps, mutable geometry | unsatisfiable without the pinned `t0` — see Global Constraints |
| 4 | model, backend, options, delegate pinned | silent GPU fallback, defaulted option, swapped model | asserting the **manifest** proves nothing; assert values recorded at the construction site |
| 5 | output topology and cardinality per frame | nondeterministic detection, tracker leak, unpinned `num_poses` | a 5×1 fixture yields zero poses every frame; needs the **detection tier** |
| 6 | hashes and deltas reported, never thresholded | an added tolerance assertion | negative requirement — unprovable by any passing test; needs a **source scan** |

## Tasks

### Task 1: Deterministic replay substrate — **DELIVERED `ed8feb3`**

- [x] Model provenance: parse, canonical-lowercase SHA validation, fail-closed `verifyModelAsset`, `requireReplayProvenance` (6 tests)
- [x] `ReplayTimeline` + `ReplayPacer`: rebasing, contiguity/monotonicity validation, never-drop-when-late, `inference_start >= rebased capture` (9 tests; drop-when-late and ordering-guard mutations red)
- [x] `ReplayRunner` and checks 1–4 (10 tests; 5 mutations red — dropped frame, corrupted canonical bytes, removed `prev+1` branch, unpinned time base, hardcoded delegate)
- [x] Check 6 source-scan guard, with its own vacuity guard (3 tests; injection into real sources red)
- [x] `Sha256Digest` extracted to `commonMain`; `ScalePadTest` delegates to it
- [x] Detection-tier fixture: 256×256, 6 frames, 1.12 MiB, golden SHAs, same-millisecond collision pair, rendered-primitive provenance, `detection_evidence.status: pending`
- [x] Fetch task with four pins: manifest-sourced URL, temp→verify→move, fetch-time verification retained alongside runtime, `--proto-redir =https`; quarantines a corrupt cache before refetching (6 hermetic tests; quarantine and verify-before-move mutations red)
- [x] Repository guard extended with model-binary suffixes (41 guard tests)
- [x] Fixture verification now **enumerates** `valid/*/fixture_metadata.json` rather than naming directories (10 tests; 3 mutations red)
- [x] Design spec section 7 amended

### Task 2: Model-asset packaging gate — **DELIVERED**

**Pin — these land in ONE commit.** The skip flag and its negative test must never exist apart, even one commit wide: skip-mode leaking into delivery builds is the "zero production callers" pattern wearing a build flag, green in CI while every device build silently lacks its asset.

- [x] Default assembly **requires** the asset present and SHA-verified, failing at **build** time, not only at session start
- [x] Skip is explicit and scoped to the CI/verify variant; never a fallback
- [x] Hermetic negative test: default assembly without the asset fails with the expected error, **no network** — prove the gate rejects, do not prove the happy path downloads
- [x] Both pins recorded in the build-contract test beside the aggregate-target and JVM-non-delivery pins

### Task 3: MediaPipe VIDEO adapter — **DELIVERED**

- [x] `androidMain` `InferencePort` over `PoseLandmarker`, VIDEO running mode, synchronous, CPU delegate
- [x] Fresh instance per run; pinned options recorded at the construction site
- [x] Runtime checksum verification before a session may start; fails closed on mismatch
- [x] Every record asserts `image_width`/`image_height` equal the declared model-input dimensions
- [x] Backend resize and tracker-derived ROI are backend behaviour: record pinned options, do not assert on them

### Task 4: Check 5 and the detection-firing question — **DELIVERED**

- [x] Instrumented run of the detection fixture against the pinned model — real `PoseLandmarker.createFromOptions` and `detectForVideo` on `emulator-5554` (`sdk_gphone16k_arm64`, API 37, arm64-v8a), 3 fresh-instance runs per invocation across 5 separate instrumentation processes; `make verify-harness-detection-replay`
- [x] Record `device_identity`, `api_level`, and `delegate` alongside `detection_evidence` — recorded in `fixture_detection/fixture_metadata.json`, guarded by two new reference tests
- [x] On reliable firing, flip `detection_evidence.status` to `confirmed` and `admissible_for_topology_checks` to `true` — fired on 6/6 frames, 1 pose, 33 normalized + 33 world landmarks, presence fully populated, per-frame observation digests byte-identical across all runs and processes
- [x] Check 5: per-frame pose count, landmark count, presence pattern identical across runs, with its mutation red — `DetectionTopology.kt` in `commonMain` (21 host tests), plus the admission and non-vacuity guards; 4 mutations red
- [x] **If the detector does not fire reliably, iterate the render** — not required: the committed render fired reliably on first execution. The render is unchanged, so the recorded evidence still applies to exactly these pixels; the generator now invalidates recorded evidence automatically if the render ever changes
- [x] Emulator and S25 results are **different observations** — the recorded observation is labelled `device_class: emulator` with `satisfies_s25_device_gate: false`, and a reference test refuses any emulator entry that claims otherwise. No physical S25 was attached; nothing here contributes to the five-run gate, which stays Task 5's
- [x] The device wrapper fails closed on a vacuous run — `make verify-harness-detection-replay` takes its verdict from `tools/check_detection_replay_run.py`, which requires the exact `DetectionTierReplayTopologyTest` suite with all three tests passing and exactly one sanitized `ReplayDetectionEvidence` record. A real `OK (0 tests)` run on the emulator exits 0 from `am instrument` and is rejected
- [x] The `confirmed` admission is bound, not declared — bound to the pinned model SHA-256, the exact Tasks Vision 1.0.0 pin, the committed fixture's frame digests, and a committed sanitized native-run artifact under `docs/evidence/detection-replay/`, by SHA-256 content binding from `fixture_metadata.json`
- **Defect found by the instrumented run:** the adapter handed MediaPipe a heap-backed image buffer, so *every* frame failed as `backend_call_failed` ("Cannot get direct access to the input buffer"). No host test could have caught it. Fixed by copying each canonical plane into a fresh direct buffer, with a host regression that reads the buffer back through `ByteBufferExtractor`.

### Task 5: Replay UI, export, and the five-run device gate — **DELIVERED (S25 evidence PENDING)**

- [x] Fixture selection, run, completion surface (`HarnessUiState`/`HarnessApp` gain `ReplayFixtureOption`/`ReplayRunPhase`); export through the real `SessionExporter` and the real `AndroidStoragePort` via the new `RealReplayComposition` (real MediaPipe VIDEO replay through the Task 3 adapter — `DeterministicCore`/`SyntheticFixture` untouched and still exercised only by the Slice 3 `HarnessComposition` parity/process-kill tests). Verified on `emulator-5554` (`sdk_gphone16k_arm64`, API 37): 6/6 frames submitted and emitted, `FinalizationResult.Published`
- [x] Every export validates against the Python reference CLI — `RealReplayCompositionExportTest` + `make verify-harness-replay-export` (any authorized device, mirrors `verify-harness-detection-replay`'s emulator allowance) pull the on-device export via `adb shell run-as` and run `observation_contracts validate-session`; executed for real, `valid: validate-session`
- [x] Five-run harness and evidence ledger — `FiveRunReplayDeviceGateTest` (5 real replay-through-export cycles in one invocation) + typed `FiveRunReplayEvidence`/`renderFiveRunReplayEvidence` (`shared/commonMain`, computes `satisfies_s25_device_gate` itself — never caller-declared) + `make verify-harness-s25-five-run-replay` (physical-S25-only gate, mirrors `verify-harness-s25-process-kill`) + `tools/check_five_run_replay.py`. Mechanism proved for real on the emulator (5/5 runs consistent, `satisfies_s25_device_gate:false` as required); no physical S25 attached, so `docs/evidence/replay-five-run/s25-ultra-five-run-replay-ledger.json` honestly stays `{"status": "pending", "missing_precondition": "no physical Samsung Galaxy S25 Ultra attached"}`, and `reference/tests/test_device_evidence_ledger.py` enforces that shape inside `make verify`, which runs in CI — pending evidence, never a silent pass
- [x] Five complete runs of the same fixture on the S25 are evidence collection, not an assertion that five proves stability — `renderFiveRunReplayEvidence`'s doc comment and `verify-harness-s25-five-run-replay`'s Makefile comment both say so; the gate requires five consistent, published, CLI-validated runs and nothing stronger

## Environment prerequisites

- `apps/diagnostic-harness/local.properties` with `sdk.dir` is a **per-worktree prerequisite**, correctly gitignored and recreated per worktree. Fix clone-locally; do not widen the check runner's environment allowlist.
- The `androidLibrary` KMP plugin uses `compileAndroidMain`, not `compileDebugKotlinAndroid`.
- TLS is intercepted in the development environment with a self-signed chain; `curl` trusts the system store where Python `urllib` refuses. Do not "fix" a certificate complaint by weakening verification.

## Acceptance

- `make verify` green by **exit code**, never by grepping output — a grep for `passed` matches the pytest summary while Ruff fails below it
- Every determinism check has a recorded mutation that turns it red
- No landmark threshold assertion exists anywhere in the harness tree
- No model binary is tracked
- Device-gated evidence is honestly `pending` or genuinely collected on the S25 — never silently skipped
