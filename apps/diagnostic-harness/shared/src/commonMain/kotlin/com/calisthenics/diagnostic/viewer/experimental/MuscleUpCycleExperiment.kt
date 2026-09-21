package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Variant label for a selected-mode muscle-up observation. The variant is recorded, never
 * inferred: a strict ring cycle and a kipping bar cycle are different observations and must not
 * be pooled into one result. It carries no hidden numeric behavior; every threshold comes from
 * [MuscleUpConfig].
 */
enum class MuscleUpVariant { RING_STRICT, BAR_KIPPING }

/**
 * Explicit prototype parameters for a single-side muscle-up candidate cycle, frozen from raw
 * inspection of development footage. These are image-plane geometric hypotheses, not validated
 * anatomical thresholds, and they say nothing about technique, depth, load or muscle activation.
 *
 * The defining measurement is a *regime crossing*, not an elbow angle. A pull-up stays below the
 * apparatus (wrist above shoulder). A dip stays above it (shoulder above wrist). A muscle-up is
 * the movement that leaves the first regime and reaches the second while the hands stay on the
 * apparatus. Elbow flexion alone cannot separate any of these, so it is never used to count.
 *
 * Two further gates exist because cached development footage showed MediaPipe returning
 * confident-but-wrong arm skeletons when the real upper body was cropped out of frame. Passing
 * confidence and bounds checks does NOT establish that a landmark is on the correct joint, so
 * these gates test physical consistency instead of simply demanding higher confidence:
 *  - [maximumGripDriftTorsoLengths]: the grip point cannot travel far, because the apparatus does
 *    not move under a fixed camera. A wrist that migrates down the frame is not a wrist.
 *  - [minimumBodyScaleRatio]/[maximumBodyScaleRatio]: the torso segment cannot suddenly halve or
 *    double in the image plane. A collapsed skeleton drawn onto the hips fails this.
 * Both gates mark a frame unusable. They never convert an unusable frame into a repetition.
 */
data class MuscleUpConfig(
    val variant: MuscleUpVariant,
    val side: ArmSide,
    /** Wrist must sit at least this normalized-height fraction ABOVE the shoulder to be below-bar. */
    val minimumHangWristAboveShoulderFraction: Double,
    /** Shoulder must sit at least this normalized-height fraction ABOVE the wrist to be supported. */
    val minimumSupportShoulderAboveWristFraction: Double,
    /** Maximum grip travel from the established reference, expressed in reference torso lengths. */
    val maximumGripDriftTorsoLengths: Double,
    val minimumBodyScaleRatio: Double,
    val maximumBodyScaleRatio: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
    /**
     * Consecutive below-bar frames required to establish the reference. Taking the reference from
     * the first frames that merely satisfy "wrist above shoulder" captured the *mount*, where the
     * athlete reaches upward before the hands are on the apparatus; every later frame then read as
     * grip drift. Sized to span roughly 150 ms of footage at the source frame rate.
     */
    val minimumReferenceSamples: Int,
    /**
     * Maximum spread of the wrist across those reference frames, in reference torso lengths. A
     * genuine grip is already stationary because the apparatus does not move; a mount is not.
     */
    val maximumReferenceWristSpreadTorsoLengths: Double,
    /**
     * A repetition finishes only when the athlete is back below the apparatus WITH THE ARMS
     * STRAIGHT (elbow at or above this angle), not at the instant of dropping below it. On real
     * footage this moved completion only ~45 ms later against reviewer labels, so it is kept for
     * its meaning as the true end of the movement, not because it improved a score.
     */
    val hangLockoutElbowDegrees: Double,
) {
    init {
        require(
            minimumHangWristAboveShoulderFraction.isFinite() &&
                minimumHangWristAboveShoulderFraction > 0.0,
        ) { "minimum_hang_wrist_above_shoulder_fraction must be finite and positive" }
        require(
            minimumSupportShoulderAboveWristFraction.isFinite() &&
                minimumSupportShoulderAboveWristFraction > 0.0,
        ) { "minimum_support_shoulder_above_wrist_fraction must be finite and positive" }
        require(maximumGripDriftTorsoLengths.isFinite() && maximumGripDriftTorsoLengths > 0.0) {
            "maximum_grip_drift_torso_lengths must be finite and positive"
        }
        require(minimumBodyScaleRatio.isFinite() && minimumBodyScaleRatio > 0.0 && minimumBodyScaleRatio < 1.0) {
            "minimum_body_scale_ratio must be finite and in (0, 1)"
        }
        require(maximumBodyScaleRatio.isFinite() && maximumBodyScaleRatio > 1.0) {
            "maximum_body_scale_ratio must be finite and greater than 1"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEndpointSamples >= 2) { "minimum_endpoint_samples must be at least 2" }
        require(minimumReferenceSamples >= 2) { "minimum_reference_samples must be at least 2" }
        require(
            maximumReferenceWristSpreadTorsoLengths.isFinite() &&
                maximumReferenceWristSpreadTorsoLengths > 0.0,
        ) { "maximum_reference_wrist_spread_torso_lengths must be finite and positive" }
        require(hangLockoutElbowDegrees.isFinite() && hangLockoutElbowDegrees in 0.0..180.0) {
            "hang_lockout_elbow_degrees must be finite and in [0, 180]"
        }
    }
}

data class MuscleUpPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

/**
 * WAITING before a reference grip/scale exists, BELOW_BAR in the hang regime, ASCENDING after
 * leaving it, SUPPORTED once above-apparatus support is actually observed, RETURNING on the way
 * back down, UNCERTAIN when continuity was lost.
 */
enum class MuscleUpPhase { WAITING, BELOW_BAR, ASCENDING, SUPPORTED, RETURNING, UNCERTAIN }

enum class MuscleUpReason {
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
    GRIP_DRIFT,
    BODY_SCALE_INCONSISTENT,
}

/**
 * How a candidate excursion away from the hang regime ended.
 *
 * [COMPLETED] requires above-apparatus support to have been *observed* on usable frames.
 * [SUPPORT_UNRESOLVED] is emitted when the athlete left and returned to the hang regime without
 * any usable support observation — for example when the upper body was cropped or the skeleton
 * became unreliable exactly where support would have been. It is deliberately NOT a repetition
 * and deliberately NOT a rejection: the endpoint is unknown and stays unknown.
 */
enum class MuscleUpExcursionOutcome { COMPLETED, SUPPORT_UNRESOLVED }

data class MuscleUpExcursion(
    val leftHangAtNs: Long,
    val supportObservedAtNs: Long?,
    val returnedToHangAtNs: Long,
    val outcome: MuscleUpExcursionOutcome,
    val unusableFrames: Int,
)

data class MuscleUpMeasurement(
    /** Positive when the shoulder is above the wrist (support-like); negative in the hang. */
    val shoulderAboveWristFraction: Double?,
    val torsoLengthPixels: Double?,
    val bodyScaleRatio: Double?,
    val gripDriftTorsoLengths: Double?,
    val reason: MuscleUpReason,
    val elbowDegrees: Double? = null,
)

data class MuscleUpFrameResult(
    val timestampNs: Long,
    val shoulderAboveWristFraction: Double?,
    val bodyScaleRatio: Double?,
    val gripDriftTorsoLengths: Double?,
    val phase: MuscleUpPhase,
    val count: Int,
    val unresolvedExcursions: Int,
    val reason: MuscleUpReason,
    val counted: Boolean,
    val completedExcursion: MuscleUpExcursion?,
)

/** Geometry the analyzer treats as the fixed reference for grip position and body scale. */
data class MuscleUpReference(
    val wristX: Double,
    val wristY: Double,
    val torsoLengthPixels: Double,
)

private const val LEFT_SHOULDER = 11
private const val LEFT_ELBOW = 13
private const val LEFT_WRIST = 15
private const val LEFT_HIP = 23
private const val RIGHT_SHOULDER = 12
private const val RIGHT_ELBOW = 14
private const val RIGHT_WRIST = 16
private const val RIGHT_HIP = 24

/**
 * Pure image-plane measurement for one frame. When [reference] is null the scale and grip gates
 * cannot be applied yet and their outputs are null; the frame is still measured so that a
 * reference can be established from the hang.
 */
fun measureMuscleUp(
    frame: MuscleUpPoseFrame,
    config: MuscleUpConfig,
    reference: MuscleUpReference?,
): MuscleUpMeasurement {
    fun reject(reason: MuscleUpReason) = MuscleUpMeasurement(null, null, null, null, reason)

    if (frame.width <= 0 || frame.height <= 0) return reject(MuscleUpReason.INVALID_DIMENSIONS)
    if (frame.landmarks.isEmpty()) return reject(MuscleUpReason.NO_POSE)

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return reject(MuscleUpReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) {
            return reject(MuscleUpReason.DUPLICATE_LANDMARK_INDEX)
        }
    }

    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST, LEFT_HIP)
        ArmSide.RIGHT -> intArrayOf(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST, RIGHT_HIP)
    }
    val joints = indices.map { byIndex[it] ?: return reject(MuscleUpReason.MISSING_LANDMARK) }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return reject(MuscleUpReason.INVALID_LANDMARK)
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
        return reject(MuscleUpReason.LOW_CONFIDENCE)
    }

    val shoulder = joints[0]
    val elbow = joints[1]
    val wrist = joints[2]
    val hip = joints[3]
    val width = frame.width.toDouble()
    val height = frame.height.toDouble()

    // Elbow angle in source pixels, preserving the pixel aspect ratio.
    val ax = (shoulder.x - elbow.x) * width
    val ay = (shoulder.y - elbow.y) * height
    val bx = (wrist.x - elbow.x) * width
    val by = (wrist.y - elbow.y) * height
    if (sqrt(ax * ax + ay * ay) == 0.0 || sqrt(bx * bx + by * by) == 0.0) {
        return reject(MuscleUpReason.ZERO_LENGTH_LIMB)
    }
    val elbowDegrees = atan2(abs(ax * by - ay * bx), ax * bx + ay * by) * 180.0 / PI

    // Both endpoints share the same normalized [0, 1] height axis, so the difference is already a
    // height-normalized fraction. Positive means the shoulder sits above the wrist in the image.
    val shoulderAboveWristFraction = wrist.y - shoulder.y

    // Segment lengths need the pixel aspect ratio, so normalized coordinates are projected first.
    val torsoDx = (hip.x - shoulder.x) * width
    val torsoDy = (hip.y - shoulder.y) * height
    val torsoLengthPixels = sqrt(torsoDx * torsoDx + torsoDy * torsoDy)
    if (torsoLengthPixels == 0.0) return reject(MuscleUpReason.ZERO_LENGTH_LIMB)

    if (reference == null) {
        return MuscleUpMeasurement(
            shoulderAboveWristFraction = shoulderAboveWristFraction,
            torsoLengthPixels = torsoLengthPixels,
            bodyScaleRatio = null,
            gripDriftTorsoLengths = null,
            reason = MuscleUpReason.NONE,
            elbowDegrees = elbowDegrees,
        )
    }

    val bodyScaleRatio = torsoLengthPixels / reference.torsoLengthPixels
    val gripDx = (wrist.x - reference.wristX) * width
    val gripDy = (wrist.y - reference.wristY) * height
    val gripDriftTorsoLengths =
        sqrt(gripDx * gripDx + gripDy * gripDy) / reference.torsoLengthPixels

    // The scale gate is checked first: once the skeleton has collapsed, its wrist position is not
    // meaningful either, and reporting the more fundamental failure is clearer in a trace.
    if (bodyScaleRatio < config.minimumBodyScaleRatio || bodyScaleRatio > config.maximumBodyScaleRatio) {
        return MuscleUpMeasurement(
            shoulderAboveWristFraction, torsoLengthPixels, bodyScaleRatio, gripDriftTorsoLengths,
            MuscleUpReason.BODY_SCALE_INCONSISTENT, elbowDegrees,
        )
    }
    if (gripDriftTorsoLengths > config.maximumGripDriftTorsoLengths) {
        return MuscleUpMeasurement(
            shoulderAboveWristFraction, torsoLengthPixels, bodyScaleRatio, gripDriftTorsoLengths,
            MuscleUpReason.GRIP_DRIFT, elbowDegrees,
        )
    }

    return MuscleUpMeasurement(
        shoulderAboveWristFraction, torsoLengthPixels, bodyScaleRatio, gripDriftTorsoLengths,
        MuscleUpReason.NONE, elbowDegrees,
    )
}

/**
 * Conservative, explicit-config candidate cycle experiment for a selected-mode muscle-up.
 *
 * It counts an excursion only when above-apparatus support was actually observed on usable
 * frames. An excursion whose support interval was never usable is reported as
 * [MuscleUpExcursionOutcome.SUPPORT_UNRESOLVED] and increments [unresolvedExcursions], never
 * [count]. No static hang is required: the hang regime is purely geometric, so a continuously
 * swinging kipping cycle is admissible. Support and full elbow lockout are different
 * observations; this experiment measures support only and makes no lockout claim.
 */
class MuscleUpCycleExperiment(private val config: MuscleUpConfig) {
    var phase: MuscleUpPhase = MuscleUpPhase.WAITING
        private set
    var count: Int = 0
        private set
    var unresolvedExcursions: Int = 0
        private set
    var reference: MuscleUpReference? = null
        private set

    private var lastTimestampNs: Long? = null
    private var hangSamples = 0
    private var supportSamples = 0
    private var hangStartNs: Long? = null
    private var supportStartNs: Long? = null

    /** The two frozen counts read as the durations they express; see [sampleCountAsDurationNs]. */
    private val minimumEndpointHoldNs = sampleCountAsDurationNs(config.minimumEndpointSamples)
    private val endpointFloor = endpointSampleFloor(config.minimumEndpointSamples)
    private val minimumReferenceSpanNs = sampleCountAsDurationNs(config.minimumReferenceSamples)

    /** Rolling window of candidate reference frames: wrist x/y, torso px, frame width/height. */
    private class ReferenceCandidate(
        val timestampNs: Long,
        val wristX: Double,
        val wristY: Double,
        val torsoPixels: Double,
        val width: Double,
        val height: Double,
    )

    private val pendingReference = ArrayDeque<ReferenceCandidate>()

    private var leftHangAtNs: Long? = null
    private var supportObservedAtNs: Long? = null
    private var unusableFramesThisExcursion = 0

    fun onFrame(frame: MuscleUpPoseFrame): MuscleUpFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            invalidate()
            return result(timestamp, null, null, null, MuscleUpReason.INVALID_TIMESTAMP)
        }
        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                invalidate()
                return result(timestamp, null, null, null, MuscleUpReason.DUPLICATE_TIMESTAMP)
            }
            if (timestamp < previous) {
                invalidate()
                return result(timestamp, null, null, null, MuscleUpReason.BACKWARDS_TIMESTAMP)
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val measurement = measureMuscleUp(frame, config, reference)
        if (measurement.reason != MuscleUpReason.NONE) {
            if (phase == MuscleUpPhase.ASCENDING || phase == MuscleUpPhase.SUPPORTED ||
                phase == MuscleUpPhase.RETURNING
            ) {
                unusableFramesThisExcursion++
            }
            invalidate()
            return result(
                timestamp, measurement.shoulderAboveWristFraction, measurement.bodyScaleRatio,
                measurement.gripDriftTorsoLengths, measurement.reason,
            )
        }
        if (gap) {
            invalidate()
            return result(
                timestamp, measurement.shoulderAboveWristFraction, measurement.bodyScaleRatio,
                measurement.gripDriftTorsoLengths, MuscleUpReason.CAPTURE_GAP,
            )
        }

        val fraction = measurement.shoulderAboveWristFraction ?: return result(
            timestamp, null, measurement.bodyScaleRatio, measurement.gripDriftTorsoLengths,
            MuscleUpReason.NO_POSE,
        )
        val isBelowBar = fraction <= -config.minimumHangWristAboveShoulderFraction
        val isSupported = fraction >= config.minimumSupportShoulderAboveWristFraction

        if (reference == null) {
            return establishReference(frame, timestamp, measurement, isBelowBar)
        }
        val elbow = measurement.elbowDegrees
        val isHangComplete = isBelowBar && elbow != null && elbow >= config.hangLockoutElbowDegrees
        return advance(timestamp, measurement, isBelowBar, isSupported, isHangComplete)
    }

    /**
     * The reference is taken only from consecutive below-bar frames. Anchoring on the hang is what
     * makes the grip gate meaningful: it is the phase where the hands are unambiguously on the
     * apparatus and the whole body is normally in frame.
     */
    private fun endpointHeld(samples: Int, startNs: Long?, timestamp: Long): Boolean {
        val start = startNs ?: return false
        return samples >= endpointFloor && timestamp - start >= minimumEndpointHoldNs
    }

    private fun clearHangRun() { hangSamples = 0; hangStartNs = null }
    private fun clearSupportRun() { supportSamples = 0; supportStartNs = null }

    /** Marks the hang endpoint as already satisfied at [timestamp], whatever comes next. */
    private fun seedSatisfiedHang(timestamp: Long) {
        hangSamples = endpointFloor
        hangStartNs = timestamp - minimumEndpointHoldNs
    }

    private fun referenceWindowSettled(timestamp: Long): Boolean =
        pendingReference.size >= 2 &&
            timestamp - pendingReference.first().timestampNs >= minimumReferenceSpanNs

    private fun establishReference(
        frame: MuscleUpPoseFrame,
        timestamp: Long,
        measurement: MuscleUpMeasurement,
        isBelowBar: Boolean,
    ): MuscleUpFrameResult {
        if (!isBelowBar) {
            pendingReference.clear()
            return result(timestamp, measurement.shoulderAboveWristFraction, null, null, MuscleUpReason.NONE)
        }
        val byIndex = frame.landmarks.associateBy { it.index }
        val wristIndex = if (config.side == ArmSide.LEFT) LEFT_WRIST else RIGHT_WRIST
        val wrist = byIndex[wristIndex] ?: return result(
            timestamp, measurement.shoulderAboveWristFraction, null, null, MuscleUpReason.MISSING_LANDMARK,
        )
        pendingReference.addLast(
            ReferenceCandidate(
                timestamp, wrist.x, wrist.y, measurement.torsoLengthPixels ?: 0.0,
                frame.width.toDouble(), frame.height.toDouble(),
            ),
        )
        // Keep the SMALLEST window that still spans the required settling time: drop the oldest
        // sample only while what remains would still cover it. At 30 fps that leaves exactly
        // `minimumReferenceSamples` frames, which is what the count rule kept.
        while (pendingReference.size > 2 &&
            timestamp - pendingReference.elementAt(1).timestampNs >= minimumReferenceSpanNs
        ) pendingReference.removeFirst()

        if (referenceWindowSettled(timestamp)) {
            val meanTorso = pendingReference.sumOf { it.torsoPixels } / pendingReference.size
            if (meanTorso > 0.0) {
                var spread = 0.0
                for (i in 0 until pendingReference.size) {
                    for (j in i + 1 until pendingReference.size) {
                        val a = pendingReference.elementAt(i)
                        val b = pendingReference.elementAt(j)
                        val dx = (a.wristX - b.wristX) * a.width
                        val dy = (a.wristY - b.wristY) * a.height
                        val distance = sqrt(dx * dx + dy * dy)
                        if (distance > spread) spread = distance
                    }
                }
                // A genuine grip is already stationary; a mount is not.
                if (spread / meanTorso <= config.maximumReferenceWristSpreadTorsoLengths) {
                    val n = pendingReference.size.toDouble()
                    reference = MuscleUpReference(
                        pendingReference.sumOf { it.wristX } / n,
                        pendingReference.sumOf { it.wristY } / n,
                        meanTorso,
                    )
                    pendingReference.clear()
                    phase = MuscleUpPhase.BELOW_BAR
                    seedSatisfiedHang(timestamp)
                }
            }
        }
        return result(timestamp, measurement.shoulderAboveWristFraction, null, null, MuscleUpReason.NONE)
    }

    private fun advance(
        timestamp: Long,
        measurement: MuscleUpMeasurement,
        isBelowBar: Boolean,
        isSupported: Boolean,
        isHangComplete: Boolean,
    ): MuscleUpFrameResult {
        var counted = false
        var completed: MuscleUpExcursion? = null

        when (phase) {
            MuscleUpPhase.WAITING, MuscleUpPhase.UNCERTAIN -> {
                if (isBelowBar) {
                    if (hangSamples == 0) hangStartNs = timestamp
                    hangSamples++
                    if (endpointHeld(hangSamples, hangStartNs, timestamp)) {
                        phase = MuscleUpPhase.BELOW_BAR
                    }
                } else {
                    clearHangRun()
                }
            }
            MuscleUpPhase.BELOW_BAR -> {
                if (!isBelowBar) {
                    phase = MuscleUpPhase.ASCENDING
                    leftHangAtNs = timestamp
                    supportObservedAtNs = null
                    unusableFramesThisExcursion = 0
                    clearSupportRun()
                }
            }
            MuscleUpPhase.ASCENDING -> {
                when {
                    isSupported -> {
                        if (supportSamples == 0) supportStartNs = timestamp
                        supportSamples++
                        if (endpointHeld(supportSamples, supportStartNs, timestamp)) {
                            phase = MuscleUpPhase.SUPPORTED
                            supportObservedAtNs = timestamp
                        }
                    }
                    isHangComplete -> {
                        // Returned to the hang without any usable support observation.
                        completed = closeExcursion(timestamp)
                        phase = MuscleUpPhase.BELOW_BAR
                    }
                    else -> clearSupportRun()
                }
            }
            MuscleUpPhase.SUPPORTED -> {
                if (!isSupported) {
                    phase = if (isHangComplete) {
                        completed = closeExcursion(timestamp)
                        counted = completed?.outcome == MuscleUpExcursionOutcome.COMPLETED
                        MuscleUpPhase.BELOW_BAR
                    } else {
                        MuscleUpPhase.RETURNING
                    }
                }
            }
            MuscleUpPhase.RETURNING -> {
                when {
                    isHangComplete -> {
                        completed = closeExcursion(timestamp)
                        counted = completed?.outcome == MuscleUpExcursionOutcome.COMPLETED
                        phase = MuscleUpPhase.BELOW_BAR
                    }
                    isSupported -> phase = MuscleUpPhase.SUPPORTED
                }
            }
        }

        return MuscleUpFrameResult(
            timestampNs = timestamp,
            shoulderAboveWristFraction = measurement.shoulderAboveWristFraction,
            bodyScaleRatio = measurement.bodyScaleRatio,
            gripDriftTorsoLengths = measurement.gripDriftTorsoLengths,
            phase = phase,
            count = count,
            unresolvedExcursions = unresolvedExcursions,
            reason = MuscleUpReason.NONE,
            counted = counted,
            completedExcursion = completed,
        )
    }

    private fun closeExcursion(timestamp: Long): MuscleUpExcursion? {
        val left = leftHangAtNs ?: return null
        val support = supportObservedAtNs
        val outcome = if (support != null) {
            count++
            MuscleUpExcursionOutcome.COMPLETED
        } else {
            unresolvedExcursions++
            MuscleUpExcursionOutcome.SUPPORT_UNRESOLVED
        }
        val excursion = MuscleUpExcursion(left, support, timestamp, outcome, unusableFramesThisExcursion)
        leftHangAtNs = null
        supportObservedAtNs = null
        unusableFramesThisExcursion = 0
        clearSupportRun()
        seedSatisfiedHang(timestamp)
        return excursion
    }

    /**
     * Continuity was lost. An excursion already in progress is closed as unresolved rather than
     * silently discarded, so an interrupted attempt is still visible in the trace.
     */
    private fun invalidate() {
        if (leftHangAtNs != null && supportObservedAtNs == null) {
            unresolvedExcursions++
        } else if (leftHangAtNs != null && supportObservedAtNs != null) {
            // Support was observed but the return was never seen: the cycle end is unknown, so it
            // is not counted as a completed repetition.
            unresolvedExcursions++
        }
        leftHangAtNs = null
        supportObservedAtNs = null
        unusableFramesThisExcursion = 0
        phase = MuscleUpPhase.UNCERTAIN
        clearHangRun()
        clearSupportRun()
        pendingReference.clear()
    }

    private fun result(
        timestamp: Long,
        fraction: Double?,
        bodyScaleRatio: Double?,
        gripDrift: Double?,
        reason: MuscleUpReason,
    ) = MuscleUpFrameResult(
        timestampNs = timestamp,
        shoulderAboveWristFraction = fraction,
        bodyScaleRatio = bodyScaleRatio,
        gripDriftTorsoLengths = gripDrift,
        phase = phase,
        count = count,
        unresolvedExcursions = unresolvedExcursions,
        reason = reason,
        counted = false,
        completedExcursion = null,
    )
}
