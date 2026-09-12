package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural expectations for a selected-mode parallel-bar dip candidate cycle.
 *
 * Selected mode only: the operator states that a dip is being performed. Nothing here classifies
 * the exercise, judges depth or technique, or claims anything about muscle activation or load.
 *
 * The two admission gates are what separate a dip from its nearest neighbours under this
 * measurement, and both are checked below:
 *  - the shoulder must sit above the wrist (arms-down support), which an overhead pull-up fails;
 *  - the shoulder-to-hip torso axis must be near-vertical, which a horizontal push-up fails.
 * Elbow flexion alone cannot tell the three apart, so it is never trusted on its own.
 */
class DipCycleExperimentTest {
    private val config = DipConfig(
        side = ArmSide.LEFT,
        extendedElbowDegrees = 155.0,
        flexedElbowDegrees = 100.0,
        minimumShoulderAboveWristFraction = .05,
        maximumTorsoTiltDegrees = 45.0,
        minimumConfidence = .8,
        maximumGapNs = 500,
        minimumEndpointSamples = 2,
    )

    @Test
    fun measuresElbowSupportAndTorsoGeometryInSourcePixels() {
        val measurement = measureDipSupport(
            frame(
                0, 400, 400,
                landmarks(
                    shoulder = .25 to .25,
                    elbow = .5 to .5,
                    wrist = .75 to .5,
                    hip = .35 to .75,
                ),
            ),
            config,
        )

        assertEquals(135.0, measurement.elbowDegrees!!, 1e-9)
        assertEquals(.25, measurement.shoulderAboveWristFraction!!, 1e-9)
        assertEquals(11.309932474020213, measurement.torsoTiltDegrees!!, 1e-9)
        assertEquals(DipReason.NONE, measurement.reason)
    }

    @Test
    fun countsOneCycleOnlyAfterSupportedLoweredAndSustainedReturnToSupport() {
        val experiment = DipCycleExperiment(config)

        assertEquals(DipPhase.WAITING, experiment.onFrame(frame(0, 400, 400, dipPose(160.0))).phase)
        assertEquals(DipPhase.SUPPORTED, experiment.onFrame(frame(100, 400, 400, dipPose(160.0))).phase)
        assertEquals(DipPhase.LOWERING, experiment.onFrame(frame(200, 400, 400, dipPose(130.0))).phase)
        assertEquals(DipPhase.LOWERING, experiment.onFrame(frame(300, 400, 400, dipPose(90.0))).phase)
        assertEquals(DipPhase.LOWERED, experiment.onFrame(frame(400, 400, 400, dipPose(90.0))).phase)
        assertEquals(DipPhase.RAISING, experiment.onFrame(frame(500, 400, 400, dipPose(130.0))).phase)
        assertEquals(0, experiment.onFrame(frame(600, 400, 400, dipPose(160.0))).count)

        val completed = experiment.onFrame(frame(700, 400, 400, dipPose(160.0)))
        assertEquals(1, completed.count)
        assertEquals(DipPhase.SUPPORTED, completed.phase)
        assertEquals(DipReason.NONE, completed.reason)
        assertTrue(completed.counted)
    }

    @Test
    fun partialLoweringThatNeverReachesTheBottomDoesNotCount() {
        val experiment = DipCycleExperiment(config)
        listOf(0L to 160.0, 100L to 160.0, 200L to 130.0, 300L to 120.0, 400L to 160.0, 500L to 160.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 400, dipPose(angle))) }

        assertEquals(0, experiment.count)
        assertEquals(DipPhase.SUPPORTED, experiment.phase)
    }

    @Test
    fun partialRaiseThatNeverReturnsToSupportDoesNotCount() {
        val experiment = DipCycleExperiment(config)
        listOf(0L to 160.0, 100L to 160.0, 200L to 130.0, 300L to 90.0, 400L to 90.0, 500L to 130.0, 600L to 140.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 400, dipPose(angle))) }

        assertEquals(0, experiment.count)
        assertEquals(DipPhase.RAISING, experiment.phase)
    }

    @Test
    fun repeatedEndpointSamplesDoNotProduceExtraCycles() {
        val experiment = DipCycleExperiment(config)
        val angles = listOf(160.0, 160.0, 160.0, 160.0, 130.0, 90.0, 90.0, 90.0, 90.0, 130.0, 160.0, 160.0, 160.0, 160.0)
        angles.forEachIndexed { index, angle ->
            experiment.onFrame(frame(index * 100L, 400, 400, dipPose(angle)))
        }

        assertEquals(1, experiment.count)
        assertEquals(DipPhase.SUPPORTED, experiment.phase)
    }

    @Test
    fun overheadPullUpGeometryIsRejectedBecauseTheShoulderIsNotAboveTheWrist() {
        val experiment = DipCycleExperiment(config)
        // Upright torso, full elbow flexion range, but the hands are overhead: a pull-up.
        val hanging = experiment.onFrame(frame(0, 400, 400, overheadPose(160.0)))
        assertEquals(DipReason.SHOULDER_NOT_ABOVE_WRIST, hanging.reason)
        assertNull(hanging.elbowDegrees)

        listOf(100L to 160.0, 200L to 130.0, 300L to 90.0, 400L to 90.0, 500L to 130.0, 600L to 160.0, 700L to 160.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 400, overheadPose(angle))) }

        assertEquals(0, experiment.count)
        assertEquals(DipPhase.UNCERTAIN, experiment.phase)
    }

    @Test
    fun horizontalPushUpGeometryIsRejectedBecauseTheTorsoIsNotUpright() {
        val experiment = DipCycleExperiment(config)
        // Shoulder above wrist is also true of a push-up; only the torso axis separates them.
        val plank = experiment.onFrame(frame(0, 400, 400, dipPose(160.0, torsoTiltDegrees = 88.0)))
        assertEquals(DipReason.TORSO_NOT_UPRIGHT, plank.reason)
        assertNull(plank.elbowDegrees)

        listOf(100L to 160.0, 200L to 130.0, 300L to 90.0, 400L to 90.0, 500L to 130.0, 600L to 160.0, 700L to 160.0)
            .forEach { (timestamp, angle) ->
                experiment.onFrame(frame(timestamp, 400, 400, dipPose(angle, torsoTiltDegrees = 88.0)))
            }

        assertEquals(0, experiment.count)
        assertEquals(DipPhase.UNCERTAIN, experiment.phase)
    }

    @Test
    fun anUprightArmsDownCycleStillCountsWhenTheTorsoLeansWithinTheAdmittedBand() {
        // The frozen footage shows a forward lean of roughly twenty degrees at the bottom, so a
        // strict vertical requirement would reject genuine dips. This band is a prototype value.
        val experiment = DipCycleExperiment(config)
        listOf(0L to 160.0, 100L to 160.0, 200L to 130.0, 300L to 90.0, 400L to 90.0, 500L to 130.0,
            600L to 160.0, 700L to 160.0)
            .forEach { (timestamp, angle) ->
                experiment.onFrame(frame(timestamp, 400, 400, dipPose(angle, torsoTiltDegrees = 21.0)))
            }

        assertEquals(1, experiment.count)
    }

    @Test
    fun missingHipLandmarkInvalidatesAnUnfinishedCycle() {
        val experiment = DipCycleExperiment(config)
        listOf(0L to 160.0, 100L to 160.0, 200L to 130.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 400, dipPose(angle))) }

        val withoutHip = experiment.onFrame(
            frame(300, 400, 400, dipPose(90.0).filterNot { it.index == 23 }),
        )
        assertEquals(DipReason.MISSING_LANDMARK, withoutHip.reason)
        assertEquals(DipPhase.UNCERTAIN, withoutHip.phase)
        assertNull(withoutHip.elbowDegrees)
        assertEquals(0, experiment.count)
    }

    @Test
    fun anEmptyPoseAndADuplicateLandmarkIndexAreBothRejected() {
        val experiment = DipCycleExperiment(config)
        assertEquals(DipReason.NO_POSE, experiment.onFrame(frame(0, 400, 400, emptyList())).reason)

        val duplicated = dipPose(160.0).let { it + it.first() }
        assertEquals(
            DipReason.DUPLICATE_LANDMARK_INDEX,
            experiment.onFrame(frame(100, 400, 400, duplicated)).reason,
        )
    }

    @Test
    fun lowConfidenceLandmarksAreNotUsedForCounting() {
        val experiment = DipCycleExperiment(config)
        val faint = experiment.onFrame(frame(0, 400, 400, dipPose(160.0, confidence = .4)))

        assertEquals(DipReason.LOW_CONFIDENCE, faint.reason)
        assertNull(faint.elbowDegrees)
        assertEquals(0, experiment.count)
    }

    @Test
    fun invalidDuplicateAndBackwardsTimestampsAreRejected() {
        val experiment = DipCycleExperiment(config)

        assertEquals(
            DipReason.INVALID_TIMESTAMP,
            experiment.onFrame(frame(-1, 400, 400, dipPose(160.0))).reason,
        )
        experiment.onFrame(frame(100, 400, 400, dipPose(160.0)))
        assertEquals(
            DipReason.DUPLICATE_TIMESTAMP,
            experiment.onFrame(frame(100, 400, 400, dipPose(160.0))).reason,
        )
        assertEquals(
            DipReason.BACKWARDS_TIMESTAMP,
            experiment.onFrame(frame(50, 400, 400, dipPose(160.0))).reason,
        )
        assertEquals(0, experiment.count)
    }

    @Test
    fun aCaptureGapInvalidatesTheCandidateAndTheNextCycleMustStartFromFreshSupport() {
        val experiment = DipCycleExperiment(config)
        listOf(0L to 160.0, 100L to 160.0, 200L to 130.0, 300L to 90.0, 400L to 90.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 400, dipPose(angle))) }

        val afterGap = experiment.onFrame(frame(2_000, 400, 400, dipPose(160.0)))
        assertEquals(DipReason.CAPTURE_GAP, afterGap.reason)
        assertEquals(0, afterGap.count)

        // The pre-gap descent cannot be spliced onto a post-gap rise.
        experiment.onFrame(frame(2_100, 400, 400, dipPose(160.0)))
        assertEquals(0, experiment.count)

        listOf(2_200L to 130.0, 2_300L to 90.0, 2_400L to 90.0, 2_500L to 130.0, 2_600L to 160.0, 2_700L to 160.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 400, dipPose(angle))) }
        assertEquals(1, experiment.count)
    }

    @Test
    fun uncertaintyClearsOnlyAfterASustainedSupportedEndpoint() {
        val experiment = DipCycleExperiment(config)
        experiment.onFrame(frame(0, 400, 400, emptyList()))
        assertEquals(DipPhase.UNCERTAIN, experiment.phase)

        // A single supported sample is not enough to rearm.
        assertEquals(DipPhase.WAITING, experiment.onFrame(frame(100, 400, 400, dipPose(160.0))).phase)
        assertEquals(DipPhase.SUPPORTED, experiment.onFrame(frame(200, 400, 400, dipPose(160.0))).phase)
    }

    @Test
    fun invalidDimensionsAreRejectedBeforeAnyGeometry() {
        val experiment = DipCycleExperiment(config)
        assertEquals(
            DipReason.INVALID_DIMENSIONS,
            experiment.onFrame(frame(0, 0, 400, dipPose(160.0))).reason,
        )
    }

    @Test
    fun theRightSideUsesTheRightLandmarkChain() {
        val rightConfig = config.copy(side = ArmSide.RIGHT)
        val leftOnly = frame(0, 400, 400, dipPose(160.0))

        assertEquals(DipReason.MISSING_LANDMARK, measureDipSupport(leftOnly, rightConfig).reason)
        assertEquals(DipReason.NONE, measureDipSupport(leftOnly, config).reason)
    }

    @Test
    fun theConfigRejectsIncoherentPrototypeParameters() {
        assertFailsWith<IllegalArgumentException> { config.copy(flexedElbowDegrees = 170.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(extendedElbowDegrees = 190.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumShoulderAboveWristFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumTorsoTiltDegrees = 90.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = 1.5) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 1) }
    }

    private fun frame(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>) =
        DipPoseFrame(timestampNs, width, height, landmarks)

    /**
     * Square frames keep normalized units and source pixels on the same scale, so the angles
     * asserted above are exactly the constructed ones.
     *
     * The arm is built as two equal segments meeting at the elbow: the upper arm points straight
     * up to the shoulder and the forearm is rotated away from it by [elbowDegrees], so 180 is a
     * locked-out support and smaller values are progressively deeper. The hip is placed down the
     * torso axis at [torsoTiltDegrees] from image-vertical.
     */
    private fun dipPose(
        elbowDegrees: Double,
        torsoTiltDegrees: Double = 0.0,
        confidence: Double = .9,
    ): List<Landmark> {
        val elbowX = .5
        val elbowY = .55
        val limb = .20
        val shoulderX = elbowX
        val shoulderY = elbowY - limb
        val radians = elbowDegrees * PI / 180.0
        val wristX = elbowX + limb * sin(radians)
        val wristY = elbowY - limb * cos(radians)
        val tilt = torsoTiltDegrees * PI / 180.0
        val hipX = shoulderX + .35 * sin(tilt)
        val hipY = shoulderY + .35 * cos(tilt)
        return landmarks(
            shoulder = shoulderX to shoulderY,
            elbow = elbowX to elbowY,
            wrist = wristX to wristY,
            hip = hipX to hipY,
            confidence = confidence,
        )
    }

    /** Same elbow parametrisation, but hanging from an overhead bar: the wrist is above the shoulder. */
    private fun overheadPose(elbowDegrees: Double, confidence: Double = .9): List<Landmark> {
        val elbowX = .5
        val elbowY = .45
        val limb = .20
        val shoulderX = elbowX
        val shoulderY = elbowY + limb
        val radians = elbowDegrees * PI / 180.0
        val wristX = elbowX + limb * sin(radians)
        val wristY = elbowY + limb * cos(radians)
        return landmarks(
            shoulder = shoulderX to shoulderY,
            elbow = elbowX to elbowY,
            wrist = wristX to wristY,
            hip = shoulderX to (shoulderY + .3),
            confidence = confidence,
        )
    }

    private fun landmarks(
        shoulder: Pair<Double, Double>,
        elbow: Pair<Double, Double>,
        wrist: Pair<Double, Double>,
        hip: Pair<Double, Double>,
        confidence: Double = .9,
    ): List<Landmark> = listOf(
        landmark(11, shoulder, confidence),
        landmark(13, elbow, confidence),
        landmark(15, wrist, confidence),
        landmark(23, hip, confidence),
    )

    private fun landmark(index: Int, point: Pair<Double, Double>, confidence: Double) = Landmark(
        index = index,
        name = null,
        x = point.first,
        y = point.second,
        z = 0.0,
        visibility = confidence,
        presence = confidence,
    )
}
