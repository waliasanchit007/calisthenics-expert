package com.calisthenics.diagnostic.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ContractDecodeException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

private val jsonParser = Json {
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = false
    isLenient = false
}

fun encodeManifest(manifest: SessionManifest): String {
    validateManifest(manifest)
    return buildString {
        append('{')
        field("session_manifest_schema_version") { string(manifest.schemaVersion.value) }
        field("session_id") { string(manifest.sessionId.value) }
        manifest.sessionKind?.let { field("session_kind") { string(it) } }
        field("participant_pseudonym_id") { string(manifest.participantPseudonymId) }
        field("capture_protocol_version") { string(manifest.captureProtocolVersion.value) }
        field("harness_git_sha") { string(manifest.harnessGitSha) }
        field("device") { device(manifest.device) }
        field("pose_pipeline") { posePipeline(manifest.posePipeline) }
        field("camera") { camera(manifest.camera) }
        field("model_input") { modelInput(manifest.modelInput) }
        field("inference_drain_timeout_ms") { long(manifest.inferenceDrainTimeoutMs) }
        field("cadence") { cadence(manifest.cadence) }
        field("clock") { clock(manifest.clock) }
        field("available_observations") { availableObservations(manifest.availableObservations) }
        field("media_references") { array(manifest.mediaReferences) { mediaReference(it) } }
        field("counts") { counts(manifest.counts) }
        field("machine_inference_failures") { array(manifest.machineInferenceFailures) { machineFailure(it) } }
        field("capture_quality_observed") { captureQuality(manifest.captureQualityObserved) }
        field("study_context") {
            append('{')
            manifest.studyContext.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                quoted(entry.key)
                append(':')
                element(entry.value)
            }
            append('}')
        }
        append('}')
    }
}

fun decodeManifest(json: String): SessionManifest = decode("manifest") {
    val root = jsonParser.parseToJsonElement(json).objectValue("manifest")
    val schemaVersion = root.requiredVersion("session_manifest_schema_version")
    requireSupportedMajor(schemaVersion, "SessionManifest")
    SessionManifest(
        schemaVersion = schemaVersion,
        sessionId = root.requiredUuid("session_id"),
        sessionKind = root.optionalString("session_kind"),
        participantPseudonymId = root.requiredString("participant_pseudonym_id"),
        captureProtocolVersion = root.requiredVersion("capture_protocol_version"),
        harnessGitSha = root.requiredString("harness_git_sha"),
        device = root.requiredObject("device").decodeDevice(),
        posePipeline = root.requiredObject("pose_pipeline").decodePosePipeline(),
        camera = root.requiredObject("camera").decodeCamera(),
        modelInput = root.requiredObject("model_input").decodeModelInput(),
        inferenceDrainTimeoutMs = root.requiredLong("inference_drain_timeout_ms"),
        cadence = root.requiredObject("cadence").decodeCadence(),
        clock = root.requiredObject("clock").decodeClock(),
        availableObservations = root.requiredObject("available_observations").decodeAvailableObservations(),
        mediaReferences = root.requiredArray("media_references").map { it.objectValue("media_reference").decodeMediaReference() },
        counts = root.requiredObject("counts").decodeCounts(),
        machineInferenceFailures = root.requiredArray("machine_inference_failures")
            .map { it.objectValue("machine_inference_failure").decodeMachineFailure() },
        captureQualityObserved = root.requiredObject("capture_quality_observed").decodeCaptureQuality(),
        studyContext = root.requiredObject("study_context"),
    ).also(::validateManifest)
}

fun encodeFrameLine(frame: RawPoseFrame): String = encodeFrame(frame) + '\n'

fun decodeFrame(json: String): RawPoseFrame = decode("frame") {
    val normalized = if (json.endsWith('\n')) json.dropLast(1) else json
    if (normalized.endsWith('\r') || normalized.contains('\n')) {
        throw ContractDecodeException("RawPoseFrame must be one LF-terminated JSON object")
    }
    val root = jsonParser.parseToJsonElement(normalized).objectValue("frame")
    val schemaVersion = root.requiredVersion("raw_pose_frame_schema_version")
    requireSupportedMajor(schemaVersion, "RawPoseFrame")
    RawPoseFrame(
        schemaVersion = schemaVersion,
        sessionId = root.requiredUuid("session_id"),
        frameIndex = root.requiredLong("frame_index"),
        captureTimestampNs = root.requiredLong("capture_timestamp_ns"),
        backendTimestampMs = root.optionalLong("backend_timestamp_ms"),
        inferenceStartNs = root.requiredLong("inference_start_ns"),
        inferenceEndNs = root.requiredLong("inference_end_ns"),
        posePresent = root.requiredBoolean("pose_present"),
        normalizedImageLandmarks = root.requiredNullableLandmarks("normalized_image_landmarks"),
        worldLandmarks = root.requiredNullableLandmarks("world_landmarks"),
        imageWidth = root.requiredLong("image_width").toIntExact("image_width"),
        imageHeight = root.requiredLong("image_height").toIntExact("image_height"),
        framesSkippedSinceLastEmittedRecord = root.requiredLong("frames_skipped_since_last_emitted_record"),
    ).also(::validateFrame)
}

fun nextBackendTimestampMs(captureTimestampNs: Long, previousBackendTimestampMs: Long?): Long {
    require(captureTimestampNs >= 0) { "capture_timestamp_ns must be non-negative" }
    val captureFloor = captureTimestampNs / 1_000_000
    return maxOf(captureFloor, (previousBackendTimestampMs ?: Long.MIN_VALUE) + 1)
}

private fun encodeFrame(frame: RawPoseFrame): String {
    validateFrame(frame)
    return buildString {
        append('{')
        field("raw_pose_frame_schema_version") { string(frame.schemaVersion.value) }
        field("session_id") { string(frame.sessionId.value) }
        field("frame_index") { long(frame.frameIndex) }
        field("capture_timestamp_ns") { long(frame.captureTimestampNs) }
        frame.backendTimestampMs?.let { field("backend_timestamp_ms") { long(it) } }
        field("inference_start_ns") { long(frame.inferenceStartNs) }
        field("inference_end_ns") { long(frame.inferenceEndNs) }
        field("pose_present") { bool(frame.posePresent) }
        field("normalized_image_landmarks") { nullableLandmarks(frame.normalizedImageLandmarks) }
        field("world_landmarks") { nullableLandmarks(frame.worldLandmarks) }
        field("image_width") { long(frame.imageWidth.toLong()) }
        field("image_height") { long(frame.imageHeight.toLong()) }
        field("frames_skipped_since_last_emitted_record") { long(frame.framesSkippedSinceLastEmittedRecord) }
        append('}')
    }
}

private fun StringBuilder.device(value: Device) {
    append('{')
    field("platform") { string(value.platform) }
    field("os_version") { string(value.osVersion) }
    field("device_model") { string(value.deviceModel) }
    value.chipset?.let { field("chipset") { string(it) } }
    field("thermal_api_available") { bool(value.thermalApiAvailable) }
    append('}')
}

private fun StringBuilder.posePipeline(value: PosePipeline) {
    append('{')
    field("backend_name") { string(value.backendName) }
    field("backend_version") { string(value.backendVersion) }
    field("model_asset_name") { string(value.modelAssetName) }
    field("model_asset_sha256") { string(value.modelAssetSha256.value) }
    field("delegate") { string(value.delegate) }
    field("running_mode") { string(value.runningMode) }
    field("num_poses") { long(value.numPoses.toLong()) }
    field("min_pose_detection_confidence") { number(value.minPoseDetectionConfidence) }
    field("min_pose_presence_confidence") { number(value.minPosePresenceConfidence) }
    field("min_tracking_confidence") { number(value.minTrackingConfidence) }
    field("landmark_topology") { string(value.landmarkTopology) }
    append('}')
}

private fun StringBuilder.camera(value: Camera) {
    append('{')
    field("facing") { string(value.facing) }
    field("capture_width") { long(value.captureWidth.toLong()) }
    field("capture_height") { long(value.captureHeight.toLong()) }
    field("orientation_degrees") { long(value.orientationDegrees.toLong()) }
    field("mirrored") { bool(value.mirrored) }
    field("requested_camera_fps") { number(value.requestedCameraFps) }
    value.sourcePixelFormat?.let { field("source_pixel_format") { string(it) } }
    value.sourceColorMatrix?.let { field("source_color_matrix") { string(it) } }
    value.sourceColorRange?.let { field("source_color_range") { string(it) } }
    value.sourceDataspaceRaw?.let { field("source_dataspace_raw") { long(it) } }
    value.timestampSource?.let { field("timestamp_source") { string(it) } }
    value.deliverySemantics?.let { field("delivery_semantics") { string(it) } }
    append('}')
}

private fun StringBuilder.modelInput(value: ModelInput) {
    append('{')
    field("model_input_width") { long(value.modelInputWidth.toLong()) }
    field("model_input_height") { long(value.modelInputHeight.toLong()) }
    field("live_preprocessing_contract_version") { string(value.livePreprocessingContractVersion.value) }
    value.fixturePreprocessingContractVersion?.let {
        field("fixture_preprocessing_contract_version") { string(it.value) }
    }
    append('}')
}

private fun StringBuilder.cadence(value: Cadence) {
    append('{')
    field("configured_inference_fps") { number(value.configuredInferenceFps) }
    field("achieved_inference_fps_timeseries") {
        array(value.achievedInferenceFpsTimeseries) {
            append('{')
            field("t_monotonic_ns") { long(it.tMonotonicNs) }
            field("achieved_inference_fps") { number(it.achievedInferenceFps) }
            append('}')
        }
    }
    value.trackerUpdateCadenceHz?.let { field("tracker_update_cadence_hz") { number(it) } }
    append('}')
}

private fun StringBuilder.clock(value: Clock) {
    append('{')
    field("monotonic_time_base") { string(value.monotonicTimeBase) }
    field("t0_monotonic_ns") { long(value.t0MonotonicNs) }
    field("started_at_utc") { string(value.startedAtUtc) }
    field("ended_at_utc") { string(value.endedAtUtc) }
    append('}')
}

private fun StringBuilder.availableObservations(value: AvailableObservations) {
    append('{')
    field("normalized_image_landmarks_available") { bool(value.normalizedImageLandmarksAvailable) }
    field("world_landmarks_available") { bool(value.worldLandmarksAvailable) }
    append('}')
}

private fun StringBuilder.mediaReference(value: MediaReference) {
    append('{')
    field("role") { string(value.role) }
    field("path_ref") { string(value.pathRef) }
    field("sha256") { string(value.sha256.value) }
    value.syncOffsetNs?.let { field("sync_offset_ns") { long(it) } }
    value.syncDriftNsPerS?.let { field("sync_drift_ns_per_s") { number(it) } }
    append('}')
}

private fun StringBuilder.counts(value: Counts) {
    append('{')
    field("camera_frames_received") { long(value.cameraFramesReceived) }
    field("inference_frames_submitted") { long(value.inferenceFramesSubmitted) }
    field("inference_records_emitted") { long(value.inferenceRecordsEmitted) }
    field("pose_detected_records") { long(value.poseDetectedRecords) }
    field("frames_skipped_before_inference") { long(value.framesSkippedBeforeInference) }
    field("frames_skipped_before_inference_by_reason") {
        append('{')
        value.framesSkippedBeforeInferenceByReason.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            quoted(entry.key)
            append(':')
            long(entry.value)
        }
        append('}')
    }
    field("trailing_frames_skipped_after_last_emitted_record") { long(value.trailingFramesSkippedAfterLastEmittedRecord) }
    field("inference_failures") { long(value.inferenceFailures) }
    append('}')
}

private fun StringBuilder.machineFailure(value: MachineInferenceFailure) {
    append('{')
    field("submission_index") { long(value.submissionIndex) }
    field("capture_timestamp_ns") { long(value.captureTimestampNs) }
    field("failure_source") { string(value.failureSource) }
    field("classification") { string(value.classification) }
    value.sourceErrorCode?.let { field("source_error_code") { string(it) } }
    value.detail?.let { field("detail") { string(it) } }
    append('}')
}

private fun StringBuilder.captureQuality(value: CaptureQualityObserved) {
    append('{')
    field("full_body_visible_at_hang") { bool(value.fullBodyVisibleAtHang) }
    field("required_joints_visible") { bool(value.requiredJointsVisible) }
    field("subject_scale_acceptable") { bool(value.subjectScaleAcceptable) }
    field("lighting_class") { string(value.lightingClass) }
    field("visible_occlusion") { bool(value.visibleOcclusion) }
    value.setupFailureLog?.let { field("setup_failure_log") { string(it) } }
    append('}')
}

private fun StringBuilder.nullableLandmarks(value: List<Landmark>?) {
    if (value == null) {
        append("null")
    } else {
        array(value) { landmark(it) }
    }
}

private fun StringBuilder.landmark(value: Landmark) {
    append('{')
    field("index") { long(value.index.toLong()) }
    value.name?.let { field("name") { string(it) } }
    field("x") { number(value.x) }
    field("y") { number(value.y) }
    field("z") { number(value.z) }
    field("visibility") { nullableNumber(value.visibility) }
    field("presence") { nullableNumber(value.presence) }
    append('}')
}

private fun <T> StringBuilder.array(values: List<T>, write: StringBuilder.(T) -> Unit) {
    append('[')
    values.forEachIndexed { index, value ->
        if (index > 0) append(',')
        write(value)
    }
    append(']')
}

private fun StringBuilder.field(name: String, write: StringBuilder.() -> Unit) {
    if (last() != '{' && last() != '[') append(',')
    quoted(name)
    append(':')
    write()
}

private fun StringBuilder.string(value: String) = quoted(value)
private fun StringBuilder.long(value: Long) = append(value)
private fun StringBuilder.bool(value: Boolean) = append(value)
private fun StringBuilder.number(value: Double) = append(CanonicalNumber.format(value))
private fun StringBuilder.nullableNumber(value: Double?) = if (value == null) append("null") else number(value)

private fun StringBuilder.quoted(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun StringBuilder.element(value: JsonElement) {
    when (value) {
        JsonNull -> append("null")
        is JsonObject -> {
            append('{')
            value.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                quoted(entry.key)
                append(':')
                element(entry.value)
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            value.forEachIndexed { index, nested ->
                if (index > 0) append(',')
                element(nested)
            }
            append(']')
        }
        is JsonPrimitive -> if (value.isString) {
            quoted(value.content)
        } else if (value.booleanOrNull != null || value.content == "true" || value.content == "false") {
            append(value.content)
        } else {
            append(CanonicalNumber.format(value.content.toDoubleOrNull() ?: error("Invalid JSON number")))
        }
    }
}

private inline fun <T> decode(kind: String, block: () -> T): T = try {
    block()
} catch (error: ContractDecodeException) {
    throw error
} catch (error: Exception) {
    throw ContractDecodeException("Invalid $kind: ${error.message ?: "malformed JSON"}", error)
}

private fun JsonElement.objectValue(kind: String): JsonObject =
    (this as? JsonObject) ?: throw ContractDecodeException("$kind must be a JSON object")

private fun JsonObject.required(key: String): JsonElement =
    get(key) ?: throw ContractDecodeException("Missing required field: $key")

private fun JsonObject.requiredObject(key: String): JsonObject = required(key).objectValue(key)
private fun JsonObject.requiredArray(key: String): JsonArray = required(key).arrayValue(key)

private fun JsonElement.arrayValue(key: String): JsonArray =
    (this as? JsonArray) ?: throw ContractDecodeException("$key must be a JSON array")

private fun JsonObject.requiredString(key: String): String = required(key).stringValue(key)

private fun JsonElement.stringValue(key: String): String {
    val primitive = this as? JsonPrimitive ?: throw ContractDecodeException("$key must be a string")
    if (!primitive.isString) throw ContractDecodeException("$key must be a string")
    return primitive.contentOrNull ?: throw ContractDecodeException("$key must not be null")
}

private fun JsonObject.optionalString(key: String): String? {
    val value = get(key) ?: return null
    return value.stringValue(key)
}

private fun JsonObject.requiredLong(key: String): Long {
    val primitive = required(key) as? JsonPrimitive ?: throw ContractDecodeException("$key must be an integer")
    if (primitive.isString) throw ContractDecodeException("$key must be an integer")
    return primitive.content.toLongOrNull() ?: throw ContractDecodeException("$key must be an integer")
}

private fun JsonObject.optionalLong(key: String): Long? {
    if (!containsKey(key)) return null
    return requiredLong(key)
}

private fun JsonObject.requiredDouble(key: String): Double {
    val primitive = required(key) as? JsonPrimitive ?: throw ContractDecodeException("$key must be a number")
    if (primitive.isString) throw ContractDecodeException("$key must be a number")
    val value = primitive.content.toDoubleOrNull() ?: throw ContractDecodeException("$key must be a number")
    if (!value.isFinite()) throw ContractDecodeException("$key must be finite")
    return value
}

private fun JsonObject.optionalDouble(key: String): Double? = if (containsKey(key)) requiredDouble(key) else null

private fun JsonObject.requiredBoolean(key: String): Boolean {
    val primitive = required(key) as? JsonPrimitive ?: throw ContractDecodeException("$key must be a boolean")
    return primitive.booleanOrNull ?: throw ContractDecodeException("$key must be a boolean")
}

private fun JsonObject.requiredVersion(key: String): SemanticVersion = SemanticVersion(requiredString(key))
private fun JsonObject.requiredUuid(key: String): Uuid = Uuid(requiredString(key))
private fun JsonObject.requiredSha256(key: String): Sha256 = Sha256(requiredString(key))

private fun JsonObject.requiredNullableLandmarks(key: String): List<Landmark>? {
    val value = required(key)
    if (value == JsonNull) return null
    return value.arrayValue(key).map { it.objectValue("landmark").decodeLandmark() }
}

private fun JsonObject.decodeDevice() = Device(
    platform = requiredString("platform"),
    osVersion = requiredString("os_version"),
    deviceModel = requiredString("device_model"),
    chipset = optionalString("chipset"),
    thermalApiAvailable = requiredBoolean("thermal_api_available"),
)

private fun JsonObject.decodePosePipeline() = PosePipeline(
    backendName = requiredString("backend_name"),
    backendVersion = requiredString("backend_version"),
    modelAssetName = requiredString("model_asset_name"),
    modelAssetSha256 = requiredSha256("model_asset_sha256"),
    delegate = requiredString("delegate"),
    runningMode = requiredString("running_mode"),
    numPoses = requiredLong("num_poses").toIntExact("num_poses"),
    minPoseDetectionConfidence = requiredDouble("min_pose_detection_confidence"),
    minPosePresenceConfidence = requiredDouble("min_pose_presence_confidence"),
    minTrackingConfidence = requiredDouble("min_tracking_confidence"),
    landmarkTopology = requiredString("landmark_topology"),
)

private fun JsonObject.decodeCamera() = Camera(
    facing = requiredString("facing"),
    captureWidth = requiredLong("capture_width").toIntExact("capture_width"),
    captureHeight = requiredLong("capture_height").toIntExact("capture_height"),
    orientationDegrees = requiredLong("orientation_degrees").toIntExact("orientation_degrees"),
    mirrored = requiredBoolean("mirrored"),
    requestedCameraFps = requiredDouble("requested_camera_fps"),
    sourcePixelFormat = optionalString("source_pixel_format"),
    sourceColorMatrix = optionalString("source_color_matrix"),
    sourceColorRange = optionalString("source_color_range"),
    sourceDataspaceRaw = optionalLong("source_dataspace_raw"),
    timestampSource = optionalString("timestamp_source"),
    deliverySemantics = optionalString("delivery_semantics"),
)

private fun JsonObject.decodeModelInput() = ModelInput(
    modelInputWidth = requiredLong("model_input_width").toIntExact("model_input_width"),
    modelInputHeight = requiredLong("model_input_height").toIntExact("model_input_height"),
    livePreprocessingContractVersion = requiredVersion("live_preprocessing_contract_version"),
    fixturePreprocessingContractVersion = if (containsKey("fixture_preprocessing_contract_version")) {
        requiredVersion("fixture_preprocessing_contract_version")
    } else null,
)

private fun JsonObject.decodeCadence() = Cadence(
    configuredInferenceFps = requiredDouble("configured_inference_fps"),
    achievedInferenceFpsTimeseries = requiredArray("achieved_inference_fps_timeseries")
        .map { sample ->
            sample.objectValue("cadence_sample").let {
                CadenceSample(it.requiredLong("t_monotonic_ns"), it.requiredDouble("achieved_inference_fps"))
            }
        },
    trackerUpdateCadenceHz = optionalDouble("tracker_update_cadence_hz"),
)

private fun JsonObject.decodeClock() = Clock(
    monotonicTimeBase = requiredString("monotonic_time_base"),
    t0MonotonicNs = requiredLong("t0_monotonic_ns"),
    startedAtUtc = requiredString("started_at_utc"),
    endedAtUtc = requiredString("ended_at_utc"),
)

private fun JsonObject.decodeAvailableObservations() = AvailableObservations(
    normalizedImageLandmarksAvailable = requiredBoolean("normalized_image_landmarks_available"),
    worldLandmarksAvailable = requiredBoolean("world_landmarks_available"),
)

private fun JsonObject.decodeMediaReference() = MediaReference(
    role = requiredString("role"),
    pathRef = requiredString("path_ref"),
    sha256 = requiredSha256("sha256"),
    syncOffsetNs = optionalLong("sync_offset_ns"),
    syncDriftNsPerS = optionalDouble("sync_drift_ns_per_s"),
)

private fun JsonObject.decodeCounts() = Counts(
    cameraFramesReceived = requiredLong("camera_frames_received"),
    inferenceFramesSubmitted = requiredLong("inference_frames_submitted"),
    inferenceRecordsEmitted = requiredLong("inference_records_emitted"),
    poseDetectedRecords = requiredLong("pose_detected_records"),
    framesSkippedBeforeInference = requiredLong("frames_skipped_before_inference"),
    framesSkippedBeforeInferenceByReason = requiredObject("frames_skipped_before_inference_by_reason")
        .mapValues { (_, value) ->
            val primitive = value as? JsonPrimitive ?: throw ContractDecodeException("skip reason must be an integer")
            primitive.content.toLongOrNull() ?: throw ContractDecodeException("skip reason must be an integer")
        },
    trailingFramesSkippedAfterLastEmittedRecord = requiredLong("trailing_frames_skipped_after_last_emitted_record"),
    inferenceFailures = requiredLong("inference_failures"),
)

private fun JsonObject.decodeMachineFailure() = MachineInferenceFailure(
    submissionIndex = requiredLong("submission_index"),
    captureTimestampNs = requiredLong("capture_timestamp_ns"),
    failureSource = requiredString("failure_source"),
    classification = requiredString("classification"),
    sourceErrorCode = optionalString("source_error_code"),
    detail = optionalString("detail"),
)

private fun JsonObject.decodeCaptureQuality() = CaptureQualityObserved(
    fullBodyVisibleAtHang = requiredBoolean("full_body_visible_at_hang"),
    requiredJointsVisible = requiredBoolean("required_joints_visible"),
    subjectScaleAcceptable = requiredBoolean("subject_scale_acceptable"),
    lightingClass = requiredString("lighting_class"),
    visibleOcclusion = requiredBoolean("visible_occlusion"),
    setupFailureLog = optionalString("setup_failure_log"),
)

private fun JsonObject.decodeLandmark() = Landmark(
    index = requiredLong("index").toIntExact("index"),
    name = optionalString("name"),
    x = requiredDouble("x"),
    y = requiredDouble("y"),
    z = requiredDouble("z"),
    visibility = optionalDoubleOrNull("visibility"),
    presence = optionalDoubleOrNull("presence"),
)

private fun JsonObject.optionalDoubleOrNull(key: String): Double? {
    if (!containsKey(key)) throw ContractDecodeException("Missing required field: $key")
    if (get(key) == JsonNull) return null
    return requiredDouble(key)
}

private fun JsonElement.toIntExact(key: String): Int =
    if (this is JsonPrimitive) content.toLongOrNull()?.toIntExact(key) ?: throw ContractDecodeException("$key is out of range")
    else throw ContractDecodeException("$key must be an integer")

private fun Long.toIntExact(key: String): Int {
    if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw ContractDecodeException("$key is out of range")
    }
    return toInt()
}

private fun requireSupportedMajor(version: SemanticVersion, artifact: String) {
    if (version.major != 1) throw ContractDecodeException("Unsupported $artifact major version: ${version.value}")
}

private fun validateManifest(value: SessionManifest) {
    requireSupportedMajor(value.schemaVersion, "SessionManifest")
    require(value.participantPseudonymId.isNotEmpty()) { "participant_pseudonym_id must not be empty" }
    require(value.harnessGitSha.isNotEmpty()) { "harness_git_sha must not be empty" }
    require(value.inferenceDrainTimeoutMs >= 0) { "inference_drain_timeout_ms must be non-negative" }
    require(value.sessionKind == null || value.sessionKind in setOf("live_study", "live_conformance", "fixture_replay")) {
        "session_kind is not recognized"
    }
    require(value.camera.captureWidth > 0 && value.camera.captureHeight > 0) { "camera dimensions must be positive" }
    require(value.modelInput.modelInputWidth > 0 && value.modelInput.modelInputHeight > 0) {
        "model input dimensions must be positive"
    }
    require(value.posePipeline.numPoses > 0) { "num_poses must be positive" }
    listOf(
        value.posePipeline.minPoseDetectionConfidence,
        value.posePipeline.minPosePresenceConfidence,
        value.posePipeline.minTrackingConfidence,
    ).forEach { require(it in 0.0..1.0) { "confidence must be between zero and one" } }
    validateFinite(value.cadence.configuredInferenceFps)
    value.cadence.achievedInferenceFpsTimeseries.forEach {
        require(it.tMonotonicNs >= 0) { "cadence time must be non-negative" }
        validateFinite(it.achievedInferenceFps)
    }
    value.counts.allCounts().forEach { require(it >= 0) { "counts must be non-negative" } }
    value.counts.framesSkippedBeforeInferenceByReason.values.forEach {
        require(it >= 0) { "skip counts must be non-negative" }
    }
    require(value.counts.cameraFramesReceived ==
        value.counts.inferenceFramesSubmitted + value.counts.framesSkippedBeforeInference
    ) { "camera_frames_received must equal submitted plus skipped" }
    require(value.counts.inferenceFramesSubmitted ==
        value.counts.inferenceRecordsEmitted + value.counts.inferenceFailures
    ) { "inference_frames_submitted must equal emitted plus failures" }
    require(value.counts.poseDetectedRecords <= value.counts.inferenceRecordsEmitted) {
        "pose_detected_records must not exceed emitted records"
    }
    require(value.counts.framesSkippedBeforeInferenceByReason.values.sum() == value.counts.framesSkippedBeforeInference) {
        "skip-reason counts must sum to skipped frames"
    }
    require(value.machineInferenceFailures.size.toLong() == value.counts.inferenceFailures) {
        "machine inference failure list must equal inference_failures"
    }
    value.machineInferenceFailures.forEach { failure ->
        require(failure.failureSource in setOf("harness", "backend")) {
            "failure_source is not recognized"
        }
        require(failure.classification in setOf("backend_call_failed", "session_teardown_timeout")) {
            "failure classification is not recognized"
        }
    }
}

private fun Counts.allCounts() = listOf(
    cameraFramesReceived,
    inferenceFramesSubmitted,
    inferenceRecordsEmitted,
    poseDetectedRecords,
    framesSkippedBeforeInference,
    trailingFramesSkippedAfterLastEmittedRecord,
    inferenceFailures,
)

private fun validateFrame(value: RawPoseFrame) {
    requireSupportedMajor(value.schemaVersion, "RawPoseFrame")
    require(value.frameIndex >= 0) { "frame_index must be non-negative" }
    require(value.captureTimestampNs >= 0) { "capture_timestamp_ns must be non-negative" }
    require(value.inferenceStartNs >= 0 && value.inferenceEndNs >= value.inferenceStartNs) {
        "inference timestamps are invalid"
    }
    require(value.backendTimestampMs == null || value.backendTimestampMs >= 0) {
        "backend_timestamp_ms must be non-negative"
    }
    require(value.imageWidth > 0 && value.imageHeight > 0) { "image dimensions must be positive" }
    require(value.framesSkippedSinceLastEmittedRecord >= 0) { "skipped frames must be non-negative" }
    value.normalizedImageLandmarks?.forEach(::validateLandmark)
    value.worldLandmarks?.forEach(::validateLandmark)
}

private fun validateLandmark(value: Landmark) {
    require(value.index >= 0) { "landmark index must be non-negative" }
    validateFinite(value.x)
    validateFinite(value.y)
    validateFinite(value.z)
    value.visibility?.let(::validateFinite)
    value.presence?.let(::validateFinite)
}

private fun validateFinite(value: Double) {
    require(value.isFinite()) { "numbers must be finite" }
}
