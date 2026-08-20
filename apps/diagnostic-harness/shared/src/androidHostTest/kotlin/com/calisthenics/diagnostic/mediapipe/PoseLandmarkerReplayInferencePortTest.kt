package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferenceResult
import com.google.mediapipe.framework.image.ByteBufferExtractor
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

    // The buffer is inspected here rather than after the call because the port closes the MPImage
    // as soon as the backend returns.
    var lastBufferWasDirect: Boolean? = null
        private set
    var lastBufferContents: ByteArray? = null
        private set

    override fun detectForVideo(image: MPImage, backendTimestampMs: Long): PoseLandmarkerDetection {
        calls++
        lastImageWidth = image.width
        lastImageHeight = image.height
        lastTimestampMs = backendTimestampMs
        val buffer = ByteBufferExtractor.extract(image)
        lastBufferWasDirect = buffer.isDirect
        lastBufferContents = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
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
        val detector = FakeDetector(PoseLandmarkerDetection(false, 0, null, null))
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
        val detector = FakeDetector(PoseLandmarkerDetection(true, 1, landmarks, worldLandmarks = null))
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

    // The instrumented run on API 37 proved this is not cosmetic: MediaPipe's native bridge
    // rejects a heap-backed image buffer with "Cannot get direct access to the input buffer. It
    // should be created using allocateDirect", exactly as it already does for the model buffer at
    // BaseOptions.build() time. Every frame failed as backend_call_failed until the canonical plane
    // was copied into a direct buffer, so this is the host-level regression for that defect.
    @Test
    fun handsTheBackendADirectBufferCarryingTheExactCanonicalBytes() {
        val canonical = ByteArray(WIDTH * HEIGHT * 3) { index -> ((index * 7) % 251).toByte() }
        val detector = FakeDetector(PoseLandmarkerDetection(true, 1, emptyList(), null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })

        port.submit(0L, canonical, 10L) {}

        assertEquals(true, detector.lastBufferWasDirect, "MediaPipe's native bridge requires a direct buffer")
        assertTrue(
            canonical.contentEquals(detector.lastBufferContents),
            "the copy into the direct buffer must preserve the canonical plane exactly",
        )
    }

    @Test
    fun aLaterSubmissionIsNotContaminatedByTheBufferOfAnEarlierOne() {
        val first = ByteArray(WIDTH * HEIGHT * 3) { 1 }
        val second = ByteArray(WIDTH * HEIGHT * 3) { 2 }
        val detector = FakeDetector(PoseLandmarkerDetection(true, 1, emptyList(), null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })

        port.submit(0L, first, 10L) {}
        port.submit(1L, second, 11L) {}

        assertTrue(second.contentEquals(detector.lastBufferContents))
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
        val detector = FakeDetector(PoseLandmarkerDetection(false, 0, null, null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })

        port.dispose()
        port.dispose()
        port.dispose()

        assertEquals(1, detector.closeCalls, "dispose() must be idempotent: only the first call may reach the detector")
    }

    @Test
    fun disposeClosesTheDetectorEvenWhenNoFrameWasEverSubmitted() {
        // Models an abandoned run: a run constructed but never used before teardown.
        val detector = FakeDetector(PoseLandmarkerDetection(false, 0, null, null))
        val port = PoseLandmarkerReplayInferencePort(detector, WIDTH, HEIGHT, nowNs = { 0L })

        port.dispose()

        assertEquals(1, detector.closeCalls)
        assertEquals(0, detector.calls)
    }
}
