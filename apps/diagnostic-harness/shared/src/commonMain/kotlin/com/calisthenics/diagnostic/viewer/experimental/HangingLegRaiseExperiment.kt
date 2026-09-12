package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

enum class HangingLegRaiseMode { SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED }

data class HangingLegRaiseConfig(
    val mode: HangingLegRaiseMode,
    val side: ArmSide,
    val loweredHipMinDegrees: Double,
    val raisedHipMaxDegrees: Double,
    val minimumHipCloseDegrees: Double,
    val raisedKneeMinDegrees: Double,
    val hangingTrunkMinDegrees: Double,
    val minimumSupportRiseRatio: Double,
    val maximumSupportTravelRatio: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
) {
    init {
        require(loweredHipMinDegrees.isFinite() && loweredHipMinDegrees in 0.0..180.0)
        require(raisedHipMaxDegrees.isFinite() && raisedHipMaxDegrees in 0.0..180.0)
        require(raisedHipMaxDegrees < loweredHipMinDegrees)
        require(minimumHipCloseDegrees.isFinite() && minimumHipCloseDegrees in 0.0..180.0)
        require(raisedKneeMinDegrees.isFinite() && raisedKneeMinDegrees in 0.0..180.0)
        require(hangingTrunkMinDegrees.isFinite() && hangingTrunkMinDegrees in 0.0..90.0)
        require(minimumSupportRiseRatio.isFinite())
        require(maximumSupportTravelRatio.isFinite() && maximumSupportTravelRatio >= 0.0)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(maximumGapNs > 0)
        require(minimumEndpointSamples > 0)
    }
}

data class HangingLegRaisePoseFrame(val timestampNs: Long, val width: Int, val height: Int, val landmarks: List<Landmark>)

enum class HangingLegRaiseReason {
    NONE, NO_POSE, INVALID_DIMENSIONS, DUPLICATE_LANDMARK_INDEX, MISSING_LANDMARK,
    INVALID_LANDMARK, LOW_CONFIDENCE, ZERO_LENGTH_SEGMENT, INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP, BACKWARDS_TIMESTAMP, CAPTURE_GAP,
}

data class HangingLegRaiseMeasurement(
    val hipDegrees: Double?, val kneeDegrees: Double?, val trunkElevationDegrees: Double?,
    val supportRiseRatio: Double?, val reason: HangingLegRaiseReason,
)

fun measureHangingLegRaise(frame: HangingLegRaisePoseFrame, config: HangingLegRaiseConfig): HangingLegRaiseMeasurement {
    if (frame.width <= 0 || frame.height <= 0) return rejected(HangingLegRaiseReason.INVALID_DIMENSIONS)
    if (frame.landmarks.isEmpty()) return rejected(HangingLegRaiseReason.NO_POSE)
    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    frame.landmarks.forEach { if (it.index < 0) return rejected(HangingLegRaiseReason.INVALID_LANDMARK) else if (byIndex.put(it.index, it) != null) return rejected(HangingLegRaiseReason.DUPLICATE_LANDMARK_INDEX) }
    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 15, 23, 25, 27)
        ArmSide.RIGHT -> intArrayOf(12, 16, 24, 26, 28)
    }
    val joints = indices.map { byIndex[it] ?: return rejected(HangingLegRaiseReason.MISSING_LANDMARK) }
    if (joints.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return rejected(HangingLegRaiseReason.INVALID_LANDMARK)
    if (joints.any {
            val visibility = it.visibility
            val presence = it.presence
            visibility == null || presence == null || !visibility.isFinite() || !presence.isFinite() ||
                visibility !in 0.0..1.0 || presence !in 0.0..1.0 ||
                visibility < config.minimumConfidence || presence < config.minimumConfidence
        }) return rejected(HangingLegRaiseReason.LOW_CONFIDENCE)
    val shoulder = joints[0]; val wrist = joints[1]; val hip = joints[2]; val knee = joints[3]; val ankle = joints[4]
    fun dx(a: Landmark, b: Landmark) = (a.x - b.x) * frame.width
    fun dy(a: Landmark, b: Landmark) = (a.y - b.y) * frame.height
    val trunkX = dx(shoulder, hip); val trunkY = dy(shoulder, hip)
    val thighX = dx(knee, hip); val thighY = dy(knee, hip)
    val shankX = dx(ankle, knee); val shankY = dy(ankle, knee)
    val trunkLength = sqrt(trunkX * trunkX + trunkY * trunkY)
    val thighLength = sqrt(thighX * thighX + thighY * thighY)
    val shankLength = sqrt(shankX * shankX + shankY * shankY)
    if (trunkLength == 0.0 || thighLength == 0.0 || shankLength == 0.0) return rejected(HangingLegRaiseReason.ZERO_LENGTH_SEGMENT)
    fun angle(ax: Double, ay: Double, bx: Double, by: Double, al: Double, bl: Double) = acos(((ax * bx + ay * by) / (al * bl)).coerceIn(-1.0, 1.0)) * 180.0 / kotlin.math.PI
    val hipDegrees = angle(trunkX, trunkY, thighX, thighY, trunkLength, thighLength)
    val kneeDegrees = angle(-thighX, -thighY, shankX, shankY, thighLength, shankLength)
    val trunkElevation = atan2(abs(trunkY), abs(trunkX)) * 180.0 / kotlin.math.PI
    val supportRise = ((shoulder.y - wrist.y) * frame.height) / trunkLength
    return HangingLegRaiseMeasurement(hipDegrees, kneeDegrees, trunkElevation, supportRise, HangingLegRaiseReason.NONE)
}

private fun rejected(reason: HangingLegRaiseReason) = HangingLegRaiseMeasurement(null, null, null, null, reason)

enum class HangingLegRaisePhase { WAITING, LOWERED, RAISING, RAISED, LOWERING, UNCERTAIN }
enum class HangingLegRaiseCycleOutcome { COUNTED, ABANDONED, SUPPORT_TRAVEL_REJECTED, KNEE_FLEXION_REJECTED, TRACKING_INTERRUPTED, CENSORED_AT_END_OF_INPUT }

data class HangingLegRaiseCycleEvent(
    val departureTimestampNs: Long, val raisedTimestampNs: Long?, val returnTimestampNs: Long?,
    val outcome: HangingLegRaiseCycleOutcome, val minimumHipDegrees: Double, val observedHipCloseDegrees: Double,
)

data class HangingLegRaiseFrameResult(
    val timestampNs: Long, val hipDegrees: Double?, val kneeDegrees: Double?, val trunkElevationDegrees: Double?,
    val supportRiseRatio: Double?, val phase: HangingLegRaisePhase, val count: Int,
    val reason: HangingLegRaiseReason, val counted: Boolean, val hanging: Boolean,
    val closedCycle: HangingLegRaiseCycleEvent?,
)

class HangingLegRaiseExperiment(private val config: HangingLegRaiseConfig) {
    var phase = HangingLegRaisePhase.WAITING; private set
    var count = 0; private set
    private var lastTimestamp: Long? = null
    private var endpointSamples = 0
    private var endpointStart: Long? = null
    private var loweredMinimumHip: Double? = null
    private var loweredSupportMin: Double? = null
    private var loweredSupportMax: Double? = null
    private var candidateDeparture: Long? = null
    private var candidateRaised: Long? = null
    private var candidateAnchor = 0.0
    private var candidateMinHip = 0.0

    fun onFrame(frame: HangingLegRaisePoseFrame): HangingLegRaiseFrameResult {
        val t = frame.timestampNs
        if (t < 0) return result(t, null, null, null, null, HangingLegRaiseReason.INVALID_TIMESTAMP, false, false, interrupt())
        lastTimestamp?.let { previous ->
            if (t == previous) return result(t, null, null, null, null, HangingLegRaiseReason.DUPLICATE_TIMESTAMP, false, false, interrupt())
            if (t < previous) return result(t, null, null, null, null, HangingLegRaiseReason.BACKWARDS_TIMESTAMP, false, false, interrupt())
        }
        val gap = lastTimestamp?.let { t - it > config.maximumGapNs } ?: false
        lastTimestamp = t
        val m = measureHangingLegRaise(frame, config)
        if (m.hipDegrees == null) return result(t, null, null, null, null, m.reason, false, false, interrupt())
        val interrupted = if (gap) interrupt() else null
        val advanced = advance(t, m.hipDegrees, m.kneeDegrees!!, m.trunkElevationDegrees!!, m.supportRiseRatio!!)
        val hanging = m.trunkElevationDegrees >= config.hangingTrunkMinDegrees &&
            m.supportRiseRatio >= config.minimumSupportRiseRatio
        return result(t, m.hipDegrees, m.kneeDegrees, m.trunkElevationDegrees, m.supportRiseRatio, if (gap) HangingLegRaiseReason.CAPTURE_GAP else HangingLegRaiseReason.NONE, advanced.first, hanging, interrupted ?: advanced.second)
    }

    private fun advance(t: Long, hip: Double, knee: Double, trunk: Double, support: Double): Pair<Boolean, HangingLegRaiseCycleEvent?> {
        val hanging = trunk >= config.hangingTrunkMinDegrees && support >= config.minimumSupportRiseRatio
        val lowered = hanging && hip >= config.loweredHipMinDegrees
        val anchor = loweredMinimumHip
        val raised = hanging && hip <= config.raisedHipMaxDegrees && knee >= config.raisedKneeMinDegrees && anchor != null && anchor - hip >= config.minimumHipCloseDegrees
        if (candidateDeparture != null) candidateMinHip = minOf(candidateMinHip, hip)
        if (phase == HangingLegRaisePhase.UNCERTAIN) phase = HangingLegRaisePhase.WAITING
        when (phase) {
            HangingLegRaisePhase.WAITING, HangingLegRaisePhase.UNCERTAIN -> if (lowered) { noteLowered(t, hip, support); if (endpointSamples >= config.minimumEndpointSamples) confirmLowered() } else clearLowered()
            HangingLegRaisePhase.LOWERED -> when {
                lowered -> noteLowered(t, hip, support)
                else -> {
                    openCandidate(t, hip, requireNotNull(anchor))
                    if (hanging && hip <= config.raisedHipMaxDegrees) return closeAndReset(HangingLegRaiseCycleOutcome.ABANDONED, null)
                    phase = HangingLegRaisePhase.RAISING; clearRun()
                }
            }
            HangingLegRaisePhase.RAISING -> when {
                !hanging -> clearRun()
                hip <= config.raisedHipMaxDegrees && support < (loweredSupportMin!! - config.maximumSupportTravelRatio) || hip <= config.raisedHipMaxDegrees && support > (loweredSupportMax!! + config.maximumSupportTravelRatio) -> return closeAndReset(HangingLegRaiseCycleOutcome.SUPPORT_TRAVEL_REJECTED, null)
                hip <= config.raisedHipMaxDegrees && knee < config.raisedKneeMinDegrees -> return closeAndReset(HangingLegRaiseCycleOutcome.KNEE_FLEXION_REJECTED, null)
                lowered -> return abandonAndRestart(t, hip, support)
                raised -> { if (endpointSamples == 0) endpointStart = t; endpointSamples++; if (endpointSamples >= config.minimumEndpointSamples) { candidateRaised = endpointStart; phase = HangingLegRaisePhase.RAISED; clearRun() } }
                else -> clearRun()
            }
            HangingLegRaisePhase.RAISED -> when {
                raised -> Unit
                lowered -> return closeAndReset(HangingLegRaiseCycleOutcome.ABANDONED, null)
                else -> { phase = HangingLegRaisePhase.LOWERING; clearRun() }
            }
            HangingLegRaisePhase.LOWERING -> when {
                lowered -> { noteRun(t); if (endpointSamples >= config.minimumEndpointSamples) { val e = closeCandidate(HangingLegRaiseCycleOutcome.COUNTED, endpointStart); count++; confirmLowered(); return true to e } }
                raised -> { phase = HangingLegRaisePhase.RAISED; clearRun() }
                else -> clearRun()
            }
        }
        return false to null
    }

    private fun noteLowered(t: Long, hip: Double, support: Double) { if (endpointSamples == 0) { endpointStart = t; loweredMinimumHip = hip; loweredSupportMin = support; loweredSupportMax = support } else { loweredMinimumHip = minOf(loweredMinimumHip ?: hip, hip); loweredSupportMin = minOf(loweredSupportMin ?: support, support); loweredSupportMax = maxOf(loweredSupportMax ?: support, support) }; endpointSamples++ }
    private fun confirmLowered() { phase = HangingLegRaisePhase.LOWERED; clearRun() }
    private fun clearRun() { endpointSamples = 0; endpointStart = null }
    private fun clearLowered() { clearRun(); loweredMinimumHip = null; loweredSupportMin = null; loweredSupportMax = null }
    private fun openCandidate(t: Long, hip: Double, anchor: Double) { candidateDeparture = t; candidateRaised = null; candidateAnchor = anchor; candidateMinHip = hip }
    private fun closeCandidate(outcome: HangingLegRaiseCycleOutcome, returnNs: Long?) = HangingLegRaiseCycleEvent(requireNotNull(candidateDeparture), candidateRaised, returnNs, outcome, candidateMinHip, candidateAnchor - candidateMinHip).also { candidateDeparture = null; candidateRaised = null }
    private fun closeAndReset(outcome: HangingLegRaiseCycleOutcome, returnNs: Long?): Pair<Boolean, HangingLegRaiseCycleEvent?> { val e = closeCandidate(outcome, returnNs); phase = HangingLegRaisePhase.WAITING; clearLowered(); return false to e }
    private fun abandonAndRestart(t: Long, hip: Double, support: Double): Pair<Boolean, HangingLegRaiseCycleEvent?> { val e = closeCandidate(HangingLegRaiseCycleOutcome.ABANDONED, null); phase = HangingLegRaisePhase.WAITING; clearLowered(); noteLowered(t, hip, support); if (endpointSamples >= config.minimumEndpointSamples) confirmLowered(); return false to e }
    private fun noteRun(t: Long) { if (endpointSamples == 0) endpointStart = t; endpointSamples++ }
    private fun interrupt(): HangingLegRaiseCycleEvent? { val e = if (candidateDeparture != null) closeCandidate(HangingLegRaiseCycleOutcome.TRACKING_INTERRUPTED, null) else null; phase = HangingLegRaisePhase.UNCERTAIN; clearLowered(); return e }
    private fun result(t: Long, hip: Double?, knee: Double?, trunk: Double?, support: Double?, reason: HangingLegRaiseReason, counted: Boolean, hanging: Boolean, event: HangingLegRaiseCycleEvent?) = HangingLegRaiseFrameResult(t, hip, knee, trunk, support, phase, count, reason, counted, hanging, event)
    fun finish(): HangingLegRaiseCycleEvent? = if (candidateDeparture != null) closeCandidate(HangingLegRaiseCycleOutcome.CENSORED_AT_END_OF_INPUT, null) else null
}
