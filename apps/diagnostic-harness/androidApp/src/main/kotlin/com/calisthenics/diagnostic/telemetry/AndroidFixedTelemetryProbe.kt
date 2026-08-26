package com.calisthenics.diagnostic.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.calisthenics.diagnostic.composition.LiveTelemetryProbe

/** Android snapshot probe. Every absent or denied platform value remains a typed UNAVAILABLE. */
class AndroidFixedTelemetryProbe(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val keepAwake: () -> Boolean? = { null },
) : LiveTelemetryProbe {
    override fun sample(): FixedTelemetryReadings {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val manager = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return FixedTelemetryReadings(
            thermal = power?.let { TelemetryValue.Value(it.currentThermalStatus) }
                ?: TelemetryValue.Unavailable("thermal_api_unavailable"),
            batteryTemperature = battery.intExtra(EXTRA_BATTERY_TEMPERATURE, "battery_temperature_unavailable"),
            batteryLevelOrCharge = battery.batteryLevelOrCharge(manager),
            charging = battery.charging(),
            powerSource = battery.powerSource(),
            connectivity = TelemetryValue.Unavailable("connectivity_probe_unavailable"),
            keepAwake = keepAwake()?.let { TelemetryValue.Value(it) }
                ?: TelemetryValue.Unavailable("keep_awake_unavailable"),
            brightness = brightness(),
            lifecycle = TelemetryValue.Value(
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) "foreground" else "background",
            ),
        )
    }

    private fun Intent?.batteryLevelOrCharge(manager: BatteryManager?): TelemetryValue<Int> {
        val level = this?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = this?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) return TelemetryValue.Value((level * 100) / scale)
        val charge = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Int.MIN_VALUE
        return if (charge != Int.MIN_VALUE) TelemetryValue.Value(charge) else TelemetryValue.Unavailable("battery_level_unavailable")
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

    private fun Intent?.intExtra(key: String, unavailableCode: String): TelemetryValue<Int> = this?.let { intent ->
        intent.getIntExtra(key, Int.MIN_VALUE).let { value ->
            if (value == Int.MIN_VALUE) TelemetryValue.Unavailable(unavailableCode) else TelemetryValue.Value(value)
        }
    } ?: TelemetryValue.Unavailable(unavailableCode)

    private fun brightness(): TelemetryValue<BrightnessState> = try {
        val resolver = context.contentResolver
        val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        TelemetryValue.Value(BrightnessState(
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "automatic" else "manual",
            raw / 255.0,
        ))
    } catch (_: Throwable) {
        TelemetryValue.Unavailable("brightness_unavailable")
    }

    private companion object {
        const val EXTRA_BATTERY_TEMPERATURE = "temperature"
    }
}
