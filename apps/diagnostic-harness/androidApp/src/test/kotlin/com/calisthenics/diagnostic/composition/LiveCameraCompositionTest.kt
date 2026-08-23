package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.contract.Landmark
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

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
    fun unavailableThermalApiFailsClosedBeforeSourceOrSessionCreation() {
        val harness = Harness(thermalAvailable = false)

        val result = assertIs<LiveCameraStartResult.Unavailable>(harness.composition.start(10))

        assertEquals("thermal_api_unavailable", result.reason.code)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertEquals(0, harness.inferenceCreations)
    }

    @Test
    fun finalManifestRecordsSessionRelativeThermalObservations() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.thermal.emit(status = 3, nowNs = 2_000L)
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
            harness.composition.stop()

            val thermal = assertIs<JsonObject>(assertNotNull(harness.finalizedManifest)
                .studyContext.getValue("thermal_observations"))
            assertEquals(true, thermal["api_available"]?.jsonPrimitive?.boolean)
            val samples = thermal["samples"]?.jsonArray ?: fail("missing thermal samples")
            assertEquals(2, samples.size)
            assertEquals(0L, samples.first().jsonObject["t_monotonic_ns"]?.jsonPrimitive?.long)
            assertEquals(2_000L, samples.last().jsonObject["t_monotonic_ns"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun poseBearingLiveExportMarksAvailableObservationsFromSealedRecords() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            val landmarks = listOf(Landmark(0, "nose", 0.5, 0.5, 0.0, null, null))
            harness.inference.completeSuccess(
                normalizedImageLandmarks = landmarks,
                worldLandmarks = landmarks,
            )
            harness.composition.stop()

            val available = assertNotNull(harness.finalizedManifest).availableObservations
            assertTrue(available.normalizedImageLandmarksAvailable)
            assertTrue(available.worldLandmarksAvailable)
        }
    }

    @Test
    fun noPoseLiveExportLeavesAvailableObservationsFalse() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
            harness.composition.stop()

            val available = assertNotNull(harness.finalizedManifest).availableObservations
            assertFalse(available.normalizedImageLandmarksAvailable)
            assertFalse(available.worldLandmarksAvailable)
        }
    }

    @Test
    fun runtimeSourceFailureIsPublishedOnCompositionAndCompletion() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.source.fail(IllegalStateException("source detail must not enter evidence"))
            harness.inference.completeSuccess()

            val completion = assertNotNull(harness.composition.completion())
            assertEquals("live_source_failed", harness.composition.internalFailure?.code)
            assertEquals("live_source_failed", completion.internalFailure?.code)
        }
    }

    @Test
    fun immutableEvidenceTelemetryCarriesAdmittedSourceGeometryAndThermalSummary() {
        withHarness(Harness(sourceResolution = LiveSourceResolution(2, 4))) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.thermal.emit(status = 3, nowNs = 2_000L)
            harness.source.emit(
                frame(
                    width = 2,
                    height = 4,
                    rotation = FrameRotation.R90,
                    mirrored = true,
                ),
            )

            val telemetry = assertNotNull(harness.composition.captureEvidenceTelemetry())

            assertEquals(2, telemetry.captureWidth)
            assertEquals(4, telemetry.captureHeight)
            assertEquals(90, telemetry.orientationDegrees)
            assertTrue(telemetry.mirrored == true)
            assertTrue(telemetry.thermalApiAvailable)
            assertEquals(null, telemetry.thermalUnavailableReason)
            assertEquals(
                listOf(LiveThermalObservation(0L, 0), LiveThermalObservation(2_000L, 3)),
                telemetry.thermalObservations,
            )
        }
    }

    @Test
    fun thermalEvidencePreservesNullReasonForPresentTelemetryAndUsesFallbackOnlyWhenAbsent() {
        val present = thermalEvidenceFromTelemetry(
            LiveCaptureEvidenceTelemetry(
                captureWidth = 2,
                captureHeight = 2,
                orientationDegrees = 0,
                mirrored = false,
                thermalApiAvailable = true,
                thermalUnavailableReason = null,
                thermalObservations = listOf(LiveThermalObservation(0L, 0)),
            ),
        )
        val absent = thermalEvidenceFromTelemetry(null)

        assertTrue(present.apiAvailable)
        assertNull(present.unavailableReason)
        assertEquals(listOf(0L), present.observations.map { it.tMonotonicNs })
        assertFalse(absent.apiAvailable)
        assertEquals("composition_telemetry_unavailable", absent.unavailableReason)
        assertTrue(absent.observations.isEmpty())
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
    fun backpressureSkipDoesNotRunTheLargeFrameConverter() {
        val largeResolution = LiveSourceResolution(4_000, 3_000)
        withHarness(Harness(sourceResolution = largeResolution)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            harness.source.emit(largeFrame(timestampNs = 1_000_002_000L))
            val skippedFrame = largeFrame(timestampNs = 1_100_002_000L)
            val elapsedMillis = measureTimeMillis {
                harness.source.emit(skippedFrame)
            }

            val snapshot = assertNotNull(harness.composition.snapshot())
            assertTrue(elapsedMillis < 10L, "backpressure skip took ${elapsedMillis}ms")
            assertEquals(2L, snapshot.counts.cameraFramesReceived)
            assertEquals(1L, snapshot.counts.inferenceFramesSubmitted)
            assertEquals(1L, snapshot.counts.framesSkippedBeforeInference)
            assertEquals(1L, snapshot.counts.framesSkippedBeforeInferenceByReason.getValue("backpressure"))
            assertEquals(0L, snapshot.counts.framesSkippedBeforeInferenceByReason.getValue("preprocessing_failed"))
            assertEquals(1, harness.inference.submissionCount)
        }
    }

    @Test
    fun cadenceSkipDoesNotRunTheLargeFrameConverter() {
        val largeResolution = LiveSourceResolution(4_000, 3_000)
        withHarness(Harness(sourceResolution = largeResolution)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(30))

            harness.source.emit(largeFrame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
            val skippedFrame = largeFrame(timestampNs = 1_010_002_000L)
            val elapsedMillis = measureTimeMillis {
                harness.source.emit(skippedFrame)
            }

            val snapshot = assertNotNull(harness.composition.snapshot())
            assertTrue(elapsedMillis < 10L, "cadence skip took ${elapsedMillis}ms")
            assertEquals(2L, snapshot.counts.cameraFramesReceived)
            assertEquals(1L, snapshot.counts.inferenceFramesSubmitted)
            assertEquals(1L, snapshot.counts.framesSkippedBeforeInference)
            assertEquals(1L, snapshot.counts.framesSkippedBeforeInferenceByReason.getValue("cadence_selection"))
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
    fun manifestSeparatesCameraTimestampSourceFromSessionTimeBase() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            val manifest = assertNotNull(harness.composition.manifest())
            assertEquals("REALTIME", manifest.camera.timestampSource)
            assertEquals("session_relative_monotonic_ns", manifest.clock.monotonicTimeBase)
            assertNotEquals(manifest.camera.timestampSource, manifest.clock.monotonicTimeBase)
        }
    }

    @Test
    fun androidClockAndCameraTimestampShareElapsedRealtimeDomainBeforeSessionRebasing() {
        var elapsedRealtimeNs = 9_000_000_000L
        val clock = LiveCameraComposition.AndroidLiveClock { elapsedRealtimeNs }
        val harness = Harness(
            clockOverride = clock,
            onFrameTimestamp = { timestampNs -> elapsedRealtimeNs = timestampNs + 1_000L },
        )
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            harness.source.emit(frame(timestampNs = 9_000_002_000L))
            harness.inference.completeSuccess()

            val record = assertNotNull(harness.composition.snapshot()).records.single()
            assertEquals(2_000L, record.captureTimestampNs)
            assertEquals(3_000L, record.inferenceStartNs)
            assertEquals(5_000L, record.inferenceEndNs)
        } finally {
            harness.composition.abandon()
        }
    }

    @Test
    fun hoursScaleAndroidClockSchedulesFullStallDurationWithoutFiring() {
        var elapsedRealtimeNs = HOURS_SCALE_START_NS
        val harness = Harness(
            clockOverride = LiveCameraComposition.AndroidLiveClock { elapsedRealtimeNs },
            onFrameTimestamp = { timestampNs -> elapsedRealtimeNs = timestampNs + 1_000L },
        )
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            harness.source.emit(frame(timestampNs = HOURS_SCALE_START_NS + 2_000L))

            val nonPositiveActionRan = harness.scheduler.awaitNonPositiveExecution(250L)
            assertNull(harness.composition.internalFailure)
            assertFalse(nonPositiveActionRan, "five-second stall duration fired immediately")
            assertEquals(listOf(FIVE_SECONDS_NS), harness.scheduler.scheduledDelays)
            assertEquals(1, harness.inference.submissionCount)
        } finally {
            harness.composition.abandon()
        }
    }

    @Test
    fun pendingInferenceDrainKeepsConfiguredDurationUntilExplicitExpiry() {
        var elapsedRealtimeNs = HOURS_SCALE_START_NS
        val harness = Harness(
            clockOverride = LiveCameraComposition.AndroidLiveClock { elapsedRealtimeNs },
            onFrameTimestamp = { timestampNs -> elapsedRealtimeNs = timestampNs + 1_000L },
            runNonPositiveSchedulerActions = false,
        )
        val completion = AtomicReference<LiveCameraCompletion?>(null)
        val stopError = AtomicReference<Throwable?>(null)
        val stopReturned = CountDownLatch(1)
        val stopThread = Thread {
            runCatching { harness.composition.stop() }
                .onSuccess(completion::set)
                .onFailure(stopError::set)
            stopReturned.countDown()
        }
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = HOURS_SCALE_START_NS + 2_000L))

            stopThread.start()
            assertTrue(harness.scheduler.awaitScheduledCount(2, 1_000L), "drain duration was not scheduled")

            assertEquals(listOf(FIVE_SECONDS_NS, FIVE_SECONDS_NS), harness.scheduler.scheduledDelays)
            assertFalse(stopReturned.await(100L, TimeUnit.MILLISECONDS), "stop returned before drain expiry")
            assertNull(harness.composition.completion())
            assertNull(harness.composition.internalFailure)
            assertTrue(assertNotNull(harness.composition.snapshot()).failures.isEmpty())

            harness.scheduler.fireNext()
            assertTrue(stopReturned.await(1L, TimeUnit.SECONDS), "explicit drain expiry did not complete stop")
            stopThread.join(1_000L)
            assertNull(stopError.get())
            val finished = assertNotNull(completion.get())
            assertNull(finished.internalFailure)
            assertEquals("session_teardown_timeout", finished.snapshot.failures.single().classification)
        } finally {
            while (harness.scheduler.hasRunnableAction) harness.scheduler.fireNext()
            stopThread.join(1_000L)
            harness.composition.abandon()
        }
    }

    @Test
    fun finalizationRecordsObservedSubmissionCadenceInsteadOfPlaceholderZero() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
            harness.source.emit(frame(timestampNs = 4_100_002_000L))
            harness.inference.callbackNowNs = 4_100_005_000L
            harness.inference.completeSuccess()
            harness.composition.stop()

            val cadence = assertNotNull(harness.finalizedManifest).cadence.achievedInferenceFpsTimeseries
            assertEquals(3, cadence.size)
            assertEquals(listOf(1.0, 0.0, 0.0), cadence.map { it.achievedInferenceFps })
            assertEquals(listOf(1_000_000_000L, 2_000_000_000L, 3_000_000_000L), cadence.map { it.tMonotonicNs })
        }
    }

    @Test
    fun manifestSeparatesDeliveredCallbackAccountingFromRequestedCameraCadenceEstimate() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
            harness.inference.callbackNowNs = 2_000_005_000L
            harness.source.emit(frame(timestampNs = 2_000_002_000L))
            harness.inference.completeSuccess()
            harness.inference.callbackNowNs = 3_000_005_000L
            harness.source.emit(frame(timestampNs = 3_000_002_000L))
            harness.inference.completeSuccess()
            harness.composition.stop()

            val manifest = assertNotNull(harness.finalizedManifest)
            val diagnostics = assertIs<JsonObject>(assertNotNull(manifest.studyContext["camera_cadence_diagnostics"]))
            assertEquals(3L, diagnostics["delivered_callback_count"]?.jsonPrimitive?.long)
            assertEquals(2_000_000_000L, diagnostics["observed_capture_span_ns"]?.jsonPrimitive?.long)
            assertEquals(61L, diagnostics["requested_callback_opportunities"]?.jsonPrimitive?.long)
            assertEquals(58L, diagnostics["upstream_unclassified_cadence_gap_estimate"]?.jsonPrimitive?.long)
            assertEquals(0L, diagnostics["delivered_callback_classification_gap"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun manifestPinsWallClockStartAndFinalizationTimes() {
        val wallTimes = ArrayDeque(listOf("session-start", "session-end"))
        withHarness(
            Harness(
                invokeManifestAtFinalization = true,
                wallClockUtc = { wallTimes.removeFirst() },
            ),
        ) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
            harness.composition.stop()

            val finalized = assertNotNull(harness.finalizedManifest)
            assertEquals("session-start", finalized.clock.startedAtUtc)
            assertEquals("session-end", finalized.clock.endedAtUtc)
        }
    }

    @Test
    fun rotationMirroringAndScalePadAreAppliedAndManifestSeparatesDimensions() {
        withHarness(
            Harness(
                modelInputWidth = 2,
                modelInputHeight = 2,
                sourceResolution = LiveSourceResolution(2, 4),
                invokeManifestAtFinalization = true,
            ),
        ) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(width = 2, height = 4, rotation = FrameRotation.R90, mirrored = true))
            harness.inference.completeSuccess()
            harness.composition.stop()

            assertEquals(1, harness.inference.submissionCount)
            assertEquals(12, harness.inference.lastInput!!.size)
            val manifest = assertNotNull(harness.finalizedManifest)
            assertEquals(2, manifest.camera.captureWidth)
            assertEquals(4, manifest.camera.captureHeight)
            assertEquals(90, manifest.camera.orientationDegrees)
            assertTrue(manifest.camera.mirrored)
            assertEquals(2, manifest.modelInput.modelInputWidth)
            assertEquals(2, manifest.modelInput.modelInputHeight)
            assertEquals("live_conformance", manifest.sessionKind)
            assertEquals("live_stream", manifest.posePipeline.runningMode)
            assertEquals("mediapipe_pose_landmarker", manifest.posePipeline.backendName)
            assertTrue(manifest.studyContext.containsKey("mediapipe_construction_evidence"))
        }
    }

    @Test
    fun geometryChangeAfterTheFirstAdmittedFrameIsRejectedBeforeInference() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L, rotation = FrameRotation.R90, mirrored = true))
            harness.inference.completeSuccess()

            harness.inference.callbackNowNs = 1_100_005_000L
            harness.source.emit(frame(timestampNs = 1_100_002_000L, rotation = FrameRotation.R0, mirrored = false))

            val snapshot = assertNotNull(harness.composition.snapshot())
            assertEquals(2L, snapshot.counts.cameraFramesReceived)
            assertEquals(1L, snapshot.counts.inferenceFramesSubmitted)
            assertEquals(1L, snapshot.counts.framesSkippedBeforeInference)
            assertEquals(1L, snapshot.counts.framesSkippedBeforeInferenceByReason.getValue("preprocessing_failed"))
            assertEquals(1, harness.inference.submissionCount)
        }
    }

    @Test
    fun zeroFrameLiveSessionFailsFinalizationRatherThanPublishingTemplateGeometry() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            val completion = harness.composition.stop()

            val failure = assertIs<FinalizationResult.Failed>(completion.finalization)
            assertEquals("finalization_failed", failure.failureCode)
            assertTrue(failure.detail.contains("source_geometry_not_observed"))
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
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
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

    @Test
    fun stopWaitsForAnInFlightSourceCallbackBeforeFreezingCadenceEvidence() {
        val harness = Harness(invokeManifestAtFinalization = true)
        val completion = AtomicReference<LiveCameraCompletion?>(null)
        val stopError = AtomicReference<Throwable?>(null)
        val stopReturned = CountDownLatch(1)
        var sourceCallbackThread: Thread? = null
        val stopThread = Thread {
            runCatching { harness.composition.stop() }
                .onSuccess(completion::set)
                .onFailure(stopError::set)
            stopReturned.countDown()
        }
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()

            sourceCallbackThread = harness.source.emitInFlight(frame(timestampNs = 1_100_002_000L))
            assertTrue(harness.source.inFlightCallbackEntered.await(1, TimeUnit.SECONDS))
            stopThread.start()
            assertTrue(harness.source.stopEntered.await(1, TimeUnit.SECONDS))
            assertFalse(
                stopReturned.await(100, TimeUnit.MILLISECONDS),
                "stop froze evidence before the in-flight source callback was classified",
            )

            harness.source.allowInFlightCallback.countDown()
            assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
            sourceCallbackThread.join(1_000)

            assertNull(stopError.get())
            val finished = assertNotNull(completion.get())
            assertIs<FinalizationResult.Published>(finished.finalization)
            assertEquals(2L, finished.snapshot.counts.cameraFramesReceived)
            assertEquals(1L, finished.snapshot.counts.inferenceFramesSubmitted)
            assertEquals(1L, finished.snapshot.counts.framesSkippedBeforeInference)
            val cadence = assertNotNull(finished.cadenceDiagnostics)
            assertEquals(finished.snapshot.counts.cameraFramesReceived, cadence.deliveredCallbackCount)
            assertEquals(0L, cadence.deliveredCallbackClassificationGap)
        } finally {
            harness.source.allowInFlightCallback.countDown()
            sourceCallbackThread?.join(1_000)
            if (stopThread.isAlive) stopThread.join(1_000)
            harness.close()
        }
    }

    @Test
    fun stopFromSourceCallbackCompletesWithoutWaitingOnItsOwnQuiescenceSignal() {
        val harness = Harness(invokeManifestAtFinalization = true, drainTimeoutNs = 0L)
        val nestedCompletion = AtomicReference<LiveCameraCompletion?>(null)
        val nestedStopFailure = AtomicReference<Throwable?>(null)
        val sourceCallbackThread = Thread {
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
        }
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10) { snapshot ->
                if (snapshot.counts.cameraFramesReceived == 1L) {
                    runCatching { harness.composition.stop() }
                        .onSuccess(nestedCompletion::set)
                        .onFailure(nestedStopFailure::set)
                }
            })

            sourceCallbackThread.start()
            sourceCallbackThread.join(2_000)

            assertFalse(sourceCallbackThread.isAlive, "source callback deadlocked teardown")
            assertNull(nestedStopFailure.get())
            assertIs<FinalizationResult.Published>(assertNotNull(nestedCompletion.get()).finalization)
        } finally {
            if (sourceCallbackThread.isAlive) {
                sourceCallbackThread.interrupt()
                sourceCallbackThread.join(1_000)
            }
            harness.close()
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
        clockOverride: LiveClock? = null,
        onFrameTimestamp: ((Long) -> Unit)? = null,
        drainTimeoutNs: Long = 5_000_000_000L,
        finalizerFailure: Throwable? = null,
        registrationResult: SessionRunnerRegistrationResult = SessionRunnerRegistrationResult.ACCEPTED,
        sourceFactoryFailure: Throwable? = null,
        sourceStartFailure: Throwable? = null,
        invokeManifestAtFinalization: Boolean = false,
        wallClockUtc: () -> String = { "test-time" },
        beforeSourceStart: () -> Unit = {},
        blockSourceStart: Boolean = false,
        runNonPositiveSchedulerActions: Boolean = true,
        sourceResolution: LiveSourceResolution = LiveSourceResolution(2, 2),
        thermalAvailable: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val inference = FakeInference(events)
        private val fallbackClock = FakeClock(clockNowNs)
        val clock = clockOverride ?: fallbackClock
        val scheduler = FakeScheduler(events, runNonPositiveSchedulerActions)
        val thermal = FakeThermalProbe(thermalAvailable)
        val source = FakeSource(
            advanceClock = onFrameTimestamp ?: { timestampNs ->
                fallbackClock.value = maxOf(fallbackClock.value, timestampNs + 1_000L)
            },
            events = events,
            startFailure = sourceStartFailure,
            blockStart = blockSourceStart,
            sourceResolution = sourceResolution,
        )
        var sourceCreations = 0
        var inferenceCreations = 0
        var finalizerCreated = false
        var finalizerCalls = 0
        var manifestCallbackInvoked = false
        val manifestCallbackInvokedLatch = CountDownLatch(1)
        lateinit var manifest: com.calisthenics.diagnostic.contract.SessionManifest
        var finalizedManifest: com.calisthenics.diagnostic.contract.SessionManifest? = null
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
                thermalProbeFactory = { thermal },
                sessionFinalizerFactory = { template, manifestAtFinalization ->
                    finalizerFailure?.let { throw it }
                    finalizerCreated = true
                    manifest = template
                    object : SessionFinalizer {
                        override fun finalize(snapshot: com.calisthenics.diagnostic.session.SealedSessionSnapshot): FinalizationResult {
                            finalizerCalls++
                            if (invokeManifestAtFinalization) {
                                finalizedManifest = manifestAtFinalization(template, snapshot)
                                manifest = finalizedManifest!!
                                manifestCallbackInvoked = true
                                manifestCallbackInvokedLatch.countDown()
                            }
                            return FinalizationResult.Published(snapshot)
                        }
                    }
                },
                drainTimeoutNs = drainTimeoutNs,
                wallClockUtc = wallClockUtc,
                registerRunner = { registrationResult },
                beforeSourceStart = beforeSourceStart,
            ),
        )

        fun start(
            fps: Int,
            onProgress: (SessionRunnerSnapshot) -> Unit = {},
        ): LiveCameraStartResult = composition.start(fps, onProgress).also { result ->
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
        private val advanceClock: (Long) -> Unit,
        private val events: MutableList<String>,
        private val startFailure: Throwable?,
        private val blockStart: Boolean,
        private val sourceResolution: LiveSourceResolution,
    ) : LiveFrameSource {
        private val callbackMonitor = Any()
        private val callbackOwnedByCurrentThread: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        private val callbackReleasedForQuiescence: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        private var callback: ((OwnedYuvFrame) -> Unit)? = null
        private var failureCallback: ((Throwable) -> Unit)? = null
        private var acceptingCallbacks = true
        private var inFlightCallbacks = 0
        private val quiescenceCallbacks = mutableListOf<() -> Unit>()
        @Volatile var stopped = false
        @Volatile var active = false
        @Volatile var startCount = 0
        @Volatile var stopCount = 0
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val inFlightCallbackEntered = CountDownLatch(1)
        val allowInFlightCallback = CountDownLatch(1)
        var onStopFrame: (() -> OwnedYuvFrame)? = null

        override fun start(
            onSourceStarted: (LiveSourceResolution) -> Unit,
            onFrame: (OwnedYuvFrame) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            startCount++
            events += "source.start.entered"
            startEntered.countDown()
            if (blockStart) check(allowStart.await(1, TimeUnit.SECONDS))
            startFailure?.let { throw it }
            onSourceStarted(sourceResolution)
            callback = onFrame
            failureCallback = onFailure
            active = true
            events += "source.start.completed"
        }

        override fun stop(onCallbacksQuiesced: () -> Unit) {
            stopCount++
            events += "source.stop"
            stopEntered.countDown()
            stopped = true
            active = false
            onStopFrame?.invoke()?.let(::emit)
            val callbacks = synchronized(callbackMonitor) {
                acceptingCallbacks = false
                quiescenceCallbacks += onCallbacksQuiesced
                releaseCurrentCallbackOwnershipLocked()
                takeQuiescenceCallbacksLocked()
            }
            callbacks.forEach { callback -> callback() }
        }

        fun emit(frame: OwnedYuvFrame) {
            if (!tryEnterCallback()) return
            claimCurrentThreadCallbackOwnership()
            try {
                advanceClock(frame.timestampNs)
                callback!!.invoke(frame)
            } finally {
                leaveCallback()
            }
        }

        fun fail(error: Throwable) {
            checkNotNull(failureCallback).invoke(error)
        }

        fun emitInFlight(frame: OwnedYuvFrame): Thread {
            check(tryEnterCallback())
            return Thread {
                claimCurrentThreadCallbackOwnership()
                inFlightCallbackEntered.countDown()
                try {
                    check(allowInFlightCallback.await(1, TimeUnit.SECONDS))
                    advanceClock(frame.timestampNs)
                    callback!!.invoke(frame)
                } finally {
                    leaveCallback()
                }
            }.apply { start() }
        }

        private fun tryEnterCallback(): Boolean = synchronized(callbackMonitor) {
            if (!acceptingCallbacks) false else true.also {
                inFlightCallbacks += 1
            }
        }

        private fun claimCurrentThreadCallbackOwnership() {
            callbackOwnedByCurrentThread.set(true)
            callbackReleasedForQuiescence.set(false)
        }

        private fun leaveCallback() {
            val callbacks = synchronized(callbackMonitor) {
                if (callbackReleasedForQuiescence.get() == true) {
                    emptyList()
                } else {
                    check(inFlightCallbacks > 0)
                    inFlightCallbacks -= 1
                    takeQuiescenceCallbacksLocked()
                }
            }
            callbackOwnedByCurrentThread.remove()
            callbackReleasedForQuiescence.remove()
            callbacks.forEach { callback -> callback() }
        }

        private fun takeQuiescenceCallbacksLocked(): List<() -> Unit> =
            if (!acceptingCallbacks && inFlightCallbacks == 0) {
                quiescenceCallbacks.toList().also { quiescenceCallbacks.clear() }
            } else {
                emptyList()
            }

        private fun releaseCurrentCallbackOwnershipLocked() {
            if (callbackOwnedByCurrentThread.get() == true && callbackReleasedForQuiescence.get() != true) {
                check(inFlightCallbacks > 0)
                inFlightCallbacks -= 1
                callbackReleasedForQuiescence.set(true)
            }
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

        fun completeSuccess(
            normalizedImageLandmarks: List<Landmark>? = null,
            worldLandmarks: List<Landmark>? = null,
        ) {
            callback!!.invoke(
                InferenceResult.Success(
                    callbackNowNs - 1_000_000_000L,
                    posePresent = normalizedImageLandmarks != null || worldLandmarks != null,
                    normalizedImageLandmarks = normalizedImageLandmarks,
                    worldLandmarks = worldLandmarks,
                ),
            )
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

    private class FakeThermalProbe(
        override val available: Boolean,
    ) : LiveThermalProbe {
        private var nowNs: (() -> Long)? = null
        private val samples = mutableListOf<LiveThermalObservation>()
        var disposeCount = 0
        override val unavailableReason: String?
            get() = if (available) null else "thermal_api_unavailable"

        override fun start(nowNs: () -> Long) {
            check(available) { "thermal_api_unavailable" }
            this.nowNs = nowNs
            samples += LiveThermalObservation(nowNs(), 0)
        }

        fun emit(status: Int, nowNs: Long) {
            check(this.nowNs != null) { "thermal probe not started" }
            samples += LiveThermalObservation(nowNs, status)
        }

        override fun observations(): List<LiveThermalObservation> = samples.toList()

        override fun dispose() {
            disposeCount++
            nowNs = null
        }
    }

    private class FakeScheduler(
        private val events: MutableList<String>,
        private val runNonPositiveActions: Boolean,
    ) : LiveScheduler {
        private data class Entry(
            val delayNs: Long,
            val action: () -> Unit,
            var disposed: Boolean = false,
            var claimed: Boolean = false,
        )

        private val monitor = java.lang.Object()
        private val entries = mutableListOf<Entry>()
        private var completedNonPositiveActions = 0
        var shutdownCount = 0
        private val shutdown = CountDownLatch(1)

        override fun scheduleAfter(delayNs: Long, action: () -> Unit): Disposable {
            require(delayNs >= 0L) { "delayNs must be non-negative" }
            val entry = Entry(delayNs, action)
            synchronized(monitor) {
                entries += entry
                monitor.notifyAll()
            }
            if (entry.delayNs <= 0L && runNonPositiveActions) {
                Thread({
                    if (claim(entry)) {
                        try {
                            entry.action()
                        } finally {
                            synchronized(monitor) {
                                completedNonPositiveActions += 1
                                monitor.notifyAll()
                            }
                        }
                    }
                }, "test-live-scheduler-immediate").apply {
                    isDaemon = true
                    start()
                }
            }
            return object : Disposable {
                override fun dispose() {
                    synchronized(monitor) { entry.disposed = true }
                }
            }
        }

        val scheduledDelays: List<Long>
            get() = synchronized(monitor) { entries.map { it.delayNs } }

        val hasRunnableAction: Boolean
            get() = synchronized(monitor) { entries.any { !it.disposed && !it.claimed } }

        fun awaitScheduledCount(expected: Int, timeoutMillis: Long): Boolean = awaitCondition(timeoutMillis) {
            entries.size >= expected
        }

        fun awaitNonPositiveExecution(timeoutMillis: Long): Boolean = awaitCondition(timeoutMillis) {
            completedNonPositiveActions > 0
        }

        fun fireNext() {
            val entry = synchronized(monitor) {
                entries.firstOrNull { !it.disposed && !it.claimed }
                    ?.also { it.claimed = true }
                    ?: error("no scheduled action")
            }
            entry.action()
        }

        private fun claim(entry: Entry): Boolean = synchronized(monitor) {
            if (entry.disposed || entry.claimed) false else true.also { entry.claimed = true }
        }

        private fun awaitCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean {
            val deadlineNs = System.nanoTime() + timeoutMillis * 1_000_000L
            synchronized(monitor) {
                while (!condition()) {
                    val remainingNs = deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) return false
                    TimeUnit.NANOSECONDS.timedWait(monitor, remainingNs)
                }
                return true
            }
        }

        override fun shutdown() {
            shutdownCount++
            events += "scheduler.shutdown"
            shutdown.countDown()
        }

        fun awaitShutdown(): Boolean = shutdown.await(1, TimeUnit.SECONDS)
    }

    private companion object {
        const val FIVE_SECONDS_NS = 5_000_000_000L
        const val HOURS_SCALE_START_NS = 43_200_000_000_000L

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

        fun largeFrame(timestampNs: Long): OwnedYuvFrame {
            val width = 4_000
            val height = 3_000
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            return OwnedYuvFrame(
                yBytes = ByteArray(width * height) { 100.toByte() },
                yRowStride = width,
                yPixelStride = 1,
                uBytes = ByteArray(chromaWidth * chromaHeight) { 128.toByte() },
                uRowStride = chromaWidth,
                uPixelStride = 1,
                vBytes = ByteArray(chromaWidth * chromaHeight) { 128.toByte() },
                vRowStride = chromaWidth,
                vPixelStride = 1,
                width = width,
                height = height,
                rotation = FrameRotation.R0,
                mirrored = false,
                rawDataSpace = OwnedYuvFrame.ADMITTED_RAW_DATASPACE,
                colorStandard = YuvColorStandard.BT601,
                colorRange = YuvColorRange.FULL,
                timestampNs = timestampNs,
            )
        }
    }
}
