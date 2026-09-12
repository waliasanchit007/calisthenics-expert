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

class ReplayJumpingJackPoseFormatException(message: String) : IllegalArgumentException(message)

data class JumpingJackPracticeWindow(
    val startNs: Long,
    val endNs: Long,
    val minimumRangeDegrees: Double,
    val endpointFraction: Double,
)

object ReplayJumpingJackPoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf(
        "arms_up_degrees", "arms_down_degrees", "legs_spread_ratio", "legs_together_ratio",
        "minimum_confidence", "maximum_gap_ns", "minimum_endpoint_samples",
    )
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    fun parseConfig(text: String): JumpingJackConfig {
        val root = parseObject(text, "config")
        val hasWindow = "practice_window" in root
        val hasSearch = "practice_search" in root
        if (hasWindow && hasSearch) invalid("practice_window and practice_search are mutually exclusive")
        requireKeys(
            root,
            when {
                hasWindow -> configKeys + "practice_window"
                hasSearch -> configKeys + "practice_search"
                else -> configKeys
            },
            "config",
        )
        return try {
            JumpingJackConfig(
                armsUpDegrees = root.requiredDouble("arms_up_degrees", "config"),
                armsDownDegrees = root.requiredDouble("arms_down_degrees", "config"),
                legsSpreadRatio = root.requiredDouble("legs_spread_ratio", "config"),
                legsTogetherRatio = root.requiredDouble("legs_together_ratio", "config"),
                minimumConfidence = root.requiredDouble("minimum_confidence", "config"),
                maximumGapNs = root.requiredLong("maximum_gap_ns", "config"),
                minimumEndpointSamples = root.requiredInt("minimum_endpoint_samples", "config"),
            )
        } catch (error: ReplayJumpingJackPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid config")
        }
    }

    fun parsePracticeWindow(text: String): JumpingJackPracticeWindow? {
        val root = parseObject(text, "config")
        if ("practice_window" !in root) return null
        if ("practice_search" in root) invalid("practice_window and practice_search are mutually exclusive")
        val window = root["practice_window"] as? JsonObject ?: invalid("practice_window must be an object")
        val keys = setOf("start_ns", "end_ns", "minimum_range_degrees", "endpoint_fraction")
        requireKeys(window, keys, "practice_window")
        val result = JumpingJackPracticeWindow(
            window.requiredLong("start_ns", "practice_window"),
            window.requiredLong("end_ns", "practice_window"),
            window.requiredDouble("minimum_range_degrees", "practice_window"),
            window.requiredDouble("endpoint_fraction", "practice_window"),
        )
        if (result.startNs < 0 || result.endNs <= result.startNs) invalid("invalid practice window bounds")
        return result
    }

    fun parsePracticeSearch(text: String): JumpingJackPracticeSearch? {
        val root = parseObject(text, "config")
        if ("practice_search" !in root) return null
        if ("practice_window" in root) invalid("practice_window and practice_search are mutually exclusive")
        val search = root["practice_search"] as? JsonObject ?: invalid("practice_search must be an object")
        val keys = setOf("start_ns", "deadline_ns", "minimum_range_degrees", "endpoint_fraction")
        requireKeys(search, keys, "practice_search")
        return try {
            JumpingJackPracticeSearch(
                search.requiredLong("start_ns", "practice_search"),
                search.requiredLong("deadline_ns", "practice_search"),
                search.requiredDouble("minimum_range_degrees", "practice_search"),
                search.requiredDouble("endpoint_fraction", "practice_search"),
            )
        } catch (error: ReplayJumpingJackPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid practice search")
        }
    }

    fun parseFrames(lines: Sequence<String>): List<JumpingJackPoseFrame> {
        val frames = ArrayList<JumpingJackPoseFrame>()
        var previousTimestamp: Long? = null
        lines.forEachIndexed { index, line ->
            val frame = parseFrame(line, index + 1, previousTimestamp)
            frames += frame
            previousTimestamp = frame.timestampNs
        }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): JumpingJackPoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = try {
            parseObject(line, "line $lineNumber")
        } catch (error: ReplayJumpingJackPoseFormatException) {
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
            } catch (error: ReplayJumpingJackPoseFormatException) {
                throw error
            } catch (error: Exception) {
                invalid("line $lineNumber: landmarks must be an array")
            }
        } ?: invalid("line $lineNumber: missing landmarks")
        return JumpingJackPoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
            ?: invalid("$label must be a JSON object")
    } catch (error: ReplayJumpingJackPoseFormatException) {
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

    private fun invalid(message: String): Nothing = throw ReplayJumpingJackPoseFormatException(message)
}

object ReplayJumpingJackPose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path")
        val config = strictPath(configPath, "jumping jack config path")
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            "jumping jack output path already exists: $output"
        }
        require(output.parent != null && Files.isDirectory(output.parent)) {
            "jumping jack output parent is not a directory: ${output.parent}"
        }
        require(output != pose && output != config) { "jumping jack output path must differ from inputs" }

        val configText = Files.readString(config, StandardCharsets.UTF_8)
        val base = ReplayJumpingJackPoseParser.parseConfig(configText)
        val practice = ReplayJumpingJackPoseParser.parsePracticeWindow(configText)
        val search = ReplayJumpingJackPoseParser.parsePracticeSearch(configText)
        var practiceEndpoint = emptyList<JumpingJackPoseFrame>()
        var calibrationEndNs: Long? = practice?.endNs
        val effective = when {
            practice != null -> {
                val frames = Files.newBufferedReader(pose, StandardCharsets.UTF_8).use {
                    ReplayJumpingJackPoseParser.parseFrames(it.lineSequence())
                }
                require(
                    frames.isNotEmpty() && frames.first().timestampNs <= practice.startNs &&
                        frames.last().timestampNs > practice.endNs,
                ) { "practice window requires coverage and later evaluation frames" }
                val fitting = frames.filter { it.timestampNs in practice.startNs..practice.endNs }
                require(
                    fitting.isNotEmpty() && fitting.first().timestampNs - practice.startNs <= base.maximumGapNs &&
                        practice.endNs - fitting.last().timestampNs <= base.maximumGapNs,
                ) { "practice window lacks boundary observations" }
                calibrateJumpingJackPractice(fitting, base, practice.minimumRangeDegrees, practice.endpointFraction).also {
                    // Validation established these exact frames are a sustained closed endpoint.
                    practiceEndpoint = fitting.takeLast(base.minimumEndpointSamples)
                }
            }
            search != null -> {
                val frames = Files.newBufferedReader(pose, StandardCharsets.UTF_8).use {
                    ReplayJumpingJackPoseParser.parseFrames(it.lineSequence())
                }
                require(frames.isNotEmpty() && frames.first().timestampNs <= search.startNs) {
                    "practice search requires input coverage of start"
                }
                val candidates = frames.filter {
                    it.timestampNs >= search.startNs && it.timestampNs <= search.deadlineNs
                }
                require(
                    candidates.isNotEmpty() && candidates.first().timestampNs - search.startNs <= base.maximumGapNs,
                ) { "practice search lacks an initial observation within maximum_gap_ns" }
                val accepted = requireNotNull(findFirstJumpingJackPracticeCalibration(candidates, base, search)) {
                    "practice search found no qualifying prefix"
                }
                val prefix = candidates.take(accepted.completionIndex + 1)
                val actualEndNs = prefix.last().timestampNs
                calibrationEndNs = actualEndNs
                require(frames.any { it.timestampNs > actualEndNs }) {
                    "practice search requires later evaluation frames"
                }
                // The accepted prefix ends in the same strict closed support used by supervised practice.
                practiceEndpoint = prefix.takeLast(base.minimumEndpointSamples)
                accepted.effectiveConfig
            }
            else -> base
        }
        val experiment = JumpingJackExperiment(effective)
        practiceEndpoint.forEach { experiment.onFrame(it) }
        var writer: java.io.BufferedWriter? = null
        try {
            Files.newBufferedReader(pose, StandardCharsets.UTF_8).use { reader ->
                var previousTimestamp: Long? = null
                var lineNumber = 0
                reader.forEachLine { line ->
                    lineNumber++
                    val frame = ReplayJumpingJackPoseParser.parseFrame(line, lineNumber, previousTimestamp)
                    previousTimestamp = frame.timestampNs
                    val evaluation = calibrationEndNs?.let { frame.timestampNs > it } ?: true
                    val result = if (evaluation) experiment.onFrame(frame) else {
                        val measurement = measureJumpingJack(frame, base)
                        JumpingJackFrameResult(
                            frame.timestampNs, measurement.armDegrees, measurement.legSpreadRatio,
                            JumpingJackPhase.WAITING, 0, JumpingJackReason.CALIBRATION_PRACTICE, false,
                        )
                    }
                    val outputWriter = writer ?: Files.newBufferedWriter(
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ).also { writer = it }
                    outputWriter.write(
                        result.toJsonLine(practice, search, calibrationEndNs, effective, evaluation, lineNumber == 1),
                    )
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
            "expected --posePath=/path --jumpingJackConfigPath=/path --jumpingJackOutputPath=/path"
        }
        val values = args.associate { argument ->
            require(argument.startsWith("--")) { "arguments must use --name=/path" }
            val separator = argument.indexOf('=')
            require(separator > 2 && separator < argument.lastIndex) { "arguments must use --name=/path" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.keys == setOf("posePath", "jumpingJackConfigPath", "jumpingJackOutputPath")) {
            "expected exactly posePath, jumpingJackConfigPath and jumpingJackOutputPath"
        }
        require(values.size == args.size) { "duplicate replay argument" }
        run(
            Paths.get(values.getValue("posePath")),
            Paths.get(values.getValue("jumpingJackConfigPath")),
            Paths.get(values.getValue("jumpingJackOutputPath")),
        )
    }

    private fun strictPath(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "$label is not a regular file: $normalized" }
        return normalized
    }

    private fun JumpingJackFrameResult.toJsonLine(
        practice: JumpingJackPracticeWindow?,
        search: JumpingJackPracticeSearch?,
        calibrationEndNs: Long?,
        effective: JumpingJackConfig,
        evaluation: Boolean,
        first: Boolean,
    ): String = buildJsonObject {
        put("timestamp_ns", timestampNs)
        put("arm_degrees", armDegrees?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("leg_spread_ratio", legSpreadRatio?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("phase", phase.name)
        put("count", count)
        put("reason", reason.name)
        put("counted", counted)
        if (practice != null) {
            put("evaluation", evaluation)
            if (first) put("calibration", buildJsonObject {
                put("profile", "supervised_arms_down_projected_range_v1")
                put("start_ns", practice.startNs)
                put("end_ns", practice.endNs)
                put("minimum_range_degrees", practice.minimumRangeDegrees)
                put("endpoint_fraction", practice.endpointFraction)
                put("arms_down_degrees", effective.armsDownDegrees)
                put("arms_up_degrees", effective.armsUpDegrees)
            })
        } else if (search != null) {
            put("evaluation", evaluation)
            if (first) put("calibration", buildJsonObject {
                put("profile", "causal_first_supported_return_v1")
                put("start_ns", search.startNs)
                put("deadline_ns", search.deadlineNs)
                put("end_ns", requireNotNull(calibrationEndNs))
                put("minimum_range_degrees", search.minimumRangeDegrees)
                put("endpoint_fraction", search.endpointFraction)
                put("arms_down_degrees", effective.armsDownDegrees)
                put("arms_up_degrees", effective.armsUpDegrees)
            })
        }
    }.toString()
}

fun main(args: Array<String>) = ReplayJumpingJackPose.main(args)
