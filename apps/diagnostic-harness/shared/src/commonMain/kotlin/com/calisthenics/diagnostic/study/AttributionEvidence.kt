package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.replay.isAuthorizedS25Target
import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition
import com.calisthenics.diagnostic.telemetry.DeliveredCallbackAccounting
import com.calisthenics.diagnostic.telemetry.PipelineStage
import com.calisthenics.diagnostic.telemetry.PipelineStageEvent
import com.calisthenics.diagnostic.telemetry.pipelineTerminalEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val REQUIRED_CADENCE_ORDER = listOf(10, 15, 30)
private val REQUIRED_CADENCE_FPS = REQUIRED_CADENCE_ORDER.toSet()

data class AttributionEvidenceSource(
    val deviceClass: String,
    val manufacturer: String,
    val model: String,
    val marketingName: String,
)

/** Full events are allowed here only because this models a bounded short attribution run. */
data class ShortCadenceAttributionRun(
    val requestedCameraFps: Int,
    val configuredInferenceFps: Int,
    val events: List<PipelineStageEvent>,
    val callbackAccounting: DeliveredCallbackAccounting,
)

data class PhysicalAttributionEvidence(
    val source: AttributionEvidenceSource,
    val runs: List<ShortCadenceAttributionRun>,
)

enum class AttributionEvidenceStatus(val wireValue: String) {
    PENDING("pending"),
    RECORDED("recorded"),
    BLOCKED("blocked"),
}

data class AttributionEvidenceValidation(
    val status: AttributionEvidenceStatus,
    val blockingCodes: List<String>,
) {
    val satisfiesGate: Boolean
        get() = status == AttributionEvidenceStatus.RECORDED && blockingCodes.isEmpty()
}

fun validateAttributionEvidence(evidence: PhysicalAttributionEvidence?): AttributionEvidenceValidation {
    if (evidence == null) {
        return AttributionEvidenceValidation(
            AttributionEvidenceStatus.PENDING,
            listOf("TELEMETRY.PHYSICAL_S25_PENDING"),
        )
    }
    val blockingCodes = buildList {
        if (!isAuthorizedS25Target(
                deviceClass = evidence.source.deviceClass,
                manufacturer = evidence.source.manufacturer,
                model = evidence.source.model,
                marketingName = evidence.source.marketingName,
            )
        ) {
            add("TELEMETRY.UNAUTHORIZED_S25_TARGET")
        }
        if (evidence.runs.map { it.configuredInferenceFps } != REQUIRED_CADENCE_ORDER) {
            add("TELEMETRY.CADENCE_SEQUENCE_INVALID")
        }
        evidence.runs.forEach { run ->
            if (run.requestedCameraFps != 30) add("TELEMETRY.CAMERA_REQUEST_NOT_30_FPS")
            validateRun(run, this)
        }
        val admitted = evidence.runs.associate { run ->
            run.configuredInferenceFps to ((run.callbackAccounting.counts[CallbackTerminalDisposition.INFERENCE_COMPLETED] ?: 0L) +
                (run.callbackAccounting.counts[CallbackTerminalDisposition.INFERENCE_FAILED] ?: 0L))
        }
        val delivered = evidence.runs.associate { run ->
            run.configuredInferenceFps to run.callbackAccounting.deliveredCallbacks
        }
        if (admitted.keys != REQUIRED_CADENCE_FPS || delivered.values.any { it <= 0L }) {
            add("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT")
        } else if (!ratioLessThan(
                admitted.getValue(10), delivered.getValue(10),
                admitted.getValue(15), delivered.getValue(15),
            ) || !ratioLessThan(
                admitted.getValue(15), delivered.getValue(15),
                admitted.getValue(30), delivered.getValue(30),
            )
        ) {
            add("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT")
        }
    }.distinct().sorted()
    return AttributionEvidenceValidation(
        if (blockingCodes.isEmpty()) AttributionEvidenceStatus.RECORDED else AttributionEvidenceStatus.BLOCKED,
        blockingCodes,
    )
}

fun renderAttributionEvidence(validation: AttributionEvidenceValidation): String {
    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", "1.0.0")
        put("record_kind", "s25_short_cadence_attribution_validation")
        put("physical_evidence_status", validation.status.wireValue)
        put("satisfies_s25_attribution_gate", validation.satisfiesGate)
        putJsonArray("blocking_codes") { validation.blockingCodes.forEach(::add) }
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}

private fun validateRun(run: ShortCadenceAttributionRun, blockingCodes: MutableList<String>) {
    val accounting = run.callbackAccounting
    if (accounting.deliveredCallbacks <= 0L ||
        accounting.unclosedCallbacks != 0L ||
        accounting.counts.values.sum() != accounting.deliveredCallbacks
    ) {
        blockingCodes += "TELEMETRY.CALLBACK_ACCOUNTING_INVALID"
    }
    if (run.events.zipWithNext().any { (first, second) -> second.sequence <= first.sequence }) {
        blockingCodes += "TELEMETRY.STAGE_SEQUENCE_INVALID"
    }
    if (run.events.zipWithNext().any { (first, second) -> second.tSessionNs < first.tSessionNs }) {
        blockingCodes += "TELEMETRY.STAGE_TIME_INVALID"
    }
    accounting.counts.forEach { (terminal, expected) ->
        val stageEvent = terminal.pipelineTerminalEvent()
        val actual = run.events.count { it.stage == stageEvent.stage && it.disposition == stageEvent.disposition }.toLong()
        if (actual != expected) blockingCodes += "TELEMETRY.STAGE_COUNT_MISMATCH"
    }
    val submissions = run.events.count { it.stage == PipelineStage.INFERENCE && it.disposition.name == "SUBMITTED" }.toLong()
    val terminalInferences = (accounting.counts[CallbackTerminalDisposition.INFERENCE_COMPLETED] ?: 0L) +
        (accounting.counts[CallbackTerminalDisposition.INFERENCE_FAILED] ?: 0L)
    if (submissions != terminalInferences) blockingCodes += "TELEMETRY.INFERENCE_ADMISSION_MISMATCH"
}

/** Compares non-negative ratios without floating-point rounding. */
private fun ratioLessThan(
    leftNumerator: Long,
    leftDenominator: Long,
    rightNumerator: Long,
    rightDenominator: Long,
): Boolean = leftNumerator * rightDenominator < rightNumerator * leftDenominator
