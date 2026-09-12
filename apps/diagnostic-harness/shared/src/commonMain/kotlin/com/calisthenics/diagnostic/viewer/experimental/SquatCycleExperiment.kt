package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.acos
import kotlin.math.sqrt

/** Explicit prototype parameters. They are not validated anatomical or form thresholds. */
data class SquatConfig(
    val side: ArmSide,
    val extendedDegrees: Double,
    val flexedDegrees: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
    val oppositeFlexedDegrees: Double = flexedDegrees,
    val oppositeMinimumExcursionDegrees: Double? = null,
) {
    init {
        require(extendedDegrees.isFinite() && extendedDegrees in 0.0..180.0) {
            "extended_degrees must be finite and in [0, 180]"
        }
        require(flexedDegrees.isFinite() && flexedDegrees in 0.0..180.0) {
            "flexed_degrees must be finite and in [0, 180]"
        }
        require(flexedDegrees < extendedDegrees) {
            "flexed_degrees must be less than extended_degrees"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEndpointSamples > 0) { "minimum_endpoint_samples must be positive" }
        require(oppositeFlexedDegrees.isFinite() && oppositeFlexedDegrees in 0.0..180.0) {
            "opposite flexed threshold must be finite and in [0, 180]"
        }
        require(oppositeMinimumExcursionDegrees == null ||
            (oppositeMinimumExcursionDegrees.isFinite() && oppositeMinimumExcursionDegrees > 0 &&
                oppositeMinimumExcursionDegrees <= 180)) { "invalid opposite motion excursion" }
    }
}

/** Offline supervised practice; does not redefine anatomical depth or valid technique. */
fun calibrateSquatPractice(
    frames: List<SquatPoseFrame>,
    base: SquatConfig,
    minimumRangeDegrees: Double,
    endpointFraction: Double,
): SquatConfig {
    require(minimumRangeDegrees.isFinite() && minimumRangeDegrees > 0 && minimumRangeDegrees < 180)
    require(endpointFraction.isFinite() && endpointFraction > 0 && endpointFraction < .5)
    val support = base.minimumEndpointSamples
    require(support >= 2 && frames.size >= 3 * support) { "practice requires sustained endpoints" }
    require(frames.first().timestampNs >= 0)
    require(frames.all { it.width == frames.first().width && it.height == frames.first().height }) {
        "practice dimensions changed"
    }
    require(frames.zipWithNext().all { (a, b) ->
        b.timestampNs > a.timestampNs && b.timestampNs - a.timestampNs <= base.maximumGapNs
    }) { "practice timestamps must be continuous and ordered" }

    fun angles(side: ArmSide): List<Double> = frames.map { frame ->
        val measurement = measureSquatKnee(frame, base.copy(side = side))
        requireNotNull(measurement.kneeDegrees) { "unusable $side practice: ${measurement.reason}" }
    }
    fun thresholds(values: List<Double>): Pair<Double, Double> {
        // A lone spike cannot establish an extreme: every point in the support window
        // must reach it. No interpolation, pooling across gaps, or evaluation input.
        val windows = values.windowed(support)
        val low = windows.minOf { it.max() }
        val high = windows.maxOf { it.min() }
        val range = high - low
        require(range >= minimumRangeDegrees) { "insufficient practice range" }
        val flexed = low + endpointFraction * range
        val extended = high - endpointFraction * range
        require(values.take(support).all { it >= extended } &&
            values.takeLast(support).all { it >= extended }) {
            "practice must start and end extended"
        }
        return flexed to extended
    }
    val oppositeSide = if (base.side == ArmSide.LEFT) ArmSide.RIGHT else ArmSide.LEFT
    val selected = angles(base.side)
    val opposite = angles(oppositeSide)
    val selectedBands = thresholds(selected)
    val oppositeBands = thresholds(opposite)
    require(selected.zip(opposite).windowed(support).any { window ->
        window.all { (a, b) -> a <= selectedBands.first && b <= oppositeBands.first }
    }) { "practice requires simultaneous bilateral bottom evidence" }
    return base.copy(
        flexedDegrees = selectedBands.first,
        extendedDegrees = selectedBands.second,
        oppositeFlexedDegrees = oppositeBands.first,
    )
}

data class SquatPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class SquatPhase { WAITING, STANDING, FLEXING, FLEXED, EXTENDING, UNCERTAIN }

enum class SquatReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    ZERO_LENGTH_LIMB,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
    OPPOSITE_KNEE_UNAVAILABLE,
    UNILATERAL_FLEXION,
    CALIBRATION_PRACTICE,
    OPPOSITE_MOTION_UNCONFIRMED,
}

data class SquatKneeMeasurement(
    val kneeDegrees: Double?,
    val reason: SquatReason,
)

data class SquatFrameResult(
    val timestampNs: Long,
    val kneeDegrees: Double?,
    val phase: SquatPhase,
    val count: Int,
    val reason: SquatReason,
    val counted: Boolean,
)

/** Pure lower-body image-plane measurement in upright source-normalized coordinates. */
fun measureSquatKnee(frame: SquatPoseFrame, config: SquatConfig): SquatKneeMeasurement {
    if (frame.width <= 0 || frame.height <= 0) {
        return SquatKneeMeasurement(null, SquatReason.INVALID_DIMENSIONS)
    }
    if (frame.landmarks.isEmpty()) {
        return SquatKneeMeasurement(null, SquatReason.NO_POSE)
    }

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return SquatKneeMeasurement(null, SquatReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) {
            return SquatKneeMeasurement(null, SquatReason.DUPLICATE_LANDMARK_INDEX)
        }
    }

    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(23, 25, 27)
        ArmSide.RIGHT -> intArrayOf(24, 26, 28)
    }
    val joints = indices.map { index ->
        byIndex[index] ?: return SquatKneeMeasurement(null, SquatReason.MISSING_LANDMARK)
    }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return SquatKneeMeasurement(null, SquatReason.INVALID_LANDMARK)
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
        return SquatKneeMeasurement(null, SquatReason.LOW_CONFIDENCE)
    }

    // Source-normalized x/y are projected into source pixels before measuring. This preserves
    // the image's pixel aspect ratio; the model/display inversion is deliberately not involved.
    val hip = joints[0]
    val knee = joints[1]
    val ankle = joints[2]
    val ax = (hip.x - knee.x) * frame.width.toDouble()
    val ay = (hip.y - knee.y) * frame.height.toDouble()
    val bx = (ankle.x - knee.x) * frame.width.toDouble()
    val by = (ankle.y - knee.y) * frame.height.toDouble()
    val aLength = sqrt(ax * ax + ay * ay)
    val bLength = sqrt(bx * bx + by * by)
    if (aLength == 0.0 || bLength == 0.0) {
        return SquatKneeMeasurement(null, SquatReason.ZERO_LENGTH_LIMB)
    }

    val cosine = ((ax * bx + ay * by) / (aLength * bLength)).coerceIn(-1.0, 1.0)
    return SquatKneeMeasurement(acos(cosine) * 180.0 / kotlin.math.PI, SquatReason.NONE)
}

/**
 * Conservative, explicit-config candidate cycle experiment; it does not label form-valid reps.
 * This exploratory bilateral baseline requires both hip-knee-ankle chains to be visible at the
 * flexed endpoint; it is not a generally validated side-view squat measurement.
 */
class SquatCycleExperiment(private val config: SquatConfig) {
    var phase: SquatPhase = SquatPhase.WAITING
        private set
    var count: Int = 0
        private set

    private var lastTimestampNs: Long? = null
    private var endpointSamples = 0
    private val oppositeStandingSamples = ArrayList<Double>()
    private var oppositeStandingReference: Double? = null

    fun onFrame(frame: SquatPoseFrame): SquatFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            invalidateCandidate()
            return result(timestamp, null, SquatReason.INVALID_TIMESTAMP, counted = false)
        }

        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                invalidateCandidate()
                return result(timestamp, null, SquatReason.DUPLICATE_TIMESTAMP, counted = false)
            }
            if (timestamp < previous) {
                invalidateCandidate()
                return result(timestamp, null, SquatReason.BACKWARDS_TIMESTAMP, counted = false)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val measurement = measureSquatKnee(frame, config)
        val angle = measurement.kneeDegrees
        if (angle == null) {
            invalidateCandidate()
            return result(timestamp, null, measurement.reason, counted = false)
        }
        if (gap) invalidateCandidate()

        val motionMinimum = config.oppositeMinimumExcursionDegrees
        val motionOpposite = if (motionMinimum == null) null else {
            val side = if (config.side == ArmSide.LEFT) ArmSide.RIGHT else ArmSide.LEFT
            measureSquatKnee(frame, config.copy(side = side)).kneeDegrees
        }
        if (motionMinimum != null) {
            if (motionOpposite == null) {
                invalidateCandidate()
                return result(timestamp, angle, SquatReason.OPPOSITE_KNEE_UNAVAILABLE, counted = false)
            }
            if (angle >= config.extendedDegrees) {
                // A new standing run invalidates the previous candidate's reference until
                // every required sample has arrived. Bent-static legs cannot borrow an old high.
                if (oppositeStandingSamples.isEmpty()) oppositeStandingReference = null
                oppositeStandingSamples.add(motionOpposite)
                if (oppositeStandingSamples.size > config.minimumEndpointSamples) oppositeStandingSamples.removeAt(0)
                if (oppositeStandingSamples.size == config.minimumEndpointSamples) {
                    oppositeStandingReference = oppositeStandingSamples.min()
                }
            } else oppositeStandingSamples.clear()
        }

        val reachedFlexedEndpoint = phase == SquatPhase.FLEXING && angle <= config.flexedDegrees
        if (reachedFlexedEndpoint) {
            val oppositeSide = if (config.side == ArmSide.LEFT) ArmSide.RIGHT else ArmSide.LEFT
            val oppositeAngle = motionOpposite ?: measureSquatKnee(frame, config.copy(side = oppositeSide)).kneeDegrees
            if (oppositeAngle == null) {
                invalidateCandidate()
                return result(timestamp, angle, SquatReason.OPPOSITE_KNEE_UNAVAILABLE, counted = false)
            }
            val corroborated = if (motionMinimum == null) oppositeAngle <= config.oppositeFlexedDegrees
                else oppositeStandingReference?.let { it - oppositeAngle >= motionMinimum } ?: false
            if (!corroborated) {
                // Legs can reach the endpoint on adjacent frames. Keep the chosen-knee descent
                // candidate alive, but require a fresh consecutive bilateral bottom run.
                phase = SquatPhase.FLEXING
                endpointSamples = 0
                return result(timestamp, angle, if (motionMinimum == null) SquatReason.UNILATERAL_FLEXION
                    else SquatReason.OPPOSITE_MOTION_UNCONFIRMED, counted = false)
            }
        }

        val counted = advance(angle)
        return result(
            timestamp = timestamp,
            kneeDegrees = angle,
            reason = if (gap) SquatReason.CAPTURE_GAP else SquatReason.NONE,
            counted = counted,
        )
    }

    private fun advance(angle: Double): Boolean {
        val isExtended = angle >= config.extendedDegrees
        val isFlexed = angle <= config.flexedDegrees
        var counted = false

        if (phase == SquatPhase.UNCERTAIN) {
            phase = SquatPhase.WAITING
            endpointSamples = 0
        }

        when (phase) {
            SquatPhase.WAITING,
            SquatPhase.UNCERTAIN,
            -> {
                if (isExtended) {
                    endpointSamples++
                    if (endpointSamples >= config.minimumEndpointSamples) {
                        phase = SquatPhase.STANDING
                    }
                } else endpointSamples = 0
            }
            SquatPhase.STANDING -> {
                endpointSamples = 0
                phase = when {
                    isExtended -> SquatPhase.STANDING
                    isFlexed -> SquatPhase.WAITING // A direct jump is not a candidate cycle.
                    else -> SquatPhase.FLEXING
                }
            }
            SquatPhase.FLEXING -> {
                phase = when {
                    isFlexed -> {
                        endpointSamples++
                        if (endpointSamples >= config.minimumEndpointSamples) {
                            endpointSamples = 0
                            SquatPhase.FLEXED
                        } else SquatPhase.FLEXING
                    }
                    isExtended -> {
                        endpointSamples = 0
                        SquatPhase.STANDING
                    }
                    else -> {
                        endpointSamples = 0
                        SquatPhase.FLEXING
                    }
                }
            }
            SquatPhase.FLEXED -> {
                phase = when {
                    isFlexed -> SquatPhase.FLEXED
                    isExtended -> {
                        endpointSamples = 0
                        SquatPhase.WAITING
                    }
                    else -> {
                        endpointSamples = 0
                        SquatPhase.EXTENDING
                    }
                }
            }
            SquatPhase.EXTENDING -> {
                phase = when {
                    isExtended -> {
                        endpointSamples++
                        if (endpointSamples >= config.minimumEndpointSamples) {
                            count++
                            endpointSamples = 0
                            counted = true
                            SquatPhase.STANDING
                        } else SquatPhase.EXTENDING
                    }
                    isFlexed -> {
                        endpointSamples = 0
                        SquatPhase.FLEXED
                    }
                    else -> {
                        endpointSamples = 0
                        SquatPhase.EXTENDING
                    }
                }
            }
        }
        return counted
    }

    private fun invalidateCandidate() {
        phase = SquatPhase.UNCERTAIN
        endpointSamples = 0
        oppositeStandingSamples.clear()
        oppositeStandingReference = null
    }

    private fun result(
        timestamp: Long,
        kneeDegrees: Double?,
        reason: SquatReason,
        counted: Boolean,
    ) = SquatFrameResult(timestamp, kneeDegrees, phase, count, reason, counted)
}
