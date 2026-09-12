package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.viewer.experimental.ExperimentalMovement
import com.calisthenics.diagnostic.viewer.experimental.OfflineMovement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

/**
 * Regression for a real defect: selecting an exploratory movement highlighted its chip but left the
 * whole downstream panel on the calibrated pull-up flow, so the screen said "practice one pull-up"
 * while a knee-raise engine was being fed. The selection reached the analyser and never reached the
 * UI. These tests pin that the panel state follows the selection at every point it is rebuilt.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineMovementPanelRoutingTest {

    @Test fun selectingAnExploratoryMovementRoutesThePanelAwayFromTheCalibratedFlow() {
        val owner = Robolectric.buildActivity(MovementViewerActivity::class.java).setup()
        try {
            val activity = owner.get()
            assertNull(activity.movementState.offlineMovement, "default must be the calibrated flow")

            assertTrue(activity.selectOfflineMovement(OfflineMovement.HANGING_KNEE_RAISE))
            assertEquals(
                OfflineMovement.HANGING_KNEE_RAISE,
                activity.movementState.offlineMovement,
                "the panel was still rendering the calibrated experiment",
            )
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun switchingBackToACalibratedMovementRestoresTheCalibratedPanel() {
        val owner = Robolectric.buildActivity(MovementViewerActivity::class.java).setup()
        try {
            val activity = owner.get()
            assertTrue(activity.selectOfflineMovement(OfflineMovement.TOES_TO_BAR))
            assertNotNull(activity.movementState.offlineMovement)

            assertTrue(activity.selectMovement(ExperimentalMovement.PUSH_UP))
            assertNull(
                activity.movementState.offlineMovement,
                "the exploratory panel survived a switch back to a calibrated movement",
            )
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun everyExploratoryMovementReachesThePanel() {
        // The defect was per-construction-site, not per-movement, so check them all rather than one.
        val owner = Robolectric.buildActivity(MovementViewerActivity::class.java).setup()
        try {
            val activity = owner.get()
            for (movement in OfflineMovement.entries) {
                assertTrue(activity.selectOfflineMovement(movement), "$movement was refused")
                assertEquals(movement, activity.movementState.offlineMovement, "$movement did not reach the panel")
            }
        } finally { owner.pause().stop().destroy() }
    }
}
