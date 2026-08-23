package com.calisthenics.diagnostic.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveFrameAdmissionTest {
    @Test
    fun supportsExactlyTenFifteenAndThirtyFpsUsingCaptureTime() {
        listOf(10, 15, 30).forEach { fps ->
            val admission = LiveFrameAdmission(fps)

            assertEquals(LiveFrameClassification.SUBMIT, admission.classify(0L))
            admission.completeSubmission()
            assertEquals(LiveFrameClassification.CADENCE_SELECTION, admission.classify(1_000_000_000L / fps - 1L))
            assertEquals(LiveFrameClassification.SUBMIT, admission.classify(1_000_000_000L / fps))
            admission.completeSubmission()
        }
    }

    @Test
    fun sameCaptureTimestampIsCadenceSelection() {
        val admission = LiveFrameAdmission(30)

        assertEquals(LiveFrameClassification.SUBMIT, admission.classify(1_000_000_000L))
        admission.completeSubmission()

        assertEquals(LiveFrameClassification.CADENCE_SELECTION, admission.classify(1_000_000_000L))
    }

    @Test
    fun occupiedSlotIsBackpressureAndCompletionReleasesIt() {
        val admission = LiveFrameAdmission(30)

        assertEquals(LiveFrameClassification.SUBMIT, admission.classify(0L))
        assertEquals(LiveFrameClassification.BACKPRESSURE, admission.classify(100_000_000L))
        admission.completeSubmission()
        assertEquals(LiveFrameClassification.SUBMIT, admission.classify(100_000_000L))
    }

    @Test
    fun preprocessingFailureIsClassifiedAfterCadenceAndSlotAdmission() {
        val admission = LiveFrameAdmission(30)

        assertEquals(
            LiveFrameClassification.PREPROCESSING_FAILED,
            admission.classify(0L, preprocessingSucceeded = false),
        )
        assertEquals(LiveFrameClassification.SUBMIT, admission.classify(0L))
    }

    @Test
    fun stoppingClassifiesDeliveredCallbacksAndDoesNotAcquireSlot() {
        val admission = LiveFrameAdmission(30)
        admission.stop()

        assertEquals(LiveFrameClassification.SESSION_STOPPING, admission.classify(0L))
        assertEquals(LiveFrameClassification.SESSION_STOPPING, admission.classify(1_000_000_000L))
    }

    @Test
    fun stopRacingReservedCandidateProducesOneSessionStoppingClassification() {
        val admission = LiveFrameAdmission(30)

        assertEquals(LiveFrameClassification.SUBMIT, admission.reserve(0L))
        admission.stop()

        assertEquals(LiveFrameClassification.SESSION_STOPPING, admission.commitCandidate(preprocessingSucceeded = true))
        assertEquals(LiveFrameClassification.SESSION_STOPPING, admission.classify(1_000_000_000L))
        assertFailsWith<IllegalStateException> { admission.completeSubmission() }
    }

    @Test
    fun oneDeliveredCallbackHasExactlyOneTerminalClassification() {
        val admission = LiveFrameAdmission(30)

        assertEquals(LiveFrameClassification.SUBMIT, admission.classify(0L))
        admission.completeSubmission()
        assertFailsWith<IllegalStateException> { admission.completeSubmission() }
    }

    @Test
    fun rejectsUnsupportedCadence() {
        assertFailsWith<IllegalArgumentException> { LiveFrameAdmission(24) }
    }
}
