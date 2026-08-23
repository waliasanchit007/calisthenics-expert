package com.calisthenics.diagnostic.camera

import android.hardware.DataSpace
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.YuvColorRange
import com.calisthenics.diagnostic.preprocessing.YuvColorStandard
import com.calisthenics.diagnostic.probe.AndroidCameraLifecycleDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import android.hardware.camera2.CaptureRequest

class CameraXFrameSource(
    private val context: android.content.Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val lifecycleDispatcher = AndroidCameraLifecycleDispatcher()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val sourceLifecycle = CameraXSourceLifecycle<ProcessCameraProvider, ImageAnalysis>(
        cleanupUseCase = { provider, useCase ->
            useCase.clearAnalyzer()
            lifecycleDispatcher.dispatch(5_000L) { provider.unbind(useCase) }
        },
        shutdownAnalyzer = { analyzerExecutor.shutdown() },
    )
    private val providerCompletionExecutor = Executor { runnable -> runnable.run() }

    fun start(
        onSourceStarted: (NegotiatedAnalysisResolution) -> Unit,
        onFrame: (OwnedYuvFrame) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val generation = sourceLifecycle.begin()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val cameraProvider = future.get()
                val selectedCameraInfo = cameraProvider.availableCameraInfos.firstOrNull { info ->
                    Camera2CameraInfo.from(info).cameraId == OwnedYuvFrame.CAMERA_ID
                } ?: error("camera ID 0 is unavailable")
                require(
                    Camera2CameraInfo.from(selectedCameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                ) { "camera ID 0 does not provide REALTIME timestamps" }
                val selector = CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        infos.filter { Camera2CameraInfo.from(it).cameraId == OwnedYuvFrame.CAMERA_ID }
                    }
                    .build()
                val useCaseBuilder = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(BACKPRESSURE_STRATEGY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(REQUESTED_ANALYSIS_WIDTH, REQUESTED_ANALYSIS_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                Camera2Interop.Extender(useCaseBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(TARGET_CAMERA_FPS, TARGET_CAMERA_FPS),
                )
                val useCase = useCaseBuilder.build()
                if (!sourceLifecycle.complete(generation, cameraProvider, useCase) {
                        useCase.setAnalyzer(analyzerExecutor) { image ->
                            handleImage(image, onFrame, onFailure)
                        }
                    }
                ) return@runCatching
                lifecycleDispatcher.dispatch(5_000L) {
                    if (sourceLifecycle.isCurrent(generation)) {
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, useCase)
                        val resolution = checkNotNull(useCase.resolutionInfo) {
                            "CameraX did not report the negotiated analysis resolution"
                        }.resolution
                        onSourceStarted(NegotiatedAnalysisResolution(resolution.width, resolution.height))
                    }
                }
            }.onFailure(onFailure)
        }, providerCompletionExecutor)
    }

    fun stop() {
        sourceLifecycle.stop()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun handleImage(image: ImageProxy, onFrame: (OwnedYuvFrame) -> Unit, onFailure: (Throwable) -> Unit) {
        var underlying: Image? = null
        handleImageWithOwnership(
            proxy = object : ImageProxyOwnership {
                override fun close() {
                    image.close()
                }
            },
            validate = {
                val source = image.image ?: error("CameraX underlying image unavailable")
                underlying = source
                require(image.format == OwnedYuvFrame.ADMITTED_PIXEL_FORMAT) { "unsupported image format" }
                require(source.dataSpace == OwnedYuvFrame.ADMITTED_RAW_DATASPACE) {
                    "unsupported DataSpace"
                }
            },
            construct = { CameraXOwnedImage(image, checkNotNull(underlying)) },
            onFrame = onFrame,
            onFailure = onFailure,
        )
    }

    companion object {
        const val BACKPRESSURE_STRATEGY = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        const val TARGET_CAMERA_FPS = 30
        const val REQUESTED_ANALYSIS_WIDTH = 1280
        const val REQUESTED_ANALYSIS_HEIGHT = 720
    }
}

data class NegotiatedAnalysisResolution(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "negotiated analysis dimensions must be positive" }
    }
}

internal interface ImageProxyOwnership {
    fun close()
}

internal fun handleImageWithOwnership(
    proxy: ImageProxyOwnership,
    validate: () -> Unit,
    construct: () -> OwnedImage,
    onFrame: (OwnedYuvFrame) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    var transferred = false
    try {
        validate()
        val owned = construct()
        transferred = true
        captureOwnedYuvFrame(owned, onFrame)
    } catch (failure: Throwable) {
        if (!transferred) proxy.close()
        onFailure(failure)
    }
}

internal class CameraXLifecycleGeneration<P, U>(
    private val unbind: (provider: P, useCase: U) -> Unit,
) {
    private var nextGeneration = 0L
    private var running = false
    private var active: Pair<P, U>? = null

    val hasActiveBinding: Boolean
        get() = active != null

    val activeBinding: Pair<P, U>?
        get() = active

    @Synchronized
    fun begin(): Long {
        active?.let { (provider, useCase) -> unbind(provider, useCase) }
        active = null
        running = true
        nextGeneration += 1L
        return nextGeneration
    }

    @Synchronized
    fun complete(generation: Long, provider: P, useCase: U): Boolean {
        if (!running || generation != nextGeneration) {
            unbind(provider, useCase)
            return false
        }
        active = provider to useCase
        return true
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = running && generation == nextGeneration

    @Synchronized
    fun stop() {
        if (!running && active == null) return
        running = false
        nextGeneration += 1L
        active?.let { (provider, useCase) -> unbind(provider, useCase) }
        active = null
    }
}

/** One-session source lifecycle that fences late provider completions from a terminated executor. */
internal class CameraXSourceLifecycle<P, U>(
    private val cleanupUseCase: (provider: P, useCase: U) -> Unit,
    private val shutdownAnalyzer: () -> Unit,
) {
    private var nextGeneration = 0L
    private var running = false
    private var started = false
    private var terminal = false
    private var active: Pair<P, U>? = null

    @Synchronized
    fun begin(): Long {
        check(!started && !terminal) { "CameraXFrameSource is one-session-owned" }
        started = true
        running = true
        nextGeneration += 1L
        return nextGeneration
    }

    @Synchronized
    fun complete(generation: Long, provider: P, useCase: U, configureAnalyzer: () -> Unit): Boolean {
        if (!running || generation != nextGeneration) {
            cleanupUseCase(provider, useCase)
            return false
        }
        active = provider to useCase
        configureAnalyzer()
        return true
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = running && generation == nextGeneration

    @Synchronized
    fun stop() {
        if (terminal) return
        terminal = true
        running = false
        nextGeneration += 1L
        active?.let { (provider, useCase) -> cleanupUseCase(provider, useCase) }
        active = null
        shutdownAnalyzer()
    }
}

@OptIn(ExperimentalGetImage::class)
private class CameraXOwnedImage(
    private val proxy: ImageProxy,
    private val image: Image,
) : OwnedImage {
    override val planes = image.planes.map { plane ->
        object : SourcePlane {
            override val bytes = plane.buffer.duplicate().let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }
            override val rowStride = plane.rowStride
            override val pixelStride = plane.pixelStride
        }
    }
    override val width = image.width
    override val height = image.height
    override val rotation = when (proxy.imageInfo.rotationDegrees) {
        0 -> FrameRotation.R0
        90 -> FrameRotation.R90
        180 -> FrameRotation.R180
        270 -> FrameRotation.R270
        else -> error("unsupported image rotation")
    }
    override val mirrored = false
    override val rawDataSpace = image.dataSpace
    override val colorStandard = when (DataSpace.getStandard(image.dataSpace)) {
        DataSpace.STANDARD_BT601_625, DataSpace.STANDARD_BT601_525 -> YuvColorStandard.BT601
        else -> error("unsupported DataSpace colour standard")
    }
    override val colorRange = when (DataSpace.getRange(image.dataSpace)) {
        DataSpace.RANGE_FULL -> YuvColorRange.FULL
        else -> error("unsupported DataSpace colour range")
    }
    override val timestampNs = proxy.imageInfo.timestamp

    override fun close() {
        proxy.close()
    }
}
