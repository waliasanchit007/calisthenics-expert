package com.calisthenics.diagnostic.replay

import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.requireReplayProvenance
import com.calisthenics.diagnostic.preprocessing.Rgb24
import com.calisthenics.diagnostic.preprocessing.scalePadRgb24

/** One decoded fixture plane with the digest the fixture metadata declares for it. */
data class ReplayFixtureFrame(
    val frameIndex: Long,
    val captureTimestampNs: Long,
    val plane: ByteArray,
    val declaredSha256: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ReplayFixtureFrame && frameIndex == other.frameIndex)

    override fun hashCode(): Int = frameIndex.hashCode()
}

data class ReplayFixture(
    val frames: List<ReplayFixtureFrame>,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    /** The oracle for check 1: submitted indices are compared against this, never run-to-run. */
    val declaredIndices: List<Long> get() = frames.map { it.frameIndex }
}

/** Geometry actually applied by canonical preprocessing, recorded as determinism evidence. */
data class PreprocessingGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
)

data class SubmittedFrame(
    val frameIndex: Long,
    val rebasedCaptureNs: Long,
    val backendTimestampMs: Long,
    val canonicalInputSha256: String,
)

/**
 * Everything the six determinism checks compare. Values excluded from equality by section 7
 * (session identity, wall clock, inference latency) are deliberately absent.
 */
data class ReplayRunReport(
    val submittedIndices: List<Long>,
    val submitted: List<SubmittedFrame>,
    val geometry: PreprocessingGeometry,
    val recordedProvenance: RecordedProvenance,
)

/**
 * Provenance as captured at the construction site — what was actually passed to the backend.
 *
 * MediaPipe does not expose its constructed options for read-back, so asserting on the manifest
 * would prove nothing about the running task. This records the values at the point they are
 * handed over, and the same record is what belongs in the export.
 */
data class RecordedProvenance(
    val modelSha256: String,
    val tasksVisionVersion: String,
    val runningMode: String,
    val delegate: String,
    val taskOptions: Map<String, Any>,
)

/**
 * Drives one deterministic replay pass.
 *
 * The session-relative time base is pinned to `t0 = 0`, so rebased capture timestamps equal
 * fixture offsets and are reproducible across runs. That is what makes backend timestamps both
 * stable and compatible with `FRAME.BACKEND_TIMESTAMP_ORDER`.
 */
class ReplayRunner(
    private val clock: ReplayClock,
    private val provenance: ModelProvenance,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val submit: (SubmittedFrame, ByteArray) -> Unit,
) {
    fun run(fixture: ReplayFixture): ReplayRunReport {
        requireReplayProvenance(provenance)
        val timeline = ReplayTimeline(
            fixture.frames.map { FixtureFrame(it.frameIndex, it.captureTimestampNs) },
            sessionStartNs = REPLAY_TIME_BASE_NS,
        )
        val planes = fixture.frames.associateBy { it.frameIndex }
        val submitted = mutableListOf<SubmittedFrame>()
        var previousBackendMs = Long.MIN_VALUE

        ReplayPacer(clock).pace(timeline) { paced ->
            val source = planes.getValue(paced.frameIndex)
            if (source.plane.size != Rgb24.byteLength(fixture.frameWidth, fixture.frameHeight)) {
                throw ReplayTimelineException("fixture plane ${paced.frameIndex} has an unexpected length")
            }
            val canonical = scalePadRgb24(
                source.plane,
                fixture.frameWidth,
                fixture.frameHeight,
                targetWidth,
                targetHeight,
            )
            val backendMs = backendTimestampMs(paced.rebasedCaptureNs, previousBackendMs)
            previousBackendMs = backendMs
            val record = SubmittedFrame(
                frameIndex = paced.frameIndex,
                rebasedCaptureNs = paced.rebasedCaptureNs,
                backendTimestampMs = backendMs,
                canonicalInputSha256 = Sha256Digest.hex(canonical),
            )
            submitted += record
            submit(record, canonical)
            PacedOutcome(inferenceStartNs = clock.nowNs())
        }

        return ReplayRunReport(
            submittedIndices = submitted.map { it.frameIndex },
            submitted = submitted,
            geometry = PreprocessingGeometry(
                sourceWidth = fixture.frameWidth,
                sourceHeight = fixture.frameHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
            ),
            recordedProvenance = RecordedProvenance(
                modelSha256 = provenance.sha256,
                tasksVisionVersion = provenance.tasksVisionVersion,
                runningMode = provenance.runningMode,
                delegate = provenance.delegate,
                taskOptions = provenance.taskOptions,
            ),
        )
    }

    companion object {
        /** Replay pins the session-relative monotonic base to zero. See design section 7. */
        const val REPLAY_TIME_BASE_NS: Long = 0L

        /** The section 6 construction rule: `max(floor(capture / 10^6), previous + 1)`. */
        fun backendTimestampMs(rebasedCaptureNs: Long, previousBackendMs: Long): Long {
            val quantized = rebasedCaptureNs / 1_000_000L
            val successor = if (previousBackendMs == Long.MIN_VALUE) Long.MIN_VALUE else previousBackendMs + 1
            return if (successor > quantized) successor else quantized
        }
    }
}
