package com.calisthenics.diagnostic.preview

import org.junit.Test
import kotlin.test.*

class ViewerResourceReleaseTest {
    @Test fun nativeReleaseWaitsForSourceQuiescenceAndRunsOnlyOnce() {
        val calls = mutableListOf<String>()
        lateinit var quiesced: () -> Unit
        releaseViewerResources({ callback -> calls += "stop"; quiesced = callback },
            { calls += "dispose" }, { calls += "shutdown" })
        assertEquals(listOf("stop"), calls)
        quiesced(); quiesced()
        assertEquals(listOf("stop", "dispose", "shutdown"), calls)
    }

    @Test fun syntheticSourceReleasesImmediatelyEvenIfNativeCloseThrows() {
        var shutdown = false
        assertFailsWith<IllegalStateException> {
            releaseViewerResources(null, { error("native close failure") }, { shutdown = true })
        }
        assertTrue(shutdown)
    }
}
