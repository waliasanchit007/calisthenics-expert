package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferenceResult
import com.google.mediapipe.framework.image.MPImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WIDTH = 4
private const val HEIGHT = 4

private class FakeDetector(
    private val detection: PoseLandmarkerDetection? = null,
    private val failWith: Throwable? = null,
) : PoseLandmarkerVideoDetector {
    var calls = 0
        private set
    var lastImageWidth: Int? = null
        private set
    var lastImageHeight: Int? = null
        private set
    var lastTimestampMs: Long? = null
        private set
    var closeCalls = 0
        private set

    override fun detectForVideo(image: MPImage, backendTimestampMs: Long): PoseLandmarkerDetection {
        calls++
        lastImageWidth = image.width
        lastImageHeight = image.height
        lastTimestampMs = backendTimestampMs
        failWith?.let { throw it }
        return detection!!
    }

    override fun close() {
        closeCalls++
    }
}

class PoseLandmarkerReplayInferencePortTest {

    @Test
    fun rejectsCanonicalInputThatDoesNotMatchDeclaredModelInputDimensions() {
        val detector = FakeDetector(PoseLandmarkerDetection(false, null, null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })
        var captured: InferenceResult? = null

        assertFailsWith<IllegalArgumentException> {
            port.submit(0L, ByteArray(WIDTH * HEIGHT * 3 - 1), 10L) { captured = it }
        }

        assertEquals(0, detector.calls, "the backend must never see a frame at the wrong declared size")
        assertNull(captured)
    }

    @Test
    fun submitsACorrectlySizedFrameAndTranslatesAPosePresentSuccess() {
        val landmarks = listOf(Landmark(0, null, 0.1, 0.2, 0.3, 0.9, 0.8))
        val detector = FakeDetector(PoseLandmarkerDetection(true, landmarks, worldLandmarks = null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 42L })
        var captured: InferenceResult? = null

        port.submit(0L, ByteArray(WIDTH * HEIGHT * 3), 10L) { captured = it }

        assertEquals(1, detector.calls)
        assertEquals(10L, detector.lastTimestampMs)
        assertEquals(WIDTH, detector.lastImageWidth)
        assertEquals(HEIGHT, detector.lastImageHeight)

        val success = captured as InferenceResult.Success
        assertTrue(success.posePresent)
        assertEquals(42L, success.inferenceEndNs)
        assertEquals(landmarks, success.normalizedImageLandmarks)
    }

    @Test
    fun translatesADetectorExceptionIntoABackendCallFailure() {
        val detector = FakeDetector(failWith = RuntimeException("boom"))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })
        var captured: InferenceResult? = null

        port.submit(0L, ByteArray(WIDTH * HEIGHT * 3), 10L) { captured = it }

        val failure = captured as InferenceResult.Failure
        assertEquals("backend", failure.failureSource)
        assertEquals("backend_call_failed", failure.classification)
        assertEquals("boom", failure.detail)
    }

    // Review finding (Critical): the returned run owner must expose an idempotent close/dispose
    // path so a completed, failed, or abandoned run's native PoseLandmarker is never leaked.
    @Test
    fun disposeClosesTheUnderlyingDetectorExactlyOnce() {
        val detector = FakeDetector(PoseLandmarkerDetection(false, null, null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })

        port.dispose()
        port.dispose()
        port.dispose()

        assertEquals(1, detector.closeCalls, "dispose() must be idempotent: only the first call may reach the detector")
    }

    @Test
    fun disposeClosesTheDetectorEvenWhenNoFrameWasEverSubmitted() {
        // Models an abandoned run: a run constructed but never used before teardown.
        val detector = FakeDetector(PoseLandmarkerDetection(false, null, null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })

        port.dispose()

        assertEquals(1, detector.closeCalls)
        assertEquals(0, detector.calls)
    }
}
