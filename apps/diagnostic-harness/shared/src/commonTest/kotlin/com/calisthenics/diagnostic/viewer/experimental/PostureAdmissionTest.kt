package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Posture admission for selected-mode static holds. The hold itself (stillness, entry, exit,
 * censoring) is the existing StaticSkillObservation; these tests cover only which shape is admitted.
 * All fixtures are SYNTHETIC side views and are not evidence of accuracy on video.
 */
class PostureAdmissionTest {
    private val base = PostureAdmissionConfig(
        posture = HoldPosture.L_SIT,
        side = ArmSide.LEFT,
        minimumConfidence = .5,
        straightMinDegrees = 160.0,
        straightMaxDegrees = 180.0,
        maximumOrientationDegreesFromHorizontal = 30.0,
        lSitHipMinDegrees = 60.0,
        lSitHipMaxDegrees = 125.0,
        lSitKneeMinDegrees = 145.0,
        lSitMaximumLegDegreesFromHorizontal = 30.0,
        maximumTrunkDegreesFromVertical = 35.0,
        wallSitKneeMinDegrees = 65.0,
        wallSitKneeMaxDegrees = 120.0,
        wallSitMaximumThighDegreesFromHorizontal = 25.0,
    )

    // Square 400x400 frame. Side view, subject facing +x.
    private fun lSit() = pose(
        shoulder = .40 to .30, wrist = .40 to .60, hip = .40 to .60, knee = .60 to .60, ankle = .80 to .60,
    )

    private fun standing() = pose(
        shoulder = .50 to .20, wrist = .50 to .50, hip = .50 to .50, knee = .50 to .70, ankle = .50 to .90,
    )

    private fun wallSit() = pose(
        shoulder = .30 to .20, wrist = .35 to .45, hip = .30 to .50, knee = .55 to .50, ankle = .55 to .80,
    )

    @Test
    fun admitsAnLSit() {
        val a = admitPosture(lSit(), base)
        assertTrue(a.admitted)
        assertEquals(90.0, a.hipDegrees!!, 1e-6)
        assertEquals(180.0, a.kneeDegrees!!, 1e-6)
    }

    @Test
    fun standingIsNotAnLSit() {
        val a = admitPosture(standing(), base)
        assertFalse(a.admitted)
        assertEquals(PostureReason.HIP_ANGLE_OUT_OF_BAND, a.reason)
    }

    /** A tuck sit has the knees bent; it is not an L-sit. */
    @Test
    fun aTuckSitIsNotAnLSit() {
        val tuck = pose(shoulder = .40 to .30, wrist = .40 to .60, hip = .40 to .60, knee = .60 to .50, ankle = .45 to .62)
        assertEquals(PostureReason.KNEE_NOT_STRAIGHT, admitPosture(tuck, base).reason)
    }

    @Test
    fun anLSitIsNotAWallSit() {
        val a = admitPosture(lSit(), base.copy(posture = HoldPosture.WALL_SIT))
        assertFalse(a.admitted)
        assertEquals(PostureReason.KNEE_ANGLE_OUT_OF_BAND, a.reason)
    }

    @Test
    fun admitsAWallSit() {
        assertTrue(admitPosture(wallSit(), base.copy(posture = HoldPosture.WALL_SIT)).admitted)
    }

    @Test
    fun standingIsNotAWallSit() {
        assertFalse(admitPosture(standing(), base.copy(posture = HoldPosture.WALL_SIT)).admitted)
    }

    @Test
    fun aLeaningTrunkIsRejected() {
        val leaning = pose(shoulder = .10 to .45, wrist = .20 to .60, hip = .40 to .60, knee = .60 to .60, ankle = .80 to .60)
        assertEquals(PostureReason.TRUNK_NOT_UPRIGHT, admitPosture(leaning, base).reason)
    }

    @Test
    fun horizontalBodyDelegatesToThePlankBodyLine() {
        val cfg = base.copy(posture = HoldPosture.HORIZONTAL_BODY)
        val straight = pose(shoulder = .20 to .50, wrist = .25 to .70, hip = .50 to .50, knee = .65 to .50, ankle = .80 to .50)
        assertTrue(admitPosture(straight, cfg).admitted)
        assertFalse(admitPosture(standing(), cfg).admitted)
    }

    @Test
    fun lowConfidenceIsNotAdmitted() {
        assertEquals(PostureReason.LOW_CONFIDENCE, admitPosture(lSit().copy(landmarks = lSit().landmarks.map { it.copy(visibility = .1) }), base).reason)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { base.copy(lSitHipMinDegrees = 130.0) }
        assertFailsWith<IllegalArgumentException> { base.copy(wallSitKneeMaxDegrees = 50.0) }
        assertFailsWith<IllegalArgumentException> { base.copy(minimumConfidence = 2.0) }
    }

    private fun pose(
        shoulder: Pair<Double, Double>,
        wrist: Pair<Double, Double>,
        hip: Pair<Double, Double>,
        knee: Pair<Double, Double>,
        ankle: Pair<Double, Double>,
    ) = StaticSkillPoseFrame(
        0, 400, 400,
        listOf(lm(11, shoulder), lm(15, wrist), lm(23, hip), lm(25, knee), lm(27, ankle)),
    )

    private fun lm(i: Int, p: Pair<Double, Double>) =
        Landmark(index = i, name = null, x = p.first, y = p.second, z = 0.0, visibility = .9, presence = .9)
}
