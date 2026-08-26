package com.calisthenics.diagnostic.live

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Constant-space capture-cadence evidence accumulated while callbacks are admitted. */
class LiveCaptureCadenceAccumulator(private val requestedCameraFps: Int) {
    private var deliveredCount = 0L
    private var firstTimestampNs: Long? = null
    private var lastTimestampNs: Long? = null

    val retainedTimestampCount: Int
        get() = 0

    init {
        require(requestedCameraFps > 0)
    }

    fun observe(timestampNs: Long) {
        require(timestampNs >= 0L) { "capture timestamps must be non-negative" }
        if (firstTimestampNs == null) firstTimestampNs = timestampNs
        lastTimestampNs = timestampNs
        deliveredCount++
    }

    fun observedSpanNs(): Long? = firstTimestampNs?.let { first ->
        lastTimestampNs?.minus(first)
    }

    fun snapshot(deliveredCallbackClassificationGap: Long): LiveCaptureCadenceDiagnostics {
        require(deliveredCallbackClassificationGap >= 0L) { "classification gap must be non-negative" }
        return LiveCaptureCadenceDiagnostics.fromObservedBounds(
            deliveredCallbackCount = deliveredCount,
            firstTimestampNs = firstTimestampNs,
            lastTimestampNs = lastTimestampNs,
            requestedCameraFps = requestedCameraFps,
            deliveredCallbackClassificationGap = deliveredCallbackClassificationGap,
        )
    }
}

/**
 * Observed CameraX analyzer-callback cadence. The upstream gap is an estimate over the closed
 * observed interval, never a sensor-frame count or a delivered-callback skip classification.
 */
data class LiveCaptureCadenceDiagnostics(
    val deliveredCallbackCount: Long,
    val firstDeliveredCaptureTimestampNs: Long?,
    val lastDeliveredCaptureTimestampNs: Long?,
    val observedCaptureSpanNs: Long?,
    val requestedCameraFps: Int,
    val requestedCallbackOpportunities: Long,
    val upstreamUnclassifiedCadenceGapEstimate: Long,
    val deliveredCallbackClassificationGap: Long,
) {
    init {
        require(deliveredCallbackCount >= 0L)
        require(requestedCameraFps > 0)
        require(requestedCallbackOpportunities >= 0L)
        require(upstreamUnclassifiedCadenceGapEstimate >= 0L)
        require(deliveredCallbackClassificationGap >= 0L)
    }

    fun asJson(): JsonObject = buildJsonObject {
        put("delivered_callback_count", deliveredCallbackCount)
        put("first_delivered_capture_timestamp_ns", firstDeliveredCaptureTimestampNs)
        put("last_delivered_capture_timestamp_ns", lastDeliveredCaptureTimestampNs)
        put("observed_capture_span_ns", observedCaptureSpanNs)
        put("requested_camera_fps", requestedCameraFps)
        put("requested_callback_opportunities", requestedCallbackOpportunities)
        put("upstream_unclassified_cadence_gap_estimate", upstreamUnclassifiedCadenceGapEstimate)
        put("delivered_callback_classification_gap", deliveredCallbackClassificationGap)
    }

    /** The upstream estimate is diagnostic only; classification accounting must still close. */
    fun isInternallyConsistentWith(counts: LiveCaptureCounts): Boolean {
        if (requestedCameraFps != REQUESTED_CAMERA_FPS ||
            deliveredCallbackCount != counts.cameraFramesReceived ||
            deliveredCallbackClassificationGap != counts.deliveredCallbackClassificationGap ||
            upstreamUnclassifiedCadenceGapEstimate !=
            (requestedCallbackOpportunities - deliveredCallbackCount).coerceAtLeast(0L)
        ) {
            return false
        }
        return when (deliveredCallbackCount) {
            0L -> firstDeliveredCaptureTimestampNs == null &&
                lastDeliveredCaptureTimestampNs == null &&
                observedCaptureSpanNs == null &&
                requestedCallbackOpportunities == 0L

            else -> {
                val first = firstDeliveredCaptureTimestampNs ?: return false
                val last = lastDeliveredCaptureTimestampNs ?: return false
                val span = observedCaptureSpanNs ?: return false
                first >= 0L && last >= first && span == last - first &&
                    requestedCallbackOpportunities == closedIntervalOpportunities(span, requestedCameraFps)
            }
        }
    }

    companion object {
        fun fromAdmittedTimestamps(
            timestampsNs: List<Long>,
            requestedCameraFps: Int,
            deliveredCallbackClassificationGap: Long,
        ): LiveCaptureCadenceDiagnostics {
            require(timestampsNs.all { it >= 0L }) { "capture timestamps must be non-negative" }
            require(deliveredCallbackClassificationGap >= 0L) { "classification gap must be non-negative" }
            val first = timestampsNs.firstOrNull()
            val last = timestampsNs.lastOrNull()
            val span = if (first == null || last == null) null else {
                require(last >= first) { "capture timestamps must be monotonic" }
                last - first
            }
            return fromObservedBounds(
                deliveredCallbackCount = timestampsNs.size.toLong(),
                firstTimestampNs = first,
                lastTimestampNs = last,
                requestedCameraFps = requestedCameraFps,
                deliveredCallbackClassificationGap = deliveredCallbackClassificationGap,
            )
        }

        internal fun fromObservedBounds(
            deliveredCallbackCount: Long,
            firstTimestampNs: Long?,
            lastTimestampNs: Long?,
            requestedCameraFps: Int,
            deliveredCallbackClassificationGap: Long,
        ): LiveCaptureCadenceDiagnostics {
            require(deliveredCallbackCount >= 0L)
            require((deliveredCallbackCount == 0L) == (firstTimestampNs == null && lastTimestampNs == null)) {
                "empty bounds must match delivered callback count"
            }
            val span = if (firstTimestampNs == null || lastTimestampNs == null) null else {
                require(lastTimestampNs >= firstTimestampNs) { "capture timestamps must be monotonic" }
                lastTimestampNs - firstTimestampNs
            }
            val opportunities = span?.let { closedIntervalOpportunities(it, requestedCameraFps) } ?: 0L
            return LiveCaptureCadenceDiagnostics(
                deliveredCallbackCount = deliveredCallbackCount,
                firstDeliveredCaptureTimestampNs = firstTimestampNs,
                lastDeliveredCaptureTimestampNs = lastTimestampNs,
                observedCaptureSpanNs = span,
                requestedCameraFps = requestedCameraFps,
                requestedCallbackOpportunities = opportunities,
                upstreamUnclassifiedCadenceGapEstimate = (opportunities - deliveredCallbackCount).coerceAtLeast(0L),
                deliveredCallbackClassificationGap = deliveredCallbackClassificationGap,
            )
        }

        /** `floor(span * fps / 1e9) + 1`, evaluated without floating-point duration arithmetic. */
        private fun closedIntervalOpportunities(spanNs: Long, fps: Int): Long {
            val wholeSeconds = spanNs / NANOS_PER_SECOND
            val remainderNs = spanNs % NANOS_PER_SECOND
            return wholeSeconds * fps.toLong() + remainderNs * fps / NANOS_PER_SECOND + 1L
        }

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val REQUESTED_CAMERA_FPS = 30
    }
}
