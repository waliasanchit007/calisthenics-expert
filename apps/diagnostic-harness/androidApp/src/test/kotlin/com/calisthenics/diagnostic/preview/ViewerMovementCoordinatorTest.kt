package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.viewer.experimental.ArmSide
import com.calisthenics.diagnostic.viewer.experimental.ExperimentStage
import org.junit.Test
import kotlin.test.*

class ViewerMovementCoordinatorTest {
    private fun raisedObservation(timeNs: Long) = observation(timeNs).copy(landmarks = listOf(
        Landmark(11,null,.5,.5,0.0,.9,.9),
        Landmark(13,null,.75,.5,0.0,.9,.9),
        Landmark(15,null,.75,.25,0.0,.9,.9),
    ))

    private fun calibratingCoordinator(): ViewerMovementCoordinator = ViewerMovementCoordinator().also {
        it.startCalibration(ArmSide.LEFT,0)
        it.update(raisedObservation(5_000_000_000),5_180_000_000)
        assertEquals(ExperimentStage.CALIBRATING,it.experiment.state.stage)
    }

    @Test fun measurementExpiryWhileNextFrameIsInFlightDoesNotCancelCalibration() {
        val coordinator = calibratingCoordinator()
        val deadline = coordinator.experiment.state.calibrationEndsNs
        // Capture 5.0/result 5.18; capture 5.12/result 5.30. At 5.25 the UI has no fresh
        // measurement, but the captures are only 120 ms apart and both results are fresh.
        coordinator.update(null,5_250_000_000)
        assertNull(coordinator.measurements)
        assertNull(coordinator.trace.last().leftDegrees)
        assertEquals(ExperimentStage.CALIBRATING,coordinator.experiment.state.stage)
        coordinator.update(raisedObservation(5_120_000_000),5_300_000_000)
        assertNotNull(coordinator.measurements)
        assertEquals(ExperimentStage.CALIBRATING,coordinator.experiment.state.stage)
        assertEquals(deadline,coordinator.experiment.state.calibrationEndsNs)
        assertEquals(0,coordinator.experiment.state.candidateCycles)
    }

    @Test fun missingResultWaitIsBoundedAndRepeatedTicksCannotRenewIt() {
        val coordinator = calibratingCoordinator()
        coordinator.update(null,5_250_000_000)
        coordinator.update(null,5_400_000_000)
        coordinator.update(null,5_499_999_999)
        assertEquals(ExperimentStage.CALIBRATING,coordinator.experiment.state.stage)
        // Accepted capture gap <250ms and result age <250ms imply arrival <500ms.
        coordinator.update(null,5_500_000_000)
        assertEquals(MovementFeedStatus.RESULT_TIMEOUT, coordinator.feedStatus)
        assertEquals(ExperimentStage.CALIBRATING,coordinator.experiment.state.stage)
        assertNull(coordinator.experiment.state.calibrationEndsNs)
        assertNull(coordinator.experiment.state.range)
        // Expiry still discards practice; setup alone resumes on three fresh observations.
        for (ms in listOf(5_600L,5_700L,5_800L)) coordinator.update(
            raisedObservation(ms * 1_000_000), (ms + 180) * 1_000_000)
        assertEquals(15_800_000_000L, coordinator.experiment.state.calibrationEndsNs)
    }

    @Test fun actualNoPoseResultPausesCalibrationImmediately() {
        val coordinator = calibratingCoordinator()
        coordinator.update(raisedObservation(5_120_000_000).copy(posePresent=false,landmarks=emptyList()),5_200_000_000)
        assertEquals(ExperimentStage.CALIBRATING,coordinator.experiment.state.stage)
        assertNull(coordinator.experiment.state.calibrationEndsNs)
        assertNull(coordinator.measurements)
    }

    @Test fun actualCaptureGapDiscardsPracticeEvenWhenResultIsFresh() {
        val coordinator = calibratingCoordinator()
        coordinator.update(raisedObservation(5_250_000_000),5_400_000_000)
        assertEquals(ExperimentStage.CALIBRATING,coordinator.experiment.state.stage)
        assertNull(coordinator.experiment.state.calibrationEndsNs)
        assertNull(coordinator.experiment.state.range)
    }

    @Test fun backwardsCaptureIsStillTerminalRatherThanRecoverableSetupLoss() {
        val coordinator = calibratingCoordinator()
        coordinator.update(raisedObservation(4_990_000_000),5_180_000_000)
        assertEquals(MovementFeedStatus.BACKWARDS, coordinator.feedStatus)
        assertEquals(ExperimentStage.ERROR, coordinator.experiment.state.stage)
        assertNull(coordinator.experiment.state.range)
    }

    @Test fun completePracticeAndCountWorkWithExpiryTicksBetweenFreshResults() {
        val coordinator = ViewerMovementCoordinator()
        coordinator.startCalibration(ArmSide.LEFT,0)
        var previous: Long? = null
        fun deliver(index: Int, wristX: Double, wristY: Double) {
            val capture = 5_000_000_000L + index * 120_000_000L
            previous?.let { coordinator.update(null,it + 250_000_000L) }
            val sample = observation(capture).copy(landmarks=listOf(
                Landmark(11,null,.5,.6,0.0,.9,.9),
                Landmark(13,null,.5,.4,0.0,.9,.9),
                Landmark(15,null,wristX,wristY,0.0,.9,.9),
            ))
            coordinator.update(sample,capture + 180_000_000L)
            previous = capture
        }
        // Literal high(180), middle(90), low(0), middle, high sequence, then remain
        // extended until the ten-second calibration closes. Wrist stays above shoulder.
        for (i in 0..84) when (i) {
            in 10..19, in 30..39 -> deliver(i,.65,.4)
            in 20..29 -> deliver(i,.5,.55)
            else -> deliver(i,.5,.25)
        }
        assertEquals(ExperimentStage.READY,coordinator.experiment.state.stage)
        assertEquals(0,coordinator.experiment.state.candidateCycles)
        coordinator.experiment.confirmCalibration()
        val wrists = listOf(.5 to .25,.5 to .25,.65 to .4,.5 to .55,
            .5 to .55,.65 to .4,.5 to .25,.5 to .25)
        wrists.forEachIndexed { i, (x,y) -> deliver(85+i,x,y) }
        assertEquals(ExperimentStage.COUNTING,coordinator.experiment.state.stage)
        assertEquals(1,coordinator.experiment.state.candidateCycles)
    }

    private fun observation(timeNs: Long) = ViewerObservation(
        ViewerFrame(byteArrayOf(), 400, 400, byteArrayOf()),
        listOf(Landmark(11,null,.25,.25,0.0,.9,.9),
            Landmark(13,null,.25,.5,0.0,.9,.9),Landmark(15,null,.5,.5,0.0,.9,.9)),
        true,timeNs,
    )

    @Test fun presentationHeldPoseNeverBecomesFreshMovementData() {
        val coordinator = ViewerMovementCoordinator()
        val display = ViewerOverlayPresenter()
        val pose = observation(0)
        coordinator.update(pose,180_000_000)
        display.present(pose,180_000_000)
        assertEquals(90.0, coordinator.measurements!!.left.elbowDegrees!!,1e-5)
        coordinator.update(null,250_000_000)
        assertNotNull(display.present(null,250_000_000))
        assertNull(coordinator.measurements)
        assertNull(coordinator.trace.last().leftDegrees)
    }

    @Test fun repeatedUiReadsDoNotDuplicateMeasurementsOrAdvanceCalibration() {
        val coordinator = ViewerMovementCoordinator()
        val pose = observation(0)
        coordinator.startCalibration(ArmSide.LEFT,0)
        coordinator.update(pose,0)
        coordinator.update(pose,100_000_000)
        assertEquals(1,coordinator.trace.size)
        assertEquals(ExperimentStage.COUNTDOWN,coordinator.experiment.state.stage)
        coordinator.update(null,16_000_000_000)
        assertNull(coordinator.experiment.state.range)
        assertEquals(0,coordinator.experiment.state.candidateCycles)
    }

    @Test fun stopAndRestartClearMeasurementsTraceAndCalibration() {
        val coordinator = ViewerMovementCoordinator()
        coordinator.update(observation(0),0)
        coordinator.startCalibration(ArmSide.RIGHT,0)
        coordinator.clear()
        assertNull(coordinator.measurements)
        assertTrue(coordinator.trace.isEmpty())
        assertEquals(ExperimentStage.IDLE,coordinator.experiment.state.stage)
        assertNull(coordinator.experiment.state.range)
        assertEquals(0,coordinator.experiment.state.candidateCycles)
    }

    @Test fun traceIsBoundedAndMissingSamplesBreakIt() {
        val coordinator = ViewerMovementCoordinator()
        repeat(160) { val time = it * 20_000_000L; coordinator.update(observation(time),time) }
        assertTrue(coordinator.trace.size <= 90)
        coordinator.update(observation(3_210_000_000).copy(posePresent=false,landmarks=emptyList()),3_210_000_000)
        assertNull(coordinator.measurements)
        assertNull(coordinator.trace.last().leftDegrees)
        coordinator.update(null,10_000_000_000)
        assertTrue(coordinator.trace.isEmpty())
    }
}
