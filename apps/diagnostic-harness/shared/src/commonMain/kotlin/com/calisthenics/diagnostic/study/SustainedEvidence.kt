package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.telemetry.PipelineDisposition
import com.calisthenics.diagnostic.telemetry.PipelineStage
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class M05BSustainedArtifactRef(val path: String, val sha256: String)

data class M05BSustainedStageAccounting(
    val cameraCallbacksReceived: Long,
    val yuvFramesAcquired: Long,
    val yuvFramesReleased: Long,
    val preprocessingStarted: Long,
    val preprocessingCompleted: Long,
    val cadenceAdmitted: Long,
    val cadenceRejected: Long,
    val inferenceSubmitted: Long,
    val inferenceCompleted: Long,
    val inferenceFailed: Long,
    val poseRecordsAppended: Long,
    val poseRecordsDurable: Long,
    val sourceFailures: Long,
    val processFailures: Long,
)

/**
 * Derives the sustained stage equations from bounded live-pipeline counters and the independently
 * reconciled pose artifact. A null reconciliation means no pose stream reached durable publication;
 * it must never be replaced with the runner's appended count.
 */
fun deriveM05BSustainedStageAccounting(
    pipelineCounts: Map<Pair<PipelineStage, PipelineDisposition>, Long>,
    finalCounts: Counts,
    reconciledPoseRecordCount: Long?,
    sourceFailures: Long,
    processFailures: Long,
): M05BSustainedStageAccounting {
    fun count(stage: PipelineStage, disposition: PipelineDisposition): Long =
        pipelineCounts[stage to disposition] ?: 0L

    val cadenceRejected = listOf(
        PipelineDisposition.CADENCE_SELECTION,
        PipelineDisposition.BACKPRESSURE,
        PipelineDisposition.SESSION_STOPPING,
    ).sumOf { count(PipelineStage.CADENCE, it) }
    return M05BSustainedStageAccounting(
        cameraCallbacksReceived = count(PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED),
        yuvFramesAcquired = count(PipelineStage.YUV_OWNERSHIP, PipelineDisposition.ACQUIRED),
        yuvFramesReleased = count(PipelineStage.YUV_OWNERSHIP, PipelineDisposition.RELEASED),
        preprocessingStarted = count(PipelineStage.PREPROCESSING, PipelineDisposition.STARTED),
        preprocessingCompleted = count(PipelineStage.PREPROCESSING, PipelineDisposition.COMPLETED),
        cadenceAdmitted = count(PipelineStage.INFERENCE, PipelineDisposition.SUBMITTED),
        cadenceRejected = cadenceRejected,
        inferenceSubmitted = count(PipelineStage.INFERENCE, PipelineDisposition.SUBMITTED),
        inferenceCompleted = count(PipelineStage.INFERENCE, PipelineDisposition.COMPLETED),
        inferenceFailed = count(PipelineStage.INFERENCE, PipelineDisposition.FAILED),
        poseRecordsAppended = finalCounts.inferenceRecordsEmitted,
        poseRecordsDurable = reconciledPoseRecordCount ?: 0L,
        sourceFailures = sourceFailures,
        processFailures = processFailures,
    )
}

data class M05BSustainedLatencySummary(val bucketCounts: List<Long>) {
    init {
        require(bucketCounts.size == M05BSustainedProtocol.latencyUpperBoundsNs.size)
        require(bucketCounts.all { it >= 0L })
    }

    val count: Long get() = bucketCounts.sum()

    fun percentileBounds(percentile: Int): LatencyBucketBounds {
        require(percentile in setOf(50, 95, 99))
        if (count == 0L) return LatencyBucketBounds(0L, null)
        val rank = (count * percentile + 99L) / 100L
        var cumulative = 0L
        var lower = 0L
        M05BSustainedProtocol.latencyUpperBoundsNs.forEachIndexed { index, upper ->
            cumulative += bucketCounts[index]
            if (cumulative >= rank) return LatencyBucketBounds(lower, upper)
            if (upper != null) lower = upper + 1L
        }
        error("histogram rank was not contained")
    }
}

data class M05BSustainedTelemetrySummary(
    val fixedSampleCount: Long,
    val firstTSessionNs: Long,
    val lastTSessionNs: Long,
    val maxInterSampleGapNs: Long,
    val terminalSample: Boolean,
)

data class M05BSustainedBatterySummary(
    val startTemperatureC: Double?,
    val endTemperatureC: Double?,
    val deltaTemperatureC: Double?,
)

/** Post-completion, constant-memory evidence owned by the live run rather than the device gate. */
data class M05BSustainedRuntimeEvidence(
    val actualCaptureSpanNs: Long,
    val stages: M05BSustainedStageAccounting,
    val latency: M05BSustainedLatencySummary,
    val telemetry: M05BSustainedTelemetrySummary,
    val battery: M05BSustainedBatterySummary,
    val sourceOutcome: M05BSustainedSourceOutcome,
    val processOutcome: M05BSustainedProcessOutcome,
)

enum class M05BSustainedSourceOutcome(val wireValue: String) {
    COMPLETED("completed"), FAILED("failed"), LOST("lost")
}

enum class M05BSustainedProcessOutcome(val wireValue: String) {
    COMPLETED("completed"), ABORTED("aborted"), FAILED("failed"), TRANSPORT_LOST("transport_lost")
}

data class M05BSustainedObservation(
    val sessionId: String,
    val attemptSequence: Int,
    val cadenceFps: Int,
    val videoEnabled: Boolean,
    val cameraFps: Int,
    val durationTargetNs: Long,
    val stopRequestedNs: Long,
    val actualCaptureSpanNs: Long,
    val boundaryEvents: List<String>,
    val envelope: M05BSustainedArtifactRef,
    val order: M05BSustainedArtifactRef,
    val admission: M05BSustainedArtifactRef,
    val attemptChain: List<M05BBoundAttemptEntry>,
    val stages: M05BSustainedStageAccounting,
    val latency: M05BSustainedLatencySummary,
    val telemetry: M05BSustainedTelemetrySummary,
    val battery: M05BSustainedBatterySummary,
    val sourceOutcome: M05BSustainedSourceOutcome,
    val processOutcome: M05BSustainedProcessOutcome,
) {
    companion object {
        val REQUIRED_BOUNDARY_EVENTS = listOf(
            "attempt_open",
            "admission_append",
            "admission_flush",
            "admission_fsync",
            "capture_start",
        )
    }
}

data class M05BSustainedValidation(val blockingCodes: List<String>) {
    val admitted: Boolean get() = blockingCodes.isEmpty()
}

fun validateM05BSustainedObservation(value: M05BSustainedObservation): M05BSustainedValidation {
    val blocking = buildList {
        if (value.durationTargetNs != M05BSustainedProtocol.TARGET_DURATION_NS) add("DURATION.TARGET")
        if (value.stopRequestedNs != M05BSustainedProtocol.TARGET_DURATION_NS) add("DURATION.STOP_REQUEST")
        if (value.actualCaptureSpanNs > M05BSustainedProtocol.MAX_STOP_REQUEST_NS) add("DURATION.OVERSHOOT")
        if (value.videoEnabled) add("PROTOCOL.VIDEO_ENABLED")
        if (value.cameraFps != 30) add("PROTOCOL.CAMERA_FPS")
        if (value.boundaryEvents != M05BSustainedObservation.REQUIRED_BOUNDARY_EVENTS) {
            add("BOUNDARY.ADMISSION_NOT_DURABLE_BEFORE_CAPTURE")
        }
        addAll(attemptBlockingCodes(value))
        val stages = value.stages
        if (stages.cameraCallbacksReceived != stages.yuvFramesAcquired ||
            stages.yuvFramesAcquired != stages.yuvFramesReleased
        ) add("STAGE.YUV")
        if (stages.yuvFramesAcquired != stages.preprocessingStarted ||
            stages.preprocessingStarted != stages.preprocessingCompleted
        ) add("STAGE.PREPROCESS")
        if (stages.preprocessingCompleted != stages.cadenceAdmitted + stages.cadenceRejected) {
            add("STAGE.CADENCE")
        }
        if (stages.cadenceAdmitted != stages.inferenceSubmitted ||
            stages.inferenceSubmitted != stages.inferenceCompleted + stages.inferenceFailed
        ) add("STAGE.INFERENCE")
        if (stages.inferenceCompleted != stages.poseRecordsAppended ||
            stages.poseRecordsAppended != stages.poseRecordsDurable
        ) add("STAGE.POSE")
        if (value.latency.count == 0L) add("LATENCY.EMPTY")
        if (value.latency.count != stages.inferenceCompleted) add("LATENCY.COUNT")
        val telemetry = value.telemetry
        val expectedSamples = value.actualCaptureSpanNs / 1_000_000_000L + 2L
        if (abs(telemetry.fixedSampleCount - expectedSamples) > 1L) add("TELEMETRY.COUNT")
        if (telemetry.firstTSessionNs != 0L) add("TELEMETRY.START_COVERAGE")
        if (telemetry.maxInterSampleGapNs > 2_000_000_000L) add("TELEMETRY.MAX_GAP")
        if (!telemetry.terminalSample || telemetry.lastTSessionNs < value.actualCaptureSpanNs) {
            add("TELEMETRY.END_COVERAGE")
        }
        val battery = value.battery
        if (battery.startTemperatureC?.isFinite() != true) add("BATTERY.START_TEMPERATURE_UNAVAILABLE")
        if (battery.endTemperatureC?.isFinite() != true) add("BATTERY.END_TEMPERATURE_UNAVAILABLE")
        if (battery.deltaTemperatureC?.isFinite() != true) add("BATTERY.DELTA_TEMPERATURE_UNAVAILABLE")
        if (battery.startTemperatureC?.isFinite() == true && battery.endTemperatureC?.isFinite() == true &&
            battery.deltaTemperatureC?.isFinite() == true &&
            abs(battery.deltaTemperatureC - (battery.endTemperatureC - battery.startTemperatureC)) > 1e-9
        ) add("BATTERY.DELTA")
        if (value.processOutcome == M05BSustainedProcessOutcome.TRANSPORT_LOST) add("RUN.TRANSPORT_LOST")
        if (value.sourceOutcome != M05BSustainedSourceOutcome.COMPLETED) add("SOURCE.OUTCOME")
    }.distinct().sorted()
    return M05BSustainedValidation(blocking)
}

private fun attemptBlockingCodes(value: M05BSustainedObservation): List<String> = buildList {
    var previous: String? = null
    var expectedCadence = 10
    value.attemptChain.forEachIndexed { index, bound ->
        if (M05BArtifactCodec.sha256(bound.exactBytes) != bound.sha256 ||
            !M05BAttemptChain.bind(bound.typed).exactBytes.contentEquals(bound.exactBytes)
        ) add("ATTEMPT.ENTRY_DIGEST")
        if (bound.typed.attemptSequence != index + 1 || bound.typed.previousEntrySha256 != previous) {
            add("ATTEMPT.CHAIN_ORDER")
        }
        if (bound.typed.cadenceFps != expectedCadence) add("ATTEMPT.CADENCE")
        when (bound.typed.outcome) {
            M05BAttemptOutcome.COMPLETED -> expectedCadence = when (expectedCadence) {
                10 -> 15
                15 -> 30
                else -> 0
            }
            M05BAttemptOutcome.ABORTED, M05BAttemptOutcome.FAILED -> Unit
            M05BAttemptOutcome.BLOCKED, M05BAttemptOutcome.TRANSPORT_LOST -> add("ATTEMPT.RECOVERY_REQUIRED")
        }
        previous = bound.sha256
    }
    val final = value.attemptChain.lastOrNull()
    if (final == null || final.typed.attemptSequence != value.attemptSequence ||
        final.typed.cadenceFps != value.cadenceFps
    ) add("ATTEMPT.RECORD_MISMATCH")
    if (final?.typed?.admissionSha256 != value.admission.sha256) add("ATTEMPT.ADMISSION_DIGEST")
}

fun renderM05BSustainedObservation(value: M05BSustainedObservation): String {
    val validation = validateM05BSustainedObservation(value)
    val record: JsonObject = buildJsonObject {
        put("sustained_run_schema_version", "1.0.0")
        put("record_kind", "m05b_sustained_run")
        put("session_id", value.sessionId)
        put("session_kind", "live_conformance")
        put("attempt_sequence", value.attemptSequence)
        put("cadence_fps", value.cadenceFps)
        put("video_enabled", value.videoEnabled)
        put("camera_fps", value.cameraFps)
        put("duration_target_ns", value.durationTargetNs)
        put("stop_requested_ns", value.stopRequestedNs)
        put("actual_capture_span_ns", value.actualCaptureSpanNs)
        putJsonArray("boundary_events") { value.boundaryEvents.forEach(::add) }
        putJsonObject("artifact_refs") {
            putArtifactRef("envelope", value.envelope)
            putArtifactRef("order", value.order)
            putArtifactRef("admission", value.admission)
        }
        putJsonArray("attempt_chain") {
            value.attemptChain.forEach { bound ->
                add(buildJsonObject {
                    put("entry", Json.parseToJsonElement(bound.exactBytes.decodeToString()))
                    put("entry_sha256", bound.sha256)
                })
            }
        }
        putStages(value.stages)
        putJsonObject("latency") {
            putJsonArray("bucket_upper_bounds_ns") {
                M05BSustainedProtocol.latencyUpperBoundsNs.forEach { upper ->
                    if (upper == null) add(JsonNull) else add(upper)
                }
            }
            putJsonArray("bucket_counts") { value.latency.bucketCounts.forEach(::add) }
            putJsonObject("percentiles") {
                listOf(50, 95, 99).forEach { percentile ->
                    val bounds = value.latency.percentileBounds(percentile)
                    putJsonObject(percentile.toString()) {
                        put("lower_inclusive_ns", bounds.lowerInclusiveNs)
                        if (bounds.upperInclusiveNs == null) put("upper_inclusive_ns", JsonNull)
                        else put("upper_inclusive_ns", bounds.upperInclusiveNs)
                    }
                }
            }
        }
        putJsonObject("telemetry") {
            put("fixed_sample_count", value.telemetry.fixedSampleCount)
            put("first_t_session_ns", value.telemetry.firstTSessionNs)
            put("last_t_session_ns", value.telemetry.lastTSessionNs)
            put("max_inter_sample_gap_ns", value.telemetry.maxInterSampleGapNs)
            put("terminal_sample", value.telemetry.terminalSample)
        }
        putJsonObject("battery") {
            putNullableDouble("start_temperature_c", value.battery.startTemperatureC)
            putNullableDouble("end_temperature_c", value.battery.endTemperatureC)
            putNullableDouble("delta_temperature_c", value.battery.deltaTemperatureC)
        }
        put("source_outcome", value.sourceOutcome.wireValue)
        put("process_outcome", value.processOutcome.wireValue)
        putJsonArray("blocking_codes") { validation.blockingCodes.forEach(::add) }
        put("non_participant_observation", true)
        put("sanitized", true)
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putArtifactRef(
    key: String,
    value: M05BSustainedArtifactRef,
) = putJsonObject(key) {
    put("path", value.path)
    put("sha256", value.sha256)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putStages(value: M05BSustainedStageAccounting) =
    putJsonObject("stage_accounting") {
        put("camera_callbacks_received", value.cameraCallbacksReceived)
        put("yuv_frames_acquired", value.yuvFramesAcquired)
        put("yuv_frames_released", value.yuvFramesReleased)
        put("preprocessing_started", value.preprocessingStarted)
        put("preprocessing_completed", value.preprocessingCompleted)
        put("cadence_admitted", value.cadenceAdmitted)
        put("cadence_rejected", value.cadenceRejected)
        put("inference_submitted", value.inferenceSubmitted)
        put("inference_completed", value.inferenceCompleted)
        put("inference_failed", value.inferenceFailed)
        put("pose_records_appended", value.poseRecordsAppended)
        put("pose_records_durable", value.poseRecordsDurable)
        put("source_failures", value.sourceFailures)
        put("process_failures", value.processFailures)
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableDouble(key: String, value: Double?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}
