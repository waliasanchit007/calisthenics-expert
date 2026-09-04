package com.calisthenics.diagnostic.camera

import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.YuvColorRange
import com.calisthenics.diagnostic.preprocessing.YuvColorStandard
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class CameraXFrameSourceOwnershipTest {
    @Test
    fun validationFailureClosesUnownedProxyExactlyOnce() {
        val proxy = FakeProxy()
        var failures = 0

        handleImageWithOwnership(
            proxy = proxy,
            validate = { error("unsupported DataSpace") },
            construct = { error("must not construct") },
            onFrame = {},
            onFailure = { failures += 1 },
        )

        assertEquals(1, proxy.closeCount)
        assertEquals(1, failures)
    }

    @Test
    fun everyPreTransferRejectionClosesTheProxyExactlyOnce() {
        listOf("underlying image unavailable", "unsupported format", "unsupported DataSpace").forEach { reason ->
            val proxy = FakeProxy()

            handleImageWithOwnership(
                proxy = proxy,
                validate = { error(reason) },
                construct = { error("must not construct") },
                onFrame = {},
                onFailure = {},
            )

            assertEquals(1, proxy.closeCount, reason)
        }
    }

    @Test
    fun constructionFailureClosesUnownedProxyExactlyOnce() {
        val proxy = FakeProxy()
        var failures = 0

        handleImageWithOwnership(
            proxy = proxy,
            validate = {},
            construct = { error("copy failed") },
            onFrame = {},
            onFailure = { failures += 1 },
        )

        assertEquals(1, proxy.closeCount)
        assertEquals(1, failures)
    }

    @Test
    fun admittedFrameTransfersCleanupToOwnedSourceExactlyOnce() {
        val proxy = FakeProxy()
        val owned = FakeOwnedImage()

        handleImageWithOwnership(
            proxy = proxy,
            validate = {},
            construct = { owned },
            onFrame = {},
            onFailure = { error("unexpected failure") },
        )

        assertEquals(0, proxy.closeCount)
        assertEquals(1, owned.closeCount)
    }

    @Test
    fun consumerFailureAfterTransferDoesNotCloseProxyTwice() {
        val proxy = FakeProxy()
        val owned = FakeOwnedImage()

        assertFailsWith<IllegalStateException> {
            handleImageWithOwnership(
                proxy = proxy,
                validate = {},
                construct = { owned },
                onFrame = { error("consumer failed") },
                onFailure = { throw it },
            )
        }

        assertEquals(0, proxy.closeCount)
        assertEquals(1, owned.closeCount)
    }

    private class FakeProxy : ImageProxyOwnership {
        var closeCount = 0

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeOwnedImage : OwnedImage {
        override val planes = listOf(
            FakePlane(byteArrayOf(1)),
            FakePlane(byteArrayOf(2)),
            FakePlane(byteArrayOf(3)),
        )
        override val width = 1
        override val height = 1
        override val rotation = FrameRotation.R0
        override val mirrored = false
        override val rawDataSpace = OwnedYuvFrame.ADMITTED_RAW_DATASPACE
        override val colorStandard = YuvColorStandard.BT601
        override val colorRange = YuvColorRange.FULL
        override val timestampNs = 0L
        override val observedAeTargetFpsRange: SourceFpsRange? = null
        var closeCount = 0

        override fun close() {
            closeCount += 1
        }
    }

    private class FakePlane(override val bytes: ByteArray) : SourcePlane {
        override val rowStride = 1
        override val pixelStride = 1
    }
}
