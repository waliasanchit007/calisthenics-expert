package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.liveCaptureFailureCodeOr
import com.calisthenics.diagnostic.toLivePhase
import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.camera.CameraXFrameSource
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.RawPoseFrame
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
import com.calisthenics.diagnostic.session.TerminalState
import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition
import com.calisthenics.diagnostic.telemetry.FixedTelemetryReadings
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySample
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySchedule
import com.calisthenics.diagnostic.telemetry.FixedTelemetryScheduler
import com.calisthenics.diagnostic.telemetry.PipelineDisposition
import com.calisthenics.diagnostic.telemetry.PipelineStage
import com.calisthenics.diagnostic.telemetry.PipelineStageEvent
import com.calisthenics.diagnostic.telemetry.PipelineTelemetryRecordingMode
import com.calisthenics.diagnostic.telemetry.SustainedStageCounterSample
import com.calisthenics.diagnostic.telemetry.TelemetryRecord
import com.calisthenics.diagnostic.telemetry.TelemetrySink
import com.calisthenics.diagnostic.telemetry.TelemetryValue
import com.calisthenics.diagnostic.ui.LiveRunPhase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

class LiveCameraCompositionTest {
    @Test
    fun explicitRecordingModesKeepFullStageEventsShortAndUseBoundedCountersForSustainedRuns() {
        withHarness(Harness()) { short ->
            assertIs<LiveCameraStartResult.Started>(
                short.start(10, recordingMode = PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION),
            )
            short.source.emit(frame(timestampNs = 1_000_002_000L))
            short.inference.completeSuccess()
            short.composition.stop()
            assertTrue(short.telemetryRecords.any { it is PipelineStageEvent })
            assertTrue(short.telemetryRecords.none { it is SustainedStageCounterSample })
        }
        withHarness(Harness()) { sustained ->
            assertIs<LiveCameraStartResult.Started>(
                sustained.start(10, recordingMode = PipelineTelemetryRecordingMode.SUSTAINED),
            )
            sustained.source.emit(frame(timestampNs = 1_000_002_000L))
            sustained.inference.completeSuccess()
            sustained.composition.stop()
            assertTrue(sustained.telemetryRecords.none { it is PipelineStageEvent })
            assertTrue(sustained.telemetryRecords.any { it is SustainedStageCounterSample })
        }
        assertEquals(30, CameraXFrameSource.TARGET_CAMERA_FPS)
    }

    @Test
    fun liveManifestDoesNotEmitACallerSelectedParticipantIdentity() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            assertEquals(
                "synthetic-m05-${harness.manifest.sessionId.value}",
                harness.manifest.participantPseudonymId,
            )
        }
    }

    @Test
    fun finalizedManifestAndM05AdmissionShareTheDerivedIdentityAndDetectionEvidence() {
        withHarness(Harness(invokeManifestAtFinalization = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()
            harness.composition.stop()

            val admission = assertNotNull(harness.composition.m05Admission())
            val finalized = assertNotNull(harness.finalizedManifest)
            val marker = assertIs<JsonObject>(finalized.studyContext.getValue("m05_admission"))
            assertEquals(admission.sessionKind, finalized.sessionKind)
            assertEquals(admission.participantPseudonymId, finalized.participantPseudonymId)
            assertEquals(admission.participantPseudonymId, marker["participant_pseudonym_id"]?.jsonPrimitive?.content)
            assertEquals(admission.inputClass.wireValue, marker["input_class"]?.jsonPrimitive?.content)
            assertEquals(admission.detectionActiveFrameFraction, marker["detection_active_frame_fraction"]?.jsonPrimitive?.double)
            assertEquals(admission.detectionEvidenceState.wireValue, marker["detection_evidence_state"]?.jsonPrimitive?.content)
            assertEquals(admission.operatorAttestedNoPerson, marker["operator_attested_no_person"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun falseOperatorAttestationBlocksBeforeAnyCameraResourceIsCreated() {
        val harness = Harness()

        val result = harness.composition.start(
            configuredInferenceFps = 10,
            operatorAttestedNoPerson = false,
        )

        val unavailable = assertIs<LiveCameraStartResult.Unavailable>(result)
        assertIs<LiveUnavailableReason.OperatorAttestationRequired>(unavailable.reason)
        assertEquals(0, harness.inferenceCreations)
        assertEquals(0, harness.sourceCreations)
    }

    @Test
    fun unsupportedFpsReleasesOwnedSchedulerBeforeAnySessionResourceIsCreated() {
        val harness = Harness()

        val result = harness.composition.start(5, operatorAttestedNoPerson = true)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.inferenceCreations)
        assertEquals(0, harness.sourceCreations)
    }

    @Test
    fun partialConstructionFailureReleasesInferenceAndSchedulerExactlyOnce() {
        val harness = Harness(finalizerFailure = IllegalStateException("finalizer construction failed"))

        val result = harness.composition.start(10, operatorAttestedNoPerson = true)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.inference.disposeCount)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertSessionDispatcherReleased(harness)
    }

    @Test
    fun registrationRejectionReleasesInferenceAndSchedulerExactlyOnce() {
        val unrelatedDispatcherStarted = CountDownLatch(1)
        val unrelatedDispatcher = SingleThreadSessionDispatcher()
        unrelatedDispatcher.dispatch { unrelatedDispatcherStarted.countDown() }
        assertTrue(
            unrelatedDispatcherStarted.await(1, TimeUnit.SECONDS),
            "unrelated dispatcher worker did not start before the leak baseline",
        )
        val harness = Harness(registrationResult = SessionRunnerRegistrationResult.REJECTED)

        try {
            val result = harness.composition.start(10, operatorAttestedNoPerson = true)

            assertIs<LiveCameraStartResult.Unavailable>(result)
            assertEquals(1, harness.inference.disposeCount)
            assertEquals(1, harness.scheduler.shutdownCount)
            assertEquals(0, harness.sourceCreations)
            assertSessionDispatcherReleased(harness)
        } finally {
            unrelatedDispatcher.shutdown()
        }
    }

    @Test
    fun sourceFactoryFailureReleasesPartiallyConstructedResourcesExactlyOnce() {
        val harness = Harness(sourceFactoryFailure = IllegalStateException("source construction failed"))

        val result = harness.composition.start(10, operatorAttestedNoPerson = true)

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.inference.disposeCount)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertSessionDispatcherReleased(harness)
    }

    @Test
    fun sourceStartFailureStopsSourceAndReleasesEveryOwnedResourceExactlyOnce() {
        val harness = Harness(sourceStartFailure = IllegalStateException("source start failed"))

        val result = runCatching { harness.composition.start(10, operatorAttestedNoPerson = true) }
            .getOrElse { fail("source-start failure must become typed unavailability", it) }

        assertIs<LiveCameraStartResult.Unavailable>(result)
        assertEquals(1, harness.source.stopCount)
        assertEquals(1, harness.inference.disposeCount)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertTrue(harness.events.indexOf("source.start.entered") < harness.events.indexOf("source.stop"))
        assertTrue(harness.events.indexOf("source.stop") < harness.events.indexOf("inference.dispose"))
        assertSessionDispatcherReleased(harness)
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

        val result = assertIs<LiveCameraStartResult.Unavailable>(harness.composition.start(10, operatorAttestedNoPerson = true))

        assertEquals("camera_permission_denied", result.reason.code)
        assertEquals(1, harness.scheduler.shutdownCount)
        assertEquals(0, harness.sourceCreations)
        assertEquals(0, harness.inferenceCreations)
    }

    @Test
    fun exactCameraCapabilityIsValidatedBeforeSourceOrSessionCreation() {
        val harness = Harness(capability = admittedCapability.copy(sourceColorStandard = "BT709"))

        val result = harness.composition.start(10, operatorAttestedNoPerson = true)

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

        val result = assertIs<LiveCameraStartResult.Unavailable>(harness.composition.start(10, operatorAttestedNoPerson = true))

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
    fun sourceFailureCodeIsPreservedInsteadOfFallingBackToTimeout() {
        assertEquals(
            "live_source_failed",
            liveCaptureFailureCodeOr(LiveInternalFailure.SourceFailure("live_source_failed"), "live_capture_timeout"),
        )
        assertEquals(
            "live_inference_stalled",
            liveCaptureFailureCodeOr(LiveInternalFailure.LiveInferenceStalled, "live_capture_timeout"),
        )
    }

    @Test
    fun publishedCompletionWithInternalFailureIsShownAsFailed() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.source.fail(IllegalStateException("source detail must not enter UI"))
            harness.inference.completeSuccess()

            val completion = assertNotNull(harness.composition.completion())
            val phase = completion.toLivePhase()

            assertIs<LiveRunPhase.Failed>(phase)
            assertEquals("live_source_failed", phase.reason)
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
    fun callbackBeforeRunnerOrAdmissionIsReadyClosesAsSourceNotReady() {
        val harness = Harness()

        harness.composition.handleFrame(frame(timestampNs = 1_000_002_000L))

        val accounting = harness.composition.callbackAccounting()
        assertEquals(1L, accounting.deliveredCallbacks)
        assertEquals(0L, accounting.unclosedCallbacks)
        assertEquals(1L, accounting.counts.getValue(CallbackTerminalDisposition.SOURCE_NOT_READY))
    }

    @Test
    fun sourceNotReadyCallbackReconcilesThroughStopSealAndFinalization() {
        withHarness(Harness(callbackBeforeRunnerReady = true)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            val completion = harness.composition.stop()

            assertIs<FinalizationResult.Published>(completion.finalization)
            assertEquals(0L, completion.snapshot.counts.cameraFramesReceived)
            assertEquals(1L, harness.composition.callbackAccounting().deliveredCallbacks)
            assertEquals(
                1L,
                harness.composition.callbackAccounting().counts
                    .getValue(CallbackTerminalDisposition.SOURCE_NOT_READY),
            )
        }
    }

    @Test
    fun negativeAndNonMonotonicCaptureRebasesCloseAsClockFaultNotPreprocessing() {
        withHarness(Harness(clockNowNs = 1_000_010_000L)) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            harness.source.emit(frame(timestampNs = 1_000_009_000L))
            harness.source.emit(frame(timestampNs = 1_000_012_000L))
            harness.inference.completeSuccess()
            harness.source.emit(frame(timestampNs = 1_000_011_000L))

            val accounting = harness.composition.callbackAccounting()
            assertEquals(2L, accounting.counts.getValue(CallbackTerminalDisposition.CLOCK_FAULT))
            assertEquals(0L, accounting.counts[CallbackTerminalDisposition.PREPROCESSING_FAILED] ?: 0L)
        }
    }

    @Test
    fun negotiatedGeometryLossAndChangeCloseAsSourceLossNotPreprocessing() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L, rotation = FrameRotation.R90))
            harness.inference.completeSuccess()
            harness.source.emit(frame(timestampNs = 1_100_002_000L, rotation = FrameRotation.R0))
            harness.source.rejectDeliveredFrame()

            val accounting = harness.composition.callbackAccounting()
            assertEquals(2L, accounting.counts.getValue(CallbackTerminalDisposition.SOURCE_LOSS))
            assertEquals(0L, accounting.counts[CallbackTerminalDisposition.PREPROCESSING_FAILED] ?: 0L)
        }
    }

    @Test
    fun canonicalizationFailureClosesAsPreprocessingFailed() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            harness.source.emit(malformedFrame(timestampNs = 1_000_002_000L))

            assertEquals(
                1L,
                harness.composition.callbackAccounting().counts
                    .getValue(CallbackTerminalDisposition.PREPROCESSING_FAILED),
            )
            assertEquals(
                1,
                harness.telemetryRecords.filterIsInstance<PipelineStageEvent>().count { event ->
                    event.stage == PipelineStage.PREPROCESSING && event.disposition == PipelineDisposition.FAILED
                },
            )
        }
    }

    @Test
    fun cadenceBackpressureAndStoppingKeepDistinctTerminalOutcomes() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.source.emit(frame(timestampNs = 1_000_003_000L))
            harness.inference.completeSuccess()
            harness.source.emit(frame(timestampNs = 1_010_002_000L))
            harness.source.onStopFrame = { frame(timestampNs = 1_300_002_000L) }

            harness.composition.stop()

            val counts = harness.composition.callbackAccounting().counts
            assertEquals(1L, counts.getValue(CallbackTerminalDisposition.BACKPRESSURE))
            assertEquals(1L, counts.getValue(CallbackTerminalDisposition.CADENCE_SELECTION))
            assertEquals(1L, counts.getValue(CallbackTerminalDisposition.SESSION_STOPPING))
        }
    }

    @Test
    fun inferenceSuccessClosesItsOriginatingCallbackAsCompleted() {
        withHarness(Harness()) { successful ->
            assertIs<LiveCameraStartResult.Started>(successful.start(10))
            successful.source.emit(frame(timestampNs = 1_000_002_000L))
            successful.inference.completeSuccess()
            assertEquals(
                1L,
                successful.composition.callbackAccounting().counts
                    .getValue(CallbackTerminalDisposition.INFERENCE_COMPLETED),
            )
        }
    }

    @Test
    fun inferenceFailureFencesCallbacksBeforeClosingItsOriginatingCallbackAsFailed() {
        val failed = Harness()
        try {
            assertIs<LiveCameraStartResult.Started>(failed.start(10))
            failed.source.emit(frame(timestampNs = 1_000_002_000L))
            failed.source.onStopFrame = { frame(timestampNs = 1_000_003_000L) }
            failed.inference.completeFailure()
            val failedSnapshot = assertNotNull(failed.composition.snapshot())
            assertEquals(
                1L,
                failed.composition.callbackAccounting().counts
                    .getValue(CallbackTerminalDisposition.INFERENCE_FAILED),
            )
            assertEquals(2L, failedSnapshot.counts.cameraFramesReceived)
            assertEquals(2L, failed.composition.callbackAccounting().deliveredCallbacks)
            assertTrue(failed.events.indexOf("source.stop") < failed.events.indexOf("encoder.finalize"))
            assertTrue(failed.events.indexOf("encoder.finalize") < failed.events.indexOf("telemetry.cancel"))
            assertTrue(failed.events.indexOf("telemetry.join") < failed.events.indexOf("telemetry.terminal"))
        } finally {
            failed.composition.abandon()
        }
    }

    @Test
    fun finalizationFencesCallbacksInferenceEncoderAndTelemetryBeforeSeal() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()

            harness.composition.stop()

            val recordsAtSeal = harness.telemetryRecords.size
            harness.telemetryScheduler.runScheduled()
            assertEquals(recordsAtSeal, harness.telemetryRecords.size)
            assertTrue(harness.events.indexOf("writer.open") < harness.events.indexOf("telemetry.sample"))
            assertTrue(harness.events.indexOf("telemetry.sample") < harness.events.indexOf("source.start.entered"))

            assertEquals(
                listOf(
                    "source.stop",
                    "encoder.finalize",
                    "telemetry.cancel",
                    "telemetry.join",
                    "telemetry.terminal",
                    "finalizer.finalize",
                ),
                harness.events.filter { event ->
                    event in setOf(
                        "source.stop",
                        "encoder.finalize",
                        "telemetry.cancel",
                        "telemetry.join",
                        "telemetry.terminal",
                        "finalizer.finalize",
                    )
                },
            )
            assertEquals(0L, harness.composition.callbackAccounting().unclosedCallbacks)
        }
    }

    @Test
    fun analyzerCallbackReturnsBeforeDurableStageAppendIsReleased() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val harness = Harness(
            blockDurableStageAppend = true,
            durableStageAppendEntered = sinkEntered,
            releaseDurableStageAppend = releaseSink,
        )
        val analyzerReturned = CountDownLatch(1)
        val analyzerThread = Thread {
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            analyzerReturned.countDown()
        }
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            analyzerThread.start()
            assertTrue(sinkEntered.await(1, TimeUnit.SECONDS))

            assertTrue(
                analyzerReturned.await(100, TimeUnit.MILLISECONDS),
                "analyzer callback performed durable telemetry I/O",
            )
        } finally {
            releaseSink.countDown()
            analyzerThread.join(1_000L)
            harness.close()
        }
    }

    @Test
    fun dispatcherDrainCompletesBeforeFinalizerSeal() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val harness = Harness(
            blockDurableStageAppend = true,
            durableStageAppendEntered = sinkEntered,
            releaseDurableStageAppend = releaseSink,
        )
        val stopReturned = CountDownLatch(1)
        val stopFailure = AtomicReference<Throwable?>(null)
        val stopThread = Thread {
            runCatching { harness.composition.stop() }.onFailure(stopFailure::set)
            stopReturned.countDown()
        }
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            assertTrue(sinkEntered.await(1, TimeUnit.SECONDS))
            harness.inference.completeSuccess()
            stopThread.start()

            assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, harness.finalizerCalls)
            releaseSink.countDown()
            assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
            assertNull(stopFailure.get())
            assertEquals(1, harness.finalizerCalls)
        } finally {
            releaseSink.countDown()
            stopThread.join(1_000L)
            harness.close()
        }
    }

    @Test
    fun samplerJoinFailureProducesTypedCompletionAndCompletesAbort() {
        val cause = IllegalStateException("scheduled telemetry action failed\nsecret continuation")
        val harness = Harness(telemetryJoinFailure = cause)
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            harness.inference.completeSuccess()

            val completion = harness.composition.stop()

            val telemetryFailure = assertIs<LiveInternalFailure.TelemetryFailure>(completion.internalFailure)
            assertEquals("live_telemetry_sampler_failed", telemetryFailure.code)
            assertTrue(telemetryFailure.sanitizedCause.contains("IllegalStateException"))
            assertTrue(telemetryFailure.sanitizedCause.contains("scheduled telemetry action failed"))
            assertFalse(telemetryFailure.sanitizedCause.contains('\n'))
            assertIs<FinalizationResult.Failed>(completion.finalization)
            assertEquals(1, harness.abortRequests)
            assertEquals(1, harness.abortCompletions)
            assertEquals(1, harness.events.count { it == "telemetry.join" })
        } finally {
            harness.composition.abandon()
        }
    }

    @Test
    fun durableQueueWorkerFailureProducesTypedCompletionAndCompletesAbort() {
        val cause = IllegalArgumentException("durable telemetry sink failed")
        val harness = Harness(durableTelemetryFailure = cause)
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            val completion = harness.composition.stop()

            val telemetryFailure = assertIs<LiveInternalFailure.TelemetryFailure>(completion.internalFailure)
            assertEquals("live_telemetry_sink_failed", telemetryFailure.code)
            assertTrue(telemetryFailure.sanitizedCause.contains("IllegalArgumentException"))
            assertIs<FinalizationResult.Failed>(completion.finalization)
            assertEquals(1, harness.abortRequests)
            assertEquals(1, harness.abortCompletions)
            assertEquals(1, harness.events.count { it == "telemetry.join" })
        } finally {
            harness.composition.abandon()
        }
    }

    @Test
    fun boundedQueueOverflowProducesTypedCompletionAndCompletesAbort() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val harness = Harness(
            telemetryQueueCapacity = 1,
            blockEveryDurableAppend = true,
            durableStageAppendEntered = sinkEntered,
            releaseDurableStageAppend = releaseSink,
        )
        val analyzerReturned = CountDownLatch(1)
        val analyzerThread = Thread {
            harness.source.emit(frame(timestampNs = 1_000_002_000L))
            analyzerReturned.countDown()
        }
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))
            assertTrue(sinkEntered.await(1, TimeUnit.SECONDS))

            analyzerThread.start()
            assertFalse(analyzerReturned.await(100, TimeUnit.MILLISECONDS))
            releaseSink.countDown()
            analyzerThread.join(1_000L)

            assertFalse(analyzerThread.isAlive)
            val completion = assertNotNull(harness.composition.completion())
            val telemetryFailure = assertIs<LiveInternalFailure.TelemetryFailure>(completion.internalFailure)
            assertEquals("live_telemetry_queue_overflow", telemetryFailure.code)
            assertIs<FinalizationResult.Failed>(completion.finalization)
            assertEquals(1, harness.abortRequests)
            assertEquals(1, harness.abortCompletions)
            assertEquals(1, harness.events.count { it == "telemetry.join" })
        } finally {
            releaseSink.countDown()
            analyzerThread.join(1_000L)
            harness.composition.abandon()
        }
    }

    @Test
    fun liveSamplerUsesDedicatedSchedulerInsteadOfInferenceDeadlineScheduler() {
        withHarness(Harness()) { harness ->
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            assertEquals(1_000_000_000L, harness.telemetryScheduler.periodNs)
            assertTrue(harness.scheduler.scheduledDelays.isEmpty())
            harness.composition.stop()
            assertTrue(harness.telemetryRecords.filterIsInstance<FixedTelemetrySample>().isNotEmpty())
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

            val snapshot = assertNotNull(harness.composition.snapshot())
            val record = assertNotNull(harness.emittedRecord)
            val manifest = assertNotNull(harness.composition.manifest())
            assertEquals(0L, snapshot.boundedState.submittedHighWaterMark)
            assertEquals(0L, snapshot.boundedState.terminalHighWaterMark)
            assertEquals(TerminalState.EMITTED, snapshot.boundedState.lastTerminalState)
            assertEquals(2_000L, snapshot.boundedState.latestInferenceLatencyNs)
            assertEquals(2_000L, record.captureTimestampNs)
            assertEquals(3_000L, record.inferenceStartNs)
            assertEquals(5_000L, record.inferenceEndNs)
            assertEquals(0L, manifest.clock.t0MonotonicNs)
            assertEquals("session_relative_monotonic_ns", manifest.clock.monotonicTimeBase)
        }
    }

    @Test
    fun sessionFinalizerReceivesSessionRelativeMonotonicClockForDurability() {
        var elapsedRealtimeNs = 9_000_000_000L
        val harness = Harness(
            clockOverride = LiveCameraComposition.AndroidLiveClock { elapsedRealtimeNs },
        )
        try {
            assertIs<LiveCameraStartResult.Started>(harness.start(10))

            assertEquals(0L, assertNotNull(harness.durableWriterClock).invoke())
            elapsedRealtimeNs += 5_000_000_000L
            assertEquals(5_000_000_000L, assertNotNull(harness.durableWriterClock).invoke())
        } finally {
            harness.composition.abandon()
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

            val snapshot = assertNotNull(harness.composition.snapshot())
            val record = assertNotNull(harness.emittedRecord)
            assertEquals(0L, snapshot.boundedState.submittedHighWaterMark)
            assertEquals(0L, snapshot.boundedState.terminalHighWaterMark)
            assertEquals(TerminalState.EMITTED, snapshot.boundedState.lastTerminalState)
            assertEquals(2_000L, snapshot.boundedState.latestInferenceLatencyNs)
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
            assertEquals(
                1L,
                harness.composition.callbackAccounting().counts
                    .getValue(CallbackTerminalDisposition.INFERENCE_FAILED),
            )
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
        val startThread = Thread { startResult.set(harness.composition.start(10, operatorAttestedNoPerson = true)) }
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
        val startThread = Thread { startResult.set(harness.composition.start(10, operatorAttestedNoPerson = true)) }
        try {
            startThread.start()
            assertTrue(harness.source.startEntered.await(1, TimeUnit.SECONDS), "source.start was not entered")

            harness.composition.abandon()
            assertEquals(0, harness.source.stopCount, "teardown stopped source before external start returned")

            harness.source.allowStart.countDown()
            startThread.join(1_000)
            assertTrue(
                harness.source.stopEntered.await(1, TimeUnit.SECONDS),
                "source.stop was not reached after external start completed",
            )

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
            val finalization = assertIs<FinalizationResult.Failed>(
                assertNotNull(nestedCompletion.get()).finalization,
            )
            assertEquals("session_aborted", finalization.failureCode)
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

    private fun assertSessionDispatcherReleased(harness: Harness) {
        assertFailsWith<RejectedExecutionException> {
            harness.sessionDispatchers.single().dispatch {}
        }
        val worker = harness.sessionDispatcherThreads.single()
        worker.join(1_000)
        assertFalse(
            worker.isAlive,
            "owned live session dispatcher worker leaked",
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
        callbackBeforeRunnerReady: Boolean = false,
        telemetryJoinFailure: Throwable? = null,
        durableTelemetryFailure: Throwable? = null,
        telemetryQueueCapacity: Int = 512,
        blockEveryDurableAppend: Boolean = false,
        blockDurableStageAppend: Boolean = false,
        durableStageAppendEntered: CountDownLatch = CountDownLatch(0),
        releaseDurableStageAppend: CountDownLatch = CountDownLatch(0),
    ) {
        val events = mutableListOf<String>()
        val inference = FakeInference(events)
        private val fallbackClock = FakeClock(clockNowNs)
        val clock = clockOverride ?: fallbackClock
        val scheduler = FakeScheduler(events, runNonPositiveSchedulerActions)
        val telemetryScheduler = FakeTelemetryScheduler(events, telemetryJoinFailure)
        val telemetryRecords = java.util.concurrent.CopyOnWriteArrayList<TelemetryRecord>()
        val thermal = FakeThermalProbe(thermalAvailable)
        val sessionDispatchers = mutableListOf<SingleThreadSessionDispatcher>()
        val sessionDispatcherThreads = mutableListOf<Thread>()
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
        var abortRequests = 0
        var abortCompletions = 0
        var durableWriterClock: (() -> Long)? = null
        var manifestCallbackInvoked = false
        val manifestCallbackInvokedLatch = CountDownLatch(1)
        var emittedRecord: RawPoseFrame? = null
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
                sessionDispatcherFactory = {
                    SingleThreadSessionDispatcher().also { dispatcher ->
                        sessionDispatchers += dispatcher
                        dispatcher.dispatch { sessionDispatcherThreads += Thread.currentThread() }
                    }
                },
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
                telemetryProbeFactory = {
                    object : LiveTelemetryProbe {
                        override fun sample() = unavailableTelemetryReadings()
                    }
                },
                telemetrySchedulerFactory = { telemetryScheduler },
                telemetryDispatcherFactory = { sink ->
                    BoundedTelemetryDispatcher(sink, telemetryQueueCapacity)
                },
                beforeRunnerReady = { deliver ->
                    if (callbackBeforeRunnerReady) deliver(frame(timestampNs = 1_000_002_000L))
                },
                encoderFinalizationSlot = { events += "encoder.finalize" },
                sessionFinalizerFactory = { template, manifestAtFinalization, monotonicNowNs ->
                    finalizerFailure?.let { throw it }
                    finalizerCreated = true
                    events += "writer.open"
                    manifest = template
                    durableWriterClock = monotonicNowNs
                    object : SessionFinalizer,
                        com.calisthenics.diagnostic.session.SessionAborter,
                        com.calisthenics.diagnostic.export.DeferredSessionAbort,
                        com.calisthenics.diagnostic.session.EmittedRecordSink,
                        TelemetrySink {
                        private var pendingAbort = false
                        override fun appendPose(record: RawPoseFrame) {
                            emittedRecord = record
                        }

                        override fun append(record: TelemetryRecord) {
                            durableTelemetryFailure?.let { throw it }
                            if (blockEveryDurableAppend || blockDurableStageAppend && record is PipelineStageEvent) {
                                durableStageAppendEntered.countDown()
                                check(releaseDurableStageAppend.await(1, TimeUnit.SECONDS))
                            }
                            telemetryRecords += record
                            if (record is FixedTelemetrySample && record.terminal) {
                                events += "telemetry.terminal"
                            } else if (record is FixedTelemetrySample) {
                                events += "telemetry.sample"
                            }
                        }

                        override fun abort(
                            snapshot: com.calisthenics.diagnostic.session.SealedSessionSnapshot,
                            reason: String,
                        ) {
                            abortRequests++
                            pendingAbort = true
                        }

                        override fun completePendingAbort() {
                            if (pendingAbort) {
                                pendingAbort = false
                                abortCompletions++
                            }
                        }

                        override fun finalize(snapshot: com.calisthenics.diagnostic.session.SealedSessionSnapshot): FinalizationResult {
                            events += "finalizer.finalize"
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
            recordingMode: PipelineTelemetryRecordingMode = PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION,
            onProgress: (SessionRunnerSnapshot) -> Unit = {},
        ): LiveCameraStartResult = composition.start(
            fps,
            operatorAttestedNoPerson = true,
            onProgress = onProgress,
            recordingMode = recordingMode,
        ).also { result ->
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
        private var rejectionCallback: ((Long?) -> Unit)? = null
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
            onFrameRejected: (Long?) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            startCount++
            events += "source.start.entered"
            startEntered.countDown()
            if (blockStart) check(allowStart.await(1, TimeUnit.SECONDS))
            startFailure?.let { throw it }
            onSourceStarted(sourceResolution)
            callback = onFrame
            rejectionCallback = onFrameRejected
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
                try {
                    callback!!.invoke(frame)
                } catch (error: Throwable) {
                    checkNotNull(failureCallback).invoke(error)
                }
            } finally {
                leaveCallback()
            }
        }

        fun fail(error: Throwable) {
            checkNotNull(failureCallback).invoke(error)
        }

        fun rejectDeliveredFrame() {
            checkNotNull(rejectionCallback).invoke(null)
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

        fun completeFailure() {
            callback!!.invoke(
                InferenceResult.Failure(
                    failureSource = "backend",
                    classification = "inference_failed",
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

    private class FakeTelemetryScheduler(
        private val events: MutableList<String>,
        private val joinFailure: Throwable?,
    ) : FixedTelemetryScheduler {
        var periodNs: Long? = null
        private var action: (() -> Unit)? = null
        private var cancelled = false

        override fun scheduleAtFixedRate(periodNs: Long, action: () -> Unit): FixedTelemetrySchedule {
            this.periodNs = periodNs
            this.action = action
            return object : FixedTelemetrySchedule {
                override fun cancel() {
                    cancelled = true
                    events += "telemetry.cancel"
                }

                override fun join() {
                    events += "telemetry.join"
                    joinFailure?.let { throw it }
                }
            }
        }

        fun runScheduled() {
            if (!cancelled) checkNotNull(action).invoke()
        }
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

        fun unavailableTelemetryReadings() = FixedTelemetryReadings(
            thermal = TelemetryValue.Unavailable("thermal_api_unavailable"),
            batteryTemperature = TelemetryValue.Unavailable("battery_temperature_unavailable"),
            batteryLevelOrCharge = TelemetryValue.Unavailable("battery_level_unavailable"),
            charging = TelemetryValue.Unavailable("charging_state_unavailable"),
            powerSource = TelemetryValue.Unavailable("power_source_unavailable"),
            connectivity = TelemetryValue.Unavailable("connectivity_api_unavailable"),
            keepAwake = TelemetryValue.Unavailable("keep_awake_unavailable"),
            brightness = TelemetryValue.Unavailable("brightness_unavailable"),
            lifecycle = TelemetryValue.Unavailable("lifecycle_unavailable"),
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
