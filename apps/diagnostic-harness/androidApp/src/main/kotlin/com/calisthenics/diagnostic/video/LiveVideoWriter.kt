package com.calisthenics.diagnostic.video

import com.calisthenics.diagnostic.camera.OwnedYuvFrame

/**
 * In-app synchronized video writing for one non-participant M0.5B session.
 *
 * The writer is constructed per run. It consumes owned frames synchronously — everything it needs
 * from a frame is read inside [accept], so the caller may recycle the frame the instant the call
 * returns — and it writes a metadata sample paired to every encoded video sample at the same muxer
 * presentation timestamp.
 *
 * Submitted presentation timestamps are derived, never supplied:
 * `submittedPtsUs = floor(rebasedCaptureNs / 1_000)`.
 *
 * The encoder seam is deliberately split into [VideoEncoderPort.submit] and
 * [VideoEncoderPort.drain] because MediaCodec is pipelined: the encoded bytes for frame N are not
 * available when frame N is submitted. Modelling the encoder as a synchronous
 * `encode(frame) -> bytes` would force the writer to pair frame N's metadata with some earlier
 * frame's video sample, silently breaking the constant-offset relation that the released
 * synchronization validator derives from every sample.
 */
interface LiveVideoWriter {
    /**
     * Submit one owned frame for encoding and write any samples the encoder has finished.
     *
     * The returned disposition describes the *submission*. `Encoded` means the frame entered the
     * encoder carrying that presentation timestamp, not that its bytes are already muxed.
     */
    fun accept(frameIndex: Long, rebasedCaptureNs: Long, ownedFrame: OwnedYuvFrame): VideoDisposition

    /** Current accounting for this run. */
    fun accounting(): VideoAccounting

    /**
     * Signal end of stream, drain every remaining encoded sample, then stop and release the muxer
     * and encoder exactly once. Idempotent.
     */
    fun finish(): VideoWriterResult
}

/** How one accepted frame was classified at submission. */
sealed interface VideoDisposition {
    data class Encoded(val submittedPtsUs: Long) : VideoDisposition

    data class DroppedBeforeEncoder(val code: String) : VideoDisposition

    data class EncoderFailure(val code: String) : VideoDisposition
}

/** Complete per-run frame accounting. Every accepted frame lands in exactly one bucket. */
data class VideoAccounting(
    val accepted: Int = 0,
    val encoded: Int = 0,
    val droppedBeforeEncoder: Int = 0,
    val encoderFailures: Int = 0,
) {
    init {
        require(accepted == encoded + droppedBeforeEncoder + encoderFailures) {
            "accepted must equal the sum of every disposition bucket"
        }
    }
}

/** The sealed result of one completed writer run. */
data class VideoWriterResult(
    val accounting: VideoAccounting,
    val submittedPtsUs: List<Long>,
    val muxedPtsUs: List<Long>,
)

/** One metadata sample, paired to its video sample at the same muxer PTS. */
data class MetadataPayload(
    val frameIndex: Long,
    val rebasedCaptureNs: Long,
    val submittedPtsUs: Long,
)

/** One encoded unit drained from the encoder, identified by the PTS it was submitted with. */
class EncodedUnit(val ptsUs: Long, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EncodedUnit && ptsUs == other.ptsUs && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * ptsUs.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "EncodedUnit(ptsUs=$ptsUs, bytes=${bytes.size})"
}

/** How one submission was classified by the encoder. */
sealed interface SubmitOutcome {
    data object Accepted : SubmitOutcome

    data class Dropped(val code: String) : SubmitOutcome

    data class Failed(val code: String) : SubmitOutcome
}

/** The encoder seam. The device build supplies MediaCodec; unit tests supply a fake. */
interface VideoEncoderPort {
    /** Read everything needed from [frame] and submit it. Must not retain [frame]. */
    fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome

    /** Return every encoded unit finished so far, in presentation order. */
    fun drain(): List<EncodedUnit>

    /** Signal end of stream so the remaining units can be drained. */
    fun signalEndOfStream()

    fun release()
}

/** The muxer seam. The device build supplies MediaMuxer; unit tests supply a recorder. */
interface VideoMuxerPort {
    fun writeVideoSample(ptsUs: Long, bytes: ByteArray)

    fun writeMetadataSample(ptsUs: Long, payload: MetadataPayload)

    fun stop()

    fun release()
}

/** Derive the submitted presentation timestamp. Floors; never rounds up. */
internal fun submittedPtsUsOf(rebasedCaptureNs: Long): Long {
    require(rebasedCaptureNs >= 0L) { "rebasedCaptureNs must be non-negative" }
    return rebasedCaptureNs / 1_000L
}

/**
 * The seam-driven writer. [MediaCodecVideoWriter] wires this to real MediaCodec/MediaMuxer ports;
 * unit tests wire it to fakes so PTS derivation, pairing, accounting, and teardown are provable
 * off-device.
 */
class SeamedVideoWriter(
    private val encoder: VideoEncoderPort,
    private val muxer: VideoMuxerPort,
) : LiveVideoWriter {
    /**
     * The writer owns its own mutual exclusion.
     *
     * The composition deliberately calls [accept] outside the session monitor, because encoding
     * must never hold the session lock. CameraX delivers frames on a background executor, so two
     * callbacks can enter concurrently; unsynchronised counters lose updates and the accounting
     * invariant then fails. This was not visible to single-threaded unit tests and only appeared
     * on the device.
     */
    private val lock = Any()
    private var accepted = 0
    private var encoded = 0
    private var droppedBeforeEncoder = 0
    private var encoderFailures = 0
    private val submittedPtsUs = mutableListOf<Long>()
    private val muxedPtsUs = mutableListOf<Long>()

    /** Metadata awaiting its video sample, keyed by the PTS the frame was submitted with. */
    private val pendingMetadata = LinkedHashMap<Long, MetadataPayload>()

    /**
     * Every PTS submitted during this run. The uniqueness rule is run-wide, not merely among
     * undrained frames: two samples sharing a presentation timestamp would make the released
     * validator's per-sample offset derivation ambiguous.
     */
    private val allSubmittedPtsUs = HashSet<Long>()
    private var finished = false

    override fun accept(
        frameIndex: Long,
        rebasedCaptureNs: Long,
        ownedFrame: OwnedYuvFrame,
    ): VideoDisposition = synchronized(lock) {
        check(!finished) { "writer already finished; construct one writer per run" }
        val ptsUs = submittedPtsUsOf(rebasedCaptureNs)
        check(allSubmittedPtsUs.add(ptsUs)) { "duplicate submitted PTS $ptsUs" }
        accepted += 1
        val disposition = when (val outcome = encoder.submit(ownedFrame, ptsUs)) {
            is SubmitOutcome.Dropped -> {
                droppedBeforeEncoder += 1
                VideoDisposition.DroppedBeforeEncoder(outcome.code)
            }

            is SubmitOutcome.Failed -> {
                encoderFailures += 1
                VideoDisposition.EncoderFailure(outcome.code)
            }

            SubmitOutcome.Accepted -> {
                encoded += 1
                submittedPtsUs += ptsUs
                pendingMetadata[ptsUs] = MetadataPayload(
                    frameIndex = frameIndex,
                    rebasedCaptureNs = rebasedCaptureNs,
                    submittedPtsUs = ptsUs,
                )
                VideoDisposition.Encoded(ptsUs)
            }
        }
        writeDrainedUnits()
        return@synchronized disposition
    }

    /** Write each finished unit as a paired video+metadata sample at the same muxer PTS. */
    private fun writeDrainedUnits() {
        for (unit in encoder.drain()) {
            val payload = pendingMetadata.remove(unit.ptsUs)
                ?: error("encoder produced PTS ${unit.ptsUs} with no submitted metadata")
            muxer.writeVideoSample(unit.ptsUs, unit.bytes)
            muxer.writeMetadataSample(unit.ptsUs, payload)
            muxedPtsUs += unit.ptsUs
        }
    }

    override fun accounting(): VideoAccounting = synchronized(lock) {
        VideoAccounting(
            accepted = accepted,
            encoded = encoded,
            droppedBeforeEncoder = droppedBeforeEncoder,
            encoderFailures = encoderFailures,
        )
    }

    override fun finish(): VideoWriterResult = synchronized(lock) {
        if (!finished) {
            encoder.signalEndOfStream()
            writeDrainedUnits()
            check(pendingMetadata.isEmpty()) {
                "encoder never produced samples for submitted PTS ${pendingMetadata.keys}"
            }
            finished = true
            muxer.stop()
            muxer.release()
            encoder.release()
        }
        return@synchronized VideoWriterResult(
            accounting = VideoAccounting(
                accepted = accepted,
                encoded = encoded,
                droppedBeforeEncoder = droppedBeforeEncoder,
                encoderFailures = encoderFailures,
            ),
            submittedPtsUs = submittedPtsUs.toList(),
            muxedPtsUs = muxedPtsUs.toList(),
        )
    }
}
