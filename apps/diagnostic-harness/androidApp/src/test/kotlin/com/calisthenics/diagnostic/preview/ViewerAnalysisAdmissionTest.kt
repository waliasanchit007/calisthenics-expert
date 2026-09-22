package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import com.calisthenics.diagnostic.viewer.experimental.OfflineMovement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ADR-0007 decision 2: analysis admits a result up to the 500 ms result deadline while the display
 * keeps its 250 ms. Without this, overlap delivers ~2% of results past 250 ms and each withheld one
 * leaves a gap wider than the knee raise's 100 ms limit (ledger Update 54: 5 -> 1.5 of 6).
 */
class ViewerAnalysisAdmissionTest {
    private val ms = 1_000_000L

    private fun observation(capturedNs: Long, posePresent: Boolean = true) = ViewerObservation(
        ViewerFrame(byteArrayOf(), 400, 400, byteArrayOf()),
        if (posePresent) (0..32).map { Landmark(it, null, .5, .5, .0, .9, .9) } else emptyList(),
        posePresent, capturedNs,
    )

    private fun kneeRaise() = ViewerMovementCoordinator().also {
        it.selectOfflineMovement(OfflineMovement.HANGING_KNEE_RAISE); it.beginSession()
    }

    @Test fun aResultTheDisplayCallsStaleStillReachesAnalysis() {
        val c = kneeRaise()
        c.update(observation(1_000 * ms), 1_000 * ms + 260 * ms)
        assertNotNull(c.offlineUpdate, "a 260 ms-old result was withheld from the engine")
        assertEquals(MovementFeedStatus.FRESH, c.feedStatus)
        assertEquals(1, c.analysisLate)
        assertEquals(0, c.analysisExpired)
    }

    @Test fun aResultAtTheDeadlineReachesNeither() {
        val c = kneeRaise()
        c.update(observation(1_000 * ms), 1_000 * ms + 500 * ms)
        assertNull(c.offlineUpdate)
        assertEquals(0, c.analysisLate)
        assertEquals(1, c.analysisExpired)
    }

    @Test fun anOnTimeResultIsNeitherLateNorExpired() {
        val c = kneeRaise()
        c.update(observation(1_000 * ms), 1_000 * ms + 180 * ms)
        assertNotNull(c.offlineUpdate)
        assertEquals(0, c.analysisLate)
        assertEquals(0, c.analysisExpired)
    }

    @Test fun theDisplayTickNeverAdmitsAndSoCannotReorderTheStream() {
        val c = kneeRaise()
        c.update(observation(1_000 * ms), 1_180 * ms)
        val fed = c.offlineUpdate
        c.tick(1_200 * ms)
        assertEquals(fed, c.offlineUpdate, "a tick advanced the engine")
        c.update(observation(1_033 * ms), 1_240 * ms)
        assertEquals(MovementFeedStatus.FRESH, c.feedStatus, "the next result in the stream was refused")
    }

    @Test fun theTickExpiresTheShownMeasurementAtTheDisplayCutoff() {
        val c = kneeRaise()
        c.update(observation(1_000 * ms), 1_180 * ms)
        c.tick(1_249 * ms)
        assertNotNull(c.measurements, "expired before 250 ms")
        c.tick(1_250 * ms)
        assertNull(c.measurements, "still shown at 250 ms")
        assertEquals(MovementFeedStatus.WAITING, c.feedStatus)
    }

    @Test fun theTickStillDeclaresATrackingGapAtTheResultDeadline() {
        val c = kneeRaise()
        c.update(observation(1_000 * ms), 1_180 * ms)
        c.tick(1_499 * ms)
        assertEquals(MovementFeedStatus.WAITING, c.feedStatus)
        c.tick(1_500 * ms)
        assertEquals(MovementFeedStatus.RESULT_TIMEOUT, c.feedStatus)
    }

    @Test fun aNoPoseResultKeepsReportingNoPoseWhileItIsTheLatest() {
        // Re-reading the latest snapshot used to report NO_POSE on every tick; the tick must too.
        val c = kneeRaise()
        c.update(observation(1_000 * ms, posePresent = false), 1_180 * ms)
        assertEquals(MovementFeedStatus.NO_POSE, c.feedStatus)
        c.tick(1_200 * ms)
        assertEquals(MovementFeedStatus.NO_POSE, c.feedStatus)
        c.tick(1_260 * ms)
        assertEquals(MovementFeedStatus.RESULT_TIMEOUT, c.feedStatus, "no result has ever arrived")
    }

    @Test fun theSessionHidesAStaleResultFromTheDisplayButNotFromAnalysis() = runBlocking {
        var callback: ((InferenceResult) -> Unit)? = null
        val port = object : InferencePort {
            override fun submit(submissionIndex: Long, canonicalInput: ByteArray, backendTimestampMs: Long, cb: (InferenceResult) -> Unit) { callback = cb }
        }
        val session = ViewerSession(port, freshnessNs = 250 * ms, mode = ViewerPipelineMode.OVERLAPPED)
        session.offer(0) { ViewerFrame(ByteArray(3), 1, 1, ByteArray(256 * 256 * 3)) }
        callback!!(InferenceResult.Success(0, true, listOf(Landmark(11, null, .5, .5, .0, .9, .9))))
        assertNull(session.snapshot(260 * ms).observation, "the display must still drop it at 250 ms")
        val analysed = withTimeout(1_000) { session.completions.first() }
        assertEquals(0L, analysed.capturedNs)
    }
}
