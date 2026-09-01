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

    /** Open a writer for one run. The caller owns [outputPath] and must not reuse it. */
    fun open(
        outputPath: String,
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int,
    ): LiveVideoWriter {
        val sink = MediaMuxerVideoSink(outputPath)
        val encoder = MediaCodecVideoEncoder(
            width = width,
            height = height,
            frameRate = frameRate,
            bitRate = bitRate,
            trackSink = sink,
        )
        return SeamedVideoWriter(encoder = encoder, muxer = sink)
    }
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
    width: Int,
    height: Int,
    frameRate: Int,
    bitRate: Int,
    private val trackSink: VideoTrackSink,
) : VideoEncoderPort {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaCodecVideoWriter.VIDEO_MIME)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var endOfStreamSignalled = false
    private var released = false

    /** The codec name is provenance: the admitted container-timestamp probe is codec-specific. */
    val codecName: String
        get() = codec.name

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
            codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
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
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0L)
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
                        units += EncodedUnit(bufferInfo.presentationTimeUs, buffer.copyOfRange(bufferInfo))
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
    }
}

/**
 * MediaMuxer-backed [VideoMuxerPort].
 *
 * The metadata track is added eagerly; the video track can only be added once the encoder reports
 * its real output format, so the muxer starts on that handshake. Samples are refused before start
 * rather than silently dropped — a lost sample would desynchronise the video and pose streams.
 */
internal class MediaMuxerVideoSink(outputPath: String) : VideoMuxerPort, VideoTrackSink {

    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val metadataTrack: Int
    private var videoTrack: Int = NO_TRACK
    private var started = false
    private var stopped = false
    private var released = false
    private val sampleInfo = MediaCodec.BufferInfo()

    init {
        val metadataFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaCodecVideoWriter.METADATA_MIME)
        }
        metadataTrack = muxer.addTrack(metadataFormat)
    }

    override fun onVideoFormat(format: MediaFormat) {
        check(!started) { "video format arrived after the muxer started" }
        videoTrack = muxer.addTrack(format)
        muxer.start()
        started = true
    }

    override fun writeVideoSample(ptsUs: Long, bytes: ByteArray) {
        check(started) { "muxer received a video sample before the encoder reported its format" }
        writeSample(videoTrack, ptsUs, bytes)
    }

    override fun writeMetadataSample(ptsUs: Long, payload: MetadataPayload) {
        check(started) { "muxer received a metadata sample before the encoder reported its format" }
        writeSample(metadataTrack, ptsUs, encodeMetadata(payload))
    }

    private fun writeSample(track: Int, ptsUs: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        sampleInfo.set(0, bytes.size, ptsUs, 0)
        muxer.writeSampleData(track, buffer, sampleInfo)
    }

    override fun stop() {
        if (stopped || !started) return
        stopped = true
        muxer.stop()
    }

    override fun release() {
        if (released) return
        released = true
        muxer.release()
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
