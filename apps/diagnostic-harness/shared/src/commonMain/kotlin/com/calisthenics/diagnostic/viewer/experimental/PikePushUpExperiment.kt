package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/** Frozen developmental geometry hypotheses for a selected side-view pike push-up experiment. */
data class PikeConfig(
    val side: ArmSide,
    val extendedElbowDegrees: Double,
    val flexedElbowDegrees: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
    val maximumHipAngleDegrees: Double,
    val minimumKneeAngleDegrees: Double,
    val minimumHipAboveShoulderFraction: Double,
) {
    init {
        require(extendedElbowDegrees.isFinite() && extendedElbowDegrees in 0.0..180.0)
        require(flexedElbowDegrees.isFinite() && flexedElbowDegrees in 0.0..180.0)
        require(flexedElbowDegrees < extendedElbowDegrees)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(maximumGapNs > 0)
        require(minimumEndpointSamples >= 2)
        require(maximumHipAngleDegrees.isFinite() && maximumHipAngleDegrees in 0.0..180.0)
        require(minimumKneeAngleDegrees.isFinite() && minimumKneeAngleDegrees in 0.0..180.0)
        require(minimumHipAboveShoulderFraction.isFinite() && minimumHipAboveShoulderFraction > 0.0)
    }
}

data class PikePoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class PikePhase { WAITING, SUPPORTED, LOWERING, LOWERED, RAISING, UNCERTAIN }

enum class PikeReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    ZERO_LENGTH_LIMB,
    HIP_NOT_ABOVE_SHOULDER,
    HIP_ANGLE_TOO_OPEN,
    KNEE_ANGLE_TOO_FLEXED,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
}

data class PikeGeometryMeasurement(
    val elbowDegrees: Double?,
    val hipAngleDegrees: Double?,
    val kneeAngleDegrees: Double?,
    val hipAboveShoulderFraction: Double?,
    val reason: PikeReason,
)

data class PikeFrameResult(
    val timestampNs: Long,
    val elbowDegrees: Double?,
    val hipAngleDegrees: Double?,
    val kneeAngleDegrees: Double?,
    val hipAboveShoulderFraction: Double?,
    val phase: PikePhase,
    val count: Int,
    val reason: PikeReason,
    val counted: Boolean,
    /**
     * When [counted] is true, the instant the final endpoint was FIRST satisfied. The frame that
     * confirms a cycle is `minimum_endpoint_samples - 1` intervals later, so [timestampNs] is
     * systematically late by that much and must not be used as the event time.
     */
    val eventTimestampNs: Long? = null,
)

fun measurePikeGeometry(frame: PikePoseFrame, config: PikeConfig): PikeGeometryMeasurement {
    fun reject(reason: PikeReason) = PikeGeometryMeasurement(null, null, null, null, reason)
    if (frame.width <= 0 || frame.height <= 0) return reject(PikeReason.INVALID_DIMENSIONS)
    if (frame.landmarks.isEmpty()) return reject(PikeReason.NO_POSE)

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return reject(PikeReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) return reject(PikeReason.DUPLICATE_LANDMARK_INDEX)
    }
    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 13, 15, 23, 25, 27)
        ArmSide.RIGHT -> intArrayOf(12, 14, 16, 24, 26, 28)
    }
    val joints = indices.map { byIndex[it] ?: return reject(PikeReason.MISSING_LANDMARK) }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }) return reject(PikeReason.INVALID_LANDMARK)
    if (joints.any { landmark ->
            val visibility = landmark.visibility
            val presence = landmark.presence
            visibility == null || presence == null || !visibility.isFinite() || !presence.isFinite() ||
                visibility !in 0.0..1.0 || presence !in 0.0..1.0 ||
                visibility < config.minimumConfidence || presence < config.minimumConfidence
        }) return reject(PikeReason.LOW_CONFIDENCE)

    val shoulder = joints[0]
    val elbow = joints[1]
    val wrist = joints[2]
    val hip = joints[3]
    val knee = joints[4]
    val ankle = joints[5]
    val elbowDegrees = angleAt(shoulder, elbow, wrist, frame.width, frame.height)
    val hipAngleDegrees = angleAt(shoulder, hip, knee, frame.width, frame.height)
    val kneeAngleDegrees = angleAt(hip, knee, ankle, frame.width, frame.height)
    if (elbowDegrees == null || hipAngleDegrees == null || kneeAngleDegrees == null) {
        return reject(PikeReason.ZERO_LENGTH_LIMB)
    }
    val hipAboveShoulderFraction = shoulder.y - hip.y

    // Hip placement is the first movement-specific rejection so an upright dip is not confused
    // with a pike merely because its leg angles happen to fall in range.
    if (hipAboveShoulderFraction < config.minimumHipAboveShoulderFraction) {
        return reject(PikeReason.HIP_NOT_ABOVE_SHOULDER)
    }
    if (hipAngleDegrees > config.maximumHipAngleDegrees) return reject(PikeReason.HIP_ANGLE_TOO_OPEN)
    if (kneeAngleDegrees < config.minimumKneeAngleDegrees) return reject(PikeReason.KNEE_ANGLE_TOO_FLEXED)
    return PikeGeometryMeasurement(elbowDegrees, hipAngleDegrees, kneeAngleDegrees, hipAboveShoulderFraction, PikeReason.NONE)
}

private fun angleAt(a: Landmark, b: Landmark, c: Landmark, width: Int, height: Int): Double? {
    val ax = (a.x - b.x) * width
    val ay = (a.y - b.y) * height
    val cx = (c.x - b.x) * width
    val cy = (c.y - b.y) * height
    val aLength = sqrt(ax * ax + ay * ay)
    val cLength = sqrt(cx * cx + cy * cy)
    if (aLength == 0.0 || cLength == 0.0) return null
    val cosine = ((ax * cx) + (ay * cy)) / (aLength * cLength)
    return acos(cosine.coerceIn(-1.0, 1.0)) * 180.0 / PI
}

class PikePushUpExperiment(private val config: PikeConfig) {
    private val timing = ElbowCycleTiming(config.minimumEndpointSamples)
    private var lastTimestampNs: Long? = null

    val phase: PikePhase get() = PikePhase.valueOf(timing.phase.name)
    val count: Int get() = timing.count

    fun onFrame(frame: PikePoseFrame): PikeFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            invalidateCandidate()
            return result(timestamp, null, null, null, null, PikeReason.INVALID_TIMESTAMP, false)
        }
        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                invalidateCandidate()
                return result(timestamp, null, null, null, null, PikeReason.DUPLICATE_TIMESTAMP, false)
            }
            if (timestamp < previous) {
                invalidateCandidate()
                return result(timestamp, null, null, null, null, PikeReason.BACKWARDS_TIMESTAMP, false)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp
        val measurement = measurePikeGeometry(frame, config)
        if (measurement.elbowDegrees == null) {
            invalidateCandidate()
            return result(
                timestamp, measurement.elbowDegrees, measurement.hipAngleDegrees,
                measurement.kneeAngleDegrees, measurement.hipAboveShoulderFraction,
                measurement.reason, false,
            )
        }
        if (gap) invalidateCandidate()
        val eventNs = timing.onObservation(
            timestampNs = timestamp,
            isSupported = measurement.elbowDegrees >= config.extendedElbowDegrees,
            isLowered = measurement.elbowDegrees <= config.flexedElbowDegrees,
        )
        val counted = eventNs != null
        return result(
            timestamp, measurement.elbowDegrees, measurement.hipAngleDegrees,
            measurement.kneeAngleDegrees, measurement.hipAboveShoulderFraction,
            if (gap) PikeReason.CAPTURE_GAP else PikeReason.NONE, counted,
        )
    }

    private fun invalidateCandidate() = timing.invalidate()

    private fun result(
        timestamp: Long,
        elbowDegrees: Double?,
        hipAngleDegrees: Double?,
        kneeAngleDegrees: Double?,
        hipAboveShoulderFraction: Double?,
        reason: PikeReason,
        counted: Boolean,
        eventNs: Long? = null,
    ) = PikeFrameResult(
        timestamp, elbowDegrees, hipAngleDegrees, kneeAngleDegrees, hipAboveShoulderFraction,
        phase, count, reason, counted, eventNs,
    )
}
