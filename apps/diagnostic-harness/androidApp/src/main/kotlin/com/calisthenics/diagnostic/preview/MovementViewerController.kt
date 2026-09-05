package com.calisthenics.diagnostic.preview

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import com.calisthenics.diagnostic.camera.CameraXFrameSource
import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerLiveAdapterFactory
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerLiveInferencePort
import com.calisthenics.diagnostic.mediapipe.realLiveDetectorConstructor
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.preprocessing.*
import com.calisthenics.diagnostic.replay.decodeReplayFixtureMetadata
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ViewerInput { SYNTHETIC, CAMERA }

/** Native lifecycle only: read packaged assets; no file, export, telemetry or network sink. */
class MovementViewerController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val input: ViewerInput,
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    @Volatile private var session: ViewerSession? = null
    @Volatile private var port: PoseLandmarkerLiveInferencePort? = null
    @Volatile private var source: CameraXFrameSource? = null
    @Volatile private var failure: String? = null

    fun start() {
        worker.execute {
            try {
                if (closed.get()) return@execute
                val provenance = parseModelProvenance(readAsset("replay/live_model_provenance.json").decodeToString())
                val native = PoseLandmarkerLiveAdapterFactory(
                    provenance, 256, 256,
                    { readAsset("replay/${provenance.assetName}") },
                    SystemClock::elapsedRealtimeNanos, realLiveDetectorConstructor(context),
                ).create()
                port = native
                val active = ViewerSession(native)
                session = active
                if (closed.get()) { active.close(); native.dispose(); return@execute }
                when (input) {
                    ViewerInput.SYNTHETIC -> {
                        val fixture = decodeReplayFixtureMetadata(readAsset("replay/detection/fixture_metadata.json").decodeToString()) {
                            readAsset("replay/detection/$it")
                        }
                        var index = 0
                        worker.scheduleWithFixedDelay({
                            if (!closed.get()) {
                                active.offer(SystemClock.elapsedRealtimeNanos()) {
                                    val rgb = fixture.frames[index++ % fixture.frames.size].plane
                                    ViewerFrame(rgb, fixture.frameWidth, fixture.frameHeight,
                                        scalePadRgb24(rgb, fixture.frameWidth, fixture.frameHeight, 256, 256))
                                }
                            }
                        }, 0, 100, TimeUnit.MILLISECONDS)
                    }
                    ViewerInput.CAMERA -> {
                        val camera = CameraXFrameSource(context, lifecycleOwner,
                            requestedLensFacing = androidx.camera.core.CameraSelector.LENS_FACING_FRONT)
                        source = camera
                        camera.start(
                            onSourceStarted = {},
                            onFrame = { frame ->
                                active.offer(frame.timestampNs) { prepareViewerFrame(frame) }
                            },
                            onFrameRejected = { active.fail("Camera format unavailable") },
                            onFailure = { active.fail("Camera unavailable") },
                        )
                    }
                }
            } catch (_: Exception) {
                closed.set(true)
                failure = "Viewer could not start"
                releaseOnWorker()
            }
        }
    }

    fun snapshot(): ViewerSnapshot {
        val startupFailure = failure
        if (startupFailure != null) return ViewerSnapshot(null, startupFailure, 0, 0, 0, startupFailure)
        return session?.snapshot(SystemClock.elapsedRealtimeNanos())
            ?: ViewerSnapshot(null, if (closed.get()) "Stopped" else "Loading pose model", 0, 0, 0, null)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        session?.close()
        try { worker.execute { releaseOnWorker() } }
        catch (_: RejectedExecutionException) {
            // Startup failure can finish teardown between close's CAS and enqueue.
            // Only releaseOnWorker shuts this executor down; it already owns disposal.
        }
    }

    private fun releaseOnWorker() {
        closed.set(true)
        session?.close()
        val camera = source
        source = null
        releaseViewerResources(camera?.let { it::stop },
            { try { port?.dispose() } finally { port = null } },
            { worker.shutdown() })
    }

    private fun readAsset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
}

internal fun prepareViewerFrame(frame: OwnedYuvFrame): ViewerFrame {
    val rgb = yuv420ToUprightRgb24(Yuv420Frame(
        frame.width, frame.height,
        YuvPlane(frame.y.bytes, frame.y.rowStride, frame.y.pixelStride),
        YuvPlane(frame.u.bytes, frame.u.rowStride, frame.u.pixelStride),
        YuvPlane(frame.v.bytes, frame.v.rowStride, frame.v.pixelStride),
        frame.colorStandard, frame.colorRange, frame.rotation, frame.mirrored,
    ))
    val (width, height) = uprightDimensions(frame.width, frame.height, frame.rotation)
    return ViewerFrame(rgb, width, height, scalePadRgb24(rgb, width, height, 256, 256))
}
