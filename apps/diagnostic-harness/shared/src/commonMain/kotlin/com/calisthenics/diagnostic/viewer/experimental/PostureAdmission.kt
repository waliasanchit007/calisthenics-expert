package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Which held shape a selected-mode static hold must show. Selected, never recognised.
 *
 * HORIZONTAL_BODY delegates to the existing plank body-line measure (planche, front lever, back
 * lever). That measure cannot tell a planche from a floor plank — its own source says it does not
 * establish that the body is elevated — so identity comes from the operator's selection.
 */
enum class HoldPosture { HORIZONTAL_BODY, L_SIT, WALL_SIT }

/**
 * Explicit admission parameters. Every value for L_SIT and WALL_SIT was reasoned from ideal
 * side-view geometry and fixed BEFORE any label or detector output for those clips was seen; none
 * is a validated anatomical or form threshold.
 *
 * L_SIT: trunk roughly upright, hips flexed to about a right angle, knees nearly straight, legs
 * roughly horizontal, hands below the shoulders (pressing down on the support).
 * WALL_SIT: trunk roughly upright, thighs roughly horizontal, knees near a right angle.
 */
data class PostureAdmissionConfig(
    val posture: HoldPosture,
    val side: ArmSide,
    val minimumConfidence: Double,
    // HORIZONTAL_BODY (existing plank body-line measure)
    val straightMinDegrees: Double,
    val straightMaxDegrees: Double,
    val maximumOrientationDegreesFromHorizontal: Double,
    // L_SIT
    val lSitHipMinDegrees: Double,
    val lSitHipMaxDegrees: Double,
    val lSitKneeMinDegrees: Double,
    val lSitMaximumLegDegreesFromHorizontal: Double,
    // shared by L_SIT and WALL_SIT
    val maximumTrunkDegreesFromVertical: Double,
    // WALL_SIT
    val wallSitKneeMinDegrees: Double,
    val wallSitKneeMaxDegrees: Double,
    val wallSitMaximumThighDegreesFromHorizontal: Double,
) {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        listOf(
            straightMinDegrees, straightMaxDegrees, maximumOrientationDegreesFromHorizontal,
            lSitHipMinDegrees, lSitHipMaxDegrees, lSitKneeMinDegrees, lSitMaximumLegDegreesFromHorizontal,
            maximumTrunkDegreesFromVertical, wallSitKneeMinDegrees, wallSitKneeMaxDegrees,
            wallSitMaximumThighDegreesFromHorizontal,
        ).forEach { require(it.isFinite() && it in 0.0..180.0) { "posture angles must be finite and in [0, 180]" } }
        require(straightMinDegrees <= straightMaxDegrees)
        require(lSitHipMinDegrees < lSitHipMaxDegrees)
        require(wallSitKneeMinDegrees < wallSitKneeMaxDegrees)
    }
}

enum class PostureReason {
    NONE, NO_POSE, MISSING_LANDMARK, INVALID_LANDMARK, LOW_CONFIDENCE, ZERO_LENGTH_SEGMENT,
    BODY_LINE_NOT_ADMITTED, TRUNK_NOT_UPRIGHT, HIP_ANGLE_OUT_OF_BAND, KNEE_NOT_STRAIGHT,
    LEGS_NOT_HORIZONTAL, HANDS_NOT_BELOW_SHOULDERS, KNEE_ANGLE_OUT_OF_BAND, THIGH_NOT_HORIZONTAL,
}

data class PostureAdmission(
    val admitted: Boolean,
    val reason: PostureReason,
    val hipDegrees: Double? = null,
    val kneeDegrees: Double? = null,
    val trunkDegreesFromVertical: Double? = null,
)

private fun angleAt(a: Landmark, b: Landmark, c: Landmark, w: Double, h: Double): Double? {
    val ax = (a.x - b.x) * w
    val ay = (a.y - b.y) * h
    val cx = (c.x - b.x) * w
    val cy = (c.y - b.y) * h
    if (sqrt(ax * ax + ay * ay) == 0.0 || sqrt(cx * cx + cy * cy) == 0.0) return null
    return atan2(abs(ax * cy - ay * cx), ax * cx + ay * cy) * 180.0 / PI
}

/** Unsigned angle of the from->to segment away from image-horizontal, in [0, 90]. */
private fun degreesFromHorizontal(from: Landmark, to: Landmark, w: Double, h: Double): Double =
    atan2(abs((to.y - from.y) * h), abs((to.x - from.x) * w)) * 180.0 / PI

/** Pure per-frame posture admission for a selected-mode static hold. */
fun admitPosture(frame: StaticSkillPoseFrame, config: PostureAdmissionConfig): PostureAdmission {
    if (frame.landmarks.isEmpty()) return PostureAdmission(false, PostureReason.NO_POSE)
    if (config.posture == HoldPosture.HORIZONTAL_BODY) {
        val body = measurePlankBodyLine(
            PlankPoseFrame(frame.timestampNs, frame.width, frame.height, frame.landmarks),
            PlankConfig(
                config.side, config.straightMinDegrees, config.straightMaxDegrees, config.minimumConfidence,
                1L, 1, config.maximumOrientationDegreesFromHorizontal,
            ),
        )
        val angle = body.bodyLineDegrees
        val inBand = angle != null && angle in config.straightMinDegrees..config.straightMaxDegrees
        return PostureAdmission(inBand, if (inBand) PostureReason.NONE else PostureReason.BODY_LINE_NOT_ADMITTED)
    }

    val byIndex = frame.landmarks.associateBy { it.index }
    val idx = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 15, 23, 25, 27)
        ArmSide.RIGHT -> intArrayOf(12, 16, 24, 26, 28)
    }
    val joints = idx.map { byIndex[it] ?: return PostureAdmission(false, PostureReason.MISSING_LANDMARK) }
    if (joints.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) {
        return PostureAdmission(false, PostureReason.INVALID_LANDMARK)
    }
    if (joints.any { j ->
            val v = j.visibility
            val p = j.presence
            v == null || p == null || v < config.minimumConfidence || p < config.minimumConfidence
        }
    ) {
        return PostureAdmission(false, PostureReason.LOW_CONFIDENCE)
    }
    val (shoulder, wrist, hip, knee, ankle) = joints
    val w = frame.width.toDouble()
    val h = frame.height.toDouble()
    val hipDeg = angleAt(shoulder, hip, knee, w, h) ?: return PostureAdmission(false, PostureReason.ZERO_LENGTH_SEGMENT)
    val kneeDeg = angleAt(hip, knee, ankle, w, h) ?: return PostureAdmission(false, PostureReason.ZERO_LENGTH_SEGMENT)
    val trunkFromVertical = 90.0 - degreesFromHorizontal(hip, shoulder, w, h)

    fun no(reason: PostureReason) = PostureAdmission(false, reason, hipDeg, kneeDeg, trunkFromVertical)
    if (trunkFromVertical > config.maximumTrunkDegreesFromVertical) return no(PostureReason.TRUNK_NOT_UPRIGHT)

    when (config.posture) {
        HoldPosture.L_SIT -> {
            if (hipDeg !in config.lSitHipMinDegrees..config.lSitHipMaxDegrees) return no(PostureReason.HIP_ANGLE_OUT_OF_BAND)
            if (kneeDeg < config.lSitKneeMinDegrees) return no(PostureReason.KNEE_NOT_STRAIGHT)
            if (degreesFromHorizontal(hip, ankle, w, h) > config.lSitMaximumLegDegreesFromHorizontal) {
                return no(PostureReason.LEGS_NOT_HORIZONTAL)
            }
            if (wrist.y <= shoulder.y) return no(PostureReason.HANDS_NOT_BELOW_SHOULDERS)
        }
        HoldPosture.WALL_SIT -> {
            if (kneeDeg !in config.wallSitKneeMinDegrees..config.wallSitKneeMaxDegrees) {
                return no(PostureReason.KNEE_ANGLE_OUT_OF_BAND)
            }
            if (degreesFromHorizontal(hip, knee, w, h) > config.wallSitMaximumThighDegreesFromHorizontal) {
                return no(PostureReason.THIGH_NOT_HORIZONTAL)
            }
        }
        HoldPosture.HORIZONTAL_BODY -> error("handled above")
    }
    return PostureAdmission(true, PostureReason.NONE, hipDeg, kneeDeg, trunkFromVertical)
}
