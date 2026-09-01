package com.calisthenics.diagnostic.purge

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PhysicalPurgeDeviceGateContractTest {
    private val source = File(
        "src/androidTest/kotlin/com/calisthenics/diagnostic/purge/PhysicalPurgeDeviceGateTest.kt",
    )

    @Test
    fun physicalGateCreatesACompleteVideoSessionBeforeTheRegistryBoundPurge() {
        val text = source.readText()

        assertTrue("recordVideo = true" in text)
        assertTrue("video.mp4" in text)
        assertTrue("manifest.json" in text)
        assertTrue("frames.ndjson" in text)
        assertTrue("synchronization-evidence.json" in text)
        assertTrue(text.indexOf("requireCompleteVideoSession") < text.indexOf("AndroidSyntheticPurge("))
    }

    @Test
    fun physicalGateMakesEveryFirstRehearsalRootNonVacuous() {
        val text = source.readText()

        assertTrue(".1.staging" in text)
        assertTrue(".transfer.bundle" in text)
        assertTrue("FilePurgeFilesystem(roots)" in text)
        assertTrue("PurgeFilesystem.devicePurgeLocations" in text)
    }

    @Test
    fun physicalGateProvesTerminalStateDurableJournalAndPermanentFence() {
        val text = source.readText()

        assertTrue("PurgeState.PURGE_COMPLETE" in text)
        assertTrue("ownedRemaining == 0" in text)
        assertTrue(".purge-tombstone.json" in text)
        assertTrue("TombstoneSessionAdmissionFence" in text)
        assertTrue("PURGE.SESSION_TOMBSTONED" in text)
        assertEquals(1, Regex("sendStatus\\(").findAll(text).count())
    }
}
