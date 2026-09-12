package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.viewer.experimental.ArmSide
import com.calisthenics.diagnostic.viewer.experimental.ExperimentStage
import com.calisthenics.diagnostic.viewer.experimental.ExperimentalMovement
import com.calisthenics.diagnostic.viewer.experimental.MuscleUpVariant
import com.calisthenics.diagnostic.viewer.experimental.OfflineMovement
import org.junit.Test
import kotlin.test.*

/**
 * The offline engines share the coordinator with the calibrated pull-up/push-up experiment, so what
 * matters here is that they stay SEPARATE: the same lifecycle rules apply, the calibrated count is
 * never advanced while an offline movement is selected, and selecting either way round leaves no
 * residue from the other.
 */
class ViewerOfflineMovementCoordinatorTest {

    private fun observation(timeNs: Long) = ViewerObservation(
        ViewerFrame(byteArrayOf(), 400, 400, byteArrayOf()),
        (0..32).map { Landmark(it, null, .5, .5, .0, .9, .9) },
        true, timeNs,
    )

    @Test fun theDefaultSelectionIsStillTheCalibratedPullUp() {
        val coordinator = ViewerMovementCoordinator()
        assertEquals(
            ViewerMovementSelection.Calibrated(ExperimentalMovement.PULL_UP),
            coordinator.selection,
        )
        assertNull(coordinator.offlineUpdate)
    }

    @Test fun selectingAnOfflineMovementIsRefusedWhileASessionIsActive() {
        // Same rule the calibrated path already enforces: the analyser cannot change underneath a
        // running capture.
        val coordinator = ViewerMovementCoordinator()
        coordinator.beginSession()
        assertFalse(coordinator.selectOfflineMovement(OfflineMovement.TOES_TO_BAR))
        assertEquals(
            ViewerMovementSelection.Calibrated(ExperimentalMovement.PULL_UP),
            coordinator.selection,
        )
        coordinator.endSession()
        assertTrue(coordinator.selectOfflineMovement(OfflineMovement.TOES_TO_BAR))
    }

    @Test fun anOfflineMovementIsFedAndReportsThroughOfflineUpdate() {
        val coordinator = ViewerMovementCoordinator()
        assertTrue(coordinator.selectOfflineMovement(OfflineMovement.HANGING_KNEE_RAISE))
        coordinator.beginSession()
        coordinator.update(observation(0), 0)

        val update = assertNotNull(coordinator.offlineUpdate, "offline engine was not fed")
        assertEquals(0L, update.timestampNs)
        assertNotNull(coordinator.measurements, "the arm trace must still be produced for the UI")
        coordinator.endSession()
    }

    @Test fun theCalibratedExperimentIsNotAdvancedWhileAnOfflineMovementIsSelected() {
        // The risk this guards: a calibrated count ticking along in the background and being read
        // as though it belonged to the offline movement on screen.
        val coordinator = ViewerMovementCoordinator()
        assertTrue(coordinator.selectOfflineMovement(OfflineMovement.DIP))
        coordinator.beginSession()
        for (index in 0..20) coordinator.update(observation(index * 33_000_000L), index * 33_000_000L)

        assertEquals(ExperimentStage.IDLE, coordinator.experiment.state.stage)
        assertEquals(0, coordinator.experiment.state.candidateCycles)
        coordinator.endSession()
    }

    @Test fun switchingBackToACalibratedMovementStopsFeedingTheOfflineEngine() {
        val coordinator = ViewerMovementCoordinator()
        assertTrue(coordinator.selectOfflineMovement(OfflineMovement.DIP))
        coordinator.beginSession()
        coordinator.update(observation(0), 0)
        assertNotNull(coordinator.offlineUpdate)
        coordinator.endSession()

        assertTrue(coordinator.selectMovement(ExperimentalMovement.PUSH_UP))
        assertNull(coordinator.offlineUpdate, "offline state survived a switch away")
        coordinator.beginSession()
        coordinator.update(observation(100_000_000L), 100_000_000L)
        assertNull(coordinator.offlineUpdate, "the offline engine was still being fed")
        coordinator.endSession()
    }

    @Test fun clearRestartsTheOfflineEngineAndKeepsTheSelection() {
        val coordinator = ViewerMovementCoordinator()
        assertTrue(coordinator.selectOfflineMovement(OfflineMovement.TOES_TO_BAR, ArmSide.RIGHT))
        coordinator.beginSession()
        coordinator.update(observation(0), 0)
        assertNotNull(coordinator.offlineUpdate)
        coordinator.endSession()

        coordinator.clear()
        assertNull(coordinator.offlineUpdate)
        assertEquals(
            ViewerMovementSelection.Offline(OfflineMovement.TOES_TO_BAR, ArmSide.RIGHT),
            coordinator.selection,
        )
        // Still usable after clear, i.e. the engine was rebuilt rather than dropped.
        coordinator.beginSession()
        coordinator.update(observation(0), 0)
        assertNotNull(coordinator.offlineUpdate, "the offline engine was not rebuilt by clear()")
        coordinator.endSession()
    }

    @Test fun theMuscleUpVariantIsCarriedOnTheSelection() {
        // Rings and bar have separate frozen configs; the selection must remember which.
        val coordinator = ViewerMovementCoordinator()
        assertTrue(
            coordinator.selectOfflineMovement(
                OfflineMovement.MUSCLE_UP, ArmSide.RIGHT, MuscleUpVariant.RING_STRICT,
            ),
        )
        assertEquals(
            ViewerMovementSelection.Offline(
                OfflineMovement.MUSCLE_UP, ArmSide.RIGHT, MuscleUpVariant.RING_STRICT,
            ),
            coordinator.selection,
        )
    }
}
