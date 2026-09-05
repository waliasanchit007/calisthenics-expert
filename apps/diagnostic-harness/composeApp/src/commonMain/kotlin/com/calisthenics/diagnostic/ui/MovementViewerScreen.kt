package com.calisthenics.diagnostic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.viewer.ViewerGeometry

/** Display observations only. There are no exercise-specific states or quality thresholds. */
data class MovementViewerUiState(
    val active: Boolean = false,
    val message: String = "Ready to explore",
    val source: String = "Packaged synthetic figure",
    val width: Int = 1,
    val height: Int = 1,
    val landmarks: List<Landmark> = emptyList(),
    val submitted: Int = 0,
    val completed: Int = 0,
    val skipped: Int = 0,
    val cameraPermission: Boolean = false,
    val attestedCameraScope: Boolean = false,
)

@Composable
fun MovementViewerScreen(
    state: MovementViewerUiState,
    image: ImageBitmap?,
    onSynthetic: () -> Unit,
    onCamera: () -> Unit,
    onStop: () -> Unit,
    onPermission: () -> Unit,
    onAttestation: (Boolean) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF72E8BF))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CALISTHENICS • EXPLORER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("See your movement", style = MaterialTheme.typography.headlineLarge)
                Text("Exercise-independent pose viewer · experimental", style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF15232A)), contentAlignment = Alignment.Center) {
                    if (image == null) {
                        Text(state.message, color = Color(0xFFB8C7CD))
                    } else {
                        Image(image, "${state.source} paired inference frame", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Canvas(Modifier.fillMaxSize()) {
                            val geometry = ViewerGeometry(state.width, state.height)
                            val points = state.landmarks.mapNotNull { landmark ->
                                geometry.modelToSource(landmark.x, landmark.y)?.let { normalized ->
                                    val pixel = geometry.fitPoint(normalized, size.width, size.height)
                                    landmark.index to Offset(pixel.x, pixel.y)
                                }
                            }.toMap()
                            for ((a, b) in skeletonEdges) {
                                val from = points[a]; val to = points[b]
                                if (from != null && to != null) drawLine(Color(0xFF72E8BF), from, to, strokeWidth = 2.dp.toPx())
                            }
                            points.values.forEach { drawCircle(Color.White, 3.dp.toPx(), it) }
                        }
                    }
                }
                Text(state.message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("${state.source} · ${state.landmarks.size}/33 landmark observations")
                Text("Completed ${state.completed} · submitted ${state.submitted} · skipped ${state.skipped}", style = MaterialTheme.typography.bodySmall)
                Text("Preview follows inference speed. Images and landmarks stay in memory and clear on stop. No recording or upload.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSynthetic, enabled = !state.active) { Text("Try synthetic figure") }
                    OutlinedButton(onClick = onStop, enabled = state.active) { Text("Stop") }
                }
                HorizontalDivider()
                Text("Movement self-test", style = MaterialTheme.typography.titleMedium)
                Text("Authorized adult self-test only, or an empty/synthetic scene. Keep bystanders out of view. No recording, screenshots or uploads. Stop whenever you want.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.attestedCameraScope, onCheckedChange = onAttestation, enabled = !state.active)
                    Text("Only me, a consenting adult, or an empty/synthetic scene is in view. I agree to transient on-device processing without recording.", style = MaterialTheme.typography.bodySmall)
                }
                if (!state.cameraPermission) {
                    OutlinedButton(onClick = onPermission, enabled = !state.active) { Text("Allow camera") }
                }
                Button(onClick = onCamera, enabled = !state.active && state.cameraPermission && state.attestedCameraScope) { Text("Start camera viewer") }
                Text("Repetitions · holds · transitions\nNo movement classification or form claims yet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// MediaPipe's body topology, independent of exercise. Faces are intentionally not connected.
private val skeletonEdges = listOf(
    11 to 12, 11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21, 17 to 19,
    12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 18 to 20,
    11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 27 to 29, 29 to 31,
    27 to 31, 24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
)
