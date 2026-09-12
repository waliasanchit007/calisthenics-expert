package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SitupCycleExperimentTest {
    // Frozen hypothesis thresholds from the reviewed design, except minimum_endpoint_samples,
    // which is 2 here so each behavioural sequence stays short and readable.
    private val config = SitupConfig(
        side = ArmSide.LEFT,
        reclinedHipMinDegrees = 130.0,
        raisedHipMaxDegrees = 85.0,
        reclinedTrunkMaxDegrees = 35.0,
        raisedTrunkMinDegrees = 45.0,
        minimumTrunkRiseDegrees = 25.0,
        minimumConfidence = 0.5,
        maximumGapNs = 500,
        minimumEndpointSamples = 2,
    )

    @Test
    fun measuresHipAngleAndTrunkElevationInSourcePixels() {
        // Hand-derived: in a 400x200 source, shoulder (80,80), hip (200,100), knee (300,50) px.
        // Hip vectors (-120,-20) and (100,-50) subtend 143.9726266 degrees, and the shoulder-hip
        // vector sits 9.4623222 degrees off image horizontal. Measuring in raw normalized units
        // instead would report 116.565 and 18.435 degrees.
        val measurement = measureSitupTrunk(
            frame(0, 400, 200, trunk(shoulder = 0.2 to 0.4, hip = 0.5 to 0.5, knee = 0.75 to 0.25)),
            config,
        )

        assertEquals(143.9726266, measurement.hipDegrees!!, 1e-6)
        assertEquals(9.4623222, measurement.torsoElevationDegrees!!, 1e-6)
        assertEquals(SitupReason.NONE, measurement.reason)
    }

    @Test
    fun mirroringTheSourceHorizontallyPreservesBothMeasurements() {
        // A front-camera mirror flips x only; neither the hip angle nor the unsigned elevation may
        // move. (Camera roll is NOT claimed invariant and is not asserted here.)
        val upright = trunk(shoulder = 0.2 to 0.4, hip = 0.5 to 0.5, knee = 0.75 to 0.25)
        val mirrored = upright.map { it.copy(x = 1.0 - it.x) }

        val direct = measureSitupTrunk(frame(0, 400, 200, upright), config)
        val flipped = measureSitupTrunk(frame(0, 400, 200, mirrored), config)

        assertEquals(direct.hipDegrees!!, flipped.hipDegrees!!, 1e-9)
        assertEquals(direct.torsoElevationDegrees!!, flipped.torsoElevationDegrees!!, 1e-9)
    }

    @Test
    fun unusableFramesProduceNoMeasurementAndATypedReason() {
        val usable = reclined()
        val cases = listOf(
            frame(0, 400, 400, emptyList()) to SitupReason.NO_POSE,
            frame(0, 0, 400, usable) to SitupReason.INVALID_DIMENSIONS,
            frame(0, 400, -1, usable) to SitupReason.INVALID_DIMENSIONS,
            frame(0, 400, 400, usable + usable.last()) to SitupReason.DUPLICATE_LANDMARK_INDEX,
            frame(0, 400, 400, usable.dropLast(1)) to SitupReason.MISSING_LANDMARK,
            frame(0, 400, 400, usable.map { if (it.index == 23) it.copy(x = Double.NaN) else it }) to
                SitupReason.INVALID_LANDMARK,
            frame(0, 400, 400, usable.map { if (it.index == 25) it.copy(y = 1.4) else it }) to
                SitupReason.INVALID_LANDMARK,
            frame(0, 400, 400, usable.map { if (it.index == 11) it.copy(visibility = 0.49) else it }) to
                SitupReason.LOW_CONFIDENCE,
            frame(0, 400, 400, usable.map { if (it.index == 11) it.copy(presence = null) else it }) to
                SitupReason.LOW_CONFIDENCE,
            frame(0, 400, 400, usable.map { if (it.index == 11) it.copy(presence = Double.POSITIVE_INFINITY) else it }) to
                SitupReason.LOW_CONFIDENCE,
            // Shoulder collapsed onto the hip: no trunk direction exists to measure.
            frame(0, 400, 400, usable.map { if (it.index == 11) it.copy(x = 0.5, y = 0.5) else it }) to
                SitupReason.ZERO_LENGTH_SEGMENT,
            // Knee collapsed onto the hip: no thigh direction exists to measure.
            frame(0, 400, 400, usable.map { if (it.index == 25) it.copy(x = 0.5, y = 0.5) else it }) to
                SitupReason.ZERO_LENGTH_SEGMENT,
        )

        cases.forEach { (unusable, expected) ->
            val measurement = measureSitupTrunk(unusable, config)
            assertNull(measurement.hipDegrees, expected.name)
            assertNull(measurement.torsoElevationDegrees, expected.name)
            assertEquals(expected, measurement.reason)
        }
    }

    @Test
    fun onlyTheConfiguredSideIsMeasuredWhenTheOtherSideIsOccluded() {
        val leftOnly = reclined()

        assertEquals(SitupReason.NONE, measureSitupTrunk(frame(0, 400, 400, leftOnly), config).reason)
        val right = measureSitupTrunk(frame(0, 400, 400, leftOnly), config.copy(side = ArmSide.RIGHT))
        assertNull(right.hipDegrees)
        assertEquals(SitupReason.MISSING_LANDMARK, right.reason)
    }

    @Test
    fun configRejectsNonFiniteOutOfRangeAndMisorderedThresholds() {
        assertFailsWith<IllegalArgumentException> { config.copy(reclinedHipMinDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(reclinedHipMinDegrees = 181.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(raisedHipMaxDegrees = -1.0) }
        // A raised hip band that is not strictly tighter than the reclined band is contradictory.
        assertFailsWith<IllegalArgumentException> { config.copy(raisedHipMaxDegrees = 130.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(reclinedTrunkMaxDegrees = 91.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(raisedTrunkMinDegrees = 35.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumTrunkRiseDegrees = -0.5) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumTrunkRiseDegrees = 91.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = 1.5) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 0) }
    }

    @Test
    fun countsTwoCompleteCyclesAndConfirmsOnlyAtTheReturningEndpointSample() {
        val experiment = SitupCycleExperiment(config)

        val first = experiment.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to reclinedPose(),
        )
        assertEquals(SitupPhase.RECLINED, first[1].phase)
        assertEquals(SitupPhase.RISING, first[2].phase)
        assertEquals(SitupPhase.RAISED, first[4].phase)
        assertEquals(SitupPhase.LOWERING, first[5].phase)
        // One returning reclined sample is not yet the configured consecutive support.
        assertEquals(0, first[6].count)
        assertEquals(false, first[6].counted)

        val completing = experiment.onFrame(frame(700, 400, 400, reclinedPose()))
        assertEquals(1, completing.count)
        assertEquals(true, completing.counted)
        assertEquals(SitupPhase.RECLINED, completing.phase)
        assertEquals(SitupReason.NONE, completing.reason)
        val cycle = completing.closedCycle!!
        assertEquals(SitupCycleOutcome.COUNTED, cycle.outcome)
        assertEquals(200L, cycle.departureTimestampNs)
        assertEquals(300L, cycle.raisedTimestampNs)
        assertEquals(600L, cycle.returnTimestampNs)
        assertEquals(70.0, cycle.minimumHipDegrees, 1e-6)
        assertEquals(60.0, cycle.observedTrunkRiseDegrees, 1e-6)

        val second = experiment.feed(
            800L to midPose(), 900L to raisedPose(), 1_000L to raisedPose(),
            1_100L to midPose(), 1_200L to reclinedPose(), 1_300L to reclinedPose(),
        )
        assertEquals(2, second.last().count)
        assertEquals(SitupCycleOutcome.COUNTED, second.last().closedCycle!!.outcome)
        assertEquals(800L, second.last().closedCycle!!.departureTimestampNs)
    }

    @Test
    fun aRaisedPositionWithoutTheRequiredRiseFromTheReclinedAnchorNeverCounts() {
        // The reclined dwell already sits at the top of the reclined trunk band (35 degrees) and the
        // raised pose satisfies both raw raised bands, but only rises 15 degrees above the anchor —
        // below the configured 25-degree relative rise.
        val raised = measureSitupTrunk(frame(0, 400, 400, pose(80.0, 50.0)), config)
        assertTrue(raised.hipDegrees!! <= config.raisedHipMaxDegrees)
        assertTrue(raised.torsoElevationDegrees!! >= config.raisedTrunkMinDegrees)

        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to reclinedPose(35.0), 100L to reclinedPose(35.0), 200L to pose(110.0, 40.0),
            300L to pose(80.0, 50.0), 400L to pose(80.0, 50.0), 500L to pose(110.0, 40.0),
            600L to reclinedPose(35.0), 700L to reclinedPose(35.0),
        )

        assertEquals(SitupPhase.RISING, results[4].phase)
        assertEquals(0, results.last().count)
    }

    @Test
    fun theReclinedAnchorIsTheHighestTrunkElevationOfTheReclinedDwell() {
        // Anchoring on the first (5 degrees) or the confirming (20 degrees) reclined sample instead
        // of the dwell maximum (35 degrees) would let this 55-degree raised pose clear the 25-degree
        // relative rise and count.
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to reclinedPose(5.0), 100L to reclinedPose(20.0), 200L to reclinedPose(35.0),
            300L to pose(110.0, 40.0), 400L to pose(80.0, 55.0), 500L to pose(80.0, 55.0),
            600L to pose(110.0, 40.0), 700L to reclinedPose(5.0), 800L to reclinedPose(5.0),
        )

        assertEquals(SitupPhase.RISING, results[5].phase)
        assertEquals(0, results.last().count)
    }

    @Test
    fun aPartialRiseThatReturnsBeforeTheRaisedBandIsConfirmedIsAbandoned() {
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to reclinedPose(), 400L to reclinedPose(),
        )

        val abandoned = results[3].closedCycle!!
        assertEquals(SitupCycleOutcome.ABANDONED, abandoned.outcome)
        assertEquals(200L, abandoned.departureTimestampNs)
        assertNull(abandoned.raisedTimestampNs)
        assertNull(abandoned.returnTimestampNs)
        assertEquals(0, results.last().count)
        // Reclined support is rebuilt from the returning samples, not inherited from the old dwell.
        assertEquals(SitupPhase.RECLINED, results[4].phase)
    }

    @Test
    fun aDirectReclinedToRaisedJumpCannotCount() {
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to raisedPose(),
            300L to raisedPose(), 400L to midPose(), 500L to reclinedPose(),
            600L to reclinedPose(),
        )

        val abandoned = results[2].closedCycle!!
        assertEquals(SitupCycleOutcome.ABANDONED, abandoned.outcome)
        assertNull(abandoned.raisedTimestampNs)
        assertEquals(SitupPhase.WAITING, results[2].phase)
        assertEquals(0, results.last().count)
    }

    @Test
    fun aDirectRaisedToReclinedJumpCannotCount() {
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to reclinedPose(),
            600L to reclinedPose(),
        )

        val abandoned = results[5].closedCycle!!
        assertEquals(SitupCycleOutcome.ABANDONED, abandoned.outcome)
        assertEquals(300L, abandoned.raisedTimestampNs)
        assertNull(abandoned.returnTimestampNs)
        assertEquals(0, results.last().count)
    }

    @Test
    fun endpointConfirmationRequiresConsecutiveSupportAtBothEnds() {
        val entry = SitupCycleExperiment(config)
        val entryResults = entry.feed(
            0L to reclinedPose(), 100L to midPose(), 200L to reclinedPose(),
            300L to midPose(), 400L to reclinedPose(),
        )
        assertEquals(SitupPhase.WAITING, entryResults.last().phase)
        assertEquals(0, entryResults.last().count)

        val exit = SitupCycleExperiment(config)
        val exitResults = exit.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to reclinedPose(), 700L to midPose(), 800L to reclinedPose(),
            900L to midPose(), 1_000L to reclinedPose(),
        )
        assertEquals(SitupPhase.LOWERING, exitResults.last().phase)
        assertEquals(0, exitResults.last().count)
    }

    @Test
    fun aCaptureGapInterruptsTheOpenCandidateAndCannotBridgeACycle() {
        val experiment = SitupCycleExperiment(config)
        experiment.feed(0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose())

        val afterGap = experiment.onFrame(frame(1_000, 400, 400, raisedPose()))
        assertEquals(SitupReason.CAPTURE_GAP, afterGap.reason)
        val interrupted = afterGap.closedCycle!!
        assertEquals(SitupCycleOutcome.TRACKING_INTERRUPTED, interrupted.outcome)
        assertEquals(200L, interrupted.departureTimestampNs)

        val rest = experiment.feed(
            1_100L to raisedPose(), 1_200L to midPose(),
            1_300L to reclinedPose(), 1_400L to reclinedPose(),
        )
        assertEquals(0, rest.last().count)
    }

    @Test
    fun anUnusableFrameClearsPartialEvidenceWhileEarlierCountsSurvive() {
        val experiment = SitupCycleExperiment(config)
        experiment.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to reclinedPose(), 700L to reclinedPose(),
        )
        assertEquals(1, experiment.count)

        val partial = experiment.feed(800L to midPose(), 900L to raisedPose(), 1_000L to raisedPose())
        assertEquals(SitupPhase.RAISED, partial.last().phase)

        val lost = experiment.onFrame(frame(1_100, 400, 400, emptyList()))
        assertEquals(SitupReason.NO_POSE, lost.reason)
        assertEquals(SitupPhase.UNCERTAIN, lost.phase)
        assertEquals(SitupCycleOutcome.TRACKING_INTERRUPTED, lost.closedCycle!!.outcome)
        assertEquals(1, lost.count)
        assertNull(lost.hipDegrees)

        // The interrupted attempt cannot resume: the returning samples alone must not complete it.
        val resumed = experiment.feed(1_200L to reclinedPose(), 1_300L to reclinedPose())
        assertEquals(1, resumed.last().count)
    }

    @Test
    fun invalidDuplicateAndBackwardsTimestampsCannotAdvanceTheCandidate() {
        val experiment = SitupCycleExperiment(config)
        experiment.feed(0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose())

        val invalid = experiment.onFrame(frame(-1, 400, 400, raisedPose()))
        assertEquals(SitupReason.INVALID_TIMESTAMP, invalid.reason)
        assertEquals(SitupPhase.UNCERTAIN, invalid.phase)
        assertEquals(SitupCycleOutcome.TRACKING_INTERRUPTED, invalid.closedCycle!!.outcome)

        val duplicate = experiment.onFrame(frame(200, 400, 400, reclinedPose()))
        assertEquals(SitupReason.DUPLICATE_TIMESTAMP, duplicate.reason)
        val backwards = experiment.onFrame(frame(150, 400, 400, reclinedPose()))
        assertEquals(SitupReason.BACKWARDS_TIMESTAMP, backwards.reason)
        assertEquals(0, backwards.count)
        assertNull(backwards.hipDegrees)
    }

    @Test
    fun finishCensorsOnlyAnOpenCandidateAndOnlyOnce() {
        val open = SitupCycleExperiment(config)
        open.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(),
        )

        val censored = open.finish()!!
        assertEquals(SitupCycleOutcome.CENSORED_AT_END_OF_INPUT, censored.outcome)
        assertEquals(200L, censored.departureTimestampNs)
        assertEquals(300L, censored.raisedTimestampNs)
        assertNull(censored.returnTimestampNs)
        assertEquals(0, open.count)
        assertNull(open.finish())

        val completed = SitupCycleExperiment(config)
        completed.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to reclinedPose(), 700L to reclinedPose(),
        )
        assertEquals(1, completed.count)
        assertNull(completed.finish())
    }

    @Test
    fun aSessionStartingAlreadyRaisedDoesNotCountTheOpeningDescent() {
        val experiment = SitupCycleExperiment(config)
        val descent = experiment.feed(
            0L to raisedPose(), 100L to raisedPose(), 200L to midPose(),
            300L to reclinedPose(), 400L to reclinedPose(),
        )
        assertEquals(0, descent.last().count)

        val cycle = experiment.feed(
            500L to midPose(), 600L to raisedPose(), 700L to raisedPose(),
            800L to midPose(), 900L to reclinedPose(), 1_000L to reclinedPose(),
        )
        assertEquals(1, cycle.last().count)
    }

    @Test
    fun aHesitationBackToTheRaisedBandStillCountsOnceOnTheFinalReturn() {
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to reclinedPose(), 100L to reclinedPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to raisedPose(), 700L to midPose(), 800L to reclinedPose(),
            900L to reclinedPose(),
        )

        assertEquals(SitupPhase.RAISED, results[6].phase)
        assertEquals(1, results.last().count)
        val cycle = results.last().closedCycle!!
        assertEquals(SitupCycleOutcome.COUNTED, cycle.outcome)
        assertEquals(300L, cycle.raisedTimestampNs)
        assertEquals(800L, cycle.returnTimestampNs)
    }

    @Test
    fun aStandingHipHingeWithAChangingHipAngleNeverCounts() {
        // Upright throughout: the trunk never enters the reclined elevation band, so the hip angle
        // sweeping across both hip bands (175 -> 80 -> 175 degrees) cannot form a candidate cycle.
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to pose(175.0, 88.0), 100L to pose(175.0, 88.0), 200L to pose(140.0, 80.0),
            300L to pose(100.0, 70.0), 400L to pose(80.0, 60.0), 500L to pose(80.0, 60.0),
            600L to pose(100.0, 70.0), 700L to pose(140.0, 80.0), 800L to pose(175.0, 88.0),
            900L to pose(175.0, 88.0),
        )

        val hips = results.map { it.hipDegrees!! }
        assertTrue(hips.max() - hips.min() > 80.0, "the hinge must actually move the hip angle")
        assertEquals(0, results.last().count)
        assertEquals(SitupPhase.WAITING, results.last().phase)
    }

    @Test
    fun aSquatDescentWithAChangingHipAngleNeverCounts() {
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to pose(178.0, 85.0), 100L to pose(178.0, 85.0), 200L to pose(130.0, 82.0),
            300L to pose(95.0, 78.0), 400L to pose(75.0, 75.0), 500L to pose(75.0, 75.0),
            600L to pose(95.0, 78.0), 700L to pose(130.0, 82.0), 800L to pose(178.0, 85.0),
            900L to pose(178.0, 85.0),
        )

        val hips = results.map { it.hipDegrees!! }
        assertTrue(hips.max() - hips.min() > 80.0, "the squat must actually move the hip angle")
        assertEquals(0, results.last().count)
        assertEquals(SitupPhase.WAITING, results.last().phase)
    }

    @Test
    fun aLyingLegOnlyTuckWithAChangingHipAngleNeverCounts() {
        // The trunk stays flat on the floor (0 degrees) while the knees are drawn to the chest, so
        // the hip angle alone closes past the raised hip band without any trunk rise.
        val experiment = SitupCycleExperiment(config)
        val results = experiment.feed(
            0L to pose(150.0, 0.0), 100L to pose(150.0, 0.0), 200L to pose(110.0, 0.0),
            300L to pose(70.0, 0.0), 400L to pose(70.0, 0.0), 500L to pose(110.0, 0.0),
            600L to pose(150.0, 0.0), 700L to pose(150.0, 0.0),
        )

        val hips = results.map { it.hipDegrees!! }
        assertTrue(hips.min() <= config.raisedHipMaxDegrees, "the tuck must close the hip band")
        assertEquals(0, results.last().count)
    }

    @Test
    fun aBentKneeReclinedCycleAtOneHundredTwentyDegreesCountsOnlyWithARevisedReclinedHipMinimum() {
        // Characterization of already implemented configuration behaviour, not a production fix: a
        // bent-knee reclined posture never opens the hip past 120 degrees, so the frozen initial
        // 130-degree reclined hip minimum can never confirm the reclined endpoint on this geometry
        // and emits zero, while an explicit 110-degree minimum with every other gate unchanged
        // counts the same sequence once. Coordinates are constructed independently by this test;
        // no source footage, private landmark data or observed count informs them.
        val sequence = listOf(
            0L to pose(120.0, 15.0), 100L to pose(120.0, 15.0), 200L to pose(100.0, 30.0),
            300L to pose(70.0, 60.0), 400L to pose(70.0, 60.0), 500L to pose(100.0, 30.0),
            600L to pose(120.0, 15.0), 700L to pose(120.0, 15.0),
        )

        val initial = SitupCycleExperiment(config)
        val initialResults = initial.feed(*sequence.toTypedArray())
        val hips = initialResults.map { it.hipDegrees!! }
        assertTrue(hips.max() < config.reclinedHipMinDegrees, "the bent-knee recline must stay under 130")
        assertEquals(0, initialResults.last().count)

        val revised = SitupCycleExperiment(config.copy(reclinedHipMinDegrees = 110.0))
        val revisedResults = revised.feed(*sequence.toTypedArray())
        assertEquals(1, revisedResults.last().count)
        assertEquals(SitupCycleOutcome.COUNTED, revisedResults.last().closedCycle!!.outcome)
    }

    @Test
    fun aRevisedReclinedHipMinimumStillDoesNotWaiveTheAnchorRelativeRiseGate() {
        // Complementary pin: relaxing only the reclined hip minimum must not turn the trunk gates
        // into formalities. This reclined dwell sits at the top of the reclined trunk band, and the
        // raised pose satisfies both raw raised bands yet rises only 15 degrees above the anchor.
        val revisedConfig = config.copy(reclinedHipMinDegrees = 110.0)
        val raised = measureSitupTrunk(frame(0, 400, 400, pose(70.0, 50.0)), revisedConfig)
        assertTrue(raised.hipDegrees!! <= revisedConfig.raisedHipMaxDegrees)
        assertTrue(raised.torsoElevationDegrees!! >= revisedConfig.raisedTrunkMinDegrees)

        val experiment = SitupCycleExperiment(revisedConfig)
        val results = experiment.feed(
            0L to pose(120.0, 35.0), 100L to pose(120.0, 35.0), 200L to pose(100.0, 40.0),
            300L to pose(70.0, 50.0), 400L to pose(70.0, 50.0), 500L to pose(100.0, 40.0),
            600L to pose(120.0, 35.0), 700L to pose(120.0, 35.0),
        )

        assertEquals(SitupPhase.RECLINED, results[1].phase, "the revised minimum must admit this recline")
        assertEquals(SitupPhase.RISING, results[4].phase)
        assertEquals(0, results.last().count)
    }

    private fun frame(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>) =
        SitupPoseFrame(timestampNs, width, height, landmarks)

    private fun SitupCycleExperiment.feed(vararg steps: Pair<Long, List<Landmark>>) =
        steps.map { (timestampNs, landmarks) -> onFrame(frame(timestampNs, 400, 400, landmarks)) }

    private fun reclined() = reclinedPose()

    private fun reclinedPose(trunkDegrees: Double = 0.0) = pose(hipDegrees = 150.0, trunkDegrees = trunkDegrees)

    private fun midPose() = pose(hipDegrees = 110.0, trunkDegrees = 20.0)

    private fun raisedPose() = pose(hipDegrees = 70.0, trunkDegrees = 60.0)

    /**
     * Builds a square-frame side-view pose whose measured hip angle and trunk elevation equal the
     * requested degrees, by construction rather than by calling the code under test: the shoulder is
     * placed on a fixed-radius ray `trunkDegrees` above image horizontal from the hip, and the knee
     * on a ray a further `hipDegrees` around the hip. A 400x400 frame keeps pixel and normalized
     * geometry proportional so the placement stays hand-checkable.
     */
    private fun pose(
        hipDegrees: Double,
        trunkDegrees: Double,
        shoulderIndex: Int = 11,
        hipIndex: Int = 23,
        kneeIndex: Int = 25,
    ): List<Landmark> {
        val radians = { degrees: Double -> degrees * kotlin.math.PI / 180.0 }
        val shoulder = Pair(0.5 - 0.25 * cos(radians(trunkDegrees)), 0.5 - 0.25 * sin(radians(trunkDegrees)))
        val kneeRay = radians(180.0 + trunkDegrees + hipDegrees)
        val knee = Pair(0.5 + 0.2 * cos(kneeRay), 0.5 + 0.2 * sin(kneeRay))
        return trunk(shoulder, Pair(0.5, 0.5), knee, shoulderIndex, hipIndex, kneeIndex)
    }

    private fun trunk(
        shoulder: Pair<Double, Double>,
        hip: Pair<Double, Double>,
        knee: Pair<Double, Double>,
        shoulderIndex: Int = 11,
        hipIndex: Int = 23,
        kneeIndex: Int = 25,
    ) = listOf(
        Landmark(shoulderIndex, null, shoulder.first, shoulder.second, 0.0, 0.95, 0.95),
        Landmark(hipIndex, null, hip.first, hip.second, 0.0, 0.95, 0.95),
        Landmark(kneeIndex, null, knee.first, knee.second, 0.0, 0.95, 0.95),
    )
}
