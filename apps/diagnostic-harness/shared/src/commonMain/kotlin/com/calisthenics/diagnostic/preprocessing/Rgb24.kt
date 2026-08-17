package com.calisthenics.diagnostic.preprocessing

/**
 * Byte layout for the canonical model-input plane declared in ADR-0002: tightly
 * packed, row-major, full-range sRGB RGB24. No bitmap or platform pixel type is used.
 */
object Rgb24 {
    const val CHANNELS: Int = 3

    fun byteLength(width: Int, height: Int): Int = width * height * CHANNELS

    fun pixelOffset(x: Int, y: Int, width: Int): Int = CHANNELS * (y * width + x)
}
