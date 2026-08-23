package com.calisthenics.diagnostic.capability

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityTest {
    @Test
    fun unknownOrUnavailableMandatoryCapabilityDisablesReplay() {
        val available = replayCapabilities()

        assertTrue(canStart(HarnessMode.REPLAY, available, sessionActive = false))
        assertFalse(
            canStart(HarnessMode.REPLAY, available.replaceState(CapabilityId.MODEL_ASSET, CapabilityState.Unknown("model not probed")), sessionActive = false),
        )
        assertFalse(
            canStart(HarnessMode.REPLAY, available.replaceState(CapabilityId.STORAGE_DURABILITY, CapabilityState.Unavailable("atomic rename unavailable")), sessionActive = false),
        )
    }

    @Test
    fun liveRequiresCaptureMetadataAndThermalSupportInAdditionToReplay() {
        val capabilities = replayCapabilities() + listOf(
            Capability(CapabilityId.CAMERA_PERMISSION, CapabilityState.Available),
            Capability(CapabilityId.CAMERA_SOURCE, CapabilityState.Available),
            Capability(CapabilityId.TIMESTAMP_COMPARABILITY, CapabilityState.Available),
            Capability(CapabilityId.COLOR_METADATA, CapabilityState.Available),
            Capability(CapabilityId.THERMAL_API, CapabilityState.Available),
        )

        assertTrue(canStart(HarnessMode.LIVE, capabilities, sessionActive = false))
        assertFalse(
            canStart(
                HarnessMode.LIVE,
                capabilities.filterNot { it.id == CapabilityId.THERMAL_API },
                sessionActive = false,
            ),
        )
        assertFalse(
            canStart(HarnessMode.LIVE, capabilities.replaceState(CapabilityId.TIMESTAMP_COMPARABILITY, CapabilityState.Unknown("timestamp source not probed")), sessionActive = false),
        )
        assertFalse(
            canStart(HarnessMode.LIVE, capabilities.replaceState(CapabilityId.COLOR_METADATA, CapabilityState.Unavailable("colour matrix missing")), sessionActive = false),
        )
    }

    @Test
    fun iosHasNoAvailableCaptureBackendOrStorageCapabilities() {
        val ios = iosUnavailableCapabilities()

        assertFalse(ios.any { it.id in setOf(CapabilityId.CAMERA_PERMISSION, CapabilityId.CAMERA_SOURCE) && it.state is CapabilityState.Available })
        assertFalse(ios.any { it.id in setOf(CapabilityId.POSE_BACKEND, CapabilityId.MODEL_ASSET, CapabilityId.STORAGE_DURABILITY) && it.state is CapabilityState.Available })
    }

    @Test
    fun replayDoesNotBypassIosUnavailability() {
        assertFalse(canStart(HarnessMode.REPLAY, iosUnavailableCapabilities(), sessionActive = false))
    }

    @Test
    fun activeSessionCannotStartAgain() {
        assertFalse(canStart(HarnessMode.REPLAY, replayCapabilities(), sessionActive = true))
    }
}

private fun replayCapabilities(): List<Capability> = listOf(
    Capability(CapabilityId.MODEL_ASSET, CapabilityState.Available),
    Capability(CapabilityId.STORAGE_DURABILITY, CapabilityState.Available),
    Capability(CapabilityId.POSE_BACKEND, CapabilityState.Available),
)

private fun List<Capability>.replaceState(id: CapabilityId, state: CapabilityState): List<Capability> =
    map { if (it.id == id) it.copy(state = state) else it }
