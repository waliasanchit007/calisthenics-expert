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
        val result = M05BRunAdmission.evaluateDerived(
            envelope = envelope(),
            order = order(),
            observedStart = observedStart(),
            sessionId = "00000000-0000-4000-8000-000000000001",
            attemptContext = M05BAttemptChain.derive(order(), emptyList()),
            inputClass = "empty_scene",
            operatorAttestedNoPerson = true,
        )
        assertTrue(result.admitted)
        assertEquals(M05BRunAdmission.REQUIRED_CONJUNCT_IDS, result.evaluatedConjunctIds)

        val unavailable = observedStart().copy(brightnessRaw = null)
        val blocked = M05BRunAdmission.evaluateDerived(
            envelope(), order(), unavailable,
            "00000000-0000-4000-8000-000000000001", M05BAttemptChain.derive(order(), emptyList()),
            "empty_scene", true,
        )
        assertFalse(blocked.admitted)
        assertTrue("DISPLAY.BRIGHTNESS_UNAVAILABLE" in blocked.blockingCodes)
    }

    @Test
    fun rendererIsSanitizedAndRecordsOrderedBlockingCodesForEveryIdentityMutation() {
        val evidence = M05BStartEnvelopeEvidence(
            envelope = envelope(),
            order = order().copy(cadencesFps = listOf(10, 10, 30)),
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
        envelopeId = "m05b-envelope-01", procedureVersion = "1.0.0",
        procedureSha256 = "e".repeat(64),
        appSha256 = "a".repeat(64), modelSha256 = "b".repeat(64),
        runtimeVersion = "mediapipe-tasks-vision-1.0.0", delegate = "CPU",
        preprocessingVersion = "1.0.0", deviceUnitDigest = "c".repeat(64),
        deviceClass = "physical_device", manufacturer = "samsung", model = "SM-S938B",
        deviceBuild = "build", apiLevel = 36,
        charging = false, powerSource = "battery", hostBatteryOverrideRejected = true,
        powerSaveMode = false, baselineBatteryObservation = M05BBatteryObservation("percent", 80.0),
        hostAdbTransport = "wifi", appTransport = "wifi", networkIdentityPersisted = false,
        ambientToken = "ambient-01", ambientConstancy = "constant",
        ambientConstancySource = "operator_attestation_end_of_baseline", keepAwake = true,
        brightnessMode = "manual", brightnessRaw = 128.0, brightnessScaleMin = 0.0,
        brightnessScaleMax = 255.0, cameraFps = 30, inferenceCadencesFps = listOf(10, 15, 30),
        durationNs = 1_500_000_000_000, overshootNs = 1_000_000_000,
        cooldownMaxDurationNs = 600_000_000_000, cooldownSamplingIntervalNs = 1_000_000_000,
        thermalStatus = "none", batteryTemperatureMinC = 29.0,
        batteryTemperatureMaxC = 30.0, batteryTemperatureRangeC = 1.0,
        orderPath = "execution-order.json", orderSha256 = "f".repeat(64), producerGitSha = "d".repeat(40),
    )

    private fun observedStart() = M05BObservedStart(
        appSha256 = "a".repeat(64), modelSha256 = "b".repeat(64),
        runtimeVersion = "mediapipe-tasks-vision-1.0.0", delegate = "CPU",
        preprocessingVersion = "1.0.0", deviceUnitDigest = "c".repeat(64),
        deviceClass = "physical_device", manufacturer = "samsung", model = "SM-S938B",
        deviceBuild = "build", apiLevel = 36, charging = false, powerSource = "battery",
        hostBatteryOverrideRejected = true, powerSaveMode = false,
        batteryObservation = M05BBatteryObservation("percent", 79.0), deviceIdleMode = false,
        hostAdbTransport = "wifi", appTransport = "wifi",
        networkIdentityPersisted = false,
        ambientToken = "ambient-01", ambientConstancy = "constant",
        ambientConstancyOperatorSupplied = true, keepAwake = true,
        brightnessMode = "manual", brightnessRaw = 128.0, brightnessScaleMin = 0.0,
        brightnessScaleMax = 255.0, thermalStatus = "none", batteryTemperatureC = 29.5,
    )

    private fun order() = M05BExecutionOrder(
        cadencesFps = listOf(10, 15, 30),
        orderId = "order-01",
        producerGitSha = "d".repeat(40),
    )
}
