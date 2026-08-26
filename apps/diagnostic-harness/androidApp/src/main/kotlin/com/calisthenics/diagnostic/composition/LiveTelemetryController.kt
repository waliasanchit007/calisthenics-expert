package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.telemetry.FixedTelemetryReadings
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySampler
import com.calisthenics.diagnostic.telemetry.FixedTelemetryScheduler
import com.calisthenics.diagnostic.telemetry.TelemetrySink
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySchedule
import java.util.concurrent.atomic.AtomicBoolean

/** Android-independent lifecycle seam; Android probes supply the typed readings. */
interface LiveTelemetryProbe {
    fun sample(): FixedTelemetryReadings
}

/** Keeps the stop fence explicit at the call site: cancel, join, terminal sample, then seal. */
class LiveTelemetryController(
    nowNs: () -> Long,
    scheduler: FixedTelemetryScheduler,
    probe: LiveTelemetryProbe,
    sink: TelemetrySink,
) {
    private val sampler = FixedTelemetrySampler(nowNs, scheduler, probe::sample, sink)

    fun start() = sampler.start()

    fun stopAndJoinBeforeSeal() = sampler.stopAndJoinWithTerminalSample()
}

/** A session-owned thread; it is deliberately separate from the inference deadline scheduler. */
class DedicatedTelemetryScheduler : FixedTelemetryScheduler {
    override fun scheduleAtFixedRate(periodNs: Long, action: () -> Unit): FixedTelemetrySchedule {
        require(periodNs > 0L)
        val cancelled = AtomicBoolean(false)
        val thread = Thread {
            while (!cancelled.get()) {
                try {
                    Thread.sleep(periodNs / 1_000_000L, (periodNs % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    // Cancellation uses interruption only to leave the wait promptly.
                }
                if (!cancelled.get()) action()
            }
        }.apply {
            name = "m05b-fixed-telemetry"
            isDaemon = true
            start()
        }
        return object : FixedTelemetrySchedule {
            override fun cancel() {
                cancelled.set(true)
                thread.interrupt()
            }

            override fun join() {
                thread.join()
            }
        }
    }
}
