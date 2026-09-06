package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalibrationRecoveryTest {
    private fun input(ms: Long, angle: Double? = 160.0, wrist: Double? = .3, confidence: Double? = .9) =
        MovementMeasurements(ms * 1_000_000L, ArmMeasurements(angle, wrist, confidence),
            ArmMeasurements(null, null, null), 3)

    private fun feed(experiment: PullUpExperiment, ms: Long, angle: Double = 160.0) {
        experiment.onSample(input(ms, angle), ms * 1_000_000L)
    }

    private fun started(): PullUpExperiment = PullUpExperiment().also {
        it.startCalibration(ArmSide.LEFT, 0)
        feed(it, 5_000)
    }

    private fun finish(experiment: PullUpExperiment, fromMs: Long = 5_300, cycle: Boolean = true, untilMs: Long = 15_000) {
        for (ms in fromMs..untilMs step 100) {
            val angle = if (!cycle) 160.0 else when (ms) {
                in 6_000..6_400 -> 100.0
                in 6_500..7_000 -> 40.0
                in 7_100..7_500 -> 100.0
                else -> 160.0
            }
            feed(experiment, ms, angle)
        }
    }

    @Test fun briefUnusableLandmarkRecoversWithoutAnotherCountdownAndRequiresFreshPractice() {
        // Immediate ERROR on a rejected landmark must fail this regression. Synthetic inputs only.
        val glitches = listOf(input(5_200, wrist = -.1), input(5_200, angle = null),
            input(5_200, confidence = .4), input(5_200, wrist = null))
        for (glitch in glitches) {
            val experiment = started()
            feed(experiment, 5_100)
            experiment.onSample(glitch, glitch.capturedNs)
            assertEquals(ExperimentStage.CALIBRATING, experiment.state.stage)
            assertEquals(CyclePhase.UNCERTAIN, experiment.state.phase)
            assertNull(experiment.state.calibrationEndsNs)
            assertNull(experiment.state.failure)
            assertNull(experiment.state.range)
            feed(experiment, 5_300)
            assertEquals(CyclePhase.UNCERTAIN, experiment.state.phase)
            feed(experiment, 5_400)
            assertEquals(CyclePhase.UNCERTAIN, experiment.state.phase)
            feed(experiment, 5_500)
            assertEquals(CyclePhase.WAITING, experiment.state.phase)
            assertEquals(15_500_000_000L, experiment.state.calibrationEndsNs)
            finish(experiment, fromMs = 5_600, untilMs = 15_500)
            assertEquals(ExperimentStage.READY, experiment.state.stage)
            assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), experiment.state.range)
            assertEquals(0, experiment.state.candidateCycles)
        }
    }

    @Test fun interruptedPracticeCannotBeStitchedToTheReturnAfterRecovery() {
        // Retaining pre-glitch samples would manufacture a complete calibration across the gap.
        val experiment = started()
        for (ms in 5_100L..7_000L step 100) feed(experiment, ms, when (ms) {
            in 6_000..6_400 -> 100.0
            in 6_500..7_000 -> 40.0
            else -> 160.0
        })
        val glitch = input(7_100, angle = 0.0, wrist = -.1)
        experiment.onSample(glitch, glitch.capturedNs)
        assertEquals(ExperimentStage.CALIBRATING, experiment.state.stage)
        finish(experiment, fromMs = 7_200, cycle = false, untilMs = 17_400)
        assertEquals(ExperimentStage.ERROR, experiment.state.stage)
        assertNull(experiment.state.range)
        assertEquals(0, experiment.state.candidateCycles)
    }

    @Test fun laterRejectedLandmarkPreservesAnAlreadyCompletePracticeForOperatorConfirmation() {
        // A bad frame after a fully observed cycle must not erase that completed evidence.
        val experiment = started()
        for (ms in 5_100L..7_900L step 100) feed(experiment, ms, when (ms) {
            in 6_000..6_400 -> 100.0
            in 6_500..7_000 -> 40.0
            in 7_100..7_400 -> 100.0
            else -> 160.0
        })
        // Completion is now offered at the observed return, before the later bad frame.
        assertEquals(ExperimentStage.READY, experiment.state.stage)
        val bad = input(8_000, angle = 0.0, wrist = -.1)
        experiment.onSample(bad, bad.capturedNs)
        assertEquals(ExperimentStage.READY, experiment.state.stage)
        assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), experiment.state.range)
        assertEquals(0, experiment.state.candidateCycles)
        // Neither the rejected angle nor later valid motion may expand the frozen range.
        feed(experiment, 8_100, 180.0)
        assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), experiment.state.range)
        assertEquals(ExperimentStage.READY, experiment.state.stage)
        experiment.confirmCalibration()
        assertEquals(ExperimentStage.COUNTING, experiment.state.stage)
        assertEquals(0, experiment.state.candidateCycles)
    }

    @Test fun rejectedFramesCannotStartANewPracticeWindow() {
        val experiment = started()
        for (ms in listOf(5_100L, 5_200L)) {
            val bad = input(ms, wrist = -.1)
            experiment.onSample(bad, bad.capturedNs)
            assertEquals(ExperimentStage.CALIBRATING, experiment.state.stage)
        }
        val bad = input(5_250, wrist = -.1)
        experiment.onSample(bad, bad.capturedNs)
        assertEquals(ExperimentStage.CALIBRATING, experiment.state.stage)
        assertNull(experiment.state.calibrationEndsNs)
        assertNull(experiment.state.range)
    }

    @Test fun recoveryBeyondTheCountingGapStillRequiresFreshWarmup() {
        for (delayMs in listOf(249L, 250L)) {
            val experiment = started()
            val bad = input(5_100, wrist = -.1)
            experiment.onSample(bad, bad.capturedNs)
            // Duplicates are not new evidence and cannot complete warmup.
            experiment.onSample(bad, bad.capturedNs + 100_000_000L)
            feed(experiment, 5_000 + delayMs)
            assertEquals(ExperimentStage.CALIBRATING, experiment.state.stage, "recovery delay $delayMs")
            assertNull(experiment.state.calibrationEndsNs)
            assertNull(experiment.state.range)
        }
    }

    @Test fun poseLossAndStalenessWaitButReversedTimeStillBlocksCalibration() {
        for (reason in listOf("no_pose", "stale", "backwards")) {
            val experiment = started()
            val bad = input(5_100, wrist = -.1)
            experiment.onSample(bad, bad.capturedNs)
            assertEquals(ExperimentStage.CALIBRATING, experiment.state.stage)
            when (reason) {
                "no_pose" -> experiment.onTrackingGap()
                "stale" -> experiment.onSample(input(5_200), 5_450_000_000L)
                else -> experiment.onSample(input(5_050), 5_200_000_000L)
            }
            assertEquals(if (reason == "backwards") ExperimentStage.ERROR else ExperimentStage.CALIBRATING,
                experiment.state.stage, reason)
            assertNull(experiment.state.calibrationEndsNs)
            assertNull(experiment.state.range)
        }
    }

    @Test fun resetErasesRecoveryAndDoesNotReusePracticeFromPreviousSession() {
        val experiment = started()
        val bad = input(5_100, wrist = -.1)
        experiment.onSample(bad, bad.capturedNs)
        experiment.reset()
        assertEquals(PullUpExperimentState(), experiment.state)
        experiment.startCalibration(ArmSide.LEFT, 0)
        finish(experiment, fromMs = 5_000)
        assertEquals(ExperimentStage.READY, experiment.state.stage)
    }
}
