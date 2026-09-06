package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import kotlin.test.*

class PushUpExperimentTest {
    private fun feed(e: PullUpExperiment, ms: Long, angle: Double, wrist: Double = -.3) {
        val ns = ms * 1_000_000
        e.onSample(MovementMeasurements(ns, ArmMeasurements(angle, wrist, .9), ArmMeasurements(), 3), ns)
    }

    private fun practice(e: PullUpExperiment) {
        e.startCalibration(ArmSide.LEFT, 0)
        for (ms in 5_000L..8_000L step 100) feed(e, ms, when (ms) {
            in 6_000..6_400, in 7_100..7_400 -> 100.0
            in 6_500..7_000 -> 40.0
            else -> 160.0
        })
    }

    @Test fun belowShoulderPracticeRequiresConfirmationAndDoesNotCountItself() {
        val e = CalibratedArmCycleExperiment(ExperimentalMovement.PUSH_UP)
        practice(e)
        assertEquals(ExperimentStage.READY, e.state.stage)
        assertEquals(0, e.state.candidateCycles)
        e.confirmCalibration()
        assertEquals(ExperimentStage.COUNTING, e.state.stage)
        assertEquals(0, e.state.candidateCycles)
        for (ms in 8_100L..10_000L step 100) feed(e, ms, when (ms) {
            in 8_500..8_700, in 9_200..9_400 -> 100.0
            in 8_800..9_100 -> 40.0
            else -> 160.0
        })
        assertEquals(1, e.state.candidateCycles)
        assertEquals(CyclePhase.EXTENDED, e.state.phase)
    }

    @Test fun wristSignConstrainsSetupNotTheObservedBottomOfMotion() {
        val e = CalibratedArmCycleExperiment(ExperimentalMovement.PUSH_UP)
        e.startCalibration(ArmSide.LEFT,0)
        for ((i,wrist) in listOf(0.0,.3,Double.NaN).withIndex()) {
            feed(e,5_000L+i*100,160.0,wrist)
            assertEquals(ExperimentStage.COUNTDOWN,e.state.stage)
        }
        for (ms in 5_300L..8_300L step 100) feed(e,ms,when(ms) {
            in 6_300..6_700, in 7_400..7_700 -> 100.0
            in 6_800..7_300 -> 40.0
            else -> 160.0
        },wrist=if(ms in 6_800..7_300) .01 else -.3)
        assertEquals(ExperimentStage.READY,e.state.stage)
        e.confirmCalibration()
        for (ms in 8_400L..10_000L step 100) feed(e,ms,when(ms) {
            in 8_800..9_000, in 9_500..9_600 -> 100.0
            in 9_100..9_400 -> 40.0
            else -> 160.0
        },wrist=if(ms in 9_100..9_400) 0.0 else -.3)
        assertEquals(1,e.state.candidateCycles)
    }

    @Test fun partialAndInterruptedMotionCannotCountAndRecoveryKeepsCompletedCount() {
        val e = CalibratedArmCycleExperiment(ExperimentalMovement.PUSH_UP)
        practice(e)
        e.confirmCalibration()
        var ms=8_100L
        fun sequence(vararg angles: Double) { for(angle in angles) { feed(e,ms,angle); ms+=100 } }
        sequence(160.0,160.0,160.0,100.0,100.0,160.0,160.0,160.0)
        assertEquals(0,e.state.candidateCycles)
        assertEquals(CycleOutcome.TOP_NOT_CONFIRMED,e.state.lastCycleOutcome)
        assertTrue(e.state.message.contains("bottom"))
        // Supported brief reversal and the return-to-extended dwell, no duplicated-frame time.
        sequence(130.0,100.0,50.0,45.0,90.0,110.0,160.0,160.0,160.0)
        assertEquals(1,e.state.candidateCycles)
        sequence(100.0,40.0,40.0,40.0)
        e.onTrackingGap()
        assertEquals(1,e.state.candidateCycles)
        assertEquals(CyclePhase.UNCERTAIN,e.state.phase)
        sequence(160.0,160.0,160.0)
        assertEquals(1,e.state.candidateCycles)
        sequence(100.0,40.0,40.0,40.0,100.0,160.0,160.0,160.0)
        assertEquals(2,e.state.candidateCycles)
        e.reset()
        assertEquals(0,e.state.candidateCycles)
        assertNull(e.state.range)
        assertEquals(ExperimentalMovement.PUSH_UP,e.state.movement)
    }

    @Test fun invalidInputsDoNotFinishAPendingPushUp() {
        for (kind in listOf("missing", "nonfinite", "stale", "duplicate", "backwards")) {
            val e = CalibratedArmCycleExperiment(ExperimentalMovement.PUSH_UP)
            practice(e); e.confirmCalibration()
            for(ms in 8_100L..8_500L step 100) feed(e,ms,160.0)
            feed(e,8_600,100.0)
            val ns=8_700_000_000L
            when(kind) {
                "missing" -> e.onSample(MovementMeasurements(ns,ArmMeasurements(),ArmMeasurements(),0),ns)
                "nonfinite" -> feed(e,8_700,40.0,Double.NaN)
                "stale" -> e.onSample(MovementMeasurements(ns,ArmMeasurements(40.0,-.3,.9),ArmMeasurements(),3),ns+250_000_000)
                "duplicate" -> repeat(4) { feed(e,8_600,40.0) }
                "backwards" -> feed(e,8_500,40.0)
            }
            for(ms in 8_800L..9_100L step 100) feed(e,ms,160.0)
            assertEquals(0,e.state.candidateCycles,kind)
        }
    }

    @Test fun defaultPullUpStillRejectsBelowShoulderPractice() {
        val e=PullUpExperiment()
        practice(e)
        assertEquals(ExperimentStage.COUNTDOWN,e.state.stage)
        assertEquals(ExperimentInputIssue.WRIST_NOT_ABOVE_SHOULDER,e.inputIssue)
        assertEquals(0,e.state.candidateCycles)
    }
}
