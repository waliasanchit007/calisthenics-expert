package com.calisthenics.diagnostic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calisthenics.diagnostic.capability.HarnessMode

@Composable
fun HarnessApp(
    state: HarnessUiState,
    onModeSelected: (HarnessMode) -> Unit = {},
    onFixtureSelected: (ReplayFixtureOption) -> Unit = {},
    onStart: () -> Unit = {},
) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Diagnostic harness", style = MaterialTheme.typography.headlineSmall)
        Text("Platform: ${state.platform}")
        Text("Mode")
        Row {
            HarnessMode.entries.forEach { mode ->
                Row {
                    RadioButton(selected = state.selectedMode == mode, onClick = { onModeSelected(mode) })
                    Text(mode.name)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (state.selectedMode == HarnessMode.REPLAY) {
            Text("Fixture", style = MaterialTheme.typography.titleMedium)
            ReplayFixtureOption.entries.forEach { option ->
                Row {
                    RadioButton(
                        selected = state.selectedFixture == option,
                        onClick = { onFixtureSelected(option) },
                    )
                    Text(option.label)
                }
            }
        }
        Text("Capabilities", style = MaterialTheme.typography.titleMedium)
        state.capabilities.forEach { capability -> Text("${capability.id}: ${capability.state}", Modifier.fillMaxWidth()) }
        if (!state.canStart) {
            Text("Start disabled", style = MaterialTheme.typography.titleMedium)
            state.disabledReasons.forEach { Text(it) }
        }
        Button(enabled = state.canStart, onClick = onStart) { Text("Start ${state.selectedMode.name}") }
        ReplayRunStatus(state.runPhase)
    }
}

@Composable
private fun ReplayRunStatus(phase: ReplayRunPhase) {
    when (phase) {
        is ReplayRunPhase.NotStarted -> Unit
        is ReplayRunPhase.Running -> {
            Text("Running", style = MaterialTheme.typography.titleMedium)
            Text("frames submitted: ${phase.framesSubmitted}")
            Text("records emitted: ${phase.framesEmitted}")
            Text("poses detected: ${phase.poseDetectedRecords}")
        }
        is ReplayRunPhase.Completed -> {
            Text("Completed", style = MaterialTheme.typography.titleMedium)
            Text("session: ${phase.summary.sessionId}")
            Text("published: ${phase.summary.published}")
            Text("records emitted: ${phase.summary.inferenceRecordsEmitted}")
            Text("poses detected: ${phase.summary.poseDetectedRecords}")
            Text("inference failures: ${phase.summary.inferenceFailures}")
        }
        is ReplayRunPhase.Failed -> {
            Text("Failed", style = MaterialTheme.typography.titleMedium)
            Text(phase.reason)
        }
    }
}
