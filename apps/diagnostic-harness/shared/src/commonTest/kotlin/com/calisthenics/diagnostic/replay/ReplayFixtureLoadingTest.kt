package com.calisthenics.diagnostic.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The real replay UI path (Task 5) reads the same `fixture_metadata.json` shape the detection-tier
 * fixture is committed in, but from packaged app assets rather than staged device-test resources.
 * This decoder is the one piece of that parsing that is pure and platform-neutral, so it is pinned
 * here instead of being duplicated inside Android-only code no host test can reach.
 */
class ReplayFixtureLoadingTest {

    private val metadataJson = """
        {
          "frame_width": 4,
          "frame_height": 2,
          "frames": [
            {"frame_index": 0, "timestamp_ns": 100000000, "sha256": "aa", "path": "decoded/frame_0000.rgb24"},
            {"frame_index": 1, "timestamp_ns": 133333333, "sha256": "bb", "path": "decoded/frame_0001.rgb24"}
          ]
        }
    """.trimIndent()

    @Test
    fun decodesEveryDeclaredFrameInOrderUsingTheSuppliedPlaneReader() {
        val requestedPaths = mutableListOf<String>()
        val fixture = decodeReplayFixtureMetadata(metadataJson) { path ->
            requestedPaths += path
            byteArrayOf(path.length.toByte())
        }

        assertEquals(4, fixture.frameWidth)
        assertEquals(2, fixture.frameHeight)
        assertEquals(listOf(0L, 1L), fixture.declaredIndices)
        assertEquals(listOf("decoded/frame_0000.rgb24", "decoded/frame_0001.rgb24"), requestedPaths)
        assertEquals("aa", fixture.frames[0].declaredSha256)
        assertEquals(100000000L, fixture.frames[0].captureTimestampNs)
        assertEquals("bb", fixture.frames[1].declaredSha256)
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
