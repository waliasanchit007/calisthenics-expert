package com.calisthenics.diagnostic.ui

import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.CapabilityId
import com.calisthenics.diagnostic.capability.CapabilityState
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.capability.requiredLiveCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveRunStateTest {
    @Test
    fun liveOffersOnlyTheThreeConfiguredInferenceRates() {
        assertEquals(listOf(10, 15, 30), LiveInferenceFps.entries.map { it.value })
    }

    @Test
    fun liveStartRequiresCameraPermissionAndAllRuntimeCapabilities() {
        val state = HarnessUiState(
            platform = "Android",
            capabilities = liveCapabilities().map { it.copy(state = CapabilityState.Available) },
            selectedMode = HarnessMode.LIVE,
            sessionActive = false,
        )

        assertFalse(state.canStart)
        assertEquals(listOf("operator_no_person_attestation_required"), state.disabledReasons)
        val attested = state.setOperatorAttestedNoPerson(true)
        assertTrue(attested.canStart)
        val denied = state.copy(
            capabilities = state.capabilities.map {
                if (it.id == CapabilityId.CAMERA_PERMISSION) {
                    it.copy(state = CapabilityState.Unavailable("camera_permission_denied"))
                } else it
            },
        )
        assertFalse(denied.canStart)
        assertEquals(
            listOf(
                "CAMERA_PERMISSION: camera_permission_denied",
                "operator_no_person_attestation_required",
            ),
            denied.disabledReasons,
        )
    }

    @Test
    fun liveNoPersonAttestationStartsFalseAndMustBeDeliberatelySetForEachRun() {
        val live = HarnessUiState(
            platform = "Android",
            capabilities = liveCapabilities().map { it.copy(state = CapabilityState.Available) },
            selectedMode = HarnessMode.LIVE,
            sessionActive = false,
        )

        assertFalse(live.operatorAttestedNoPerson)
        assertTrue(live.setOperatorAttestedNoPerson(true).operatorAttestedNoPerson)
        assertFalse(
            live.setOperatorAttestedNoPerson(true)
                .selectMode(HarnessMode.REPLAY)
                .selectMode(HarnessMode.LIVE)
                .operatorAttestedNoPerson,
        )
    }

    @Test
    fun activeOrFailedLiveRunDisablesStartAndPreservesReplayState() {
        val replay = HarnessUiState(
            platform = "Android",
            capabilities = liveCapabilities().map { it.copy(state = CapabilityState.Available) },
            selectedMode = HarnessMode.REPLAY,
            sessionActive = false,
        ).copy(runPhase = ReplayRunPhase.Running(2, 1, 1))
        val live = replay.selectMode(HarnessMode.LIVE).copy(sessionActive = true)

        assertFalse(live.canStart)
        assertEquals(ReplayRunPhase.Running(2, 1, 1), live.runPhase)
    }

    @Test
    fun liveRunStateCarriesCountersAndTypedCompletionReason() {
        val running = LiveRunPhase.Running(
            configuredInferenceFps = 15,
            cameraFramesReceived = 4,
            inferenceFramesSubmitted = 1,
            inferenceRecordsEmitted = 0,
            framesSkippedBeforeInference = 3,
            inferenceFailures = 0,
        )
        assertEquals(3L, running.framesSkippedBeforeInference)
        assertEquals("live_inference_stalled", LiveRunPhase.Failed("live_inference_stalled").reason)
    }

    private fun liveCapabilities(): List<Capability> = requiredLiveCapabilities().map { Capability(it, CapabilityState.Unknown("pending")) }
}
