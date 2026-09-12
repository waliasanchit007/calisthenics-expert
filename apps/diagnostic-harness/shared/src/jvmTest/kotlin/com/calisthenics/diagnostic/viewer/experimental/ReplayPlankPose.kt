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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ReplayPlankPoseFormatException(message: String) : IllegalArgumentException(message)

object ReplayPlankPoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf(
        "side", "straight_min_degrees", "straight_max_degrees", "minimum_confidence",
        "maximum_gap_ns", "minimum_entry_samples", "maximum_orientation_degrees_from_horizontal",
    )
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    fun parseConfig(text: String): PlankConfig {
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
            PlankConfig(
                side = side,
                straightMinDegrees = root.requiredDouble("straight_min_degrees", "config"),
                straightMaxDegrees = root.requiredDouble("straight_max_degrees", "config"),
                minimumConfidence = root.requiredDouble("minimum_confidence", "config"),
                maximumGapNs = root.requiredLong("maximum_gap_ns", "config"),
                minimumEntrySamples = root.requiredInt("minimum_entry_samples", "config"),
                maximumOrientationDegreesFromHorizontal = root.requiredDouble(
                    "maximum_orientation_degrees_from_horizontal",
                    "config",
                ),
            )
        } catch (error: ReplayPlankPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid config")
        }
    }

    fun parseFrames(lines: Sequence<String>): List<PlankPoseFrame> {
        val frames = ArrayList<PlankPoseFrame>()
        var previousTimestamp: Long? = null
        lines.forEachIndexed { index, line ->
            val frame = parseFrame(line, index + 1, previousTimestamp)
            frames += frame
            previousTimestamp = frame.timestampNs
        }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): PlankPoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = try {
            parseObject(line, "line $lineNumber")
        } catch (error: ReplayPlankPoseFormatException) {
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
            } catch (error: ReplayPlankPoseFormatException) {
                throw error
            } catch (error: Exception) {
                invalid("line $lineNumber: landmarks must be an array")
            }
        } ?: invalid("line $lineNumber: missing landmarks")
        return PlankPoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
            ?: invalid("$label must be a JSON object")
    } catch (error: ReplayPlankPoseFormatException) {
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

    private fun invalid(message: String): Nothing = throw ReplayPlankPoseFormatException(message)
}

object ReplayPlankPose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path")
        val config = strictPath(configPath, "plank config path")
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            "plank output path already exists: $output"
        }
        require(output.parent != null && Files.isDirectory(output.parent)) {
            "plank output parent is not a directory: ${output.parent}"
        }
        require(output != pose && output != config) { "plank output path must differ from inputs" }

        val experiment = PlankHoldExperiment(
            ReplayPlankPoseParser.parseConfig(Files.readString(config, StandardCharsets.UTF_8)),
        )
        var writer: java.io.BufferedWriter? = null
        try {
            Files.newBufferedReader(pose, StandardCharsets.UTF_8).use { reader ->
                var previousTimestamp: Long? = null
                var lineNumber = 0
                // The most recently produced row is held back by one frame so that, if input ends
                // while a hold is still open, its still-null closed_hold can be replaced with an
                // explicitly censored closure before it is written — never as an extra row, and
                // never silently dropped, preserving one output row per input frame.
                var pending: PlankFrameResult? = null
                reader.forEachLine { line ->
                    lineNumber++
                    val frame = ReplayPlankPoseParser.parseFrame(line, lineNumber, previousTimestamp)
                    previousTimestamp = frame.timestampNs
                    val result = experiment.onFrame(frame)
                    pending?.let { previous ->
                        val outputWriter = writer ?: Files.newBufferedWriter(
                            output,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        ).also { writer = it }
                        outputWriter.write(previous.toJsonLine())
                        outputWriter.newLine()
                    }
                    pending = result
                }
                require(lineNumber > 0) { "pose input must contain at least one JSONL frame" }
                val censored = experiment.finish()
                val last = requireNotNull(pending).let { if (censored != null) it.copy(closedHold = censored) else it }
                val outputWriter = writer ?: Files.newBufferedWriter(
                    output,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).also { writer = it }
                outputWriter.write(last.toJsonLine())
                outputWriter.newLine()
            }
        } finally {
            writer?.close()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "expected --posePath=/path --plankConfigPath=/path --plankOutputPath=/path"
        }
        val values = args.associate { argument ->
            require(argument.startsWith("--")) { "arguments must use --name=/path" }
            val separator = argument.indexOf('=')
            require(separator > 2 && separator < argument.lastIndex) { "arguments must use --name=/path" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.keys == setOf("posePath", "plankConfigPath", "plankOutputPath")) {
            "expected exactly posePath, plankConfigPath and plankOutputPath"
        }
        require(values.size == args.size) { "duplicate replay argument" }
        run(
            Paths.get(values.getValue("posePath")),
            Paths.get(values.getValue("plankConfigPath")),
            Paths.get(values.getValue("plankOutputPath")),
        )
    }

    private fun strictPath(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "$label is not a regular file: $normalized" }
        return normalized
    }

    private fun PlankFrameResult.toJsonLine(): String = buildJsonObject {
        put("timestamp_ns", timestampNs)
        put("body_line_degrees", bodyLineDegrees?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("state", state.name)
        put("reason", reason.name)
        put("closed_hold", closedHold?.let { hold ->
            buildJsonObject {
                put("entry_timestamp_ns", hold.entryTimestampNs)
                put("exit_timestamp_ns", hold.exitTimestampNs)
                put("duration_ns", hold.durationNs)
                put("observed_ns", hold.observedNs)
                put("end_reason", hold.endReason.name)
            }
        } ?: kotlinx.serialization.json.JsonNull)
    }.toString()
}

fun main(args: Array<String>) = ReplayPlankPose.main(args)
