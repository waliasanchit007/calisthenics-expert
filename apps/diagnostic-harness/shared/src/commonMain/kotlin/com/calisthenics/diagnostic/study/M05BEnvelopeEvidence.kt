package com.calisthenics.diagnostic.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class M05BStartEnvelope(
    val appSha256: String,
    val modelSha256: String,
    val runtimeVersion: String,
    val delegate: String,
    val preprocessingVersion: String,
    val deviceUnitDigest: String,
    val deviceClass: String,
    val manufacturer: String,
    val model: String,
    val deviceBuild: String,
    val apiLevel: Int,
    val charging: Boolean,
    val powerSource: String,
    val hostBatteryOverrideRejected: Boolean,
    val hostAdbTransport: String,
    val appTransport: String,
    val networkIdentityPersisted: Boolean,
    val ambientToken: String,
    val ambientConstancy: String,
    val keepAwake: Boolean,
    val brightnessMode: String,
    val brightnessRaw: Double,
    val brightnessScaleMin: Double,
    val brightnessScaleMax: Double,
    val cameraFps: Int = 30,
    val inferenceCadencesFps: List<Int> = listOf(10, 15, 30),
    val durationNs: Long = 1_500_000_000_000,
    val overshootNs: Long = 1_000_000_000,
    val cooldownMaxDurationNs: Long = 600_000_000_000,
    val cooldownSamplingIntervalNs: Long = 1_000_000_000,
    val thermalStatus: String = "none",
    val batteryTemperatureMinC: Double = 29.0,
    val batteryTemperatureMaxC: Double = 30.0,
    val batteryTemperatureRangeC: Double = 1.0,
)

data class M05BObservedStart(
    val appSha256: String?,
    val modelSha256: String?,
    val runtimeVersion: String?,
    val delegate: String?,
    val preprocessingVersion: String?,
    val deviceUnitDigest: String?,
    val deviceClass: String?,
    val manufacturer: String?,
    val model: String?,
    val deviceBuild: String?,
    val apiLevel: Int?,
    val charging: Boolean?,
    val powerSource: String?,
    val hostBatteryOverrideRejected: Boolean?,
    val hostAdbTransport: String?,
    val appTransport: String?,
    val networkIdentityPersisted: Boolean?,
    val ambientToken: String?,
    val ambientConstancy: String?,
    val keepAwake: Boolean?,
    val brightnessMode: String?,
    val brightnessRaw: Double?,
    val brightnessScaleMin: Double?,
    val brightnessScaleMax: Double?,
    val thermalStatus: String? = "none",
    val batteryTemperatureMinC: Double? = 29.0,
    val batteryTemperatureMaxC: Double? = 30.0,
    val batteryTemperatureRangeC: Double? = 1.0,
)

data class M05BExecutionOrder(val cadencesFps: List<Int>)

enum class M05BPredecessorStatus { NOT_APPLICABLE, COMPLETED, BLOCKED, UNAVAILABLE }

data class M05BRunAdmissionResult(
    val admitted: Boolean,
    val blockingCodes: List<String>,
    val evaluatedConjunctIds: List<String>,
)

object M05BRunAdmission {
    val REQUIRED_CONJUNCT_IDS: List<String> = listOf(
        "DEVICE.CLASS", "DEVICE.MANUFACTURER", "DEVICE.MODEL", "DEVICE.BUILD", "DEVICE.API",
        "IDENTITY.APP_DIGEST", "IDENTITY.MODEL_DIGEST", "IDENTITY.RUNTIME", "IDENTITY.DELEGATE",
        "IDENTITY.PREPROCESSING", "IDENTITY.DEVICE_UNIT", "THERMAL.STATUS", "THERMAL.RANGE",
        "POWER.CHARGING", "POWER.SOURCE",
        "POWER.HOST_OVERRIDE", "CONNECTIVITY.HOST_WIFI_ADB", "CONNECTIVITY.APP_WIFI",
        "AMBIENT.TOKEN", "AMBIENT.CONSTANCY", "DISPLAY.KEEP_AWAKE", "DISPLAY.BRIGHTNESS_MODE",
        "DISPLAY.BRIGHTNESS_VALUE", "DISPLAY.BRIGHTNESS_RANGE", "PROTOCOL.CAMERA_FPS",
        "PROTOCOL.CADENCE_SET", "PROTOCOL.DURATION", "PROTOCOL.OVERSHOOT", "PROTOCOL.COOLDOWN",
        "ORDER.PERMITTED", "SESSION.IDENTITY", "SESSION.INPUT_CLASS", "SESSION.NO_PERSON",
    )

    fun evaluate(
        envelope: M05BStartEnvelope,
        order: M05BExecutionOrder,
        observedStart: M05BObservedStart,
        sessionId: String,
        cadenceFps: Int,
        attemptSequence: Int,
        predecessorStatus: M05BPredecessorStatus,
        inputClass: String = "empty_scene",
        operatorAttestedNoPerson: Boolean = true,
    ): M05BRunAdmissionResult {
        val blocking = mutableListOf<String>()
        compare(envelope.appSha256, observedStart.appSha256, "IDENTITY.APP_DIGEST_MISMATCH", blocking)
        compare(envelope.modelSha256, observedStart.modelSha256, "IDENTITY.MODEL_DIGEST_MISMATCH", blocking)
        compare(envelope.runtimeVersion, observedStart.runtimeVersion, "IDENTITY.RUNTIME_MISMATCH", blocking)
        compare(envelope.delegate, observedStart.delegate, "IDENTITY.DELEGATE_MISMATCH", blocking)
        compare(envelope.preprocessingVersion, observedStart.preprocessingVersion, "IDENTITY.PREPROCESSING_MISMATCH", blocking)
        compare(envelope.deviceUnitDigest, observedStart.deviceUnitDigest, "IDENTITY.DEVICE_UNIT_MISMATCH", blocking)
        compare(envelope.deviceClass, observedStart.deviceClass, "DEVICE.CLASS_MISMATCH", blocking)
        compare(envelope.manufacturer, observedStart.manufacturer, "DEVICE.MANUFACTURER_MISMATCH", blocking)
        compare(envelope.model, observedStart.model, "DEVICE.MODEL_MISMATCH", blocking)
        compare(envelope.deviceBuild, observedStart.deviceBuild, "DEVICE.BUILD_MISMATCH", blocking)
        compare(envelope.apiLevel, observedStart.apiLevel, "DEVICE.API_MISMATCH", blocking)
        compare(envelope.charging, observedStart.charging, "POWER.CHARGING", blocking)
        compare(envelope.powerSource, observedStart.powerSource, "POWER.SOURCE_NOT_BATTERY", blocking)
        compare(envelope.hostBatteryOverrideRejected, observedStart.hostBatteryOverrideRejected, "POWER.HOST_OVERRIDE", blocking)
        compare(envelope.hostAdbTransport, observedStart.hostAdbTransport, "CONNECTIVITY.HOST_NOT_WIFI_ADB", blocking)
        compare(envelope.appTransport, observedStart.appTransport, "CONNECTIVITY.APP_NOT_WIFI", blocking)
        compare(envelope.networkIdentityPersisted, observedStart.networkIdentityPersisted, "CONNECTIVITY.NETWORK_IDENTITY", blocking)
        compare(envelope.ambientToken, observedStart.ambientToken, "AMBIENT.TOKEN", blocking)
        compare(envelope.ambientConstancy, observedStart.ambientConstancy, "AMBIENT.NOT_CONSTANT", blocking)
        compare(envelope.thermalStatus, observedStart.thermalStatus, "THERMAL.STATUS", blocking)
        compare(envelope.batteryTemperatureMinC, observedStart.batteryTemperatureMinC, "THERMAL.RANGE", blocking)
        compare(envelope.batteryTemperatureMaxC, observedStart.batteryTemperatureMaxC, "THERMAL.RANGE", blocking)
        compare(envelope.batteryTemperatureRangeC, observedStart.batteryTemperatureRangeC, "THERMAL.RANGE", blocking)
        compare(envelope.keepAwake, observedStart.keepAwake, "DISPLAY.KEEP_AWAKE", blocking)
        compare(envelope.brightnessMode, observedStart.brightnessMode, "DISPLAY.BRIGHTNESS_NOT_MANUAL", blocking)
        compare(envelope.brightnessRaw, observedStart.brightnessRaw, "DISPLAY.BRIGHTNESS_VALUE", blocking)
        compare(envelope.brightnessScaleMin, observedStart.brightnessScaleMin, "DISPLAY.BRIGHTNESS_RANGE", blocking)
        compare(envelope.brightnessScaleMax, observedStart.brightnessScaleMax, "DISPLAY.BRIGHTNESS_RANGE", blocking)
        if (observedStart.brightnessRaw == null) blocking += "DISPLAY.BRIGHTNESS_UNAVAILABLE"
        if (observedStart.brightnessScaleMin == null || observedStart.brightnessScaleMax == null) blocking += "DISPLAY.BRIGHTNESS_UNAVAILABLE"
        if (observedStart.brightnessRaw != null && observedStart.brightnessScaleMin != null && observedStart.brightnessScaleMax != null &&
            observedStart.brightnessRaw !in observedStart.brightnessScaleMin..observedStart.brightnessScaleMax) {
            blocking += "DISPLAY.BRIGHTNESS_RANGE"
        }
        if (envelope.cameraFps != 30) blocking += "PROTOCOL.CAMERA_FPS"
        if (envelope.inferenceCadencesFps != listOf(10, 15, 30)) blocking += "PROTOCOL.CADENCE_SET"
        if (envelope.durationNs != 1_500_000_000_000) blocking += "PROTOCOL.DURATION"
        if (envelope.overshootNs != 1_000_000_000L) blocking += "PROTOCOL.OVERSHOOT"
        if (envelope.cooldownMaxDurationNs <= 0L || envelope.cooldownSamplingIntervalNs <= 0L) {
            blocking += "PROTOCOL.COOLDOWN"
        }
        if (envelope.deviceClass != "physical_device") blocking += "DEVICE.CLASS"
        if (envelope.manufacturer != "samsung") blocking += "DEVICE.MANUFACTURER"
        if (!Regex("^SM-S938[A-Z0-9]*$").matches(envelope.model)) blocking += "DEVICE.MODEL"
        if (envelope.apiLevel != 36) blocking += "DEVICE.API"
        if (envelope.charging || envelope.powerSource != "battery" || !envelope.hostBatteryOverrideRejected) {
            blocking += "POWER.PROTOCOL"
        }
        if (envelope.hostAdbTransport != "wifi" || envelope.appTransport != "wifi" || envelope.networkIdentityPersisted) {
            blocking += "CONNECTIVITY.PROTOCOL"
        }
        if (envelope.ambientConstancy !in listOf("constant", "changed", "unavailable")) blocking += "AMBIENT.CONSTANCY"
        if (!envelope.keepAwake || envelope.brightnessMode != "manual") blocking += "DISPLAY.PROTOCOL"
        if (order.cadencesFps != listOf(10, 15, 30) || cadenceFps !in listOf(10, 15, 30)) blocking += "ORDER.PERMITTED"
        if (cadenceFps != 10 && predecessorStatus != M05BPredecessorStatus.COMPLETED) blocking += "ATTEMPT.PREDECESSOR_INCOMPLETE"
        if (!Regex("^00000000-0000-4000-8000-[0-9a-f]{12}$").matches(sessionId)) blocking += "SESSION.IDENTITY"
        if (attemptSequence < 1) blocking += "ATTEMPT.SEQUENCE"
        if (inputClass !in listOf("empty_scene", "displayed_synthetic_figure")) blocking += "SESSION.INPUT_CLASS"
        if (!operatorAttestedNoPerson) blocking += "SESSION.NO_PERSON"
        if (envelope.ambientConstancy != "constant") blocking += "AMBIENT.NOT_CONSTANT"
        return M05BRunAdmissionResult(true == blocking.distinct().isEmpty(), blocking.distinct(), REQUIRED_CONJUNCT_IDS)
    }

    private fun compare(expected: Any?, actual: Any?, code: String, blocking: MutableList<String>) {
        if (actual == null || expected != actual) blocking += code
    }
}

data class M05BStartEnvelopeEvidence(
    val envelope: M05BStartEnvelope,
    val order: M05BExecutionOrder,
    val observedStart: M05BObservedStart,
)

fun renderM05BStartEnvelopeEvidence(evidence: M05BStartEnvelopeEvidence): String {
    val result = M05BRunAdmission.evaluate(
        evidence.envelope, evidence.order, evidence.observedStart,
        "00000000-0000-4000-8000-000000000001", 10, 1, M05BPredecessorStatus.NOT_APPLICABLE,
    )
    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", "1.0.0")
        put("record_kind", "m05b_start_envelope_device_gate_observation")
        put("sanitized", true)
        put("non_participant_observation", true)
        put("envelope", buildJsonObject {
            put("device_class", evidence.envelope.deviceClass)
            put("manufacturer", evidence.envelope.manufacturer)
            put("model", evidence.envelope.model)
            put("device_build", evidence.envelope.deviceBuild)
            put("api_level", evidence.envelope.apiLevel)
            put("app_sha256", evidence.envelope.appSha256)
            put("model_sha256", evidence.envelope.modelSha256)
            put("runtime_version", evidence.envelope.runtimeVersion)
            put("delegate", evidence.envelope.delegate)
            put("preprocessing_version", evidence.envelope.preprocessingVersion)
            put("device_unit_digest", evidence.envelope.deviceUnitDigest)
            put("charging", evidence.envelope.charging)
            put("power_source", evidence.envelope.powerSource)
            put("host_adb_transport", evidence.envelope.hostAdbTransport)
            put("app_transport", evidence.envelope.appTransport)
            put("network_identity_persisted", evidence.envelope.networkIdentityPersisted)
            put("ambient_token", evidence.envelope.ambientToken)
            put("ambient_constancy", evidence.envelope.ambientConstancy)
            put("keep_awake", evidence.envelope.keepAwake)
            put("brightness_mode", evidence.envelope.brightnessMode)
            put("brightness_raw", evidence.envelope.brightnessRaw)
            put("brightness_scale_min", evidence.envelope.brightnessScaleMin)
            put("brightness_scale_max", evidence.envelope.brightnessScaleMax)
            put("thermal_status", evidence.envelope.thermalStatus)
            put("temperature_min_c", evidence.envelope.batteryTemperatureMinC)
            put("temperature_max_c", evidence.envelope.batteryTemperatureMaxC)
            put("temperature_range_c", evidence.envelope.batteryTemperatureRangeC)
            put("camera_fps", evidence.envelope.cameraFps)
            put("duration_ns", evidence.envelope.durationNs)
            put("overshoot_ns", evidence.envelope.overshootNs)
            put("cooldown_max_duration_ns", evidence.envelope.cooldownMaxDurationNs)
            put("cooldown_sampling_interval_ns", evidence.envelope.cooldownSamplingIntervalNs)
        })
        putJsonArray("execution_order") { evidence.order.cadencesFps.forEach(::add) }
        put("observed_start", buildJsonObject {
            put("device_class", evidence.observedStart.deviceClass)
            put("manufacturer", evidence.observedStart.manufacturer)
            put("model", evidence.observedStart.model)
            put("device_build", evidence.observedStart.deviceBuild)
            put("api_level", evidence.observedStart.apiLevel)
            put("app_sha256", evidence.observedStart.appSha256)
            put("model_sha256", evidence.observedStart.modelSha256)
            put("runtime_version", evidence.observedStart.runtimeVersion)
            put("delegate", evidence.observedStart.delegate)
            put("preprocessing_version", evidence.observedStart.preprocessingVersion)
            put("device_unit_digest", evidence.observedStart.deviceUnitDigest)
            put("charging", evidence.observedStart.charging)
            put("power_source", evidence.observedStart.powerSource)
            put("host_adb_transport", evidence.observedStart.hostAdbTransport)
            put("app_transport", evidence.observedStart.appTransport)
            put("network_identity_persisted", evidence.observedStart.networkIdentityPersisted)
            put("ambient_token", evidence.observedStart.ambientToken)
            put("ambient_constancy", evidence.observedStart.ambientConstancy)
            put("keep_awake", evidence.observedStart.keepAwake)
            put("brightness_mode", evidence.observedStart.brightnessMode)
            put("brightness_raw", evidence.observedStart.brightnessRaw)
            put("brightness_scale_min", evidence.observedStart.brightnessScaleMin)
            put("brightness_scale_max", evidence.observedStart.brightnessScaleMax)
            put("thermal_status", evidence.observedStart.thermalStatus)
            put("temperature_min_c", evidence.observedStart.batteryTemperatureMinC)
            put("temperature_max_c", evidence.observedStart.batteryTemperatureMaxC)
            put("temperature_range_c", evidence.observedStart.batteryTemperatureRangeC)
        })
        putJsonArray("blocking_codes") { result.blockingCodes.forEach(::add) }
        put("satisfies_s25_m05b_admission", result.admitted)
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}
