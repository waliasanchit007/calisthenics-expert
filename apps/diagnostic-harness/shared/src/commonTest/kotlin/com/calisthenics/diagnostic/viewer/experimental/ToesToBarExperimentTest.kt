package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Behavioural expectations for a selected-mode kipping toes-to-bar candidate cycle.
 *
 * The defining observable is both feet reaching hand height, in torso lengths. It is separated from
 * a knee raise (feet stop short of the bar), from a single-leg kick (one foot only), and from the
 * above-bar support phase of a muscle-up (hands below shoulders, so not hanging).
 *
 * All fixtures are SYNTHETIC and are not evidence of accuracy on video.
 */
class ToesToBarExperimentTest {
    private val config = ToesToBarConfig(
        side = ArmSide.LEFT,
        minimumHandsAboveShoulders = .35,
        maximumFeetBelowHandsAtBar = .6,
        minimumFeetBelowHandsDown = 1.5,
        minimumConfidence = .5,
        maximumGapNs = 500,
        minimumEndpointSamples = 2,
        requireBothAnkles = true,
    )

    // Torso = shoulder .40 -> hip .60 = 0.2 height units. Hands at .20 => 1.0 torso lengths up.
    private fun down(t: Long) = frame(t, pose(ankle1Y = .90, ankle2Y = .90)) // feet 3.5 below hands
    private fun mid(t: Long) = frame(t, pose(ankle1Y = .45, ankle2Y = .45)) // 1.25, neither endpoint
    private fun atBar(t: Long) = frame(t, pose(ankle1Y = .24, ankle2Y = .24)) // 0.2, feet at hand height

    @Test
    fun measuresInTorsoLengths() {
        val m = measureToesToBar(atBar(0), config)
        assertEquals(1.0, m.handsAboveShoulders!!, 1e-9)
        assertEquals(.2, m.feetBelowHands!!, 1e-9)
        assertEquals(ToesToBarReason.NONE, m.reason)
    }

    @Test
    fun countsOnTheReturnAfterTheFeetReachTheBar() {
        val e = ToesToBarExperiment(config)
        e.onFrame(down(0))
        assertEquals(ToesToBarPhase.HANGING, e.onFrame(down(100)).phase)
        assertEquals(ToesToBarPhase.RAISING, e.onFrame(mid(200)).phase)
        e.onFrame(atBar(300))
        assertEquals(ToesToBarPhase.AT_BAR, e.onFrame(atBar(400)).phase)
        assertEquals(ToesToBarPhase.RETURNING, e.onFrame(mid(500)).phase)
        assertEquals(0, e.onFrame(down(600)).count)
        val closed = e.onFrame(down(700))
        assertTrue(closed.counted)
        assertEquals(1, closed.count)
    }

    /** "Toes to bar" means both feet; one foot kicked up is not a touch. */
    @Test
    fun oneFootAtTheBarIsNotATouch() {
        val e = ToesToBarExperiment(config)
        e.onFrame(down(0))
        e.onFrame(down(100))
        e.onFrame(frame(200, pose(ankle1Y = .24, ankle2Y = .90)))
        val r = e.onFrame(frame(300, pose(ankle1Y = .24, ankle2Y = .90)))
        assertEquals(ToesToBarPhase.HANGING, r.phase)
        e.onFrame(down(400))
        assertEquals(0, e.onFrame(down(500)).count)
    }

    /** A knee raise lifts the feet but stops short of hand height. */
    @Test
    fun aKneeRaiseThatStopsShortIsNotCounted() {
        val e = ToesToBarExperiment(config)
        e.onFrame(down(0))
        e.onFrame(down(100))
        e.onFrame(mid(200))
        e.onFrame(mid(300))
        e.onFrame(down(400))
        assertEquals(0, e.onFrame(down(500)).count)
    }

    /** Above-bar muscle-up support: hands below shoulders, so not hanging. */
    @Test
    fun theSupportPhaseOfAMuscleUpIsNotHanging() {
        val e = ToesToBarExperiment(config)
        val support = frame(0, pose(wristY = .60, ankle1Y = .95, ankle2Y = .95))
        val r = e.onFrame(support)
        assertEquals(ToesToBarReason.NOT_HANGING, r.reason)
        assertEquals(ToesToBarPhase.UNCERTAIN, r.phase)
    }

    @Test
    fun aDropoutAtTheBarBreaksTheCycle() {
        val e = ToesToBarExperiment(config)
        e.onFrame(down(0))
        e.onFrame(down(100))
        e.onFrame(mid(200))
        e.onFrame(atBar(300))
        e.onFrame(atBar(400))
        assertEquals(ToesToBarReason.NO_POSE, e.onFrame(ToesToBarPoseFrame(500, 400, 400, emptyList())).reason)
        e.onFrame(mid(600))
        e.onFrame(down(700))
        assertEquals(0, e.onFrame(down(800)).count)
    }

    @Test
    fun treatsRepeatedBackwardsAndGappedTimestampsAsDiscontinuities() {
        val e = ToesToBarExperiment(config)
        e.onFrame(down(0))
        e.onFrame(down(100))
        assertEquals(ToesToBarReason.DUPLICATE_TIMESTAMP, e.onFrame(down(100)).reason)
        assertEquals(ToesToBarReason.BACKWARDS_TIMESTAMP, e.onFrame(down(50)).reason)
        assertEquals(ToesToBarReason.CAPTURE_GAP, e.onFrame(down(100 + config.maximumGapNs + 1)).reason)
    }

    @Test
    fun rejectsFramesBelowTheConfiguredConfidence() {
        val e = ToesToBarExperiment(config)
        assertEquals(
            ToesToBarReason.LOW_CONFIDENCE,
            e.onFrame(frame(0, pose(ankle1Y = .90, ankle2Y = .90, confidence = .1))).reason,
        )
    }

    /**
     * Regression for a generalisation failure: with requireBothAnkles the engine reported ZERO
     * repetitions on two independent side-view recordings, because the far ankle was occluded in
     * 86/420 and 116/1051 frames. With it false, the observable ankle is used.
     */
    @Test
    fun withoutRequireBothAnklesAnOccludedFarAnkleStillMeasures() {
        val oneAnkle = listOf(
            landmark(11, .50, .40, .9), landmark(15, .50, .20, .9), landmark(23, .50, .60, .9),
            landmark(27, .48, .24, .9), landmark(28, .52, .24, .1), // far ankle below confidence
        )
        assertEquals(ToesToBarReason.LOW_CONFIDENCE, measureToesToBar(frame(0, oneAnkle), config).reason)
        val relaxed = measureToesToBar(frame(0, oneAnkle), config.copy(requireBothAnkles = false))
        assertEquals(ToesToBarReason.NONE, relaxed.reason)
        assertEquals(.2, relaxed.feetBelowHands!!, 1e-9)
    }

    /** The cost of the fallback, stated explicitly: one leg up can now read as a touch. */
    @Test
    fun withoutRequireBothAnklesASingleLegKickCanCount() {
        val strict = ToesToBarExperiment(config)
        val relaxed = ToesToBarExperiment(config.copy(requireBothAnkles = false))
        // Far ankle unusable throughout; near ankle performs a full cycle.
        fun f(t: Long, nearY: Double) = frame(t, listOf(
            landmark(11, .50, .40, .9), landmark(15, .50, .20, .9), landmark(23, .50, .60, .9),
            landmark(27, .48, nearY, .9), landmark(28, .52, .90, .1)))
        val seq = listOf(.90, .90, .45, .24, .24, .45, .90, .90)
        seq.forEachIndexed { i, y -> strict.onFrame(f(i * 100L, y)); relaxed.onFrame(f(i * 100L, y)) }
        assertEquals(0, strict.count)
        assertEquals(1, relaxed.count)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { config.copy(maximumFeetBelowHandsAtBar = 2.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumHandsAboveShoulders = 0.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 1) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
    }

    private fun frame(t: Long, landmarks: List<Landmark>) = ToesToBarPoseFrame(t, 400, 400, landmarks)

    private fun pose(
        shoulderY: Double = .40,
        wristY: Double = .20,
        hipY: Double = .60,
        ankle1Y: Double,
        ankle2Y: Double,
        confidence: Double = .9,
    ): List<Landmark> = listOf(
        landmark(11, .50, shoulderY, confidence),
        landmark(15, .50, wristY, confidence),
        landmark(23, .50, hipY, confidence),
        landmark(27, .48, ankle1Y, confidence),
        landmark(28, .52, ankle2Y, confidence),
    )

    private fun landmark(index: Int, x: Double, y: Double, confidence: Double) =
        Landmark(index = index, name = null, x = x, y = y, z = 0.0, visibility = confidence, presence = confidence)
}
