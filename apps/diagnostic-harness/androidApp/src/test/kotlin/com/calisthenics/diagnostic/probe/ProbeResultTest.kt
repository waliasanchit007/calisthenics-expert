package com.calisthenics.diagnostic.probe

import com.calisthenics.diagnostic.replay.CAMERA_DATASPACE_UNKNOWN
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
        underlyingImageAvailable = true,
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
        assertContains(json, "\"underlying_image_available\"")
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
    fun liveAdmissionRequiresAnUnderlyingImageBridgeThatProducedAnImage() {
        // CameraX's underlying-image accessor is nullable and experimental. A probe that read no
        // image never read a DataSpace either, so it observed nothing admission can rest on.
        assertFalse(complete.copy(underlyingImageAvailable = false).isLiveAdmissible())
    }

    @Test
    fun liveAdmissionRejectsTheUnknownRawDataSpaceSentinel() {
        assertFalse(complete.copy(sourceDataspaceRaw = CAMERA_DATASPACE_UNKNOWN).isLiveAdmissible())
        assertFalse(complete.copy(sourceDataspaceRaw = null).isLiveAdmissible())
    }

    @Test
    fun liveAdmissionIsDelegatedToTheSharedObservationRuleNotRestatedHere() {
        // The device-authorization rule already existed as four divergent copies once. The camera
        // admission rule has one home, in shared/commonMain, and this type only supplies the
        // observation it decides on.
        val observation = complete.toCameraObservation()

        assertEquals(
            CameraCapabilityObservation(
                cameraId = "0",
                timestampSource = "REALTIME",
                sourceDataspaceRaw = 146931712,
                sourceColorStandard = "BT601",
                sourceColorRange = "LIMITED",
                underlyingImageAvailable = true,
                pixelFormat = "YUV_420_888",
            ),
            observation,
        )
    }

    @Test
    fun storageAdmissionRequiresBothDurabilityCapabilities() {
        assertTrue(complete.isStorageAdmissible())
        assertFalse(complete.copy(directoryFsyncSupported = false).isStorageAdmissible())
        assertFalse(complete.copy(atomicRenameSupported = false).isStorageAdmissible())
    }
}
