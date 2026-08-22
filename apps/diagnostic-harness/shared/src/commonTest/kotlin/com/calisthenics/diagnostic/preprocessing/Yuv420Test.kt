package com.calisthenics.diagnostic.preprocessing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import com.calisthenics.diagnostic.hash.Sha256Digest

private object Yuv420GoldenVector {
    const val WIDTH = 3
    const val HEIGHT = 3
    val y = byteArrayOf(16, 64, 128.toByte(), 235.toByte(), 0, 0, 0, 32, 96, 192.toByte(), 0, 0, 48, 112, 208.toByte(), 0)
    val u = byteArrayOf(90, 0, 240.toByte(), 0, 0, 0, 128.toByte(), 0, 54, 0, 0, 0)
    val v = byteArrayOf(240.toByte(), 0, 110, 0, 0, 0, 128.toByte(), 0, 180.toByte(), 0, 0, 0)
    val expected = byteArrayOf(173.toByte(), 0, 0, 221.toByte(), 0, 0, 103, 102, 255.toByte(), 157.toByte(), 0, 0, 157.toByte(), 0, 0, 7, 6, 230.toByte(), 0, 0, 0, 0, 0, 0, 121, 36, 0)
    const val Y_SHA256 = "e7157c1d72a1257d5aec71041efaf9fa4a9a51e0d8fbf63b2625fd1576bdf9a8"
    const val U_SHA256 = "ab493fbe4c24786a30dca20738caac3688214e24606b7bd15aff26cc20b64390"
    const val V_SHA256 = "0de3c7b9440a247cafe77b089ea0789b9c70ef21e695ac0592b4e6c0629ada93"
    const val Y_BYTE_LENGTH = 16
    const val U_BYTE_LENGTH = 12
    const val V_BYTE_LENGTH = 12
    const val EXPECTED_SHA256 = "63f53ffc9e376ca613c18ef120894757377dbb67bd1ef42166b2509a1d58b0c1"

    val rotatedExpected = byteArrayOf(189.toByte(), 0, 0, 255.toByte(), 93, 93, 48, 48, 48, 253.toByte(), 29, 29, 255.toByte(), 157.toByte(), 157.toByte(), 112, 112, 112)
    const val ROTATED_SHA256 = "1f9607eb9967be8cf4baed584006a216dea3822b0922714f9a29bf326e659037"
}

class Yuv420Test {
    @Test
    fun goldenVectorProducesExactRgbBytesAndHash() {
        assertEquals(Yuv420GoldenVector.Y_BYTE_LENGTH, Yuv420GoldenVector.y.size)
        assertEquals(Yuv420GoldenVector.U_BYTE_LENGTH, Yuv420GoldenVector.u.size)
        assertEquals(Yuv420GoldenVector.V_BYTE_LENGTH, Yuv420GoldenVector.v.size)
        assertEquals(Yuv420GoldenVector.Y_SHA256, Sha256Digest.hex(Yuv420GoldenVector.y))
        assertEquals(Yuv420GoldenVector.U_SHA256, Sha256Digest.hex(Yuv420GoldenVector.u))
        assertEquals(Yuv420GoldenVector.V_SHA256, Sha256Digest.hex(Yuv420GoldenVector.v))
        val frame = Yuv420Frame(
            Yuv420GoldenVector.WIDTH,
            Yuv420GoldenVector.HEIGHT,
            YuvPlane(Yuv420GoldenVector.y, 5, 1),
            YuvPlane(Yuv420GoldenVector.u, 6, 2),
            YuvPlane(Yuv420GoldenVector.v, 6, 2),
            YuvColorStandard.BT601,
            YuvColorRange.FULL,
            FrameRotation.R0,
            mirrored = false,
        )
        val actual = yuv420ToUprightRgb24(frame)
        assertEquals(Yuv420GoldenVector.EXPECTED_SHA256, Sha256Digest.hex(actual))
        assertContentEquals(Yuv420GoldenVector.expected, actual)
    }

    @Test
    fun planesAndFrameDefensivelyCopyMutableInputs() {
        val y = byteArrayOf(16, 32, 48, 64)
        val plane = YuvPlane(y, 2, 1)
        y[0] = 99
        assertEquals(16, plane.bytes[0].toInt())
        val frame = Yuv420Frame(2, 2, plane, YuvPlane(byteArrayOf(128.toByte()), 1, 1), YuvPlane(byteArrayOf(128.toByte()), 1, 1), YuvColorStandard.BT601, YuvColorRange.FULL, FrameRotation.R0, false)
        val exposed = frame.y.bytes
        exposed[0] = 99
        assertEquals(16, frame.y.bytes[0].toInt())
    }

    @Test
    fun invalidPlaneBoundsAndUnsupportedMetadataAreRejected() {
        assertFailsWith<IllegalArgumentException> { YuvPlane(byteArrayOf(1), 0, 1) }
        assertFailsWith<IllegalArgumentException> {
            Yuv420Frame(3, 2, YuvPlane(ByteArray(10), 2, 2), YuvPlane(byteArrayOf(128.toByte(), 128.toByte()), 1, 1), YuvPlane(byteArrayOf(128.toByte(), 128.toByte()), 1, 1), YuvColorStandard.BT601, YuvColorRange.FULL, FrameRotation.R0, false)
        }
        assertFailsWith<IllegalArgumentException> {
            Yuv420Frame(3, 3, YuvPlane(byteArrayOf(1), 1, 1), YuvPlane(byteArrayOf(1), 1, 1), YuvPlane(byteArrayOf(1), 1, 1), YuvColorStandard.BT601, YuvColorRange.FULL, FrameRotation.R0, false)
        }
    }

    @Test
    fun uprightDimensionsSwapForQuarterTurns() {
        assertEquals(3 to 2, uprightDimensions(2, 3, FrameRotation.R90))
        assertEquals(2 to 3, uprightDimensions(2, 3, FrameRotation.R180))
    }

    @Test
    fun rotationAndMirroringChangeOutputInTheDeclaredOrder() {
        val frame = Yuv420Frame(2, 3, YuvPlane(byteArrayOf(32, 96, 0, 160.toByte(), 224.toByte(), 0, 48, 112, 0), 3, 1), YuvPlane(byteArrayOf(90.toByte(), 240.toByte(), 128.toByte(), 54), 2, 1), YuvPlane(byteArrayOf(240.toByte(), 110, 128.toByte(), 180.toByte()), 2, 1), YuvColorStandard.BT601, YuvColorRange.FULL, FrameRotation.R90, true)
        val actual = yuv420ToUprightRgb24(frame)
        assertEquals(Yuv420GoldenVector.ROTATED_SHA256, Sha256Digest.hex(actual))
        assertContentEquals(Yuv420GoldenVector.rotatedExpected, actual)
        assertNotEquals(actual.toList(), yuv420ToUprightRgb24(frame.copy(mirrored = false)).toList())
    }
}
