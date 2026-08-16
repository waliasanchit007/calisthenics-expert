package com.calisthenics.diagnostic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.CapabilityId
import com.calisthenics.diagnostic.capability.CapabilityState
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.ui.HarnessApp
import com.calisthenics.diagnostic.ui.HarnessUiState
import com.calisthenics.diagnostic.ui.selectMode

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
            HarnessApp(state = state, onModeSelected = { state = state.selectMode(it) })
        }
    }
}
