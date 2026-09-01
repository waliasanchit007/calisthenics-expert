package com.calisthenics.diagnostic.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import java.nio.ByteBuffer

/**
 * The device-side wiring of [LiveVideoWriter] onto real MediaCodec and MediaMuxer.
 *
 * Nothing here decides policy. PTS derivation, sample pairing, accounting, and teardown ordering
 * all live in [SeamedVideoWriter], which is provable off-device; this file only supplies the two
 * ports. The device gate is what proves this wiring works against the real codec.
 */
object MediaCodecVideoWriter {

    const val VIDEO_MIME: String = MediaFormat.MIMETYPE_VIDEO_AVC
    const val METADATA_MIME: String = "application/octet-stream"

    /**
     * Open a writer for one run over [descriptor].
     *
     * The descriptor belongs to the video artifact inside the session's staging attempt, so the
     * container commits with the session. The encoder never publishes independently and never
     * learns a filesystem path.
     */
    fun open(
        descriptor: java.io.FileDescriptor,
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
        onReleased: () -> Unit = {},
    ): LiveVideoWriter {
        val sink = MediaMuxerVideoSink(descriptor, onReleased)
        val encoder = MediaCodecVideoEncoder(
            width = width,
            height = height,
            frameRate = frameRate,
            bitRate = bitRate,
            trackSink = sink,
        )
        val writer = SeamedVideoWriter(encoder = encoder, muxer = sink)
        return object : LiveVideoWriter by writer, MuxerDiagnostics {
            override fun muxerDiagnostics(): Map<String, Long> = sink.diagnostics()

            override fun encoderName(): String = encoder.codecName
        }
    }
}

/**
 * What the real muxer did during a run.
 *
 * Implemented by the device wiring only. The gate queries it so an unreadable container is
 * reported as evidence -- which track indices existed, whether the muxer started, how many samples
 * and key frames were written -- rather than leaving the cause to be guessed at.
 */
interface MuxerDiagnostics {
    fun muxerDiagnostics(): Map<String, Long>

    /**
     * The encoder component name, e.g. `c2.qti.avc.encoder`.
     *
     * This is what the admitted Task 3 probe recorded, and it is NOT the track MIME. Reporting
     * `video/avc` here compares two different identifiers and the provenance gate rightly refuses.
     */
    fun encoderName(): String
}

/** Lets the encoder hand the muxer the real output format before any sample is written. */
internal interface VideoTrackSink {
    fun onVideoFormat(format: MediaFormat)
}

/**
 * MediaCodec-backed [VideoEncoderPort].
 *
 * `submit` reads everything it needs from the owned frame and returns immediately, so the caller
 * may recycle the frame. `drain` returns only units the codec has actually finished, which is why
 * the writer pairs metadata by presentation timestamp rather than by submission order.
 */
internal class MediaCodecVideoEncoder(
    private val width: Int,
    private val height: Int,
    frameRate: Int,
    bitRate: Int,
    private val trackSink: VideoTrackSink,
) : VideoEncoderPort {

    /**
     * Bytes in one YUV420 frame.
     *
     * queueInputBuffer must be told how many bytes the buffer actually holds. Passing 0 -- easy to
     * do when the planes were filled through getInputImage rather than the raw ByteBuffer -- marks
     * every input as empty, so the codec encodes nothing and never emits output. On the device
     * that produced five accepted frames, zero drained units, and a 40-byte container.
     */
    private val frameSizeBytes: Int = width * height * 3 / 2

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaCodecVideoWriter.VIDEO_MIME)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var endOfStreamSignalled = false
    private var released = false

    /**
     * The codec name is provenance: the admitted container-timestamp probe is codec-specific.
     *
     * Captured eagerly. MediaCodec.getName() throws "codec is released already" once the codec is
     * released, and provenance is read when the evidence record is built, which is after
     * finalization.
     */
    val codecName: String = codec.name

    init {
        val format = MediaFormat.createVideoFormat(MediaCodecVideoWriter.VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome {
        if (released) return SubmitOutcome.Failed("encoder_released")
        val index = try {
            codec.dequeueInputBuffer(0L)
        } catch (error: IllegalStateException) {
            return SubmitOutcome.Failed("encoder_dequeue_input_failed")
        }
        if (index < 0) return SubmitOutcome.Dropped("encoder_input_unavailable")

        return try {
            val image = codec.getInputImage(index)
                ?: return SubmitOutcome.Failed("encoder_input_image_unavailable")
            copyPlanes(frame, image)
            codec.queueInputBuffer(index, 0, frameSizeBytes, ptsUs, 0)
            SubmitOutcome.Accepted
        } catch (error: IllegalStateException) {
            SubmitOutcome.Failed("encoder_queue_input_failed")
        } catch (error: IllegalArgumentException) {
            SubmitOutcome.Failed("encoder_input_layout_rejected")
        }
    }

    override fun drain(): List<EncodedUnit> {
        if (released) return emptyList()
        val units = mutableListOf<EncodedUnit>()
        // Before end of stream a zero timeout keeps the camera callback non-blocking. After it,
        // the codec still owes us every remaining frame plus the format change that starts the
        // muxer, and those take time to emerge: polling with a zero timeout returns nothing and
        // the container is finalized empty.
        val timeoutUs = if (endOfStreamSignalled) DRAIN_TIMEOUT_US else 0L
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return units

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackSink.onVideoFormat(codec.outputFormat)
                }

                index < 0 -> Unit

                else -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && !isCodecConfig && bufferInfo.size > 0) {
                        units += EncodedUnit(
                            ptsUs = bufferInfo.presentationTimeUs,
                            bytes = buffer.copyOfRange(bufferInfo),
                            flags = bufferInfo.flags,
                        )
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return units
                }
            }
        }
    }

    override fun signalEndOfStream() {
        if (released || endOfStreamSignalled) return
        endOfStreamSignalled = true
        val index = codec.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        codec.release()
    }

    /** Copy the owned planes into the codec's own layout, honouring its strides. */
    private fun copyPlanes(frame: OwnedYuvFrame, image: android.media.Image) {
        require(image.planes.size == 3) { "encoder input image must expose three planes" }
        copyPlane(frame.y.bytes, frame.y.rowStride, frame.y.pixelStride, image.planes[0], frame.width, frame.height)
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        copyPlane(frame.u.bytes, frame.u.rowStride, frame.u.pixelStride, image.planes[1], chromaWidth, chromaHeight)
        copyPlane(frame.v.bytes, frame.v.rowStride, frame.v.pixelStride, image.planes[2], chromaWidth, chromaHeight)
    }

    private fun copyPlane(
        source: ByteArray,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        target: android.media.Image.Plane,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        val targetBuffer = target.buffer
        val targetRowStride = target.rowStride
        val targetPixelStride = target.pixelStride

        // Contiguous rows on both sides copy a row at a time. The per-pixel path below is correct
        // for every layout but runs millions of bounds-checked iterations per 1080p frame.
        if (sourcePixelStride == 1 && targetPixelStride == 1) {
            for (row in 0 until heightPixels) {
                val sourceIndex = row * sourceRowStride
                val targetIndex = row * targetRowStride
                val available = minOf(widthPixels, source.size - sourceIndex, targetBuffer.limit() - targetIndex)
                if (available <= 0) continue
                targetBuffer.position(targetIndex)
                targetBuffer.put(source, sourceIndex, available)
            }
            targetBuffer.clear()
            return
        }

        for (row in 0 until heightPixels) {
            for (column in 0 until widthPixels) {
                val sourceIndex = row * sourceRowStride + column * sourcePixelStride
                val targetIndex = row * targetRowStride + column * targetPixelStride
                if (sourceIndex < source.size && targetIndex < targetBuffer.limit()) {
                    targetBuffer.put(targetIndex, source[sourceIndex])
                }
            }
        }
    }

    private fun ByteBuffer.copyOfRange(info: MediaCodec.BufferInfo): ByteArray {
        val bytes = ByteArray(info.size)
        position(info.offset)
        limit(info.offset + info.size)
        get(bytes)
        clear()
        return bytes
    }

    private companion object {
        const val END_OF_STREAM_TIMEOUT_US = 10_000L
        const val DRAIN_TIMEOUT_US = 250_000L
    }
}

/**
 * MediaMuxer-backed [VideoMuxerPort].
 *
 * The metadata track is added eagerly; the video track can only be added once the encoder reports
 * its real output format, so the muxer starts on that handshake. Samples are refused before start
 * rather than silently dropped — a lost sample would desynchronise the video and pose streams.
 */
internal class MediaMuxerVideoSink(
    descriptor: java.io.FileDescriptor,
    private val onReleased: () -> Unit = {},
) : VideoMuxerPort, VideoTrackSink {

    private val muxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val metadataTrack: Int
    private var videoTrack: Int = NO_TRACK
    private var started = false
    private var stopped = false
    private var released = false
    private var videoSamples = 0
    private var keyFrames = 0
    private var formatChanges = 0
    private val sampleInfo = MediaCodec.BufferInfo()

    init {
        val metadataFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaCodecVideoWriter.METADATA_MIME)
        }
        metadataTrack = muxer.addTrack(metadataFormat)
    }

    override fun onVideoFormat(format: MediaFormat) {
        formatChanges += 1
        check(!started) { "video format arrived after the muxer started" }
        videoTrack = muxer.addTrack(format)
        muxer.start()
        started = true
    }

    override fun writeVideoSample(ptsUs: Long, bytes: ByteArray, flags: Int) {
        check(started) { "muxer received a video sample before the encoder reported its format" }
        if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) keyFrames += 1
        videoSamples += 1
        writeSample(videoTrack, ptsUs, bytes, flags)
    }

    override fun writeMetadataSample(ptsUs: Long, payload: MetadataPayload) {
        check(started) { "muxer received a metadata sample before the encoder reported its format" }
        writeSample(metadataTrack, ptsUs, encodeMetadata(payload), 0)
    }

    private fun writeSample(track: Int, ptsUs: Long, bytes: ByteArray, flags: Int) {
        val buffer = ByteBuffer.wrap(bytes)
        sampleInfo.set(0, bytes.size, ptsUs, flags)
        muxer.writeSampleData(track, buffer, sampleInfo)
    }

    /** What the muxer actually did, so an unreadable container is evidence rather than a guess. */
    fun diagnostics(): Map<String, Long> = mapOf(
        "muxer_video_track" to videoTrack.toLong(),
        "muxer_metadata_track" to metadataTrack.toLong(),
        "muxer_started" to if (started) 1L else 0L,
        "muxer_video_samples" to videoSamples.toLong(),
        "muxer_key_frames" to keyFrames.toLong(),
        "muxer_format_changes" to formatChanges.toLong(),
    )

    override fun stop() {
        if (stopped || !started) return
        stopped = true
        muxer.stop()
    }

    override fun release() {
        if (released) return
        released = true
        muxer.release()
        // The storage layer owns the underlying file handle; hand it back so it can be closed.
        onReleased()
    }

    private companion object {
        const val NO_TRACK = -1

        /**
         * Fixed 24-byte big-endian layout: frame index, rebased capture ns, submitted PTS us.
         * A fixed layout keeps the host-side demux verifier independent of any serialization
         * library and makes a truncated sample detectable rather than silently reinterpreted.
         */
        fun encodeMetadata(payload: MetadataPayload): ByteArray =
            ByteBuffer.allocate(24)
                .putLong(payload.frameIndex)
                .putLong(payload.rebasedCaptureNs)
                .putLong(payload.submittedPtsUs)
                .array()
    }
}
