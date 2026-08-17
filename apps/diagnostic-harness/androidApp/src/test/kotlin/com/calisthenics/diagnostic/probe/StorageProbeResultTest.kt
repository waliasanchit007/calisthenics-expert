package com.calisthenics.diagnostic.probe

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageProbeResultTest {
    private val complete = StorageProbeResult(
        deviceModel = "SM-S938B",
        osBuild = "BP1A.250305.019",
        filesystemType = "ext4",
        probeGitSha = "0123456789abcdef0123456789abcdef01234567",
        appendFlushFsyncSupported = true,
        directoryFsyncSupported = true,
        renameWithinFilesdirSupported = true,
        renameOntoPopulatedDestinationRejected = true,
        renameOntoEmptyDestinationRefusedByPrecheck = true,
    )

    @Test
    fun serializesTheDeclaredCanonicalFieldNames() {
        val json = complete.toCanonicalJson()

        assertContains(json, "\"device_model\"")
        assertContains(json, "\"os_build\"")
        assertContains(json, "\"filesystem_type\"")
        assertContains(json, "\"probe_git_sha\"")
        assertContains(json, "\"append_flush_fsync_supported\"")
        assertContains(json, "\"directory_fsync_supported\"")
        assertContains(json, "\"rename_within_filesdir_supported\"")
        assertContains(json, "\"rename_onto_populated_destination_rejected\"")
        assertContains(json, "\"rename_onto_empty_destination_refused_by_precheck\"")
    }

    @Test
    fun roundTripsThroughCanonicalJson() {
        assertEquals(complete, StorageProbeResult.fromCanonicalJson(complete.toCanonicalJson()))
    }

    @Test
    fun nullFilesystemTypeIsPreservedRatherThanFabricated() {
        val withoutFilesystemType = complete.copy(filesystemType = null)

        assertContains(withoutFilesystemType.toCanonicalJson(), "\"filesystem_type\":null")
        assertEquals(
            withoutFilesystemType,
            StorageProbeResult.fromCanonicalJson(withoutFilesystemType.toCanonicalJson()),
        )
    }

    @Test
    fun durabilityAdmissionRequiresEveryPrimitiveToSucceed() {
        assertTrue(complete.isDurabilityAdmissible())
        assertFalse(complete.copy(appendFlushFsyncSupported = false).isDurabilityAdmissible())
        assertFalse(complete.copy(directoryFsyncSupported = false).isDurabilityAdmissible())
        assertFalse(complete.copy(renameWithinFilesdirSupported = false).isDurabilityAdmissible())
        assertFalse(complete.copy(renameOntoPopulatedDestinationRejected = false).isDurabilityAdmissible())
        assertFalse(complete.copy(renameOntoEmptyDestinationRefusedByPrecheck = false).isDurabilityAdmissible())
    }
}
