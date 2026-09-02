package com.calisthenics.diagnostic.study

/** Fixed before physical observation; values are measurement resolution, not acceptance limits. */
object M05BSustainedProtocol {
    const val TARGET_DURATION_NS = 1_500_000_000_000L
    const val MAX_STOP_REQUEST_NS = 1_501_000_000_000L
    val latencyUpperBoundsNs: List<Long?> = listOf(
        1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_000_000L, 32_000_000L,
        64_000_000L, 128_000_000L, 256_000_000L, 512_000_000L, 1_024_000_000L,
        2_048_000_000L, 4_096_000_000L, null,
    )

    fun stopRequestNs(): Long = TARGET_DURATION_NS

    fun validateStopRequest(value: Long) {
        require(value == TARGET_DURATION_NS) { "stop must be requested exactly at 1,500 seconds" }
        require(value <= MAX_STOP_REQUEST_NS) { "stop request exceeds one-second allowance" }
    }
}

data class LatencyBucketBounds(val lowerInclusiveNs: Long, val upperInclusiveNs: Long?)

/** Bounded histogram: it retains fourteen counters, never individual latency values. */
class SustainedLatencyHistogram {
    private val counts = LongArray(M05BSustainedProtocol.latencyUpperBoundsNs.size)

    val count: Long get() = counts.sum()

    fun record(latencyNs: Long) {
        require(latencyNs >= 0L) { "latency must be non-negative" }
        val index = M05BSustainedProtocol.latencyUpperBoundsNs.indexOfFirst { upper ->
            upper == null || latencyNs <= upper
        }
        check(index >= 0)
        check(counts[index] < Long.MAX_VALUE) { "latency histogram counter overflow" }
        counts[index] += 1L
    }

    fun bucketCounts(): List<Long> = counts.toList()

    fun snapshot(): M05BSustainedLatencySummary = M05BSustainedLatencySummary(bucketCounts())

    fun percentileBounds(percentile: Int): LatencyBucketBounds {
        require(percentile in setOf(50, 95, 99)) { "only frozen percentile ranks are supported" }
        check(count > 0L) { "cannot derive percentile from an empty histogram" }
        val rank = (count * percentile + 99L) / 100L
        var cumulative = 0L
        var lower = 0L
        M05BSustainedProtocol.latencyUpperBoundsNs.forEachIndexed { index, upper ->
            cumulative += counts[index]
            if (cumulative >= rank) return LatencyBucketBounds(lower, upper)
            if (upper != null) lower = upper + 1L
        }
        error("histogram rank was not contained")
    }
}
