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

class ReplaySquatPoseFormatException(message: String) : IllegalArgumentException(message)

data class SquatPracticeWindow(
    val startNs: Long,
    val endNs: Long,
    val minimumRangeDegrees: Double,
    val endpointFraction: Double,
    val oppositeMinimumExcursionDegrees: Double? = null,
)

object ReplaySquatPoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf(
        "side", "extended_degrees", "flexed_degrees", "minimum_confidence",
        "maximum_gap_ns", "minimum_endpoint_samples",
    )
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    fun parseConfig(text: String): SquatConfig {
        val root = parseObject(text, "config")
        requireKeys(root, if ("practice_window" in root) configKeys + "practice_window" else configKeys, "config")
        val side = root.requiredString("side", "config").let {
            try {
                ArmSide.valueOf(it)
            } catch (_: IllegalArgumentException) {
                invalid("config.side must be LEFT or RIGHT")
            }
        }
        return try {
            SquatConfig(
                side = side,
                extendedDegrees = root.requiredDouble("extended_degrees", "config"),
                flexedDegrees = root.requiredDouble("flexed_degrees", "config"),
                minimumConfidence = root.requiredDouble("minimum_confidence", "config"),
                maximumGapNs = root.requiredLong("maximum_gap_ns", "config"),
                minimumEndpointSamples = root.requiredInt("minimum_endpoint_samples", "config"),
            )
        } catch (error: ReplaySquatPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid config")
        }
    }

    fun parsePracticeWindow(text: String): SquatPracticeWindow? {
        val root = parseObject(text, "config")
        if ("practice_window" !in root) return null
        val window = root["practice_window"] as? JsonObject ?: invalid("practice_window must be an object")
        val optional = "opposite_minimum_excursion_degrees"
        val keys = setOf("start_ns", "end_ns", "minimum_range_degrees", "endpoint_fraction")
        requireKeys(window, if (optional in window) keys + optional else keys, "practice_window")
        val result = SquatPracticeWindow(
            window.requiredLong("start_ns", "practice_window"),
            window.requiredLong("end_ns", "practice_window"),
            window.requiredDouble("minimum_range_degrees", "practice_window"),
            window.requiredDouble("endpoint_fraction", "practice_window"),
            if (optional in window) window.requiredDouble(optional, "practice_window") else null,
        )
        if (result.startNs < 0 || result.endNs <= result.startNs) invalid("invalid practice window bounds")
        return result
    }

    fun parseFrames(lines: Sequence<String>): List<SquatPoseFrame> {
        val frames = ArrayList<SquatPoseFrame>()
        var previousTimestamp: Long? = null
        lines.forEachIndexed { index, line ->
            val frame = parseFrame(line, index + 1, previousTimestamp)
            frames += frame
            previousTimestamp = frame.timestampNs
        }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): SquatPoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = try {
            parseObject(line, "line $lineNumber")
        } catch (error: ReplaySquatPoseFormatException) {
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
            } catch (error: ReplaySquatPoseFormatException) {
                throw error
            } catch (error: Exception) {
                invalid("line $lineNumber: landmarks must be an array")
            }
        } ?: invalid("line $lineNumber: missing landmarks")
        return SquatPoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
            ?: invalid("$label must be a JSON object")
    } catch (error: ReplaySquatPoseFormatException) {
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

    private fun invalid(message: String): Nothing = throw ReplaySquatPoseFormatException(message)
}

object ReplaySquatPose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path")
        val config = strictPath(configPath, "squat config path")
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            "squat output path already exists: $output"
        }
        require(output.parent != null && Files.isDirectory(output.parent)) {
            "squat output parent is not a directory: ${output.parent}"
        }
        require(output != pose && output != config) { "squat output path must differ from inputs" }

        val configText = Files.readString(config, StandardCharsets.UTF_8)
        val base = ReplaySquatPoseParser.parseConfig(configText)
        val practice = ReplaySquatPoseParser.parsePracticeWindow(configText)
        var practiceEndpoint = emptyList<SquatPoseFrame>()
        val effective = if (practice == null) base else {
            val frames = Files.newBufferedReader(pose, StandardCharsets.UTF_8).use {
                ReplaySquatPoseParser.parseFrames(it.lineSequence())
            }
            require(frames.isNotEmpty() && frames.first().timestampNs <= practice.startNs &&
                frames.last().timestampNs > practice.endNs) { "practice window requires coverage and later evaluation frames" }
            val fitting = frames.filter { it.timestampNs in practice.startNs..practice.endNs }
            require(fitting.isNotEmpty() && fitting.first().timestampNs - practice.startNs <= base.maximumGapNs &&
                practice.endNs - fitting.last().timestampNs <= base.maximumGapNs) { "practice window lacks boundary observations" }
            calibrateSquatPractice(fitting, base, practice.minimumRangeDegrees, practice.endpointFraction)
                .copy(oppositeMinimumExcursionDegrees = practice.oppositeMinimumExcursionDegrees).also {
                // Validation established these exact frames are a sustained standing endpoint.
                practiceEndpoint = fitting.takeLast(base.minimumEndpointSamples)
            }
        }
        val experiment = SquatCycleExperiment(effective)
        practiceEndpoint.forEach { experiment.onFrame(it) }
        var writer: java.io.BufferedWriter? = null
        try {
            Files.newBufferedReader(pose, StandardCharsets.UTF_8).use { reader ->
                var previousTimestamp: Long? = null
                var lineNumber = 0
                reader.forEachLine { line ->
                    lineNumber++
                    val frame = ReplaySquatPoseParser.parseFrame(line, lineNumber, previousTimestamp)
                    previousTimestamp = frame.timestampNs
                    val evaluation = practice == null || frame.timestampNs > practice.endNs
                    val result = if (evaluation) experiment.onFrame(frame) else SquatFrameResult(
                        frame.timestampNs, measureSquatKnee(frame, base).kneeDegrees,
                        SquatPhase.WAITING, 0, SquatReason.CALIBRATION_PRACTICE, false,
                    )
                    val outputWriter = writer ?: Files.newBufferedWriter(
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ).also { writer = it }
                    outputWriter.write(result.toJsonLine(practice, effective, evaluation, lineNumber == 1))
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
            "expected --posePath=/path --squatConfigPath=/path --squatOutputPath=/path"
        }
        val values = args.associate { argument ->
            require(argument.startsWith("--")) { "arguments must use --name=/path" }
            val separator = argument.indexOf('=')
            require(separator > 2 && separator < argument.lastIndex) { "arguments must use --name=/path" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.keys == setOf("posePath", "squatConfigPath", "squatOutputPath")) {
            "expected exactly posePath, squatConfigPath and squatOutputPath"
        }
        require(values.size == args.size) { "duplicate replay argument" }
        run(
            Paths.get(values.getValue("posePath")),
            Paths.get(values.getValue("squatConfigPath")),
            Paths.get(values.getValue("squatOutputPath")),
        )
    }

    private fun strictPath(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "$label is not a regular file: $normalized" }
        return normalized
    }

    private fun SquatFrameResult.toJsonLine(
        practice: SquatPracticeWindow?,
        effective: SquatConfig,
        evaluation: Boolean,
        first: Boolean,
    ): String = buildJsonObject {
        put("timestamp_ns", timestampNs)
        put("knee_degrees", kneeDegrees?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("phase", phase.name)
        put("count", count)
        put("reason", reason.name)
        put("counted", counted)
        if (practice != null) {
            put("evaluation", evaluation)
            if (first) put("calibration", buildJsonObject {
                put("profile", if (effective.oppositeMinimumExcursionDegrees == null)
                    "supervised_per_side_projected_range_v1" else "supervised_per_side_motion_witness_v1")
                put("start_ns", practice.startNs)
                put("end_ns", practice.endNs)
                put("minimum_range_degrees", practice.minimumRangeDegrees)
                put("endpoint_fraction", practice.endpointFraction)
                put("flexed_degrees", effective.flexedDegrees)
                put("extended_degrees", effective.extendedDegrees)
                put("opposite_flexed_degrees", effective.oppositeFlexedDegrees)
                effective.oppositeMinimumExcursionDegrees?.let { put("opposite_minimum_excursion_degrees", it) }
            })
        }
    }.toString()
}

fun main(args: Array<String>) = ReplaySquatPose.main(args)
