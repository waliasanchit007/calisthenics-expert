package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the selected-mode pike push-up candidate. These are developmental image-plane
 * hypotheses only: they do not establish form quality, force, or automatic movement identity.
 */
class PikePushUpExperimentTest {
    private val config = PikeConfig(
        side = ArmSide.LEFT,
        extendedElbowDegrees = 155.0,
        flexedElbowDegrees = 100.0,
        minimumConfidence = 0.5,
        maximumGapNs = 250_000_000L,
        minimumEndpointSamples = 2,
        maximumHipAngleDegrees = 130.0,
        minimumKneeAngleDegrees = 150.0,
        minimumHipAboveShoulderFraction = 0.03,
    )

    @Test
    fun countsOnlySupportedLoweringLoweredRaisingSupportedSequence() {
        val experiment = PikePushUpExperiment(config)
        val elbowAngles = listOf(160.0, 160.0, 130.0, 90.0, 90.0, 130.0, 160.0, 160.0)

        val results = elbowAngles.mapIndexed { index, angle ->
            experiment.onFrame(frame(index * 100_000_000L, pikePose(angle)))
        }

        assertEquals(1, results.last().count)
        assertTrue(results.last().counted)
        assertEquals(PikePhase.SUPPORTED, results.last().phase)
    }

    @Test
    fun staticSupportedPikeDoesNotCountWithoutLoweringAndReturn() {
        val experiment = PikePushUpExperiment(config)

        repeat(8) { index -> experiment.onFrame(frame(index * 100_000_000L, pikePose(160.0))) }

        assertEquals(0, experiment.count)
        assertEquals(PikePhase.SUPPORTED, experiment.phase)
    }

    @Test
    fun ordinaryPushUpConfusionIsRejectedWhenHipIsStraight() {
        val measurement = measurePikeGeometry(frame(0, ordinaryPushUpPose()), config)

        assertEquals(PikeReason.HIP_ANGLE_TOO_OPEN, measurement.reason)
        assertNull(measurement.elbowDegrees)
    }

    @Test
    fun dipConfusionIsRejectedWhenHipIsBelowTheShoulder() {
        val measurement = measurePikeGeometry(frame(0, dipConfusionPose(160.0)), config)

        assertEquals(PikeReason.HIP_NOT_ABOVE_SHOULDER, measurement.reason)
        assertNull(measurement.hipAngleDegrees)
    }

    @Test
    fun missingRequiredJointResetsCandidateAndCannotCreateAnArtificialCycle() {
        val experiment = PikePushUpExperiment(config)
        experiment.onFrame(frame(0, pikePose(160.0)))
        experiment.onFrame(frame(100_000_000L, pikePose(160.0))) // arms the candidate
        experiment.onFrame(frame(200_000_000L, pikePose(130.0)))
        val missing = experiment.onFrame(
            frame(300_000_000L, pikePose(90.0).filterNot { it.index == 25 }),
        )
        experiment.onFrame(frame(400_000_000L, pikePose(90.0)))
        experiment.onFrame(frame(500_000_000L, pikePose(130.0)))
        experiment.onFrame(frame(600_000_000L, pikePose(160.0)))
        experiment.onFrame(frame(700_000_000L, pikePose(160.0)))

        assertEquals(PikeReason.MISSING_LANDMARK, missing.reason)
        assertEquals(0, experiment.count)
        assertEquals(PikePhase.SUPPORTED, experiment.phase)
    }

    @Test
    fun captureGapResetsCandidateAndDoesNotStitchAcrossDiscontinuity() {
        val experiment = PikePushUpExperiment(config)
        experiment.onFrame(frame(0, pikePose(160.0)))
        experiment.onFrame(frame(100_000_000L, pikePose(160.0)))
        experiment.onFrame(frame(200_000_000L, pikePose(130.0)))
        val gap = experiment.onFrame(frame(500_000_001L, pikePose(90.0)))

        assertEquals(PikeReason.CAPTURE_GAP, gap.reason)
        assertEquals(0, experiment.count)
        assertEquals(PikePhase.WAITING, experiment.phase)
    }

    private fun frame(timestampNs: Long, landmarks: List<Landmark>) =
        PikePoseFrame(timestampNs, 500, 500, landmarks)

    private fun pikePose(elbowDegrees: Double, confidence: Double = .9): List<Landmark> {
        val shoulder = .35 to .55
        val elbow = .35 to .70
        val limb = .15
        val radians = elbowDegrees * PI / 180.0
        val wrist = (elbow.first + limb * sin(radians)) to (elbow.second - limb * kotlin.math.cos(radians))
        return sixJoints(shoulder, elbow, wrist, .55 to .25, .65 to .55, .75 to .75, confidence)
    }

    private fun ordinaryPushUpPose(): List<Landmark> =
        sixJoints(.35 to .55, .35 to .70, .55 to .85, .55 to .50, .65 to .55, .75 to .60, .9)

    private fun dipConfusionPose(elbowDegrees: Double): List<Landmark> =
        sixJoints(.35 to .45, .35 to .60, .55 to .75, .55 to .80, .65 to .85, .75 to .90, .9)

    private fun sixJoints(
        shoulder: Pair<Double, Double>,
        elbow: Pair<Double, Double>,
        wrist: Pair<Double, Double>,
        hip: Pair<Double, Double>,
        knee: Pair<Double, Double>,
        ankle: Pair<Double, Double>,
        confidence: Double,
    ): List<Landmark> = listOf(
        landmark(11, shoulder, confidence), landmark(13, elbow, confidence), landmark(15, wrist, confidence),
        landmark(23, hip, confidence), landmark(25, knee, confidence), landmark(27, ankle, confidence),
    )

    private fun landmark(index: Int, point: Pair<Double, Double>, confidence: Double) = Landmark(
        index, null, point.first, point.second, 0.0, confidence, confidence,
    )
}
