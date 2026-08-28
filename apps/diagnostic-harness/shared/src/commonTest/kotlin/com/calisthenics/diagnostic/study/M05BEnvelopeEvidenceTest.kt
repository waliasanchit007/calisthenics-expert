package com.calisthenics.diagnostic.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M05BEnvelopeEvidenceTest {
    @Test
    fun rendererDerivesAdmissionFromObservedStartValuesAndNeverAcceptsUnavailableValues() {
        val result = M05BRunAdmission.evaluate(
            envelope = envelope(),
            order = M05BExecutionOrder(listOf(10, 15, 30)),
            observedStart = observedStart(),
            sessionId = "00000000-0000-4000-8000-000000000001",
            cadenceFps = 10,
            attemptSequence = 1,
            predecessorStatus = M05BPredecessorStatus.NOT_APPLICABLE,
        )
        assertTrue(result.admitted)
        assertEquals(M05BRunAdmission.REQUIRED_CONJUNCT_IDS, result.evaluatedConjunctIds)

        val unavailable = observedStart().copy(brightnessRaw = null)
        val blocked = M05BRunAdmission.evaluate(
            envelope(), M05BExecutionOrder(listOf(10, 15, 30)), unavailable,
            "00000000-0000-4000-8000-000000000001", 10, 1,
            M05BPredecessorStatus.NOT_APPLICABLE,
        )
        assertFalse(blocked.admitted)
        assertTrue("DISPLAY.BRIGHTNESS_UNAVAILABLE" in blocked.blockingCodes)
    }

    @Test
    fun rendererIsSanitizedAndRecordsOrderedBlockingCodesForEveryIdentityMutation() {
        val evidence = M05BStartEnvelopeEvidence(
            envelope = envelope(),
            order = M05BExecutionOrder(listOf(10, 10, 30)),
            observedStart = observedStart().copy(appSha256 = "0".repeat(64)),
        )
        val record = Json.parseToJsonElement(renderM05BStartEnvelopeEvidence(evidence)).jsonObject
        assertFalse(record.getValue("satisfies_s25_m05b_admission").jsonPrimitive.boolean)
        assertTrue(record.getValue("sanitized").jsonPrimitive.boolean)
        assertTrue(record.getValue("blocking_codes").jsonArray.isNotEmpty())
        assertFalse(record.containsKey("adb_serial"))
        assertFalse(record.containsKey("ssid"))
    }

    private fun envelope() = M05BStartEnvelope(
        appSha256 = "a".repeat(64), modelSha256 = "b".repeat(64),
        runtimeVersion = "mediapipe-tasks-vision-1.0.0", delegate = "CPU",
        preprocessingVersion = "1.0.0", deviceUnitDigest = "c".repeat(64),
        deviceClass = "physical_device", manufacturer = "samsung", model = "SM-S938B",
        deviceBuild = "build", apiLevel = 36,
        charging = false, powerSource = "battery", hostBatteryOverrideRejected = true,
        hostAdbTransport = "wifi", appTransport = "wifi", networkIdentityPersisted = false,
        ambientToken = "ambient-01", ambientConstancy = "constant", keepAwake = true,
        brightnessMode = "manual", brightnessRaw = 128.0, brightnessScaleMin = 0.0,
        brightnessScaleMax = 255.0, cameraFps = 30, inferenceCadencesFps = listOf(10, 15, 30),
        durationNs = 1_500_000_000_000, overshootNs = 1_000_000_000,
    )

    private fun observedStart() = M05BObservedStart(
        appSha256 = "a".repeat(64), modelSha256 = "b".repeat(64),
        runtimeVersion = "mediapipe-tasks-vision-1.0.0", delegate = "CPU",
        preprocessingVersion = "1.0.0", deviceUnitDigest = "c".repeat(64),
        deviceClass = "physical_device", manufacturer = "samsung", model = "SM-S938B",
        deviceBuild = "build", apiLevel = 36, charging = false, powerSource = "battery",
        hostBatteryOverrideRejected = true, hostAdbTransport = "wifi", appTransport = "wifi",
        networkIdentityPersisted = false,
        ambientToken = "ambient-01", ambientConstancy = "constant", keepAwake = true,
        brightnessMode = "manual", brightnessRaw = 128.0, brightnessScaleMin = 0.0,
        brightnessScaleMax = 255.0,
    )
}
