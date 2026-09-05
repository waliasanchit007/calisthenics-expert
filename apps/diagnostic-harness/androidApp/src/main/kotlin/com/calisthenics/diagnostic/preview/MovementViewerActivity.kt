package com.calisthenics.diagnostic.preview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.calisthenics.diagnostic.ui.MovementViewerScreen
import com.calisthenics.diagnostic.ui.MovementViewerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.calisthenics.diagnostic.viewer.ViewerPoint

class MovementViewerActivity : ComponentActivity() {
    private var controller by mutableStateOf<MovementViewerController?>(null)
    internal var snapshot by mutableStateOf<ViewerSnapshot?>(null)
        private set
    private var input by mutableStateOf(ViewerInput.SYNTHETIC)
    private var nativePreview by mutableStateOf<PreviewView?>(null)
    internal var attestedCameraScope by mutableStateOf(false)
    private val overlayPresenter = ViewerOverlayPresenter()
    internal var overlay by mutableStateOf<ViewerOverlay?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            var permission by remember { mutableStateOf(hasCameraPermission()) }
            val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
            LaunchedEffect(controller) {
                val current = controller ?: return@LaunchedEffect
                launch {
                    current.snapshots().collect { latest ->
                        if (controller === current) {
                            // Derive expiry at display time, including results already old on arrival.
                            updateViewerSnapshot(current.snapshot(), latest.observation)
                            if (latest.failure != null) finishWithFailure(latest)
                        }
                    }
                }
                while (controller === current) {
                    val latest = current.snapshot()
                    updateViewerSnapshot(latest)
                    if (latest.failure != null) { finishWithFailure(latest); break }
                    // Expiry/metric refresh only: completions above are event-driven.
                    delay(50)
                }
            }
            val observation = snapshot?.observation
            val image = remember(observation, input) {
                if (input == ViewerInput.SYNTHETIC) observation?.frame?.toBitmap()?.asImageBitmap() else null
            }
            val view = nativePreview
            val target = view?.let { unmirroredFrontPreviewTransform(it.sensorToViewTransform, it.width) }
            val displayedPose = overlay?.observation
            val geometry = displayedPose?.frame?.analysisTransform
            val livePoints = if (view != null && view.width > 0 && view.height > 0 && geometry != null) {
                displayedPose.landmarks.mapNotNull { landmark ->
                    mapPreviewPoint(landmark.x, landmark.y, geometry, target)?.let {
                        landmark.index to ViewerPoint(it.x / view.width, it.y / view.height)
                    }
                }.toMap()
            } else emptyMap()
            MovementViewerScreen(
                state = MovementViewerUiState(
                    active = controller != null,
                    message = if (overlay?.held == true) "Tracking gap · holding last pose briefly"
                        else snapshot?.message ?: "Ready to explore",
                    source = if (input == ViewerInput.SYNTHETIC) "Packaged synthetic figure" else "Front camera",
                    width = observation?.frame?.width ?: 1,
                    height = observation?.frame?.height ?: 1,
                    landmarks = observation?.landmarks.orEmpty(),
                    submitted = snapshot?.submitted ?: 0, completed = snapshot?.completed ?: 0,
                    skipped = snapshot?.skipped ?: 0, cameraPermission = permission,
                    attestedCameraScope = attestedCameraScope,
                    performance = formatViewerTiming(snapshot?.performance, controller?.previewFps()),
                ),
                image = image,
                onSynthetic = { startViewer(ViewerInput.SYNTHETIC) },
                onCamera = { startViewer(ViewerInput.CAMERA) },
                onStop = ::stopViewer,
                onPermission = { requestPermission.launch(Manifest.permission.CAMERA) },
                onAttestation = { attestedCameraScope = it },
                livePreview = view?.let { native -> { modifier -> AndroidView(factory = { native }, modifier = modifier) } },
                livePoints = livePoints,
                liveOverlayAlpha = overlay?.alpha ?: 0f,
            )
        }
    }

    fun startViewer(selected: ViewerInput): MovementViewerController? {
        if (controller != null) return controller
        if (selected == ViewerInput.CAMERA && (!attestedCameraScope || !hasCameraPermission())) return null
        input = selected
        overlayPresenter.clear()
        overlay = null
        snapshot = null
        if (selected == ViewerInput.SYNTHETIC) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = if (selected == ViewerInput.CAMERA) PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        } else null
        nativePreview = view
        return MovementViewerController(this, this, selected, previewView = view).also { current ->
            view?.setFrameUpdateListener(ContextCompat.getMainExecutor(this)) { timestamp -> current.onPreviewFrame(timestamp) }
            controller = current
            current.start()
        }
    }

    internal fun finishWithFailure(latest: ViewerSnapshot) {
        stopViewer()
        snapshot = latest.copy(observation = null)
    }

    private fun updateViewerSnapshot(latest: ViewerSnapshot, candidate: ViewerObservation? = latest.observation) {
        snapshot = latest
        if (controller != null && input == ViewerInput.CAMERA && latest.failure == null) {
            overlay = overlayPresenter.present(candidate, SystemClock.elapsedRealtimeNanos())
        } else {
            overlayPresenter.clear()
            overlay = null
        }
    }

    fun stopViewer() {
        overlayPresenter.clear()
        overlay = null
        controller?.close()
        controller = null
        nativePreview = null
        snapshot = null
        attestedCameraScope = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() { stopViewer(); super.onStop() }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

private fun formatViewerTiming(performance: ViewerPerformance?, previewFps: Double?): String {
    fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
    return "Preview ${number(previewFps)} FPS · analysis ${number(performance?.offerFps)} FPS · pose ${number(performance?.poseFps)} FPS\n" +
        "Prepare ${number(performance?.preparationMs)} ms · inference ${number(performance?.inferenceMs)} ms\n" +
        "Capture→result ${number(performance?.captureToResultMs)} ms · pose age ${number(performance?.poseAgeMs)} ms"
}

private fun ViewerFrame.toBitmap(): Bitmap {
    val pixels = IntArray(width * height) { index ->
        val offset = index * 3
        (0xFF shl 24) or ((rgb[offset].toInt() and 0xFF) shl 16) or
            ((rgb[offset + 1].toInt() and 0xFF) shl 8) or (rgb[offset + 2].toInt() and 0xFF)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
