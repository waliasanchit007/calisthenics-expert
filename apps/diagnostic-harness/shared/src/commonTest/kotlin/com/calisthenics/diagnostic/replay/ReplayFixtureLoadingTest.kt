package com.calisthenics.diagnostic.replay

import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The real replay UI path (Task 5) reads the same `fixture_metadata.json` shape the detection-tier
 * fixture is committed in, but from packaged app assets rather than staged device-test resources.
 * This decoder is the one piece of that parsing that is pure and platform-neutral, so it is pinned
 * here instead of being duplicated inside Android-only code no host test can reach.
 *
 * It is also the trust boundary for fixture bytes. The declared `sha256` is an untrusted string in
 * a JSON file; the plane behind it has passed through Gradle staging, an APK asset merge, and an
 * `adb install` by the time a device reads it. The decoder therefore verifies the bytes it was
 * handed and refuses the fixture otherwise, so nothing downstream can record digests for pixels it
 * did not actually replay.
 */
class ReplayFixtureLoadingTest {

    private fun plane(path: String): ByteArray = path.encodeToByteArray()

    private val firstPath = "decoded/frame_0000.rgb24"
    private val secondPath = "decoded/frame_0001.rgb24"
    private val firstSha = Sha256Digest.hex(plane(firstPath))
    private val secondSha = Sha256Digest.hex(plane(secondPath))

    private fun metadataJson(
        firstDeclaredSha: String = firstSha,
        secondDeclaredSha: String = secondSha,
    ) = """
        {
          "frame_width": 4,
          "frame_height": 2,
          "frames": [
            {"frame_index": 0, "timestamp_ns": 100000000, "sha256": "$firstDeclaredSha", "path": "$firstPath"},
            {"frame_index": 1, "timestamp_ns": 133333333, "sha256": "$secondDeclaredSha", "path": "$secondPath"}
          ]
        }
    """.trimIndent()

    @Test
    fun decodesEveryDeclaredFrameInOrderUsingTheSuppliedPlaneReader() {
        val requestedPaths = mutableListOf<String>()
        val fixture = decodeReplayFixtureMetadata(metadataJson()) { path ->
            requestedPaths += path
            plane(path)
        }

        assertEquals(4, fixture.frameWidth)
        assertEquals(2, fixture.frameHeight)
        assertEquals(listOf(0L, 1L), fixture.declaredIndices)
        assertEquals(listOf(firstPath, secondPath), requestedPaths)
        assertEquals(firstSha, fixture.frames[0].declaredSha256)
        assertEquals(100000000L, fixture.frames[0].captureTimestampNs)
        assertEquals(secondSha, fixture.frames[1].declaredSha256)
    }

    @Test
    fun refusesAPlaneWhoseBytesDoNotMatchItsDeclaredDigest() {
        // The exact substitution a corrupted staging step, a truncated asset merge, or a swapped
        // fixture directory produces: the metadata is intact and the bytes are not.
        val error = assertFailsWith<ReplayFixtureLoadingException> {
            decodeReplayFixtureMetadata(metadataJson()) { path ->
                if (path == secondPath) plane("some other plane entirely") else plane(path)
            }
        }
        assertTrue(
            error.message!!.contains("frame 1") && error.message!!.contains(secondSha),
            "the failure must name the offending frame and both digests: ${error.message}",
        )
    }

    @Test
    fun refusesAPlaneTruncatedByASingleByte() {
        // A prefix or length-only comparison would accept this; only hashing the actual bytes
        // rejects it.
        assertFailsWith<ReplayFixtureLoadingException> {
            decodeReplayFixtureMetadata(metadataJson()) { path -> plane(path).dropLast(1).toByteArray() }
        }
    }

    @Test
    fun exposesTheDigestComputedFromTheActualBytesForEveryFrame() {
        val fixture = decodeReplayFixtureMetadata(metadataJson()) { path -> plane(path) }

        assertEquals(
            listOf(Sha256Digest.hex(plane(firstPath)), Sha256Digest.hex(plane(secondPath))),
            fixture.frames.map { it.actualSha256 },
        )
    }

    @Test
    fun refusesMetadataThatIsNotAJsonObject() {
        assertFailsWith<ReplayFixtureLoadingException> {
            decodeReplayFixtureMetadata("[]") { byteArrayOf() }
        }
    }

    @Test
    fun refusesMetadataMissingTheFramesArray() {
        assertFailsWith<ReplayFixtureLoadingException> {
            decodeReplayFixtureMetadata("""{"frame_width": 4, "frame_height": 2}""") { byteArrayOf() }
        }
    }
}
