package com.calisthenics.diagnostic.ui

import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.CapabilityId
import com.calisthenics.diagnostic.capability.CapabilityState
import com.calisthenics.diagnostic.capability.HarnessMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessUiStateTest {
    @Test
    fun disabledReasonsIdentifyUnknownAndUnavailableCapabilities() {
        val state = HarnessUiState(
            platform = "Android",
            capabilities = listOf(
                Capability(CapabilityId.MODEL_ASSET, CapabilityState.Unknown("model probe pending")),
                Capability(CapabilityId.STORAGE_DURABILITY, CapabilityState.Unavailable("fsync failed")),
                Capability(CapabilityId.POSE_BACKEND, CapabilityState.Available),
            ),
            selectedMode = HarnessMode.REPLAY,
            sessionActive = false,
        )

        assertFalse(state.canStart)
        assertEquals(
            listOf(
                "MODEL_ASSET: model probe pending",
                "STORAGE_DURABILITY: fsync failed",
            ),
            state.disabledReasons,
        )
    }

    @Test
    fun availableReplayHasNoDisabledReason() {
        val state = HarnessUiState(
            "Android",
            listOf(
                Capability(CapabilityId.MODEL_ASSET, CapabilityState.Available),
                Capability(CapabilityId.STORAGE_DURABILITY, CapabilityState.Available),
                Capability(CapabilityId.POSE_BACKEND, CapabilityState.Available),
            ),
            HarnessMode.REPLAY,
            false,
        )

        assertTrue(state.canStart)
        assertEquals(emptyList(), state.disabledReasons)
    }

    @Test
    fun selectingModeReturnsUpdatedStateWithoutStartingSession() {
        val state = HarnessUiState("Android", emptyList(), HarnessMode.REPLAY, false)

        val selected = state.selectMode(HarnessMode.LIVE)

        assertEquals(HarnessMode.LIVE, selected.selectedMode)
        assertFalse(selected.sessionActive)
        assertFalse(selected.canStart)
    }
}
