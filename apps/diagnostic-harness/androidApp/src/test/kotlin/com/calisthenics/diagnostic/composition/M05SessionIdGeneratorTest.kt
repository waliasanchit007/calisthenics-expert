package com.calisthenics.diagnostic.composition

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class M05SessionIdGeneratorTest {
    @Test
    fun generatorUsesReservedNamespaceWithExactlyFortyEightRandomSuffixBits() {
        val id = M05SessionIdGenerator.next().value

        assertTrue(id.matches(Regex("^00000000-0000-4000-8000-[0-9a-f]{12}$")))
        assertEquals(36, id.length)
    }
}
