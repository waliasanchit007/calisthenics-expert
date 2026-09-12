package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for the not-yet-implemented `selectSitupSide` pure function: choose an eligible side once
 * from a declared setup window using tracking validity alone (never a rep count), per
 * docs/superpowers/sdd/2026-09-09-overnight-movement-evidence/situp-admission/design.md.
 */
class SitupSideAdmissionTest {
    private val baseConfig = SitupConfig(
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
    fun onlyLeftEligibleIsSelectedEvenWhenTheConfiguredSideIsRight() {
        val base = baseConfig.copy(side = ArmSide.RIGHT)
        val frames = listOf(frame(0, leftOnlyLandmarks()), frame(100, leftOnlyLandmarks()))

        assertEquals(ArmSide.LEFT, selectSitupSide(frames, base))
    }

    @Test
    fun onlyRightEligibleIsSelectedEvenWhenTheConfiguredSideIsLeft() {
        val base = baseConfig.copy(side = ArmSide.LEFT)
        val frames = listOf(frame(0, rightOnlyLandmarks()), frame(100, rightOnlyLandmarks()))

        assertEquals(ArmSide.RIGHT, selectSitupSide(frames, base))
    }

    @Test
    fun bothEligibleKeepsTheConfiguredSideAsTiePreferenceRight() {
        val base = baseConfig.copy(side = ArmSide.RIGHT)
        val frames = listOf(frame(0, bothSidesLandmarks()), frame(100, bothSidesLandmarks()))

        assertEquals(ArmSide.RIGHT, selectSitupSide(frames, base))
    }

    @Test
    fun bothEligibleKeepsTheConfiguredSideAsTiePreferenceLeft() {
        val base = baseConfig.copy(side = ArmSide.LEFT)
        val frames = listOf(frame(0, bothSidesLandmarks()), frame(100, bothSidesLandmarks()))

        assertEquals(ArmSide.LEFT, selectSitupSide(frames, base))
    }

    @Test
    fun neitherEligibleReturnsNullRatherThanTheConfiguredSide() {
        val frames = listOf(frame(0, lowConfidenceBothSidesLandmarks()), frame(100, lowConfidenceBothSidesLandmarks()))

        assertNull(selectSitupSide(frames, baseConfig))
    }

    @Test
    fun aSingleUnusableObservationMakesThatSideIneligibleEvenIfEveryOtherObservationPasses() {
        val base = baseConfig.copy(side = ArmSide.LEFT)
        val frames = listOf(
            frame(0, bothSidesLandmarks()),
            frame(100, rightOnlyLandmarks()),
        )

        assertEquals(ArmSide.RIGHT, selectSitupSide(frames, base))
    }

    @Test
    fun rejectsFewerObservationsThanTheRequiredMinimum() {
        val frames = listOf(frame(0, bothSidesLandmarks()))

        assertFailsWith<IllegalArgumentException> { selectSitupSide(frames, baseConfig) }
    }

    @Test
    fun rejectsAWindowSmallerThanMaxOfTwoAndTheConfiguredMinimumEndpointSamples() {
        val base = baseConfig.copy(minimumEndpointSamples = 5)
        val frames = listOf(0L, 100L, 200L, 300L).map { frame(it, bothSidesLandmarks()) }

        assertFailsWith<IllegalArgumentException> { selectSitupSide(frames, base) }
    }

    @Test
    fun rejectsANegativeFirstTimestamp() {
        val frames = listOf(frame(-1, bothSidesLandmarks()), frame(100, bothSidesLandmarks()))

        assertFailsWith<IllegalArgumentException> { selectSitupSide(frames, baseConfig) }
    }

    @Test
    fun rejectsNonIncreasingTimestamps() {
        val duplicate = listOf(frame(0, bothSidesLandmarks()), frame(0, bothSidesLandmarks()))
        val backwards = listOf(frame(100, bothSidesLandmarks()), frame(0, bothSidesLandmarks()))

        assertFailsWith<IllegalArgumentException> { selectSitupSide(duplicate, baseConfig) }
        assertFailsWith<IllegalArgumentException> { selectSitupSide(backwards, baseConfig) }
    }

    @Test
    fun rejectsAGapLargerThanTheConfiguredMaximumGap() {
        val frames = listOf(frame(0, bothSidesLandmarks()), frame(baseConfig.maximumGapNs + 1, bothSidesLandmarks()))

        assertFailsWith<IllegalArgumentException> { selectSitupSide(frames, baseConfig) }
    }

    @Test
    fun rejectsInconsistentFrameDimensions() {
        val frames = listOf(
            frame(0, bothSidesLandmarks(), width = 400, height = 400),
            frame(100, bothSidesLandmarks(), width = 401, height = 400),
        )

        assertFailsWith<IllegalArgumentException> { selectSitupSide(frames, baseConfig) }
    }

    @Test
    fun rejectsNonPositiveFrameDimensions() {
        val frames = listOf(
            frame(0, bothSidesLandmarks(), width = 0, height = 400),
            frame(100, bothSidesLandmarks(), width = 0, height = 400),
        )

        assertFailsWith<IllegalArgumentException> { selectSitupSide(frames, baseConfig) }
    }

    private fun frame(timestampNs: Long, landmarks: List<Landmark>, width: Int = 400, height: Int = 400) =
        SitupPoseFrame(timestampNs, width, height, landmarks)

    private fun landmark(index: Int, x: Double, y: Double, visibility: Double = 0.95, presence: Double = 0.95) =
        Landmark(index = index, name = null, x = x, y = y, z = 0.0, visibility = visibility, presence = presence)

    // Hand-derived side-view geometry reused from ReplaySitupPoseTest's reclined pose; only which
    // side's landmark indices are present or absent (or dropped below the confidence floor) varies.
    private fun leftOnlyLandmarks() = listOf(
        landmark(11, 0.25, 0.5),
        landmark(23, 0.5, 0.5),
        landmark(25, 0.6732051, 0.4),
    )

    private fun rightOnlyLandmarks() = listOf(
        landmark(12, 0.75, 0.5),
        landmark(24, 0.5, 0.5),
        landmark(26, 0.3267949, 0.4),
    )

    private fun bothSidesLandmarks() = leftOnlyLandmarks() + rightOnlyLandmarks()

    private fun lowConfidenceBothSidesLandmarks() = bothSidesLandmarks().map {
        it.copy(visibility = 0.1, presence = 0.1)
    }
}
