package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.ModelProvenanceException
import com.google.mediapipe.framework.image.MPImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

private val VALID_MODEL_BYTES = ByteArray(64) { it.toByte() }

private val PROVENANCE = ModelProvenance(
    assetName = "pose_landmarker_full.task",
    sha256 = Sha256Digest.hex(VALID_MODEL_BYTES),
    byteCount = VALID_MODEL_BYTES.size.toLong(),
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

/**
 * A trivial stand-in for the real detector. It never touches MediaPipe's native bridge, so it
 * exercises [PoseLandmarkerReplayAdapterFactory]'s own contract (fresh construction, checksum
 * gating) without replacing [PoseLandmarkerReplayInferencePort] or [InferencePort] with a fake:
 * only the innermost native-construction seam is substituted.
 */
private class StubDetector : PoseLandmarkerVideoDetector {
    override fun detectForVideo(image: MPImage, backendTimestampMs: Long) =
        PoseLandmarkerDetection(posePresent = false, poseCount = 0, normalizedLandmarks = null, worldLandmarks = null)

    override fun close() = Unit
}

class PoseLandmarkerReplayAdapterFactoryTest {

    @Test
    fun everyCreateCallConstructsAFreshDetectorInstance() {
        var constructions = 0
        val detectors = mutableListOf<StubDetector>()
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { VALID_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = {
                constructions++
                StubDetector().also(detectors::add)
            },
        )

        factory.create()
        factory.create()

        assertEquals(2, constructions, "each replay run must construct its own PoseLandmarker")
        assertNotSame(detectors[0], detectors[1])
    }

    // Mutation target: caching/memoizing the detector across create() calls (e.g. `by lazy`)
    // must turn the above test red; this test pins the same behaviour from the other direction.
    @Test
    fun constructDetectorSeamIsInvokedOnceForEachCreateCall() {
        var constructions = 0
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { VALID_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { constructions++; StubDetector() },
        )
        repeat(3) { factory.create() }
        assertEquals(3, constructions)
    }

    @Test
    fun createFailsClosedOnRuntimeShaMismatchBeforeConstructingABackend() {
        var constructions = 0
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { ByteArray(VALID_MODEL_BYTES.size) },
            nowNs = { 0L },
            constructDetector = { constructions++; StubDetector() },
        )

        assertFailsWith<ModelProvenanceException> { factory.create() }
        assertEquals(0, constructions, "a checksum mismatch must never reach backend construction")
    }

    @Test
    fun createFailsClosedOnRuntimeByteCountMismatchBeforeConstructingABackend() {
        var constructions = 0
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { VALID_MODEL_BYTES + byteArrayOf(0) },
            nowNs = { 0L },
            constructDetector = { constructions++; StubDetector() },
        )

        assertFailsWith<ModelProvenanceException> { factory.create() }
        assertEquals(0, constructions)
    }

    @Test
    fun createRecordsConstructionEvidenceOnSuccess() {
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { VALID_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { StubDetector() },
        )
        factory.create()
        val evidence = factory.lastConstructionEvidence
        assertEquals("VIDEO", evidence?.runningMode)
        assertEquals("CPU", evidence?.delegate)
        assertEquals(256, evidence?.modelInputWidth)
        assertEquals(256, evidence?.modelInputHeight)
    }

    @Test
    fun createRejectsANonVideoRunningModeBeforeReadingTheModel() {
        var readCalls = 0
        val liveProvenance = PROVENANCE.copy(runningMode = "LIVE_STREAM")
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = liveProvenance,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { readCalls++; VALID_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { StubDetector() },
        )
        assertFailsWith<ModelProvenanceException> { factory.create() }
        assertEquals(0, readCalls)
    }

    // Review finding (Important): production replay must be pinned to CPU, not merely capable of
    // recording whatever delegate provenance declares (that recording behaviour is separately
    // covered, at the lower `buildPoseLandmarkerOptions` level, by
    // PoseLandmarkerConstructionTest.aPinnedGpuDelegateIsRecordedRatherThanSilentlyPresentedAsCpu).
    @Test
    fun createRejectsAGpuDelegateBeforeReadingTheModel() {
        var readCalls = 0
        val gpuProvenance = PROVENANCE.copy(delegate = "GPU")
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = gpuProvenance,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { readCalls++; VALID_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { StubDetector() },
        )
        assertFailsWith<ModelProvenanceException> { factory.create() }
        assertEquals(0, readCalls, "a non-CPU delegate must never reach model reading or backend construction")
    }

    // Review finding (Important): evidence must be published only after successful detector
    // construction, and cleared (never stale) on every failed create().
    @Test
    fun createDoesNotPublishConstructionEvidenceWhenDetectorConstructionFails() {
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { VALID_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { throw RuntimeException("native construction failed") },
        )

        assertFailsWith<RuntimeException> { factory.create() }
        assertEquals(null, factory.lastConstructionEvidence)
    }

    @Test
    fun createClearsStaleEvidenceWhenAConstructionFailureFollowsAPriorSuccess() {
        var shouldFail = false
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { VALID_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = {
                if (shouldFail) throw RuntimeException("native construction failed") else StubDetector()
            },
        )

        factory.create()
        assertEquals("CPU", factory.lastConstructionEvidence?.delegate, "sanity: first create() must succeed")

        shouldFail = true
        assertFailsWith<RuntimeException> { factory.create() }

        assertEquals(
            null,
            factory.lastConstructionEvidence,
            "a failed create() must never leave a prior run's evidence looking current",
        )
    }
}
