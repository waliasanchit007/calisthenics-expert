package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ReplaySitupPoseFormatException(message: String) : IllegalArgumentException(message)

object ReplaySitupPoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf(
        "side", "reclined_hip_min_degrees", "raised_hip_max_degrees", "reclined_trunk_max_degrees",
        "raised_trunk_min_degrees", "minimum_trunk_rise_degrees", "minimum_confidence",
        "maximum_gap_ns", "minimum_endpoint_samples",
    )
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    data class SideAdmissionWindow(val startNs: Long, val endNs: Long)

    fun parseConfig(text: String): SitupConfig {
        val root = parseObject(text, "config")
        requireConfigKeys(root)
        val side = root.requiredString("side", "config").let {
            try {
                ArmSide.valueOf(it)
            } catch (_: IllegalArgumentException) {
                invalid("config.side must be LEFT or RIGHT")
            }
        }
        return try {
            SitupConfig(
                side = side,
                reclinedHipMinDegrees = root.requiredDouble("reclined_hip_min_degrees", "config"),
                raisedHipMaxDegrees = root.requiredDouble("raised_hip_max_degrees", "config"),
                reclinedTrunkMaxDegrees = root.requiredDouble("reclined_trunk_max_degrees", "config"),
                raisedTrunkMinDegrees = root.requiredDouble("raised_trunk_min_degrees", "config"),
                minimumTrunkRiseDegrees = root.requiredDouble("minimum_trunk_rise_degrees", "config"),
                minimumConfidence = root.requiredDouble("minimum_confidence", "config"),
                maximumGapNs = root.requiredLong("maximum_gap_ns", "config"),
                minimumEndpointSamples = root.requiredInt("minimum_endpoint_samples", "config"),
            )
        } catch (error: ReplaySitupPoseFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "invalid config")
        }
    }

    /** Optional opt-in bounded sit-up side admission window; absent means unchanged baseline behavior. */
    fun parseSideAdmissionWindow(text: String): SideAdmissionWindow? {
        val root = parseObject(text, "config")
        val element = root["side_admission_window"] ?: return null
        val windowObject = element as? JsonObject ?: invalid("config.side_admission_window must be an object")
        requireKeys(windowObject, setOf("start_ns", "end_ns"), "config.side_admission_window")
        val startNs = windowObject.requiredLong("start_ns", "config.side_admission_window")
        val endNs = windowObject.requiredLong("end_ns", "config.side_admission_window")
        if (startNs < 0 || endNs <= startNs) {
            invalid("config.side_admission_window requires 0 <= start_ns < end_ns")
        }
        return SideAdmissionWindow(startNs, endNs)
    }

    private fun requireConfigKeys(root: JsonObject) {
        val allowed = configKeys + "side_admission_window"
        if (root.keys != configKeys && root.keys != allowed) {
            invalid(
                "config keys mismatch; missing=${configKeys - root.keys} unknown=${root.keys - allowed}",
            )
        }
    }

    fun parseFrames(lines: Sequence<String>): List<SitupPoseFrame> {
        val frames = ArrayList<SitupPoseFrame>()
        var previousTimestamp: Long? = null
        lines.forEachIndexed { index, line ->
            val frame = parseFrame(line, index + 1, previousTimestamp)
            frames += frame
            previousTimestamp = frame.timestampNs
        }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): SitupPoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = try {
            parseObject(line, "line $lineNumber")
        } catch (error: ReplaySitupPoseFormatException) {
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
                    val label = "line $lineNumber landmark $landmarkIndex"
                    val landmarkObject = value as? JsonObject ?: invalid("$label: must be an object")
                    requireKeys(landmarkObject, landmarkKeys, label)
                    Landmark(
                        index = landmarkObject.requiredInt("index", label),
                        name = null,
                        x = landmarkObject.requiredDouble("x", label),
                        y = landmarkObject.requiredDouble("y", label),
                        z = landmarkObject.requiredDouble("z", label),
                        visibility = landmarkObject.requiredDouble("visibility", label),
                        presence = landmarkObject.requiredDouble("presence", label),
                    )
                }
            } catch (error: ReplaySitupPoseFormatException) {
                throw error
            } catch (error: Exception) {
                invalid("line $lineNumber: landmarks must be an array")
            }
        } ?: invalid("line $lineNumber: missing landmarks")
        return SitupPoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: invalid("$label must be a JSON object")
    } catch (error: ReplaySitupPoseFormatException) {
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

    private fun invalid(message: String): Nothing = throw ReplaySitupPoseFormatException(message)
}

private data class SideAdmissionMetadata(
    val startNs: Long,
    val endNs: Long,
    val preferredSide: ArmSide,
    val selectedSide: ArmSide,
    val minimumSamples: Int,
    val observedSamples: Int,
    val leftUsableSamples: Int,
    val rightUsableSamples: Int,
) {
    val profile: String get() = "tracking_only_fixed_side_v1"
}

object ReplaySitupPose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path")
        val config = strictPath(configPath, "situp config path")
        val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            "situp output path already exists: $output"
        }
        require(output.parent != null && Files.isDirectory(output.parent)) {
            "situp output parent is not a directory: ${output.parent}"
        }
        require(output != pose && output != config) { "situp output path must differ from inputs" }

        val configText = Files.readString(config, StandardCharsets.UTF_8)
        val situpConfig = ReplaySitupPoseParser.parseConfig(configText)
        val window = ReplaySitupPoseParser.parseSideAdmissionWindow(configText)
        if (window != null) {
            runWithSideAdmission(pose, situpConfig, window, output)
        } else {
            runBaseline(pose, situpConfig, output)
        }
    }

    private fun runBaseline(pose: Path, situpConfig: SitupConfig, output: Path) {
        val experiment = SitupCycleExperiment(situpConfig)
        var writer: java.io.BufferedWriter? = null
        try {
            Files.newBufferedReader(pose, StandardCharsets.UTF_8).use { reader ->
                var previousTimestamp: Long? = null
                var lineNumber = 0
                // The most recent row is held back by one frame so that, if input ends with a
                // candidate still open, its still-null closed_cycle can be replaced by the censored
                // closure before it is written — never as an extra row, never silently dropped,
                // preserving exactly one output row per input frame.
                var pending: SitupFrameResult? = null
                reader.forEachLine { line ->
                    lineNumber++
                    val frame = ReplaySitupPoseParser.parseFrame(line, lineNumber, previousTimestamp)
                    previousTimestamp = frame.timestampNs
                    val result = experiment.onFrame(frame)
                    pending?.let { previous ->
                        val outputWriter = writer ?: newWriter(output).also { writer = it }
                        outputWriter.write(previous.toJsonLine())
                        outputWriter.newLine()
                    }
                    pending = result
                }
                require(lineNumber > 0) { "pose input must contain at least one JSONL frame" }
                val censored = experiment.finish()
                val last = requireNotNull(pending).let {
                    if (censored != null) it.copy(closedCycle = censored) else it
                }
                val outputWriter = writer ?: newWriter(output).also { writer = it }
                outputWriter.write(last.toJsonLine())
                outputWriter.newLine()
            }
        } finally {
            writer?.close()
        }
    }

    /**
     * Opt-in bounded sit-up side admission: a side is selected once from ONLY the frames inside
     * the declared `[start_ns, end_ns]` setup window (never a rep count, never a later frame).
     * Every frame at or before `end_ns` is an unconditional prefix row (never fed to any counter);
     * a fresh, unchanged counter for the selected side is fed ONLY frames after `end_ns`.
     */
    private fun runWithSideAdmission(
        pose: Path,
        baseConfig: SitupConfig,
        window: ReplaySitupPoseParser.SideAdmissionWindow,
        output: Path,
    ) {
        val lines = Files.readAllLines(pose, StandardCharsets.UTF_8)
        require(lines.isNotEmpty()) { "pose input must contain at least one JSONL frame" }
        val frames = ReplaySitupPoseParser.parseFrames(lines.asSequence())

        val windowFrames = frames.filter { it.timestampNs in window.startNs..window.endNs }
        require(frames.first().timestampNs <= window.startNs) {
            "side_admission_window requires the clip to cover the declared start_ns"
        }
        require(frames.any { it.timestampNs > window.endNs }) {
            "side_admission_window requires at least one evaluation frame after end_ns"
        }
        require(
            windowFrames.isNotEmpty() &&
                windowFrames.first().timestampNs - window.startNs <= baseConfig.maximumGapNs,
        ) {
            "side_admission_window requires the first setup observation within maximum_gap_ns of start_ns"
        }
        require(
            windowFrames.isNotEmpty() &&
                window.endNs - windowFrames.last().timestampNs <= baseConfig.maximumGapNs,
        ) {
            "side_admission_window requires the last setup observation within maximum_gap_ns of end_ns"
        }
        val selectedSide = selectSitupSide(windowFrames, baseConfig)
        require(selectedSide != null) {
            "side_admission_window has no eligible side in the declared setup window"
        }
        val admission = SideAdmissionMetadata(
            startNs = window.startNs,
            endNs = window.endNs,
            preferredSide = baseConfig.side,
            selectedSide = selectedSide,
            minimumSamples = maxOf(2, baseConfig.minimumEndpointSamples),
            observedSamples = windowFrames.size,
            leftUsableSamples = usableSampleCount(windowFrames, baseConfig, ArmSide.LEFT),
            rightUsableSamples = usableSampleCount(windowFrames, baseConfig, ArmSide.RIGHT),
        )
        val experiment = SitupCycleExperiment(baseConfig.copy(side = selectedSide))

        newWriter(output).use { writer ->
            // Same one-row hold-back as the baseline path: the last written row, whichever branch
            // produced it, may still need its closed_cycle replaced by a censored closure.
            var pending: Pair<SitupFrameResult, Boolean>? = null
            var admissionWritten = false
            for (frame in frames) {
                val evaluation = frame.timestampNs > window.endNs
                val result = if (evaluation) {
                    experiment.onFrame(frame)
                } else {
                    SitupFrameResult(
                        timestampNs = frame.timestampNs,
                        hipDegrees = null,
                        torsoElevationDegrees = null,
                        phase = SitupPhase.WAITING,
                        count = 0,
                        reason = SitupReason.SIDE_ADMISSION,
                        counted = false,
                        closedCycle = null,
                    )
                }
                pending?.let { (previous, previousEvaluation) ->
                    val previousAdmission = if (!admissionWritten) admission else null
                    admissionWritten = true
                    writer.write(previous.toJsonLine(evaluation = previousEvaluation, admission = previousAdmission))
                    writer.newLine()
                }
                pending = result to evaluation
            }
            val censored = experiment.finish()
            val (last, lastEvaluation) = requireNotNull(pending)
            val finalResult = if (censored != null) last.copy(closedCycle = censored) else last
            val finalAdmission = if (!admissionWritten) admission else null
            writer.write(finalResult.toJsonLine(evaluation = lastEvaluation, admission = finalAdmission))
            writer.newLine()
        }
    }

    private fun usableSampleCount(frames: List<SitupPoseFrame>, base: SitupConfig, side: ArmSide): Int {
        val config = base.copy(side = side)
        return frames.count { frame ->
            val measurement = measureSitupTrunk(frame, config)
            measurement.reason == SitupReason.NONE &&
                measurement.hipDegrees != null &&
                measurement.torsoElevationDegrees != null
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "expected --posePath=/path --situpConfigPath=/path --situpOutputPath=/path"
        }
        val values = args.associate { argument ->
            require(argument.startsWith("--")) { "arguments must use --name=/path" }
            val separator = argument.indexOf('=')
            require(separator > 2 && separator < argument.lastIndex) { "arguments must use --name=/path" }
            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.keys == setOf("posePath", "situpConfigPath", "situpOutputPath")) {
            "expected exactly posePath, situpConfigPath and situpOutputPath"
        }
        require(values.size == args.size) { "duplicate replay argument" }
        run(
            Paths.get(values.getValue("posePath")),
            Paths.get(values.getValue("situpConfigPath")),
            Paths.get(values.getValue("situpOutputPath")),
        )
    }

    private fun newWriter(output: Path): java.io.BufferedWriter = Files.newBufferedWriter(
        output,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )

    private fun strictPath(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "$label is not a regular file: $normalized" }
        return normalized
    }

    private fun SitupFrameResult.toJsonLine(
        evaluation: Boolean? = null,
        admission: SideAdmissionMetadata? = null,
    ): String = buildJsonObject {
        put("timestamp_ns", timestampNs)
        put("phase", phase.name)
        put("count", count)
        put("reason", reason.name)
        put("counted", counted)
        put("hip_degrees", hipDegrees?.let(::JsonPrimitive) ?: JsonNull)
        put("torso_elevation_degrees", torsoElevationDegrees?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "closed_cycle",
            closedCycle?.let { cycle ->
                buildJsonObject {
                    put("departure_timestamp_ns", cycle.departureTimestampNs)
                    put("raised_timestamp_ns", cycle.raisedTimestampNs?.let(::JsonPrimitive) ?: JsonNull)
                    put("return_timestamp_ns", cycle.returnTimestampNs?.let(::JsonPrimitive) ?: JsonNull)
                    put("outcome", cycle.outcome.name)
                    put("minimum_hip_degrees", cycle.minimumHipDegrees)
                    put("observed_trunk_rise_degrees", cycle.observedTrunkRiseDegrees)
                }
            } ?: JsonNull,
        )
        if (evaluation != null) put("evaluation", evaluation)
        if (admission != null) {
            put(
                "side_admission",
                buildJsonObject {
                    put("profile", admission.profile)
                    put("start_ns", admission.startNs)
                    put("end_ns", admission.endNs)
                    put("preferred_side", admission.preferredSide.name)
                    put("selected_side", admission.selectedSide.name)
                    put("minimum_samples", admission.minimumSamples)
                    put("observed_samples", admission.observedSamples)
                    put("left_usable_samples", admission.leftUsableSamples)
                    put("right_usable_samples", admission.rightUsableSamples)
                },
            )
        }
    }.toString()
}

fun main(args: Array<String>) = ReplaySitupPose.main(args)
