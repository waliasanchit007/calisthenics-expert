package androidx.camera.view

import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.core.SurfaceRequest
import com.calisthenics.diagnostic.camera.AnalysisFrameTransform
import com.calisthenics.diagnostic.preview.mapPreviewPoint
import com.calisthenics.diagnostic.preview.unmirroredFrontPreviewTransform
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/** Exercise the pinned CameraX implementation, not a hand-authored mirror matrix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnmirroredFrontPreviewTest {
    @Test fun mirrorOffPreviewAndOverlayAgreeAcrossAllRotations() {
        val cases = listOf(0 to (0.25 to 0.375), 90 to (0.625 to 0.25),
            180 to (0.75 to 0.625), 270 to (0.375 to 0.75))
        cases.forEach { (rotation, model) ->
            val quarterTurn = rotation == 90 || rotation == 270
            val size = if (quarterTurn) Size(200, 400) else Size(400, 200)
            val transform = PreviewTransformation().apply {
                setScaleType(PreviewView.ScaleType.FIT_CENTER)
                setTransformationInfo(SurfaceRequest.TransformationInfo.of(
                    Rect(0, 0, 400, 200), rotation, Surface.ROTATION_0,
                    true, Matrix(), false, // Camera surface; MIRROR_MODE_OFF.
                ), Size(400, 200), true) // Front lens.
            }
            val cameraX = assertNotNull(transform.getSensorToViewTransform(size, View.LAYOUT_DIRECTION_LTR))
            val original = FloatArray(9).also(cameraX::getValues)
            val target = unmirroredFrontPreviewTransform(cameraX, size.width)
            val point = assertNotNull(mapPreviewPoint(model.first, model.second,
                AnalysisFrameTransform(Matrix(), 400, 200, rotation), target))
            // Literal clockwise rotations of raw (100,50), without front-camera reflection.
            val expected = when (rotation) {
                0 -> 100f to 50f
                90 -> 150f to 100f
                180 -> 300f to 150f
                else -> 50f to 300f
            }
            assertEquals(expected.first, point.x, .01f, "rotation=$rotation x")
            assertEquals(expected.second, point.y, .01f, "rotation=$rotation y")
            assertContentEquals(original, FloatArray(9).also(cameraX::getValues))
        }
    }

    @Test fun unavailablePreviewDoesNotInventATransform() {
        assertNull(unmirroredFrontPreviewTransform(null, 400))
        assertNull(unmirroredFrontPreviewTransform(Matrix(), 0))
    }
}
