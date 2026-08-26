package com.calisthenics.diagnostic.telemetry

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.provider.Settings
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class AndroidFixedTelemetryProbeTest {
    @Test
    fun platformReaderDistinguishesWifiCellularNoActiveNetworkAndUnavailableCapabilities() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadowConnectivity = shadowOf(connectivity)

        shadowConnectivity.setActiveNetworkInfo(connectedNetwork(ConnectivityManager.TYPE_WIFI))
        val wifiNetwork = requireNotNull(connectivity.activeNetwork)
        shadowConnectivity.setNetworkCapabilities(
            wifiNetwork,
            capabilitiesWithTransport(ConnectivityManager.TYPE_WIFI),
        )
        val wifi = platformProbe(context).sample().connectivity

        shadowConnectivity.setActiveNetworkInfo(connectedNetwork(ConnectivityManager.TYPE_MOBILE))
        val cellularNetwork = requireNotNull(connectivity.activeNetwork)
        shadowConnectivity.setNetworkCapabilities(
            cellularNetwork,
            capabilitiesWithTransport(ConnectivityManager.TYPE_MOBILE),
        )
        val cellular = platformProbe(context).sample().connectivity

        shadowConnectivity.setActiveNetworkInfo(null)
        val disconnected = platformProbe(context).sample().connectivity

        shadowConnectivity.setActiveNetworkInfo(connectedNetwork(ConnectivityManager.TYPE_WIFI))
        shadowConnectivity.setNetworkCapabilities(requireNotNull(connectivity.activeNetwork), null)
        val unavailable = platformProbe(context).sample().connectivity

        assertEquals(
            ConnectivityState("wifi", connected = true),
            assertIs<TelemetryValue.Value<ConnectivityState>>(wifi).value,
        )
        assertEquals(
            ConnectivityState("cellular", connected = true),
            assertIs<TelemetryValue.Value<ConnectivityState>>(cellular).value,
        )
        assertEquals(
            ConnectivityState("none", connected = false),
            assertIs<TelemetryValue.Value<ConnectivityState>>(disconnected).value,
        )
        assertEquals(
            "connectivity_api_unavailable",
            assertIs<TelemetryValue.Unavailable>(unavailable).code,
        )
    }

    @Test
    fun platformReaderUsesKeepScreenOnFlagAndMarksNoActivityUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val disabled = platformProbe(context, activity = activity).sample().keepAwake

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val enabled = platformProbe(context, activity = activity).sample().keepAwake

        val unavailable = platformProbe(context).sample().keepAwake

        assertEquals(false, assertIs<TelemetryValue.Value<Boolean>>(disabled).value)
        assertEquals(true, assertIs<TelemetryValue.Value<Boolean>>(enabled).value)
        assertEquals("keep_awake_unavailable", assertIs<TelemetryValue.Unavailable>(unavailable).code)
    }

    @Test
    fun platformReaderUsesWindowBrightnessAndMarksPlatformDefaultUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.42f }
        val manual = platformProbe(context, activity = activity).sample().brightness

        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
        )
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.73f }
        val automatic = platformProbe(context, activity = activity).sample().brightness

        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val unavailable = platformProbe(context, activity = activity).sample().brightness

        val manualBrightness = assertIs<TelemetryValue.Value<BrightnessState>>(manual).value
        val automaticBrightness = assertIs<TelemetryValue.Value<BrightnessState>>(automatic).value

        assertEquals("manual", manualBrightness.mode)
        assertEquals(0.42, manualBrightness.value, absoluteTolerance = 0.000_001)
        assertEquals("automatic", automaticBrightness.mode)
        assertEquals(0.73, automaticBrightness.value, absoluteTolerance = 0.000_001)
        assertEquals("brightness_unavailable", assertIs<TelemetryValue.Unavailable>(unavailable).code)
    }

    @Test
    fun platformReaderPrefersBatteryBroadcastPercentageThenChargeCounterFallback() {
        val application = RuntimeEnvironment.getApplication()
        val batteryManager = application.getSystemService(BatteryManager::class.java)
        shadowOf(batteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 4_321_000)

        val percent = platformProbe(
            batteryBroadcastContext(application, level = 60, scale = 80),
        ).sample().batteryLevelOrCharge
        val chargeCounter = platformProbe(
            batteryBroadcastContext(application),
        ).sample().batteryLevelOrCharge

        assertEquals(
            BatteryReading(BatteryReadingUnit.PERCENT, 75),
            assertIs<TelemetryValue.Value<BatteryReading>>(percent).value,
        )
        assertEquals(
            BatteryReading(BatteryReadingUnit.MICROAMP_HOURS, 4_321_000),
            assertIs<TelemetryValue.Value<BatteryReading>>(chargeCounter).value,
        )
    }

    @Test
    fun broadcastPercentageIsPreferredAndChargeCounterFallbackKeepsMicroampHourUnits() {
        val percent = probe(
            battery = AndroidBatteryObservation(level = 60, scale = 80, chargeCounterMicroampHours = 4_321_000),
        ).sample().batteryLevelOrCharge
        val chargeCounter = probe(
            battery = AndroidBatteryObservation(level = null, scale = null, chargeCounterMicroampHours = 4_321_000),
        ).sample().batteryLevelOrCharge

        assertEquals(
            BatteryReading(BatteryReadingUnit.PERCENT, 75),
            assertIs<TelemetryValue.Value<BatteryReading>>(percent).value,
        )
        assertEquals(
            BatteryReading(BatteryReadingUnit.MICROAMP_HOURS, 4_321_000),
            assertIs<TelemetryValue.Value<BatteryReading>>(chargeCounter).value,
        )
    }

    @Test
    fun connectivityDistinguishesWifiCellularDisconnectedAndUnavailableWithoutIdentity() {
        val observations = listOf(
            AndroidConnectivityObservation.Connected(AndroidConnectivityTransport.WIFI),
            AndroidConnectivityObservation.Connected(AndroidConnectivityTransport.CELLULAR),
            AndroidConnectivityObservation.Disconnected,
            AndroidConnectivityObservation.Unavailable,
        )

        val readings = observations.map { connectivity ->
            probe(connectivity = connectivity).sample().connectivity
        }

        assertEquals(
            ConnectivityState("wifi", connected = true),
            assertIs<TelemetryValue.Value<ConnectivityState>>(readings[0]).value,
        )
        assertEquals(
            ConnectivityState("cellular", connected = true),
            assertIs<TelemetryValue.Value<ConnectivityState>>(readings[1]).value,
        )
        assertEquals(
            ConnectivityState("none", connected = false),
            assertIs<TelemetryValue.Value<ConnectivityState>>(readings[2]).value,
        )
        assertEquals(
            "connectivity_api_unavailable",
            assertIs<TelemetryValue.Unavailable>(readings[3]).code,
        )
    }

    @Test
    fun keepAwakePreservesWindowFlagTrueFalseAndMissingActivityAsUnavailable() {
        val enabled = probe(keepAwake = true).sample().keepAwake
        val disabled = probe(keepAwake = false).sample().keepAwake
        val unavailable = probe(keepAwake = null).sample().keepAwake

        assertEquals(true, assertIs<TelemetryValue.Value<Boolean>>(enabled).value)
        assertEquals(false, assertIs<TelemetryValue.Value<Boolean>>(disabled).value)
        assertEquals("keep_awake_unavailable", assertIs<TelemetryValue.Unavailable>(unavailable).code)
    }

    @Test
    fun brightnessUsesNormalizedDisplayValueAndPreservesManualOrAutomaticMode() {
        val manual = probe(
            brightness = AndroidBrightnessObservation(AndroidBrightnessMode.MANUAL, normalizedValue = 0.42),
        ).sample().brightness
        val automatic = probe(
            brightness = AndroidBrightnessObservation(AndroidBrightnessMode.AUTOMATIC, normalizedValue = 0.73),
        ).sample().brightness

        assertEquals(
            BrightnessState("manual", 0.42),
            assertIs<TelemetryValue.Value<BrightnessState>>(manual).value,
        )
        assertEquals(
            BrightnessState("automatic", 0.73),
            assertIs<TelemetryValue.Value<BrightnessState>>(automatic).value,
        )
    }

    @Test
    fun missingOrInvalidNormalizedBrightnessIsTypedUnavailable() {
        val missing = probe(brightness = null).sample().brightness
        val invalid = probe(
            brightness = AndroidBrightnessObservation(AndroidBrightnessMode.MANUAL, normalizedValue = 1.1),
        ).sample().brightness

        assertEquals("brightness_unavailable", assertIs<TelemetryValue.Unavailable>(missing).code)
        assertEquals("brightness_unavailable", assertIs<TelemetryValue.Unavailable>(invalid).code)
    }

    private fun probe(
        battery: AndroidBatteryObservation = AndroidBatteryObservation(null, null, null),
        connectivity: AndroidConnectivityObservation = AndroidConnectivityObservation.Unavailable,
        keepAwake: Boolean? = null,
        brightness: AndroidBrightnessObservation? = null,
    ) = AndroidFixedTelemetryProbe {
        AndroidFixedTelemetryObservation(
            thermal = TelemetryValue.Unavailable("thermal_api_unavailable"),
            batteryTemperature = TelemetryValue.Unavailable("battery_temperature_unavailable"),
            battery = battery,
            charging = TelemetryValue.Unavailable("charging_state_unavailable"),
            powerSource = TelemetryValue.Unavailable("power_source_unavailable"),
            connectivity = connectivity,
            keepAwake = keepAwake,
            brightness = brightness,
            lifecycle = TelemetryValue.Unavailable("lifecycle_unavailable"),
        )
    }

    private fun platformProbe(
        context: Context,
        lifecycleOwner: LifecycleOwner = foregroundLifecycleOwner(),
        activity: Activity? = null,
    ) = AndroidFixedTelemetryProbe(context, lifecycleOwner, activity)

    private fun batteryBroadcastContext(
        application: Context,
        level: Int? = null,
        scale: Int? = null,
    ): Context = object : ContextWrapper(application) {
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent =
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                level?.let { putExtra(BatteryManager.EXTRA_LEVEL, it) }
                scale?.let { putExtra(BatteryManager.EXTRA_SCALE, it) }
            }
    }

    private fun connectedNetwork(type: Int) = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED,
        type,
        0,
        true,
        NetworkInfo.State.CONNECTED,
    )

    private fun capabilitiesWithTransport(type: Int) = ShadowNetworkCapabilities.newInstance().also { capabilities ->
        shadowOf(capabilities).addTransportType(
            when (type) {
                ConnectivityManager.TYPE_WIFI -> android.net.NetworkCapabilities.TRANSPORT_WIFI
                ConnectivityManager.TYPE_MOBILE -> android.net.NetworkCapabilities.TRANSPORT_CELLULAR
                else -> error("unsupported transport type")
            },
        )
    }

    private fun foregroundLifecycleOwner(): LifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
    }
}
