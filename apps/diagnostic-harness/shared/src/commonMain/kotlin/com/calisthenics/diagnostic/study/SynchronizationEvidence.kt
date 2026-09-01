package com.calisthenics.diagnostic.study

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val SYNCHRONIZATION_EVIDENCE_SCHEMA_VERSION = "1.0.0"

/**
 * The only admitted camera-capture-to-container mapping. Pinned by the released
 * `synchronization_evidence.v1` schema; the Python validator rejects anything else.
 */
const val SYNCHRONIZATION_MAPPING_METHOD = "camera_capture_to_container_pts_constant_offset_v1"

const val SYNC_BLOCK_NO_SAMPLES = "synchronization_samples_empty"
const val SYNC_BLOCK_NON_CONSTANT_OFFSET = "container_timestamp_transform_non_constant"
const val SYNC_BLOCK_PTS_DERIVATION = "submitted_pts_not_derived_from_rebased_capture"
const val SYNC_BLOCK_VIDEO_PTS_MISMATCH = "video_pts_not_equal_to_demuxed_pts"
const val SYNC_BLOCK_DUPLICATE_PTS = "submitted_pts_not_unique"
const val SYNC_BLOCK_COUNT_MISMATCH = "encoded_video_frames_not_equal_to_sample_count"
const val SYNC_BLOCK_PROBE_CODEC_MISMATCH = "probe_codec_mismatch"
const val SYNC_BLOCK_PROBE_CONTAINER_MISMATCH = "probe_container_mismatch"

/** One (submitted, demuxed) pair for a single encoded frame. */
data class SynchronizationSample(
    val frameIndex: Long,
    val rebasedCaptureTimestampNs: Long,
    val submittedPtsUs: Long,
    val demuxedPtsUs: Long,
)

/** Full pipeline accounting; every delivered callback must be explained by one of these. */
data class SynchronizationCounts(
    val deliveredCameraCallbacks: Int,
    val encodedVideoFrames: Int,
    val submittedInferenceFrames: Int,
    val emittedPoseRecords: Int,
    val droppedBeforeEncoder: Int,
    val encoderFailures: Int,
    val inferenceSkippedFrames: Int,
    val inferenceFailures: Int,
)

/** Identity of the artefacts this evidence binds together, each by digest. */
data class SynchronizationArtifacts(
    val videoRole: String,
    val videoPathRef: String,
    val videoSha256: String,
    val videoByteLength: Long,
    val rawPoseStreamPathRef: String,
    val rawPoseStreamSha256: String,
)

data class SynchronizationEvidence(
    val sessionId: String,
    val manifestSessionId: String,
    val artifacts: SynchronizationArtifacts,
    val container: String,
    val codec: String,
    val cameraTimestampDomain: String,
    val encoderTimestampDomain: String,
    val samples: List<SynchronizationSample>,
    val videoPtsUs: List<Long>,
    val counts: SynchronizationCounts,
    val firstCameraFrameTimestampNs: Long,
    val lastCameraFrameTimestampNs: Long,
    val firstPoseCaptureTimestampNs: Long,
    val lastPoseCaptureTimestampNs: Long,
    val producerGitSha: String,
    val deviceModel: String,
    val deviceApiLevel: Int,
    val deviceAbi: String,
    /** Container and codec recorded by the admitted Task 3 probe this run inherits. */
    val probeContainer: String,
    val probeCodec: String,
)

/**
 * Derive the one constant container offset in nanoseconds, or null when the transform is not
 * constant across the complete sequence.
 *
 * This deliberately delegates to Task 3's [deriveContainerOffsetUs] rather than repeating the
 * constancy rule. The released Python validator is the admission authority; a second independent
 * implementation here would be a second source of truth for the same invariant.
 */
fun deriveSynchronizationOffsetNs(samples: List<SynchronizationSample>): Long? {
    val offsetUs = deriveContainerOffsetUs(
        ContainerTimestampObservation(
            submittedPtsUs = samples.map { it.submittedPtsUs },
            demuxedPtsUs = samples.map { it.demuxedPtsUs },
        ),
    ) ?: return null
    return offsetUs * 1_000L
}

/**
 * Reasons this run must not be admitted as synchronized evidence.
 *
 * A blocking reason is an observation, not a failure to hide: the gate records it and exits
 * non-zero rather than narrowing the contract to produce a green result.
 */
fun synchronizationBlockingReasons(evidence: SynchronizationEvidence): List<String> = buildList {
    val samples = evidence.samples
    if (samples.isEmpty()) {
        add(SYNC_BLOCK_NO_SAMPLES)
    } else {
        if (samples.any { it.submittedPtsUs != it.rebasedCaptureTimestampNs / 1_000L }) {
            add(SYNC_BLOCK_PTS_DERIVATION)
        }
        if (samples.map { it.submittedPtsUs }.distinct().size != samples.size) {
            add(SYNC_BLOCK_DUPLICATE_PTS)
        }
        if (deriveSynchronizationOffsetNs(samples) == null) {
            add(SYNC_BLOCK_NON_CONSTANT_OFFSET)
        }
        if (evidence.videoPtsUs != samples.map { it.demuxedPtsUs }) {
            add(SYNC_BLOCK_VIDEO_PTS_MISMATCH)
        }
        if (evidence.counts.encodedVideoFrames != samples.size) {
            add(SYNC_BLOCK_COUNT_MISMATCH)
        }
    }
    // Provenance: the admitted probe measured one specific codec in one specific container.
    // Inheriting its transform-class conclusion under a different encoder is not supported.
    if (evidence.codec != evidence.probeCodec) add(SYNC_BLOCK_PROBE_CODEC_MISMATCH)
    if (evidence.container != evidence.probeContainer) add(SYNC_BLOCK_PROBE_CONTAINER_MISMATCH)
}

fun renderSynchronizationEvidence(evidence: SynchronizationEvidence): String {
    val reasons = synchronizationBlockingReasons(evidence)
    val offsetNs = deriveSynchronizationOffsetNs(evidence.samples)
    return buildJsonObject {
        put("synchronization_evidence_schema_version", SYNCHRONIZATION_EVIDENCE_SCHEMA_VERSION)
        put("session_id", evidence.sessionId)
        put("manifest_session_id", evidence.manifestSessionId)
        put("video_role", evidence.artifacts.videoRole)
        put("video_path_ref", evidence.artifacts.videoPathRef)
        put("video_sha256", evidence.artifacts.videoSha256)
        put("video_byte_length", evidence.artifacts.videoByteLength)
        put("raw_pose_stream_path_ref", evidence.artifacts.rawPoseStreamPathRef)
        put("raw_pose_stream_sha256", evidence.artifacts.rawPoseStreamSha256)
        put("container", evidence.container)
        put("codec", evidence.codec)
        put("camera_timestamp_domain", evidence.cameraTimestampDomain)
        put("encoder_timestamp_domain", evidence.encoderTimestampDomain)
        put("mapping_method", SYNCHRONIZATION_MAPPING_METHOD)
        put("container_offset_ns", offsetNs)
        put("container_drift_ns_per_s", 0)
        putJsonArray("video_pts_us") { evidence.videoPtsUs.forEach { add(it) } }
        putJsonArray("samples") {
            evidence.samples.forEach { sample ->
                add(
                    buildJsonObject {
                        put("frame_index", sample.frameIndex)
                        put("rebased_capture_timestamp_ns", sample.rebasedCaptureTimestampNs)
                        put("submitted_pts_us", sample.submittedPtsUs)
                        put("demuxed_pts_us", sample.demuxedPtsUs)
                    },
                )
            }
        }
        put("first_camera_frame_timestamp_ns", evidence.firstCameraFrameTimestampNs)
        put("last_camera_frame_timestamp_ns", evidence.lastCameraFrameTimestampNs)
        put("first_encoded_frame_pts_us", evidence.samples.firstOrNull()?.submittedPtsUs)
        put("last_encoded_frame_pts_us", evidence.samples.lastOrNull()?.submittedPtsUs)
        put("first_pose_capture_timestamp_ns", evidence.firstPoseCaptureTimestampNs)
        put("last_pose_capture_timestamp_ns", evidence.lastPoseCaptureTimestampNs)
        putJsonObject("counts") {
            put("delivered_camera_callbacks", evidence.counts.deliveredCameraCallbacks)
            put("encoded_video_frames", evidence.counts.encodedVideoFrames)
            put("submitted_inference_frames", evidence.counts.submittedInferenceFrames)
            put("emitted_pose_records", evidence.counts.emittedPoseRecords)
            put("dropped_before_encoder", evidence.counts.droppedBeforeEncoder)
            put("encoder_failures", evidence.counts.encoderFailures)
            put("inference_skipped_frames", evidence.counts.inferenceSkippedFrames)
            put("inference_failures", evidence.counts.inferenceFailures)
        }
        put("producer_git_sha", evidence.producerGitSha)
        put("device_model", evidence.deviceModel)
        put("device_api_level", evidence.deviceApiLevel)
        put("device_abi", evidence.deviceAbi)
        putJsonArray("blocking_reasons") { reasons.forEach { add(it) } }
        put("blocking_result", reasons.isNotEmpty())
    }.toString()
}
