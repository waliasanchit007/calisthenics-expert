PYTHON ?= $(shell for candidate in python3.14 python3.13 python3.12 python3; do \
	if command -v "$$candidate" >/dev/null 2>&1 && "$$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; then \
		printf '%s' "$$candidate"; exit 0; \
	fi; \
done; printf '%s' python3)
VENV_PYTHON := .venv/bin/python
CLI := $(VENV_PYTHON) -m observation_contracts

.PHONY: bootstrap verify verify-harness-jvm verify-harness-ios verify-harness-foundation verify-kotlin-export verify-harness-android-export-parity verify-harness-detection-replay verify-harness-s25-process-kill verify-harness-replay-export verify-harness-s25-five-run-replay fetch-model-asset prepare-harness-gradle-dependencies

bootstrap:
	@command -v "$(PYTHON)" >/dev/null 2>&1 || { echo "error: Python interpreter '$(PYTHON)' was not found" >&2; exit 1; }
	@$(PYTHON) -c 'import sys; sys.exit(f"error: Python >= 3.12 is required, got {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}") if sys.version_info < (3, 12) else None'
	@if [ -x "$(VENV_PYTHON)" ] && ! "$(VENV_PYTHON)" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; then \
		echo "error: existing .venv uses an unsupported Python interpreter; remove .venv and rerun make bootstrap" >&2; \
		"$(VENV_PYTHON)" --version >&2 || true; \
		exit 1; \
	fi
	@if [ ! -x "$(VENV_PYTHON)" ]; then $(PYTHON) -m venv .venv; fi
	$(VENV_PYTHON) -m pip install --upgrade pip
	$(VENV_PYTHON) -m pip install -e 'reference[dev]'
	git config --local core.hooksPath .githooks

fetch-model-asset:
	$(VENV_PYTHON) tools/fetch_model_asset.py

# The cold-runner preparation seam for `make verify`.
#
# `make verify` runs reference/tests/test_model_asset_build_gate.py, which drives the real Gradle
# task graph with `--offline`. That flag is load-bearing and is never relaxed: it is what proves the
# delivery gate rejects a missing model asset with no network path able to fetch one. It also
# forbids resolving the Android Gradle Plugin, so on a clean CI runner with an empty Gradle cache
# the build dies at "Plugin [id: 'com.android.application', version: '9.1.0'] was not found" and
# every one of those tests fails for a reason that has nothing to do with the gate.
#
# This target populates that cache with one online invocation, and CI runs it before `make verify`
# (.github/workflows/verify.yml, pinned by reference/tests/test_ci_gradle_cache_ordering.py). It is
# deliberately NOT a prerequisite of `verify`: `verify` must stay runnable with no network on a warm
# machine, and coupling the two would smuggle a network dependency into the hermetic suite.
#
# It assembles only `jvmVerification` — the mechanically distinct non-delivery variant that is the
# gate's sole exclusion. The pinned model asset is never fetched here and no skip-shaped property is
# ever passed, so the gate a cold runner faces is exactly the gate a device build faces, and warming
# the cache can never become a way to package a delivery APK without the verified asset.
prepare-harness-gradle-dependencies:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleJvmVerification

verify-harness-jvm:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest :androidApp:assembleJvmVerification

verify-harness-ios:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:linkDebugTestIosArm64 :shared:iosSimulatorArm64Test :composeApp:linkDebugTestIosArm64 :composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64
	test -d apps/diagnostic-harness/composeApp/build/bin/iosArm64/debugFramework/DiagnosticHarness.framework
	test -d apps/diagnostic-harness/composeApp/build/bin/iosSimulatorArm64/debugFramework/DiagnosticHarness.framework

verify-harness-foundation:
	$(MAKE) verify-harness-jvm verify-harness-ios

verify-harness-android-export-parity:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2=="device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-android-export-parity requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:generateSyntheticExport :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.calisthenics.diagnostic.composition.InstallableCompositionExportParityTest

# Device-gated: the detection-tier instrumented replay run (design spec §7, check 5 and the
# fixture's admission criterion). This is the only place `PoseLandmarker.createFromOptions` and
# `detectForVideo` actually execute, so it is the only thing that can show the pinned model fires on
# the synthetic render and that output topology repeats across runs.
#
# An emulator IS an authorized target here, unlike verify-harness-s25-process-kill. That target's
# refusal of emulators belongs to the five-run S25 gate; this observation answers a different
# question — whether the render fires the detector at all — and design §7 treats the two as separate
# observations rather than substitutes. The observed device class, model, and API level are printed
# precisely so recorded evidence can never be transcribed against the wrong device.
#
# `am instrument` reports test failure in its output while still exiting 0, so the transcript is
# the load-bearing signal — but reading it for an "OK (" line is not a check. `OK (0 tests)` is
# exactly what a mistyped class name produces, a run of some unrelated suite prints the same line,
# and neither says anything was observed. The verdict is therefore delegated to
# tools/check_detection_replay_run.py and taken from its exit status: it requires the exact
# DetectionTierReplayTopologyTest suite with all three of its tests passing, exactly one sanitized
# ReplayDetectionEvidence record emitted by that run, and that record bound to the pinned model
# SHA-256, the pinned Tasks Vision version, and the committed fixture's frame digests. Its
# regressions are hermetic and host-side, in reference/tests/test_detection_replay_evidence.py.
#
# The device-test APK carries the SHA-verified model asset and the committed fixture planes, staged
# by :shared:stageDeviceTestReplayResources, which fails closed if the asset was never fetched.
verify-harness-detection-replay:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2=="device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-detection-replay requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2=="device" {print $$1}'); \
	printf 'detection-tier replay target: serial=%s model=%s api=%s abi=%s\n' \
		"$$serial" \
		"$$(adb -s "$$serial" shell getprop ro.product.model | tr -d '\r')" \
		"$$(adb -s "$$serial" shell getprop ro.build.version.sdk | tr -d '\r')" \
		"$$(adb -s "$$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"; \
	case "$$serial" in \
		emulator-*) echo "note: emulator observation; this does NOT satisfy the five-run S25 Ultra device gate" ;; \
	esac; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:assembleAndroidDeviceTest; \
	adb -s "$$serial" install -r -t shared/build/outputs/apk/androidTest/shared-androidTest.apk; \
	transcript=$$(mktemp); \
	trap 'rm -f "$$transcript"' EXIT INT TERM; \
	adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.mediapipe.DetectionTierReplayTopologyTest com.calisthenics.diagnostic.shared.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; \
	cat "$$transcript"; \
	cd ../.. && $(VENV_PYTHON) tools/check_detection_replay_run.py "$$transcript"

# Task 5: the real replay UI path — real MediaPipe VIDEO replay, real SessionExporter, real
# AndroidStoragePort — end to end, on any authorized attached device (an emulator is authorized
# here, exactly like verify-harness-detection-replay; the S25-only gate lives in
# verify-harness-s25-five-run-replay below). Every export must validate against the Python
# reference CLI, and the CLI cannot run on-device, so the wrapper pulls the export via
# `adb shell run-as` (a debug build) and validates it on the host.
verify-harness-replay-export:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2=="device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-replay-export requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2=="device" {print $$1}'); \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	adb -s "$$serial" shell pm clear com.calisthenics.diagnostic.android; \
	transcript=$$(mktemp); \
	trap 'rm -f "$$transcript"' EXIT INT TERM; \
	adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.composition.RealReplayCompositionExportTest com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; \
	cat "$$transcript"; \
	cd ../.. && $(VENV_PYTHON) tools/check_real_replay_export.py "$$serial" com.calisthenics.diagnostic.android "$$transcript"

# The five-run S25 device gate (design spec §7 / Task 5 brief). Device-gated exactly like
# verify-harness-s25-process-kill: requires exactly one authorized physical Samsung Galaxy S25
# Ultra, never a generic emulator, and never mutates the pending/recorded ledger itself — the
# ledger at docs/evidence/replay-five-run/s25-ultra-five-run-replay-ledger.json is only ever
# updated by a human copying the sanitized record this target prints. Five complete runs of the
# same fixture are evidence collection, not proof of stability: see FiveRunReplayDeviceGateTest.
verify-harness-s25-five-run-replay:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2=="device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-s25-five-run-replay requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2=="device" {print $$1}'); \
	case "$$serial" in \
		emulator-*) \
			echo "error: attached target '$$serial' is an emulator; only a physical Samsung Galaxy S25 Ultra is an authorized target for this gate" >&2; \
			exit 1; \
			;; \
	esac; \
	manufacturer=$$(adb -s "$$serial" shell getprop ro.product.manufacturer | tr -d '\r'); \
	manufacturer_lower=$$(printf '%s' "$$manufacturer" | tr '[:upper:]' '[:lower:]'); \
	marketing_name=$$(adb -s "$$serial" shell getprop ro.config.marketing_name | tr -d '\r'); \
	model=$$(adb -s "$$serial" shell getprop ro.product.model | tr -d '\r'); \
	model_upper=$$(printf '%s' "$$model" | tr '[:lower:]' '[:upper:]'); \
	if [ "$$manufacturer_lower" != "samsung" ]; then \
		echo "error: attached target '$$serial' has manufacturer '$$manufacturer', not the authorized Samsung Galaxy S25 Ultra" >&2; \
		exit 1; \
	fi; \
	authorized=0; \
	case "$$model_upper" in SM-S938*) authorized=1 ;; esac; \
	case "$$marketing_name" in *"S25 Ultra"*) authorized=1 ;; esac; \
	if [ "$$authorized" -ne 1 ]; then \
		echo "error: attached target '$$serial' model '$$model' marketing name '$$marketing_name' does not match the authorized Samsung Galaxy S25 Ultra" >&2; \
		exit 1; \
	fi; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	adb -s "$$serial" shell pm clear com.calisthenics.diagnostic.android; \
	transcript=$$(mktemp); \
	trap 'rm -f "$$transcript"' EXIT INT TERM; \
	adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.composition.FiveRunReplayDeviceGateTest com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; \
	cat "$$transcript"; \
	cd ../.. && $(VENV_PYTHON) tools/check_five_run_replay.py "$$serial" com.calisthenics.diagnostic.android "$$transcript"

# Device-gated per design spec §9.2: requires exactly one authorized physical Samsung Galaxy S25
# Ultra target, never a generic emulator, and never mutates the pending/recorded ledger itself —
# the ledger is only ever updated by a human copying sanitized on-device evidence (task brief
# Step 5). Two separate `am instrument` invocations, not `connected...AndroidTest --tests`, which
# this project has already proven unreliable for selecting a single method.
#
# Evidence freshness and run-correlation: after installing both APKs and before the first
# invocation, `pm clear` wipes only the diagnostic app's own private data (never the androidTest
# package) and must report "Success", so a residue left by an earlier run can never be mistaken
# for this run's evidence. The first invocation's exact output and shell status are captured (no
# `|| true`) and the status is printed for diagnostics only: AOSP's `am instrument` can report the
# instrumented process's death through the watcher while the command itself still exits 0, so a
# non-zero shell status is not required. The load-bearing evidence is the output containing
# "Process crashed" — proof the instrumentation process actually died mid-commit, not merely that
# the command failed for some unrelated reason — or the target fails before the second, verifying
# invocation ever runs. The second invocation's non-zero result remains fatal, as before.
verify-harness-s25-process-kill:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2=="device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-s25-process-kill requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2=="device" {print $$1}'); \
	case "$$serial" in \
		emulator-*) \
			echo "error: attached target '$$serial' is an emulator; only a physical Samsung Galaxy S25 Ultra is an authorized target for this probe" >&2; \
			exit 1; \
			;; \
	esac; \
	manufacturer=$$(adb -s "$$serial" shell getprop ro.product.manufacturer | tr -d '\r'); \
	manufacturer_lower=$$(printf '%s' "$$manufacturer" | tr '[:upper:]' '[:lower:]'); \
	marketing_name=$$(adb -s "$$serial" shell getprop ro.config.marketing_name | tr -d '\r'); \
	model=$$(adb -s "$$serial" shell getprop ro.product.model | tr -d '\r'); \
	model_upper=$$(printf '%s' "$$model" | tr '[:lower:]' '[:upper:]'); \
	if [ "$$manufacturer_lower" != "samsung" ]; then \
		echo "error: attached target '$$serial' has manufacturer '$$manufacturer', not the authorized Samsung Galaxy S25 Ultra" >&2; \
		exit 1; \
	fi; \
	authorized=0; \
	case "$$model_upper" in SM-S938*) authorized=1 ;; esac; \
	case "$$marketing_name" in *"S25 Ultra"*) authorized=1 ;; esac; \
	if [ "$$authorized" -ne 1 ]; then \
		echo "error: attached target '$$serial' model '$$model' marketing name '$$marketing_name' does not match the authorized Samsung Galaxy S25 Ultra" >&2; \
		exit 1; \
	fi; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	clear_output=$$(adb -s "$$serial" shell pm clear com.calisthenics.diagnostic.android | tr -d '\r'); \
	if [ "$$clear_output" != "Success" ]; then \
		echo "error: 'pm clear com.calisthenics.diagnostic.android' did not report Success (got: $$clear_output); refusing to run against possibly stale app data" >&2; \
		exit 1; \
	fi; \
	set +e; \
	verify_transcript=$$(mktemp); \
	trap 'rm -f "$$verify_transcript"' EXIT INT TERM; \
	first_output=$$(adb -s "$$serial" shell am instrument -w -e class com.calisthenics.diagnostic.export.ProcessKillDuringExportTest#killDuringCommit com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner 2>&1); \
	first_exit_code=$$?; \
	set -e; \
	printf '%s\n' "$$first_output"; \
	printf 'killDuringCommit exit status: %s\n' "$$first_exit_code"; \
	if ! printf '%s' "$$first_output" | grep -q "Process crashed"; then \
		echo "error: killDuringCommit output does not identify an instrumentation process crash (\"Process crashed\"); refusing to trust unrelated or stale evidence" >&2; \
		exit 1; \
	fi; \
	adb -s "$$serial" shell am instrument -w -e class com.calisthenics.diagnostic.export.ProcessKillDuringExportTest#verifyNoPartialEntryAndResidueClassification com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$verify_transcript" 2>&1; \
	cat "$$verify_transcript"; \
	cd ../.. && $(VENV_PYTHON) tools/record_process_kill_evidence.py "$$verify_transcript"

verify-kotlin-export:
	@set -eu; \
	cd apps/diagnostic-harness; \
	./gradlew --no-daemon :shared:jvmTest :shared:generateSyntheticExport; \
	first_manifest_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/manifest.json | awk '{print $$1}'); \
	first_frames_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/frames.ndjson | awk '{print $$1}'); \
	./gradlew --no-daemon :shared:generateSyntheticExport; \
	second_manifest_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/manifest.json | awk '{print $$1}'); \
	second_frames_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/frames.ndjson | awk '{print $$1}'); \
	test "$$first_manifest_hash" = "$$second_manifest_hash"; \
	test "$$first_frames_hash" = "$$second_frames_hash"; \
	printf 'manifest sha256: %s\n' "$$second_manifest_hash"; \
	printf 'frames sha256: %s\n' "$$second_frames_hash"; \
	cd ../..; \
	$(CLI) validate-session apps/diagnostic-harness/shared/build/conformance/synthetic-session/manifest.json apps/diagnostic-harness/shared/build/conformance/synthetic-session/frames.ndjson

verify:
	$(VENV_PYTHON) -m ruff format --check reference
	$(VENV_PYTHON) -m ruff check reference
	$(VENV_PYTHON) -m mypy --config-file reference/pyproject.toml reference/src
	$(VENV_PYTHON) -m pytest reference/tests
	$(CLI) validate-session fixtures/synthetic/valid/session/manifest.json fixtures/synthetic/valid/session/frames.ndjson fixtures/synthetic/valid/session/annotation_independent_a.json fixtures/synthetic/valid/session/annotation_independent_b.json fixtures/synthetic/valid/session/annotation_adjudicated.json
	$(CLI) verify-fixture fixtures/synthetic/valid/session/fixture_metadata.json
	$(VENV_PYTHON) -c 'from pathlib import Path; from observation_contracts.fixture_integrity import discover_negative_fixtures, run_declared_validation; root = Path(".").resolve(); cases = discover_negative_fixtures(root); assert cases; assert all(run_declared_validation(case).codes == [case.expected_error_code] for case in cases)'
	$(CLI) verify-preprocessing-vector fixtures/synthetic/preprocessing/scale_pad_golden/vector.json
	$(CLI) guard-repository .
