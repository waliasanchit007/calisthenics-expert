package com.calisthenics.diagnostic.probe

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
import android.system.Os
import android.system.OsConstants
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.MainActivity
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.study.M05BArtifactCodec
import com.calisthenics.diagnostic.study.M05BBaselineClock
import com.calisthenics.diagnostic.study.M05BBaselineReduction
import com.calisthenics.diagnostic.study.M05BBaselineReducer
import com.calisthenics.diagnostic.study.M05BBatteryObservation
import com.calisthenics.diagnostic.study.M05BDeviceUnitDigest
import com.calisthenics.diagnostic.study.M05BExecutionOrder
import com.calisthenics.diagnostic.study.M05BIdleBaselineSampler
import com.calisthenics.diagnostic.study.M05BObservedStart
import com.calisthenics.diagnostic.study.M05BPostBaselineAttestation
import com.calisthenics.diagnostic.study.M05BStartEnvelopeEvidence
import com.calisthenics.diagnostic.study.M05BThermalStatus
import com.calisthenics.diagnostic.study.renderM05BStartEnvelopeEvidence
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertTrue
import org.junit.Test

class M05BEnvelopeDeviceGateTest {
    @Test
    fun observeBaseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val preSupplied = M05BPostBaselineAttestation.beginBaseline(
            arguments.getString("m05b_ambient_constancy_attestation"),
        )
        if (preSupplied.blockingCodes.isNotEmpty()) {
            instrumentation.sendStatus(
                7,
                Bundle().apply {
                    putString("M05BBaselinePhaseStatus", "blocked")
                    putString("M05BBaselineBlockingCode", preSupplied.blockingCodes.single())
                },
            )
        }
        assertTrue(preSupplied.blockingCodes.isEmpty(), preSupplied.blockingCodes.joinToString(","))
        val pendingBaseline = baselineFile(context)
        if (pendingBaseline.exists()) {
            check(pendingBaseline.delete()) { "stale pending baseline could not be removed" }
            fsyncDirectory(pendingBaseline.parentFile!!)
        }
        val model = parseModelProvenance(
            context.assets.open("replay/model_provenance.json").use { it.readBytes().decodeToString() },
        )
        val appSha = sha256File(context.applicationInfo.sourceDir)
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotBlank() }
        val appScopeDigest = appScopeDigest(context)
        val deviceUnitDigest = if (androidId != null && appScopeDigest != null) {
            M05BDeviceUnitDigest.derive(androidId, appScopeDigest)
        } else {
            null
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val hostOverrideRejected = arguments.getString("host_battery_override_rejected") == "true"
        val ambientToken = arguments.getString("m05b_ambient_token")
        try {
            val baseline = M05BIdleBaselineSampler.collect(AndroidBaselineClock) {
                observeStart(
                    context = context,
                    scenario = scenario,
                    appSha256 = appSha,
                    modelSha256 = model.sha256,
                    runtimeVersion = model.tasksVisionVersion,
                    delegate = model.delegate,
                    deviceUnitDigest = deviceUnitDigest,
                    hostOverrideRejected = hostOverrideRejected,
                    ambientToken = ambientToken,
                )
            }
            val exactBaselineBytes = M05BArtifactCodec.canonicalBytes(
                JsonArray(baseline.map(M05BArtifactCodec::canonicalObservedStart)),
            )
            persistBaseline(context, exactBaselineBytes)
            instrumentation.sendStatus(
                7,
                Bundle().apply {
                    putString("M05BBaselinePhaseStatus", "awaiting_operator_attestation")
                    putString("M05BBaselineChallenge", Sha256Digest.hex(exactBaselineBytes))
                },
            )
            assertTrue(baseline.size == M05BIdleBaselineSampler.SAMPLE_COUNT)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun attestAndEmit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val baselineFile = baselineFile(context)
        val exactBaselineBytes = baselineFile.readBytes()
        val expectedChallenge = Sha256Digest.hex(exactBaselineBytes)
        val attestation = M05BPostBaselineAttestation.complete(
            expectedChallenge = expectedChallenge,
            suppliedChallenge = arguments.getString("m05b_baseline_challenge"),
            suppliedAttestation = arguments.getString("m05b_ambient_constancy_attestation"),
        )
        assertTrue(attestation.blockingCodes.isEmpty(), attestation.blockingCodes.joinToString(","))
        assertTrue(baselineFile.delete(), "consumed pending baseline could not be removed")
        fsyncDirectory(baselineFile.parentFile!!)
        val samples = Json.parseToJsonElement(exactBaselineBytes.decodeToString()).jsonArray.map { sample ->
            M05BArtifactCodec.decodeObservedStart(M05BArtifactCodec.canonicalBytes(sample))
        }
        val order = M05BExecutionOrder(
            cadencesFps = listOf(10, 15, 30),
            orderId = "m05b-s25-order",
            producerGitSha = BuildConfig.PROBE_GIT_SHA,
        )
        val reduction = M05BBaselineReducer.reduce(
            samples = samples,
            ambientToken = samples.lastOrNull()?.ambientToken,
            ambientConstancyAttestation = attestation.attestation,
            order = order,
            producerGitSha = BuildConfig.PROBE_GIT_SHA,
        )
        val (envelope, observed, baselineBlockingCodes) = when (reduction) {
            is M05BBaselineReduction.Frozen -> Triple(
                reduction.envelope,
                reduction.finalObservedStart,
                reduction.blockingCodes,
            )
            is M05BBaselineReduction.Blocked -> Triple(
                null,
                requireNotNull(reduction.finalObservedStart),
                reduction.blockingCodes,
            )
        }
        val record = renderM05BStartEnvelopeEvidence(
            M05BStartEnvelopeEvidence(
                envelope = envelope,
                order = order,
                observedStart = observed,
                baselineBlockingCodes = baselineBlockingCodes,
            ),
        )
        val admitted = Json.parseToJsonElement(record).jsonObject
            .getValue("satisfies_s25_m05b_admission").jsonPrimitive.boolean
        instrumentation.sendStatus(
            7,
            Bundle().apply {
                putString("M05BStartEnvelopeEvidence", record)
                putString("M05BEnvelopeGateStatus", if (admitted) "admitted" else "blocked")
            },
        )
        assertTrue(record.contains("m05b_start_envelope_device_gate_observation"))
    }

    private fun persistBaseline(context: android.content.Context, exactBytes: ByteArray) {
        val destination = baselineFile(context)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(exactBytes)
            output.flush()
            output.fd.sync()
        }
        Os.rename(temporary.path, destination.path)
        fsyncDirectory(destination.parentFile!!)
    }

    private fun baselineFile(context: android.content.Context): File =
        File(context.filesDir, "m05b-baseline-observations.json")

    private fun fsyncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun observeStart(
        context: android.content.Context,
        scenario: ActivityScenario<MainActivity>,
        appSha256: String,
        modelSha256: String,
        runtimeVersion: String,
        delegate: String,
        deviceUnitDigest: String?,
        hostOverrideRejected: Boolean,
        ambientToken: String?,
    ): M05BObservedStart {
        val battery = context.registerReceiver(null, IntentFilterHolder.battery)
        var keepAwake: Boolean? = null
        var normalizedBrightness: Float? = null
        scenario.onActivity { activity ->
            keepAwake = activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            normalizedBrightness = activity.window.attributes.screenBrightness
        }
        val brightnessMode = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull()
        val brightnessRaw = normalizedBrightness?.takeIf { it.isFinite() && it in 0.0f..1.0f }?.toDouble()
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
            setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val wifi = context.getSystemService(ConnectivityManager::class.java)
            ?.activeNetwork
            ?.let { network ->
                context.getSystemService(ConnectivityManager::class.java)
                    ?.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } == true
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10.0)
        val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, Int.MIN_VALUE)
            ?.takeIf { it >= 0 }
        val batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, Int.MIN_VALUE)
            ?.takeIf { it > 0 }
        val powerManager = context.getSystemService(PowerManager::class.java)
        return M05BObservedStart(
            appSha256 = appSha256,
            modelSha256 = modelSha256,
            runtimeVersion = runtimeVersion,
            delegate = delegate,
            preprocessingVersion = "1.0.0",
            deviceUnitDigest = deviceUnitDigest,
            deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
            manufacturer = Build.MANUFACTURER.lowercase(),
            model = Build.MODEL,
            deviceBuild = Build.FINGERPRINT,
            apiLevel = Build.VERSION.SDK_INT,
            charging = charging,
            powerSource = if (plugged == 0) "battery" else "external",
            hostBatteryOverrideRejected = hostOverrideRejected,
            powerSaveMode = powerManager?.isPowerSaveMode,
            batteryObservation = if (batteryLevel != null && batteryScale != null) {
                M05BBatteryObservation("percent", batteryLevel.toDouble() * 100.0 / batteryScale)
            } else {
                null
            },
            deviceIdleMode = powerManager?.isDeviceIdleMode,
            hostAdbTransport = InstrumentationRegistry.getArguments().getString("host_adb_transport"),
            appTransport = if (wifi) "wifi" else null,
            networkIdentityPersisted = false,
            ambientToken = ambientToken,
            ambientConstancy = null,
            ambientConstancyOperatorSupplied = false,
            keepAwake = keepAwake,
            brightnessMode = when (brightnessMode) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "automatic"
                else -> null
            },
            brightnessRaw = brightnessRaw,
            brightnessScaleMin = brightnessRaw?.let { 0.0 },
            brightnessScaleMax = brightnessRaw?.let { 1.0 },
            thermalStatus = powerManager?.currentThermalStatus?.let(M05BThermalStatus::fromAndroid),
            batteryTemperatureC = temperature,
        )
    }

    @Suppress("DEPRECATION")
    private fun appScopeDigest(context: android.content.Context): String? = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            },
        )
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        certificates.map { Sha256Digest.hex(it.toByteArray()) }.sorted().joinToString(",")
            .takeIf { it.isNotEmpty() }
            ?.let { signingDigest -> Sha256Digest.hex("${context.packageName}\u0000$signingDigest".encodeToByteArray()) }
    }.getOrNull()

    private fun sha256File(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private object IntentFilterHolder {
        val battery = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    }

    private object AndroidBaselineClock : M05BBaselineClock {
        override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

        override fun sleepUntilNs(deadlineNs: Long) {
            while (true) {
                val remainingNs = deadlineNs - nowNs()
                if (remainingNs <= 0L) return
                SystemClock.sleep((remainingNs / 1_000_000L).coerceAtLeast(1L))
            }
        }
    }
}
