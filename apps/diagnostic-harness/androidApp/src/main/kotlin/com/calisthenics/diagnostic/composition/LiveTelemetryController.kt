package com.calisthenics.diagnostic.composition

import android.os.SystemClock
import com.calisthenics.diagnostic.telemetry.FixedTelemetryReadings
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySampler
import com.calisthenics.diagnostic.telemetry.FixedTelemetryScheduler
import com.calisthenics.diagnostic.telemetry.TelemetrySink
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySchedule
import com.calisthenics.diagnostic.telemetry.TelemetryRecord
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class TelemetryDispatchFailureCode {
    QUEUE_OVERFLOW,
    SINK_FAILURE,
    JOIN_FAILURE,
    POST_FENCE_APPEND,
}

class TelemetryDispatchException(
    val failureCode: TelemetryDispatchFailureCode,
    cause: Throwable? = null,
) : IllegalStateException("live telemetry dispatch failed: ${failureCode.name.lowercase()}", cause)

data class TelemetryDispatchSnapshot(
    val acceptedRecords: Long,
    val queueOverflows: Long,
    val sinkFailures: Long,
    val postFenceRejections: Long,
    val joined: Boolean,
)

/** One non-blocking producer queue and one durable-I/O worker owned by a live session. */
class BoundedTelemetryDispatcher(
    private val durableSink: TelemetrySink,
    capacity: Int = DEFAULT_CAPACITY,
) : TelemetrySink {
    private val monitor = Any()
    private val queue = ArrayBlockingQueue<TelemetryRecord>(capacity)
    private val joinCompleted = CountDownLatch(1)
    private var accepting = true
    private var fenceRequested = false
    private var joinClaimed = false
    private var joined = false
    private var terminalFailure: TelemetryDispatchException? = null
    private var acceptedRecords = 0L
    private var queueOverflows = 0L
    private var sinkFailures = 0L
    private var postFenceRejections = 0L
    private val worker = Thread(::drain).apply {
        name = "m05b-telemetry-dispatch"
        isDaemon = true
        start()
    }

    init {
        require(capacity > 0) { "telemetry queue capacity must be positive" }
    }

    override fun append(record: TelemetryRecord) {
        synchronized(monitor) {
            terminalFailure?.let { throw it }
            if (!accepting) {
                postFenceRejections += 1L
                throw TelemetryDispatchException(TelemetryDispatchFailureCode.POST_FENCE_APPEND)
            }
            if (!queue.offer(record)) {
                queueOverflows += 1L
                val failure = TelemetryDispatchException(TelemetryDispatchFailureCode.QUEUE_OVERFLOW)
                terminalFailure = failure
                accepting = false
                throw failure
            }
            acceptedRecords += 1L
        }
    }

    fun fenceAndJoin() {
        val ownsJoin = synchronized(monitor) {
            if (!fenceRequested) {
                fenceRequested = true
                accepting = false
            }
            if (!joinClaimed) true.also { joinClaimed = true } else false
        }
        if (ownsJoin) {
            var interrupted: InterruptedException? = null
            try {
                while (worker.isAlive) {
                    try {
                        worker.join()
                    } catch (error: InterruptedException) {
                        if (interrupted == null) interrupted = error
                    }
                }
                interrupted?.let { error ->
                    synchronized(monitor) {
                        if (terminalFailure == null) {
                            terminalFailure = TelemetryDispatchException(
                                TelemetryDispatchFailureCode.JOIN_FAILURE,
                                error,
                            )
                        }
                    }
                    Thread.currentThread().interrupt()
                }
            } finally {
                synchronized(monitor) { joined = true }
                joinCompleted.countDown()
            }
        } else {
            joinCompleted.await()
        }
        synchronized(monitor) { terminalFailure }?.let { throw it }
    }

    fun snapshot(): TelemetryDispatchSnapshot = synchronized(monitor) {
        TelemetryDispatchSnapshot(
            acceptedRecords = acceptedRecords,
            queueOverflows = queueOverflows,
            sinkFailures = sinkFailures,
            postFenceRejections = postFenceRejections,
            joined = joined,
        )
    }

    private fun drain() {
        while (true) {
            if (synchronized(monitor) { terminalFailure != null }) {
                queue.clear()
                return
            }
            val record = try {
                queue.poll(POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (record != null) {
                try {
                    durableSink.append(record)
                } catch (error: Throwable) {
                    synchronized(monitor) {
                        sinkFailures += 1L
                        if (terminalFailure == null) {
                            terminalFailure = TelemetryDispatchException(
                                TelemetryDispatchFailureCode.SINK_FAILURE,
                                error,
                            )
                        }
                        accepting = false
                    }
                    queue.clear()
                    return
                }
            } else if (synchronized(monitor) { !accepting && queue.isEmpty() }) {
                return
            }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 512
        const val POLL_MILLIS = 25L
    }
}

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
    private val dispatcher: BoundedTelemetryDispatcher = BoundedTelemetryDispatcher(sink),
) {
    private val sampler = FixedTelemetrySampler(nowNs, scheduler, probe::sample, sink)
    private val stopMonitor = Any()
    private val stopCompleted = CountDownLatch(1)
    private var stopClaimed = false
    private var stopFailure: Throwable? = null

    fun start() = sampler.start()

    fun stopAndJoinBeforeSeal() {
        val ownsStop = synchronized(stopMonitor) {
            if (!stopClaimed) true.also { stopClaimed = true } else false
        }
        if (ownsStop) {
            var failure: Throwable? = null
            try {
                sampler.stopAndJoinWithTerminalSample()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                dispatcher.fenceAndJoin()
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            synchronized(stopMonitor) { stopFailure = failure }
            stopCompleted.countDown()
        } else {
            var interrupted = false
            while (true) {
                try {
                    stopCompleted.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        synchronized(stopMonitor) { stopFailure }?.let { throw it }
    }
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
