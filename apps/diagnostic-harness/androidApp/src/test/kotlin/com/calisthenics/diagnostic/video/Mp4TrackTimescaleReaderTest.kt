package com.calisthenics.diagnostic.video

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class Mp4TrackTimescaleReaderTest {

    @Test
    fun readsEqualVideoAndMetadataTimescalesFromVersionZeroMdhdBoxes() {
        val result = Mp4TrackTimescaleReader.read(
            movie(
                track("vide", mdhdV0(90_000)),
                track("meta", mdhdV0(90_000)),
            ),
        )

        assertEquals(
            TrackTimescales(videoTicksPerSecond = 90_000L, metadataTicksPerSecond = 90_000L),
            result,
        )
    }

    @Test
    fun readsVersionOneMdhdTimescaleAtItsWiderFieldOffset() {
        val result = Mp4TrackTimescaleReader.read(
            movie(
                track("vide", mdhdV1(48_000)),
                track("meta", mdhdV1(48_000)),
            ),
        )

        assertEquals(48_000L, result.videoTicksPerSecond)
        assertEquals(48_000L, result.metadataTicksPerSecond)
    }

    @Test
    fun rejectsADeclaredBoxThatRunsPastItsParentBoundary() {
        val valid = movie(track("vide", mdhdV0(90_000)), track("meta", mdhdV0(90_000)))
        val truncated = valid.copyOf(valid.size - 1)

        assertFailsWith<IllegalArgumentException> { Mp4TrackTimescaleReader.read(truncated) }
    }

    @Test
    fun rejectsAMovieWithoutOneVideoAndOneMetadataTrack() {
        val missingMetadata = movie(track("vide", mdhdV0(90_000)))

        assertFailsWith<IllegalArgumentException> {
            Mp4TrackTimescaleReader.read(missingMetadata)
        }
    }

    @Test
    fun rejectsAZeroTimescale() {
        val movie = movie(track("vide", mdhdV0(0)), track("meta", mdhdV0(0)))

        assertFailsWith<IllegalArgumentException> { Mp4TrackTimescaleReader.read(movie) }
    }

    @Test
    fun rejectsInconsistentVideoAndMetadataTimescales() {
        val movie = movie(track("vide", mdhdV0(90_000)), track("meta", mdhdV0(1_000)))

        assertFailsWith<IllegalArgumentException> { Mp4TrackTimescaleReader.read(movie) }
    }

    @Test
    fun rejectsDuplicateTrackAuthorities() {
        val movie = movie(
            track("vide", mdhdV0(90_000)),
            track("vide", mdhdV0(90_000)),
            track("meta", mdhdV0(90_000)),
        )

        assertFailsWith<IllegalArgumentException> { Mp4TrackTimescaleReader.read(movie) }
    }

    private fun movie(vararg tracks: ByteArray): ByteArray = box("moov", concat(*tracks))

    private fun track(handler: String, mdhd: ByteArray): ByteArray =
        box("trak", box("mdia", concat(mdhd, hdlr(handler))))

    private fun mdhdV0(timescale: Int): ByteArray = box(
        "mdhd",
        ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt(1)
            .putInt(2)
            .putInt(timescale)
            .putInt(3)
            .putInt(0)
            .array(),
    )

    private fun mdhdV1(timescale: Int): ByteArray = box(
        "mdhd",
        ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN)
            .putInt(1 shl 24)
            .putLong(1)
            .putLong(2)
            .putInt(timescale)
            .putLong(3)
            .putInt(0)
            .array(),
    )

    private fun hdlr(handler: String): ByteArray = box(
        "hdlr",
        ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt(0)
            .put(handler.encodeToByteArray())
            .put(ByteArray(12))
            .array(),
    )

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + payload.size)
            .put(type.encodeToByteArray())
            .put(payload)
            .array()

    private fun concat(vararg chunks: ByteArray): ByteArray =
        ByteArray(chunks.sumOf { it.size }).also { result ->
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(result, destinationOffset = offset)
                offset += chunk.size
            }
        }
}
