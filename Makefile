PYTHON ?= $(shell for candidate in python3.14 python3.13 python3.12 python3; do \
	if command -v "$$candidate" >/dev/null 2>&1 && "$$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; then \
		printf '%s' "$$candidate"; exit 0; \
	fi; \
done; printf '%s' python3)
VENV_PYTHON := .venv/bin/python
CLI := $(VENV_PYTHON) -m observation_contracts
M05B_CANDIDATE_IDENTITY_FILE ?= .m05b-envelope-candidate.env
M05B_ATTRIBUTION_IDENTITY_FILE ?= .m05b-attribution-candidate.json

.PHONY: bootstrap verify verify-study-readiness verify-harness-jvm verify-harness-ios verify-harness-foundation verify-kotlin-export verify-harness-android-export-parity verify-harness-detection-replay verify-harness-s25-process-kill verify-harness-replay-export verify-harness-s25-five-run-replay verify-harness-s25-camera-probe verify-harness-s25-storage-exclusion prepare-harness-s25-m05b-envelope-candidate verify-harness-s25-m05b-envelope verify-harness-s25-m05b-attribution verify-harness-s25-live-capture fetch-model-asset prepare-harness-gradle-dependencies

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
	cd apps/diagnostic-harness && ./gradlew --no-daemon :shared:jvmTest :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleJvmVerification

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
	first_envelope_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/start-envelope.json | awk '{print $$1}'); \
	first_order_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/execution-order.json | awk '{print $$1}'); \
	first_admission_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/run-admission.json | awk '{print $$1}'); \
	./gradlew --no-daemon :shared:generateSyntheticExport; \
	second_manifest_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/manifest.json | awk '{print $$1}'); \
	second_frames_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/frames.ndjson | awk '{print $$1}'); \
	second_envelope_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/start-envelope.json | awk '{print $$1}'); \
	second_order_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/execution-order.json | awk '{print $$1}'); \
	second_admission_hash=$$(shasum -a 256 shared/build/conformance/synthetic-session/run-admission.json | awk '{print $$1}'); \
	test "$$first_manifest_hash" = "$$second_manifest_hash"; \
	test "$$first_frames_hash" = "$$second_frames_hash"; \
	test "$$first_envelope_hash" = "$$second_envelope_hash"; \
	test "$$first_order_hash" = "$$second_order_hash"; \
	test "$$first_admission_hash" = "$$second_admission_hash"; \
	printf 'manifest sha256: %s\n' "$$second_manifest_hash"; \
	printf 'frames sha256: %s\n' "$$second_frames_hash"; \
	printf 'start envelope sha256: %s\n' "$$second_envelope_hash"; \
	printf 'execution order sha256: %s\n' "$$second_order_hash"; \
	printf 'run admission sha256: %s\n' "$$second_admission_hash"; \
	cd ../..; \
	$(CLI) validate-session apps/diagnostic-harness/shared/build/conformance/synthetic-session/manifest.json apps/diagnostic-harness/shared/build/conformance/synthetic-session/frames.ndjson; \
	$(CLI) verify-m05b-envelope start_envelope apps/diagnostic-harness/shared/build/conformance/synthetic-session/start-envelope.json; \
	$(CLI) verify-m05b-envelope execution_order apps/diagnostic-harness/shared/build/conformance/synthetic-session/execution-order.json; \
	$(CLI) verify-m05b-envelope run_admission apps/diagnostic-harness/shared/build/conformance/synthetic-session/run-admission.json

verify-study-readiness:
	$(CLI) verify-study-readiness docs/protocols/study-readiness.m05.v1.json
	@set +e; \
	$(CLI) verify-participant-capture-gate docs/protocols/study-readiness.m05.v1.json; \
	participant_gate_status=$$?; \
	set -e; \
	if [ "$$participant_gate_status" -ne 2 ]; then \
		echo "error: participant-capture gate returned $$participant_gate_status; expected policy block status 2" >&2; \
		exit 1; \
	fi; \
	echo "participant-capture gate is blocked as expected (status 2)"

# The S25 camera capability probe (design spec section 8, Slice 5 precondition). Device-gated on
# exactly the same preamble as verify-harness-s25-process-kill and
# verify-harness-s25-five-run-replay: exactly one attached authorized target, never an emulator,
# and Samsung plus the SM-S938 model family with the "S25 Ultra" marketing-name fallback —
# consistent with isAuthorizedS25Target, the single home of that rule (shared/commonMain). An
# emulated camera reports whatever the host image made up, so it says nothing about what the S25
# sensor and CameraX's underlying-image bridge actually do.
#
# Like every other device gate here, this never writes the pending/recorded ledger at
# docs/evidence/device-probes/s25-ultra-camera-capability-ledger.json. The checker prints the
# sanitized record and a human copies it.
#
# `am instrument` exits 0 even when the test fails, so its status is preserved and printed for
# diagnostics rather than trusted as the verdict, and any non-zero value fails the target before
# the transcript is interpreted at all. The verdict itself belongs to
# tools/check_camera_capability_probe.py, whose exit status distinguishes three outcomes:
#
#   0 — one passing probe test, one parseable sanitized record, live capture admissible;
#   1 — the transcript could not be trusted (no record, two records, wrong suite, OK (0 tests),
#       missing required fields, or a declared verdict contradicting the observations);
#   3 — the probe succeeded and live capture is NOT admissible: UNKNOWN timestamps, an unknown
#       DataSpace, or an unavailable underlying-image bridge. Design spec section 8 keeps the
#       harness fail-closed here and stops the milestone for a material scope/contract decision;
#       the observation is real evidence and is still printed for recording.
#
# Those regressions are hermetic and host-side, in
# reference/tests/test_camera_capability_probe_check.py.
verify-harness-s25-camera-probe:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2=="device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-s25-camera-probe requires exactly one authorized attached device, found $$device_count" >&2; \
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
	printf 'camera capability probe target: serial=%s model=%s marketing_name=%s api=%s\n' \
		"$$serial" "$$model" "$$marketing_name" \
		"$$(adb -s "$$serial" shell getprop ro.build.version.sdk | tr -d '\r')"; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	transcript=$$(mktemp); \
	trap 'rm -f "$$transcript"' EXIT INT TERM; \
	set +e; \
	adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; \
	instrument_status=$$?; \
	set -e; \
	cat "$$transcript"; \
	printf 'camera probe instrumentation exit status: %s\n' "$$instrument_status"; \
	if [ "$$instrument_status" -ne 0 ]; then \
		echo "error: am instrument exited $$instrument_status; the transcript is not a trustworthy observation" >&2; \
		exit "$$instrument_status"; \
	fi; \
	cd ../.. && $(VENV_PYTHON) tools/check_camera_capability_probe.py "$$transcript"

# M0.5B Task 1 physical evidence. Static tests prove the source manifest and both extraction-rule
# domains; this target proves the packaged app reports backup disabled, the packaged rule resource
# parses to the same complete-parent exclusions, and the app-private parent contains exactly the
# four registry-derived children on the authorized S25. It clears app data after installation so
# no pre-rule state can be mistaken for a post-rule observation. The typed record is printed only;
# this target never edits an evidence ledger.
verify-harness-s25-storage-exclusion:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2 == "device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-s25-storage-exclusion requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2 == "device" {print $$1}'); \
	case "$$serial" in emulator-*) echo "error: emulator is not authorized for this gate" >&2; exit 1 ;; esac; \
	manufacturer=$$(adb -s "$$serial" shell getprop ro.product.manufacturer | tr -d '\r'); \
	model=$$(adb -s "$$serial" shell getprop ro.product.model | tr -d '\r'); \
	marketing_name=$$(adb -s "$$serial" shell getprop ro.config.marketing_name | tr -d '\r'); \
	manufacturer_lower=$$(printf '%s' "$$manufacturer" | tr '[:upper:]' '[:lower:]'); \
	model_upper=$$(printf '%s' "$$model" | tr '[:lower:]' '[:upper:]'); \
	authorized=0; \
	case "$$model_upper" in SM-S938*) authorized=1 ;; esac; \
	case "$$marketing_name" in *"S25 Ultra"*) authorized=1 ;; esac; \
	if [ "$$manufacturer_lower" != samsung ] || [ "$$authorized" -ne 1 ]; then \
		echo "error: attached target is not the authorized Samsung Galaxy S25 Ultra" >&2; \
		exit 1; \
	fi; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	clear_result=$$(adb -s "$$serial" shell pm clear com.calisthenics.diagnostic.android | tr -d '\r'); \
	if [ "$$clear_result" != "Success" ]; then \
		echo "error: pm clear returned '$$clear_result'" >&2; \
		exit 1; \
	fi; \
	transcript=$$(mktemp); \
	trap 'rm -f "$$transcript"' EXIT INT TERM; \
	set +e; \
	adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.probe.StorageExclusionDeviceGateTest com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; \
	instrument_status=$$?; \
	set -e; \
	cat "$$transcript"; \
	if [ "$$instrument_status" -ne 0 ]; then exit "$$instrument_status"; fi; \
	cd ../.. && $(VENV_PYTHON) tools/check_storage_exclusion.py "$$transcript"

# One-time preparation for the exact M0.5B candidate. This is deliberately separate from the
# physical evidence sequence. It is the only target allowed to build, install, or clear app data.
prepare-harness-s25-m05b-envelope-candidate:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2 == "device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then echo "error: candidate preparation requires exactly one authorized attached device, found $$device_count" >&2; exit 1; fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2 == "device" {print $$1}'); \
	case "$$serial" in emulator-*) echo "error: emulator is not authorized for M0.5B candidate preparation" >&2; exit 1 ;; esac; \
	manufacturer=$$(adb -s "$$serial" shell getprop ro.product.manufacturer | tr -d '\r'); \
	model=$$(adb -s "$$serial" shell getprop ro.product.model | tr -d '\r'); \
	marketing_name=$$(adb -s "$$serial" shell getprop ro.config.marketing_name | tr -d '\r'); \
	manufacturer_lower=$$(printf '%s' "$$manufacturer" | tr '[:upper:]' '[:lower:]'); \
	model_upper=$$(printf '%s' "$$model" | tr '[:lower:]' '[:upper:]'); \
	case "$$model_upper" in SM-S938*) authorized=1 ;; *) authorized=0 ;; esac; \
	case "$$marketing_name" in *"S25 Ultra"*) authorized=1 ;; esac; \
	if [ "$$manufacturer_lower" != samsung ] || [ "$$authorized" -ne 1 ]; then echo "error: attached target is not the authorized Samsung Galaxy S25 Ultra" >&2; exit 1; fi; \
	if [ -n "$$(git status --porcelain --untracked-files=normal)" ]; then echo "error: candidate preparation requires a clean source tree" >&2; exit 1; fi; \
	$(MAKE) fetch-model-asset; \
	(cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest); \
	adb -s "$$serial" install -r apps/diagnostic-harness/androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r apps/diagnostic-harness/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	clear_result=$$(adb -s "$$serial" shell pm clear com.calisthenics.diagnostic.android | tr -d '\r'); \
	if [ "$$clear_result" != Success ]; then echo "error: pm clear returned '$$clear_result'" >&2; exit 1; fi; \
	app_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	test_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android.test | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	if [ -z "$$app_remote_apk" ] || [ -z "$$test_remote_apk" ]; then echo "error: installed candidate APK path is unavailable" >&2; exit 1; fi; \
	candidate_tmp=$$(mktemp -d); identity_tmp="$(M05B_CANDIDATE_IDENTITY_FILE).tmp"; \
	trap 'rm -rf "$$candidate_tmp"; rm -f "$$identity_tmp"' EXIT INT TERM; \
	adb -s "$$serial" exec-out cat "$$app_remote_apk" > "$$candidate_tmp/app.apk"; \
	adb -s "$$serial" exec-out cat "$$test_remote_apk" > "$$candidate_tmp/test.apk"; \
	app_sha256=$$(shasum -a 256 "$$candidate_tmp/app.apk" | awk '{print $$1}'); \
	test_sha256=$$(shasum -a 256 "$$candidate_tmp/test.apk" | awk '{print $$1}'); \
	producer_git_sha=$$(git rev-parse HEAD); \
	model_sha256=$$(sed -n 's/.*"sha256": "\([0-9a-f]*\)".*/\1/p' apps/diagnostic-harness/model-assets/live_model_provenance.json | head -n 1); \
	backend_version=$$(sed -n 's/.*"tasks_vision_version": "\([^"]*\)".*/\1/p' apps/diagnostic-harness/model-assets/live_model_provenance.json | head -n 1); \
	delegate=$$(sed -n 's/.*"delegate": "\([^"]*\)".*/\1/p' apps/diagnostic-harness/model-assets/live_model_provenance.json | head -n 1 | tr '[:upper:]' '[:lower:]'); \
	runtime_version=$$(adb -s "$$serial" shell getprop ro.build.version.release | tr -d '\r'); \
	printf 'M05B_CANDIDATE_APP_SHA256=%s\nM05B_CANDIDATE_TEST_SHA256=%s\nM05B_CANDIDATE_GIT_SHA=%s\nM05B_CANDIDATE_PROBE_GIT_SHA=%s\nM05B_CANDIDATE_MODEL_SHA256=%s\nM05B_CANDIDATE_RUNTIME_VERSION=%s\nM05B_CANDIDATE_BACKEND_VERSION=%s\nM05B_CANDIDATE_DELEGATE=%s\n' "$$app_sha256" "$$test_sha256" "$$producer_git_sha" "$$producer_git_sha" "$$model_sha256" "$$runtime_version" "$$backend_version" "$$delegate" > "$$identity_tmp"; \
	mv "$$identity_tmp" "$(M05B_CANDIDATE_IDENTITY_FILE)"; \
	identity_json_tmp="$(M05B_ATTRIBUTION_IDENTITY_FILE).tmp"; \
	printf '{"app_git_sha":"%s","probe_git_sha":"%s","apk_sha256":"%s","model_sha256":"%s","runtime_version":"%s","backend_version":"%s","delegate":"%s"}\n' "$$producer_git_sha" "$$producer_git_sha" "$$app_sha256" "$$model_sha256" "$$runtime_version" "$$backend_version" "$$delegate" > "$$identity_json_tmp"; \
	mv "$$identity_json_tmp" "$(M05B_ATTRIBUTION_IDENTITY_FILE)"; \
	printf 'Prepared exact M0.5B candidate identity at %s and %s\napp_sha256=%s\ntest_sha256=%s\nproducer_git_sha=%s\n' "$(M05B_CANDIDATE_IDENTITY_FILE)" "$(M05B_ATTRIBUTION_IDENTITY_FILE)" "$$app_sha256" "$$test_sha256" "$$producer_git_sha"

# M0.5B Task 7 physical start-envelope gate. It consumes an already-installed exact candidate;
# rebuilding, reinstalling, package-task execution, model fetching, and app-data clearing are
# prohibited during the evidence sequence. The target prints evidence and never writes the ledger.
verify-harness-s25-m05b-envelope:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2 == "device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then echo "error: verify-harness-s25-m05b-envelope requires exactly one authorized attached device, found $$device_count" >&2; exit 1; fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2 == "device" {print $$1}'); \
	case "$$serial" in emulator-*) echo "error: emulator is not authorized for the M0.5B S25 envelope gate" >&2; exit 1 ;; esac; \
	if [ ! -f "$(M05B_CANDIDATE_IDENTITY_FILE)" ]; then echo "error: missing exact candidate identity; run prepare-harness-s25-m05b-envelope-candidate once" >&2; exit 1; fi; \
	expected_app_sha256=$$(sed -n 's/^M05B_CANDIDATE_APP_SHA256=//p' "$(M05B_CANDIDATE_IDENTITY_FILE)"); \
	expected_test_sha256=$$(sed -n 's/^M05B_CANDIDATE_TEST_SHA256=//p' "$(M05B_CANDIDATE_IDENTITY_FILE)"); \
	expected_git_sha=$$(sed -n 's/^M05B_CANDIDATE_GIT_SHA=//p' "$(M05B_CANDIDATE_IDENTITY_FILE)"); \
	for digest in "$$expected_app_sha256" "$$expected_test_sha256"; do printf '%s\n' "$$digest" | grep -Eq '^[0-9a-f]{64}$$' || { echo "error: malformed candidate digest" >&2; exit 1; }; done; \
	printf '%s\n' "$$expected_git_sha" | grep -Eq '^[0-9a-f]{40}$$' || { echo "error: malformed candidate git SHA" >&2; exit 1; }; \
	if [ "$$(git rev-parse HEAD)" != "$$expected_git_sha" ] || [ -n "$$(git status --porcelain --untracked-files=normal)" ]; then echo "error: source changed after candidate preparation" >&2; exit 1; fi; \
	app_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	test_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android.test | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	if [ -z "$$app_remote_apk" ] || [ -z "$$test_remote_apk" ]; then echo "error: prepared candidate is not installed" >&2; exit 1; fi; \
	candidate_tmp=$$(mktemp -d); baseline_transcript=$$(mktemp); transcript=$$(mktemp); trap 'rm -rf "$$candidate_tmp"; rm -f "$$baseline_transcript" "$$transcript"' EXIT INT TERM; \
	adb -s "$$serial" exec-out cat "$$app_remote_apk" > "$$candidate_tmp/app.apk"; \
	adb -s "$$serial" exec-out cat "$$test_remote_apk" > "$$candidate_tmp/test.apk"; \
	actual_app_sha256=$$(shasum -a 256 "$$candidate_tmp/app.apk" | awk '{print $$1}'); \
	actual_test_sha256=$$(shasum -a 256 "$$candidate_tmp/test.apk" | awk '{print $$1}'); \
	if [ "$$actual_app_sha256" != "$$expected_app_sha256" ] || [ "$$actual_test_sha256" != "$$expected_test_sha256" ]; then echo "error: installed candidate bytes differ from prepared identity" >&2; exit 1; fi; \
	: "$${M05B_AMBIENT_TOKEN:?set M05B_AMBIENT_TOKEN to the bounded operator token}"; \
	if [ "$${M05B_AMBIENT_CONSTANCY_ATTESTATION+x}" = x ]; then echo "error: AMBIENT.ATTESTATION_PRE_SUPPLIED; ambient constancy must be attested after the baseline" >&2; exit 1; fi; \
	battery_dump=$$(adb -s "$$serial" shell dumpsys battery | tr -d '\r'); \
	host_override_rejected=true; if printf '%s\n' "$$battery_dump" | grep -Eq '(^|: )((AC|USB|Wireless|Dock) powered): true'; then host_override_rejected=false; fi; \
	host_adb_transport=false; case "$$serial" in *:*) host_adb_transport=true ;; esac; \
	set +e; adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.probe.M05BEnvelopeDeviceGateTest#observeBaseline -e host_adb_transport "$$([ "$$host_adb_transport" = true ] && echo wifi || echo unavailable)" -e host_battery_override_rejected "$$host_override_rejected" -e m05b_ambient_token "$$M05B_AMBIENT_TOKEN" -e m05b_candidate_app_sha256 "$$expected_app_sha256" -e m05b_candidate_git_sha "$$expected_git_sha" com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$baseline_transcript" 2>&1; baseline_status=$$?; set -e; \
	cat "$$baseline_transcript"; printf 'M0.5B baseline instrumentation exit status: %s\n' "$$baseline_status"; \
	if [ "$$baseline_status" -ne 0 ]; then exit "$$baseline_status"; fi; \
	baseline_challenge=$$($(VENV_PYTHON) tools/check_m05b_baseline_phase.py "$$baseline_transcript"); \
	printf 'After observing the completed baseline, attest ambient constancy (constant or changed): ' >/dev/tty; \
	IFS= read -r M05B_AMBIENT_CONSTANCY_ATTESTATION </dev/tty; \
	case "$$M05B_AMBIENT_CONSTANCY_ATTESTATION" in constant|changed) ;; *) echo "error: ambient constancy attestation must be constant or changed" >&2; exit 1 ;; esac; \
	app_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	test_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android.test | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	if [ -z "$$app_remote_apk" ] || [ -z "$$test_remote_apk" ]; then echo "error: prepared candidate disappeared after the baseline" >&2; exit 1; fi; \
	adb -s "$$serial" exec-out cat "$$app_remote_apk" > "$$candidate_tmp/app-after-baseline.apk"; \
	adb -s "$$serial" exec-out cat "$$test_remote_apk" > "$$candidate_tmp/test-after-baseline.apk"; \
	actual_app_sha256=$$(shasum -a 256 "$$candidate_tmp/app-after-baseline.apk" | awk '{print $$1}'); \
	actual_test_sha256=$$(shasum -a 256 "$$candidate_tmp/test-after-baseline.apk" | awk '{print $$1}'); \
	if [ "$$actual_app_sha256" != "$$expected_app_sha256" ] || [ "$$actual_test_sha256" != "$$expected_test_sha256" ]; then echo "error: installed candidate changed during the baseline" >&2; exit 1; fi; \
	set +e; adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.probe.M05BEnvelopeDeviceGateTest#attestAndEmit -e m05b_baseline_challenge "$$baseline_challenge" -e m05b_ambient_constancy_attestation "$$M05B_AMBIENT_CONSTANCY_ATTESTATION" -e m05b_candidate_app_sha256 "$$expected_app_sha256" -e m05b_candidate_git_sha "$$expected_git_sha" com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; instrument_status=$$?; set -e; \
	cat "$$transcript"; printf 'M0.5B envelope instrumentation exit status: %s\n' "$$instrument_status"; \
	if [ "$$instrument_status" -ne 0 ]; then exit "$$instrument_status"; fi; \
	$(VENV_PYTHON) tools/check_m05b_envelope.py "$$transcript" "$$expected_app_sha256" "$$expected_git_sha"

# Task 6's short attribution gate. It consumes the exact candidate prepared above and transfers
# only the durable exports referenced by the exact instrumentation record. It never builds,
# installs, clears app data, or writes the pending/recorded evidence ledger.
verify-harness-s25-m05b-attribution:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2 == "device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then echo "error: verify-harness-s25-m05b-attribution requires exactly one authorized attached device, found $$device_count" >&2; exit 1; fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2 == "device" {print $$1}'); \
	case "$$serial" in emulator-*) echo "error: emulator is not authorized for the S25 attribution gate" >&2; exit 1 ;; *:*) ;; *) echo "error: attribution gate requires Wi-Fi ADB, got '$$serial'" >&2; exit 1 ;; esac; \
	manufacturer=$$(adb -s "$$serial" shell getprop ro.product.manufacturer | tr -d '\r'); \
	model=$$(adb -s "$$serial" shell getprop ro.product.model | tr -d '\r'); \
	marketing_name=$$(adb -s "$$serial" shell getprop ro.config.marketing_name | tr -d '\r'); \
	if ! $(VENV_PYTHON) -c 'import sys; from pathlib import Path; sys.path.insert(0, str(Path("tools").resolve())); from check_five_run_replay import is_authorized_five_run_gate_target; raise SystemExit(0 if is_authorized_five_run_gate_target({"device_class":"physical_device", "manufacturer":sys.argv[1], "model":sys.argv[2], "marketing_name":sys.argv[3]}) else 1)' "$$manufacturer" "$$model" "$$marketing_name"; then echo "error: attached target is not the authorized Samsung Galaxy S25 Ultra" >&2; exit 1; fi; \
	if [ ! -f "$(M05B_CANDIDATE_IDENTITY_FILE)" ] || [ ! -f "$(M05B_ATTRIBUTION_IDENTITY_FILE)" ]; then echo "error: exact attribution candidate identity is missing; run prepare-harness-s25-m05b-envelope-candidate once" >&2; exit 1; fi; \
	expected_app_sha256=$$(sed -n 's/^M05B_CANDIDATE_APP_SHA256=//p' "$(M05B_CANDIDATE_IDENTITY_FILE)"); \
	expected_test_sha256=$$(sed -n 's/^M05B_CANDIDATE_TEST_SHA256=//p' "$(M05B_CANDIDATE_IDENTITY_FILE)"); \
	expected_git_sha=$$(sed -n 's/^M05B_CANDIDATE_GIT_SHA=//p' "$(M05B_CANDIDATE_IDENTITY_FILE)"); \
	if [ "$$expected_git_sha" != "$$(git rev-parse HEAD)" ] || [ -n "$$(git status --porcelain --untracked-files=normal)" ]; then echo "error: source changed after candidate preparation" >&2; exit 1; fi; \
	for digest in "$$expected_app_sha256" "$$expected_test_sha256"; do printf '%s\n' "$$digest" | grep -Eq '^[0-9a-f]{64}$$' || { echo "error: malformed candidate digest" >&2; exit 1; }; done; \
	app_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	test_remote_apk=$$(adb -s "$$serial" shell pm path com.calisthenics.diagnostic.android.test | tr -d '\r' | sed -n 's/^package://p' | head -n 1); \
	if [ -z "$$app_remote_apk" ] || [ -z "$$test_remote_apk" ]; then echo "error: exact attribution candidate is not installed" >&2; exit 1; fi; \
	candidate_tmp=$$(mktemp -d); transcript=$$(mktemp); trap 'rm -rf "$$candidate_tmp"; rm -f "$$transcript"' EXIT INT TERM; \
	adb -s "$$serial" exec-out cat "$$app_remote_apk" > "$$candidate_tmp/app.apk"; \
	adb -s "$$serial" exec-out cat "$$test_remote_apk" > "$$candidate_tmp/test.apk"; \
	actual_app_sha256=$$(shasum -a 256 "$$candidate_tmp/app.apk" | awk '{print $$1}'); actual_test_sha256=$$(shasum -a 256 "$$candidate_tmp/test.apk" | awk '{print $$1}'); \
	if [ "$$actual_app_sha256" != "$$expected_app_sha256" ] || [ "$$actual_test_sha256" != "$$expected_test_sha256" ]; then echo "error: installed candidate bytes differ from prepared identity" >&2; exit 1; fi; \
	set +e; adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.composition.M05BAttributionDeviceGateTest com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; instrument_status=$$?; set -e; \
	cat "$$transcript"; \
	$(VENV_PYTHON) tools/check_m05b_attribution.py --serial "$$serial" --package com.calisthenics.diagnostic.android --transcript "$$transcript" --subprocess-exit-code "$$instrument_status" --prepared-identity "$(M05B_ATTRIBUTION_IDENTITY_FILE)"

# Task 5's bounded physical live-capture evidence gate. The device test emits one typed record;
# the host checker pulls and validates the exact export before deriving the final verdict. This
# target never writes the pending/recorded evidence ledger.
verify-harness-s25-live-capture:
	@set -eu; \
	device_count=$$(adb devices -l | tail -n +2 | awk '$$2 == "device"' | wc -l | tr -d ' '); \
	if [ "$$device_count" -ne 1 ]; then \
		echo "error: verify-harness-s25-live-capture requires exactly one authorized attached device, found $$device_count" >&2; \
		exit 1; \
	fi; \
	serial=$$(adb devices -l | tail -n +2 | awk '$$2 == "device" {print $$1}'); \
	case "$$serial" in \
		emulator-*) echo "error: attached target '$$serial' is an emulator; only a physical Samsung Galaxy S25 Ultra is authorized" >&2; exit 1 ;; \
	esac; \
	manufacturer=$$(adb -s "$$serial" shell getprop ro.product.manufacturer | tr -d '\r'); \
	manufacturer_lower=$$(printf '%s' "$$manufacturer" | tr '[:upper:]' '[:lower:]'); \
	model=$$(adb -s "$$serial" shell getprop ro.product.model | tr -d '\r'); \
	model_upper=$$(printf '%s' "$$model" | tr '[:lower:]' '[:upper:]'); \
	marketing_name=$$(adb -s "$$serial" shell getprop ro.config.marketing_name | tr -d '\r'); \
	if [ "$$manufacturer_lower" != "samsung" ]; then echo "error: unauthorized manufacturer '$$manufacturer'" >&2; exit 1; fi; \
	authorized=0; case "$$model_upper" in SM-S938*) authorized=1 ;; esac; case "$$marketing_name" in *"S25 Ultra"*) authorized=1 ;; esac; \
	if [ "$$authorized" -ne 1 ]; then echo "error: unauthorized S25 target model '$$model' marketing name '$$marketing_name'" >&2; exit 1; fi; \
	$(MAKE) fetch-model-asset; \
	cd apps/diagnostic-harness && ./gradlew --no-daemon :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk; \
	adb -s "$$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk; \
	clear_result=$$(adb -s "$$serial" shell pm clear com.calisthenics.diagnostic.android | tr -d '\r'); \
	if [ "$$clear_result" != "Success" ]; then echo "error: pm clear returned '$$clear_result'" >&2; exit 1; fi; \
	transcript=$$(mktemp); \
	trap 'rm -f "$$transcript"' EXIT INT TERM; \
	set +e; \
	adb -s "$$serial" shell am instrument -w -r -e class com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest com.calisthenics.diagnostic.android.test/androidx.test.runner.AndroidJUnitRunner >"$$transcript" 2>&1; \
	instrument_status=$$?; \
	set -e; \
	cat "$$transcript"; \
	cd ../.. && $(VENV_PYTHON) tools/check_live_capture_run.py "$$serial" com.calisthenics.diagnostic.android "$$transcript" "$$instrument_status"

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
	$(MAKE) verify-study-readiness
