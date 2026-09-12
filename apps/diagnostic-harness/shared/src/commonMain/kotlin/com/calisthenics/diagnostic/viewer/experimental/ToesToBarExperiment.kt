package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.sqrt

/**
 * Explicit prototype parameters for a selected-mode kipping toes-to-bar candidate cycle.
 *
 * The defining observable is FEET REACHING HAND HEIGHT: the lower of the two ankles, measured
 * below the configured wrist in torso lengths. The existing hanging-leg-raise experiment does not
 * measure this; it measures hip closure and requires a sustained legs-down dwell before each
 * raise. On real kipping footage that dwell never occurs — the legs swing straight back into the
 * next kip with the knees bending — so it detected 4 of 7 repetitions. That behaviour is correct
 * for a strict hanging leg raise and simply the wrong model here, which is why the two movements
 * are kept separate.
 *
 * Values were calibrated on ONE development recording (k71s4T2_CJI) and frozen before running on a
 * second recording (AJAVu29c-NU) whose labels were frozen before this experiment existed. Both are
 * the same coach and gym, so that is a second recording, not an independent athlete.
 *  - touches measured 0.21-0.34 torso lengths below the hands (both feet);
 *  - legs-down returns measured 2.12-3.31;
 *  - the kipping muscle-ups in the same recordings never came closer than 1.37.
 * [maximumFeetBelowHandsAtBar] = 0.6 and [minimumFeetBelowHandsDown] = 1.5 sit with margin between
 * these. [minimumHandsAboveShoulders] = 0.35 reuses the hanging-leg-raise support-rise value; it
 * rejects the above-bar support phase of a muscle-up, where the hands are below the shoulders.
 */
data class ToesToBarConfig(
    val side: ArmSide,
    val minimumHandsAboveShoulders: Double,
    val maximumFeetBelowHandsAtBar: Double,
    val minimumFeetBelowHandsDown: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
    /**
     * When true, BOTH ankles must be observable; a frame with only one usable ankle is unusable.
     * That was the v1 behaviour and it is the stricter reading of "toes to bar".
     *
     * It does not survive side-view filming. On two independent recordings the far ankle was
     * usable in only 86/420 and 116/1051 frames (a rig upright, and simple self-occlusion) while
     * the near ankle was usable in 349 and 882 — so the engine reported zero repetitions on both.
     * With false, the lower of whichever ankles are observable is used. The cost is explicit: a
     * one-legged kick on the observable side can then be accepted as a touch.
     */
    val requireBothAnkles: Boolean,
) {
    init {
        require(minimumHandsAboveShoulders.isFinite() && minimumHandsAboveShoulders > 0.0) {
            "minimum_hands_above_shoulders must be finite and positive"
        }
        require(maximumFeetBelowHandsAtBar.isFinite()) { "maximum_feet_below_hands_at_bar must be finite" }
        require(minimumFeetBelowHandsDown.isFinite()) { "minimum_feet_below_hands_down must be finite" }
        require(maximumFeetBelowHandsAtBar < minimumFeetBelowHandsDown) {
            "maximum_feet_below_hands_at_bar must be less than minimum_feet_below_hands_down"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEndpointSamples >= 2) { "minimum_endpoint_samples must be at least 2" }
    }
}

data class ToesToBarPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

/** HANGING = legs down, RAISING, AT_BAR = feet at hand height, RETURNING. */
enum class ToesToBarPhase { WAITING, HANGING, RAISING, AT_BAR, RETURNING, UNCERTAIN }

enum class ToesToBarReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    ZERO_LENGTH_TORSO,
    NOT_HANGING,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
}

data class ToesToBarMeasurement(
    val handsAboveShoulders: Double?,
    val feetBelowHands: Double?,
    val reason: ToesToBarReason,
)

data class ToesToBarFrameResult(
    val timestampNs: Long,
    val handsAboveShoulders: Double?,
    val feetBelowHands: Double?,
    val phase: ToesToBarPhase,
    val count: Int,
    val reason: ToesToBarReason,
    val counted: Boolean,
    /**
     * When [counted] is true, the instant the final endpoint was FIRST satisfied. The frame that
     * confirms a cycle is `minimum_endpoint_samples - 1` intervals later, so [timestampNs] is
     * systematically late by that much and must not be used as the event time.
     */
    val eventTimestampNs: Long? = null,
)

/**
 * Pure image-plane measurement. Both quantities are in torso lengths (shoulder-to-hip, projected
 * in source pixels), so they are independent of framing and camera distance.
 */
fun measureToesToBar(frame: ToesToBarPoseFrame, config: ToesToBarConfig): ToesToBarMeasurement {
    fun reject(reason: ToesToBarReason) = ToesToBarMeasurement(null, null, reason)

    if (frame.width <= 0 || frame.height <= 0) return reject(ToesToBarReason.INVALID_DIMENSIONS)
    if (frame.landmarks.isEmpty()) return reject(ToesToBarReason.NO_POSE)

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return reject(ToesToBarReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) return reject(ToesToBarReason.DUPLICATE_LANDMARK_INDEX)
    }
    val sideIndices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 15, 23)
        ArmSide.RIGHT -> intArrayOf(12, 16, 24)
    }
    val core = sideIndices.map { byIndex[it] ?: return reject(ToesToBarReason.MISSING_LANDMARK) }
    // Ankles: both when required, otherwise whichever are observable (see requireBothAnkles).
    val ankleCandidates = listOf(27, 28).mapNotNull { byIndex[it] }
    if (config.requireBothAnkles && ankleCandidates.size < 2) return reject(ToesToBarReason.MISSING_LANDMARK)
    if (ankleCandidates.isEmpty()) return reject(ToesToBarReason.MISSING_LANDMARK)
    val joints = core + ankleCandidates
    if (joints.any { l ->
            !l.x.isFinite() || !l.y.isFinite() || !l.z.isFinite() || l.x !in 0.0..1.0 || l.y !in 0.0..1.0
        }
    ) {
        return reject(ToesToBarReason.INVALID_LANDMARK)
    }
    fun usable(l: Landmark): Boolean {
        val v = l.visibility
        val p = l.presence
        return v != null && p != null && v.isFinite() && p.isFinite() && v in 0.0..1.0 && p in 0.0..1.0 &&
            v >= config.minimumConfidence && p >= config.minimumConfidence
    }
    if (core.any { !usable(it) }) return reject(ToesToBarReason.LOW_CONFIDENCE)
    val usableAnkles = ankleCandidates.filter { usable(it) }
    if (config.requireBothAnkles && usableAnkles.size < 2) return reject(ToesToBarReason.LOW_CONFIDENCE)
    if (usableAnkles.isEmpty()) return reject(ToesToBarReason.LOW_CONFIDENCE)

    val shoulder = core[0]
    val wrist = core[1]
    val hip = core[2]
    val lowerAnkleY = usableAnkles.maxOf { it.y } // larger y = lower in the image

    val dx = (hip.x - shoulder.x) * frame.width
    val dy = (hip.y - shoulder.y) * frame.height
    val torso = sqrt(dx * dx + dy * dy) / frame.height // torso length in normalized height units
    if (torso == 0.0) return reject(ToesToBarReason.ZERO_LENGTH_TORSO)

    val handsAboveShoulders = (shoulder.y - wrist.y) / torso
    val feetBelowHands = (lowerAnkleY - wrist.y) / torso
    if (handsAboveShoulders < config.minimumHandsAboveShoulders) {
        return ToesToBarMeasurement(handsAboveShoulders, feetBelowHands, ToesToBarReason.NOT_HANGING)
    }
    return ToesToBarMeasurement(handsAboveShoulders, feetBelowHands, ToesToBarReason.NONE)
}

/**
 * Conservative, explicit-config candidate cycle experiment for selected-mode kipping toes-to-bar.
 * Counts on the RETURN to legs-down after the feet reached hand height. No stillness is required,
 * so continuous kipping is admissible. It does not judge the kip, contact with the bar, or form.
 */
class ToesToBarExperiment(private val config: ToesToBarConfig) {
    val phase: ToesToBarPhase get() = timing.phase.toToesToBarPhase()
    val count: Int get() = timing.count

    private var lastTimestampNs: Long? = null
    private val timing = ElbowCycleTiming(config.minimumEndpointSamples)

    fun onFrame(frame: ToesToBarPoseFrame): ToesToBarFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            timing.invalidate()
            return result(timestamp, null, null, ToesToBarReason.INVALID_TIMESTAMP, false)
        }
        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                timing.invalidate()
                return result(timestamp, null, null, ToesToBarReason.DUPLICATE_TIMESTAMP, false)
            }
            if (timestamp < previous) {
                timing.invalidate()
                return result(timestamp, null, null, ToesToBarReason.BACKWARDS_TIMESTAMP, false)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val m = measureToesToBar(frame, config)
        if (m.reason != ToesToBarReason.NONE) {
            timing.invalidate()
            return result(timestamp, m.handsAboveShoulders, m.feetBelowHands, m.reason, false)
        }
        if (gap) timing.invalidate()

        val feet = m.feetBelowHands!!
        // ElbowCycleTiming's "supported" endpoint is legs-down; its "lowered" endpoint is feet-at-bar.
        val eventNs = timing.onObservation(
            timestampNs = timestamp,
            isSupported = feet >= config.minimumFeetBelowHandsDown,
            isLowered = feet <= config.maximumFeetBelowHandsAtBar,
        )
        return result(
            timestamp, m.handsAboveShoulders, feet,
            if (gap) ToesToBarReason.CAPTURE_GAP else ToesToBarReason.NONE, eventNs != null, eventNs,
        )
    }

    private fun result(
        timestamp: Long,
        hands: Double?,
        feet: Double?,
        reason: ToesToBarReason,
        counted: Boolean,
        eventNs: Long? = null,
    ) = ToesToBarFrameResult(timestamp, hands, feet, phase, count, reason, counted, eventNs)
}

internal fun ElbowCyclePhase.toToesToBarPhase(): ToesToBarPhase = when (this) {
    ElbowCyclePhase.WAITING -> ToesToBarPhase.WAITING
    ElbowCyclePhase.SUPPORTED -> ToesToBarPhase.HANGING
    ElbowCyclePhase.LOWERING -> ToesToBarPhase.RAISING
    ElbowCyclePhase.LOWERED -> ToesToBarPhase.AT_BAR
    ElbowCyclePhase.RAISING -> ToesToBarPhase.RETURNING
    ElbowCyclePhase.UNCERTAIN -> ToesToBarPhase.UNCERTAIN
}
