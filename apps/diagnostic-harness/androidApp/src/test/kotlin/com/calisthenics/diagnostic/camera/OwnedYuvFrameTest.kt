package com.calisthenics.diagnostic.camera

import androidx.camera.core.ImageAnalysis
import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.YuvColorRange
import com.calisthenics.diagnostic.preprocessing.YuvColorStandard
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class OwnedYuvFrameTest {
    @Test
    fun copiesAllPlanesAndMetadataBeforeClosingSource() {
        val source = FakeImage(
            planes = listOf(
                FakePlane(byteArrayOf(1, 2), rowStride = 8, pixelStride = 1),
                FakePlane(byteArrayOf(3), rowStride = 4, pixelStride = 2),
                FakePlane(byteArrayOf(4), rowStride = 5, pixelStride = 3),
            ),
            width = 640,
            height = 480,
            rotation = FrameRotation.R90,
            mirrored = false,
            rawDataSpace = 146931712,
            colorStandard = YuvColorStandard.BT601,
            colorRange = YuvColorRange.FULL,
            timestampNs = 123_456_789L,
            observedAeTargetFpsRange = SourceFpsRange(30, 30),
        )

        var callbackSawClosed = false
        val frame = captureOwnedYuvFrame(source) {
            callbackSawClosed = source.closed
        }
        source.mutateBuffers()

        assertContentEquals(byteArrayOf(1, 2), frame.y.bytes)
        assertContentEquals(byteArrayOf(3), frame.u.bytes)
        assertContentEquals(byteArrayOf(4), frame.v.bytes)
        assertEquals(8, frame.y.rowStride)
        assertEquals(1, frame.y.pixelStride)
        assertEquals(4, frame.u.rowStride)
        assertEquals(2, frame.u.pixelStride)
        assertEquals(5, frame.v.rowStride)
        assertEquals(3, frame.v.pixelStride)
        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertEquals(FrameRotation.R90, frame.rotation)
        assertEquals(146931712, frame.rawDataSpace)
        assertEquals(YuvColorStandard.BT601, frame.colorStandard)
        assertEquals(YuvColorRange.FULL, frame.colorRange)
        assertEquals(123_456_789L, frame.timestampNs)
        assertEquals(SourceFpsRange(30, 30), frame.observedAeTargetFpsRange)
        assertTrue(source.closed)
        assertTrue(callbackSawClosed)
    }

    @Test
    fun pinsCameraXSourceConstants() {
        assertEquals(1, ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        assertEquals(0, ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        assertFalse(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST == ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
        assertEquals(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST, CameraXFrameSource.BACKPRESSURE_STRATEGY)
        assertEquals(146931712, OwnedYuvFrame.ADMITTED_RAW_DATASPACE)
    }

    @Test
    fun closesSourceExactlyOnceWhenConsumerThrows() {
        val source = validImage()

        assertFailsWith<IllegalStateException> {
            captureOwnedYuvFrame(source) { error("consumer failed") }
        }

        assertEquals(1, source.closeCount)
    }

    @Test
    fun absentObservedAeRangeRemainsAbsentRatherThanUsingTheRequestedRange() {
        val frame = captureOwnedYuvFrame(validImage()) {}

        assertEquals(null, frame.observedAeTargetFpsRange)
    }

    @Test
    fun changedObservedAeRangeRemainsObservable() {
        val frame = captureOwnedYuvFrame(validImage(SourceFpsRange(24, 30))) {}

        assertEquals(SourceFpsRange(24, 30), frame.observedAeTargetFpsRange)
    }

    private fun validImage(observedAeTargetFpsRange: SourceFpsRange? = null) = FakeImage(
        planes = listOf(
            FakePlane(byteArrayOf(1), rowStride = 1, pixelStride = 1),
            FakePlane(byteArrayOf(2), rowStride = 1, pixelStride = 1),
            FakePlane(byteArrayOf(3), rowStride = 1, pixelStride = 1),
        ),
        width = 1,
        height = 1,
        rotation = FrameRotation.R0,
        mirrored = false,
        rawDataSpace = 146931712,
        colorStandard = YuvColorStandard.BT601,
        colorRange = YuvColorRange.FULL,
        timestampNs = 0L,
        observedAeTargetFpsRange = observedAeTargetFpsRange,
    )

    private class FakeImage(
        override val planes: List<SourcePlane>,
        override val width: Int,
        override val height: Int,
        override val rotation: FrameRotation,
        override val mirrored: Boolean,
        override val rawDataSpace: Int,
        override val colorStandard: YuvColorStandard,
        override val colorRange: YuvColorRange,
        override val timestampNs: Long,
        override val observedAeTargetFpsRange: SourceFpsRange? = null,
    ) : OwnedImage {
        var closed = false
        var closeCount = 0

        override fun close() {
            closed = true
            closeCount += 1
        }

        fun mutateBuffers() {
            planes.forEach { (it as FakePlane).bytes.fill(99) }
        }
    }

    private class FakePlane(
        override val bytes: ByteArray,
        override val rowStride: Int,
        override val pixelStride: Int,
    ) : SourcePlane
}
