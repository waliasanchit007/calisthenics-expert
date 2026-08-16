package com.calisthenics.diagnostic.ui

import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.capability.canStart
import com.calisthenics.diagnostic.capability.unavailableReasons

data class HarnessUiState(val platform: String, val capabilities: List<Capability>, val selectedMode: HarnessMode, val sessionActive: Boolean)
val HarnessUiState.canStart: Boolean get() = canStart(selectedMode, capabilities, sessionActive)
val HarnessUiState.disabledReasons: List<String> get() = unavailableReasons(selectedMode, capabilities, sessionActive)

fun HarnessUiState.selectMode(mode: HarnessMode): HarnessUiState = copy(selectedMode = mode)
