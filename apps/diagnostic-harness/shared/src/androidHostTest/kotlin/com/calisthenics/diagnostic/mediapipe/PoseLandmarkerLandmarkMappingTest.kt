package com.calisthenics.diagnostic.mediapipe

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.google.mediapipe.tasks.components.containers.Landmark as MpLandmark

class PoseLandmarkerLandmarkMappingTest {

    @Test
    fun mapsNormalizedLandmarksPreservingOrderAndFields() {
        val source = listOf(
            NormalizedLandmark.create(0.1f, 0.2f, 0.3f, Optional.of(0.9f), Optional.of(0.8f)),
            NormalizedLandmark.create(0.4f, 0.5f, 0.6f, Optional.empty(), Optional.empty()),
        )

        val mapped = mapNormalizedLandmarks(source)

        assertEquals(2, mapped.size)
        assertEquals(0, mapped[0].index)
        assertEquals(0.1, mapped[0].x, 1e-6)
        assertEquals(0.2, mapped[0].y, 1e-6)
        assertEquals(0.3, mapped[0].z, 1e-6)
        assertEquals(0.9, mapped[0].visibility!!, 1e-6)
        assertEquals(0.8, mapped[0].presence!!, 1e-6)

        assertEquals(1, mapped[1].index)
        assertNull(mapped[1].visibility)
        assertNull(mapped[1].presence)
    }

    @Test
    fun mapsWorldLandmarksPreservingOrderAndFields() {
        val source = listOf(MpLandmark.create(1.0f, 2.0f, 3.0f, Optional.of(0.7f), Optional.of(0.6f)))

        val mapped = mapWorldLandmarks(source)

        assertEquals(1, mapped.size)
        assertEquals(0, mapped[0].index)
        assertEquals(1.0, mapped[0].x, 1e-6)
        assertEquals(2.0, mapped[0].y, 1e-6)
        assertEquals(3.0, mapped[0].z, 1e-6)
        assertEquals(0.7, mapped[0].visibility!!, 1e-6)
        assertEquals(0.6, mapped[0].presence!!, 1e-6)
    }
}
