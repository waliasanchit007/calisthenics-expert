package com.calisthenics.diagnostic.live

import com.calisthenics.diagnostic.contract.CadenceSample
import com.calisthenics.diagnostic.contract.RawPoseFrame

private const val ONE_SECOND_NS = 1_000_000_000L

/**
 * Counts emitted records in completed, session-relative one-second windows.
 *
 * The final partial window is intentionally omitted. A zero-valued completed window is real
 * evidence of an observed stall, while an empty result means no complete window was observed.
 */
fun achievedInferenceFpsTimeseriesOf(
    emittedRecords: List<RawPoseFrame>,
    windowNs: Long = ONE_SECOND_NS,
): List<CadenceSample> {
    require(windowNs > 0L) { "cadence window must be positive" }
    if (emittedRecords.isEmpty()) return emptyList()
    emittedRecords.forEach { require(it.captureTimestampNs >= 0L) { "capture timestamp must be non-negative" } }
    val lastTimestamp = emittedRecords.maxOf { it.captureTimestampNs }
    val completedWindows = lastTimestamp / windowNs
    if (completedWindows == 0L) return emptyList()

    return (1L..completedWindows).map { windowNumber ->
        val windowEnd = windowNumber * windowNs
        val windowStart = windowEnd - windowNs
        val count = emittedRecords.count { it.captureTimestampNs in windowStart until windowEnd }
        CadenceSample(windowEnd, count.toDouble())
    }
}
