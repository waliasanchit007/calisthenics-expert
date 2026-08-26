package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.export.StorageLocationRegistry
import com.calisthenics.diagnostic.export.StorageOwner
import com.calisthenics.diagnostic.replay.isAuthorizedS25Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class StorageExclusionEnvironment(
    val deviceClass: String,
    val deviceIdentity: String,
    val deviceFingerprint: String,
    val apiLevel: Int,
    val androidRelease: String,
    val abi: String,
    val manufacturer: String,
    val model: String,
    val marketingName: String,
)

/** Raw observations only; callers cannot declare whether the gate passed. */
data class StorageExclusionObservation(
    val allowBackupDisabled: Boolean,
    val dataExtractionRulesResource: String?,
    val cloudBackupExcludedParent: String?,
    val deviceTransferExcludedParent: String?,
    val registeredAndroidChildren: List<String>,
    val observedTopLevelChildren: List<String>,
)

data class StorageExclusionEvidence(
    val emittedBy: String,
    val harnessGitSha: String,
    val environment: StorageExclusionEnvironment,
    val observation: StorageExclusionObservation,
) {
    init {
        require(Regex("^[0-9a-f]{40}$").matches(harnessGitSha)) {
            "harnessGitSha must be a lowercase 40-hex commit SHA"
        }
    }
}

private const val EXTRACTION_RULES_RESOURCE = "@xml/data_extraction_rules"
private const val EXCLUDED_PARENT = "diagnostic-harness/"

private fun expectedAndroidChildren(): List<String> =
    StorageLocationRegistry.entries
        .filter { it.owner == StorageOwner.ANDROID_APP }
        .map { it.relativeRoot }
        .sorted()

fun storageExclusionBlockingReasons(evidence: StorageExclusionEvidence): List<String> = buildList {
    val observation = evidence.observation
    if (!observation.allowBackupDisabled) add("backup_enabled")
    if (observation.dataExtractionRulesResource != EXTRACTION_RULES_RESOURCE) {
        add("data_extraction_rules_missing")
    }
    if (observation.cloudBackupExcludedParent != EXCLUDED_PARENT) {
        add("cloud_backup_parent_not_excluded")
    }
    if (observation.deviceTransferExcludedParent != EXCLUDED_PARENT) {
        add("device_transfer_parent_not_excluded")
    }
    val expected = expectedAndroidChildren()
    if (observation.registeredAndroidChildren.sorted() != expected) {
        add("registry_projection_mismatch")
    }
    if (observation.observedTopLevelChildren.sorted() != expected) {
        add("unregistered_top_level_child")
    }
    if (!isAuthorizedS25Target(
            deviceClass = evidence.environment.deviceClass,
            manufacturer = evidence.environment.manufacturer,
            model = evidence.environment.model,
            marketingName = evidence.environment.marketingName,
        )
    ) {
        add("unauthorized_device_target")
    }
}

fun renderStorageExclusionEvidence(evidence: StorageExclusionEvidence): String {
    val reasons = storageExclusionBlockingReasons(evidence)
    val environment = evidence.environment
    val observation = evidence.observation
    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", "1.0.0")
        put("record_kind", "storage_exclusion_device_gate_observation")
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("non_participant_observation", true)
        put("emitted_by", evidence.emittedBy)
        put("harness_git_sha", evidence.harnessGitSha)
        putJsonObject("environment") {
            put("device_class", environment.deviceClass)
            put("device_identity", environment.deviceIdentity)
            put("device_fingerprint", environment.deviceFingerprint)
            put("api_level", environment.apiLevel)
            put("android_release", environment.androidRelease)
            put("abi", environment.abi)
            put("manufacturer", environment.manufacturer)
            put("model", environment.model)
            put("marketing_name", environment.marketingName)
        }
        putJsonObject("observation") {
            put("allow_backup_disabled", observation.allowBackupDisabled)
            put("data_extraction_rules_resource", observation.dataExtractionRulesResource)
            put("cloud_backup_excluded_parent", observation.cloudBackupExcludedParent)
            put("device_transfer_excluded_parent", observation.deviceTransferExcludedParent)
            putJsonArray("registered_android_children") {
                observation.registeredAndroidChildren.sorted().forEach(::add)
            }
            putJsonArray("observed_top_level_children") {
                observation.observedTopLevelChildren.sorted().forEach(::add)
            }
        }
        putJsonArray("blocking_reasons") { reasons.forEach(::add) }
        put("satisfies_s25_storage_exclusion_gate", reasons.isEmpty())
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}
