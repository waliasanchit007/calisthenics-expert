package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.export.DurableSessionWriter
import com.calisthenics.diagnostic.export.DurableWriterState
import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ceil

enum class M05BAmbientConstancyAttestation(val wireValue: String) {
    CONSTANT("constant"),
    CHANGED("changed"),
}

data class M05BPostBaselineAttestationResult(
    val attestation: M05BAmbientConstancyAttestation?,
    val blockingCodes: List<String>,
)

object M05BPostBaselineAttestation {
    fun beginBaseline(preSuppliedAttestation: String?): M05BPostBaselineAttestationResult =
        M05BPostBaselineAttestationResult(
            attestation = null,
            blockingCodes = if (preSuppliedAttestation == null) {
                emptyList()
            } else {
                listOf("AMBIENT.ATTESTATION_PRE_SUPPLIED")
            },
        )

    fun complete(
        expectedChallenge: String,
        suppliedChallenge: String?,
        suppliedAttestation: String?,
    ): M05BPostBaselineAttestationResult {
        val blocking = buildList {
            if (!SHA256.matches(expectedChallenge) || suppliedChallenge != expectedChallenge) {
                add("AMBIENT.ATTESTATION_CHALLENGE_MISMATCH")
            }
            if (suppliedAttestation !in M05BAmbientConstancyAttestation.entries.map { it.wireValue }) {
                add("AMBIENT.ATTESTATION_MISSING")
            }
        }
        val attestation = M05BAmbientConstancyAttestation.entries.singleOrNull {
            it.wireValue == suppliedAttestation
        }
        return M05BPostBaselineAttestationResult(attestation, blocking)
    }
}

interface M05BBaselineClock {
    fun nowNs(): Long
    fun sleepUntilNs(deadlineNs: Long)
}

object M05BIdleBaselineSampler {
    const val SAMPLE_COUNT = 300
    const val SAMPLE_INTERVAL_NS = 1_000_000_000L
    const val BASELINE_DURATION_NS = 300_000_000_000L

    fun collect(clock: M05BBaselineClock, sample: () -> M05BObservedStart): List<M05BObservedStart> {
        val startedAtNs = clock.nowNs()
        return buildList(SAMPLE_COUNT) {
            repeat(SAMPLE_COUNT) { index ->
                clock.sleepUntilNs(startedAtNs + (index + 1L) * SAMPLE_INTERVAL_NS)
                add(sample())
            }
        }
    }
}

sealed interface M05BBaselineReduction {
    val finalObservedStart: M05BObservedStart?
    val blockingCodes: List<String>

    data class Frozen(
        val envelope: M05BStartEnvelope,
        override val finalObservedStart: M05BObservedStart,
        override val blockingCodes: List<String> = emptyList(),
    ) : M05BBaselineReduction

    data class Blocked(
        override val finalObservedStart: M05BObservedStart?,
        override val blockingCodes: List<String>,
    ) : M05BBaselineReduction
}

object M05BBaselineReducer {
    fun reduce(
        samples: List<M05BObservedStart>,
        ambientToken: String?,
        ambientConstancyAttestation: M05BAmbientConstancyAttestation?,
        order: M05BExecutionOrder,
        producerGitSha: String,
    ): M05BBaselineReduction {
        val blocking = mutableListOf<String>()
        if (samples.size != M05BIdleBaselineSampler.SAMPLE_COUNT) blocking += "BASELINE.SAMPLE_COUNT"
        if (ambientToken == null || !SLUG.matches(ambientToken)) blocking += "AMBIENT.TOKEN"
        when (ambientConstancyAttestation) {
            null -> blocking += "AMBIENT.ATTESTATION_MISSING"
            M05BAmbientConstancyAttestation.CHANGED -> blocking += "AMBIENT.NOT_CONSTANT"
            M05BAmbientConstancyAttestation.CONSTANT -> Unit
        }
        val finalSample = samples.lastOrNull()
        samples.forEach { sample -> blocking += unavailableCodes(sample) }
        samples.firstOrNull()?.let { first ->
            samples.drop(1).forEach { sample -> blocking += changedCodes(first, sample) }
        }
        val temperatures = samples.mapNotNull { it.batteryTemperatureC?.takeIf(Double::isFinite) }
        if (temperatures.size != samples.size) blocking += "THERMAL.RANGE"
        if (finalSample == null) return M05BBaselineReduction.Blocked(null, blocking.distinct())

        val attestedFinal = finalSample.copy(
            ambientToken = ambientToken,
            ambientConstancy = ambientConstancyAttestation?.wireValue,
            ambientConstancyOperatorSupplied = ambientConstancyAttestation != null,
        )
        if (blocking.isNotEmpty()) return M05BBaselineReduction.Blocked(attestedFinal, blocking.distinct())

        val minimum = temperatures.minOrNull()!!
        val maximum = temperatures.maxOrNull()!!
        val orderArtifact = M05BArtifactCodec.bindOrder(order, "execution-order.json")
        val procedureFields = buildJsonObject {
            put("procedure_version", "1.0.0")
            put("idle_baseline_duration_ns", M05BIdleBaselineSampler.BASELINE_DURATION_NS)
            put("sampling_interval_ns", M05BIdleBaselineSampler.SAMPLE_INTERVAL_NS)
            put("cooldown_max_duration_ns", M05BCooldownSampler.MAX_DURATION_NS)
            put("cooldown_sampling_interval_ns", M05BCooldownSampler.SAMPLE_INTERVAL_NS)
        }
        val procedureSha = M05BArtifactCodec.sha256(M05BArtifactCodec.canonicalBytes(procedureFields))
        return M05BBaselineReduction.Frozen(
            envelope = M05BStartEnvelope(
                envelopeId = "m05b-envelope-${producerGitSha.take(12)}",
                procedureVersion = "1.0.0",
                procedureSha256 = procedureSha,
                appSha256 = requireNotNull(attestedFinal.appSha256),
                modelSha256 = requireNotNull(attestedFinal.modelSha256),
                runtimeVersion = requireNotNull(attestedFinal.runtimeVersion),
                delegate = requireNotNull(attestedFinal.delegate),
                preprocessingVersion = requireNotNull(attestedFinal.preprocessingVersion),
                deviceUnitDigest = requireNotNull(attestedFinal.deviceUnitDigest),
                deviceClass = requireNotNull(attestedFinal.deviceClass),
                manufacturer = requireNotNull(attestedFinal.manufacturer),
                model = requireNotNull(attestedFinal.model),
                deviceBuild = requireNotNull(attestedFinal.deviceBuild),
                apiLevel = requireNotNull(attestedFinal.apiLevel),
                charging = requireNotNull(attestedFinal.charging),
                powerSource = requireNotNull(attestedFinal.powerSource),
                hostBatteryOverrideRejected = requireNotNull(attestedFinal.hostBatteryOverrideRejected),
                powerSaveMode = requireNotNull(attestedFinal.powerSaveMode),
                baselineBatteryObservation = attestedFinal.batteryObservation,
                hostAdbTransport = requireNotNull(attestedFinal.hostAdbTransport),
                appTransport = requireNotNull(attestedFinal.appTransport),
                networkIdentityPersisted = requireNotNull(attestedFinal.networkIdentityPersisted),
                ambientToken = requireNotNull(ambientToken),
                ambientConstancy = M05BAmbientConstancyAttestation.CONSTANT.wireValue,
                ambientConstancySource = "operator_attestation_end_of_baseline",
                keepAwake = requireNotNull(attestedFinal.keepAwake),
                brightnessMode = requireNotNull(attestedFinal.brightnessMode),
                brightnessRaw = requireNotNull(attestedFinal.brightnessRaw),
                brightnessScaleMin = requireNotNull(attestedFinal.brightnessScaleMin),
                brightnessScaleMax = requireNotNull(attestedFinal.brightnessScaleMax),
                cameraFps = 30,
                inferenceCadencesFps = listOf(10, 15, 30),
                durationNs = 1_500_000_000_000L,
                overshootNs = 1_000_000_000L,
                cooldownMaxDurationNs = M05BCooldownSampler.MAX_DURATION_NS,
                cooldownSamplingIntervalNs = M05BCooldownSampler.SAMPLE_INTERVAL_NS,
                thermalStatus = requireNotNull(attestedFinal.thermalStatus),
                batteryTemperatureMinC = minimum,
                batteryTemperatureMaxC = maximum,
                batteryTemperatureRangeC = maximum - minimum,
                orderPath = orderArtifact.path,
                orderSha256 = orderArtifact.sha256,
                producerGitSha = producerGitSha,
            ),
            finalObservedStart = attestedFinal,
        )
    }

    private fun unavailableCodes(value: M05BObservedStart): List<String> = buildList {
        if (value.appSha256 == null || value.modelSha256 == null || value.runtimeVersion == null ||
            value.delegate == null || value.preprocessingVersion == null || value.deviceUnitDigest == null
        ) add("IDENTITY.UNAVAILABLE")
        if (value.deviceClass == null || value.manufacturer == null || value.model == null ||
            value.deviceBuild == null || value.apiLevel == null
        ) add("DEVICE.UNAVAILABLE")
        if (value.charging == null || value.powerSource == null || value.hostBatteryOverrideRejected == null ||
            value.powerSaveMode == null
        ) add("POWER.UNAVAILABLE")
        if (value.hostAdbTransport == null || value.appTransport == null || value.networkIdentityPersisted == null) {
            add("CONNECTIVITY.UNAVAILABLE")
        }
        if (value.keepAwake == null || value.brightnessMode == null || value.brightnessRaw == null ||
            value.brightnessScaleMin == null || value.brightnessScaleMax == null
        ) add("DISPLAY.BRIGHTNESS_UNAVAILABLE")
        if (value.thermalStatus == null || value.batteryTemperatureC == null) add("THERMAL.RANGE")
    }

    private fun changedCodes(first: M05BObservedStart, current: M05BObservedStart): List<String> = buildList {
        if (listOf(first.appSha256, first.modelSha256, first.runtimeVersion, first.delegate,
                first.preprocessingVersion, first.deviceUnitDigest) !=
            listOf(current.appSha256, current.modelSha256, current.runtimeVersion, current.delegate,
                current.preprocessingVersion, current.deviceUnitDigest)
        ) add("IDENTITY.CHANGED")
        if (listOf(first.deviceClass, first.manufacturer, first.model, first.deviceBuild, first.apiLevel) !=
            listOf(current.deviceClass, current.manufacturer, current.model, current.deviceBuild, current.apiLevel)
        ) add("DEVICE.CHANGED")
        if (listOf(first.charging, first.powerSource, first.hostBatteryOverrideRejected, first.powerSaveMode) !=
            listOf(current.charging, current.powerSource, current.hostBatteryOverrideRejected, current.powerSaveMode)
        ) add("POWER.CHANGED")
        if (listOf(first.hostAdbTransport, first.appTransport, first.networkIdentityPersisted) !=
            listOf(current.hostAdbTransport, current.appTransport, current.networkIdentityPersisted)
        ) add("CONNECTIVITY.CHANGED")
        if (first.thermalStatus != current.thermalStatus) add("THERMAL.STATUS_CHANGED")
        if (listOf(first.keepAwake, first.brightnessMode, first.brightnessRaw,
                first.brightnessScaleMin, first.brightnessScaleMax) !=
            listOf(current.keepAwake, current.brightnessMode, current.brightnessRaw,
                current.brightnessScaleMin, current.brightnessScaleMax)
        ) add("DISPLAY.CHANGED")
    }
}

object M05BDeviceUnitDigest {
    const val DOMAIN = "calisthenics-expert/m05b/device-unit/v1"

    fun derive(androidId: String, appScopeDigest: String): String =
        deriveForDomain(DOMAIN, androidId, appScopeDigest)

    fun deriveForDomain(domain: String, androidId: String, appScopeDigest: String): String {
        require(androidId.isNotBlank())
        require(SHA256.matches(appScopeDigest))
        return Sha256Digest.hex("$domain\u0000$appScopeDigest\u0000$androidId".encodeToByteArray())
    }
}

object M05BThermalStatus {
    fun fromAndroid(status: Int): String? = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        else -> null
    }
}

sealed interface M05BCooldownResult {
    data class Ready(val observedStart: M05BObservedStart) : M05BCooldownResult
    data class Blocked(val blockingCodes: List<String>) : M05BCooldownResult
}

object M05BCooldownSampler {
    const val MAX_DURATION_NS = 600_000_000_000L
    const val SAMPLE_INTERVAL_NS = 1_000_000_000L
    private const val SAMPLE_COUNT = 600

    fun awaitReady(
        clock: M05BBaselineClock,
        envelope: M05BStartEnvelope,
        sample: () -> M05BObservedStart,
    ): M05BCooldownResult {
        val startedAt = clock.nowNs()
        repeat(SAMPLE_COUNT) { index ->
            val observed = sample()
            val temperature = observed.batteryTemperatureC
            if (observed.thermalStatus == envelope.thermalStatus && temperature != null &&
                temperature.isFinite() && temperature in envelope.batteryTemperatureMinC..envelope.batteryTemperatureMaxC
            ) return M05BCooldownResult.Ready(observed)
            if (index + 1 < SAMPLE_COUNT) clock.sleepUntilNs(startedAt + (index + 1L) * SAMPLE_INTERVAL_NS)
        }
        return M05BCooldownResult.Blocked(listOf("THERMAL.COOLDOWN_TIMEOUT"))
    }
}

data class M05BLatencyBounds(val lowerInclusiveNs: Long, val upperInclusiveNs: Long?)

object M05BLatencyProtocol {
    val BUCKET_UPPER_BOUNDS_NS = listOf(
        1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_000_000L, 32_000_000L,
        64_000_000L, 128_000_000L, 256_000_000L, 512_000_000L, 1_024_000_000L,
        2_048_000_000L, 4_096_000_000L,
    )
    val PERCENTILE_RANKS = listOf(50, 95, 99)

    fun summarize(valuesNs: List<Long>): Map<Int, M05BLatencyBounds> {
        require(valuesNs.isNotEmpty())
        require(valuesNs.all { it >= 0L })
        val sorted = valuesNs.sorted()
        return PERCENTILE_RANKS.associateWith { percentile ->
            val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceAtLeast(1)
            boundsFor(sorted[rank - 1])
        }
    }

    private fun boundsFor(value: Long): M05BLatencyBounds {
        val index = BUCKET_UPPER_BOUNDS_NS.indexOfFirst { value <= it }
        if (index < 0) return M05BLatencyBounds(BUCKET_UPPER_BOUNDS_NS.last() + 1L, null)
        return M05BLatencyBounds(
            lowerInclusiveNs = if (index == 0) 0L else BUCKET_UPPER_BOUNDS_NS[index - 1] + 1L,
            upperInclusiveNs = BUCKET_UPPER_BOUNDS_NS[index],
        )
    }
}

data class M05BBoundArtifact<T>(
    val typed: T,
    val path: String,
    val exactBytes: ByteArray,
    val sha256: String,
)

data class M05BFrozenRunInputs(
    val envelope: M05BBoundArtifact<M05BStartEnvelope>,
    val order: M05BBoundArtifact<M05BExecutionOrder>,
    val observedStart: M05BObservedStart,
    val exactBytes: ByteArray,
) {
    companion object {
        fun bind(
            envelope: M05BBoundArtifact<M05BStartEnvelope>,
            order: M05BBoundArtifact<M05BExecutionOrder>,
            observedStart: M05BObservedStart,
        ): M05BFrozenRunInputs {
            require(M05BArtifactCodec.validateEnvelope(envelope).isEmpty())
            require(M05BArtifactCodec.validateOrder(order).isEmpty())
            require(envelope.typed.orderPath == order.path)
            require(envelope.typed.orderSha256 == order.sha256)
            val bytes = M05BArtifactCodec.canonicalBytes(buildJsonObject {
                put("frozen_run_inputs_schema_version", "1.0.0")
                put("record_kind", "m05b_frozen_run_inputs")
                put("envelope_path", envelope.path)
                put("envelope_sha256", envelope.sha256)
                put("envelope_json", envelope.exactBytes.decodeToString())
                put("order_path", order.path)
                put("order_sha256", order.sha256)
                put("order_json", order.exactBytes.decodeToString())
                put("observed_start", M05BArtifactCodec.canonicalObservedStart(observedStart))
            })
            return M05BFrozenRunInputs(envelope, order, observedStart, bytes)
        }

        fun decode(exactBytes: ByteArray): M05BFrozenRunInputs {
            val value = Json.parseToJsonElement(exactBytes.decodeToString()).jsonObject
            require(M05BArtifactCodec.canonicalBytes(value).contentEquals(exactBytes)) {
                "frozen run inputs must use the canonical encoding"
            }
            require(value.getValue("frozen_run_inputs_schema_version").jsonPrimitive.content == "1.0.0")
            require(value.getValue("record_kind").jsonPrimitive.content == "m05b_frozen_run_inputs")
            val envelope = M05BArtifactCodec.decodeEnvelope(
                value.getValue("envelope_json").jsonPrimitive.content.encodeToByteArray(),
                value.getValue("envelope_path").jsonPrimitive.content,
            )
            val order = M05BArtifactCodec.decodeOrder(
                value.getValue("order_json").jsonPrimitive.content.encodeToByteArray(),
                value.getValue("order_path").jsonPrimitive.content,
            )
            require(value.getValue("envelope_sha256").jsonPrimitive.content == envelope.sha256)
            require(value.getValue("order_sha256").jsonPrimitive.content == order.sha256)
            require(envelope.typed.orderPath == order.path)
            require(envelope.typed.orderSha256 == order.sha256)
            val observed = M05BArtifactCodec.decodeObservedStart(
                M05BArtifactCodec.canonicalBytes(value.getValue("observed_start")),
            )
            return M05BFrozenRunInputs(envelope, order, observed, exactBytes)
        }
    }
}

object M05BArtifactCodec {
    fun bindEnvelope(value: M05BStartEnvelope, path: String): M05BBoundArtifact<M05BStartEnvelope> {
        requireRelativePath(path)
        val bytes = canonicalEnvelopeBytes(value)
        return M05BBoundArtifact(value, path, bytes, sha256(bytes))
    }

    fun bindOrder(value: M05BExecutionOrder, path: String): M05BBoundArtifact<M05BExecutionOrder> {
        requireRelativePath(path)
        val bytes = canonicalOrderBytes(value)
        return M05BBoundArtifact(value, path, bytes, sha256(bytes))
    }

    fun canonicalEnvelopeBytes(value: M05BStartEnvelope): ByteArray = canonicalBytes(envelopeJson(value))
    fun canonicalOrderBytes(value: M05BExecutionOrder): ByteArray = canonicalBytes(orderJson(value))
    fun canonicalObservedStart(value: M05BObservedStart): JsonObject = observedStartJson(value)
    fun decodeEnvelope(exactBytes: ByteArray, path: String): M05BBoundArtifact<M05BStartEnvelope> {
        requireRelativePath(path)
        val objectValue = decodeCanonicalObject(exactBytes)
        require(objectValue.getValue("start_envelope_schema_version").jsonPrimitive.content == "1.0.0")
        require(objectValue.getValue("record_kind").jsonPrimitive.content == "m05b_start_envelope")
        return M05BBoundArtifact(envelopeFromJson(objectValue), path, exactBytes, sha256(exactBytes))
    }

    fun decodeOrder(exactBytes: ByteArray, path: String): M05BBoundArtifact<M05BExecutionOrder> {
        requireRelativePath(path)
        val objectValue = decodeCanonicalObject(exactBytes)
        require(objectValue.getValue("execution_order_schema_version").jsonPrimitive.content == "1.0.0")
        require(objectValue.getValue("record_kind").jsonPrimitive.content == "m05b_execution_order")
        return M05BBoundArtifact(orderFromJson(objectValue), path, exactBytes, sha256(exactBytes))
    }

    fun decodeAttemptEntry(exactBytes: ByteArray): M05BBoundAttemptEntry {
        val objectValue = decodeCanonicalObject(exactBytes)
        require(objectValue.getValue("attempt_entry_schema_version").jsonPrimitive.content == "1.0.0")
        require(objectValue.getValue("record_kind").jsonPrimitive.content == "m05b_attempt_entry")
        val outcome = M05BAttemptOutcome.entries.single {
            it.wireValue == objectValue.getValue("outcome").jsonPrimitive.content
        }
        val typed = M05BAttemptEntry(
            attemptSequence = requireNotNull(objectValue.getValue("attempt_sequence").jsonPrimitive.intOrNull),
            cadenceFps = requireNotNull(objectValue.getValue("cadence_fps").jsonPrimitive.intOrNull),
            admissionSha256 = objectValue.getValue("admission_sha256").jsonPrimitive.content,
            outcome = outcome,
            previousEntrySha256 = objectValue.nullableString("previous_entry_sha256"),
        )
        return M05BBoundAttemptEntry(typed, exactBytes, sha256(exactBytes))
    }

    fun decodeObservedStart(exactBytes: ByteArray): M05BObservedStart {
        val objectValue = decodeCanonicalObject(exactBytes)
        return observedStartFromJson(objectValue)
    }
    fun sha256(bytes: ByteArray): String = Sha256Digest.hex(bytes)

    fun canonicalBytes(element: JsonElement): ByteArray = canonicalize(element).toString().encodeToByteArray()

    fun validateEnvelope(artifact: M05BBoundArtifact<M05BStartEnvelope>): List<String> = buildList {
        if (sha256(artifact.exactBytes) != artifact.sha256) add("ARTIFACT.ENVELOPE_DIGEST")
        if (!canonicalEnvelopeBytes(artifact.typed).contentEquals(artifact.exactBytes)) {
            add("ARTIFACT.ENVELOPE_TYPED_MISMATCH")
        }
        runCatching { Json.parseToJsonElement(artifact.exactBytes.decodeToString()).jsonObject }
            .onFailure { add("ARTIFACT.ENVELOPE_BYTES_INVALID") }
    }.distinct()

    fun validateOrder(artifact: M05BBoundArtifact<M05BExecutionOrder>): List<String> = buildList {
        if (sha256(artifact.exactBytes) != artifact.sha256) add("ARTIFACT.ORDER_DIGEST")
        if (!canonicalOrderBytes(artifact.typed).contentEquals(artifact.exactBytes)) {
            add("ARTIFACT.ORDER_TYPED_MISMATCH")
        }
        runCatching { Json.parseToJsonElement(artifact.exactBytes.decodeToString()).jsonObject }
            .onFailure { add("ARTIFACT.ORDER_BYTES_INVALID") }
    }.distinct()

    private fun envelopeJson(value: M05BStartEnvelope) = buildJsonObject {
        put("start_envelope_schema_version", "1.0.0")
        put("record_kind", "m05b_start_envelope")
        put("envelope_id", value.envelopeId)
        putJsonObject("procedure") {
            put("procedure_version", value.procedureVersion)
            put("procedure_sha256", value.procedureSha256)
            put("idle_baseline_duration_ns", M05BIdleBaselineSampler.BASELINE_DURATION_NS)
            put("sampling_interval_ns", M05BIdleBaselineSampler.SAMPLE_INTERVAL_NS)
            put("cooldown_max_duration_ns", value.cooldownMaxDurationNs)
            put("cooldown_sampling_interval_ns", value.cooldownSamplingIntervalNs)
        }
        putJsonObject("device") {
            put("device_class", value.deviceClass); put("manufacturer", value.manufacturer)
            put("model", value.model); put("device_build", value.deviceBuild); put("api_level", value.apiLevel)
            put("app_sha256", value.appSha256); put("model_sha256", value.modelSha256)
            put("runtime_version", value.runtimeVersion); put("delegate", value.delegate)
            put("preprocessing_version", value.preprocessingVersion); put("device_unit_digest", value.deviceUnitDigest)
        }
        putJsonObject("power") {
            put("charging", value.charging); put("power_source", value.powerSource)
            put("host_battery_override_rejected", value.hostBatteryOverrideRejected)
            put("power_save_mode", value.powerSaveMode)
            val battery = value.baselineBatteryObservation
            if (battery == null) {
                put("baseline_battery_observation", JsonNull)
            } else {
                putJsonObject("baseline_battery_observation") {
                    put("unit", battery.unit)
                    put("value", battery.value)
                }
            }
        }
        putJsonObject("connectivity") {
            put("host_adb_transport", value.hostAdbTransport); put("app_transport", value.appTransport)
            put("network_identity_persisted", value.networkIdentityPersisted)
        }
        putJsonObject("thermal") {
            put("initial_status", value.thermalStatus); put("battery_temperature_min_c", value.batteryTemperatureMinC)
            put("battery_temperature_max_c", value.batteryTemperatureMaxC)
            put("battery_temperature_range_c", value.batteryTemperatureRangeC)
        }
        putJsonObject("ambient") {
            put("token", value.ambientToken); put("constancy", value.ambientConstancy)
            put("constancy_source", value.ambientConstancySource)
        }
        putJsonObject("display") {
            put("keep_awake", value.keepAwake); put("brightness_mode", value.brightnessMode)
            put("brightness_raw", value.brightnessRaw); put("brightness_scale_min", value.brightnessScaleMin)
            put("brightness_scale_max", value.brightnessScaleMax)
        }
        putJsonObject("protocol") {
            put("camera_fps", value.cameraFps)
            putJsonArray("inference_cadences_fps") { value.inferenceCadencesFps.forEach { add(it) } }
            put("duration_ns", value.durationNs); put("overshoot_ns", value.overshootNs)
            putJsonArray("latency_bucket_upper_bounds_ns") { M05BLatencyProtocol.BUCKET_UPPER_BOUNDS_NS.forEach { add(it) } }
            putJsonArray("latency_percentile_ranks") { M05BLatencyProtocol.PERCENTILE_RANKS.forEach { add(it) } }
        }
        putJsonObject("order_binding") { put("path", value.orderPath); put("sha256", value.orderSha256) }
        put("producer_git_sha", value.producerGitSha)
    }

    private fun orderJson(value: M05BExecutionOrder) = buildJsonObject {
        put("execution_order_schema_version", "1.0.0")
        put("record_kind", "m05b_execution_order")
        put("order_id", value.orderId)
        putJsonArray("cadences_fps") { value.cadencesFps.forEach { add(it) } }
        put("producer_git_sha", value.producerGitSha)
    }

    private fun observedStartJson(value: M05BObservedStart) = buildJsonObject {
        putJsonObject("device") {
            putNullable("device_class", value.deviceClass); putNullable("manufacturer", value.manufacturer)
            putNullable("model", value.model); putNullable("device_build", value.deviceBuild)
            putNullable("api_level", value.apiLevel); putNullable("app_sha256", value.appSha256)
            putNullable("model_sha256", value.modelSha256); putNullable("runtime_version", value.runtimeVersion)
            putNullable("delegate", value.delegate); putNullable("preprocessing_version", value.preprocessingVersion)
            putNullable("device_unit_digest", value.deviceUnitDigest)
        }
        putJsonObject("power") {
            putNullable("charging", value.charging); putNullable("power_source", value.powerSource)
            putNullable("host_battery_override_rejected", value.hostBatteryOverrideRejected)
            putNullable("power_save_mode", value.powerSaveMode)
            val battery = value.batteryObservation
            if (battery == null) {
                put("battery_observation", JsonNull)
            } else {
                putJsonObject("battery_observation") {
                    put("unit", battery.unit)
                    put("value", battery.value)
                }
            }
            putNullable("device_idle_mode", value.deviceIdleMode)
        }
        putJsonObject("connectivity") {
            putNullable("host_adb_transport", value.hostAdbTransport); putNullable("app_transport", value.appTransport)
            putNullable("network_identity_persisted", value.networkIdentityPersisted)
        }
        putJsonObject("thermal") {
            putNullable("initial_status", value.thermalStatus); putNullable("battery_temperature_c", value.batteryTemperatureC)
        }
        putJsonObject("ambient") {
            putNullable("token", value.ambientToken); putNullable("constancy", value.ambientConstancy)
            putNullable("constancy_operator_supplied", value.ambientConstancyOperatorSupplied)
        }
        putJsonObject("display") {
            putNullable("keep_awake", value.keepAwake); putNullable("brightness_mode", value.brightnessMode)
            putNullable("brightness_raw", value.brightnessRaw); putNullable("brightness_scale_min", value.brightnessScaleMin)
            putNullable("brightness_scale_max", value.brightnessScaleMax)
        }
    }

    private fun observedStartFromJson(value: JsonObject): M05BObservedStart {
        val device = value.getValue("device").jsonObject
        val power = value.getValue("power").jsonObject
        val connectivity = value.getValue("connectivity").jsonObject
        val thermal = value.getValue("thermal").jsonObject
        val ambient = value.getValue("ambient").jsonObject
        val display = value.getValue("display").jsonObject
        val battery = (power.getValue("battery_observation") as? JsonObject)?.let {
            M05BBatteryObservation(
                unit = it.getValue("unit").jsonPrimitive.content,
                value = requireNotNull(it.getValue("value").jsonPrimitive.doubleOrNull),
            )
        }
        return M05BObservedStart(
            appSha256 = device.nullableString("app_sha256"),
            modelSha256 = device.nullableString("model_sha256"),
            runtimeVersion = device.nullableString("runtime_version"),
            delegate = device.nullableString("delegate"),
            preprocessingVersion = device.nullableString("preprocessing_version"),
            deviceUnitDigest = device.nullableString("device_unit_digest"),
            deviceClass = device.nullableString("device_class"),
            manufacturer = device.nullableString("manufacturer"),
            model = device.nullableString("model"),
            deviceBuild = device.nullableString("device_build"),
            apiLevel = device.getValue("api_level").jsonPrimitive.intOrNull,
            charging = power.getValue("charging").jsonPrimitive.booleanOrNull,
            powerSource = power.nullableString("power_source"),
            hostBatteryOverrideRejected = power.getValue("host_battery_override_rejected").jsonPrimitive.booleanOrNull,
            powerSaveMode = power.getValue("power_save_mode").jsonPrimitive.booleanOrNull,
            batteryObservation = battery,
            deviceIdleMode = power.getValue("device_idle_mode").jsonPrimitive.booleanOrNull,
            hostAdbTransport = connectivity.nullableString("host_adb_transport"),
            appTransport = connectivity.nullableString("app_transport"),
            networkIdentityPersisted = connectivity.getValue("network_identity_persisted").jsonPrimitive.booleanOrNull,
            ambientToken = ambient.nullableString("token"),
            ambientConstancy = ambient.nullableString("constancy"),
            ambientConstancyOperatorSupplied = ambient.getValue("constancy_operator_supplied").jsonPrimitive.booleanOrNull,
            keepAwake = display.getValue("keep_awake").jsonPrimitive.booleanOrNull,
            brightnessMode = display.nullableString("brightness_mode"),
            brightnessRaw = display.getValue("brightness_raw").jsonPrimitive.doubleOrNull,
            brightnessScaleMin = display.getValue("brightness_scale_min").jsonPrimitive.doubleOrNull,
            brightnessScaleMax = display.getValue("brightness_scale_max").jsonPrimitive.doubleOrNull,
            thermalStatus = thermal.nullableString("initial_status"),
            batteryTemperatureC = thermal.getValue("battery_temperature_c").jsonPrimitive.doubleOrNull,
        )
    }

    private fun envelopeFromJson(value: JsonObject): M05BStartEnvelope {
        val procedure = value.getValue("procedure").jsonObject
        val device = value.getValue("device").jsonObject
        val power = value.getValue("power").jsonObject
        val connectivity = value.getValue("connectivity").jsonObject
        val thermal = value.getValue("thermal").jsonObject
        val ambient = value.getValue("ambient").jsonObject
        val display = value.getValue("display").jsonObject
        val protocol = value.getValue("protocol").jsonObject
        val order = value.getValue("order_binding").jsonObject
        val baselineBattery = (power.getValue("baseline_battery_observation") as? JsonObject)?.let {
            M05BBatteryObservation(
                unit = it.getValue("unit").jsonPrimitive.content,
                value = requireNotNull(it.getValue("value").jsonPrimitive.doubleOrNull),
            )
        }
        return M05BStartEnvelope(
            envelopeId = value.string("envelope_id"),
            procedureVersion = procedure.string("procedure_version"),
            procedureSha256 = procedure.string("procedure_sha256"),
            appSha256 = device.string("app_sha256"),
            modelSha256 = device.string("model_sha256"),
            runtimeVersion = device.string("runtime_version"),
            delegate = device.string("delegate"),
            preprocessingVersion = device.string("preprocessing_version"),
            deviceUnitDigest = device.string("device_unit_digest"),
            deviceClass = device.string("device_class"),
            manufacturer = device.string("manufacturer"),
            model = device.string("model"),
            deviceBuild = device.string("device_build"),
            apiLevel = device.int("api_level"),
            charging = power.boolean("charging"),
            powerSource = power.string("power_source"),
            hostBatteryOverrideRejected = power.boolean("host_battery_override_rejected"),
            powerSaveMode = power.boolean("power_save_mode"),
            baselineBatteryObservation = baselineBattery,
            hostAdbTransport = connectivity.string("host_adb_transport"),
            appTransport = connectivity.string("app_transport"),
            networkIdentityPersisted = connectivity.boolean("network_identity_persisted"),
            ambientToken = ambient.string("token"),
            ambientConstancy = ambient.string("constancy"),
            ambientConstancySource = ambient.string("constancy_source"),
            keepAwake = display.boolean("keep_awake"),
            brightnessMode = display.string("brightness_mode"),
            brightnessRaw = display.double("brightness_raw"),
            brightnessScaleMin = display.double("brightness_scale_min"),
            brightnessScaleMax = display.double("brightness_scale_max"),
            cameraFps = protocol.int("camera_fps"),
            inferenceCadencesFps = protocol.getValue("inference_cadences_fps").jsonArray.map {
                requireNotNull(it.jsonPrimitive.intOrNull)
            },
            durationNs = protocol.long("duration_ns"),
            overshootNs = protocol.long("overshoot_ns"),
            cooldownMaxDurationNs = procedure.long("cooldown_max_duration_ns"),
            cooldownSamplingIntervalNs = procedure.long("cooldown_sampling_interval_ns"),
            thermalStatus = thermal.string("initial_status"),
            batteryTemperatureMinC = thermal.double("battery_temperature_min_c"),
            batteryTemperatureMaxC = thermal.double("battery_temperature_max_c"),
            batteryTemperatureRangeC = thermal.double("battery_temperature_range_c"),
            orderPath = order.string("path"),
            orderSha256 = order.string("sha256"),
            producerGitSha = value.string("producer_git_sha"),
        )
    }

    private fun orderFromJson(value: JsonObject) = M05BExecutionOrder(
        cadencesFps = value.getValue("cadences_fps").jsonArray.map {
            requireNotNull(it.jsonPrimitive.intOrNull)
        },
        orderId = value.string("order_id"),
        producerGitSha = value.string("producer_git_sha"),
    )

    private fun decodeCanonicalObject(exactBytes: ByteArray): JsonObject {
        val objectValue = Json.parseToJsonElement(exactBytes.decodeToString()).jsonObject
        require(canonicalBytes(objectValue).contentEquals(exactBytes)) {
            "artifact bytes must use the canonical encoding"
        }
        return objectValue
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun requireRelativePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\'))
        require(!Regex("^[A-Za-z]:").containsMatchIn(path))
        require(path.split('/', '\\').none { it == ".." || it.isEmpty() })
    }

    private fun JsonObject.nullableString(key: String): String? =
        getValue(key).jsonPrimitive.contentOrNull

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = requireNotNull(getValue(key).jsonPrimitive.intOrNull)
    private fun JsonObject.long(key: String): Long = requireNotNull(getValue(key).jsonPrimitive.longOrNull)
    private fun JsonObject.double(key: String): Double = requireNotNull(getValue(key).jsonPrimitive.doubleOrNull)
    private fun JsonObject.boolean(key: String): Boolean =
        requireNotNull(getValue(key).jsonPrimitive.booleanOrNull)
}

enum class M05BAttemptOutcome(val wireValue: String) {
    COMPLETED("completed"), BLOCKED("blocked"), ABORTED("aborted"), FAILED("failed"), TRANSPORT_LOST("transport_lost")
}

data class M05BAttemptEntry(
    val attemptSequence: Int,
    val cadenceFps: Int,
    val admissionSha256: String,
    val outcome: M05BAttemptOutcome,
    val previousEntrySha256: String?,
)

data class M05BBoundAttemptEntry(
    val typed: M05BAttemptEntry,
    val exactBytes: ByteArray,
    val sha256: String,
)

data class M05BAttemptContext(
    val attemptSequence: Int,
    val cadenceFps: Int,
    val predecessorStatus: M05BPredecessorStatus,
    val previousEntrySha256: String?,
    val blockingCodes: List<String>,
)

object M05BAttemptChain {
    fun bind(value: M05BAttemptEntry): M05BBoundAttemptEntry {
        val bytes = M05BArtifactCodec.canonicalBytes(entryJson(value))
        return M05BBoundAttemptEntry(value, bytes, M05BArtifactCodec.sha256(bytes))
    }

    fun derive(order: M05BExecutionOrder, history: List<M05BBoundAttemptEntry>): M05BAttemptContext {
        val blocking = mutableListOf<String>()
        var previousDigest: String? = null
        var completedCadences = 0
        history.forEachIndexed { index, bound ->
            if (M05BArtifactCodec.sha256(bound.exactBytes) != bound.sha256 ||
                !M05BArtifactCodec.canonicalBytes(entryJson(bound.typed)).contentEquals(bound.exactBytes)
            ) blocking += "ATTEMPT.CHAIN_DIGEST"
            if (bound.typed.attemptSequence != index + 1 || bound.typed.previousEntrySha256 != previousDigest) {
                blocking += "ATTEMPT.CHAIN_ORDER"
            }
            val expectedCadence = order.cadencesFps.getOrNull(completedCadences)
            if (bound.typed.cadenceFps != expectedCadence) blocking += "ATTEMPT.CADENCE"
            if (bound.typed.outcome == M05BAttemptOutcome.COMPLETED && expectedCadence != null) completedCadences++
            previousDigest = bound.sha256
        }
        if (history.lastOrNull()?.typed?.outcome == M05BAttemptOutcome.TRANSPORT_LOST) {
            blocking += "ATTEMPT.TRANSPORT_RECOVERY_REQUIRED"
        }
        if (history.lastOrNull()?.typed?.outcome == M05BAttemptOutcome.BLOCKED) {
            blocking += "ATTEMPT.BLOCKED_OBSERVATION"
        }
        if (completedCadences >= order.cadencesFps.size) blocking += "ATTEMPT.ORDER_COMPLETE"
        val cadence = order.cadencesFps.getOrNull(completedCadences) ?: 0
        val predecessor = when (history.lastOrNull()?.typed?.outcome) {
            null -> M05BPredecessorStatus.NOT_APPLICABLE
            M05BAttemptOutcome.COMPLETED -> M05BPredecessorStatus.COMPLETED
            M05BAttemptOutcome.BLOCKED, M05BAttemptOutcome.ABORTED, M05BAttemptOutcome.FAILED,
            M05BAttemptOutcome.TRANSPORT_LOST -> M05BPredecessorStatus.BLOCKED
        }
        return M05BAttemptContext(history.size + 1, cadence, predecessor, previousDigest, blocking.distinct())
    }

    private fun entryJson(value: M05BAttemptEntry) = buildJsonObject {
        put("attempt_entry_schema_version", "1.0.0")
        put("record_kind", "m05b_attempt_entry")
        put("attempt_sequence", value.attemptSequence)
        put("cadence_fps", value.cadenceFps)
        put("admission_sha256", value.admissionSha256)
        put("outcome", value.outcome.wireValue)
        if (value.previousEntrySha256 == null) {
            put("previous_entry_sha256", JsonNull)
        } else {
            put("previous_entry_sha256", value.previousEntrySha256)
        }
    }
}

data class M05BPublishedAttempt(
    val sessionId: String,
    val attemptSequence: Int,
    val cadenceFps: Int,
    val admissionSha256: String,
    val outcome: M05BAttemptOutcome,
) {
    init {
        require(sessionId.isNotBlank())
        require(attemptSequence > 0)
        require(cadenceFps > 0)
        require(SHA256.matches(admissionSha256))
        require(outcome == M05BAttemptOutcome.COMPLETED || outcome == M05BAttemptOutcome.BLOCKED)
    }
}

/**
 * Reconstructs the one immutable terminal entry selected from durable published evidence.
 *
 * The published session and its run-admission artifact are authoritative; a lost instrumentation
 * transcript cannot cause the same cadence to be issued again. An orphaned publication is supplied
 * as BLOCKED; only a fully validated normal path supplies COMPLETED. Re-running recovery is
 * byte-for-byte idempotent and a conflicting entry fails closed.
 */
object M05BPublishedAttemptRecovery {
    fun recover(
        order: M05BExecutionOrder,
        history: List<M05BBoundAttemptEntry>,
        published: M05BPublishedAttempt,
    ): List<M05BBoundAttemptEntry> {
        val existing = history.filter { it.typed.admissionSha256 == published.admissionSha256 }
        if (existing.isNotEmpty()) {
            require(existing.size == 1)
            val entry = existing.single().typed
            require(entry.attemptSequence == published.attemptSequence)
            require(entry.cadenceFps == published.cadenceFps)
            require(entry.outcome == published.outcome)
            require(history.last() == existing.single())
            return history
        }

        val context = M05BAttemptChain.derive(order, history)
        require(context.blockingCodes.isEmpty()) { context.blockingCodes.joinToString(",") }
        require(context.attemptSequence == published.attemptSequence)
        require(context.cadenceFps == published.cadenceFps)
        val appended = M05BAttemptChain.bind(
            M05BAttemptEntry(
                attemptSequence = published.attemptSequence,
                cadenceFps = published.cadenceFps,
                admissionSha256 = published.admissionSha256,
                outcome = published.outcome,
                previousEntrySha256 = context.previousEntrySha256,
            ),
        )
        return history + appended
    }
}

data class M05BDurableAdmissionOutcome(
    val result: M05BRunAdmissionResult,
    val admissionBytes: ByteArray,
    val context: M05BAttemptContext,
)

class M05BDurableAdmissionBoundary(private val writer: DurableSessionWriter) {
    private var consumed = false

    fun admitAndStart(
        envelopeArtifact: M05BBoundArtifact<M05BStartEnvelope>,
        orderArtifact: M05BBoundArtifact<M05BExecutionOrder>,
        observedStart: M05BObservedStart,
        attemptHistory: List<M05BBoundAttemptEntry>,
        inputClass: String,
        operatorAttestedNoPerson: Boolean,
        startCapture: () -> Unit,
    ): M05BDurableAdmissionOutcome {
        check(!consumed) { "admission boundary was already consumed for this attempt" }
        consumed = true
        if (writer.state == DurableWriterState.NEW) writer.open()
        check(writer.state == DurableWriterState.OPEN) { "durable writer must be open for admission" }

        val context = M05BAttemptChain.derive(orderArtifact.typed, attemptHistory)
        val artifactCodes = M05BArtifactCodec.validateOrder(orderArtifact) +
            M05BArtifactCodec.validateEnvelope(envelopeArtifact) +
            if (envelopeArtifact.typed.orderPath != orderArtifact.path ||
                envelopeArtifact.typed.orderSha256 != orderArtifact.sha256
            ) listOf("ORDER.DIGEST") else emptyList()
        val evaluated = M05BRunAdmission.evaluateDerived(
            envelopeArtifact.typed,
            orderArtifact.typed,
            observedStart,
            writer.sessionId.value,
            context,
            inputClass,
            operatorAttestedNoPerson,
        )
        val result = evaluated.copy(
            admitted = evaluated.admitted && artifactCodes.isEmpty() && context.blockingCodes.isEmpty(),
            blockingCodes = (artifactCodes + context.blockingCodes + evaluated.blockingCodes).distinct(),
        ).let { it.copy(admitted = it.blockingCodes.isEmpty()) }
        val bytes = admissionBytes(
            result, envelopeArtifact, orderArtifact, observedStart, context, inputClass, operatorAttestedNoPerson,
        )
        writer.appendAdmissionArtifact(bytes)
        if (result.admitted) {
            startCapture()
        } else {
            writer.abort("m05b admission blocked", ZERO_COUNTS)
        }
        return M05BDurableAdmissionOutcome(result, bytes, context)
    }

    private fun admissionBytes(
        result: M05BRunAdmissionResult,
        envelope: M05BBoundArtifact<M05BStartEnvelope>,
        order: M05BBoundArtifact<M05BExecutionOrder>,
        observed: M05BObservedStart,
        context: M05BAttemptContext,
        inputClass: String,
        noPerson: Boolean,
    ): ByteArray = M05BArtifactCodec.canonicalBytes(buildJsonObject {
        put("run_admission_schema_version", "1.0.0"); put("record_kind", "m05b_run_admission")
        put("session_id", writer.sessionId.value); put("session_kind", "live_conformance")
        put("input_class", inputClass); put("operator_attested_no_person", noPerson)
        put("cadence_fps", context.cadenceFps); put("attempt_sequence", context.attemptSequence)
        put("predecessor_status", context.predecessorStatus.name.lowercase())
        put("envelope_path", envelope.path); put("order_path", order.path)
        put("envelope_sha256", envelope.sha256); put("order_sha256", order.sha256)
        putJsonArray("evaluated_conjunct_ids") { result.evaluatedConjunctIds.forEach { add(it) } }
        putJsonArray("blocking_codes") { result.blockingCodes.forEach { add(it) } }
        put("admission_status", if (result.admitted) "admitted" else "blocked")
        put("observed_start", M05BArtifactCodec.canonicalObservedStart(observed))
    })

    private companion object {
        val ZERO_COUNTS = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) =
    if (value == null) put(key, JsonNull) else put(key, value)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Int?) =
    if (value == null) put(key, JsonNull) else put(key, value)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Double?) =
    if (value == null) put(key, JsonNull) else put(key, value)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Boolean?) =
    if (value == null) put(key, JsonNull) else put(key, value)

private val SHA256 = Regex("^[0-9a-f]{64}$")
private val SLUG = Regex("^[a-z0-9]+(?:[-_][a-z0-9]+)*$")
