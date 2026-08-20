package com.calisthenics.diagnostic.replay

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Emulator and physical-device observations are different evidence (design section 7), so the
 * label must be derived from platform build markers rather than declared by the caller. This is
 * the same derivation `DetectionTierReplayTopologyTest` uses privately for Task 4; Task 5's
 * five-run evidence needs the identical rule from a second call site (an androidApp instrumented
 * test in a different source set), so it is promoted here as a pure, host-tested function instead
 * of being duplicated by hand a second time.
 */
class DeviceClassTest {
    @Test
    fun classifiesKnownEmulatorFingerprintsAsEmulator() {
        assertEquals(
            "emulator",
            androidDeviceClassOf("google/sdk_gphone16k_arm64/emu64a16k:17/CP31.260623.009/1:user/dev-keys", "ranchu"),
        )
        assertEquals("emulator", androidDeviceClassOf("generic/goldfish/generic:13/TP1A/1:userdebug/test-keys", "goldfish"))
    }

    @Test
    fun classifiesASamsungFingerprintAsPhysicalDevice() {
        assertEquals(
            "physical_device",
            androidDeviceClassOf("samsung/e3qxxx/e3q:16/BP2A.250705.008/S938BXXU4BYH2:user/release-keys", "qcom"),
        )
    }
}
