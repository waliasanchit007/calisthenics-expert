package com.calisthenics.diagnostic.probe

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runners.JUnit4
import org.junit.runner.RunWith

@RunWith(JUnit4::class)
class CameraCapabilityProbeTest {
    @Test
    fun unavailableMetadataCannotBeAdmitted() {
        val result = ProbeResult(
            deviceModel = "test",
            osBuild = "test",
            cameraId = "0",
            timestampSource = "UNKNOWN",
            sourceDataspaceRaw = null,
            sourceColorMatrix = null,
            sourceColorRange = null,
            pixelFormat = "YUV_420_888",
            cameraxVersion = "1.6.1",
            probeGitSha = "test",
            directoryFsyncSupported = false,
            atomicRenameSupported = false,
        )
        assertFalse(result.isLiveAdmissible())
        assertFalse(result.isStorageAdmissible())
    }
}
