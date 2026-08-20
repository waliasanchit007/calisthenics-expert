package com.calisthenics.diagnostic.ui

import com.calisthenics.diagnostic.capability.HarnessMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Task 5: the common Compose fixture-selection/run/completion surface. `HarnessUiState` gained
 * [ReplayFixtureOption] selection and a [ReplayRunPhase] so `HarnessApp` can render the design
 * section 12 "active session monitor" and "completion/export summary" states without any
 * Android/MediaPipe-specific type leaking into `commonMain` UI code.
 */
class ReplayRunStateTest {

    private fun baseState() = HarnessUiState(
        platform = "Android",
        capabilities = emptyList(),
        selectedMode = HarnessMode.REPLAY,
        sessionActive = false,
    )

    @Test
    fun defaultsToTheDetectionTierFixtureAndNoRunYet() {
        val state = baseState()
        assertEquals(ReplayFixtureOption.DETECTION_TIER, state.selectedFixture)
        assertEquals(ReplayRunPhase.NotStarted, state.runPhase)
    }

    @Test
    fun selectingAFixtureReturnsUpdatedStateWithoutTouchingRunPhase() {
        val state = baseState().copy(runPhase = ReplayRunPhase.Running(1, 0, 0))

        val selected = state.selectFixture(ReplayFixtureOption.DETECTION_TIER)

        assertEquals(ReplayFixtureOption.DETECTION_TIER, selected.selectedFixture)
        assertEquals(ReplayRunPhase.Running(1, 0, 0), selected.runPhase)
    }

    @Test
    fun runningPhaseCarriesLiveCounters() {
        val phase = ReplayRunPhase.Running(framesSubmitted = 3, framesEmitted = 2, poseDetectedRecords = 1)
        assertEquals(3, phase.framesSubmitted)
        assertEquals(2, phase.framesEmitted)
        assertEquals(1, phase.poseDetectedRecords)
    }

    @Test
    fun completedPhaseCarriesAPublishedSummary() {
        val summary = ReplayRunSummary(
            sessionId = "5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c",
            published = true,
            inferenceRecordsEmitted = 6,
            poseDetectedRecords = 6,
            inferenceFailures = 0,
        )
        val phase = ReplayRunPhase.Completed(summary)
        assertIs<ReplayRunPhase.Completed>(phase)
        assertEquals(summary, phase.summary)
    }

    @Test
    fun failedPhaseCarriesASanitizedReason() {
        val phase = ReplayRunPhase.Failed("model_provenance_unreadable")
        assertEquals("model_provenance_unreadable", phase.reason)
    }
}
