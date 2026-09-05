package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.live.LiveFrameAdmission
import com.calisthenics.diagnostic.live.LiveFrameClassification

enum class AttributionV2TerminalClass {
    ADMITTED_COMPLETED,
    ADMITTED_INFERENCE_FAILED,
    CADENCE_ONLY,
    BACKPRESSURE_ONLY,
    CADENCE_AND_BACKPRESSURE,
    PREPROCESSING_FAILED,
    SESSION_STOPPING,
    ADMISSION_STATE_DIVERGENCE,
}

private val CONSERVATION_TERMINALS = AttributionV2TerminalClass.entries.toSet()

data class AttributionV2Callback(
    val callbackId: Long,
    val captureTimestampNs: Long,
    val productionClassification: LiveFrameClassification,
    val cadenceDue: Boolean,
    val slotAvailable: Boolean,
    val terminalClass: AttributionV2TerminalClass,
    val phase: AttributionV2CallbackPhase? = null,
    val completionTimestampNs: Long? = null,
)

data class AttributionV2SourceArm(val measuredTimestampsNs: List<Long>)

data class AttributionV2BackendArm(
    val measuredServiceDurationsNs: List<Long>,
    val inferenceFailures: Int,
)

data class AttributionV2SelectorArm(
    val productionSelections: Map<Int, List<Long>>,
    val hostSelections: Map<Int, List<Long>>,
)

data class AttributionV2CallbackArm(
    val deliveredCallbackIds: List<Long>,
    val terminalCallbacks: List<AttributionV2Callback>,
    val submittedInferenceIds: List<Long>,
    val pendingInferenceIds: Set<Long>,
)

data class AttributionV2Replicate(
    val cadenceOrder: List<Int>,
    val admittedCompletionRates: Map<Int, Long>,
    val cadenceOpportunityRates: Map<Int, Long>,
    val sourceSupported: Map<Int, Boolean>,
    val backendSupported: Map<Int, Boolean>,
)

data class M05BAttributionV2Evidence(
    val protocolVersion: String,
    val identityMatches: Boolean,
    val environmentMatches: Boolean,
    val sourceArm: AttributionV2SourceArm?,
    val backendArm: AttributionV2BackendArm?,
    val selectorArm: AttributionV2SelectorArm?,
    val callbackArm: AttributionV2CallbackArm?,
    val replicates: List<AttributionV2Replicate>,
    val deviceBlockingCodes: List<String>,
)

enum class AttributionV2Status { PENDING, RECORDED, BLOCKED }

data class M05BAttributionV2Validation(
    val status: AttributionV2Status,
    val blockingCodes: List<String>,
) {
    val satisfiesGate: Boolean get() = status == AttributionV2Status.RECORDED && blockingCodes.isEmpty()
}

fun closedIntervalOpportunities(spanNs: Long, fps: Int): Long {
    require(spanNs >= 0L) { "spanNs must be non-negative" }
    require(fps > 0) { "fps must be positive" }
    return (spanNs / 1_000_000_000L) * fps +
        ((spanNs % 1_000_000_000L) * fps) / 1_000_000_000L + 1L
}

fun sourceSupports(timestampsNs: List<Long>, cadenceFps: Int): Boolean {
    if (timestampsNs.size != M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS ||
        timestampsNs.zipWithNext().any { (first, second) -> second <= first }
    ) return false
    val span = timestampsNs.last() - timestampsNs.first()
    return timestampsNs.size.toLong() >= closedIntervalOpportunities(span, cadenceFps)
}

fun backendSupports(serviceDurationsNs: List<Long>, cadenceFps: Int): Boolean {
    if (serviceDurationsNs.size != M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS ||
        serviceDurationsNs.any { it < 0L }
    ) return false
    val interval = 1_000_000_000L / cadenceFps
    return serviceDurationsNs.all { it <= interval }
}

fun productionSelectorTimestamps(timestampsNs: List<Long>, cadenceFps: Int): List<Long> {
    val admission = LiveFrameAdmission(cadenceFps)
    return buildList {
        timestampsNs.forEach { timestamp ->
            if (admission.classify(timestamp) == LiveFrameClassification.SUBMIT) {
                add(timestamp)
                admission.completeSubmission()
            }
        }
    }
}

fun validateAttributionV2(evidence: M05BAttributionV2Evidence?): M05BAttributionV2Validation {
    if (evidence == null) return M05BAttributionV2Validation(AttributionV2Status.PENDING, emptyList())
    val blockers = buildList {
        if (evidence.protocolVersion != M05BAttributionV2Protocol.VERSION) add("ATTRIBUTION.PROTOCOL_DIGEST")
        if (!evidence.identityMatches) add("ATTRIBUTION.CANDIDATE_IDENTITY")
        if (!evidence.environmentMatches) add("ATTRIBUTION.ENVIRONMENT_MISMATCH")
        validateSource(evidence.sourceArm, this)
        validateBackend(evidence.backendArm, this)
        validateSelector(evidence.selectorArm, this)
        evidence.callbackArm?.let { addAll(attributionV2CallbackBlockers(it)) }
        if (evidence.selectorArm != null &&
            (evidence.sourceArm == null || evidence.backendArm == null || evidence.replicates.isEmpty())
        ) add("ATTRIBUTION.SELECTOR_ONLY")
        validateReplicates(evidence.replicates, this)
    }.distinct().sorted()
    return M05BAttributionV2Validation(
        if (blockers.isEmpty()) AttributionV2Status.RECORDED else AttributionV2Status.BLOCKED,
        blockers,
    )
}

private fun validateSource(arm: AttributionV2SourceArm?, blockers: MutableList<String>) {
    if (arm == null || arm.measuredTimestampsNs.size != M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS) {
        blockers += "ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT"
        return
    }
    if (arm.measuredTimestampsNs.zipWithNext().any { (first, second) -> second <= first }) {
        blockers += "ATTRIBUTION.SOURCE_TIMESTAMP_INVALID"
        return
    }
    if (M05BAttributionV2Protocol.TARGET_CADENCES_FPS.any { !sourceSupports(arm.measuredTimestampsNs, it) }) {
        blockers += "ATTRIBUTION.SOURCE_LIMITED"
    }
}

private fun validateBackend(arm: AttributionV2BackendArm?, blockers: MutableList<String>) {
    if (arm == null || arm.measuredServiceDurationsNs.size != M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS) {
        blockers += "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT"
        return
    }
    if (arm.inferenceFailures != 0) blockers += "ATTRIBUTION.INFERENCE_FAILURE"
    if (M05BAttributionV2Protocol.TARGET_CADENCES_FPS.any { !backendSupports(arm.measuredServiceDurationsNs, it) }) {
        blockers += "ATTRIBUTION.BACKEND_LIMITED"
    }
}

private fun validateSelector(arm: AttributionV2SelectorArm?, blockers: MutableList<String>) {
    if (arm == null) {
        blockers += "ATTRIBUTION.SELECTOR_DIVERGENCE"
        return
    }
    val cadences = M05BAttributionV2Protocol.TARGET_CADENCES_FPS
    if (arm.productionSelections != arm.hostSelections ||
        arm.productionSelections.keys != cadences.toSet() ||
        cadences.zipWithNext().any { (lower, upper) ->
            arm.productionSelections.getValue(lower).size >= arm.productionSelections.getValue(upper).size
        }
    ) blockers += "ATTRIBUTION.SELECTOR_DIVERGENCE"
}

fun attributionV2CallbackBlockers(arm: AttributionV2CallbackArm): List<String> = buildList {
    val delivered = arm.deliveredCallbackIds
    val terminalIds = arm.terminalCallbacks.filter {
        it.terminalClass in CONSERVATION_TERMINALS
    }.map { it.callbackId }
    val admittedIds = arm.terminalCallbacks.filter {
        it.terminalClass == AttributionV2TerminalClass.ADMITTED_COMPLETED ||
            it.terminalClass == AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED
    }.map { it.callbackId }
    if (delivered.size != delivered.distinct().size || terminalIds.size != terminalIds.distinct().size ||
        delivered.toSet() != terminalIds.toSet() || arm.submittedInferenceIds.toSet() != admittedIds.toSet() ||
        arm.pendingInferenceIds.isNotEmpty()
    ) add("ATTRIBUTION.TERMINAL_CLOSURE")
    if (arm.terminalCallbacks.size != delivered.size) add("ATTRIBUTION.CALLBACK_CONSERVATION")
    arm.terminalCallbacks.forEach { callback ->
        if (callback.terminalClass == AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE ||
            !terminalMatchesPredicates(callback)
        ) add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    }
}.distinct().sorted()

private fun terminalMatchesPredicates(callback: AttributionV2Callback): Boolean = when (callback.terminalClass) {
    AttributionV2TerminalClass.ADMITTED_COMPLETED,
    AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED,
    -> callback.productionClassification == LiveFrameClassification.SUBMIT
    AttributionV2TerminalClass.CADENCE_ONLY ->
        !callback.cadenceDue && callback.slotAvailable &&
            callback.productionClassification == LiveFrameClassification.CADENCE_SELECTION
    AttributionV2TerminalClass.BACKPRESSURE_ONLY ->
        callback.cadenceDue && !callback.slotAvailable &&
            callback.productionClassification == LiveFrameClassification.BACKPRESSURE
    AttributionV2TerminalClass.CADENCE_AND_BACKPRESSURE ->
        !callback.cadenceDue && !callback.slotAvailable &&
            callback.productionClassification == LiveFrameClassification.BACKPRESSURE
    AttributionV2TerminalClass.PREPROCESSING_FAILED ->
        callback.productionClassification == LiveFrameClassification.PREPROCESSING_FAILED
    AttributionV2TerminalClass.SESSION_STOPPING ->
        callback.productionClassification == LiveFrameClassification.SESSION_STOPPING
    AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE -> false
}

private fun validateReplicates(replicates: List<AttributionV2Replicate>, blockers: MutableList<String>) {
    if (replicates.size != 2 || replicates.map { it.cadenceOrder } != M05BAttributionV2Protocol.REPLICATE_ORDERS) {
        blockers += "ATTRIBUTION.REPLICATE_DISAGREEMENT"
        return
    }
    val cadences = M05BAttributionV2Protocol.TARGET_CADENCES_FPS
    replicates.forEach { replicate ->
        val supported = cadences.all {
            replicate.sourceSupported[it] == true && replicate.backendSupported[it] == true
        }
        val admittedDistinct = cadences.zipWithNext().all { (lower, upper) ->
            (replicate.admittedCompletionRates[lower] ?: Long.MAX_VALUE) <
                (replicate.admittedCompletionRates[upper] ?: Long.MIN_VALUE)
        }
        val opportunitiesDistinct = cadences.zipWithNext().all { (lower, upper) ->
            (replicate.cadenceOpportunityRates[lower] ?: Long.MAX_VALUE) <
                (replicate.cadenceOpportunityRates[upper] ?: Long.MIN_VALUE)
        }
        if (!supported || !admittedDistinct || !opportunitiesDistinct) {
            blockers += "ATTRIBUTION.END_TO_END_NOT_DISTINCT"
        }
    }
}
