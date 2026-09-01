package com.calisthenics.diagnostic.study

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val SYNCHRONIZATION_EVIDENCE_SCHEMA_VERSION = "1.0.0"
const val BOUNDED_SYNCHRONIZATION_EVIDENCE_SCHEMA_VERSION = "1.1.0"

/**
 * The legacy exact camera-capture-to-container mapping. It remains immutable while the additive
 * bounded profile covers finite-timescale container quantization.
 */
const val SYNCHRONIZATION_MAPPING_METHOD = "camera_capture_to_container_pts_constant_offset_v1"
const val BOUNDED_SYNCHRONIZATION_MAPPING_METHOD =
    "camera_capture_to_container_pts_bounded_quantization_v1"
const val BOUNDED_SYNCHRONIZATION_PROFILE = "bounded_quantization"

const val SYNC_BLOCK_NO_SAMPLES = "synchronization_samples_empty"
const val SYNC_BLOCK_NON_CONSTANT_OFFSET = "container_timestamp_transform_non_constant"
const val SYNC_BLOCK_PTS_DERIVATION = "submitted_pts_not_derived_from_rebased_capture"
const val SYNC_BLOCK_VIDEO_PTS_MISMATCH = "video_pts_not_equal_to_demuxed_pts"
const val SYNC_BLOCK_DUPLICATE_PTS = "submitted_pts_not_unique"
const val SYNC_BLOCK_COUNT_MISMATCH = "encoded_video_frames_not_equal_to_sample_count"
const val SYNC_BLOCK_CALLBACK_ACCOUNTING = "video_callback_accounting_mismatch"
const val SYNC_BLOCK_PROBE_CODEC_MISMATCH = "probe_codec_mismatch"
const val SYNC_BLOCK_PROBE_CONTAINER_MISMATCH = "probe_container_mismatch"
const val SYNC_BLOCK_TIMEBASE_INVALID = "container_track_timebase_invalid"

enum class SynchronizationProfile {
    EXACT,
    BOUNDED_QUANTIZATION,
}

data class SynchronizationTimebases(
    val videoTicksPerSecond: Long,
    val metadataTicksPerSecond: Long,
)

data class BoundedSynchronizationOffset(
    val representativeNs: Long,
    val minimumNs: Long,
    val maximumNs: Long,
    val spanBoundNs: Long,
)

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
    val profile: SynchronizationProfile = SynchronizationProfile.EXACT,
    val timebases: SynchronizationTimebases? = null,
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

fun deriveQuantizationSpanBoundNs(timebases: SynchronizationTimebases): Long? {
    val ticksPerSecond = timebases.videoTicksPerSecond
    if (
        ticksPerSecond <= 0L ||
        timebases.metadataTicksPerSecond <= 0L ||
        ticksPerSecond != timebases.metadataTicksPerSecond
    ) {
        return null
    }
    val numerator = 2_000_000_000L
    val ceilTickPairNs = numerator / ticksPerSecond +
        if (numerator % ticksPerSecond == 0L) 0L else 1L
    return ceilTickPairNs + 2_000L
}

fun deriveBoundedSynchronizationOffset(
    samples: List<SynchronizationSample>,
    timebases: SynchronizationTimebases,
): BoundedSynchronizationOffset? {
    if (samples.isEmpty()) return null
    val spanBoundNs = deriveQuantizationSpanBoundNs(timebases) ?: return null
    val offsets = samples.map { (it.submittedPtsUs - it.demuxedPtsUs) * 1_000L }.sorted()
    return BoundedSynchronizationOffset(
        representativeNs = offsets[(offsets.size - 1) / 2],
        minimumNs = offsets.first(),
        maximumNs = offsets.last(),
        spanBoundNs = spanBoundNs,
    )
}

fun deriveSynchronizationRepresentativeOffsetNs(evidence: SynchronizationEvidence): Long? =
    when (evidence.profile) {
        SynchronizationProfile.EXACT -> deriveSynchronizationOffsetNs(evidence.samples)
        SynchronizationProfile.BOUNDED_QUANTIZATION -> evidence.timebases?.let { timebases ->
            deriveBoundedSynchronizationOffset(evidence.samples, timebases)?.representativeNs
        }
    }

/** Include callbacks rejected before video-writer admission in the released drop identity. */
fun deriveTotalDroppedBeforeEncoder(
    deliveredCameraCallbacks: Int,
    acceptedByVideoWriter: Int,
    writerDroppedBeforeEncoder: Int,
): Int? {
    val rejectedBeforeWriter = deliveredCameraCallbacks - acceptedByVideoWriter
    if (rejectedBeforeWriter < 0) return null
    return writerDroppedBeforeEncoder + rejectedBeforeWriter
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
        when (evidence.profile) {
            SynchronizationProfile.EXACT -> {
                if (deriveSynchronizationOffsetNs(samples) == null) {
                    add(SYNC_BLOCK_NON_CONSTANT_OFFSET)
                }
            }

            SynchronizationProfile.BOUNDED_QUANTIZATION -> {
                val bounded = evidence.timebases?.let { timebases ->
                    deriveBoundedSynchronizationOffset(samples, timebases)
                }
                if (bounded == null) {
                    add(SYNC_BLOCK_TIMEBASE_INVALID)
                } else if (bounded.maximumNs - bounded.minimumNs > bounded.spanBoundNs) {
                    add(SYNC_BLOCK_NON_CONSTANT_OFFSET)
                }
            }
        }
        if (evidence.videoPtsUs != samples.map { it.demuxedPtsUs }) {
            add(SYNC_BLOCK_VIDEO_PTS_MISMATCH)
        }
        if (evidence.counts.encodedVideoFrames != samples.size) {
            add(SYNC_BLOCK_COUNT_MISMATCH)
        }
    }
    if (evidence.counts.deliveredCameraCallbacks !=
        evidence.counts.encodedVideoFrames +
        evidence.counts.droppedBeforeEncoder +
        evidence.counts.encoderFailures
    ) {
        add(SYNC_BLOCK_CALLBACK_ACCOUNTING)
    }
    // Provenance: the admitted probe measured one specific codec in one specific container.
    // Inheriting its transform-class conclusion under a different encoder is not supported.
    if (evidence.codec != evidence.probeCodec) add(SYNC_BLOCK_PROBE_CODEC_MISMATCH)
    if (evidence.container != evidence.probeContainer) add(SYNC_BLOCK_PROBE_CONTAINER_MISMATCH)
}

fun renderSynchronizationEvidence(evidence: SynchronizationEvidence): String {
    val reasons = synchronizationBlockingReasons(evidence)
    val offsetNs = deriveSynchronizationRepresentativeOffsetNs(evidence)
    val bounded = if (evidence.profile == SynchronizationProfile.BOUNDED_QUANTIZATION) {
        evidence.timebases?.let { deriveBoundedSynchronizationOffset(evidence.samples, it) }
    } else {
        null
    }
    return buildJsonObject {
        put(
            "synchronization_evidence_schema_version",
            when (evidence.profile) {
                SynchronizationProfile.EXACT -> SYNCHRONIZATION_EVIDENCE_SCHEMA_VERSION
                SynchronizationProfile.BOUNDED_QUANTIZATION ->
                    BOUNDED_SYNCHRONIZATION_EVIDENCE_SCHEMA_VERSION
            },
        )
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
        if (evidence.profile == SynchronizationProfile.BOUNDED_QUANTIZATION) {
            put("sync_profile", BOUNDED_SYNCHRONIZATION_PROFILE)
        }
        put(
            "mapping_method",
            when (evidence.profile) {
                SynchronizationProfile.EXACT -> SYNCHRONIZATION_MAPPING_METHOD
                SynchronizationProfile.BOUNDED_QUANTIZATION ->
                    BOUNDED_SYNCHRONIZATION_MAPPING_METHOD
            },
        )
        put("container_offset_ns", offsetNs)
        put("container_drift_ns_per_s", 0)
        if (bounded != null && evidence.timebases != null) {
            putJsonObject("container_timebases") {
                put("video_ticks_per_second", evidence.timebases.videoTicksPerSecond)
                put("metadata_ticks_per_second", evidence.timebases.metadataTicksPerSecond)
            }
            putJsonObject("container_offset_interval_ns") {
                put("minimum", bounded.minimumNs)
                put("maximum", bounded.maximumNs)
            }
            put("container_quantization_span_bound_ns", bounded.spanBoundNs)
        }
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
        put("first_encoded_frame_pts_us", evidence.videoPtsUs.firstOrNull())
        put("last_encoded_frame_pts_us", evidence.videoPtsUs.lastOrNull())
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
