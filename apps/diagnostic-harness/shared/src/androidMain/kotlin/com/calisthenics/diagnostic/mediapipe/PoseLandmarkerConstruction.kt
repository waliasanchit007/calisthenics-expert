package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.model.ModelProvenance
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer

/**
 * Provenance as captured at the MediaPipe construction site: the exact local values this function
 * hands to [PoseLandmarker.PoseLandmarkerOptions.builder], not a re-serialization of
 * [ModelProvenance]. MediaPipe's `PoseLandmarkerOptions` getters are package-private, so a running
 * task's options cannot be read back; this mirrors the same construction-site-recording pattern
 * `ReplayRunner.RecordedProvenance` already uses for that reason.
 */
data class PoseLandmarkerConstructionEvidence(
    val modelSha256: String,
    val tasksVisionVersion: String,
    val runningMode: String,
    val delegate: String,
    val numPoses: Int,
    val minPoseDetectionConfidence: Float,
    val minPosePresenceConfidence: Float,
    val minTrackingConfidence: Float,
    val outputSegmentationMasks: Boolean,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
)

/** The real MediaPipe options object plus the evidence recorded from the same construction values. */
data class PoseLandmarkerConstructionResult(
    val options: PoseLandmarker.PoseLandmarkerOptions,
    val evidence: PoseLandmarkerConstructionEvidence,
)

private fun ModelProvenance.taskOptionFloat(key: String): Float =
    (taskOptions.getValue(key) as Number).toFloat()

private fun ModelProvenance.taskOptionInt(key: String): Int = (taskOptions.getValue(key) as Number).toInt()

private fun ModelProvenance.taskOptionBoolean(key: String): Boolean = taskOptions.getValue(key) as Boolean

/**
 * Builds the real MediaPipe options object from the pinned [provenance] and the runtime-verified
 * [modelBytes], and records evidence from the identical local values passed to the builder — the
 * delegate handed to [BaseOptions] is [ModelProvenance.delegate] verbatim, never hardcoded, so a
 * pinned GPU delegate cannot be silently presented as CPU (design section 7, check 4).
 */
fun buildPoseLandmarkerOptions(
    provenance: ModelProvenance,
    modelBytes: ByteArray,
    modelInputWidth: Int,
    modelInputHeight: Int,
): PoseLandmarkerConstructionResult {
    val delegate = Delegate.valueOf(provenance.delegate)
    val runningMode = RunningMode.valueOf(provenance.runningMode)
    val numPoses = provenance.taskOptionInt("num_poses")
    val minPoseDetectionConfidence = provenance.taskOptionFloat("min_pose_detection_confidence")
    val minPosePresenceConfidence = provenance.taskOptionFloat("min_pose_presence_confidence")
    val minTrackingConfidence = provenance.taskOptionFloat("min_tracking_confidence")
    val outputSegmentationMasks = provenance.taskOptionBoolean("output_segmentation_masks")

    // MediaPipe's native bridge rejects a heap-backed ByteBuffer at BaseOptions.Builder.build()
    // time, so the verified bytes must be copied into a direct buffer here.
    val directModelBuffer = ByteBuffer.allocateDirect(modelBytes.size).put(modelBytes).also { it.flip() }

    val baseOptions = BaseOptions.builder()
        .setModelAssetBuffer(directModelBuffer)
        .setDelegate(delegate)
        .build()

    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
        .setBaseOptions(baseOptions)
        .setRunningMode(runningMode)
        .setNumPoses(numPoses)
        .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
        .setMinPosePresenceConfidence(minPosePresenceConfidence)
        .setMinTrackingConfidence(minTrackingConfidence)
        .setOutputSegmentationMasks(outputSegmentationMasks)
        .build()

    val evidence = PoseLandmarkerConstructionEvidence(
        modelSha256 = provenance.sha256,
        tasksVisionVersion = provenance.tasksVisionVersion,
        runningMode = runningMode.name,
        delegate = delegate.name,
        numPoses = numPoses,
        minPoseDetectionConfidence = minPoseDetectionConfidence,
        minPosePresenceConfidence = minPosePresenceConfidence,
        minTrackingConfidence = minTrackingConfidence,
        outputSegmentationMasks = outputSegmentationMasks,
        modelInputWidth = modelInputWidth,
        modelInputHeight = modelInputHeight,
    )

    return PoseLandmarkerConstructionResult(options, evidence)
}
