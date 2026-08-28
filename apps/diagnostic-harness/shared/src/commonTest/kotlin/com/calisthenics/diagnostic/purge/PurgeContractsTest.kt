package com.calisthenics.diagnostic.purge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PurgeContractsTest {
    @Test
    fun reservedSyntheticSessionIdAcceptsOnlyTheExactM05Namespace() {
        assertEquals(
            "00000000-0000-4000-8000-000000000001",
            PurgeSessionId.parseOrNull("00000000-0000-4000-8000-000000000001")?.value,
        )
        assertNull(PurgeSessionId.parseOrNull("00000000-0000-4000-8001-000000000001"))
        assertNull(PurgeSessionId.parseOrNull("10000000-0000-4000-8000-000000000001"))
        assertNull(PurgeSessionId.parseOrNull("00000000-0000-4000-8000-00000000001"))
    }
}
