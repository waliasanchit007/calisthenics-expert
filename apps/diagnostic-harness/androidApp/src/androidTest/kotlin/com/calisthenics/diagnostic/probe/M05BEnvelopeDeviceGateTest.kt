package com.calisthenics.diagnostic.probe

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.MainActivity
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.study.M05BExecutionOrder
import com.calisthenics.diagnostic.study.M05BObservedStart
import com.calisthenics.diagnostic.study.M05BPredecessorStatus
import com.calisthenics.diagnostic.study.M05BStartEnvelope
import com.calisthenics.diagnostic.study.M05BStartEnvelopeEvidence
import com.calisthenics.diagnostic.study.renderM05BStartEnvelopeEvidence
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.test.assertTrue
import org.junit.Test

class M05BEnvelopeDeviceGateTest {
    @Test
    fun emitsOneSanitizedIdleStartObservationWithoutWritingAnAuthoritativeLedger() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val battery = context.registerReceiver(null, IntentFilterHolder.battery)
        val model = parseModelProvenance(
            context.assets.open("replay/model_provenance.json").use { it.readBytes().decodeToString() },
        )
        val appSha = sha256File(context.applicationInfo.sourceDir)
        val androidIdDigest = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotBlank() }?.let { Sha256Digest.hex(it.encodeToByteArray()) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var keepAwake: Boolean? = null
        var normalizedBrightness: Float? = null
        scenario.onActivity { activity ->
            keepAwake = activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            normalizedBrightness = activity.window.attributes.screenBrightness
        }
        scenario.close()
        val brightnessMode = runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
            )
        }.getOrNull()
        val brightnessRaw = normalizedBrightness
            ?.takeIf { it.isFinite() && it in 0.0f..1.0f }
            ?.times(255.0f)
            ?.toDouble()
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
            setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val wifi = context.getSystemService(ConnectivityManager::class.java)
            ?.activeNetwork
            ?.let { wifiManager ->
                context.getSystemService(ConnectivityManager::class.java)
                    ?.getNetworkCapabilities(wifiManager)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } == true
        val deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE)
        val hostOverrideRejected = InstrumentationRegistry.getArguments()
            .getString("host_battery_override_rejected") == "true"
        val observed = M05BObservedStart(
            appSha256 = appSha,
            modelSha256 = model.sha256,
            runtimeVersion = model.tasksVisionVersion,
            delegate = model.delegate,
            preprocessingVersion = "1.0.0",
            deviceUnitDigest = androidIdDigest,
            deviceClass = deviceClass,
            manufacturer = Build.MANUFACTURER.lowercase(),
            model = Build.MODEL,
            deviceBuild = Build.FINGERPRINT,
            apiLevel = Build.VERSION.SDK_INT,
            charging = charging,
            powerSource = if (plugged == 0) "battery" else "external",
            hostBatteryOverrideRejected = hostOverrideRejected,
            hostAdbTransport = if (InstrumentationRegistry.getArguments().getString("host_adb_transport") == "wifi") "wifi" else null,
            appTransport = if (wifi) "wifi" else null,
            networkIdentityPersisted = false,
            ambientToken = null,
            ambientConstancy = "unavailable",
            keepAwake = keepAwake,
            brightnessMode = when (brightnessMode) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "automatic"
                else -> null
            },
            brightnessRaw = brightnessRaw,
            brightnessScaleMin = brightnessRaw?.let { 0.0 },
            brightnessScaleMax = brightnessRaw?.let { 255.0 },
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
                context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus?.toString()
            } else null,
            batteryTemperatureMinC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0),
            batteryTemperatureMaxC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0),
            batteryTemperatureRangeC = 0.0,
        )
        val envelope = M05BStartEnvelope(
            appSha256 = appSha,
            modelSha256 = model.sha256,
            runtimeVersion = model.tasksVisionVersion,
            delegate = model.delegate,
            preprocessingVersion = "1.0.0",
            deviceUnitDigest = androidIdDigest ?: "",
            deviceClass = deviceClass,
            manufacturer = Build.MANUFACTURER.lowercase(),
            model = Build.MODEL,
            deviceBuild = Build.FINGERPRINT,
            apiLevel = Build.VERSION.SDK_INT,
            charging = false,
            powerSource = "battery",
            hostBatteryOverrideRejected = hostOverrideRejected,
            hostAdbTransport = "wifi",
            appTransport = "wifi",
            networkIdentityPersisted = false,
            ambientToken = "ambient-unavailable",
            ambientConstancy = "constant",
            keepAwake = true,
            brightnessMode = "manual",
            brightnessRaw = brightnessRaw ?: 128.0,
            brightnessScaleMin = 0.0,
            brightnessScaleMax = 255.0,
            thermalStatus = observed.thermalStatus ?: "unavailable",
            batteryTemperatureMinC = observed.batteryTemperatureMinC ?: 0.0,
            batteryTemperatureMaxC = observed.batteryTemperatureMaxC ?: 0.0,
            batteryTemperatureRangeC = observed.batteryTemperatureRangeC ?: 0.0,
        )
        val evidence = M05BStartEnvelopeEvidence(envelope, M05BExecutionOrder(listOf(10, 15, 30)), observed)
        val record = renderM05BStartEnvelopeEvidence(evidence)
        instrumentation.sendStatus(7, Bundle().apply { putString("M05BStartEnvelopeEvidence", record) })
        assertTrue(record.contains("m05b_start_envelope_device_gate_observation"))
    }

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
}
