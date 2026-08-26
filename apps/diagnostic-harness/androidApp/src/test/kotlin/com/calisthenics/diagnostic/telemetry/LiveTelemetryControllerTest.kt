package com.calisthenics.diagnostic.telemetry

import com.calisthenics.diagnostic.composition.LiveTelemetryController
import com.calisthenics.diagnostic.composition.LiveTelemetryProbe
import kotlin.test.assertEquals
import org.junit.Test

class LiveTelemetryControllerTest {
    @Test
    fun stopCancelsAndJoinsBeforeAppendingTheTerminalProbeSnapshot() {
        val operations = mutableListOf<String>()
        val scheduler = object : FixedTelemetryScheduler {
            override fun scheduleAtFixedRate(periodNs: Long, action: () -> Unit): FixedTelemetrySchedule = object : FixedTelemetrySchedule {
                override fun cancel() { operations += "cancel" }
                override fun join() { operations += "join" }
            }
        }
        val sink = object : TelemetrySink {
            override fun append(record: TelemetryRecord) { operations += if ((record as FixedTelemetrySample).terminal) "terminal" else "sample" }
        }
        val controller = LiveTelemetryController(
            nowNs = { 0L },
            scheduler = scheduler,
            probe = object : LiveTelemetryProbe {
                override fun sample(): FixedTelemetryReadings = unavailableReadings()
            },
            sink = sink,
        )

        controller.start()
        controller.stopAndJoinBeforeSeal()

        assertEquals(listOf("sample", "cancel", "join", "terminal"), operations)
    }

    private fun unavailableReadings() = FixedTelemetryReadings(
        thermal = TelemetryValue.Unavailable("thermal_api_unavailable"),
        batteryTemperature = TelemetryValue.Unavailable("battery_temperature_unavailable"),
        batteryLevelOrCharge = TelemetryValue.Unavailable("battery_level_unavailable"),
        charging = TelemetryValue.Unavailable("charging_state_unavailable"),
        powerSource = TelemetryValue.Unavailable("power_source_unavailable"),
        connectivity = TelemetryValue.Unavailable("connectivity_api_unavailable"),
        keepAwake = TelemetryValue.Unavailable("keep_awake_unavailable"),
        brightness = TelemetryValue.Unavailable("brightness_unavailable"),
        lifecycle = TelemetryValue.Unavailable("lifecycle_unavailable"),
    )
}
