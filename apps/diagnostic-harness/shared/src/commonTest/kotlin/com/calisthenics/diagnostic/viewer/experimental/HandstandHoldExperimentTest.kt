package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural expectations for a selected-mode handstand hold interval.
 *
 * A hold is an interval, never a repetition. It needs a stacked body: hip above shoulder AND both
 * ankles above the hip. Hip-above-shoulder alone over-extended holds on real footage because the
 * hips are already above the shoulders during a split-leg kick-up and dismount.
 *
 * All fixtures are SYNTHETIC and are not evidence of accuracy on video.
 */
class HandstandHoldExperimentTest {
    private val config = HandstandHoldConfig(
        support = HandstandSupport.FREESTANDING,
        side = ArmSide.LEFT,
        minimumHipAboveShoulderFraction = .10,
        minimumAnkleAboveHipFraction = .05,
        minimumConfidence = .5,
        maximumGapNs = 500,
        minimumHoldNs = 300,
        minimumNonHoldingSamplesToClose = 1,
    )

    // Stacked handstand: wrist .85 (floor) > shoulder .70 > hip .40 > ankles .10.
    private fun stacked(t: Long) = frame(t, pose(ankle1Y = .10, ankle2Y = .10))

    // Split-leg kick-up: hips above shoulders but one foot still near the floor.
    private fun kickUp(t: Long) = frame(t, pose(ankle1Y = .10, ankle2Y = .80))

    private fun standing(t: Long) = frame(t, pose(shoulderY = .30, wristY = .60, hipY = .55, ankle1Y = .90, ankle2Y = .90))

    @Test
    fun reportsOneIntervalWithEntryExitAndDuration() {
        val e = HandstandHoldExperiment(config)
        e.onFrame(standing(0))
        e.onFrame(stacked(100))
        e.onFrame(stacked(200))
        e.onFrame(stacked(300))
        e.onFrame(stacked(400))
        val end = e.onFrame(standing(500))

        val interval = assertNotNull(end.completedInterval)
        assertEquals(100L, interval.entryTimestampNs)
        assertEquals(400L, interval.exitTimestampNs)
        assertEquals(300L, interval.durationNs)
        assertEquals(HandstandReason.NOT_INVERTED, interval.endReason)
        assertFalse(interval.leftCensored)
        assertFalse(interval.rightCensored)
    }

    /** Regression for the over-extended entry on real footage. */
    @Test
    fun aSplitLegKickUpIsNotHolding() {
        val e = HandstandHoldExperiment(config)
        val r = e.onFrame(kickUp(0))
        assertFalse(r.holding)
        assertEquals(HandstandReason.LEGS_NOT_STACKED, r.reason)
    }

    @Test
    fun theHoldStartsOnlyOnceTheLegsAreStackedNotAtTheKickUp() {
        val e = HandstandHoldExperiment(config)
        e.onFrame(kickUp(0))
        e.onFrame(kickUp(100))
        e.onFrame(stacked(200))
        e.onFrame(stacked(300))
        e.onFrame(stacked(400))
        e.onFrame(stacked(500))
        val interval = assertNotNull(e.onFrame(kickUp(600)).completedInterval)
        assertEquals(200L, interval.entryTimestampNs)
        assertEquals(500L, interval.exitTimestampNs)
        assertEquals(HandstandReason.LEGS_NOT_STACKED, interval.endReason)
    }

    @Test
    fun aHoldPresentOnTheFirstFrameIsLeftCensored() {
        val e = HandstandHoldExperiment(config)
        for (t in listOf(0L, 100, 200, 300, 400)) e.onFrame(stacked(t))
        val interval = assertNotNull(e.onFrame(standing(500)).completedInterval)
        assertTrue(interval.leftCensored)
    }

    @Test
    fun aHoldStillRunningAtEndOfInputIsRightCensored() {
        val e = HandstandHoldExperiment(config)
        e.onFrame(standing(0))
        for (t in listOf(100L, 200, 300, 400, 500)) e.onFrame(stacked(t))
        val interval = assertNotNull(e.finish())
        assertTrue(interval.rightCensored)
        assertEquals(HandstandReason.END_OF_INPUT, interval.endReason)
    }

    /** Fragments are never stitched: a gap ends the interval even if holding resumes. */
    @Test
    fun aCaptureGapEndsTheIntervalAndIsNotBridged() {
        val e = HandstandHoldExperiment(config)
        e.onFrame(standing(0))
        for (t in listOf(100L, 200, 300, 400)) e.onFrame(stacked(t))
        val resumed = e.onFrame(stacked(400 + config.maximumGapNs + 1))
        val first = assertNotNull(resumed.completedInterval)
        assertEquals(400L, first.exitTimestampNs)
        assertEquals(HandstandReason.CAPTURE_GAP, first.endReason)
        assertEquals(1, e.intervals.size)
    }

    @Test
    fun aHoldShorterThanTheMinimumIsNotReported() {
        val e = HandstandHoldExperiment(config)
        e.onFrame(standing(0))
        e.onFrame(stacked(100))
        e.onFrame(stacked(200))
        assertNull(e.onFrame(standing(300)).completedInterval)
        assertTrue(e.intervals.isEmpty())
    }

    @Test
    fun aDipIsNotInverted() {
        val e = HandstandHoldExperiment(config)
        val dip = frame(0, pose(shoulderY = .40, wristY = .60, hipY = .70, ankle1Y = .95, ankle2Y = .95))
        assertEquals(HandstandReason.NOT_INVERTED, e.onFrame(dip).reason)
    }

    @Test
    fun lowConfidenceEndsTheHold() {
        val e = HandstandHoldExperiment(config)
        e.onFrame(standing(0))
        for (t in listOf(100L, 200, 300, 400)) e.onFrame(stacked(t))
        val faint = e.onFrame(frame(500, pose(ankle1Y = .10, ankle2Y = .10, confidence = .1)))
        assertEquals(HandstandReason.LOW_CONFIDENCE, faint.reason)
        assertEquals(HandstandReason.LOW_CONFIDENCE, assertNotNull(faint.completedInterval).endReason)
    }

    @Test
    fun treatsRepeatedAndBackwardsTimestampsAsBreaks() {
        val e = HandstandHoldExperiment(config)
        e.onFrame(stacked(0))
        e.onFrame(stacked(100))
        assertEquals(HandstandReason.DUPLICATE_TIMESTAMP, e.onFrame(stacked(100)).reason)
        assertEquals(HandstandReason.BACKWARDS_TIMESTAMP, e.onFrame(stacked(50)).reason)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { config.copy(minimumAnkleAboveHipFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumHipAboveShoulderFraction = -1.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumHoldNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumNonHoldingSamplesToClose = 0) }
    }

    /**
     * Persistence: [HandstandHoldConfig.minimumNonHoldingSamplesToClose] frames that fail the
     * stacked rule are required before an open hold is closed. On btUWnzTI8yk a steady hold was
     * split by a 1-2 frame LEGS_NOT_STACKED blip, while every break that mattered there was 32-222
     * frames long. Every other test in this class runs with the value 1, the original behaviour.
     */
    private val persistent = config.copy(minimumNonHoldingSamplesToClose = 3)

    @Test
    fun aSingleUnstackedFrameNoLongerEndsAHold() {
        val experiment = HandstandHoldExperiment(persistent)
        for (t in 0L until 10L) experiment.onFrame(stacked(t * 100))
        val blip = experiment.onFrame(kickUp(1000))
        assertNull(blip.completedInterval)
        assertFalse(blip.holding)
        for (t in 11L until 20L) experiment.onFrame(stacked(t * 100))

        val closed = assertNotNull(experiment.finish())
        assertEquals(0L, closed.entryTimestampNs)
        assertEquals(1900L, closed.exitTimestampNs)
        assertTrue(closed.rightCensored)
        assertEquals(1, experiment.intervals.size)
    }

    @Test
    fun consecutiveUnstackedFramesStillEndTheHoldAtTheLastStackedFrame() {
        val experiment = HandstandHoldExperiment(persistent)
        for (t in 0L until 10L) experiment.onFrame(stacked(t * 100))
        assertNull(experiment.onFrame(kickUp(1000)).completedInterval)
        assertNull(experiment.onFrame(kickUp(1100)).completedInterval)
        val third = experiment.onFrame(kickUp(1200))

        val closed = assertNotNull(third.completedInterval)
        assertEquals(HandstandReason.LEGS_NOT_STACKED, closed.endReason)
        // The exit is the last stacked frame, not the frame that finally proved the break.
        assertEquals(900L, closed.exitTimestampNs)
    }

    /**
     * The persistence window is for geometry only. A frame the engine could not measure is not
     * evidence that the hold continued, so it still ends the interval on the first occurrence.
     */
    @Test
    fun anUnmeasurableFrameStillEndsTheHoldImmediately() {
        val experiment = HandstandHoldExperiment(persistent)
        for (t in 0L until 10L) experiment.onFrame(stacked(t * 100))

        val dropout = experiment.onFrame(HandstandPoseFrame(1000, 400, 400, emptyList()))

        val closed = assertNotNull(dropout.completedInterval)
        assertEquals(HandstandReason.NO_POSE, closed.endReason)
        assertEquals(900L, closed.exitTimestampNs)
    }

    private fun frame(t: Long, landmarks: List<Landmark>) = HandstandPoseFrame(t, 400, 400, landmarks)

    private fun pose(
        shoulderY: Double = .70,
        wristY: Double = .85,
        hipY: Double = .40,
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
