package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/**
 * Offline only. Writes one JSON row per frame, then one final `{"intervals": [...]}` row holding
 * every admitted hold interval, including a right-censored one closed at end of input.
 */
object ReplayHandstandHoldPose {
    private val KEYS = setOf(
        "support", "side", "minimum_hip_above_shoulder_fraction", "minimum_ankle_above_hip_fraction",
        "minimum_confidence", "maximum_gap_ns", "minimum_hold_ns",
        "minimum_non_holding_samples_to_close",
    )

    fun parseConfig(text: String): HandstandHoldConfig {
        val j = Json.parseToJsonElement(text).jsonObject
        require(j.keys == KEYS) { "config keys must be exactly $KEYS" }
        fun number(key: String): JsonPrimitive {
            val p = j.getValue(key).jsonPrimitive
            require(!p.isString) { "$key must be a number" }
            return p
        }
        return HandstandHoldConfig(
            support = HandstandSupport.valueOf(j.getValue("support").jsonPrimitive.content),
            side = ArmSide.valueOf(j.getValue("side").jsonPrimitive.content),
            minimumHipAboveShoulderFraction = number("minimum_hip_above_shoulder_fraction").double,
            minimumAnkleAboveHipFraction = number("minimum_ankle_above_hip_fraction").double,
            minimumConfidence = number("minimum_confidence").double,
            maximumGapNs = number("maximum_gap_ns").long,
            minimumHoldNs = number("minimum_hold_ns").long,
            minimumNonHoldingSamplesToClose = number("minimum_non_holding_samples_to_close").int,
        )
    }

    private fun HandstandHoldInterval.toJson() = buildJsonObject {
        put("entry_ns", entryTimestampNs); put("exit_ns", exitTimestampNs); put("duration_ns", durationNs)
        put("end_reason", endReason.name); put("left_censored", leftCensored); put("right_censored", rightCensored)
    }

    fun run(input: Path, config: Path, output: Path) {
        require(!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "output already exists" }
        require(output != input && output != config) { "output must differ from inputs" }
        val engine = HandstandHoldExperiment(parseConfig(Files.readString(config)))
        val frames = Files.newBufferedReader(input).use { ReplayPikePushUpPoseParser.parseFrames(it.lineSequence()) }
        Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            for (f in frames) {
                val r = engine.onFrame(HandstandPoseFrame(f.timestampNs, f.width, f.height, f.landmarks))
                val row = buildJsonObject {
                    put("timestamp_ns", r.timestampNs); put("holding", r.holding); put("reason", r.reason.name)
                    put("hip_above_shoulder_fraction", r.hipAboveShoulderFraction?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("ankle_above_hip_fraction", r.ankleAboveHipFraction?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                writer.write(row.toString()); writer.newLine()
            }
            engine.finish()
            writer.write(buildJsonObject { put("intervals", JsonArray(engine.intervals.map { it.toJson() })) }.toString())
            writer.newLine()
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.startsWith("--") && '=' in it })
        val options = args.map { it.removePrefix("--").split('=', limit = 2) }.associate { it[0] to it[1] }
        require(options.keys == setOf("posePath", "handstandConfigPath", "handstandOutputPath"))
        run(
            Path.of(options.getValue("posePath")),
            Path.of(options.getValue("handstandConfigPath")),
            Path.of(options.getValue("handstandOutputPath")),
        )
    }
}

/** Top-level shim so a Gradle JavaExec task can resolve `ReplayHandstandHoldPoseKt`. */
fun main(args: Array<String>) = ReplayHandstandHoldPose.main(args)
