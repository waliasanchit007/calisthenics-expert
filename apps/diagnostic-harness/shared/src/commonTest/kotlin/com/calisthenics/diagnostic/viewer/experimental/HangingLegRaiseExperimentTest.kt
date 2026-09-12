package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural pins for the selected-mode hanging-leg-raise candidate cycle.
 *
 * Every coordinate in this file is constructed from ideal side-view geometry by this test. No source
 * footage, private landmark data, pose cache or observed count informs any number here, and no test
 * asserts an accuracy, ROM or form claim.
 */
class HangingLegRaiseExperimentTest {
    /**
     * The frozen hypothesis configuration
     * (`offline-corpus/coverage-first-20260909/dynamic/hanging-leg-raise-config-v1.json`), except
     * `minimumEndpointSamples`, which is 2 here so each behavioural sequence stays short and
     * readable. `frozenConfig` below keeps the real value for the timeline pin.
     */
    private val config = HangingLegRaiseConfig(
        mode = HangingLegRaiseMode.SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED,
        side = ArmSide.LEFT,
        loweredHipMinDegrees = 150.0,
        raisedHipMaxDegrees = 110.0,
        minimumHipCloseDegrees = 45.0,
        raisedKneeMinDegrees = 140.0,
        hangingTrunkMinDegrees = 55.0,
        minimumSupportRiseRatio = 0.35,
        maximumSupportTravelRatio = 0.30,
        minimumConfidence = 0.5,
        maximumGapNs = 100_000_000L,
        minimumEndpointSamples = 2,
    )

    private val frozenConfig = config.copy(minimumEndpointSamples = 3)

    @Test
    fun measuresHipKneeTrunkAndSupportInSourcePixels() {
        // Hand-derived: in a 400x200 source, shoulder (80,80), hip (200,100), knee (300,50),
        // ankle (360,20) and wrist (60,40) px. Hip vectors (-120,-20) and (100,-50) subtend
        // 143.9726266 degrees; knee->hip (-100,50) and knee->ankle (60,-30) are exactly antiparallel
        // so the knee reads 180; the shoulder-hip vector sits 9.4623222 degrees off image horizontal;
        // and the wrist projects 40 px above the shoulder against a 121.6552506 px trunk, a ratio of
        // 0.3287980. Measuring in raw normalized units instead would report 116.5651, 180.0, 18.4349
        // and 0.6325, so this case pins the pixel projection for three of the four observables.
        val measurement = measureHangingLegRaise(
            frame(
                0,
                400,
                200,
                limb(
                    shoulder = 0.2 to 0.4,
                    hip = 0.5 to 0.5,
                    knee = 0.75 to 0.25,
                    ankle = 0.9 to 0.1,
                    wrist = 0.15 to 0.2,
                ),
            ),
            config,
        )

        assertEquals(143.9726266, measurement.hipDegrees!!, 1e-6)
        assertEquals(180.0, measurement.kneeDegrees!!, 1e-6)
        assertEquals(9.4623222, measurement.trunkElevationDegrees!!, 1e-6)
        assertEquals(0.3287980, measurement.supportRiseRatio!!, 1e-6)
        assertEquals(HangingLegRaiseReason.NONE, measurement.reason)
    }

    @Test
    fun mirroringTheSourceHorizontallyPreservesEveryMeasurement() {
        // A front-camera mirror flips x only. None of the four observables may move. (Camera roll is
        // NOT claimed invariant for the trunk elevation or the support ratio and is not asserted.)
        val upright = limb(0.2 to 0.4, 0.5 to 0.5, 0.75 to 0.25, 0.9 to 0.1, 0.15 to 0.2)
        val mirrored = upright.map { it.copy(x = 1.0 - it.x) }

        val direct = measureHangingLegRaise(frame(0, 400, 200, upright), config)
        val flipped = measureHangingLegRaise(frame(0, 400, 200, mirrored), config)

        assertEquals(direct.hipDegrees!!, flipped.hipDegrees!!, 1e-9)
        assertEquals(direct.kneeDegrees!!, flipped.kneeDegrees!!, 1e-9)
        assertEquals(direct.trunkElevationDegrees!!, flipped.trunkElevationDegrees!!, 1e-9)
        assertEquals(direct.supportRiseRatio!!, flipped.supportRiseRatio!!, 1e-9)
    }

    @Test
    fun theBuilderProducesTheRequestedGeometryIndependentlyOfTheCodeUnderTest() {
        // Guards every later sequence: if the constructor drifted, the behavioural pins would be
        // asserting the wrong poses. Constructed by placement, verified by measurement.
        val measurement = measureHangingLegRaise(
            frame(0, 400, 400, pose(hipDegrees = 120.0, kneeDegrees = 155.0, supportRiseRatio = 0.62)),
            config,
        )

        assertEquals(120.0, measurement.hipDegrees!!, 1e-6)
        assertEquals(155.0, measurement.kneeDegrees!!, 1e-6)
        assertEquals(90.0, measurement.trunkElevationDegrees!!, 1e-6)
        assertEquals(0.62, measurement.supportRiseRatio!!, 1e-6)
    }

    @Test
    fun unusableFramesProduceNoMeasurementAndATypedReason() {
        val usable = pose(hipDegrees = 175.0)
        val knee = usable.single { it.index == 25 }
        val cases = listOf(
            frame(0, 400, 400, emptyList()) to HangingLegRaiseReason.NO_POSE,
            frame(0, 0, 400, usable) to HangingLegRaiseReason.INVALID_DIMENSIONS,
            frame(0, 400, -1, usable) to HangingLegRaiseReason.INVALID_DIMENSIONS,
            frame(0, 400, 400, usable + usable.last()) to HangingLegRaiseReason.DUPLICATE_LANDMARK_INDEX,
            // Each of the five configured points is individually mandatory: the far side is never
            // substituted and no point is ever fabricated.
            frame(0, 400, 400, usable.filterNot { it.index == 11 }) to HangingLegRaiseReason.MISSING_LANDMARK,
            frame(0, 400, 400, usable.filterNot { it.index == 15 }) to HangingLegRaiseReason.MISSING_LANDMARK,
            frame(0, 400, 400, usable.filterNot { it.index == 23 }) to HangingLegRaiseReason.MISSING_LANDMARK,
            frame(0, 400, 400, usable.filterNot { it.index == 25 }) to HangingLegRaiseReason.MISSING_LANDMARK,
            frame(0, 400, 400, usable.filterNot { it.index == 27 }) to HangingLegRaiseReason.MISSING_LANDMARK,
            frame(0, 400, 400, usable.map { if (it.index == 23) it.copy(x = Double.NaN) else it }) to
                HangingLegRaiseReason.INVALID_LANDMARK,
            frame(0, 400, 400, usable.map { if (it.index == 27) it.copy(y = 1.4) else it }) to
                HangingLegRaiseReason.INVALID_LANDMARK,
            frame(0, 400, 400, usable.map { if (it.index == 15) it.copy(visibility = 0.49) else it }) to
                HangingLegRaiseReason.LOW_CONFIDENCE,
            frame(0, 400, 400, usable.map { if (it.index == 11) it.copy(presence = null) else it }) to
                HangingLegRaiseReason.LOW_CONFIDENCE,
            frame(0, 400, 400, usable.map { if (it.index == 25) it.copy(presence = Double.POSITIVE_INFINITY) else it }) to
                HangingLegRaiseReason.LOW_CONFIDENCE,
            // Shoulder collapsed onto the hip: no trunk direction exists, so neither the hip angle
            // nor the support ratio can be formed.
            frame(0, 400, 400, usable.map { if (it.index == 11) it.copy(x = 0.5, y = 0.5) else it }) to
                HangingLegRaiseReason.ZERO_LENGTH_SEGMENT,
            // Knee collapsed onto the hip: no thigh direction exists.
            frame(0, 400, 400, usable.map { if (it.index == 25) it.copy(x = 0.5, y = 0.5) else it }) to
                HangingLegRaiseReason.ZERO_LENGTH_SEGMENT,
            // Ankle collapsed onto the knee: no shank direction exists, so no knee angle exists.
            frame(0, 400, 400, usable.map { if (it.index == 27) it.copy(x = knee.x, y = knee.y) else it }) to
                HangingLegRaiseReason.ZERO_LENGTH_SEGMENT,
        )

        cases.forEach { (unusable, expected) ->
            val measurement = measureHangingLegRaise(unusable, config)
            assertNull(measurement.hipDegrees, expected.name)
            assertNull(measurement.kneeDegrees, expected.name)
            assertNull(measurement.trunkElevationDegrees, expected.name)
            assertNull(measurement.supportRiseRatio, expected.name)
            assertEquals(expected, measurement.reason)
        }
    }

    @Test
    fun onlyTheConfiguredSideIsMeasuredWhenTheOtherSideIsOccluded() {
        val leftOnly = pose(hipDegrees = 175.0)

        assertEquals(
            HangingLegRaiseReason.NONE,
            measureHangingLegRaise(frame(0, 400, 400, leftOnly), config).reason,
        )
        val right = measureHangingLegRaise(frame(0, 400, 400, leftOnly), config.copy(side = ArmSide.RIGHT))
        assertNull(right.hipDegrees)
        assertEquals(HangingLegRaiseReason.MISSING_LANDMARK, right.reason)
    }

    @Test
    fun configRejectsNonFiniteOutOfRangeAndMisorderedThresholds() {
        assertFailsWith<IllegalArgumentException> { config.copy(loweredHipMinDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(loweredHipMinDegrees = 181.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(raisedHipMaxDegrees = -1.0) }
        // A raised hip band that is not strictly tighter than the lowered band is contradictory.
        assertFailsWith<IllegalArgumentException> { config.copy(raisedHipMaxDegrees = 150.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumHipCloseDegrees = -0.5) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumHipCloseDegrees = 181.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(raisedKneeMinDegrees = 181.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(raisedKneeMinDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(hangingTrunkMinDegrees = 91.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(hangingTrunkMinDegrees = -1.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumSupportRiseRatio = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumSupportTravelRatio = -0.1) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumSupportTravelRatio = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = 1.5) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 0) }
    }

    @Test
    fun countsOneCompleteCycleAndConfirmsOnlyAtTheReturningEndpointSample() {
        val experiment = HangingLegRaiseExperiment(config)

        val opening = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to loweredPose(),
        )
        assertEquals(HangingLegRaisePhase.LOWERED, opening[1].phase)
        assertEquals(HangingLegRaisePhase.RAISING, opening[2].phase)
        assertEquals(HangingLegRaisePhase.RAISED, opening[4].phase)
        assertEquals(HangingLegRaisePhase.LOWERING, opening[5].phase)
        assertTrue(opening.all { it.hanging })
        // One returning lowered sample is not yet the configured consecutive support.
        assertEquals(0, opening[6].count)
        assertEquals(false, opening[6].counted)

        val completing = experiment.onFrame(frame(700, 400, 400, loweredPose()))
        assertEquals(1, completing.count)
        assertEquals(true, completing.counted)
        assertEquals(HangingLegRaisePhase.LOWERED, completing.phase)
        assertEquals(HangingLegRaiseReason.NONE, completing.reason)
        val cycle = completing.closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.COUNTED, cycle.outcome)
        assertEquals(200L, cycle.departureTimestampNs)
        assertEquals(300L, cycle.raisedTimestampNs)
        assertEquals(600L, cycle.returnTimestampNs)
        assertEquals(85.0, cycle.minimumHipDegrees, 1e-6)
        assertEquals(90.0, cycle.observedHipCloseDegrees, 1e-6)
    }

    @Test
    fun aBentKneeHangingTuckReachingTheRaisedHipBandIsRejectedAsKneeFlexion() {
        // The single most important discrimination in this family. The tuck closes the hip angle
        // exactly as far as a straight-leg raise does and satisfies every other gate, so the
        // projected knee angle is the only thing separating them. Without the knee gate this
        // sequence would count.
        val tuck = measureHangingLegRaise(frame(0, 400, 400, pose(85.0, kneeDegrees = 75.0)), config)
        assertTrue(tuck.hipDegrees!! <= config.raisedHipMaxDegrees, "the tuck must close the hip band")
        assertTrue(tuck.trunkElevationDegrees!! >= config.hangingTrunkMinDegrees)
        assertTrue(tuck.supportRiseRatio!! >= config.minimumSupportRiseRatio)
        assertTrue(tuck.kneeDegrees!! < config.raisedKneeMinDegrees)

        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to pose(85.0, kneeDegrees = 75.0), 400L to pose(85.0, kneeDegrees = 75.0),
            500L to midPose(), 600L to loweredPose(), 700L to loweredPose(),
        )

        val rejected = results[3].closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.KNEE_FLEXION_REJECTED, rejected.outcome)
        assertEquals(200L, rejected.departureTimestampNs)
        assertNull(rejected.raisedTimestampNs)
        assertEquals(0, results.last().count)
    }

    @Test
    fun travelOfTheBodyAlongTheSupportIsRejectedAsSupportTravel() {
        // The legs also close the hip angle with an extended knee, so every movement gate is
        // satisfied; only the wrist-to-shoulder separation betrays that the body translated along
        // the support instead of the legs rotating about a hanging shoulder. The lowered dwell sits
        // at 0.50 and the allowance is 0.30, so 0.85 is travel.
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to pose(85.0, supportRiseRatio = 0.85), 400L to pose(85.0, supportRiseRatio = 0.85),
            500L to midPose(), 600L to loweredPose(), 700L to loweredPose(),
        )

        val rejected = results[3].closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.SUPPORT_TRAVEL_REJECTED, rejected.outcome)
        assertNull(rejected.raisedTimestampNs)
        assertEquals(0, results.last().count)

        // Travel within the allowance does not reject: the gate must not be a hair trigger.
        val tolerated = HangingLegRaiseExperiment(config)
        val counted = tolerated.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to pose(85.0, supportRiseRatio = 0.75), 400L to pose(85.0, supportRiseRatio = 0.75),
            500L to midPose(), 600L to loweredPose(), 700L to loweredPose(),
        )
        assertEquals(1, counted.last().count)
    }

    @Test
    fun travelThatCollapsesTheSupportSeparationFailsTheHangingGateRatherThanCounting() {
        // The opposite travel direction, documented as a distinct path: when the shoulders rise far
        // enough toward the support the separation drops below `minimumSupportRiseRatio`, so those
        // samples are not admitted as hanging at all and the candidate cannot reach the raised band.
        // Either way no count is produced; the two mechanisms are reported differently on purpose.
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to pose(85.0, supportRiseRatio = 0.05), 400L to pose(85.0, supportRiseRatio = 0.05),
            500L to midPose(), 600L to loweredPose(), 700L to loweredPose(),
        )

        assertEquals(false, results[3].hanging)
        assertEquals(true, results[0].hanging)
        assertEquals(0, results.last().count)
    }

    @Test
    fun aStandingLegLiftWithoutOverheadSupportNeverCounts() {
        // Retained standing negative in synthetic form: upright trunk, arms not above the shoulders.
        // The hip angle sweeps across both bands with an extended knee and still cannot count.
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to pose(175.0, supportRiseRatio = -0.40), 100L to pose(175.0, supportRiseRatio = -0.40),
            200L to pose(140.0, supportRiseRatio = -0.40), 300L to pose(85.0, supportRiseRatio = -0.40),
            400L to pose(85.0, supportRiseRatio = -0.40), 500L to pose(140.0, supportRiseRatio = -0.40),
            600L to pose(175.0, supportRiseRatio = -0.40), 700L to pose(175.0, supportRiseRatio = -0.40),
        )

        val hips = results.map { it.hipDegrees!! }
        assertTrue(hips.max() - hips.min() > 80.0, "the standing lift must actually move the hip angle")
        assertTrue(results.none { it.hanging }, "no frame may be admitted as hanging")
        assertEquals(0, results.last().count)
        assertEquals(HangingLegRaisePhase.WAITING, results.last().phase)
    }

    @Test
    fun aValidMeasuredNonHangingFrameReportsHangingFalse() {
        val result = HangingLegRaiseExperiment(config).onFrame(
            frame(0, 400, 400, pose(hipDegrees = 85.0, trunkDegrees = 5.0)),
        )

        assertEquals(HangingLegRaiseReason.NONE, result.reason)
        assertTrue(result.hipDegrees != null)
        assertEquals(false, result.hanging)
    }

    @Test
    fun aNonHangingFrameBreaksConsecutiveRaisedEndpointSupport() {
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(),
            // The pose is fully measured but fails the hanging predicate. It must reset the
            // consecutive raised-endpoint run rather than leave one sample credited.
            400L to pose(85.0, trunkDegrees = 5.0),
            500L to raisedPose(), 600L to midPose(),
            700L to loweredPose(), 800L to loweredPose(),
        )

        assertEquals(false, results[4].hanging)
        assertEquals(HangingLegRaisePhase.RAISING, results[5].phase)
        assertEquals(0, results.last().count)
    }

    @Test
    fun aSupineTorsoNeverCounts() {
        // Sit-up-family posture: the trunk lies across the frame. Only the trunk gate differs from a
        // countable sequence, so this pins that gate on its own.
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to pose(175.0, trunkDegrees = 5.0), 100L to pose(175.0, trunkDegrees = 5.0),
            200L to pose(140.0, trunkDegrees = 5.0), 300L to pose(85.0, trunkDegrees = 5.0),
            400L to pose(85.0, trunkDegrees = 5.0), 500L to pose(140.0, trunkDegrees = 5.0),
            600L to pose(175.0, trunkDegrees = 5.0), 700L to pose(175.0, trunkDegrees = 5.0),
        )

        assertTrue(results.none { it.hanging })
        assertEquals(0, results.last().count)
        assertEquals(HangingLegRaisePhase.WAITING, results.last().phase)
    }

    @Test
    fun aStaticHangWithoutLegMotionNeverCountsAndStaysDistinguishable() {
        // Static lying/hanging must be reported as an admitted, confirmed lowered endpoint with no
        // candidate at all: observably different from "not hanging" and from "partial movement".
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to loweredPose(),
            300L to loweredPose(), 400L to loweredPose(),
        )

        assertTrue(results.all { it.hanging })
        assertEquals(HangingLegRaisePhase.LOWERED, results.last().phase)
        assertEquals(0, results.last().count)
        assertTrue(results.all { it.closedCycle == null })
        assertNull(experiment.finish())
    }

    @Test
    fun aPartialRaiseThatReturnsBeforeTheRaisedBandIsConfirmedIsAbandoned() {
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to loweredPose(), 400L to loweredPose(),
        )

        val abandoned = results[3].closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.ABANDONED, abandoned.outcome)
        assertEquals(200L, abandoned.departureTimestampNs)
        assertNull(abandoned.raisedTimestampNs)
        assertNull(abandoned.returnTimestampNs)
        assertEquals(0, results.last().count)
        // Lowered support is rebuilt from the returning samples, not inherited from the old dwell.
        assertEquals(HangingLegRaisePhase.LOWERED, results[4].phase)
    }

    @Test
    fun aRaiseWithoutTheRequiredClosureFromTheLoweredAnchorNeverCounts() {
        // The lowered dwell already sits at the bottom of the lowered hip band (150 degrees) and the
        // raised pose satisfies the raw raised hip band (108 <= 110), yet the closure relative to the
        // anchor is only 42 degrees, three short of the configured 45. Posture alone must never
        // substitute for observed movement.
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(150.0), 100L to loweredPose(150.0), 200L to pose(130.0),
            300L to pose(108.0), 400L to pose(108.0), 500L to pose(130.0),
            600L to loweredPose(150.0), 700L to loweredPose(150.0),
        )

        assertTrue(results[3].hipDegrees!! <= config.raisedHipMaxDegrees)
        assertEquals(HangingLegRaisePhase.RAISING, results[4].phase)
        assertEquals(0, results.last().count)
    }

    @Test
    fun theLoweredAnchorIsTheMostClosedHipAngleOfTheLoweredDwell() {
        // The anchor must be the MOST CLOSED hip angle of the lowered dwell, because that yields the
        // smallest measured closure and so the most conservative decision. Anchoring on the first
        // sample (178 degrees, closure 70) or the confirming sample (165 degrees, closure 57)
        // instead of the dwell minimum (152 degrees, closure 44) would let this 108-degree raised
        // pose clear the 45-degree relative closure and count.
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(178.0), 100L to loweredPose(165.0), 200L to loweredPose(152.0),
            300L to pose(130.0), 400L to pose(108.0), 500L to pose(108.0),
            600L to pose(130.0), 700L to loweredPose(178.0), 800L to loweredPose(178.0),
        )

        assertEquals(HangingLegRaisePhase.RAISING, results[5].phase)
        assertEquals(0, results.last().count)
    }

    @Test
    fun directEndpointJumpsCannotCount() {
        val entry = HangingLegRaiseExperiment(config)
        val entryResults = entry.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to raisedPose(),
            300L to raisedPose(), 400L to midPose(), 500L to loweredPose(),
            600L to loweredPose(),
        )
        assertEquals(HangingLegRaiseCycleOutcome.ABANDONED, entryResults[2].closedCycle!!.outcome)
        assertNull(entryResults[2].closedCycle!!.raisedTimestampNs)
        assertEquals(HangingLegRaisePhase.WAITING, entryResults[2].phase)
        assertEquals(0, entryResults.last().count)

        val exit = HangingLegRaiseExperiment(config)
        val exitResults = exit.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to loweredPose(),
            600L to loweredPose(),
        )
        val abandoned = exitResults[5].closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.ABANDONED, abandoned.outcome)
        assertEquals(300L, abandoned.raisedTimestampNs)
        assertNull(abandoned.returnTimestampNs)
        assertEquals(0, exitResults.last().count)
    }

    @Test
    fun endpointConfirmationRequiresConsecutiveSupportAtBothEnds() {
        val entry = HangingLegRaiseExperiment(config)
        val entryResults = entry.feed(
            0L to loweredPose(), 100L to midPose(), 200L to loweredPose(),
            300L to midPose(), 400L to loweredPose(),
        )
        assertEquals(HangingLegRaisePhase.WAITING, entryResults.last().phase)
        assertEquals(0, entryResults.last().count)

        val exit = HangingLegRaiseExperiment(config)
        val exitResults = exit.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to loweredPose(), 700L to midPose(), 800L to loweredPose(),
            900L to midPose(), 1_000L to loweredPose(),
        )
        assertEquals(HangingLegRaisePhase.LOWERING, exitResults.last().phase)
        assertEquals(0, exitResults.last().count)
    }

    @Test
    fun aCaptureGapInterruptsTheOpenCandidateAndCannotBridgeACycle() {
        val experiment = HangingLegRaiseExperiment(config)
        experiment.feed(0L to loweredPose(), 100L to loweredPose(), 200L to midPose())

        val afterGap = experiment.onFrame(frame(1_000_000_000L, 400, 400, raisedPose()))
        assertEquals(HangingLegRaiseReason.CAPTURE_GAP, afterGap.reason)
        val interrupted = afterGap.closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.TRACKING_INTERRUPTED, interrupted.outcome)
        assertEquals(200L, interrupted.departureTimestampNs)

        val rest = experiment.feed(
            1_000_000_100L to raisedPose(), 1_000_000_200L to midPose(),
            1_000_000_300L to loweredPose(), 1_000_000_400L to loweredPose(),
        )
        assertEquals(0, rest.last().count)
    }

    @Test
    fun anUnusableFrameClearsPartialEvidenceWhileEarlierCountsSurvive() {
        val experiment = HangingLegRaiseExperiment(config)
        experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to loweredPose(), 700L to loweredPose(),
        )
        assertEquals(1, experiment.count)

        val partial = experiment.feed(800L to midPose(), 900L to raisedPose(), 1_000L to raisedPose())
        assertEquals(HangingLegRaisePhase.RAISED, partial.last().phase)

        val lost = experiment.onFrame(frame(1_100L, 400, 400, emptyList()))
        assertEquals(HangingLegRaiseReason.NO_POSE, lost.reason)
        assertEquals(HangingLegRaisePhase.UNCERTAIN, lost.phase)
        assertEquals(HangingLegRaiseCycleOutcome.TRACKING_INTERRUPTED, lost.closedCycle!!.outcome)
        assertEquals(1, lost.count)
        assertNull(lost.hipDegrees)
        assertEquals(false, lost.hanging)

        // The interrupted attempt cannot resume: the returning samples alone must not complete it.
        val resumed = experiment.feed(1_200L to loweredPose(), 1_300L to loweredPose())
        assertEquals(1, resumed.last().count)
    }

    @Test
    fun invalidDuplicateAndBackwardsTimestampsCannotAdvanceTheCandidate() {
        val experiment = HangingLegRaiseExperiment(config)
        experiment.feed(0L to loweredPose(), 100L to loweredPose(), 200L to midPose())

        val invalid = experiment.onFrame(frame(-1, 400, 400, raisedPose()))
        assertEquals(HangingLegRaiseReason.INVALID_TIMESTAMP, invalid.reason)
        assertEquals(HangingLegRaisePhase.UNCERTAIN, invalid.phase)
        assertEquals(HangingLegRaiseCycleOutcome.TRACKING_INTERRUPTED, invalid.closedCycle!!.outcome)

        val duplicate = experiment.onFrame(frame(200, 400, 400, loweredPose()))
        assertEquals(HangingLegRaiseReason.DUPLICATE_TIMESTAMP, duplicate.reason)
        val backwards = experiment.onFrame(frame(150, 400, 400, loweredPose()))
        assertEquals(HangingLegRaiseReason.BACKWARDS_TIMESTAMP, backwards.reason)
        assertEquals(0, backwards.count)
        assertNull(backwards.hipDegrees)
    }

    @Test
    fun finishCensorsOnlyAnOpenCandidateAndOnlyOnce() {
        val open = HangingLegRaiseExperiment(config)
        open.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(),
        )

        val censored = open.finish()!!
        assertEquals(HangingLegRaiseCycleOutcome.CENSORED_AT_END_OF_INPUT, censored.outcome)
        assertEquals(200L, censored.departureTimestampNs)
        assertEquals(300L, censored.raisedTimestampNs)
        assertNull(censored.returnTimestampNs)
        assertEquals(0, open.count)
        assertNull(open.finish())

        val completed = HangingLegRaiseExperiment(config)
        completed.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to loweredPose(), 700L to loweredPose(),
        )
        assertEquals(1, completed.count)
        assertNull(completed.finish())
    }

    @Test
    fun aSessionStartingAlreadyRaisedDoesNotCountTheOpeningDescent() {
        val experiment = HangingLegRaiseExperiment(config)
        val descent = experiment.feed(
            0L to raisedPose(), 100L to raisedPose(), 200L to midPose(),
            300L to loweredPose(), 400L to loweredPose(),
        )
        assertEquals(0, descent.last().count)

        val cycle = experiment.feed(
            500L to midPose(), 600L to raisedPose(), 700L to raisedPose(),
            800L to midPose(), 900L to loweredPose(), 1_000L to loweredPose(),
        )
        assertEquals(1, cycle.last().count)
    }

    @Test
    fun aHesitationBackToTheRaisedBandStillCountsOnceOnTheFinalReturn() {
        val experiment = HangingLegRaiseExperiment(config)
        val results = experiment.feed(
            0L to loweredPose(), 100L to loweredPose(), 200L to midPose(),
            300L to raisedPose(), 400L to raisedPose(), 500L to midPose(),
            600L to raisedPose(), 700L to midPose(), 800L to loweredPose(),
            900L to loweredPose(),
        )

        assertEquals(HangingLegRaisePhase.RAISED, results[6].phase)
        assertEquals(1, results.last().count)
        val cycle = results.last().closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.COUNTED, cycle.outcome)
        assertEquals(300L, cycle.raisedTimestampNs)
        assertEquals(800L, cycle.returnTimestampNs)
    }

    @Test
    fun theFrozenPositiveLabelTimelineCountsExactlyOnceUnderTheFrozenConfig() {
        // Synthetic 30 fps reconstruction of the phase timeline frozen in
        // offline-corpus/coverage-first-20260909/dynamic/labels-v1.json for
        // clips/positive-hanging-leg-raise-v1.mp4: an opening descent from an already-raised start
        // (0.00-0.95 s, must not count), a lowered dwell (1.00-1.70 s), a departure (1.70-2.10 s), a
        // raised dwell (2.30-2.90 s), a lowering (2.90-3.30 s) and a returning lowered dwell
        // (3.40-3.70 s). Uses the real frozen minimumEndpointSamples of 3.
        //
        // This pins the counter against the FROZEN LABEL SHAPE only. It is not evidence about the
        // clip: no landmark from the clip is used, and no accuracy claim follows from it.
        val experiment = HangingLegRaiseExperiment(frozenConfig)
        val results = ArrayList<HangingLegRaiseFrameResult>()
        var index = 0
        while (index <= 111) {
            val seconds = index / 30.0
            val hip = when {
                seconds < 0.95 -> ramp(seconds, 0.0, 0.95, 85.0, 175.0)
                seconds < 1.70 -> 175.0
                seconds < 2.30 -> ramp(seconds, 1.70, 2.30, 175.0, 85.0)
                seconds < 2.90 -> 85.0
                seconds < 3.40 -> ramp(seconds, 2.90, 3.40, 85.0, 175.0)
                else -> 175.0
            }
            results += experiment.onFrame(
                frame((seconds * 1_000_000_000L).toLong(), 400, 400, pose(hip)),
            )
            index++
        }

        assertEquals(1, results.last().count, "exactly the one frozen cycle")
        val counted = results.single { it.counted }.closedCycle!!
        assertEquals(HangingLegRaiseCycleOutcome.COUNTED, counted.outcome)
        // The departure is asserted against the frozen departure window directly. The raised and
        // returning endpoints are asserted against the frozen windows' OUTER bounds only, because a
        // band is confirmed the moment it is entered with consecutive support, whereas the raw label
        // marks the interval over which the held extreme was visible. A confirmation therefore
        // legitimately precedes its labelled dwell, and pinning it to the dwell interval would
        // encode a timing claim the label does not make.
        assertInWindow("departure", counted.departureTimestampNs, 1.70, 2.10)
        assertInWindow("raised", counted.raisedTimestampNs!!, 1.70, 2.90)
        assertInWindow("return", counted.returnTimestampNs!!, 2.90, 3.70)
        assertTrue(
            counted.departureTimestampNs < counted.raisedTimestampNs!! &&
                counted.raisedTimestampNs!! < counted.returnTimestampNs!!,
            "the three endpoints must be strictly ordered observed sample times",
        )
        assertNull(experiment.finish(), "the clip ends in the lowered dwell, so nothing is censored")
    }

    private fun assertInWindow(label: String, timestampNs: Long, startSeconds: Double, endSeconds: Double) {
        val seconds = timestampNs / 1_000_000_000.0
        assertTrue(
            seconds >= startSeconds - 1e-9 && seconds <= endSeconds + 1e-9,
            "$label endpoint ${seconds}s must fall inside the frozen [$startSeconds, $endSeconds]s window",
        )
    }

    private fun ramp(at: Double, from: Double, to: Double, start: Double, end: Double): Double =
        start + (end - start) * ((at - from) / (to - from))

    private fun frame(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>) =
        HangingLegRaisePoseFrame(timestampNs, width, height, landmarks)

    private fun HangingLegRaiseExperiment.feed(vararg steps: Pair<Long, List<Landmark>>) =
        steps.map { (timestampNs, landmarks) -> onFrame(frame(timestampNs, 400, 400, landmarks)) }

    private fun loweredPose(hipDegrees: Double = 175.0) = pose(hipDegrees)

    private fun midPose() = pose(hipDegrees = 130.0)

    private fun raisedPose() = pose(hipDegrees = 85.0)

    /**
     * Builds a square-frame side-view hanging pose whose four measured observables equal the
     * requested values by construction rather than by calling the code under test.
     *
     * The shoulder sits on a fixed-radius ray `trunkDegrees` above image horizontal from the hip
     * (90 degrees puts it directly above, i.e. a vertical hanging trunk); the knee sits on a ray a
     * further `hipDegrees` around the hip; the ankle sits on a ray a further `kneeDegrees` around the
     * knee; and the wrist sits `supportRiseRatio` trunk-lengths directly above the shoulder, so a
     * negative ratio places it below the shoulder as arms-down standing would. A 400x400 frame keeps
     * pixel and normalized geometry proportional so the placement stays hand-checkable.
     */
    private fun pose(
        hipDegrees: Double,
        kneeDegrees: Double = 180.0,
        trunkDegrees: Double = 90.0,
        supportRiseRatio: Double = 0.5,
    ): List<Landmark> {
        val radians = { degrees: Double -> degrees * kotlin.math.PI / 180.0 }
        val trunkRadius = 0.25
        val shoulder = Pair(
            0.5 - trunkRadius * cos(radians(trunkDegrees)),
            0.5 - trunkRadius * sin(radians(trunkDegrees)),
        )
        val kneeRay = radians(180.0 + trunkDegrees + hipDegrees)
        val knee = Pair(0.5 + 0.2 * cos(kneeRay), 0.5 + 0.2 * sin(kneeRay))
        val ankleRay = kneeRay + radians(180.0 + kneeDegrees)
        val ankle = Pair(knee.first + 0.14 * cos(ankleRay), knee.second + 0.14 * sin(ankleRay))
        val wrist = Pair(shoulder.first, shoulder.second - supportRiseRatio * trunkRadius)
        return limb(shoulder, Pair(0.5, 0.5), knee, ankle, wrist)
    }

    private fun limb(
        shoulder: Pair<Double, Double>,
        hip: Pair<Double, Double>,
        knee: Pair<Double, Double>,
        ankle: Pair<Double, Double>,
        wrist: Pair<Double, Double>,
    ) = listOf(
        Landmark(11, null, shoulder.first, shoulder.second, 0.0, 0.95, 0.95),
        Landmark(15, null, wrist.first, wrist.second, 0.0, 0.95, 0.95),
        Landmark(23, null, hip.first, hip.second, 0.0, 0.95, 0.95),
        Landmark(25, null, knee.first, knee.second, 0.0, 0.95, 0.95),
        Landmark(27, null, ankle.first, ankle.second, 0.0, 0.95, 0.95),
    )
}
