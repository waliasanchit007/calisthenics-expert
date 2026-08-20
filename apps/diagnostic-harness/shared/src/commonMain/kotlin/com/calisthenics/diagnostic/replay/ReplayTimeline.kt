package com.calisthenics.diagnostic.replay

/** Raised when a replay fixture timeline cannot be trusted to drive a deterministic session. */
class ReplayTimelineException(message: String) : IllegalArgumentException(message)

/** One fixture frame as recorded, before rebasing onto the session monotonic clock. */
data class FixtureFrame(val frameIndex: Long, val captureTimestampNs: Long)

/** One fixture frame after rebasing onto the session monotonic clock. */
data class RebasedFrame(
    val frameIndex: Long,
    val fixtureCaptureNs: Long,
    val rebasedCaptureNs: Long,
)

/** What the caller observed while handling one paced frame. */
data class PacedOutcome(val inferenceStartNs: Long)

/** Wall-free clock seam so pacing is deterministic under test. */
interface ReplayClock {
    fun nowNs(): Long

    /** Blocks until [deadlineNs]; returns immediately when already at or past it. */
    fun waitUntil(deadlineNs: Long)
}

/**
 * A validated fixture timeline rebased onto the session monotonic clock.
 *
 * Rebasing preserves the fixture's relative spacing exactly: the first frame lands on
 * `sessionStartNs` and every later frame keeps its original offset from the fixture's first
 * capture. Absolute fixture timestamps are therefore excluded from replay equality, while the
 * intervals that drive the pacer are not.
 */
class ReplayTimeline(frames: List<FixtureFrame>, private val sessionStartNs: Long) {
    private val rebased: List<RebasedFrame>

    init {
        if (frames.isEmpty()) {
            throw ReplayTimelineException("a replay fixture must contain at least one frame")
        }
        frames.forEachIndexed { position, frame ->
            if (frame.frameIndex != position.toLong()) {
                throw ReplayTimelineException(
                    "fixture frame indices must be contiguous from zero: " +
                        "position $position carries index ${frame.frameIndex}",
                )
            }
            if (position > 0 && frame.captureTimestampNs <= frames[position - 1].captureTimestampNs) {
                throw ReplayTimelineException(
                    "fixture capture timestamps must strictly increase: " +
                        "${frames[position - 1].captureTimestampNs} then ${frame.captureTimestampNs}",
                )
            }
        }
        val origin = frames.first().captureTimestampNs
        rebased = frames.map { frame ->
            RebasedFrame(
                frameIndex = frame.frameIndex,
                fixtureCaptureNs = frame.captureTimestampNs,
                rebasedCaptureNs = sessionStartNs + (frame.captureTimestampNs - origin),
            )
        }
    }

    fun rebased(): List<RebasedFrame> = rebased

    val size: Int get() = rebased.size
}

/**
 * Paces a rebased timeline against a monotonic clock.
 *
 * The pacer waits until each rebased capture instant and **never drops a frame because
 * processing ran late**. When handling costs more than the fixture interval it falls behind
 * while preserving the submitted sequence, which is what makes replay deterministic in
 * submitted-frame terms even on a slow device.
 */
class ReplayPacer(private val clock: ReplayClock) {
    fun pace(timeline: ReplayTimeline, handle: (RebasedFrame) -> PacedOutcome): List<PacedOutcome> =
        timeline.rebased().map { frame ->
            clock.waitUntil(frame.rebasedCaptureNs)
            val outcome = handle(frame)
            if (outcome.inferenceStartNs < frame.rebasedCaptureNs) {
                throw ReplayTimelineException(
                    "inference_start_ns ${outcome.inferenceStartNs} precedes rebased capture " +
                        "${frame.rebasedCaptureNs} for frame ${frame.frameIndex}",
                )
            }
            outcome
        }
}
