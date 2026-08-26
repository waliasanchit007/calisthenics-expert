package com.calisthenics.diagnostic.live

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveCaptureCadenceDiagnosticsTest {
    @Test
    fun incremental_diagnostics_match_the_released_batch_oracle() {
        val timestamps = listOf(0L, 33_333_333L, 66_666_666L, 1_000_000_000L)
        val accumulator = LiveCaptureCadenceAccumulator(requestedCameraFps = 30)

        timestamps.forEach(accumulator::observe)

        assertEquals(
            LiveCaptureCadenceDiagnostics.fromAdmittedTimestamps(timestamps, 30, 2),
            accumulator.snapshot(deliveredCallbackClassificationGap = 2),
        )
    }

    @Test
    fun retained_cardinality_is_constant_across_session_lengths() {
        val short = LiveCaptureCadenceAccumulator(30)
        val long = LiveCaptureCadenceAccumulator(30)
        repeat(1_000) { short.observe(it * 33_333_333L) }
        repeat(100_000) { long.observe(it * 33_333_333L) }

        assertEquals(0, short.retainedTimestampCount)
        assertEquals(0, long.retainedTimestampCount)
        assertEquals(1_000L, short.snapshot(0).deliveredCallbackCount)
        assertEquals(100_000L, long.snapshot(0).deliveredCallbackCount)
    }
}
