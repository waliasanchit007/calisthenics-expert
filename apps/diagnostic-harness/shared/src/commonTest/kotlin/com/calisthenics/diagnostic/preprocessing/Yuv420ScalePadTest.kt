package com.calisthenics.diagnostic.preprocessing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Yuv420ScalePadTest {
    @Test
    fun fusedOutputIsByteIdenticalAcrossOrientationsMirrorsStridesAndTargets() {
        val targets = listOf(1 to 1, 5 to 7, 8 to 3, 11 to 9, 256 to 256)
        for (rotation in FrameRotation.entries) {
            for (mirrored in listOf(false, true)) {
                val frame = syntheticFrame(rotation, mirrored)
                val upright = yuv420ToUprightRgb24(frame)
                val (uprightWidth, uprightHeight) = uprightDimensions(frame.width, frame.height, rotation)
                for ((targetWidth, targetHeight) in targets) {
                    val expected = scalePadRgb24(
                        upright,
                        uprightWidth,
                        uprightHeight,
                        targetWidth,
                        targetHeight,
                    )
                    assertContentEquals(
                        expected,
                        yuv420ToScalePadRgb24(frame, targetWidth, targetHeight),
                        "mismatch for $rotation mirrored=$mirrored target=${targetWidth}x$targetHeight",
                    )
                }
            }
        }
    }

    @Test
    fun evenDimensionDownsamplingMatchesTheScalePadOracle() {
        val frame = syntheticFrame(FrameRotation.R180, mirrored = true, width = 64, height = 36)
        val upright = yuv420ToUprightRgb24(frame)
        val expected = scalePadRgb24(upright, 64, 36, 16, 16)

        assertContentEquals(expected, yuv420ToScalePadRgb24(frame, targetWidth = 16, targetHeight = 16))
    }

    @Test
    fun blackWhiteVectorUsesExactBilinearRoundingAndCenteredPadding() {
        val frame = Yuv420Frame(
            width = 2,
            height = 1,
            y = YuvPlane(byteArrayOf(0, 255.toByte()), rowStride = 2, pixelStride = 1),
            u = YuvPlane(byteArrayOf(128.toByte()), rowStride = 1, pixelStride = 1),
            v = YuvPlane(byteArrayOf(128.toByte()), rowStride = 1, pixelStride = 1),
            colorStandard = YuvColorStandard.BT601,
            colorRange = YuvColorRange.FULL,
            rotation = FrameRotation.R0,
            mirrored = false,
        )

        val expected = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 85, 85, 85, 170.toByte(), 170.toByte(), 170.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 85, 85, 85, 170.toByte(), 170.toByte(), 170.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 85, 85, 85, 170.toByte(), 170.toByte(), 170.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 0, 0, 0,
        )

        assertContentEquals(expected, yuv420ToScalePadRgb24(frame, targetWidth = 8, targetHeight = 3))
    }

    @Test
    fun unsupportedSourceAndTargetDimensionsAreRejected() {
        val frame = Yuv420Frame(
            width = 16_385,
            height = 1,
            y = YuvPlane(ByteArray(16_385), rowStride = 16_385, pixelStride = 1),
            u = YuvPlane(ByteArray(8_193), rowStride = 8_193, pixelStride = 1),
            v = YuvPlane(ByteArray(8_193), rowStride = 8_193, pixelStride = 1),
            colorStandard = YuvColorStandard.BT601,
            colorRange = YuvColorRange.FULL,
            rotation = FrameRotation.R0,
            mirrored = false,
        )
        assertFailsWith<IllegalArgumentException> { yuv420ToScalePadRgb24(frame) }

        val smallFrame = frame.copy(
            width = 1,
            y = YuvPlane(byteArrayOf(0), rowStride = 1, pixelStride = 1),
            u = YuvPlane(byteArrayOf(128.toByte()), rowStride = 1, pixelStride = 1),
            v = YuvPlane(byteArrayOf(128.toByte()), rowStride = 1, pixelStride = 1),
        )
        assertFailsWith<IllegalArgumentException> { yuv420ToScalePadRgb24(smallFrame, targetWidth = 4_097) }
    }

    private fun syntheticFrame(
        rotation: FrameRotation,
        mirrored: Boolean,
        width: Int = 3,
        height: Int = 5,
    ): Yuv420Frame {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return Yuv420Frame(
            width = width,
            height = height,
            y = logicalPlane(width + 5, 1, width, height) { x, y ->
                when ((x + 2 * y) % 5) {
                    0 -> 0
                    1 -> 16
                    2 -> 128
                    3 -> 235
                    else -> 255
                }
            },
            u = logicalPlane(chromaWidth * 3 + 2, 3, chromaWidth, chromaHeight) { x, y ->
                if ((x + y) % 2 == 0) 0 else 255
            },
            v = logicalPlane(chromaWidth * 2 + 2, 2, chromaWidth, chromaHeight) { x, y ->
                if ((2 * x + y) % 3 == 0) 255 else 0
            },
            colorStandard = YuvColorStandard.BT601,
            colorRange = YuvColorRange.FULL,
            rotation = rotation,
            mirrored = mirrored,
        )
    }

    private fun logicalPlane(
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        value: (x: Int, y: Int) -> Int,
    ): YuvPlane {
        return YuvPlane(ByteArray((height - 1) * rowStride + (width - 1) * pixelStride + 1) { 0x5A }, rowStride, pixelStride).let { plane ->
            val bytes = plane.bytes
            for (y in 0 until height) {
                for (x in 0 until width) {
                    bytes[y * rowStride + x * pixelStride] = value(x, y).toByte()
                }
            }
            YuvPlane(bytes, rowStride, pixelStride)
        }
    }
}
