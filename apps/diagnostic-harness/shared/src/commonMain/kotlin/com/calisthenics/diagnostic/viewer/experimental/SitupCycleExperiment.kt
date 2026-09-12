package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Explicit prototype parameters for a single-side sit-up-family candidate cycle. They are an
 * initial geometric hypothesis, not validated anatomical, ROM or form thresholds, and they were
 * frozen before any replay.
 *
 * The reclined and raised bands each require BOTH a hip-angle band and a trunk-elevation band, and
 * the raised band additionally requires a rise relative to the reclined anchor. Requiring trunk
 * motion is what keeps a standing hip hinge and a lying leg-only tuck from reading as a sit-up:
 * either can move the hip angle across both bands on its own. It remains an explicit limitation
 * that this measurement cannot establish ground contact and cannot reject every sit-down/stand-up
 * transition, and that neither the unsigned elevation nor a difference of two unsigned elevations
 * is camera-roll invariant. Only the configured side is measured; the far side is never required
 * or fabricated, and there is no side switching or practice calibration.
 */
data class SitupConfig(
    val side: ArmSide,
    val reclinedHipMinDegrees: Double,
    val raisedHipMaxDegrees: Double,
    val reclinedTrunkMaxDegrees: Double,
    val raisedTrunkMinDegrees: Double,
    val minimumTrunkRiseDegrees: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
) {
    init {
        require(reclinedHipMinDegrees.isFinite() && reclinedHipMinDegrees in 0.0..180.0) {
            "reclined_hip_min_degrees must be finite and in [0, 180]"
        }
        require(raisedHipMaxDegrees.isFinite() && raisedHipMaxDegrees in 0.0..180.0) {
            "raised_hip_max_degrees must be finite and in [0, 180]"
        }
        require(raisedHipMaxDegrees < reclinedHipMinDegrees) {
            "raised_hip_max_degrees must be less than reclined_hip_min_degrees"
        }
        require(reclinedTrunkMaxDegrees.isFinite() && reclinedTrunkMaxDegrees in 0.0..90.0) {
            "reclined_trunk_max_degrees must be finite and in [0, 90]"
        }
        require(raisedTrunkMinDegrees.isFinite() && raisedTrunkMinDegrees in 0.0..90.0) {
            "raised_trunk_min_degrees must be finite and in [0, 90]"
        }
        require(reclinedTrunkMaxDegrees < raisedTrunkMinDegrees) {
            "reclined_trunk_max_degrees must be less than raised_trunk_min_degrees"
        }
        require(minimumTrunkRiseDegrees.isFinite() && minimumTrunkRiseDegrees in 0.0..90.0) {
            "minimum_trunk_rise_degrees must be finite and in [0, 90]"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEndpointSamples > 0) { "minimum_endpoint_samples must be positive" }
    }
}

data class SitupPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class SitupReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    ZERO_LENGTH_SEGMENT,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
    SIDE_ADMISSION,
}

/** Both measurements are present together or absent together with a typed rejection reason. */
data class SitupTrunkMeasurement(
    val hipDegrees: Double?,
    val torsoElevationDegrees: Double?,
    val reason: SitupReason,
)

/**
 * Pure image-plane shoulder-hip-knee measurement in upright source-normalized coordinates:
 * the hip angle, plus the unsigned elevation of the hip-to-shoulder trunk vector above image
 * horizontal. Source-normalized x/y are projected into source pixels first, preserving the
 * image's pixel aspect ratio.
 */
fun measureSitupTrunk(frame: SitupPoseFrame, config: SitupConfig): SitupTrunkMeasurement {
    if (frame.width <= 0 || frame.height <= 0) return rejected(SitupReason.INVALID_DIMENSIONS)
    if (frame.landmarks.isEmpty()) return rejected(SitupReason.NO_POSE)

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return rejected(SitupReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) {
            return rejected(SitupReason.DUPLICATE_LANDMARK_INDEX)
        }
    }

    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 23, 25)
        ArmSide.RIGHT -> intArrayOf(12, 24, 26)
    }
    val joints = indices.map { index -> byIndex[index] ?: return rejected(SitupReason.MISSING_LANDMARK) }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return rejected(SitupReason.INVALID_LANDMARK)
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
        return rejected(SitupReason.LOW_CONFIDENCE)
    }

    val shoulder = joints[0]
    val hip = joints[1]
    val knee = joints[2]
    val trunkX = (shoulder.x - hip.x) * frame.width.toDouble()
    val trunkY = (shoulder.y - hip.y) * frame.height.toDouble()
    val thighX = (knee.x - hip.x) * frame.width.toDouble()
    val thighY = (knee.y - hip.y) * frame.height.toDouble()
    val trunkLength = sqrt(trunkX * trunkX + trunkY * trunkY)
    val thighLength = sqrt(thighX * thighX + thighY * thighY)
    if (trunkLength == 0.0 || thighLength == 0.0) return rejected(SitupReason.ZERO_LENGTH_SEGMENT)

    val cosine = ((trunkX * thighX + trunkY * thighY) / (trunkLength * thighLength)).coerceIn(-1.0, 1.0)
    // Unsigned elevation above image horizontal: a reclined trunk lies across the frame, a raised
    // trunk points away from it. Sign is deliberately dropped so a subject facing either way reads
    // the same; this is NOT a roll-invariant quantity and is not claimed to be.
    val elevation = atan2(abs(trunkY), abs(trunkX)) * 180.0 / kotlin.math.PI
    return SitupTrunkMeasurement(acos(cosine) * 180.0 / kotlin.math.PI, elevation, SitupReason.NONE)
}

private fun rejected(reason: SitupReason) = SitupTrunkMeasurement(null, null, reason)

enum class SitupPhase { WAITING, RECLINED, RISING, RAISED, LOWERING, UNCERTAIN }

enum class SitupCycleOutcome { COUNTED, ABANDONED, TRACKING_INTERRUPTED, CENSORED_AT_END_OF_INPUT }

/**
 * A closed candidate cycle. `departureTimestampNs` is the first sample that left the confirmed
 * reclined dwell; `raisedTimestampNs` and `returnTimestampNs` are the first samples of the
 * consecutive runs that later confirmed the raised and returning reclined endpoints, and stay null
 * while that endpoint was never confirmed. All three are observed sample times: no endpoint time is
 * ever interpolated or invented.
 */
data class SitupCycleEvent(
    val departureTimestampNs: Long,
    val raisedTimestampNs: Long?,
    val returnTimestampNs: Long?,
    val outcome: SitupCycleOutcome,
    val minimumHipDegrees: Double,
    val observedTrunkRiseDegrees: Double,
)

data class SitupFrameResult(
    val timestampNs: Long,
    val hipDegrees: Double?,
    val torsoElevationDegrees: Double?,
    val phase: SitupPhase,
    val count: Int,
    val reason: SitupReason,
    val counted: Boolean,
    val closedCycle: SitupCycleEvent?,
)

/**
 * Conservative, explicit-config candidate cycle experiment for one configured side. It counts a
 * continuously observed reclined -> raised trunk -> reclined sequence with consecutive endpoint
 * support; it does not recognise the exercise automatically and does not label form-valid reps.
 *
 * A direct endpoint jump (reclined straight to raised, or raised straight to reclined) is not a
 * candidate cycle. An unusable observation or invalid/gapped timing discards the unfinished
 * candidate; counts already emitted survive. Fixed endpoint-sample counts are cadence-sensitive
 * rather than time-normalized, which is an accepted limitation of this baseline.
 */
class SitupCycleExperiment(private val config: SitupConfig) {
    var phase: SitupPhase = SitupPhase.WAITING
        private set
    var count: Int = 0
        private set

    private var lastTimestampNs: Long? = null
    private var endpointSamples = 0
    private var endpointRunStartNs: Long? = null
    private var reclinedDwellMaxTrunk: Double? = null
    private var anchorTrunkDegrees: Double? = null

    private var candidateDepartureNs: Long? = null
    private var candidateRaisedNs: Long? = null
    private var candidateAnchorTrunk = 0.0
    private var candidateMinHip = 0.0
    private var candidateMaxTrunk = 0.0

    fun onFrame(frame: SitupPoseFrame): SitupFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            return result(timestamp, null, null, SitupReason.INVALID_TIMESTAMP, false, interrupt())
        }
        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                return result(timestamp, null, null, SitupReason.DUPLICATE_TIMESTAMP, false, interrupt())
            }
            if (timestamp < previous) {
                return result(timestamp, null, null, SitupReason.BACKWARDS_TIMESTAMP, false, interrupt())
            }
        }
        val gap = previous != null && timestamp - previous > config.maximumGapNs
        lastTimestampNs = timestamp

        val measurement = measureSitupTrunk(frame, config)
        val hip = measurement.hipDegrees
        val trunk = measurement.torsoElevationDegrees
        if (hip == null || trunk == null) {
            return result(timestamp, null, null, measurement.reason, false, interrupt())
        }

        // An unobserved span may not be stitched across: the candidate dies before this sample is
        // allowed to contribute anything to it.
        val interrupted = if (gap) interrupt() else null
        val advanced = advance(timestamp, hip, trunk)
        return result(
            timestamp = timestamp,
            hipDegrees = hip,
            torsoElevationDegrees = trunk,
            reason = if (gap) SitupReason.CAPTURE_GAP else SitupReason.NONE,
            counted = advanced.counted,
            closedCycle = interrupted ?: advanced.closedCycle,
        )
    }

    private class Advance(val counted: Boolean, val closedCycle: SitupCycleEvent?)

    private fun advance(timestamp: Long, hip: Double, trunk: Double): Advance {
        val isReclined = hip >= config.reclinedHipMinDegrees && trunk <= config.reclinedTrunkMaxDegrees
        val anchor = anchorTrunkDegrees
        // The raised endpoint needs the closed hip band, a high trunk elevation AND a rise relative
        // to the reclined anchor, so a pose that is merely upright cannot satisfy it.
        val isRaised = hip <= config.raisedHipMaxDegrees &&
            trunk >= config.raisedTrunkMinDegrees &&
            anchor != null &&
            trunk - anchor >= config.minimumTrunkRiseDegrees

        if (candidateDepartureNs != null) {
            candidateMinHip = minOf(candidateMinHip, hip)
            candidateMaxTrunk = maxOf(candidateMaxTrunk, trunk)
        }
        if (phase == SitupPhase.UNCERTAIN) phase = SitupPhase.WAITING

        when (phase) {
            SitupPhase.WAITING, SitupPhase.UNCERTAIN -> {
                if (isReclined) {
                    if (noteReclinedSample(timestamp, trunk)) confirmReclined()
                } else {
                    clearReclinedEvidence()
                }
            }
            SitupPhase.RECLINED -> {
                if (isReclined) {
                    reclinedDwellMaxTrunk = maxOf(reclinedDwellMaxTrunk ?: trunk, trunk)
                    anchorTrunkDegrees = reclinedDwellMaxTrunk
                } else {
                    openCandidate(timestamp, hip, trunk, requireNotNull(anchor))
                    if (isRaised) {
                        // Reclined straight to a confirmed raised band: unsupported endpoint jump.
                        val closed = closeCandidate(SitupCycleOutcome.ABANDONED, null)
                        phase = SitupPhase.WAITING
                        clearReclinedEvidence()
                        return Advance(false, closed)
                    }
                    phase = SitupPhase.RISING
                    endpointSamples = 0
                    endpointRunStartNs = null
                }
            }
            SitupPhase.RISING -> {
                when {
                    isRaised -> {
                        if (endpointSamples == 0) endpointRunStartNs = timestamp
                        endpointSamples++
                        if (endpointSamples >= config.minimumEndpointSamples) {
                            candidateRaisedNs = endpointRunStartNs
                            phase = SitupPhase.RAISED
                            endpointSamples = 0
                            endpointRunStartNs = null
                        }
                    }
                    isReclined -> return abandonAndRestart(timestamp, trunk)
                    else -> {
                        endpointSamples = 0
                        endpointRunStartNs = null
                    }
                }
            }
            SitupPhase.RAISED -> {
                when {
                    isRaised -> Unit
                    // Raised straight back to a confirmed reclined band: unsupported endpoint jump.
                    isReclined -> return abandonAndRestart(timestamp, trunk)
                    else -> {
                        phase = SitupPhase.LOWERING
                        endpointSamples = 0
                        endpointRunStartNs = null
                    }
                }
            }
            SitupPhase.LOWERING -> {
                when {
                    isReclined -> {
                        if (noteReclinedSample(timestamp, trunk)) {
                            val returnNs = endpointRunStartNs
                            count++
                            val closed = closeCandidate(SitupCycleOutcome.COUNTED, returnNs)
                            confirmReclined()
                            return Advance(true, closed)
                        }
                    }
                    // The subject rose again before the return was confirmed; the same candidate
                    // stays open and its already-confirmed raised endpoint is not re-litigated.
                    isRaised -> {
                        phase = SitupPhase.RAISED
                        clearReclinedRun()
                    }
                    else -> clearReclinedRun()
                }
            }
        }
        return Advance(false, null)
    }

    private fun abandonAndRestart(timestamp: Long, trunk: Double): Advance {
        val closed = closeCandidate(SitupCycleOutcome.ABANDONED, null)
        phase = SitupPhase.WAITING
        clearReclinedEvidence()
        if (noteReclinedSample(timestamp, trunk)) confirmReclined()
        return Advance(false, closed)
    }

    /** Accumulates one reclined sample of the current consecutive run; true once it is supported. */
    private fun noteReclinedSample(timestamp: Long, trunk: Double): Boolean {
        if (endpointSamples == 0) {
            endpointRunStartNs = timestamp
            reclinedDwellMaxTrunk = trunk
        } else {
            reclinedDwellMaxTrunk = maxOf(reclinedDwellMaxTrunk ?: trunk, trunk)
        }
        endpointSamples++
        return endpointSamples >= config.minimumEndpointSamples
    }

    /**
     * The anchor is the HIGHEST trunk elevation seen in the reclined dwell, so the later relative
     * rise is measured against the most conservative reclined reading rather than a favourable one.
     */
    private fun confirmReclined() {
        phase = SitupPhase.RECLINED
        anchorTrunkDegrees = reclinedDwellMaxTrunk
        endpointSamples = 0
        endpointRunStartNs = null
    }

    private fun clearReclinedRun() {
        endpointSamples = 0
        endpointRunStartNs = null
    }

    private fun clearReclinedEvidence() {
        clearReclinedRun()
        reclinedDwellMaxTrunk = null
        anchorTrunkDegrees = null
    }

    private fun openCandidate(timestamp: Long, hip: Double, trunk: Double, anchor: Double) {
        candidateDepartureNs = timestamp
        candidateRaisedNs = null
        candidateAnchorTrunk = anchor
        candidateMinHip = hip
        candidateMaxTrunk = trunk
    }

    private fun closeCandidate(outcome: SitupCycleOutcome, returnNs: Long?): SitupCycleEvent {
        val event = SitupCycleEvent(
            departureTimestampNs = requireNotNull(candidateDepartureNs),
            raisedTimestampNs = candidateRaisedNs,
            returnTimestampNs = returnNs,
            outcome = outcome,
            minimumHipDegrees = candidateMinHip,
            observedTrunkRiseDegrees = candidateMaxTrunk - candidateAnchorTrunk,
        )
        candidateDepartureNs = null
        candidateRaisedNs = null
        return event
    }

    /** Discards every piece of unfinished evidence; emitted counts are untouched. */
    private fun interrupt(): SitupCycleEvent? {
        val closed = if (candidateDepartureNs != null) {
            closeCandidate(SitupCycleOutcome.TRACKING_INTERRUPTED, null)
        } else {
            null
        }
        phase = SitupPhase.UNCERTAIN
        clearReclinedEvidence()
        return closed
    }

    private fun result(
        timestamp: Long,
        hipDegrees: Double?,
        torsoElevationDegrees: Double?,
        reason: SitupReason,
        counted: Boolean,
        closedCycle: SitupCycleEvent?,
    ) = SitupFrameResult(timestamp, hipDegrees, torsoElevationDegrees, phase, count, reason, counted, closedCycle)

    /**
     * Call once after the last frame. An open candidate is reported as explicitly censored at the
     * endpoints actually observed — never completed, never silently dropped — and only once; a
     * second call reports nothing because no candidate remains open.
     */
    fun finish(): SitupCycleEvent? =
        if (candidateDepartureNs != null) {
            closeCandidate(SitupCycleOutcome.CENSORED_AT_END_OF_INPUT, null)
        } else {
            null
        }
}


/**
 * Chooses an eligible side once from ONLY the declared-window frames, using tracking validity
 * alone (never a rep count or any frame outside the window). Both sides eligible keeps
 * `base.side` as a tie preference; one side eligible returns it; neither returns null. Malformed
 * temporal/dimension sequences are rejected via `require`, not silently tolerated.
 */
fun selectSitupSide(frames: List<SitupPoseFrame>, base: SitupConfig): ArmSide? {
    val minimumSamples = maxOf(2, base.minimumEndpointSamples)
    require(frames.size >= minimumSamples) {
        "selectSitupSide requires at least $minimumSamples declared-window observations"
    }
    require(frames.first().timestampNs >= 0L) {
        "selectSitupSide requires a nonnegative first timestamp"
    }
    val width = frames.first().width
    val height = frames.first().height
    require(width > 0 && height > 0) {
        "selectSitupSide requires positive frame dimensions"
    }
    require(frames.all { it.width == width && it.height == height }) {
        "selectSitupSide requires constant frame dimensions across the declared window"
    }
    for (index in 1 until frames.size) {
        val previous = frames[index - 1].timestampNs
        val current = frames[index].timestampNs
        require(current > previous) {
            "selectSitupSide requires strictly increasing timestamps"
        }
        require(current - previous <= base.maximumGapNs) {
            "selectSitupSide requires each gap to be within maximum_gap_ns"
        }
    }

    val leftEligible = isSitupSideEligible(frames, base, ArmSide.LEFT)
    val rightEligible = isSitupSideEligible(frames, base, ArmSide.RIGHT)
    return when {
        leftEligible && rightEligible -> base.side
        leftEligible -> ArmSide.LEFT
        rightEligible -> ArmSide.RIGHT
        else -> null
    }
}

private fun isSitupSideEligible(frames: List<SitupPoseFrame>, base: SitupConfig, side: ArmSide): Boolean {
    val config = base.copy(side = side)
    return frames.all { frame ->
        val measurement = measureSitupTrunk(frame, config)
        measurement.reason == SitupReason.NONE &&
            measurement.hipDegrees != null &&
            measurement.torsoElevationDegrees != null
    }
}
