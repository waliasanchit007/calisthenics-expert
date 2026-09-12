package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral tests for the stationarity half of an observable static hold.
 *
 * `PlankHoldExperiment` already measures whether a shoulder-hip-ankle body line is straight and
 * near-horizontal, and already rules out upright standing. What it does not measure is whether the
 * body is actually *still*, because a floor plank is stationary by construction. A suspended hold
 * loses that assumption: a straight body swinging, being carried, or rotating through horizontal
 * would satisfy every existing gate. These tests pin the missing measurement only, and deliberately
 * pin the refusals too — stationarity alone must never assert a hold, and no skill identity,
 * grip, contact, support force, elevation or form is ever inferred from skeletal geometry.
 */
class StaticSkillObservationTest {
    private val config = StaticSkillConfig(
        minimumConfidence = .5,
        maximumTorsoPointSpeedTorsoLengthsPerSecond = .30,
        maximumGapNs = 100_000_000L,
        minimumStillNs = 300_000_000L,
        mode = StaticSkillMode.TORSO_STILL,
        minimumMovingSamplesToClose = 1,
        motionBaselineNs = 0L,
    )

    @Test
    fun measuresCentroidSpeedInTorsoLengthsPerSecondThroughSourcePixels() {
        // 0.5px of travel on a 100px torso over one 30fps frame: 0.005 torso-lengths / (1/30)s.
        val measurement = measureTorsoCentroidSpeed(
            previous = frame(0L, centroidXPx = 350.0),
            current = frame(FRAME_NS, centroidXPx = 350.5),
            config = config,
        )

        assertEquals(.15, assertNotNull(measurement.centroidSpeedTorsoLengthsPerSecond), 1e-6)
        assertEquals(StaticSkillReason.NONE, measurement.reason)
    }

    @Test
    fun centroidSpeedIsInvariantToSubjectScale() {
        // Same motion expressed as a fraction of body size must read identically whether the
        // subject fills the frame or is far away, otherwise a threshold means nothing across clips.
        val near = measureTorsoCentroidSpeed(
            frame(0L, centroidXPx = 350.0, torsoLengthPx = 100.0),
            frame(FRAME_NS, centroidXPx = 350.5, torsoLengthPx = 100.0),
            config,
        )
        val far = measureTorsoCentroidSpeed(
            frame(0L, centroidXPx = 350.0, torsoLengthPx = 200.0),
            frame(FRAME_NS, centroidXPx = 351.0, torsoLengthPx = 200.0),
            config,
        )

        assertEquals(
            assertNotNull(near.centroidSpeedTorsoLengthsPerSecond),
            assertNotNull(far.centroidSpeedTorsoLengthsPerSecond),
            1e-9,
        )
    }

    @Test
    fun measurementEncodesOnlyMotionMagnitudeNotGravityOrSupport() {
        // Identical displacement upward, downward and sideways must produce identical speed. The
        // measurement carries no gravity, support, contact or "which way is up" model, so it can
        // never be read as evidence that a body is held against gravity rather than resting.
        val right = measureTorsoCentroidSpeed(
            frame(0L, centroidXPx = 350.0, centroidYPx = 180.0),
            frame(FRAME_NS, centroidXPx = 351.0, centroidYPx = 180.0),
            config,
        )
        val up = measureTorsoCentroidSpeed(
            frame(0L, centroidXPx = 350.0, centroidYPx = 180.0),
            frame(FRAME_NS, centroidXPx = 350.0, centroidYPx = 179.0),
            config,
        )
        val down = measureTorsoCentroidSpeed(
            frame(0L, centroidXPx = 350.0, centroidYPx = 180.0),
            frame(FRAME_NS, centroidXPx = 350.0, centroidYPx = 181.0),
            config,
        )

        val speed = assertNotNull(right.centroidSpeedTorsoLengthsPerSecond)
        assertEquals(speed, assertNotNull(up.centroidSpeedTorsoLengthsPerSecond), 1e-9)
        assertEquals(speed, assertNotNull(down.centroidSpeedTorsoLengthsPerSecond), 1e-9)
    }

    @Test
    fun aZeroLengthTorsoHasNoUsableScaleAndYieldsNoSpeed() {
        val degenerate = frame(FRAME_NS, centroidXPx = 350.0, torsoLengthPx = 0.0)
        val measurement = measureTorsoCentroidSpeed(
            frame(0L, centroidXPx = 350.0, torsoLengthPx = 0.0),
            degenerate,
            config,
        )

        assertNull(measurement.centroidSpeedTorsoLengthsPerSecond)
        assertEquals(StaticSkillReason.ZERO_LENGTH_TORSO, measurement.reason)
    }

    @Test
    fun theFirstFrameHasNoSpeedAndCanNeverCountAsStill() {
        // A single frame contains no motion evidence at all. Reporting 0.0 would let one frame
        // read as perfectly still, which is the easiest possible false positive for a hold.
        val observation = StaticSkillObservation(config)

        val result = observation.onFrame(frame(0L, centroidXPx = 350.0), bodyLineAdmitted = true)

        assertNull(result.centroidSpeedTorsoLengthsPerSecond)
        assertEquals(StaticSkillReason.NO_PREVIOUS_FRAME, result.reason)
        assertEquals(StaticSkillState.WAITING, result.state)
        assertNull(result.closedInterval)
        assertNull(observation.finish())
    }

    @Test
    fun stillnessIsConfirmedByElapsedDurationNotFrameCount() {
        // 30fps: the still span first reaches 300ms at frame 10 (333_333_330ns from frame 0).
        val fast = StaticSkillObservation(config)
        val fastStates = (0..10).map { index ->
            fast.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true).state
        }
        assertEquals(StaticSkillState.ENTERING, fastStates[9])
        assertEquals(StaticSkillState.STILL, fastStates[10])

        // 15fps: the same 300ms is reached at frame 5 (333_333_330ns). A frame-count rule would
        // need twice as many frames here and would silently mean a different real duration.
        val slow = StaticSkillObservation(config)
        val slowStates = (0..5).map { index ->
            slow.onFrame(stillFrame(index, HALF_RATE_FRAME_NS), bodyLineAdmitted = true).state
        }
        assertEquals(StaticSkillState.ENTERING, slowStates[4])
        assertEquals(StaticSkillState.STILL, slowStates[5])
    }

    @Test
    fun aMovingBodyNeverEntersStill() {
        val observation = StaticSkillObservation(config)

        repeat(40) { index ->
            // 5px per 30fps frame on a 100px torso: 1.5 torso-lengths/s, five times the gate.
            val result = observation.onFrame(
                frame(index * FRAME_NS, centroidXPx = 350.0 + 5.0 * index),
                bodyLineAdmitted = true,
            )
            assertTrue(result.state != StaticSkillState.STILL)
            assertNull(result.closedInterval)
            if (index > 0) assertEquals(StaticSkillReason.MOVING, result.reason)
        }
        assertNull(observation.finish())
    }

    @Test
    fun rotatingTorsoWithFixedCentroidNeverEntersTorsoStill() {
        // Equal-and-opposite shoulder/hip motion leaves the centroid fixed. A centroid-only gate
        // would falsely accept this run; every torso point must be included in the motion gate.
        val observation = StaticSkillObservation(config)
        observation.onFrame(rotatingTorsoFrame(0L, angleDegrees = 0.0), bodyLineAdmitted = true)

        repeat(39) { offset ->
            val index = offset + 1
            val result = observation.onFrame(
                rotatingTorsoFrame(index * FRAME_NS, angleDegrees = 3.0 * index),
                bodyLineAdmitted = true,
            )
            assertEquals(0.0, assertNotNull(result.centroidSpeedTorsoLengthsPerSecond), 1e-9)
            assertTrue(result.maxTorsoPointSpeedTorsoLengthsPerSecond > .30)
            assertTrue(result.state != StaticSkillState.STILL)
            assertEquals(StaticSkillReason.MOVING, result.reason)
        }
        assertNull(observation.finish())
    }

    @Test
    fun aStillSpanShorterThanTheMinimumProducesNoClosedInterval() {
        // Mirrors the real footage: the dismount contains short stationary-looking fragments
        // (longest 0.28s). Without a minimum duration those fragments would each report a hold.
        val observation = StaticSkillObservation(config)

        repeat(9) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }
        val breaking = observation.onFrame(
            frame(9 * FRAME_NS, centroidXPx = 400.0),
            bodyLineAdmitted = true,
        )

        assertEquals(StaticSkillReason.MOVING, breaking.reason)
        assertNull(breaking.closedInterval)
        assertNull(observation.finish())
    }

    @Test
    fun stationarityAloneNeverAssertsAHoldWithoutBodyLineAdmission() {
        // A person standing perfectly still is perfectly stationary. Stillness is necessary for a
        // hold and nowhere near sufficient, so an unadmitted body line must block entry outright.
        val observation = StaticSkillObservation(config)

        repeat(40) { index ->
            val result = observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = false)
            assertTrue(result.state != StaticSkillState.STILL)
            assertEquals(StaticSkillReason.BODY_LINE_NOT_ADMITTED, result.reason)
            assertNull(result.closedInterval)
        }
        assertNull(observation.finish())
    }

    @Test
    fun losingBodyLineAdmissionClosesTheIntervalAtTheLastConfirmedSample() {
        val observation = StaticSkillObservation(config)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        val released = observation.onFrame(stillFrame(20, FRAME_NS), bodyLineAdmitted = false)

        val closed = assertNotNull(released.closedInterval)
        assertEquals(0L, closed.entryTimestampNs)
        assertEquals(19 * FRAME_NS, closed.exitTimestampNs)
        assertEquals(StaticSkillReason.BODY_LINE_NOT_ADMITTED, closed.endReason)
        assertNull(observation.finish())
    }

    @Test
    fun anIntervalOpenAtTheFirstObservedFrameIsReportedLeftCensored() {
        // The admitted clip is already mid-hold at frame 0, so its onset is unknown and its
        // duration is a lower bound. That must be visible on the event, not silently lost.
        val observation = StaticSkillObservation(config)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        val closed = assertNotNull(
            observation.onFrame(frame(20 * FRAME_NS, centroidXPx = 500.0), bodyLineAdmitted = true)
                .closedInterval,
        )

        assertTrue(closed.leftCensored)
    }

    @Test
    fun anIntervalThatBeginsAfterObservedMotionIsNotLeftCensored() {
        val observation = StaticSkillObservation(config)
        // Five frames of real motion first, so the onset of stillness is actually witnessed.
        repeat(5) { index ->
            observation.onFrame(
                frame(index * FRAME_NS, centroidXPx = 350.0 + 5.0 * index),
                bodyLineAdmitted = true,
            )
        }
        repeat(20) { index ->
            observation.onFrame(
                frame((5 + index) * FRAME_NS, centroidXPx = 500.0),
                bodyLineAdmitted = true,
            )
        }

        val closed = assertNotNull(observation.finish())
        assertTrue(!closed.leftCensored)
        assertEquals(StaticSkillReason.CENSORED_AT_END_OF_INPUT, closed.endReason)
    }

    @Test
    fun unselectedModeNeverLabelsAnIntervalWithASkillIdentity() {
        // Skill identity is operator-declared, never inferred from geometry.
        val observation = StaticSkillObservation(config.copy(mode = StaticSkillMode.UNSELECTED))
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        assertEquals(StaticSkillLabel.UNCERTAIN, assertNotNull(observation.finish()).label)
    }

    @Test
    fun selectedModeLabelsTheIntervalOnlyAsHumanFlagCompatible() {
        val observation = StaticSkillObservation(config)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        assertEquals(
            StaticSkillLabel.TORSO_STILL,
            assertNotNull(observation.finish()).label,
        )
    }

    @Test
    fun lowConfidenceWithinToleranceKeepsTheIntervalAliveButUnobserved() {
        val observation = StaticSkillObservation(config)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        val blind = observation.onFrame(
            frame(20 * FRAME_NS, centroidXPx = 350.0, confidence = .1),
            bodyLineAdmitted = true,
        )
        assertEquals(StaticSkillReason.LOW_CONFIDENCE, blind.reason)
        assertEquals(StaticSkillState.STILL, blind.state)
        assertNull(blind.closedInterval)

        observation.onFrame(stillFrame(21, FRAME_NS), bodyLineAdmitted = true)
        val closed = assertNotNull(observation.finish())

        // The unobserved span must not be counted as held wall-clock time.
        assertTrue(closed.observedNs < closed.durationNs)
    }

    @Test
    fun aGapBeyondToleranceClosesTheIntervalAsCaptureGap() {
        val observation = StaticSkillObservation(config)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        // 200ms later, still and still admitted: two good samples astride an unobserved gap must
        // never stitch into one confirmed hold.
        val afterGap = observation.onFrame(
            frame(19 * FRAME_NS + 200_000_000L, centroidXPx = 350.0),
            bodyLineAdmitted = true,
        )

        val closed = assertNotNull(afterGap.closedInterval)
        assertEquals(StaticSkillReason.CAPTURE_GAP, closed.endReason)
        assertEquals(19 * FRAME_NS, closed.exitTimestampNs)
    }

    @Test
    fun anIntervalStillOpenAtEndOfInputIsCensoredNotDropped() {
        val observation = StaticSkillObservation(config)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        val censored = assertNotNull(observation.finish())

        assertEquals(StaticSkillReason.CENSORED_AT_END_OF_INPUT, censored.endReason)
        assertEquals(19 * FRAME_NS, censored.exitTimestampNs)
        assertNull(observation.finish())
    }

    @Test
    fun backwardsAndDuplicateTimestampsInvalidateRatherThanMeasure() {
        val backwards = StaticSkillObservation(config)
        backwards.onFrame(stillFrame(5, FRAME_NS), bodyLineAdmitted = true)
        val earlier = backwards.onFrame(stillFrame(4, FRAME_NS), bodyLineAdmitted = true)
        assertEquals(StaticSkillReason.BACKWARDS_TIMESTAMP, earlier.reason)
        assertEquals(StaticSkillState.UNCERTAIN, earlier.state)

        val duplicate = StaticSkillObservation(config)
        duplicate.onFrame(stillFrame(5, FRAME_NS), bodyLineAdmitted = true)
        val repeated = duplicate.onFrame(stillFrame(5, FRAME_NS), bodyLineAdmitted = true)
        assertEquals(StaticSkillReason.DUPLICATE_TIMESTAMP, repeated.reason)

        val negative = StaticSkillObservation(config)
        val invalid = negative.onFrame(frame(-1L, centroidXPx = 350.0), bodyLineAdmitted = true)
        assertEquals(StaticSkillReason.INVALID_TIMESTAMP, invalid.reason)
    }

    @Test
    fun missingTorsoLandmarksAreReportedRatherThanApproximated() {
        val observation = StaticSkillObservation(config)
        observation.onFrame(stillFrame(0, FRAME_NS), bodyLineAdmitted = true)

        val partial = observation.onFrame(
            StaticSkillPoseFrame(
                timestampNs = FRAME_NS,
                width = WIDTH,
                height = HEIGHT,
                landmarks = torsoLandmarks(350.0, 180.0, 100.0, .9).filter { it.index != 24 },
            ),
            bodyLineAdmitted = true,
        )

        assertNull(partial.centroidSpeedTorsoLengthsPerSecond)
        assertEquals(StaticSkillReason.MISSING_LANDMARK, partial.reason)
    }

    @Test
    fun nonFiniteConfidenceIsReportedRatherThanAccepted() {
        for (confidence in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val measurement = measureTorsoCentroidSpeed(
                frame(0L, centroidXPx = 350.0),
                frame(FRAME_NS, centroidXPx = 350.0, confidence = confidence),
                config,
            )
            assertEquals(StaticSkillReason.LOW_CONFIDENCE, measurement.reason)
            assertNull(measurement.centroidSpeedTorsoLengthsPerSecond)
        }
    }

    @Test
    fun emptyLandmarksAndInvalidDimensionsAreDistinctReasons() {
        val observation = StaticSkillObservation(config)

        val noPose = observation.onFrame(
            StaticSkillPoseFrame(0L, WIDTH, HEIGHT, emptyList()),
            bodyLineAdmitted = true,
        )
        assertEquals(StaticSkillReason.NO_POSE, noPose.reason)

        val badDimensions = StaticSkillObservation(config).onFrame(
            StaticSkillPoseFrame(0L, 0, HEIGHT, torsoLandmarks(350.0, 180.0, 100.0, .9)),
            bodyLineAdmitted = true,
        )
        assertEquals(StaticSkillReason.INVALID_DIMENSIONS, badDimensions.reason)
    }

    @Test
    fun duplicateLandmarkIndicesAreRejected() {
        val landmarks = torsoLandmarks(350.0, 180.0, 100.0, .9)
        val result = StaticSkillObservation(config).onFrame(
            StaticSkillPoseFrame(0L, WIDTH, HEIGHT, landmarks + landmarks.first()),
            bodyLineAdmitted = true,
        )

        assertEquals(StaticSkillReason.DUPLICATE_LANDMARK_INDEX, result.reason)
    }

    @Test
    fun configRejectsNonFiniteAndOutOfRangeParameters() {
        assertFailsWith<IllegalArgumentException> { config.copy(minimumConfidence = 1.5) }
        assertFailsWith<IllegalArgumentException> {
            config.copy(maximumTorsoPointSpeedTorsoLengthsPerSecond = -1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(maximumTorsoPointSpeedTorsoLengthsPerSecond = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> { config.copy(maximumGapNs = 0L) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumStillNs = 0L) }
        assertFailsWith<IllegalArgumentException> { config.copy(minimumMovingSamplesToClose = 0) }
    }

    /**
     * Persistence: `minimumMovingSamplesToClose` frames over the speed gate are required before a
     * hold is closed. Measured on a real wall-sit recording, 6 of 540 frames exceeded the gate as
     * isolated single-frame spikes and split one steady hold into three fragments. These tests pin
     * the new behaviour; every other test in this class runs with the value 1, which is the
     * original behaviour, so none of them changes.
     */
    private val persistent = config.copy(minimumMovingSamplesToClose = 3)

    @Test
    fun aSingleOverThresholdFrameNoLongerClosesAHold() {
        val observation = StaticSkillObservation(persistent)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        // One frame of real travel, then stillness resumes from the new position, so exactly one
        // frame is over the gate. 40.5px on a 100px torso at 30fps is 12 torso-lengths/s.
        val spike = observation.onFrame(frame(20 * FRAME_NS, centroidXPx = 400.0), bodyLineAdmitted = true)
        assertEquals(StaticSkillReason.MOVING, spike.reason)
        assertNull(spike.closedInterval)
        assertEquals(StaticSkillState.STILL, spike.state)

        for (index in 21 until 40) {
            observation.onFrame(
                frame(index * FRAME_NS, centroidXPx = 400.0 + .5 * (index - 20)),
                bodyLineAdmitted = true,
            )
        }

        val closed = assertNotNull(observation.finish())
        assertEquals(0L, closed.entryTimestampNs)
        assertEquals(39 * FRAME_NS, closed.exitTimestampNs)
        assertEquals(StaticSkillReason.CENSORED_AT_END_OF_INPUT, closed.endReason)
        // The spike is survived, not absorbed: the two frame intervals it touches are excluded
        // from observed coverage, so the interval still reports only genuinely still time.
        assertEquals(closed.durationNs - 2 * FRAME_NS, closed.observedNs)
    }

    @Test
    fun consecutiveOverThresholdFramesStillCloseTheHoldAtTheLastStillFrame() {
        val observation = StaticSkillObservation(persistent)
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        assertNull(observation.onFrame(frame(20 * FRAME_NS, centroidXPx = 400.0), bodyLineAdmitted = true).closedInterval)
        assertNull(observation.onFrame(frame(21 * FRAME_NS, centroidXPx = 450.0), bodyLineAdmitted = true).closedInterval)
        val third = observation.onFrame(frame(22 * FRAME_NS, centroidXPx = 500.0), bodyLineAdmitted = true)

        val closed = assertNotNull(third.closedInterval)
        assertEquals(StaticSkillReason.MOVING, closed.endReason)
        // The exit is the last still frame, not the frame that finally proved the motion.
        assertEquals(19 * FRAME_NS, closed.exitTimestampNs)
        assertEquals(StaticSkillState.WAITING, third.state)
        assertNull(observation.finish())
    }

    @Test
    fun separatedSpikesDoNotAccumulateTowardsClosing() {
        // Three isolated spikes, each followed by still frames: this is the wall-sit failure. The
        // counter must reset on each still frame, or three scattered spikes would close the hold.
        val observation = StaticSkillObservation(persistent)
        val spikeIndices = setOf(10, 20, 30)
        var centroid = 350.0
        var closedCount = 0
        for (index in 0 until 40) {
            centroid += if (index in spikeIndices) 40.0 else .5
            val result = observation.onFrame(
                frame(index * FRAME_NS, centroidXPx = centroid),
                bodyLineAdmitted = true,
            )
            if (result.closedInterval != null) closedCount++
        }

        assertEquals(0, closedCount)
        val closed = assertNotNull(observation.finish())
        assertEquals(39 * FRAME_NS - closed.entryTimestampNs, closed.durationNs)
        assertEquals(StaticSkillReason.CENSORED_AT_END_OF_INPUT, closed.endReason)
    }

    @Test
    fun anAbsorbedTransientDoesNotCountAsALapseInCapture() {
        // Regression. The gap check used to key off the last STILL frame, which the debouncer
        // deliberately holds back. On the real 25-30fps clips, maximumGapNs of 100ms is under
        // three frame intervals, so absorbing two transients made the next frame look like a
        // dropout: merged holds closed as CAPTURE_GAP, and one clip gained fragments instead of
        // losing them. Capture continuity is about frames arriving, not about stillness.
        val observation = StaticSkillObservation(persistent.copy(maximumGapNs = 2 * FRAME_NS))
        repeat(20) { index ->
            observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true)
        }

        // Two consecutive over-threshold frames: absorbed, so the last still frame is now three
        // frame intervals behind by the time frame 22 arrives.
        assertNull(observation.onFrame(frame(20 * FRAME_NS, centroidXPx = 400.0), bodyLineAdmitted = true).closedInterval)
        assertNull(observation.onFrame(frame(21 * FRAME_NS, centroidXPx = 400.5), bodyLineAdmitted = true).closedInterval)
        val resumed = observation.onFrame(frame(22 * FRAME_NS, centroidXPx = 401.0), bodyLineAdmitted = true)

        assertNull(resumed.closedInterval)
        assertEquals(StaticSkillReason.NONE, resumed.reason)
        assertEquals(StaticSkillState.STILL, resumed.state)

        // A genuine dropout is still a gap: no frame at all for four intervals.
        val afterDropout = observation.onFrame(frame(26 * FRAME_NS, centroidXPx = 401.5), bodyLineAdmitted = true)
        val closed = assertNotNull(afterDropout.closedInterval)
        assertEquals(StaticSkillReason.CAPTURE_GAP, closed.endReason)
        assertEquals(22 * FRAME_NS, closed.exitTimestampNs)
    }

    // ---- motionBaselineNs: what the gate measures, and over what interval ----------------------
    //
    // The one-frame gate is displacement times frame rate, so a FIXED spatial landmark jitter scores
    // proportionally to the capture rate. Measured on the six frozen hold clips, the gate's median
    // tracked jitter amplitude rather than athlete motion: over a 4.67s back lever the torso
    // centroid moved 0.105 torso-lengths NET while the gate reported a median of 0.209/s and put
    // 29 of 110 frames over a 0.30 gate. These tests pin the measurement interval itself.

    @Test
    fun aZeroBaselineMeasuresAgainstTheImmediatelyPreviousFrame() {
        // The compatibility guarantee, pinned rather than assumed: 3px on a 100px torso in one
        // 30fps frame is 0.03 * 30 = 0.9 torso-lengths/s, the one-frame answer exactly.
        val observation = StaticSkillObservation(config)
        observation.onFrame(frame(0L, centroidXPx = 350.0), bodyLineAdmitted = true)
        val second = observation.onFrame(frame(FRAME_NS, centroidXPx = 353.0), bodyLineAdmitted = true)

        assertEquals(.9, assertNotNull(second.maxTorsoPointSpeedTorsoLengthsPerSecond), 1e-7)
    }

    @Test
    fun aFixedBaselineMeasuresAcrossTheBaselineNotAcrossOneFrame() {
        // Jitter with no net drift: 350, 356, 350, 356, 350. A one-frame measure sees 6px every
        // frame and reports 1.8 torso-lengths/s; a four-frame baseline compares 350 against 350 and
        // correctly reports that the body went nowhere. Uniform motion CANNOT tell these two apart
        // - both give the same answer - so the discriminating case is the jittering one, which is
        // also the case the real clips exhibit.
        val observation = StaticSkillObservation(config.copy(motionBaselineNs = 4 * FRAME_NS))
        var last: StaticSkillFrameResult? = null
        for (index in 0..4) {
            val centroid = if (index % 2 == 0) 350.0 else 356.0
            last = observation.onFrame(frame(index * FRAME_NS, centroidXPx = centroid), bodyLineAdmitted = true)
        }

        assertEquals(.0, assertNotNull(assertNotNull(last).maxTorsoPointSpeedTorsoLengthsPerSecond), 1e-7)
    }

    @Test
    fun aFixedBaselineGivesTheSameAnswerAtTwoDifferentFrameRates() {
        // Frame-rate invariance, the defect this parameter exists to remove. One physical signal -
        // a 3px/s drift carrying a fixed +-6px jitter - sampled at 30fps and at 15fps. The
        // one-frame measure sees the jitter and so scales with the capture rate: 3.6 against 1.8.
        // Over a baseline of 0.1333s (an even number of frames at both rates, so the jitter
        // cancels) both rates must report the drift alone, 0.03 torso-lengths/s.
        val baselineNs = 4 * FRAME_NS
        val driftPxPerSecond = 3.0
        val jitterPx = 6.0
        fun measure(stepNs: Long, frames: Int, baseline: Long): Double {
            val observation = StaticSkillObservation(config.copy(motionBaselineNs = baseline))
            var last: StaticSkillFrameResult? = null
            for (index in 0..frames) {
                val timestampNs = index * stepNs
                val drift = driftPxPerSecond * timestampNs / 1_000_000_000.0
                val jitter = if (index % 2 == 0) .0 else jitterPx
                last = observation.onFrame(
                    frame(timestampNs, centroidXPx = 350.0 + drift + jitter),
                    bodyLineAdmitted = true,
                )
            }
            return assertNotNull(assertNotNull(last).maxTorsoPointSpeedTorsoLengthsPerSecond)
        }

        val oneFrameAt30 = measure(FRAME_NS, frames = 8, baseline = 0L)
        val oneFrameAt15 = measure(2 * FRAME_NS, frames = 4, baseline = 0L)
        // Not exactly 2: the drift adds to the jitter on one side and subtracts on the other, and
        // it does so by a different amount at each rate. The claim under test is that the one-frame
        // measure scales with the capture rate at all, which a 2.03 ratio establishes.
        assertEquals(2.0, oneFrameAt30 / oneFrameAt15, .05)

        val baselineAt30 = measure(FRAME_NS, frames = 8, baseline = baselineNs)
        val baselineAt15 = measure(2 * FRAME_NS, frames = 4, baseline = baselineNs)
        assertEquals(baselineAt30, baselineAt15, 1e-7)
        assertEquals(driftPxPerSecond / 100.0, baselineAt30, 1e-3)
    }

    @Test
    fun stillnessIsNotAssertedBeforeTheBaselineIsAvailable() {
        // Refuse rather than infer. Falling back to a one-frame measurement here would reintroduce
        // exactly the quantity the baseline exists to avoid, so the first frames report no
        // measurement at all and no candidate hold is opened.
        val observation = StaticSkillObservation(config.copy(motionBaselineNs = 5 * FRAME_NS))
        val first = observation.onFrame(frame(0L, centroidXPx = 350.0), bodyLineAdmitted = true)
        val second = observation.onFrame(frame(FRAME_NS, centroidXPx = 350.5), bodyLineAdmitted = true)

        assertEquals(StaticSkillReason.NO_PREVIOUS_FRAME, first.reason)
        assertEquals(StaticSkillReason.NO_PREVIOUS_FRAME, second.reason)
        assertNull(second.maxTorsoPointSpeedTorsoLengthsPerSecond.takeIf { it != 0.0 })
        assertEquals(StaticSkillState.WAITING, second.state)
    }

    @Test
    fun aGenuineSustainedMotionStillClosesTheHoldUnderAFixedBaseline() {
        // The cost side, pinned so it cannot be quietly lost: a longer baseline must not make a
        // real exit undetectable. 20px per frame is far outside any gate at any baseline.
        val baseline = 3 * FRAME_NS
        val observation = StaticSkillObservation(config.copy(motionBaselineNs = baseline))
        repeat(30) { index -> observation.onFrame(stillFrame(index, FRAME_NS), bodyLineAdmitted = true) }

        var closed: StaticSkillInterval? = null
        var index = 30
        var centroid = 350.0 + .5 * 30
        while (closed == null && index < 60) {
            centroid += 20.0
            closed = observation.onFrame(frame(index * FRAME_NS, centroidXPx = centroid), bodyLineAdmitted = true)
                .closedInterval
            index++
        }

        assertNotNull(closed)
        assertEquals(StaticSkillReason.MOVING, closed.endReason)
        // Detected within the baseline, not merely eventually.
        assertTrue((index - 30) * FRAME_NS <= baseline + FRAME_NS, "closed after ${index - 30} frames")
    }

    @Test
    fun theBaselineSpansOnlyFramesThatWereActuallyDelivered() {
        // Never bridge a missing observation. After a dropout the measurement is taken between two
        // real frames and divided by the real elapsed time between them, so a body that moved
        // during the dropout is not credited with having been still through it.
        val observation = StaticSkillObservation(
            config.copy(motionBaselineNs = 2 * FRAME_NS, maximumGapNs = 10 * FRAME_NS),
        )
        observation.onFrame(frame(0L, centroidXPx = 350.0), bodyLineAdmitted = true)
        observation.onFrame(frame(FRAME_NS, centroidXPx = 350.0), bodyLineAdmitted = true)
        // Nothing for six intervals, then a frame 6px further on. Against the frame at 1*FRAME_NS
        // that is 0.06 torso-lengths over six intervals: 0.06 / 0.2s = 0.3, not 0.06 * 30 = 1.8.
        val afterDropout = observation.onFrame(frame(7 * FRAME_NS, centroidXPx = 356.0), bodyLineAdmitted = true)

        assertEquals(.3, assertNotNull(afterDropout.maxTorsoPointSpeedTorsoLengthsPerSecond), 1e-7)
    }

    @Test
    fun aNegativeMotionBaselineIsRejected() {
        assertFailsWith<IllegalArgumentException> { config.copy(motionBaselineNs = -1L) }
    }

    // ---- the image-bounds check is scoped to what is measured -----------------------------------

    @Test
    fun aLimbOutsideTheFrameDoesNotDiscardAnOtherwiseUsableTorso() {
        // Regression. MediaPipe emits normalized coordinates outside [0,1] for a body part that has
        // left the frame. validate() used to reject the WHOLE frame on any of the 33 landmarks being
        // out of bounds, including the 29 it never reads. On a held-out wall sit that discarded 488
        // of 600 posed frames because the near foot sat at the bottom edge, while posture admission
        // - which scopes its checks to the joints it uses - accepted the same frames.
        val observation = StaticSkillObservation(config)
        val withFootOutOfFrame = StaticSkillPoseFrame(
            timestampNs = 0L,
            width = WIDTH,
            height = HEIGHT,
            landmarks = torsoLandmarks(350.0, 180.0, 100.0, .9) +
                landmark(index = 31, x = .5, y = 1.4, confidence = .9),
        )

        val result = observation.onFrame(withFootOutOfFrame, bodyLineAdmitted = true)

        assertEquals(StaticSkillReason.NO_PREVIOUS_FRAME, result.reason)
    }

    @Test
    fun aTorsoLandmarkOutsideTheFrameStillDiscardsTheFrame() {
        // The other half, so the scoping cannot quietly become "never check bounds at all": the
        // engine measures the torso, so a torso point out of the image is still unusable.
        val observation = StaticSkillObservation(config)
        val withHipOutOfFrame = StaticSkillPoseFrame(
            timestampNs = 0L,
            width = WIDTH,
            height = HEIGHT,
            landmarks = torsoLandmarks(350.0, 180.0, 100.0, .9)
                .filter { it.index != 23 } + landmark(index = 23, x = .5, y = 1.4, confidence = .9),
        )

        val result = observation.onFrame(withHipOutOfFrame, bodyLineAdmitted = true)

        assertEquals(StaticSkillReason.INVALID_LANDMARK, result.reason)
    }

    @Test
    fun aNonFiniteCoordinateAnywhereStillDiscardsTheFrame() {
        // Finiteness stays global on purpose. A NaN is corruption, not a limb leaving the frame.
        val observation = StaticSkillObservation(config)
        val corrupt = StaticSkillPoseFrame(
            timestampNs = 0L,
            width = WIDTH,
            height = HEIGHT,
            landmarks = torsoLandmarks(350.0, 180.0, 100.0, .9) +
                landmark(index = 31, x = Double.NaN, y = .5, confidence = .9),
        )

        val result = observation.onFrame(corrupt, bodyLineAdmitted = true)

        assertEquals(StaticSkillReason.INVALID_LANDMARK, result.reason)
    }

    private fun stillFrame(index: Int, frameNs: Long) = frame(
        timestampNs = index * frameNs,
        // 0.5px of jitter per 30fps frame: 0.15 torso-lengths/s, comfortably inside the gate but
        // deliberately non-zero, so "still" never depends on numerically identical coordinates.
        centroidXPx = 350.0 + .5 * index,
    )

    private fun frame(
        timestampNs: Long,
        centroidXPx: Double,
        centroidYPx: Double = 180.0,
        torsoLengthPx: Double = 100.0,
        confidence: Double = .9,
    ) = StaticSkillPoseFrame(
        timestampNs = timestampNs,
        width = WIDTH,
        height = HEIGHT,
        landmarks = torsoLandmarks(centroidXPx, centroidYPx, torsoLengthPx, confidence),
    )

    private fun rotatingTorsoFrame(timestampNs: Long, angleDegrees: Double): StaticSkillPoseFrame {
        val angle = angleDegrees * kotlin.math.PI / 180.0
        val halfLength = 50.0
        fun point(along: Double, across: Double, index: Int): Landmark {
            val x = 350.0 + along * kotlin.math.cos(angle) - across * kotlin.math.sin(angle)
            val y = 180.0 + along * kotlin.math.sin(angle) + across * kotlin.math.cos(angle)
            return landmark(index, x / WIDTH, y / HEIGHT, .9)
        }
        return StaticSkillPoseFrame(
            timestampNs = timestampNs,
            width = WIDTH,
            height = HEIGHT,
            landmarks = listOf(
                point(-halfLength, -SPREAD_PX, 11),
                point(-halfLength, SPREAD_PX, 12),
                point(halfLength, -SPREAD_PX, 23),
                point(halfLength, SPREAD_PX, 24),
            ),
        )
    }

    /**
     * A horizontal torso: shoulders at `centroidX - length/2`, hips at `centroidX + length/2`.
     * The four-point centroid is exactly (`centroidX`, `centroidY`) and the shoulder-mid to
     * hip-mid distance is exactly `torsoLengthPx`, so expected speeds are exact.
     */
    private fun torsoLandmarks(
        centroidXPx: Double,
        centroidYPx: Double,
        torsoLengthPx: Double,
        confidence: Double,
    ): List<Landmark> {
        val shoulderX = (centroidXPx - torsoLengthPx / 2.0) / WIDTH
        val hipX = (centroidXPx + torsoLengthPx / 2.0) / WIDTH
        val upperY = (centroidYPx - SPREAD_PX) / HEIGHT
        val lowerY = (centroidYPx + SPREAD_PX) / HEIGHT
        return listOf(
            landmark(11, shoulderX, upperY, confidence),
            landmark(12, shoulderX, lowerY, confidence),
            landmark(23, hipX, upperY, confidence),
            landmark(24, hipX, lowerY, confidence),
        )
    }

    private fun landmark(index: Int, x: Double, y: Double, confidence: Double) = Landmark(
        index = index,
        name = null,
        x = x,
        y = y,
        z = 0.0,
        visibility = confidence,
        presence = confidence,
    )

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val SPREAD_PX = 10.0
        // Not exactly 1/30s, so expected speeds carry a ~1e-8 relative error; the tolerances
        // above are sized for that, not loosened to accommodate a behaviour difference.
        const val FRAME_NS = 33_333_333L
        const val HALF_RATE_FRAME_NS = 66_666_666L
    }
}
