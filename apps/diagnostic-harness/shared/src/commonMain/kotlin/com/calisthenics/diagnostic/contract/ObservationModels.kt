package com.calisthenics.diagnostic.contract

import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline

@JvmInline
value class SemanticVersion(val value: String) {
    init {
        require(SEMANTIC_VERSION.matches(value)) { "Invalid semantic version: $value" }
    }

    val major: Int
        get() = value.substringBefore('.').toInt()
}

@JvmInline
value class Uuid(val value: String) {
    init {
        require(UUID.matches(value)) { "Invalid UUID: $value" }
    }
}

@JvmInline
value class Sha256(val value: String) {
    init {
        require(SHA256.matches(value)) { "Invalid SHA-256: $value" }
    }
}

data class SessionManifest(
    val schemaVersion: SemanticVersion,
    val sessionId: Uuid,
    val sessionKind: String?,
    val participantPseudonymId: String,
    val captureProtocolVersion: SemanticVersion,
    val harnessGitSha: String,
    val device: Device,
    val posePipeline: PosePipeline,
    val camera: Camera,
    val modelInput: ModelInput,
    val inferenceDrainTimeoutMs: Long,
    val cadence: Cadence,
    val clock: Clock,
    val availableObservations: AvailableObservations,
    val mediaReferences: List<MediaReference>,
    val counts: Counts,
    val machineInferenceFailures: List<MachineInferenceFailure>,
    val captureQualityObserved: CaptureQualityObserved,
    val studyContext: Map<String, JsonElement>,
    val rawPoseStream: ArtifactReference? = null,
    val synchronizationEvidence: ArtifactReference? = null,
    val mediaRequired: Boolean? = null,
)

data class ArtifactReference(
    val pathRef: String,
    val sha256: Sha256,
    val schemaVersion: SemanticVersion,
)

data class RawPoseFrame(
    val schemaVersion: SemanticVersion,
    val sessionId: Uuid,
    val frameIndex: Long,
    val captureTimestampNs: Long,
    val backendTimestampMs: Long?,
    val inferenceStartNs: Long,
    val inferenceEndNs: Long,
    val posePresent: Boolean,
    val normalizedImageLandmarks: List<Landmark>?,
    val worldLandmarks: List<Landmark>?,
    val imageWidth: Int,
    val imageHeight: Int,
    val framesSkippedSinceLastEmittedRecord: Long,
)

data class Landmark(
    val index: Int,
    val name: String?,
    val x: Double,
    val y: Double,
    val z: Double,
    val visibility: Double?,
    val presence: Double?,
)

data class Device(
    val platform: String,
    val osVersion: String,
    val deviceModel: String,
    val chipset: String?,
    val thermalApiAvailable: Boolean,
)

data class PosePipeline(
    val backendName: String,
    val backendVersion: String,
    val modelAssetName: String,
    val modelAssetSha256: Sha256,
    val delegate: String,
    val runningMode: String,
    val numPoses: Int,
    val minPoseDetectionConfidence: Double,
    val minPosePresenceConfidence: Double,
    val minTrackingConfidence: Double,
    val landmarkTopology: String,
)

data class Camera(
    val facing: String,
    val captureWidth: Int,
    val captureHeight: Int,
    val orientationDegrees: Int,
    val mirrored: Boolean,
    val requestedCameraFps: Double,
    val sourcePixelFormat: String?,
    val sourceColorMatrix: String?,
    val sourceColorRange: String?,
    val sourceDataspaceRaw: Long?,
    val timestampSource: String?,
    val deliverySemantics: String?,
)

data class ModelInput(
    val modelInputWidth: Int,
    val modelInputHeight: Int,
    val livePreprocessingContractVersion: SemanticVersion,
    val fixturePreprocessingContractVersion: SemanticVersion?,
)

data class Cadence(
    val configuredInferenceFps: Double,
    val achievedInferenceFpsTimeseries: List<CadenceSample>,
    val trackerUpdateCadenceHz: Double?,
)

data class CadenceSample(
    val tMonotonicNs: Long,
    val achievedInferenceFps: Double,
)

data class Clock(
    val monotonicTimeBase: String,
    val t0MonotonicNs: Long,
    val startedAtUtc: String,
    val endedAtUtc: String,
)

data class AvailableObservations(
    val normalizedImageLandmarksAvailable: Boolean,
    val worldLandmarksAvailable: Boolean,
)

data class MediaReference(
    val role: String,
    val pathRef: String,
    val sha256: Sha256,
    val syncOffsetNs: Long?,
    val syncDriftNsPerS: Double?,
)

data class Counts(
    val cameraFramesReceived: Long,
    val inferenceFramesSubmitted: Long,
    val inferenceRecordsEmitted: Long,
    val poseDetectedRecords: Long,
    val framesSkippedBeforeInference: Long,
    val framesSkippedBeforeInferenceByReason: Map<String, Long>,
    val trailingFramesSkippedAfterLastEmittedRecord: Long,
    val inferenceFailures: Long,
)

data class MachineInferenceFailure(
    val submissionIndex: Long,
    val captureTimestampNs: Long,
    val failureSource: String,
    val classification: String,
    val sourceErrorCode: String?,
    val detail: String?,
)

data class CaptureQualityObserved(
    val fullBodyVisibleAtHang: Boolean,
    val requiredJointsVisible: Boolean,
    val subjectScaleAcceptable: Boolean,
    val lightingClass: String,
    val visibleOcclusion: Boolean,
    val setupFailureLog: String?,
)

private val SEMANTIC_VERSION = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
