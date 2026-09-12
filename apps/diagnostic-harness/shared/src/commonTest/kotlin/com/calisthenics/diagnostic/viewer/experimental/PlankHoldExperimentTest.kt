package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlankHoldExperimentTest {
    private val config = PlankConfig(
        side = ArmSide.LEFT,
        straightMinDegrees = 160.0,
        straightMaxDegrees = 180.0,
        minimumConfidence = .8,
        maximumGapNs = 500,
        minimumEntrySamples = 2,
        maximumOrientationDegreesFromHorizontal = 45.0,
    )

    @Test
    fun measuresBodyLineAngleWithSourcePixelAspectRatio() {
        val measurement = measurePlankBodyLine(frame(0, 400, 200, bodyLine(
            shoulder = Pair(.25, .25),
            hip = Pair(.5, .5),
            ankle = Pair(.75, .5),
        )), config)

        assertEquals(153.4349488, measurement.bodyLineDegrees!!, 1e-5)
        assertEquals(PlankReason.NONE, measurement.reason)
    }

    @Test
    fun uprightStandingBodyLineIsRejectedAsUnsupportedOrientationDespiteAppearingStraight() {
        // Shoulder/hip/ankle collinear and vertical in the image plane: geometrically "straight"
        // (180 degrees) exactly like a genuine plank, but this is a standing posture, not a plank.
        // Straightness alone cannot tell these apart; the orientation gate exists precisely for
        // this case. It does not attempt to distinguish a held plank from lying flat on a surface,
        // which remains an explicit, undismissed limitation of this body-line-hold measurement.
        val measurement = measurePlankBodyLine(frame(0, 400, 200, bodyLine(
            shoulder = Pair(.5, .1),
            hip = Pair(.5, .5),
            ankle = Pair(.5, .9),
        )), config)

        assertNull(measurement.bodyLineDegrees)
        assertEquals(PlankReason.UNSUPPORTED_ORIENTATION, measurement.reason)
    }

    @Test
    fun standingOrientationNeverConfirmsOrExtendsAHold() {
        val experiment = PlankHoldExperiment(config)
        val standing = bodyLine(shoulder = Pair(.5, .1), hip = Pair(.5, .5), ankle = Pair(.5, .9))

        repeat(5) { index ->
            val result = experiment.onFrame(frame(index * 100L, 400, 200, standing))
            assertEquals(PlankReason.UNSUPPORTED_ORIENTATION, result.reason)
            assertEquals(PlankState.UNCERTAIN, result.state)
            assertNull(result.closedHold)
        }
    }

    @Test
    fun holdRequiresConsecutiveEntrySamplesAndReportsEntryExitDurationAndCoverage() {
        val experiment = PlankHoldExperiment(config)

        assertEquals(PlankState.ENTERING, experiment.onFrame(frame(0, 400, 200, straightAt())).state)
        val confirmed = experiment.onFrame(frame(100, 400, 200, straightAt()))
        assertEquals(PlankState.HOLDING, confirmed.state)
        assertNull(confirmed.closedHold)

        experiment.onFrame(frame(300, 400, 200, straightAt()))
        val exiting = experiment.onFrame(frame(500, 400, 200, bentAt()))

        assertEquals(PlankState.WAITING, exiting.state)
        assertEquals(PlankReason.OUT_OF_BAND, exiting.reason)
        val closed = assertNotNull(exiting.closedHold)
        assertEquals(0L, closed.entryTimestampNs)
        assertEquals(300L, closed.exitTimestampNs)
        assertEquals(300L, closed.durationNs)
        assertEquals(300L, closed.observedNs)
        assertEquals(PlankReason.OUT_OF_BAND, closed.endReason)
    }

    @Test
    fun singleInBandSampleDoesNotConfirmAHold() {
        val experiment = PlankHoldExperiment(config)
        assertEquals(PlankState.ENTERING, experiment.onFrame(frame(0, 400, 200, straightAt())).state)
        val brokenEntry = experiment.onFrame(frame(100, 400, 200, bentAt()))
        assertEquals(PlankState.WAITING, brokenEntry.state)
        assertNull(brokenEntry.closedHold)
        assertNull(experiment.onFrame(frame(200, 400, 200, bentAt())).closedHold)
    }

    @Test
    fun gapWithinToleranceSurvivesButIsExcludedFromObservedCoverage() {
        val experiment = PlankHoldExperiment(config)
        experiment.onFrame(frame(0, 400, 200, straightAt()))
        experiment.onFrame(frame(100, 400, 200, straightAt()))

        val duringGap = experiment.onFrame(frame(400, 400, 200, emptyList()))
        assertEquals(PlankState.HOLDING, duringGap.state)
        assertEquals(PlankReason.NO_POSE, duringGap.reason)
        assertNull(duringGap.closedHold)

        val afterGap = experiment.onFrame(frame(500, 400, 200, straightAt()))
        assertEquals(PlankState.HOLDING, afterGap.state)
        assertNull(afterGap.closedHold)

        val exiting = experiment.onFrame(frame(700, 400, 200, bentAt()))
        val closed = assertNotNull(exiting.closedHold)
        // Exit is the last confirmed in-band sample (500), not the out-of-band sample itself (700):
        // the interval between a good sample and the next unobserved/exiting sample is never claimed.
        assertEquals(500L, closed.exitTimestampNs)
        assertEquals(500L, closed.durationNs)
        // 0->100 is a contiguous observed span (100). The 100->400 no-pose gap breaks contiguity, so
        // the isolated good sample at 500 cannot retroactively prove the unobserved span was held.
        assertEquals(100L, closed.observedNs)
    }

    @Test
    fun gapExceedingToleranceClosesTheHoldAtTheLastGoodSample() {
        val experiment = PlankHoldExperiment(config)
        experiment.onFrame(frame(0, 400, 200, straightAt()))
        experiment.onFrame(frame(100, 400, 200, straightAt()))

        val afterGap = experiment.onFrame(frame(700, 400, 200, emptyList()))
        assertEquals(PlankState.UNCERTAIN, afterGap.state)
        assertEquals(PlankReason.NO_POSE, afterGap.reason)
        val closed = assertNotNull(afterGap.closedHold)
        assertEquals(0L, closed.entryTimestampNs)
        assertEquals(100L, closed.exitTimestampNs)
        assertEquals(100L, closed.durationNs)
        assertEquals(100L, closed.observedNs)
        assertEquals(PlankReason.CAPTURE_GAP, closed.endReason)
    }

    @Test
    fun sustainedNoPoseNeverStitchesAnUnconfirmedCandidate() {
        val experiment = PlankHoldExperiment(config)
        experiment.onFrame(frame(0, 400, 200, straightAt()))

        for (timestamp in 100L..1_000L step 100L) {
            val result = experiment.onFrame(frame(timestamp, 400, 200, emptyList()))
            assertEquals(PlankReason.NO_POSE, result.reason)
            assertEquals(PlankState.UNCERTAIN, result.state)
            assertNull(result.closedHold)
        }
    }

    @Test
    fun negativeDuplicateAndBackwardsTimestampsCannotAdvanceOrStitchTheCandidate() {
        val experiment = PlankHoldExperiment(config)
        experiment.onFrame(frame(0, 400, 200, straightAt()))
        experiment.onFrame(frame(100, 400, 200, straightAt()))

        val negative = experiment.onFrame(frame(-1, 400, 200, straightAt()))
        assertEquals(PlankReason.INVALID_TIMESTAMP, negative.reason)
        assertEquals(PlankState.UNCERTAIN, negative.state)
        val closedByNegative = assertNotNull(negative.closedHold)
        assertEquals(PlankReason.INVALID_TIMESTAMP, closedByNegative.endReason)

        val fresh = PlankHoldExperiment(config)
        fresh.onFrame(frame(0, 400, 200, straightAt()))
        fresh.onFrame(frame(100, 400, 200, straightAt()))
        val duplicate = fresh.onFrame(frame(100, 400, 200, straightAt()))
        assertEquals(PlankReason.DUPLICATE_TIMESTAMP, duplicate.reason)
        assertNotNull(duplicate.closedHold)
        val backwards = fresh.onFrame(frame(50, 400, 200, straightAt()))
        assertEquals(PlankReason.BACKWARDS_TIMESTAMP, backwards.reason)
    }

    @Test
    fun unusableLandmarksDoNotProduceABodyLineAngle() {
        val valid = straightAt()
        val cases = listOf(
            valid.dropLast(1),
            valid + valid.last(),
            valid.map { if (it.index == 23) it.copy(x = Double.NaN) else it },
            valid.map { if (it.index == 23) it.copy(visibility = .79) else it },
            valid.map { if (it.index == 23) it.copy(presence = Double.POSITIVE_INFINITY) else it },
        )

        cases.forEach { landmarks ->
            val result = measurePlankBodyLine(frame(0, 400, 200, landmarks), config)
            assertNull(result.bodyLineDegrees, landmarks.toString())
            assertEquals(true, result.reason != PlankReason.NONE, landmarks.toString())
        }
    }

    @Test
    fun configRejectsNonFiniteOutOfBoundsAndInvertedThresholds() {
        assertFailsWith<IllegalArgumentException> { config.copy(straightMinDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(straightMinDegrees = 181.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(straightMinDegrees = 180.0, straightMaxDegrees = 180.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = -0.1) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEntrySamples = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumOrientationDegreesFromHorizontal = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumOrientationDegreesFromHorizontal = 91.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumOrientationDegreesFromHorizontal = -1.0) }
    }

    @Test
    fun entrySamplesSeparatedByExcessiveGapDoNotStitchIntoAConfirmedHold() {
        val experiment = PlankHoldExperiment(config)
        val first = experiment.onFrame(frame(0, 400, 200, straightAt()))
        assertEquals(PlankState.ENTERING, first.state)

        // No frame at all (not even an unusable one) arrives between these two in-band samples,
        // e.g. sparse sampling silently dropped rows; the gap must still be honored.
        val second = experiment.onFrame(frame(10_000, 400, 200, straightAt()))
        assertEquals(PlankState.ENTERING, second.state)
        assertNull(second.closedHold)

        // Confirmed only once two in-band samples land within the tolerated gap of each other.
        val confirmed = experiment.onFrame(frame(10_100, 400, 200, straightAt()))
        assertEquals(PlankState.HOLDING, confirmed.state)
    }

    @Test
    fun goodFramesSeparatedByExcessiveGapDuringHoldingCloseAtLastGoodAndRestartConservatively() {
        val experiment = PlankHoldExperiment(config)
        experiment.onFrame(frame(0, 400, 200, straightAt()))
        val confirmed = experiment.onFrame(frame(100, 400, 200, straightAt()))
        assertEquals(PlankState.HOLDING, confirmed.state)

        // The next observed row is in-band but arrives long after the tolerated gap, with no
        // intervening unusable-frame row at all.
        val afterGap = experiment.onFrame(frame(10_000, 400, 200, straightAt()))
        assertEquals(PlankReason.CAPTURE_GAP, afterGap.reason)
        val closed = assertNotNull(afterGap.closedHold)
        assertEquals(0L, closed.entryTimestampNs)
        assertEquals(100L, closed.exitTimestampNs)
        assertEquals(100L, closed.durationNs)
        assertEquals(100L, closed.observedNs)
        assertEquals(PlankReason.CAPTURE_GAP, closed.endReason)
        // The unobserved span is never counted as held, and the new sample starts a fresh,
        // unconfirmed candidate rather than silently continuing the old hold.
        assertEquals(PlankState.ENTERING, afterGap.state)
    }

    @Test
    fun recoveryAfterToleratedUnusableFrameStillRespectsTheGapBudgetFromLastGoodSample() {
        val experiment = PlankHoldExperiment(config)
        experiment.onFrame(frame(0, 400, 200, straightAt()))
        experiment.onFrame(frame(100, 400, 200, straightAt()))

        val duringGap = experiment.onFrame(frame(200, 400, 200, emptyList()))
        assertEquals(PlankState.HOLDING, duringGap.state)
        assertNull(duringGap.closedHold)

        // The unusable frame itself was within tolerance (100ns), but the eventual good recovery
        // arrives 800ns after the last good sample -- past maximumGapNs -- with no further
        // unusable-frame row to catch it via the advanceUnusable path.
        val delayedRecovery = experiment.onFrame(frame(1000, 400, 200, straightAt()))
        assertEquals(PlankReason.CAPTURE_GAP, delayedRecovery.reason)
        val closed = assertNotNull(delayedRecovery.closedHold)
        assertEquals(100L, closed.exitTimestampNs)
        assertEquals(PlankReason.CAPTURE_GAP, closed.endReason)
        assertEquals(PlankState.ENTERING, delayedRecovery.state)
    }

    @Test
    fun finishReturnsNullWhenNoHoldIsOpen() {
        val experiment = PlankHoldExperiment(config)
        assertNull(experiment.finish())
        experiment.onFrame(frame(0, 400, 200, straightAt()))
        assertNull(experiment.finish())
    }

    @Test
    fun finishCensorsAnOpenHoldAtEndOfInputClosingAtTheLastGoodSampleWithoutInventingAnExit() {
        val experiment = PlankHoldExperiment(config)
        experiment.onFrame(frame(0, 400, 200, straightAt()))
        experiment.onFrame(frame(100, 400, 200, straightAt()))
        experiment.onFrame(frame(300, 400, 200, straightAt()))

        val censored = assertNotNull(experiment.finish())
        assertEquals(0L, censored.entryTimestampNs)
        assertEquals(300L, censored.exitTimestampNs)
        assertEquals(300L, censored.durationNs)
        assertEquals(300L, censored.observedNs)
        assertEquals(PlankReason.CENSORED_AT_END_OF_INPUT, censored.endReason)
        // Nothing is left open to censor a second time.
        assertNull(experiment.finish())
    }

    private fun frame(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): PlankPoseFrame =
        PlankPoseFrame(timestampNs, width, height, landmarks)

    // A near-horizontal image-plane body line, matching this experiment's side-view assumption.
    private fun straightAt(): List<Landmark> = bodyLine(Pair(.2, .5), Pair(.5, .5), Pair(.8, .5))

    private fun bentAt(): List<Landmark> = bodyLine(Pair(.2, .4), Pair(.5, .6), Pair(.8, .4))

    private fun bodyLine(
        shoulder: Pair<Double, Double>,
        hip: Pair<Double, Double>,
        ankle: Pair<Double, Double>,
    ) = listOf(
        Landmark(11, null, shoulder.first, shoulder.second, 0.0, .95, .95),
        Landmark(23, null, hip.first, hip.second, 0.0, .95, .95),
        Landmark(27, null, ankle.first, ankle.second, 0.0, .95, .95),
    )
}
