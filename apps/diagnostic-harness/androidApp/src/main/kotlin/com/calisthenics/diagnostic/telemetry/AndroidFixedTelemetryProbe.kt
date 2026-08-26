package com.calisthenics.diagnostic.telemetry

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.calisthenics.diagnostic.composition.LiveTelemetryProbe

internal data class AndroidBatteryObservation(
    val level: Int?,
    val scale: Int?,
    val chargeCounterMicroampHours: Int?,
)

internal enum class AndroidConnectivityTransport {
    WIFI,
    CELLULAR,
    OTHER,
}

internal sealed interface AndroidConnectivityObservation {
    data class Connected(val transport: AndroidConnectivityTransport) : AndroidConnectivityObservation
    data object Disconnected : AndroidConnectivityObservation
    data object Unavailable : AndroidConnectivityObservation
}

internal enum class AndroidBrightnessMode {
    MANUAL,
    AUTOMATIC,
}

internal data class AndroidBrightnessObservation(
    val mode: AndroidBrightnessMode,
    val normalizedValue: Double,
)

internal data class AndroidFixedTelemetryObservation(
    val thermal: TelemetryValue<Int>,
    val batteryTemperature: TelemetryValue<Int>,
    val battery: AndroidBatteryObservation,
    val charging: TelemetryValue<Boolean>,
    val powerSource: TelemetryValue<String>,
    val connectivity: AndroidConnectivityObservation,
    val keepAwake: Boolean?,
    val brightness: AndroidBrightnessObservation?,
    val lifecycle: TelemetryValue<String>,
)

private fun interface AndroidFixedTelemetryObservationSource {
    fun observe(): AndroidFixedTelemetryObservation
}

/** Android snapshot probe. Every absent or denied platform value remains a typed UNAVAILABLE. */
class AndroidFixedTelemetryProbe private constructor(
    private val source: AndroidFixedTelemetryObservationSource,
) : LiveTelemetryProbe {
    constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        activity: Activity? = lifecycleOwner as? Activity,
    ) : this(
        AndroidFixedTelemetryObservationSource {
            observeAndroidPlatform(context, lifecycleOwner, activity)
        },
    )

    internal constructor(observe: () -> AndroidFixedTelemetryObservation) : this(
        AndroidFixedTelemetryObservationSource(observe),
    )

    override fun sample(): FixedTelemetryReadings {
        val observation = source.observe()
        return FixedTelemetryReadings(
            thermal = observation.thermal,
            batteryTemperature = observation.batteryTemperature,
            batteryLevelOrCharge = observation.battery.asTelemetryValue(),
            charging = observation.charging,
            powerSource = observation.powerSource,
            connectivity = observation.connectivity.asTelemetryValue(),
            keepAwake = observation.keepAwake?.let { TelemetryValue.Value(it) }
                ?: TelemetryValue.Unavailable("keep_awake_unavailable"),
            brightness = observation.brightness
                ?.takeIf { it.normalizedValue.isFinite() && it.normalizedValue in 0.0..1.0 }
                ?.let { brightness ->
                    TelemetryValue.Value(
                        BrightnessState(
                            mode = brightness.mode.name.lowercase(),
                            value = brightness.normalizedValue,
                        ),
                    )
                }
                ?: TelemetryValue.Unavailable("brightness_unavailable"),
            lifecycle = observation.lifecycle,
        )
    }
}

private fun observeAndroidPlatform(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    activity: Activity?,
): AndroidFixedTelemetryObservation {
    val battery = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val manager = context.getSystemService(BatteryManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    return AndroidFixedTelemetryObservation(
        thermal = power?.let { TelemetryValue.Value(it.currentThermalStatus) }
            ?: TelemetryValue.Unavailable("thermal_api_unavailable"),
        batteryTemperature = battery.intExtra(EXTRA_BATTERY_TEMPERATURE, "battery_temperature_unavailable"),
        battery = AndroidBatteryObservation(
            level = battery.intExtraOrNull(BatteryManager.EXTRA_LEVEL),
            scale = battery.intExtraOrNull(BatteryManager.EXTRA_SCALE),
            chargeCounterMicroampHours = manager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeUnless { it == Int.MIN_VALUE },
        ),
        charging = battery.charging(),
        powerSource = battery.powerSource(),
        connectivity = observeConnectivity(context),
        keepAwake = observeKeepAwake(activity),
        brightness = observeBrightness(context, activity),
        lifecycle = TelemetryValue.Value(
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) "foreground" else "background",
        ),
    )
}

private fun Intent?.charging(): TelemetryValue<Boolean> = this?.let { intent ->
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    TelemetryValue.Value(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
} ?: TelemetryValue.Unavailable("charging_state_unavailable")

private fun Intent?.powerSource(): TelemetryValue<String> = this?.let { intent ->
    when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
        0 -> TelemetryValue.Value("battery")
        BatteryManager.BATTERY_PLUGGED_AC -> TelemetryValue.Value("ac")
        BatteryManager.BATTERY_PLUGGED_USB -> TelemetryValue.Value("usb")
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> TelemetryValue.Value("wireless")
        else -> TelemetryValue.Unavailable("power_source_unavailable")
    }
} ?: TelemetryValue.Unavailable("power_source_unavailable")

private fun Intent?.intExtra(key: String, unavailableCode: String): TelemetryValue<Int> =
    intExtraOrNull(key)?.let { TelemetryValue.Value(it) }
        ?: TelemetryValue.Unavailable(unavailableCode)

private fun Intent?.intExtraOrNull(key: String): Int? = this
    ?.getIntExtra(key, Int.MIN_VALUE)
    ?.takeUnless { it == Int.MIN_VALUE }

private fun AndroidBatteryObservation.asTelemetryValue(): TelemetryValue<BatteryReading> {
    val observedLevel = level
    val observedScale = scale
    if (observedLevel != null && observedLevel >= 0 && observedScale != null && observedScale > 0) {
        val percent = ((observedLevel.toLong() * 100L) / observedScale.toLong()).toInt()
        if (percent in 0..100) {
            return TelemetryValue.Value(BatteryReading(BatteryReadingUnit.PERCENT, percent))
        }
    }
    return chargeCounterMicroampHours
        ?.let { TelemetryValue.Value(BatteryReading(BatteryReadingUnit.MICROAMP_HOURS, it)) }
        ?: TelemetryValue.Unavailable("battery_level_unavailable")
}

private fun AndroidConnectivityObservation.asTelemetryValue(): TelemetryValue<ConnectivityState> = when (this) {
    is AndroidConnectivityObservation.Connected -> TelemetryValue.Value(
        ConnectivityState(
            transport = when (transport) {
                AndroidConnectivityTransport.WIFI -> "wifi"
                AndroidConnectivityTransport.CELLULAR -> "cellular"
                AndroidConnectivityTransport.OTHER -> "other"
            },
            connected = true,
        ),
    )
    AndroidConnectivityObservation.Disconnected -> TelemetryValue.Value(
        ConnectivityState(transport = "none", connected = false),
    )
    AndroidConnectivityObservation.Unavailable -> TelemetryValue.Unavailable("connectivity_api_unavailable")
}

private fun observeConnectivity(context: Context): AndroidConnectivityObservation = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return@runCatching AndroidConnectivityObservation.Unavailable
    val activeNetwork = manager.activeNetwork
        ?: return@runCatching AndroidConnectivityObservation.Disconnected
    val capabilities = manager.getNetworkCapabilities(activeNetwork)
        ?: return@runCatching AndroidConnectivityObservation.Unavailable
    val transport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> AndroidConnectivityTransport.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> AndroidConnectivityTransport.CELLULAR
        else -> AndroidConnectivityTransport.OTHER
    }
    AndroidConnectivityObservation.Connected(transport)
}.getOrDefault(AndroidConnectivityObservation.Unavailable)

private fun observeKeepAwake(activity: Activity?): Boolean? = activity?.let { liveActivity ->
    runCatching {
        liveActivity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    }.getOrNull()
}

private fun observeBrightness(
    context: Context,
    activity: Activity?,
): AndroidBrightnessObservation? = runCatching {
    val normalized = activity?.window?.attributes?.screenBrightness?.toDouble()
        ?: return@runCatching null
    val mode = when (
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
    ) {
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> AndroidBrightnessMode.MANUAL
        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> AndroidBrightnessMode.AUTOMATIC
        else -> return@runCatching null
    }
    AndroidBrightnessObservation(mode, normalized)
}.getOrNull()

private const val EXTRA_BATTERY_TEMPERATURE = "temperature"
