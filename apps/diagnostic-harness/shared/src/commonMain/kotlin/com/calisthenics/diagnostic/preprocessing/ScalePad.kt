package com.calisthenics.diagnostic.preprocessing

/**
 * Exact-fraction rational number used to reproduce the Python reference's
 * [fractions.Fraction] arithmetic without floating-point rounding.
 */
private class Frac(numerator: Long, denominator: Long) {
    val num: Long
    val den: Long

    init {
        require(denominator != 0L) { "denominator must not be zero" }
        val sign = if (denominator < 0) -1L else 1L
        val n = numerator * sign
        val d = denominator * sign
        val g = gcd(if (n < 0) -n else n, d)
        num = if (g == 0L) 0L else n / g
        den = if (g == 0L) 1L else d / g
    }

    operator fun plus(other: Frac): Frac = Frac(num * other.den + other.num * den, den * other.den)
    operator fun minus(other: Frac): Frac = Frac(num * other.den - other.num * den, den * other.den)
    operator fun times(other: Frac): Frac = Frac(num * other.num, den * other.den)
    operator fun compareTo(other: Frac): Int = (num * other.den).compareTo(other.num * den)

    companion object {
        val ZERO = Frac(0, 1)
        val HALF = Frac(1, 2)

        fun of(value: Long): Frac = Frac(value, 1)

        private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

private fun minFrac(a: Frac, b: Frac): Frac = if (a.compareTo(b) <= 0) a else b
private fun maxFrac(a: Frac, b: Frac): Frac = if (a.compareTo(b) >= 0) a else b

/** floor(value + 1/2), matching the Python reference's half-up rounding for non-negative values. */
private fun roundHalfUp(value: Frac): Int {
    val shifted = value + Frac.HALF
    return floorDiv(shifted.num, shifted.den).toInt()
}

private fun floorDiv(numerator: Long, denominator: Long): Long {
    val quotient = numerator / denominator
    val remainder = numerator % denominator
    return if (remainder != 0L && (remainder < 0) != (denominator < 0)) quotient - 1 else quotient
}

private fun sourceCoordinate(destination: Int, sourceSize: Int, destinationSize: Int): Frac {
    var coordinate = Frac(2L * destination + 1, 2) * Frac(sourceSize.toLong(), destinationSize.toLong())
    coordinate -= Frac.HALF
    return maxFrac(Frac.ZERO, minFrac(Frac.of((sourceSize - 1).toLong()), coordinate))
}

/**
 * Scales packed RGB24 [source] to fit `targetWidth`x`targetHeight` preserving aspect ratio with
 * centre-of-pixel bilinear interpolation, half-up channel rounding, and exact black padding, per
 * ADR-0002 and the committed decoded-frame fixture contract.
 */
fun scalePadRgb24(
    source: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): ByteArray {
    require(sourceWidth >= 1 && sourceHeight >= 1 && targetWidth >= 1 && targetHeight >= 1) {
        "dimensions must be positive"
    }
    val expectedLength = Rgb24.byteLength(sourceWidth, sourceHeight)
    require(source.size == expectedLength) { "source byte length must equal $expectedLength" }

    val scale = minFrac(
        Frac(targetWidth.toLong(), sourceWidth.toLong()),
        Frac(targetHeight.toLong(), sourceHeight.toLong()),
    )
    val scaledWidth = maxOf(1, roundHalfUp(Frac.of(sourceWidth.toLong()) * scale))
    val scaledHeight = maxOf(1, roundHalfUp(Frac.of(sourceHeight.toLong()) * scale))
    val left = (targetWidth - scaledWidth) / 2
    val top = (targetHeight - scaledHeight) / 2
    val output = ByteArray(Rgb24.byteLength(targetWidth, targetHeight))

    for (destinationY in 0 until scaledHeight) {
        val sourceY = sourceCoordinate(destinationY, sourceHeight, scaledHeight)
        val y0 = floorDiv(sourceY.num, sourceY.den).toInt()
        val y1 = minOf(y0 + 1, sourceHeight - 1)
        val wy = sourceY - Frac.of(y0.toLong())
        for (destinationX in 0 until scaledWidth) {
            val sourceX = sourceCoordinate(destinationX, sourceWidth, scaledWidth)
            val x0 = floorDiv(sourceX.num, sourceX.den).toInt()
            val x1 = minOf(x0 + 1, sourceWidth - 1)
            val wx = sourceX - Frac.of(x0.toLong())
            val outputOffset = Rgb24.pixelOffset(destinationX + left, destinationY + top, targetWidth)
            for (channel in 0 until Rgb24.CHANNELS) {
                val topLeft = source[Rgb24.pixelOffset(x0, y0, sourceWidth) + channel].toUnsignedInt()
                val topRight = source[Rgb24.pixelOffset(x1, y0, sourceWidth) + channel].toUnsignedInt()
                val bottomLeft = source[Rgb24.pixelOffset(x0, y1, sourceWidth) + channel].toUnsignedInt()
                val bottomRight = source[Rgb24.pixelOffset(x1, y1, sourceWidth) + channel].toUnsignedInt()

                val oneMinusWx = Frac.of(1) - wx
                val oneMinusWy = Frac.of(1) - wy
                val topValue = oneMinusWx * Frac.of(topLeft.toLong()) + wx * Frac.of(topRight.toLong())
                val bottomValue = oneMinusWx * Frac.of(bottomLeft.toLong()) + wx * Frac.of(bottomRight.toLong())
                val value = oneMinusWy * topValue + wy * bottomValue
                output[outputOffset + channel] = roundHalfUp(value).toByte()
            }
        }
    }
    return output
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF
