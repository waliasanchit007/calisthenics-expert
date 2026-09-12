package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Explicit prototype parameters for a side-view plank body-line hold. They are not validated
 * anatomical or form thresholds. Counting/hold detection is distinct from form assessment: this
 * measures whether the shoulder-hip-ankle image-plane angle stays within a declared band, not
 * whether the plank is technically correct. Straightness alone cannot distinguish a supported
 * plank from an upright standing body or a person lying flat on a surface; `maximumOrientationDegreesFromHorizontal`
 * only rules out the upright-standing case (a near-vertical image-plane body line). It cannot
 * detect whether a straight, near-horizontal body is actually elevated off a floor/mat versus
 * resting on it — that remains an explicit, undismissed limitation of this body-line-hold
 * measurement, not a plank-detection claim.
 */
data class PlankConfig(
    val side: ArmSide,
    val straightMinDegrees: Double,
    val straightMaxDegrees: Double,
    val minimumConfidence: Double,
    val maximumGapNs: Long,
    val minimumEntrySamples: Int,
    val maximumOrientationDegreesFromHorizontal: Double,
) {
    init {
        require(straightMinDegrees.isFinite() && straightMinDegrees in 0.0..180.0) {
            "straight_min_degrees must be finite and in [0, 180]"
        }
        require(straightMaxDegrees.isFinite() && straightMaxDegrees in 0.0..180.0) {
            "straight_max_degrees must be finite and in [0, 180]"
        }
        require(straightMinDegrees < straightMaxDegrees) {
            "straight_min_degrees must be less than straight_max_degrees"
        }
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimum_confidence must be finite and in [0, 1]"
        }
        require(maximumGapNs > 0) { "maximum_gap_ns must be positive" }
        require(minimumEntrySamples > 0) { "minimum_entry_samples must be positive" }
        require(
            maximumOrientationDegreesFromHorizontal.isFinite() &&
                maximumOrientationDegreesFromHorizontal in 0.0..90.0
        ) {
            "maximum_orientation_degrees_from_horizontal must be finite and in [0, 90]"
        }
    }
}

data class PlankPoseFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val landmarks: List<Landmark>,
)

enum class PlankState { WAITING, ENTERING, HOLDING, UNCERTAIN }

enum class PlankReason {
    NONE,
    NO_POSE,
    INVALID_DIMENSIONS,
    DUPLICATE_LANDMARK_INDEX,
    MISSING_LANDMARK,
    INVALID_LANDMARK,
    LOW_CONFIDENCE,
    ZERO_LENGTH_SEGMENT,
    INVALID_TIMESTAMP,
    DUPLICATE_TIMESTAMP,
    BACKWARDS_TIMESTAMP,
    CAPTURE_GAP,
    OUT_OF_BAND,
    UNSUPPORTED_ORIENTATION,
    CENSORED_AT_END_OF_INPUT,
}

data class PlankBodyLineMeasurement(
    val bodyLineDegrees: Double?,
    val reason: PlankReason,
)

/** A closed hold interval. `observedNs` excludes any unobserved span within the hold (gaps,
 * low-confidence or missing-landmark frames tolerated within `maximumGapNs`); it is always
 * less than or equal to the wall-clock `durationNs`. */
data class PlankHoldEvent(
    val entryTimestampNs: Long,
    val exitTimestampNs: Long,
    val durationNs: Long,
    val observedNs: Long,
    val endReason: PlankReason,
)

data class PlankFrameResult(
    val timestampNs: Long,
    val bodyLineDegrees: Double?,
    val state: PlankState,
    val reason: PlankReason,
    val closedHold: PlankHoldEvent?,
)

/** Pure image-plane shoulder-hip-ankle measurement in upright source-normalized coordinates. */
fun measurePlankBodyLine(frame: PlankPoseFrame, config: PlankConfig): PlankBodyLineMeasurement {
    if (frame.width <= 0 || frame.height <= 0) {
        return PlankBodyLineMeasurement(null, PlankReason.INVALID_DIMENSIONS)
    }
    if (frame.landmarks.isEmpty()) {
        return PlankBodyLineMeasurement(null, PlankReason.NO_POSE)
    }

    val byIndex = HashMap<Int, Landmark>(frame.landmarks.size)
    for (landmark in frame.landmarks) {
        if (landmark.index < 0) return PlankBodyLineMeasurement(null, PlankReason.INVALID_LANDMARK)
        if (byIndex.put(landmark.index, landmark) != null) {
            return PlankBodyLineMeasurement(null, PlankReason.DUPLICATE_LANDMARK_INDEX)
        }
    }

    val indices = when (config.side) {
        ArmSide.LEFT -> intArrayOf(11, 23, 27)
        ArmSide.RIGHT -> intArrayOf(12, 24, 28)
    }
    val joints = indices.map { index ->
        byIndex[index] ?: return PlankBodyLineMeasurement(null, PlankReason.MISSING_LANDMARK)
    }
    if (joints.any { landmark ->
            !landmark.x.isFinite() || !landmark.y.isFinite() || !landmark.z.isFinite() ||
                landmark.x !in 0.0..1.0 || landmark.y !in 0.0..1.0
        }
    ) {
        return PlankBodyLineMeasurement(null, PlankReason.INVALID_LANDMARK)
    }
    if (joints.any { landmark ->
            val visibility = landmark.visibility
            val presence = landmark.presence
            visibility == null || presence == null ||
                !visibility.isFinite() || !presence.isFinite() ||
                visibility !in 0.0..1.0 || presence !in 0.0..1.0 ||
                visibility < config.minimumConfidence || presence < config.minimumConfidence
        }
    ) {
        return PlankBodyLineMeasurement(null, PlankReason.LOW_CONFIDENCE)
    }

    // Source-normalized x/y are projected into source pixels before measuring, preserving the
    // image's pixel aspect ratio, matching the squat knee-angle geometry's convention.
    val shoulder = joints[0]
    val hip = joints[1]
    val ankle = joints[2]
    val ax = (shoulder.x - hip.x) * frame.width.toDouble()
    val ay = (shoulder.y - hip.y) * frame.height.toDouble()
    val bx = (ankle.x - hip.x) * frame.width.toDouble()
    val by = (ankle.y - hip.y) * frame.height.toDouble()
    val aLength = sqrt(ax * ax + ay * ay)
    val bLength = sqrt(bx * bx + by * by)
    if (aLength == 0.0 || bLength == 0.0) {
        return PlankBodyLineMeasurement(null, PlankReason.ZERO_LENGTH_SEGMENT)
    }

    // Orientation admission: the shoulder-to-ankle image-plane vector's angle from horizontal.
    // This is a provisional side-view assumption (subject's body line runs roughly across the
    // frame), not a validated camera-angle model. It only rules out a near-vertical body line
    // (e.g. a standing subject) reading as a "straight" hold; it says nothing about whether a
    // straight, near-horizontal body is actually elevated off a supporting surface.
    val fullBodyDx = (ankle.x - shoulder.x) * frame.width.toDouble()
    val fullBodyDy = (ankle.y - shoulder.y) * frame.height.toDouble()
    if (fullBodyDx != 0.0 || fullBodyDy != 0.0) {
        val orientationDegrees = atan2(abs(fullBodyDy), abs(fullBodyDx)) * 180.0 / kotlin.math.PI
        if (orientationDegrees > config.maximumOrientationDegreesFromHorizontal) {
            return PlankBodyLineMeasurement(null, PlankReason.UNSUPPORTED_ORIENTATION)
        }
    }

    val cosine = ((ax * bx + ay * by) / (aLength * bLength)).coerceIn(-1.0, 1.0)
    return PlankBodyLineMeasurement(acos(cosine) * 180.0 / kotlin.math.PI, PlankReason.NONE)
}

/**
 * Conservative, explicit-config hold-interval experiment. It reports duration/entry/exit/coverage
 * diagnostics, not a rep count, and it does not label form-valid planks. A gap or unusable frame
 * within `maximumGapNs` keeps a hold alive but is excluded from `observedNs`; a gap exceeding it,
 * or a confidently out-of-band reading, closes the interval at the last confirmed in-band sample.
 */
class PlankHoldExperiment(private val config: PlankConfig) {
    var state: PlankState = PlankState.WAITING
        private set

    private var lastTimestampNs: Long? = null
    private var entrySamples = 0
    private var entryCandidateTimestampNs: Long? = null
    private var lastEntrySampleTimestampNs: Long? = null
    private var entryTimestampNs: Long? = null
    private var lastGoodTimestampNs: Long? = null
    private var observedNs: Long = 0

    fun onFrame(frame: PlankPoseFrame): PlankFrameResult {
        val timestamp = frame.timestampNs
        if (timestamp < 0L) {
            val closed = invalidate(PlankReason.INVALID_TIMESTAMP)
            return result(timestamp, null, PlankReason.INVALID_TIMESTAMP, closed)
        }

        val previous = lastTimestampNs
        if (previous != null) {
            if (timestamp == previous) {
                val closed = invalidate(PlankReason.DUPLICATE_TIMESTAMP)
                return result(timestamp, null, PlankReason.DUPLICATE_TIMESTAMP, closed)
            }
            if (timestamp < previous) {
                val closed = invalidate(PlankReason.BACKWARDS_TIMESTAMP)
                return result(timestamp, null, PlankReason.BACKWARDS_TIMESTAMP, closed)
            }
        }
        val previousRawTimestampNs = lastTimestampNs
        lastTimestampNs = timestamp

        val measurement = measurePlankBodyLine(frame, config)
        val angle = measurement.bodyLineDegrees
        if (angle == null) {
            return advanceUnusable(timestamp, measurement.reason)
        }

        val inBand = angle in config.straightMinDegrees..config.straightMaxDegrees
        return when (state) {
            PlankState.WAITING, PlankState.UNCERTAIN -> {
                if (state == PlankState.UNCERTAIN) state = PlankState.WAITING
                if (inBand) {
                    acceptCandidateSample(timestamp)
                } else {
                    entrySamples = 0
                    entryCandidateTimestampNs = null
                    lastEntrySampleTimestampNs = null
                }
                result(timestamp, angle, PlankReason.NONE, null)
            }
            PlankState.ENTERING -> {
                if (inBand) {
                    acceptCandidateSample(timestamp)
                    result(timestamp, angle, PlankReason.NONE, null)
                } else {
                    entrySamples = 0
                    entryCandidateTimestampNs = null
                    lastEntrySampleTimestampNs = null
                    state = PlankState.WAITING
                    result(timestamp, angle, PlankReason.NONE, null)
                }
            }
            PlankState.HOLDING -> {
                if (inBand) {
                    // A confident in-band reading does not, by itself, prove nothing happened since
                    // the last confirmed-good sample: sparse sampling can deliver two good rows with
                    // no intervening unusable-frame row at all. Gate on elapsed time exactly like the
                    // unusable-frame path so a large gap is never silently counted as held.
                    val gapSinceLastGood = timestamp - requireNotNull(lastGoodTimestampNs)
                    if (gapSinceLastGood > config.maximumGapNs) {
                        val closed = closeHold(PlankReason.CAPTURE_GAP)
                        // Restart entry evidence with this sample rather than stitching a confirmed
                        // hold across the unobserved gap.
                        acceptCandidateSample(timestamp)
                        return result(timestamp, angle, PlankReason.CAPTURE_GAP, closed)
                    }
                    // Only a run of contiguous good samples establishes an observed interval; an
                    // isolated good sample right after a tolerated gap/bad frame cannot retroactively
                    // prove the unobserved span was held, so it contributes no duration by itself.
                    if (previousRawTimestampNs == lastGoodTimestampNs) {
                        observedNs += timestamp - requireNotNull(lastGoodTimestampNs)
                    }
                    lastGoodTimestampNs = timestamp
                    result(timestamp, angle, PlankReason.NONE, null)
                } else {
                    val closed = closeHold(PlankReason.OUT_OF_BAND)
                    result(timestamp, angle, PlankReason.OUT_OF_BAND, closed)
                }
            }
        }
    }

    /**
     * Accepts an in-band sample toward the current (unconfirmed) entry candidate. If the previous
     * candidate sample is farther in the past than `maximumGapNs`, the stale candidate is discarded
     * first — two in-band samples separated by an unobserved gap must never stitch into a confirmed
     * hold, whether or not an explicit unusable-frame row appeared between them.
     */
    private fun acceptCandidateSample(timestamp: Long) {
        val lastCandidate = lastEntrySampleTimestampNs
        if (entrySamples > 0 && lastCandidate != null && timestamp - lastCandidate > config.maximumGapNs) {
            entrySamples = 0
            entryCandidateTimestampNs = null
        }
        if (entrySamples == 0) entryCandidateTimestampNs = timestamp
        entrySamples++
        lastEntrySampleTimestampNs = timestamp
        if (entrySamples >= config.minimumEntrySamples) {
            state = PlankState.HOLDING
            entryTimestampNs = entryCandidateTimestampNs
            lastGoodTimestampNs = timestamp
            observedNs = timestamp - requireNotNull(entryTimestampNs)
        } else {
            state = PlankState.ENTERING
        }
    }

    private fun advanceUnusable(timestamp: Long, reason: PlankReason): PlankFrameResult {
        if (state == PlankState.HOLDING) {
            val gap = timestamp - requireNotNull(lastGoodTimestampNs)
            if (gap > config.maximumGapNs) {
                val closed = closeHold(PlankReason.CAPTURE_GAP)
                state = PlankState.UNCERTAIN
                return result(timestamp, null, reason, closed)
            }
            // Within the tolerated gap: the hold survives but this span is not observed.
            return result(timestamp, null, reason, null)
        }
        entrySamples = 0
        entryCandidateTimestampNs = null
        lastEntrySampleTimestampNs = null
        state = PlankState.UNCERTAIN
        return result(timestamp, null, reason, null)
    }

    private fun invalidate(reason: PlankReason): PlankHoldEvent? {
        val closed = if (state == PlankState.HOLDING) closeHold(reason) else null
        entrySamples = 0
        entryCandidateTimestampNs = null
        lastEntrySampleTimestampNs = null
        state = PlankState.UNCERTAIN
        return closed
    }

    private fun closeHold(reason: PlankReason): PlankHoldEvent {
        val entry = requireNotNull(entryTimestampNs)
        val exit = requireNotNull(lastGoodTimestampNs)
        val event = PlankHoldEvent(
            entryTimestampNs = entry,
            exitTimestampNs = exit,
            durationNs = exit - entry,
            observedNs = observedNs,
            endReason = reason,
        )
        state = PlankState.WAITING
        entrySamples = 0
        entryCandidateTimestampNs = null
        lastEntrySampleTimestampNs = null
        entryTimestampNs = null
        lastGoodTimestampNs = null
        observedNs = 0
        return event
    }

    private fun result(
        timestamp: Long,
        bodyLineDegrees: Double?,
        reason: PlankReason,
        closedHold: PlankHoldEvent?,
    ) = PlankFrameResult(timestamp, bodyLineDegrees, state, reason, closedHold)

    /**
     * Call once after the last frame has been processed. If a hold is still open when input ends,
     * this reports it as an explicitly censored interval closed at the last confirmed-good sample
     * — never inventing an exit beyond what was actually observed, and never silently dropping the
     * interval merely because no further frame arrived to close it. Returns null if no hold was
     * open (nothing to censor).
     */
    fun finish(): PlankHoldEvent? =
        if (state == PlankState.HOLDING) closeHold(PlankReason.CENSORED_AT_END_OF_INPUT) else null
}
