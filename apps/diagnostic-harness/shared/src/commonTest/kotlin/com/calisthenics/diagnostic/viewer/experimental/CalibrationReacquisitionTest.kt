package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalibrationReacquisitionTest {
    private fun sample(ms: Long, angle: Double = 160.0, wrist: Double = .3) =
        MovementMeasurements(ms * 1_000_000L, ArmMeasurements(angle, wrist, .9),
            ArmMeasurements(160.0, .3, .9), 6)

    private fun feed(e: PullUpExperiment, ms: Long, angle: Double = 160.0, wrist: Double = .3) {
        e.onSample(sample(ms, angle, wrist), ms * 1_000_000L)
    }

    private fun started() = PullUpExperiment().also {
        it.startCalibration(ArmSide.LEFT, 0)
        feed(it, 5_000)
    }

    @Test fun prolongedLossWaitsThenGivesFreshPracticeTimeWithoutButtonOrCountdown() {
        val e = started()
        // Keeping the 250 ms terminal recovery deadline fails this at 5.3 s.
        for (ms in 5_100L..16_000L step 100) {
            feed(e, ms, wrist = -.1)
            assertEquals(ExperimentStage.CALIBRATING, e.state.stage)
            assertEquals(CyclePhase.UNCERTAIN, e.state.phase)
            assertNull(e.state.calibrationEndsNs)
            assertNull(e.state.failure)
            assertEquals(ArmSide.LEFT, e.state.side) // Never silently switch to the valid right arm.
        }
        feed(e, 16_100)
        feed(e, 16_200)
        assertNull(e.state.calibrationEndsNs)
        feed(e, 16_300)
        assertEquals(26_300_000_000L, e.state.calibrationEndsNs)
        assertEquals(CyclePhase.WAITING, e.state.phase)
        for (ms in 16_400L..26_300L step 100) feed(e, ms, when (ms) {
            in 17_000..17_400 -> 100.0
            in 17_500..18_000 -> 40.0
            in 18_100..18_500 -> 100.0
            else -> 160.0
        })
        assertEquals(ExperimentStage.READY, e.state.stage)
        assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), e.state.range)
        assertEquals(0, e.state.candidateCycles)
    }

    @Test fun duplicateBurstsAndAnotherBadFrameCannotCompleteReacquisition() {
        val e = started()
        feed(e, 5_100, wrist = -.1)
        feed(e, 5_200)
        repeat(20) { e.onSample(sample(5_200), 5_400_000_000L) }
        assertNull(e.state.calibrationEndsNs)
        feed(e, 5_201)
        feed(e, 5_202)
        assertNull(e.state.calibrationEndsNs) // Three frames without elapsed dwell aren't stability.
        feed(e, 5_300, wrist = -.1)
        feed(e, 5_400)
        feed(e, 5_500)
        assertNull(e.state.calibrationEndsNs)
        feed(e, 5_600)
        assertEquals(15_600_000_000L, e.state.calibrationEndsNs)
        feed(e, 5_700)
        assertEquals(15_600_000_000L, e.state.calibrationEndsNs) // Not a rolling deadline.
    }

    @Test fun poseLossStaleFrameAndCaptureGapDiscardPracticeInsteadOfAbortingSetup() {
        for (reason in listOf("no_pose", "stale", "capture_gap")) {
            val e = started()
            for (ms in 5_100L..6_000L step 100) feed(e, ms, if (ms < 5_600) 160.0 else 40.0)
            when (reason) {
                "no_pose" -> e.onTrackingGap()
                "stale" -> e.onSample(sample(6_100), 6_400_000_000L)
                else -> feed(e, 6_400)
            }
            assertEquals(ExperimentStage.CALIBRATING, e.state.stage, reason)
            assertNull(e.state.calibrationEndsNs, reason)
            for (ms in 6_500L..17_000L step 100) feed(e, ms)
            assertNull(e.state.range, reason) // A return alone cannot complete the pre-gap half.
            assertEquals(0, e.state.candidateCycles, reason)
        }
    }

    @Test fun largeGapBetweenReacquisitionSamplesCannotWarmTheMedianAcrossMissingMotion() {
        val e = started()
        feed(e, 5_100, wrist = -.1)
        feed(e, 5_200)
        feed(e, 5_300)
        feed(e, 6_000)
        assertNull(e.state.calibrationEndsNs)
        feed(e, 6_100)
        assertNull(e.state.calibrationEndsNs)
        feed(e, 6_200)
        assertEquals(16_200_000_000L, e.state.calibrationEndsNs)
    }
}
