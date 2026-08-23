package com.calisthenics.diagnostic.live

import com.calisthenics.diagnostic.replay.isAuthorizedS25Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val LIVE_CAPTURE_EVIDENCE_SCHEMA_VERSION = "1.0.0"
const val LIVE_CAPTURE_RECORD_KIND = "live_capture_device_gate_observation"

const val LIVE_CAPTURE_BLOCK_UNAUTHORIZED_TARGET = "unauthorized_device_target"
const val LIVE_CAPTURE_BLOCK_MANIFEST_DEVICE_MISMATCH = "manifest_device_mismatch"
const val LIVE_CAPTURE_BLOCK_CAMERA_ID = "camera_id_not_zero"
const val LIVE_CAPTURE_BLOCK_TIMESTAMP_SOURCE = "timestamp_source_not_realtime"
const val LIVE_CAPTURE_BLOCK_RAW_DATASPACE = "raw_dataspace_not_admitted"
const val LIVE_CAPTURE_BLOCK_COLOUR_STANDARD = "colour_standard_not_bt601"
const val LIVE_CAPTURE_BLOCK_COLOUR_RANGE = "colour_range_not_full"
const val LIVE_CAPTURE_BLOCK_UNDERLYING_IMAGE = "underlying_image_bridge_unavailable"
const val LIVE_CAPTURE_BLOCK_PIXEL_FORMAT = "pixel_format_not_yuv_420_888"
const val LIVE_CAPTURE_BLOCK_RECEIVED = "camera_frames_not_received"
const val LIVE_CAPTURE_BLOCK_SUBMITTED = "inference_frames_not_submitted"
const val LIVE_CAPTURE_BLOCK_EMITTED = "inference_records_not_emitted"
const val LIVE_CAPTURE_BLOCK_CLASSIFICATION_ACCOUNTING = "delivered_callback_classification_gap"
const val LIVE_CAPTURE_BLOCK_CADENCE_DIAGNOSTICS = "camera_cadence_diagnostics_invalid"
const val LIVE_CAPTURE_BLOCK_VALIDATOR = "reference_validation_failed"
const val LIVE_CAPTURE_BLOCK_INSTRUMENTATION = "instrumentation_failed"
const val LIVE_CAPTURE_BLOCK_TIMEOUT = "live_capture_timeout"
const val LIVE_CAPTURE_BLOCK_STALL = "live_inference_stalled"
const val LIVE_CAPTURE_BLOCK_FAILURE = "live_capture_failed"
const val LIVE_CAPTURE_BLOCK_CAMERA_CAPABILITY = "camera_capability_not_admitted"
const val LIVE_CAPTURE_BLOCK_PERMISSION = "camera_permission_denied"
const val LIVE_CAPTURE_BLOCK_INITIALIZATION = "live_initialization_failed"

private const val ADMITTED_CAMERA_ID = "0"
private const val ADMITTED_TIMESTAMP_SOURCE = "REALTIME"
private const val ADMITTED_DATASPACE = 146931712
private const val ADMITTED_STANDARD = "BT601"
private const val ADMITTED_RANGE = "FULL"
private const val ADMITTED_PIXEL_FORMAT = "YUV_420_888"

data class LiveCaptureEnvironment(
    val deviceClass: String,
    val deviceIdentity: String,
    val deviceFingerprint: String,
    val apiLevel: Int,
    val androidRelease: String,
    val abi: String,
    val manufacturer: String,
    val model: String,
    val marketingName: String,
    val chipset: String,
)

data class LiveCaptureManifestDevice(
    val platform: String,
    val osVersion: String,
    val deviceModel: String,
    val chipset: String?,
)

data class LiveCaptureCameraObservation(
    val cameraId: String,
    val timestampSource: String,
    val sourceDataspaceRaw: Int?,
    val sourceColorStandard: String?,
    val sourceColorRange: String?,
    val underlyingImageAvailable: Boolean,
    val pixelFormat: String,
)

data class LiveCaptureCounts(
    val cameraFramesReceived: Long,
    val inferenceFramesSubmitted: Long,
    val inferenceRecordsEmitted: Long,
    /**
     * Identity check for delivered analyzer callbacks only. It deliberately excludes the
     * requested-versus-delivered upstream cadence estimate.
     */
    val deliveredCallbackClassificationGap: Long,
    val cadenceDiagnostics: LiveCaptureCadenceDiagnostics,
    val inferenceFailures: Long = 0L,
    val framesSkippedBeforeInference: Long = 0L,
    val framesSkippedBeforeInferenceByReason: Map<String, Long> = emptyMap(),
)

data class LiveCaptureFailureIdentity(
    val submissionIndex: Long,
    val failureSource: String,
    val classification: String,
    val sourceErrorCode: String?,
)

data class LiveCaptureEvidence(
    val emittedBy: String,
    val sessionId: String,
    val environment: LiveCaptureEnvironment,
    val manifestDevice: LiveCaptureManifestDevice,
    val camera: LiveCaptureCameraObservation,
    val counts: LiveCaptureCounts,
    val referenceValidationSucceeded: Boolean,
    val instrumentationSucceeded: Boolean,
    val runFailureCode: String? = null,
    val runFailureDetail: String? = null,
    val inferenceFailureIdentities: List<LiveCaptureFailureIdentity> = emptyList(),
)

private val ABSOLUTE_PATH = Regex("""(?:file://)?(?:/|[A-Za-z]:[\\/])\S+""")

/** Keeps device evidence single-line, bounded, and free of local paths or stack-trace text. */
fun sanitizeLiveCaptureFailureDetail(detail: String?): String? {
    val firstLine = detail?.lineSequence()?.firstOrNull()?.trim()?.take(256) ?: return null
    if (firstLine.isEmpty()) return null
    return firstLine.replace(ABSOLUTE_PATH, "<redacted-path>")
}

fun isAuthorizedLiveCaptureTarget(environment: LiveCaptureEnvironment): Boolean =
    isAuthorizedS25Target(
        deviceClass = environment.deviceClass,
        manufacturer = environment.manufacturer,
        model = environment.model,
        marketingName = environment.marketingName,
    )

fun liveCaptureBlockingReasons(evidence: LiveCaptureEvidence): List<String> {
    val reasons = mutableListOf<String>()
    if (!isAuthorizedLiveCaptureTarget(evidence.environment)) reasons += LIVE_CAPTURE_BLOCK_UNAUTHORIZED_TARGET
    val manifest = evidence.manifestDevice
    val environment = evidence.environment
    if (manifest.platform != "android" ||
        manifest.osVersion != environment.androidRelease ||
        manifest.deviceModel != environment.model ||
        manifest.chipset != environment.chipset
    ) {
        reasons += LIVE_CAPTURE_BLOCK_MANIFEST_DEVICE_MISMATCH
    }
    val camera = evidence.camera
    if (camera.cameraId != ADMITTED_CAMERA_ID) reasons += LIVE_CAPTURE_BLOCK_CAMERA_ID
    if (camera.timestampSource != ADMITTED_TIMESTAMP_SOURCE) reasons += LIVE_CAPTURE_BLOCK_TIMESTAMP_SOURCE
    if (camera.sourceDataspaceRaw != ADMITTED_DATASPACE) reasons += LIVE_CAPTURE_BLOCK_RAW_DATASPACE
    if (camera.sourceColorStandard != ADMITTED_STANDARD) reasons += LIVE_CAPTURE_BLOCK_COLOUR_STANDARD
    if (camera.sourceColorRange != ADMITTED_RANGE) reasons += LIVE_CAPTURE_BLOCK_COLOUR_RANGE
    if (!camera.underlyingImageAvailable) reasons += LIVE_CAPTURE_BLOCK_UNDERLYING_IMAGE
    if (camera.pixelFormat != ADMITTED_PIXEL_FORMAT) reasons += LIVE_CAPTURE_BLOCK_PIXEL_FORMAT
    val counts = evidence.counts
    if (counts.cameraFramesReceived <= 0L) reasons += LIVE_CAPTURE_BLOCK_RECEIVED
    if (counts.inferenceFramesSubmitted <= 0L) reasons += LIVE_CAPTURE_BLOCK_SUBMITTED
    if (counts.inferenceRecordsEmitted <= 0L) reasons += LIVE_CAPTURE_BLOCK_EMITTED
    if (counts.deliveredCallbackClassificationGap != 0L) {
        reasons += LIVE_CAPTURE_BLOCK_CLASSIFICATION_ACCOUNTING
    }
    if (!counts.cadenceDiagnostics.isInternallyConsistentWith(counts)) {
        reasons += LIVE_CAPTURE_BLOCK_CADENCE_DIAGNOSTICS
    }
    if (!evidence.referenceValidationSucceeded) reasons += LIVE_CAPTURE_BLOCK_VALIDATOR
    when (evidence.runFailureCode) {
        LIVE_CAPTURE_BLOCK_TIMEOUT -> reasons += LIVE_CAPTURE_BLOCK_TIMEOUT
        LIVE_CAPTURE_BLOCK_STALL -> reasons += LIVE_CAPTURE_BLOCK_STALL
        LIVE_CAPTURE_BLOCK_FAILURE -> reasons += LIVE_CAPTURE_BLOCK_FAILURE
        LIVE_CAPTURE_BLOCK_CAMERA_CAPABILITY -> reasons += LIVE_CAPTURE_BLOCK_CAMERA_CAPABILITY
        LIVE_CAPTURE_BLOCK_PERMISSION -> reasons += LIVE_CAPTURE_BLOCK_PERMISSION
        LIVE_CAPTURE_BLOCK_INITIALIZATION -> reasons += LIVE_CAPTURE_BLOCK_INITIALIZATION
    }
    if (!evidence.instrumentationSucceeded) reasons += LIVE_CAPTURE_BLOCK_INSTRUMENTATION
    return reasons
}

fun liveCaptureIsAdmitted(evidence: LiveCaptureEvidence): Boolean = liveCaptureBlockingReasons(evidence).isEmpty()

fun renderLiveCaptureEvidence(evidence: LiveCaptureEvidence): String {
    val blockingReasons = liveCaptureBlockingReasons(evidence)
    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", LIVE_CAPTURE_EVIDENCE_SCHEMA_VERSION)
        put("record_kind", LIVE_CAPTURE_RECORD_KIND)
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("emitted_by", evidence.emittedBy)
        put("session_id", evidence.sessionId)
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
            put("chipset", evidence.environment.chipset)
        }
        putJsonObject("manifest_device") {
            put("platform", evidence.manifestDevice.platform)
            put("os_version", evidence.manifestDevice.osVersion)
            put("device_model", evidence.manifestDevice.deviceModel)
            put("chipset", evidence.manifestDevice.chipset)
        }
        putJsonObject("camera") {
            put("camera_id", evidence.camera.cameraId)
            put("timestamp_source", evidence.camera.timestampSource)
            put("source_dataspace_raw", evidence.camera.sourceDataspaceRaw)
            put("source_color_standard", evidence.camera.sourceColorStandard)
            put("source_color_range", evidence.camera.sourceColorRange)
            put("underlying_image_available", evidence.camera.underlyingImageAvailable)
            put("pixel_format", evidence.camera.pixelFormat)
        }
        putJsonObject("counts") {
            put("camera_frames_received", evidence.counts.cameraFramesReceived)
            put("inference_frames_submitted", evidence.counts.inferenceFramesSubmitted)
            put("inference_records_emitted", evidence.counts.inferenceRecordsEmitted)
            put("delivered_callback_classification_gap", evidence.counts.deliveredCallbackClassificationGap)
            put("cadence_diagnostics", evidence.counts.cadenceDiagnostics.asJson())
            put("inference_failures", evidence.counts.inferenceFailures)
            put("frames_skipped_before_inference", evidence.counts.framesSkippedBeforeInference)
            putJsonObject("frames_skipped_before_inference_by_reason") {
                evidence.counts.framesSkippedBeforeInferenceByReason.entries.sortedBy { it.key }.forEach { entry ->
                    val reason = entry.key
                    val count = entry.value
                    put(reason, count)
                }
            }
        }
        putJsonArray("inference_failure_identities") {
            evidence.inferenceFailureIdentities.forEach { failure ->
                add(
                    buildJsonObject {
                        put("submission_index", failure.submissionIndex)
                        put("failure_source", failure.failureSource)
                        put("classification", failure.classification)
                        put("source_error_code", failure.sourceErrorCode)
                    },
                )
            }
        }
        put("reference_validation_succeeded", evidence.referenceValidationSucceeded)
        put("instrumentation_succeeded", evidence.instrumentationSucceeded)
        put("run_failure_code", evidence.runFailureCode)
        put("run_failure_detail", evidence.runFailureDetail)
        putJsonArray("blocking_reasons") { blockingReasons.forEach(::add) }
        put("satisfies_s25_live_capture", blockingReasons.isEmpty())
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}
