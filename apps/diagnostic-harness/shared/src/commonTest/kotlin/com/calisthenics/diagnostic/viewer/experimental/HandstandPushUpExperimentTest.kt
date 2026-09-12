package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural expectations for a selected-mode handstand push-up candidate cycle.
 *
 * Selected mode only: the operator states the orientation. Nothing here classifies the exercise,
 * judges depth validity or technique, or claims anything about load or muscle activation.
 *
 * Depth is the shoulder-to-wrist span, not the elbow angle. On real development footage the elbow
 * angle did not separate the endpoints (bottoms 154-169 degrees overlapped tops 163-179 degrees)
 * because a side view foreshortens the forearm; the span separated them cleanly. The inversion
 * gate is what distinguishes this from a dip (hip below shoulder) and a push-up (hip level).
 *
 * All fixtures are SYNTHETIC. They encode observed shapes; they are not footage and are not
 * evidence of accuracy on video.
 */
class HandstandPushUpExperimentTest {
    private val config = HspuConfig(
        variant = HspuVariant.WALL_BACK_TO_WALL,
        side = ArmSide.LEFT,
        minimumHipAboveShoulderFraction = .10,
        minimumReferenceSamples = 2,
        maximumReferenceSpanSpreadRatio = .06,
        extendedSpanRatio = .80,
        loweredSpanRatio = .75,
        minimumConfidence = .5,
        maximumGapNs = 500,
        minimumEndpointSamples = 2,
    )

    // Inverted, arms extended: hip above shoulder by .30, span .28.
    private fun top(t: Long) = frame(t, pose(shoulderY = .50, wristY = .78, hipY = .20))

    // Inverted, lowered: span .20 => ratio .714, at or below loweredSpanRatio.
    private fun bottom(t: Long) = frame(t, pose(shoulderY = .50, wristY = .70, hipY = .20))

    // Mid-travel: span .216 => ratio .771, neither extended nor lowered.
    private fun mid(t: Long) = frame(t, pose(shoulderY = .50, wristY = .716, hipY = .20))

    @Test
    fun measuresInversionAndSpanWithoutAReference() {
        val m = measureHspu(top(0), config, reference = null)
        assertEquals(.30, m.hipAboveShoulderFraction!!, 1e-9)
        assertEquals(.28, m.shoulderToWristSpan!!, 1e-9)
        assertNull(m.spanRatio)
        assertEquals(HspuReason.NONE, m.reason)
    }

    @Test
    fun countsOneCycleOnExtendedLoweredExtended() {
        val e = HandstandPushUpExperiment(config)

        // Two settled frames establish the reference; they make no timing observation.
        e.onFrame(top(0))
        e.onFrame(top(100))
        assertNotNull(e.reference)

        assertEquals(HspuPhase.WAITING, e.onFrame(top(200)).phase)
        assertEquals(HspuPhase.SUPPORTED, e.onFrame(top(300)).phase)
        assertEquals(HspuPhase.LOWERING, e.onFrame(mid(400)).phase)
        assertEquals(HspuPhase.LOWERING, e.onFrame(bottom(500)).phase)
        assertEquals(HspuPhase.LOWERED, e.onFrame(bottom(600)).phase)
        assertEquals(HspuPhase.RAISING, e.onFrame(mid(700)).phase)
        assertEquals(0, e.onFrame(top(800)).count)

        val closed = e.onFrame(top(900))
        assertTrue(closed.counted)
        assertEquals(1, closed.count)
        assertEquals(HspuPhase.SUPPORTED, closed.phase)
    }

    /** A parallel-bar dip has the hip BELOW the shoulder and must never enter this experiment. */
    @Test
    fun rejectsADipBecauseTheHipIsBelowTheShoulder() {
        val e = HandstandPushUpExperiment(config)
        val dip = frame(0, pose(shoulderY = .40, wristY = .60, hipY = .70))
        val r = e.onFrame(dip)
        assertEquals(HspuReason.NOT_INVERTED, r.reason)
        assertEquals(0, r.count)
        assertNull(e.reference)
    }

    /** A horizontal push-up has the hip level with the shoulder. */
    @Test
    fun rejectsAHorizontalPushUp() {
        val e = HandstandPushUpExperiment(config)
        val pushUp = frame(0, pose(shoulderY = .50, wristY = .70, hipY = .50))
        assertEquals(HspuReason.NOT_INVERTED, e.onFrame(pushUp).reason)
        assertNull(e.reference)
    }

    /**
     * Regression for a defect found on real footage: a short reference window latched onto the
     * kick-up, where the span is still changing. On one clip that fixed the reference at 0.308
     * while the settled top was 0.21, after which "extended" was never reached again and 4 of 5
     * repetitions were lost. A changing span must not establish a reference.
     */
    @Test
    fun doesNotEstablishAReferenceFromAChangingSpanDuringTheKickUp() {
        val e = HandstandPushUpExperiment(config)

        // Inverted but still settling: span shrinks 0.40 -> 0.34 -> 0.28, well beyond the spread.
        e.onFrame(frame(0, pose(shoulderY = .50, wristY = .90, hipY = .20)))
        e.onFrame(frame(100, pose(shoulderY = .50, wristY = .84, hipY = .20)))
        assertNull(e.reference)

        // Settled: two consecutive equal spans establish it at 0.28, not 0.40.
        e.onFrame(top(200))
        e.onFrame(top(300))
        assertNotNull(e.reference)
        assertEquals(.28, e.reference!!.extendedSpan, 1e-9)
    }

    /** Coming down out of the handstand is not a repetition. */
    @Test
    fun dismountIsNotCounted() {
        val e = HandstandPushUpExperiment(config)
        e.onFrame(top(0))
        e.onFrame(top(100))
        e.onFrame(bottom(200))
        e.onFrame(bottom(300))
        // Athlete comes down: no longer inverted.
        val down = e.onFrame(frame(400, pose(shoulderY = .50, wristY = .70, hipY = .80)))
        assertEquals(HspuReason.NOT_INVERTED, down.reason)
        assertEquals(0, down.count)
    }

    @Test
    fun treatsRepeatedAndBackwardsTimestampsAsDiscontinuities() {
        val e = HandstandPushUpExperiment(config)
        e.onFrame(top(0))
        e.onFrame(top(100))
        assertEquals(HspuReason.DUPLICATE_TIMESTAMP, e.onFrame(top(100)).reason)
        assertEquals(HspuReason.BACKWARDS_TIMESTAMP, e.onFrame(top(50)).reason)
    }

    @Test
    fun treatsALargeCaptureGapAsADiscontinuity() {
        val e = HandstandPushUpExperiment(config)
        e.onFrame(top(0))
        e.onFrame(top(100))
        assertEquals(HspuReason.CAPTURE_GAP, e.onFrame(top(100 + config.maximumGapNs + 1)).reason)
    }

    @Test
    fun rejectsFramesBelowTheConfiguredConfidence() {
        val e = HandstandPushUpExperiment(config)
        val faint = frame(0, pose(shoulderY = .50, wristY = .78, hipY = .20, confidence = .1))
        assertEquals(HspuReason.LOW_CONFIDENCE, e.onFrame(faint).reason)
    }

    @Test
    fun variantIsRecordedAndNotInferred() {
        val facing = HandstandPushUpExperiment(config.copy(variant = HspuVariant.WALL_FACING))
        facing.onFrame(top(0))
        facing.onFrame(top(100))
        assertNotNull(facing.reference)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { config.copy(loweredSpanRatio = .90) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumReferenceSamples = 1) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 1) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumHipAboveShoulderFraction = 0.0) }
    }

    private fun frame(t: Long, landmarks: List<Landmark>) = HspuPoseFrame(t, 400, 400, landmarks)

    private fun pose(
        shoulderY: Double,
        wristY: Double,
        hipY: Double,
        confidence: Double = .9,
    ): List<Landmark> = listOf(
        landmark(11, .50, shoulderY, confidence),
        landmark(15, .50, wristY, confidence),
        landmark(23, .50, hipY, confidence),
    )

    private fun landmark(index: Int, x: Double, y: Double, confidence: Double) =
        Landmark(index = index, name = null, x = x, y = y, z = 0.0, visibility = confidence, presence = confidence)
}
