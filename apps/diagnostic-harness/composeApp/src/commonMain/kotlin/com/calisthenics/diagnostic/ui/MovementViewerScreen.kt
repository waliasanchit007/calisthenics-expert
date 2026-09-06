package com.calisthenics.diagnostic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.viewer.ViewerGeometry
import com.calisthenics.diagnostic.viewer.ViewerPoint

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
    val performance: String = "Timing unavailable until frames arrive",
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
    livePreview: (@Composable (Modifier) -> Unit)? = null,
    livePoints: Map<Int, ViewerPoint> = emptyMap(),
    liveOverlayAlpha: Float = 1f,
    movementSummary: String? = null,
    movementPanel: (@Composable () -> Unit)? = null,
    diagnosticsStatus: String? = null,
    onDisableDiagnostics: (() -> Unit)? = null,
    feedbackRecordingEnabled: Boolean = false,
    onDeleteRecordings: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val recordingLabel = when {
        diagnosticsStatus?.startsWith("Recording locally") == true -> "● Recording locally"
        diagnosticsStatus?.startsWith("Starting local") == true -> "Starting local recording"
        diagnosticsStatus?.startsWith("Recording failed") == true ||
            diagnosticsStatus?.startsWith("Recording unavailable") == true -> "Recording unavailable"
        diagnosticsStatus?.startsWith("Not recording") == true ||
            diagnosticsStatus?.startsWith("Recording stopped") == true -> "Recording stopped"
        diagnosticsStatus?.startsWith("Diagnostics ON") == true -> "Local logs on"
        state.active -> "Not recording"
        else -> "Camera stays off until you start"
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF72E8BF))) {
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete=false },
            title = { Text("Delete local recordings?") },
            text = { Text("Deletes feedback sessions from this phone. Copies on the Mac remain.") },
            confirmButton = { TextButton(onClick = { confirmDelete=false; onDeleteRecordings?.invoke() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete=false }) { Text("Cancel") } })
        if(showConsent) AlertDialog(onDismissRequest={showConsent=false},
            title={Text("Before you start")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Only you, a consenting adult, or an empty scene. Keep bystanders out of view.")
                Text(if(feedbackRecordingEnabled)
                    "Saves up to 3 minutes of camera-input images and movement logs locally for debugging and Mac retrieval. No audio or cloud uploads."
                    else "On-device camera processing only. No app recording or uploads.")
                Row(Modifier.toggleable(state.attestedCameraScope,role=Role.Checkbox,onValueChange=onAttestation),
                    verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(state.attestedCameraScope,onCheckedChange=null)
                    Text("I agree for this session")
                }
            }},
            confirmButton={TextButton(enabled=state.attestedCameraScope && state.cameraPermission,
                onClick={showConsent=false; onCamera()}) {Text("Start self-test")}},
            dismissButton={TextButton(onClick={showConsent=false; onAttestation(false)}) {Text("Cancel")}})
        if(showOptions) AlertDialog(onDismissRequest={showOptions=false},
            title={Text("Session options")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Experimental pull-up counting. Not a form assessment.")
                Text("Detailed tracking logs are collected for debugging; they do not need to stay on your screen.")
                if(!state.active) TextButton(onClick={showOptions=false;onSynthetic()}) {Text("Try synthetic figure")}
                onDisableDiagnostics?.let { disable ->
                    TextButton(onClick={showOptions=false;disable()}) {Text("Stop local recording & logs")}
                }
                onDeleteRecordings?.let {
                    TextButton(onClick={showOptions=false;confirmDelete=true}) {Text("Delete local recordings")}
                }
            }},
            confirmButton={TextButton(onClick={showOptions=false}) {Text("Done")}})
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(horizontal=16.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Movement",style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={showOptions=true}) {Text("Options")}
                }
                Text(if(state.active || diagnosticsStatus!=null) recordingLabel else "Private self-test · no uploads",
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xFF15232A)), contentAlignment = Alignment.Center) {
                    if (livePreview != null) livePreview(Modifier.fillMaxSize())
                    if (image == null && livePreview == null) {
                        Text(if(state.active) "Camera preview" else "Your movement, in view", color = Color(0xFFB8C7CD))
                    } else {
                        if (image != null && livePreview == null) Image(image, "${state.source} paired inference frame", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Canvas(Modifier.fillMaxSize()) {
                            val geometry = ViewerGeometry(state.width, state.height)
                            val points = if (livePreview != null) livePoints.mapValues { (_, point) ->
                                Offset(point.x * size.width, point.y * size.height)
                            } else state.landmarks.mapNotNull { landmark ->
                                geometry.modelToSource(landmark.x, landmark.y)?.let { normalized ->
                                    val pixel = geometry.fitPoint(normalized, size.width, size.height)
                                    landmark.index to Offset(pixel.x, pixel.y)
                                }
                            }.toMap()
                            for ((a, b) in skeletonEdges) {
                                val from = points[a]; val to = points[b]
                                if (from != null && to != null) drawLine(Color(0xFF72E8BF), from, to, strokeWidth = 2.dp.toPx(),
                                    alpha = if (livePreview != null) liveOverlayAlpha else 1f)
                            }
                            points.values.forEach { drawCircle(Color.White, 3.dp.toPx(), it,
                                alpha = if (livePreview != null) liveOverlayAlpha else 1f) }
                        }
                    }
                }
                if(state.active) movementPanel?.invoke()
                if(state.active) OutlinedButton(onClick=onStop,modifier=Modifier.fillMaxWidth()) {Text("Stop")}
                else {
                    if(state.message.startsWith("Error",ignoreCase=true) || state.message.contains("failed",ignoreCase=true))
                        Text("Camera could not start. Check permission and try again.")
                    Button(onClick={
                        if(!state.cameraPermission) onPermission()
                        else { onAttestation(false); showConsent=true }
                    },modifier=Modifier.fillMaxWidth()) {Text(if(state.cameraPermission) "Start camera" else "Allow camera")}
                    Text("Experimental · practice first, then count",style=MaterialTheme.typography.labelSmall)
                }
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
