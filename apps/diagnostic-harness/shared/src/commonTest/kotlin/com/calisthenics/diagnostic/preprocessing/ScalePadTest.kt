package com.calisthenics.diagnostic.preprocessing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlin.test.assertFailsWith

/**
 * Bytes below are a literal copy of the committed golden vector at
 * `src/commonTest/resources/preprocessing/scale_pad_golden/` (mirrored from
 * `fixtures/synthetic/preprocessing/scale_pad_golden/`), embedded so the same
 * vector executes identically on JVM and iOS without platform resource I/O.
 */
private object ScalePadGoldenVector {
    const val INPUT_WIDTH = 2
    const val INPUT_HEIGHT = 2
    const val OUTPUT_WIDTH = 5
    const val OUTPUT_HEIGHT = 4

    val input: ByteArray = byteArrayOf(
        -1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1,
    )

    val expectedOutput: ByteArray = byteArrayOf(
        -1, 0, 0, -65, 64, 0, 64, -65, 0, 0, -1, 0, 0, 0, 0,
        -65, 0, 64, -97, 64, 64, 96, -65, 64, 64, -1, 64, 0, 0, 0,
        64, 0, -65, 96, 64, -65, -97, -65, -65, -65, -1, -65, 0, 0, 0,
        0, 0, -1, 64, 64, -1, -65, -65, -1, -1, -1, -1, 0, 0, 0,
    )

    const val INPUT_SHA256 = "6733cdd08e5c7ef0453e2759ef0d28fbd43ea2aa7883b55422a13dac38e23ecc"
    const val EXPECTED_OUTPUT_SHA256 = "ce3964c59aab18fd02721b7a472645c1741b2c5c7ecdfe3c10af37c0165a1888"
}

private fun ByteArray.toUnsignedHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}

class ScalePadTest {
    @Test
    fun goldenVectorFixtureBytesMatchDeclaredSha256() {
        assertEquals(12, ScalePadGoldenVector.input.size)
        assertEquals(60, ScalePadGoldenVector.expectedOutput.size)
        assertEquals(ScalePadGoldenVector.INPUT_SHA256, Sha256Digest.hex(ScalePadGoldenVector.input))
        assertEquals(ScalePadGoldenVector.EXPECTED_OUTPUT_SHA256, Sha256Digest.hex(ScalePadGoldenVector.expectedOutput))
    }

    @Test
    fun goldenVectorProducesExactOutputBytes() {
        val actual = scalePadRgb24(
            ScalePadGoldenVector.input,
            ScalePadGoldenVector.INPUT_WIDTH,
            ScalePadGoldenVector.INPUT_HEIGHT,
            ScalePadGoldenVector.OUTPUT_WIDTH,
            ScalePadGoldenVector.OUTPUT_HEIGHT,
        )
        assertContentEquals(ScalePadGoldenVector.expectedOutput, actual)
    }

    @Test
    fun outputByteLengthMatchesTargetDimensions() {
        val actual = scalePadRgb24(
            ScalePadGoldenVector.input,
            ScalePadGoldenVector.INPUT_WIDTH,
            ScalePadGoldenVector.INPUT_HEIGHT,
            ScalePadGoldenVector.OUTPUT_WIDTH,
            ScalePadGoldenVector.OUTPUT_HEIGHT,
        )
        assertEquals(ScalePadGoldenVector.OUTPUT_WIDTH * ScalePadGoldenVector.OUTPUT_HEIGHT * 3, actual.size)
    }

    @Test
    fun rightColumnIsBlackPaddingForTheGoldenVector() {
        val actual = scalePadRgb24(
            ScalePadGoldenVector.input,
            ScalePadGoldenVector.INPUT_WIDTH,
            ScalePadGoldenVector.INPUT_HEIGHT,
            ScalePadGoldenVector.OUTPUT_WIDTH,
            ScalePadGoldenVector.OUTPUT_HEIGHT,
        )
        for (y in 0 until ScalePadGoldenVector.OUTPUT_HEIGHT) {
            val offset = (y * ScalePadGoldenVector.OUTPUT_WIDTH + 4) * 3
            assertEquals(0, actual[offset].toInt())
            assertEquals(0, actual[offset + 1].toInt())
            assertEquals(0, actual[offset + 2].toInt())
        }
    }

    @Test
    fun scaleGeometryPlacesContentAtExpectedTopLeftOffset() {
        // scale = min(5/2, 4/2) = 2 exactly; scaled dims are 4x4; left = (5-4)//2 = 0, top = (4-4)//2 = 0.
        val actual = scalePadRgb24(
            ScalePadGoldenVector.input,
            ScalePadGoldenVector.INPUT_WIDTH,
            ScalePadGoldenVector.INPUT_HEIGHT,
            ScalePadGoldenVector.OUTPUT_WIDTH,
            ScalePadGoldenVector.OUTPUT_HEIGHT,
        )
        val topLeftOffset = 0
        assertEquals(255, actual[topLeftOffset].toInt() and 0xFF)
        assertEquals(0, actual[topLeftOffset + 1].toInt() and 0xFF)
        assertEquals(0, actual[topLeftOffset + 2].toInt() and 0xFF)
    }

    @Test
    fun oneByOneSourceUpscaledToOneByOneTargetIsIdentity() {
        val source = byteArrayOf(10, 20, 30)
        val actual = scalePadRgb24(source, 1, 1, 1, 1)
        assertContentEquals(source, actual)
    }

    @Test
    fun oneByOneSourceUpscaledIntoSquareCanvasFillsItEntirelyWithNoPadding() {
        val source = byteArrayOf(10, 20, 30)
        val actual = scalePadRgb24(source, 1, 1, 3, 3)
        assertEquals(27, actual.size)
        // scale = min(3/1, 3/1) = 3 exactly; scaled dims are 3x3, so the single source pixel's
        // colour (there is nothing else to bilinearly interpolate against) fills every pixel.
        for (offset in 0 until actual.size step 3) {
            assertEquals(10, actual[offset].toInt())
            assertEquals(20, actual[offset + 1].toInt())
            assertEquals(30, actual[offset + 2].toInt())
        }
    }

    @Test
    fun narrowerAspectRatioTargetPadsBlackOnLeftAndRight() {
        val source = byteArrayOf(10, 20, 30, 10, 20, 30)
        // 1x2 source into a 3x2 target: scale = min(3/1, 2/2) = 1; scaled dims are 1x2, centered
        // with left = (3-1)//2 = 1, so column 0 and column 2 are black padding on every row.
        val actual = scalePadRgb24(source, 1, 2, 3, 2)
        assertEquals(18, actual.size)
        for (y in 0 until 2) {
            val rowOffset = y * 3 * 3
            assertEquals(0, actual[rowOffset].toInt())
            assertEquals(0, actual[rowOffset + 1].toInt())
            assertEquals(0, actual[rowOffset + 2].toInt())
            assertEquals(10, actual[rowOffset + 3].toInt())
            assertEquals(20, actual[rowOffset + 4].toInt())
            assertEquals(30, actual[rowOffset + 5].toInt())
            assertEquals(0, actual[rowOffset + 6].toInt())
            assertEquals(0, actual[rowOffset + 7].toInt())
            assertEquals(0, actual[rowOffset + 8].toInt())
        }
    }

    @Test
    fun zeroOrNegativeDimensionsAreRejected() {
        val source = byteArrayOf(1, 2, 3)
        assertFailsWith<IllegalArgumentException> { scalePadRgb24(source, 0, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { scalePadRgb24(source, 1, -1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { scalePadRgb24(source, 1, 1, 0, 1) }
        assertFailsWith<IllegalArgumentException> { scalePadRgb24(source, 1, 1, 1, -2) }
    }

    @Test
    fun inputByteLengthMismatchIsRejected() {
        val tooShort = byteArrayOf(1, 2, 3)
        assertFailsWith<IllegalArgumentException> { scalePadRgb24(tooShort, 2, 2, 4, 4) }
        val tooLong = ByteArray(13)
        assertFailsWith<IllegalArgumentException> { scalePadRgb24(tooLong, 2, 2, 4, 4) }
    }
}
