package com.calisthenics.diagnostic.video

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * What a published container actually contains, read back through the platform demuxer.
 *
 * This is the observation side of Task 4. The writer records what it *submitted*; this reads what
 * the container *holds*. Task 3's released validator derives one constant offset from every
 * (submitted, demuxed) pair, so both sequences must be reported in full — an endpoint-only
 * comparison cannot see a drift that develops in the middle of a run.
 */
data class DemuxedVideoEvidence(
    val videoPtsUs: List<Long>,
    val metadataPtsUs: List<Long>,
    val metadataPayloads: List<MetadataPayload>,
    val codec: String,
    val container: String,
    val byteLength: Long,
    val sha256: String,
)

object VideoDemuxVerifier {

    private const val METADATA_RECORD_BYTES = 24
    private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024

    /** Read [path] back through MediaExtractor and report exactly what it contains. */
    fun verify(path: String): DemuxedVideoEvidence {
        val file = File(path)
        require(file.isFile) { "no container at $path" }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)

            var videoTrack = -1
            var metadataTrack = -1
            var codec = ""
            val discovered = mutableListOf<String>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                discovered += mime
                when {
                    mime.startsWith("video/") -> {
                        videoTrack = index
                        codec = mime
                    }

                    mime == MediaCodecVideoWriter.METADATA_MIME -> metadataTrack = index
                }
            }
            // Name what was actually found. A bare "no video track" says nothing about whether
            // the muxer wrote the wrong MIME, one track, or none.
            require(videoTrack >= 0) {
                "container has no video track; tracks=" + discovered.joinToString(",")
            }
            require(metadataTrack >= 0) {
                "container has no metadata track; tracks=" + discovered.joinToString(",")
            }

            val videoPtsUs = readPresentationTimestamps(extractor, videoTrack)
            val metadataPtsUs = mutableListOf<Long>()
            val metadataPayloads = readMetadata(extractor, metadataTrack, metadataPtsUs)

            return DemuxedVideoEvidence(
                videoPtsUs = videoPtsUs,
                metadataPtsUs = metadataPtsUs.toList(),
                metadataPayloads = metadataPayloads,
                codec = codec,
                container = "video/mp4",
                byteLength = file.length(),
                sha256 = sha256Of(file),
            )
        } finally {
            extractor.release()
        }
    }

    private fun readPresentationTimestamps(extractor: MediaExtractor, track: Int): List<Long> {
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
        val timestamps = mutableListOf<Long>()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            timestamps += extractor.sampleTime
            extractor.advance()
        }
        extractor.unselectTrack(track)
        return timestamps
    }

    private fun readMetadata(
        extractor: MediaExtractor,
        track: Int,
        timestampsOut: MutableList<Long>,
    ): List<MetadataPayload> {
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
        val payloads = mutableListOf<MetadataPayload>()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            require(size == METADATA_RECORD_BYTES) {
                "metadata sample is $size bytes, expected $METADATA_RECORD_BYTES"
            }
            buffer.position(0)
            buffer.limit(size)
            val record = buffer.slice().order(ByteOrder.BIG_ENDIAN)
            payloads += MetadataPayload(
                frameIndex = record.long,
                rebasedCaptureNs = record.long,
                submittedPtsUs = record.long,
            )
            timestampsOut += extractor.sampleTime
            buffer.clear()
            extractor.advance()
        }
        extractor.unselectTrack(track)
        return payloads
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(block)
                if (read <= 0) break
                digest.update(block, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
