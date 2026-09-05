package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.live.LiveAdmissionPredicates
import com.calisthenics.diagnostic.live.LiveFrameAdmission
import com.calisthenics.diagnostic.live.LiveFrameClassification
import com.calisthenics.diagnostic.study.M05BAttributionV2Protocol
import com.calisthenics.diagnostic.study.AttributionV2CallbackPhase
import com.calisthenics.diagnostic.study.AttributionV2EnvironmentBoundary
import com.calisthenics.diagnostic.study.AttributionV2EnvironmentSnapshot
import com.calisthenics.diagnostic.study.AttributionV2FpsRange
import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition

interface AttributionV2CallbackObserver {
    /** Captures the source configuration belonging to this callback before admission is observed. */
    fun onFrameConfiguration(
        callbackId: Long,
        configuration: AttributionV2ObservedFrameConfiguration?,
    ) = Unit

    fun onAdmission(
        callbackId: Long,
        captureTimestampNs: Long,
        observedSessionNs: Long,
        productionClassification: LiveFrameClassification,
        productionPredicates: LiveAdmissionPredicates,
    )

    fun onTerminal(callbackId: Long, terminal: CallbackTerminalDisposition, completedNs: Long)
}

data class AttributionV2ObservedFrameConfiguration(
    val deliveredWidth: Int,
    val deliveredHeight: Int,
    val deliveredFormat: String,
    val negotiatedCameraFpsRange: AttributionV2FpsRange?,
)

data class AttributionV2ObservedFrameConfigurationBoundary(
    val start: AttributionV2ObservedFrameConfiguration?,
    val end: AttributionV2ObservedFrameConfiguration?,
    val missing: Boolean,
    val consistent: Boolean,
)

data class AttributionV2ObservedAdmission(
    val callbackId: Long,
    val captureTimestampNs: Long,
    val observedSessionNs: Long,
    val productionClassification: LiveFrameClassification,
    val productionPredicates: LiveAdmissionPredicates,
    val shadowSelections: Map<Int, Boolean>,
    val frameConfiguration: AttributionV2ObservedFrameConfiguration? = null,
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
    private val frameConfigurations = mutableMapOf<Long, AttributionV2ObservedFrameConfiguration?>()

    @Synchronized
    override fun onFrameConfiguration(
        callbackId: Long,
        configuration: AttributionV2ObservedFrameConfiguration?,
    ) {
        frameConfigurations[callbackId] = configuration
    }

    @Synchronized
    override fun onAdmission(
        callbackId: Long,
        captureTimestampNs: Long,
        observedSessionNs: Long,
        productionClassification: LiveFrameClassification,
        productionPredicates: LiveAdmissionPredicates,
    ) {
        val frameConfiguration = frameConfigurations.remove(callbackId)
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
            frameConfiguration,
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

    @Synchronized
    fun observedFrameConfigurationBoundary(): AttributionV2ObservedFrameConfigurationBoundary {
        val configurations = admissions.map { it.frameConfiguration }
        val observed = configurations.filterNotNull()
        return AttributionV2ObservedFrameConfigurationBoundary(
            start = observed.firstOrNull(),
            end = observed.lastOrNull(),
            missing = configurations.isEmpty() || configurations.any { it == null },
            consistent = configurations.isNotEmpty() &&
                configurations.none { it == null } &&
                observed.distinct().size == 1,
        )
    }
}

fun attributionV2EnvironmentBoundaryFromObservedConfiguration(
    armStartedNs: Long,
    armEndedNs: Long,
    start: AttributionV2EnvironmentSnapshot,
    end: AttributionV2EnvironmentSnapshot,
    observed: AttributionV2ObservedFrameConfigurationBoundary,
): AttributionV2EnvironmentBoundary? {
    if (observed.missing || !observed.consistent) return null
    val observedStart = observed.start ?: return null
    val observedEnd = observed.end ?: return null
    if (observedStart.negotiatedCameraFpsRange == null ||
        observedEnd.negotiatedCameraFpsRange == null
    ) return null
    return AttributionV2EnvironmentBoundary(
        armStartedNs = armStartedNs,
        armEndedNs = armEndedNs,
        start = start.withObservedConfiguration(observedStart),
        end = end.withObservedConfiguration(observedEnd),
    )
}

private fun AttributionV2EnvironmentSnapshot.withObservedConfiguration(
    observed: AttributionV2ObservedFrameConfiguration,
): AttributionV2EnvironmentSnapshot = copy(
    negotiatedCameraFpsRange = requireNotNull(observed.negotiatedCameraFpsRange),
    deliveredWidth = observed.deliveredWidth,
    deliveredHeight = observed.deliveredHeight,
    deliveredFormat = observed.deliveredFormat,
)
