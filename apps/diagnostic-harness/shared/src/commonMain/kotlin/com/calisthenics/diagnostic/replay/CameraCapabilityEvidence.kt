package com.calisthenics.diagnostic.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Schema version of the emitted camera-capability probe observation record. */
const val CAMERA_CAPABILITY_EVIDENCE_SCHEMA_VERSION: String = "1.0.0"

/** Discriminator so a reader can never mistake this for a replay, detection, or kill observation. */
const val CAMERA_CAPABILITY_RECORD_KIND: String = "camera_capability_probe_device_gate_observation"

/**
 * Mirrors `android.hardware.DataSpace.DATASPACE_UNKNOWN`, which is `0`.
 *
 * `commonMain` cannot see the Android platform constant, and the raw integer is exactly what
 * design section 8 requires the record to carry, so the sentinel is pinned here and checked
 * against the platform value by `CameraProbeDataSpaceSentinelTest` in `androidApp`. A device that
 * reports an unknown data space has made a real observation; it has not made an admissible one.
 */
const val CAMERA_DATASPACE_UNKNOWN: Int = 0

/** The device the camera capability was observed on. Mirrors [ProcessKillEnvironment]. */
data class CameraCapabilityEnvironment(
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

/**
 * The pinned build inputs the probe ran under.
 *
 * `probeGitSha` binds the record to the exact probe source that produced it, and `cameraxVersion`
 * to the CameraX release whose nullable underlying-image bridge was exercised — the bridge is
 * experimental, so an observation is only interpretable against the version that made it.
 */
data class CameraCapabilityProvenance(
    val osBuild: String,
    val cameraxVersion: String,
    val probeGitSha: String,
)

/**
 * What the probe actually read from the selected camera, per design section 8.
 *
 * Every field is an observation, never a caller-declared verdict:
 * [renderCameraCapabilityEvidence] derives admission from these alone.
 *
 * [underlyingImageAvailable] is carried separately from [sourceDataspaceRaw] because the two
 * failures are different findings. CameraX's underlying-image accessor is nullable and
 * experimental: it may yield no image at all (nothing to read a data space from), or it may yield
 * an image whose data space is `DATASPACE_UNKNOWN`. Collapsing both into "no colour metadata"
 * would record the S25 as having reported an unknown colour space when it was never asked, and
 * design section 8 stops the milestone on the distinction.
 */
data class CameraCapabilityObservation(
    /** The Camera2 ID of the camera the probe selected. */
    val cameraId: String,
    /** `SENSOR_INFO_TIMESTAMP_SOURCE`, decoded: `REALTIME` or `UNKNOWN`, never remapped. */
    val timestampSource: String,
    /** The raw `android.media.Image.dataSpace` integer, or null when no image was produced. */
    val sourceDataspaceRaw: Int?,
    /** The decoded colour standard, or null when the raw value decodes to no known standard. */
    val sourceColorStandard: String?,
    /** The decoded colour range, or null when the raw value decodes to no known range. */
    val sourceColorRange: String?,
    /** Whether CameraX's nullable underlying-image bridge produced an image to read at all. */
    val underlyingImageAvailable: Boolean,
    /** The analyzer output format. Design section 8 forbids CameraX RGBA conversion. */
    val pixelFormat: String,
)

/**
 * One complete sanitized camera-capability observation, addressed at the design section 8 live
 * admission precondition. No landmark coordinate, and no participant-derived value of any kind,
 * has anywhere to go in this shape, so none can leak.
 */
data class CameraCapabilityEvidence(
    val emittedBy: String,
    val environment: CameraCapabilityEnvironment,
    val provenance: CameraCapabilityProvenance,
    val observation: CameraCapabilityObservation,
)

/** Reported when the sensor timestamp domain is not `REALTIME`. */
const val CAMERA_BLOCK_TIMESTAMP_SOURCE: String = "timestamp_source_not_realtime"

/** Reported when CameraX's nullable underlying-image bridge produced no image. */
const val CAMERA_BLOCK_UNDERLYING_IMAGE: String = "underlying_image_bridge_unavailable"

/** Reported when the raw data space is absent or `DATASPACE_UNKNOWN`. */
const val CAMERA_BLOCK_RAW_DATASPACE: String = "raw_dataspace_unknown"

/** Reported when the raw data space decoded to no known colour standard. */
const val CAMERA_BLOCK_COLOUR_STANDARD: String = "colour_standard_undecoded"

/** Reported when the raw data space decoded to no known colour range. */
const val CAMERA_BLOCK_COLOUR_RANGE: String = "colour_range_undecoded"

/** Reported when the observing device is not the one authorized physical S25 Ultra. */
const val CAMERA_BLOCK_UNAUTHORIZED_TARGET: String = "unauthorized_device_target"

/** The timestamp domain design section 8 requires before live capture may be admitted. */
private const val REALTIME_TIMESTAMP_SOURCE = "REALTIME"

/**
 * Whether [environment] is the one authorized physical camera-probe gate target.
 *
 * Delegates to [isAuthorizedS25Target], the single home for this rule. Restating it here is what
 * produced four divergent copies once already.
 */
fun isAuthorizedCameraProbeTarget(environment: CameraCapabilityEnvironment): Boolean =
    isAuthorizedS25Target(
        deviceClass = environment.deviceClass,
        manufacturer = environment.manufacturer,
        model = environment.model,
        marketingName = environment.marketingName,
    )

/**
 * Every reason [observation] alone blocks live admission, in a fixed order.
 *
 * This is the single home of the camera admission rule. `ProbeResult.isLiveAdmissible` on Android
 * asks this rather than restating it: the device-authorization rule already existed as four
 * divergent copies once, three of them wrong in the same way, and a second copy of *this* rule
 * would drift the same way the moment a conjunct changed.
 *
 * Each conjunct is load-bearing:
 * - a non-`REALTIME` timestamp source leaves capture timestamps in a domain that cannot be
 *   compared with `elapsedRealtimeNanos` inference timing, and design section 8 forbids remapping
 *   it to another clock;
 * - an unavailable underlying-image bridge means no data space was ever read, so a record showing
 *   decoded colour alongside it is self-contradictory and is disbelieved rather than trusted;
 * - an absent or `DATASPACE_UNKNOWN` raw value, or a value that decodes to no known standard or
 *   range, is unknown colour provenance — the exact condition design section 8 stops the milestone
 *   on rather than silently weakening admission.
 *
 * An unknown data space and an unavailable bridge are both perfectly valid *observations*. They
 * are simply never admissible ones, and this function is the only place that decides so.
 */
fun cameraObservationBlockingReasons(observation: CameraCapabilityObservation): List<String> = buildList {
    if (observation.timestampSource != REALTIME_TIMESTAMP_SOURCE) add(CAMERA_BLOCK_TIMESTAMP_SOURCE)
    if (!observation.underlyingImageAvailable) add(CAMERA_BLOCK_UNDERLYING_IMAGE)
    val rawDataSpace = observation.sourceDataspaceRaw
    if (rawDataSpace == null || rawDataSpace == CAMERA_DATASPACE_UNKNOWN) add(CAMERA_BLOCK_RAW_DATASPACE)
    if (observation.sourceColorStandard == null) add(CAMERA_BLOCK_COLOUR_STANDARD)
    if (observation.sourceColorRange == null) add(CAMERA_BLOCK_COLOUR_RANGE)
}

/**
 * Every reason [observation] on [environment] blocks live admission, in a fixed order.
 *
 * An empty list is the *definition* of admission — [renderCameraCapabilityEvidence] takes the flag
 * from `isEmpty()` rather than recomputing a parallel conjunction, so the recorded reasons and the
 * recorded verdict cannot drift apart.
 *
 * The device conjunct is kept separate from the observation conjuncts because an unauthorized
 * target does not make the observation wrong — it makes it evidence about some other device, which
 * the design section 8 gate is not about.
 */
fun cameraLiveAdmissionBlockingReasons(
    environment: CameraCapabilityEnvironment,
    observation: CameraCapabilityObservation,
): List<String> = buildList {
    addAll(cameraObservationBlockingReasons(observation))
    if (!isAuthorizedCameraProbeTarget(environment)) add(CAMERA_BLOCK_UNAUTHORIZED_TARGET)
}

/**
 * Renders [evidence] as one deterministic line of JSON, computing
 * `satisfies_s25_camera_live_admission` from the observed fields.
 *
 * This is the single place camera live admission is decided. A caller can misreport what it
 * observed, but it cannot assert admission directly: the flag is always
 * `cameraLiveAdmissionBlockingReasons(...).isEmpty()`, and every reason it found is recorded
 * beside it so a blocking observation is an informative finding rather than a bare `false`.
 *
 * Admission here is a *precondition* for the design section 8 live adapter, never a claim that the
 * adapter works. It certifies only that this device, on this build, reported a comparable
 * timestamp domain and known colour provenance through the bridge the adapter would depend on.
 */
fun renderCameraCapabilityEvidence(evidence: CameraCapabilityEvidence): String {
    val environment = evidence.environment
    val observation = evidence.observation
    val blockingReasons = cameraLiveAdmissionBlockingReasons(environment, observation)

    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", CAMERA_CAPABILITY_EVIDENCE_SCHEMA_VERSION)
        put("record_kind", CAMERA_CAPABILITY_RECORD_KIND)
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        // Design section 8 collects this before any participant exists; AGENTS.md keeps real
        // participant data out of Phase 0 entirely. The classification is explicit so a reader
        // never has to infer it from the absence of fields.
        put("non_participant_observation", true)
        put("observation_subject", "device_camera_capability")
        put("emitted_by", evidence.emittedBy)
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
        putJsonObject("provenance") {
            put("os_build", evidence.provenance.osBuild)
            put("camerax_version", evidence.provenance.cameraxVersion)
            put("probe_git_sha", evidence.provenance.probeGitSha)
        }
        putJsonObject("observation") {
            put("camera_id", observation.cameraId)
            put("timestamp_source", observation.timestampSource)
            put("source_dataspace_raw", observation.sourceDataspaceRaw)
            put("source_color_standard", observation.sourceColorStandard)
            put("source_color_range", observation.sourceColorRange)
            put("underlying_image_available", observation.underlyingImageAvailable)
            put("pixel_format", observation.pixelFormat)
        }
        put("authorized_s25_target", isAuthorizedCameraProbeTarget(environment))
        putJsonArray("live_admission_blocking_reasons") { blockingReasons.forEach { add(it) } }
        put("satisfies_s25_camera_live_admission", blockingReasons.isEmpty())
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}
