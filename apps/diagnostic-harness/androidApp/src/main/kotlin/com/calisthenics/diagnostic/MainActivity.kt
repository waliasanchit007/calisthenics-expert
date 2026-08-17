package com.calisthenics.diagnostic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.CapabilityId
import com.calisthenics.diagnostic.capability.CapabilityState
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.composition.HarnessComposition
import com.calisthenics.diagnostic.probe.StorageCapabilityProbe
import com.calisthenics.diagnostic.ui.HarnessApp
import com.calisthenics.diagnostic.ui.HarnessUiState
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

            LaunchedEffect(Unit) {
                val probeResult = withContext(Dispatchers.IO) {
                    StorageCapabilityProbe(applicationContext, BuildConfig.PROBE_GIT_SHA).run()
                }
                val durabilityState = if (probeResult.isDurabilityAdmissible()) {
                    CapabilityState.Available
                } else {
                    CapabilityState.Unavailable("storage_capability_probe_failed")
                }
                state = state.copy(
                    capabilities = state.capabilities.map { capability ->
                        if (capability.id == CapabilityId.STORAGE_DURABILITY) {
                            capability.copy(state = durabilityState)
                        } else {
                            capability
                        }
                    },
                )
            }

            HarnessApp(
                state = state,
                onModeSelected = { state = state.selectMode(it) },
                onStart = {
                    state = state.copy(sessionActive = true)
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            HarnessComposition(applicationContext).runDeterministicSession()
                        }
                        state = state.copy(sessionActive = false)
                    }
                },
            )
        }
    }
}
