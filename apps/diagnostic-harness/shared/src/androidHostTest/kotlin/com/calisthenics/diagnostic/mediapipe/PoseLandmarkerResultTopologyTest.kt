package com.calisthenics.diagnostic.mediapipe

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.google.mediapipe.tasks.components.containers.Landmark as MpLandmark

private fun normalized(count: Int, offset: Float): List<NormalizedLandmark> = (0 until count).map { index ->
    NormalizedLandmark.create(
        offset + index * 0.01f,
        offset + index * 0.02f,
        offset + index * 0.03f,
        Optional.of(0.9f),
        Optional.of(0.8f),
    )
}

private fun world(count: Int): List<MpLandmark> = (0 until count).map { index ->
    MpLandmark.create(index * 0.1f, index * 0.2f, index * 0.3f, Optional.of(0.7f), Optional.of(0.6f))
}

/**
 * Check 5 needs the *number* of poses the backend returned, not only whether the first one exists.
 * These cases pin that extraction on the host against MediaPipe's own landmark container types, so
 * the device run has to prove only that the native call happens, not what its result means.
 */
class PoseLandmarkerResultTopologyTest {

    @Test
    fun recordsTheNumberOfPosesTheBackendReturned() {
        val detection = poseLandmarkerDetectionOf(
            listOf(normalized(33, 0.1f), normalized(33, 0.5f)),
            listOf(world(33), world(33)),
        )

        assertEquals(2, detection.poseCount)
        assertTrue(detection.posePresent)
        assertEquals(33, detection.normalizedLandmarks!!.size)
        assertEquals(33, detection.worldLandmarks!!.size)
    }

    @Test
    fun anEmptyResultIsRecordedAsZeroPosesRatherThanAsOne() {
        val detection = poseLandmarkerDetectionOf(emptyList(), emptyList())

        assertEquals(0, detection.poseCount)
        assertFalse(detection.posePresent)
        assertNull(detection.normalizedLandmarks)
        assertNull(detection.worldLandmarks)
    }

    @Test
    fun aPoseEntryCarryingNoLandmarksIsNotReportedAsPresent() {
        val detection = poseLandmarkerDetectionOf(listOf(emptyList()), listOf(emptyList()))

        // The entry exists, so the count is honest, but there is no pose behind it.
        assertEquals(1, detection.poseCount)
        assertFalse(detection.posePresent)
    }

    @Test
    fun onlyTheFirstPoseIsMappedBecauseNumPosesIsPinnedToOne() {
        val detection = poseLandmarkerDetectionOf(
            listOf(normalized(2, 0.1f), normalized(5, 0.5f)),
            listOf(world(2), world(5)),
        )

        assertEquals(2, detection.poseCount)
        assertEquals(2, detection.normalizedLandmarks!!.size)
        assertEquals(0.1, detection.normalizedLandmarks!![0].x, 1e-6)
    }
}
