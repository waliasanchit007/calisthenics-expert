package com.calisthenics.diagnostic.viewer

data class ViewerPoint(val x: Float, val y: Float)

/** Pure inverse/display geometry for the upright image used by the movement viewer. */
class ViewerGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val modelWidth: Int = 256,
    val modelHeight: Int = 256,
) {
    private val scaledWidth: Int
    private val scaledHeight: Int
    private val left: Int
    private val top: Int

    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        require(modelWidth > 0 && modelHeight > 0) { "model dimensions must be positive" }

        val modelWidthLimited = modelWidth.toLong() * sourceHeight <= modelHeight.toLong() * sourceWidth
        val scaleNumerator = if (modelWidthLimited) modelWidth else modelHeight
        val scaleDenominator = if (modelWidthLimited) sourceWidth else sourceHeight
        scaledWidth = roundHalfUp(sourceWidth.toLong() * scaleNumerator, scaleDenominator.toLong()).coerceAtLeast(1)
        scaledHeight = roundHalfUp(sourceHeight.toLong() * scaleNumerator, scaleDenominator.toLong()).coerceAtLeast(1)
        left = (modelWidth - scaledWidth) / 2
        top = (modelHeight - scaledHeight) / 2
    }

    /**
     * Maps a model-normalized point back to normalized coordinates in the upright source image.
     * Points in scale-pad padding, outside the model canvas, or with nonfinite coordinates are
     * not source points and return null.
     */
    fun modelToSource(x: Double, y: Double): ViewerPoint? {
        if (!x.isFinite() || !y.isFinite()) return null

        val modelX = x * modelWidth
        val modelY = y * modelHeight
        val right = left + scaledWidth
        val bottom = top + scaledHeight
        if (modelX < left || modelX > right || modelY < top || modelY > bottom) return null

        return ViewerPoint(
            ((modelX - left) / scaledWidth).toFloat(),
            ((modelY - top) / scaledHeight).toFloat(),
        )
    }

    /** Maps a normalized source point into a centered aspect-fit viewport in pixels. */
    fun fitPoint(point: ViewerPoint, viewportWidth: Float, viewportHeight: Float): ViewerPoint {
        require(viewportWidth.isFinite() && viewportWidth > 0f) { "viewport width must be positive and finite" }
        require(viewportHeight.isFinite() && viewportHeight > 0f) { "viewport height must be positive and finite" }

        val scale = minOf(
            viewportWidth.toDouble() / sourceWidth,
            viewportHeight.toDouble() / sourceHeight,
        )
        val fittedWidth = sourceWidth * scale
        val fittedHeight = sourceHeight * scale
        return ViewerPoint(
            (point.x * fittedWidth + (viewportWidth - fittedWidth) / 2.0).toFloat(),
            (point.y * fittedHeight + (viewportHeight - fittedHeight) / 2.0).toFloat(),
        )
    }

    private fun roundHalfUp(numerator: Long, denominator: Long): Int {
        val quotient = numerator / denominator
        val remainder = numerator % denominator
        return (quotient + if (remainder * 2L >= denominator) 1L else 0L).toInt()
    }
}
