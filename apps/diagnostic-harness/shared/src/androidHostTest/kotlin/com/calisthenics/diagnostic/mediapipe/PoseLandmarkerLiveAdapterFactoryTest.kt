package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.ModelProvenanceException
import com.google.mediapipe.framework.image.MPImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val LIVE_MODEL_BYTES = ByteArray(64) { it.toByte() }

private val LIVE_PROVENANCE = ModelProvenance(
    assetName = "pose_landmarker_full.task",
    sha256 = Sha256Digest.hex(LIVE_MODEL_BYTES),
    byteCount = LIVE_MODEL_BYTES.size.toLong(),
    sourceUrl = "https://example.invalid/pose_landmarker_full.task",
    modelFamily = "pose_landmarker",
    modelVariant = "full",
    quantization = "float16",
    upstreamRevision = "1",
    tasksVisionVersion = "1.0.0",
    runningMode = "LIVE_STREAM",
    delegate = "CPU",
    taskOptions = mapOf(
        "num_poses" to 1,
        "min_pose_detection_confidence" to 0.5,
        "min_pose_presence_confidence" to 0.5,
        "min_tracking_confidence" to 0.5,
        "output_segmentation_masks" to false,
    ),
)

private class FactoryStubDetector : PoseLandmarkerLiveDetector {
    override fun detectAsync(image: MPImage, backendTimestampMs: Long) = Unit
    override fun close() = Unit
}

class PoseLandmarkerLiveAdapterFactoryTest {
    @Test
    fun liveConstructionWiresBothListenersBeforeDetectorConstruction() {
        var constructions = 0
        var suppliedListeners: PoseLandmarkerLiveListenerBundle? = null
        val factory = PoseLandmarkerLiveAdapterFactory(
            provenance = LIVE_PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { LIVE_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { _, listeners ->
                constructions++
                suppliedListeners = listeners
                FactoryStubDetector()
            },
        )

        factory.create().dispose()

        assertEquals(1, constructions)
        assertEquals(true, suppliedListeners != null, "LIVE_STREAM requires a result/error listener bundle")
        assertEquals("LIVE_STREAM", factory.lastConstructionEvidence?.runningMode)
    }

    @Test
    fun preconstructionCallbacksAreRecordedWithoutDereferencingAnUninstalledPort() {
        var listeners: PoseLandmarkerLiveListenerBundle? = null
        val factory = PoseLandmarkerLiveAdapterFactory(
            provenance = LIVE_PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { LIVE_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { _, supplied ->
                listeners = supplied
                supplied.errorListener(IllegalStateException("too early"))
                FactoryStubDetector()
            },
        )

        factory.create().dispose()

        assertEquals(1L, factory.diagnosticPreconstructionCallbackDrops)
        assertEquals(true, listeners != null)
    }

    @Test
    fun videoProvenanceIsRejectedBeforeReadingOrConstructingLiveBackend() {
        var reads = 0
        var constructions = 0
        val factory = PoseLandmarkerLiveAdapterFactory(
            provenance = LIVE_PROVENANCE.copy(runningMode = "VIDEO"),
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { reads++; LIVE_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { _, _ -> constructions++; FactoryStubDetector() },
        )

        assertFailsWith<ModelProvenanceException> { factory.create() }
        assertEquals(0, reads, "VIDEO provenance must not be admitted by the live factory")
        assertEquals(0, constructions)
    }

    @Test
    fun imageProvenanceIsRejectedBeforeReadingOrConstructingLiveBackend() {
        var reads = 0
        val factory = PoseLandmarkerLiveAdapterFactory(
            provenance = LIVE_PROVENANCE.copy(runningMode = "IMAGE"),
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { reads++; LIVE_MODEL_BYTES },
            nowNs = { 0L },
            constructDetector = { _, _ -> FactoryStubDetector() },
        )

        assertFailsWith<ModelProvenanceException> { factory.create() }
        assertEquals(0, reads)
    }

    @Test
    fun runtimeModelMismatchFailsBeforeLiveDetectorConstruction() {
        var constructions = 0
        val factory = PoseLandmarkerLiveAdapterFactory(
            provenance = LIVE_PROVENANCE,
            modelInputWidth = 256,
            modelInputHeight = 256,
            readModelBytes = { ByteArray(LIVE_MODEL_BYTES.size) },
            nowNs = { 0L },
            constructDetector = { _, _ -> constructions++; FactoryStubDetector() },
        )

        assertFailsWith<ModelProvenanceException> { factory.create() }
        assertEquals(0, constructions)
    }
}
