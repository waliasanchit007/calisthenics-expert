package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.contract.Landmark
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
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

class ReplayHangingLegRaisePoseFormatException(message: String) : IllegalArgumentException(message)

object ReplayHangingLegRaisePoseParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val configKeys = setOf("mode", "side", "lowered_hip_min_degrees", "raised_hip_max_degrees", "minimum_hip_close_degrees", "raised_knee_min_degrees", "hanging_trunk_min_degrees", "minimum_support_rise_ratio", "maximum_support_travel_ratio", "minimum_confidence", "maximum_gap_ns", "minimum_endpoint_samples")
    private val frameKeys = setOf("timestamp_ns", "width", "height", "landmarks")
    private val landmarkKeys = setOf("index", "x", "y", "z", "visibility", "presence")

    fun parseConfig(text: String): HangingLegRaiseConfig {
        val root = parseObject(text, "config")
        requireKeys(root, configKeys, "config")
        val mode = root.requiredString("mode", "config").let { value -> try { HangingLegRaiseMode.valueOf(value) } catch (_: IllegalArgumentException) { invalid("config.mode must be SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED") } }
        val side = root.requiredString("side", "config").let { value -> try { ArmSide.valueOf(value) } catch (_: IllegalArgumentException) { invalid("config.side must be LEFT or RIGHT") } }
        return try {
            HangingLegRaiseConfig(mode, side, root.requiredDouble("lowered_hip_min_degrees", "config"), root.requiredDouble("raised_hip_max_degrees", "config"), root.requiredDouble("minimum_hip_close_degrees", "config"), root.requiredDouble("raised_knee_min_degrees", "config"), root.requiredDouble("hanging_trunk_min_degrees", "config"), root.requiredDouble("minimum_support_rise_ratio", "config"), root.requiredDouble("maximum_support_travel_ratio", "config"), root.requiredDouble("minimum_confidence", "config"), root.requiredLong("maximum_gap_ns", "config"), root.requiredInt("minimum_endpoint_samples", "config"))
        } catch (e: ReplayHangingLegRaisePoseFormatException) { throw e } catch (e: IllegalArgumentException) { invalid(e.message ?: "invalid config") }
    }

    fun parseFrames(lines: Sequence<String>): List<HangingLegRaisePoseFrame> {
        val frames = ArrayList<HangingLegRaisePoseFrame>(); var previous: Long? = null
        lines.forEachIndexed { index, line -> val frame = parseFrame(line, index + 1, previous); frames += frame; previous = frame.timestampNs }
        return frames
    }

    internal fun parseFrame(line: String, lineNumber: Int, previousTimestamp: Long?): HangingLegRaisePoseFrame {
        if (line.isBlank()) invalid("line $lineNumber: blank JSONL row")
        val root = try { parseObject(line, "line $lineNumber") } catch (e: ReplayHangingLegRaisePoseFormatException) { throw e } catch (e: Exception) { invalid("line $lineNumber: invalid JSON: ${e.message ?: "parse failure"}") }
        requireKeys(root, frameKeys, "line $lineNumber")
        val timestamp = root.requiredLong("timestamp_ns", "line $lineNumber")
        if (timestamp < 0 || previousTimestamp?.let { timestamp <= it } == true) invalid("line $lineNumber: timestamp_ns must be strictly increasing and nonnegative")
        val width = root.requiredInt("width", "line $lineNumber"); val height = root.requiredInt("height", "line $lineNumber")
        if (width <= 0 || height <= 0) invalid("line $lineNumber: width and height must be positive")
        val landmarks = try { root["landmarks"]!!.jsonArray.mapIndexed { i, value ->
            val label = "line $lineNumber landmark $i"; val obj = value as? JsonObject ?: invalid("$label: must be an object"); requireKeys(obj, landmarkKeys, label)
            Landmark(obj.requiredInt("index", label), null, obj.requiredDouble("x", label), obj.requiredDouble("y", label), obj.requiredDouble("z", label), obj.requiredDouble("visibility", label), obj.requiredDouble("presence", label))
        } } catch (e: ReplayHangingLegRaisePoseFormatException) { throw e } catch (_: Exception) { invalid("line $lineNumber: landmarks must be an array") }
        return HangingLegRaisePoseFrame(timestamp, width, height, landmarks)
    }

    private fun parseObject(text: String, label: String): JsonObject = try { json.parseToJsonElement(text) as? JsonObject ?: invalid("$label must be a JSON object") } catch (e: ReplayHangingLegRaisePoseFormatException) { throw e } catch (e: Exception) { invalid("$label: invalid JSON: ${e.message ?: "parse failure"}") }
    private fun requireKeys(root: JsonObject, expected: Set<String>, label: String) { if (root.keys != expected) invalid("$label keys mismatch; missing=${expected - root.keys} unknown=${root.keys - expected}") }
    private fun JsonObject.requiredString(key: String, label: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$label.$key must be a string")
    private fun JsonObject.requiredDouble(key: String, label: String): Double { val p = this[key] as? JsonPrimitive; if (p == null || p.isString) invalid("$label.$key must be a number"); return p.doubleOrNull?.takeIf { it.isFinite() } ?: invalid("$label.$key must be a finite number") }
    private fun JsonObject.requiredLong(key: String, label: String): Long { val p = this[key] as? JsonPrimitive; if (p == null || p.isString) invalid("$label.$key must be an integer"); return p.longOrNull ?: invalid("$label.$key must be an integer") }
    private fun JsonObject.requiredInt(key: String, label: String): Int { val p = this[key] as? JsonPrimitive; if (p == null || p.isString) invalid("$label.$key must be an integer"); return p.intOrNull ?: invalid("$label.$key must be an integer") }
    private fun invalid(message: String): Nothing = throw ReplayHangingLegRaisePoseFormatException(message)
}

object ReplayHangingLegRaisePose {
    fun run(posePath: Path, configPath: Path, outputPath: Path) {
        val pose = strictPath(posePath, "pose path"); val config = strictPath(configPath, "hanging leg raise config path"); val output = outputPath.toAbsolutePath().normalize()
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) { "hanging leg raise output path already exists: $output" }
        require(output.parent != null && Files.isDirectory(output.parent)) { "hanging leg raise output parent is not a directory: ${output.parent}" }
        require(output != pose && output != config) { "hanging leg raise output path must differ from inputs" }
        val parsedConfig = ReplayHangingLegRaisePoseParser.parseConfig(Files.readString(config, StandardCharsets.UTF_8))
        val frames = ReplayHangingLegRaisePoseParser.parseFrames(Files.readAllLines(pose, StandardCharsets.UTF_8).asSequence())
        require(frames.isNotEmpty()) { "pose input must contain at least one JSONL frame" }
        val experiment = HangingLegRaiseExperiment(parsedConfig)
        val results = frames.map { experiment.onFrame(it) }
        val censored = experiment.finish()
        Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { writer -> results.forEachIndexed { i, result -> writer.write(result.toJsonLine(if (i == results.lastIndex) censored else null)); writer.newLine() } }
    }

    private fun strictPath(path: Path, label: String): Path { val normalized = path.toAbsolutePath().normalize(); require(Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) { "$label is not a regular file: $normalized" }; return normalized }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.startsWith("--") && it.substringAfter('=').isNotEmpty() }) { "expected --posePath=/path --hangingLegRaiseConfigPath=/path --hangingLegRaiseOutputPath=/path" }
        val values = args.map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
        require(values.keys == setOf("--posePath", "--hangingLegRaiseConfigPath", "--hangingLegRaiseOutputPath")) { "expected --posePath=/path --hangingLegRaiseConfigPath=/path --hangingLegRaiseOutputPath=/path" }
        run(Paths.get(values.getValue("--posePath")), Paths.get(values.getValue("--hangingLegRaiseConfigPath")), Paths.get(values.getValue("--hangingLegRaiseOutputPath")))
    }
}

private fun HangingLegRaiseFrameResult.toJsonLine(censored: HangingLegRaiseCycleEvent? = null): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
    put("timestamp_ns", timestampNs); put("phase", phase.name); put("count", count); put("reason", reason.name); put("counted", counted); put("hanging", hanging)
    putNullable("hip_degrees", hipDegrees); putNullable("knee_degrees", kneeDegrees); putNullable("trunk_elevation_degrees", trunkElevationDegrees); putNullable("support_rise_ratio", supportRiseRatio)
    val event = closedCycle ?: censored
    if (event == null) put("closed_cycle", JsonNull) else put("closed_cycle", buildJsonObject { put("departure_timestamp_ns", event.departureTimestampNs); putNullable("raised_timestamp_ns", event.raisedTimestampNs); putNullable("return_timestamp_ns", event.returnTimestampNs); put("outcome", event.outcome.name); put("minimum_hip_degrees", event.minimumHipDegrees); put("observed_hip_close_degrees", event.observedHipCloseDegrees) })
})

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Double?) { if (value == null) put(key, JsonNull) else put(key, value) }
private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) { if (value == null) put(key, JsonNull) else put(key, value) }

fun main(args: Array<String>): Unit = ReplayHangingLegRaisePose.main(args)
