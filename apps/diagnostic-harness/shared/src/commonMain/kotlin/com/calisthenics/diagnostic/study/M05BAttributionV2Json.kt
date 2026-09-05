package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.live.LiveFrameClassification
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val V2_SHA256 = Regex("\\A[0-9a-f]{64}\\z")
private val V2_GIT_SHA = Regex("\\A[0-9a-f]{40}\\z")
private val V2_SAFE_REFERENCE = Regex(
    "\\Aexports/(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|[0-9a-f]{64})\\z",
)

enum class AttributionV2ArmStatus(val wireValue: String) {
    RECORDED("recorded"),
    BLOCKED("blocked"),
}

data class AttributionV2DeviceSource(
    val deviceClass: String,
    val deviceIdentity: String,
    val manufacturer: String,
    val model: String,
    val apiLevel: Int,
    val androidRelease: String,
    val osBuild: String,
)

data class AttributionV2RunIdentity(
    val appGitSha: String,
    val probeGitSha: String,
    val appApkSha256: String,
    val testApkSha256: String,
    val modelSha256: String,
    val runtimeVersion: String,
    val backendVersion: String,
    val delegate: String,
)

data class AttributionV2FpsRange(val lower: Int, val upper: Int)

data class AttributionV2EnvironmentSnapshot(
    val thermalStatus: Int = 0,
    val batteryTemperatureC: Double = 25.0,
    val batteryLevelPercent: Double = 80.0,
    val powerSource: String = "battery",
    val charging: Boolean = false,
    val wifiTransport: Boolean = true,
    val screenKeepAwake: Boolean = true,
    val deviceIdle: Boolean = false,
    val negotiatedCameraFpsRange: AttributionV2FpsRange = AttributionV2FpsRange(30, 30),
    val deliveredWidth: Int = 1280,
    val deliveredHeight: Int = 720,
    val deliveredFormat: String = "YUV_420_888",
)

data class AttributionV2EnvironmentBoundary(
    val armStartedNs: Long = 0L,
    val armEndedNs: Long = M05BAttributionV2Protocol.ARM_CAP_NS,
    val start: AttributionV2EnvironmentSnapshot = AttributionV2EnvironmentSnapshot(),
    val end: AttributionV2EnvironmentSnapshot = start,
)

data class AttributionV2ArtifactRef(
    val relativePath: String,
    val lengthBytes: Long,
    val sha256: String,
)

data class AttributionV2SessionBundle(
    val sessionReference: String,
    val manifest: AttributionV2ArtifactRef,
    val frames: AttributionV2ArtifactRef,
    val telemetry: AttributionV2ArtifactRef,
)

enum class AttributionV2CallbackPhase {
    WARMUP,
    MEASURED,
}

data class AttributionV2SourceObservation(
    val status: AttributionV2ArmStatus,
    val blockingCodes: List<String>,
    val warmupCount: Int,
    val measuredTimestampsNs: List<Long>,
    val observedAeTargetFps: AttributionV2FpsRange?,
    val thermalStatus: Int,
    val initialCooldownThermalStatuses: List<Int> = emptyList(),
    val initialCooldownRecovered: Boolean? = null,
    val environment: AttributionV2EnvironmentBoundary = AttributionV2EnvironmentBoundary(),
)

data class AttributionV2BackendObservation(
    val status: AttributionV2ArmStatus,
    val blockingCodes: List<String>,
    val warmupCount: Int,
    val warmupElapsedNs: Long,
    val measuredServiceDurationsNs: List<Long>,
    val inferenceFailures: Int,
    val thermalStatus: Int,
    val armStartedNs: Long = 0L,
    val deliveredCallbackIds: List<Long> = emptyList(),
    val terminalCallbacks: List<AttributionV2Callback> = emptyList(),
    val submittedInferenceIds: List<Long> = emptyList(),
    val pendingInferenceIds: Set<Long> = emptySet(),
    val rawEvidenceOverflow: Boolean = false,
    val environment: AttributionV2EnvironmentBoundary = AttributionV2EnvironmentBoundary(),
    val sessionBundle: AttributionV2SessionBundle? = null,
)

data class AttributionV2SelectorObservation(
    val status: AttributionV2ArmStatus,
    val blockingCodes: List<String>,
    val productionSelections: Map<Int, List<Long>>,
    val hostSelections: Map<Int, List<Long>>,
)

data class AttributionV2EndToEndRun(
    val cadenceFps: Int,
    val sessionReference: String,
    val armStartedNs: Long,
    val initialThermalStatus: Int,
    val finalThermalStatus: Int,
    val deliveredCallbackIds: List<Long>,
    val terminalCallbacks: List<AttributionV2Callback>,
    val submittedInferenceIds: List<Long>,
    val pendingInferenceIds: Set<Long>,
    val measuredSpanNs: Long,
    val environment: AttributionV2EnvironmentBoundary = AttributionV2EnvironmentBoundary(),
    val sessionBundle: AttributionV2SessionBundle? = null,
)

data class AttributionV2ReplicateObservation(
    val order: List<Int>,
    val status: AttributionV2ArmStatus,
    val blockingCodes: List<String>,
    val cooldownThermalStatuses: List<Int>,
    val runs: List<AttributionV2EndToEndRun>,
)

/**
 * Bounded device-produced observation. This is intentionally distinct from
 * [M05BAttributionV2Evidence], the normalized pure-validation input.
 */
data class M05BAttributionV2DeviceObservation(
    val protocolSha256: String,
    val device: AttributionV2DeviceSource,
    val identity: AttributionV2RunIdentity,
    val sourceArm: AttributionV2SourceObservation,
    val backendArm: AttributionV2BackendObservation?,
    val selectorArm: AttributionV2SelectorObservation?,
    val endToEndReplicates: List<AttributionV2ReplicateObservation>,
    val blockingCodes: List<String>,
)

fun renderM05BAttributionV2Evidence(value: M05BAttributionV2DeviceObservation): String {
    validateObservationBounds(value)
    val derived = deriveM05BAttributionV2BlockingCodes(value)
    require(value.blockingCodes.sorted() == derived) {
        "device blocking codes must equal the locally derived blockers"
    }
    return buildJsonObject {
        put("evidence_schema_version", M05BAttributionV2Protocol.VERSION)
        put("record_kind", "m05b_attribution_v2_device_observation")
        put("protocol_sha256", value.protocolSha256)
        put("sanitized", true)
        put("non_participant_observation", true)
        put("device", renderDevice(value.device))
        put("identity", renderIdentity(value.identity))
        put("source_arm", renderSource(value.sourceArm))
        if (value.backendArm == null) put("backend_arm", null) else put("backend_arm", renderBackend(value.backendArm))
        if (value.selectorArm == null) put("selector_arm", null) else put("selector_arm", renderSelector(value.selectorArm))
        putJsonArray("end_to_end_replicates") {
            value.endToEndReplicates.forEach { add(renderReplicate(it)) }
        }
        putStringArray("blocking_codes", derived)
    }.toString()
}

/** Derives all blocker codes from raw device observation fields before serialization. */
fun deriveM05BAttributionV2BlockingCodes(value: M05BAttributionV2DeviceObservation): List<String> = (
    value.sourceArm.blockingCodes +
        (value.backendArm?.let { backend ->
            backend.blockingCodes + attributionV2BackendRawBlockers(backend)
        } ?: emptyList()) +
        if (value.backendArm != null && value.sourceArm.initialCooldownRecovered != true) {
            listOf("ATTRIBUTION.THERMAL_STATE_MISMATCH")
        } else {
            emptyList()
        } +
        (value.selectorArm?.blockingCodes ?: emptyList()) +
        value.endToEndReplicates.flatMap { replicate ->
            replicate.blockingCodes + replicate.runs.flatMap { run ->
                attributionV2CallbackBlockers(run.toCallbackArm())
            }
        } +
        validateAttributionV2(value.toValidationEvidence()).blockingCodes
    ).distinct().sorted()

private fun validateObservationBounds(value: M05BAttributionV2DeviceObservation) {
    require(V2_SHA256.matches(value.protocolSha256)) { "invalid protocol digest" }
    require(V2_GIT_SHA.matches(value.identity.appGitSha) && V2_GIT_SHA.matches(value.identity.probeGitSha)) {
        "invalid Git identity"
    }
    require(
        listOf(
            value.identity.appApkSha256,
            value.identity.testApkSha256,
            value.identity.modelSha256,
        ).all(V2_SHA256::matches),
    ) { "invalid binary identity" }
    require(value.sourceArm.measuredTimestampsNs.size <= M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS)
    require(
        (value.backendArm?.measuredServiceDurationsNs?.size ?: 0) <=
            M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS,
    )
    require(value.sourceArm.initialCooldownThermalStatuses.size <= cooldownMaximumSamples())
    value.backendArm?.let { backend ->
        require(backend.deliveredCallbackIds.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
        require(backend.terminalCallbacks.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
        require(backend.submittedInferenceIds.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
        require(backend.pendingInferenceIds.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
        validateEnvironmentBounds(backend.environment)
        backend.sessionBundle?.let(::validateSessionBundle)
    }
    require(value.endToEndReplicates.size <= M05BAttributionV2Protocol.REPLICATE_ORDERS.size)
    value.endToEndReplicates.forEach { replicate ->
        require(replicate.runs.size <= M05BAttributionV2Protocol.TARGET_CADENCES_FPS.size)
        require(replicate.cooldownThermalStatuses.size <= cooldownMaximumSamples())
        replicate.runs.forEach { run ->
            require(V2_SAFE_REFERENCE.matches(run.sessionReference)) { "unsafe session reference" }
            require(run.deliveredCallbackIds.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
            require(run.terminalCallbacks.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
            require(run.submittedInferenceIds.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
            require(run.pendingInferenceIds.size <= M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
            validateEnvironmentBounds(run.environment)
            run.sessionBundle?.let(::validateSessionBundle)
        }
    }
    validateEnvironmentBounds(value.sourceArm.environment)
}

private fun validateEnvironmentBounds(value: AttributionV2EnvironmentBoundary) {
    require(value.armStartedNs >= 0L && value.armEndedNs >= value.armStartedNs)
    listOf(value.start, value.end).forEach { snapshot ->
        require(snapshot.thermalStatus >= 0)
        require(snapshot.batteryTemperatureC.isFinite())
        require(snapshot.batteryLevelPercent in 0.0..100.0)
        require(snapshot.powerSource in setOf("battery", "usb", "wireless", "unknown"))
        require(snapshot.deliveredWidth > 0 && snapshot.deliveredHeight > 0)
        require(snapshot.deliveredFormat in setOf("YUV_420_888", "RGBA_8888"))
        require(snapshot.negotiatedCameraFpsRange.lower > 0)
        require(snapshot.negotiatedCameraFpsRange.upper >= snapshot.negotiatedCameraFpsRange.lower)
    }
}

private fun validateSessionBundle(value: AttributionV2SessionBundle) {
    require(V2_SAFE_REFERENCE.matches(value.sessionReference))
    listOf(value.manifest, value.frames, value.telemetry).forEach { artifact ->
        require(artifact.relativePath.startsWith("${value.sessionReference}/"))
        require(artifact.lengthBytes >= 0L)
        require(V2_SHA256.matches(artifact.sha256))
    }
}

private fun cooldownMaximumSamples(): Int =
    (M05BAttributionV2Protocol.COOLDOWN_MAX_NS / M05BAttributionV2Protocol.COOLDOWN_SAMPLE_NS).toInt() + 1

private fun M05BAttributionV2DeviceObservation.toValidationEvidence(): M05BAttributionV2Evidence {
    return M05BAttributionV2Evidence(
        protocolVersion = M05BAttributionV2Protocol.VERSION,
        identityMatches = true,
        environmentMatches = endToEndReplicates.all { replicate ->
            replicate.runs.all { it.initialThermalStatus == it.finalThermalStatus }
        },
        sourceArm = AttributionV2SourceArm(sourceArm.measuredTimestampsNs),
        backendArm = backendArm?.let {
            AttributionV2BackendArm(it.measuredServiceDurationsNs, it.inferenceFailures)
        },
        selectorArm = selectorArm?.let {
            AttributionV2SelectorArm(it.productionSelections, it.hostSelections)
        },
        callbackArm = backendArm?.let {
            AttributionV2CallbackArm(
                deliveredCallbackIds = it.deliveredCallbackIds,
                terminalCallbacks = it.terminalCallbacks,
                submittedInferenceIds = it.submittedInferenceIds,
                pendingInferenceIds = it.pendingInferenceIds,
            )
        },
        replicates = endToEndReplicates.map { replicate ->
            AttributionV2Replicate(
                cadenceOrder = replicate.order,
                admittedCompletionRates = attributionV2ExactRateOrderValues(replicate.runs, admitted = true),
                cadenceOpportunityRates = attributionV2ExactRateOrderValues(replicate.runs, admitted = false),
                sourceSupported = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith {
                    sourceSupports(sourceArm.measuredTimestampsNs, it)
                },
                backendSupported = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith {
                    backendArm?.let { arm -> backendSupports(arm.measuredServiceDurationsNs, it) } == true
                },
            )
        },
        deviceBlockingCodes = blockingCodes,
    )
}

private fun AttributionV2EndToEndRun.toCallbackArm() = AttributionV2CallbackArm(
    deliveredCallbackIds = deliveredCallbackIds,
    terminalCallbacks = terminalCallbacks,
    submittedInferenceIds = submittedInferenceIds,
    pendingInferenceIds = pendingInferenceIds,
)

private fun attributionV2BackendRawBlockers(value: AttributionV2BackendObservation): List<String> = buildList {
    if (value.rawEvidenceOverflow) add("ATTRIBUTION.CALLBACK_CONSERVATION")
    if (value.terminalCallbacks.any { it.phase == null }) {
        add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    }
    addAll(
        attributionV2CallbackBlockers(
            AttributionV2CallbackArm(
                deliveredCallbackIds = value.deliveredCallbackIds,
                terminalCallbacks = value.terminalCallbacks,
                submittedInferenceIds = value.submittedInferenceIds,
                pendingInferenceIds = value.pendingInferenceIds,
            ),
        ),
    )
    val completed = value.terminalCallbacks.filter {
        it.terminalClass == AttributionV2TerminalClass.ADMITTED_COMPLETED ||
            it.terminalClass == AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED
    }
    val warmup = completed.filter { it.phase == AttributionV2CallbackPhase.WARMUP }
    val measured = completed.filter { it.phase == AttributionV2CallbackPhase.MEASURED }
    if (value.terminalCallbacks.any { callback ->
            callback.admissionObservedSessionNs == null || callback.admissionObservedSessionNs < 0L
        }
    ) {
        add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    }
    val measuredDurationOperandsValid = measured.all { callback ->
        val admissionNs = callback.admissionObservedSessionNs
        val completionNs = callback.completionTimestampNs
        admissionNs != null && completionNs != null && completionNs >= admissionNs
    }
    if (!measuredDurationOperandsValid) {
        add("ATTRIBUTION.ADMISSION_STATE_DIVERGENCE")
    } else {
        val recomputedDurationsNs = measured.map { callback ->
            checkNotNull(callback.completionTimestampNs) - checkNotNull(callback.admissionObservedSessionNs)
        }
        if (recomputedDurationsNs != value.measuredServiceDurationsNs) {
            add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
        }
    }
    val warmupElapsedNs = warmup.mapNotNull { it.completionTimestampNs }
        .maxOrNull()?.minus(value.armStartedNs) ?: 0L
    if (warmup.size < M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS ||
        warmupElapsedNs < M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS ||
        measured.size != M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS
    ) add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
    val rawInferenceFailures = completed.count {
        it.terminalClass == AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED
    }
    if (rawInferenceFailures != value.inferenceFailures || rawInferenceFailures != 0) {
        add("ATTRIBUTION.INFERENCE_FAILURE")
    }
}

internal fun attributionV2ExactRateOrderValues(
    runs: List<AttributionV2EndToEndRun>,
    admitted: Boolean,
): Map<Int, Long> {
    data class Rate(val count: Long, val spanNs: Long)

    val rates = runs.associate { run ->
        val measured = run.terminalCallbacks.filter { it.phase == AttributionV2CallbackPhase.MEASURED }
        run.cadenceFps to Rate(
            count = if (admitted) {
                measured.count { it.terminalClass == AttributionV2TerminalClass.ADMITTED_COMPLETED }.toLong()
            } else {
                measured.count { it.cadenceDue }.toLong()
            },
            spanNs = run.measuredSpanNs,
        )
    }
    return M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith { cadence ->
        val current = rates[cadence] ?: Rate(0L, 0L)
        rates.values.count { other ->
            current.spanNs > 0L && other.spanNs > 0L &&
                other.count * current.spanNs < current.count * other.spanNs
        }.toLong()
    }
}

private fun renderDevice(value: AttributionV2DeviceSource): JsonObject = buildJsonObject {
    put("device_class", value.deviceClass)
    put("device_identity", value.deviceIdentity)
    put("manufacturer", value.manufacturer)
    put("model", value.model)
    put("api_level", value.apiLevel)
    put("android_release", value.androidRelease)
    put("os_build", value.osBuild)
}

private fun renderIdentity(value: AttributionV2RunIdentity): JsonObject = buildJsonObject {
    put("app_git_sha", value.appGitSha)
    put("probe_git_sha", value.probeGitSha)
    put("app_apk_sha256", value.appApkSha256)
    put("test_apk_sha256", value.testApkSha256)
    put("model_sha256", value.modelSha256)
    put("runtime_version", value.runtimeVersion)
    put("backend_version", value.backendVersion)
    put("delegate", value.delegate)
}

private fun renderSource(value: AttributionV2SourceObservation): JsonObject = buildJsonObject {
    put("status", value.status.wireValue)
    putStringArray("blocking_codes", value.blockingCodes.sorted())
    put("warmup_count", value.warmupCount)
    put("measured_count", value.measuredTimestampsNs.size)
    putLongArray("timestamps_ns", value.measuredTimestampsNs)
    value.observedAeTargetFps?.let { range ->
        putJsonObject("observed_ae_target_fps") {
            put("lower", range.lower)
            put("upper", range.upper)
        }
    } ?: put("observed_ae_target_fps", null)
    put("thermal_status", value.thermalStatus)
    putIntArray("initial_cooldown_thermal_statuses", value.initialCooldownThermalStatuses)
    value.initialCooldownRecovered?.let { put("initial_cooldown_recovered", it) } ?: put("initial_cooldown_recovered", null)
    put("environment", renderEnvironment(value.environment))
}

private fun renderBackend(value: AttributionV2BackendObservation): JsonObject = buildJsonObject {
    put("status", value.status.wireValue)
    putStringArray("blocking_codes", value.blockingCodes.sorted())
    put("warmup_count", value.warmupCount)
    put("warmup_elapsed_ns", value.warmupElapsedNs)
    put("measured_count", value.measuredServiceDurationsNs.size)
    putLongArray("service_durations_ns", value.measuredServiceDurationsNs)
    put("inference_failures", value.inferenceFailures)
    put("thermal_status", value.thermalStatus)
    put("arm_started_ns", value.armStartedNs)
    putJsonObject("warmup_predicates") {
        val completed = value.terminalCallbacks.filter {
            it.terminalClass == AttributionV2TerminalClass.ADMITTED_COMPLETED ||
                it.terminalClass == AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED
        }
        val warmup = completed.filter { it.phase == AttributionV2CallbackPhase.WARMUP }
        val elapsedNs = warmup.mapNotNull { it.completionTimestampNs }
            .maxOrNull()?.minus(value.armStartedNs) ?: 0L
        put("completion_count", warmup.size)
        put("elapsed_ns", elapsedNs)
        put("completion_count_satisfied", warmup.size >= M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS)
        put("elapsed_satisfied", elapsedNs >= M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS)
    }
    putLongArray("delivered_callback_ids", value.deliveredCallbackIds)
    putJsonArray("terminal_callbacks") { value.terminalCallbacks.forEach { add(renderCallback(it)) } }
    putLongArray("submitted_inference_ids", value.submittedInferenceIds)
    putLongArray("pending_inference_ids", value.pendingInferenceIds.sorted())
    put("raw_evidence_overflow", value.rawEvidenceOverflow)
    put("environment", renderEnvironment(value.environment))
    put(
        "session_bundle",
        renderSessionBundle(value.sessionBundle ?: syntheticSessionBundle(
            "exports/00000000-0000-4000-8000-000000000000",
        )),
    )
}

private fun renderSelector(value: AttributionV2SelectorObservation): JsonObject = buildJsonObject {
    put("status", value.status.wireValue)
    put("authoritative", false)
    putStringArray("blocking_codes", value.blockingCodes.sorted())
    putJsonArray("runs") {
        M05BAttributionV2Protocol.TARGET_CADENCES_FPS.forEach { cadence ->
            add(buildJsonObject {
                put("cadence_fps", cadence)
                putLongArray("production_selected_timestamps_ns", value.productionSelections[cadence].orEmpty())
                putLongArray("host_selected_timestamps_ns", value.hostSelections[cadence].orEmpty())
            })
        }
    }
}

private fun renderReplicate(value: AttributionV2ReplicateObservation): JsonObject = buildJsonObject {
    putIntArray("order", value.order)
    put("status", value.status.wireValue)
    putStringArray("blocking_codes", value.blockingCodes.sorted())
    putIntArray("cooldown_thermal_statuses", value.cooldownThermalStatuses)
    putJsonArray("runs") { value.runs.forEach { add(renderRun(it)) } }
}

private fun renderRun(value: AttributionV2EndToEndRun): JsonObject = buildJsonObject {
    put("cadence_fps", value.cadenceFps)
    put("session_reference", value.sessionReference)
    put("arm_started_ns", value.armStartedNs)
    put("initial_thermal_status", value.initialThermalStatus)
    put("final_thermal_status", value.finalThermalStatus)
    put("measured_span_ns", value.measuredSpanNs)
    putLongArray("delivered_callback_ids", value.deliveredCallbackIds)
    putJsonArray("terminal_callbacks") { value.terminalCallbacks.forEach { add(renderCallback(it)) } }
    putLongArray("submitted_inference_ids", value.submittedInferenceIds)
    putLongArray("pending_inference_ids", value.pendingInferenceIds.sorted())
    put("environment", renderEnvironment(value.environment))
    val sessionBundle = value.sessionBundle ?: syntheticSessionBundle(value.sessionReference)
    put("session_bundle", renderSessionBundle(sessionBundle))
}

private fun renderEnvironment(value: AttributionV2EnvironmentBoundary): JsonObject = buildJsonObject {
    put("arm_started_ns", value.armStartedNs)
    put("arm_ended_ns", value.armEndedNs)
    put("start", renderEnvironmentSnapshot(value.start))
    put("end", renderEnvironmentSnapshot(value.end))
}

private fun renderEnvironmentSnapshot(value: AttributionV2EnvironmentSnapshot): JsonObject = buildJsonObject {
    put("thermal_status", value.thermalStatus)
    put("battery_temperature_c", value.batteryTemperatureC)
    put("battery_level_percent", value.batteryLevelPercent)
    put("power_source", value.powerSource)
    put("charging", value.charging)
    put("wifi_transport", value.wifiTransport)
    put("screen_keep_awake", value.screenKeepAwake)
    put("device_idle", value.deviceIdle)
    putJsonObject("negotiated_camera_fps_range") {
        put("lower", value.negotiatedCameraFpsRange.lower)
        put("upper", value.negotiatedCameraFpsRange.upper)
    }
    put("delivered_width", value.deliveredWidth)
    put("delivered_height", value.deliveredHeight)
    put("delivered_format", value.deliveredFormat)
}

private fun renderSessionBundle(value: AttributionV2SessionBundle): JsonObject = buildJsonObject {
    put("session_reference", value.sessionReference)
    put("manifest", renderArtifactRef(value.manifest))
    put("frames", renderArtifactRef(value.frames))
    put("telemetry", renderArtifactRef(value.telemetry))
}

private fun renderArtifactRef(value: AttributionV2ArtifactRef): JsonObject = buildJsonObject {
    put("relative_path", value.relativePath)
    put("length_bytes", value.lengthBytes)
    put("sha256", value.sha256)
}

private fun syntheticSessionBundle(sessionReference: String): AttributionV2SessionBundle {
    val base = "$sessionReference/"
    fun ref(name: String, digest: String) = AttributionV2ArtifactRef("$base$name", 0L, digest)
    return AttributionV2SessionBundle(
        sessionReference = sessionReference,
        manifest = ref("manifest.json", "0".repeat(64)),
        frames = ref("frames.ndjson", "1".repeat(64)),
        telemetry = ref("telemetry.ndjson", "2".repeat(64)),
    )
}

private fun renderCallback(value: AttributionV2Callback): JsonObject = buildJsonObject {
    put("callback_id", value.callbackId)
    put("capture_timestamp_ns", value.captureTimestampNs)
    put("production_classification", value.productionClassification.wireValue())
    put("cadence_due", value.cadenceDue)
    put("slot_available", value.slotAvailable)
    put("terminal_class", value.terminalClass.name.lowercase())
    if (value.phase == null) put("phase", null) else put("phase", value.phase.name.lowercase())
    if (value.admissionObservedSessionNs == null) {
        put("admission_observed_session_ns", null)
    } else {
        put("admission_observed_session_ns", value.admissionObservedSessionNs)
    }
    if (value.completionTimestampNs == null) {
        put("completion_timestamp_ns", null)
    } else {
        put("completion_timestamp_ns", value.completionTimestampNs)
    }
}

private fun LiveFrameClassification.wireValue(): String = when (this) {
    LiveFrameClassification.SUBMIT -> "submit"
    LiveFrameClassification.CADENCE_SELECTION -> "cadence_selection"
    LiveFrameClassification.BACKPRESSURE -> "backpressure"
    LiveFrameClassification.PREPROCESSING_FAILED -> "preprocessing_failed"
    LiveFrameClassification.SESSION_STOPPING -> "session_stopping"
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putStringArray(name: String, values: List<String>) {
    put(name, JsonArray(values.map(::JsonPrimitive)))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putLongArray(name: String, values: List<Long>) {
    put(name, buildJsonArray { values.forEach(::add) })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIntArray(name: String, values: List<Int>) {
    put(name, buildJsonArray { values.forEach(::add) })
}
