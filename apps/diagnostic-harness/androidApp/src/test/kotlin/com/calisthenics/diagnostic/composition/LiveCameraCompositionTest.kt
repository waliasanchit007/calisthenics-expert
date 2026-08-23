package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerConstructionEvidence
import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.YuvColorRange
import com.calisthenics.diagnostic.preprocessing.YuvColorStandard
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import com.calisthenics.diagnostic.session.SessionFinalizer
import com.calisthenics.diagnostic.session.SessionRunnerSnapshot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LiveCameraCompositionTest {
    @Test
    fun unsupportedFpsReleasesOwnedSchedulerBeforeAnySessionResourceIsCreated() {
        val harness = Harness()

        val result = harness.composition.start(5)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.inferenceCreations)
        assertEquals(0, harness.sourceCreations)
    }

    @Test
    fun partialConstructionFailureReleasesInferenceAndSchedulerExactlyOnce() {
        val harness = Harness(finalizerFailure = IllegalStateException("finalizer construction failed"))

        val result = harness.composition.start(10)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.inference.disposeCount)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertNoLiveSessionDispatcher()
    }

    @Test
    fun registrationRejectionReleasesInferenceAndSchedulerExactlyOnce() {
        val harness = Harness(registrationResult = SessionRunnerRegistrationResult.REJECTED)

        val result = harness.composition.start(10)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.inference.disposeCount)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertNoLiveSessionDispatcher()
    }

    @Test
    fun sourceFactoryFailureReleasesPartiallyConstructedResourcesExactlyOnce() {
        val harness = Harness(sourceFactoryFailure = IllegalStateException("source construction failed"))

        val result = harness.composition.start(10)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.inference.disposeCount)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertNoLiveSessionDispatcher()
    }

    @Test
    fun sourceStartFailureStopsSourceAndReleasesEveryOwnedResourceExactlyOnce() {
        val harness = Harness(sourceStartFailure = IllegalStateException("source start failed"))

        val result = runCatching { harness.composition.start(10) }
            .getOrElse { fail("source-start failure must become typed unavailability", it) }

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.source.stopCount)
        assertEquals(1, harness.inference.disposeCount)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertTrue(harness.events.indexOf("source.start.entered") < harness.events.indexOf("source.stop"))
        assertTrue(harness.events.indexOf("source.stop") < harness.events.indexOf("inference.dispose"))
        assertNoLiveSessionDispatcher()
    }

    @Test
    fun abandonFencesSourceAndReleasesPendingSessionWithoutWaitingForDrain() {
        val harness = Harness(drainTimeoutNs = 5_000_000_000L)
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))

            val before = System.nanoTime()
            harness.composition.abandon()
            val elapsedNs = System.nanoTime() - before

            assertTrue(elapsedNs < 1_000_000_000L, "abandon must not wait for the five-second drain")
            assertEquals(1, harness.source.stopCount)
            assertTrue(harness.inference.awaitDisposed(), "abandon did not release inference")
            assertTrue(harness.scheduler.awaitShutdown(), "abandon did not release scheduler")
            assertEquals(1, harness.inference.disposeCount)
            assertEquals(1, harness.scheduler.shutdownCount)
            assertTrue(harness.events.indexOf("source.stop") < harness.events.indexOf("inference.dispose"))

            harness.composition.abandon()
            assertEquals(1, harness.source.stopCount)
            assertEquals(1, harness.inference.disposeCount)
            assertEquals(1, harness.scheduler.shutdownCount)
        } finally {
            harness.composition.abandon()
        }
    }

    @Test
    fun testHarnessCleanupCompletesAStartedSessionWithPendingInference() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))

            harness.close()

            assertNotNull(harness.composition.completion())
        }
    }

    @Test
    fun deniedCameraPermissionFailsClosedBeforeSourceOrSessionCreation() {
        val harness = Harness(permissionGranted = false)

        val result = assertIs<LiveCameraStartResult.Unavailable>(harness.composition.start(10))

        assertEquals("camera_permission_denied", result.reason.code)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertEquals(0, harness.inferenceCreations)
    }

    @Test
    fun exactCameraCapabilityIsValidatedBeforeSourceOrSessionCreation() {
        val harness = Harness(capability = admittedCapability.copy(sourceColorStandard = "BT709"))

        val result = harness.composition.start(10)

        val unavailable = assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals("camera_capability_not_admitted", unavailable.reason.code)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertEquals(0, harness.inferenceCreations)
        assertFalse(harness.finalizerCreated)
    }

    @Test
    fun everyDeliveredFrameHasExactlyOneSubmitOrSkipClassification() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            harness.source.emit(frame(timestampNs = 1_000_001_000L))
            harness.source.emit(frame(timestampNs = 1_000_002_000L)) // backpressure
            harness.inference.completeSuccess()
            harness.source.emit(frame(timestampNs = 1_000_003_000L)) // cadence
            harness.source.emit(malformedFrame(timestampNs = 1_100_001_001L)) // preprocessing
            harness.source.onStopFrame = { frame(timestampNs = 1_000_400_000L) }
            harness.composition.stop()

            val snapshot = assertNotNull(harness.composition.snapshot())
            assertEquals(5L, snapshot.counts.cameraFramesReceived)
            assertEquals(1L, snapshot.counts.inferenceFramesSubmitted)
            assertEquals(4L, snapshot.counts.framesSkippedBeforeInference)
            assertEquals(
                mapOf(
                    "backpressure" to 1L,
                    "cadence_selection" to 1L,
                    "preprocessing_failed" to 1L,
                    "session_stopping" to 1L,
                ),
                snapshot.counts.framesSkippedBeforeInferenceByReason,
            )
            assertEquals(1, harness.inference.submissionCount)
        }
    }

    @Test
    fun captureInferenceStartAndCallbackUseOneSessionRelativeClock() {
        withHarness(Harness(clockNowNs = 1_000_000_000L)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()

            val record = assertNotNull(harness.composition.snapshot()).records.single()
            val manifest = assertNotNull(harness.composition.manifest())
            assertEquals(2_000L, record.captureTimestampNs)
            assertEquals(3_000L, record.inferenceStartNs)
            assertEquals(5_000L, record.inferenceEndNs)
            assertEquals(0L, manifest.clock.t0MonotonicNs)
            assertEquals("session_relative_monotonic_ns", manifest.clock.monotonicTimeBase)
        }
    }

    @Test
    fun rotationMirroringAndScalePadAreAppliedAndManifestSeparatesDimensions() {
        withHarness(Harness(modelInputWidth = 2, modelInputHeight = 2)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(width = 2, height = 4, rotation = FrameRotation.R90, mirrored = true))
            assertEquals(1, harness.inference.submissionCount)
            assertEquals(12, harness.inference.lastInput!!.size)
            val manifest = assertNotNull(harness.composition.manifest())
            assertEquals(2, manifest.camera.captureWidth)
            assertEquals(4, manifest.camera.captureHeight)
            assertEquals(2, manifest.modelInput.modelInputWidth)
            assertEquals(2, manifest.modelInput.modelInputHeight)
            assertEquals("live_conformance", manifest.sessionKind)
            assertEquals("live_stream", manifest.posePipeline.runningMode)
            assertEquals("mediapipe_pose_landmarker", manifest.posePipeline.backendName)
            assertTrue(manifest.studyContext.containsKey("mediapipe_construction_evidence"))
        }
    }

    @Test
    fun silentInferenceStallStopsAcquisitionAndPublishesOnlyTeardownTimeout() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.onStopFrame = { frame(timestampNs = 1_000_400_000L) }
            harness.source.emit(frame(timestampNs = 1_000_002_000L))

            harness.scheduler.fireNext() // five-second live stall fence
            assertTrue(harness.source.stopped)
            assertTrue(harness.composition.internalFailure is LiveInternalFailure.LiveInferenceStalled)
            harness.scheduler.fireNext() // ordinary SessionRunner drain deadline

            val completion = assertNotNull(harness.composition.completion())
            assertTrue(completion.internalFailure is LiveInternalFailure.LiveInferenceStalled)
            val failure = completion.snapshot.failures.single()
            assertEquals("session_teardown_timeout", failure.classification)
            assertFalse(completion.snapshot.failures.any { it.classification == "backend_call_failed" })
        }
    }

    @Test
    fun stopDrainsAndFinalizesOnce() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()

            val completion = harness.composition.stop()

            assertNotNull(completion)
            assertIs<FinalizationResult.Published>(completion.finalization)
            assertEquals(1, harness.finalizerCalls)
            assertEquals(completion, harness.composition.stop())
            assertEquals(1, harness.finalizerCalls)
        }
    }

    @Test
    fun finalizerManifestCallbackDoesNotDeadlockStopFinalization() {
        val harness = Harness(invokeManifestAtFinalization = true)
        val stopReturned = CountDownLatch(1)
        val stopError = AtomicReference<Throwable?>(null)
        val completion = AtomicReference<LiveCameraCompletion?>(null)
        val stopThread = Thread {
            runCatching { harness.composition.stop() }
                .onSuccess(completion::set)
                .onFailure(stopError::set)
            stopReturned.countDown()
        }
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            stopThread.start()

            assertTrue(stopReturned.await(1, TimeUnit.SECONDS), "stop/finalization deadlocked")
            stopThread.join(1_000)
            assertNull(stopError.get())
            assertIs<FinalizationResult.Published>(assertNotNull(completion.get()).finalization)
            assertTrue(harness.manifestCallbackInvoked)
        } finally {
            if (stopThread.isAlive) {
                stopThread.interrupt()
                stopThread.join(1_000)
                harness.manifestCallbackInvokedLatch.await(1, TimeUnit.SECONDS)
            }
            harness.composition.abandon()
            harness.scheduler.awaitShutdown()
        }
    }

    @Test
    fun abandonBetweenSourcePublicationAndStartPreventsSourceResurrection() {
        val sourceStartGateEntered = CountDownLatch(1)
        val allowSourceStart = CountDownLatch(1)
        val harness = Harness(
            beforeSourceStart = {
                sourceStartGateEntered.countDown()
                check(allowSourceStart.await(1, TimeUnit.SECONDS))
            },
        )
        val startResult = AtomicReference<LiveCameraStartResult?>(null)
        val startThread = Thread { startResult.set(harness.composition.start(10)) }
        try {
            startThread.start()
            assertTrue(sourceStartGateEntered.await(1, TimeUnit.SECONDS), "source-start gate was not reached")

            harness.composition.abandon()
            allowSourceStart.countDown()
            startThread.join(1_000)

            val unavailable = assertIs<LiveCameraStartResult.Unavailable>(startResult.get())
            assertEquals("live_session_closed", assertIs<LiveUnavailableReason.Initialization>(unavailable.reason).detail)
            assertEquals(0, harness.source.startCount)
            assertEquals(1, harness.source.stopCount)
            assertTrue(harness.inference.awaitDisposed(), "abandon did not release inference")
            assertTrue(harness.scheduler.awaitShutdown(), "abandon did not release scheduler")
            assertEquals(1, harness.inference.disposeCount)
            assertEquals(1, harness.scheduler.shutdownCount)
        } finally {
            allowSourceStart.countDown()
            startThread.join(1_000)
            harness.composition.abandon()
        }
    }

    @Test
    fun abandonDuringExternalSourceStartStopsAfterStartCompletes() {
        val harness = Harness(blockSourceStart = true)
        val startResult = AtomicReference<LiveCameraStartResult?>(null)
        val startThread = Thread { startResult.set(harness.composition.start(10)) }
        try {
            startThread.start()
            assertTrue(harness.source.startEntered.await(1, TimeUnit.SECONDS), "source.start was not entered")

            harness.composition.abandon()
            assertEquals(0, harness.source.stopCount, "teardown stopped source before external start returned")

            harness.source.allowStart.countDown()
            startThread.join(1_000)

            val unavailable = assertIs<LiveCameraStartResult.Unavailable>(startResult.get())
            assertEquals("live_session_closed", assertIs<LiveUnavailableReason.Initialization>(unavailable.reason).detail)
            assertEquals(1, harness.source.startCount)
            assertEquals(1, harness.source.stopCount)
            assertTrue(harness.events.indexOf("source.start.completed") < harness.events.indexOf("source.stop"))
            assertFalse(harness.source.active)
            assertTrue(harness.inference.awaitDisposed(), "abandon did not release inference")
            assertTrue(harness.scheduler.awaitShutdown(), "abandon did not release scheduler")
            assertEquals(1, harness.inference.disposeCount)
            assertEquals(1, harness.scheduler.shutdownCount)
        } finally {
            harness.source.allowStart.countDown()
            startThread.join(1_000)
            harness.composition.abandon()
        }
    }

    @Test
    fun callbackAfterStopCompletesTheDrainWithoutWaitingForDeadline() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))

            val completion = AtomicReference<LiveCameraCompletion?>(null)
            val stopError = AtomicReference<Throwable?>(null)
            val stopReturned = CountDownLatch(1)
            val stopThread = Thread {
                runCatching { harness.composition.stop() }
                    .onSuccess(completion::set)
                    .onFailure(stopError::set)
                stopReturned.countDown()
            }.apply { start() }

            assertTrue(stopReturned.await(100, TimeUnit.MILLISECONDS).not(), "stop returned before callback")
            harness.inference.completeSuccess()
            assertTrue(stopReturned.await(1, TimeUnit.SECONDS), "callback did not complete the drain")
            stopThread.join(1_000)
            assertNull(stopError.get())
            assertIs<FinalizationResult.Published>(assertNotNull(completion.get()).finalization)
        }
    }

    private inline fun <T> withHarness(harness: Harness, block: (Harness) -> T): T =
        try {
            block(harness)
        } finally {
            harness.close()
        }

    private fun assertNoLiveSessionDispatcher() {
        assertFalse(
            Thread.getAllStackTraces().keys.any { it.name == "replay-session-dispatcher" && it.isAlive },
            "live session dispatcher worker leaked",
        )
    }

    private class Harness(
        capability: CameraCapabilityObservation = admittedCapability,
        permissionGranted: Boolean = true,
        modelInputWidth: Int = 2,
        modelInputHeight: Int = 2,
        clockNowNs: Long = 1_000_001_000L,
        drainTimeoutNs: Long = 5_000_000_000L,
        finalizerFailure: Throwable? = null,
        registrationResult: SessionRunnerRegistrationResult = SessionRunnerRegistrationResult.ACCEPTED,
        sourceFactoryFailure: Throwable? = null,
        sourceStartFailure: Throwable? = null,
        invokeManifestAtFinalization: Boolean = false,
        beforeSourceStart: () -> Unit = {},
        blockSourceStart: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val inference = FakeInference(events)
        val scheduler = FakeScheduler(events)
        val clock = FakeClock(clockNowNs)
        val source = FakeSource(clock, events, sourceStartFailure, blockSourceStart)
        var sourceCreations = 0
        var inferenceCreations = 0
        var finalizerCreated = false
        var finalizerCalls = 0
        var manifestCallbackInvoked = false
        val manifestCallbackInvokedLatch = CountDownLatch(1)
        lateinit var manifest: com.calisthenics.diagnostic.contract.SessionManifest
        private var started = false

        val composition = LiveCameraComposition(
            LiveCameraComposition.Dependencies(
                cameraCapability = capability,
                permissionGranted = permissionGranted,
                modelInputWidth = modelInputWidth,
                modelInputHeight = modelInputHeight,
                clock = clock,
                scheduler = scheduler,
                sourceFactory = {
                    sourceFactoryFailure?.let { throw it }
                    sourceCreations++
                    source
                },
                inferenceFactory = {
                    inferenceCreations++
                    LiveInference(inference, inference, constructionEvidence)
                },
                sessionFinalizerFactory = { template, manifestAtFinalization ->
                    finalizerFailure?.let { throw it }
                    finalizerCreated = true
                    manifest = template
                    object : SessionFinalizer {
                        override fun finalize(snapshot: com.calisthenics.diagnostic.session.SealedSessionSnapshot): FinalizationResult {
                            finalizerCalls++
                            if (invokeManifestAtFinalization) {
                                manifestAtFinalization(template)
                                manifestCallbackInvoked = true
                                manifestCallbackInvokedLatch.countDown()
                            }
                            return FinalizationResult.Published(snapshot)
                        }
                    }
                },
                drainTimeoutNs = drainTimeoutNs,
                registerRunner = { registrationResult },
                beforeSourceStart = beforeSourceStart,
            ),
        )

        fun start(fps: Int): LiveCameraStartResult = composition.start(fps).also { result ->
            if (result is LiveCameraStartResult.Started) started = true
        }

        fun close() {
            if (!started) return
            if (composition.completion() == null) {
                if (inference.hasPendingSubmission) inference.completeSuccess()
                composition.stop()
            }
            started = false
        }
    }

    private class FakeClock(var value: Long) : LiveClock {
        override fun nowNs(): Long = value
    }

    private class FakeSource(
        private val clock: FakeClock,
        private val events: MutableList<String>,
        private val startFailure: Throwable?,
        private val blockStart: Boolean,
    ) : LiveFrameSource {
        private var callback: ((OwnedYuvFrame) -> Unit)? = null
        @Volatile var stopped = false
        @Volatile var active = false
        @Volatile var startCount = 0
        @Volatile var stopCount = 0
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        var onStopFrame: (() -> OwnedYuvFrame)? = null

        override fun start(onFrame: (OwnedYuvFrame) -> Unit, onFailure: (Throwable) -> Unit) {
            startCount++
            events += "source.start.entered"
            startEntered.countDown()
            if (blockStart) check(allowStart.await(1, TimeUnit.SECONDS))
            startFailure?.let { throw it }
            callback = onFrame
            active = true
            events += "source.start.completed"
        }

        override fun stop() {
            stopCount++
            events += "source.stop"
            stopped = true
            active = false
            onStopFrame?.invoke()?.let { callback?.invoke(it) }
        }

        fun emit(frame: OwnedYuvFrame) {
            clock.value = maxOf(clock.value, frame.timestampNs + 1_000L)
            callback!!.invoke(frame)
        }
    }

    private class FakeInference(private val events: MutableList<String>) : InferencePort, Disposable {
        var submissionCount = 0
        var lastInput: ByteArray? = null
        var disposeCount = 0
        private val disposed = CountDownLatch(1)
        private var callback: ((InferenceResult) -> Unit)? = null
        var callbackNowNs = 1_000_005_000L
        val hasPendingSubmission: Boolean
            get() = callback != null

        override fun submit(
            submissionIndex: Long,
            canonicalInput: ByteArray,
            backendTimestampMs: Long,
            callback: (InferenceResult) -> Unit,
        ) {
            submissionCount++
            lastInput = canonicalInput
            this.callback = callback
        }

        fun completeSuccess() {
            callback!!.invoke(InferenceResult.Success(callbackNowNs - 1_000_000_000L, posePresent = false))
            callback = null
        }

        override fun cancel(submissionIndex: Long) = Unit
        override fun dispose() {
            disposeCount++
            events += "inference.dispose"
            disposed.countDown()
        }

        fun awaitDisposed(): Boolean = disposed.await(1, TimeUnit.SECONDS)
    }

    private class FakeScheduler(private val events: MutableList<String>) : LiveScheduler {
        private val actions = ArrayDeque<() -> Unit>()
        var shutdownCount = 0
        private val shutdown = CountDownLatch(1)

        override fun scheduleAt(deadlineNs: Long, action: () -> Unit): Disposable {
            actions += action
            return object : Disposable {
                override fun dispose() = Unit
            }
        }

        fun fireNext() {
            check(actions.isNotEmpty())
            actions.removeFirst().invoke()
        }

        override fun shutdown() {
            shutdownCount++
            events += "scheduler.shutdown"
            shutdown.countDown()
        }

        fun awaitShutdown(): Boolean = shutdown.await(1, TimeUnit.SECONDS)
    }

    private companion object {
        val admittedCapability = CameraCapabilityObservation(
            cameraId = "0",
            timestampSource = "REALTIME",
            sourceDataspaceRaw = OwnedYuvFrame.ADMITTED_RAW_DATASPACE,
            sourceColorStandard = "BT601",
            sourceColorRange = "FULL",
            underlyingImageAvailable = true,
            pixelFormat = "YUV_420_888",
        )

        val constructionEvidence = PoseLandmarkerConstructionEvidence(
            modelSha256 = "a".repeat(64),
            tasksVisionVersion = "0.10.22",
            runningMode = "LIVE_STREAM",
            delegate = "CPU",
            numPoses = 1,
            minPoseDetectionConfidence = 0.5f,
            minPosePresenceConfidence = 0.5f,
            minTrackingConfidence = 0.5f,
            outputSegmentationMasks = false,
            modelInputWidth = 2,
            modelInputHeight = 2,
        )

        fun frame(
            width: Int = 2,
            height: Int = 2,
            timestampNs: Long = 1_000_002_000L,
            rotation: FrameRotation = FrameRotation.R0,
            mirrored: Boolean = false,
        ) = OwnedYuvFrame(
            yBytes = ByteArray(width * height) { 100.toByte() },
            yRowStride = width,
            yPixelStride = 1,
            uBytes = ByteArray(((width + 1) / 2) * ((height + 1) / 2)) { 128.toByte() },
            uRowStride = (width + 1) / 2,
            uPixelStride = 1,
            vBytes = ByteArray(((width + 1) / 2) * ((height + 1) / 2)) { 128.toByte() },
            vRowStride = (width + 1) / 2,
            vPixelStride = 1,
            width = width,
            height = height,
            rotation = rotation,
            mirrored = mirrored,
            rawDataSpace = OwnedYuvFrame.ADMITTED_RAW_DATASPACE,
            colorStandard = YuvColorStandard.BT601,
            colorRange = YuvColorRange.FULL,
            timestampNs = timestampNs,
        )

        fun malformedFrame(timestampNs: Long) = OwnedYuvFrame(
            yBytes = byteArrayOf(100.toByte()),
            yRowStride = 2,
            yPixelStride = 1,
            uBytes = byteArrayOf(128.toByte()),
            uRowStride = 1,
            uPixelStride = 1,
            vBytes = byteArrayOf(128.toByte()),
            vRowStride = 1,
            vPixelStride = 1,
            width = 2,
            height = 2,
            rotation = FrameRotation.R0,
            mirrored = false,
            rawDataSpace = OwnedYuvFrame.ADMITTED_RAW_DATASPACE,
            colorStandard = YuvColorStandard.BT601,
            colorRange = YuvColorRange.FULL,
            timestampNs = timestampNs,
        )
    }
}
