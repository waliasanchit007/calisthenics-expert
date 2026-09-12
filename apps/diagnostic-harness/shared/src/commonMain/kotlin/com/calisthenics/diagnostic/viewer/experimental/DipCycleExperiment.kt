package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Explicit prototype parameters for a single-side parallel-bar dip candidate cycle. They are an
 * initial geometric hypothesis frozen from raw footage inspection, not validated anatomical, form
 * or muscle-activation thresholds.
 *
 * Elbow flexion alone cannot separate a dip from an overhead pull-up or a horizontal push-up, so
 * it is never trusted on its own. Two admission gates are required on every frame:
 *  - [minimumShoulderAboveWristFraction]: the shoulder must sit above the wrist by at least this
 *    normalized-height fraction (arms-down support). A pull-up fails this because its wrist is
 *    above the shoulder.
 *  - [maximumTorsoTiltDegrees]: the shoulder-to-hip axis must stay within this many degrees of
 *    image-vertical. A horizontal push-up fails this.
 * Only the configured side is measured; the far side is never required or fabricated, and there
 * is no practice calibration or side switching.
 */
data class DipConfig(
    val side: ArmSide,
    val extendedElbowDegrees: Double,
    val flexedElbowDegrees: Double,
    val minimumShoulderAboveWristFraction: Double,
    val maximumTorsoTiltDegrees: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
) {
    init {
        require(extendedElbowDegrees.isFinite() && extendedElbowDegrees in 0.0..180.0) {
            "extended_elbow_degrees must be finite and in [0, 180]"
        }
        require(flexedElbowDegrees.isFinite() && flexedElbowDegrees in 0.0..180.0) {
            "flexed_elbow_degrees must be finite and in [0, 180]"
        }
        require(flexedElbowDegrees < extendedElbowDegrees) {
            "flexed_elbow_degrees must be less than extended_elbow_degrees"
        }
        require(minimumShoulderAboveWristFraction.isFinite() && minimumShoulderAboveWristFraction > 0.0) {
            "minimum_shoulder_above_wrist_fraction must be finite and positive"
        }
        require(maximumTorsoTiltDegrees.isFinite() && maximumTorsoTiltDegrees > 0.0 && maximumTorsoTiltDegrees < 90.0) {
            "maximum_torso_tilt_degrees must be finite and in (0, 90)"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEndpointSamples >= 2) { "minimum_endpoint_samples must be at least 2" }
    }
}

data class DipPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class DipPhase { WAITING, SUPPORTED, LOWERING, LOWERED, RAISING, UNCERTAIN }

enum class DipReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    ZERO_LENGTH_LIMB,
    SHOULDER_NOT_ABOVE_WRIST,
    TORSO_NOT_UPRIGHT,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
}

data class DipSupportMeasurement(
    val elbowDegrees: Double?,
    val shoulderAboveWristFraction: Double?,
    val torsoTiltDegrees: Double?,
    val reason: DipReason,
)

data class DipFrameResult(
    val timestampNs: Long,
    val elbowDegrees: Double?,
    val shoulderAboveWristFraction: Double?,
    val torsoTiltDegrees: Double?,
    val phase: DipPhase,
    val count: Int,
    val reason: DipReason,
    val counted: Boolean,
    /**
     * When [counted] is true, the instant the final endpoint was FIRST satisfied. The frame that
     * confirms a cycle is `minimum_endpoint_samples - 1` intervals later, so [timestampNs] is
     * systematically late by that much and must not be used as the event time.
     */
    val eventTimestampNs: Long? = null,
)

/**
 * Pure image-plane measurement of a candidate parallel-bar dip support, including the two
 * admission gates. Neither gate establishes correct technique or load; they only distinguish this
 * candidate from its nearest visual neighbours under this measurement.
 */
fun measureDipSupport(frame: DipPoseFrame, config: DipConfig): DipSupportMeasurement {
    fun reject(reason: DipReason) = DipSupportMeasurement(null, null, null, reason)

    if (frame.width <= 0 || frame.height <= 0) return reject(DipReason.INVALID_DIMENSIONS)
    if (frame.landmarks.isEmpty()) return reject(DipReason.NO_POSE)

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return reject(DipReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) return reject(DipReason.DUPLICATE_LANDMARK_INDEX)
    }

    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 13, 15, 23)
        ArmSide.RIGHT -> intArrayOf(12, 14, 16, 24)
    }
    val joints = indices.map { byIndex[it] ?: return reject(DipReason.MISSING_LANDMARK) }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return reject(DipReason.INVALID_LANDMARK)
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
        return reject(DipReason.LOW_CONFIDENCE)
    }

    val shoulder = joints[0]
    val elbow = joints[1]
    val wrist = joints[2]
    val hip = joints[3]
    val width = frame.width.toDouble()
    val height = frame.height.toDouble()

    // Source-normalized x/y are projected into source pixels before measuring the elbow angle,
    // preserving the image's pixel aspect ratio.
    val ax = (shoulder.x - elbow.x) * width
    val ay = (shoulder.y - elbow.y) * height
    val bx = (wrist.x - elbow.x) * width
    val by = (wrist.y - elbow.y) * height
    val aLength = sqrt(ax * ax + ay * ay)
    val bLength = sqrt(bx * bx + by * by)
    if (aLength == 0.0 || bLength == 0.0) return reject(DipReason.ZERO_LENGTH_LIMB)
    val elbowDegrees = atan2(abs(ax * by - ay * bx), ax * bx + ay * by) * 180.0 / PI

    // Both endpoints are already normalized to the same [0, 1] image-height axis, so their
    // difference is a height-normalized fraction directly; no pixel conversion is needed.
    val shoulderAboveWristFraction = wrist.y - shoulder.y

    // The torso axis angle needs the pixel aspect ratio, unlike the fraction above.
    val hipDx = (hip.x - shoulder.x) * width
    val hipDy = (hip.y - shoulder.y) * height
    val torsoTiltDegrees = atan2(abs(hipDx), hipDy) * 180.0 / PI

    if (shoulderAboveWristFraction < config.minimumShoulderAboveWristFraction) {
        return reject(DipReason.SHOULDER_NOT_ABOVE_WRIST)
    }
    if (torsoTiltDegrees > config.maximumTorsoTiltDegrees) {
        return reject(DipReason.TORSO_NOT_UPRIGHT)
    }

    return DipSupportMeasurement(elbowDegrees, shoulderAboveWristFraction, torsoTiltDegrees, DipReason.NONE)
}

/**
 * Conservative, explicit-config candidate cycle experiment for a selected-mode parallel-bar dip.
 * It does not label form-valid reps, judge depth, or claim anything about load or muscle
 * activation; it only tracks a supported-lowered-supported excursion of the configured side.
 */
class DipCycleExperiment(private val config: DipConfig) {
    val phase: DipPhase get() = timing.phase.toDipPhase()
    val count: Int get() = timing.count

    private var lastTimestampNs: Long? = null
    private val timing = ElbowCycleTiming(config.minimumEndpointSamples)

    fun onFrame(frame: DipPoseFrame): DipFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            invalidateCandidate()
            return result(timestamp, null, null, null, DipReason.INVALID_TIMESTAMP, counted = false)
        }

        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                invalidateCandidate()
                return result(timestamp, null, null, null, DipReason.DUPLICATE_TIMESTAMP, counted = false)
            }
            if (timestamp < previous) {
                invalidateCandidate()
                return result(timestamp, null, null, null, DipReason.BACKWARDS_TIMESTAMP, counted = false)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val measurement = measureDipSupport(frame, config)
        val angle = measurement.elbowDegrees
        if (angle == null) {
            invalidateCandidate()
            return result(
                timestamp, null, measurement.shoulderAboveWristFraction, measurement.torsoTiltDegrees,
                measurement.reason, counted = false,
            )
        }
        if (gap) invalidateCandidate()

        val eventNs = advance(timestamp, angle)
        val counted = eventNs != null
        return result(
            timestamp = timestamp,
            elbowDegrees = angle,
            shoulderAboveWristFraction = measurement.shoulderAboveWristFraction,
            torsoTiltDegrees = measurement.torsoTiltDegrees,
            reason = if (gap) DipReason.CAPTURE_GAP else DipReason.NONE,
            counted = counted,
            eventNs = eventNs,
        )
    }

    private fun advance(timestamp: Long, angle: Double): Long? {
        return timing.onObservation(
            timestampNs = timestamp,
            isSupported = angle >= config.extendedElbowDegrees,
            isLowered = angle <= config.flexedElbowDegrees,
        )
    }

    private fun invalidateCandidate() {
        timing.invalidate()
    }

    private fun result(
        timestamp: Long,
        elbowDegrees: Double?,
        shoulderAboveWristFraction: Double?,
        torsoTiltDegrees: Double?,
        reason: DipReason,
        counted: Boolean,
        eventNs: Long? = null,
    ) = DipFrameResult(timestamp, elbowDegrees, shoulderAboveWristFraction, torsoTiltDegrees, phase, count, reason, counted, eventNs)
}
