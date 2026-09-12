package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.hypot

enum class StaticSkillMode { UNSELECTED, TORSO_STILL }

enum class StaticSkillState { WAITING, ENTERING, STILL, UNCERTAIN }

enum class StaticSkillLabel { UNCERTAIN, TORSO_STILL }

enum class StaticSkillReason {
    NONE, NO_POSE, INVALID_DIMENSIONS, DUPLICATE_LANDMARK_INDEX, MISSING_LANDMARK,
    INVALID_LANDMARK, LOW_CONFIDENCE, ZERO_LENGTH_TORSO, INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP, BACKWARDS_TIMESTAMP, NO_PREVIOUS_FRAME, BODY_LINE_NOT_ADMITTED,
    MOVING, CAPTURE_GAP, CENSORED_AT_END_OF_INPUT,
}

data class StaticSkillConfig(
    val minimumConfidence: Double,
    val maximumTorsoPointSpeedTorsoLengthsPerSecond: Double,
    val maximumGapNs: Long,
    val minimumStillNs: Long,
    val mode: StaticSkillMode,
    /**
     * Consecutive over-threshold frames required before a hold is closed as MOVING.
     *
     * With 1 (the original behaviour) a single frame ends an interval. Measured on a real wall-sit
     * hold, only 6 of 540 frames exceeded the speed threshold, yet those isolated spikes split one
     * steady hold into three fragments. Requiring persistence mirrors `minimumEndpointSamples`
     * used elsewhere in this codebase. It does not change the threshold itself.
     */
    val minimumMovingSamplesToClose: Int,
    /**
     * How far back the motion measurement reaches, in nanoseconds.
     *
     * With 0 (the original behaviour) motion is the displacement since the immediately previous
     * valid frame, divided by that frame interval. That measure is not frame-rate invariant: a
     * fixed spatial landmark jitter produces a speed proportional to the capture rate, so the same
     * athlete holding equally still scores differently at 24 and 60 fps. Measured across the six
     * frozen hold clips, the gate's median tracked landmark jitter amplitude, not athlete motion:
     * over a 4.67 s back lever the torso centroid moved 0.105 torso-lengths NET while the gate
     * reported a median of 0.209 torso-lengths/s.
     *
     * With a positive value the measurement is taken against the most recent valid frame at least
     * this far back, divided by the ACTUAL elapsed time. Jitter then contributes roughly
     * jitter/baseline rather than jitter*fps. The cost is latency: motion cannot be reported sooner
     * than this baseline, so a genuine exit is detected up to this much late.
     *
     * Only the measurement reaches back. Coverage, candidate start and capture continuity still key
     * off the immediately previous frame, so with 0 nothing whatsoever changes.
     */
    val motionBaselineNs: Long,
    val side: ArmSide = ArmSide.LEFT,
    val straightMinDegrees: Double = 0.0,
    val straightMaxDegrees: Double = 180.0,
    val maximumOrientationDegreesFromHorizontal: Double = 30.0,
) {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(
            maximumTorsoPointSpeedTorsoLengthsPerSecond.isFinite() &&
                maximumTorsoPointSpeedTorsoLengthsPerSecond >= 0.0
        )
        require(maximumGapNs > 0L)
        require(minimumStillNs > 0L)
        require(minimumMovingSamplesToClose >= 1)
        require(motionBaselineNs >= 0L)
    }
}

data class StaticSkillPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

data class StaticSkillMotionMeasurement(
    val centroidSpeedTorsoLengthsPerSecond: Double?,
    val maxTorsoPointSpeedTorsoLengthsPerSecond: Double = 0.0,
    val reason: StaticSkillReason,
)

data class StaticSkillInterval(
    val entryTimestampNs: Long,
    val exitTimestampNs: Long,
    val durationNs: Long,
    val observedNs: Long,
    val endReason: StaticSkillReason,
    val label: StaticSkillLabel,
    val leftCensored: Boolean,
)

data class StaticSkillFrameResult(
    val timestampNs: Long,
    val centroidSpeedTorsoLengthsPerSecond: Double?,
    val maxTorsoPointSpeedTorsoLengthsPerSecond: Double,
    val state: StaticSkillState,
    val reason: StaticSkillReason,
    val closedInterval: StaticSkillInterval?,
)

private val torsoIndices = intArrayOf(11, 12, 23, 24)

fun measureTorsoCentroidSpeed(
    previous: StaticSkillPoseFrame,
    current: StaticSkillPoseFrame,
    config: StaticSkillConfig,
): StaticSkillMotionMeasurement = measureTorsoMotion(previous, current, config)

private fun measureTorsoMotion(
    previous: StaticSkillPoseFrame,
    current: StaticSkillPoseFrame,
    config: StaticSkillConfig,
): StaticSkillMotionMeasurement {
    val previousPoints = torsoPoints(previous, config) ?: return invalidMeasurement(previous, config)
    val currentPoints = torsoPoints(current, config) ?: return invalidMeasurement(current, config)
    val elapsedNs = current.timestampNs - previous.timestampNs
    if (elapsedNs <= 0L) return StaticSkillMotionMeasurement(null, 0.0, StaticSkillReason.INVALID_TIMESTAMP)
    val scale = torsoLength(currentPoints)
    if (scale <= 0.0) return StaticSkillMotionMeasurement(null, 0.0, StaticSkillReason.ZERO_LENGTH_TORSO)
    val seconds = elapsedNs / 1_000_000_000.0
    val centroidPrevious = centroid(previousPoints)
    val centroidCurrent = centroid(currentPoints)
    val centroidSpeed = distance(centroidPrevious, centroidCurrent) / scale / seconds
    val maxPointSpeed = previousPoints.zip(currentPoints).maxOf { (before, after) ->
        distance(before, after) / scale / seconds
    }
    return StaticSkillMotionMeasurement(centroidSpeed, maxPointSpeed, StaticSkillReason.NONE)
}

class StaticSkillObservation(private val config: StaticSkillConfig) {
    var state: StaticSkillState = StaticSkillState.WAITING
        private set

    private var previousFrame: StaticSkillPoseFrame? = null

    /**
     * Valid frames recent enough to serve as a motion baseline, oldest first. Pruned to the window
     * plus the single frame just outside it, so it stays bounded. Invalid frames are not added and
     * do not clear it: the measurement always spans two real observations and never interpolates
     * across a dropout, exactly as before. Dropouts remain the capture-gap rule's job.
     */
    private val recentFrames = ArrayDeque<StaticSkillPoseFrame>()
    private var lastTimestampNs: Long? = null
    private var lastInputWasValid = false
    private var candidateStartNs: Long? = null
    private var lastGoodNs: Long? = null
    /**
     * Timestamp of the last frame that reached the stillness logic, whether it was still or an
     * absorbed transient. `lastGoodNs` cannot serve here: with [StaticSkillConfig.minimumMovingSamplesToClose]
     * above 1 it deliberately stays behind on absorbed frames, so a gap check keyed off it would
     * report CAPTURE_GAP on frames that arrived perfectly on time. Capture continuity and
     * stillness are separate questions and are now tracked separately. With the value 1 no frame
     * is ever absorbed, so this is always equal to `lastGoodNs` and nothing changes.
     */
    private var lastAliveNs: Long? = null
    private var observedNs = 0L
    private var candidateWasPrecededByMotion = false
    private var finished = false
    private var movingSamples = 0

    fun onFrame(frame: StaticSkillPoseFrame, bodyLineAdmitted: Boolean): StaticSkillFrameResult {
        require(!finished) { "observation is already finished" }
        if (frame.timestampNs < 0L) return invalidate(frame.timestampNs, StaticSkillReason.INVALID_TIMESTAMP)
        val previousTimestamp = lastTimestampNs
        if (previousTimestamp != null) {
            if (frame.timestampNs == previousTimestamp) return invalidate(frame.timestampNs, StaticSkillReason.DUPLICATE_TIMESTAMP)
            if (frame.timestampNs < previousTimestamp) return invalidate(frame.timestampNs, StaticSkillReason.BACKWARDS_TIMESTAMP)
        }
        lastTimestampNs = frame.timestampNs
        val previous = previousFrame
        val validation = validate(frame)
        if (validation != StaticSkillReason.NONE) {
            lastInputWasValid = false
            return unusable(frame.timestampNs, validation)
        }
        if (previous == null) {
            previousFrame = frame
            recentFrames.addLast(frame)
            lastInputWasValid = true
            if (!bodyLineAdmitted) return basic(frame.timestampNs, null, 0.0, StaticSkillState.WAITING, StaticSkillReason.BODY_LINE_NOT_ADMITTED)
            return basic(frame.timestampNs, null, 0.0, StaticSkillState.WAITING, StaticSkillReason.NO_PREVIOUS_FRAME)
        }
        pruneBaseline(frame.timestampNs)
        val baseline = baselineFrame(frame.timestampNs)
        if (baseline == null) {
            // Not enough history to measure over the required baseline yet. Refuse rather than fall
            // back to a one-frame measurement, which would be the very quantity this baseline exists
            // to avoid. With motionBaselineNs = 0 this branch is unreachable: the previous frame is
            // always in the buffer and always earlier than this one.
            previousFrame = frame
            recentFrames.addLast(frame)
            lastInputWasValid = true
            return basic(frame.timestampNs, null, 0.0, StaticSkillState.WAITING, StaticSkillReason.NO_PREVIOUS_FRAME)
        }
        val measurement = measureTorsoMotion(baseline, frame, config)
        previousFrame = frame
        recentFrames.addLast(frame)
        val hadValidPredecessor = lastInputWasValid
        lastInputWasValid = true
        if (measurement.reason != StaticSkillReason.NONE) return unusable(frame.timestampNs, measurement.reason)
        val moving = measurement.maxTorsoPointSpeedTorsoLengthsPerSecond > config.maximumTorsoPointSpeedTorsoLengthsPerSecond
        if (!bodyLineAdmitted) {
            val closed = if (state == StaticSkillState.STILL) {
                close(requireNotNull(lastGoodNs), StaticSkillReason.BODY_LINE_NOT_ADMITTED)
            } else {
                null
            }
            resetCandidate()
            state = StaticSkillState.WAITING
            return result(frame.timestampNs, measurement, StaticSkillReason.BODY_LINE_NOT_ADMITTED, closed)
        }
        var gapClosed: StaticSkillInterval? = null
        if (state == StaticSkillState.STILL && lastAliveNs != null &&
            frame.timestampNs - requireNotNull(lastAliveNs) > config.maximumGapNs
        ) {
            gapClosed = close(requireNotNull(lastGoodNs), StaticSkillReason.CAPTURE_GAP)
            resetCandidate()
            state = StaticSkillState.WAITING
        }
        if (moving) {
            movingSamples++
            if (movingSamples < config.minimumMovingSamplesToClose && candidateStartNs != null) {
                // Transient: keep the interval alive. This frame is NOT added to observedNs,
                // because lastGoodNs stays behind, so coverage still reflects only still frames.
                // It IS alive for capture-continuity purposes: the camera delivered it.
                lastAliveNs = frame.timestampNs
                return result(frame.timestampNs, measurement, StaticSkillReason.MOVING, gapClosed)
            }
            val closed = if (state == StaticSkillState.STILL) {
                close(requireNotNull(lastGoodNs), StaticSkillReason.MOVING)
            } else {
                null
            } ?: gapClosed
            resetCandidate()
            candidateWasPrecededByMotion = true
            state = StaticSkillState.WAITING
            return result(frame.timestampNs, measurement, StaticSkillReason.MOVING, closed)
        }
        movingSamples = 0
        if (candidateStartNs == null) {
            candidateStartNs = if (gapClosed != null || !hadValidPredecessor) frame.timestampNs else previous.timestampNs
            lastGoodNs = frame.timestampNs
            lastAliveNs = frame.timestampNs
            observedNs = if (gapClosed == null && hadValidPredecessor) frame.timestampNs - previous.timestampNs else 0L
        } else if (hadValidPredecessor && lastGoodNs == previous.timestampNs) {
            observedNs += frame.timestampNs - previous.timestampNs
        }
        lastGoodNs = frame.timestampNs
        lastAliveNs = frame.timestampNs
        if (frame.timestampNs - requireNotNull(candidateStartNs) >= config.minimumStillNs) state = StaticSkillState.STILL
        else if (state != StaticSkillState.STILL) state = StaticSkillState.ENTERING
        return result(frame.timestampNs, measurement, StaticSkillReason.NONE, gapClosed)
    }

    fun finish(): StaticSkillInterval? {
        if (finished) return null
        finished = true
        val end = lastGoodNs ?: return null
        return if (state == StaticSkillState.STILL) close(end, StaticSkillReason.CENSORED_AT_END_OF_INPUT) else null
    }

    private fun unusable(timestampNs: Long, reason: StaticSkillReason): StaticSkillFrameResult {
        if (state == StaticSkillState.STILL && previousFrame != null &&
            timestampNs - requireNotNull(lastAliveNs) > config.maximumGapNs
        ) {
            val closed = close(requireNotNull(lastGoodNs), StaticSkillReason.CAPTURE_GAP)
            resetCandidate()
            state = StaticSkillState.UNCERTAIN
            return basic(timestampNs, null, 0.0, state, reason, closed)
        }
        if (state != StaticSkillState.STILL) state = StaticSkillState.UNCERTAIN
        return basic(timestampNs, null, 0.0, state, reason)
    }

    private fun invalidate(timestampNs: Long, reason: StaticSkillReason) = basic(timestampNs, null, 0.0, StaticSkillState.UNCERTAIN, reason)

    private fun close(timestampNs: Long, reason: StaticSkillReason): StaticSkillInterval? {
        val start = candidateStartNs ?: return null
        return StaticSkillInterval(
            start, timestampNs, timestampNs - start, observedNs, reason,
            if (config.mode == StaticSkillMode.TORSO_STILL) StaticSkillLabel.TORSO_STILL else StaticSkillLabel.UNCERTAIN,
            !candidateWasPrecededByMotion,
        )
    }

    /**
     * Newest buffered frame at least [StaticSkillConfig.motionBaselineNs] before [now]. The buffer is
     * ascending, so the last frame that still satisfies the cutoff is the one wanted.
     */
    private fun baselineFrame(now: Long): StaticSkillPoseFrame? {
        val cutoff = now - config.motionBaselineNs
        var chosen: StaticSkillPoseFrame? = null
        for (candidate in recentFrames) {
            if (candidate.timestampNs > cutoff || candidate.timestampNs >= now) break
            chosen = candidate
        }
        return chosen
    }

    /** Keep the baseline window plus exactly one frame older than it; drop everything before that. */
    private fun pruneBaseline(now: Long) {
        val cutoff = now - config.motionBaselineNs
        while (recentFrames.size >= 2 && recentFrames[1].timestampNs <= cutoff) recentFrames.removeFirst()
    }

    private fun resetCandidate() {
        movingSamples = 0
        candidateStartNs = null
        lastGoodNs = null
        lastAliveNs = null
        observedNs = 0L
    }

    private fun result(timestampNs: Long, measurement: StaticSkillMotionMeasurement, reason: StaticSkillReason, closed: StaticSkillInterval?) =
        basic(timestampNs, measurement.centroidSpeedTorsoLengthsPerSecond, measurement.maxTorsoPointSpeedTorsoLengthsPerSecond, state, reason, closed)

    private fun basic(timestampNs: Long, centroid: Double?, maxPoint: Double, currentState: StaticSkillState, reason: StaticSkillReason, closed: StaticSkillInterval? = null) =
        StaticSkillFrameResult(timestampNs, centroid, maxPoint, currentState, reason, closed)

    private fun validate(frame: StaticSkillPoseFrame): StaticSkillReason {
        if (frame.width <= 0 || frame.height <= 0) return StaticSkillReason.INVALID_DIMENSIONS
        if (frame.landmarks.isEmpty()) return StaticSkillReason.NO_POSE
        val seen = HashSet<Int>()
        if (frame.landmarks.any { !seen.add(it.index) }) return StaticSkillReason.DUPLICATE_LANDMARK_INDEX
        if (frame.landmarks.any { it.index < 0 || !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return StaticSkillReason.INVALID_LANDMARK
        val points = torsoIndices.map { index -> frame.landmarks.firstOrNull { it.index == index } ?: return StaticSkillReason.MISSING_LANDMARK }
        // The image-bounds check applies to the landmarks this engine MEASURES, not to all 33.
        // MediaPipe emits normalized coordinates outside [0,1] for a body part that has left the
        // frame, which is an ordinary thing for a limb to do and says nothing about the torso. The
        // private torsoPoints() helper below already scopes its bounds check this way; validate()
        // did not, so a near foot at the bottom edge discarded the whole frame. Measured on a
        // held-out wall sit: 498 of 600 posed frames had some landmark out of bounds and only 10 of
        // those involved a torso landmark, so 488 frames lost their stillness measurement for a
        // reason unrelated to what was being measured. Every clip in the existing corpus has the
        // athlete wholly inside the frame, so this is a no-op on all of them.
        if (points.any { it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return StaticSkillReason.INVALID_LANDMARK
        if (points.any {
                it.visibility == null || it.presence == null ||
                    !it.visibility!!.isFinite() || !it.presence!!.isFinite() ||
                    it.visibility !in 0.0..1.0 || it.presence !in 0.0..1.0 ||
                    it.visibility!! < config.minimumConfidence || it.presence!! < config.minimumConfidence
            }) return StaticSkillReason.LOW_CONFIDENCE
        return StaticSkillReason.NONE
    }

}

private data class Point(val x: Double, val y: Double)
private fun torsoPoints(frame: StaticSkillPoseFrame, config: StaticSkillConfig): List<Point>? {
    if (frame.width <= 0 || frame.height <= 0 || frame.landmarks.isEmpty()) return null
    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    if (frame.landmarks.any { byIndex.put(it.index, it) != null }) return null
    val points = torsoIndices.map { byIndex[it] ?: return null }
    if (points.any {
            !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() ||
                it.x !in 0.0..1.0 || it.y !in 0.0..1.0 ||
                it.visibility == null || it.presence == null ||
                !it.visibility!!.isFinite() || !it.presence!!.isFinite() ||
                it.visibility !in 0.0..1.0 || it.presence !in 0.0..1.0 ||
                it.visibility!! < config.minimumConfidence || it.presence!! < config.minimumConfidence
        }) return null
    return points.map { Point(it.x * frame.width, it.y * frame.height) }
}

private fun invalidMeasurement(frame: StaticSkillPoseFrame, config: StaticSkillConfig) =
    StaticSkillMotionMeasurement(null, 0.0, when {
        frame.width <= 0 || frame.height <= 0 -> StaticSkillReason.INVALID_DIMENSIONS
        frame.landmarks.isEmpty() -> StaticSkillReason.NO_POSE
        frame.landmarks.map { it.index }.toSet().size != frame.landmarks.size -> StaticSkillReason.DUPLICATE_LANDMARK_INDEX
        torsoIndices.any { index -> frame.landmarks.none { it.index == index } } -> StaticSkillReason.MISSING_LANDMARK
        frame.landmarks.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() } -> StaticSkillReason.INVALID_LANDMARK
        frame.landmarks.any {
            it.visibility == null || it.presence == null ||
                !it.visibility!!.isFinite() || !it.presence!!.isFinite() ||
                it.visibility !in 0.0..1.0 || it.presence !in 0.0..1.0 ||
                it.visibility!! < config.minimumConfidence || it.presence!! < config.minimumConfidence
        } -> StaticSkillReason.LOW_CONFIDENCE
        else -> StaticSkillReason.INVALID_LANDMARK
    })

private fun centroid(points: List<Point>) = Point(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
private fun torsoLength(points: List<Point>): Double = distance(
    Point((points[0].x + points[1].x) / 2.0, (points[0].y + points[1].y) / 2.0),
    Point((points[2].x + points[3].x) / 2.0, (points[2].y + points[3].y) / 2.0),
)
private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
