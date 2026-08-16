package com.calisthenics.diagnostic.probe

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbeResultTest {
    private val complete = ProbeResult(
        deviceModel = "SM-S938B",
        osBuild = "BP1A.250305.019",
        cameraId = "0",
        timestampSource = "REALTIME",
        sourceDataspaceRaw = 146931712,
        sourceColorMatrix = "BT601",
        sourceColorRange = "LIMITED",
        pixelFormat = "YUV_420_888",
        cameraxVersion = "1.6.1",
        probeGitSha = "0123456789abcdef0123456789abcdef01234567",
        directoryFsyncSupported = true,
        atomicRenameSupported = true,
    )

    @Test
    fun serializesTheDeclaredCanonicalFieldNames() {
        val json = complete.toCanonicalJson()

        assertContains(json, "\"device_model\"")
        assertContains(json, "\"os_build\"")
        assertContains(json, "\"camera_id\"")
        assertContains(json, "\"timestamp_source\"")
        assertContains(json, "\"source_dataspace_raw\"")
        assertContains(json, "\"source_color_matrix\"")
        assertContains(json, "\"source_color_range\"")
        assertContains(json, "\"pixel_format\"")
        assertContains(json, "\"camerax_version\"")
        assertContains(json, "\"probe_git_sha\"")
        assertContains(json, "\"directory_fsync_supported\"")
        assertContains(json, "\"atomic_rename_supported\"")
    }

    @Test
    fun liveAdmissionRequiresRealtimeAndDecodedColourMetadata() {
        assertTrue(complete.isLiveAdmissible())
        assertFalse(complete.copy(timestampSource = "UNKNOWN").isLiveAdmissible())
        assertFalse(complete.copy(sourceColorMatrix = null).isLiveAdmissible())
        assertFalse(complete.copy(sourceColorRange = null).isLiveAdmissible())
    }

    @Test
    fun storageAdmissionRequiresBothDurabilityCapabilities() {
        assertTrue(complete.isStorageAdmissible())
        assertFalse(complete.copy(directoryFsyncSupported = false).isStorageAdmissible())
        assertFalse(complete.copy(atomicRenameSupported = false).isStorageAdmissible())
    }
}
