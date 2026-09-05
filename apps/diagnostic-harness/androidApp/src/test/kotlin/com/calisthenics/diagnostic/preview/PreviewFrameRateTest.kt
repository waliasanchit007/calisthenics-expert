package com.calisthenics.diagnostic.preview

import org.junit.Test
import kotlin.test.*

class PreviewFrameRateTest {
    @Test fun usesObservedFrameIntervalsAndDoesNotCountDuplicateCallbacks() {
        val rate = PreviewFrameRate()
        assertNull(rate.fps(0))
        rate.onFrame(1_000_000_000)
        assertNull(rate.fps(1_000_000_000))
        rate.onFrame(1_500_000_000)
        rate.onFrame(1_500_000_000)
        rate.onFrame(2_000_000_000)
        assertEquals(2.0,rate.fps(2_000_000_000))
        assertEquals(0.0,rate.fps(5_000_000_000))
    }
}
