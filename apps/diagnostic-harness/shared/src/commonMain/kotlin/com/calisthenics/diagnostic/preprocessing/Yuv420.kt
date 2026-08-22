package com.calisthenics.diagnostic.preprocessing

enum class YuvColorStandard { BT601 }

enum class YuvColorRange { FULL }

enum class FrameRotation { R0, R90, R180, R270 }

class YuvPlane(bytes: ByteArray, val rowStride: Int, val pixelStride: Int) {
    private val storage = bytes.copyOf()

    init {
        require(rowStride > 0) { "rowStride must be positive" }
        require(pixelStride > 0) { "pixelStride must be positive" }
        require(rowStride >= pixelStride) { "rowStride must be at least pixelStride" }
    }

    val bytes: ByteArray
        get() = storage.copyOf()

    internal fun requireBounds(width: Int, height: Int, name: String) {
        val logicalRowBytes = checkedAdd(
            checkedMultiply((width - 1).toLong(), pixelStride.toLong(), name),
            1L,
            name,
        )
        require(rowStride.toLong() >= logicalRowBytes) { "$name plane rowStride does not cover a logical row" }
        val last = checkedAdd(
            checkedMultiply((height - 1).toLong(), rowStride.toLong(), name),
            checkedMultiply((width - 1).toLong(), pixelStride.toLong(), name),
            name,
        )
        require(last < storage.size.toLong()) { "$name plane bytes do not cover declared dimensions" }
    }

    internal fun sample(x: Int, y: Int): Int = storage[y * rowStride + x * pixelStride].toInt() and 0xFF

    private fun checkedMultiply(left: Long, right: Long, name: String): Long = try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$name plane geometry overflows Long")
    }

    private fun checkedAdd(left: Long, right: Long, name: String): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$name plane geometry overflows Long")
    }
}

data class Yuv420Frame(
    val width: Int,
    val height: Int,
    val y: YuvPlane,
    val u: YuvPlane,
    val v: YuvPlane,
    val colorStandard: YuvColorStandard,
    val colorRange: YuvColorRange,
    val rotation: FrameRotation,
    val mirrored: Boolean,
) {
    init {
        require(width > 0 && height > 0) { "dimensions must be positive" }
        y.requireBounds(width, height, "Y")
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        u.requireBounds(chromaWidth, chromaHeight, "U")
        v.requireBounds(chromaWidth, chromaHeight, "V")
    }
}

fun uprightDimensions(width: Int, height: Int, rotation: FrameRotation): Pair<Int, Int> {
    require(width > 0 && height > 0) { "dimensions must be positive" }
    return if (rotation == FrameRotation.R90 || rotation == FrameRotation.R270) {
        height to width
    } else {
        width to height
    }
}

fun yuv420ToUprightRgb24(frame: Yuv420Frame): ByteArray {
    require(frame.colorStandard == YuvColorStandard.BT601) { "only BT601 is admitted" }
    require(frame.colorRange == YuvColorRange.FULL) { "only FULL range is admitted" }

    val (outputWidth, outputHeight) = uprightDimensions(frame.width, frame.height, frame.rotation)
    val output = ByteArray(Rgb24.byteLength(outputWidth, outputHeight))
    for (outputY in 0 until outputHeight) {
        for (outputX in 0 until outputWidth) {
            val uprightX = if (frame.mirrored) outputWidth - 1 - outputX else outputX
            val (sourceX, sourceY) = sourceCoordinate(uprightX, outputY, frame.width, frame.height, frame.rotation)
            val y = frame.y.sample(sourceX, sourceY)
            val chromaX = sourceX / 2
            val chromaY = sourceY / 2
            val u = frame.u.sample(chromaX, chromaY)
            val v = frame.v.sample(chromaX, chromaY)
            val offset = Rgb24.pixelOffset(outputX, outputY, outputWidth)
            output[offset] = convert(y, v - 128, 1_402_000).toByte()
            output[offset + 1] = convert(y, u - 128, v - 128, -344_136, -714_136).toByte()
            output[offset + 2] = convert(y, u - 128, 1_772_000).toByte()
        }
    }
    return output
}

private fun sourceCoordinate(
    uprightX: Int,
    uprightY: Int,
    width: Int,
    height: Int,
    rotation: FrameRotation,
): Pair<Int, Int> = when (rotation) {
    FrameRotation.R0 -> uprightX to uprightY
    FrameRotation.R90 -> uprightY to (height - 1 - uprightX)
    FrameRotation.R180 -> (width - 1 - uprightX) to (height - 1 - uprightY)
    FrameRotation.R270 -> (width - 1 - uprightY) to uprightX
}

private fun convert(y: Int, delta: Int, coefficient: Int): Int =
    clamp(roundHalfUp(y.toLong() * 1_000_000L + delta.toLong() * coefficient))

private fun convert(y: Int, uDelta: Int, vDelta: Int, uCoefficient: Int, vCoefficient: Int): Int =
    clamp(roundHalfUp(y.toLong() * 1_000_000L + uDelta.toLong() * uCoefficient + vDelta.toLong() * vCoefficient))

private fun roundHalfUp(numerator: Long): Long = floorDiv(numerator + 500_000L, 1_000_000L)

private fun floorDiv(numerator: Long, denominator: Long): Long {
    val quotient = numerator / denominator
    val remainder = numerator % denominator
    return if (remainder != 0L && (remainder < 0) != (denominator < 0)) quotient - 1 else quotient
}

private fun clamp(value: Long): Int = value.coerceIn(0L, 255L).toInt()
