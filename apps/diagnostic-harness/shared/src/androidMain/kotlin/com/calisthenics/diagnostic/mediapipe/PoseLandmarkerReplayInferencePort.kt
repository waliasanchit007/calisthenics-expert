package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.preprocessing.Rgb24
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer

/**
 * The `androidMain` [InferencePort] over MediaPipe Tasks Vision: VIDEO running mode, synchronous
 * `detectForVideo`, one fixed model-input size per instance. Backend resize and tracker-derived
 * ROI are MediaPipe's own behaviour inside [detector]; this class asserts nothing about them.
 *
 * Also [Disposable]: this port owns one fresh native `PoseLandmarker` per replay run (see
 * [PoseLandmarkerReplayAdapterFactory]), so the caller that owns a run — whichever terminal path
 * it ends on (completed, failed, or abandoned) — must call [dispose] exactly once that run is
 * over. [dispose] itself is idempotent, so a caller that is unsure whether teardown already ran
 * may call it again safely.
 */
class PoseLandmarkerReplayInferencePort(
    private val detector: PoseLandmarkerVideoDetector,
    private val modelInputWidth: Int,
    private val modelInputHeight: Int,
    private val nowNs: () -> Long,
) : InferencePort, Disposable {
    private var disposed = false

    override fun submit(
        submissionIndex: Long,
        canonicalInput: ByteArray,
        backendTimestampMs: Long,
        callback: (InferenceResult) -> Unit,
    ) {
        val expectedLength = Rgb24.byteLength(modelInputWidth, modelInputHeight)
        require(canonicalInput.size == expectedLength) {
            "canonical input length ${canonicalInput.size} does not match the declared model-input " +
                "dimensions ${modelInputWidth}x$modelInputHeight (expected $expectedLength bytes)"
        }

        // MediaPipe's native bridge cannot address a heap-backed buffer: `detectForVideo` fails
        // with "Cannot get direct access to the input buffer. It should be created using
        // allocateDirect", the same constraint `BaseOptions.setModelAssetBuffer` imposes on the
        // model bytes. The canonical plane is therefore copied into a fresh direct buffer per
        // submission — fresh, not pooled, so no frame can be handed a buffer another frame still
        // owns, which is exactly the "buffer reuse" vacuity check 2 exists to catch.
        val directInput = ByteBuffer.allocateDirect(canonicalInput.size)
            .put(canonicalInput)
            .also { it.flip() }

        val image: MPImage = ByteBufferImageBuilder(
            directInput,
            modelInputWidth,
            modelInputHeight,
            MPImage.IMAGE_FORMAT_RGB,
        ).build()

        val outcome = try {
            detector.detectForVideo(image, backendTimestampMs)
        } catch (error: Throwable) {
            callback(
                InferenceResult.Failure(
                    failureSource = "backend",
                    classification = "backend_call_failed",
                    detail = error.message,
                ),
            )
            return
        } finally {
            image.close()
        }

        callback(
            InferenceResult.Success(
                inferenceEndNs = nowNs(),
                posePresent = outcome.posePresent,
                normalizedImageLandmarks = outcome.normalizedLandmarks,
                worldLandmarks = outcome.worldLandmarks,
            ),
        )
    }

    override fun cancel(submissionIndex: Long) = Unit

    /** Idempotent: only the first call reaches [PoseLandmarkerVideoDetector.close]. */
    override fun dispose() {
        if (disposed) return
        disposed = true
        detector.close()
    }
}
