package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition
import com.calisthenics.diagnostic.telemetry.DeliveredCallbackAccounting
import com.calisthenics.diagnostic.telemetry.PipelineDisposition
import com.calisthenics.diagnostic.telemetry.PipelineStage
import com.calisthenics.diagnostic.telemetry.PipelineStageEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttributionEvidenceTest {
    @Test
    fun absentPhysicalS25EvidenceRendersPendingInsteadOfAPass() {
        val validation = validateAttributionEvidence(null)
        val rendered = Json.parseToJsonElement(renderAttributionEvidence(null)).jsonObject

        assertEquals(AttributionEvidenceStatus.PENDING, validation.status)
        assertFalse(validation.satisfiesGate)
        assertTrue("TELEMETRY.PHYSICAL_S25_PENDING" in validation.blockingCodes)
        assertEquals("pending", rendered.getValue("physical_evidence_status").jsonPrimitive.content)
        assertFalse(rendered.getValue("satisfies_s25_attribution_gate").jsonPrimitive.boolean)
    }

    @Test
    fun hostValidationRequiresFixedThirtyFpsRequestDistinctTenFifteenThirtyAdmissionAndReconciledEvents() {
        val evidence = PhysicalAttributionEvidence(
            source = source(),
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

    @Test
    fun malformedCadenceKeysBlockWithoutThrowing() {
        val validEvidence = PhysicalAttributionEvidence(
            source = source(),
            runs = listOf(run(10, 10), run(15, 15), run(30, 30)),
        )

        listOf(
            listOf(5, 15, 30),
            listOf(10, 20, 30),
            listOf(10, 15, 15),
            listOf(15, 10, 30),
        ).forEach { configuredCadences ->
            val evidence = validEvidence.copy(runs = configuredCadences.mapIndexed { index, fps ->
                validEvidence.runs[index].copy(configuredInferenceFps = fps)
            })
            val result = runCatching { validateAttributionEvidence(evidence) }
                .getOrElse { error("malformed cadence keys must produce typed validation: $it") }

            assertEquals(AttributionEvidenceStatus.BLOCKED, result.status)
            assertTrue("TELEMETRY.CADENCE_SEQUENCE_INVALID" in result.blockingCodes)
            assertTrue("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT" in result.blockingCodes)
        }
    }

    @Test
    fun mixedArtifactStageSequenceMayContainGaps() {
        val evidence = PhysicalAttributionEvidence(
            source = source(),
            runs = listOf(
                run(10, 10, 40).withMixedArtifactSequenceGap(),
                run(15, 15, 40),
                run(30, 30, 40),
            ),
        )

        val result = validateAttributionEvidence(evidence)

        assertEquals(AttributionEvidenceStatus.RECORDED, result.status)
        assertTrue(result.satisfiesGate)
    }

    @Test
    fun cadenceAdmissionUsesDeliveredCallbackRatesNotRawCounts() {
        val evidence = PhysicalAttributionEvidence(
            source = source(),
            runs = listOf(
                run(10, admittedInferenceCount = 20, deliveredCallbacks = 100),
                run(15, admittedInferenceCount = 15, deliveredCallbacks = 50),
                run(30, admittedInferenceCount = 10, deliveredCallbacks = 10),
            ),
        )

        val result = validateAttributionEvidence(evidence)

        assertEquals(AttributionEvidenceStatus.RECORDED, result.status)
        assertTrue(result.satisfiesGate)
    }

    @Test
    fun zeroDeliveryRunBlocksCadenceAdmission() {
        val evidence = PhysicalAttributionEvidence(
            source = source(),
            runs = listOf(
                run(10, admittedInferenceCount = 0, deliveredCallbacks = 0),
                run(15, admittedInferenceCount = 1, deliveredCallbacks = 10),
                run(30, admittedInferenceCount = 2, deliveredCallbacks = 10),
            ),
        )

        val result = validateAttributionEvidence(evidence)

        assertEquals(AttributionEvidenceStatus.BLOCKED, result.status)
        assertTrue("TELEMETRY.CALLBACK_ACCOUNTING_INVALID" in result.blockingCodes)
    }

    @Test
    fun equalAdmissionRatesAreNotStrictlyDistinct() {
        val evidence = PhysicalAttributionEvidence(
            source = source(),
            runs = listOf(
                run(10, admittedInferenceCount = 1, deliveredCallbacks = 2),
                run(15, admittedInferenceCount = 2, deliveredCallbacks = 4),
                run(30, admittedInferenceCount = 3, deliveredCallbacks = 6),
            ),
        )

        val result = validateAttributionEvidence(evidence)

        assertEquals(AttributionEvidenceStatus.BLOCKED, result.status)
        assertTrue("TELEMETRY.CADENCE_ADMISSION_NOT_DISTINCT" in result.blockingCodes)
    }

    @Test
    fun reservedSyntheticIdsAreNotLimitedToTheThreeFixtureIds() {
        val evidence = PhysicalAttributionEvidence(
            source = source(),
            runs = listOf(run(10, 10), run(15, 15), run(30, 30)).mapIndexed { index, value ->
                if (index == 0) value.copy(
                    sessionId = "00000000-0000-4000-8000-deadbeefcafe",
                    exportRelativePath = "exports/00000000-0000-4000-8000-deadbeefcafe",
                ) else value
            },
        )

        val result = validateAttributionEvidence(evidence)

        assertEquals(AttributionEvidenceStatus.RECORDED, result.status)
        assertTrue(result.satisfiesGate)
    }

    @Test
    fun deviceIdentityBuildAndApiAreRequiredAndRenderedWithoutRawFingerprint() {
        val evidence = PhysicalAttributionEvidence(source(), listOf(run(10, 10), run(15, 15), run(30, 30)))
        val rendered = Json.parseToJsonElement(renderAttributionEvidence(evidence)).jsonObject

        assertEquals("samsung/SM-S938B/pa3q/pa3qxins", rendered.getValue("device").jsonObject.getValue("device_identity").jsonPrimitive.content)
        assertEquals(36, rendered.getValue("device").jsonObject.getValue("api_level").jsonPrimitive.int)
        assertEquals("pa3q:16/build:user/release-keys", rendered.getValue("device").jsonObject.getValue("os_build").jsonPrimitive.content)
        assertFalse("device_fingerprint" in rendered.toString())

        val incomplete = validateAttributionEvidence(
            evidence.copy(source = source().copy(deviceIdentity = "", apiLevel = 0, osBuild = "")),
        )
        assertTrue("TELEMETRY.DEVICE_IDENTITY_INCOMPLETE" in incomplete.blockingCodes)
    }

    @Test
    fun overflowingAccountingIsBlockedWithoutOverflowingTheRatioCalculation() {
        val valid = listOf(run(10, 10), run(15, 15), run(30, 30))
        val overflowing = valid.mapIndexed { index, value ->
            if (index == 0) value.copy(
                callbackAccounting = DeliveredCallbackAccounting(
                    deliveredCallbacks = Long.MAX_VALUE,
                    unclosedCallbacks = 0L,
                    counts = mapOf(
                        CallbackTerminalDisposition.INFERENCE_COMPLETED to Long.MAX_VALUE,
                        CallbackTerminalDisposition.INFERENCE_FAILED to 1L,
                    ),
                ),
            ) else value
        }

        val result = validateAttributionEvidence(PhysicalAttributionEvidence(source(), overflowing))
        val rendered = Json.parseToJsonElement(
            renderAttributionEvidence(PhysicalAttributionEvidence(source(), overflowing)),
        ).jsonObject

        assertEquals(AttributionEvidenceStatus.BLOCKED, result.status)
        assertTrue("TELEMETRY.CALLBACK_ACCOUNTING_INVALID" in result.blockingCodes)
        assertEquals("blocked", rendered.getValue("physical_evidence_status").jsonPrimitive.content)
        assertEquals(
            0L,
            rendered.getValue("runs").jsonArray.first().jsonObject.getValue("admitted_callbacks").jsonPrimitive.long,
        )
    }

    private fun source() = AttributionEvidenceSource(
        deviceClass = "physical_device",
        deviceIdentity = "samsung/SM-S938B/pa3q/pa3qxins",
        manufacturer = "samsung",
        model = "SM-S938B",
        apiLevel = 36,
        osBuild = "pa3q:16/build:user/release-keys",
        marketingName = "",
    )

    private fun run(
        configuredInferenceFps: Int,
        admittedInferenceCount: Int,
        deliveredCallbacks: Int = 40,
    ): ShortCadenceAttributionRun {
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
            sessionId = "00000000-0000-4000-8000-00000000000${when (configuredInferenceFps) {
                10 -> 1
                15 -> 2
                else -> 3
            }}",
            exportRelativePath = "exports/00000000-0000-4000-8000-00000000000${when (configuredInferenceFps) {
                10 -> 1
                15 -> 2
                else -> 3
            }}",
            manifestSha256 = "a".repeat(64),
            framesSha256 = "b".repeat(64),
            telemetrySha256 = "c".repeat(64),
            identity = AttributionRunIdentity(
                appGitSha = "d".repeat(40),
                probeGitSha = "e".repeat(40),
                apkSha256 = "0".repeat(64),
                modelSha256 = "f".repeat(64),
                runtimeVersion = "runtime",
                backendVersion = "backend",
                delegate = "CPU",
            ),
        )
    }

    private fun ShortCadenceAttributionRun.withMixedArtifactSequenceGap(): ShortCadenceAttributionRun = copy(
        events = events.mapIndexed { index, event ->
            event.copy(sequence = event.sequence + if (index == 0) 0L else 1L)
        },
    )
}
