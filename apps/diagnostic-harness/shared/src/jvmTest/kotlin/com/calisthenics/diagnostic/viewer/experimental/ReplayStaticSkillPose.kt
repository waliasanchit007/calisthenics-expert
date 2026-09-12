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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ReplayStaticSkillPoseFormatException(message: String) : IllegalArgumentException(message)

object ReplayStaticSkillPoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf(
        "side", "straight_min_degrees", "straight_max_degrees", "minimum_confidence",
        "maximum_orientation_degrees_from_horizontal", "maximum_torso_point_speed_torso_lengths_per_second",
        "maximum_gap_ns", "minimum_still_ns", "mode",
    )
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    fun parseConfig(text: String): StaticSkillConfig {
        val root = parseObject(text, "config")
        requireKeys(root, configKeys, "config")
        val side = root.requiredString("side", "config").let {
            try { ArmSide.valueOf(it) } catch (_: IllegalArgumentException) { invalid("config.side must be LEFT or RIGHT") }
        }
        return try {
            StaticSkillConfig(
                minimumConfidence = root.requiredDouble("minimum_confidence", "config"),
                maximumTorsoPointSpeedTorsoLengthsPerSecond = root.requiredDouble(
                    "maximum_torso_point_speed_torso_lengths_per_second", "config",
                ),
                maximumGapNs = root.requiredLong("maximum_gap_ns", "config"),
                minimumStillNs = root.requiredLong("minimum_still_ns", "config"),
                // Pinned to 1: this path must reproduce pre-existing results byte-for-byte.
                minimumMovingSamplesToClose = 1,
                // Pinned to 0 for the same reason: this path measures against the immediately
                // previous frame, as it always has. The fixed-baseline measurement is exercised
                // through ReplayPostureHoldPose, which takes it from a config.
                motionBaselineNs = 0L,
                mode = try { StaticSkillMode.valueOf(root.requiredString("mode", "config")) }
                catch (_: IllegalArgumentException) { invalid("config.mode must be UNSELECTED or TORSO_STILL") },
                side = side,
                straightMinDegrees = root.requiredDouble("straight_min_degrees", "config"),
                straightMaxDegrees = root.requiredDouble("straight_max_degrees", "config"),
                maximumOrientationDegreesFromHorizontal = root.requiredDouble(
                    "maximum_orientation_degrees_from_horizontal", "config",
                ),
            )
        } catch (error: ReplayStaticSkillPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid config")
        }
    }

    fun parseFrames(lines: Sequence<String>): List<StaticSkillPoseFrame> {
        val frames = ArrayList<StaticSkillPoseFrame>()
        var previous: Long? = null
        lines.forEachIndexed { index, line ->
            val frame = parseFrame(line, index + 1, previous)
            frames += frame
            previous = frame.timestampNs
        }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): StaticSkillPoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = parseObject(line, "line $lineNumber")
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
                val item = value as? JsonObject ?: invalid("line $lineNumber landmark $landmarkIndex: must be an object")
                requireKeys(item, landmarkKeys, "line $lineNumber landmark $landmarkIndex")
                Landmark(
                    item.requiredInt("index", "line $lineNumber landmark $landmarkIndex"), null,
                    item.requiredDouble("x", "line $lineNumber landmark $landmarkIndex"),
                    item.requiredDouble("y", "line $lineNumber landmark $landmarkIndex"),
                    item.requiredDouble("z", "line $lineNumber landmark $landmarkIndex"),
                    item.requiredDouble("visibility", "line $lineNumber landmark $landmarkIndex"),
                    item.requiredDouble("presence", "line $lineNumber landmark $landmarkIndex"),
                )
            }
        } catch (error: ReplayStaticSkillPoseFormatException) { throw error }
        catch (error: Exception) { invalid("line $lineNumber: landmarks must be an array") }
        return StaticSkillPoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: invalid("$label must be a JSON object")
    } catch (error: ReplayStaticSkillPoseFormatException) { throw error }
    catch (error: Exception) { invalid("$label: invalid JSON: ${error.message ?: "parse failure"}") }

    private fun requireKeys(root: JsonObject, expected: Set<String>, label: String) {
        if (root.keys != expected) invalid("$label keys mismatch; missing=${expected - root.keys} unknown=${root.keys - expected}")
    }
    private fun JsonObject.requiredString(key: String, label: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$label.$key must be a string")
    private fun JsonObject.requiredDouble(key: String, label: String): Double {
        val value = this[key] as? JsonPrimitive
        if (value == null || value.isString) invalid("$label.$key must be a number")
        return value.doubleOrNull?.takeIf { it.isFinite() } ?: invalid("$label.$key must be a finite number")
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
    private fun invalid(message: String): Nothing = throw ReplayStaticSkillPoseFormatException(message)
}

object ReplayStaticSkillPose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path")
        val configPathNormalized = strictPath(configPath, "static skill config path")
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) { "static skill output path already exists: $output" }
        require(output.parent != null && Files.isDirectory(output.parent)) { "static skill output parent is not a directory: ${output.parent}" }
        require(output != pose && output != configPathNormalized) { "static skill output path must differ from inputs" }
        val staticConfig = ReplayStaticSkillPoseParser.parseConfig(Files.readString(configPathNormalized, StandardCharsets.UTF_8))
        val plankConfig = PlankConfig(
            staticConfig.side, staticConfig.straightMinDegrees, staticConfig.straightMaxDegrees,
            staticConfig.minimumConfidence, staticConfig.maximumGapNs, 1,
            staticConfig.maximumOrientationDegreesFromHorizontal,
        )
        val experiment = StaticSkillObservation(staticConfig)
        var writer: java.io.BufferedWriter? = null
        try {
            Files.newBufferedReader(pose, StandardCharsets.UTF_8).use { reader ->
                var previous: Long? = null
                var lineNumber = 0
                var pending: StaticSkillFrameResult? = null
                reader.forEachLine { line ->
                    lineNumber++
                    val frame = ReplayStaticSkillPoseParser.parseFrame(line, lineNumber, previous)
                    previous = frame.timestampNs
                    val body = measurePlankBodyLine(
                        PlankPoseFrame(frame.timestampNs, frame.width, frame.height, frame.landmarks), plankConfig,
                    )
                    val admitted = body.bodyLineDegrees != null
                    val result = experiment.onFrame(frame, admitted)
                    pending?.let { writeRow(writer, output, it).also { writer = it } }
                    pending = result
                }
                require(lineNumber > 0) { "pose input must contain at least one JSONL frame" }
                val final = requireNotNull(pending)
                val censored = experiment.finish()
                writeRow(writer, output, if (censored == null) final else final.copy(closedInterval = censored)).also { writer = it }
            }
        } finally { writer?.close() }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "expected --posePath=/path --staticSkillConfigPath=/path --staticSkillOutputPath=/path" }
        val values = args.associate { argument ->
            require(argument.startsWith("--")) { "arguments must use --name=/path" }
            val separator = argument.indexOf('=')
            require(separator > 2 && separator < argument.lastIndex) { "arguments must use --name=/path" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.keys == setOf("posePath", "staticSkillConfigPath", "staticSkillOutputPath")) {
            "expected exactly posePath, staticSkillConfigPath and staticSkillOutputPath"
        }
        require(values.size == args.size) { "duplicate replay argument" }
        run(Paths.get(values.getValue("posePath")), Paths.get(values.getValue("staticSkillConfigPath")), Paths.get(values.getValue("staticSkillOutputPath")))
    }

    private fun strictPath(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "$label is not a regular file: $normalized" }
        return normalized
    }
    private fun writeRow(existing: java.io.BufferedWriter?, output: Path, result: StaticSkillFrameResult): java.io.BufferedWriter {
        val writer = existing ?: Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        writer.write(result.toJsonLine()); writer.newLine(); return writer
    }
    private fun StaticSkillFrameResult.toJsonLine() = buildJsonObject {
        put("timestamp_ns", timestampNs)
        put("state", state.name); put("reason", reason.name)
        put("body_line_admitted", reason != StaticSkillReason.BODY_LINE_NOT_ADMITTED)
        put("centroid_speed_torso_lengths_per_second", centroidSpeedTorsoLengthsPerSecond)
        put("max_torso_point_speed_torso_lengths_per_second", maxTorsoPointSpeedTorsoLengthsPerSecond)
        put("closed_interval", closedInterval?.let { interval -> buildJsonObject {
            put("entry_timestamp_ns", interval.entryTimestampNs); put("exit_timestamp_ns", interval.exitTimestampNs)
            put("duration_ns", interval.durationNs); put("observed_ns", interval.observedNs)
            put("end_reason", interval.endReason.name); put("label", interval.label.name)
            put("left_censored", interval.leftCensored)
        } } ?: JsonNull)
    }.toString()
}

fun main(args: Array<String>) = ReplayStaticSkillPose.main(args)
