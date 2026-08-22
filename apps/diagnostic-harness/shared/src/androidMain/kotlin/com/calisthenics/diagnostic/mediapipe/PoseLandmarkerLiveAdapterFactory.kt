package com.calisthenics.diagnostic.mediapipe

import android.content.Context
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.ModelProvenanceException
import com.calisthenics.diagnostic.model.verifyModelAsset
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File

/** Builds one fresh LIVE_STREAM detector and port per live session. */
class PoseLandmarkerLiveAdapterFactory(
    private val provenance: ModelProvenance,
    private val modelInputWidth: Int,
    private val modelInputHeight: Int,
    private val readModelBytes: () -> ByteArray,
    private val nowNs: () -> Long,
    private val constructDetector: (
        PoseLandmarker.PoseLandmarkerOptions,
        PoseLandmarkerLiveListenerBundle,
    ) -> PoseLandmarkerLiveDetector,
) {
    var lastConstructionEvidence: PoseLandmarkerConstructionEvidence? = null
        private set

    var diagnosticPreconstructionCallbackDrops: Long = 0
        private set

    fun create(): PoseLandmarkerLiveInferencePort {
        lastConstructionEvidence = null
        diagnosticPreconstructionCallbackDrops = 0
        requireLiveProvenance(provenance)

        val modelBytes = readModelBytes()
        verifyModelAsset(
            provenance = provenance,
            actualSha256 = Sha256Digest.hex(modelBytes),
            actualByteCount = modelBytes.size.toLong(),
        )

        var port: PoseLandmarkerLiveInferencePort? = null
        var preconstructionDrops = 0L
        val listeners = PoseLandmarkerLiveListenerBundle(
            resultListener = { detection, timestampMs, image ->
                port?.onResult(detection, timestampMs, image) ?: run { preconstructionDrops++ }
            },
            errorListener = { error ->
                port?.onError(error) ?: run { preconstructionDrops++ }
            },
        )
        val constructed = buildPoseLandmarkerOptions(
            provenance,
            modelBytes,
            modelInputWidth,
            modelInputHeight,
            liveListeners = listeners,
        )
        val detector = constructDetector(constructed.options, listeners)
        val createdPort = PoseLandmarkerLiveInferencePort(
            detector,
            modelInputWidth,
            modelInputHeight,
            nowNs,
        )
        port = createdPort
        diagnosticPreconstructionCallbackDrops = preconstructionDrops
        lastConstructionEvidence = constructed.evidence
        return createdPort
    }
}

private fun requireLiveProvenance(provenance: ModelProvenance) {
    if (provenance.runningMode != "LIVE_STREAM") {
        throw ModelProvenanceException(
            "live capture requires running_mode LIVE_STREAM, found ${provenance.runningMode}",
        )
    }
}

fun realLiveDetectorConstructor(context: Context):
    (PoseLandmarker.PoseLandmarkerOptions, PoseLandmarkerLiveListenerBundle) -> PoseLandmarkerLiveDetector =
    { options, _ -> RealPoseLandmarkerLiveDetector(PoseLandmarker.createFromOptions(context, options)) }

fun readLiveModelAssetBytes(assetFile: File): () -> ByteArray = { assetFile.readBytes() }
