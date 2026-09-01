package com.calisthenics.diagnostic.probe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import com.calisthenics.diagnostic.video.MediaCodecVideoWriter
import com.calisthenics.diagnostic.video.Mp4TrackTimescaleReader
import com.calisthenics.diagnostic.video.TrackTimescales
import java.nio.ByteBuffer
import java.io.File
import java.util.UUID

data class ContainerTimestampProbeResult(
    val onGrid: ContainerTimestampProbeSequence,
    val offGrid: ContainerTimestampProbeSequence,
    val timebases: TrackTimescales,
    val container: String,
    val codec: String,
)

data class ContainerTimestampProbeSequence(
    val submittedPtsUs: List<Long>,
    val demuxedPtsUs: List<Long>,
)

data class ControlledSubmittedPts(
    val onGrid: List<Long>,
    val offGrid: List<Long>,
)

fun deriveControlledSubmittedPts(timebases: TrackTimescales): ControlledSubmittedPts {
    val ticksPerSecond = timebases.videoTicksPerSecond
    require(ticksPerSecond > 0L && ticksPerSecond == timebases.metadataTicksPerSecond) {
        "controlled probe requires one positive common track timebase"
    }
    val microsecondGrid = 1_000_000L / greatestCommonDivisor(1_000_000L, ticksPerSecond)
    require(microsecondGrid > 1L) {
        "container timebase cannot express an integral-microsecond off-grid control"
    }
    val base = ((1_000_000L + microsecondGrid - 1L) / microsecondGrid) * microsecondGrid
    val onGridInterval = ((33_333L + microsecondGrid / 2L) / microsecondGrid) * microsecondGrid
    val onGrid = listOf(base, base + onGridInterval, base + 2L * onGridInterval)
    val offGrid = listOf(
        base,
        base + onGridInterval + 1L,
        base + 2L * onGridInterval + 2L,
    )
    require(onGrid.all { it * ticksPerSecond % 1_000_000L == 0L }) {
        "derived on-grid sequence is not aligned to the observed track timebase"
    }
    require(offGrid.any { it * ticksPerSecond % 1_000_000L != 0L }) {
        "derived off-grid sequence is vacuously aligned"
    }
    return ControlledSubmittedPts(onGrid = onGrid, offGrid = offGrid)
}

private tailrec fun greatestCommonDivisor(left: Long, right: Long): Long =
    if (right == 0L) left else greatestCommonDivisor(right, left % right)

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
        val calibration = runOnce(CALIBRATION_PTS_US)
        val controls = deriveControlledSubmittedPts(calibration.timebases)
        val onGrid = runOnce(controls.onGrid)
        val offGrid = runOnce(controls.offGrid)
        check(onGrid.codecName == calibration.codecName) {
            "controlled probe selected different encoders across runs"
        }
        check(offGrid.codecName == calibration.codecName) {
            "controlled probe selected different encoders across runs"
        }
        check(onGrid.timebases == calibration.timebases && offGrid.timebases == calibration.timebases) {
            "controlled probe observed different track timebases across runs"
        }
        return ContainerTimestampProbeResult(
            onGrid = ContainerTimestampProbeSequence(controls.onGrid, onGrid.demuxedPtsUs),
            offGrid = ContainerTimestampProbeSequence(
                controls.offGrid,
                offGrid.demuxedPtsUs,
            ),
            timebases = calibration.timebases,
            container = "video/mp4",
            codec = calibration.codecName,
        )
    }

    private fun runOnce(submittedPtsUs: List<Long>): ProbeRun {
        val output = File(context.cacheDir, "container-timestamp-${UUID.randomUUID()}.mp4")
        return try {
            val encoded = encodeAndMux(output, submittedPtsUs)
            val demuxed = demux(output)
            ProbeRun(
                codecName = encoded.codecName,
                demuxedPtsUs = demuxed.videoPtsUs,
                timebases = demuxed.timebases,
            )
        } finally {
            check(!output.exists() || output.delete()) { "failed to delete timestamp-probe container" }
        }
    }

    private fun encodeAndMux(output: File, submittedPtsUs: List<Long>): EncodedContainer {
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
            var videoTrackIndex = -1
            var metadataTrackIndex = -1
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
                        if (inputIndex < submittedPtsUs.size) {
                            check(inputBuffer.remaining() >= frame.size) {
                                "encoder input buffer is smaller than the synthetic frame"
                            }
                            inputBuffer.put(frame)
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                frame.size,
                                submittedPtsUs[inputIndex],
                                0,
                            )
                            inputIndex += 1
                        } else {
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                submittedPtsUs.last() + FRAME_INTERVAL_US,
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
                        metadataTrackIndex = muxer.addTrack(
                            MediaFormat().apply {
                                setString(MediaFormat.KEY_MIME, MediaCodecVideoWriter.METADATA_MIME)
                            },
                        )
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
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
                            muxer.writeSampleData(videoTrackIndex, encodedBuffer, info)
                            val metadata = ByteBuffer.allocate(java.lang.Long.BYTES)
                                .putLong(info.presentationTimeUs)
                                .apply { flip() }
                            val metadataInfo = MediaCodec.BufferInfo().apply {
                                set(0, metadata.remaining(), info.presentationTimeUs, 0)
                            }
                            muxer.writeSampleData(metadataTrackIndex, metadata, metadataInfo)
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

    private fun demux(output: File): DemuxedProbe {
        check(output.isFile && output.length() > 0L) { "timestamp probe did not produce an MP4" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            val tracks = (0 until extractor.trackCount).associateBy { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            val videoTrack = tracks.entries.singleOrNull { it.key.startsWith("video/") }?.value
                ?: error("timestamp probe MP4 contains no unique video track")
            val metadataTrack = tracks[MediaCodecVideoWriter.METADATA_MIME]
                ?: error("timestamp probe MP4 contains no metadata track")
            val videoPtsUs = readTrackPts(extractor, videoTrack)
            val metadataPtsUs = readTrackPts(extractor, metadataTrack)
            check(videoPtsUs == metadataPtsUs) {
                "timestamp probe video and metadata PTS disagree"
            }
            return DemuxedProbe(
                videoPtsUs = videoPtsUs,
                timebases = Mp4TrackTimescaleReader.read(output.readBytes()),
            )
        } finally {
            extractor.release()
        }
    }

    private fun readTrackPts(extractor: MediaExtractor, trackIndex: Int): List<Long> {
        extractor.selectTrack(trackIndex)
        return buildList {
            while (extractor.sampleTime >= 0L) {
                add(extractor.sampleTime)
                if (!extractor.advance()) break
            }
        }.also { extractor.unselectTrack(trackIndex) }
    }

    private data class EncodedContainer(val codecName: String)
    private data class ProbeRun(
        val codecName: String,
        val demuxedPtsUs: List<Long>,
        val timebases: TrackTimescales,
    )
    private data class DemuxedProbe(
        val videoPtsUs: List<Long>,
        val timebases: TrackTimescales,
    )

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
        val CALIBRATION_PTS_US = listOf(1_000_000L, 1_033_333L, 1_066_666L)
    }
}
