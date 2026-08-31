package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.replay.isAuthorizedS25Target
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val CONTAINER_TIMESTAMP_EVIDENCE_SCHEMA_VERSION = "1.0.0"
const val CONTAINER_TIMESTAMP_RECORD_KIND = "container_timestamp_probe_device_gate_observation"
const val CONTAINER_TIMESTAMP_BLOCK_EMPTY_INPUT = "submitted_pts_empty"
const val CONTAINER_TIMESTAMP_BLOCK_ZERO_PTS = "submitted_pts_not_strictly_positive"
const val CONTAINER_TIMESTAMP_BLOCK_NEGATIVE_DEMUX_PTS = "demuxed_pts_negative"
const val CONTAINER_TIMESTAMP_BLOCK_COUNT_MISMATCH = "submitted_demuxed_count_mismatch"
const val CONTAINER_TIMESTAMP_BLOCK_SUBMITTED_ORDER = "submitted_pts_not_strictly_increasing"
const val CONTAINER_TIMESTAMP_BLOCK_DEMUX_ORDER = "demuxed_pts_not_strictly_increasing"
const val CONTAINER_TIMESTAMP_BLOCK_NON_CONSTANT_TRANSFORM = "container_timestamp_transform_non_constant"
const val CONTAINER_TIMESTAMP_BLOCK_UNAUTHORIZED_TARGET = "unauthorized_device_target"
const val CONTAINER_TIMESTAMP_BLOCK_PROVENANCE = "invalid_probe_git_sha"

data class ContainerTimestampEnvironment(
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

data class ContainerTimestampProvenance(
    val harnessGitSha: String,
    val container: String,
    val codec: String,
)

data class ContainerTimestampObservation(
    val submittedPtsUs: List<Long>,
    val demuxedPtsUs: List<Long>,
)

data class ContainerTimestampEvidence(
    val emittedBy: String,
    val environment: ContainerTimestampEnvironment,
    val provenance: ContainerTimestampProvenance,
    val observation: ContainerTimestampObservation,
)

fun deriveContainerOffsetUs(observation: ContainerTimestampObservation): Long? {
    if (observation.submittedPtsUs.isEmpty() ||
        observation.submittedPtsUs.size != observation.demuxedPtsUs.size
    ) {
        return null
    }
    return observation.submittedPtsUs
        .zip(observation.demuxedPtsUs) { submitted, demuxed -> submitted - demuxed }
        .distinct()
        .singleOrNull()
}

fun containerTimestampBlockingReasons(evidence: ContainerTimestampEvidence): List<String> = buildList {
    val observation = evidence.observation
    if (observation.submittedPtsUs.isEmpty()) add(CONTAINER_TIMESTAMP_BLOCK_EMPTY_INPUT)
    if (observation.submittedPtsUs.any { it <= 0L }) add(CONTAINER_TIMESTAMP_BLOCK_ZERO_PTS)
    if (observation.demuxedPtsUs.any { it < 0L }) add(CONTAINER_TIMESTAMP_BLOCK_NEGATIVE_DEMUX_PTS)
    if (!observation.submittedPtsUs.zipWithNext().all { (before, after) -> before < after }) {
        add(CONTAINER_TIMESTAMP_BLOCK_SUBMITTED_ORDER)
    }
    if (!observation.demuxedPtsUs.zipWithNext().all { (before, after) -> before < after }) {
        add(CONTAINER_TIMESTAMP_BLOCK_DEMUX_ORDER)
    }
    if (observation.submittedPtsUs.size != observation.demuxedPtsUs.size) {
        add(CONTAINER_TIMESTAMP_BLOCK_COUNT_MISMATCH)
    } else if (observation.submittedPtsUs.isNotEmpty() && deriveContainerOffsetUs(observation) == null) {
        add(CONTAINER_TIMESTAMP_BLOCK_NON_CONSTANT_TRANSFORM)
    }
    if (!isAuthorizedS25Target(
            deviceClass = evidence.environment.deviceClass,
            manufacturer = evidence.environment.manufacturer,
            model = evidence.environment.model,
            marketingName = evidence.environment.marketingName,
        )
    ) {
        add(CONTAINER_TIMESTAMP_BLOCK_UNAUTHORIZED_TARGET)
    }
    if (!Regex("[0-9a-f]{40}").matches(evidence.provenance.harnessGitSha)) {
        add(CONTAINER_TIMESTAMP_BLOCK_PROVENANCE)
    }
}

fun renderContainerTimestampEvidence(evidence: ContainerTimestampEvidence): String {
    val reasons = containerTimestampBlockingReasons(evidence)
    val offset = deriveContainerOffsetUs(evidence.observation)
    return buildJsonObject {
        put("artifact_schema_version", CONTAINER_TIMESTAMP_EVIDENCE_SCHEMA_VERSION)
        put("record_kind", CONTAINER_TIMESTAMP_RECORD_KIND)
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("non_participant_observation", true)
        put("observation_subject", "device_container_timestamp_transform")
        put("emitted_by", evidence.emittedBy)
        putJsonObject("environment") {
            put("device_class", evidence.environment.deviceClass)
            put("device_identity", evidence.environment.deviceIdentity)
            put("device_fingerprint", evidence.environment.deviceFingerprint)
            put("api_level", evidence.environment.apiLevel)
            put("android_release", evidence.environment.androidRelease)
            put("abi", evidence.environment.abi)
            put("manufacturer", evidence.environment.manufacturer)
            put("model", evidence.environment.model)
            put("marketing_name", evidence.environment.marketingName)
        }
        putJsonObject("provenance") {
            put("harness_git_sha", evidence.provenance.harnessGitSha)
            put("container", evidence.provenance.container)
            put("codec", evidence.provenance.codec)
        }
        putJsonObject("observation") {
            putJsonArray("submitted_pts_us") {
                evidence.observation.submittedPtsUs.forEach { add(it) }
            }
            putJsonArray("demuxed_pts_us") {
                evidence.observation.demuxedPtsUs.forEach { add(it) }
            }
            put("constant_offset_us", offset)
        }
        putJsonArray("blocking_reasons") { reasons.forEach { add(it) } }
        put("blocking_result", reasons.isNotEmpty())
    }.toString()
}
