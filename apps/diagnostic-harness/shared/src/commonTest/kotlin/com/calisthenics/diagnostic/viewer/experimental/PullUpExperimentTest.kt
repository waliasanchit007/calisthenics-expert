package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PullUpExperimentTest {
    @Test fun diagnosticsIdentifyTheActualRejectedInputWithoutChangingCounting() {
        val experiment = PullUpExperiment()
        experiment.startCalibration(ArmSide.LEFT,0)
        experiment.onSample(sample(5_000_000_000,leftAngle=160.0),5_000_000_000)
        assertNull(experiment.inputIssue)
        experiment.onSample(sample(5_100_000_000,leftAngle=160.0,leftWrist=-.1),5_100_000_000)
        assertEquals(ExperimentInputIssue.WRIST_NOT_ABOVE_SHOULDER,experiment.inputIssue)
        assertEquals(ExperimentStage.CALIBRATING,experiment.state.stage)
        assertEquals(CyclePhase.UNCERTAIN,experiment.state.phase)
        assertNull(experiment.state.failure)
        assertEquals(0,experiment.state.candidateCycles)
        experiment.reset()
        assertNull(experiment.inputIssue)
        experiment.startCalibration(ArmSide.LEFT,0)
        experiment.onSample(sample(5_000_000_000,leftAngle=null),5_000_000_000)
        assertEquals(ExperimentInputIssue.ELBOW_UNAVAILABLE,experiment.inputIssue)
        experiment.onSample(sample(5_100_000_000,leftAngle=160.0,leftConfidence=.2),5_100_000_000)
        assertEquals(ExperimentInputIssue.CONFIDENCE_UNAVAILABLE,experiment.inputIssue)
        experiment.onTrackingGap()
        assertEquals(ExperimentInputIssue.TRACKING_GAP,experiment.inputIssue)
    }
    private val nsPerMs = 1_000_000L
    private val countdownEnd = 5_000L * nsPerMs
    private val calibrationEnd = 15_000L * nsPerMs

    private fun sample(
        capturedNs: Long,
        leftAngle: Double? = null,
        rightAngle: Double? = null,
        leftWrist: Double? = 1.0,
        leftConfidence: Double? = .9,
        rightWrist: Double? = 1.0,
        rightConfidence: Double? = .9,
    ) = MovementMeasurements(
        capturedNs,
        ArmMeasurements(leftAngle, leftWrist, leftConfidence),
        ArmMeasurements(rightAngle, rightWrist, rightConfidence),
        availableArmJoints = 3,
    )

    private fun experimentWithCalibration(
        side: ArmSide = ArmSide.LEFT,
        angles: List<Double> = listOf(160.0, 160.0, 160.0, 100.0, 40.0, 40.0, 100.0, 160.0, 160.0, 160.0),
        finalAngle: Double? = 160.0,
        tailAngle: Double = 160.0,
    ): PullUpExperiment {
        val experiment = PullUpExperiment()
        experiment.startCalibration(side, 0)
        experiment.onSample(sample(4_000L * nsPerMs, leftAngle = 160.0, rightAngle = 160.0), 4_000L * nsPerMs)
        angles.forEachIndexed { index, angle ->
            val timestamp = countdownEnd + index * 200L * nsPerMs
            experiment.onSample(
                if (side == ArmSide.LEFT) sample(timestamp, leftAngle = angle, rightAngle = null)
                else sample(timestamp, leftAngle = null, rightAngle = angle),
                timestamp,
            )
        }
        var nextTimestamp = countdownEnd + angles.size * 200L * nsPerMs
        while (nextTimestamp < calibrationEnd) {
            experiment.onSample(
                if (side == ArmSide.LEFT) sample(nextTimestamp, leftAngle = tailAngle, rightAngle = null)
                else sample(nextTimestamp, leftAngle = null, rightAngle = tailAngle),
                nextTimestamp,
            )
            nextTimestamp += 200L * nsPerMs
        }
        experiment.onSample(
            if (side == ArmSide.LEFT) sample(calibrationEnd, leftAngle = finalAngle, rightAngle = null)
            else sample(calibrationEnd, leftAngle = null, rightAngle = finalAngle),
            calibrationEnd,
        )
        return experiment
    }

    private fun feedLeft(experiment: PullUpExperiment, timestampMs: Long, angle: Double) {
        val timestamp = timestampMs * nsPerMs
        experiment.onSample(sample(timestamp, leftAngle = angle, rightAngle = null), timestamp)
    }

    private fun countBriefTrajectory(angles: List<Double>, gapAt: Int? = null): Int {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)
        angles.forEachIndexed { index, angle ->
            if (index == gapAt) experiment.onTrackingGap()
            feedLeft(experiment, 16_300 + index * 100L, angle)
        }
        return experiment.state.candidateCycles
    }

    @Test
    fun briefTopWithSupportedApproachAndReversalCountsWithoutRequiringATopHold() {
        // Synthetic trajectory: two LOW samples span100ms, below the old120ms dwell.
        // Both sides have directional MIDDLE observations; count only after HIGH dwell.
        assertEquals(1, countBriefTrajectory(
            listOf(140.0, 110.0, 80.0, 40.0, 42.0, 80.0, 110.0, 140.0, 160.0, 160.0, 160.0),
        ))
    }

    @Test
    fun briefTopDoesNotCountBeforeReturningToExtendedDwell() {
        assertEquals(0, countBriefTrajectory(listOf(140.0, 110.0, 80.0, 40.0, 42.0, 80.0, 110.0)))
    }

    @Test
    fun completedAndRejectedCandidatesExposeAnOutcomeAndResetClearsIt() {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)
        listOf(140.0, 110.0, 80.0, 40.0, 42.0, 80.0, 110.0, 160.0, 160.0, 160.0)
            .forEachIndexed { i, a -> feedLeft(experiment, 16_300 + i * 100L, a) }
        assertEquals(CycleOutcome.COUNTED, experiment.state.lastCycleOutcome)
        listOf(140.0, 110.0, 80.0, 110.0, 160.0, 160.0, 160.0)
            .forEachIndexed { i, a -> feedLeft(experiment, 17_300 + i * 100L, a) }
        assertEquals(1, experiment.state.candidateCycles)
        assertEquals(CycleOutcome.TOP_NOT_CONFIRMED, experiment.state.lastCycleOutcome)
        feedLeft(experiment, 18_000, 110.0)
        experiment.onTrackingGap()
        assertEquals(CycleOutcome.TRACKING_INTERRUPTED, experiment.state.lastCycleOutcome)
        experiment.reset()
        assertNull(experiment.state.lastCycleOutcome)
    }

    @Test
    fun briefTopEvidenceRejectsSingleFrameSpikesPartialsAndUnsupportedReversals() {
        val trajectories = listOf(
            listOf(140.0, 110.0, 80.0, 40.0, 80.0, 110.0, 160.0, 160.0, 160.0), // single LOW
            listOf(140.0, 110.0, 80.0, 75.0, 80.0, 110.0, 160.0, 160.0, 160.0), // partial
            listOf(40.0, 42.0, 160.0, 160.0, 160.0), // two-frame jump, no approach/departure
            listOf(140.0, 40.0, 42.0, 80.0, 110.0, 160.0, 160.0, 160.0), // thin approach
            listOf(110.0, 140.0, 40.0, 42.0, 80.0, 110.0, 160.0, 160.0, 160.0), // wrong approach direction
            listOf(140.0, 110.0, 40.0, 42.0, 110.0, 80.0, 160.0, 160.0, 160.0), // wrong departure direction
        )
        trajectories.forEach { assertEquals(0, countBriefTrajectory(it), it.toString()) }
    }

    @Test
    fun aTrackingGapErasesBriefTopEvidenceAtEveryPartOfTheReversal() {
        val trajectory = listOf(140.0, 110.0, 80.0, 40.0, 42.0, 80.0, 110.0, 140.0, 160.0, 160.0, 160.0)
        for (index in 1..8) assertEquals(0, countBriefTrajectory(trajectory, gapAt = index), "gap at$index")
    }

    @Test
    fun duplicateLowSampleCannotCompleteBriefTopEvidence() {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)
        feedLeft(experiment, 16_300, 140.0)
        feedLeft(experiment, 16_400, 110.0)
        feedLeft(experiment, 16_500, 40.0)
        feedLeft(experiment, 16_500, 42.0)
        listOf(80.0, 110.0, 160.0, 160.0, 160.0).forEachIndexed { index, angle ->
            feedLeft(experiment, 16_600 + index * 100L, angle)
        }
        assertEquals(0, experiment.state.candidateCycles)
    }

    @Test
    fun unusableOrStaleTopSamplesCannotCompleteBriefTopEvidence() {
        for (invalid in listOf("confidence", "wrist", "missing", "stale", "backwards", "gap")) {
            val experiment = experimentWithCalibration()
            experiment.confirmCalibration()
            feedLeft(experiment, 16_000, 160.0)
            feedLeft(experiment, 16_200, 160.0)
            feedLeft(experiment, 16_300, 140.0)
            feedLeft(experiment, 16_400, 110.0)
            feedLeft(experiment, 16_500, 40.0)
            val timeMs = when (invalid) { "backwards" -> 16_450L; "gap" -> 16_800L; else -> 16_600L }
            val input = sample(timeMs * nsPerMs,
                leftAngle = if (invalid == "missing") null else 42.0,
                leftWrist = if (invalid == "wrist") -.1 else 1.0,
                leftConfidence = if (invalid == "confidence") .4 else .9)
            experiment.onSample(input, (timeMs + if (invalid == "stale") 300 else 0) * nsPerMs)
            listOf(80.0, 110.0, 160.0, 160.0, 160.0).forEachIndexed { index, angle ->
                feedLeft(experiment, maxOf(timeMs, 16_600L) + 100 + index * 100L, angle)
            }
            assertEquals(0, experiment.state.candidateCycles, invalid)
        }
    }

    @Test
    fun startsIdleWithTheDeclaredDefaults() {
        val experiment = PullUpExperiment()

        assertEquals(PullUpExperimentState(), experiment.state)
    }

    @Test
    fun calibrationRequiresAFullDwelledPracticeCycleAndFreezesItsRange() {
        val experiment = experimentWithCalibration()

        assertEquals(ExperimentStage.READY, experiment.state.stage, experiment.state.toString())
        assertEquals(0, experiment.state.candidateCycles)
        assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), experiment.state.range)
        assertEquals(CyclePhase.EXTENDED, experiment.state.phase)

        val frozen = experiment.state.range
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)
        assertEquals(ExperimentStage.COUNTING, experiment.state.stage)
        assertEquals(0, experiment.state.candidateCycles)
        assertEquals(frozen, experiment.state.range)
    }

    @Test
    fun recordedFullCyclesIncrementExactlyOnceAndPartialsDoNot() {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)

        feedLeft(experiment, 16_400, 100.0)
        feedLeft(experiment, 16_600, 40.0)
        feedLeft(experiment, 16_800, 40.0)
        feedLeft(experiment, 17_000, 100.0)
        feedLeft(experiment, 17_200, 160.0)
        feedLeft(experiment, 17_400, 160.0)
        assertEquals(1, experiment.state.candidateCycles)

        feedLeft(experiment, 17_600, 100.0)
        feedLeft(experiment, 17_800, 160.0)
        feedLeft(experiment, 18_000, 160.0)
        assertEquals(1, experiment.state.candidateCycles)

        feedLeft(experiment, 18_200, 100.0)
        feedLeft(experiment, 18_400, 40.0)
        feedLeft(experiment, 18_600, 40.0)
        feedLeft(experiment, 18_800, 100.0)
        feedLeft(experiment, 19_000, 160.0)
        feedLeft(experiment, 19_200, 160.0)
        assertEquals(2, experiment.state.candidateCycles)
    }

    @Test
    fun endpointJitterAndOneFrameCannotSatisfyDwell() {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        assertEquals(CyclePhase.UNCERTAIN, experiment.state.phase)
        feedLeft(experiment, 16_050, 159.0)
        feedLeft(experiment, 16_100, 160.0)
        assertEquals(CyclePhase.UNCERTAIN, experiment.state.phase)
        feedLeft(experiment, 16_200, 160.0)
        assertEquals(CyclePhase.EXTENDED, experiment.state.phase)

        listOf(159.0, 157.0, 160.0, 158.0).forEachIndexed { index, angle ->
            feedLeft(experiment, 16_400 + index * 100L, angle)
        }
        assertEquals(0, experiment.state.candidateCycles)
    }

    @Test
    fun isolatedCalibrationSpikeIsRemovedByMedianThree() {
        val experiment = experimentWithCalibration(
            angles = listOf(160.0, 160.0, 160.0, 100.0, 40.0, 0.0, 40.0, 100.0, 160.0, 160.0, 160.0),
        )

        assertEquals(ExperimentStage.READY, experiment.state.stage)
        assertEquals(0, experiment.state.candidateCycles)
        assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), experiment.state.range)
    }

    @Test
    fun stationaryCalibrationFailsUsabilityGuardWithNoRange() {
        val experiment = experimentWithCalibration(
            angles = listOf(120.0, 120.0, 120.0),
            finalAngle = 120.0,
            tailAngle = 120.0,
        )

        assertEquals(ExperimentStage.ERROR, experiment.state.stage)
        assertNull(experiment.state.range)
        assertTrue(experiment.state.message.contains("range"))
    }

    @Test
    fun narrowButOrderedFullExcursionFailsSpreadGuard() {
        val experiment = experimentWithCalibration(
            angles = listOf(110.0, 110.0, 110.0, 105.0, 100.0, 100.0, 100.0, 105.0, 110.0, 110.0, 110.0),
            finalAngle = 110.0,
            tailAngle = 110.0,
        )

        assertEquals(ExperimentStage.ERROR, experiment.state.stage)
        assertNull(experiment.state.range)
        assertTrue(experiment.state.message.contains("range"))
    }

    @Test
    fun firstFrameSpikeDoesNotInflateFrozenHighAfterMedianThreeWarmup() {
        val experiment = experimentWithCalibration(
            angles = listOf(180.0, 160.0, 160.0, 100.0, 40.0, 40.0, 100.0, 160.0, 160.0, 160.0),
        )

        assertEquals(ExperimentStage.READY, experiment.state.stage)
        assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), experiment.state.range)
    }

    @Test
    fun unusableSamplesDoNotAdvanceCalibrationOrCounting() {
        val experiment = PullUpExperiment()
        experiment.startCalibration(ArmSide.LEFT, 0)
        val invalid = listOf(
            sample(countdownEnd, leftAngle = null),
            sample(countdownEnd + 200 * nsPerMs, leftAngle = 120.0, leftWrist = 0.0),
            sample(countdownEnd + 400 * nsPerMs, leftAngle = 120.0, leftConfidence = .49),
        )
        invalid.forEach { experiment.onSample(it, it.capturedNs) }
        assertEquals(ExperimentStage.COUNTDOWN, experiment.state.stage)
        assertNull(experiment.state.range)

        val invalidDuringCalibration = PullUpExperiment()
        invalidDuringCalibration.startCalibration(ArmSide.LEFT, 0)
        invalidDuringCalibration.onSample(sample(countdownEnd, leftAngle = 160.0), countdownEnd)
        invalidDuringCalibration.onSample(sample(countdownEnd + 200 * nsPerMs, leftAngle = null), countdownEnd + 200 * nsPerMs)
        assertEquals(ExperimentStage.CALIBRATING, invalidDuringCalibration.state.stage)
        assertEquals(CyclePhase.UNCERTAIN, invalidDuringCalibration.state.phase)
        assertNull(invalidDuringCalibration.state.range)

        val negativeDuringCalibration = PullUpExperiment()
        negativeDuringCalibration.startCalibration(ArmSide.LEFT, 0)
        negativeDuringCalibration.onSample(sample(countdownEnd, leftAngle = 160.0), countdownEnd)
        negativeDuringCalibration.onSample(sample(-1, leftAngle = 160.0), 0)
        assertEquals(ExperimentStage.ERROR, negativeDuringCalibration.state.stage)

        val calibrated = experimentWithCalibration()
        calibrated.confirmCalibration()
        feedLeft(calibrated, 16_000, 160.0)
        feedLeft(calibrated, 16_200, 160.0)
        calibrated.onSample(sample(17_000 * nsPerMs, leftAngle = null), 17_000 * nsPerMs)
        assertEquals(0, calibrated.state.candidateCycles)
    }

    @Test
    fun staleSamplesAndLongGapsBreakPendingCycleButFreshExtendedSamplesRearm() {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)
        feedLeft(experiment, 16_400, 100.0)
        experiment.onSample(sample(16_500 * nsPerMs, leftAngle = 40.0), 16_800 * nsPerMs)
        feedLeft(experiment, 16_900, 40.0)
        assertEquals(0, experiment.state.candidateCycles)

        feedLeft(experiment, 17_400, 160.0)
        feedLeft(experiment, 17_600, 160.0)
        feedLeft(experiment, 17_800, 100.0)
        feedLeft(experiment, 18_000, 40.0)
        feedLeft(experiment, 18_200, 40.0)
        feedLeft(experiment, 18_400, 100.0)
        feedLeft(experiment, 18_600, 160.0)
        feedLeft(experiment, 18_800, 160.0)
        assertEquals(1, experiment.state.candidateCycles)
    }

    @Test
    fun trackingLossPreservesCountAndNewCalibrationResetsCountAndRangeAtomically() {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)
        feedLeft(experiment, 16_400, 100.0)
        feedLeft(experiment, 16_600, 40.0)
        feedLeft(experiment, 16_800, 40.0)
        feedLeft(experiment, 17_000, 100.0)
        feedLeft(experiment, 17_200, 160.0)
        feedLeft(experiment, 17_400, 160.0)
        assertEquals(1, experiment.state.candidateCycles)

        feedLeft(experiment, 17_600, 100.0)
        experiment.onTrackingGap()
        assertEquals(1, experiment.state.candidateCycles)
        assertEquals(CyclePhase.UNCERTAIN, experiment.state.phase)

        experiment.startCalibration(ArmSide.RIGHT, 30_000 * nsPerMs)
        assertEquals(ExperimentStage.COUNTDOWN, experiment.state.stage)
        assertEquals(ArmSide.RIGHT, experiment.state.side)
        assertEquals(0, experiment.state.candidateCycles)
        assertNull(experiment.state.range)
    }

    @Test
    fun duplicateAndBackwardsTimestampsNeverLowerWatermarkOrCount() {
        val experiment = experimentWithCalibration()
        experiment.confirmCalibration()
        feedLeft(experiment, 16_000, 160.0)
        feedLeft(experiment, 16_200, 160.0)
        experiment.onSample(sample(16_200 * nsPerMs, leftAngle = 40.0), 16_200 * nsPerMs)
        experiment.onSample(sample(16_100 * nsPerMs, leftAngle = 40.0), 16_200 * nsPerMs)
        assertEquals(CyclePhase.UNCERTAIN, experiment.state.phase)
        feedLeft(experiment, 16_400, 160.0)
        feedLeft(experiment, 16_600, 160.0)
        assertEquals(CyclePhase.EXTENDED, experiment.state.phase)
    }

    @Test
    fun calibrationFinalizesAccumulatedSamplesBeforeBeyondWindowAngleValidation() {
        val experiment = experimentWithCalibration(finalAngle = null)

        assertEquals(ExperimentStage.READY, experiment.state.stage)
        assertEquals(ObservedRange(ArmSide.LEFT, 40.0, 160.0), experiment.state.range)
    }

    @Test
    fun calibrationInterSampleGapFailsBeforeWindowFinalization() {
        val experiment = PullUpExperiment()
        experiment.startCalibration(ArmSide.LEFT, 0)
        experiment.onSample(sample(countdownEnd, leftAngle = 160.0), countdownEnd)
        experiment.onSample(sample(countdownEnd + 200 * nsPerMs, leftAngle = 160.0), countdownEnd + 200 * nsPerMs)
        experiment.onSample(sample(calibrationEnd, leftAngle = 160.0), calibrationEnd)

        assertEquals(ExperimentStage.ERROR, experiment.state.stage)
        assertNull(experiment.state.range)
    }

    @Test
    fun onlyTheChosenSideIsRequiredAndReadyDoesNotCount() {
        val experiment = experimentWithCalibration(
            side = ArmSide.RIGHT,
            angles = listOf(160.0, 160.0, 160.0, 100.0, 40.0, 40.0, 100.0, 160.0, 160.0, 160.0),
        )
        assertEquals(ExperimentStage.READY, experiment.state.stage)
        experiment.onSample(sample(calibrationEnd + 1, leftAngle = null, rightAngle = 40.0), calibrationEnd + 1)
        assertEquals(ExperimentStage.READY, experiment.state.stage)
        assertTrue(experiment.state.range?.side == ArmSide.RIGHT)
    }
}
