package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ReplayPikePushUpPoseFormatException(message: String) : IllegalArgumentException(message)

object ReplayPikePushUpPoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf(
        "side", "extended_elbow_degrees", "flexed_elbow_degrees", "minimum_confidence",
        "maximum_gap_ns", "minimum_endpoint_samples", "maximum_hip_angle_degrees",
        "minimum_knee_angle_degrees", "minimum_hip_above_shoulder_fraction",
    )
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    fun parseConfig(text: String): PikeConfig {
        val root = parseObject(text, "config")
        requireKeys(root, configKeys, "config")
        val side = root.requiredString("side", "config").let {
            try { ArmSide.valueOf(it) } catch (_: IllegalArgumentException) {
                invalid("config.side must be LEFT or RIGHT")
            }
        }
        return try {
            PikeConfig(
                side = side,
                extendedElbowDegrees = root.requiredDouble("extended_elbow_degrees", "config"),
                flexedElbowDegrees = root.requiredDouble("flexed_elbow_degrees", "config"),
                minimumConfidence = root.requiredDouble("minimum_confidence", "config"),
                maximumGapNs = root.requiredLong("maximum_gap_ns", "config"),
                minimumEndpointSamples = root.requiredInt("minimum_endpoint_samples", "config"),
                maximumHipAngleDegrees = root.requiredDouble("maximum_hip_angle_degrees", "config"),
                minimumKneeAngleDegrees = root.requiredDouble("minimum_knee_angle_degrees", "config"),
                minimumHipAboveShoulderFraction = root.requiredDouble(
                    "minimum_hip_above_shoulder_fraction", "config",
                ),
            )
        } catch (error: ReplayPikePushUpPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid config")
        }
    }

    fun parseFrames(lines: Sequence<String>): List<PikePoseFrame> {
        val frames = ArrayList<PikePoseFrame>()
        var previousTimestamp: Long? = null
        lines.forEachIndexed { index, line ->
            val frame = parseFrame(line, index + 1, previousTimestamp)
            frames += frame
            previousTimestamp = frame.timestampNs
        }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): PikePoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = try {
            parseObject(line, "line $lineNumber")
        } catch (error: ReplayPikePushUpPoseFormatException) {
            throw error
        } catch (error: Exception) {
            invalid("line $lineNumber: invalid JSON: ${error.message ?: "parse failure"}")
        }
        requireKeys(root, frameKeys, "line $lineNumber")
        val timestamp = root.requiredLong("timestamp_ns", "line $lineNumber")
        if (timestamp < 0L || (previousTimestamp != null && timestamp <= previousTimestamp)) {
            invalid("line $lineNumber: timestamp_ns must be strictly increasing and nonnegative")
        }
        val width = root.requiredInt("width", "line $lineNumber")
        val height = root.requiredInt("height", "line $lineNumber")
        if (width <= 0 || height <= 0) invalid("line $lineNumber: width and height must be positive")
        val landmarks = try {
            root["landmarks"]!!.jsonArray.mapIndexed { landmarkIndex, value ->
                val label = "line $lineNumber landmark $landmarkIndex"
                val item = value as? JsonObject ?: invalid("$label: must be an object")
                requireKeys(item, landmarkKeys, label)
                Landmark(
                    item.requiredInt("index", label), null,
                    item.requiredDouble("x", label), item.requiredDouble("y", label),
                    item.requiredDouble("z", label), item.requiredDouble("visibility", label),
                    item.requiredDouble("presence", label),
                )
            }
        } catch (error: ReplayPikePushUpPoseFormatException) {
            throw error
        } catch (error: Exception) {
            invalid("line $lineNumber: landmarks must be an array")
        }
        return PikePoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: invalid("$label must be a JSON object")
    } catch (error: ReplayPikePushUpPoseFormatException) {
        throw error
    } catch (error: Exception) {
        invalid("$label: invalid JSON: ${error.message ?: "parse failure"}")
    }

    private fun requireKeys(root: JsonObject, expected: Set<String>, label: String) {
        if (root.keys != expected) invalid("$label keys mismatch; missing=${expected - root.keys} unknown=${root.keys - expected}")
    }
    private fun JsonObject.requiredString(key: String, label: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid("$label.$key must be a string")
    private fun JsonObject.requiredDouble(key: String, label: String): Double {
        val value = this[key] as? JsonPrimitive
        if (value == null || value.isString) invalid("$label.$key must be a number")
        return value.doubleOrNull?.takeIf { it.isFinite() }
            ?: invalid("$label.$key must be a finite number")
    }
    private fun JsonObject.requiredLong(key: String, label: String): Long {
        val value = this[key] as? JsonPrimitive
        if (value == null || value.isString) invalid("$label.$key must be an integer")
        return value.longOrNull ?: invalid("$label.$key must be an integer")
    }
    private fun JsonObject.requiredInt(key: String, label: String): Int {
        val value = this[key] as? JsonPrimitive
        if (value == null || value.isString) invalid("$label.$key must be an integer")
        return value.intOrNull ?: invalid("$label.$key must be an integer")
    }
    private fun invalid(message: String): Nothing = throw ReplayPikePushUpPoseFormatException(message)
}

object ReplayPikePushUpPose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path")
        val config = strictPath(configPath, "pike push-up config path")
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            "pike push-up output path already exists: $output"
        }
        require(output.parent != null && Files.isDirectory(output.parent)) {
            "pike push-up output parent is not a directory: ${output.parent}"
        }
        require(output != pose && output != config) { "pike push-up output path must differ from inputs" }
        val parsedConfig = ReplayPikePushUpPoseParser.parseConfig(
            Files.readString(config, StandardCharsets.UTF_8),
        )
        val frames = ReplayPikePushUpPoseParser.parseFrames(
            Files.readAllLines(pose, StandardCharsets.UTF_8).asSequence(),
        )
        require(frames.isNotEmpty()) { "pose input must contain at least one JSONL frame" }
        val experiment = PikePushUpExperiment(parsedConfig)
        val results = frames.map { experiment.onFrame(it) }
        Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { writer ->
            results.forEach { result -> writer.write(result.toJsonLine()); writer.newLine() }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "expected --posePath=/path --pikePushUpConfigPath=/path --pikePushUpOutputPath=/path"
        }
        val values = args.associate { argument ->
            require(argument.startsWith("--")) { "arguments must use --name=/path" }
            val separator = argument.indexOf('=')
            require(separator > 2 && separator < argument.lastIndex) { "arguments must use --name=/path" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.keys == setOf("posePath", "pikePushUpConfigPath", "pikePushUpOutputPath")) {
            "expected exactly posePath, pikePushUpConfigPath and pikePushUpOutputPath"
        }
        require(values.size == args.size) { "duplicate replay argument" }
        run(
            Paths.get(values.getValue("posePath")),
            Paths.get(values.getValue("pikePushUpConfigPath")),
            Paths.get(values.getValue("pikePushUpOutputPath")),
        )
    }

    private fun strictPath(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            "$label is not a regular file: $normalized"
        }
        return normalized
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(key: String, value: Long?) {
    put(key, value?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
}

private fun PikeFrameResult.toJsonLine() = buildJsonObject {
    put("timestamp_ns", timestampNs)
    putNullable("elbow_degrees", elbowDegrees)
    putNullable("hip_angle_degrees", hipAngleDegrees)
    putNullable("knee_angle_degrees", kneeAngleDegrees)
    putNullable("hip_above_shoulder_fraction", hipAboveShoulderFraction)
    put("phase", phase.name)
    put("count", count)
    put("reason", reason.name)
    put("counted", counted)
    putNullableLong("event_timestamp_ns", eventTimestampNs)
}.toString()

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Double?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}
