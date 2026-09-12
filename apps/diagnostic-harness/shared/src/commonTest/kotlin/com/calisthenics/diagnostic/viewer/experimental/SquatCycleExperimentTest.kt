package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SquatCycleExperimentTest {
    private val config = SquatConfig(
        side = ArmSide.LEFT,
        extendedDegrees = 160.0,
        flexedDegrees = 80.0,
        minimumConfidence = .8,
        maximumGapNs = 500,
        minimumEndpointSamples = 2,
    )

    @Test
    fun measuresKneeAngleWithSourcePixelAspectRatio() {
        val measurement = measureSquatKnee(frame(0, 400, 200, knee(
            hip = Pair(.25, .25),
            knee = Pair(.5, .5),
            ankle = Pair(.75, .5),
        )), config)

        assertEquals(153.4349488, measurement.kneeDegrees!!, 1e-5)
        assertEquals(SquatReason.NONE, measurement.reason)
    }

    @Test
    fun countsOnlyStandingFlexedStandingWithEndpointEvidence() {
        val experiment = SquatCycleExperiment(config)

        assertEquals(SquatPhase.WAITING, experiment.onFrame(frame(0, 400, 200, kneeAt(170.0))).phase)
        assertEquals(SquatPhase.STANDING, experiment.onFrame(frame(100, 400, 200, kneeAt(170.0))).phase)
        assertEquals(SquatPhase.FLEXING, experiment.onFrame(frame(200, 400, 200, kneeAt(120.0))).phase)
        assertEquals(SquatPhase.FLEXING, experiment.onFrame(frame(300, 400, 200, kneeAt(70.0))).phase)
        assertEquals(SquatPhase.FLEXED, experiment.onFrame(frame(400, 400, 200, kneeAt(70.0))).phase)
        assertEquals(SquatPhase.EXTENDING, experiment.onFrame(frame(500, 400, 200, kneeAt(120.0))).phase)
        assertEquals(0, experiment.onFrame(frame(600, 400, 200, kneeAt(170.0))).count)
        val completed = experiment.onFrame(frame(700, 400, 200, kneeAt(170.0)))

        assertEquals(1, completed.count)
        assertEquals(SquatPhase.STANDING, completed.phase)
        assertEquals(SquatReason.NONE, completed.reason)
        assertEquals(true, completed.counted)
    }

    @Test
    fun partialExcursionDoesNotCountAndDoesNotReuseOldStandingEvidence() {
        val experiment = SquatCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0, 300L to 170.0, 400L to 170.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        assertEquals(0, experiment.count)
        assertEquals(SquatPhase.STANDING, experiment.phase)
        assertEquals(false, experiment.onFrame(frame(500, 400, 200, kneeAt(70.0))).counted)
    }

    @Test
    fun noPoseAndCaptureGapInvalidateAnUnfinishedCycle() {
        val experiment = SquatCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        val noPose = experiment.onFrame(frame(300, 400, 200, emptyList()))
        assertNull(noPose.kneeDegrees)
        assertEquals(SquatReason.NO_POSE, noPose.reason)
        assertEquals(SquatPhase.UNCERTAIN, noPose.phase)

        experiment.onFrame(frame(1_000, 400, 200, kneeAt(170.0)))
        val afterGap = experiment.onFrame(frame(1_600, 400, 200, kneeAt(70.0)))
        assertEquals(0, afterGap.count)
        assertEquals(SquatReason.CAPTURE_GAP, afterGap.reason)
        assertEquals(SquatPhase.WAITING, afterGap.phase)
    }

    @Test
    fun sustainedNoPoseNeverAdvancesOrStitchesAnUnfinishedCandidate() {
        val experiment = SquatCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        for (timestamp in 300L..1_000L step 100L) {
            val result = experiment.onFrame(frame(timestamp, 400, 200, emptyList()))
            assertEquals(SquatReason.NO_POSE, result.reason)
            assertEquals(SquatPhase.UNCERTAIN, result.phase)
            assertEquals(0, result.count)
            assertEquals(false, result.counted)
        }
    }

    @Test
    fun endpointEvidenceMustBeConsecutiveAtBothExtremes() {
        val experiment = SquatCycleExperiment(config)
        listOf(
            0L to 170.0, 100L to 170.0, 200L to 120.0,
            300L to 70.0, 400L to 120.0, 500L to 70.0,
            600L to 120.0, 700L to 170.0, 800L to 170.0,
        ).forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        assertEquals(0, experiment.count)

        val returningEvidence = SquatCycleExperiment(config)
        listOf(
            0L to 170.0, 100L to 170.0, 200L to 120.0,
            300L to 70.0, 400L to 70.0, 500L to 120.0,
            600L to 170.0, 700L to 120.0, 800L to 170.0,
        ).forEach { (timestamp, angle) -> returningEvidence.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        assertEquals(0, returningEvidence.count)
    }

    @Test
    fun negativeTimestampInvalidatesPendingEvidenceBeforeLaterFrames() {
        val experiment = SquatCycleExperiment(config)
        experiment.onFrame(frame(0, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(100, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(200, 400, 200, kneeAt(120.0)))

        val invalid = experiment.onFrame(frame(-1, 400, 200, kneeAt(70.0)))
        assertEquals(SquatReason.INVALID_TIMESTAMP, invalid.reason)
        assertEquals(SquatPhase.UNCERTAIN, invalid.phase)
        experiment.onFrame(frame(300, 400, 200, kneeAt(70.0)))
        experiment.onFrame(frame(400, 400, 200, kneeAt(120.0)))
        experiment.onFrame(frame(500, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(600, 400, 200, kneeAt(170.0)))

        assertEquals(0, experiment.count)
    }

    @Test
    fun flexedEndpointRequiresBilateralKneeFlexion() {
        val unilateral = SquatCycleExperiment(config)
        listOf(
            0L to 170.0,
            100L to 170.0,
            200L to 120.0,
            300L to 70.0,
            400L to 70.0,
            500L to 120.0,
            600L to 170.0,
            700L to 170.0,
            800L to 170.0,
        ).forEach { (timestamp, angle) ->
            val result = unilateral.onFrame(frameWithSides(timestamp, angle, 170.0))
            if (timestamp == 300L) {
                assertEquals(SquatReason.UNILATERAL_FLEXION, result.reason)
                assertEquals(SquatPhase.FLEXING, result.phase)
            }
        }
        assertEquals(0, unilateral.count)

        val bilateral = SquatCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0, 300L to 70.0, 400L to 70.0,
            500L to 120.0, 600L to 170.0, 700L to 170.0, 800L to 170.0)
            .forEach { (timestamp, angle) -> bilateral.onFrame(frameWithSides(timestamp, angle, angle)) }
        assertEquals(1, bilateral.count)

        val asynchronous = SquatCycleExperiment(config)
        asynchronous.onFrame(frameWithSides(0, 170.0, 170.0))
        asynchronous.onFrame(frameWithSides(100, 170.0, 170.0))
        asynchronous.onFrame(frameWithSides(200, 120.0, 120.0))
        val selectedFirst = asynchronous.onFrame(frameWithSides(300, 70.0, 120.0))
        assertEquals(SquatReason.UNILATERAL_FLEXION, selectedFirst.reason)
        assertEquals(SquatPhase.FLEXING, selectedFirst.phase)
        asynchronous.onFrame(frameWithSides(400, 70.0, 70.0))
        asynchronous.onFrame(frameWithSides(500, 70.0, 70.0))
        asynchronous.onFrame(frameWithSides(600, 120.0, 120.0))
        asynchronous.onFrame(frameWithSides(700, 170.0, 170.0))
        asynchronous.onFrame(frameWithSides(800, 170.0, 170.0))
        assertEquals(1, asynchronous.count)
    }

    @Test
    fun missingOrLowConfidenceOppositeKneeInvalidatesAtFlexedEndpoint() {
        for (opposite in listOf(emptyList(), kneeAt(70.0, 24, 26, 28).map {
            if (it.index == 26) it.copy(visibility = .79) else it
        })) {
            val experiment = SquatCycleExperiment(config)
            experiment.onFrame(frameWithSides(0, 170.0, 170.0))
            experiment.onFrame(frameWithSides(100, 170.0, 170.0))
            experiment.onFrame(frameWithSides(200, 120.0, 170.0))
            val result = experiment.onFrame(
                SquatPoseFrame(300, 400, 200, kneeAt(70.0) + opposite),
            )
            assertEquals(SquatReason.OPPOSITE_KNEE_UNAVAILABLE, result.reason)
            assertEquals(SquatPhase.UNCERTAIN, result.phase)
            assertEquals(0, result.count)
        }
    }

    @Test
    fun repeatedAndBackwardsTimestampsCannotAdvanceTheCandidate() {
        val experiment = SquatCycleExperiment(config)
        experiment.onFrame(frame(0, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(100, 400, 200, kneeAt(170.0)))

        val duplicate = experiment.onFrame(frame(100, 400, 200, kneeAt(70.0)))
        assertEquals(SquatReason.DUPLICATE_TIMESTAMP, duplicate.reason)
        assertEquals(0, duplicate.count)
        val backwards = experiment.onFrame(frame(50, 400, 200, kneeAt(70.0)))
        assertEquals(SquatReason.BACKWARDS_TIMESTAMP, backwards.reason)
        assertEquals(0, backwards.count)
    }

    @Test
    fun unusableLandmarksDoNotProduceKneeAngles() {
        val valid = kneeAt(170.0)
        val cases = listOf(
            valid.dropLast(1),
            valid + valid.last(),
            valid.map { if (it.index == 25) it.copy(x = Double.NaN) else it },
            valid.map { if (it.index == 25) it.copy(visibility = .79) else it },
            valid.map { if (it.index == 25) it.copy(presence = Double.POSITIVE_INFINITY) else it },
        )

        cases.forEach { landmarks ->
            val result = measureSquatKnee(frame(0, 400, 200, landmarks), config)
            assertNull(result.kneeDegrees, landmarks.toString())
            assertEquals(true, result.reason != SquatReason.NONE, landmarks.toString())
        }
    }

    @Test
    fun configRejectsNonFiniteOutOfBoundsAndInvertedThresholds() {
        assertFailsWith<IllegalArgumentException> { config.copy(extendedDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(extendedDegrees = 181.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(flexedDegrees = 160.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = -0.1) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 0) }
    }

    private fun frame(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): SquatPoseFrame {
        val withOpposite = if (landmarks.isEmpty() || landmarks.any { it.index in setOf(24, 26, 28) }) {
            landmarks
        } else {
            landmarks + landmarks.mapNotNull { landmark ->
                when (landmark.index) {
                    23 -> landmark.copy(index = 24)
                    25 -> landmark.copy(index = 26)
                    27 -> landmark.copy(index = 28)
                    else -> null
                }
            }
        }
        return SquatPoseFrame(timestampNs, width, height, withOpposite)
    }

    private fun kneeAt(angle: Double): List<Landmark> = when (angle) {
        170.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.52, .75))
        120.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.716506, .75))
        70.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.617462, .415))
        else -> error("test angle not defined: $angle")
    }

    private fun kneeAt(angle: Double, hipIndex: Int, kneeIndex: Int, ankleIndex: Int): List<Landmark> = when (angle) {
        170.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.52, .75), hipIndex, kneeIndex, ankleIndex)
        120.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.716506, .75), hipIndex, kneeIndex, ankleIndex)
        70.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.617462, .415), hipIndex, kneeIndex, ankleIndex)
        else -> error("test angle not defined: $angle")
    }

    private fun frameWithSides(timestampNs: Long, chosenAngle: Double, oppositeAngle: Double) =
        frame(timestampNs, 400, 200, kneeAt(chosenAngle) + kneeAt(oppositeAngle, 24, 26, 28))

    private fun knee(
        hip: Pair<Double, Double>,
        knee: Pair<Double, Double>,
        ankle: Pair<Double, Double>,
        hipIndex: Int = 23,
        kneeIndex: Int = 25,
        ankleIndex: Int = 27,
    ) = listOf(
        Landmark(hipIndex, null, hip.first, hip.second, 0.0, .95, .95),
        Landmark(kneeIndex, null, knee.first, knee.second, 0.0, .95, .95),
        Landmark(ankleIndex, null, ankle.first, ankle.second, 0.0, .95, .95),
    )
}
