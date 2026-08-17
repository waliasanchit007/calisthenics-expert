package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionLedgerTest {
    @Test
    fun tableOfEventsPreservesEveryCounterIdentity() {
        val ledger = ledger()

        receiveAndSubmit(ledger, frameIndex = 0, captureTimestampNs = 100_000_001, submissionIndex = 0, inferenceStartNs = 100_000_010)
        ledger.accept(SessionEvent.InferenceSucceeded(submissionIndex = 0, inferenceEndNs = 100_000_020, posePresent = false))
        receiveAndSkip(ledger, frameIndex = 1, captureTimestampNs = 100_000_101, reason = SkipReason.CADENCE_SELECTION)
        receiveAndSubmit(ledger, frameIndex = 2, captureTimestampNs = 100_000_201, submissionIndex = 1, inferenceStartNs = 100_000_210)
        ledger.accept(SessionEvent.InferenceFailed(submissionIndex = 1, failureSource = "backend", classification = "backend_call_failed"))
        receiveAndSkip(ledger, frameIndex = 3, captureTimestampNs = 100_000_301, reason = SkipReason.BACKPRESSURE)
        receiveAndSubmit(ledger, frameIndex = 4, captureTimestampNs = 100_000_401, submissionIndex = 2, inferenceStartNs = 100_000_410)
        ledger.accept(SessionEvent.InferenceSucceeded(submissionIndex = 2, inferenceEndNs = 100_000_420, posePresent = true))
        receiveAndSkip(ledger, frameIndex = 5, captureTimestampNs = 100_000_501, reason = SkipReason.PREPROCESSING_FAILED)
        receiveAndSkip(ledger, frameIndex = 6, captureTimestampNs = 100_000_601, reason = SkipReason.SESSION_STOPPING)

        val snapshot = ledger.snapshot()
        val counts = snapshot.counts
        assertEquals(7, counts.cameraFramesReceived)
        assertEquals(3, counts.inferenceFramesSubmitted)
        assertEquals(2, counts.inferenceRecordsEmitted)
        assertEquals(1, counts.inferenceFailures)
        assertEquals(4, counts.framesSkippedBeforeInference)
        assertEquals(1, counts.poseDetectedRecords)
        assertEquals(counts.cameraFramesReceived, counts.inferenceFramesSubmitted + counts.framesSkippedBeforeInference)
        assertEquals(counts.inferenceFramesSubmitted, counts.inferenceRecordsEmitted + counts.inferenceFailures)
        assertEquals(counts.inferenceRecordsEmitted, snapshot.records.size.toLong())
        assertEquals(snapshot.records.count { it.posePresent }.toLong(), counts.poseDetectedRecords)
        assertEquals(counts.framesSkippedBeforeInference, counts.framesSkippedBeforeInferenceByReason.values.sum())
        assertEquals(counts.framesSkippedBeforeInference, snapshot.records.sumOf { it.framesSkippedSinceLastEmittedRecord } + counts.trailingFramesSkippedAfterLastEmittedRecord)
    }

    @Test
    fun skipReasonsAndFailureIndicesAreAttributableAndBounded() {
        val ledger = ledger()

        receiveAndSkip(ledger, 0, 100_000_000, SkipReason.CADENCE_SELECTION)
        receiveAndSkip(ledger, 1, 100_000_001, SkipReason.BACKPRESSURE)
        receiveAndSubmit(ledger, 2, 100_000_002, 0, 100_000_003)
        ledger.accept(SessionEvent.InferenceFailed(0, "backend", "backend_call_failed", "E-42", "detector failed"))
        receiveAndSubmit(ledger, 3, 100_000_004, 1, 100_000_005)
        ledger.accept(SessionEvent.InferenceFailed(1, "backend", "backend_call_failed"))

        val snapshot = ledger.snapshot()
        assertEquals(
            mapOf(
                "cadence_selection" to 1L,
                "backpressure" to 1L,
                "preprocessing_failed" to 0L,
                "session_stopping" to 0L,
            ),
            snapshot.counts.framesSkippedBeforeInferenceByReason,
        )
        assertEquals(2, snapshot.failures.size)
        assertEquals(snapshot.counts.inferenceFailures, snapshot.failures.size.toLong())
        assertEquals(snapshot.failures.size, snapshot.failures.map { it.submissionIndex }.toSet().size)
        assertTrue(snapshot.failures.all { it.submissionIndex in 0 until snapshot.counts.inferenceFramesSubmitted })
        assertEquals("E-42", snapshot.failures.first().sourceErrorCode)
    }

    @Test
    fun skipsBeforeFailedCallsCarryIntoNextRecordAndTrailingSkips() {
        val ledger = ledger()

        receiveAndSubmit(ledger, 0, 200_000_001, 0, 200_000_002)
        ledger.accept(SessionEvent.InferenceFailed(0, "backend", "backend_call_failed"))
        receiveAndSkip(ledger, 1, 200_000_101, SkipReason.BACKPRESSURE)
        receiveAndSkip(ledger, 2, 200_000_201, SkipReason.CADENCE_SELECTION)
        receiveAndSubmit(ledger, 3, 200_000_301, 1, 200_000_302)
        ledger.accept(SessionEvent.InferenceSucceeded(1, 200_000_303, posePresent = true))
        receiveAndSkip(ledger, 4, 200_000_401, SkipReason.SESSION_STOPPING)

        val snapshot = ledger.snapshot()
        assertEquals(2, snapshot.records.single().framesSkippedSinceLastEmittedRecord)
        assertEquals(1, snapshot.counts.trailingFramesSkippedAfterLastEmittedRecord)
        assertEquals(3, snapshot.counts.framesSkippedBeforeInference)
        assertEquals(3, snapshot.records.sumOf { it.framesSkippedSinceLastEmittedRecord } + snapshot.counts.trailingFramesSkippedAfterLastEmittedRecord)
    }

    @Test
    fun emittedRecordsHaveStrictFrameSessionVersionDimensionAndTimeInvariants() {
        val ledger = ledger()

        receiveAndSubmit(ledger, 10, 300_000_001, 0, 300_000_010)
        ledger.accept(SessionEvent.InferenceSucceeded(0, 300_000_020, posePresent = false))
        receiveAndSubmit(ledger, 12, 300_000_101, 1, 300_000_110)
        ledger.accept(SessionEvent.InferenceSucceeded(1, 300_000_120, posePresent = true))

        val records = ledger.snapshot().records
        assertEquals(listOf(10L, 12L), records.map { it.frameIndex })
        assertTrue(records.zipWithNext().all { (first, second) -> second.frameIndex > first.frameIndex })
        assertTrue(records.all { it.sessionId == TEST_SESSION_ID })
        assertTrue(records.all { it.schemaVersion == TEST_SCHEMA_VERSION })
        assertTrue(records.all { it.imageWidth == TEST_WIDTH && it.imageHeight == TEST_HEIGHT })
        assertTrue(records.all { it.captureTimestampNs <= it.inferenceStartNs && it.inferenceStartNs <= it.inferenceEndNs })
    }

    @Test
    fun sameMillisecondCapturesCarryMonotonicBackendTimestamps() {
        val ledger = ledger()

        receiveAndSubmit(ledger, 0, 400_000_001, 0, 400_000_002)
        ledger.accept(SessionEvent.InferenceSucceeded(0, 400_000_003, posePresent = false))
        receiveAndSubmit(ledger, 1, 400_000_999, 1, 400_001_000)
        ledger.accept(SessionEvent.InferenceSucceeded(1, 400_001_001, posePresent = false))

        assertEquals(listOf(400L, 401L), ledger.snapshot().records.map { it.backendTimestampMs })
    }

    @Test
    fun stopClosesAdmissionAndTimeoutClassifiesAnOutstandingSubmission() {
        val ledger = ledger()
        receiveAndSubmit(ledger, 0, 500_000_000, 0, 500_000_001)

        ledger.accept(SessionEvent.StopRequested)
        ledger.accept(SessionEvent.DrainTimedOut)

        val snapshot = ledger.snapshot()
        assertEquals(1, snapshot.counts.inferenceFailures)
        assertEquals("harness", snapshot.failures.single().failureSource)
        assertEquals("session_teardown_timeout", snapshot.failures.single().classification)
        assertFailsWith<IllegalStateException> {
            ledger.accept(SessionEvent.FrameReceived(1, 500_000_100, TEST_WIDTH, TEST_HEIGHT))
        }
    }

    private fun ledger() = SessionLedger(
        SessionConfiguration(
            sessionId = TEST_SESSION_ID,
            schemaVersion = TEST_SCHEMA_VERSION,
            imageWidth = TEST_WIDTH,
            imageHeight = TEST_HEIGHT,
        ),
        DirectSessionDispatcher,
    )

    private fun receiveAndSubmit(
        ledger: SessionLedger,
        frameIndex: Long,
        captureTimestampNs: Long,
        submissionIndex: Long,
        inferenceStartNs: Long,
    ) {
        ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, TEST_WIDTH, TEST_HEIGHT))
        ledger.accept(SessionEvent.InferenceSubmitted(submissionIndex, inferenceStartNs))
    }

    private fun receiveAndSkip(ledger: SessionLedger, frameIndex: Long, captureTimestampNs: Long, reason: SkipReason) {
        ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, TEST_WIDTH, TEST_HEIGHT))
        ledger.accept(SessionEvent.FrameSkipped(reason))
    }

    private companion object {
        val TEST_SESSION_ID = Uuid("12345678-1234-4234-8234-123456789abc")
        val TEST_SCHEMA_VERSION = SemanticVersion("1.1.0")
        const val TEST_WIDTH = 256
        const val TEST_HEIGHT = 256
    }
}
