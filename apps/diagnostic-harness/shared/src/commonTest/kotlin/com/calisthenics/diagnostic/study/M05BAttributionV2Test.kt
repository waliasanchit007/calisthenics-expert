package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.live.LiveFrameClassification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M05BAttributionV2Test {
    @Test
    fun sourceCapacityUsesExactClosedIntervalBoundary() {
        val exact = List(300) { index -> index * 100_000_000L }

        assertTrue(sourceSupports(exact, 10))
        assertFalse(sourceSupports(exact.dropLast(1), 10))
        assertFalse(sourceSupports(exact.toMutableList().also { it[150] = it[149] }, 10))
        assertFalse(sourceSupports(exact.toMutableList().also { it[150] = it[149] - 1L }, 10))
        assertFalse(sourceSupports(exact, 15))
        assertFalse(sourceSupports(exact, 30))
    }

    @Test
    fun backendCapacityPinsCountAndInclusiveInterval() {
        val interval10 = 100_000_000L
        val exact = List(100) { interval10 }

        assertTrue(backendSupports(exact, 10))
        assertFalse(backendSupports(exact.dropLast(1), 10))
        assertFalse(backendSupports(exact.toMutableList().also { it[50]++ }, 10))
        assertFalse(backendSupports(exact.toMutableList().also { it[50] = -1L }, 10))
    }

    @Test
    fun selectorUsesProductionAdmissionAtExactTenFifteenThirtyBoundaries() {
        val timestamps = listOf(0L, 33_333_333L, 66_666_666L, 99_999_999L, 100_000_000L, 133_333_333L)

        assertEquals(listOf(0L, 100_000_000L), productionSelectorTimestamps(timestamps, 10))
        assertEquals(listOf(0L, 66_666_666L, 133_333_333L), productionSelectorTimestamps(timestamps, 15))
        assertEquals(listOf(0L, 33_333_333L, 66_666_666L, 99_999_999L, 133_333_333L), productionSelectorTimestamps(timestamps, 30))
    }

    @Test
    fun everyTerminalClassParticipatesInExactCallbackConservation() {
        val terminals = AttributionV2TerminalClass.entries.mapIndexed { index, terminal ->
            AttributionV2Callback(
                callbackId = index.toLong(),
                captureTimestampNs = index * 1_000L,
                productionClassification = classificationFor(terminal),
                cadenceDue = terminal !in setOf(
                    AttributionV2TerminalClass.CADENCE_ONLY,
                    AttributionV2TerminalClass.CADENCE_AND_BACKPRESSURE,
                ),
                slotAvailable = terminal !in setOf(
                    AttributionV2TerminalClass.BACKPRESSURE_ONLY,
                    AttributionV2TerminalClass.CADENCE_AND_BACKPRESSURE,
                ),
                terminalClass = terminal,
            )
        }
        val arm = callbackArm(terminals)

        assertFalse(
            "ATTRIBUTION.TERMINAL_CLOSURE" in validateAttributionV2(
                baseEvidence(callbackArm = arm),
            ).blockingCodes,
        )
        assertTrue(
            "ATTRIBUTION.TERMINAL_CLOSURE" in validateAttributionV2(
                baseEvidence(callbackArm = arm.copy(terminalCallbacks = terminals + terminals.first())),
            ).blockingCodes,
        )
        assertTrue(
            "ATTRIBUTION.TERMINAL_CLOSURE" in validateAttributionV2(
                baseEvidence(callbackArm = arm.copy(terminalCallbacks = terminals.dropLast(1))),
            ).blockingCodes,
        )
    }

    @Test
    fun divergenceFailuresReplicateDisagreementAndSelectorOnlyEvidenceBlock() {
        val divergent = callback(
            terminal = AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE,
            classification = LiveFrameClassification.CADENCE_SELECTION,
        )
        assertTrue(
            "ATTRIBUTION.ADMISSION_STATE_DIVERGENCE" in validateAttributionV2(
                baseEvidence(callbackArm = callbackArm(listOf(divergent))),
            ).blockingCodes,
        )
        assertTrue(
            "ATTRIBUTION.INFERENCE_FAILURE" in validateAttributionV2(
                baseEvidence(inferenceFailures = 1),
            ).blockingCodes,
        )
        assertTrue(
            "ATTRIBUTION.REPLICATE_DISAGREEMENT" in validateAttributionV2(
                baseEvidence(replicates = listOf(distinctReplicate(listOf(10, 15, 30)))),
            ).blockingCodes,
        )
        assertTrue(
            "ATTRIBUTION.SELECTOR_ONLY" in validateAttributionV2(
                baseEvidence(source = null, backend = null, replicates = emptyList()),
            ).blockingCodes,
        )
    }

    @Test
    fun validTwoOrderEvidenceDerivesAdmissionWithoutCallerSuccessBoolean() {
        val result = validateAttributionV2(baseEvidence())

        assertEquals(AttributionV2Status.RECORDED, result.status)
        assertEquals(emptyList(), result.blockingCodes)
        assertTrue(result.satisfiesGate)
    }

    @Test
    fun exactRateOrderingUsesCrossProductsWhenIntegerFloorsCollide() {
        val runs = listOf(
            rateRun(10, 1, 1_100_000_000L),
            rateRun(15, 2, 2_100_000_000L),
            rateRun(30, 3, 3_100_000_000L),
        )

        assertEquals(
            mapOf(10 to 0L, 15 to 1L, 30 to 2L),
            attributionV2ExactRateOrderValues(runs, admitted = true),
        )
    }

    private fun baseEvidence(
        source: AttributionV2SourceArm? = AttributionV2SourceArm(sourceTimestamps()),
        backend: AttributionV2BackendArm? = AttributionV2BackendArm(List(100) { 10_000_000L }, 0),
        callbackArm: AttributionV2CallbackArm = callbackArm(listOf(callback())),
        inferenceFailures: Int = 0,
        replicates: List<AttributionV2Replicate> = listOf(
            distinctReplicate(listOf(10, 15, 30)),
            distinctReplicate(listOf(30, 15, 10)),
        ),
    ) = M05BAttributionV2Evidence(
        protocolVersion = M05BAttributionV2Protocol.VERSION,
        identityMatches = true,
        environmentMatches = true,
        sourceArm = source,
        backendArm = backend?.copy(inferenceFailures = inferenceFailures),
        selectorArm = AttributionV2SelectorArm(
            sourceTimestamps().associateWithSelectorResults(),
            sourceTimestamps().associateWithSelectorResults(),
        ),
        callbackArm = callbackArm,
        replicates = replicates,
        deviceBlockingCodes = emptyList(),
    )

    private fun sourceTimestamps() = List(300) { index -> index * 20_000_000L }

    private fun List<Long>.associateWithSelectorResults() = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith {
        productionSelectorTimestamps(this, it)
    }

    private fun callback(
        terminal: AttributionV2TerminalClass = AttributionV2TerminalClass.ADMITTED_COMPLETED,
        classification: LiveFrameClassification = LiveFrameClassification.SUBMIT,
    ) = AttributionV2Callback(0, 0, classification, cadenceDue = true, slotAvailable = true, terminal)

    private fun callbackArm(terminals: List<AttributionV2Callback>) = AttributionV2CallbackArm(
        deliveredCallbackIds = terminals.map { it.callbackId },
        terminalCallbacks = terminals,
        submittedInferenceIds = terminals.filter {
            it.terminalClass in setOf(
                AttributionV2TerminalClass.ADMITTED_COMPLETED,
                AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED,
            )
        }.map { it.callbackId },
        pendingInferenceIds = emptySet(),
    )

    private fun distinctReplicate(order: List<Int>) = AttributionV2Replicate(
        cadenceOrder = order,
        admittedCompletionRates = mapOf(10 to 9L, 15 to 14L, 30 to 29L),
        cadenceOpportunityRates = mapOf(10 to 10L, 15 to 15L, 30 to 30L),
        sourceSupported = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith { true },
        backendSupported = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith { true },
    )

    private fun rateRun(cadence: Int, count: Int, spanNs: Long) = AttributionV2EndToEndRun(
        cadenceFps = cadence,
        sessionReference = "exports/00000000-0000-4000-8000-00000000000$cadence",
        armStartedNs = 0L,
        initialThermalStatus = 0,
        finalThermalStatus = 0,
        deliveredCallbackIds = (0 until count).map(Int::toLong),
        terminalCallbacks = (0 until count).map { callbackId ->
            AttributionV2Callback(
                callbackId = callbackId.toLong(),
                captureTimestampNs = callbackId.toLong() * spanNs / (count - 1).coerceAtLeast(1),
                productionClassification = LiveFrameClassification.SUBMIT,
                cadenceDue = true,
                slotAvailable = true,
                terminalClass = AttributionV2TerminalClass.ADMITTED_COMPLETED,
                phase = AttributionV2CallbackPhase.MEASURED,
            )
        },
        submittedInferenceIds = (0 until count).map(Int::toLong),
        pendingInferenceIds = emptySet(),
        measuredSpanNs = spanNs,
    )

    private fun classificationFor(terminal: AttributionV2TerminalClass): LiveFrameClassification = when (terminal) {
        AttributionV2TerminalClass.ADMITTED_COMPLETED,
        AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED,
        -> LiveFrameClassification.SUBMIT
        AttributionV2TerminalClass.CADENCE_ONLY -> LiveFrameClassification.CADENCE_SELECTION
        AttributionV2TerminalClass.BACKPRESSURE_ONLY,
        AttributionV2TerminalClass.CADENCE_AND_BACKPRESSURE,
        -> LiveFrameClassification.BACKPRESSURE
        AttributionV2TerminalClass.PREPROCESSING_FAILED -> LiveFrameClassification.PREPROCESSING_FAILED
        AttributionV2TerminalClass.SESSION_STOPPING -> LiveFrameClassification.SESSION_STOPPING
        AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE -> LiveFrameClassification.CADENCE_SELECTION
    }
}
