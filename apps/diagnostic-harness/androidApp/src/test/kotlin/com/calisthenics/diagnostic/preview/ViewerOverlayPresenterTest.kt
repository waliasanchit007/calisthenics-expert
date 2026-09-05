package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import org.junit.Test
import kotlin.test.*

class ViewerOverlayPresenterTest {
    private fun pose(capturedMs: Long) = ViewerObservation(
        ViewerFrame(byteArrayOf(), 1, 1, byteArrayOf()),
        listOf(Landmark(11, null, .5, .5, .0, .8, .9)), true, capturedMs * 1_000_000,
    )

    @Test fun normalInferenceGapDoesNotBlinkWhenRawFreshnessExpires() {
        var now = 0L
        lateinit var complete: (InferenceResult) -> Unit
        val port = object : InferencePort {
            override fun submit(submissionIndex: Long, canonicalInput: ByteArray,
                backendTimestampMs: Long, callback: (InferenceResult) -> Unit) { complete = callback }
        }
        val session = ViewerSession(port, freshnessNs = 250_000_000, clockNs = { now })
        val display = ViewerOverlayPresenter()
        session.offer(0) { pose(0).frame }
        now = 180_000_000
        complete(InferenceResult.Success(now, true, pose(0).landmarks))
        val first = assertNotNull(display.present(session.snapshot(now).observation, now))
        now = 200_000_000
        session.offer(120_000_000) { pose(120).frame }
        now = 250_000_000
        assertNull(session.snapshot(now).observation, "raw freshness remains strict")
        val between = assertNotNull(display.present(session.snapshot(now).observation, now))
        assertSame(first.observation, between.observation)
        assertEquals(1f, between.alpha)
        assertTrue(between.held)
        now = 300_000_000
        complete(InferenceResult.Success(now, true, pose(120).landmarks))
        val next = assertNotNull(display.present(session.snapshot(now).observation, now))
        assertEquals(120_000_000, next.observation.capturedNs)
        assertFalse(next.held)
    }

    @Test fun singleMissDoesNotErasePoseOrRefreshItsCaptureDeadline() {
        val display = ViewerOverlayPresenter()
        val first = pose(0)
        display.present(first, 100_000_000)
        val miss = pose(200).copy(posePresent = false, landmarks = emptyList())
        assertSame(first, assertNotNull(display.present(miss, 250_000_000)).observation)
        val fading = assertNotNull(display.present(miss, 425_000_000))
        assertEquals(.5f, fading.alpha, .0001f)
        assertTrue(fading.held)
        assertNull(display.present(miss, 500_000_000))
        assertNull(display.present(miss, 600_000_000))
    }

    @Test fun noDetectionDoesNotInventAPoseAndLateResultsDoNotGetNewLifetimes() {
        val display = ViewerOverlayPresenter()
        assertNull(display.present(null, 100_000_000))
        assertNull(display.present(pose(0), 500_000_000))
        assertNull(display.present(pose(600), 550_000_000), "future timestamp is invalid")
    }

    @Test fun newerPoseReplacesImmediatelyWithoutInterpolationOrRawMutation() {
        val display = ViewerOverlayPresenter()
        display.present(pose(0), 180_000_000)
        val next = pose(300)
        val observed = assertNotNull(display.present(next, 400_000_000))
        assertSame(next, observed.observation)
        assertEquals(1f, observed.alpha)
        assertFalse(observed.held)
        assertEquals(300_000_000, next.capturedNs)
    }

    @Test fun olderResultCannotReplaceNewerDisplayedPose() {
        val display = ViewerOverlayPresenter()
        val newest = pose(200)
        display.present(newest, 250_000_000)
        assertSame(newest, assertNotNull(display.present(pose(100), 300_000_000)).observation)
    }

    @Test fun clearReleasesHeldPoseImmediately() {
        val display = ViewerOverlayPresenter()
        display.present(pose(0), 100_000_000)
        display.clear()
        assertNull(display.present(null, 110_000_000))
    }
}
