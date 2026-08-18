package com.calisthenics.diagnostic.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Raised whenever pinned model provenance cannot be trusted. Always fails closed. */
class ModelProvenanceException(message: String) : IllegalArgumentException(message)

private val SHA256 = Regex("^[0-9a-f]{64}$")
private val RUNNING_MODES = setOf("VIDEO", "LIVE_STREAM")
private val DELEGATES = setOf("CPU", "GPU")

private val parser = Json {
    ignoreUnknownKeys = false
    allowSpecialFloatingPointValues = false
    isLenient = false
}

/**
 * The pinned identity of one MediaPipe model asset.
 *
 * Every field participates in replay determinism: the spec requires model SHA, Tasks Vision
 * version, delegate, running mode, and all task options to be pinned and recorded, because
 * changing any of them invalidates previously collected replay evidence.
 */
data class ModelProvenance(
    val assetName: String,
    val sha256: String,
    val byteCount: Long,
    val sourceUrl: String,
    val modelFamily: String,
    val modelVariant: String,
    val quantization: String,
    val upstreamRevision: String,
    val tasksVisionVersion: String,
    val runningMode: String,
    val delegate: String,
    val taskOptions: Map<String, Any>,
)

fun parseModelProvenance(json: String): ModelProvenance {
    val root = try {
        parser.parseToJsonElement(json).jsonObject
    } catch (error: Exception) {
        throw ModelProvenanceException("model provenance is not a JSON object: ${error.message}")
    }

    fun str(key: String): String = root[key]?.jsonPrimitive?.contentOrNull
        ?: throw ModelProvenanceException("model provenance is missing string field: $key")

    val sha = str("sha256")
    if (!SHA256.matches(sha)) {
        throw ModelProvenanceException("model provenance sha256 must be canonical lowercase hex: $sha")
    }
    val byteCount = root["byte_count"]?.jsonPrimitive?.longOrNull
        ?: throw ModelProvenanceException("model provenance is missing integer field: byte_count")
    if (byteCount <= 0L) {
        throw ModelProvenanceException("model provenance byte_count must be positive: $byteCount")
    }
    val runningMode = str("running_mode")
    if (runningMode !in RUNNING_MODES) {
        throw ModelProvenanceException("unsupported running_mode: $runningMode")
    }
    val delegate = str("delegate")
    if (delegate !in DELEGATES) {
        throw ModelProvenanceException("unsupported delegate: $delegate")
    }
    val options = root["task_options"]?.jsonObject
        ?: throw ModelProvenanceException("model provenance is missing object field: task_options")
    if (options.isEmpty()) {
        throw ModelProvenanceException("task_options must pin at least one option")
    }
    val decoded = options.mapValues { (key, value) ->
        val primitive = value.jsonPrimitive
        primitive.booleanOrNull
            ?: primitive.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            ?: primitive.doubleOrNull
            ?: primitive.contentOrNull
            ?: throw ModelProvenanceException("task_options.$key is not a scalar")
    }

    return ModelProvenance(
        assetName = str("asset_name"),
        sha256 = sha,
        byteCount = byteCount,
        sourceUrl = str("source_url"),
        modelFamily = str("model_family"),
        modelVariant = str("model_variant"),
        quantization = str("quantization"),
        upstreamRevision = str("upstream_revision"),
        tasksVisionVersion = str("tasks_vision_version"),
        runningMode = runningMode,
        delegate = delegate,
        taskOptions = decoded,
    )
}

/**
 * Fails closed when the asset on disk is not byte-identical to the pinned identity. The session
 * must not start on an unverified model.
 */
fun verifyModelAsset(provenance: ModelProvenance, actualSha256: String, actualByteCount: Long) {
    if (actualByteCount != provenance.byteCount) {
        throw ModelProvenanceException(
            "model asset byte_count mismatch: pinned ${provenance.byteCount}, actual $actualByteCount",
        )
    }
    if (actualSha256 != provenance.sha256) {
        throw ModelProvenanceException(
            "model asset sha256 mismatch: pinned ${provenance.sha256}, actual $actualSha256",
        )
    }
}

/** Replay is defined only for synchronous VIDEO mode; LIVE_STREAM belongs to live capture. */
fun requireReplayProvenance(provenance: ModelProvenance): ModelProvenance {
    if (provenance.runningMode != "VIDEO") {
        throw ModelProvenanceException(
            "deterministic replay requires running_mode VIDEO, found ${provenance.runningMode}",
        )
    }
    return provenance
}
