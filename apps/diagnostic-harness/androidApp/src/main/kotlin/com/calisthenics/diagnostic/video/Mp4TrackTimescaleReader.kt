package com.calisthenics.diagnostic.video

import java.nio.charset.StandardCharsets

data class TrackTimescales(
    val videoTicksPerSecond: Long,
    val metadataTicksPerSecond: Long,
)

/** Minimal, fail-closed ISO BMFF reader for the two track timescales this harness owns. */
object Mp4TrackTimescaleReader {

    private const val BOX_HEADER_BYTES = 8
    private const val EXTENDED_BOX_HEADER_BYTES = 16

    fun read(bytes: ByteArray): TrackTimescales {
        require(bytes.isNotEmpty()) { "container bytes are empty" }
        val movie = boxes(bytes, 0, bytes.size).singleOrNull { it.type == "moov" }
            ?: throw IllegalArgumentException("container must have exactly one moov box")
        val tracks = boxes(bytes, movie.payloadStart, movie.end)
            .filter { it.type == "trak" }
            .mapNotNull { readTrack(bytes, it) }

        val video = tracks.filter { it.handler == "vide" }
        val metadata = tracks.filter { it.handler == "meta" }
        require(video.size == 1) { "container must have exactly one video track" }
        require(metadata.size == 1) { "container must have exactly one metadata track" }
        val videoTimescale = video.single().ticksPerSecond
        val metadataTimescale = metadata.single().ticksPerSecond
        require(videoTimescale > 0L && metadataTimescale > 0L) {
            "track timescales must be positive"
        }
        require(videoTimescale == metadataTimescale) {
            "video and metadata track timescales must agree"
        }
        return TrackTimescales(videoTimescale, metadataTimescale)
    }

    private fun readTrack(bytes: ByteArray, track: Box): TrackObservation? {
        val media = boxes(bytes, track.payloadStart, track.end).singleOrNull { it.type == "mdia" }
            ?: throw IllegalArgumentException("track must have exactly one mdia box")
        val mediaChildren = boxes(bytes, media.payloadStart, media.end)
        val handlerBox = mediaChildren.singleOrNull { it.type == "hdlr" }
            ?: throw IllegalArgumentException("track must have exactly one hdlr box")
        val handler = readHandler(bytes, handlerBox)
        if (handler !in setOf("vide", "meta")) return null
        val mediaHeader = mediaChildren.singleOrNull { it.type == "mdhd" }
            ?: throw IllegalArgumentException("track must have exactly one mdhd box")
        return TrackObservation(handler, readTimescale(bytes, mediaHeader))
    }

    private fun readHandler(bytes: ByteArray, box: Box): String {
        require(box.end - box.payloadStart >= 12) { "truncated hdlr box" }
        return ascii(bytes, box.payloadStart + 8)
    }

    private fun readTimescale(bytes: ByteArray, box: Box): Long {
        require(box.end > box.payloadStart) { "truncated mdhd box" }
        return when (val version = bytes[box.payloadStart].toInt() and 0xff) {
            0 -> {
                require(box.end - box.payloadStart >= 16) { "truncated version-0 mdhd box" }
                unsignedInt(bytes, box.payloadStart + 12)
            }

            1 -> {
                require(box.end - box.payloadStart >= 24) { "truncated version-1 mdhd box" }
                unsignedInt(bytes, box.payloadStart + 20)
            }

            else -> throw IllegalArgumentException("unsupported mdhd version $version")
        }
    }

    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        require(start in 0..end && end <= bytes.size) { "invalid box boundary" }
        val result = mutableListOf<Box>()
        var cursor = start
        while (cursor < end) {
            require(end - cursor >= BOX_HEADER_BYTES) { "truncated box header" }
            val size32 = unsignedInt(bytes, cursor)
            val type = ascii(bytes, cursor + 4)
            val headerBytes: Int
            val declaredSize: Long
            when (size32) {
                0L -> {
                    headerBytes = BOX_HEADER_BYTES
                    declaredSize = (end - cursor).toLong()
                }

                1L -> {
                    require(end - cursor >= EXTENDED_BOX_HEADER_BYTES) {
                        "truncated extended box header"
                    }
                    headerBytes = EXTENDED_BOX_HEADER_BYTES
                    declaredSize = unsignedLong(bytes, cursor + 8)
                }

                else -> {
                    headerBytes = BOX_HEADER_BYTES
                    declaredSize = size32
                }
            }
            require(declaredSize >= headerBytes.toLong()) { "box $type is smaller than its header" }
            require(declaredSize <= (end - cursor).toLong()) {
                "box $type extends past its parent boundary"
            }
            val boxEnd = cursor + declaredSize.toInt()
            result += Box(type, cursor + headerBytes, boxEnd)
            cursor = boxEnd
        }
        require(cursor == end) { "box sequence did not end at its declared boundary" }
        return result
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size) { "truncated unsigned integer" }
        return ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
    }

    private fun unsignedLong(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 8 <= bytes.size) { "truncated unsigned long" }
        require(bytes[offset].toInt() and 0x80 == 0) { "box size exceeds signed host range" }
        var value = 0L
        repeat(8) { index ->
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
        return value
    }

    private fun ascii(bytes: ByteArray, offset: Int): String {
        require(offset >= 0 && offset + 4 <= bytes.size) { "truncated box type" }
        return String(bytes, offset, 4, StandardCharsets.US_ASCII)
    }

    private data class Box(
        val type: String,
        val payloadStart: Int,
        val end: Int,
    )

    private data class TrackObservation(
        val handler: String,
        val ticksPerSecond: Long,
    )
}
