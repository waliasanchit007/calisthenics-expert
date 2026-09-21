package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark

/**
 * Support condition of a selected-mode handstand hold. Recorded, never inferred: a wall-supported
 * hold and a freestanding hold are different observations. Wall contact is NOT measured by this
 * experiment — a side-on camera cannot resolve it — so the operator's statement is the only source.
 */
enum class HandstandSupport { WALL_SUPPORTED, FREESTANDING, UNKNOWN }

/**
 * Explicit prototype parameters for a handstand hold interval, frozen before the first real-video
 * run. Image-plane geometry only; nothing here judges line quality, balance or technique.
 *
 * A frame is HOLDING only when the body is stacked: hip above shoulder AND both ankles above the
 * hip. Hip-above-shoulder alone was tried first on real footage and over-extended the hold at both
 * ends (+1.3 s at entry, +1.8 s at exit), because the hips are already above the shoulders during a
 * split-leg kick-up and dismount. Requiring BOTH ankles also tolerates MediaPipe left/right leg
 * identity swaps, which are known to occur on inverted bodies.
 *
 * A hold is an interval, never a repetition. Any break ends it with a stated reason; separate
 * fragments are never stitched into one longer hold.
 */
data class HandstandHoldConfig(
    val support: HandstandSupport,
    val side: ArmSide,
    val minimumHipAboveShoulderFraction: Double,
    val minimumAnkleAboveHipFraction: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumHoldNs: Long,
    /**
     * Consecutive non-holding frames required before an open hold is closed.
     *
     * With 1 (the original behaviour) a single frame ends an interval. Measured on
     * btUWnzTI8yk, a steady wall-supported hold was split at 51.67/51.77 s by a 1-2 frame
     * LEGS_NOT_STACKED blip, while the breaks that matter there are 32-222 frames long. Requiring
     * persistence mirrors `minimumEndpointSamples` and `StaticSkillConfig.minimumMovingSamplesToClose`.
     * It changes no threshold.
     *
     * It absorbs ONLY the geometric non-holding reasons, measured from an otherwise valid frame.
     * A pose failure, a capture gap or a timestamp fault still breaks the hold immediately: an
     * unmeasurable frame is not evidence that the hold continued.
     */
    val minimumNonHoldingSamplesToClose: Int,
) {
    init {
        require(minimumHipAboveShoulderFraction.isFinite() && minimumHipAboveShoulderFraction > 0.0) {
            "minimum_hip_above_shoulder_fraction must be finite and positive"
        }
        require(minimumAnkleAboveHipFraction.isFinite() && minimumAnkleAboveHipFraction > 0.0) {
            "minimum_ankle_above_hip_fraction must be finite and positive"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumHoldNs > 0) { "minimum_hold_ns must be positive" }
        require(minimumNonHoldingSamplesToClose >= 1) { "minimum_non_holding_samples_to_close must be at least 1" }
    }
}

data class HandstandPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class HandstandReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    NOT_INVERTED,
    LEGS_NOT_STACKED,
    HANDS_NOT_BELOW_SHOULDERS,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
    END_OF_INPUT,
}

/**
 * One continuous holding interval. [leftCensored] means holding was already true on the first
 * frame of input, so the real entry is unobserved and [entryTimestampNs] is only the start of
 * observation. [rightCensored] means holding was still true at the end of input.
 */
data class HandstandHoldInterval(
    val entryTimestampNs: Long,
    val exitTimestampNs: Long,
    val durationNs: Long,
    val endReason: HandstandReason,
    val leftCensored: Boolean,
    val rightCensored: Boolean,
)

data class HandstandFrameResult(
    val timestampNs: Long,
    val holding: Boolean,
    val reason: HandstandReason,
    val hipAboveShoulderFraction: Double?,
    val ankleAboveHipFraction: Double?,
    /** Set on the frame that closed an interval long enough to be admitted. */
    val completedInterval: HandstandHoldInterval?,
)

/** Pure per-frame test of the stacked-inversion condition. */
fun measureHandstand(frame: HandstandPoseFrame, config: HandstandHoldConfig): Triple<HandstandReason, Double?, Double?> {
    if (frame.width <= 0 || frame.height <= 0) return Triple(HandstandReason.INVALID_DIMENSIONS, null, null)
    if (frame.landmarks.isEmpty()) return Triple(HandstandReason.NO_POSE, null, null)

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return Triple(HandstandReason.INVALID_LANDMARK, null, null)
        if (byIndex.put(landmark.index, landmark) != null) {
            return Triple(HandstandReason.DUPLICATE_LANDMARK_INDEX, null, null)
        }
    }
    val sideIndices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 15, 23)
        ArmSide.RIGHT -> intArrayOf(12, 16, 24)
    }
    // Both ankles are required regardless of side: see the class comment on limb identity swaps.
    val indices = sideIndices + intArrayOf(27, 28)
    val joints = indices.map { byIndex[it] ?: return Triple(HandstandReason.MISSING_LANDMARK, null, null) }
    if (joints.any { l ->
            !l.x.isFinite() || !l.y.isFinite() || !l.z.isFinite() || l.x !in 0.0..1.0 || l.y !in 0.0..1.0
        }
    ) {
        return Triple(HandstandReason.INVALID_LANDMARK, null, null)
    }
    if (joints.any { l ->
            val v = l.visibility
            val p = l.presence
            v == null || p == null || !v.isFinite() || !p.isFinite() || v !in 0.0..1.0 || p !in 0.0..1.0 ||
                v < config.minimumConfidence || p < config.minimumConfidence
        }
    ) {
        return Triple(HandstandReason.LOW_CONFIDENCE, null, null)
    }

    val shoulder = joints[0]
    val wrist = joints[1]
    val hip = joints[2]
    val highestAnkleY = maxOf(joints[3].y, joints[4].y) // larger y = lower in the image

    // Normalized height fractions; positive means "above" in the image.
    val hipAboveShoulder = shoulder.y - hip.y
    val ankleAboveHip = hip.y - highestAnkleY

    if (hipAboveShoulder < config.minimumHipAboveShoulderFraction) {
        return Triple(HandstandReason.NOT_INVERTED, hipAboveShoulder, ankleAboveHip)
    }
    if (ankleAboveHip < config.minimumAnkleAboveHipFraction) {
        return Triple(HandstandReason.LEGS_NOT_STACKED, hipAboveShoulder, ankleAboveHip)
    }
    if (wrist.y <= shoulder.y) {
        return Triple(HandstandReason.HANDS_NOT_BELOW_SHOULDERS, hipAboveShoulder, ankleAboveHip)
    }
    return Triple(HandstandReason.NONE, hipAboveShoulder, ankleAboveHip)
}

/**
 * Conservative, explicit-config hold-interval experiment for a selected-mode handstand. It reports
 * intervals with entry, exit, duration, end reason and censoring. It does not count repetitions,
 * judge the line, or claim wall contact.
 */
class HandstandHoldExperiment(private val config: HandstandHoldConfig) {
    private var lastTimestampNs: Long? = null
    private var firstTimestampNs: Long? = null
    private var entryNs: Long? = null
    private var lastHoldingNs: Long? = null
    private var entryIsLeftCensored = false
    private var nonHoldingSamples = 0
    private var nonHoldingStartNs: Long? = null

    /**
     * `minimumNonHoldingSamplesToClose` read as the duration it expresses; see
     * [sampleCountAsDurationNs]. A non-holding burst is tolerated until it has LASTED this long,
     * rather than until it has covered that many frames.
     */
    private val nonHoldingToleranceNs = sampleCountAsDurationNs(config.minimumNonHoldingSamplesToClose)
    private val nonHoldingFloor = endpointSampleFloor(config.minimumNonHoldingSamplesToClose)

    val intervals = mutableListOf<HandstandHoldInterval>()

    fun onFrame(frame: HandstandPoseFrame): HandstandFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) return breakWith(timestamp, HandstandReason.INVALID_TIMESTAMP, null, null)
        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) return breakWith(timestamp, HandstandReason.DUPLICATE_TIMESTAMP, null, null)
            if (timestamp < previous) return breakWith(timestamp, HandstandReason.BACKWARDS_TIMESTAMP, null, null)
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp
        if (firstTimestampNs == null) firstTimestampNs = timestamp

        val (reason, hipAbove, ankleAbove) = measureHandstand(frame, config)
        if (gap) {
            // The frame before the gap was the last observed holding frame; close there.
            val closed = close(HandstandReason.CAPTURE_GAP, rightCensored = false)
            return if (reason == HandstandReason.NONE) {
                open(timestamp, leftCensored = false)
                HandstandFrameResult(timestamp, true, HandstandReason.CAPTURE_GAP, hipAbove, ankleAbove, closed)
            } else {
                HandstandFrameResult(timestamp, false, HandstandReason.CAPTURE_GAP, hipAbove, ankleAbove, closed)
            }
        }
        if (reason != HandstandReason.NONE) {
            if (reason.isGeometricNonHolding()) {
                if (nonHoldingSamples == 0) nonHoldingStartNs = timestamp
                nonHoldingSamples++
                if (!nonHoldingLastedLongEnough(timestamp) && entryNs != null) {
                    // Transient: keep the interval open. lastHoldingNs stays behind, so the exit
                    // still reports the last frame that genuinely satisfied the stacked rule.
                    return HandstandFrameResult(timestamp, false, reason, hipAbove, ankleAbove, null)
                }
            } else {
                clearNonHoldingRun()
            }
            val closed = close(reason, rightCensored = false)
            return HandstandFrameResult(timestamp, false, reason, hipAbove, ankleAbove, closed)
        }
        clearNonHoldingRun()
        if (entryNs == null) open(timestamp, leftCensored = timestamp == firstTimestampNs)
        lastHoldingNs = timestamp
        return HandstandFrameResult(timestamp, true, HandstandReason.NONE, hipAbove, ankleAbove, null)
    }

    /** Close any interval still open at the end of input; it is right-censored. */
    fun finish(): HandstandHoldInterval? = close(HandstandReason.END_OF_INPUT, rightCensored = true)

    /** The three reasons a VALID measurement can report "not a handstand right now". */
    private fun HandstandReason.isGeometricNonHolding() = this == HandstandReason.NOT_INVERTED ||
        this == HandstandReason.LEGS_NOT_STACKED || this == HandstandReason.HANDS_NOT_BELOW_SHOULDERS

    private fun clearNonHoldingRun() { nonHoldingSamples = 0; nonHoldingStartNs = null }

    private fun nonHoldingLastedLongEnough(timestamp: Long): Boolean {
        val start = nonHoldingStartNs ?: return false
        return nonHoldingSamples >= nonHoldingFloor && timestamp - start >= nonHoldingToleranceNs
    }

    private fun open(timestamp: Long, leftCensored: Boolean) {
        entryNs = timestamp
        lastHoldingNs = timestamp
        entryIsLeftCensored = leftCensored
    }

    private fun close(reason: HandstandReason, rightCensored: Boolean): HandstandHoldInterval? {
        val entry = entryNs ?: return null
        val exit = lastHoldingNs ?: entry
        entryNs = null
        lastHoldingNs = null
        clearNonHoldingRun()
        val duration = exit - entry
        if (duration < config.minimumHoldNs) return null
        val interval = HandstandHoldInterval(entry, exit, duration, reason, entryIsLeftCensored, rightCensored)
        intervals += interval
        return interval
    }

    private fun breakWith(
        timestamp: Long,
        reason: HandstandReason,
        hipAbove: Double?,
        ankleAbove: Double?,
    ): HandstandFrameResult {
        val closed = close(reason, rightCensored = false)
        return HandstandFrameResult(timestamp, false, reason, hipAbove, ankleAbove, closed)
    }
}
