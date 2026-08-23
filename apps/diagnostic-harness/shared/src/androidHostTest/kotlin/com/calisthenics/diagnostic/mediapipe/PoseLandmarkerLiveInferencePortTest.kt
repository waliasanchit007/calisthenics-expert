package com.calisthenics.diagnostic.mediapipe

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferenceResult
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val LIVE_WIDTH = 4
private const val LIVE_HEIGHT = 4

private class ManualExecutor(private val onExecute: () -> Unit = {}) : Executor {
    private val pending = ArrayDeque<() -> Unit>()

    override fun execute(command: Runnable) {
        onExecute()
        pending += command::run
    }

    fun runNext() = pending.removeFirst().invoke()

    val size: Int get() = pending.size
}

private class FakeLiveDetector : PoseLandmarkerLiveDetector {
    var detectCalls = 0
        private set
    var lastImage: MPImage? = null
        private set
    var lastTimestampMs: Long? = null
        private set
    var closeCalls = 0
        private set
    var onDetect: (() -> Unit)? = null

    override fun detectAsync(image: MPImage, backendTimestampMs: Long) {
        detectCalls++
        lastImage = image
        lastTimestampMs = backendTimestampMs
        onDetect?.invoke()
    }

    override fun close() {
        closeCalls++
    }
}

private fun image(bytes: ByteArray = ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3) { it.toByte() }): MPImage =
    ByteBufferImageBuilder(
        ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() },
        LIVE_WIDTH,
        LIVE_HEIGHT,
        MPImage.IMAGE_FORMAT_RGB,
    ).build()

private fun imageBytes(value: MPImage): ByteArray {
    val buffer = ByteBufferExtractor.extract(value)
    return ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
}

class PoseLandmarkerLiveInferencePortTest {
    @Test
    fun detectAsyncReturningDoesNotReleaseTheOwnedInputOrSlot() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 9L }, executor)
        val input = ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3) { 7 }

        port.submit(3L, input, 17L) {}

        assertEquals(1, detector.detectCalls)
        assertEquals(17L, detector.lastTimestampMs)
        assertEquals(input.toList(), imageBytes(checkNotNull(detector.lastImage)).toList())
        assertTrue(port.hasPendingInference)
        assertEquals(0, executor.size)
    }

    @Test
    fun portBoundaryCopiesInputBeforeTheCallerCanMutateIt() {
        val detector = FakeLiveDetector()
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, ManualExecutor())
        val input = ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3) { 1 }

        port.submit(0L, input, 10L) {}
        input.fill(2)

        assertEquals(ByteArray(input.size) { 1 }.toList(), imageBytes(checkNotNull(detector.lastImage)).toList())
    }

    @Test
    fun occupiedSlotRejectsASecondSubmissionUntilTheFirstCallback() {
        val detector = FakeLiveDetector()
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, ManualExecutor())
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {}

        assertFailsWith<IllegalStateException> {
            port.submit(1L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 11L) {}
        }
    }

    @Test
    fun resultClosesOwnedImageBeforeQueuedRunnerNotificationAndNeverClosesCallbackImage() {
        val detector = FakeLiveDetector()
        lateinit var port: PoseLandmarkerLiveInferencePort
        var ownedClosesWhenEnqueued = -1L
        val executor = ManualExecutor { ownedClosesWhenEnqueued = port.diagnosticOwnedImageCloses }
        var callbackResult: InferenceResult? = null
        port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 123L }, executor)
        port.submit(7L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 21L) { callbackResult = it }
        val callbackImage = image(ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3) { 4 })

        port.onResult(
            PoseLandmarkerDetection(
                posePresent = true,
                poseCount = 1,
                normalizedLandmarks = listOf(Landmark(0, null, 0.1, 0.2, 0.3, null, null)),
                worldLandmarks = null,
            ),
            callbackTimestampMs = 21L,
            callbackImage = callbackImage,
        )

        assertFalse(port.hasPendingInference)
        assertEquals(1, executor.size)
        assertEquals(1L, port.diagnosticOwnedImageCloses)
        assertEquals(1L, ownedClosesWhenEnqueued)
        assertEquals(ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3) { 4 }.toList(), imageBytes(callbackImage).toList())
        assertEquals(null, callbackResult, "the native listener must only enqueue runner notification")

        executor.runNext()
        val success = assertNotNull(callbackResult) as InferenceResult.Success
        assertEquals(123L, success.inferenceEndNs, "nowNs is already rebased to the session clock")
        assertTrue(success.posePresent)

        callbackImage.close()
        port.dispose()
    }

    @Test
    fun timestampMismatchFailsTheSubmissionClosedAndLatchesUnhealthy() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var callbackResult: InferenceResult? = null
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, executor)
        port.submit(4L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 31L) { callbackResult = it }
        val callbackImage = image()

        port.onResult(
            PoseLandmarkerDetection(false, 0, null, null),
            callbackTimestampMs = 32L,
            callbackImage = callbackImage,
        )

        assertFalse(port.isHealthy)
        assertFalse(port.hasPendingInference)
        executor.runNext()
        val failure = assertNotNull(callbackResult) as InferenceResult.Failure
        assertEquals("backend_call_failed", failure.classification)
        assertEquals("backend_timestamp_mismatch", failure.sourceErrorCode)

        callbackImage.close()
        port.dispose()
    }

    @Test
    fun occupiedRejectionCreatesAndClosesNoUnownedImage() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var imageCreates = 0
        var imageCloses = 0
        val port = PoseLandmarkerLiveInferencePort(
            detector,
            LIVE_WIDTH,
            LIVE_HEIGHT,
            { 0L },
            executor,
            imageFactory = { buffer, width, height ->
                imageCreates++
                ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()
            },
            closeImage = { imageCloses++; it.close() },
        )

        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {}
        assertFailsWith<IllegalStateException> {
            port.submit(1L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 11L) {}
        }

        assertEquals(1, imageCreates)
        assertEquals(0, imageCloses)
        port.cancel(0L)
        assertEquals(1, imageCloses)
        port.dispose()
    }

    @Test
    fun concurrentSubmissionsHaveOneAtomicAdmissionAndOneImageCreation() {
        val detector = FakeLiveDetector()
        val enteredDetector = CountDownLatch(1)
        val releaseDetector = CountDownLatch(1)
        detector.onDetect = {
            enteredDetector.countDown()
            check(releaseDetector.await(5, TimeUnit.SECONDS))
        }
        var imageCreates = 0
        val secondFailure = AtomicReference<Throwable?>(null)
        val port = PoseLandmarkerLiveInferencePort(
            detector,
            LIVE_WIDTH,
            LIVE_HEIGHT,
            { 0L },
            ManualExecutor(),
            imageFactory = { buffer, width, height ->
                imageCreates++
                ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()
            },
        )

        val first = Thread { port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {} }
        first.start()
        assertTrue(enteredDetector.await(5, TimeUnit.SECONDS))
        val second = Thread {
            try {
                port.submit(1L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 11L) {}
            } catch (error: Throwable) {
                secondFailure.set(error)
            }
        }
        second.start()
        second.join(5_000)
        releaseDetector.countDown()
        first.join(5_000)

        assertTrue(secondFailure.get() is IllegalStateException)
        assertEquals(1, imageCreates)
        port.dispose()
    }

    @Test
    fun unhealthyRejectionCreatesNoImageAfterTimestampMismatchClosesThePort() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var imageCreates = 0
        var imageCloses = 0
        val port = PoseLandmarkerLiveInferencePort(
            detector,
            LIVE_WIDTH,
            LIVE_HEIGHT,
            { 0L },
            executor,
            imageFactory = { buffer, width, height ->
                imageCreates++
                ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGB).build()
            },
            closeImage = { imageCloses++; it.close() },
        )
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {}
        val callbackImage = image()
        port.onResult(PoseLandmarkerDetection(false, 0, null, null), 11L, callbackImage)

        assertFailsWith<IllegalStateException> {
            port.submit(1L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 12L) {}
        }
        assertEquals(1, imageCreates)
        assertEquals(1, imageCloses)
        callbackImage.close()
        port.dispose()
    }

    @Test
    fun duplicateAndLateCallbacksCannotReenterTheRunner() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var callbacks = 0
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, executor)
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) { callbacks++ }
        val firstImage = image()
        port.onResult(PoseLandmarkerDetection(false, 0, null, null), 10L, firstImage)
        executor.runNext()

        val lateImage = image()
        port.onResult(PoseLandmarkerDetection(false, 0, null, null), 10L, lateImage)
        assertEquals(1, callbacks)
        assertEquals(1L, port.diagnosticCallbackDrops)

        firstImage.close()
        lateImage.close()
        port.dispose()
    }

    @Test
    fun unattributableErrorDropsWithoutRunnerNotificationAndLatchesUnhealthy() {
        val port = PoseLandmarkerLiveInferencePort(
            FakeLiveDetector(), LIVE_WIDTH, LIVE_HEIGHT, { 0L }, ManualExecutor(),
        )

        port.onError(IllegalStateException("late"))

        assertFalse(port.isHealthy)
        assertEquals(1L, port.diagnosticCallbackDrops)
        port.dispose()
    }

    @Test
    fun attributedErrorReleasesOwnedImageAndNotifiesExactlyOnce() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var callbacks = 0
        var callbackResult: InferenceResult? = null
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, executor)
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {
            callbacks++
            callbackResult = it
        }
        val callbackImage = image()

        port.onError(IllegalArgumentException("native error"))

        assertFalse(port.hasPendingInference)
        assertEquals(1, executor.size)
        executor.runNext()
        val failure = assertNotNull(callbackResult) as InferenceResult.Failure
        assertEquals("backend_call_failed", failure.classification)
        assertEquals(1, callbacks)
        callbackImage.close()
        port.dispose()
    }

    @Test
    fun cancelReleasesOwnedImageAndLateCallbackCannotReachRunner() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var callbacks = 0
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, executor)
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) { callbacks++ }
        port.cancel(0L)

        assertFalse(port.hasPendingInference)
        assertEquals(1L, port.diagnosticOwnedImageCloses)
        val lateImage = image()
        port.onResult(PoseLandmarkerDetection(false, 0, null, null), 10L, lateImage)
        assertEquals(0, callbacks)
        assertEquals(1L, port.diagnosticCallbackDrops)

        lateImage.close()
        port.dispose()
    }

    @Test
    fun callbackExceptionsDoNotEscapeTheNativeListenerThread() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, executor)
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) { error("runner callback failed") }
        val callbackImage = image()

        port.onResult(PoseLandmarkerDetection(false, 0, null, null), 10L, callbackImage)
        executor.runNext()

        assertTrue(true, "the queued callback failure must be contained by the port")
        callbackImage.close()
        port.dispose()
    }

    @Test
    fun clockFailureReturnsOneSchemaValidBackendFailureWithoutEscapingListener() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var callbackResult: InferenceResult? = null
        var callbacks = 0
        val port = PoseLandmarkerLiveInferencePort(
            detector,
            LIVE_WIDTH,
            LIVE_HEIGHT,
            { error("clock unavailable") },
            executor,
        )
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {
            callbacks++
            callbackResult = it
        }
        val callbackImage = image()

        port.onResult(PoseLandmarkerDetection(false, 0, null, null), 10L, callbackImage)
        executor.runNext()

        val failure = assertNotNull(callbackResult) as InferenceResult.Failure
        assertEquals("backend_call_failed", failure.classification)
        assertEquals("callback_clock_failed", failure.sourceErrorCode)
        assertEquals(1, callbacks)
        callbackImage.close()
        port.dispose()
    }

    @Test
    fun ownedImageCloseFailureReturnsOneBackendFailureWithoutEscapingListener() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        var callbackResult: InferenceResult? = null
        var callbacks = 0
        val port = PoseLandmarkerLiveInferencePort(
            detector,
            LIVE_WIDTH,
            LIVE_HEIGHT,
            { 0L },
            executor,
            closeImage = { error("owned image close failed") },
        )
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {
            callbacks++
            callbackResult = it
        }
        val callbackImage = image()

        port.onResult(PoseLandmarkerDetection(false, 0, null, null), 10L, callbackImage)
        executor.runNext()

        val failure = assertNotNull(callbackResult) as InferenceResult.Failure
        assertEquals("backend_call_failed", failure.classification)
        assertEquals("owned_image_close_failed", failure.sourceErrorCode)
        assertEquals(1, callbacks)
        callbackImage.close()
        port.dispose()
    }

    @Test
    fun disposeClosesDetectorAndOwnedImageExactlyOnceWithoutWaitingForQueuedCallback() {
        val detector = FakeLiveDetector()
        val executor = ManualExecutor()
        val port = PoseLandmarkerLiveInferencePort(detector, LIVE_WIDTH, LIVE_HEIGHT, { 0L }, executor)
        port.submit(0L, ByteArray(LIVE_WIDTH * LIVE_HEIGHT * 3), 10L) {}
        port.dispose()
        port.dispose()

        assertEquals(1, detector.closeCalls)
        assertEquals(1L, port.diagnosticOwnedImageCloses)
    }
}
