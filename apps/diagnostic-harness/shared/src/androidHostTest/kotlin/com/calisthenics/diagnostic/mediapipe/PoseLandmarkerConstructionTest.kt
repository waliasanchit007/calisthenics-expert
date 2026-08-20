package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.model.ModelProvenance
import kotlin.test.Test
import kotlin.test.assertEquals

private val PROVENANCE = ModelProvenance(
    assetName = "pose_landmarker_full.task",
    sha256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1",
    byteCount = 10L,
    sourceUrl = "https://example.invalid/pose_landmarker_full.task",
    modelFamily = "pose_landmarker",
    modelVariant = "full",
    quantization = "float16",
    upstreamRevision = "1",
    tasksVisionVersion = "1.0.0",
    runningMode = "VIDEO",
    delegate = "CPU",
    taskOptions = mapOf(
        "num_poses" to 1,
        "min_pose_detection_confidence" to 0.5,
        "min_pose_presence_confidence" to 0.5,
        "min_tracking_confidence" to 0.5,
        "output_segmentation_masks" to false,
    ),
)

class PoseLandmarkerConstructionTest {

    @Test
    fun evidenceRecordsThePinnedValuesUsedToBuildTheRealOptions() {
        val constructed = buildPoseLandmarkerOptions(PROVENANCE, ByteArray(10), modelInputWidth = 256, modelInputHeight = 256)

        assertEquals(PROVENANCE.sha256, constructed.evidence.modelSha256)
        assertEquals("1.0.0", constructed.evidence.tasksVisionVersion)
        assertEquals("VIDEO", constructed.evidence.runningMode)
        assertEquals("CPU", constructed.evidence.delegate)
        assertEquals(1, constructed.evidence.numPoses)
        assertEquals(0.5f, constructed.evidence.minPoseDetectionConfidence)
        assertEquals(0.5f, constructed.evidence.minPosePresenceConfidence)
        assertEquals(0.5f, constructed.evidence.minTrackingConfidence)
        assertEquals(false, constructed.evidence.outputSegmentationMasks)
        assertEquals(256, constructed.evidence.modelInputWidth)
        assertEquals(256, constructed.evidence.modelInputHeight)
    }

    // Guards against the "silent GPU fallback" vacuity trap named in design section 7 check 4:
    // recorded evidence must track a pinned GPU delegate, not a hardcoded CPU value.
    @Test
    fun aPinnedGpuDelegateIsRecordedRatherThanSilentlyPresentedAsCpu() {
        val gpuProvenance = PROVENANCE.copy(delegate = "GPU")
        val constructed = buildPoseLandmarkerOptions(gpuProvenance, ByteArray(10), 256, 256)
        assertEquals("GPU", constructed.evidence.delegate)
    }

    @Test
    fun buildingTheRealOptionsRequiresNoDeviceOrNativeLibrary() {
        // Merely building com.google.mediapipe...PoseLandmarker.PoseLandmarkerOptions must not
        // touch the native bridge; only PoseLandmarker.createFromOptions does. This test running
        // at all on a plain JVM host is the evidence for that split.
        buildPoseLandmarkerOptions(PROVENANCE, ByteArray(10), 256, 256)
    }
}
