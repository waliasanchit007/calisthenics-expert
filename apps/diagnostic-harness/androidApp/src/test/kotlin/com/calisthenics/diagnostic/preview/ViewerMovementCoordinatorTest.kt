package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.viewer.experimental.ArmSide
import com.calisthenics.diagnostic.viewer.experimental.ExperimentStage
import org.junit.Test
import kotlin.test.*

class ViewerMovementCoordinatorTest {
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
