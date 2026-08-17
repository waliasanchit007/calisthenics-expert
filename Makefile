PYTHON ?= $(shell for candidate in python3.14 python3.13 python3.12 python3; do \
	if command -v "$$candidate" >/dev/null 2>&1 && "$$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; then \
		printf '%s' "$$candidate"; exit 0; \
	fi; \
done; printf '%s' python3)
VENV_PYTHON := .venv/bin/python
CLI := $(VENV_PYTHON) -m observation_contracts

.PHONY: bootstrap verify verify-harness-jvm verify-harness-ios verify-harness-foundation verify-kotlin-export verify-harness-android-export-parity verify-harness-s25-process-kill

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

verify-harness-jvm:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest :androidApp:assembleDebug

verify-harness-ios:
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:linkDebugTestIosArm64 :shared:iosSimulatorArm64Test :composeApp:linkDebugTestIosArm64 :composeApp:iosSimulatorArm64Test :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64
	test -d apps/diagnostic-harness/composeApp/build/bin/iosArm64/debugFramework/DiagnosticHarness.framework
	test -d apps/diagnostic-harness/composeApp/build/bin/iosSimulatorArm64/debugFramework/DiagnosticHarness.framework

verify-harness-foundation:
	$(MAKE) verify-harness-jvm verify-harness-ios

verify-harness-android-export-parity:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | grep -c 'device product:' || true); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-android-export-parity requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:generateSyntheticExport :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.calisthenics.diagnostic.composition.InstallableCompositionExportParityTest

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
	device_count=$$(adb devices -l | tail -n +2 | grep -c 'device product:' || true); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-s25-process-kill requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	serial=$$(adb devices -l | tail -n +2 | grep 'device product:' | awk '{print $$1}'); \
	case "$$serial" in \
		emulator-*) \
			echo "error: attached target '$$serial' is an emulator; only a physical Samsung Galaxy S25 Ultra is an authorized target for this probe" >&2; \
			exit 1; \
			;; \
	esac; \
	manufacturer=$$(adb -s "$$serial" shell getprop ro.product.manufacturer | tr -d '\r'); \
	manufacturer_lower=$$(printf '%s' "$$manufacturer" | tr '[:upper:]' '[:lower:]'); \
	marketing_name=$$(adb -s "$$serial" shell getprop ro.config.marketing_name | tr -d '\r'); \
	if [ "$$manufacturer_lower" != "samsung" ]; then \
		echo "error: attached target '$$serial' has manufacturer '$$manufacturer', not the authorized Samsung Galaxy S25 Ultra" >&2; \
		exit 1; \
	fi; \
	case "$$marketing_name" in \
		*"S25 Ultra"*) : ;; \
		*) \
			echo "error: attached target '$$serial' marketing name '$$marketing_name' does not match the authorized Samsung Galaxy S25 Ultra" >&2; \
			exit 1; \
			;; \
	esac; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	clear_output=$$(adb -s "$$serial" shell pm clear com.calisthenics.diagnostic.android | tr -d '\r'); \
	if [ "$$clear_output" != "Success" ]; then \
		echo "error: 'pm clear com.calisthenics.diagnostic.android' did not report Success (got: $$clear_output); refusing to run against possibly stale app data" >&2; \
		exit 1; \
	fi; \
	set +e; \
	first_output=$$(adb -s "$$serial" shell am instrument -w -e class com.calisthenics.diagnostic.export.ProcessKillDuringExportTest#killDuringCommit com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner 2>&1); \
	first_exit_code=$$?; \
	set -e; \
	printf '%s\n' "$$first_output"; \
	printf 'killDuringCommit exit status: %s\n' "$$first_exit_code"; \
	if ! printf '%s' "$$first_output" | grep -q "Process crashed"; then \
		echo "error: killDuringCommit output does not identify an instrumentation process crash (\"Process crashed\"); refusing to trust unrelated or stale evidence" >&2; \
		exit 1; \
	fi; \
	adb -s "$$serial" shell am instrument -w -e class com.calisthenics.diagnostic.export.ProcessKillDuringExportTest#verifyNoPartialEntryAndResidueClassification com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner

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
