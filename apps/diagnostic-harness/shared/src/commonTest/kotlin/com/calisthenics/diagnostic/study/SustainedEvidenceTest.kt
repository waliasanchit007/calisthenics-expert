package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.telemetry.PipelineDisposition
import com.calisthenics.diagnostic.telemetry.PipelineStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SustainedEvidenceTest {
    @Test
    fun validObservationRendersTheFixedProtocolAndNoCallerSuppliedVerdict() {
        val observation = validObservation()

        val validation = validateM05BSustainedObservation(observation)
        val rendered = Json.parseToJsonElement(renderM05BSustainedObservation(observation)).jsonObject

        assertTrue(validation.admitted)
        assertEquals(emptyList(), validation.blockingCodes)
        assertEquals(1_500_000_000_000L, rendered.getValue("duration_target_ns").jsonPrimitive.content.toLong())
        assertEquals(1_500_000_000_000L, rendered.getValue("stop_requested_ns").jsonPrimitive.content.toLong())
        assertFalse(rendered.getValue("video_enabled").jsonPrimitive.boolean)
        assertEquals(30, rendered.getValue("camera_fps").jsonPrimitive.content.toInt())
        assertTrue(rendered.getValue("blocking_codes").jsonArray.isEmpty())
        assertFalse("admitted" in rendered)
    }

    @Test
    fun durationVideoCameraAndDurableBoundaryAreLoadBearing() {
        val original = validObservation()
        val mutations = listOf(
            original.copy(durationTargetNs = 1L) to "DURATION.TARGET",
            original.copy(actualCaptureSpanNs = 1_501_000_000_001L) to "DURATION.OVERSHOOT",
            original.copy(videoEnabled = true) to "PROTOCOL.VIDEO_ENABLED",
            original.copy(cameraFps = 29) to "PROTOCOL.CAMERA_FPS",
            original.copy(boundaryEvents = listOf("attempt_open", "capture_start")) to
                "BOUNDARY.ADMISSION_NOT_DURABLE_BEFORE_CAPTURE",
        )

        mutations.forEach { (value, code) ->
            assertTrue(code in validateM05BSustainedObservation(value).blockingCodes, code)
        }
    }

    @Test
    fun everyStageEquationIsDerived() {
        val original = validObservation()
        val mutations = listOf(
            original.stages.copy(yuvFramesReleased = 3) to "STAGE.YUV",
            original.stages.copy(preprocessingCompleted = 3) to "STAGE.PREPROCESS",
            original.stages.copy(cadenceRejected = 5) to "STAGE.CADENCE",
            original.stages.copy(inferenceFailed = 1) to "STAGE.INFERENCE",
            original.stages.copy(poseRecordsDurable = 3) to "STAGE.POSE",
        )

        mutations.forEach { (stages, code) ->
            assertTrue(code in validateM05BSustainedObservation(original.copy(stages = stages)).blockingCodes, code)
        }
    }

    @Test
    fun stageAccountingCombinesBoundedPipelineCountsWithReconciledDurablePoseCount() {
        val counts = mapOf(
            (PipelineStage.CAMERA_CALLBACK to PipelineDisposition.RECEIVED) to 10L,
            (PipelineStage.YUV_OWNERSHIP to PipelineDisposition.ACQUIRED) to 10L,
            (PipelineStage.YUV_OWNERSHIP to PipelineDisposition.RELEASED) to 10L,
            (PipelineStage.PREPROCESSING to PipelineDisposition.STARTED) to 10L,
            (PipelineStage.PREPROCESSING to PipelineDisposition.COMPLETED) to 10L,
            (PipelineStage.CADENCE to PipelineDisposition.CADENCE_SELECTION) to 4L,
            (PipelineStage.CADENCE to PipelineDisposition.BACKPRESSURE) to 2L,
            (PipelineStage.INFERENCE to PipelineDisposition.SUBMITTED) to 4L,
            (PipelineStage.INFERENCE to PipelineDisposition.COMPLETED) to 4L,
        )
        val finalCounts = Counts(
            cameraFramesReceived = 10,
            inferenceFramesSubmitted = 4,
            inferenceRecordsEmitted = 4,
            poseDetectedRecords = 3,
            framesSkippedBeforeInference = 6,
            framesSkippedBeforeInferenceByReason = mapOf(
                "cadence_selection" to 4,
                "backpressure" to 2,
                "session_stopping" to 0,
            ),
            trailingFramesSkippedAfterLastEmittedRecord = 0,
            inferenceFailures = 0,
        )

        val stages = deriveM05BSustainedStageAccounting(
            pipelineCounts = counts,
            finalCounts = finalCounts,
            reconciledPoseRecordCount = 4,
            sourceFailures = 0,
            processFailures = 0,
        )

        assertEquals(10, stages.cameraCallbacksReceived)
        assertEquals(10, stages.yuvFramesReleased)
        assertEquals(4, stages.cadenceAdmitted)
        assertEquals(6, stages.cadenceRejected)
        assertEquals(4, stages.poseRecordsAppended)
        assertEquals(4, stages.poseRecordsDurable)
    }

    @Test
    fun stageAccountingDoesNotPretendAnUnpublishedPoseStreamWasDurable() {
        val finalCounts = Counts(1, 1, 1, 1, 0, emptyMap(), 0, 0)

        val stages = deriveM05BSustainedStageAccounting(
            pipelineCounts = emptyMap(),
            finalCounts = finalCounts,
            reconciledPoseRecordCount = null,
            sourceFailures = 0,
            processFailures = 1,
        )

        assertEquals(1, stages.poseRecordsAppended)
        assertEquals(0, stages.poseRecordsDurable)
        assertEquals(1, stages.processFailures)
    }

    @Test
    fun telemetryAndScalarBatteryValuesNeverDefault() {
        val original = validObservation()
        val missingStart = original.copy(battery = original.battery.copy(startTemperatureC = null))
        val badDelta = original.copy(battery = original.battery.copy(deltaTemperatureC = 0.0))
        val telemetryGap = original.copy(telemetry = original.telemetry.copy(maxInterSampleGapNs = 2_000_000_001L))
        val missingTerminal = original.copy(telemetry = original.telemetry.copy(terminalSample = false))

        assertTrue(
            "BATTERY.START_TEMPERATURE_UNAVAILABLE" in
                validateM05BSustainedObservation(missingStart).blockingCodes,
        )
        assertTrue("BATTERY.DELTA" in validateM05BSustainedObservation(badDelta).blockingCodes)
        assertTrue("TELEMETRY.MAX_GAP" in validateM05BSustainedObservation(telemetryGap).blockingCodes)
        assertTrue("TELEMETRY.END_COVERAGE" in validateM05BSustainedObservation(missingTerminal).blockingCodes)
    }

    @Test
    fun finalAttemptAndAdmissionDigestAreBoundToTheFrozenOrder() {
        val original = validObservation()
        val staleAdmission = original.copy(
            admission = original.admission.copy(sha256 = "f".repeat(64)),
        )
        val reordered = original.copy(
            attemptChain = listOf(
                M05BAttemptChain.bind(
                    M05BAttemptEntry(2, 15, "c".repeat(64), M05BAttemptOutcome.COMPLETED, null),
                ),
            ),
        )

        assertTrue("ATTEMPT.ADMISSION_DIGEST" in validateM05BSustainedObservation(staleAdmission).blockingCodes)
        assertTrue("ATTEMPT.CHAIN_ORDER" in validateM05BSustainedObservation(reordered).blockingCodes)
    }

    @Test
    fun latencyPercentilesAreRenderedAsNearestRankContainingBounds() {
        val rendered = Json.parseToJsonElement(renderM05BSustainedObservation(validObservation())).jsonObject
        val percentiles = rendered.getValue("latency").jsonObject.getValue("percentiles").jsonObject

        assertEquals(
            2_000_000L,
            percentiles.getValue("50").jsonObject.getValue("upper_inclusive_ns").jsonPrimitive.content.toLong(),
        )
        assertEquals(
            8_000_000L,
            percentiles.getValue("95").jsonObject.getValue("upper_inclusive_ns").jsonPrimitive.content.toLong(),
        )
    }

    private fun validObservation(): M05BSustainedObservation {
        val histogram = SustainedLatencyHistogram().apply {
            record(500_000L)
            record(1_500_000L)
            record(3_000_000L)
            record(7_000_000L)
        }
        val attempt = M05BAttemptChain.bind(
            M05BAttemptEntry(1, 10, "c".repeat(64), M05BAttemptOutcome.COMPLETED, null),
        )
        return M05BSustainedObservation(
            sessionId = "00000000-0000-4000-8000-000000000010",
            attemptSequence = 1,
            cadenceFps = 10,
            videoEnabled = false,
            cameraFps = 30,
            durationTargetNs = M05BSustainedProtocol.TARGET_DURATION_NS,
            stopRequestedNs = M05BSustainedProtocol.TARGET_DURATION_NS,
            actualCaptureSpanNs = 1_500_500_000_000L,
            boundaryEvents = M05BSustainedObservation.REQUIRED_BOUNDARY_EVENTS,
            envelope = M05BSustainedArtifactRef("envelope.json", "a".repeat(64)),
            order = M05BSustainedArtifactRef("execution-order.json", "b".repeat(64)),
            admission = M05BSustainedArtifactRef("run-admission.json", "c".repeat(64)),
            attemptChain = listOf(attempt),
            stages = M05BSustainedStageAccounting(
                cameraCallbacksReceived = 10,
                yuvFramesAcquired = 10,
                yuvFramesReleased = 10,
                preprocessingStarted = 10,
                preprocessingCompleted = 10,
                cadenceAdmitted = 4,
                cadenceRejected = 6,
                inferenceSubmitted = 4,
                inferenceCompleted = 4,
                inferenceFailed = 0,
                poseRecordsAppended = 4,
                poseRecordsDurable = 4,
                sourceFailures = 0,
                processFailures = 0,
            ),
            latency = histogram.snapshot(),
            telemetry = M05BSustainedTelemetrySummary(
                fixedSampleCount = 1_502,
                firstTSessionNs = 0L,
                lastTSessionNs = 1_500_500_000_000L,
                maxInterSampleGapNs = 1_000_000_000L,
                terminalSample = true,
            ),
            battery = M05BSustainedBatterySummary(29.5, 30.0, 0.5),
            sourceOutcome = M05BSustainedSourceOutcome.COMPLETED,
            processOutcome = M05BSustainedProcessOutcome.COMPLETED,
        )
    }
}
