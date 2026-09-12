package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Explicit prototype parameters for a synchronized-arm/leg jumping-jack candidate cycle. Not
 * validated anatomical or form thresholds. Unlike squat/lunge's single knee angle, this movement
 * needs two independent signals -- arm elevation and leg spread -- to be true TOGETHER for a
 * candidate "open" position, so an arm-only raise or a leg-only step is not, by itself, sufficient
 * (see `measureJumpingJack`'s averaged/ratio design for why each signal alone requires both limbs).
 */
data class JumpingJackConfig(
    val armsUpDegrees: Double,
    val armsDownDegrees: Double,
    val legsSpreadRatio: Double,
    val legsTogetherRatio: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
) {
    init {
        require(armsUpDegrees.isFinite() && armsUpDegrees in 0.0..180.0) {
            "arms_up_degrees must be finite and in [0, 180]"
        }
        require(armsDownDegrees.isFinite() && armsDownDegrees in 0.0..180.0) {
            "arms_down_degrees must be finite and in [0, 180]"
        }
        require(armsDownDegrees < armsUpDegrees) {
            "arms_down_degrees must be less than arms_up_degrees"
        }
        require(legsSpreadRatio.isFinite() && legsSpreadRatio > 0.0) {
            "legs_spread_ratio must be finite and positive"
        }
        require(legsTogetherRatio.isFinite() && legsTogetherRatio > 0.0) {
            "legs_together_ratio must be finite and positive"
        }
        require(legsTogetherRatio < legsSpreadRatio) {
            "legs_together_ratio must be less than legs_spread_ratio"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEndpointSamples > 0) { "minimum_endpoint_samples must be positive" }
    }
}

data class JumpingJackPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class JumpingJackPhase { WAITING, TOGETHER, OPENING, OPEN, CLOSING, UNCERTAIN }

enum class JumpingJackReason {
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
    CALIBRATION_PRACTICE,
}

data class JumpingJackMeasurement(
    val armDegrees: Double?,
    val legSpreadRatio: Double?,
    val reason: JumpingJackReason,
)

data class JumpingJackFrameResult(
    val timestampNs: Long,
    val armDegrees: Double?,
    val legSpreadRatio: Double?,
    val phase: JumpingJackPhase,
    val count: Int,
    val reason: JumpingJackReason,
    val counted: Boolean,
)

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_WRIST = 15
private const val RIGHT_WRIST = 16
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28
private val REQUIRED_INDICES =
    intArrayOf(LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP, LEFT_ANKLE, RIGHT_ANKLE)

private fun pixels(landmark: Landmark, width: Double, height: Double) =
    Pair(landmark.x * width, landmark.y * height)

private fun angleAt(vertex: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double? {
    val ax = a.first - vertex.first
    val ay = a.second - vertex.second
    val bx = b.first - vertex.first
    val by = b.second - vertex.second
    val aLength = sqrt(ax * ax + ay * ay)
    val bLength = sqrt(bx * bx + by * by)
    if (aLength == 0.0 || bLength == 0.0) return null
    val cosine = ((ax * bx + ay * by) / (aLength * bLength)).coerceIn(-1.0, 1.0)
    return acos(cosine) * 180.0 / kotlin.math.PI
}

private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val dx = a.first - b.first
    val dy = a.second - b.second
    return sqrt(dx * dx + dy * dy)
}

/**
 * Pure bilateral image-plane measurement in upright source-normalized coordinates. Arm elevation
 * is the AVERAGE of each shoulder's hip-shoulder-wrist angle: averaging (rather than requiring one
 * arbitrary side) means a single-arm raise pulls the average only to roughly the midpoint between
 * "down" and "up", so it cannot alone cross either extreme threshold -- both arms must move for the
 * average to register as up or down. Leg spread is the ankle-to-ankle distance normalized by hip
 * width (scale-invariant); unlike the arm measure, a single leg stepping out CAN raise this ratio
 * on its own -- this is a known, disclosed limitation, not solved here (see the test suite's
 * `singleLegStepDoesNotHaveTheSameBilateralProtectionAsArms` case).
 */
fun measureJumpingJack(frame: JumpingJackPoseFrame, config: JumpingJackConfig): JumpingJackMeasurement {
    if (frame.width <= 0 || frame.height <= 0) {
        return JumpingJackMeasurement(null, null, JumpingJackReason.INVALID_DIMENSIONS)
    }
    if (frame.landmarks.isEmpty()) {
        return JumpingJackMeasurement(null, null, JumpingJackReason.NO_POSE)
    }

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return JumpingJackMeasurement(null, null, JumpingJackReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) {
            return JumpingJackMeasurement(null, null, JumpingJackReason.DUPLICATE_LANDMARK_INDEX)
        }
    }
    val joints = REQUIRED_INDICES.map { index ->
        byIndex[index] ?: return JumpingJackMeasurement(null, null, JumpingJackReason.MISSING_LANDMARK)
    }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return JumpingJackMeasurement(null, null, JumpingJackReason.INVALID_LANDMARK)
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
        return JumpingJackMeasurement(null, null, JumpingJackReason.LOW_CONFIDENCE)
    }

    val width = frame.width.toDouble()
    val height = frame.height.toDouble()
    val leftShoulder = pixels(byIndex.getValue(LEFT_SHOULDER), width, height)
    val rightShoulder = pixels(byIndex.getValue(RIGHT_SHOULDER), width, height)
    val leftWrist = pixels(byIndex.getValue(LEFT_WRIST), width, height)
    val rightWrist = pixels(byIndex.getValue(RIGHT_WRIST), width, height)
    val leftHip = pixels(byIndex.getValue(LEFT_HIP), width, height)
    val rightHip = pixels(byIndex.getValue(RIGHT_HIP), width, height)
    val leftAnkle = pixels(byIndex.getValue(LEFT_ANKLE), width, height)
    val rightAnkle = pixels(byIndex.getValue(RIGHT_ANKLE), width, height)

    val leftArmDegrees = angleAt(leftShoulder, leftHip, leftWrist)
        ?: return JumpingJackMeasurement(null, null, JumpingJackReason.ZERO_LENGTH_LIMB)
    val rightArmDegrees = angleAt(rightShoulder, rightHip, rightWrist)
        ?: return JumpingJackMeasurement(null, null, JumpingJackReason.ZERO_LENGTH_LIMB)
    val hipWidth = distance(leftHip, rightHip)
    if (hipWidth == 0.0) return JumpingJackMeasurement(null, null, JumpingJackReason.ZERO_LENGTH_LIMB)
    val ankleDistance = distance(leftAnkle, rightAnkle)

    val armDegrees = (leftArmDegrees + rightArmDegrees) / 2.0
    val legSpreadRatio = ankleDistance / hipWidth
    return JumpingJackMeasurement(armDegrees, legSpreadRatio, JumpingJackReason.NONE)
}

/**
 * Offline supervised practice; does not redefine anatomical thresholds or valid technique. Learns
 * only a closed-band `armsDownDegrees` from a bilateral, sustained practice clip -- every other
 * config field (including `armsUpDegrees`) is unchanged, so opening detection is never affected.
 */
fun calibrateJumpingJackPractice(
    frames: List<JumpingJackPoseFrame>,
    base: JumpingJackConfig,
    minimumRangeDegrees: Double,
    endpointFraction: Double,
): JumpingJackConfig {
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

    val measurements = frames.map { frame ->
        val measurement = measureJumpingJack(frame, base)
        val arm = requireNotNull(measurement.armDegrees) { "unusable practice frame: ${measurement.reason}" }
        val leg = requireNotNull(measurement.legSpreadRatio) { "unusable practice frame: ${measurement.reason}" }
        arm to leg
    }
    val arms = measurements.map { it.first }
    val legs = measurements.map { it.second }

    // A lone spike cannot establish an extreme: every point in the support window
    // must reach it. No interpolation, pooling across gaps, or evaluation input.
    val windows = arms.windowed(support)
    val low = windows.minOf { it.max() }
    val high = windows.maxOf { it.min() }
    val range = high - low
    require(range >= minimumRangeDegrees) { "insufficient practice range" }
    val armsDown = low + endpointFraction * range
    require(armsDown < base.armsUpDegrees) { "learned arms_down_degrees must be less than arms_up_degrees" }

    require(
        arms.take(support).all { it <= armsDown } && legs.take(support).all { it <= base.legsTogetherRatio } &&
            arms.takeLast(support).all { it <= armsDown } && legs.takeLast(support).all { it <= base.legsTogetherRatio },
    ) { "practice must start and end with a closed, bilateral endpoint" }
    require(
        arms.zip(legs).windowed(support).any { window ->
            window.all { (arm, leg) -> arm >= base.armsUpDegrees && leg >= base.legsSpreadRatio }
        },
    ) { "practice requires a sustained joint open endpoint at the base thresholds" }

    return base.copy(armsDownDegrees = armsDown)
}

/** Offline-only bounded search parameters for the first strict-practice prefix that calibrates. */
data class JumpingJackPracticeSearch(
    val startNs: Long,
    val deadlineNs: Long,
    val minimumRangeDegrees: Double,
    val endpointFraction: Double,
) {
    init {
        require(startNs >= 0) { "practice search start_ns must be nonnegative" }
        require(deadlineNs > startNs) { "practice search deadline_ns must be after start_ns" }
        require(minimumRangeDegrees.isFinite() && minimumRangeDegrees > 0.0 && minimumRangeDegrees < 180.0) {
            "practice search minimum_range_degrees must be finite and in (0, 180)"
        }
        require(endpointFraction.isFinite() && endpointFraction > 0.0 && endpointFraction < .5) {
            "practice search endpoint_fraction must be finite and in (0, .5)"
        }
    }
}

data class JumpingJackPracticeSearchResult(
    val completionIndex: Int,
    val effectiveConfig: JumpingJackConfig,
)

/**
 * Returns the first prefix accepted by [calibrateJumpingJackPractice], never inspecting later
 * observations after success. The deliberate O(n squared) prefix reuse is bounded to offline
 * practice windows; it makes no live-performance claim.
 */
fun findFirstJumpingJackPracticeCalibration(
    frames: List<JumpingJackPoseFrame>,
    base: JumpingJackConfig,
    search: JumpingJackPracticeSearch,
): JumpingJackPracticeSearchResult? {
    require(base.minimumEndpointSamples >= 2) { "practice search requires at least two endpoint samples" }
    require(frames.isNotEmpty()) { "practice search requires observations" }

    val support = base.minimumEndpointSamples
    val prefix = ArrayList<JumpingJackPoseFrame>()
    var previous: JumpingJackPoseFrame? = null
    var dimensions: Pair<Int, Int>? = null
    for ((index, frame) in frames.withIndex()) {
        if (frame.timestampNs > search.deadlineNs) return null
        require(frame.timestampNs >= search.startNs) { "practice search frame precedes start" }
        require(frame.width > 0 && frame.height > 0) { "practice search frame dimensions must be positive" }
        val expectedDimensions = dimensions
        if (expectedDimensions == null) {
            dimensions = frame.width to frame.height
        } else {
            require(frame.width == expectedDimensions.first && frame.height == expectedDimensions.second) {
                "practice search dimensions changed"
            }
        }
        previous?.let { prior ->
            require(frame.timestampNs > prior.timestampNs) { "practice search timestamps must increase" }
            require(frame.timestampNs - prior.timestampNs <= base.maximumGapNs) {
                "practice search timestamps must remain continuous"
            }
        }
        val measurement = measureJumpingJack(frame, base)
        require(measurement.armDegrees != null && measurement.legSpreadRatio != null) {
            "practice search frame is unusable: ${measurement.reason}"
        }
        prefix += frame
        previous = frame

        if (prefix.size < 3 * support) continue
        try {
            val effective = calibrateJumpingJackPractice(
                prefix,
                base,
                search.minimumRangeDegrees,
                search.endpointFraction,
            )
            return JumpingJackPracticeSearchResult(index, effective)
        } catch (_: IllegalArgumentException) {
            // Preconditions above leave strict range/order/endpoint rejection as inadmissibility.
        }
    }
    return null
}

/**
 * Conservative, explicit-config candidate cycle experiment requiring synchronized arm-elevation and
 * leg-spread/return; it does not label form-valid reps and is not a general jumping-jack detector.
 */
class JumpingJackExperiment(private val config: JumpingJackConfig) {
    var phase: JumpingJackPhase = JumpingJackPhase.WAITING
        private set
    var count: Int = 0
        private set

    private var lastTimestampNs: Long? = null
    private var endpointSamples = 0

    fun onFrame(frame: JumpingJackPoseFrame): JumpingJackFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            invalidateCandidate()
            return result(timestamp, null, null, JumpingJackReason.INVALID_TIMESTAMP, counted = false)
        }

        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                invalidateCandidate()
                return result(timestamp, null, null, JumpingJackReason.DUPLICATE_TIMESTAMP, counted = false)
            }
            if (timestamp < previous) {
                invalidateCandidate()
                return result(timestamp, null, null, JumpingJackReason.BACKWARDS_TIMESTAMP, counted = false)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val measurement = measureJumpingJack(frame, config)
        val armDegrees = measurement.armDegrees
        val legSpreadRatio = measurement.legSpreadRatio
        if (armDegrees == null || legSpreadRatio == null) {
            invalidateCandidate()
            return result(timestamp, null, null, measurement.reason, counted = false)
        }
        if (gap) invalidateCandidate()

        val counted = advance(armDegrees, legSpreadRatio)
        return result(
            timestamp = timestamp,
            armDegrees = armDegrees,
            legSpreadRatio = legSpreadRatio,
            reason = if (gap) JumpingJackReason.CAPTURE_GAP else JumpingJackReason.NONE,
            counted = counted,
        )
    }

    private fun advance(armDegrees: Double, legSpreadRatio: Double): Boolean {
        val isTogether = armDegrees <= config.armsDownDegrees && legSpreadRatio <= config.legsTogetherRatio
        val isOpen = armDegrees >= config.armsUpDegrees && legSpreadRatio >= config.legsSpreadRatio
        var counted = false

        if (phase == JumpingJackPhase.UNCERTAIN) {
            phase = JumpingJackPhase.WAITING
            endpointSamples = 0
        }

        when (phase) {
            JumpingJackPhase.WAITING,
            JumpingJackPhase.UNCERTAIN,
            -> {
                if (isTogether) {
                    endpointSamples++
                    if (endpointSamples >= config.minimumEndpointSamples) {
                        phase = JumpingJackPhase.TOGETHER
                    }
                } else endpointSamples = 0
            }
            JumpingJackPhase.TOGETHER -> {
                endpointSamples = 0
                phase = when {
                    isTogether -> JumpingJackPhase.TOGETHER
                    isOpen -> JumpingJackPhase.WAITING // A direct jump is not a candidate cycle.
                    else -> JumpingJackPhase.OPENING
                }
            }
            JumpingJackPhase.OPENING -> {
                phase = when {
                    isOpen -> {
                        endpointSamples++
                        if (endpointSamples >= config.minimumEndpointSamples) {
                            endpointSamples = 0
                            JumpingJackPhase.OPEN
                        } else JumpingJackPhase.OPENING
                    }
                    isTogether -> {
                        endpointSamples = 0
                        JumpingJackPhase.TOGETHER
                    }
                    else -> {
                        endpointSamples = 0
                        JumpingJackPhase.OPENING
                    }
                }
            }
            JumpingJackPhase.OPEN -> {
                phase = when {
                    isOpen -> JumpingJackPhase.OPEN
                    isTogether -> {
                        endpointSamples = 0
                        JumpingJackPhase.WAITING
                    }
                    else -> {
                        endpointSamples = 0
                        JumpingJackPhase.CLOSING
                    }
                }
            }
            JumpingJackPhase.CLOSING -> {
                phase = when {
                    isTogether -> {
                        endpointSamples++
                        if (endpointSamples >= config.minimumEndpointSamples) {
                            count++
                            endpointSamples = 0
                            counted = true
                            JumpingJackPhase.TOGETHER
                        } else JumpingJackPhase.CLOSING
                    }
                    isOpen -> {
                        endpointSamples = 0
                        JumpingJackPhase.OPEN
                    }
                    else -> {
                        endpointSamples = 0
                        JumpingJackPhase.CLOSING
                    }
                }
            }
        }
        return counted
    }

    private fun invalidateCandidate() {
        phase = JumpingJackPhase.UNCERTAIN
        endpointSamples = 0
    }

    private fun result(
        timestamp: Long,
        armDegrees: Double?,
        legSpreadRatio: Double?,
        reason: JumpingJackReason,
        counted: Boolean,
    ) = JumpingJackFrameResult(timestamp, armDegrees, legSpreadRatio, phase, count, reason, counted)
}
