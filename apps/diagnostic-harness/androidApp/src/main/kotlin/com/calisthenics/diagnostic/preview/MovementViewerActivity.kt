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
import com.calisthenics.diagnostic.ui.MovementMeasurementsPanel
import com.calisthenics.diagnostic.ui.MovementPanelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.calisthenics.diagnostic.viewer.ViewerPoint
import com.calisthenics.diagnostic.android.BuildConfig
import java.io.File

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
    private val movementCoordinator = ViewerMovementCoordinator()
    internal var movementState by mutableStateOf(MovementPanelState())
        private set
    internal var diagnosticsEnabled by mutableStateOf(false)
        private set
    private var diagnosticLog: ViewerDiagnosticLog? = null
    private var diagnosticDeadlineNs = 0L
    private var feedback: ViewerFeedbackBundle? = null
    private var recordingDisabled = false
    private var feedbackMessage by mutableStateOf<String?>(null)
    private val diagnosticMarker get() = File(noBackupFilesDir,"movement-diagnostics.enabled")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wallNow = System.currentTimeMillis()
        if (viewerDiagnosticsAuthorized(diagnosticMarker,BuildConfig.DEBUG,wallNow)) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            diagnosticDeadlineNs = nowNs + (DIAGNOSTIC_AUTH_MS - (wallNow-diagnosticMarker.lastModified())) * 1_000_000L
            diagnosticLog = ViewerDiagnosticLog(BuildConfig.PROBE_GIT_SHA,nowNs)
            diagnosticsEnabled = true
        }
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
                feedbackRecordingEnabled = BuildConfig.DEBUG,
                onDeleteRecordings = if (BuildConfig.DEBUG && controller == null) ({ deleteLocalFeedback() }) else null,
                diagnosticsStatus = feedback?.status ?: feedbackMessage ?: if (diagnosticsEnabled)
                    "Diagnostics ON · local ADB trace includes derived movement measurements, not images/video. " +
                    "Auto-off within 30 min · writer drops ${diagnosticLog?.droppedRows ?: 0}" else null,
                onDisableDiagnostics = if (diagnosticsEnabled) ({ disableDiagnostics() }) else null,
                movementSummary = if (controller != null) when (movementState.experiment.stage) {
                    com.calisthenics.diagnostic.viewer.experimental.ExperimentStage.READY -> "Practice accepted · confirm below to start counting"
                    com.calisthenics.diagnostic.viewer.experimental.ExperimentStage.COUNTING -> "Counting ON · ${movementState.experiment.candidateCycles} candidate cycles"
                    else -> "Setup / practice · counting is OFF"
                } else null,
                movementPanel = {
                    MovementMeasurementsPanel(movementState,
                        onCalibrate = { side ->
                            if (controller != null && input == ViewerInput.CAMERA) {
                                val now = SystemClock.elapsedRealtimeNanos()
                                movementCoordinator.startCalibration(side,now)
                                recordDiagnostic(ViewerDebugEvent.CALIBRATE,now)
                                refreshMovementPanel()
                            }
                        },
                        onConfirmRange = {
                            if (controller != null && input == ViewerInput.CAMERA) {
                                movementCoordinator.experiment.confirmCalibration()
                                recordDiagnostic(ViewerDebugEvent.CONFIRM)
                                refreshMovementPanel()
                            }
                        },
                        onReset = {
                            movementCoordinator.experiment.reset()
                            recordDiagnostic(ViewerDebugEvent.RESET)
                            refreshMovementPanel()
                        },
                    )
                },
            )
        }
    }

    fun startViewer(selected: ViewerInput): MovementViewerController? {
        if (controller != null) return controller
        if (selected == ViewerInput.CAMERA && (!attestedCameraScope || !hasCameraPermission())) return null
        input = selected
        movementCoordinator.clear()
        movementState = MovementPanelState(cameraActive = selected == ViewerInput.CAMERA)
        overlayPresenter.clear()
        overlay = null
        snapshot = null
        if (selected == ViewerInput.CAMERA && BuildConfig.DEBUG && !recordingDisabled) startFeedback()
        recordDiagnostic(if (selected == ViewerInput.CAMERA) ViewerDebugEvent.START_CAMERA else ViewerDebugEvent.START_SYNTHETIC)
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
        feedback?.markStopReason("camera_error")
        stopViewer()
        snapshot = latest.copy(observation = null)
    }

    private fun updateViewerSnapshot(latest: ViewerSnapshot, candidate: ViewerObservation? = latest.observation) {
        val decisionNs = SystemClock.elapsedRealtimeNanos()
        snapshot = latest
        if (controller != null && latest.failure == null) {
            // Only the raw freshness-filtered observation; never candidate or overlay-held data.
            movementCoordinator.update(latest.observation,decisionNs)
        } else movementCoordinator.clear()
        refreshMovementPanel()
        recordDiagnostic(ViewerDebugEvent.SAMPLE,decisionNs)
        if (controller != null && input == ViewerInput.CAMERA && latest.failure == null) {
            overlay = overlayPresenter.present(candidate, SystemClock.elapsedRealtimeNanos())
        } else {
            overlayPresenter.clear()
            overlay = null
        }
    }

    private fun refreshMovementPanel() {
        movementState = MovementPanelState(movementCoordinator.measurements,movementCoordinator.trace,
            movementCoordinator.experiment.state,controller != null && input == ViewerInput.CAMERA,
            SystemClock.elapsedRealtimeNanos())
    }

    fun stopViewer() {
        if (controller != null) recordDiagnostic(ViewerDebugEvent.STOP)
        diagnosticLog?.close()
        diagnosticLog = null
        diagnosticsEnabled = false
        movementCoordinator.clear()
        movementState = MovementPanelState()
        overlayPresenter.clear()
        overlay = null
        controller?.close()
        controller = null
        nativePreview = null
        snapshot = null
        attestedCameraScope = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() { feedback?.markStopReason("background"); stopViewer(); super.onStop() }

    override fun onDestroy() {
        diagnosticLog?.close()
        diagnosticLog = null
        super.onDestroy()
    }

    private fun recordDiagnostic(event: ViewerDebugEvent, now: Long = SystemClock.elapsedRealtimeNanos()) {
        val logger = diagnosticLog ?: return
        val expired = now >= diagnosticDeadlineNs
        val latest = snapshot
        latest?.observation?.let { feedback?.recordObservation(it,now) }
        fun confidence(indices: Set<Int>, presence: Boolean): Double? {
            val joints = latest?.observation?.landmarks?.filter { it.index in indices } ?: return null
            if (joints.size != 3 || joints.map { it.index }.toSet().size != 3) return null
            val values = joints.map { if (presence) it.presence else it.visibility }
            if (values.any { it == null || !it.isFinite() }) return null
            return values.filterNotNull().minOrNull()
        }
        val scores = ViewerJointConfidence(confidence(setOf(11,13,15),false),confidence(setOf(11,13,15),true),
            confidence(setOf(12,14,16),false),confidence(setOf(12,14,16),true))
        logger.record(if (expired) ViewerDebugEvent.EXPIRED else event,now,
            movementCoordinator.measurements,movementCoordinator.experiment.state,
            movementCoordinator.experiment.inputIssue,movementCoordinator.feedStatus,
            movementCoordinator.captureGapNs,latest?.submitted ?: 0,latest?.completed ?: 0,latest?.skipped ?: 0,
            scores,latest?.performance,latest?.observation)
        if (expired) {
            logger.close(); diagnosticLog = null; diagnosticsEnabled = false
        }
    }

    private fun disableDiagnostics() {
        feedback?.markStopReason("disabled")
        recordDiagnostic(ViewerDebugEvent.DISABLE)
        diagnosticLog?.close(); diagnosticLog = null; diagnosticsEnabled = false
        diagnosticMarker.delete()
        recordingDisabled = true
    }

    private fun startFeedback() {
        diagnosticLog?.close()
        diagnosticLog = null
        val previous = feedback
        if (previous != null && !previous.completion.isDone) {
            feedbackMessage = "Previous recording is draining; no new recording started"
            diagnosticsEnabled = false
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        try {
            val bundle = ViewerFeedbackBundle(File(noBackupFilesDir,"movement-feedback"),BuildConfig.PROBE_GIT_SHA,now,"{}",
                modelProvenanceProvider={ assets.open("replay/live_model_provenance.json").bufferedReader().use { it.readText() } })
            feedback = bundle
            diagnosticDeadlineNs = now + 180_000_000_000L
            lateinit var logger: ViewerDiagnosticLog
            logger = ViewerDiagnosticLog(BuildConfig.PROBE_GIT_SHA,now,exact=true,
                sessionId=bundle.sessionId,onClosed={ bundle.close(loggerDropped=logger.droppedRows,cause="user_stop") },emit={ line ->
                    bundle.recordTrace(line,org.json.JSONObject(line).getLong("decision_ns"))
                })
            diagnosticLog = logger
            diagnosticsEnabled = true
        } catch (_: Exception) {
            feedbackMessage = "Recording unavailable; camera analysis may continue"
            diagnosticsEnabled = false
        }
    }

    private fun deleteLocalFeedback() {
        if (controller != null || feedback?.completion?.isDone == false) return
        Thread({
            val result = runCatching { deleteViewerFeedback(File(noBackupFilesDir,"movement-feedback")) }
            runOnUiThread {
                feedback = null
                feedbackMessage = result.fold({ "Deleted $it local session(s). Already copied Mac files remain." },
                    { "Local deletion failed; retained files were not all removed." })
            }
        },"viewer-feedback-delete").start()
    }

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
