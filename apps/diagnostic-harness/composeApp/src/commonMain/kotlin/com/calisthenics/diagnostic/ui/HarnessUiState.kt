package com.calisthenics.diagnostic.ui

import com.calisthenics.diagnostic.capability.Capability
import com.calisthenics.diagnostic.capability.HarnessMode
import com.calisthenics.diagnostic.capability.canStart
import com.calisthenics.diagnostic.capability.unavailableReasons

data class HarnessUiState(
    val platform: String,
    val capabilities: List<Capability>,
    val selectedMode: HarnessMode,
    val sessionActive: Boolean,
    val selectedFixture: ReplayFixtureOption = ReplayFixtureOption.DETECTION_TIER,
    val runPhase: ReplayRunPhase = ReplayRunPhase.NotStarted,
    val selectedLiveFps: LiveInferenceFps = LiveInferenceFps.FPS_30,
    val liveRunPhase: LiveRunPhase = LiveRunPhase.NotStarted,
    val operatorAttestedNoPerson: Boolean = false,
)
val HarnessUiState.canStart: Boolean
    get() = canStart(selectedMode, capabilities, sessionActive) &&
        (selectedMode != HarnessMode.LIVE || operatorAttestedNoPerson)

val HarnessUiState.disabledReasons: List<String>
    get() {
        val reasons = unavailableReasons(selectedMode, capabilities, sessionActive)
        return if (selectedMode == HarnessMode.LIVE && !operatorAttestedNoPerson) {
            reasons + "operator_no_person_attestation_required"
        } else {
            reasons
        }
    }

fun HarnessUiState.selectMode(mode: HarnessMode): HarnessUiState =
    copy(selectedMode = mode, operatorAttestedNoPerson = if (mode == HarnessMode.LIVE) false else operatorAttestedNoPerson)
fun HarnessUiState.selectFixture(option: ReplayFixtureOption): HarnessUiState = copy(selectedFixture = option)
fun HarnessUiState.selectLiveFps(fps: LiveInferenceFps): HarnessUiState = copy(selectedLiveFps = fps)
fun HarnessUiState.setOperatorAttestedNoPerson(attested: Boolean): HarnessUiState =
    copy(operatorAttestedNoPerson = attested)
