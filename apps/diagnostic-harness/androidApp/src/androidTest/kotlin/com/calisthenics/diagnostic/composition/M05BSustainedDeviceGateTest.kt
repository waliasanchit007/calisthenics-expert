package com.calisthenics.diagnostic.composition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.MainActivity
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.probe.CameraCapabilityProbe
import com.calisthenics.diagnostic.probe.readMarketingName
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.study.M05BArtifactCodec
import com.calisthenics.diagnostic.study.M05BAttemptOutcome
import com.calisthenics.diagnostic.study.M05BPublishedAttemptRecovery
import com.calisthenics.diagnostic.study.M05BBaselineClock
import com.calisthenics.diagnostic.study.M05BBatteryObservation
import com.calisthenics.diagnostic.study.M05BCooldownResult
import com.calisthenics.diagnostic.study.M05BCooldownSampler
import com.calisthenics.diagnostic.study.M05BObservedStart
import com.calisthenics.diagnostic.study.M05BPublishedAttempt
import com.calisthenics.diagnostic.study.M05BSustainedArtifactRef
import com.calisthenics.diagnostic.study.M05BSustainedObservation
import com.calisthenics.diagnostic.study.M05BThermalStatus
import com.calisthenics.diagnostic.study.renderM05BSustainedObservation
import com.calisthenics.diagnostic.study.validateM05BSustainedObservation
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/** Runs one exact, empty-scene sustained attempt and emits only references to durable bytes. */
class M05BSustainedDeviceGateTest {
    @Test
    fun publishesTheNextFrozenCadence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantCameraPermission(instrumentation, context.packageName)
        val roots = assertIs<StorageRootsResult.Ready>(AndroidStorageRoots.from(context)).roots
        val state = M05BDeviceRunStateStore(context, roots)
        val frozen = state.loadFrozenInputs()
        val recoveredHistory = state.recoverPublishedAttempts()
        val attemptContext = com.calisthenics.diagnostic.study.M05BAttemptChain.derive(
            frozen.order.typed,
            recoveredHistory,
        )
        assertTrue(attemptContext.blockingCodes.isEmpty(), attemptContext.blockingCodes.joinToString(","))

        val lifecycleOwner = InstrumentationLifecycleOwner()
        instrumentation.runOnMainSync { lifecycleOwner.resume() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val provenance = parseModelProvenance(
            context.assets.open("replay/live_model_provenance.json").use { it.readBytes().decodeToString() },
        )
        val currentObservation = {
            observeStart(
                context = context,
                scenario = scenario,
                frozen = frozen.observedStart,
                appSha256 = Sha256Digest.hex(File(context.applicationInfo.sourceDir).readBytes()),
                modelSha256 = provenance.sha256,
                runtimeVersion = provenance.tasksVisionVersion,
                delegate = provenance.delegate,
                hostOverrideRejected = InstrumentationRegistry.getArguments()
                    .getString("host_battery_override_rejected") == "true",
                ambientConstancyAttested = InstrumentationRegistry.getArguments()
                    .getString("m05b_ambient_constancy_attestation") == "constant",
            )
        }
        val cooldown = M05BCooldownSampler.awaitReady(
            AndroidBaselineClock,
            frozen.envelope.typed,
            currentObservation,
        )
        val observedStart = assertIs<M05BCooldownResult.Ready>(cooldown).observedStart
        val capability = CameraCapabilityProbe(
            context = context,
            lifecycleOwner = lifecycleOwner,
            probeGitSha = BuildConfig.PROBE_GIT_SHA,
        ).run().toCameraObservation()
        val composition = LiveCameraComposition.fromAndroid(
            context = context,
            lifecycleOwner = lifecycleOwner,
            cameraCapability = capability,
            permissionGranted = true,
            m05bRunAdmission = M05BLiveRunAdmission(
                envelopeArtifact = frozen.envelope,
                orderArtifact = frozen.order,
                observedStart = observedStart,
                attemptHistory = recoveredHistory,
            ),
        )
        val scheduler = GateScheduler()
        val stopped = CountDownLatch(1)
        var stopObservation: M05BSustainedStopObservation? = null
        val controller = M05BSustainedRunController.forComposition(composition, scheduler) {
            stopObservation = it
            stopped.countDown()
        }

        try {
            val started = assertIs<LiveCameraStartResult.Started>(
                controller.start(attemptContext.cadenceFps),
            )
            assertTrue(stopped.await(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES), "sustained stop did not complete")
            val stop = requireNotNull(stopObservation)
            val completion = requireNotNull(composition.completion())
            assertIs<FinalizationResult.Published>(completion.finalization)
            val export = File(roots.exportsRoot, started.sessionId.value)
            val manifest = File(export, "manifest.json")
            val frames = File(export, "frames.ndjson")
            val telemetry = File(export, "telemetry.ndjson")
            val admission = File(export, "run-admission.json")
            listOf(manifest, frames, telemetry, admission).forEach { file ->
                assertTrue(file.isFile, "published artifact is missing: ${file.name}")
            }
            val admissionSha = Sha256Digest.hex(admission.readBytes())
            val published = M05BPublishedAttempt(
                sessionId = started.sessionId.value,
                attemptSequence = attemptContext.attemptSequence,
                cadenceFps = attemptContext.cadenceFps,
                admissionSha256 = admissionSha,
                outcome = M05BAttemptOutcome.COMPLETED,
            )
            fun observation(history: List<com.calisthenics.diagnostic.study.M05BBoundAttemptEntry>) =
                M05BSustainedObservation(
                    sessionId = started.sessionId.value,
                    attemptSequence = attemptContext.attemptSequence,
                    cadenceFps = attemptContext.cadenceFps,
                    videoEnabled = false,
                    cameraFps = 30,
                    durationTargetNs = com.calisthenics.diagnostic.study.M05BSustainedProtocol.TARGET_DURATION_NS,
                    stopRequestedNs = stop.requestedAtNs,
                    actualCaptureSpanNs = stop.runtimeEvidence.actualCaptureSpanNs,
                    boundaryEvents = M05BSustainedObservation.REQUIRED_BOUNDARY_EVENTS,
                    envelope = M05BSustainedArtifactRef(frozen.envelope.path, frozen.envelope.sha256),
                    order = M05BSustainedArtifactRef(frozen.order.path, frozen.order.sha256),
                    admission = M05BSustainedArtifactRef(
                        "exports/${started.sessionId.value}/run-admission.json",
                        admissionSha,
                    ),
                    attemptChain = history,
                    stages = stop.runtimeEvidence.stages,
                    latency = stop.runtimeEvidence.latency,
                    telemetry = stop.runtimeEvidence.telemetry,
                    battery = stop.runtimeEvidence.battery,
                    sourceOutcome = stop.runtimeEvidence.sourceOutcome,
                    processOutcome = stop.runtimeEvidence.processOutcome,
                )
            val provisionalHistory = M05BPublishedAttemptRecovery.recover(
                frozen.order.typed,
                recoveredHistory,
                published,
            )
            val outcome = if (validateM05BSustainedObservation(observation(provisionalHistory)).admitted) {
                M05BAttemptOutcome.COMPLETED
            } else {
                M05BAttemptOutcome.BLOCKED
            }
            val history = state.recordPublishedAttempt(published.copy(outcome = outcome))
            val sustained = renderM05BSustainedObservation(observation(history))
            val record = referenceRecord(
                sessionId = started.sessionId.value,
                cadenceFps = attemptContext.cadenceFps,
                manifest = manifest,
                frames = frames,
                telemetry = telemetry,
                admission = admission,
                frozenInputs = frozen.exactBytes,
                attemptHistory = history,
                sustainedRecord = sustained,
                appSha256 = Sha256Digest.hex(File(context.applicationInfo.sourceDir).readBytes()),
            )
            instrumentation.sendStatus(STATUS_CODE, Bundle().apply { putString(STATUS_TAG, record) })
        } finally {
            controller.dispose()
            scheduler.dispose()
            if (composition.completion() == null) composition.abandon()
            scenario.close()
            instrumentation.runOnMainSync { lifecycleOwner.destroy() }
        }
    }

    private fun referenceRecord(
        sessionId: String,
        cadenceFps: Int,
        manifest: File,
        frames: File,
        telemetry: File,
        admission: File,
        frozenInputs: ByteArray,
        attemptHistory: List<com.calisthenics.diagnostic.study.M05BBoundAttemptEntry>,
        sustainedRecord: String,
        appSha256: String,
    ): String = buildJsonObject {
        put("record_kind", "m05b_sustained_session_reference")
        put("suite", SUITE)
        put("sanitized", true)
        put("non_participant_observation", true)
        put("session_id", sessionId)
        put("cadence_fps", cadenceFps)
        put("export_relative_path", "exports/$sessionId")
        putJsonObject("device") {
            put("device_class", androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE))
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("marketing_name", readMarketingName())
            put("api_level", Build.VERSION.SDK_INT)
            put("android_release", Build.VERSION.RELEASE)
        }
        putJsonObject("candidate") {
            put("git_sha", BuildConfig.PROBE_GIT_SHA)
            put("app_sha256", appSha256)
        }
        putJsonObject("frozen_inputs_ref") {
            put("path", "m05b-frozen-run-inputs.json")
            put("sha256", Sha256Digest.hex(frozenInputs))
        }
        putJsonObject("durable_artifacts") {
            putRef("manifest", "exports/$sessionId/manifest.json", manifest)
            putRef("frames", "exports/$sessionId/frames.ndjson", frames)
            putRef("telemetry", "exports/$sessionId/telemetry.ndjson", telemetry)
            putRef("run_admission", "exports/$sessionId/run-admission.json", admission)
        }
        putJsonArray("attempt_entry_refs") {
            attemptHistory.forEach { entry ->
                add(buildJsonObject {
                    put("path", "m05b-run-state/attempt-chain/attempt-${entry.typed.attemptSequence.toString().padStart(4, '0')}.json")
                    put("sha256", entry.sha256)
                })
            }
        }
        put("sustained_record", Json.parseToJsonElement(sustainedRecord))
    }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRef(key: String, path: String, file: File) =
        putJsonObject(key) {
            put("path", path)
            put("sha256", Sha256Digest.hex(file.readBytes()))
        }

    private fun observeStart(
        context: android.content.Context,
        scenario: ActivityScenario<MainActivity>,
        frozen: M05BObservedStart,
        appSha256: String,
        modelSha256: String,
        runtimeVersion: String,
        delegate: String,
        hostOverrideRejected: Boolean,
        ambientConstancyAttested: Boolean,
    ): M05BObservedStart {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var keepAwake: Boolean? = null
        var brightness: Float? = null
        scenario.onActivity { activity ->
            keepAwake = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            brightness = activity.window.attributes.screenBrightness
        }
        val power = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = connectivity?.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } == true
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val brightnessMode = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull()
        val normalized = brightness?.takeIf { it.isFinite() && it in 0.0f..1.0f }?.toDouble()
        return frozen.copy(
            appSha256 = appSha256,
            modelSha256 = modelSha256,
            runtimeVersion = runtimeVersion,
            delegate = delegate,
            deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
            manufacturer = Build.MANUFACTURER.lowercase(),
            model = Build.MODEL,
            deviceBuild = Build.FINGERPRINT,
            apiLevel = Build.VERSION.SDK_INT,
            charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
            ),
            powerSource = if (plugged == 0) "battery" else "external",
            hostBatteryOverrideRejected = hostOverrideRejected,
            powerSaveMode = power?.isPowerSaveMode,
            batteryObservation = if (level != null && scale != null) {
                M05BBatteryObservation("percent", level.toDouble() * 100.0 / scale)
            } else null,
            deviceIdleMode = power?.isDeviceIdleMode,
            hostAdbTransport = InstrumentationRegistry.getArguments().getString("host_adb_transport"),
            appTransport = if (wifi) "wifi" else null,
            networkIdentityPersisted = false,
            ambientToken = frozen.ambientToken,
            ambientConstancy = if (ambientConstancyAttested) "constant" else null,
            ambientConstancyOperatorSupplied = ambientConstancyAttested,
            keepAwake = keepAwake,
            brightnessMode = when (brightnessMode) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "automatic"
                else -> null
            },
            brightnessRaw = normalized,
            brightnessScaleMin = normalized?.let { 0.0 },
            brightnessScaleMax = normalized?.let { 1.0 },
            thermalStatus = power?.currentThermalStatus?.let(M05BThermalStatus::fromAndroid),
            batteryTemperatureC = temperature,
        )
    }

    private fun grantCameraPermission(instrumentation: android.app.Instrumentation, packageName: String) {
        if (ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
    }

    private class InstrumentationLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private object AndroidBaselineClock : M05BBaselineClock {
        override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
        override fun sleepUntilNs(deadlineNs: Long) {
            while (true) {
                val remaining = deadlineNs - nowNs()
                if (remaining <= 0L) return
                SystemClock.sleep((remaining / 1_000_000L).coerceAtLeast(1L))
            }
        }
    }

    private class GateScheduler : LiveScheduler, Disposable {
        private val executor = Executors.newSingleThreadScheduledExecutor()
        override fun scheduleAfter(delayNs: Long, action: () -> Unit): Disposable {
            val future = executor.schedule(action, delayNs, TimeUnit.NANOSECONDS)
            return object : Disposable { override fun dispose() { future.cancel(false) } }
        }
        override fun dispose() { executor.shutdownNow() }
    }

    private companion object {
        const val STATUS_TAG = "M05BSustainedEvidence"
        const val STATUS_CODE = 4
        const val SUITE = "com.calisthenics.diagnostic.composition.M05BSustainedDeviceGateTest"
        const val RUN_TIMEOUT_MINUTES = 27L
    }
}
