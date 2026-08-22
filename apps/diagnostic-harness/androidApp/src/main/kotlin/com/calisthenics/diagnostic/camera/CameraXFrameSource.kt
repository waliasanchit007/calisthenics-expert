package com.calisthenics.diagnostic.camera

import android.hardware.DataSpace
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.YuvColorRange
import com.calisthenics.diagnostic.preprocessing.YuvColorStandard
import com.calisthenics.diagnostic.probe.AndroidCameraLifecycleDispatcher
import java.util.concurrent.Executors

class CameraXFrameSource(
    private val context: android.content.Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val lifecycleDispatcher = AndroidCameraLifecycleDispatcher()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val lifecycleGeneration = CameraXLifecycleGeneration<ProcessCameraProvider, ImageAnalysis> { provider, useCase ->
        lifecycleDispatcher.dispatch(5_000L) { provider.unbind(useCase) }
    }
    private var stopped = true

    fun start(onFrame: (OwnedYuvFrame) -> Unit, onFailure: (Throwable) -> Unit) {
        check(stopped) { "CameraXFrameSource is already started" }
        stopped = false
        val generation = lifecycleGeneration.begin()
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
                val useCase = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(BACKPRESSURE_STRATEGY)
                    .build()
                useCase.setAnalyzer(analyzerExecutor) { image ->
                    handleImage(image, onFrame, onFailure)
                }
                if (!lifecycleGeneration.complete(generation, cameraProvider, useCase)) return@runCatching
                lifecycleDispatcher.dispatch(5_000L) {
                    if (lifecycleGeneration.isCurrent(generation)) {
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, useCase)
                    }
                }
            }.onFailure(onFailure)
        }, analyzerExecutor)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        lifecycleGeneration.stop()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun handleImage(image: ImageProxy, onFrame: (OwnedYuvFrame) -> Unit, onFailure: (Throwable) -> Unit) {
        runCatching {
            val underlying = image.image ?: error("CameraX underlying image unavailable")
            require(image.format == OwnedYuvFrame.ADMITTED_PIXEL_FORMAT) { "unsupported image format" }
            require(underlying.dataSpace == OwnedYuvFrame.ADMITTED_RAW_DATASPACE) { "unsupported DataSpace" }
            val source = CameraXOwnedImage(image, underlying)
            captureOwnedYuvFrame(source, onFrame)
        }.onFailure(onFailure)
    }

    companion object {
        const val BACKPRESSURE_STRATEGY = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
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
