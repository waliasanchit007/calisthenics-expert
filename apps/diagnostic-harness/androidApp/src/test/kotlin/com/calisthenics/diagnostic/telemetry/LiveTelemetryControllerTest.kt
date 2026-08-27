package com.calisthenics.diagnostic.telemetry

import com.calisthenics.diagnostic.composition.LiveTelemetryController
import com.calisthenics.diagnostic.composition.LiveTelemetryProbe
import com.calisthenics.diagnostic.composition.DedicatedTelemetryScheduler
import com.calisthenics.diagnostic.composition.BoundedTelemetryDispatcher
import com.calisthenics.diagnostic.composition.TelemetryDispatchException
import com.calisthenics.diagnostic.composition.TelemetryDispatchFailureCode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class LiveTelemetryControllerTest {
    @Test
    fun stageAndFixedSampleProducersShareOneTimestampAndEnqueueSerializer() {
        val firstClockEntered = CountDownLatch(1)
        val releaseFirstClock = CountDownLatch(1)
        val clockCalls = AtomicInteger(0)
        val durableRecords = CopyOnWriteArrayList<TelemetryRecord>()
        val dispatcher = BoundedTelemetryDispatcher(TelemetrySink(durableRecords::add), capacity = 4)
        val recorder = PipelineStageRecorder(
            nowNs = {
                if (clockCalls.incrementAndGet() == 1) {
                    firstClockEntered.countDown()
                    check(releaseFirstClock.await(1, TimeUnit.SECONDS))
                    10L
                } else {
                    20L
                }
            },
            sink = dispatcher,
        )
        val stageProducer = Thread {
            recorder.record(0L, PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED)
        }
        val sampleProducer = Thread {
            recorder.recordFixedSample(unavailableReadings(), terminal = false)
        }

        try {
            stageProducer.start()
            assertTrue(firstClockEntered.await(1, TimeUnit.SECONDS))
            sampleProducer.start()
            releaseFirstClock.countDown()
            stageProducer.join(1_000L)
            sampleProducer.join(1_000L)
            dispatcher.fenceAndJoin()

            assertEquals(listOf(10L, 20L), durableRecords.map(::recordTimeNs))
            assertIs<PipelineStageEvent>(durableRecords[0])
            assertIs<FixedTelemetrySample>(durableRecords[1])
        } finally {
            releaseFirstClock.countDown()
            stageProducer.join(1_000L)
            sampleProducer.join(1_000L)
            runCatching { dispatcher.fenceAndJoin() }
        }
    }

    @Test
    fun analyzerProducerReturnsBeforeBlockedDurableSinkIsReleased() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val dispatcher = BoundedTelemetryDispatcher(
            durableSink = TelemetrySink {
                sinkEntered.countDown()
                check(releaseSink.await(1, TimeUnit.SECONDS))
            },
            capacity = 2,
        )

        try {
            dispatcher.append(stageEvent(0L))
            assertTrue(sinkEntered.await(1, TimeUnit.SECONDS))
            val producerReturned = CountDownLatch(1)
            Thread {
                dispatcher.append(stageEvent(1L))
                producerReturned.countDown()
            }.start()

            assertTrue(
                producerReturned.await(100, TimeUnit.MILLISECONDS),
                "analyzer producer blocked on durable telemetry I/O",
            )
        } finally {
            releaseSink.countDown()
            dispatcher.fenceAndJoin()
        }
    }

    @Test
    fun boundedDispatcherOverflowIsCountedAndTyped() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val dispatcher = BoundedTelemetryDispatcher(
            durableSink = TelemetrySink {
                sinkEntered.countDown()
                check(releaseSink.await(1, TimeUnit.SECONDS))
            },
            capacity = 1,
        )

        try {
            dispatcher.append(stageEvent(0L))
            assertTrue(sinkEntered.await(1, TimeUnit.SECONDS))
            dispatcher.append(stageEvent(1L))

            val failure = assertFailsWith<TelemetryDispatchException> {
                dispatcher.append(stageEvent(2L))
            }
            assertEquals(TelemetryDispatchFailureCode.QUEUE_OVERFLOW, failure.failureCode)
            assertEquals(1L, dispatcher.snapshot().queueOverflows)
        } finally {
            releaseSink.countDown()
            assertFailsWith<TelemetryDispatchException> { dispatcher.fenceAndJoin() }
        }
    }

    @Test
    fun dispatcherFenceDrainsQueuedWritesBeforeJoinReturns() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val fenceReturned = CountDownLatch(1)
        val dispatcher = BoundedTelemetryDispatcher(
            durableSink = TelemetrySink {
                sinkEntered.countDown()
                check(releaseSink.await(1, TimeUnit.SECONDS))
            },
            capacity = 2,
        )
        dispatcher.append(stageEvent(0L))
        assertTrue(sinkEntered.await(1, TimeUnit.SECONDS))
        Thread {
            dispatcher.fenceAndJoin()
            fenceReturned.countDown()
        }.start()

        assertFalse(fenceReturned.await(100, TimeUnit.MILLISECONDS))
        releaseSink.countDown()
        assertTrue(fenceReturned.await(1, TimeUnit.SECONDS))
        assertTrue(dispatcher.snapshot().joined)
    }

    @Test
    fun dispatcherRejectsAndCountsEveryPostFenceAppend() {
        val dispatcher = BoundedTelemetryDispatcher(TelemetrySink {}, capacity = 1)
        dispatcher.fenceAndJoin()

        val failure = assertFailsWith<TelemetryDispatchException> {
            dispatcher.append(stageEvent(0L))
        }

        assertEquals(TelemetryDispatchFailureCode.POST_FENCE_APPEND, failure.failureCode)
        assertEquals(1L, dispatcher.snapshot().postFenceRejections)
    }

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

    private fun stageEvent(sequence: Long) = PipelineStageEvent(
        sequence = sequence,
        tSessionNs = sequence,
        frameIndex = sequence,
        stage = PipelineStage.CAMERA_CALLBACK,
        disposition = PipelineDisposition.RECEIVED,
    )

    private fun recordTimeNs(record: TelemetryRecord): Long = when (record) {
        is PipelineStageEvent -> record.tSessionNs
        is FixedTelemetrySample -> record.tSessionNs
        is SustainedStageCounterSample -> record.tSessionNs
    }
}
