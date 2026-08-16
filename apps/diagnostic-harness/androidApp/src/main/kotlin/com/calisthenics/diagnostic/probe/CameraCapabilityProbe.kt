package com.calisthenics.diagnostic.probe

import android.content.Context
import android.hardware.DataSpace
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraCapabilityProbe(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val probeGitSha: String = "unknown",
) {
    @OptIn(ExperimentalGetImage::class)
    fun run(): ProbeResult {
        val provider = ProcessCameraProvider.getInstance(context).get(timeoutMillis, TimeUnit.MILLISECONDS)
        val cameraInfo = provider.availableCameraInfos.firstOrNull { info ->
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: return unavailableResult("none", "UNKNOWN", false, false)
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val cameraId = camera2Info.cameraId
        val timestampSource = timestampLabel(
            camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
        )
        val executor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var dataSpace: Int? = null
        var matrix: String? = null
        var range: String? = null
        val firstFrame = CountDownLatch(1)
        val acceptedFrame = AtomicBoolean(false)
        try {
            analysis.setAnalyzer(executor) { image ->
                try {
                    if (!acceptedFrame.compareAndSet(false, true)) return@setAnalyzer
                    val source = image.image?.dataSpace
                    dataSpace = source
                    matrix = source?.let(::matrixLabel)
                    range = source?.let(::rangeLabel)
                    firstFrame.countDown()
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            // CameraX delivers asynchronously; a real device run uses this bounded wait.
            check(firstFrame.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "analysis callback timeout" }
        } finally {
            analysis.clearAnalyzer()
            provider.unbindAll()
            executor.shutdownNow()
            executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        val durability = exerciseCacheDurability()
        return ProbeResult(
            deviceModel = Build.MODEL,
            osBuild = Build.DISPLAY,
            cameraId = cameraId,
            timestampSource = timestampSource,
            sourceDataspaceRaw = dataSpace,
            sourceColorMatrix = matrix,
            sourceColorRange = range,
            pixelFormat = "YUV_420_888",
            cameraxVersion = "1.6.1",
            probeGitSha = probeGitSha,
            directoryFsyncSupported = durability.first,
            atomicRenameSupported = durability.second,
        )
    }

    fun runAndPersist(): ProbeResult {
        val result = run()
        val directory = context.cacheDir
        val staging = File(directory, TEMP_RESULT_FILE)
        val output = File(directory, RESULT_FILE)
        FileOutputStream(staging).use { stream ->
            stream.write(result.toCanonicalJson().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Os.rename(staging.absolutePath, output.absolutePath)
        runCatching {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
        return result
    }

    private fun exerciseCacheDurability(): Pair<Boolean, Boolean> {
        val directory = context.cacheDir
        val directoryFsync = runCatching {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }.isSuccess
        val atomicRename = runCatching {
            val source = File(directory, TEMP_FILE)
            val target = File(directory, RENAMED_FILE)
            FileOutputStream(source).use { stream ->
                stream.write(0)
                stream.fd.sync()
            }
            Os.rename(source.absolutePath, target.absolutePath)
            target.delete()
        }.isSuccess
        return directoryFsync to atomicRename
    }

    private fun unavailableResult(
        cameraId: String,
        timestampSource: String,
        directoryFsyncSupported: Boolean,
        atomicRenameSupported: Boolean,
    ) = ProbeResult(
        deviceModel = Build.MODEL,
        osBuild = Build.DISPLAY,
        cameraId = cameraId,
        timestampSource = timestampSource,
        sourceDataspaceRaw = null,
        sourceColorMatrix = null,
        sourceColorRange = null,
        pixelFormat = "YUV_420_888",
        cameraxVersion = "1.6.1",
        probeGitSha = probeGitSha,
        directoryFsyncSupported = directoryFsyncSupported,
        atomicRenameSupported = atomicRenameSupported,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val RESULT_FILE = "camera-capability-probe.json"
        private const val TEMP_RESULT_FILE = ".camera-capability-probe.json.tmp"
        private const val TEMP_FILE = ".camera-capability-probe.tmp"
        private const val RENAMED_FILE = ".camera-capability-probe.committed"

        private fun timestampLabel(source: Int?): String = when (source) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
            else -> "UNKNOWN"
        }

        private fun matrixLabel(dataSpace: Int): String? = when (DataSpace.getStandard(dataSpace)) {
            DataSpace.STANDARD_BT709 -> "BT709"
            DataSpace.STANDARD_BT601_625 -> "BT601"
            DataSpace.STANDARD_BT601_525 -> "BT601"
            DataSpace.STANDARD_BT2020 -> "BT2020"
            else -> null
        }

        private fun rangeLabel(dataSpace: Int): String? = when (DataSpace.getRange(dataSpace)) {
            DataSpace.RANGE_FULL -> "FULL"
            DataSpace.RANGE_LIMITED -> "LIMITED"
            else -> null
        }
    }
}
