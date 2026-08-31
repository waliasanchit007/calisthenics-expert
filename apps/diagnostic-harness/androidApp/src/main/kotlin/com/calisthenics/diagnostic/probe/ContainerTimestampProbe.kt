package com.calisthenics.diagnostic.probe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.UUID

data class ContainerTimestampProbeResult(
    val submittedPtsUs: List<Long>,
    val demuxedPtsUs: List<Long>,
    val container: String,
    val codec: String,
)

/**
 * Measures Android's real encoder -> MP4 muxer -> MP4 extractor timestamp transform.
 *
 * The non-zero presentation timestamps are intentionally far from zero: if the muxer rebases the
 * first sample, the result is visible as one derived constant offset rather than being hidden by
 * a zero-origin input. No participant pixels are used; every submitted frame is a synthetic black
 * YUV420 frame.
 */
class ContainerTimestampProbe(private val context: Context) {
    fun run(): ContainerTimestampProbeResult {
        val output = File(context.cacheDir, "container-timestamp-${UUID.randomUUID()}.mp4")
        return try {
            val encoded = encodeAndMux(output)
            ContainerTimestampProbeResult(
                submittedPtsUs = SUBMITTED_PTS_US,
                demuxedPtsUs = demux(output),
                container = "video/mp4",
                codec = encoded.codecName,
            )
        } finally {
            check(!output.exists() || output.delete()) { "failed to delete timestamp-probe container" }
        }
    }

    private fun encodeAndMux(output: File): EncodedContainer {
        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val codecName = encoder.name
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var trackIndex = -1
            var inputIndex = 0
            var inputEos = false
            var outputEos = false
            val deadlineMs = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
            val frame = ByteArray(WIDTH * HEIGHT * 3 / 2) { index ->
                if (index < WIDTH * HEIGHT) 16.toByte() else 128.toByte()
            }

            while (!outputEos) {
                check(SystemClock.elapsedRealtime() < deadlineMs) { "container timestamp probe timed out" }

                if (!inputEos) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = checkNotNull(encoder.getInputBuffer(inputBufferIndex))
                        inputBuffer.clear()
                        if (inputIndex < SUBMITTED_PTS_US.size) {
                            check(inputBuffer.remaining() >= frame.size) {
                                "encoder input buffer is smaller than the synthetic frame"
                            }
                            inputBuffer.put(frame)
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                frame.size,
                                SUBMITTED_PTS_US[inputIndex],
                                0,
                            )
                            inputIndex += 1
                        } else {
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                SUBMITTED_PTS_US.last() + FRAME_INTERVAL_US,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
                        }
                    }
                }

                when (val outputBufferIndex = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "encoder output format changed more than once" }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outputBufferIndex >= 0) {
                        val encodedBuffer = checkNotNull(encoder.getOutputBuffer(outputBufferIndex))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted) { "encoder emitted media before its output format" }
                            encodedBuffer.position(info.offset)
                            encodedBuffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, encodedBuffer, info)
                        }
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
            check(muxerStarted) { "encoder produced no MP4 track" }
            return EncodedContainer(codecName)
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun demux(output: File): List<Long> {
        check(output.isFile && output.length() > 0L) { "timestamp probe did not produce an MP4" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("timestamp probe MP4 contains no video track")
            extractor.selectTrack(trackIndex)
            return buildList {
                while (extractor.sampleTime >= 0L) {
                    add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
        }
    }

    private data class EncodedContainer(val codecName: String)

    private companion object {
        const val MIME_TYPE = "video/avc"
        // The S25 vendor AVC encoder rejects the otherwise valid 32x32 probe format at configure
        // time. Keep the synthetic plane small, aligned, and inside the hardware encoder's
        // supported size envelope; pixel content and timestamp semantics are unchanged.
        const val WIDTH = 256
        const val HEIGHT = 256
        const val BIT_RATE = 128_000
        const val FRAME_RATE = 30
        const val FRAME_INTERVAL_US = 33_333L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val PROBE_TIMEOUT_MS = 20_000L
        val SUBMITTED_PTS_US = listOf(1_000_000L, 1_033_333L, 1_066_666L)
    }
}
