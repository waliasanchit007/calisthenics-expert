package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.viewer.measureMovement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingGuidanceTest {
    private fun joints() = listOf(
        Landmark(11,null,.50,.50,0.0,.95,.95),
        Landmark(13,null,.50,.30,0.0,.95,.95),
        Landmark(15,null,.50,.10,0.0,.95,.95),
    )

    @Test fun wristOutsideImageIsNotReportedAsMissingElbowAndCannotStartPractice() {
        val e = PullUpExperiment()
        e.startCalibration(ArmSide.LEFT, 0)
        val frame = joints().map { if (it.index == 15) it.copy(y=-.0035) else it }
        val measured = measureMovement(frame,1080,1440,5_000_000_000)
        e.onSample(measured,5_000_000_000)
        assertNull(measured.left.elbowDegrees) // Never clamp or trust the offscreen prediction.
        assertEquals(ExperimentInputIssue.WRIST_UNAVAILABLE,e.inputIssue)
        assertEquals(ExperimentStage.COUNTDOWN,e.state.stage)
        assertNull(e.state.calibrationEndsNs)
        assertTrue(e.state.message.contains("wrist",ignoreCase=true),e.state.message)
        assertTrue(e.state.message.contains("outside",ignoreCase=true),e.state.message)
        assertEquals(0,e.state.candidateCycles)
    }

    @Test fun missingShoulderAndLowConfidenceElbowHaveDifferentActionableReasons() {
        val cases = listOf(
            Triple(joints().filter { it.index != 11 }, "shoulder", "missing"),
            Triple(joints().map { if (it.index==13) it.copy(presence=.4) else it }, "elbow", "confidence"),
            Triple(joints().map { if (it.index==15) it.copy(x=Double.NaN) else it }, "wrist", "invalid"),
        )
        for ((frame,joint,reason) in cases) {
            val e=PullUpExperiment()
            e.startCalibration(ArmSide.LEFT,0)
            e.onSample(measureMovement(frame,400,400,5_000_000_000),5_000_000_000)
            assertTrue(e.state.message.contains(joint,ignoreCase=true),e.state.message)
            assertTrue(e.state.message.contains(reason,ignoreCase=true),e.state.message)
            assertNull(e.state.range)
        }
    }

    @Test fun rejectionDuringPracticeRetainsTheActualMissingJointInPauseGuidance() {
        val e=PullUpExperiment()
        e.startCalibration(ArmSide.LEFT,0)
        e.onSample(measureMovement(joints(),400,400,5_000_000_000),5_000_000_000)
        e.onSample(measureMovement(joints().dropLast(1),400,400,5_100_000_000),5_100_000_000)
        assertEquals(ExperimentStage.CALIBRATING,e.state.stage)
        assertEquals(CyclePhase.UNCERTAIN,e.state.phase)
        assertTrue(e.state.message.contains("wrist",ignoreCase=true),e.state.message)
        assertTrue(e.state.message.contains("missing",ignoreCase=true),e.state.message)
        assertNull(e.state.calibrationEndsNs)
    }

    @Test fun wristBelowShoulderGuidesSetupInsteadOfSilentlyWaitingAtZeroSeconds() {
        val e=PullUpExperiment()
        e.startCalibration(ArmSide.LEFT,0)
        e.onSample(measureMovement(joints().map { if(it.index==15) it.copy(y=.7) else it },
            400,400,5_000_000_000),5_000_000_000)
        assertEquals(ExperimentStage.COUNTDOWN,e.state.stage)
        assertTrue(e.state.message.contains("above",ignoreCase=true),e.state.message)
    }
}
