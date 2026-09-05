package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import org.junit.Test
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ViewerSessionTest {
    @Test fun snapshotAndStopDoNotWaitForNativeSubmission() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val port = object : InferencePort {
            override fun submit(submissionIndex: Long, canonicalInput: ByteArray, backendTimestampMs: Long, callback: (InferenceResult) -> Unit) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val session = ViewerSession(port)
        val worker = thread { session.offer(10) { frame() } }
        val responsive = CountDownLatch(1)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            thread { session.snapshot(20); session.close(); responsive.countDown() }
            assertTrue(responsive.await(1, TimeUnit.SECONDS), "native submission must not hold the UI state lock")
        } finally { release.countDown(); worker.join() }
    }
    private class Port : InferencePort {
        var submissions = 0
        var callback: ((InferenceResult) -> Unit)? = null
        override fun submit(submissionIndex: Long, canonicalInput: ByteArray, backendTimestampMs: Long, callback: (InferenceResult) -> Unit) {
            submissions++
            this.callback = callback
        }
    }
    private fun frame(marker: Int = 7) = ViewerFrame(ByteArray(3) { marker.toByte() }, 1, 1, ByteArray(256 * 256 * 3))
    private fun pose() = InferenceResult.Success(20, true, listOf(Landmark(11, null, 0.5, 0.5, 0.0, 0.8, 0.9)))

    @Test fun pairsTheResultWithItsOwnFrameAndDoesNotPrepareWhileBusy() {
        val port = Port()
        val session = ViewerSession(port)
        session.offer(10) { frame(7) }
        session.offer(11) { error("busy frame must not be prepared") }
        assertEquals(1, port.submissions)
        port.callback!!(pose())
        val snapshot = session.snapshot(20)
        assertEquals(7.toByte(), snapshot.observation!!.frame.rgb[0])
        assertEquals(11, snapshot.observation!!.landmarks.single().index)
        assertEquals(1, snapshot.skipped)
    }

    @Test fun poseAbsenceReplacesThePreviousSkeleton() {
        val port = Port(); val session = ViewerSession(port)
        session.offer(10) { frame() }; port.callback!!(pose())
        session.offer(2_000_000) { frame(9) }
        port.callback!!(InferenceResult.Success(3_000_000, false))
        assertFalse(session.snapshot(3_000_000).observation!!.posePresent)
        assertTrue(session.snapshot(3_000_000).observation!!.landmarks.isEmpty())
    }

    @Test fun stopClearsFramesAndLateCallbacksCannotResurrectThem() {
        val port = Port(); val session = ViewerSession(port)
        session.offer(10) { frame() }; val late = port.callback!!
        session.close(); late(pose())
        assertNull(session.snapshot(20).observation)
        assertEquals("Stopped", session.snapshot(20).message)
        session.offer(30) { error("stopped viewer must not prepare") }
        assertEquals(1, port.submissions)
    }

    @Test fun freshnessUsesCaptureAgeNotRecentCallbackArrival() {
        val port = Port(); val session = ViewerSession(port, freshnessNs = 100)
        session.offer(10) { frame() }; port.callback!!(pose())
        assertNotNull(session.snapshot(109).observation)
        assertNull(session.snapshot(110).observation)
        assertEquals("Frame stale", session.snapshot(110).message)
    }

    @Test fun missingCallbackTimesOutAndCannotLaterPublish() {
        val port = Port(); val session = ViewerSession(port, inferenceTimeoutNs = 100)
        session.offer(10) { frame() }; val late = port.callback!!
        assertEquals("Inference timed out", session.snapshot(110).failure)
        late(pose())
        assertNull(session.snapshot(111).observation)
    }

    @Test fun backendFailureClearsPriorImageAndExposesOnlyFixedMessage() {
        val port = Port(); val session = ViewerSession(port)
        session.offer(10) { frame() }; port.callback!!(pose())
        session.offer(2_000_000) { frame() }
        port.callback!!(InferenceResult.Failure("backend", "failed", detail = "private-content"))
        assertNull(session.snapshot(3_000_000).observation)
        assertEquals("Inference failed", session.snapshot(3_000_000).failure)
    }

    @Test fun performanceUsesObservedElapsedTimeAndStageDurations() {
        val clock = Clock()
        val port = Port()
        val session = ViewerSession(port, clockNs = clock::now)

        session.offer(0) {
            clock.nowNs = 2_000_000
            frame()
        }
        clock.nowNs = 1_000_000_000
        session.offer(1_000_000_000) { error("an in-flight offer must be skipped") }
        port.callback!!(pose())

        val performance = session.snapshot(1_500_000_000).performance
        assertEquals(2, performance.cameraOffers)
        assertEquals(1, performance.completions)
        assertEquals(2.0 / 1.5, performance.offerFps!!, 0.000001)
        assertEquals(1.0 / 1.5, performance.poseFps!!, 0.000001)
        assertEquals(2.0, performance.preparationMs)
        assertEquals(998.0, performance.inferenceMs)
        assertEquals(1_000.0, performance.captureToResultMs)
        assertEquals(1_500.0, performance.poseAgeMs)
    }

    @Test fun stateFlowPublishesAcceptanceSkipAndImmediateCompletion() {
        val clock = Clock()
        val port = Port()
        val session = ViewerSession(port, clockNs = clock::now)

        session.offer(0) { frame() }
        assertEquals(1, session.snapshots.value.submitted)
        session.offer(1) { error("busy frame must not be prepared") }
        assertEquals(1, session.snapshots.value.skipped)

        port.callback!!(pose())
        assertNotNull(session.snapshots.value.observation)
        assertEquals(1, session.snapshots.value.completed)
    }

    @Test fun staleSnapshotClearsStateFlowWhenSnapshotIsRead() {
        val port = Port()
        val session = ViewerSession(port, freshnessNs = 100)
        session.offer(10) { frame() }
        port.callback!!(pose())

        assertNotNull(session.snapshots.value.observation)
        session.snapshot(110)
        assertNull(session.snapshots.value.observation)
        assertEquals("Frame stale", session.snapshots.value.message)
    }

    @Test fun stopClearsStateFlowAndLateCallbackCannotRestoreIt() {
        val port = Port()
        val session = ViewerSession(port)
        session.offer(10) { frame() }
        val late = port.callback!!

        session.close()
        assertNull(session.snapshots.value.observation)
        assertEquals("Stopped", session.snapshots.value.message)
        late(pose())
        assertNull(session.snapshots.value.observation)
        assertEquals("Stopped", session.snapshots.value.message)
    }

    private class Clock(var nowNs: Long = 0) {
        fun now(): Long = nowNs
    }
}
