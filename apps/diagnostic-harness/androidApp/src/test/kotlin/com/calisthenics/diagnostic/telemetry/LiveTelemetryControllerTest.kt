package com.calisthenics.diagnostic.telemetry

import com.calisthenics.diagnostic.composition.LiveTelemetryController
import com.calisthenics.diagnostic.composition.LiveTelemetryProbe
import com.calisthenics.diagnostic.composition.DedicatedTelemetryScheduler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    @Test
    fun dedicatedSchedulerAdvancesAbsoluteElapsedRealtimeDeadlinesAtAFixedRate() {
        val deadlines = CopyOnWriteArrayList<Long>()
        val actions = AtomicInteger(0)
        val finished = IllegalStateException("finished fixed-rate observation")
        val scheduler = DedicatedTelemetryScheduler(
            elapsedRealtimeNanos = { 100L },
            awaitDeadline = { deadline -> deadlines += deadline },
        )
        val schedule = scheduler.scheduleAtFixedRate(periodNs = 10L) {
            if (actions.incrementAndGet() == 3) throw finished
        }

        assertEquals(finished, assertFailsWith<IllegalStateException> { schedule.join() })
        assertEquals(listOf(110L, 120L, 130L), deadlines)
    }

    @Test
    fun dedicatedSchedulerRethrowsTheExactActionFailureFromJoin() {
        val failure = IllegalArgumentException("probe or sink failed")
        val scheduler = DedicatedTelemetryScheduler(
            elapsedRealtimeNanos = { 0L },
            awaitDeadline = {},
        )
        val schedule = scheduler.scheduleAtFixedRate(periodNs = 1L) { throw failure }

        assertEquals(failure, assertFailsWith<IllegalArgumentException> { schedule.join() })
    }

    @Test
    fun failedScheduledProbeIsRethrownWithoutAppendingATerminalSample() {
        val failure = IllegalStateException("scheduled probe failed")
        val scheduledAttempted = CountDownLatch(1)
        val probeCalls = AtomicInteger(0)
        val samples = CopyOnWriteArrayList<FixedTelemetrySample>()
        val dedicated = DedicatedTelemetryScheduler(
            elapsedRealtimeNanos = { System.nanoTime() },
            awaitDeadline = { deadline ->
                while (System.nanoTime() < deadline) Thread.yield()
            },
        )
        val controller = LiveTelemetryController(
            nowNs = { 0L },
            scheduler = FixedTelemetryScheduler { _, action ->
                dedicated.scheduleAtFixedRate(periodNs = 1_000_000L, action = action)
            },
            probe = object : LiveTelemetryProbe {
                override fun sample(): FixedTelemetryReadings {
                    if (probeCalls.incrementAndGet() > 1) {
                        scheduledAttempted.countDown()
                        throw failure
                    }
                    return unavailableReadings()
                }
            },
            sink = TelemetrySink { record -> samples += record as FixedTelemetrySample },
        )
        controller.start()
        assertTrue(scheduledAttempted.await(1, TimeUnit.SECONDS))

        assertEquals(failure, assertFailsWith<IllegalStateException> { controller.stopAndJoinBeforeSeal() })
        assertEquals(2, probeCalls.get())
        assertEquals(listOf(false), samples.map { it.terminal })
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
