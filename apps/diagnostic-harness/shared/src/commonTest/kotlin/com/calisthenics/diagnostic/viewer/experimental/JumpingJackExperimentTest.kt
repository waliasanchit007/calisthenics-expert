package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val LEFT_SHOULDER = 11
private const val RIGHT_SHOULDER = 12
private const val LEFT_WRIST = 15
private const val RIGHT_WRIST = 16
private const val LEFT_HIP = 23
private const val RIGHT_HIP = 24
private const val LEFT_ANKLE = 27
private const val RIGHT_ANKLE = 28

class JumpingJackExperimentTest {
    private val config = JumpingJackConfig(
        armsUpDegrees = 150.0,
        armsDownDegrees = 30.0,
        legsSpreadRatio = 2.5,
        legsTogetherRatio = 1.0,
        minimumConfidence = .8,
        maximumGapNs = 500,
        minimumEndpointSamples = 2,
    )

    @Test
    fun measuresAveragedArmDegreesAndAnkleHipSpreadRatio() {
        val closed = measureJumpingJack(frame(0, 400, 400, closedLandmarks()), config)
        assertEquals(6.619329748788, closed.armDegrees!!, 1e-4)
        assertEquals(0.666666667, closed.legSpreadRatio!!, 1e-4)
        assertEquals(JumpingJackReason.NONE, closed.reason)

        val open = measureJumpingJack(frame(0, 400, 400, openLandmarks()), config)
        assertEquals(173.263988786, open.armDegrees!!, 1e-4)
        assertEquals(6.666666667, open.legSpreadRatio!!, 1e-4)
        assertEquals(JumpingJackReason.NONE, open.reason)
    }

    @Test
    fun countsOnlyTogetherOpenTogetherWithEndpointEvidence() {
        val experiment = JumpingJackExperiment(config)
        assertEquals(JumpingJackPhase.WAITING, experiment.onFrame(frame(0, 400, 400, closedLandmarks())).phase)
        assertEquals(JumpingJackPhase.TOGETHER, experiment.onFrame(frame(100, 400, 400, closedLandmarks())).phase)
        assertEquals(JumpingJackPhase.OPENING, experiment.onFrame(frame(200, 400, 400, transitionLandmarks())).phase)
        assertEquals(JumpingJackPhase.OPENING, experiment.onFrame(frame(300, 400, 400, openLandmarks())).phase)
        assertEquals(JumpingJackPhase.OPEN, experiment.onFrame(frame(400, 400, 400, openLandmarks())).phase)
        assertEquals(
            JumpingJackPhase.CLOSING,
            experiment.onFrame(frame(500, 400, 400, transitionLandmarks())).phase,
        )
        assertEquals(0, experiment.onFrame(frame(600, 400, 400, closedLandmarks())).count)
        val completed = experiment.onFrame(frame(700, 400, 400, closedLandmarks()))

        assertEquals(1, completed.count)
        assertEquals(JumpingJackPhase.TOGETHER, completed.phase)
        assertEquals(JumpingJackReason.NONE, completed.reason)
        assertEquals(true, completed.counted)
    }

    @Test
    fun ordinaryStandingAloneNeverCounts() {
        val experiment = JumpingJackExperiment(config)
        repeat(10) { index ->
            val result = experiment.onFrame(frame(index * 100L, 400, 400, closedLandmarks()))
            assertEquals(0, result.count)
            assertEquals(false, result.counted)
        }
        assertEquals(JumpingJackPhase.TOGETHER, experiment.phase)
    }

    @Test
    fun armOnlyMotionWithLegsStayingTogetherNeverCounts() {
        // Counterexample required by design: raising and lowering only the arms, legs never spread.
        val experiment = JumpingJackExperiment(config)
        val frames = listOf(
            0L to closedLandmarks(),
            100L to closedLandmarks(),
            200L to armsUpLegsTogetherLandmarks(),
            300L to armsUpLegsTogetherLandmarks(),
            400L to closedLandmarks(),
            500L to closedLandmarks(),
        )
        val results = frames.map { (t, landmarks) -> experiment.onFrame(frame(t, 400, 400, landmarks)) }

        assertEquals(true, results.none { it.counted })
        assertEquals(0, experiment.count)
    }

    @Test
    fun legOnlyMotionWithArmsStayingDownNeverCounts() {
        // Counterexample required by design: spreading and returning only the legs, arms never rise.
        val experiment = JumpingJackExperiment(config)
        val frames = listOf(
            0L to closedLandmarks(),
            100L to closedLandmarks(),
            200L to armsDownLegsSpreadLandmarks(),
            300L to armsDownLegsSpreadLandmarks(),
            400L to closedLandmarks(),
            500L to closedLandmarks(),
        )
        val results = frames.map { (t, landmarks) -> experiment.onFrame(frame(t, 400, 400, landmarks)) }

        assertEquals(true, results.none { it.counted })
        assertEquals(0, experiment.count)
    }

    @Test
    fun singleLegStepDoesNotHaveTheSameBilateralProtectionAsArms() {
        // Disclosed confound, not a bug: averaging both arms means a single-arm raise cannot alone
        // cross either arm threshold (see armOnlyMotionWithLegsStayingTogetherNeverCounts and
        // measureJumpingJack's doc comment). The leg-spread ratio has no equivalent protection: it is
        // a single symmetric ankle-distance/hip-width number, so ONE leg stepping far out while the
        // other stays planted at its "together" position can, on its own, cross legsSpreadRatio (see
        // measuresAveragedArmDegreesAndAnkleHipSpreadRatio-style geometry: ~7.0 here versus the 2.5
        // threshold), even though this is not a real bilateral jumping-jack leg spread. Combined with
        // a genuine bilateral arm raise, this is counted as a completed jack. No asymmetric-leg
        // threshold is implemented to suppress this: doing so without real single-leg-step-confound
        // footage to validate against would be an unvalidated hidden threshold.
        val experiment = JumpingJackExperiment(config)
        val frames = listOf(
            0L to closedLandmarks(),
            100L to closedLandmarks(),
            200L to transitionLandmarks(),
            300L to armsUpSingleLegSpreadLandmarks(),
            400L to armsUpSingleLegSpreadLandmarks(),
            500L to transitionLandmarks(),
            600L to closedLandmarks(),
            700L to closedLandmarks(),
        )
        val results = frames.map { (t, landmarks) -> experiment.onFrame(frame(t, 400, 400, landmarks)) }

        assertEquals(
            true,
            results.last().counted,
            "confound: a bilateral arm raise plus a single-leg-only step counts as a completed jack",
        )
    }

    @Test
    fun eitherSingleRaisedArmIsRejectedEvenWhenLegsMeetTheOpenGate() {
        // Exercise both the unit-test and real replay arm thresholds. Legs MUST spread:
        // keeping them together would reject even a broken unilateral-arm implementation.
        for (upThreshold in listOf(150.0, 110.0)) {
            val candidateConfig = config.copy(armsUpDegrees = upThreshold)
            fun countWith(open: List<Landmark>): Int {
                val experiment = JumpingJackExperiment(candidateConfig)
                listOf(
                    closedLandmarks(), closedLandmarks(), transitionLandmarks(),
                    open, open, transitionLandmarks(), closedLandmarks(), closedLandmarks(),
                ).forEachIndexed { index, landmarks ->
                    experiment.onFrame(frame(index * 100L, 400, 400, landmarks))
                }
                return experiment.count
            }
            assertEquals(1, countWith(openLandmarks()), "paired bilateral positive control")
            for (downWrist in listOf(LEFT_WRIST, RIGHT_WRIST)) {
                val resting = closedLandmarks().single { it.index == downWrist }
                val unilateral = openLandmarks().map { if (it.index == downWrist) resting else it }
                val measurement = measureJumpingJack(frame(0, 400, 400, unilateral), candidateConfig)
                assertEquals(true, measurement.legSpreadRatio!! >= candidateConfig.legsSpreadRatio)
                assertEquals(
                    0, countWith(unilateral),
                    "single arm must not count: downWrist=$downWrist, upThreshold=$upThreshold",
                )
            }
        }
    }

    @Test
    fun captureGapInvalidatesAnUnfinishedCycle() {
        val experiment = JumpingJackExperiment(config)
        listOf(0L to closedLandmarks(), 100L to closedLandmarks(), 200L to openLandmarks())
            .forEach { (t, landmarks) -> experiment.onFrame(frame(t, 400, 400, landmarks)) }

        experiment.onFrame(frame(1_000, 400, 400, closedLandmarks()))
        val afterGap = experiment.onFrame(frame(1_600, 400, 400, closedLandmarks()))
        assertEquals(0, afterGap.count)
        assertEquals(JumpingJackReason.CAPTURE_GAP, afterGap.reason)
        assertEquals(JumpingJackPhase.WAITING, afterGap.phase)
    }

    @Test
    fun noPoseInvalidatesAnUnfinishedCycle() {
        val experiment = JumpingJackExperiment(config)
        listOf(0L to closedLandmarks(), 100L to closedLandmarks(), 200L to openLandmarks())
            .forEach { (t, landmarks) -> experiment.onFrame(frame(t, 400, 400, landmarks)) }

        val noPose = experiment.onFrame(frame(300, 400, 400, emptyList()))
        assertNull(noPose.armDegrees)
        assertEquals(JumpingJackReason.NO_POSE, noPose.reason)
        assertEquals(JumpingJackPhase.UNCERTAIN, noPose.phase)
    }

    @Test
    fun negativeTimestampInvalidatesPendingEvidenceBeforeLaterFrames() {
        val experiment = JumpingJackExperiment(config)
        experiment.onFrame(frame(0, 400, 400, closedLandmarks()))
        experiment.onFrame(frame(100, 400, 400, closedLandmarks()))
        experiment.onFrame(frame(200, 400, 400, openLandmarks()))

        val invalid = experiment.onFrame(frame(-1, 400, 400, openLandmarks()))
        assertEquals(JumpingJackReason.INVALID_TIMESTAMP, invalid.reason)
        assertEquals(JumpingJackPhase.UNCERTAIN, invalid.phase)
    }

    @Test
    fun repeatedAndBackwardsTimestampsCannotAdvanceTheCandidate() {
        val experiment = JumpingJackExperiment(config)
        experiment.onFrame(frame(0, 400, 400, closedLandmarks()))
        experiment.onFrame(frame(100, 400, 400, closedLandmarks()))

        val duplicate = experiment.onFrame(frame(100, 400, 400, openLandmarks()))
        assertEquals(JumpingJackReason.DUPLICATE_TIMESTAMP, duplicate.reason)
        val backwards = experiment.onFrame(frame(50, 400, 400, openLandmarks()))
        assertEquals(JumpingJackReason.BACKWARDS_TIMESTAMP, backwards.reason)
        assertEquals(0, experiment.count)
    }

    @Test
    fun unusableLandmarksDoNotProduceMeasurements() {
        val valid = closedLandmarks()
        val cases = listOf(
            valid.dropLast(1),
            valid + valid.last(),
            valid.map { if (it.index == LEFT_WRIST) it.copy(x = Double.NaN) else it },
            valid.map { if (it.index == LEFT_ANKLE) it.copy(visibility = .79) else it },
            valid.map { if (it.index == RIGHT_HIP) it.copy(presence = Double.POSITIVE_INFINITY) else it },
        )

        cases.forEach { landmarks ->
            val result = measureJumpingJack(frame(0, 400, 400, landmarks), config)
            assertNull(result.armDegrees, landmarks.toString())
            assertNull(result.legSpreadRatio, landmarks.toString())
            assertEquals(true, result.reason != JumpingJackReason.NONE, landmarks.toString())
        }
    }

    @Test
    fun configRejectsNonFiniteOutOfBoundsAndInvertedThresholds() {
        assertFailsWith<IllegalArgumentException> { config.copy(armsUpDegrees = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { config.copy(armsUpDegrees = 181.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(armsDownDegrees = 150.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(legsSpreadRatio = 0.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(legsTogetherRatio = 2.5) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = -0.1) }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumEndpointSamples = 0) }
    }

    private fun frame(timestampNs: Long, width: Int, height: Int, landmarks: List<Landmark>): JumpingJackPoseFrame =
        JumpingJackPoseFrame(timestampNs, width, height, landmarks)

    // Square 400x400 frame so raw normalized deltas are usable directly without aspect scaling.
    private fun landmarks(
        leftWrist: Pair<Double, Double>,
        rightWrist: Pair<Double, Double>,
        leftAnkle: Pair<Double, Double>,
        rightAnkle: Pair<Double, Double>,
    ): List<Landmark> = listOf(
        Landmark(LEFT_SHOULDER, null, .45, .30, 0.0, .95, .95),
        Landmark(RIGHT_SHOULDER, null, .55, .30, 0.0, .95, .95),
        Landmark(LEFT_WRIST, null, leftWrist.first, leftWrist.second, 0.0, .95, .95),
        Landmark(RIGHT_WRIST, null, rightWrist.first, rightWrist.second, 0.0, .95, .95),
        Landmark(LEFT_HIP, null, .47, .55, 0.0, .95, .95),
        Landmark(RIGHT_HIP, null, .53, .55, 0.0, .95, .95),
        Landmark(LEFT_ANKLE, null, leftAnkle.first, leftAnkle.second, 0.0, .95, .95),
        Landmark(RIGHT_ANKLE, null, rightAnkle.first, rightAnkle.second, 0.0, .95, .95),
    )

    // Arms down at sides (~6.6 degrees), ankles together (~0.667 ratio).
    private fun closedLandmarks(): List<Landmark> =
        landmarks(Pair(.44, .58), Pair(.56, .58), Pair(.48, .90), Pair(.52, .90))

    // Arms overhead (~173.3 degrees), ankles spread (~6.67 ratio).
    private fun openLandmarks(): List<Landmark> =
        landmarks(Pair(.40, .05), Pair(.60, .05), Pair(.30, .90), Pair(.70, .90))

    // Arms horizontal (~94.6 degrees), ankles at a moderate spread (~2.0 ratio) -- strictly between
    // both pairs of thresholds, so neither "together" nor "open" is true.
    private fun transitionLandmarks(): List<Landmark> =
        landmarks(Pair(.20, .30), Pair(.80, .30), Pair(.44, .90), Pair(.56, .90))

    // Arms overhead, ankles together: isolates arm elevation from leg spread.
    private fun armsUpLegsTogetherLandmarks(): List<Landmark> =
        landmarks(Pair(.40, .05), Pair(.60, .05), Pair(.48, .90), Pair(.52, .90))

    // Arms down, ankles spread: isolates leg spread from arm elevation.
    private fun armsDownLegsSpreadLandmarks(): List<Landmark> =
        landmarks(Pair(.44, .58), Pair(.56, .58), Pair(.30, .90), Pair(.70, .90))

    // Arms overhead (bilateral), but only the LEFT ankle steps out; the right ankle stays at its
    // "together" x-position. The ankle-distance/hip-width ratio is ~7.0, still above legsSpreadRatio.
    private fun armsUpSingleLegSpreadLandmarks(): List<Landmark> =
        landmarks(Pair(.40, .05), Pair(.60, .05), Pair(.10, .90), Pair(.52, .90))
}
