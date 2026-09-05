package com.calisthenics.diagnostic.viewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ViewerGeometryTest {
    @Test
    fun squareSourceMapsModelCornersToUprightSourceCorners() {
        val geometry = ViewerGeometry(640, 640)

        assertEquals(ViewerPoint(0f, 0f), geometry.modelToSource(0.0, 0.0))
        assertEquals(ViewerPoint(1f, 1f), geometry.modelToSource(1.0, 1.0))
        assertEquals(ViewerPoint(0.5f, 0.5f), geometry.modelToSource(0.5, 0.5))
    }

    @Test
    fun wideSourceRejectsTopAndBottomPadding() {
        val geometry = ViewerGeometry(400, 200)

        assertNull(geometry.modelToSource(0.5, 0.0))
        assertNull(geometry.modelToSource(0.5, 0.249))
        assertEquals(ViewerPoint(0.5f, 0f), geometry.modelToSource(0.5, 0.25))
        assertEquals(ViewerPoint(0f, 1f), geometry.modelToSource(0.0, 0.75))
        assertEquals(ViewerPoint(1f, 1f), geometry.modelToSource(1.0, 0.75))
        assertNull(geometry.modelToSource(0.5, 0.751))
    }

    @Test
    fun tallSourceRejectsLeftAndRightPadding() {
        val geometry = ViewerGeometry(200, 400)

        assertNull(geometry.modelToSource(0.0, 0.5))
        assertNull(geometry.modelToSource(0.249, 0.5))
        assertEquals(ViewerPoint(0f, 0.5f), geometry.modelToSource(0.25, 0.5))
        assertEquals(ViewerPoint(1f, 0.5f), geometry.modelToSource(0.75, 0.5))
        assertNull(geometry.modelToSource(0.751, 0.5))
    }

    @Test
    fun oddScaledDimensionUsesHalfUpRoundingAndIntegerPaddingOffset() {
        // 2x3 -> 8x7 uses scale 7/3, so the scaled width is round-half-up(14/3) = 5.
        // The remaining three columns use integer left = 3 / 2 = 1, leaving two on the right.
        val geometry = ViewerGeometry(2, 3, modelWidth = 8, modelHeight = 7)

        assertNull(geometry.modelToSource(0.0, 0.5))
        assertEquals(ViewerPoint(0f, 0.5f), geometry.modelToSource(1.0 / 8.0, 0.5))
        assertEquals(ViewerPoint(1f, 0.5f), geometry.modelToSource(6.0 / 8.0, 0.5))
        assertNull(geometry.modelToSource(7.0 / 8.0, 0.5))
    }

    @Test
    fun nonfiniteAndOutOfCanvasModelCoordinatesAreRejected() {
        val geometry = ViewerGeometry(100, 100)

        assertNull(geometry.modelToSource(Double.NaN, 0.5))
        assertNull(geometry.modelToSource(0.5, Double.POSITIVE_INFINITY))
        assertNull(geometry.modelToSource(Double.NEGATIVE_INFINITY, 0.5))
        assertNull(geometry.modelToSource(0.5, Double.NEGATIVE_INFINITY))
        assertNull(geometry.modelToSource(-0.001, 0.5))
        assertNull(geometry.modelToSource(1.001, 0.5))
        assertNull(geometry.modelToSource(0.5, -0.001))
        assertNull(geometry.modelToSource(0.5, 1.001))
    }

    @Test
    fun fitPointLetterboxesWideSourceInSquareViewport() {
        val geometry = ViewerGeometry(400, 200)

        assertEquals(ViewerPoint(0f, 75f), geometry.fitPoint(ViewerPoint(0f, 0f), 300f, 300f))
        assertEquals(ViewerPoint(150f, 150f), geometry.fitPoint(ViewerPoint(0.5f, 0.5f), 300f, 300f))
        assertEquals(ViewerPoint(300f, 225f), geometry.fitPoint(ViewerPoint(1f, 1f), 300f, 300f))
    }

    @Test
    fun fitPointLetterboxesTallSourceInSquareViewport() {
        val geometry = ViewerGeometry(200, 400)

        assertEquals(ViewerPoint(75f, 0f), geometry.fitPoint(ViewerPoint(0f, 0f), 300f, 300f))
        assertEquals(ViewerPoint(225f, 300f), geometry.fitPoint(ViewerPoint(1f, 1f), 300f, 300f))
    }

    @Test
    fun fitPointLetterboxesSquareSourceInWideViewport() {
        val geometry = ViewerGeometry(640, 640)

        assertEquals(ViewerPoint(40f, 0f), geometry.fitPoint(ViewerPoint(0f, 0f), 320f, 240f))
        assertEquals(ViewerPoint(280f, 240f), geometry.fitPoint(ViewerPoint(1f, 1f), 320f, 240f))
    }

    @Test
    fun invalidGeometryAndViewportDimensionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ViewerGeometry(0, 100) }
        assertFailsWith<IllegalArgumentException> { ViewerGeometry(100, -1) }
        assertFailsWith<IllegalArgumentException> { ViewerGeometry(100, 100, modelWidth = 0) }
        assertFailsWith<IllegalArgumentException> { ViewerGeometry(100, 100, modelHeight = -1) }

        val geometry = ViewerGeometry(100, 100)
        assertFailsWith<IllegalArgumentException> { geometry.fitPoint(ViewerPoint(0.5f, 0.5f), 0f, 100f) }
        assertFailsWith<IllegalArgumentException> { geometry.fitPoint(ViewerPoint(0.5f, 0.5f), 100f, -1f) }
        assertFailsWith<IllegalArgumentException> { geometry.fitPoint(ViewerPoint(0.5f, 0.5f), Float.NaN, 100f) }
        assertFailsWith<IllegalArgumentException> { geometry.fitPoint(ViewerPoint(0.5f, 0.5f), 100f, Float.POSITIVE_INFINITY) }
    }
}
