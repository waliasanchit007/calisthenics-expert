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
fun HarnessApp(state: HarnessUiState, onModeSelected: (HarnessMode) -> Unit = {}, onStart: () -> Unit = {}) {
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
        Text("Capabilities", style = MaterialTheme.typography.titleMedium)
        state.capabilities.forEach { capability -> Text("${capability.id}: ${capability.state}", Modifier.fillMaxWidth()) }
        if (!state.canStart) {
            Text("Start disabled", style = MaterialTheme.typography.titleMedium)
            state.disabledReasons.forEach { Text(it) }
        }
        Button(enabled = state.canStart, onClick = onStart) { Text("Start ${state.selectedMode.name}") }
    }
}
