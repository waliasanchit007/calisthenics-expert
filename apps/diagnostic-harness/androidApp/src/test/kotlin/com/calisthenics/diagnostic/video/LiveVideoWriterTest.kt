package com.calisthenics.diagnostic.video

import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.YuvColorRange
import com.calisthenics.diagnostic.preprocessing.YuvColorStandard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Task 4 fan-out, lifecycle, and pairing behaviour for the in-app video writer.
 *
 * The real MediaCodec/MediaMuxer path is exercised by the on-device gate. These unit tests drive
 * the writer through its encoder/muxer seams so that PTS derivation, sample pairing, disposition
 * accounting, and teardown ordering are provable off-device.
 */
class LiveVideoWriterTest {

    @Test
    fun submittedPtsIsFlooredMicrosecondsOfTheRebasedCaptureTimestamp() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer)

        // 33_333_999 ns floors to 33_333 us; the remainder must never round up.
        val disposition = writer.accept(0, 33_333_999L, frame())

        assertEquals(VideoDisposition.Encoded(33_333L), disposition)
        assertEquals(listOf(33_333L), muxer.videoPtsUs)
    }

    @Test
    fun zeroRebasedCaptureProducesZeroPts() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer)

        assertEquals(VideoDisposition.Encoded(0L), writer.accept(0, 0L, frame()))
    }

    @Test
    fun everyEncodedFrameWritesPairedVideoAndMetadataSamplesAtTheSameMuxerPts() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer)

        writer.accept(0, 0L, frame())
        writer.accept(1, 33_333_000L, frame())
        writer.accept(2, 66_666_000L, frame())

        assertEquals(listOf(0L, 33_333L, 66_666L), muxer.videoPtsUs)
        assertEquals(muxer.videoPtsUs, muxer.metadataPtsUs)
        assertEquals(
            listOf(
                MetadataPayload(frameIndex = 0, rebasedCaptureNs = 0L, submittedPtsUs = 0L),
                MetadataPayload(frameIndex = 1, rebasedCaptureNs = 33_333_000L, submittedPtsUs = 33_333L),
                MetadataPayload(frameIndex = 2, rebasedCaptureNs = 66_666_000L, submittedPtsUs = 66_666L),
            ),
            muxer.metadataPayloads,
        )
    }

    /**
     * The pipelined case the seam exists for: the encoder holds frames back, so units drain out of
     * step with submission. Each metadata sample must still follow its own video sample.
     */
    @Test
    fun pipelinedEncoderStillPairsEachMetadataSampleWithItsOwnVideoSample() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer, encoder = PipelinedEncoder(latency = 2))

        writer.accept(0, 0L, frame())
        writer.accept(1, 33_333_000L, frame())
        writer.accept(2, 66_666_000L, frame())
        // Nothing has drained yet at a latency of two frames behind.
        assertTrue(muxer.videoPtsUs.size < 3)
        val result = writer.finish()

        assertEquals(listOf(0L, 33_333L, 66_666L), muxer.videoPtsUs)
        assertEquals(muxer.videoPtsUs, muxer.metadataPtsUs)
        assertEquals(
            listOf(0L, 33_333L, 66_666L),
            muxer.metadataPayloads.map { it.submittedPtsUs },
        )
        assertEquals(listOf(0L, 1L, 2L), muxer.metadataPayloads.map { it.frameIndex })
        assertEquals(listOf(0L, 33_333L, 66_666L), result.muxedPtsUs)
    }

    @Test
    fun finishFailsWhenTheEncoderNeverProducesASubmittedFrame() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer, encoder = SwallowingEncoder())

        writer.accept(0, 0L, frame())

        // A silently lost frame would leave video and pose streams disagreeing about length.
        assertFailsWith<IllegalStateException> { writer.finish() }
    }

    @Test
    fun aFrameDroppedBeforeTheEncoderIsClassifiedAndWritesNoSample() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer, encoder = DroppingEncoder("encoder_input_unavailable"))

        val disposition = writer.accept(0, 0L, frame())

        assertEquals(VideoDisposition.DroppedBeforeEncoder("encoder_input_unavailable"), disposition)
        assertTrue(muxer.videoPtsUs.isEmpty())
        assertTrue(muxer.metadataPtsUs.isEmpty())
        assertEquals(1, writer.accounting().droppedBeforeEncoder)
        assertEquals(0, writer.accounting().encoded)
    }

    @Test
    fun anEncoderFailureIsClassifiedAndCounted() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer, encoder = FailingEncoder("encoder_fault"))

        val disposition = writer.accept(0, 0L, frame())

        assertEquals(VideoDisposition.EncoderFailure("encoder_fault"), disposition)
        assertTrue(muxer.videoPtsUs.isEmpty())
        assertEquals(1, writer.accounting().encoderFailures)
    }

    @Test
    fun submitReadsTheFrameSynchronouslyBeforeReturning() {
        val muxer = RecordingMuxer()
        val encoder = ImmediateEncoder()
        val writer = newWriter(muxer, encoder = encoder)

        val disposition = writer.accept(0, 0L, frame())

        // The caller may recycle the owned frame the instant accept() returns. Deferring the read
        // to another thread is what turns that into a use-after-close.
        assertIs<VideoDisposition.Encoded>(disposition)
        assertEquals(1, encoder.submitCount, "submit did not happen inside accept()")
        assertEquals(listOf(0L), muxer.videoPtsUs, "video sample was not written inside accept()")
        assertEquals(listOf(0L), muxer.metadataPtsUs, "metadata sample was not written inside accept()")
    }

    @Test
    fun theWriterCannotAcceptFramesAfterFinish() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer)

        writer.accept(0, 0L, frame())
        writer.finish()

        assertFailsWith<IllegalStateException> { writer.accept(1, 33_333_000L, frame()) }
    }

    @Test
    fun aDuplicateSubmittedPtsIsRejected() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer)

        writer.accept(0, 33_333_000L, frame())

        // Two frames sharing a PTS would make the metadata pairing ambiguous.
        assertFailsWith<IllegalStateException> { writer.accept(1, 33_333_400L, frame()) }
    }

    @Test
    fun finishStopsAndReleasesTheEncoderAndMuxerExactlyOnce() {
        val muxer = RecordingMuxer()
        val encoder = ImmediateEncoder()
        val writer = newWriter(muxer, encoder = encoder)

        writer.accept(0, 0L, frame())
        writer.finish()
        writer.finish()

        assertEquals(1, encoder.releaseCount)
        assertEquals(1, encoder.endOfStreamCount)
        assertEquals(1, muxer.stopCount)
        assertEquals(1, muxer.releaseCount)
    }

    @Test
    fun finishReportsTheCompleteAccountingForTheRun() {
        val muxer = RecordingMuxer()
        val writer = newWriter(muxer, encoder = FlakyEncoder(failAtIndex = 1))

        writer.accept(0, 0L, frame())
        writer.accept(1, 33_333_000L, frame())
        writer.accept(2, 66_666_000L, frame())
        val result = writer.finish()

        assertEquals(2, result.accounting.encoded)
        assertEquals(1, result.accounting.encoderFailures)
        assertEquals(0, result.accounting.droppedBeforeEncoder)
        assertEquals(3, result.accounting.accepted)
        assertEquals(listOf(0L, 66_666L), result.submittedPtsUs)
        assertEquals(listOf(0L, 66_666L), result.muxedPtsUs)
    }

    @Test
    fun writerConstructionIsPerRunSoTwoRunsShareNoAccounting() {
        val first = newWriter(RecordingMuxer())
        first.accept(0, 0L, frame())
        first.finish()

        val second = newWriter(RecordingMuxer())

        assertEquals(0, second.accounting().accepted)
        assertIs<VideoDisposition.Encoded>(second.accept(0, 0L, frame()))
    }

    @Test
    fun concurrentCallbacksKeepTheAccountingInvariant() {
        // CameraX delivers on a background executor and the composition calls accept() outside the
        // session monitor, so two callbacks can enter concurrently. Unsynchronised counters lose
        // updates and VideoAccounting's init check then throws. A single-threaded test cannot see
        // this; it first appeared on the device.
        val muxer = SynchronizedRecordingMuxer()
        val writer = SeamedVideoWriter(encoder = ThreadSafeImmediateEncoder(), muxer = muxer)
        val threads = 8
        val perThread = 60
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        repeat(threads) { t ->
            Thread {
                start.await()
                repeat(perThread) { i ->
                    val index = (t * perThread + i).toLong()
                    val rebasedNs = (index + 1L) * 1_000L
                    runCatching { writer.accept(index, rebasedNs, frame()) }
                        .onFailure { failures += it }
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish")

        assertTrue(failures.isEmpty(), "concurrent accept failed")
        val accounting = writer.accounting()
        assertEquals(threads * perThread, accounting.accepted)
        assertEquals(
            accounting.accepted,
            accounting.encoded + accounting.droppedBeforeEncoder + accounting.encoderFailures,
        )
    }

    // --- seams -------------------------------------------------------------------------------

    private fun newWriter(
        muxer: RecordingMuxer,
        encoder: VideoEncoderPort = ImmediateEncoder(),
    ): LiveVideoWriter = SeamedVideoWriter(encoder = encoder, muxer = muxer)

    private class RecordingMuxer : VideoMuxerPort {
        val videoPtsUs = mutableListOf<Long>()
        val metadataPtsUs = mutableListOf<Long>()
        val metadataPayloads = mutableListOf<MetadataPayload>()
        var stopCount = 0
        var releaseCount = 0

        override fun writeVideoSample(ptsUs: Long, bytes: ByteArray) {
            videoPtsUs += ptsUs
        }

        override fun writeMetadataSample(ptsUs: Long, payload: MetadataPayload) {
            metadataPtsUs += ptsUs
            metadataPayloads += payload
        }

        override fun stop() {
            stopCount += 1
        }

        override fun release() {
            releaseCount += 1
        }
    }

    /** Produces each submitted frame's unit immediately. */
    private open class ImmediateEncoder : VideoEncoderPort {
        var submitCount = 0
        var releaseCount = 0
        var endOfStreamCount = 0
        protected val ready = ArrayDeque<EncodedUnit>()

        override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome {
            submitCount += 1
            ready.addLast(EncodedUnit(ptsUs, ByteArray(1)))
            return SubmitOutcome.Accepted
        }

        override fun drain(): List<EncodedUnit> {
            val units = ready.toList()
            ready.clear()
            return units
        }

        override fun signalEndOfStream() {
            endOfStreamCount += 1
        }

        override fun release() {
            releaseCount += 1
        }
    }

    /** Holds units back by [latency] submissions, releasing the rest at end of stream. */
    private class PipelinedEncoder(private val latency: Int) : VideoEncoderPort {
        private val queued = ArrayDeque<EncodedUnit>()
        private var endOfStream = false

        override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome {
            queued.addLast(EncodedUnit(ptsUs, ByteArray(1)))
            return SubmitOutcome.Accepted
        }

        override fun drain(): List<EncodedUnit> {
            val releasable = if (endOfStream) queued.size else (queued.size - latency).coerceAtLeast(0)
            val units = mutableListOf<EncodedUnit>()
            repeat(releasable) { units += queued.removeFirst() }
            return units
        }

        override fun signalEndOfStream() {
            endOfStream = true
        }

        override fun release() = Unit
    }

    /** Accepts submissions but never produces a unit. */
    private class SwallowingEncoder : VideoEncoderPort {
        override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome = SubmitOutcome.Accepted

        override fun drain(): List<EncodedUnit> = emptyList()

        override fun signalEndOfStream() = Unit

        override fun release() = Unit
    }

    private class DroppingEncoder(private val code: String) : VideoEncoderPort {
        override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome = SubmitOutcome.Dropped(code)

        override fun drain(): List<EncodedUnit> = emptyList()

        override fun signalEndOfStream() = Unit

        override fun release() = Unit
    }

    private class FailingEncoder(private val code: String) : VideoEncoderPort {
        override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome = SubmitOutcome.Failed(code)

        override fun drain(): List<EncodedUnit> = emptyList()

        override fun signalEndOfStream() = Unit

        override fun release() = Unit
    }

    private class FlakyEncoder(private val failAtIndex: Int) : VideoEncoderPort {
        private var seen = 0
        private val ready = ArrayDeque<EncodedUnit>()

        override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome {
            val index = seen
            seen += 1
            if (index == failAtIndex) return SubmitOutcome.Failed("encoder_fault")
            ready.addLast(EncodedUnit(ptsUs, ByteArray(1)))
            return SubmitOutcome.Accepted
        }

        override fun drain(): List<EncodedUnit> {
            val units = ready.toList()
            ready.clear()
            return units
        }

        override fun signalEndOfStream() = Unit

        override fun release() = Unit
    }


    private class SynchronizedRecordingMuxer : VideoMuxerPort {
        private val guard = Any()
        val videoPtsUs = mutableListOf<Long>()

        override fun writeVideoSample(ptsUs: Long, bytes: ByteArray) {
            synchronized(guard) { videoPtsUs += ptsUs }
        }

        override fun writeMetadataSample(ptsUs: Long, payload: MetadataPayload) = Unit

        override fun stop() = Unit

        override fun release() = Unit
    }

    private class ThreadSafeImmediateEncoder : VideoEncoderPort {
        private val guard = Any()
        private val ready = ArrayDeque<EncodedUnit>()

        override fun submit(frame: OwnedYuvFrame, ptsUs: Long): SubmitOutcome {
            synchronized(guard) { ready.addLast(EncodedUnit(ptsUs, ByteArray(1))) }
            return SubmitOutcome.Accepted
        }

        override fun drain(): List<EncodedUnit> = synchronized(guard) {
            val units = ready.toList()
            ready.clear()
            units
        }

        override fun signalEndOfStream() = Unit

        override fun release() = Unit
    }

    private companion object {
        fun frame(
            width: Int = 2,
            height: Int = 2,
            timestampNs: Long = 1_000_002_000L,
        ) = OwnedYuvFrame(
            yBytes = ByteArray(width * height) { 100.toByte() },
            yRowStride = width,
            yPixelStride = 1,
            uBytes = ByteArray(((width + 1) / 2) * ((height + 1) / 2)) { 128.toByte() },
            uRowStride = (width + 1) / 2,
            uPixelStride = 1,
            vBytes = ByteArray(((width + 1) / 2) * ((height + 1) / 2)) { 128.toByte() },
            vRowStride = (width + 1) / 2,
            vPixelStride = 1,
            width = width,
            height = height,
            rotation = FrameRotation.R0,
            mirrored = false,
            rawDataSpace = OwnedYuvFrame.ADMITTED_RAW_DATASPACE,
            colorStandard = YuvColorStandard.BT601,
            colorRange = YuvColorRange.FULL,
            timestampNs = timestampNs,
        )
    }
}
