package com.calisthenics.diagnostic.preprocessing

private const val MAX_SOURCE_DIMENSION = 16_384
private const val MAX_TARGET_DIMENSION = 4_096

/**
 * Converts YUV420 directly to the exact RGB24 scale-pad result used by the oracle path.
 * The output is upright, centre-of-pixel bilinear, half-up rounded, and black padded.
 */
fun yuv420ToScalePadRgb24(
    frame: Yuv420Frame,
    targetWidth: Int = 256,
    targetHeight: Int = 256,
): ByteArray {
    require(frame.colorStandard == YuvColorStandard.BT601) { "only BT601 is admitted" }
    require(frame.colorRange == YuvColorRange.FULL) { "only FULL range is admitted" }
    require(frame.width <= MAX_SOURCE_DIMENSION && frame.height <= MAX_SOURCE_DIMENSION) {
        "source dimensions must be at most $MAX_SOURCE_DIMENSION"
    }
    require(targetWidth in 1..MAX_TARGET_DIMENSION && targetHeight in 1..MAX_TARGET_DIMENSION) {
        "target dimensions must be between 1 and $MAX_TARGET_DIMENSION"
    }

    val (uprightWidth, uprightHeight) = uprightDimensions(frame.width, frame.height, frame.rotation)
    val widthLimited = targetWidth.toLong() * uprightHeight <= targetHeight.toLong() * uprightWidth
    val scaleNumerator = if (widthLimited) targetWidth.toLong() else targetHeight.toLong()
    val scaleDenominator = if (widthLimited) uprightWidth.toLong() else uprightHeight.toLong()
    val scaledWidth = if (widthLimited) {
        targetWidth
    } else {
        roundHalfUp(uprightWidth.toLong() * scaleNumerator, scaleDenominator)
    }.coerceAtLeast(1)
    val scaledHeight = if (widthLimited) {
        roundHalfUp(uprightHeight.toLong() * scaleNumerator, scaleDenominator)
    } else {
        targetHeight
    }.coerceAtLeast(1)
    val left = (targetWidth - scaledWidth) / 2
    val top = (targetHeight - scaledHeight) / 2
    val output = ByteArray(Rgb24.byteLength(targetWidth, targetHeight))
    val dx = 2L * scaledWidth
    val dy = 2L * scaledHeight
    val denominator = dx * dy
    val maxX = (uprightWidth - 1).toLong() * dx
    val maxY = (uprightHeight - 1).toLong() * dy

    for (destinationY in 0 until scaledHeight) {
        val ny = (((2L * destinationY + 1L) * uprightHeight) - scaledHeight)
            .coerceIn(0L, maxY)
        val y0 = (ny / dy).toInt()
        val y1 = minOf(y0 + 1, uprightHeight - 1)
        val wy = ny % dy
        val oneMinusWy = dy - wy
        for (destinationX in 0 until scaledWidth) {
            val nx = (((2L * destinationX + 1L) * uprightWidth) - scaledWidth)
                .coerceIn(0L, maxX)
            val x0 = (nx / dx).toInt()
            val x1 = minOf(x0 + 1, uprightWidth - 1)
            val wx = nx % dx
            val oneMinusWx = dx - wx
            val topLeft = frame.uprightRgb24At(x0, y0, uprightWidth)
            val topRight = frame.uprightRgb24At(x1, y0, uprightWidth)
            val bottomLeft = frame.uprightRgb24At(x0, y1, uprightWidth)
            val bottomRight = frame.uprightRgb24At(x1, y1, uprightWidth)
            val outputOffset = Rgb24.pixelOffset(destinationX + left, destinationY + top, targetWidth)
            for (channel in 0 until Rgb24.CHANNELS) {
                val topLeftValue = channel(topLeft, channel)
                val topRightValue = channel(topRight, channel)
                val bottomLeftValue = channel(bottomLeft, channel)
                val bottomRightValue = channel(bottomRight, channel)
                val numerator = topLeftValue.toLong() * oneMinusWx * oneMinusWy +
                    topRightValue.toLong() * wx * oneMinusWy +
                    bottomLeftValue.toLong() * oneMinusWx * wy +
                    bottomRightValue.toLong() * wx * wy
                output[outputOffset + channel] = ((numerator + denominator / 2L) / denominator).toByte()
            }
        }
    }
    return output
}

private fun roundHalfUp(numerator: Long, denominator: Long): Int =
    ((2L * numerator + denominator) / (2L * denominator)).toInt()

private fun channel(rgb: Int, channel: Int): Int = when (channel) {
    0 -> rgb shr 16 and 0xFF
    1 -> rgb shr 8 and 0xFF
    else -> rgb and 0xFF
}
