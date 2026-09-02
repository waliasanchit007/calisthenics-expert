package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.study.M05BSustainedBatterySummary
import com.calisthenics.diagnostic.study.M05BSustainedLatencySummary
import com.calisthenics.diagnostic.study.M05BSustainedProcessOutcome
import com.calisthenics.diagnostic.study.M05BSustainedRuntimeEvidence
import com.calisthenics.diagnostic.study.M05BSustainedSourceOutcome
import com.calisthenics.diagnostic.study.M05BSustainedStageAccounting
import com.calisthenics.diagnostic.study.M05BSustainedTelemetrySummary
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class M05BSustainedRunControllerTest {
    @Test
    fun schedulesTheExactRemainingDurationFromTheAdmittedSessionClock() {
        val run = FakeSustainedRun(elapsedNs = 25L)
        val scheduler = CapturingScheduler()
        val observations = mutableListOf<M05BSustainedStopObservation>()
        val controller = M05BSustainedRunController(run, scheduler, observations::add)

        assertIs<LiveCameraStartResult.Started>(controller.start(10))

        assertEquals(1_500_000_000_000L - 25L, scheduler.delayNs)
        run.elapsedNs = 1_500_000_000_000L
        scheduler.fire()
        assertEquals(1, run.stopCount)
        assertEquals(1_500_000_000_000L, observations.single().requestedAtNs)
        assertEquals(1_500_000_000_000L, observations.single().invokedAtNs)
        assertEquals(emptyList(), observations.single().blockingCodes)
        assertEquals(run.stopEvidence, observations.single().runtimeEvidence)
    }

    @Test
    fun aLateSchedulerRecordsTheObservedOvershootInsteadOfNormalizingIt() {
        val run = FakeSustainedRun(elapsedNs = 0L)
        val scheduler = CapturingScheduler()
        val observations = mutableListOf<M05BSustainedStopObservation>()
        val controller = M05BSustainedRunController(run, scheduler, observations::add)
        assertIs<LiveCameraStartResult.Started>(controller.start(15))

        run.elapsedNs = 1_501_000_000_001L
        scheduler.fire()

        assertEquals(1_501_000_000_001L, observations.single().invokedAtNs)
        assertTrue("DURATION.OVERSHOOT" in observations.single().blockingCodes)
    }

    @Test
    fun blockedAdmissionSchedulesNothingAndControllerIsSingleUse() {
        val run = FakeSustainedRun(
            elapsedNs = 0L,
            startResult = LiveCameraStartResult.Unavailable(
                LiveUnavailableReason.RunAdmissionBlocked(listOf("THERMAL.RANGE")),
            ),
        )
        val scheduler = CapturingScheduler()
        val controller = M05BSustainedRunController(run, scheduler) {}

        val result = controller.start(30)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(null, scheduler.delayNs)
        assertEquals(0, run.stopCount)
        assertFailsWith<IllegalStateException> { controller.start(30) }
    }

    private class FakeSustainedRun(
        var elapsedNs: Long,
        private val startResult: LiveCameraStartResult = LiveCameraStartResult.Started(
            Uuid("00000000-0000-4000-8000-000000000010"),
        ),
    ) : M05BSustainedLiveRun {
        val stopEvidence = runtimeEvidence()
        var stopCount = 0
        var startedCadence: Int? = null

        override fun start(cadenceFps: Int): LiveCameraStartResult {
            startedCadence = cadenceFps
            return startResult
        }

        override fun stop(): M05BSustainedRuntimeEvidence {
            stopCount++
            return stopEvidence
        }

        override fun elapsedNs(): Long = elapsedNs
    }

    companion object {
        private fun runtimeEvidence() = M05BSustainedRuntimeEvidence(
            actualCaptureSpanNs = 1_500_000_000_000L,
            stages = M05BSustainedStageAccounting(1, 1, 1, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0),
            latency = M05BSustainedLatencySummary(listOf(1L) + List(13) { 0L }),
            telemetry = M05BSustainedTelemetrySummary(1_502, 0, 1_500_000_000_000L, 1_000_000_000L, true),
            battery = M05BSustainedBatterySummary(32.0, 33.0, 1.0),
            sourceOutcome = M05BSustainedSourceOutcome.COMPLETED,
            processOutcome = M05BSustainedProcessOutcome.COMPLETED,
        )
    }

    private class CapturingScheduler : LiveScheduler {
        var delayNs: Long? = null
        private var action: (() -> Unit)? = null

        override fun scheduleAfter(delayNs: Long, action: () -> Unit): Disposable {
            this.delayNs = delayNs
            this.action = action
            return object : Disposable {
                override fun dispose() {
                    this@CapturingScheduler.action = null
                }
            }
        }

        fun fire() = checkNotNull(action).invoke()
    }
}
