package com.calisthenics.diagnostic.telemetry

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidFixedTelemetryProbeTest {
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
}
