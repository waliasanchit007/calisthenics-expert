package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LungeCycleExperimentTest {
    private val config = LungeConfig(
        side = ArmSide.LEFT,
        extendedDegrees = 160.0,
        flexedDegrees = 100.0,
        minimumConfidence = .8,
        maximumGapNs = 500,
        minimumEndpointSamples = 2,
    )

    @Test
    fun measuresKneeAngleWithSourcePixelAspectRatio() {
        val measurement = measureLungeKnee(frame(0, 400, 200, knee(
            hip = Pair(.25, .25),
            knee = Pair(.5, .5),
            ankle = Pair(.75, .5),
        )), config)

        assertEquals(153.4349488, measurement.kneeDegrees!!, 1e-5)
        assertEquals(LungeReason.NONE, measurement.reason)
    }

    @Test
    fun countsOnlyStandingFlexedStandingWithEndpointEvidence() {
        val experiment = LungeCycleExperiment(config)

        assertEquals(LungePhase.WAITING, experiment.onFrame(frame(0, 400, 200, kneeAt(170.0))).phase)
        assertEquals(LungePhase.STANDING, experiment.onFrame(frame(100, 400, 200, kneeAt(170.0))).phase)
        assertEquals(LungePhase.FLEXING, experiment.onFrame(frame(200, 400, 200, kneeAt(120.0))).phase)
        assertEquals(LungePhase.FLEXING, experiment.onFrame(frame(300, 400, 200, kneeAt(70.0))).phase)
        assertEquals(LungePhase.FLEXED, experiment.onFrame(frame(400, 400, 200, kneeAt(70.0))).phase)
        assertEquals(LungePhase.EXTENDING, experiment.onFrame(frame(500, 400, 200, kneeAt(120.0))).phase)
        assertEquals(0, experiment.onFrame(frame(600, 400, 200, kneeAt(170.0))).count)
        val completed = experiment.onFrame(frame(700, 400, 200, kneeAt(170.0)))

        assertEquals(1, completed.count)
        assertEquals(LungePhase.STANDING, completed.phase)
        assertEquals(LungeReason.NONE, completed.reason)
        assertEquals(true, completed.counted)
    }

    @Test
    fun ordinaryStandingAloneNeverCounts() {
        val experiment = LungeCycleExperiment(config)
        repeat(10) { index ->
            val result = experiment.onFrame(frame(index * 100L, 400, 200, kneeAt(170.0)))
            assertEquals(0, result.count)
            assertEquals(false, result.counted)
        }
        assertEquals(LungePhase.STANDING, experiment.phase)
    }

    @Test
    fun kneeLiftMarchingPartialBendNeverReachesFlexedAndDoesNotCount() {
        // A knee lift/march raises the knee (120 degrees, between this config's 100-degree flexed
        // threshold and 160-degree extended threshold) without ever dropping to the lunge bottom —
        // this must read as a partial excursion, not a completed cycle.
        val experiment = LungeCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0, 300L to 120.0, 400L to 170.0, 500L to 170.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAtDegrees(angle))) }

        assertEquals(0, experiment.count)
        assertEquals(LungePhase.STANDING, experiment.phase)
    }

    @Test
    fun deepKneeLiftMarchIsIndistinguishableFromAGroundedLungeOnThisSingleLegMeasurement() {
        // Disclosed confound, not a bug: kneeLiftMarchingPartialBendNeverReachesFlexedAndDoesNotCount
        // only proves rejection of a SHALLOW knee lift (120 degrees, never crossing this config's
        // 100-degree flexed threshold). A deeper knee lift/high-march -- hip stays put, thigh raises
        // toward horizontal, relaxed shin hangs down under gravity -- reaches a knee angle well below
        // 100 degrees too, with the foot never touching the ground. These hand-derived coordinates
        // (a raised knee well off the hip's vertical line, ankle hanging beneath the raised knee) are
        // geometrically a knee lift, not a lunge, but this experiment measures only a single leg's
        // hip-knee-ankle angle and has no ankle-height/grounding signal to tell them apart, so it
        // counts identically to a real lunge. No ankle-height or "grounded foot" threshold is
        // implemented to suppress this: doing so without real deep-knee-lift footage to validate
        // against would be an unvalidated hidden threshold, which this project's discipline forbids.
        // This is the documented single-leg-tracking limitation, exercised concretely rather than
        // only asserted in prose.
        val experiment = LungeCycleExperiment(config)
        val frames = listOf(
            0L to standingLeftKnee(),
            100L to standingLeftKnee(),
            200L to kneeLiftLeftKnee(raised = false),
            300L to kneeLiftLeftKnee(raised = true),
            400L to kneeLiftLeftKnee(raised = true),
            500L to kneeLiftLeftKnee(raised = false),
            600L to standingLeftKnee(),
        )
        val results = frames.map { (timestamp, landmarks) -> experiment.onFrame(frame(timestamp, 400, 400, landmarks)) }

        assertEquals(94.49258148, results[3].kneeDegrees!!, 1e-5)
        assertEquals(LungePhase.FLEXING, results[3].phase)
        assertEquals(LungePhase.FLEXED, results[4].phase)
        val completed = experiment.onFrame(frame(700, 400, 400, standingLeftKnee()))

        assertEquals(1, completed.count)
        assertEquals(true, completed.counted, "confound: single-leg tracking counts a lifted, non-grounded knee as a completed lunge")
    }

    @Test
    fun partialExcursionDoesNotCountAndDoesNotReuseOldStandingEvidence() {
        val experiment = LungeCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0, 300L to 170.0, 400L to 170.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        assertEquals(0, experiment.count)
        assertEquals(LungePhase.STANDING, experiment.phase)
        assertEquals(false, experiment.onFrame(frame(500, 400, 200, kneeAt(70.0))).counted)
    }

    @Test
    fun noPoseAndCaptureGapInvalidateAnUnfinishedCycle() {
        val experiment = LungeCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        val noPose = experiment.onFrame(frame(300, 400, 200, emptyList()))
        assertNull(noPose.kneeDegrees)
        assertEquals(LungeReason.NO_POSE, noPose.reason)
        assertEquals(LungePhase.UNCERTAIN, noPose.phase)

        experiment.onFrame(frame(1_000, 400, 200, kneeAt(170.0)))
        val afterGap = experiment.onFrame(frame(1_600, 400, 200, kneeAt(70.0)))
        assertEquals(0, afterGap.count)
        assertEquals(LungeReason.CAPTURE_GAP, afterGap.reason)
        assertEquals(LungePhase.WAITING, afterGap.phase)
    }

    @Test
    fun sustainedNoPoseNeverAdvancesOrStitchesAnUnfinishedCandidate() {
        val experiment = LungeCycleExperiment(config)
        listOf(0L to 170.0, 100L to 170.0, 200L to 120.0)
            .forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        for (timestamp in 300L..1_000L step 100L) {
            val result = experiment.onFrame(frame(timestamp, 400, 200, emptyList()))
            assertEquals(LungeReason.NO_POSE, result.reason)
            assertEquals(LungePhase.UNCERTAIN, result.phase)
            assertEquals(0, result.count)
            assertEquals(false, result.counted)
        }
    }

    @Test
    fun endpointEvidenceMustBeConsecutiveAtBothExtremes() {
        val experiment = LungeCycleExperiment(config)
        listOf(
            0L to 170.0, 100L to 170.0, 200L to 120.0,
            300L to 70.0, 400L to 120.0, 500L to 70.0,
            600L to 120.0, 700L to 170.0, 800L to 170.0,
        ).forEach { (timestamp, angle) -> experiment.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        assertEquals(0, experiment.count)

        val returningEvidence = LungeCycleExperiment(config)
        listOf(
            0L to 170.0, 100L to 170.0, 200L to 120.0,
            300L to 70.0, 400L to 70.0, 500L to 120.0,
            600L to 170.0, 700L to 120.0, 800L to 170.0,
        ).forEach { (timestamp, angle) -> returningEvidence.onFrame(frame(timestamp, 400, 200, kneeAt(angle))) }

        assertEquals(0, returningEvidence.count)
    }

    @Test
    fun negativeTimestampInvalidatesPendingEvidenceBeforeLaterFrames() {
        val experiment = LungeCycleExperiment(config)
        experiment.onFrame(frame(0, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(100, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(200, 400, 200, kneeAt(120.0)))

        val invalid = experiment.onFrame(frame(-1, 400, 200, kneeAt(70.0)))
        assertEquals(LungeReason.INVALID_TIMESTAMP, invalid.reason)
        assertEquals(LungePhase.UNCERTAIN, invalid.phase)
        experiment.onFrame(frame(300, 400, 200, kneeAt(70.0)))
        experiment.onFrame(frame(400, 400, 200, kneeAt(120.0)))
        experiment.onFrame(frame(500, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(600, 400, 200, kneeAt(170.0)))

        assertEquals(0, experiment.count)
    }

    @Test
    fun repeatedAndBackwardsTimestampsCannotAdvanceTheCandidate() {
        val experiment = LungeCycleExperiment(config)
        experiment.onFrame(frame(0, 400, 200, kneeAt(170.0)))
        experiment.onFrame(frame(100, 400, 200, kneeAt(170.0)))

        val duplicate = experiment.onFrame(frame(100, 400, 200, kneeAt(70.0)))
        assertEquals(LungeReason.DUPLICATE_TIMESTAMP, duplicate.reason)
        assertEquals(0, duplicate.count)
        val backwards = experiment.onFrame(frame(50, 400, 200, kneeAt(70.0)))
        assertEquals(LungeReason.BACKWARDS_TIMESTAMP, backwards.reason)
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
            val result = measureLungeKnee(frame(0, 400, 200, landmarks), config)
            assertNull(result.kneeDegrees, landmarks.toString())
            assertEquals(true, result.reason != LungeReason.NONE, landmarks.toString())
        }
    }

    @Test
    fun leftAndRightSideSelectionTrackIndependentLegs() {
        val fullCycle = listOf(
            0L to 170.0, 100L to 170.0, 200L to 120.0,
            300L to 70.0, 400L to 70.0, 500L to 120.0,
            600L to 170.0, 700L to 170.0,
        )

        val left = LungeCycleExperiment(config)
        fullCycle.forEach { (timestamp, angle) -> left.onFrame(frame(timestamp, 400, 200, kneeAt(angle, 23, 25, 27))) }
        assertEquals(1, left.count)

        val right = LungeCycleExperiment(config.copy(side = ArmSide.RIGHT))
        // Only the LEFT leg is present; the RIGHT-tracked experiment must never see a usable angle.
        fullCycle.forEach { (timestamp, angle) -> right.onFrame(frame(timestamp, 400, 200, kneeAt(angle, 23, 25, 27))) }
        assertEquals(0, right.count)
        assertEquals(LungePhase.UNCERTAIN, right.phase)
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

    @Test
    fun configDefaultsToTheSingleLegBaselineProfile() {
        assertEquals(LungeAdmissionProfile.SINGLE_LEG_BASELINE, config.admissionProfile)
    }

    @Test
    fun bilateralFixtureProducesTheRequestedImagePlaneAngles() {
        // Pins the synthetic angle constructor itself, so a broken fixture cannot silently make
        // the admission tests below pass or fail for the wrong reason.
        val trackedFrame = bilateralFrame(0, tracked = 70.0, contralateral = 178.0, side = ArmSide.LEFT)
        assertEquals(70.0, measureLungeKnee(trackedFrame, config).kneeDegrees!!, 1e-6)
        assertEquals(178.0, measureLungeKnee(trackedFrame, config.copy(side = ArmSide.RIGHT)).kneeDegrees!!, 1e-6)
    }

    @Test
    fun contralateralProfileRejectsTheUnilateralKneeLiftThatTheBaselineCounts() {
        for (side in listOf(ArmSide.LEFT, ArmSide.RIGHT)) {
            // Tracked leg performs a full lower-and-return excursion while the other leg stays
            // straight underneath: a standing unilateral knee lift, not a lunge lowering.
            val steps = fullExcursion(contralateral = 178.0)

            val baseline = drive(baselineConfig(side), steps, side)
            assertEquals(
                1,
                baseline.last().count,
                "$side baseline behavior must be preserved unchanged: it still counts the knee lift",
            )

            val optIn = drive(contralateralConfig(side), steps, side)
            assertEquals(
                0,
                optIn.last().count,
                "$side leg-lift: the non-tracked leg is not flexed at the tracked flexed endpoint, so CONTRALATERAL_NOT_EXTENDED_V1 must not count",
            )
            assertTrue(
                optIn.any { it.reason == LungeReason.CONTRALATERAL_EXTENDED },
                "$side leg-lift rejection must be explicit, not a silent non-count",
            )
            assertTrue(optIn.none { it.counted }, "$side leg-lift must never emit a counted frame")
        }
    }

    @Test
    fun contralateralProfileCountsABilateralBentCycle() {
        for (side in listOf(ArmSide.LEFT, ArmSide.RIGHT)) {
            val results = drive(contralateralConfig(side), fullExcursion(contralateral = 120.0), side)
            assertEquals(1, results.last().count, "$side bilateral-bent cycle must still count")
            assertTrue(
                results.none { it.reason == LungeReason.CONTRALATERAL_EXTENDED },
                "$side bilateral-bent cycle must not be rejected",
            )
        }
    }

    @Test
    fun contralateralProfileStillCountsASquatShapedCycleAsAKnownLimitation() {
        // Deliberate, characterized limitation: this profile rejects single-leg support only. A
        // symmetric bilateral squat flexes both knees, so it remains a candidate cycle here. This
        // is not lunge identity, and no stance or grounding signal is implemented.
        for (side in listOf(ArmSide.LEFT, ArmSide.RIGHT)) {
            val squatShaped = listOf(
                Triple(0L, 170.0, 170.0), Triple(100L, 170.0, 170.0), Triple(200L, 120.0, 120.0),
                Triple(300L, 70.0, 70.0), Triple(400L, 70.0, 70.0), Triple(500L, 120.0, 120.0),
                Triple(600L, 170.0, 170.0), Triple(700L, 170.0, 170.0),
            )
            val results = drive(contralateralConfig(side), squatShaped, side)
            assertEquals(
                1,
                results.last().count,
                "$side squat confusion is deliberately retained by this profile",
            )
        }
    }

    @Test
    fun contralateralAtExactlyExtendedDegreesRejectsAndJustBelowAdmits() {
        // Strict inequality against the existing extendedDegrees; no newly fitted number.
        val atThreshold = drive(contralateralConfig(ArmSide.LEFT), fullExcursion(contralateral = 160.0), ArmSide.LEFT)
        assertEquals(0, atThreshold.last().count, "contralateral exactly at extendedDegrees must reject")
        assertTrue(atThreshold.any { it.reason == LungeReason.CONTRALATERAL_EXTENDED })

        val justBelow = drive(contralateralConfig(ArmSide.LEFT), fullExcursion(contralateral = 159.0), ArmSide.LEFT)
        assertEquals(1, justBelow.last().count, "contralateral just below extendedDegrees must admit")
    }

    @Test
    fun missingContralateralGeometryInvalidatesTheCandidateWithAnExplicitReason() {
        val trackedOnly = fullExcursion(contralateral = 120.0).map { (timestamp, tracked, _) ->
            timestamp to legAt(tracked, ArmSide.LEFT)
        }

        val baseline = LungeCycleExperiment(baselineConfig(ArmSide.LEFT))
        trackedOnly.forEach { (timestamp, landmarks) -> baseline.onFrame(frame(timestamp, 400, 400, landmarks)) }
        assertEquals(1, baseline.count, "the baseline must keep ignoring unselected-leg geometry")

        val optIn = LungeCycleExperiment(contralateralConfig(ArmSide.LEFT))
        val results = trackedOnly.map { (timestamp, landmarks) -> optIn.onFrame(frame(timestamp, 400, 400, landmarks)) }
        assertEquals(0, optIn.count, "absent contralateral geometry must never be counted or bridged")
        assertEquals(LungeReason.CONTRALATERAL_UNAVAILABLE, results.first().reason)
        assertEquals(LungePhase.UNCERTAIN, results.first().phase)
        assertTrue(results.all { it.reason == LungeReason.CONTRALATERAL_UNAVAILABLE })
    }

    @Test
    fun lowConfidenceInvalidOrDegenerateContralateralGeometryIsUnavailableNotAdmitted() {
        val usable = bilateralLandmarks(tracked = 70.0, contralateral = 120.0, side = ArmSide.LEFT)
        val contralateralKnee = usable.single { it.index == 26 }
        val cases = mapOf(
            "low confidence" to usable.map { if (it.index == 26) it.copy(visibility = .79) else it },
            "non-finite coordinate" to usable.map { if (it.index == 26) it.copy(x = Double.NaN) else it },
            "out of range coordinate" to usable.map { if (it.index == 28) it.copy(y = 1.5) else it },
            "zero-length contralateral limb" to usable.map {
                if (it.index == 28) it.copy(x = contralateralKnee.x, y = contralateralKnee.y) else it
            },
        )

        cases.forEach { (label, landmarks) ->
            val experiment = LungeCycleExperiment(contralateralConfig(ArmSide.LEFT))
            val result = experiment.onFrame(frame(0, 400, 400, landmarks))
            assertEquals(LungeReason.CONTRALATERAL_UNAVAILABLE, result.reason, label)
            assertEquals(false, result.counted, label)
            assertEquals(LungePhase.UNCERTAIN, result.phase, label)
        }
    }

    @Test
    fun aStraightContralateralLegWhileStandingDoesNotRejectTheCycle() {
        // The other leg is expected to be straight at full standing; single-leg support is only
        // decided at the tracked flexed endpoint.
        val steps = listOf(
            Triple(0L, 170.0, 178.0), Triple(100L, 170.0, 178.0), Triple(200L, 120.0, 178.0),
            Triple(300L, 70.0, 120.0), Triple(400L, 70.0, 120.0), Triple(500L, 120.0, 150.0),
            Triple(600L, 170.0, 178.0), Triple(700L, 170.0, 178.0),
        )
        val results = drive(contralateralConfig(ArmSide.LEFT), steps, ArmSide.LEFT)

        assertEquals(1, results.last().count, "a straight contralateral leg while standing must not reject")
        assertTrue(results.none { it.reason == LungeReason.CONTRALATERAL_EXTENDED })
    }

    @Test
    fun aRejectedContralateralCandidateCannotCompleteLateAndRecoversOnAFreshCycle() {
        val experiment = LungeCycleExperiment(contralateralConfig(ArmSide.LEFT))
        val rejectedThenFresh = fullExcursion(contralateral = 178.0) +
            fullExcursion(contralateral = 120.0).map { (timestamp, tracked, contralateral) ->
                Triple(timestamp + 800L, tracked, contralateral)
            }
        val results = rejectedThenFresh.map { (timestamp, tracked, contralateral) ->
            experiment.onFrame(bilateralFrame(timestamp, tracked, contralateral, ArmSide.LEFT))
        }

        assertEquals(0, results[7].count, "the rejected knee lift must not complete late")
        assertEquals(1, results.last().count, "a fresh bilateral cycle must still be counted afterwards")
        assertEquals(1, results.count { it.counted })
    }

    @Test
    fun contralateralProfileKeepsGapDuplicateAndBackwardsTimestampProtection() {
        val gapped = LungeCycleExperiment(contralateralConfig(ArmSide.LEFT))
        listOf(Triple(0L, 170.0, 178.0), Triple(100L, 170.0, 178.0), Triple(200L, 120.0, 150.0))
            .forEach { (timestamp, tracked, contralateral) ->
                gapped.onFrame(bilateralFrame(timestamp, tracked, contralateral, ArmSide.LEFT))
            }
        val afterGap = gapped.onFrame(bilateralFrame(1_600, 70.0, 120.0, ArmSide.LEFT))
        assertEquals(LungeReason.CAPTURE_GAP, afterGap.reason, "the capture-gap reason must survive the new profile")
        assertEquals(LungePhase.WAITING, afterGap.phase)
        assertEquals(0, afterGap.count)

        val repeated = LungeCycleExperiment(contralateralConfig(ArmSide.LEFT))
        repeated.onFrame(bilateralFrame(0, 170.0, 178.0, ArmSide.LEFT))
        repeated.onFrame(bilateralFrame(100, 170.0, 178.0, ArmSide.LEFT))
        assertEquals(
            LungeReason.DUPLICATE_TIMESTAMP,
            repeated.onFrame(bilateralFrame(100, 70.0, 120.0, ArmSide.LEFT)).reason,
        )
        assertEquals(
            LungeReason.BACKWARDS_TIMESTAMP,
            repeated.onFrame(bilateralFrame(50, 70.0, 120.0, ArmSide.LEFT)).reason,
        )
        assertEquals(0, repeated.count)
    }

    @Test
    fun theContralateralDiagnosticIsReportedOnlyWhenTheProfileMeasuresIt() {
        val optIn = LungeCycleExperiment(contralateralConfig(ArmSide.LEFT))
            .onFrame(bilateralFrame(0, 170.0, 120.0, ArmSide.LEFT))
        assertEquals(120.0, assertNotNull(optIn.contralateralKneeDegrees), 1e-6)

        val baseline = LungeCycleExperiment(baselineConfig(ArmSide.LEFT))
            .onFrame(bilateralFrame(0, 170.0, 120.0, ArmSide.LEFT))
        assertNull(baseline.contralateralKneeDegrees, "the baseline must not start reporting contralateral geometry")
    }

    private fun frame(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): LungePoseFrame =
        LungePoseFrame(timestampNs, width, height, landmarks)

    private fun baselineConfig(side: ArmSide) = config.copy(side = side)

    private fun contralateralConfig(side: ArmSide) =
        config.copy(side = side, admissionProfile = LungeAdmissionProfile.CONTRALATERAL_NOT_EXTENDED_V1)

    /** Tracked leg lowers and returns; the other leg is held at [contralateral] throughout. */
    private fun fullExcursion(contralateral: Double) = listOf(
        Triple(0L, 170.0, contralateral), Triple(100L, 170.0, contralateral), Triple(200L, 120.0, contralateral),
        Triple(300L, 70.0, contralateral), Triple(400L, 70.0, contralateral), Triple(500L, 120.0, contralateral),
        Triple(600L, 170.0, contralateral), Triple(700L, 170.0, contralateral),
    )

    private fun drive(
        configuration: LungeConfig,
        steps: List<Triple<Long, Double, Double>>,
        side: ArmSide,
    ): List<LungeFrameResult> {
        val experiment = LungeCycleExperiment(configuration)
        return steps.map { (timestamp, tracked, contralateral) ->
            experiment.onFrame(bilateralFrame(timestamp, tracked, contralateral, side))
        }
    }

    private fun bilateralFrame(timestampNs: Long, tracked: Double, contralateral: Double, side: ArmSide) =
        frame(timestampNs, 400, 400, bilateralLandmarks(tracked, contralateral, side))

    private fun bilateralLandmarks(tracked: Double, contralateral: Double, side: ArmSide): List<Landmark> =
        legAt(tracked, side) + legAt(contralateral, if (side == ArmSide.LEFT) ArmSide.RIGHT else ArmSide.LEFT)

    // Square 400x400 frame, so normalized deltas scale identically on both axes and the measured
    // image-plane angle equals the requested one. The knee sits .25 below the hip and the ankle
    // .25 from the knee, rotated to the requested hip-knee-ankle angle. Real landmark indices are
    // used for each leg: no mirrored or synthesized contralateral points.
    private fun legAt(degrees: Double, side: ArmSide): List<Landmark> {
        val hipX = if (side == ArmSide.LEFT) .35 else .55
        val radians = degrees * PI / 180.0
        val hip = Pair(hipX, .30)
        val kneeJoint = Pair(hipX, .55)
        val ankle = Pair(hipX + .25 * sin(radians), .55 - .25 * cos(radians))
        return when (side) {
            ArmSide.LEFT -> knee(hip, kneeJoint, ankle, 23, 25, 27)
            ArmSide.RIGHT -> knee(hip, kneeJoint, ankle, 24, 26, 28)
        }
    }

    // Square 400x400 frame so raw normalized deltas are usable directly without aspect scaling.
    // Hip fixed at (.5, .30): a knee lift raises the hip's own thigh without hip-joint translation.
    private fun standingLeftKnee(): List<Landmark> = knee(Pair(.5, .30), Pair(.5, .55), Pair(.5, .80))

    // raised=false: transitional midpoint (thigh partway up); raised=true: thigh near horizontal,
    // relaxed shin hanging below the raised knee, foot well off the ground -- 94.49 degrees, below
    // this config's 100-degree flexed threshold.
    private fun kneeLiftLeftKnee(raised: Boolean): List<Landmark> = if (raised) {
        knee(Pair(.5, .30), Pair(.62, .32), Pair(.60, .55))
    } else {
        knee(Pair(.5, .30), Pair(.56, .435), Pair(.55, .675))
    }

    private fun kneeAt(angle: Double): List<Landmark> = kneeAtDegrees(angle)

    private fun kneeAt(angle: Double, hipIndex: Int, kneeIndex: Int, ankleIndex: Int): List<Landmark> =
        when (angle) {
            170.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.52, .75), hipIndex, kneeIndex, ankleIndex)
            120.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.716506, .75), hipIndex, kneeIndex, ankleIndex)
            70.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.617462, .415), hipIndex, kneeIndex, ankleIndex)
            else -> error("test angle not defined: $angle")
        }

    private fun kneeAtDegrees(angle: Double): List<Landmark> = when (angle) {
        170.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.52, .75))
        130.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.669845, .535811))
        120.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.716506, .75))
        70.0 -> knee(Pair(.5, .25), Pair(.5, .5), Pair(.617462, .415))
        else -> error("test angle not defined: $angle")
    }

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
