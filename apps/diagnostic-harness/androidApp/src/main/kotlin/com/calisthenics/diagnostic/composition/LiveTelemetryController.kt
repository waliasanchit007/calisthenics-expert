package com.calisthenics.diagnostic.composition

import android.os.SystemClock
import com.calisthenics.diagnostic.telemetry.FixedTelemetryReadings
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySampler
import com.calisthenics.diagnostic.telemetry.FixedTelemetryScheduler
import com.calisthenics.diagnostic.telemetry.TelemetrySink
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySchedule
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
class DedicatedTelemetryScheduler internal constructor(
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val awaitDeadline: (Long) -> Unit = ::awaitElapsedRealtimeDeadline,
) : FixedTelemetryScheduler {
    override fun scheduleAtFixedRate(periodNs: Long, action: () -> Unit): FixedTelemetrySchedule {
        require(periodNs > 0L)
        val cancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread {
            try {
                var nextDeadlineNs = elapsedRealtimeNanos() + periodNs
                while (!cancelled.get()) {
                    awaitDeadline(nextDeadlineNs)
                    if (!cancelled.get()) action()
                    nextDeadlineNs += periodNs
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                cancelled.set(true)
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
                failure.get()?.let { throw it }
            }
        }
    }
}

private fun awaitElapsedRealtimeDeadline(deadlineNs: Long) {
    while (true) {
        val remainingNs = deadlineNs - SystemClock.elapsedRealtimeNanos()
        if (remainingNs <= 0L) return
        try {
            Thread.sleep(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
        } catch (_: InterruptedException) {
            return
        }
    }
}
