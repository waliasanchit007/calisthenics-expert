package com.calisthenics.diagnostic.purge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val PHYSICAL_PURGE_EVIDENCE_SCHEMA_VERSION: String = "1.0.0-draft"
const val PHYSICAL_PURGE_RECORD_KIND: String = "m05b_physical_purge_device_observation"

data class PhysicalPurgeEnvironment(
    val deviceClass: String,
    val manufacturer: String,
    val model: String,
    val marketingName: String,
    val apiLevel: Int,
)

data class PhysicalPurgeArtifactPreconditions(
    val manifestPresent: Boolean,
    val videoPresent: Boolean,
    val poseStreamPresent: Boolean,
    val synchronizationEvidencePresent: Boolean,
) {
    val completeVideoSession: Boolean
        get() = manifestPresent && videoPresent && poseStreamPresent && synchronizationEvidencePresent
}

data class PhysicalPurgeObservation(
    val sessionId: String,
    val harnessGitSha: String,
    val appApkSha256: String,
    val testApkSha256: String,
    val environment: PhysicalPurgeEnvironment,
    val artifacts: PhysicalPurgeArtifactPreconditions,
    val purgeRecord: PurgeRecord,
    val tombstoneSha256: String,
    val tombstoneByteLength: Long,
    val readmissionRefusalCode: String,
    val blockingReasons: List<String>,
) {
    init {
        require(PurgeSessionId.parseOrNull(sessionId) != null) {
            "physical purge evidence requires a reserved synthetic session ID"
        }
        require(SHA1.matches(harnessGitSha)) { "harness Git SHA must be lowercase SHA-1" }
        require(SHA256.matches(appApkSha256)) { "app APK SHA-256 must be lowercase hex" }
        require(SHA256.matches(testApkSha256)) { "test APK SHA-256 must be lowercase hex" }
        require(SHA256.matches(tombstoneSha256)) { "tombstone SHA-256 must be lowercase hex" }
        require(tombstoneByteLength > 0) { "tombstone journal must be non-empty" }
    }

    fun render(): String = Json.encodeToString(JsonObject.serializer(), record())

    private fun record(): JsonObject {
        val expectedLocations = PurgeFilesystem.devicePurgeLocations
        val locationSetIsExact = purgeRecord.locationResults.map(PurgeLocationResult::location) == expectedLocations
        val inventoriesAreNonVacuous = purgeRecord.locationResults.all {
            it.preInventory.entriesScanned > 0 && it.preInventory.ownedEntries > 0
        }
        val postScansAreEmpty = purgeRecord.locationResults.all {
            it.postScan.entriesScanned > 0 && it.postScan.ownedRemaining == 0
        }
        val terminalResultsAreClosed = purgeRecord.locationResults.all {
            it.terminalResult == PurgeTerminalResult.DELETED ||
                it.terminalResult == PurgeTerminalResult.ALREADY_ABSENT
        }
        val admissible = blockingReasons.isEmpty() && artifacts.completeVideoSession &&
            purgeRecord.state == PurgeState.PURGE_COMPLETE && locationSetIsExact &&
            inventoriesAreNonVacuous && postScansAreEmpty && terminalResultsAreClosed &&
            readmissionRefusalCode == "PURGE.SESSION_TOMBSTONED"

        return buildJsonObject {
            put("artifact_schema_version", PHYSICAL_PURGE_EVIDENCE_SCHEMA_VERSION)
            put("record_kind", PHYSICAL_PURGE_RECORD_KIND)
            put("sanitized", true)
            put("non_participant_observation", true)
            put("contains_landmark_coordinates", false)
            put("policy_status", "proposal")
            put("session_id", sessionId)
            put("harness_git_sha", harnessGitSha)
            put("app_apk_sha256", appApkSha256)
            put("test_apk_sha256", testApkSha256)
            putJsonObject("environment") {
                put("device_class", environment.deviceClass)
                put("manufacturer", environment.manufacturer)
                put("model", environment.model)
                put("marketing_name", environment.marketingName)
                put("api_level", environment.apiLevel)
            }
            putJsonObject("artifacts_before_purge") {
                put("manifest_present", artifacts.manifestPresent)
                put("video_present", artifacts.videoPresent)
                put("pose_stream_present", artifacts.poseStreamPresent)
                put("synchronization_evidence_present", artifacts.synchronizationEvidencePresent)
                put("complete_video_session", artifacts.completeVideoSession)
            }
            putJsonObject("purge") {
                put("state", purgeRecord.state.name)
                put("operation_sequence", purgeRecord.operationSequence)
                putJsonArray("location_results") {
                    purgeRecord.locationResults.forEach { result ->
                        add(buildJsonObject {
                            put("location_id", result.location.name.lowercase())
                            put("terminal_result", result.terminalResult.name.lowercase())
                            putJsonObject("pre_inventory") {
                                put("entries_scanned", result.preInventory.entriesScanned)
                                put("owned_entries", result.preInventory.ownedEntries)
                                put("owned_files", result.preInventory.ownedFiles)
                                put("owned_bytes", result.preInventory.ownedBytes)
                            }
                            putJsonObject("post_scan") {
                                put("entries_scanned", result.postScan.entriesScanned)
                                put("owned_remaining", result.postScan.ownedRemaining)
                            }
                        })
                    }
                }
            }
            putJsonObject("tombstone") {
                put("sha256", tombstoneSha256)
                put("byte_length", tombstoneByteLength)
                put("readmission_refusal_code", readmissionRefusalCode)
            }
            putJsonArray("blocking_reasons") { blockingReasons.distinct().sorted().forEach(::add) }
            put("admissible", admissible)
        }
    }
}

private val SHA1 = Regex("^[0-9a-f]{40}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
