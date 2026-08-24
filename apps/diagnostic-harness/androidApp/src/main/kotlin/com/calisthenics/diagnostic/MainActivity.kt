package com.calisthenics.diagnostic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.CapabilityId
import com.calisthenics.diagnostic.capability.CapabilityState
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.capability.requiredLiveCapabilities
import com.calisthenics.diagnostic.composition.LiveCameraCompletion
import com.calisthenics.diagnostic.composition.LiveCameraComposition
import com.calisthenics.diagnostic.composition.LiveCameraStartResult
import com.calisthenics.diagnostic.composition.LiveInternalFailure
import com.calisthenics.diagnostic.composition.RealReplayComposition
import com.calisthenics.diagnostic.composition.RealReplayFixtureSelection
import com.calisthenics.diagnostic.composition.RealReplayResult
import com.calisthenics.diagnostic.composition.SessionRunnerOwnership
import com.calisthenics.diagnostic.probe.CameraCapabilityProbe
import com.calisthenics.diagnostic.probe.ReplayCapabilityOutcome
import com.calisthenics.diagnostic.probe.ReplayCapabilityProbe
import com.calisthenics.diagnostic.probe.StorageCapabilityProbe
import com.calisthenics.diagnostic.probe.StorageProbeOutcome
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SessionRunnerSnapshot
import com.calisthenics.diagnostic.thermal.AndroidLiveThermalProbe
import com.calisthenics.diagnostic.ui.HarnessApp
import com.calisthenics.diagnostic.ui.HarnessUiState
import com.calisthenics.diagnostic.ui.LiveRunPhase
import com.calisthenics.diagnostic.ui.ReplayFixtureOption
import com.calisthenics.diagnostic.ui.ReplayRunPhase
import com.calisthenics.diagnostic.ui.ReplayRunSummary
import com.calisthenics.diagnostic.ui.selectFixture
import com.calisthenics.diagnostic.ui.selectLiveFps
import com.calisthenics.diagnostic.ui.selectMode
import com.calisthenics.diagnostic.ui.setOperatorAttestedNoPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember { mutableStateOf(initialState()) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                state = state.copy(
                    capabilities = state.capabilities.map { capability ->
                        if (capability.id == CapabilityId.CAMERA_PERMISSION) {
                            capability.copy(
                                state = if (granted) CapabilityState.Available
                                else CapabilityState.Unavailable("camera_permission_denied"),
                            )
                        } else capability
                    },
                )
            }
            val coroutineScope = rememberCoroutineScope()
            var cameraObservation by remember { mutableStateOf<CameraCapabilityObservation?>(null) }
            var liveComposition by remember { mutableStateOf<LiveCameraComposition?>(null) }
            val sessionRunnerOwnership = remember { SessionRunnerOwnership() }
            val latestLiveComposition by rememberUpdatedState(liveComposition)
            val permissionGranted = state.capabilities.any {
                it.id == CapabilityId.CAMERA_PERMISSION && it.state is CapabilityState.Available
            }

            DisposableEffect(Unit) {
                onDispose {
                    if (latestLiveComposition != null) {
                        latestLiveComposition?.abandon()
                    } else {
                        sessionRunnerOwnership.teardown()
                    }
                }
            }

            LaunchedEffect(permissionGranted) {
                if (!permissionGranted) return@LaunchedEffect
                val cameraProbe = withContext(Dispatchers.IO) {
                    runCatching {
                        CameraCapabilityProbe(
                            context = applicationContext,
                            lifecycleOwner = this@MainActivity,
                            probeGitSha = com.calisthenics.diagnostic.android.BuildConfig.PROBE_GIT_SHA,
                        ).run()
                    }
                }
                cameraProbe.onSuccess { result ->
                    cameraObservation = result.toCameraObservation()
                    val exact = result.cameraId == "0" && result.pixelFormat == "YUV_420_888" && result.isLiveAdmissible()
                    state = state.copy(capabilities = state.capabilities.map { capability ->
                        when (capability.id) {
                            CapabilityId.CAMERA_SOURCE -> capability.copy(state = if (exact) CapabilityState.Available else CapabilityState.Unavailable("camera_capability_not_admitted"))
                            CapabilityId.TIMESTAMP_COMPARABILITY -> capability.copy(state = if (result.timestampSource == "REALTIME") CapabilityState.Available else CapabilityState.Unavailable("timestamp_source_not_realtime"))
                            CapabilityId.COLOR_METADATA -> capability.copy(state = if (result.isLiveAdmissible()) CapabilityState.Available else CapabilityState.Unavailable("colour_metadata_not_admitted"))
                            else -> capability
                        }
                    })
                }.onFailure { error ->
                    state = state.copy(capabilities = state.capabilities.map { capability ->
                        if (capability.id in setOf(CapabilityId.CAMERA_SOURCE, CapabilityId.TIMESTAMP_COMPARABILITY, CapabilityId.COLOR_METADATA)) {
                            capability.copy(state = CapabilityState.Unavailable(error.message ?: "camera_capability_probe_failed"))
                        } else capability
                    })
                }
            }

            LaunchedEffect(Unit) {
                val storage = withContext(Dispatchers.IO) {
                    StorageCapabilityProbe(applicationContext, com.calisthenics.diagnostic.android.BuildConfig.PROBE_GIT_SHA).run()
                }
                val durabilityState = when (storage) {
                    is StorageProbeOutcome.Completed -> if (storage.result.isDurabilityAdmissible()) CapabilityState.Available else CapabilityState.Unavailable("storage_capability_probe_failed")
                    is StorageProbeOutcome.Unavailable -> CapabilityState.Unavailable(storage.reason)
                }
                val replay = withContext(Dispatchers.IO) { ReplayCapabilityProbe(applicationContext).run() }
                val replayState = when (replay) {
                    is ReplayCapabilityOutcome.Available -> CapabilityState.Available
                    is ReplayCapabilityOutcome.Unavailable -> CapabilityState.Unavailable(replay.reason)
                }
                state = state.copy(capabilities = state.capabilities.map { capability ->
                    when (capability.id) {
                        CapabilityId.STORAGE_DURABILITY -> capability.copy(state = durabilityState)
                        CapabilityId.MODEL_ASSET, CapabilityId.POSE_BACKEND -> capability.copy(state = replayState)
                        else -> capability
                    }
                })
            }

            HarnessApp(
                state = state,
                onModeSelected = { state = state.selectMode(it) },
                onFixtureSelected = { state = state.selectFixture(it) },
                onLiveFpsSelected = { state = state.selectLiveFps(it) },
                onOperatorAttestationChanged = { state = state.setOperatorAttestedNoPerson(it) },
                onRequestCameraPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onStop = {
                    val composition = liveComposition
                    coroutineScope.launch(Dispatchers.IO) {
                        composition?.stop()
                    }
                },
                onStart = {
                    state = state.copy(sessionActive = true)
                    coroutineScope.launch {
                        if (state.selectedMode == HarnessMode.REPLAY) {
                            state = state.copy(runPhase = ReplayRunPhase.Running(0, 0, 0))
                            val selection = when (state.selectedFixture) {
                                ReplayFixtureOption.DETECTION_TIER -> RealReplayFixtureSelection.DETECTION_TIER
                            }
                            val result = withContext(Dispatchers.IO) {
                                RealReplayComposition(applicationContext).run(
                                    fixture = selection,
                                    onSessionRunnerCreated = { runner -> sessionRunnerOwnership.register(runner) },
                                    onProgress = { snapshot -> state = state.copy(runPhase = snapshot.toReplayRunning()) },
                                )
                            }
                            sessionRunnerOwnership.clear()
                            state = state.copy(sessionActive = false, runPhase = state.runPhase.toCompletionPhase(result))
                        } else {
                            state = state.copy(liveRunPhase = LiveRunPhase.Running(state.selectedLiveFps.value, 0, 0, 0, 0, 0))
                            val observation = cameraObservation
                            if (observation == null) {
                                state = state.copy(sessionActive = false, liveRunPhase = LiveRunPhase.Failed("camera_capability_not_admitted"))
                                return@launch
                            }
                            val composition = runCatching {
                                LiveCameraComposition.fromAndroid(
                                    context = applicationContext,
                                    lifecycleOwner = this@MainActivity,
                                    cameraCapability = observation,
                                    permissionGranted = permissionGranted,
                                    onSessionRunnerCreated = { runner -> sessionRunnerOwnership.register(runner) },
                                    onCompletion = { completion ->
                                        state = state.copy(
                                            sessionActive = false,
                                            liveRunPhase = completion.toLivePhase(),
                                        )
                                        if (liveComposition?.completion()?.sessionId == completion.sessionId) {
                                            liveComposition = null
                                        }
                                        sessionRunnerOwnership.clear()
                                    },
                                )
                            }.getOrElse { error ->
                                state = state.copy(sessionActive = false, liveRunPhase = LiveRunPhase.Failed(error.message ?: "live_initialization_failed"))
                                return@launch
                            }
                            liveComposition = composition
                            val operatorAttestedNoPerson = state.operatorAttestedNoPerson
                            when (val started = withContext(Dispatchers.IO) {
                                composition.start(
                                    state.selectedLiveFps.value,
                                    operatorAttestedNoPerson = operatorAttestedNoPerson,
                                ) { snapshot ->
                                    state = state.copy(
                                        liveRunPhase = snapshot.toLiveRunning(
                                            fps = state.selectedLiveFps.value,
                                            composition = composition,
                                        ),
                                    )
                                }
                            }) {
                                is LiveCameraStartResult.Unavailable -> {
                                    composition.abandon()
                                    if (liveComposition === composition) liveComposition = null
                                    sessionRunnerOwnership.clear()
                                    state = state.copy(sessionActive = false, liveRunPhase = LiveRunPhase.Failed(started.reason.code))
                                }
                                is LiveCameraStartResult.Started -> Unit
                            }
                        }
                    }
                },
            )
        }
    }

    private fun initialState(): HarnessUiState {
        val permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val thermalProbe = AndroidLiveThermalProbe.from(applicationContext)
        return HarnessUiState(
            platform = "Android",
            capabilities = requiredLiveCapabilities().map { id ->
                Capability(
                    id,
                    when (id) {
                        CapabilityId.CAMERA_PERMISSION -> if (permissionGranted) {
                            CapabilityState.Available
                        } else {
                            CapabilityState.Unavailable("camera_permission_denied")
                        }

                        CapabilityId.THERMAL_API -> if (thermalProbe.available) {
                            CapabilityState.Available
                        } else {
                            CapabilityState.Unavailable(thermalProbe.unavailableReason ?: "thermal_api_unavailable")
                        }

                        else -> CapabilityState.Unknown("capability probe pending")
                    },
                )
            },
            selectedMode = HarnessMode.REPLAY,
            sessionActive = false,
        )
    }
}

private fun SessionRunnerSnapshot.toReplayRunning() = ReplayRunPhase.Running(counts.inferenceFramesSubmitted, counts.inferenceRecordsEmitted, counts.poseDetectedRecords)

private fun SessionRunnerSnapshot.toLiveRunning(
    fps: Int,
    composition: LiveCameraComposition,
): LiveRunPhase.Running {
    val latestRecord = records.lastOrNull()
    val latestThermal = composition.latestThermalObservation()
    return LiveRunPhase.Running(
        configuredInferenceFps = fps,
        cameraFramesReceived = counts.cameraFramesReceived,
        inferenceFramesSubmitted = counts.inferenceFramesSubmitted,
        inferenceRecordsEmitted = counts.inferenceRecordsEmitted,
        framesSkippedBeforeInference = counts.framesSkippedBeforeInference,
        inferenceFailures = counts.inferenceFailures,
        configuredCameraFps = 30,
        deliveredCallbackCount = counts.cameraFramesReceived,
        latestInferenceLatencyNs = latestRecord?.let { it.inferenceEndNs - it.inferenceStartNs },
        latestThermalStatus = latestThermal?.status,
        latestThermalObservedAtNs = latestThermal?.tMonotonicNs,
        thermalUnavailableReason = null,
    )
}

internal fun liveCaptureFailureCodeOr(internalFailure: LiveInternalFailure?, fallback: String?): String? =
    internalFailure?.code ?: fallback

internal fun LiveCameraCompletion.toLivePhase(): LiveRunPhase = when (val result = finalization) {
    is FinalizationResult.Published -> internalFailure?.let { LiveRunPhase.Failed(it.code) }
        ?: LiveRunPhase.Completed(sessionId.value, true, snapshot.counts.inferenceRecordsEmitted, snapshot.counts.framesSkippedBeforeInference, snapshot.counts.inferenceFailures)
    is FinalizationResult.Failed -> LiveRunPhase.Failed(internalFailure?.code ?: result.failureCode)
    is FinalizationResult.Cancelled -> LiveRunPhase.Failed(result.reason)
}

private fun ReplayRunPhase.toCompletionPhase(result: RealReplayResult): ReplayRunPhase = when (result) {
    is RealReplayResult.CapabilityUnavailable -> ReplayRunPhase.Failed(result.reason)
    is RealReplayResult.Cancelled -> ReplayRunPhase.Failed(result.reason)
    is RealReplayResult.Completed -> {
        val counts = result.snapshot.counts
        ReplayRunPhase.Completed(ReplayRunSummary(result.sessionId.value, result.finalization is FinalizationResult.Published, counts.inferenceRecordsEmitted, counts.poseDetectedRecords, counts.inferenceFailures))
    }
}
