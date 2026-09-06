package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import kotlin.test.*

class CalibrationCompletionTest {
    private fun feed(e: PullUpExperiment, ms: Long, angle: Double, wrist: Double = .3) {
        val ns=ms*1_000_000
        e.onSample(MovementMeasurements(ns,ArmMeasurements(angle,wrist,.9),ArmMeasurements(),3),ns)
    }

    private fun practice(e: PullUpExperiment, finish: Long = 8_000) {
        e.startCalibration(ArmSide.LEFT,0)
        for(ms in 5_000L..finish step 100) feed(e,ms,when(ms) {
            in 6_000..6_400, in 7_100..7_400 -> 100.0
            in 6_500..7_000 -> 40.0
            else -> 160.0
        })
    }

    @Test fun completedPracticeOffersConfirmationBeforeTheWindowEnds() {
        val e=PullUpExperiment()
        practice(e)
        assertEquals(ExperimentStage.READY,e.state.stage)
        assertEquals(ObservedRange(ArmSide.LEFT,40.0,160.0),e.state.range)
        assertEquals(0,e.state.candidateCycles)
        // No auto-confirm or automatic credit for subsequent motion while READY.
        for(ms in 8_100L..10_000L step 100) feed(e,ms,if(ms<9_000)40.0 else 160.0)
        assertEquals(ExperimentStage.READY,e.state.stage)
        assertEquals(0,e.state.candidateCycles)
        e.confirmCalibration()
        assertEquals(ExperimentStage.COUNTING,e.state.stage)
        assertEquals(0,e.state.candidateCycles)
    }

    @Test fun releasingTheBarAfterACompletedPracticeCannotEraseOrExpandIt() {
        val e=PullUpExperiment()
        practice(e)
        feed(e,8_100,100.0) // Start of an incomplete extra motion before releasing the bar.
        feed(e,8_200,20.0)
        feed(e,8_300,0.0,wrist=-.2)
        e.onTrackingGap()
        assertEquals(ExperimentStage.READY,e.state.stage)
        assertEquals(ObservedRange(ArmSide.LEFT,40.0,160.0),e.state.range)
        assertEquals(0,e.state.candidateCycles)
    }

    @Test fun reachingTheTopAloneIsNotACompletedPracticeAndResetErasesIt() {
        val e=PullUpExperiment()
        practice(e,finish=7_000)
        assertEquals(ExperimentStage.CALIBRATING,e.state.stage)
        assertNull(e.state.range)
        e.confirmCalibration()
        assertEquals(ExperimentStage.CALIBRATING,e.state.stage)
        e.reset()
        e.startCalibration(ArmSide.LEFT,8_000_000_000)
        for(ms in 13_000L..14_000L step 100) feed(e,ms,160.0)
        assertEquals(ExperimentStage.CALIBRATING,e.state.stage)
        assertNull(e.state.range)
    }
}
