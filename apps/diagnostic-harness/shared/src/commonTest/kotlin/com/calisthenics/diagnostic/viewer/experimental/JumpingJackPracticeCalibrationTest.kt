package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_WRIST = 15
private const val RIGHT_WRIST = 16
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28

/** Practice arm/leg fixture: sustained closed -> sustained open -> sustained closed. */
private val PRACTICE_ARM_THETAS = listOf(20.0, 20.0, 70.0, 120.0, 120.0, 70.0, 20.0, 20.0)
private val PRACTICE_LEG_RATIOS = listOf(0.2, 0.2, 0.8, 1.5, 1.5, 0.8, 0.2, 0.2)

class JumpingJackPracticeCalibrationTest {
    private val base = JumpingJackConfig(
        armsUpDegrees = 100.0,
        armsDownDegrees = 25.0,
        legsSpreadRatio = 1.4,
        legsTogetherRatio = .3,
        minimumConfidence = .5,
        maximumGapNs = 250,
        minimumEndpointSamples = 2,
    )

    @Test
    fun learnsExactLosslessArmsDownAndKeepsEveryOtherFieldUnchanged() {
        val fitted = calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS), base, 20.0, .15)

        // Literal expectation: low=20, high=120, range=100, fraction=.15 => 20 + 15 = 35.
        assertEquals(35.0, fitted.armsDownDegrees, 1e-9)
        assertEquals(base.armsUpDegrees, fitted.armsUpDegrees)
        assertEquals(base.legsSpreadRatio, fitted.legsSpreadRatio)
        assertEquals(base.legsTogetherRatio, fitted.legsTogetherRatio)
        assertEquals(base.minimumConfidence, fitted.minimumConfidence)
        assertEquals(base.maximumGapNs, fitted.maximumGapNs)
        assertEquals(base.minimumEndpointSamples, fitted.minimumEndpointSamples)
    }

    @Test
    fun learnedClosingCountsALaterDeepOpenCycleWhoseMinimumExceedsTheOldFixedAngle() {
        val fitted = calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS), base, 20.0, .15)
        // A later cycle only closes to arm theta 30 (never reaching the old fixed 25), but 30 <= the
        // learned 35, so the fitted config counts it while the un-fitted baseline never does.
        val laterCycle = listOf(30.0, 30.0, 70.0, 120.0, 120.0, 70.0, 30.0, 30.0)
        val laterLegs = listOf(.2, .2, .8, 1.5, 1.5, .8, .2, .2)

        val underFitted = JumpingJackExperiment(fitted)
        frames(laterCycle, laterLegs).forEach { underFitted.onFrame(it) }
        val underBaseline = JumpingJackExperiment(base)
        frames(laterCycle, laterLegs).forEach { underBaseline.onFrame(it) }

        assertEquals(1, underFitted.count)
        assertEquals(0, underBaseline.count)
    }

    @Test
    fun evaluationFramesCannotChangeTheLearnedBandBecauseCalibrationOnlyConsumesPracticeFrames() {
        val fitted = calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS), base, 20.0, .15)
        // Feeding wildly different "evaluation" frames through a freshly fitted config must not
        // resurface as a different learned threshold -- calibration already returned a fixed value.
        val experiment = JumpingJackExperiment(fitted)
        frames(listOf(5.0, 5.0, 170.0, 170.0), listOf(.1, .1, 2.0, 2.0)).forEach { experiment.onFrame(it) }
        assertEquals(35.0, fitted.armsDownDegrees, 1e-9)
    }

    @Test
    fun openingThresholdIsNeverLearnedOrChanged() {
        val fitted = calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS), base, 20.0, .15)
        assertEquals(100.0, fitted.armsUpDegrees, 1e-9)
    }

    @Test
    fun learnedClosedThresholdAtOrAboveOpenIsAnExplicitCalibrationError() {
        val lowCeiling = base.copy(armsUpDegrees = 30.0)
        assertFailsWith<IllegalArgumentException> {
            calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS), lowCeiling, 20.0, .15)
        }
    }

    @Test
    fun oneFrameSpikeAndShallowRangeCannotDefineABand() {
        for (thetas in listOf(
            listOf(20.0, 20.0, 20.0, 120.0, 20.0, 20.0, 20.0), // single-sample spike, never sustained
            List(8) { 20.0 }, // no range at all
        )) {
            assertFailsWith<IllegalArgumentException>(thetas.toString()) {
                calibrateJumpingJackPractice(frames(thetas, List(thetas.size) { .2 }), base, 20.0, .15)
            }
        }
    }

    @Test
    fun tooFewObservationsForTheRequiredSupportIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            calibrateJumpingJackPractice(frames(listOf(20.0, 20.0, 120.0, 120.0, 20.0), listOf(.2, .2, 1.5, 1.5, .2)), base, 20.0, .15)
        }
    }

    @Test
    fun missingOpenNeverAdmitsPracticeRegardlessOfLearnedRange() {
        // Arms swing widely enough to pass the range/endpoint checks, but never jointly reach the
        // BASE open requirement (arm>=armsUp AND leg>=legsSpread) together.
        val shallowOpen = listOf(20.0, 20.0, 60.0, 90.0, 90.0, 60.0, 20.0, 20.0)
        val legs = listOf(.2, .2, .8, 1.5, 1.5, .8, .2, .2)
        assertFailsWith<IllegalArgumentException> {
            calibrateJumpingJackPractice(frames(shallowOpen, legs), base, 20.0, .15)
        }
    }

    @Test
    fun armOnlyPracticeWhereLegsNeverSpreadIsRejected() {
        val legsNeverSpread = List(PRACTICE_ARM_THETAS.size) { .2 }
        assertFailsWith<IllegalArgumentException> {
            calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, legsNeverSpread), base, 20.0, .15)
        }
    }

    @Test
    fun endpointsMustJointlySatisfyLearnedArmsDownAndBaseLegsTogether() {
        // Legs never return together at the end, only arms do.
        val legsNeverReturn = listOf(.2, .2, .8, 1.5, 1.5, .8, .2, 1.5)
        assertFailsWith<IllegalArgumentException> {
            calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, legsNeverReturn), base, 20.0, .15)
        }
    }

    @Test
    fun unusablePracticeCannotBeJoinedAcrossGapsOrDimensionChangesOrLowConfidence() {
        val valid = frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS)
        val variants = listOf(
            valid.mapIndexed { i, f -> if (i == 3) f.copy(landmarks = emptyList()) else f },
            valid.mapIndexed { i, f -> if (i >= 3) f.copy(timestampNs = f.timestampNs + 1000) else f },
            valid.mapIndexed { i, f -> if (i == 3) f.copy(timestampNs = 5) else f },
            valid.mapIndexed { i, f -> if (i == 3) f.copy(width = 200) else f },
            valid.mapIndexed { i, f ->
                if (i == 3) f.copy(landmarks = f.landmarks.map { it.copy(visibility = .1) }) else f
            },
        )
        for (variant in variants) assertFailsWith<IllegalArgumentException> {
            calibrateJumpingJackPractice(variant, base, 20.0, .15)
        }
    }

    @Test
    fun invalidCalibrationParametersAreRejected() {
        val practiceFrames = frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS)
        for (minimumRange in listOf(0.0, -1.0, 180.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { calibrateJumpingJackPractice(practiceFrames, base, minimumRange, .15) }
        }
        for (fraction in listOf(0.0, -.1, .5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { calibrateJumpingJackPractice(practiceFrames, base, 20.0, fraction) }
        }
    }

    @Test
    fun fittedConfigStillRejectsADirectTogetherToOpenJumpAsACandidateCycle() {
        val fitted = calibrateJumpingJackPractice(frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS), base, 20.0, .15)
        val experiment = JumpingJackExperiment(fitted)
        frames(listOf(20.0, 20.0, 120.0, 120.0), listOf(.2, .2, 1.5, 1.5)).forEach { experiment.onFrame(it) }
        assertEquals(0, experiment.count)
        assertEquals(JumpingJackPhase.WAITING, experiment.phase)
    }

    @Test
    fun firstSupportedSearchPrefixFreezesItsCompletionAndEffectiveConfigBeforeLaterFrames() {
        val accepted = frames(
            listOf(20.0, 20.0, 120.0, 120.0, 20.0, 20.0),
            listOf(.2, .2, 1.5, 1.5, .2, .2),
        )
        val search = JumpingJackPracticeSearch(0, 1_000, 20.0, .15)

        val initial = findFirstJumpingJackPracticeCalibration(accepted, base, search)
        val future = accepted + frames(
            listOf(120.0, 120.0),
            listOf(1.5, 1.5),
        ).mapIndexed { index, frame -> frame.copy(timestampNs = 600L + index * 100L) } +
            accepted.last().copy(timestampNs = 800, width = 200)
        val appended = findFirstJumpingJackPracticeCalibration(future, base, search)

        assertEquals(5, initial!!.completionIndex)
        assertEquals(initial, appended)
        assertEquals(35.0, initial.effectiveConfig.armsDownDegrees, 1e-9)
        assertEquals(base.armsUpDegrees, initial.effectiveConfig.armsUpDegrees)
        assertEquals(base.legsSpreadRatio, initial.effectiveConfig.legsSpreadRatio)
        assertEquals(base.legsTogetherRatio, initial.effectiveConfig.legsTogetherRatio)
        assertEquals(base.minimumConfidence, initial.effectiveConfig.minimumConfidence)
        assertEquals(base.maximumGapNs, initial.effectiveConfig.maximumGapNs)
        assertEquals(base.minimumEndpointSamples, initial.effectiveConfig.minimumEndpointSamples)
    }

    @Test
    fun searchRejectsPrecompletionBadInputAndNeverAdmitsAShallowOrArmOnlyOpen() {
        val valid = frames(PRACTICE_ARM_THETAS, PRACTICE_LEG_RATIOS)
        val search = JumpingJackPracticeSearch(0, 700, 20.0, .15)
        val badPrefixes = listOf(
            valid.mapIndexed { index, frame -> if (index == 3) frame.copy(landmarks = emptyList()) else frame },
            valid.mapIndexed { index, frame -> if (index == 3) frame.copy(width = 200) else frame },
            valid.mapIndexed { index, frame -> if (index == 3) frame.copy(timestampNs = 5) else frame },
            valid.mapIndexed { index, frame -> if (index == 3) frame.copy(timestampNs = 500) else frame },
        )
        for (badPrefix in badPrefixes) {
            assertFailsWith<IllegalArgumentException> {
                findFirstJumpingJackPracticeCalibration(badPrefix, base, search)
            }
        }

        val armOnly = frames(PRACTICE_ARM_THETAS, List(PRACTICE_ARM_THETAS.size) { .2 })
        val shallowArm = frames(
            listOf(20.0, 20.0, 90.0, 90.0, 20.0, 20.0),
            listOf(.2, .2, 1.5, 1.5, .2, .2),
        )
        assertEquals(null, findFirstJumpingJackPracticeCalibration(armOnly, base, search))
        assertEquals(null, findFirstJumpingJackPracticeCalibration(shallowArm, base, search))
    }

    @Test
    fun searchAcceptsTheCompletionAtItsInclusiveDeadlineAndReturnsNullBeforeIt() {
        val completeAtDeadline = frames(
            listOf(20.0, 20.0, 120.0, 120.0, 20.0, 20.0),
            listOf(.2, .2, 1.5, 1.5, .2, .2),
        )

        val admitted = findFirstJumpingJackPracticeCalibration(
            completeAtDeadline,
            base,
            JumpingJackPracticeSearch(0, 500, 20.0, .15),
        )
        val tooEarly = findFirstJumpingJackPracticeCalibration(
            completeAtDeadline,
            base,
            JumpingJackPracticeSearch(0, 400, 20.0, .15),
        )

        assertEquals(5, admitted!!.completionIndex)
        assertEquals(null, tooEarly)
        assertFailsWith<IllegalArgumentException> {
            JumpingJackPracticeSearch(-1, 500, 20.0, .15)
        }
    }

    private fun frames(
        armThetaDegrees: List<Double>,
        legSpreadRatios: List<Double>,
        stepNs: Long = 100L,
    ): List<JumpingJackPoseFrame> = armThetaDegrees.indices.map { i ->
        JumpingJackPoseFrame(
            i * stepNs,
            400,
            400,
            armLandmarks(armThetaDegrees[i]) + legLandmarks(legSpreadRatios[i]),
        )
    }

    // Shoulders directly above hips; rotating the wrist by theta around the shoulder produces an
    // EXACT hip-shoulder-wrist angle of theta for both arms, so the bilateral average is theta too.
    private fun armLandmarks(thetaDegrees: Double): List<Landmark> {
        val radians = thetaDegrees * PI / 180.0
        return listOf(
            Landmark(LEFT_SHOULDER, null, .45, .30, 0.0, .95, .95),
            Landmark(RIGHT_SHOULDER, null, .55, .30, 0.0, .95, .95),
            Landmark(LEFT_WRIST, null, .45 + .25 * sin(radians), .30 + .25 * cos(radians), 0.0, .95, .95),
            Landmark(RIGHT_WRIST, null, .55 + .25 * sin(radians), .30 + .25 * cos(radians), 0.0, .95, .95),
        )
    }

    // Hips are .10 apart; ankles symmetric around the midline at ratio*.10 apart => ratio EXACTLY.
    private fun legLandmarks(ratio: Double): List<Landmark> = listOf(
        Landmark(LEFT_HIP, null, .45, .55, 0.0, .95, .95),
        Landmark(RIGHT_HIP, null, .55, .55, 0.0, .95, .95),
        Landmark(LEFT_ANKLE, null, .5 - ratio * .10 / 2.0, .90, 0.0, .95, .95),
        Landmark(RIGHT_ANKLE, null, .5 + ratio * .10 / 2.0, .90, 0.0, .95, .95),
    )
}
