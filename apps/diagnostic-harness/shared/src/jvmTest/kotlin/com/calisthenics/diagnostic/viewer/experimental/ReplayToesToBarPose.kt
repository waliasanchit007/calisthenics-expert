package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/** Offline only: reuse the existing strict pose-row reader, never another movement's geometry. */
object ReplayToesToBarPose {
    private val KEYS = setOf(
        "side", "minimum_hands_above_shoulders", "maximum_feet_below_hands_at_bar",
        "minimum_feet_below_hands_down", "minimum_confidence", "maximum_gap_ns", "minimum_endpoint_samples",
        "require_both_ankles",
    )

    fun parseConfig(text: String): ToesToBarConfig {
        val j = Json.parseToJsonElement(text).jsonObject
        require(j.keys == KEYS) { "config keys must be exactly $KEYS" }
        fun number(key: String): JsonPrimitive {
            val p = j.getValue(key).jsonPrimitive
            require(!p.isString) { "$key must be a number" }
            return p
        }
        return ToesToBarConfig(
            side = ArmSide.valueOf(j.getValue("side").jsonPrimitive.content),
            minimumHandsAboveShoulders = number("minimum_hands_above_shoulders").double,
            maximumFeetBelowHandsAtBar = number("maximum_feet_below_hands_at_bar").double,
            minimumFeetBelowHandsDown = number("minimum_feet_below_hands_down").double,
            minimumConfidence = number("minimum_confidence").double,
            maximumGapNs = number("maximum_gap_ns").long,
            minimumEndpointSamples = number("minimum_endpoint_samples").int,
            requireBothAnkles = j.getValue("require_both_ankles").jsonPrimitive.boolean,
        )
    }

    fun run(input: Path, config: Path, output: Path) {
        require(!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "output already exists" }
        require(output != input && output != config) { "output must differ from inputs" }
        val engine = ToesToBarExperiment(parseConfig(Files.readString(config)))
        val frames = Files.newBufferedReader(input).use { ReplayPikePushUpPoseParser.parseFrames(it.lineSequence()) }
        Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            for (f in frames) {
                val r = engine.onFrame(ToesToBarPoseFrame(f.timestampNs, f.width, f.height, f.landmarks))
                val row = buildJsonObject {
                    put("timestamp_ns", r.timestampNs); put("count", r.count); put("counted", r.counted)
                    put("event_timestamp_ns", r.eventTimestampNs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("phase", r.phase.name); put("reason", r.reason.name)
                    put("hands_above_shoulders", r.handsAboveShoulders?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("feet_below_hands", r.feetBelowHands?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                writer.write(row.toString()); writer.newLine()
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.startsWith("--") && '=' in it })
        val options = args.map { it.removePrefix("--").split('=', limit = 2) }.associate { it[0] to it[1] }
        require(options.keys == setOf("posePath", "toesToBarConfigPath", "toesToBarOutputPath"))
        run(
            Path.of(options.getValue("posePath")),
            Path.of(options.getValue("toesToBarConfigPath")),
            Path.of(options.getValue("toesToBarOutputPath")),
        )
    }
}

/** Top-level shim so a Gradle JavaExec task can resolve `ReplayToesToBarPoseKt`. */
fun main(args: Array<String>) = ReplayToesToBarPose.main(args)
