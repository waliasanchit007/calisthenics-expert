package com.calisthenics.diagnostic.study

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SustainedProtocolTest {
    @Test
    fun `histogram uses fixed nearest rank bounds without retaining samples`() {
        val histogram = SustainedLatencyHistogram()
        repeat(4) { histogram.record(900_000) }
        repeat(3) { histogram.record(1_500_000) }
        repeat(2) { histogram.record(3_000_000) }
        histogram.record(7_000_000)

        assertEquals(10, histogram.count)
        assertEquals(LatencyBucketBounds(1_000_001, 2_000_000), histogram.percentileBounds(50))
        assertEquals(LatencyBucketBounds(4_000_001, 8_000_000), histogram.percentileBounds(95))
        assertEquals(14, histogram.bucketCounts().size)
    }

    @Test
    fun `stop request must be exact and never exceed one second allowance`() {
        assertEquals(M05BSustainedProtocol.TARGET_DURATION_NS, M05BSustainedProtocol.stopRequestNs())
        assertFailsWith<IllegalArgumentException> {
            M05BSustainedProtocol.validateStopRequest(M05BSustainedProtocol.MAX_STOP_REQUEST_NS + 1)
        }
    }
}
