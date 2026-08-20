package com.calisthenics.diagnostic.mediapipe

import android.content.Context
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.ModelProvenanceException
import com.calisthenics.diagnostic.model.requireReplayProvenance
import com.calisthenics.diagnostic.model.verifyModelAsset
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File

/**
 * Builds one fresh [PoseLandmarkerReplayInferencePort] per call to [create]. Every call:
 *
 * 1. re-reads and re-verifies the model asset bytes against the pinned [provenance] — runtime
 *    checksum verification, fails closed on mismatch, before any backend construction;
 * 2. builds the MediaPipe options from the verified bytes;
 * 3. constructs a brand-new detector through [constructDetector] — never a cached/shared
 *    instance, so cross-run tracker state can never leak between replay runs;
 * 4. publishes [lastConstructionEvidence] only once [constructDetector] returns successfully —
 *    a call that fails at any step (including detector construction itself) clears rather than
 *    leaves the previous run's evidence in place, so it never misrepresents a run that never
 *    obtained a backend.
 *
 * [constructDetector] is the narrow factory seam: production wiring points it at
 * [PoseLandmarker.createFromOptions] (see [realDetectorConstructor]); a test can substitute it to
 * observe fresh-instance semantics without replacing this class, [PoseLandmarkerReplayInferencePort],
 * or [InferencePort] with a fake.
 *
 * The returned port is also `Disposable` (see [PoseLandmarkerReplayInferencePort]); the caller
 * that owns one replay run must call `dispose()` on it once that run reaches any terminal path
 * (completed, failed, or abandoned) so its fresh native `PoseLandmarker` is never leaked.
 */
class PoseLandmarkerReplayAdapterFactory(
    private val provenance: ModelProvenance,
    private val modelInputWidth: Int,
    private val modelInputHeight: Int,
    private val readModelBytes: () -> ByteArray,
    private val nowNs: () -> Long,
    private val constructDetector: (PoseLandmarker.PoseLandmarkerOptions) -> PoseLandmarkerVideoDetector,
) {
    /** The construction evidence recorded by the most recent *successful* [create] call, or `null`. */
    var lastConstructionEvidence: PoseLandmarkerConstructionEvidence? = null
        private set

    fun create(): PoseLandmarkerReplayInferencePort {
        // Cleared up front so any failure below — gate rejection, checksum mismatch, or detector
        // construction itself throwing — never leaves a prior run's evidence looking current.
        lastConstructionEvidence = null

        requireReplayProvenance(provenance)
        requireCpuDelegateForAndroidReplay(provenance)

        val modelBytes = readModelBytes()
        verifyModelAsset(
            provenance = provenance,
            actualSha256 = Sha256Digest.hex(modelBytes),
            actualByteCount = modelBytes.size.toLong(),
        )

        val constructed = buildPoseLandmarkerOptions(
            provenance,
            modelBytes,
            modelInputWidth,
            modelInputHeight,
        )

        val detector = constructDetector(constructed.options)
        lastConstructionEvidence = constructed.evidence
        return PoseLandmarkerReplayInferencePort(detector, modelInputWidth, modelInputHeight, nowNs)
    }
}

/**
 * The `androidMain` MediaPipe adapter is pinned to the CPU delegate specifically (design section
 * 7: "The initial replay delegate is CPU to minimize undeclared device-specific GPU variation"),
 * per Task 3's own contract. This is an adapter/construction-boundary rule scoped to this factory,
 * not a change to the more permissive commonMain `requireReplayProvenance` gate: `ReplayRunner`'s
 * check 4 relies on being able to record a pinned GPU delegate honestly rather than have it
 * rejected outright (design section 7 check 4, "silent GPU fallback" vacuity trap).
 */
private fun requireCpuDelegateForAndroidReplay(provenance: ModelProvenance) {
    if (provenance.delegate != "CPU") {
        throw ModelProvenanceException(
            "androidMain MediaPipe replay requires the CPU delegate, found ${provenance.delegate}",
        )
    }
}

/** Production wiring for [PoseLandmarkerReplayAdapterFactory.constructDetector]: the real backend. */
fun realDetectorConstructor(context: Context): (PoseLandmarker.PoseLandmarkerOptions) -> PoseLandmarkerVideoDetector =
    { options -> RealPoseLandmarkerVideoDetector(PoseLandmarker.createFromOptions(context, options)) }

/** Production wiring for [PoseLandmarkerReplayAdapterFactory.readModelBytes]: re-read from disk on every call. */
fun readModelAssetBytes(assetFile: File): () -> ByteArray = { assetFile.readBytes() }
