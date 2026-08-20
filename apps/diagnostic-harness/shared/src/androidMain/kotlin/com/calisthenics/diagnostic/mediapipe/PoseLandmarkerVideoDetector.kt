package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.contract.Landmark
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.components.containers.Landmark as MpLandmark

/**
 * One synchronous VIDEO-mode detection, translated out of MediaPipe's own result type.
 *
 * [poseCount] is the number of pose entries the backend returned, recorded separately from
 * [posePresent] because design section 7 check 5 compares per-frame pose *cardinality* across runs:
 * a tracker leak or an unpinned `num_poses` changes the count while `posePresent` stays true.
 */
data class PoseLandmarkerDetection(
    val posePresent: Boolean,
    val poseCount: Int,
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
 * Translates the two landmark projections of one `PoseLandmarkerResult` into the adapter's own
 * result type.
 *
 * Extracted from [RealPoseLandmarkerVideoDetector] so the meaning of a backend result — pose
 * cardinality, presence, and landmark mapping — is covered by host tests, leaving the instrumented
 * device run to prove only that the native call itself happens. It takes the projections rather
 * than `PoseLandmarkerResult` because MediaPipe 1.0.0 keeps that type's only factory
 * package-private, so no host test could construct one.
 *
 * Only the first pose is mapped: `num_poses` is pinned to 1, and mapping further entries would
 * invent landmark observations the pinned options never asked for. [PoseLandmarkerDetection.poseCount]
 * stays honest regardless, which is what check 5 compares.
 */
fun poseLandmarkerDetectionOf(
    poses: List<List<NormalizedLandmark>>,
    worldPoses: List<List<MpLandmark>>,
): PoseLandmarkerDetection {
    val normalized = poses.firstOrNull()
    val world = worldPoses.firstOrNull()
    return PoseLandmarkerDetection(
        posePresent = !normalized.isNullOrEmpty(),
        poseCount = poses.size,
        normalizedLandmarks = normalized?.let(::mapNormalizedLandmarks),
        worldLandmarks = world?.let(::mapWorldLandmarks),
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
        return poseLandmarkerDetectionOf(result.landmarks(), result.worldLandmarks())
    }

    override fun close() = landmarker.close()
}
