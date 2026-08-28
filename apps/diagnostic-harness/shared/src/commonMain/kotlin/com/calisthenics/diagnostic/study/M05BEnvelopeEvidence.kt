package com.calisthenics.diagnostic.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class M05BStartEnvelope(
    val envelopeId: String,
    val procedureVersion: String,
    val procedureSha256: String,
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
    val powerSaveMode: Boolean,
    val baselineBatteryObservation: M05BBatteryObservation?,
    val hostAdbTransport: String,
    val appTransport: String,
    val networkIdentityPersisted: Boolean,
    val ambientToken: String,
    val ambientConstancy: String,
    val ambientConstancySource: String,
    val keepAwake: Boolean,
    val brightnessMode: String,
    val brightnessRaw: Double,
    val brightnessScaleMin: Double,
    val brightnessScaleMax: Double,
    val cameraFps: Int,
    val inferenceCadencesFps: List<Int>,
    val durationNs: Long,
    val overshootNs: Long,
    val cooldownMaxDurationNs: Long,
    val cooldownSamplingIntervalNs: Long,
    val thermalStatus: String,
    val batteryTemperatureMinC: Double,
    val batteryTemperatureMaxC: Double,
    val batteryTemperatureRangeC: Double,
    val orderPath: String,
    val orderSha256: String,
    val producerGitSha: String,
)

data class M05BBatteryObservation(val unit: String, val value: Double)

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
    val powerSaveMode: Boolean?,
    val batteryObservation: M05BBatteryObservation?,
    val deviceIdleMode: Boolean?,
    val hostAdbTransport: String?,
    val appTransport: String?,
    val networkIdentityPersisted: Boolean?,
    val ambientToken: String?,
    val ambientConstancy: String?,
    val ambientConstancyOperatorSupplied: Boolean?,
    val keepAwake: Boolean?,
    val brightnessMode: String?,
    val brightnessRaw: Double?,
    val brightnessScaleMin: Double?,
    val brightnessScaleMax: Double?,
    val thermalStatus: String?,
    val batteryTemperatureC: Double?,
)

data class M05BExecutionOrder(
    val cadencesFps: List<Int>,
    val orderId: String,
    val producerGitSha: String,
)

enum class M05BPredecessorStatus { NOT_APPLICABLE, COMPLETED, BLOCKED, UNAVAILABLE }

data class M05BRunAdmissionResult(
    val admitted: Boolean,
    val blockingCodes: List<String>,
    val evaluatedConjunctIds: List<String>,
)

internal object M05BRunAdmission {
    val REQUIRED_CONJUNCT_IDS: List<String> = listOf(
        "DEVICE.CLASS", "DEVICE.MANUFACTURER", "DEVICE.MODEL", "DEVICE.BUILD", "DEVICE.API",
        "IDENTITY.APP_DIGEST", "IDENTITY.MODEL_DIGEST", "IDENTITY.RUNTIME", "IDENTITY.DELEGATE",
        "IDENTITY.PREPROCESSING", "IDENTITY.DEVICE_UNIT", "THERMAL.STATUS", "THERMAL.RANGE",
        "POWER.CHARGING", "POWER.SOURCE",
        "POWER.HOST_OVERRIDE", "POWER.SAVE_MODE", "CONNECTIVITY.HOST_WIFI_ADB", "CONNECTIVITY.APP_WIFI",
        "AMBIENT.TOKEN", "AMBIENT.CONSTANCY", "DISPLAY.KEEP_AWAKE", "DISPLAY.BRIGHTNESS_MODE",
        "DISPLAY.BRIGHTNESS_VALUE", "DISPLAY.BRIGHTNESS_RANGE", "PROTOCOL.CAMERA_FPS",
        "PROTOCOL.CADENCE_SET", "PROTOCOL.DURATION", "PROTOCOL.OVERSHOOT", "PROTOCOL.COOLDOWN",
        "ORDER.PERMITTED", "SESSION.IDENTITY", "SESSION.INPUT_CLASS", "SESSION.NO_PERSON",
    )

    fun evaluateDerived(
        envelope: M05BStartEnvelope,
        order: M05BExecutionOrder,
        observedStart: M05BObservedStart,
        sessionId: String,
        attemptContext: M05BAttemptContext,
        inputClass: String,
        operatorAttestedNoPerson: Boolean,
    ): M05BRunAdmissionResult {
        val blocking = mutableListOf<String>()
        val evaluated = mutableListOf<String>()
        fun evaluate(id: String, check: () -> Unit) {
            check()
            evaluated += id
        }

        evaluate("DEVICE.CLASS") {
            compare(envelope.deviceClass, observedStart.deviceClass, "DEVICE.CLASS_MISMATCH", blocking)
            if (envelope.deviceClass != "physical_device") blocking += "DEVICE.CLASS"
        }
        evaluate("DEVICE.MANUFACTURER") {
            compare(envelope.manufacturer, observedStart.manufacturer, "DEVICE.MANUFACTURER_MISMATCH", blocking)
            if (envelope.manufacturer != "samsung") blocking += "DEVICE.MANUFACTURER"
        }
        evaluate("DEVICE.MODEL") {
            compare(envelope.model, observedStart.model, "DEVICE.MODEL_MISMATCH", blocking)
            if (!Regex("^SM-S938[A-Z0-9]*$").matches(envelope.model)) blocking += "DEVICE.MODEL"
        }
        evaluate("DEVICE.BUILD") { compare(envelope.deviceBuild, observedStart.deviceBuild, "DEVICE.BUILD_MISMATCH", blocking) }
        evaluate("DEVICE.API") {
            compare(envelope.apiLevel, observedStart.apiLevel, "DEVICE.API_MISMATCH", blocking)
            if (envelope.apiLevel != 36) blocking += "DEVICE.API"
        }
        evaluate("IDENTITY.APP_DIGEST") { compare(envelope.appSha256, observedStart.appSha256, "IDENTITY.APP_DIGEST_MISMATCH", blocking) }
        evaluate("IDENTITY.MODEL_DIGEST") { compare(envelope.modelSha256, observedStart.modelSha256, "IDENTITY.MODEL_DIGEST_MISMATCH", blocking) }
        evaluate("IDENTITY.RUNTIME") { compare(envelope.runtimeVersion, observedStart.runtimeVersion, "IDENTITY.RUNTIME_MISMATCH", blocking) }
        evaluate("IDENTITY.DELEGATE") { compare(envelope.delegate, observedStart.delegate, "IDENTITY.DELEGATE_MISMATCH", blocking) }
        evaluate("IDENTITY.PREPROCESSING") { compare(envelope.preprocessingVersion, observedStart.preprocessingVersion, "IDENTITY.PREPROCESSING_MISMATCH", blocking) }
        evaluate("IDENTITY.DEVICE_UNIT") { compare(envelope.deviceUnitDigest, observedStart.deviceUnitDigest, "IDENTITY.DEVICE_UNIT_MISMATCH", blocking) }
        evaluate("THERMAL.STATUS") { compare(envelope.thermalStatus, observedStart.thermalStatus, "THERMAL.STATUS", blocking) }
        evaluate("THERMAL.RANGE") {
            val temperature = observedStart.batteryTemperatureC
            if (temperature == null || !temperature.isFinite() ||
                temperature !in envelope.batteryTemperatureMinC..envelope.batteryTemperatureMaxC
            ) blocking += "THERMAL.RANGE"
        }
        evaluate("POWER.CHARGING") {
            compare(envelope.charging, observedStart.charging, "POWER.CHARGING", blocking)
            if (envelope.charging) blocking += "POWER.CHARGING"
        }
        evaluate("POWER.SOURCE") {
            compare(envelope.powerSource, observedStart.powerSource, "POWER.SOURCE_NOT_BATTERY", blocking)
            if (envelope.powerSource != "battery") blocking += "POWER.SOURCE_NOT_BATTERY"
        }
        evaluate("POWER.HOST_OVERRIDE") {
            compare(envelope.hostBatteryOverrideRejected, observedStart.hostBatteryOverrideRejected, "POWER.HOST_OVERRIDE", blocking)
            if (!envelope.hostBatteryOverrideRejected) blocking += "POWER.HOST_OVERRIDE"
        }
        evaluate("POWER.SAVE_MODE") {
            compare(envelope.powerSaveMode, observedStart.powerSaveMode, "POWER.SAVE_MODE", blocking)
            if (envelope.powerSaveMode || observedStart.powerSaveMode != false) blocking += "POWER.SAVE_MODE"
        }
        evaluate("CONNECTIVITY.HOST_WIFI_ADB") {
            compare(envelope.hostAdbTransport, observedStart.hostAdbTransport, "CONNECTIVITY.HOST_NOT_WIFI_ADB", blocking)
            if (envelope.hostAdbTransport != "wifi") blocking += "CONNECTIVITY.HOST_NOT_WIFI_ADB"
        }
        evaluate("CONNECTIVITY.APP_WIFI") {
            compare(envelope.appTransport, observedStart.appTransport, "CONNECTIVITY.APP_NOT_WIFI", blocking)
            compare(envelope.networkIdentityPersisted, observedStart.networkIdentityPersisted, "CONNECTIVITY.NETWORK_IDENTITY", blocking)
            if (envelope.appTransport != "wifi" || envelope.networkIdentityPersisted) blocking += "CONNECTIVITY.APP_NOT_WIFI"
        }
        evaluate("AMBIENT.TOKEN") { compare(envelope.ambientToken, observedStart.ambientToken, "AMBIENT.TOKEN", blocking) }
        evaluate("AMBIENT.CONSTANCY") {
            compare(envelope.ambientConstancy, observedStart.ambientConstancy, "AMBIENT.NOT_CONSTANT", blocking)
            compare(true, observedStart.ambientConstancyOperatorSupplied, "AMBIENT.ATTESTATION_MISSING", blocking)
            if (envelope.ambientConstancy != "constant") blocking += "AMBIENT.NOT_CONSTANT"
        }
        evaluate("DISPLAY.KEEP_AWAKE") { compare(envelope.keepAwake, observedStart.keepAwake, "DISPLAY.KEEP_AWAKE", blocking) }
        evaluate("DISPLAY.BRIGHTNESS_MODE") { compare(envelope.brightnessMode, observedStart.brightnessMode, "DISPLAY.BRIGHTNESS_NOT_MANUAL", blocking) }
        evaluate("DISPLAY.BRIGHTNESS_VALUE") {
            compare(envelope.brightnessRaw, observedStart.brightnessRaw, "DISPLAY.BRIGHTNESS_VALUE", blocking)
            if (observedStart.brightnessRaw == null) blocking += "DISPLAY.BRIGHTNESS_UNAVAILABLE"
        }
        evaluate("DISPLAY.BRIGHTNESS_RANGE") {
            compare(envelope.brightnessScaleMin, observedStart.brightnessScaleMin, "DISPLAY.BRIGHTNESS_RANGE", blocking)
            compare(envelope.brightnessScaleMax, observedStart.brightnessScaleMax, "DISPLAY.BRIGHTNESS_RANGE", blocking)
            val brightness = observedStart.brightnessRaw
            val minimum = observedStart.brightnessScaleMin
            val maximum = observedStart.brightnessScaleMax
            if (minimum == null || maximum == null) blocking += "DISPLAY.BRIGHTNESS_UNAVAILABLE"
            if (brightness != null && minimum != null && maximum != null && brightness !in minimum..maximum) {
                blocking += "DISPLAY.BRIGHTNESS_RANGE"
            }
        }
        evaluate("PROTOCOL.CAMERA_FPS") { if (envelope.cameraFps != 30) blocking += "PROTOCOL.CAMERA_FPS" }
        evaluate("PROTOCOL.CADENCE_SET") { if (envelope.inferenceCadencesFps != listOf(10, 15, 30)) blocking += "PROTOCOL.CADENCE_SET" }
        evaluate("PROTOCOL.DURATION") { if (envelope.durationNs != 1_500_000_000_000L) blocking += "PROTOCOL.DURATION" }
        evaluate("PROTOCOL.OVERSHOOT") { if (envelope.overshootNs != 1_000_000_000L) blocking += "PROTOCOL.OVERSHOOT" }
        evaluate("PROTOCOL.COOLDOWN") {
            if (envelope.cooldownMaxDurationNs != 600_000_000_000L ||
                envelope.cooldownSamplingIntervalNs != 1_000_000_000L
            ) blocking += "PROTOCOL.COOLDOWN"
        }
        evaluate("ORDER.PERMITTED") {
            if (order.cadencesFps != listOf(10, 15, 30) || attemptContext.cadenceFps !in listOf(10, 15, 30)) {
                blocking += "ORDER.PERMITTED"
            }
            if (attemptContext.cadenceFps != 10 && attemptContext.predecessorStatus != M05BPredecessorStatus.COMPLETED) {
                blocking += "ATTEMPT.PREDECESSOR_INCOMPLETE"
            }
            if (attemptContext.attemptSequence < 1) blocking += "ATTEMPT.SEQUENCE"
            blocking += attemptContext.blockingCodes
        }
        evaluate("SESSION.IDENTITY") {
            if (!Regex("^00000000-0000-4000-8000-[0-9a-f]{12}$").matches(sessionId)) blocking += "SESSION.IDENTITY"
        }
        evaluate("SESSION.INPUT_CLASS") {
            if (inputClass !in listOf("empty_scene", "displayed_synthetic_figure")) blocking += "SESSION.INPUT_CLASS"
        }
        evaluate("SESSION.NO_PERSON") { if (!operatorAttestedNoPerson) blocking += "SESSION.NO_PERSON" }
        check(evaluated == REQUIRED_CONJUNCT_IDS) { "admission did not execute the released conjunct set in order" }
        return M05BRunAdmissionResult(blocking.distinct().isEmpty(), blocking.distinct(), evaluated)
    }

    private fun compare(expected: Any?, actual: Any?, code: String, blocking: MutableList<String>) {
        if (actual == null || expected != actual) blocking += code
    }
}

data class M05BStartEnvelopeEvidence(
    val envelope: M05BStartEnvelope?,
    val order: M05BExecutionOrder,
    val observedStart: M05BObservedStart,
    val baselineBlockingCodes: List<String> = emptyList(),
)

fun renderM05BStartEnvelopeEvidence(evidence: M05BStartEnvelopeEvidence): String {
    val envelope = evidence.envelope
    val orderArtifact = M05BArtifactCodec.bindOrder(
        evidence.order,
        envelope?.orderPath ?: "execution-order.json",
    )
    val envelopeArtifact = envelope?.let { M05BArtifactCodec.bindEnvelope(it, "envelope.json") }
    val result = envelope?.let {
        M05BRunAdmission.evaluateDerived(
            it,
            evidence.order,
            evidence.observedStart,
            "00000000-0000-4000-8000-000000000001",
            M05BAttemptChain.derive(evidence.order, emptyList()),
            "empty_scene",
            true,
        )
    }
    val bindingCodes = if (envelope == null) {
        evidence.baselineBlockingCodes.ifEmpty { listOf("BASELINE.BLOCKED") }
    } else {
        buildList {
            addAll(evidence.baselineBlockingCodes)
            if (envelope.orderSha256 != orderArtifact.sha256) add("ORDER.DIGEST")
            if (envelope.orderPath != orderArtifact.path) add("ORDER.PATH")
            addAll(result?.blockingCodes.orEmpty())
        }.distinct()
    }
    val record: JsonObject = buildJsonObject {
        put("artifact_schema_version", "1.0.0")
        put("record_kind", "m05b_start_envelope_device_gate_observation")
        put("sanitized", true)
        put("non_participant_observation", true)
        if (envelopeArtifact != null) {
            put("envelope_path", envelopeArtifact.path)
            put("envelope_sha256", envelopeArtifact.sha256)
            put("envelope_json", envelopeArtifact.exactBytes.decodeToString())
        }
        put("order_path", orderArtifact.path)
        put("order_sha256", orderArtifact.sha256)
        put("execution_order_json", orderArtifact.exactBytes.decodeToString())
        put("observed_start", M05BArtifactCodec.canonicalObservedStart(evidence.observedStart))
        putJsonArray("blocking_codes") { bindingCodes.forEach(::add) }
        put("satisfies_s25_m05b_admission", envelope != null && bindingCodes.isEmpty())
    }
    return Json.encodeToString(JsonObject.serializer(), record)
}
