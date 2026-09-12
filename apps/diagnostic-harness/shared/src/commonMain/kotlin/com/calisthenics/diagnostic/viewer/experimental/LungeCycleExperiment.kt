package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Explicit prototype parameters for a single-leg-tracked lunge cycle. They are not validated
 * anatomical or form thresholds. Unlike the squat baseline, this does NOT require bilateral
 * confirmation: a lunge is inherently asymmetric (one leg leads and flexes deeply, the other
 * trails), so only the configured side's hip-knee-ankle angle is measured. If the subject
 * alternates which leg leads, only reps where the configured side is the leading/flexed leg are
 * expected to register here — this is a single-leg-tracked candidate cycle, not an
 * alternating-leg-aware counter, and that is an explicit, documented limitation, not a bug.
 */
/**
 * Which admission rule a [LungeCycleExperiment] applies on top of the single-leg cycle machine.
 *
 * [SINGLE_LEG_BASELINE] is the unchanged default and the only behavior any existing configuration
 * gets. [CONTRALATERAL_NOT_EXTENDED_V1] is an offline, opt-in development profile that additionally
 * requires the non-tracked leg to be flexed at the tracked flexed endpoint. It is a targeted
 * rejection of standing single-leg knee lifts. It is NOT lunge identity, NOT a stance, grounding or
 * foot-support signal, and NOT a mobile-readiness claim; the squat-shaped confusion is deliberately
 * retained and characterized in tests.
 */
enum class LungeAdmissionProfile { SINGLE_LEG_BASELINE, CONTRALATERAL_NOT_EXTENDED_V1 }

data class LungeConfig(
    val side: ArmSide,
    val extendedDegrees: Double,
    val flexedDegrees: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
    val admissionProfile: LungeAdmissionProfile = LungeAdmissionProfile.SINGLE_LEG_BASELINE,
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
    }
}

data class LungePoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class LungePhase { WAITING, STANDING, FLEXING, FLEXED, EXTENDING, UNCERTAIN }

enum class LungeReason {
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

    /** Opt-in profile only: the non-tracked leg could not be measured on this frame. */
    CONTRALATERAL_UNAVAILABLE,

    /** Opt-in profile only: the non-tracked leg was not flexed at the tracked flexed endpoint. */
    CONTRALATERAL_EXTENDED,
}

data class LungeKneeMeasurement(
    val kneeDegrees: Double?,
    val reason: LungeReason,
)

data class LungeFrameResult(
    val timestampNs: Long,
    val kneeDegrees: Double?,
    val phase: LungePhase,
    val count: Int,
    val reason: LungeReason,
    val counted: Boolean,
    /** Non-tracked knee angle; always null under [LungeAdmissionProfile.SINGLE_LEG_BASELINE]. */
    val contralateralKneeDegrees: Double? = null,
)

/** Pure single-leg image-plane measurement in upright source-normalized coordinates. */
fun measureLungeKnee(frame: LungePoseFrame, config: LungeConfig): LungeKneeMeasurement =
    measureKneeChain(frame, config.side, config.minimumConfidence)

/**
 * The non-tracked leg, measured from its own real landmark indices under exactly the same
 * validation, confidence and aspect-ratio rules. There is no mirroring, no synthesized point and
 * no fallback to the selected leg: if the other leg is not independently measurable the caller
 * must treat the frame as unavailable.
 */
private fun measureContralateralKnee(frame: LungePoseFrame, config: LungeConfig): LungeKneeMeasurement =
    measureKneeChain(frame, oppositeSide(config.side), config.minimumConfidence)

private fun oppositeSide(side: ArmSide): ArmSide =
    if (side == ArmSide.LEFT) ArmSide.RIGHT else ArmSide.LEFT

// Each chain is measured independently from the frame, so the two measurements share no state and
// neither can mutate the input. Rebuilding the index per chain costs one extra pass on an offline
// replay frame and keeps both legs on identical rules.
private fun measureKneeChain(
    frame: LungePoseFrame,
    side: ArmSide,
    minimumConfidence: Double,
): LungeKneeMeasurement {
    if (frame.width <= 0 || frame.height <= 0) {
        return LungeKneeMeasurement(null, LungeReason.INVALID_DIMENSIONS)
    }
    if (frame.landmarks.isEmpty()) {
        return LungeKneeMeasurement(null, LungeReason.NO_POSE)
    }

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return LungeKneeMeasurement(null, LungeReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) {
            return LungeKneeMeasurement(null, LungeReason.DUPLICATE_LANDMARK_INDEX)
        }
    }

    val indices = when (side) {
        ArmSide.LEFT -> intArrayOf(23, 25, 27)
        ArmSide.RIGHT -> intArrayOf(24, 26, 28)
    }
    val joints = indices.map { index ->
        byIndex[index] ?: return LungeKneeMeasurement(null, LungeReason.MISSING_LANDMARK)
    }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return LungeKneeMeasurement(null, LungeReason.INVALID_LANDMARK)
    }
    if (joints.any { landmark ->
            val visibility = landmark.visibility
            val presence = landmark.presence
            visibility == null || presence == null ||
                !visibility.isFinite() || !presence.isFinite() ||
                visibility !in 0.0..1.0 || presence !in 0.0..1.0 ||
                visibility < minimumConfidence || presence < minimumConfidence
        }
    ) {
        return LungeKneeMeasurement(null, LungeReason.LOW_CONFIDENCE)
    }

    // Source-normalized x/y are projected into source pixels before measuring, preserving the
    // image's pixel aspect ratio, matching the squat/plank geometry's convention.
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
        return LungeKneeMeasurement(null, LungeReason.ZERO_LENGTH_LIMB)
    }

    val cosine = ((ax * bx + ay * by) / (aLength * bLength)).coerceIn(-1.0, 1.0)
    return LungeKneeMeasurement(acos(cosine) * 180.0 / kotlin.math.PI, LungeReason.NONE)
}

/**
 * Conservative, explicit-config candidate cycle experiment for a single tracked leg; it does not
 * label form-valid reps and does not require bilateral confirmation the way the squat baseline
 * does (see the class-level limitation on `LungeConfig`). Not a generally validated lunge
 * measurement, and not an alternating-leg-aware counter.
 */
class LungeCycleExperiment(private val config: LungeConfig) {
    var phase: LungePhase = LungePhase.WAITING
        private set
    var count: Int = 0
        private set

    private var lastTimestampNs: Long? = null
    private var endpointSamples = 0

    fun onFrame(frame: LungePoseFrame): LungeFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            invalidateCandidate()
            return result(timestamp, null, LungeReason.INVALID_TIMESTAMP, counted = false)
        }

        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                invalidateCandidate()
                return result(timestamp, null, LungeReason.DUPLICATE_TIMESTAMP, counted = false)
            }
            if (timestamp < previous) {
                invalidateCandidate()
                return result(timestamp, null, LungeReason.BACKWARDS_TIMESTAMP, counted = false)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val measurement = measureLungeKnee(frame, config)
        val angle = measurement.kneeDegrees
        if (angle == null) {
            invalidateCandidate()
            return result(timestamp, null, measurement.reason, counted = false)
        }
        if (gap) invalidateCandidate()

        if (config.admissionProfile == LungeAdmissionProfile.CONTRALATERAL_NOT_EXTENDED_V1) {
            return admitContralateral(timestamp, frame, angle, gap)
        }

        val counted = advance(angle)
        return result(
            timestamp = timestamp,
            kneeDegrees = angle,
            reason = if (gap) LungeReason.CAPTURE_GAP else LungeReason.NONE,
            counted = counted,
        )
    }

    /**
     * Opt-in admission: the non-tracked leg must be independently measurable, and at the tracked
     * leg's flexed endpoint it must be strictly less extended than the existing
     * [LungeConfig.extendedDegrees]. A straight non-tracked leg away from that endpoint — standing,
     * for instance — is expected and never rejects. Rejection reuses the existing candidate
     * invalidation, so a rejected candidate cannot complete later.
     */
    private fun admitContralateral(
        timestamp: Long,
        frame: LungePoseFrame,
        angle: Double,
        gap: Boolean,
    ): LungeFrameResult {
        val contralateral = measureContralateralKnee(frame, config).kneeDegrees
        if (contralateral == null) {
            invalidateCandidate()
            return result(timestamp, angle, LungeReason.CONTRALATERAL_UNAVAILABLE, counted = false)
        }
        if (angle <= config.flexedDegrees && contralateral >= config.extendedDegrees) {
            invalidateCandidate()
            return result(
                timestamp = timestamp,
                kneeDegrees = angle,
                reason = LungeReason.CONTRALATERAL_EXTENDED,
                counted = false,
                contralateralKneeDegrees = contralateral,
            )
        }
        val counted = advance(angle)
        return result(
            timestamp = timestamp,
            kneeDegrees = angle,
            reason = if (gap) LungeReason.CAPTURE_GAP else LungeReason.NONE,
            counted = counted,
            contralateralKneeDegrees = contralateral,
        )
    }

    private fun advance(angle: Double): Boolean {
        val isExtended = angle >= config.extendedDegrees
        val isFlexed = angle <= config.flexedDegrees
        var counted = false

        if (phase == LungePhase.UNCERTAIN) {
            phase = LungePhase.WAITING
            endpointSamples = 0
        }

        when (phase) {
            LungePhase.WAITING,
            LungePhase.UNCERTAIN,
            -> {
                if (isExtended) {
                    endpointSamples++
                    if (endpointSamples >= config.minimumEndpointSamples) {
                        phase = LungePhase.STANDING
                    }
                } else endpointSamples = 0
            }
            LungePhase.STANDING -> {
                endpointSamples = 0
                phase = when {
                    isExtended -> LungePhase.STANDING
                    isFlexed -> LungePhase.WAITING // A direct jump is not a candidate cycle.
                    else -> LungePhase.FLEXING
                }
            }
            LungePhase.FLEXING -> {
                phase = when {
                    isFlexed -> {
                        endpointSamples++
                        if (endpointSamples >= config.minimumEndpointSamples) {
                            endpointSamples = 0
                            LungePhase.FLEXED
                        } else LungePhase.FLEXING
                    }
                    isExtended -> {
                        endpointSamples = 0
                        LungePhase.STANDING
                    }
                    else -> {
                        endpointSamples = 0
                        LungePhase.FLEXING
                    }
                }
            }
            LungePhase.FLEXED -> {
                phase = when {
                    isFlexed -> LungePhase.FLEXED
                    isExtended -> {
                        endpointSamples = 0
                        LungePhase.WAITING
                    }
                    else -> {
                        endpointSamples = 0
                        LungePhase.EXTENDING
                    }
                }
            }
            LungePhase.EXTENDING -> {
                phase = when {
                    isExtended -> {
                        endpointSamples++
                        if (endpointSamples >= config.minimumEndpointSamples) {
                            count++
                            endpointSamples = 0
                            counted = true
                            LungePhase.STANDING
                        } else LungePhase.EXTENDING
                    }
                    isFlexed -> {
                        endpointSamples = 0
                        LungePhase.FLEXED
                    }
                    else -> {
                        endpointSamples = 0
                        LungePhase.EXTENDING
                    }
                }
            }
        }
        return counted
    }

    private fun invalidateCandidate() {
        phase = LungePhase.UNCERTAIN
        endpointSamples = 0
    }

    private fun result(
        timestamp: Long,
        kneeDegrees: Double?,
        reason: LungeReason,
        counted: Boolean,
        contralateralKneeDegrees: Double? = null,
    ) = LungeFrameResult(timestamp, kneeDegrees, phase, count, reason, counted, contralateralKneeDegrees)
}
