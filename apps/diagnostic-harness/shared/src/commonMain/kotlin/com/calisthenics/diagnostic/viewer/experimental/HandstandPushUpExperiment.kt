package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark

/**
 * Orientation of a selected-mode handstand push-up. Recorded, never inferred: wall-supported
 * back-to-wall, wall-facing (chest to wall) and freestanding are different observations and must
 * not be pooled. Carries no numeric behaviour of its own.
 */
enum class HspuVariant { WALL_BACK_TO_WALL, WALL_FACING, FREESTANDING }

/**
 * Explicit prototype parameters for a single-side handstand push-up candidate cycle, frozen from
 * raw inspection of development footage. Image-plane geometry only; nothing here judges technique,
 * depth validity, load or muscle activation.
 *
 * Depth is measured as the SHOULDER-TO-WRIST VERTICAL SPAN, not as an elbow angle. On real
 * development footage the elbow angle failed to separate the endpoints at all: on one clip the
 * bottoms measured 154-169 degrees while the tops measured 163-179 degrees, an overlapping range,
 * because a side view foreshortens the forearm. The span separated the same endpoints cleanly.
 *
 * The span is compared against a per-clip [HspuReference] rather than an absolute threshold,
 * because framing and camera distance change the pixel scale between recordings.
 *
 * [minimumHipAboveShoulderFraction] is the admission gate that makes this a handstand push-up
 * rather than its neighbours: the hip must sit ABOVE the shoulder in the image (the athlete is
 * inverted). A parallel-bar dip fails it because its hip is below the shoulder; a horizontal
 * push-up fails it because hip and shoulder are level.
 */
data class HspuConfig(
    val variant: HspuVariant,
    val side: ArmSide,
    val minimumHipAboveShoulderFraction: Double,
    /**
     * Consecutive inverted frames required to establish the extended-span reference, and the
     * maximum relative spread across them. A short window latches onto the kick-up, where the span
     * is still changing: on one clip a 5-frame window fixed the reference at 0.308 while the
     * settled top was 0.21, after which "extended" was never reached again and 4 of 5 repetitions
     * were lost. The reference must come from a settled inversion.
     */
    val minimumReferenceSamples: Int,
    val maximumReferenceSpanSpreadRatio: Double,
    /** Span at or above this fraction of the reference counts as the extended (top) endpoint. */
    val extendedSpanRatio: Double,
    /** Span at or below this fraction of the reference counts as the lowered (bottom) endpoint. */
    val loweredSpanRatio: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
) {
    init {
        require(minimumHipAboveShoulderFraction.isFinite() && minimumHipAboveShoulderFraction > 0.0) {
            "minimum_hip_above_shoulder_fraction must be finite and positive"
        }
        require(minimumReferenceSamples >= 2) { "minimum_reference_samples must be at least 2" }
        require(
            maximumReferenceSpanSpreadRatio.isFinite() && maximumReferenceSpanSpreadRatio > 0.0,
        ) { "maximum_reference_span_spread_ratio must be finite and positive" }
        require(extendedSpanRatio.isFinite() && extendedSpanRatio in 0.0..1.5) {
            "extended_span_ratio must be finite and in [0, 1.5]"
        }
        require(loweredSpanRatio.isFinite() && loweredSpanRatio > 0.0) {
            "lowered_span_ratio must be finite and positive"
        }
        require(loweredSpanRatio < extendedSpanRatio) {
            "lowered_span_ratio must be less than extended_span_ratio"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEndpointSamples >= 2) { "minimum_endpoint_samples must be at least 2" }
    }
}

data class HspuPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class HspuPhase { WAITING, SUPPORTED, LOWERING, LOWERED, RAISING, UNCERTAIN }

enum class HspuReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    NOT_INVERTED,
    ZERO_LENGTH_SPAN,
    REFERENCE_UNAVAILABLE,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
}

/** Per-clip extended-span reference, in normalized image height. */
data class HspuReference(val extendedSpan: Double)

data class HspuMeasurement(
    val hipAboveShoulderFraction: Double?,
    val shoulderToWristSpan: Double?,
    val spanRatio: Double?,
    val reason: HspuReason,
)

data class HspuFrameResult(
    val timestampNs: Long,
    val hipAboveShoulderFraction: Double?,
    val shoulderToWristSpan: Double?,
    val spanRatio: Double?,
    val phase: HspuPhase,
    val count: Int,
    val reason: HspuReason,
    val counted: Boolean,
    /**
     * When [counted] is true, the instant the final endpoint was FIRST satisfied. The frame that
     * confirms a cycle is `minimum_endpoint_samples - 1` intervals later, so [timestampNs] is
     * systematically late by that much and must not be used as the event time.
     */
    val eventTimestampNs: Long? = null,
)

private const val L_SHOULDER = 11
private const val L_WRIST = 15
private const val L_HIP = 23
private const val R_SHOULDER = 12
private const val R_WRIST = 16
private const val R_HIP = 24

/**
 * Pure image-plane measurement of a candidate handstand push-up frame, including the inversion
 * admission gate. The gate does not establish correct technique; it only distinguishes this
 * candidate from its nearest visual neighbours under this measurement.
 */
fun measureHspu(
    frame: HspuPoseFrame,
    config: HspuConfig,
    reference: HspuReference?,
): HspuMeasurement {
    fun reject(reason: HspuReason) = HspuMeasurement(null, null, null, reason)

    if (frame.width <= 0 || frame.height <= 0) return reject(HspuReason.INVALID_DIMENSIONS)
    if (frame.landmarks.isEmpty()) return reject(HspuReason.NO_POSE)

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return reject(HspuReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) {
            return reject(HspuReason.DUPLICATE_LANDMARK_INDEX)
        }
    }

    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(L_SHOULDER, L_WRIST, L_HIP)
        ArmSide.RIGHT -> intArrayOf(R_SHOULDER, R_WRIST, R_HIP)
    }
    val joints = indices.map { byIndex[it] ?: return reject(HspuReason.MISSING_LANDMARK) }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return reject(HspuReason.INVALID_LANDMARK)
    }
    if (joints.any { landmark ->
            val visibility = landmark.visibility
            val presence = landmark.presence
            visibility == null || presence == null ||
                !visibility.isFinite() || !presence.isFinite() ||
                visibility !in 0.0..1.0 || presence !in 0.0..1.0 ||
                visibility < config.minimumConfidence || presence < config.minimumConfidence
        }
    ) {
        return reject(HspuReason.LOW_CONFIDENCE)
    }

    val shoulder = joints[0]
    val wrist = joints[1]
    val hip = joints[2]

    // Both are on the same normalized [0, 1] height axis, so the differences are height-normalized
    // fractions directly. Positive hipAbove means the hip sits above the shoulder (inverted).
    val hipAboveShoulderFraction = shoulder.y - hip.y
    val shoulderToWristSpan = wrist.y - shoulder.y

    if (hipAboveShoulderFraction < config.minimumHipAboveShoulderFraction) {
        return HspuMeasurement(hipAboveShoulderFraction, null, null, HspuReason.NOT_INVERTED)
    }
    // The hands must be below the shoulders: the athlete is pressing from the floor, not hanging.
    if (shoulderToWristSpan <= 0.0) {
        return HspuMeasurement(
            hipAboveShoulderFraction, shoulderToWristSpan, null,
            if (shoulderToWristSpan == 0.0) HspuReason.ZERO_LENGTH_SPAN else HspuReason.NOT_INVERTED,
        )
    }

    if (reference == null) {
        return HspuMeasurement(hipAboveShoulderFraction, shoulderToWristSpan, null, HspuReason.NONE)
    }
    val ratio = shoulderToWristSpan / reference.extendedSpan
    return HspuMeasurement(hipAboveShoulderFraction, shoulderToWristSpan, ratio, HspuReason.NONE)
}

/**
 * Conservative, explicit-config candidate cycle experiment for a selected-mode handstand push-up.
 *
 * It tracks an extended-lowered-extended excursion of the shoulder-to-wrist span while the athlete
 * is inverted. It does not judge depth validity, label form-valid repetitions, or claim anything
 * about load. Kick-up and dismount are not repetitions: they fail the inversion gate or the
 * reference requirement and cannot be counted.
 */
class HandstandPushUpExperiment(private val config: HspuConfig) {
    val phase: HspuPhase get() = if (reference == null) HspuPhase.WAITING else timing.phase.toHspuPhase()
    val count: Int get() = timing.count
    var reference: HspuReference? = null
        private set

    private var lastTimestampNs: Long? = null
    private val timing = ElbowCycleTiming(config.minimumEndpointSamples)
    private class PendingSpan(val timestampNs: Long, val span: Double)

    private val pendingSpans = ArrayDeque<PendingSpan>()

    /**
     * `minimumReferenceSamples` read as the duration it expresses; see [sampleCountAsDurationNs].
     * The frozen config says 15, which at 30 fps is the 466.7 ms of settled inversion this gates on.
     */
    private val minimumReferenceSpanNs = sampleCountAsDurationNs(config.minimumReferenceSamples)

    fun onFrame(frame: HspuPoseFrame): HspuFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            invalidate()
            return result(timestamp, null, null, null, HspuReason.INVALID_TIMESTAMP, false)
        }
        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                invalidate()
                return result(timestamp, null, null, null, HspuReason.DUPLICATE_TIMESTAMP, false)
            }
            if (timestamp < previous) {
                invalidate()
                return result(timestamp, null, null, null, HspuReason.BACKWARDS_TIMESTAMP, false)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val measurement = measureHspu(frame, config, reference)
        if (measurement.reason != HspuReason.NONE) {
            invalidate()
            return result(
                timestamp, measurement.hipAboveShoulderFraction, measurement.shoulderToWristSpan,
                measurement.spanRatio, measurement.reason, false,
            )
        }
        if (gap) {
            invalidate()
            return result(
                timestamp, measurement.hipAboveShoulderFraction, measurement.shoulderToWristSpan,
                measurement.spanRatio, HspuReason.CAPTURE_GAP, false,
            )
        }

        val span = measurement.shoulderToWristSpan!!
        if (reference == null) {
            establishReference(timestamp, span)
            return result(
                timestamp, measurement.hipAboveShoulderFraction, span, null,
                if (reference == null) HspuReason.REFERENCE_UNAVAILABLE else HspuReason.NONE, false,
            )
        }

        val ratio = span / reference!!.extendedSpan
        val eventNs = timing.onObservation(
            timestampNs = timestamp,
            isSupported = ratio >= config.extendedSpanRatio,
            isLowered = ratio <= config.loweredSpanRatio,
        )
        val counted = eventNs != null
        return result(
            timestamp, measurement.hipAboveShoulderFraction, span, ratio, HspuReason.NONE, counted, eventNs,
        )
    }

    /** The reference is the extended span of a SETTLED inversion, never of the kick-up. */
    private fun establishReference(timestampNs: Long, span: Double) {
        pendingSpans.addLast(PendingSpan(timestampNs, span))
        // Keep the SMALLEST window that still spans the required settling time: drop the oldest
        // sample only while what remains would still cover it. At 30 fps that leaves exactly
        // `minimumReferenceSamples` frames, which is what the count rule kept.
        while (pendingSpans.size > 2 &&
            timestampNs - pendingSpans.elementAt(1).timestampNs >= minimumReferenceSpanNs
        ) pendingSpans.removeFirst()
        if (pendingSpans.size < 2) return
        if (timestampNs - pendingSpans.first().timestampNs < minimumReferenceSpanNs) return
        val spans = pendingSpans.map { it.span }
        val mean = spans.sum() / spans.size
        if (mean <= 0.0) return
        val spread = (spans.max() - spans.min()) / mean
        if (spread <= config.maximumReferenceSpanSpreadRatio) {
            reference = HspuReference(mean)
            pendingSpans.clear()
        }
    }

    private fun invalidate() {
        timing.invalidate()
        pendingSpans.clear()
    }

    private fun result(
        timestamp: Long,
        hipAbove: Double?,
        span: Double?,
        ratio: Double?,
        reason: HspuReason,
        counted: Boolean,
        eventNs: Long? = null,
    ) = HspuFrameResult(timestamp, hipAbove, span, ratio, phase, count, reason, counted, eventNs)
}

internal fun ElbowCyclePhase.toHspuPhase(): HspuPhase = HspuPhase.valueOf(name)
