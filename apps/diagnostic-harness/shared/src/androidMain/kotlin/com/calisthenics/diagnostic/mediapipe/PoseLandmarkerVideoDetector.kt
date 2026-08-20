package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.contract.Landmark
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.components.containers.Landmark as MpLandmark

/** One synchronous VIDEO-mode detection, translated out of MediaPipe's own result type. */
data class PoseLandmarkerDetection(
    val posePresent: Boolean,
    val normalizedLandmarks: List<Landmark>?,
    val worldLandmarks: List<Landmark>?,
)

/**
 * The narrow seam between the [com.calisthenics.diagnostic.session.InferencePort] adapter and the
 * MediaPipe backend. Only [RealPoseLandmarkerVideoDetector] reaches the native `PoseLandmarker`;
 * everything above this interface is real, testable Kotlin.
 */
interface PoseLandmarkerVideoDetector {
    fun detectForVideo(image: MPImage, backendTimestampMs: Long): PoseLandmarkerDetection

    fun close()
}

fun mapNormalizedLandmarks(landmarks: List<NormalizedLandmark>): List<Landmark> = landmarks.mapIndexed { index, landmark ->
    Landmark(
        index = index,
        name = null,
        x = landmark.x().toDouble(),
        y = landmark.y().toDouble(),
        z = landmark.z().toDouble(),
        visibility = landmark.visibility().orElse(null)?.toDouble(),
        presence = landmark.presence().orElse(null)?.toDouble(),
    )
}

fun mapWorldLandmarks(landmarks: List<MpLandmark>): List<Landmark> = landmarks.mapIndexed { index, landmark ->
    Landmark(
        index = index,
        name = null,
        x = landmark.x().toDouble(),
        y = landmark.y().toDouble(),
        z = landmark.z().toDouble(),
        visibility = landmark.visibility().orElse(null)?.toDouble(),
        presence = landmark.presence().orElse(null)?.toDouble(),
    )
}

/**
 * Wraps one real `PoseLandmarker`. A fresh instance is constructed per replay run by
 * [PoseLandmarkerReplayAdapterFactory]; this class never outlives that run and never shares
 * tracker state across runs.
 */
class RealPoseLandmarkerVideoDetector(private val landmarker: PoseLandmarker) : PoseLandmarkerVideoDetector {
    override fun detectForVideo(image: MPImage, backendTimestampMs: Long): PoseLandmarkerDetection {
        val result = landmarker.detectForVideo(image, backendTimestampMs)
        val normalized = result.landmarks().firstOrNull()
        val world = result.worldLandmarks().firstOrNull()
        return PoseLandmarkerDetection(
            posePresent = !normalized.isNullOrEmpty(),
            normalizedLandmarks = normalized?.let(::mapNormalizedLandmarks),
            worldLandmarks = world?.let(::mapWorldLandmarks),
        )
    }

    override fun close() = landmarker.close()
}
