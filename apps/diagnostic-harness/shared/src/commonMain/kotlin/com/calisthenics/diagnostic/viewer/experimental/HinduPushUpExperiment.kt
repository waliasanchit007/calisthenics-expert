package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.PI

/** Selected side-view image-plane hypothesis, not technique or force assessment. */
data class HinduConfig(
    val side: ArmSide,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEndpointSamples: Int,
    val minimumHipDeltaFraction: Double,
    val minimumEndpointElbowDegrees: Double,
    val minimumKneeDegrees: Double,
) {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(maximumGapNs > 0 && minimumEndpointSamples >= 2)
        require(minimumHipDeltaFraction.isFinite() && minimumHipDeltaFraction > 0 && minimumHipDeltaFraction < 1)
        require(minimumEndpointElbowDegrees.isFinite() && minimumEndpointElbowDegrees in 0.0..180.0)
        require(minimumKneeDegrees.isFinite() && minimumKneeDegrees in 0.0..180.0)
    }
}

data class HinduPoseFrame(val timestampNs: Long, val width: Int, val height: Int, val landmarks: List<Landmark>)
data class HinduResult(
    val timestampNs: Long, val count: Int, val counted: Boolean, val phase: String,
    val reason: String, val hipDeltaFraction: Double?, val elbowDegrees: Double?,
    /** When [counted], the instant the final endpoint was first satisfied, not the confirming frame. */
    val eventTimestampNs: Long? = null,
)

class HinduPushUpExperiment(private val config: HinduConfig) {
    // Reuse established two-endpoint sequencing, but feed high/low hip support observations.
    private val timing = ElbowCycleTiming(config.minimumEndpointSamples)
    private var previous: Long? = null
    val count: Int get() = timing.count

    fun onFrame(frame: HinduPoseFrame): HinduResult {
        fun reject(reason: String): HinduResult {
            timing.invalidate()
            return result(frame.timestampNs, false, reason, null, null)
        }
        val old = previous
        if (frame.timestampNs < 0 || (old != null && frame.timestampNs <= old)) return reject("INVALID_TIMESTAMP")
        previous = frame.timestampNs
        if (old != null && frame.timestampNs - old > config.maximumGapNs) return reject("CAPTURE_GAP")
        if (frame.width <= 0 || frame.height <= 0) return reject("UNAVAILABLE")
        val byIndex = frame.landmarks.associateBy { it.index }
        if (byIndex.size != frame.landmarks.size) return reject("UNAVAILABLE")
        val ids = if (config.side == ArmSide.LEFT) listOf(11,13,15,23,25,27) else listOf(12,14,16,24,26,28)
        val joints = ids.map { byIndex[it] ?: return reject("UNAVAILABLE") }
        if (joints.any {
            !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() ||
                it.x !in 0.0..1.0 || it.y !in 0.0..1.0 ||
                it.visibility == null || it.presence == null ||
                !it.visibility.isFinite() || !it.presence.isFinite() ||
                it.visibility !in config.minimumConfidence..1.0 || it.presence !in config.minimumConfidence..1.0
        }) return reject("UNAVAILABLE")
        fun angle(a: Landmark, b: Landmark, c: Landmark): Double? {
            val ax=(a.x-b.x)*frame.width; val ay=(a.y-b.y)*frame.height
            val cx=(c.x-b.x)*frame.width; val cy=(c.y-b.y)*frame.height
            val lengths=hypot(ax,ay)*hypot(cx,cy)
            if (lengths == 0.0) return null
            return acos(((ax*cx+ay*cy)/lengths).coerceIn(-1.0,1.0))*180.0/PI
        }
        val (shoulder, elbow, wrist, hip, knee) = joints
        val ankle = joints[5]
        val elbowAngle=angle(shoulder,elbow,wrist) ?: return reject("UNAVAILABLE")
        val kneeAngle=angle(hip,knee,ankle) ?: return reject("UNAVAILABLE")
        if (kneeAngle < config.minimumKneeDegrees) return reject("KNEE_FLEXED")
        if (wrist.y - shoulder.y < config.minimumHipDeltaFraction) return reject("WRIST_NOT_BELOW_SHOULDER")
        val delta=shoulder.y-hip.y
        val extended=elbowAngle >= config.minimumEndpointElbowDegrees
        val eventNs=timing.onObservation(
            timestampNs=frame.timestampNs,
            isSupported=extended && delta >= config.minimumHipDeltaFraction,
            isLowered=extended && delta <= -config.minimumHipDeltaFraction,
        )
        return result(frame.timestampNs,eventNs != null,"NONE",delta,elbowAngle,eventNs)
    }

    private fun result(time:Long,counted:Boolean,reason:String,delta:Double?,angle:Double?,eventNs:Long?=null): HinduResult {
        val phase=when(timing.phase) {
            ElbowCyclePhase.WAITING -> "WAITING"
            ElbowCyclePhase.SUPPORTED -> "HIGH_HIP"
            ElbowCyclePhase.LOWERING -> "TO_LOW_HIP"
            ElbowCyclePhase.LOWERED -> "LOW_HIP"
            ElbowCyclePhase.RAISING -> "RETURNING"
            ElbowCyclePhase.UNCERTAIN -> "UNCERTAIN"
        }
        return HinduResult(time,count,counted,phase,reason,delta,angle,eventNs)
    }
}
