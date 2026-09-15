package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.viewer.experimental.OfflineMovement
import org.junit.Test
import kotlin.test.*

/**
 * 54% of captures in the 2026-09-15 pull-up-bar session were refused for a joint outside the frame
 * and the athlete had no way to know. The advice is derived from the engine's own rejection reasons,
 * so it cannot disagree with what the engine actually demands.
 */
class ViewerMovementCoordinatorFramingTest {

    private fun coordinator(): ViewerMovementCoordinator {
        val c = ViewerMovementCoordinator()
        c.selectOfflineMovement(OfflineMovement.HANGING_KNEE_RAISE)
        return c
    }

    /** A landmark outside 0..1 is exactly what INVALID_LANDMARK means. */
    private fun outOfFrame() = (0..32).map {
        Landmark(index = it, name = null, x = if (it == 24) 1.9 else .5, y = .5, z = .0,
            visibility = .9, presence = .9)
    }

    private fun feed(c: ViewerMovementCoordinator, frames: Int, points: List<Landmark>) {
        for (i in 0 until frames) {
            val t = (i + 1) * 100_000_000L
            c.update(ViewerObservation(ViewerFrame(byteArrayOf(), 400, 400, byteArrayOf()), points, true, t), t)
        }
    }

    @Test fun oneBadFrameIsNotAdvice() {
        val c = coordinator()
        feed(c, 3, outOfFrame())
        assertNull(c.framingAdvice, "a short burst must not raise advice")
    }

    @Test fun aSustainedRunOfOutOfFrameJointsTellsTheAthleteToMoveThePhone() {
        val c = coordinator()
        feed(c, 25, outOfFrame())
        val advice = c.framingAdvice
        assertNotNull(advice)
        assertTrue(advice.contains("outside the frame"), "got: $advice")
    }

    @Test fun clearingResetsTheAdvice() {
        val c = coordinator()
        feed(c, 25, outOfFrame())
        assertNotNull(c.framingAdvice)
        c.clear()
        assertNull(c.framingAdvice)
    }
}
