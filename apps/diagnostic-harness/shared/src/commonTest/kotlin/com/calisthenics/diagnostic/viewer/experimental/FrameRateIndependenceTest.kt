package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The property the 2026-09-16 decimation study says the engines must have and did not:
 * **the same movement, sampled at a different rate, must be counted the same way.**
 *
 * That study (ledger Update 44) measured the frozen configs against decimated caches and found the
 * hanging knee raise falling 6 -> 0 and the handstand push-up 5 -> 0 between 30 fps and 10 fps,
 * while the four engines whose `minimumEndpointSamples` is 2 were untouched. The cause was units:
 * a sample count is a frame-rate-dependent way of writing a duration. These tests pin the fix as a
 * property of the engines rather than as a number in a results table.
 *
 * Nothing here involves a recording, a label or an accuracy claim. Each timeline is a synthetic
 * reconstruction sampled on several grids, so the only thing that differs between the cases is the
 * sampling rate.
 */
class FrameRateIndependenceTest {

    private val kneeRaiseConfig = HangingLegRaiseConfig(
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
        // The real frozen value, and the one the study showed to be rate-fragile.
        minimumEndpointSamples = 3,
    )

    /**
     * The frozen positive-label phase shape used elsewhere in these tests: an opening descent that
     * must not count, a lowered dwell, a departure, a raised dwell, a lowering and a returning
     * lowered dwell. Expressed in SECONDS, so it is the same physical movement at every rate.
     */
    private fun hipDegreesAt(seconds: Double): Double = when {
        seconds < 0.95 -> ramp(seconds, 0.0, 0.95, 85.0, 175.0)
        seconds < 1.70 -> 175.0
        seconds < 2.30 -> ramp(seconds, 1.70, 2.30, 175.0, 85.0)
        seconds < 2.90 -> 85.0
        seconds < 3.40 -> ramp(seconds, 2.90, 3.40, 85.0, 175.0)
        else -> 175.0
    }

    private fun countKneeRaisesAt(fps: Double, config: HangingLegRaiseConfig = kneeRaiseConfig): Int {
        val experiment = HangingLegRaiseExperiment(config)
        var last = 0
        var index = 0
        while (index / fps <= 3.70) {
            val seconds = index / fps
            val timestampNs = (seconds * 1_000_000_000.0).toLong()
            last = experiment.onFrame(
                HangingLegRaisePoseFrame(timestampNs, 400, 400, hangingPose(hipDegreesAt(seconds))),
            ).count
            index++
        }
        return last
    }

    @Test
    fun oneKneeRaiseIsCountedOnceAtEveryCaptureRate() {
        // 30 fps is where the config was frozen; 10 fps is what the device pipeline delivered on
        // 2026-09-15. Under the old sample-count rule the 10 fps column of the study collapsed to
        // zero; the movement did not change, only the grid it was sampled on.
        for (fps in listOf(30.0, 25.0, 24.0, 20.0, 15.0, 12.0, 10.0)) {
            assertEquals(1, countKneeRaisesAt(fps), "one cycle should be counted at $fps fps")
        }
    }

    @Test
    fun theEndpointHoldIsStillLoadBearing() {
        // A mutation check in test form. If the gate were vacuous the counts above would prove
        // nothing, so demand that a hold longer than either dwell suppresses the count AT EVERY
        // RATE — the gate must be equally strict in physical time, not merely present.
        val tooLong = kneeRaiseConfig.copy(minimumEndpointSamples = 40) // 1.3 s at 30 fps
        for (fps in listOf(30.0, 15.0, 10.0)) {
            assertEquals(0, countKneeRaisesAt(fps, tooLong), "the hold gate must still bite at $fps fps")
        }
    }

    @Test
    fun aSingleFrameNeverConfirmsAnEndpointHoweverLongThePauseBeforeIt() {
        // The debounce the sample count used to provide has to survive the change to durations:
        // one frame is one observation, and an arbitrarily long gap before it is not evidence that
        // anything was held.
        val timing = ElbowCycleTiming(3)
        assertEquals(ElbowCyclePhase.WAITING, timing.phase)
        timing.onObservation(10_000_000_000L, isSupported = true, isLowered = false)
        assertEquals(
            ElbowCyclePhase.WAITING, timing.phase,
            "a lone supported frame confirmed an endpoint",
        )
        timing.onObservation(10_100_000_000L, isSupported = true, isLowered = false)
        assertEquals(ElbowCyclePhase.SUPPORTED, timing.phase)
    }

    @Test
    fun theHandstandReferenceSettlesInTheSameWallClockTimeAtAnyRate() {
        // `minimumReferenceSamples = 15` is 466.7 ms of settled inversion at 30 fps. At 10 fps the
        // study saw REFERENCE_UNAVAILABLE rise from 5% of frames to 85%, because 15 CONSECUTIVE
        // clean frames is 1.5 s there. The window must cover the same half second either way.
        for (fps in listOf(30.0, 10.0)) {
            val experiment = HandstandPushUpExperiment(hspuConfig)
            var settledAfterSeconds: Double? = null
            var index = 0
            while (index < (2.0 * fps).toInt()) {
                val seconds = index / fps
                val result = experiment.onFrame(
                    HspuPoseFrame(
                        (seconds * 1_000_000_000.0).toLong(), 400, 400,
                        hspuPose(shoulderY = .50, wristY = .78, hipY = .20),
                    ),
                )
                if (settledAfterSeconds == null && result.reason == HspuReason.NONE) {
                    settledAfterSeconds = seconds
                }
                index++
            }
            val settled = requireNotNull(settledAfterSeconds) { "no reference at $fps fps" }
            assertTrue(
                settled >= 0.466 && settled <= 0.60,
                "at $fps fps the reference settled after ${settled}s, outside the 466.7 ms window",
            )
        }
    }

    @Test
    fun aSampleCountIsReadAsTheDurationItSpansAtThirtyFramesPerSecond() {
        assertEquals(0L, sampleCountAsDurationNs(1), "one sample spans no time")
        assertEquals(33_333_333L, sampleCountAsDurationNs(2))
        assertEquals(66_666_666L, sampleCountAsDurationNs(3))
        assertEquals(466_666_662L, sampleCountAsDurationNs(15))
        // A config asking for a single sample keeps getting a single sample; raising it to two
        // would be a silent behaviour change rather than a change of units.
        assertEquals(1, endpointSampleFloor(1))
        assertEquals(2, endpointSampleFloor(2))
        assertEquals(2, endpointSampleFloor(9))
    }

    private val hspuConfig = HspuConfig(
        variant = HspuVariant.WALL_BACK_TO_WALL,
        side = ArmSide.LEFT,
        minimumHipAboveShoulderFraction = .10,
        minimumReferenceSamples = 15,
        maximumReferenceSpanSpreadRatio = .06,
        extendedSpanRatio = .80,
        loweredSpanRatio = .75,
        minimumConfidence = .5,
        maximumGapNs = 250_000_000L,
        minimumEndpointSamples = 2,
    )

    private fun ramp(at: Double, from: Double, to: Double, start: Double, end: Double): Double =
        start + (end - start) * ((at - from) / (to - from))

    private fun hspuPose(shoulderY: Double, wristY: Double, hipY: Double): List<Landmark> = listOf(
        Landmark(11, null, .50, shoulderY, 0.0, .9, .9),
        Landmark(15, null, .50, wristY, 0.0, .9, .9),
        Landmark(23, null, .50, hipY, 0.0, .9, .9),
    )

    /** The same construction the hanging-leg-raise tests use: observables set by geometry, not by code. */
    private fun hangingPose(
        hipDegrees: Double,
        kneeDegrees: Double = 180.0,
        trunkDegrees: Double = 90.0,
        supportRiseRatio: Double = 0.5,
    ): List<Landmark> {
        val radians = { degrees: Double -> degrees * PI / 180.0 }
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
        return listOf(
            Landmark(11, null, shoulder.first, shoulder.second, 0.0, 0.95, 0.95),
            Landmark(15, null, wrist.first, wrist.second, 0.0, 0.95, 0.95),
            Landmark(23, null, 0.5, 0.5, 0.0, 0.95, 0.95),
            Landmark(25, null, knee.first, knee.second, 0.0, 0.95, 0.95),
            Landmark(27, null, ankle.first, ankle.second, 0.0, 0.95, 0.95),
        )
    }
}
