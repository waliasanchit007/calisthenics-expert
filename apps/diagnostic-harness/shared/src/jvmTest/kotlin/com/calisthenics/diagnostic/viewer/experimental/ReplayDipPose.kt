package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ReplayDipPoseFormatException(message: String) : IllegalArgumentException(message)

/**
 * There is no practice-calibration path here on purpose: dip thresholds are stated in the config
 * and frozen from raw footage inspection, so a replay cannot quietly refit them to the clip it is
 * scoring.
 */
object ReplayDipPoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf(
        "side", "extended_elbow_degrees", "flexed_elbow_degrees",
        "minimum_shoulder_above_wrist_fraction", "maximum_torso_tilt_degrees",
        "minimum_confidence", "maximum_gap_ns", "minimum_endpoint_samples",
    )
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    fun parseConfig(text: String): DipConfig {
        val root = parseObject(text, "config")
        requireKeys(root, configKeys, "config")
        val side = root.requiredString("side", "config").let {
            try {
                ArmSide.valueOf(it)
            } catch (_: IllegalArgumentException) {
                invalid("config.side must be LEFT or RIGHT")
            }
        }
        return try {
            DipConfig(
                side = side,
                extendedElbowDegrees = root.requiredDouble("extended_elbow_degrees", "config"),
                flexedElbowDegrees = root.requiredDouble("flexed_elbow_degrees", "config"),
                minimumShoulderAboveWristFraction = root.requiredDouble(
                    "minimum_shoulder_above_wrist_fraction", "config",
                ),
                maximumTorsoTiltDegrees = root.requiredDouble("maximum_torso_tilt_degrees", "config"),
                minimumConfidence = root.requiredDouble("minimum_confidence", "config"),
                maximumGapNs = root.requiredLong("maximum_gap_ns", "config"),
                minimumEndpointSamples = root.requiredInt("minimum_endpoint_samples", "config"),
            )
        } catch (error: ReplayDipPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid config")
        }
    }

    fun parseFrames(lines: Sequence<String>): List<DipPoseFrame> {
        val frames = ArrayList<DipPoseFrame>()
        var previousTimestamp: Long? = null
        lines.forEachIndexed { index, line ->
            val frame = parseFrame(line, index + 1, previousTimestamp)
            frames += frame
            previousTimestamp = frame.timestampNs
        }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): DipPoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = try {
            parseObject(line, "line $lineNumber")
        } catch (error: ReplayDipPoseFormatException) {
            throw error
        } catch (error: Exception) {
            invalid("line $lineNumber: invalid JSON: ${error.message ?: "parse failure"}")
        }
        requireKeys(root, frameKeys, "line $lineNumber")
        val timestamp = root.requiredLong("timestamp_ns", "line $lineNumber")
        if (timestamp < 0) invalid("line $lineNumber: timestamp_ns must be nonnegative")
        if (previousTimestamp != null && timestamp <= previousTimestamp) {
            invalid("line $lineNumber: timestamp_ns must be strictly increasing")
        }
        val width = root.requiredInt("width", "line $lineNumber")
        val height = root.requiredInt("height", "line $lineNumber")
        if (width <= 0 || height <= 0) invalid("line $lineNumber: width and height must be positive")
        val landmarks = root["landmarks"]?.let { element ->
            try {
                element.jsonArray.mapIndexed { landmarkIndex, value ->
                    val landmarkObject = value as? JsonObject
                        ?: invalid("line $lineNumber landmark $landmarkIndex: must be an object")
                    requireKeys(landmarkObject, landmarkKeys, "line $lineNumber landmark $landmarkIndex")
                    Landmark(
                        index = landmarkObject.requiredInt("index", "line $lineNumber landmark $landmarkIndex"),
                        name = null,
                        x = landmarkObject.requiredDouble("x", "line $lineNumber landmark $landmarkIndex"),
                        y = landmarkObject.requiredDouble("y", "line $lineNumber landmark $landmarkIndex"),
                        z = landmarkObject.requiredDouble("z", "line $lineNumber landmark $landmarkIndex"),
                        visibility = landmarkObject.requiredDouble("visibility", "line $lineNumber landmark $landmarkIndex"),
                        presence = landmarkObject.requiredDouble("presence", "line $lineNumber landmark $landmarkIndex"),
                    )
                }
            } catch (error: ReplayDipPoseFormatException) {
                throw error
            } catch (error: Exception) {
                invalid("line $lineNumber: landmarks must be an array")
            }
        } ?: invalid("line $lineNumber: missing landmarks")
        return DipPoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
            ?: invalid("$label must be a JSON object")
    } catch (error: ReplayDipPoseFormatException) {
        throw error
    } catch (error: Exception) {
        invalid("$label: invalid JSON: ${error.message ?: "parse failure"}")
    }

    private fun requireKeys(root: JsonObject, expected: Set<String>, label: String) {
        if (root.keys != expected) {
            val missing = expected - root.keys
            val unknown = root.keys - expected
            invalid("$label keys mismatch; missing=$missing unknown=$unknown")
        }
    }

    private fun JsonObject.requiredString(key: String, label: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid("$label.$key must be a string")

    private fun JsonObject.requiredDouble(key: String, label: String): Double {
        val primitive = this[key] as? JsonPrimitive
        if (primitive == null || primitive.isString) invalid("$label.$key must be a number")
        return primitive.doubleOrNull?.takeIf { it.isFinite() }
            ?: invalid("$label.$key must be a finite number")
    }

    private fun JsonObject.requiredLong(key: String, label: String): Long {
        val primitive = this[key] as? JsonPrimitive
        if (primitive == null || primitive.isString) invalid("$label.$key must be an integer")
        return primitive.longOrNull ?: invalid("$label.$key must be an integer")
    }

    private fun JsonObject.requiredInt(key: String, label: String): Int {
        val primitive = this[key] as? JsonPrimitive
        if (primitive == null || primitive.isString) invalid("$label.$key must be an integer")
        return primitive.intOrNull ?: invalid("$label.$key must be an integer")
    }

    private fun invalid(message: String): Nothing = throw ReplayDipPoseFormatException(message)
}

object ReplayDipPose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path")
        val config = strictPath(configPath, "dip config path")
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            "dip output path already exists: $output"
        }
        require(output.parent != null && Files.isDirectory(output.parent)) {
            "dip output parent is not a directory: ${output.parent}"
        }
        require(output != pose && output != config) { "dip output path must differ from inputs" }

        val configText = Files.readString(config, StandardCharsets.UTF_8)
        val dipConfig = ReplayDipPoseParser.parseConfig(configText)
        val experiment = DipCycleExperiment(dipConfig)
        var writer: java.io.BufferedWriter? = null
        try {
            Files.newBufferedReader(pose, StandardCharsets.UTF_8).use { reader ->
                var previousTimestamp: Long? = null
                var lineNumber = 0
                reader.forEachLine { line ->
                    lineNumber++
                    val frame = ReplayDipPoseParser.parseFrame(line, lineNumber, previousTimestamp)
                    previousTimestamp = frame.timestampNs
                    val result = experiment.onFrame(frame)
                    val outputWriter = writer ?: Files.newBufferedWriter(
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ).also { writer = it }
                    outputWriter.write(result.toJsonLine())
                    outputWriter.newLine()
                }
                require(lineNumber > 0) { "pose input must contain at least one JSONL frame" }
            }
        } finally {
            writer?.close()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "expected --posePath=/path --dipConfigPath=/path --dipOutputPath=/path"
        }
        val values = args.associate { argument ->
            require(argument.startsWith("--")) { "arguments must use --name=/path" }
            val separator = argument.indexOf('=')
            require(separator > 2 && separator < argument.lastIndex) { "arguments must use --name=/path" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.keys == setOf("posePath", "dipConfigPath", "dipOutputPath")) {
            "expected exactly posePath, dipConfigPath and dipOutputPath"
        }
        require(values.size == args.size) { "duplicate replay argument" }
        run(
            Paths.get(values.getValue("posePath")),
            Paths.get(values.getValue("dipConfigPath")),
            Paths.get(values.getValue("dipOutputPath")),
        )
    }

    private fun strictPath(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "$label is not a regular file: $normalized" }
        return normalized
    }

    private fun DipFrameResult.toJsonLine(): String = buildJsonObject {
        put("timestamp_ns", timestampNs)
        put("elbow_degrees", elbowDegrees?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put(
            "shoulder_above_wrist_fraction",
            shoulderAboveWristFraction?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull,
        )
        put("torso_tilt_degrees", torsoTiltDegrees?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("phase", phase.name)
        put("count", count)
        put("reason", reason.name)
        put("counted", counted)
        put("event_timestamp_ns", eventTimestampNs?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
    }.toString()
}

fun main(args: Array<String>) = ReplayDipPose.main(args)
