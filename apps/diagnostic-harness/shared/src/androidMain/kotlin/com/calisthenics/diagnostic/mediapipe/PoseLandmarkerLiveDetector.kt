package com.calisthenics.diagnostic.mediapipe

import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/** Callbacks installed in MediaPipe options before a LIVE_STREAM detector is constructed. */
data class PoseLandmarkerLiveListenerBundle(
    val resultListener: (PoseLandmarkerDetection, Long, MPImage) -> Unit,
    val errorListener: (RuntimeException) -> Unit,
)

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
