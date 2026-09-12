package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SquatPracticeCalibrationTest {
    private val base = SquatConfig(ArmSide.LEFT, 150.0, 110.0, .5, 250, 2)
    private val practice = listOf(175.0, 175.0, 150.0, 125.0, 125.0, 150.0, 175.0, 175.0)

    @Test
    fun invalidExcursionCannotDisableTheMotionGate() {
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 181.0)) {
            assertFailsWith<IllegalArgumentException> { base.copy(oppositeMinimumExcursionDegrees = value) }
        }
    }

    @Test
    fun motionWitnessAcceptsBilateralDescentWithoutRequiringMatchingDepth() {
        val fitted = calibrateSquatPractice(frames(practice, rightAngles = practice), base, 20.0, .2)
        val witness = SquatCycleExperiment(fitted.copy(oppositeMinimumExcursionDegrees = 20.0))
        val absolute = SquatCycleExperiment(fitted)
        val opposite = listOf(175.0, 175.0, 160.0, 145.0, 145.0, 160.0, 175.0, 175.0)
        frames(practice, rightAngles = opposite).forEach { witness.onFrame(it); absolute.onFrame(it) }
        assertEquals(1, witness.count)
        assertEquals(0, absolute.count)
    }

    @Test
    fun motionWitnessRejectsStraightBentAndSlightlySwayingStaticOppositeLegs() {
        val fitted = calibrateSquatPractice(frames(practice, rightAngles = practice), base, 20.0, .2)
        for (opposite in listOf(List(8) { 175.0 }, List(8) { 130.0 },
            listOf(175.0, 175.0, 173.0, 170.0, 170.0, 173.0, 175.0, 175.0))) {
            val detector = SquatCycleExperiment(fitted.copy(oppositeMinimumExcursionDegrees = 20.0))
            frames(practice, rightAngles = opposite).forEach { detector.onFrame(it) }
            assertEquals(0, detector.count, opposite.toString())
        }
    }

    @Test
    fun eachCandidateNeedsItsOwnOppositeStandingReference() {
        val fitted = calibrateSquatPractice(frames(practice, rightAngles = practice), base, 20.0, .2)
        val detector = SquatCycleExperiment(fitted.copy(oppositeMinimumExcursionDegrees = 20.0))
        frames(practice, rightAngles = practice).forEach { detector.onFrame(it) }
        assertEquals(1, detector.count)
        frames(practice, rightAngles = List(8) { 130.0 }).forEach {
            detector.onFrame(it.copy(timestampNs = it.timestampNs + 800))
        }
        assertEquals(1, detector.count)
    }

    @Test
    fun missingOppositeFramesInvalidateTheMotionReferenceEvenBeforeTheBottom() {
        val fitted = calibrateSquatPractice(frames(practice, rightAngles = practice), base, 20.0, .2)
        for (gap in listOf(false, true)) {
            val detector = SquatCycleExperiment(fitted.copy(oppositeMinimumExcursionDegrees = 20.0))
            frames(practice, rightAngles = practice).forEachIndexed { i, f ->
                val broken = if (gap && i >= 2) f.copy(timestampNs = f.timestampNs + 1000)
                    else if (!gap && i == 2) f.copy(landmarks = f.landmarks.filter { it.index % 2 == 1 }) else f
                detector.onFrame(broken)
            }
            assertEquals(0, detector.count)
        }
    }

    @Test
    fun perSideRangesCountLaterCyclesThatNeverReachTheOldFixedAngle() {
        val fitted = calibrateSquatPractice(frames(practice), base, 20.0, .2)
        // Literal expectations: left 125..175 => 135/165; right 150..175 => 155/170.
        assertEquals(135.0, fitted.flexedDegrees, 1e-7)
        assertEquals(165.0, fitted.extendedDegrees, 1e-7)
        assertEquals(155.0, fitted.oppositeFlexedDegrees, 1e-7)
        val detector = SquatCycleExperiment(fitted)
        val baseline = SquatCycleExperiment(base)
        frames(practice).forEach { detector.onFrame(it); baseline.onFrame(it) }
        assertEquals(1, detector.count)
        assertEquals(0, baseline.count)
    }

    @Test
    fun calibratedBilateralGateStillRejectsAStationaryOppositeLeg() {
        val fitted = calibrateSquatPractice(frames(practice), base, 20.0, .2)
        val detector = SquatCycleExperiment(fitted)
        frames(practice, stationaryRight = true).forEach { detector.onFrame(it) }
        assertEquals(0, detector.count)
    }

    @Test
    fun oppositeLegRangeCannotBeBorrowedFromTheSelectedLeg() {
        assertFailsWith<IllegalArgumentException> {
            calibrateSquatPractice(frames(practice, stationaryRight = true), base, 20.0, .2)
        }
    }

    @Test
    fun incompletePracticeAndSingleSampleSpikesCannotDefineARange() {
        for (angles in listOf(
            listOf(175.0, 175.0, 150.0, 125.0, 125.0),
            listOf(125.0, 125.0, 150.0, 175.0, 175.0),
            listOf(175.0, 175.0, 175.0, 125.0, 175.0, 175.0, 175.0),
            List(8) { 175.0 },
        )) {
            assertFailsWith<IllegalArgumentException>(angles.toString()) {
                calibrateSquatPractice(frames(angles), base, 20.0, .2)
            }
        }
    }

    @Test
    fun unusablePracticeCannotBeJoinedAcrossGapsOrDimensionChanges() {
        val valid = frames(practice)
        val variants = listOf(
            valid.mapIndexed { i, f -> if (i == 3) f.copy(landmarks = emptyList()) else f },
            valid.mapIndexed { i, f -> if (i >= 3) f.copy(timestampNs = f.timestampNs + 1000) else f },
            valid.mapIndexed { i, f -> if (i == 3) f.copy(timestampNs = 200) else f },
            valid.mapIndexed { i, f -> if (i == 3) f.copy(width = 200) else f },
            valid.mapIndexed { i, f -> if (i == 3) f.copy(landmarks = f.landmarks.map { it.copy(visibility = .1) }) else f },
        )
        for (frames in variants) assertFailsWith<IllegalArgumentException> {
            calibrateSquatPractice(frames, base, 20.0, .2)
        }
    }

    @Test
    fun laterIncompleteCyclesAndTrackingGapsDoNotCountAfterCalibration() {
        val fitted = calibrateSquatPractice(frames(practice), base, 20.0, .2)
        val partial = SquatCycleExperiment(fitted)
        frames(listOf(175.0, 175.0, 150.0, 150.0, 150.0, 175.0, 175.0)).forEach { partial.onFrame(it) }
        assertEquals(0, partial.count)
        val interrupted = SquatCycleExperiment(fitted)
        frames(practice).forEachIndexed { i, frame ->
            interrupted.onFrame(if (i >= 3) frame.copy(timestampNs = frame.timestampNs + 1000) else frame)
        }
        assertEquals(0, interrupted.count)
    }

    private fun frames(angles: List<Double>, stationaryRight: Boolean = false, rightAngles: List<Double>? = null) = angles.mapIndexed { i, angle ->
        SquatPoseFrame(i * 100L, 100, 100,
            leg(angle, 23) + leg(rightAngles?.get(i) ?: if (stationaryRight) 175.0 else 175.0 - (175.0 - angle) / 2, 24))
    }

    private fun leg(angle: Double, hip: Int): List<Landmark> {
        val radians = angle * PI / 180.0
        return listOf(
            Landmark(hip, null, .5, .25, 0.0, .95, .95),
            Landmark(hip + 2, null, .5, .5, 0.0, .95, .95),
            Landmark(hip + 4, null, .5 + .25 * sin(radians), .5 - .25 * cos(radians), 0.0, .95, .95),
        )
    }
}
