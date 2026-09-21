package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural expectations for a selected-mode muscle-up candidate cycle.
 *
 * Selected mode only: the operator states which variant is being performed. Nothing here
 * classifies the exercise, judges technique, or claims anything about load or muscle activation.
 *
 * The defining measurement is a regime crossing. A pull-up never leaves the hang regime (wrist
 * above shoulder); a dip never enters it; a muscle-up crosses from one to the other while the
 * hands stay on the apparatus. Support and full elbow lockout are different observations and this
 * experiment measures support only.
 *
 * Several cases below are regressions for concrete failures already cached from development
 * footage, where MediaPipe returned confident-but-wrong arm skeletons while the real upper body
 * was cropped out of frame. Those are fixed with physical-consistency gates, never by demanding
 * higher confidence: an unusable frame stays unusable and an unobserved endpoint stays unknown.
 *
 * All fixtures here are SYNTHETIC. They encode observed failure shapes; they are not real
 * footage and are not evidence of accuracy on video.
 */
/**
 * The engines gate endpoints on ELAPSED TIME, so a synthetic clock has to tick at a rate a camera
 * could actually produce. These fixtures address frames in TICKS; one tick is one frame at a hair
 * under 30 fps, applied at the single point where a frame is built. Every sequence, interval and
 * expectation below is unchanged — only the unit they are counted in is now physical.
 */
private const val TICK = 333_334L

/** The capture-gap limit, in ticks, so that gap fixtures stay readable in the same unit. */
private const val GAP_TICKS = 500L

class MuscleUpCycleExperimentTest {
    private val config = MuscleUpConfig(
        variant = MuscleUpVariant.BAR_KIPPING,
        side = ArmSide.LEFT,
        minimumHangWristAboveShoulderFraction = .05,
        minimumSupportShoulderAboveWristFraction = .05,
        maximumGripDriftTorsoLengths = 1.0,
        minimumBodyScaleRatio = .6,
        maximumBodyScaleRatio = 1.6,
        minimumConfidence = .5,
        maximumGapNs = GAP_TICKS * TICK,
        minimumEndpointSamples = 2,
        minimumReferenceSamples = 2,
        maximumReferenceWristSpreadTorsoLengths = .15,
        hangLockoutElbowDegrees = 150.0,
    )

    // Hang: wrist well above shoulder. Torso (shoulder->hip) is 100 px at 400 px height.
    private fun hang(t: Long) = frame(t, pose(shoulderY = .50, wristY = .20, hipY = .75))

    // Above-apparatus support: shoulder above wrist, grip still near its hang position.
    private fun support(t: Long) = frame(t, pose(shoulderY = .20, wristY = .30, hipY = .45))

    // Neither regime: mid-transition.
    private fun between(t: Long) = frame(t, pose(shoulderY = .35, wristY = .33, hipY = .60))

    @Test
    fun measuresRegimeCrossingWithoutAReferenceOnTheFirstFrame() {
        val measurement = measureMuscleUp(hang(0), config, reference = null)

        assertEquals(-.30, measurement.shoulderAboveWristFraction!!, 1e-9)
        assertEquals(100.0, measurement.torsoLengthPixels!!, 1e-9)
        assertNull(measurement.bodyScaleRatio)
        assertNull(measurement.gripDriftTorsoLengths)
        assertEquals(MuscleUpReason.NONE, measurement.reason)
    }

    @Test
    fun countsOneCycleOnlyWhenAboveApparatusSupportIsObserved() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        assertEquals(MuscleUpPhase.BELOW_BAR, experiment.onFrame(hang(100)).phase)
        assertEquals(MuscleUpPhase.ASCENDING, experiment.onFrame(between(200)).phase)
        assertEquals(MuscleUpPhase.ASCENDING, experiment.onFrame(support(300)).phase)
        assertEquals(MuscleUpPhase.SUPPORTED, experiment.onFrame(support(400)).phase)
        assertEquals(MuscleUpPhase.RETURNING, experiment.onFrame(between(500)).phase)

        val closed = experiment.onFrame(hang(600))
        assertTrue(closed.counted)
        assertEquals(1, closed.count)
        assertEquals(0, closed.unresolvedExcursions)
        val excursion = assertNotNull(closed.completedExcursion)
        assertEquals(MuscleUpExcursionOutcome.COMPLETED, excursion.outcome)
        assertEquals(200L * TICK, excursion.leftHangAtNs)
        assertEquals(400L * TICK, excursion.supportObservedAtNs)
        assertEquals(600L * TICK, excursion.returnedToHangAtNs)
    }

    /**
     * Regression for a cached development failure: the upper body was cropped and the arm skeleton
     * was drawn onto the shorts, collapsing the torso segment to a fraction of its real length
     * while confidence and bounds checks still passed. The excursion must resolve as unknown.
     */
    @Test
    fun reportsSupportUnresolvedWhenBodyScaleCollapses() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        experiment.onFrame(between(200))

        // Torso collapses from 100 px to 40 px: a real torso cannot do this between frames.
        val collapsed = experiment.onFrame(frame(300, pose(shoulderY = .20, wristY = .30, hipY = .30)))
        assertEquals(MuscleUpReason.BODY_SCALE_INCONSISTENT, collapsed.reason)
        assertEquals(.4, collapsed.bodyScaleRatio!!, 1e-9)
        assertEquals(MuscleUpPhase.UNCERTAIN, collapsed.phase)

        experiment.onFrame(hang(400))
        val recovered = experiment.onFrame(hang(500))
        assertEquals(0, recovered.count)
        assertEquals(1, recovered.unresolvedExcursions)
        assertEquals(MuscleUpPhase.BELOW_BAR, recovered.phase)
    }

    /**
     * Regression for the other cached failure: the whole skeleton migrated to the bottom of the
     * frame, leaving the shoulder above the wrist. That satisfies a naive support test, but the
     * apparatus cannot move, so the grip point cannot travel that far.
     */
    @Test
    fun rejectsFalseSupportWhenTheGripPointMigrates() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        experiment.onFrame(between(200))

        // Shoulder above wrist (naive support) but the grip has dropped 2.4 torso lengths.
        val migrated = experiment.onFrame(frame(300, pose(shoulderY = .70, wristY = .80, hipY = .95)))
        assertEquals(MuscleUpReason.GRIP_DRIFT, migrated.reason)
        assertTrue(migrated.gripDriftTorsoLengths!! > config.maximumGripDriftTorsoLengths)
        assertEquals(0, migrated.count)

        experiment.onFrame(hang(400))
        val recovered = experiment.onFrame(hang(500))
        assertEquals(0, recovered.count)
        assertEquals(1, recovered.unresolvedExcursions)
    }

    /**
     * Toes-to-bar follows muscle-ups in real development footage. The hips travel a long way but
     * the shoulders never rise above the hands, so the hang regime is never left.
     */
    @Test
    fun doesNotCountToesToBarBecauseTheHangRegimeIsNeverLeft() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        // Hips swing forward and up; shoulder and wrist are unchanged.
        experiment.onFrame(frame(200, pose(shoulderY = .50, wristY = .20, hipY = .65, hipX = .70)))
        experiment.onFrame(frame(300, pose(shoulderY = .50, wristY = .20, hipY = .55, hipX = .75)))
        val end = experiment.onFrame(hang(400))

        assertEquals(0, end.count)
        assertEquals(0, end.unresolvedExcursions)
        assertEquals(MuscleUpPhase.BELOW_BAR, end.phase)
    }

    @Test
    fun doesNotCountASingleFrameOfSupport() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        experiment.onFrame(between(200))
        experiment.onFrame(support(300))
        experiment.onFrame(between(400))
        val end = experiment.onFrame(hang(500))

        assertEquals(0, end.count)
        assertEquals(1, end.unresolvedExcursions)
    }

    @Test
    fun doesNotCountWhenPoseDropsOutAfterSupportSoTheReturnIsNeverSeen() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        experiment.onFrame(between(200))
        experiment.onFrame(support(300))
        experiment.onFrame(support(400))

        val dropout = experiment.onFrame(MuscleUpPoseFrame(500 * TICK, 400, 400, emptyList()))
        assertEquals(MuscleUpReason.NO_POSE, dropout.reason)
        assertEquals(0, dropout.count)
        assertEquals(1, dropout.unresolvedExcursions)
    }

    @Test
    fun treatsRepeatedAndBackwardsTimestampsAsDiscontinuities() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        assertEquals(MuscleUpReason.DUPLICATE_TIMESTAMP, experiment.onFrame(hang(100)).reason)
        assertEquals(MuscleUpReason.BACKWARDS_TIMESTAMP, experiment.onFrame(hang(50)).reason)
    }

    @Test
    fun treatsALargeCaptureGapAsADiscontinuity() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        val gapped = experiment.onFrame(hang(100 + GAP_TICKS + 1))
        assertEquals(MuscleUpReason.CAPTURE_GAP, gapped.reason)
        assertEquals(MuscleUpPhase.UNCERTAIN, gapped.phase)
    }

    @Test
    fun rejectsFramesBelowTheConfiguredConfidence() {
        val experiment = MuscleUpCycleExperiment(config)
        val faint = frame(0, pose(shoulderY = .50, wristY = .20, hipY = .75, confidence = .1))
        assertEquals(MuscleUpReason.LOW_CONFIDENCE, experiment.onFrame(faint).reason)
    }

    @Test
    fun variantIsRecordedAndNotInferred() {
        val ring = MuscleUpCycleExperiment(config.copy(variant = MuscleUpVariant.RING_STRICT))
        ring.onFrame(hang(0))
        ring.onFrame(hang(100))
        // The variant label carries no numeric behaviour of its own.
        assertEquals(MuscleUpPhase.BELOW_BAR, ring.onFrame(hang(200)).phase)
    }

    /**
     * Regression for a defect found on real footage: the reference was taken from the first frames
     * that merely satisfied "wrist above shoulder", which during a mount is the athlete reaching
     * upward before the hands reach the apparatus. Every later frame then read as grip drift —
     * 572 of 617 frames on one clip. A moving wrist must not establish a reference.
     */
    @Test
    fun doesNotEstablishAReferenceFromAMovingGripDuringTheMount() {
        val experiment = MuscleUpCycleExperiment(config)

        // Wrist travels while still below-bar: reaching upward, not yet gripping.
        experiment.onFrame(frame(0, pose(shoulderY = .60, wristY = .45, hipY = .85)))
        experiment.onFrame(frame(100, pose(shoulderY = .55, wristY = .35, hipY = .80)))
        val mounting = experiment.onFrame(frame(200, pose(shoulderY = .52, wristY = .26, hipY = .77)))
        assertNull(experiment.reference)
        assertEquals(MuscleUpPhase.WAITING, mounting.phase)

        // Once the grip is stationary the reference is accepted.
        experiment.onFrame(hang(300))
        val settled = experiment.onFrame(hang(400))
        assertNotNull(experiment.reference)
        assertEquals(MuscleUpPhase.BELOW_BAR, settled.phase)
        assertEquals(.20, experiment.reference!!.wristY, 1e-9)
    }

    /**
     * Dropping back below the apparatus is not yet the end of the repetition: the arms are still
     * bent. The cycle closes only once they are straight in the hang.
     */
    @Test
    fun closesTheCycleOnlyWhenTheArmsAreStraightInTheHang() {
        val experiment = MuscleUpCycleExperiment(config)

        experiment.onFrame(hang(0))
        experiment.onFrame(hang(100))
        experiment.onFrame(between(200))
        experiment.onFrame(support(300))
        experiment.onFrame(support(400))

        // Below the bar but elbows sharply bent: must not close yet.
        val bent = experiment.onFrame(frame(500, pose(shoulderY = .50, wristY = .20, hipY = .75, elbowBend = .15)))
        assertEquals(0, bent.count)
        assertEquals(MuscleUpPhase.RETURNING, bent.phase)

        val straight = experiment.onFrame(hang(600))
        assertTrue(straight.counted)
        assertEquals(1, straight.count)
        assertEquals(600L * TICK, straight.completedExcursion!!.returnedToHangAtNs)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { config.copy(minimumBodyScaleRatio = 1.2) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumBodyScaleRatio = .8) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGripDriftTorsoLengths = 0.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 1) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumReferenceSamples = 1) }
        assertFailsWith<IllegalArgumentException> { config.copy(hangLockoutElbowDegrees = 190.0) }
        assertFailsWith<IllegalArgumentException> {
            config.copy(maximumReferenceWristSpreadTorsoLengths = 0.0)
        }
    }

    /** [t] is a frame index on the synthetic 30 fps clock, not a nanosecond count. */
    private fun frame(t: Long, landmarks: List<Landmark>) = MuscleUpPoseFrame(t * TICK, 400, 400, landmarks)

    private fun pose(
        shoulderY: Double,
        wristY: Double,
        hipY: Double,
        hipX: Double = .50,
        confidence: Double = .9,
        // Horizontal offset of the elbow from the shoulder-wrist line; 0 means a straight arm.
        elbowBend: Double = 0.0,
    ): List<Landmark> = listOf(
        landmark(11, .50, shoulderY, confidence),
        landmark(13, .50 + elbowBend, (shoulderY + wristY) / 2, confidence),
        landmark(15, .50, wristY, confidence),
        landmark(23, hipX, hipY, confidence),
    )

    private fun landmark(index: Int, x: Double, y: Double, confidence: Double) = Landmark(
        index = index,
        name = null,
        x = x,
        y = y,
        z = 0.0,
        visibility = confidence,
        presence = confidence,
    )
}
