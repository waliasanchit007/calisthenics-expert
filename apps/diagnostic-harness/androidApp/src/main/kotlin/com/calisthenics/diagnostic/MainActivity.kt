package com.calisthenics.diagnostic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.CapabilityId
import com.calisthenics.diagnostic.capability.CapabilityState
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.composition.RealReplayComposition
import com.calisthenics.diagnostic.composition.RealReplayFixtureSelection
import com.calisthenics.diagnostic.composition.RealReplayResult
import com.calisthenics.diagnostic.composition.SessionRunnerOwnership
import com.calisthenics.diagnostic.probe.ReplayCapabilityOutcome
import com.calisthenics.diagnostic.probe.ReplayCapabilityProbe
import com.calisthenics.diagnostic.probe.StorageCapabilityProbe
import com.calisthenics.diagnostic.probe.StorageProbeOutcome
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.ui.HarnessApp
import com.calisthenics.diagnostic.ui.HarnessUiState
import com.calisthenics.diagnostic.ui.ReplayFixtureOption
import com.calisthenics.diagnostic.ui.ReplayRunPhase
import com.calisthenics.diagnostic.ui.ReplayRunSummary
import com.calisthenics.diagnostic.ui.selectFixture
import com.calisthenics.diagnostic.ui.selectMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember {
                mutableStateOf(
                    HarnessUiState("Android", listOf(
                        Capability(CapabilityId.MODEL_ASSET, CapabilityState.Unknown("model probe pending")),
                        Capability(CapabilityId.STORAGE_DURABILITY, CapabilityState.Unknown("storage probe pending")),
                        Capability(CapabilityId.POSE_BACKEND, CapabilityState.Unknown("pose backend pending")),
                    ), HarnessMode.REPLAY, sessionActive = false),
                )
            }
            val coroutineScope = rememberCoroutineScope()
            // Bound to teardown so a run left non-terminal when this composable leaves composition
            // (activity finishing mid-run) is abandoned exactly once, per the Task 5 brief. Normal
            // completion already reaches a terminal state through SessionRunner.finalize() itself,
            // so abandon() here is a no-op in that case.
            val sessionRunnerOwnership = remember { SessionRunnerOwnership() }
            DisposableEffect(Unit) {
                onDispose { sessionRunnerOwnership.teardown() }
            }

            LaunchedEffect(Unit) {
                val probeOutcome = withContext(Dispatchers.IO) {
                    StorageCapabilityProbe(applicationContext, com.calisthenics.diagnostic.android.BuildConfig.PROBE_GIT_SHA).run()
                }
                val durabilityState = when (probeOutcome) {
                    is StorageProbeOutcome.Completed -> if (probeOutcome.result.isDurabilityAdmissible()) {
                        CapabilityState.Available
                    } else {
                        CapabilityState.Unavailable("storage_capability_probe_failed")
                    }
                    is StorageProbeOutcome.Unavailable -> CapabilityState.Unavailable(probeOutcome.reason)
                }
                val replayCapabilityState = when (val outcome = withContext(Dispatchers.IO) { ReplayCapabilityProbe(applicationContext).run() }) {
                    is ReplayCapabilityOutcome.Available -> CapabilityState.Available
                    is ReplayCapabilityOutcome.Unavailable -> CapabilityState.Unavailable(outcome.reason)
                }
                state = state.copy(
                    capabilities = state.capabilities.map { capability ->
                        when (capability.id) {
                            CapabilityId.STORAGE_DURABILITY -> capability.copy(state = durabilityState)
                            CapabilityId.MODEL_ASSET, CapabilityId.POSE_BACKEND -> capability.copy(state = replayCapabilityState)
                            else -> capability
                        }
                    },
                )
            }

            HarnessApp(
                state = state,
                onModeSelected = { state = state.selectMode(it) },
                onFixtureSelected = { state = state.selectFixture(it) },
                onStart = {
                    state = state.copy(sessionActive = true, runPhase = ReplayRunPhase.Running(0, 0, 0))
                    coroutineScope.launch {
                        val selection = when (state.selectedFixture) {
                            ReplayFixtureOption.DETECTION_TIER -> RealReplayFixtureSelection.DETECTION_TIER
                        }
                        val result = withContext(Dispatchers.IO) {
                            RealReplayComposition(applicationContext).run(
                                fixture = selection,
                                onSessionRunnerCreated = { runner -> sessionRunnerOwnership.register(runner) },
                                onProgress = { snapshot ->
                                    state = state.copy(
                                        runPhase = ReplayRunPhase.Running(
                                            framesSubmitted = snapshot.counts.inferenceFramesSubmitted,
                                            framesEmitted = snapshot.counts.inferenceRecordsEmitted,
                                            poseDetectedRecords = snapshot.counts.poseDetectedRecords,
                                        ),
                                    )
                                },
                            )
                        }
                        sessionRunnerOwnership.clear()
                        state = state.copy(sessionActive = false, runPhase = state.runPhase.toCompletionPhase(result))
                    }
                },
            )
        }
    }
}

private fun ReplayRunPhase.toCompletionPhase(result: RealReplayResult): ReplayRunPhase = when (result) {
    is RealReplayResult.CapabilityUnavailable -> ReplayRunPhase.Failed(result.reason)
    is RealReplayResult.Cancelled -> ReplayRunPhase.Failed(result.reason)
    is RealReplayResult.Completed -> {
        val counts = result.snapshot.counts
        ReplayRunPhase.Completed(
            ReplayRunSummary(
                sessionId = result.sessionId.value,
                published = result.finalization is FinalizationResult.Published,
                inferenceRecordsEmitted = counts.inferenceRecordsEmitted,
                poseDetectedRecords = counts.poseDetectedRecords,
                inferenceFailures = counts.inferenceFailures,
            ),
        )
    }
}
