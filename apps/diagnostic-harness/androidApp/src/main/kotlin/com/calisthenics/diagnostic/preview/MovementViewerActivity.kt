package com.calisthenics.diagnostic.preview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.calisthenics.diagnostic.ui.MovementViewerScreen
import com.calisthenics.diagnostic.ui.MovementViewerUiState
import kotlinx.coroutines.delay

class MovementViewerActivity : ComponentActivity() {
    private var controller by mutableStateOf<MovementViewerController?>(null)
    internal var snapshot by mutableStateOf<ViewerSnapshot?>(null)
        private set
    private var input by mutableStateOf(ViewerInput.SYNTHETIC)
    internal var attestedCameraScope by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            var permission by remember { mutableStateOf(hasCameraPermission()) }
            val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
            LaunchedEffect(controller) {
                val current = controller ?: return@LaunchedEffect
                while (controller === current) {
                    val latest = current.snapshot()
                    snapshot = latest
                    if (latest.failure != null) { finishWithFailure(latest); break }
                    delay(100)
                }
            }
            val observation = snapshot?.observation
            val image = remember(observation) { observation?.frame?.toBitmap()?.asImageBitmap() }
            MovementViewerScreen(
                state = MovementViewerUiState(
                    active = controller != null,
                    message = snapshot?.message ?: "Ready to explore",
                    source = if (input == ViewerInput.SYNTHETIC) "Packaged synthetic figure" else "Rear camera",
                    width = observation?.frame?.width ?: 1,
                    height = observation?.frame?.height ?: 1,
                    landmarks = observation?.landmarks.orEmpty(),
                    submitted = snapshot?.submitted ?: 0, completed = snapshot?.completed ?: 0,
                    skipped = snapshot?.skipped ?: 0, cameraPermission = permission,
                    attestedCameraScope = attestedCameraScope,
                ),
                image = image,
                onSynthetic = { startViewer(ViewerInput.SYNTHETIC) },
                onCamera = { startViewer(ViewerInput.CAMERA) },
                onStop = ::stopViewer,
                onPermission = { requestPermission.launch(Manifest.permission.CAMERA) },
                onAttestation = { attestedCameraScope = it },
            )
        }
    }

    fun startViewer(selected: ViewerInput): MovementViewerController? {
        if (controller != null) return controller
        if (selected == ViewerInput.CAMERA && (!attestedCameraScope || !hasCameraPermission())) return null
        input = selected
        snapshot = null
        if (selected == ViewerInput.SYNTHETIC) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return MovementViewerController(this, this, selected).also { controller = it; it.start() }
    }

    internal fun finishWithFailure(latest: ViewerSnapshot) {
        stopViewer()
        snapshot = latest.copy(observation = null)
    }

    fun stopViewer() {
        controller?.close()
        controller = null
        snapshot = null
        attestedCameraScope = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() { stopViewer(); super.onStop() }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

private fun ViewerFrame.toBitmap(): Bitmap {
    val pixels = IntArray(width * height) { index ->
        val offset = index * 3
        (0xFF shl 24) or ((rgb[offset].toInt() and 0xFF) shl 16) or
            ((rgb[offset + 1].toInt() and 0xFF) shl 8) or (rgb[offset + 2].toInt() and 0xFF)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
