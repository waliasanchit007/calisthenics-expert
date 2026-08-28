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
import kotlinx.serialization.json.putJsonObject

private val REQUIRED_CADENCE_ORDER = listOf(10, 15, 30)
private val SHA256_PATTERN = Regex("\\A[0-9a-f]{64}\\z")
private val SESSION_ID_PATTERN = Regex("\\A00000000-0000-4000-8000-[0-9a-f]{12}\\z")

data class AttributionEvidenceSource(
    val deviceClass: String,
    val deviceIdentity: String,
    val manufacturer: String,
    val model: String,
    val apiLevel: Int,
    val osBuild: String,
    val marketingName: String,
)

data class AttributionRunIdentity(
    val appGitSha: String,
    val probeGitSha: String,
    val apkSha256: String,
    val modelSha256: String,
    val runtimeVersion: String,
    val backendVersion: String,
    val delegate: String,
)

/** Full events are allowed here only because this models a bounded short attribution run. */
data class ShortCadenceAttributionRun(
    val requestedCameraFps: Int,
    val configuredInferenceFps: Int,
    val events: List<PipelineStageEvent>,
    val callbackAccounting: DeliveredCallbackAccounting,
    val sessionId: String,
    val exportRelativePath: String,
    val manifestSha256: String,
    val framesSha256: String,
    val telemetrySha256: String,
    val identity: AttributionRunIdentity,
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
        if (evidence.source.deviceIdentity.isBlank() || evidence.source.apiLevel <= 0 || evidence.source.osBuild.isBlank()) {
            add("TELEMETRY.DEVICE_IDENTITY_INCOMPLETE")
        }
        if (evidence.runs.size != REQUIRED_CADENCE_ORDER.size ||
            evidence.runs.map { it.configuredInferenceFps } != REQUIRED_CADENCE_ORDER
        ) {
            add("TELEMETRY.CADENCE_SEQUENCE_INVALID")
        }
        if (evidence.runs.any { it.requestedCameraFps != 30 }) {
            add("TELEMETRY.CAMERA_REQUEST_NOT_30_FPS")
        }
        evidence.runs.forEach { run -> validateRunBindings(run, this) }
        if (evidence.runs.map { it.sessionId }.distinct().size != evidence.runs.size) {
            add("TELEMETRY.SESSION_ID_NOT_DISTINCT")
        }
        if (evidence.runs.map { it.identity }.distinct().size > 1) {
            add("TELEMETRY.RUN_IDENTITY_MISMATCH")
        }
        evidence.runs.forEach { run -> validateRun(run, this) }

        val admitted = evidence.runs.map { run ->
            safeAdd(
                run.callbackAccounting.counts[CallbackTerminalDisposition.INFERENCE_COMPLETED] ?: 0L,
                run.callbackAccounting.counts[CallbackTerminalDisposition.INFERENCE_FAILED] ?: 0L,
            )
        }
        val delivered = evidence.runs.map { it.callbackAccounting.deliveredCallbacks }
        if (admitted.size != REQUIRED_CADENCE_ORDER.size ||
            delivered.size != REQUIRED_CADENCE_ORDER.size ||
            delivered.any { it <= 0L } ||
            admitted.any { it == null || it < 0L } ||
            evidence.runs.map { it.configuredInferenceFps } != REQUIRED_CADENCE_ORDER
        ) {
            add("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT")
        } else if (!fractionLessThan(admitted[0]!!, delivered[0], admitted[1]!!, delivered[1]) ||
            !fractionLessThan(admitted[1]!!, delivered[1], admitted[2]!!, delivered[2])
        ) {
            add("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT")
        }
    }.distinct().sorted()
    return AttributionEvidenceValidation(
        if (blockingCodes.isEmpty()) AttributionEvidenceStatus.RECORDED else AttributionEvidenceStatus.BLOCKED,
        blockingCodes,
    )
}

/** Computes the verdict from the observation. There is no renderer overload accepting a verdict. */
fun renderAttributionEvidence(evidence: PhysicalAttributionEvidence?): String {
    val validation = validateAttributionEvidence(evidence)
    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", "1.0.0")
        put("record_kind", "s25_short_cadence_attribution_validation")
        put("physical_evidence_status", validation.status.wireValue)
        put("satisfies_s25_attribution_gate", validation.satisfiesGate)
        putJsonArray("blocking_codes") { validation.blockingCodes.forEach(::add) }
        evidence?.let { observed ->
            putJsonObject("device") {
                put("device_class", observed.source.deviceClass)
                put("device_identity", observed.source.deviceIdentity)
                put("manufacturer", observed.source.manufacturer)
                put("model", observed.source.model)
                put("api_level", observed.source.apiLevel)
                put("os_build", observed.source.osBuild)
                put("marketing_name", observed.source.marketingName)
            }
            putJsonArray("runs") {
                observed.runs.forEach { run ->
                    add(buildJsonObject {
                        put("session_id", run.sessionId)
                        put("export_relative_path", run.exportRelativePath)
                        put("requested_camera_fps", run.requestedCameraFps)
                        put("configured_inference_fps", run.configuredInferenceFps)
                        put("manifest_sha256", run.manifestSha256)
                        put("frames_sha256", run.framesSha256)
                        put("telemetry_sha256", run.telemetrySha256)
                        run.identity.let { identity ->
                            putJsonObject("identity") {
                                put("app_git_sha", identity.appGitSha)
                                put("probe_git_sha", identity.probeGitSha)
                                put("apk_sha256", identity.apkSha256)
                                put("model_sha256", identity.modelSha256)
                                put("runtime_version", identity.runtimeVersion)
                                put("backend_version", identity.backendVersion)
                                put("delegate", identity.delegate)
                            }
                        }
                        put("delivered_callbacks", run.callbackAccounting.deliveredCallbacks)
                        put(
                            "admitted_callbacks",
                            safeAdd(
                                run.callbackAccounting.counts[CallbackTerminalDisposition.INFERENCE_COMPLETED] ?: 0L,
                                run.callbackAccounting.counts[CallbackTerminalDisposition.INFERENCE_FAILED] ?: 0L,
                            ) ?: 0L,
                        )
                    })
                }
            }
        }
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}

private fun validateRunBindings(run: ShortCadenceAttributionRun, blockingCodes: MutableList<String>) {
    if (!SESSION_ID_PATTERN.matches(run.sessionId) || run.exportRelativePath.isUnsafeRelativePath() ||
        run.sessionId !in run.exportRelativePath.split('/')
    ) {
        blockingCodes += "TELEMETRY.SESSION_BINDING_INVALID"
    }
    if (!run.manifestSha256.isSha256() || !run.framesSha256.isSha256() || !run.telemetrySha256.isSha256()) {
        blockingCodes += "TELEMETRY.ARTIFACT_DIGEST_INVALID"
    }
    val identity = run.identity
    if (!identity.appGitSha.isGitSha() || !identity.probeGitSha.isGitSha() ||
        !identity.apkSha256.isSha256() ||
        !identity.modelSha256.isSha256() || identity.runtimeVersion.isBlank() ||
        identity.backendVersion.isBlank() || identity.delegate.isBlank()
    ) {
        blockingCodes += "TELEMETRY.RUN_IDENTITY_INVALID"
    }
}

private fun validateRun(run: ShortCadenceAttributionRun, blockingCodes: MutableList<String>) {
    val accounting = run.callbackAccounting
    val total = safeNonNegativeSum(accounting.counts.values)
    if (accounting.deliveredCallbacks < 0L || accounting.unclosedCallbacks < 0L ||
        accounting.counts.values.any { it < 0L } || total == null ||
        accounting.deliveredCallbacks <= 0L || accounting.unclosedCallbacks != 0L ||
        total != accounting.deliveredCallbacks
    ) {
        blockingCodes += "TELEMETRY.CALLBACK_ACCOUNTING_INVALID"
    }
    if (run.events.zipWithNext().any { (first, second) -> second.sequence <= first.sequence }) {
        blockingCodes += "TELEMETRY.STAGE_SEQUENCE_INVALID"
    }
    if (run.events.zipWithNext().any { (first, second) -> second.tSessionNs < first.tSessionNs }) {
        blockingCodes += "TELEMETRY.STAGE_TIME_INVALID"
    }
    val receivedFrames = run.events.filter {
        it.stage == PipelineStage.CAMERA_CALLBACK && it.disposition.name == "RECEIVED"
    }.map { it.frameIndex }
    val terminalEvents = run.events.filter { event -> terminalFor(event) != null }
    val terminalFrames = terminalEvents.map { it.frameIndex }
    if (receivedFrames.size != receivedFrames.distinct().size ||
        terminalFrames.size != terminalFrames.distinct().size || receivedFrames != terminalFrames
    ) {
        blockingCodes += "TELEMETRY.TERMINAL_ACCOUNTING_INVALID"
    }
    val derivedCounts = terminalEvents.groupingBy { event -> terminalFor(event)!! }.eachCount()
        .mapValues { it.value.toLong() }
    val suppliedCounts = accounting.counts.filterValues { it != 0L }
    if (receivedFrames.size.toLong() != accounting.deliveredCallbacks || suppliedCounts != derivedCounts) {
        blockingCodes += "TELEMETRY.ACCOUNTING_NOT_DERIVED"
    }
    val submissions = run.events.count {
        it.stage == PipelineStage.INFERENCE && it.disposition.name == "SUBMITTED"
    }.toLong()
    val terminalInferences = safeAdd(
        derivedCounts[CallbackTerminalDisposition.INFERENCE_COMPLETED] ?: 0L,
        derivedCounts[CallbackTerminalDisposition.INFERENCE_FAILED] ?: 0L,
    )
    if (terminalInferences == null || submissions != terminalInferences) {
        blockingCodes += "TELEMETRY.INFERENCE_ADMISSION_MISMATCH"
    }
}

private fun terminalFor(event: PipelineStageEvent): CallbackTerminalDisposition? =
    CallbackTerminalDisposition.values().firstOrNull { terminal ->
        terminal.pipelineTerminalEvent().stage == event.stage &&
            terminal.pipelineTerminalEvent().disposition == event.disposition
    }

private fun String.isGitSha(): Boolean = Regex("\\A[0-9a-f]{40}\\z").matches(this)

private fun String.isSha256(): Boolean = SHA256_PATTERN.matches(this)

private fun String.isUnsafeRelativePath(): Boolean =
    isEmpty() || startsWith('/') || contains('\\') || split('/').any { it.isEmpty() || it == "." || it == ".." }

private fun safeNonNegativeSum(values: Collection<Long>): Long? {
    var total = 0L
    values.forEach { value ->
        if (value < 0L || total > Long.MAX_VALUE - value) return null
        total += value
    }
    return total
}

private fun safeAdd(left: Long, right: Long): Long? {
    if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) return null
    return left + right
}

/** Exact comparison without overflowing Long multiplication, including Long.MAX_VALUE inputs. */
private fun fractionLessThan(leftNumerator: Long, leftDenominator: Long, rightNumerator: Long, rightDenominator: Long): Boolean {
    if (leftNumerator < 0L || rightNumerator < 0L || leftDenominator <= 0L || rightDenominator <= 0L) return false
    var a = leftNumerator
    var b = leftDenominator
    var c = rightNumerator
    var d = rightDenominator
    var inverted = false
    while (true) {
        val leftWhole = a / b
        val rightWhole = c / d
        if (leftWhole != rightWhole) return if (inverted) leftWhole > rightWhole else leftWhole < rightWhole
        val leftRemainder = a % b
        val rightRemainder = c % d
        if (leftRemainder == 0L || rightRemainder == 0L) {
            if (leftRemainder == 0L && rightRemainder == 0L) return false
            val result = leftRemainder == 0L && rightRemainder != 0L
            return if (inverted) !result else result
        }
        a = b
        b = leftRemainder
        c = d
        d = rightRemainder
        inverted = !inverted
    }
}
