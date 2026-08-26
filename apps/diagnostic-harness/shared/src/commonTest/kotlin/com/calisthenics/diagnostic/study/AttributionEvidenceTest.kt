package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition
import com.calisthenics.diagnostic.telemetry.DeliveredCallbackAccounting
import com.calisthenics.diagnostic.telemetry.PipelineDisposition
import com.calisthenics.diagnostic.telemetry.PipelineStage
import com.calisthenics.diagnostic.telemetry.PipelineStageEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttributionEvidenceTest {
    @Test
    fun absentPhysicalS25EvidenceRendersPendingInsteadOfAPass() {
        val validation = validateAttributionEvidence(null)
        val rendered = Json.parseToJsonElement(renderAttributionEvidence(validation)).jsonObject

        assertEquals(AttributionEvidenceStatus.PENDING, validation.status)
        assertFalse(validation.satisfiesGate)
        assertTrue("TELEMETRY.PHYSICAL_S25_PENDING" in validation.blockingCodes)
        assertEquals("pending", rendered.getValue("physical_evidence_status").jsonPrimitive.content)
        assertFalse(rendered.getValue("satisfies_s25_attribution_gate").jsonPrimitive.boolean)
    }

    @Test
    fun hostValidationRequiresFixedThirtyFpsRequestDistinctTenFifteenThirtyAdmissionAndReconciledEvents() {
        val evidence = PhysicalAttributionEvidence(
            source = AttributionEvidenceSource("physical_device", "samsung", "SM-S938B", ""),
            runs = listOf(run(10, 10), run(15, 15), run(30, 30)),
        )

        val accepted = validateAttributionEvidence(evidence)
        val wrongCameraRequest = validateAttributionEvidence(
            evidence.copy(runs = evidence.runs.map { it.copy(requestedCameraFps = 29) }),
        )
        val badSequence = validateAttributionEvidence(
            evidence.copy(runs = evidence.runs.mapIndexed { index, value ->
                if (index == 0) value.copy(events = value.events.map { event -> event.copy(sequence = 0L) }) else value
            }),
        )
        val badTimestamp = validateAttributionEvidence(
            evidence.copy(runs = evidence.runs.mapIndexed { index, value ->
                if (index == 0) value.copy(events = value.events.mapIndexed { eventIndex, event ->
                    if (eventIndex == 0) event.copy(tSessionNs = 2L) else event
                }) else value
            }),
        )

        assertEquals(AttributionEvidenceStatus.RECORDED, accepted.status)
        assertTrue(accepted.satisfiesGate)
        assertEquals(AttributionEvidenceStatus.BLOCKED, wrongCameraRequest.status)
        assertTrue("TELEMETRY.CAMERA_REQUEST_NOT_30_FPS" in wrongCameraRequest.blockingCodes)
        assertEquals(AttributionEvidenceStatus.BLOCKED, badSequence.status)
        assertTrue("TELEMETRY.STAGE_SEQUENCE_INVALID" in badSequence.blockingCodes)
        assertEquals(AttributionEvidenceStatus.BLOCKED, badTimestamp.status)
        assertTrue("TELEMETRY.STAGE_TIME_INVALID" in badTimestamp.blockingCodes)
    }

    private fun run(configuredInferenceFps: Int, admittedInferenceCount: Int): ShortCadenceAttributionRun {
        val deliveredCallbacks = 40
        val cadenceSelections = deliveredCallbacks - admittedInferenceCount
        val events = buildList {
            var sequence = 0L
            var time = 0L
            repeat(admittedInferenceCount) { frameIndex ->
                add(PipelineStageEvent(sequence++, time++, frameIndex.toLong(), PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED))
                add(PipelineStageEvent(sequence++, time++, frameIndex.toLong(), PipelineStage.INFERENCE, PipelineDisposition.SUBMITTED))
                add(PipelineStageEvent(sequence++, time++, frameIndex.toLong(), PipelineStage.INFERENCE, PipelineDisposition.COMPLETED))
            }
            repeat(cadenceSelections) { offset ->
                val frameIndex = admittedInferenceCount + offset
                add(PipelineStageEvent(sequence++, time++, frameIndex.toLong(), PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED))
                add(PipelineStageEvent(sequence++, time++, frameIndex.toLong(), PipelineStage.CADENCE, PipelineDisposition.CADENCE_SELECTION))
            }
        }
        return ShortCadenceAttributionRun(
            requestedCameraFps = 30,
            configuredInferenceFps = configuredInferenceFps,
            events = events,
            callbackAccounting = DeliveredCallbackAccounting(
                deliveredCallbacks = deliveredCallbacks.toLong(),
                unclosedCallbacks = 0L,
                counts = mapOf(
                    CallbackTerminalDisposition.INFERENCE_COMPLETED to admittedInferenceCount.toLong(),
                    CallbackTerminalDisposition.CADENCE_SELECTION to cadenceSelections.toLong(),
                ),
            ),
        )
    }
}
