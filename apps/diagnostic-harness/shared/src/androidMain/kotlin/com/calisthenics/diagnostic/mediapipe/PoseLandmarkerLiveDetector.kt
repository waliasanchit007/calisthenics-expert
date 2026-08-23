package com.calisthenics.diagnostic.mediapipe

import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/** Callbacks installed in MediaPipe options before a LIVE_STREAM detector is constructed. */
data class PoseLandmarkerLiveListenerBundle(
    val resultListener: (PoseLandmarkerDetection, Long, MPImage) -> Unit,
    val errorListener: (Throwable) -> Unit,
)

/** Stable attribution for failures raised while translating one native result. */
class PoseLandmarkerLiveCallbackException(
    val sourceErrorCode: String,
    cause: Throwable,
) : RuntimeException(cause.message, cause)

/**
 * Contains result mapping and routes mapping failures through the same error path as native
 * backend errors. The outer catch ensures no listener exception can escape MediaPipe's thread.
 */
internal fun notifyLiveResultSafely(
    timestampMs: () -> Long,
    callbackImage: MPImage,
    listeners: PoseLandmarkerLiveListenerBundle,
    mapResult: () -> PoseLandmarkerDetection,
) {
    try {
        val extractedTimestamp = try {
            timestampMs()
        } catch (error: Throwable) {
            throw PoseLandmarkerLiveCallbackException("result_timestamp_failed", error)
        }
        listeners.resultListener(mapResult(), extractedTimestamp, callbackImage)
    } catch (error: Throwable) {
        try {
            listeners.errorListener(
                error as? PoseLandmarkerLiveCallbackException
                    ?: PoseLandmarkerLiveCallbackException("result_mapping_failed", error),
            )
        } catch (_: Throwable) {
            // Native listener containment is fail-closed even if the error sink itself fails.
        }
    }
}

/** Host-testable seam around MediaPipe's asynchronous LIVE_STREAM call. */
interface PoseLandmarkerLiveDetector {
    fun detectAsync(image: MPImage, backendTimestampMs: Long)

    fun close()
}

/** Real native detector. Mapping stays at this boundary because result construction is package-private. */
class RealPoseLandmarkerLiveDetector(
    private val landmarker: PoseLandmarker,
) : PoseLandmarkerLiveDetector {
    override fun detectAsync(image: MPImage, backendTimestampMs: Long) {
        landmarker.detectAsync(image, backendTimestampMs)
    }

    override fun close() = landmarker.close()
}
