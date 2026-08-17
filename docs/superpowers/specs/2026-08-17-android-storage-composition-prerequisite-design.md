# Android Storage and Installable Composition Prerequisite — Design

Status: approved implementation design — the user's milestone brief is approved and authorizes autonomous execution; the mandatory scoped Codex Sol pre-implementation challenge on Task 2 (Section 7) and the milestone-boundary cross-provider review remain implementation gates, not human approval gates

Date: 2026-08-17

Source of truth: the approved milestone brief (`.codex/attachments/93f350b1-c5f6-40da-9221-7ed5fd291c38/pasted-text.txt`), `AGENTS.md`, `docs/central-handoff.md`, ADR-0004, `docs/superpowers/specs/2026-08-16-phase-0-diagnostic-harness-design.md`, and `docs/superpowers/plans/2026-08-17-phase-0-shared-deterministic-core.md`

## 1. Milestone boundary

This milestone does not map onto an existing section 15 slice. It is recorded as **Slice 4a — Android storage and installable composition prerequisite**, a named prerequisite to section 15 Slice 4 ("Android replay — pinned MediaPipe/model, VIDEO adapter, UI/export, five-run S25 evidence").

Slice 4a exists because Slice 4 cannot start truthfully without a real, device-conformant `StoragePort` implementation and a real installable composition path proving the shared deterministic core actually produces identical output off the JVM host. Everything Slice 4a adds — the real port, its conformance evidence, the capability probe, and the synthetic-fixture composition path — is a precondition for wiring MediaPipe/CameraX into a trustworthy export pipeline, not part of that wiring itself.

This boundary decision:

- does **not** renumber section 15's existing slices 1–6;
- does **not** rename Slice 4; Slice 4 keeps its existing definition and scope;
- inserts "4a" purely as a label for this prerequisite work, positioned between the completed Slice 3 (shared deterministic core, merged at `f15337d`) and Slice 4;
- is recorded here and in the implementation plan's title/header, and nowhere else — no other document is edited to introduce this label.

## 2. Goal

Deliver a real, device-conformant Android implementation of the existing 12-method `StoragePort`, prove that implementation against the same conformance evidence the JVM fake already satisfies, produce non-participant capability evidence for the storage primitives on real hardware, wire an installable Android composition path that runs a complete synthetic session end-to-end and proves its export is byte-identical to the JVM-generated export, and land (device-gated, non-blocking) the S25 process-kill regression required by spec section 14.

This is not Slice 4. No MediaPipe, CameraX, YUV acquisition, VIDEO/LIVE_STREAM adapter, or UI expansion beyond what composition strictly requires belongs in this milestone.

## 3. Scope

### In scope

- The real `AndroidStoragePort` implementation in `shared/androidMain`, against the existing 12-method `StoragePort` interface, unchanged.
- A shared, platform-parameterized port conformance suite, run against both the existing JVM fake and the real `AndroidStoragePort`.
- A storage capability probe recording non-participant primitive evidence on the target filesystem.
- An installable composition path: `androidApp` constructs the real port and runs one complete deterministic synthetic session end to end, with export output proven byte-identical to the JVM-generated reference export.
- The device-gated S25 process-kill regression (spec section 14), landed with a typed skip and a CI-visible pending-evidence ledger when no device is attached.
- Two previously deferred Minor debts, cleared before or alongside the adapter: locale-independent canonical number formatting, and JVM toolchain pinning for the `shared` `jvm()` host-test target.

### Out of scope

- MediaPipe, model pinning/provenance, CameraX, YUV acquisition, VIDEO replay, LIVE_STREAM slot behaviour — this is Slice 4 proper.
- Any UI screen, state field, or navigation beyond what is strictly required to trigger and observe the composition path through the existing `composeApp` capability/mode shell (`Capability`, `CapabilityState`, `HarnessMode`, `sessionActive`). No new screens.
- Functional iOS adapters. iOS remains compile/link-valid and non-functional, as ADR-0004 established.
- Any change to the 12-method `StoragePort` interface, the six-step commit sequence, or any other decision the Slice 3 numbered decision record (design spec §10, plan Task 5/6) already settled.
- Storage permissions of any kind. Scoped storage does not affect app-private `filesDir`; `MANAGE_EXTERNAL_STORAGE` and all storage permissions remain absent from the manifest.

## 4. The two Minor debts (must land before or with Task 1)

### 4.1 Locale-independent canonical number formatting

**Risk.** `CanonicalNumber.format` (`shared/src/commonMain/kotlin/.../contract/CanonicalNumber.kt`) currently relies on Kotlin/JVM `Double.toString()`. Per the JLS, `java.lang.Double.toString(double)` is specified to be locale-invariant — it always uses `.` and never groups digits — so the *current* implementation is not actually broken. The debt is that nothing in the repository proves this, and nothing prevents a future change (a `String.format`, a `NumberFormat`, a `"%f"`-style call added near number formatting) from silently introducing locale dependence that a single-host JVM CI run, always executing under the runner's `en_US`/`C` locale, could never observe. Android additionally uses ICU-backed locale data that can differ from the OpenJDK implementation in corner cases the JVM alone cannot exercise.

**Design.** Add a regression test, not a production change, that:

1. Encodes a representative set of doubles through `CanonicalNumber.format` and through full `encodeManifest`/`encodeFrameLine` (reusing the existing RFC 8785 vector fixture, `shared/src/commonTest/resources/contract/number-vectors.json`) under the JVM's ambient default locale.
2. Repeats the same encoding after `Locale.setDefault` is forced to a comma-decimal locale (e.g. `Locale.GERMANY`) and to a non-Latin-digit locale (e.g. `Locale.forLanguageTag("ar-SA")`), restoring the original default locale in a teardown so the mutation never leaks into other tests in the same JVM.
3. Asserts byte-for-byte identical output across all three locale states.

Because `java.util.Locale` is JVM/Android-only, this test cannot live in `commonTest`. It is added twice, deliberately, to close the exact gap the brief names:

- `shared/src/jvmTest` — fast, deterministic, runs in protected Ubuntu CI on every push. Catches a locale-dependence regression immediately.
- `shared/src/androidDeviceTest` (the instrumented Android test source set added by Task 1/2's `androidLibrary { withDeviceTestBuilder {} }` wiring) — runs against the real Android ICU implementation on device/emulator, because a JVM-only test cannot detect an Android-specific ICU divergence, which is precisely the "real device introduces ... variance a single-host run cannot detect" risk the brief names.

No production code changes are anticipated. If either test finds real locale dependence, the fix is scoped to whatever call site the test identifies — most plausibly a future contributor's editing mistake in `CanonicalNumber.kt`, not the current implementation.

### 4.2 JVM toolchain pinning

**Risk.** `shared/build.gradle.kts` declares `jvm { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`. This pins the *bytecode target*, not the *toolchain JDK* Gradle uses to compile and run that target. Without an explicit `kotlin { jvmToolchain(...) }` declaration, Gradle resolves whichever JDK happens to be on the invoking machine's `JAVA_HOME`/`PATH` (subject to its own toolchain auto-detection heuristics). CI happens to pin Temurin 21 in the workflow YAML (`actions/setup-java@v5`, `java-version: "21"`), but that pin lives in CI configuration, not in the build itself — a contributor running `make verify-harness-jvm` locally on a different JDK gets a build that compiles and passes despite not exercising the same toolchain CI exercises, silently weakening the JVM host as an "independent platform check" the design spec's module-shape section (§4) relies on.

**Design.** Add `kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }` to `shared/build.gradle.kts`, reusing the existing version-catalog-accessor pattern already used two lines above it (`libs.versions.android.compileSdk.get().toInt()`). Add a `jdk = "21"` entry to `gradle/libs.versions.toml`, matching the CI workflow's `java-version: "21"`. Extend `reference/tests/test_harness_build_contract.py`'s `EXPECTED_VERSIONS` table and assertions to guard both the catalog entry and the `jvmToolchain(` call site, exactly as it already guards `jvmTarget = JvmTarget.JVM_17`.

This pin is scoped to `shared`'s `jvm()` host-test target only, because that is the specific target the debt names ("The JVM target exists to catch platform divergence"). `androidApp` and `composeApp` are compiled through AGP's own Android toolchain resolution, which is governed by `compileSdk`/`minSdk`/`targetSdk` pins already guarded by the same build-contract test; that is a separate mechanism outside this debt's stated scope and is not touched here.

## 5. AndroidStoragePort (Task 1)

### 5.1 Where Context lives

`Context` never enters `commonMain`. `AndroidStorageRoots.from(context: Context): StorageRoots` is a small `androidMain` factory function that reads `context.filesDir.absolutePath` and derives:

```text
filesDir     = context.filesDir.absolutePath
stagingRoot  = "$filesDir/diagnostic-harness/staging"
exportsRoot  = "$filesDir/diagnostic-harness/exports"
```

matching the layout the design spec (§10) already specifies. `AndroidStoragePort(roots: StorageRoots)` receives an already-constructed `StorageRoots` value — it never receives `Context` directly — so the port class itself stays a plain Kotlin class with only `java.io`/`android.system` dependencies, constructible and testable without an Android `Context` mock. Composition happens once, in the `androidApp` composition layer (Task 4), which is the only place that ever calls `AndroidStorageRoots.from(context)`.

`StorageRoots.init` already enforces "external storage is never a valid root" (both roots must be below `filesDir`, and `filesDir` itself must be an absolute, normalized path) — this existing commonMain invariant is exercised, not re-implemented, by `AndroidStorageRoots.from`.

### 5.2 Primitive implementations

All twelve `StoragePort` methods are implemented with `java.io`/`java.nio.file` and `android.system.Os`, and none of them contain ordering logic:

| Method | Implementation |
|---|---|
| `createStagingAttempt` | `stagingRoot/<sessionId>.<nonce>.staging`; nonce is a monotonically increasing counter scoped to the port instance so concurrent attempts for the same session never collide; creates the directory with `Files.createDirectories`. |
| `appendLine` | `Files.write(path, line.toByteArray(UTF_8), CREATE, APPEND)` on the attempt's `frames.ndjson`. |
| `flush` | delegates to the same `FileChannel`/`FileOutputStream` the append path used; no separate buffering layer is introduced, so flush has real work to do. |
| `fsyncFile` | opens the target file with `StandardOpenOption.WRITE` and calls `FileChannel.force(true)` — the same pattern the existing JVM `FileStorage.fsyncFile` in `GenerateSyntheticExport.kt` already uses, kept identical rather than reinvented, and an equivalent durability guarantee to `fsync(2)` for regular files. `android.system.Os.fsync` is reserved for the directory case below, where no `FileChannel` abstraction exists. |
| `reconcile` | reads back the staged `frames.ndjson`, decodes every line with the existing commonMain `decodeFrame`, and returns line/pose-present counts — identical semantics to `FakeStorage.reconcile` and the JVM `FileStorage.reconcile` used by `GenerateSyntheticExport.kt`. |
| `writeManifest` | writes the encoded manifest string to the attempt's `manifest.json`. |
| `fsyncDirectory` | **exactly** `android.system.Os.open(path, OsConstants.O_RDONLY, 0)` then `Os.fsync(fd)` then `Os.close(fd)`, per spec line 272 and the existing `CameraCapabilityProbe.fsyncDirectory` precedent in `androidApp`. This is the one primitive that cannot be expressed through `java.io`/`java.nio.file`, because the JVM's `FileChannel` API only opens regular files, not directories, for `force()`. |
| `destinationExists` | `Files.exists(Paths.get(destinationPath))`. The explicit pre-check the exporter calls immediately before `renameNoReplace`. |
| `renameNoReplace` | **Corrected by the Task 2 pre-implementation challenge (see §6.1 point 3).** Plain `android.system.Os.rename(attempt.path, destinationPath)` — not `java.nio.file.Files.move`. `Files.move` without `REPLACE_EXISTING` refuses to overwrite an *existing* destination — including an empty one — so it would silently absorb the deleted-precheck mutation the conformance suite exists to catch (§6.1 point 3), making the mutation load-bearing on the fake but inert on Android. POSIX `rename(2)`, which `Os.rename` calls directly, has no such refusal for an empty destination directory, which is exactly the residual-race behaviour §5.3 already documents and depends on. No `ATOMIC_MOVE` option, no retry, no CAS loop, no `REPLACE_EXISTING`-equivalent flag. It is honest about being check-then-rename: the existence check happened one line earlier in `SessionExporter`, not inside this method, and this method makes no atomicity claim of its own. |
| `writeAbortMarker` | writes a sanitized reason string plus the sealed counters to the attempt's abort-marker file. |
| `scanCommittedRoot` | lists `exportsRoot`'s direct children; any directory lacking `manifest.json` is reported as `ExportRootCorruption`, **including an empty directory** — there is no `if (isEmpty) skip` branch anywhere in this method. |
| `scanStagingResidues` | lists `stagingRoot`'s direct children not present in a "renamed" set the port tracks in memory for its own lifetime; classifies each by abort-marker presence: marker present → `Abandoned`, marker absent → `CrashResidue`. |

**The invariant that does not move.** None of the twelve methods above sequences another method, checks a return value from another method to decide whether to call a third, or otherwise encodes "first do X, then Y, then Z." The six-step commit sequence (fsync stream → reconcile → write+fsync manifest → fsync staging directory → check-then-rename → fsync exports root) stays exactly where it already lives: `SessionExporter.finalize` in `shared/commonMain`. `AndroidStoragePort` is a pure primitive implementation; if any method above is found, during implementation, to need to call a second `StoragePort` method to do its job, that is the exact failure mode ADR-0004 and the Slice 3 port-granularity decision (design spec §10, decision 6) exist to prevent, and the correct response is to stop and re-examine the primitive boundary, not to add the call.

### 5.3 Renaming residual race — unchanged

`renameNoReplace` remains honest check-then-rename, exactly as the design spec and Slice 3 decision record already settled (decision 6): POSIX `rename(2)` silently replaces an *empty* destination directory and only fails for a *populated* one, so `SessionExporter`'s existing `destinationExists` pre-check immediately before calling `renameNoReplace` is the actual defense against clobbering a pre-existing empty directory, not an atomicity property of the rename call. `AndroidStoragePort.renameNoReplace` must not attempt `Os.rename` with any flag, retry, or lock that would imply a stronger guarantee than this. The residual race between the pre-check and the rename remains accepted because publish is single-process and dispatcher-confined, per the existing threat model — this design does not revisit that acceptance.

## 6. Port conformance suite (Task 2) — the load-bearing design decision

The existing eleven `SessionExporterTest` probes construct a fresh `FakeStorage()` per test and drive it through `SessionExporter`. Two things about that shape do not survive contact with a real filesystem port unchanged, and resolving them is this section's actual content.

**Reconciled by the mandatory Task 2 pre-implementation Sol challenge (verdict: CHANGES_REQUIRED; record: the run ledger's `port-conformance-challenge.md`).** The challenge adopted four corrections to this section before any Task 2 test/production code was written, folded into §6.1/§6.2 below rather than tracked separately:

1. `AndroidStoragePort.renameNoReplace` must use plain `android.system.Os.rename`, not `java.nio.file.Files.move` (§5.2, §6.1 point 3).
2. `FaultInjectingStoragePort` must preserve `FakeStorage`'s existing ordinal semantics exactly (§6.1 point 1, §6.2).
3. The test-only harness needs a backend-neutral destination snapshot/fingerprint/readback capability, not a new production observation API (§6.1 point 2, §6.2).
4. Shared conformance proves only lexical `filesDir` containment and root distinctness; same-mount identity is Android-specific evidence, proved solely by the concrete Android harness/test (§6.2).

A fifth, non-blocking execution correction — connected instrumentation may not accept Gradle `--tests`; use instrumentation-runner class arguments or the full `connectedAndroidDeviceTest`/`connectedDebugAndroidTest` task — governs Task 2 Steps 3–5's commands and is reflected in the plan, not in this design section.

### 6.1 Why the current shape does not port directly

1. **Fault injection is baked into `FakeStorage` itself.** `FakeStorage.before(operation)` increments an ordinal, records it, and throws when the ordinal matches `failOnOrdinal`/`failOnOrdinals`. A real `AndroidStoragePort` has no equivalent hook — its methods call `Files`/`Os` directly. Fault injection needs to move from "a capability of the fake" to "a capability of the *harness*," so the same fault-sweep tests can drive both a fake and a real port through the identical mechanism. **Ordinal compatibility is exact, not approximate:** `FaultInjectingStoragePort` must count and label precisely the nine operations `FakeStorage.before` already covers — `appendLine`, `flush`, `fsyncFile`, `reconcile`, `writeManifest`, `fsyncDirectory`, `destinationExists`, `renameNoReplace`, `writeAbortMarker` — consuming an ordinal and recording an operation label for each, in that call order, identically to the existing `operations` list shape. `createStagingAttempt`, `scanCommittedRoot`, and `scanStagingResidues` are not part of `SessionExporter`'s six-step commit sequence `FakeStorage.before` ordinal-gates today, so the decorator delegates all three without consuming an ordinal or being eligible to inject a failure on them; promoting the decorator to wrap a real port must not silently widen or narrow which calls are fault-injectable relative to what `FakeStorage` already exercised.
2. **Some scenarios require filesystem state the twelve production methods cannot produce.** "A pre-existing *empty* directory at the destination" is not a state the protocol ever creates (per design spec §10: "the protocol never creates one there"). `FakeStorage.precreateDestination(path, empty = true/false)` is a test-only backdoor into the fake's internal maps. A real port has no equivalent backdoor without one being added deliberately. A second, related gap the challenge surfaced: proving the non-empty-destination-fails-untouched probe (§6.2) on a real filesystem needs a way to read back what is actually at a destination path, independent of the port under test — `FakeStorage`'s existing assertions inspect its own `committedDirectories` map directly, which has no filesystem analogue. `StoragePortTestHarness` therefore also owns a backend-neutral, test-only destination snapshot/fingerprint/readback capability (§6.2) sufficient to prove a populated destination's contents are byte-for-byte unchanged after a failed rename attempt, on both the fake and the real Android harness. This capability lives entirely in `shared/commonTest`/the concrete test harnesses; **no observation method is added to the production `StoragePort` interface** to support it.
3. **The "deleted-pre-check mutation" probe must turn the empty-destination probe RED against both storage backends.** `SessionExporter` is `commonMain` code; deleting its `destinationExists` call is a single edit regardless of which `StoragePort` implementation is injected underneath it — but the approved milestone brief explicitly requires this mutation to be exercised against **both** the fake and the real `AndroidStoragePort` conformance variants, not verified once and assumed backend-agnostic. The shared empty-destination conformance behaviour (the "empty destination refused by the pre-check" probe already codified in `StoragePortConformanceSuite`, §6.2) runs against both backends as a normal, passing test; deleting the production pre-check makes **both** variants fail, because each backend's underlying rename is a plain, unguarded rename — `FakeStorage`'s in-memory move and `AndroidStoragePort.renameNoReplace`'s `android.system.Os.rename`-backed primitive alike — that silently replaces an empty destination directory once the pre-check no longer intercepts it first. This premise is only true because `renameNoReplace` uses `Os.rename` and not `Files.move`: `Files.move` without `REPLACE_EXISTING` already refuses an *existing* destination — empty or not — so it would leave the mutation inert on Android and the probe would stay green with the pre-check deleted, defeating the probe's purpose. §5.2's `renameNoReplace` row is corrected accordingly. This stays practical: it is **one** temporary production mutation, applied once to `SessionExporter`, with both suite variants (`FakeStoragePortConformanceTest`, `AndroidStoragePortConformanceTest`) run against it and reverted afterward — not a mutation-testing framework or tool.
4. **Same-mount identity is not provable by a fake.** `StorageRoots.init` (existing `commonMain` code, unchanged) already enforces the two invariants a fake and a real port share equally: `filesDir` is an absolute, normalized path, and `stagingRoot`/`exportsRoot` are both lexically below it and distinct from each other. Those are the only root-shape claims the *shared* conformance suite may assert, because they are pure string properties any `StorageRoots` value satisfies identically regardless of backend. "Same filesystem mount" is a real Android hazard (design spec §8.3) checked via `android.system.Os.stat(path).st_dev`, which has no meaning for `FakeStorage`'s in-memory paths — a fake asserting same-mount would be asserting a property of a mount that does not exist. That check is therefore verified exclusively where a real mount exists: `AndroidStorageRoots.from`'s own construction-time `st_dev` comparison (design spec §5.1, §8.3, already settled) and the concrete `AndroidStoragePortConformanceTest`/its harness, which may assert same-mount using `AndroidStorageRoots.from` and `st_dev` directly. `StoragePortConformanceSuite`'s shared, platform-neutral root-enforcement probe never claims same-mount evidence, on either backend.

### 6.2 Extraction design

- **`StoragePortTestHarness`** (new, `shared/commonTest`): a small interface owning everything a conformance test needs beyond the twelve production methods —
  - `freshPort(): StoragePort` — a new, empty port instance scoped to the test.
  - `precreateDestination(destinationPath: String, populated: Boolean)` — the one deliberate backdoor, needed only for the destination-collision probes (§6.1 point 2), implemented once per platform (in-memory map mutation for the fake; direct `Files.createDirectory`/`Files.write` for the real Android harness, entirely outside the port under test).
  - `destinationFingerprint(destinationPath: String): DestinationFingerprint?` (or an equivalently named backend-neutral snapshot/readback function; exact shape is a test-only implementation detail, not itself part of the production contract) — returns `null` when nothing exists at the path, and otherwise a value equality-comparable snapshot of the destination's contents (e.g. its manifest and frame-line bytes) sufficient to assert, before and after a failed rename attempt, that the destination was not modified. Implemented once per platform: reading the fake's own `committedDirectories` map for `FakeStoragePortTestHarness`, and reading the real files under the destination path via `java.io.File`/`Files` for the real Android harness — entirely outside the port under test, exactly like `precreateDestination`. This is a test-only capability of the harness, never a method added to production `StoragePort`.
  - `dispose()` — deletes any real filesystem state a real-port harness created, so instrumented test runs do not accumulate residue across invocations.
- **`FaultInjectingStoragePort`** (new, `shared/commonTest`): a `StoragePort` decorator that wraps *any* delegate — fake or real — incrementing an ordinal and recording an operation label before delegating each call, and throwing before delegating when the ordinal matches an injected failure set. This is `FakeStorage.before()` promoted out of the fake and generalized to wrap a real port identically. Because the throw happens before delegation, "ordinal N fails" means exactly what it already means in the existing tests: the underlying primitive's side effect never happens for that call. **Ordinal/operation-label mapping is exact** (§6.1 point 1): `appendLine`, `flush`, `fsyncFile:<file>`, `reconcile`, `writeManifest`, `fsyncDirectory:<staging|exports>`, `destinationExists`, `renameNoReplace`, `writeAbortMarker` consume ordinals and are injectable, preserving the existing label strings `FakeStorage.operations` already produces (so `fixedCommitOrderIsObservable`'s expected list is unchanged); `createStagingAttempt`, `scanCommittedRoot`, `scanStagingResidues` delegate directly, consuming no ordinal and never eligible for injected failure.
- **`StoragePortConformanceSuite`** (new, `shared/commonTest`, abstract): one abstract test class taking `abstract fun harness(): StoragePortTestHarness`, containing every port-level probe the brief enumerates:
  - fault sweep at each of the nine fault-injectable primitives (via `FaultInjectingStoragePort` wrapping `harness().freshPort()`, sweeping every ordinal in one export attempt, exactly as `everyOrdinalFaultPreventsValidPublishExceptReportsUncertainDurabilityAfterRename` already does against the fake);
  - non-empty destination fails untouched — asserted via `harness().precreateDestination(..., populated = true)` and a `harness().destinationFingerprint(...)` comparison taken before and after the failed export, proving byte-for-byte non-modification on both backends (§6.1 point 2) — and empty destination refused by the pre-check (via `harness().precreateDestination(..., populated = false)`);
  - abort-marker-present (`Abandoned`) versus abort-marker-absent (`CrashResidue`) classification;
  - corrupt-export-root detection, including an empty directory;
  - unique staging path per attempt, and permanent abandonment of a failed attempt (never re-entered);
  - `filesDir` root enforcement: lexical containment (both roots below `filesDir`) and root distinctness only, exercised through `StorageRoots.init`, which is identical code regardless of backend. **Same-mount identity is deliberately excluded from this shared probe** (§6.1 point 4) — it is proved solely by the concrete `AndroidStoragePortConformanceTest`/its harness via `AndroidStorageRoots.from` and `st_dev`, never claimed against the fake.
- **`FakeStorage`** (modified): stripped of its own `failOnOrdinal`/`failOnOrdinals`/`dropAppends`/`precreateDestination` machinery, now a pure in-memory `StoragePort` implementation with no test-only surface beyond what `StoragePortTestHarness` needs.
- **Two concrete suites:**
  - `FakeStoragePortConformanceTest : StoragePortConformanceSuite()` in `shared/jvmTest`, wired to `FakeStorage` — fast, runs in protected Ubuntu CI on every push.
  - `AndroidStoragePortConformanceTest : StoragePortConformanceSuite()` in `shared/androidDeviceTest` (the instrumented source set Task 1 adds), wired to real `AndroidStoragePort` rooted at the instrumented test's own `context.filesDir` — runs on device/emulator via `connectedAndroidDeviceTest`/`connectedDebugAndroidTest` (confirm the exact task name against the installed AGP KMP plugin; do not assume Gradle `--tests` filtering is honoured for this connected task — invoke the full task, or pass class names through the Android instrumentation runner's own `-e class` argument, not `--tests`), not in Ubuntu CI. This same suite is also where the Android-only same-mount assertion from §6.1 point 4 lives, as an additional test method beyond the shared abstract class's probes.
- **`SessionExporterTest`** (modified): keeps only exporter-level assertions that are not port-conformance probes in the sense above — the fixed operation-order sequence (`fixedCommitOrderIsObservable`), the second-finalize-joins-first-result idempotency check, and sanitized-error-detail assertions. These stay JVM/fake-only because they characterize `SessionExporter`'s own behaviour, not the storage backend's. The pre-check mutation check is separate and is **not** JVM/fake-only: per §6.1 point 3, it is the empty-destination-refused-by-precheck probe already present in `StoragePortConformanceSuite` above, exercised against **both** concrete suites — `FakeStoragePortConformanceTest` and `AndroidStoragePortConformanceTest` — with `SessionExporter`'s `destinationExists` pre-check temporarily deleted, confirming both suite variants turn RED before the mutation is reverted.

### 6.3 What this design change proves, and what it deliberately does not claim

Running the identical `StoragePortConformanceSuite` against both backends proves the *port contract* — not merely the fake — is satisfied identically by real Android primitives. It does **not** prove crash-time atomicity of any individual `Os.fsync`/rename call (no in-process test can observe that; that is exactly what Task 3's capability probe and Task 5's process-kill test exist to approach from different angles), and it does not claim the fault-injection ordinals map onto genuine hardware failure modes — they are a deterministic proxy for "this call did not complete," which is the property `SessionExporter`'s ordering logic actually depends on.

**Mandatory pre-implementation gate.** Because this is the one design boundary in this milestone where fake-versus-real divergence can hide silently — a probe that passes against `FakeStorage` and is never actually exercised against `AndroidStoragePort` is precisely the "validated function with zero production callers" shape the brief calls out — Task 2 receives a scoped Codex Sol challenge *before* implementation, specifically on this Section 6.2 extraction design: whether `FaultInjectingStoragePort` wrapping a real port actually forces the same code paths the fake's inline fault injection forced, whether `precreateDestination` as a harness-only backdoor is a legitimate substitute for a state the protocol cannot otherwise produce, and whether any probe in the brief's required list (fault sweep, destination cases, abort/crash classification, corruption scan, staging uniqueness, `filesDir` enforcement) is unwritable against the real harness as designed here. Tasks 1, 3, 4, 5 receive no such challenge; their design is settled by this document and the adopted Slice 3 decision record.

## 7. Storage capability probe (Task 3)

Spec line 272 requires a capability probe verifying the primitives on the target filesystem, "as non-participant evidence" — the same category the existing `CameraCapabilityProbe` (`androidApp/src/main/kotlin/.../probe/CameraCapabilityProbe.kt`) already establishes for camera/timestamp/colour metadata. The storage capability probe is a new sibling in the same package and evidence shape, not a `shared` component — like the camera probe, it is Android-application-level non-participant tooling, not core business logic:

- `androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/StorageCapabilityProbe.kt`
- `androidApp/src/main/kotlin/com/calisthenics/diagnostic/probe/StorageProbeResult.kt`

### 7.1 What it exercises

Constructed with the real `AndroidStorageRoots.from(context)` and a scratch `AndroidStoragePort` rooted there (never the production session's own roots, so the probe cannot corrupt a real session):

1. `appendLine` + `flush` + `fsyncFile` succeed on a throwaway staging attempt.
2. `fsyncDirectory` succeeds on that attempt's staging directory (`Os.open`/`Os.fsync`, per spec line 272).
3. `renameNoReplace` succeeds renaming that attempt within `filesDir` (staging → a scratch destination under the probe's own subtree, never the real `exportsRoot`).
4. A second rename attempt, targeting a destination pre-populated with a manifest, **fails**.
5. A third rename attempt, targeting a pre-existing **empty** destination directory, is refused by the `destinationExists` pre-check before any rename is attempted — mirroring exactly the exporter's own defense, proving the probe validates the same pre-check the production code path relies on, not a different one.

### 7.2 Evidence record shape

`StorageProbeResult` mirrors `ProbeResult`'s existing shape and canonical-JSON conventions:

```text
device_model, os_build, filesystem_type (nullable — read from /proc/mounts, matching the mount
    entry whose mount point is the longest prefix of the resolved filesDir path, and taking its
    filesystem-type column; android.os.StatFs exposes block/space accounting only, not filesystem
    type, so it is not used for this field; null, not a fabricated value, where /proc/mounts is
    unreadable or no matching entry is found),
probe_git_sha, append_flush_fsync_supported, directory_fsync_supported,
rename_within_filesdir_supported, rename_onto_populated_destination_rejected,
rename_onto_empty_destination_refused_by_precheck
```

The record explicitly states, in a fixed comment/field alongside it (mirroring how `ProbeResult.isStorageAdmissible()`/`isLiveAdmissible()` already read as derived booleans rather than raw claims), that this probe verifies the *calls succeed*, not crash-time durability: an `fsync` returning success is evidence the kernel accepted the durability request, not proof the underlying storage survives a real power loss or `SIGKILL` at that instant. That distinction is the one the brief requires be stated rather than implied, and it is why Task 5's process-kill test exists as a separate, harder form of evidence — the capability probe and the process-kill test answer different questions and neither substitutes for the other.

Evidence lands at `docs/evidence/device-probes/s25-ultra-storage-capability.json` when a device run succeeds, following the exact convention `docs/evidence/device-probes/s25-ultra-android-16.json` already established for the camera probe. If no device is connected, the probe code is committed without fabricated evidence, matching the same precedent.

## 8. Installable composition path (Task 4)

### 8.1 Reusing the existing synthetic fixture, not inventing a new one

`shared/src/jvmTest/kotlin/.../contract/GenerateSyntheticExport.kt` already builds exactly the fixture this task needs: a fixed `SESSION_ID`, a `syntheticManifest()` whose `device` field is a deliberately synthetic literal (`Device("android", "synthetic-os-1", "synthetic-kotlin-device", ...)` — already labelled "android" even though that file runs on the JVM, precisely so the manifest is platform-invariant and comparable), and a fixed 2×2 RGB24 source buffer. This is promoted, unchanged in value, out of `jvmTest`-private scope into a new `shared/commonMain` fixture:

- `shared/src/commonMain/kotlin/com/calisthenics/diagnostic/conformance/SyntheticFixture.kt` — exposes the session ID, the manifest template, and the source RGB24 bytes/dimensions as public constants/functions, documented as conformance-only, never participant data.

`GenerateSyntheticExport.kt` is modified to consume `SyntheticFixture` instead of its private duplicate, so the JVM-generated reference and the Android-generated comparison are constructed from one literal source, not two copies that could drift.

### 8.2 Composition root

- `androidApp/src/main/kotlin/com/calisthenics/diagnostic/composition/HarnessComposition.kt` — constructs `AndroidStorageRoots.from(context)`, a production `AndroidStoragePort` rooted there, and exposes `fun runDeterministicSession(): DeterministicCoreResult`, which builds a `DeterministicCore` (existing `shared/commonMain` class, already used by `GenerateSyntheticExport.kt` and `DeterministicCoreTest`) from `SyntheticFixture`'s configuration/manifest and the real port, and calls `.run(sourceRgb24, sourceWidth, sourceHeight)`.
- `MainActivity.kt` is modified only to: (a) populate the existing `STORAGE_DURABILITY` capability slot from a real probe result (Task 3's `StorageCapabilityProbe`/`isStorageAdmissible()`) instead of the current `CapabilityState.Unknown("storage probe pending")` placeholder, and (b) wire the existing `HarnessMode.REPLAY` start affordance (already gated by `canStart`/`sessionActive` in `shared/commonMain/.../session/SessionTypes.kt`) to call `HarnessComposition.runDeterministicSession()` off the main thread. No new screen, no new `HarnessUiState` field, no new capability ID is introduced — this is composition wiring of state and actions that already exist, which is the explicit boundary the brief draws between "composition" and "UI expansion."

### 8.3 Direct-boot and same-mount fail-closed handling live here

`AndroidStorageRoots.from(context)` is the single call site that can observe both Android hazards named in the brief, so both are handled at construction, before any `AndroidStoragePort` is created:

- **Direct boot.** Before first unlock, `context.filesDir` is credential-encrypted storage and may be unavailable. `AndroidStorageRoots.from` catches the specific failure Android surfaces for this state (an inaccessible/unavailable `filesDir`, not a generic `IOException` swallowed silently) and returns a typed result — `Result<StorageRoots>`-shaped, or a small sealed `StorageRootsResult { Ready(StorageRoots) | Unavailable(reason: String) }` — rather than throwing an unhandled exception up through `HarnessComposition`. `MainActivity` surfaces `Unavailable` through the existing `CapabilityState.Unavailable(reason)` vocabulary on the `STORAGE_DURABILITY` slot, which already disables session start via `canStart` with no new state machine needed.
- **Same mount.** `AndroidStorageRoots.from` confirms staging and exports resolve to the same filesystem mount as `filesDir` at construction time by comparing `android.system.Os.stat(path).st_dev` for `filesDir` and the two derived roots' parent directories — identical `st_dev` is the standard POSIX definition of "same mounted filesystem," and is what actually determines whether a plain `rename(2)` across those paths can succeed without crossing a device boundary. (Android does not remap `filesDir` across mounts under ordinary operation, but this check makes the assumption verified rather than merely trusted.) It fails closed with the same `Unavailable` shape if the comparison does not hold, rather than constructing a `StorageRoots` that would later fail confusingly deep inside an export attempt.

Both failure paths produce a typed, observable capability state; neither throws past the composition boundary, and neither is a silent fallback to a different root.

### 8.4 Byte-identity acceptance test

`androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/composition/InstallableCompositionExportParityTest.kt` runs `HarnessComposition.runDeterministicSession()` on-device (instrumented, real filesystem, real `Os` calls) and asserts the resulting `manifest.json`/`frames.ndjson` bytes are identical to the JVM-generated reference. The comparison happens entirely on-device, with no `adb pull`/external storage/new permission involved: a Gradle task (`copyJvmSyntheticExportFixture` in `androidApp/build.gradle.kts`, depending on `:shared:generateSyntheticExport`) copies `shared/build/conformance/synthetic-session/{manifest.json,frames.ndjson}` into an `androidTest` asset directory before the instrumented test runs; the test reads those bundled bytes via `InstrumentationRegistry`'s test context assets and compares them to the bytes it just produced on-device.

Any byte divergence is a blocking finding for this milestone, not a tolerance — per the brief, it would mean the shared deterministic core is not actually shared. A new Makefile target, `verify-harness-android-export-parity`, requires exactly one connected device (mirroring the existing S25 probe device-check convention) and is not part of protected Ubuntu CI, for the same reason `connectedDebugAndroidTest` never is.

`:androidApp:assembleDebug` already exists as a `verify-harness-jvm` dependency; no new Gradle wiring is needed to keep producing an installable debug APK — this task's job is to make sure that APK actually contains a working composition, which the instrumented test above proves.

## 9. S25 process-kill test (Task 5, device-gated, non-blocking)

### 9.1 Two-invocation design

A process that kills itself mid-export cannot also report a JUnit result for that same test invocation — the instrumentation process is gone. The test is therefore two separate `am instrument` invocations against the same on-device staging/exports roots, run in sequence by a driver script, not two methods relying on in-process continuation:

1. **Self-kill invocation** (`androidApp/src/androidTest/kotlin/com/calisthenics/diagnostic/export/ProcessKillDuringExportTest.kt`, method `killDuringCommit`): starts a real export through `HarnessComposition`'s port, wrapped by a `FaultInjectingStoragePort`-shaped decorator whose injected "fault" at a chosen ordinal (after some primitives have run, before the commit completes) is `Process.killProcess(Process.myPid())` instead of a thrown exception. This invocation is *expected* to end with the instrumentation process dying mid-run; the driver script tolerates that non-zero/aborted result rather than treating it as the probe's failure.
2. **Verification invocation** (same file, method `verifyNoPartialEntryAndResidueClassification`, run as a fresh process in a second `am instrument` call): scans the exports root left behind by invocation 1 and asserts it is either empty or contains only fully valid, manifest-bearing sessions — never a partial or corrupt entry — via the same `scanCommittedRoot` the conformance suite already exercises. It then scans staging residues and asserts the abandoned attempt from invocation 1 classifies as `CrashResidue` (no abort marker was written, because the kill happened before `SessionExporter`'s abandonment path had a chance to run), extending the section 14 check exactly as the brief requires ("extended to assert staging-residue classification").

### 9.2 Device-gated, non-blocking, and never silently absent

The Makefile target `verify-harness-s25-process-kill` requires exactly one connected S25-matching device (the same `adb devices -l` convention the existing camera-probe device gate uses) and runs both invocations in sequence. It is never invoked from protected Ubuntu CI, which has no attached device — this is the same structural gate the existing camera/storage probes already use, and by itself satisfies "must not block the milestone on hardware."

The brief asks for something the existing camera-probe precedent does not yet provide: CI must *report* the missing evidence as pending, not simply say nothing. This is made concrete and CI-checkable rather than aspirational: a tracked ledger file, `docs/evidence/device-probes/s25-ultra-process-kill-ledger.json`, records:

```json
{"status": "pending", "missing_precondition": "no S25 Ultra attached"}
```

or, once a device run succeeds:

```json
{"status": "recorded", "evidence_file": "s25-ultra-process-kill.json"}
```

A new Python regression test (extending `reference/tests/test_harness_build_contract.py` or a small sibling file) asserts this ledger file exists and its `status` is one of exactly `"pending"`/`"recorded"`, with `missing_precondition` required and non-empty when `"pending"`. This test runs in `make verify`, in protected Ubuntu CI, on every push — so CI always visibly reports the evidence state, and a future contributor deleting or forgetting the ledger file breaks the build rather than silently losing the pending marker. This is the mechanism that turns "device unavailable" into a permanently visible, CI-enforced pending state instead of a silent skip, without making milestone completion depend on hardware being attached.

## 10. Review, routing, and dogfood constraints (encoded here for this milestone; not a change to AGENTS.md)

- **No routine per-task review.** Tasks 1, 3, 4, and 5 receive no independent model review; deterministic tests, static checks, diff/scope inspection, and CI carry acceptance, exactly as the milestone's operating gate already establishes for routine implementation.
- **Task 2 alone gets a mandatory scoped Codex Sol pre-implementation challenge**, on the extraction design in §6.2 — not on Tasks 1, 3, 4, or 5, whose design this document and the adopted Slice 3 decision record already settle.
- **One unconditional full-range review at the milestone boundary**, assigned to a provider that implemented nothing in this milestone. Dual-provider routing applies: if Codex implemented, Claude reviews; if Claude implemented, Codex Sol reviews. No same-tier self-review for a triggered review.
- Only Critical/Important findings block; one review plus one scoped re-review is the maximum; Minor findings are batched as debt, not fixed inline.
- **Dogfooded through the Slice 2 control plane.** Task dispatch, acknowledgement, and review handoff for this milestone go through the same Buzz orchestration path Task 0 of the prior plan first dogfooded — that mechanism is already established and is not re-derived here. A recovery cycle is any manual intervention outside that documented happy path; the milestone-wide budget is one recovery cycle, cumulative across every task in this plan (not per task) — a second aborts the dogfood run for this milestone, exactly as the brief states, and requires completing the affected task outside Buzz so Calisthenics is never stranded behind the dogfood.
- **Routing and telemetry.** Every routing decision — provider, model, account identity, tokens in/out, wall-clock duration — is recorded in the ignored run ledger, exactly as the prior plan's Delivery and Review Protocol requires. Missing telemetry is recorded as a typed `unavailable`, never an estimate. No provider account or credential identity is encoded in any committed identifier (branch name, commit, filesystem path).
- **Never use or propose paid credits.** No recurring macOS/paid CI runner is added; the existing local-iOS-evidence-in-PR pattern from the diagnostic harness design (§14) is unaffected and untouched by this milestone.

## 11. Stop and falsification conditions specific to this milestone

- If any required conformance probe (§6.2) cannot be written against the real `AndroidStoragePort` harness as designed, that is a finding to escalate during the Task 2 Sol challenge, not an exemption to note and move past.
- If the Task 4 byte-identity comparison (§8.4) diverges for any reason, that is a blocking finding for the whole milestone — the composition path does not ship with a documented tolerance, and Slice 4 does not start until the divergence is root-caused and resolved.
- If direct-boot or same-mount detection (§8.3) cannot be exercised deterministically in an instrumented test (because neither condition can be forced without genuinely rebooting or remounting a device), that gap is recorded explicitly as a known evidence limitation in the milestone report, not silently assumed correct from code inspection alone.
- If the Task 5 self-kill invocation does not reliably leave the process-killed state the verification invocation expects (for example, if `Process.killProcess` races with `SessionExporter` far enough to always land past the commit point), that is reported as a design gap in the milestone report and the ordinal is adjusted, not the assertion weakened.
