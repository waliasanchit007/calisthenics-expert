package com.calisthenics.diagnostic.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Schema version of the emitted detection-tier replay observation record. */
const val DETECTION_REPLAY_EVIDENCE_SCHEMA_VERSION: String = "1.0.0"

/** Discriminator so a reader can never mistake this record for a session export or a probe result. */
const val DETECTION_REPLAY_RECORD_KIND: String = "detection_tier_replay_observation"

/**
 * The device the observation was made on. Derived from platform build markers by the caller, never
 * declared: an emulator observation and a physical-device observation are different observations,
 * and design section 7 treats a divergence between them as a finding rather than a discrepancy to
 * reconcile.
 */
data class DetectionReplayEnvironment(
    val deviceClass: String,
    val deviceIdentity: String,
    val deviceFingerprint: String,
    val apiLevel: Int,
    val androidRelease: String,
    val abi: String,
)

/**
 * The pinned inputs the run actually constructed its backend from, read at the construction site
 * rather than copied off the manifest. This is what binds the observation to one exact model and
 * one exact Tasks Vision release.
 */
data class DetectionReplayProvenanceBinding(
    val modelAssetName: String,
    val modelSha256: String,
    val tasksVisionVersion: String,
    val runningMode: String,
    val delegate: String,
    val numPoses: Int,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
)

/**
 * The exact pixels the run replayed, identified by the fixture's declared per-frame digests. An
 * observation that cannot name the frames it saw is not evidence about any particular render.
 */
data class DetectionReplayFixtureBinding(
    val fixtureId: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameIndices: List<Long>,
    val frameSha256: List<String>,
)

/** How the run was driven, and the canonical preprocessed input it actually submitted. */
data class DetectionReplayRunBinding(
    val runsPerInvocation: Int,
    val freshPoseLandmarkerPerRun: Boolean,
    val backendTimestampsMs: List<Long>,
    val canonicalInputSha256: List<String>,
)

/**
 * One complete sanitized detection-tier replay observation.
 *
 * Sanitized is a structural property here, not a filtering step: the record carries digests,
 * cardinality, presence structure, environment, and provenance. There is nowhere to put a landmark
 * coordinate, so none can leak. Coordinate values are check 6 observations and stay on the device.
 */
data class DetectionReplayEvidence(
    val emittedBy: String,
    val environment: DetectionReplayEnvironment,
    val provenance: DetectionReplayProvenanceBinding,
    val fixture: DetectionReplayFixtureBinding,
    val run: DetectionReplayRunBinding,
    val topology: List<FrameTopology>,
    val landmarkObservationSha256: List<String>,
)

/**
 * Renders [evidence] as one deterministic line of JSON.
 *
 * `make verify-harness-detection-replay` refuses to pass unless the instrumented run emits exactly
 * one of these, so the shape is a contract between the device test and the host checker. Rendering
 * lives here, in `commonMain`, so that contract is pinned by host tests instead of being assembled
 * inside an instrumented test no host verification can reach.
 *
 * The cardinality guards below are not defensive noise. A record whose topology or digest list
 * covers fewer frames than the fixture declares would present a partial run as a complete one, and
 * that is precisely the vacuity the wrapper exists to reject.
 */
fun renderDetectionReplayEvidence(evidence: DetectionReplayEvidence): String {
    val frameCount = evidence.fixture.frameIndices.size
    require(evidence.fixture.frameSha256.size == frameCount) {
        "fixture declares $frameCount frames but ${evidence.fixture.frameSha256.size} digests"
    }
    require(evidence.topology.size == frameCount) {
        "fixture declares $frameCount frames but the run reports ${evidence.topology.size}"
    }
    require(evidence.landmarkObservationSha256.size == frameCount) {
        "fixture declares $frameCount frames but " +
            "${evidence.landmarkObservationSha256.size} landmark observation digests were recorded"
    }
    require(evidence.topology.map { it.frameIndex } == evidence.fixture.frameIndices) {
        "the observed frame indices ${evidence.topology.map { it.frameIndex }} are not the " +
            "fixture's declared frames ${evidence.fixture.frameIndices}"
    }
    require(evidence.run.backendTimestampsMs.size == frameCount) {
        "fixture declares $frameCount frames but ${evidence.run.backendTimestampsMs.size} " +
            "backend timestamps were submitted"
    }
    require(evidence.run.canonicalInputSha256.size == frameCount) {
        "fixture declares $frameCount frames but ${evidence.run.canonicalInputSha256.size} " +
            "canonical inputs were submitted"
    }

    return Json.encodeToString(JsonObject.serializer(), buildRecord(evidence, frameCount))
}

private fun buildRecord(evidence: DetectionReplayEvidence, frameCount: Int): JsonObject =
    buildJsonObject {
        put("artifact_schema_version", DETECTION_REPLAY_EVIDENCE_SCHEMA_VERSION)
        put("record_kind", DETECTION_REPLAY_RECORD_KIND)
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("emitted_by", evidence.emittedBy)
        putJsonObject("environment") {
            put("device_class", evidence.environment.deviceClass)
            put("device_identity", evidence.environment.deviceIdentity)
            put("device_fingerprint", evidence.environment.deviceFingerprint)
            put("api_level", evidence.environment.apiLevel)
            put("android_release", evidence.environment.androidRelease)
            put("abi", evidence.environment.abi)
        }
        putJsonObject("provenance") {
            put("model_asset_name", evidence.provenance.modelAssetName)
            put("model_sha256", evidence.provenance.modelSha256)
            put("tasks_vision_version", evidence.provenance.tasksVisionVersion)
            put("running_mode", evidence.provenance.runningMode)
            put("delegate", evidence.provenance.delegate)
            put("num_poses", evidence.provenance.numPoses)
            put("model_input_width", evidence.provenance.modelInputWidth)
            put("model_input_height", evidence.provenance.modelInputHeight)
        }
        putJsonObject("fixture") {
            put("fixture_id", evidence.fixture.fixtureId)
            put("frame_width", evidence.fixture.frameWidth)
            put("frame_height", evidence.fixture.frameHeight)
            put("frame_count", frameCount)
            putJsonArray("frame_indices") { evidence.fixture.frameIndices.forEach { add(it) } }
            putJsonArray("frame_sha256") { evidence.fixture.frameSha256.forEach { add(it) } }
        }
        putJsonObject("run") {
            put("runs_per_invocation", evidence.run.runsPerInvocation)
            put("fresh_pose_landmarker_per_run", evidence.run.freshPoseLandmarkerPerRun)
            putJsonArray("backend_timestamps_ms") { evidence.run.backendTimestampsMs.forEach { add(it) } }
            putJsonArray("canonical_input_sha256") { evidence.run.canonicalInputSha256.forEach { add(it) } }
        }
        putJsonObject("topology") {
            putJsonArray("pose_present_frames") {
                evidence.topology.filter { it.posePresent }.forEach { add(it.frameIndex) }
            }
            putJsonArray("pose_count_per_frame") { evidence.topology.forEach { add(it.poseCount) } }
            putJsonArray("normalized_landmark_count_per_frame") {
                evidence.topology.forEach { add(it.normalizedLandmarkCount) }
            }
            putJsonArray("world_landmark_count_per_frame") {
                evidence.topology.forEach { add(it.worldLandmarkCount) }
            }
            put(
                "presence_pattern_fully_populated",
                evidence.topology.all { frame ->
                    frame.presencePattern.isNotEmpty() && frame.presencePattern.all { it }
                },
            )
        }
        putJsonArray("landmark_observation_sha256") {
            evidence.landmarkObservationSha256.forEach { add(it) }
        }
        // This record never satisfies the five-run Samsung Galaxy S25 Ultra device gate, on any
        // device. That gate is a separate observation about process-kill durability; nothing about
        // detection firing contributes to it, so the field is a constant rather than a derivation
        // someone could later persuade to be true.
        put("satisfies_s25_device_gate", false)
    }
