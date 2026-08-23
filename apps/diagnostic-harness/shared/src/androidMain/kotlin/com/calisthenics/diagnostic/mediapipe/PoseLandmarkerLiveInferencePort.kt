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
    private val imageFactory: (ByteBuffer, Int, Int) -> MPImage = { buffer, width, height ->
        ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()
    },
    private val closeImage: (MPImage) -> Unit = { it.close() },
) : InferencePort, Disposable {
    private sealed interface Slot {
        data object Empty : Slot
        data class Occupied(
            val submissionIndex: Long,
            val backendTimestampMs: Long,
            val directInput: ByteBuffer,
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

        val occupied = synchronized(monitor) {
            check(healthy && slot is Slot.Empty) {
                if (!healthy) "live inference port is unhealthy" else "live inference slot is occupied"
            }
            // Reservation and resource creation share this monitor. A rejected submission
            // therefore cannot allocate an image that is outside the authoritative slot.
            val directInput = ByteBuffer.allocateDirect(canonicalInput.size)
                .put(canonicalInput.copyOf())
                .also { it.flip() }
            val image = imageFactory(directInput, modelInputWidth, modelInputHeight)
            Slot.Occupied(submissionIndex, backendTimestampMs, directInput, image, callback).also {
                slot = it
            }
        }

        try {
            detector.detectAsync(occupied.image, backendTimestampMs)
        } catch (error: Throwable) {
            val released = releaseSubmission(submissionIndex)
            released?.let { owned ->
                val closeFailure = closeOwnedImage(owned.image)
                enqueue(
                    owned.callback,
                    InferenceResult.Failure(
                        failureSource = "backend",
                        classification = "backend_call_failed",
                        sourceErrorCode = closeFailure?.let { "owned_image_close_failed" } ?: "detect_async_failed",
                        detail = error.message ?: closeFailure?.message,
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
        val closeFailure = closeOwnedImage(occupied.image)
        val result = if (closeFailure != null) {
            InferenceResult.Failure(
                failureSource = "backend",
                classification = "backend_call_failed",
                sourceErrorCode = "owned_image_close_failed",
                detail = closeFailure.message,
            )
        } else if (!timestampMatches) {
            synchronized(monitor) { healthy = false }
            InferenceResult.Failure(
                failureSource = "backend",
                classification = "backend_call_failed",
                sourceErrorCode = "backend_timestamp_mismatch",
                detail = "expected ${occupied.backendTimestampMs}, received $callbackTimestampMs",
            )
        } else {
            try {
                InferenceResult.Success(
                    inferenceEndNs = nowNs(),
                    posePresent = detection.posePresent,
                    normalizedImageLandmarks = detection.normalizedLandmarks,
                    worldLandmarks = detection.worldLandmarks,
                )
            } catch (error: Throwable) {
                synchronized(monitor) { healthy = false }
                InferenceResult.Failure(
                    failureSource = "backend",
                    classification = "backend_call_failed",
                    sourceErrorCode = "callback_clock_failed",
                    detail = error.message,
                )
            }
        }
        // callbackImage is supplied by MediaPipe and deliberately not closed here.
        enqueue(occupied.callback, result)
    }

    /** Called by the native listener; an error is attributable only while the slot is occupied. */
    fun onError(error: Throwable) {
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

        val closeFailure = closeOwnedImage(occupied.image)
        val sourceErrorCode = closeFailure?.let { "owned_image_close_failed" }
            ?: (error as? PoseLandmarkerLiveCallbackException)?.sourceErrorCode
            ?: "mediapipe_error"
        enqueue(
            occupied.callback,
            InferenceResult.Failure(
                failureSource = "backend",
                classification = "backend_call_failed",
                sourceErrorCode = sourceErrorCode,
                detail = error.message ?: closeFailure?.message,
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

    private fun closeOwnedImage(image: MPImage): Throwable? {
        var failure: Throwable? = null
        try {
            closeImage(image)
        } catch (error: Throwable) {
            failure = error
            synchronized(monitor) { healthy = false }
        } finally {
            synchronized(monitor) { ownedImageCloses++ }
        }
        return failure
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
