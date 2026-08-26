package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.MachineInferenceFailure
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedSessionStateTest {
    @Test
    fun largeRunsRetainNoRecordsOrTerminalMapAndOnlyDurationBoundedCadenceBuckets() {
        fun run(recordCount: Int): BoundedSessionSnapshot {
            val state = BoundedSessionState()
            repeat(recordCount) { index ->
                state.submitted(index.toLong())
                state.emitted(index.toLong(), frame(index, recordCount))
            }
            return state.snapshot()
        }

        val thousand = run(1_000)
        val hundredThousand = run(100_000)

        assertEquals(0, thousand.retainedRecordCount)
        assertEquals(0, hundredThousand.retainedRecordCount)
        assertEquals(0, thousand.retainedTerminalEntryCount)
        assertEquals(0, hundredThousand.retainedTerminalEntryCount)
        assertEquals(thousand.achievedInferenceFpsTimeseries.size, hundredThousand.achievedInferenceFpsTimeseries.size)
        assertEquals(null, hundredThousand.currentSubmissionIndex)
        assertEquals(99_999L, hundredThousand.submittedHighWaterMark)
        assertEquals(99_999L, hundredThousand.terminalHighWaterMark)
    }

    @Test
    fun availabilityIsMonotoneAndFirstFailureIsTheOnlyRetainedFailure() {
        val state = BoundedSessionState()
        state.submitted(0)
        state.emitted(0, frame(0, 2, normalized = true, world = false))
        state.submitted(1)
        state.emitted(1, frame(1, 2, normalized = false, world = true))
        state.submitted(2)
        state.failed(failure(2))

        val snapshot = state.snapshot()
        assertTrue(snapshot.normalizedImageLandmarksAvailable)
        assertTrue(snapshot.worldLandmarksAvailable)
        assertEquals(failure(2), snapshot.failure)
        assertEquals(1L, snapshot.latestInferenceLatencyNs)

        state.submitted(3)
        assertFailsWith<IllegalStateException> { state.failed(failure(3)) }
        assertEquals(failure(2), state.snapshot().failure)
    }

    @Test
    fun exactlyOneSubmissionCanBeInFlightAndTerminalIndicesMustBeContiguous() {
        val state = BoundedSessionState()
        state.submitted(0)

        assertFailsWith<IllegalStateException> { state.submitted(1) }
        assertFailsWith<IllegalStateException> { state.emitted(1, frame(1, 2)) }
        state.emitted(0, frame(0, 2))
        state.submitted(1)
        state.failed(failure(1))

        val snapshot = state.snapshot()
        assertFalse(snapshot.normalizedImageLandmarksAvailable)
        assertEquals(TerminalState.FAILED, snapshot.lastTerminalState)
    }

    private fun frame(
        index: Int,
        count: Int,
        normalized: Boolean = false,
        world: Boolean = false,
    ) = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("00000000-0000-4000-8000-000000000001"),
        frameIndex = index.toLong(),
        captureTimestampNs = index.toLong() * 10_000_000_000L / count,
        backendTimestampMs = index.toLong(),
        inferenceStartNs = index.toLong() * 10_000_000_000L / count,
        inferenceEndNs = (index.toLong() * 10_000_000_000L / count) + 1,
        posePresent = normalized || world,
        normalizedImageLandmarks = if (normalized) listOf(landmark()) else null,
        worldLandmarks = if (world) listOf(landmark()) else null,
        imageWidth = 1,
        imageHeight = 1,
        framesSkippedSinceLastEmittedRecord = 0,
    )

    private fun failure(index: Long) = MachineInferenceFailure(
        submissionIndex = index,
        captureTimestampNs = index,
        failureSource = "backend",
        classification = "backend_call_failed",
        sourceErrorCode = null,
        detail = null,
    )

    private fun landmark() = Landmark(0, "nose", 0.0, 0.0, 0.0, null, null)
}
