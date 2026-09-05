package com.calisthenics.diagnostic.preview

import android.graphics.Matrix
import com.calisthenics.diagnostic.camera.AnalysisFrameTransform
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PreviewOverlayTransformTest {
    private fun matrix(vararg values: Float) = Matrix().apply { setValues(values) }
    private val identity get() = Matrix()

    @Test fun inverseRotationMapsUprightPointsToSensorAtEveryQuarterTurn() {
        // Raw point (100,50) in a 400x200 buffer. Literal model coordinates after rotation/pad.
        val cases = listOf(0 to (0.25 to 0.375), 90 to (0.625 to 0.25),
            180 to (0.75 to 0.625), 270 to (0.375 to 0.75))
        cases.forEach { (rotation, model) ->
            val source = AnalysisFrameTransform(identity, 400, 200, rotation)
            val point = assertNotNull(mapPreviewPoint(model.first, model.second, source, identity))
            assertEquals(100f, point.x, 0.01f, "rotation=$rotation")
            assertEquals(50f, point.y, 0.01f, "rotation=$rotation")
        }
    }

    @Test fun mapsNonzeroSensorCropThroughThePreviewMirrorExactlyOnce() {
        // sensor -> buffer: half-size then translate (-100,-50).
        // raw (100,50) -> sensor (400,200). sensor -> view: mirror x about 1000.
        val source = AnalysisFrameTransform(matrix(.5f,0f,-100f, 0f,.5f,-50f, 0f,0f,1f), 400,200,0)
        val target = matrix(-1f,0f,1000f, 0f,1f,0f, 0f,0f,1f)
        val point = assertNotNull(mapPreviewPoint(.25,.375,source,target))
        assertEquals(600f,point.x,.01f)
        assertEquals(200f,point.y,.01f)
    }

    @Test fun unavailableOrNonInvertibleTransformAndModelPaddingDoNotInventOverlay() {
        val source = AnalysisFrameTransform(identity,400,200,0)
        assertNull(mapPreviewPoint(.5,.1,source,identity))
        assertNull(mapPreviewPoint(.5,.5,source,null))
        assertNull(mapPreviewPoint(Double.NaN,.5,source,identity))
        val singular = AnalysisFrameTransform(matrix(0f,0f,0f,0f,0f,0f,0f,0f,1f),400,200,0)
        assertNull(mapPreviewPoint(.5,.5,singular,identity))
    }

    @Test fun capturedTransformDoesNotChangeWhenSourceMatrixIsReused() {
        val reused = Matrix()
        val source = AnalysisFrameTransform(reused,400,200,0)
        reused.setScale(2f,2f)
        val point = assertNotNull(mapPreviewPoint(.25,.375,source,identity))
        assertEquals(100f,point.x,.01f)
        assertEquals(50f,point.y,.01f)
    }
}
