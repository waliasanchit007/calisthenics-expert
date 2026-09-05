package com.calisthenics.diagnostic.camera

import androidx.camera.core.CameraSelector
import org.junit.Test
import kotlin.test.*

class SourceCameraSelectionTest {
    private val available = listOf("0" to CameraSelector.LENS_FACING_BACK, "42" to CameraSelector.LENS_FACING_FRONT)

    @Test fun viewerSelectsFrontByLensDirectionNotAssumedId() {
        assertEquals("42", selectSourceCameraId(available, CameraSelector.LENS_FACING_FRONT))
    }

    @Test fun studyDefaultRemainsExactlyCameraZero() {
        assertEquals("0", selectSourceCameraId(available))
        assertFailsWith<IllegalStateException> { selectSourceCameraId(listOf("7" to CameraSelector.LENS_FACING_BACK)) }
    }

    @Test fun missingFrontNeverFallsBackToRear() {
        assertFailsWith<IllegalStateException> {
            selectSourceCameraId(listOf("0" to CameraSelector.LENS_FACING_BACK), CameraSelector.LENS_FACING_FRONT)
        }
    }
}
