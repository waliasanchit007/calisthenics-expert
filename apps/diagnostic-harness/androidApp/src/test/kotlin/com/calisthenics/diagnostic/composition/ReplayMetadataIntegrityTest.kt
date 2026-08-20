package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlin.test.assertEquals
import org.junit.Test

class ReplayMetadataIntegrityTest {
    @Test
    fun mediaReferenceDigestIsSha256OfExactReferencedMetadataBytes() {
        val metadataBytes = "{\"frame_digests\":[\"a\",\"b\"]}\n".encodeToByteArray()

        assertEquals(
            Sha256Digest.hex(metadataBytes),
            fixtureMetadataSha256(metadataBytes).value,
        )
    }
}
