package com.calisthenics.diagnostic.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageExclusionEvidenceTest {
    @Test
    fun rendererDerivesAdmissionFromEveryObservedConjunct() {
        val baseline = observation()
        assertTrue(renderedGate(baseline))

        listOf(
            baseline.copy(allowBackupDisabled = false),
            baseline.copy(dataExtractionRulesResource = null),
            baseline.copy(cloudBackupExcludedParent = null),
            baseline.copy(deviceTransferExcludedParent = null),
            baseline.copy(observedTopLevelChildren = baseline.observedTopLevelChildren + "unregistered"),
        ).forEach { mutation -> assertFalse(renderedGate(mutation)) }
    }

    @Test
    fun unauthorizedTargetCannotSatisfyTheGateAndRecordIsSanitized() {
        val evidence = evidence(
            observation(),
            environment().copy(deviceClass = "emulator"),
        )
        val record = Json.parseToJsonElement(renderStorageExclusionEvidence(evidence)).jsonObject

        assertFalse(record.getValue("satisfies_s25_storage_exclusion_gate").jsonPrimitive.boolean)
        assertTrue(record.getValue("sanitized").jsonPrimitive.boolean)
        assertTrue(record.getValue("non_participant_observation").jsonPrimitive.boolean)
        assertTrue(record.getValue("blocking_reasons").jsonArray.isNotEmpty())
    }

    private fun renderedGate(observation: StorageExclusionObservation): Boolean {
        val record = Json.parseToJsonElement(
            renderStorageExclusionEvidence(evidence(observation, environment())),
        ).jsonObject
        return record.getValue("satisfies_s25_storage_exclusion_gate").jsonPrimitive.boolean
    }

    private fun evidence(
        observation: StorageExclusionObservation,
        environment: StorageExclusionEnvironment,
    ) = StorageExclusionEvidence(
        emittedBy = "StorageExclusionDeviceGateTest",
        harnessGitSha = "0123456789abcdef0123456789abcdef01234567",
        environment = environment,
        observation = observation,
    )

    private fun environment() = StorageExclusionEnvironment(
        deviceClass = "physical_device",
        deviceIdentity = "samsung/SM-S938B/pa3q/pa3qxins",
        deviceFingerprint = "samsung/pa3q/pa3qxins:16/test:user/release-keys",
        apiLevel = 36,
        androidRelease = "16",
        abi = "arm64-v8a",
        manufacturer = "samsung",
        model = "SM-S938B",
        marketingName = "",
    )

    private fun observation() = StorageExclusionObservation(
        allowBackupDisabled = true,
        dataExtractionRulesResource = "@xml/data_extraction_rules",
        cloudBackupExcludedParent = "diagnostic-harness/",
        deviceTransferExcludedParent = "diagnostic-harness/",
        registeredAndroidChildren = listOf("exports", "staging", "transfer-staging", "tombstones"),
        observedTopLevelChildren = listOf("exports", "staging", "transfer-staging", "tombstones"),
    )
}
