package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.live.LiveAdmissionPredicates
import com.calisthenics.diagnostic.live.LiveFrameAdmission
import com.calisthenics.diagnostic.live.LiveFrameClassification
import com.calisthenics.diagnostic.study.M05BAttributionV2Protocol
import com.calisthenics.diagnostic.study.AttributionV2CallbackPhase
import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition

interface AttributionV2CallbackObserver {
    fun onAdmission(
        callbackId: Long,
        captureTimestampNs: Long,
        observedSessionNs: Long,
        productionClassification: LiveFrameClassification,
        productionPredicates: LiveAdmissionPredicates,
    )

    fun onTerminal(callbackId: Long, terminal: CallbackTerminalDisposition, completedNs: Long)
}

data class AttributionV2ObservedAdmission(
    val callbackId: Long,
    val captureTimestampNs: Long,
    val observedSessionNs: Long,
    val productionClassification: LiveFrameClassification,
    val productionPredicates: LiveAdmissionPredicates,
    val shadowSelections: Map<Int, Boolean>,
)

data class AttributionV2ObservedTerminal(
    val callbackId: Long,
    val terminal: CallbackTerminalDisposition,
    val completedNs: Long,
    val phase: AttributionV2CallbackPhase? = null,
)

/** Observation-only projection; it owns its shadow selectors and never receives production state. */
class AttributionV2Observation(
    private val phaseAtTerminal: ((CallbackTerminalDisposition, Long) -> AttributionV2CallbackPhase?)? = null,
) : AttributionV2CallbackObserver {
    private val shadowAdmissions = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith(::LiveFrameAdmission)
    private val admissions = mutableListOf<AttributionV2ObservedAdmission>()
    private val terminals = mutableListOf<AttributionV2ObservedTerminal>()

    @Synchronized
    override fun onAdmission(
        callbackId: Long,
        captureTimestampNs: Long,
        observedSessionNs: Long,
        productionClassification: LiveFrameClassification,
        productionPredicates: LiveAdmissionPredicates,
    ) {
        val selections = shadowAdmissions.mapValues { (_, admission) ->
            val due = admission.predicates(captureTimestampNs).cadenceDue
            if (admission.classify(captureTimestampNs) == LiveFrameClassification.SUBMIT) {
                admission.completeSubmission()
            }
            due
        }
        admissions += AttributionV2ObservedAdmission(
            callbackId,
            captureTimestampNs,
            observedSessionNs,
            productionClassification,
            productionPredicates,
            selections,
        )
    }

    @Synchronized
    override fun onTerminal(callbackId: Long, terminal: CallbackTerminalDisposition, completedNs: Long) {
        terminals += AttributionV2ObservedTerminal(
            callbackId = callbackId,
            terminal = terminal,
            completedNs = completedNs,
            phase = phaseAtTerminal?.invoke(terminal, completedNs),
        )
    }

    @Synchronized
    fun admissionSnapshot(): List<AttributionV2ObservedAdmission> = admissions.toList()

    @Synchronized
    fun terminalSnapshot(): List<AttributionV2ObservedTerminal> = terminals.toList()
}
