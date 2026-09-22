package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-0007 decision 1: in OVERLAPPED mode every admitted frame is prepared while the previous one
 * infers, exactly one inference is in flight, and a one-slot latest-wins mailbox joins the two.
 * SERIAL behaviour is pinned separately, and unchanged, by ViewerSessionTest.
 */
class ViewerSessionOverlappedTest {

    private class Submission(val index: Long, val backendMs: Long, val marker: Byte, val callback: (InferenceResult) -> Unit)

    /** Records every submission and, like the real port, refuses a second one while one is in flight. */
    private class RecordingPort : InferencePort {
        val submissions = mutableListOf<Submission>()
        var inFlight = false
        override fun submit(submissionIndex: Long, canonicalInput: ByteArray, backendTimestampMs: Long, callback: (InferenceResult) -> Unit) {
            check(!inFlight) { "live inference slot is occupied" }
            inFlight = true
            submissions += Submission(submissionIndex, backendTimestampMs, canonicalInput[0]) { r -> inFlight = false; callback(r) }
        }
        fun complete(i: Int, present: Boolean = true) = submissions[i].callback(
            InferenceResult.Success(0, present, if (present) listOf(Landmark(11, null, .5, .5, .0, .9, .9)) else null),
        )
    }

    private fun frame(marker: Int) = ViewerFrame(ByteArray(3) { marker.toByte() }, 1, 1, ByteArray(256 * 256 * 3) { marker.toByte() })
    private fun overlapped(port: InferencePort, clock: () -> Long = { 0L }) =
        ViewerSession(port, clockNs = clock, mode = ViewerPipelineMode.OVERLAPPED)

    @Test fun aFrameOfferedDuringInferenceIsPreparedNotSkipped() {
        val port = RecordingPort(); val session = overlapped(port)
        var prepared = 0
        session.offer(0) { prepared++; frame(1) }
        session.offer(33_000_000) { prepared++; frame(2) }
        assertEquals(2, prepared, "SERIAL would skip the second offer unprepared; OVERLAPPED must prepare it")
        assertEquals(1, port.submissions.size, "but it must not be submitted while one is in flight")
        assertEquals(0, session.snapshots.value.skipped)
    }

    @Test fun theMailboxKeepsOnlyTheNewestFrameAndCountsTheOnesItDropped() {
        val port = RecordingPort(); val session = overlapped(port)
        session.offer(0) { frame(1) }                 // submitted
        session.offer(33_000_000) { frame(2) }        // waits
        session.offer(66_000_000) { frame(3) }        // replaces 2
        session.offer(99_000_000) { frame(4) }        // replaces 3
        port.complete(0)
        assertEquals(2, port.submissions.size)
        assertEquals(4.toByte(), port.submissions[1].marker, "inference must take the NEWEST prepared frame")
        assertEquals(99L, port.submissions[1].backendMs)
        assertEquals(2, session.snapshots.value.performance.superseded)
    }

    @Test fun submissionsAreStrictlyOrderedAndEachResultKeepsItsOwnFrame() {
        val port = RecordingPort(); val session = overlapped(port)
        session.offer(0) { frame(1) }
        session.offer(33_000_000) { frame(2) }
        port.complete(0)
        session.offer(66_000_000) { frame(3) }
        port.complete(1)
        port.complete(2)
        assertEquals(listOf(0L, 33L, 66L), port.submissions.map { it.backendMs })
        assertEquals(3.toByte(), session.snapshots.value.observation!!.frame.rgb[0])
        assertEquals(66_000_000L, session.snapshots.value.observation!!.capturedNs)
    }

    @Test fun aFrameNotNewerThanTheLastAcceptedOneIsNeverPrepared() {
        val port = RecordingPort(); val session = overlapped(port)
        session.offer(10_000_000) { frame(1) }
        session.offer(10_400_000) { error("same millisecond as the last accepted frame") }
        session.offer(5_000_000) { error("older than the last accepted frame") }
        assertEquals(2, session.snapshots.value.skipped)
    }

    @Test fun closeClearsTheMailboxAndALateCompletionCannotSubmitIt() {
        val port = RecordingPort(); val session = overlapped(port)
        session.offer(0) { frame(1) }
        session.offer(33_000_000) { frame(2) }
        session.close()
        port.complete(0)
        assertEquals(1, port.submissions.size, "the waiting frame was submitted after close")
        assertEquals(null, session.snapshots.value.observation)
    }

    @Test fun anInferenceFailureDropsTheWaitingFrame() {
        val port = RecordingPort(); val session = overlapped(port)
        session.offer(0) { frame(1) }
        session.offer(33_000_000) { frame(2) }
        port.submissions[0].callback(InferenceResult.Failure("backend", "backend_call_failed", null, null))
        assertEquals(1, port.submissions.size)
        assertEquals("Inference failed", session.snapshots.value.failure)
    }

    @Test fun thePartitionStaysExactWhenAFrameWaitsInTheMailbox() {
        // Reads in order: A offered, A prep start, A prepared, A submitted; B offered, B prep start,
        // B prepared; A completed; B submitted; B completed.
        val reads = ArrayDeque(listOf(10L, 13, 18, 19, 43, 46, 54, 57, 57, 98).map { it * 1_000_000 })
        var last = 0L
        val port = RecordingPort(); val session = overlapped(port) { if (reads.isNotEmpty()) last = reads.removeFirst(); last }
        session.offer(0) { frame(1) }
        session.offer(33_000_000) { frame(2) }
        port.complete(0)
        port.complete(1)
        val p = session.snapshot(98_000_000).performance
        assertEquals(10.0, p.captureToOfferMs)
        assertEquals(3.0, p.admissionMs)
        assertEquals(8.0, p.preparationMs)
        assertEquals(3.0, p.submitGapMs, "B waited 3 ms in the mailbox for A to finish")
        assertEquals(41.0, p.inferenceMs)
        assertEquals(65.0, p.captureToResultMs)
        val sum = listOf(p.captureToOfferMs, p.admissionMs, p.preparationMs, p.submitGapMs, p.inferenceMs).sumOf { assertNotNull(it) }
        assertEquals(p.captureToResultMs, sum)
        assertEquals("OVERLAPPED", p.pipelineMode)
    }

    @Test fun everyCompletionReachesTheAnalysisStreamOnceAndInCaptureOrder() = runBlocking {
        // ADR-0007 decision 2: analysis must not depend on the conflated display snapshot.
        val port = RecordingPort(); val session = overlapped(port)
        session.offer(0) { frame(1) }
        session.offer(33_000_000) { frame(2) }
        port.complete(0)
        port.complete(1, present = false)
        session.offer(66_000_000) { frame(3) }
        port.complete(2)
        val seen = withTimeout(1_000) { session.completions.take(3).toList() }
        assertEquals(listOf(0L, 33_000_000L, 66_000_000L), seen.map { it.capturedNs })
        assertEquals(listOf(true, false, true), seen.map { it.posePresent }, "no-pose results are completions too")
        assertEquals(0, session.snapshots.value.performance.completionsDropped)
    }

    @Test fun theSubmissionFenceHoldsUntilANativeSubmitReturns() {
        // The port calls the detector outside its own monitor and disposes it outside it too, so
        // disposal must wait for an in-progress submit. This is what the controller waits on.
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val port = object : InferencePort {
            override fun submit(submissionIndex: Long, canonicalInput: ByteArray, backendTimestampMs: Long, callback: (InferenceResult) -> Unit) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val session = overlapped(port)
        val offering = thread { session.offer(0) { frame(1) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        session.close()                                             // stop never waits
        assertFalse(session.awaitNativeSubmissionsIdle(50), "the fence opened while a submit was still running")
        release.countDown()
        offering.join(5_000)
        assertTrue(session.awaitNativeSubmissionsIdle(1_000))
    }

    @Test fun serialIsStillTheDefault() {
        assertEquals(ViewerPipelineMode.SERIAL, ViewerSession(RecordingPort()).mode)
    }
}
