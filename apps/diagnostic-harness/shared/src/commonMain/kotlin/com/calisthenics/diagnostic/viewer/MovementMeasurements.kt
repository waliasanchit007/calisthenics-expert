package com.calisthenics.diagnostic.viewer

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI

enum class ArmJoint { SHOULDER, ELBOW, WRIST }
enum class JointIssueReason { MISSING, LOW_CONFIDENCE, INVALID_POSITION, OUT_OF_FRAME }

data class ArmTrackingIssue(val joint: ArmJoint, val reason: JointIssueReason) {
    fun guidance(side: String): String {
        val name = "$side ${joint.name.lowercase()}"
        return when (reason) {
            JointIssueReason.MISSING -> "$name is missing from tracking. Keep it visible"
            JointIssueReason.LOW_CONFIDENCE -> "$name tracking confidence is low. Keep it visible and well lit"
            JointIssueReason.INVALID_POSITION -> "$name position is invalid. Wait for reliable tracking"
            JointIssueReason.OUT_OF_FRAME -> "$name is outside the camera image. Move back or tilt the camera to include your hands and bar"
        }
    }
}

data class ArmMeasurements(
    val elbowDegrees: Double? = null,
    val wristHeightFraction: Double? = null,
    val minimumConfidence: Double? = null,
    val trackingIssue: ArmTrackingIssue? = null,
)

/** Image-plane observations, not anatomical angles, canonical poses or exercise events. */
data class MovementMeasurements(
    val capturedNs: Long,
    val left: ArmMeasurements,
    val right: ArmMeasurements,
    val availableArmJoints: Int,
)

fun measureMovement(landmarks: List<Landmark>, width: Int, height: Int, capturedNs: Long): MovementMeasurements {
    val geometry = ViewerGeometry(width, height)
    val grouped = landmarks.groupBy { it.index }
    val issues = mutableMapOf<Int, JointIssueReason>()
    val points = (11..16).mapNotNull { index ->
        fun reject(reason: JointIssueReason): Nothing? { issues[index] = reason; return null }
        val landmark = grouped[index]?.singleOrNull() ?: return@mapNotNull reject(JointIssueReason.MISSING)
        val scores = listOf(landmark.visibility, landmark.presence)
        // Exploratory measurement availability, not a validated tracking-quality classification.
        if (scores.any { it == null || !it.isFinite() || it !in .5..1.0 }) return@mapNotNull reject(JointIssueReason.LOW_CONFIDENCE)
        if (!landmark.x.isFinite() || !landmark.y.isFinite()) return@mapNotNull reject(JointIssueReason.INVALID_POSITION)
        val p = geometry.modelToSource(landmark.x, landmark.y) ?: return@mapNotNull reject(JointIssueReason.OUT_OF_FRAME)
        index to Triple(p.x.toDouble() * width, p.y.toDouble() * height, scores.filterNotNull().min())
    }.toMap()
    fun arm(shoulder: Int, elbow: Int, wrist: Int): ArmMeasurements {
        for ((index,joint) in listOf(shoulder to ArmJoint.SHOULDER, elbow to ArmJoint.ELBOW, wrist to ArmJoint.WRIST)) {
            issues[index]?.let { return ArmMeasurements(trackingIssue=ArmTrackingIssue(joint,it)) }
        }
        val s = points[shoulder] ?: return ArmMeasurements()
        val e = points[elbow] ?: return ArmMeasurements()
        val w = points[wrist] ?: return ArmMeasurements()
        val ax = s.first - e.first; val ay = s.second - e.second
        val bx = w.first - e.first; val by = w.second - e.second
        val angle = if ((ax == 0.0 && ay == 0.0) || (bx == 0.0 && by == 0.0)) null
            else atan2(abs(ax * by - ay * bx), ax * bx + ay * by) * 180.0 / PI
        return ArmMeasurements(angle, (s.second - w.second) / height, minOf(s.third, e.third, w.third))
    }
    return MovementMeasurements(capturedNs, arm(11,13,15), arm(12,14,16), points.size)
}
