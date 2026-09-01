package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.export.StorageLocationId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PhysicalPurgeEvidenceTest {
    @Test
    fun completeVideoEnabledThreeRootPurgeRendersAnAdmissibleSanitizedRecord() {
        val rendered = Json.parseToJsonElement(completeObservation().render()).jsonObject

        assertTrue(rendered.getValue("admissible").jsonPrimitive.boolean)
        assertTrue(rendered.getValue("non_participant_observation").jsonPrimitive.boolean)
        assertFalse(rendered.getValue("contains_landmark_coordinates").jsonPrimitive.boolean)
        val encoded = rendered.toString()
        assertFalse(encoded.contains("/data/"))
        assertFalse(encoded.contains("RZGL"))
        assertFalse(encoded.contains("participant_id"))
        assertFalse(encoded.contains("timestamp"))
    }

    @Test
    fun rendererDerivesFailureWhenVideoOrAnyCompleteArtifactIsMissing() {
        val missingVideo = completeObservation().copy(
            artifacts = completeArtifacts().copy(videoPresent = false),
        )
        val missingSync = completeObservation().copy(
            artifacts = completeArtifacts().copy(synchronizationEvidencePresent = false),
        )

        assertFalse(admissible(missingVideo))
        assertFalse(admissible(missingSync))
    }

    @Test
    fun rendererDerivesFailureForIncompleteOrVacuousPurge() {
        val incomplete = completeObservation().copy(
            purgeRecord = completeRecord().copy(state = PurgeState.PURGE_INCOMPLETE),
        )
        val vacuous = completeObservation().copy(
            purgeRecord = completeRecord().copy(
                locationResults = completeRecord().locationResults.mapIndexed { index, result ->
                    if (index == 1) result.copy(preInventory = result.preInventory.copy(ownedEntries = 0)) else result
                },
            ),
        )

        assertFalse(admissible(incomplete))
        assertFalse(admissible(vacuous))
    }

    @Test
    fun rendererRequiresReservedSessionAndClosedProvenanceDigests() {
        assertFailsWith<IllegalArgumentException> {
            completeObservation().copy(sessionId = "10000000-0000-4000-8000-000000000001")
        }
        assertFailsWith<IllegalArgumentException> {
            completeObservation().copy(appApkSha256 = "not-a-digest")
        }
    }

    private fun admissible(observation: PhysicalPurgeObservation): Boolean =
        Json.parseToJsonElement(observation.render()).jsonObject
            .getValue("admissible").jsonPrimitive.boolean

    private fun completeObservation() = PhysicalPurgeObservation(
        sessionId = "00000000-0000-4000-8000-000000000001",
        harnessGitSha = "a".repeat(40),
        appApkSha256 = "b".repeat(64),
        testApkSha256 = "c".repeat(64),
        environment = PhysicalPurgeEnvironment(
            deviceClass = "physical_device",
            manufacturer = "samsung",
            model = "SM-S938B",
            marketingName = "Galaxy S25 Ultra",
            apiLevel = 36,
        ),
        artifacts = completeArtifacts(),
        purgeRecord = completeRecord(),
        tombstoneSha256 = "d".repeat(64),
        tombstoneByteLength = 1024,
        readmissionRefusalCode = "PURGE.SESSION_TOMBSTONED",
        blockingReasons = emptyList(),
    )

    private fun completeArtifacts() = PhysicalPurgeArtifactPreconditions(
        manifestPresent = true,
        videoPresent = true,
        poseStreamPresent = true,
        synchronizationEvidencePresent = true,
    )

    private fun completeRecord() = PurgeRecord(
        state = PurgeState.PURGE_COMPLETE,
        operationSequence = 3,
        locationResults = PurgeFilesystem.devicePurgeLocations.map { location ->
            PurgeLocationResult(
                location = location,
                preInventory = PurgeInventory(4, 2, 1, 32),
                terminalResult = PurgeTerminalResult.DELETED,
                postScan = PurgePostScan(2, 0),
            )
        },
    )
}
