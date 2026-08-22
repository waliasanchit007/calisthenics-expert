package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.preprocessing.Rgb24
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The asynchronous MediaPipe LIVE_STREAM port. The monitor-confined slot is the sole ownership
 * authority; the SessionRunner pending view is only an advisory copy of that state.
 */
class PoseLandmarkerLiveInferencePort(
    private val detector: PoseLandmarkerLiveDetector,
    private val modelInputWidth: Int,
    private val modelInputHeight: Int,
    private val nowNs: () -> Long,
    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor(),
) : InferencePort, Disposable {
    private sealed interface Slot {
        data object Empty : Slot
        data class Occupied(
            val submissionIndex: Long,
            val backendTimestampMs: Long,
            val image: MPImage,
            val callback: (InferenceResult) -> Unit,
        ) : Slot
        data object Closed : Slot
    }

    private val monitor = Any()
    private var slot: Slot = Slot.Empty
    private var healthy = true
    private var callbackDrops = 0L
    private var disposed = false
    private var ownedImageCloses = 0L

    val isHealthy: Boolean
        get() = synchronized(monitor) { healthy }

    val hasPendingInference: Boolean
        get() = synchronized(monitor) { slot is Slot.Occupied }

    val diagnosticCallbackDrops: Long
        get() = synchronized(monitor) { callbackDrops }

    internal val diagnosticOwnedImageCloses: Long
        get() = synchronized(monitor) { ownedImageCloses }

    override fun submit(
        submissionIndex: Long,
        canonicalInput: ByteArray,
        backendTimestampMs: Long,
        callback: (InferenceResult) -> Unit,
    ) {
        val expectedLength = Rgb24.byteLength(modelInputWidth, modelInputHeight)
        require(canonicalInput.size == expectedLength) {
            "canonical input length ${canonicalInput.size} does not match the declared model-input " +
                "dimensions ${modelInputWidth}x$modelInputHeight (expected $expectedLength bytes)"
        }

        val directInput = ByteBuffer.allocateDirect(canonicalInput.size)
            .put(canonicalInput.copyOf())
            .also { it.flip() }
        val image = ByteBufferImageBuilder(
            directInput,
            modelInputWidth,
            modelInputHeight,
            MPImage.IMAGE_FORMAT_RGB,
        ).build()

        synchronized(monitor) {
            check(healthy && slot is Slot.Empty) {
                if (!healthy) "live inference port is unhealthy" else "live inference slot is occupied"
            }
            slot = Slot.Occupied(submissionIndex, backendTimestampMs, image, callback)
        }

        try {
            detector.detectAsync(image, backendTimestampMs)
        } catch (error: Throwable) {
            val released = releaseSubmission(submissionIndex)
            released?.let { owned ->
                closeOwnedImage(owned.image)
                enqueue(
                    owned.callback,
                    InferenceResult.Failure(
                        failureSource = "backend",
                        classification = "backend_call_failed",
                        detail = error.message,
                    ),
                )
            }
        }
    }

    /** Called by the native listener; it never invokes the runner on the native callback thread. */
    fun onResult(
        detection: PoseLandmarkerDetection,
        callbackTimestampMs: Long,
        callbackImage: MPImage,
    ) {
        val terminal = synchronized(monitor) {
            val occupied = slot as? Slot.Occupied
            if (occupied == null) {
                callbackDrops++
                return@synchronized null
            }
            val timestampMatches = callbackTimestampMs == occupied.backendTimestampMs
            slot = if (timestampMatches) Slot.Empty else Slot.Closed
            occupied to timestampMatches
        } ?: return

        val (occupied, timestampMatches) = terminal
        closeOwnedImage(occupied.image)
        val result = if (timestampMatches) {
            InferenceResult.Success(
                inferenceEndNs = nowNs(),
                posePresent = detection.posePresent,
                normalizedImageLandmarks = detection.normalizedLandmarks,
                worldLandmarks = detection.worldLandmarks,
            )
        } else {
            synchronized(monitor) { healthy = false }
            InferenceResult.Failure(
                failureSource = "backend",
                classification = "backend_timestamp_mismatch",
                detail = "expected ${occupied.backendTimestampMs}, received $callbackTimestampMs",
            )
        }
        // callbackImage is supplied by MediaPipe and deliberately not closed here.
        enqueue(occupied.callback, result)
    }

    /** Called by the native listener; an error is attributable only while the slot is occupied. */
    fun onError(error: RuntimeException) {
        val occupied = synchronized(monitor) {
            val current = slot as? Slot.Occupied
            if (current == null) {
                callbackDrops++
                healthy = false
                slot = Slot.Closed
            } else {
                slot = Slot.Empty
            }
            current
        } ?: return

        closeOwnedImage(occupied.image)
        enqueue(
            occupied.callback,
            InferenceResult.Failure(
                failureSource = "backend",
                classification = "backend_call_failed",
                detail = error.message,
            ),
        )
    }

    override fun cancel(submissionIndex: Long) {
        val occupied = synchronized(monitor) {
            val current = slot as? Slot.Occupied
            if (current?.submissionIndex != submissionIndex) return@synchronized null
            slot = Slot.Empty
            current
        } ?: return
        closeOwnedImage(occupied.image)
    }

    override fun dispose() {
        val disposal = synchronized(monitor) {
            if (disposed) return@synchronized false to null
            disposed = true
            val current = slot as? Slot.Occupied
            slot = Slot.Closed
            true to current
        }
        if (!disposal.first) return
        disposal.second?.let { closeOwnedImage(it.image) }
        try {
            detector.close()
        } finally {
            (callbackExecutor as? ExecutorService)?.shutdownNow()
        }
    }

    private fun releaseSubmission(submissionIndex: Long): Slot.Occupied? = synchronized(monitor) {
        val current = slot as? Slot.Occupied
        if (current?.submissionIndex != submissionIndex) return@synchronized null
        slot = Slot.Empty
        current
    }

    private fun closeOwnedImage(image: MPImage) {
        image.close()
        synchronized(monitor) { ownedImageCloses++ }
    }

    private fun enqueue(callback: (InferenceResult) -> Unit, result: InferenceResult) {
        try {
            callbackExecutor.execute {
                try {
                    callback(result)
                } catch (_: Throwable) {
                    synchronized(monitor) { healthy = false }
                }
            }
        } catch (_: Throwable) {
            synchronized(monitor) { healthy = false }
        }
    }
}
