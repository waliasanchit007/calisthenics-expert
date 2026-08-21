package com.calisthenics.diagnostic.probe

import android.hardware.DataSpace
import com.calisthenics.diagnostic.replay.CAMERA_DATASPACE_UNKNOWN
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins the `shared/commonMain` sentinel to the Android platform constant it mirrors.
 *
 * `commonMain` cannot see `android.hardware.DataSpace`, so the value the camera admission rule
 * rejects has to be restated there. This is the one place the restatement is checked against the
 * platform, so the gate cannot come to reject a value the device never reports — or, worse, accept
 * the one it does.
 */
class CameraProbeDataSpaceSentinelTest {
    @Test
    fun theSharedUnknownSentinelIsThePlatformUnknownDataSpace() {
        assertEquals(DataSpace.DATASPACE_UNKNOWN, CAMERA_DATASPACE_UNKNOWN)
    }
}
