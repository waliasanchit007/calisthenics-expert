package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/** Offline only: reuse the existing strict pose-row reader, never another movement's geometry. */
object ReplayHspuPose {
    private val KEYS = setOf(
        "variant", "side", "minimum_hip_above_shoulder_fraction",
        "minimum_reference_samples", "maximum_reference_span_spread_ratio",
        "extended_span_ratio", "lowered_span_ratio",
        "minimum_confidence", "maximum_gap_ns", "minimum_endpoint_samples",
    )

    fun parseConfig(text: String): HspuConfig {
        val j = Json.parseToJsonElement(text).jsonObject
        require(j.keys == KEYS) { "config keys must be exactly $KEYS" }
        fun number(key: String): JsonPrimitive {
            val p = j.getValue(key).jsonPrimitive
            require(!p.isString) { "$key must be a number" }
            return p
        }
        return HspuConfig(
            variant = HspuVariant.valueOf(j.getValue("variant").jsonPrimitive.content),
            side = ArmSide.valueOf(j.getValue("side").jsonPrimitive.content),
            minimumHipAboveShoulderFraction = number("minimum_hip_above_shoulder_fraction").double,
            minimumReferenceSamples = number("minimum_reference_samples").int,
            maximumReferenceSpanSpreadRatio = number("maximum_reference_span_spread_ratio").double,
            extendedSpanRatio = number("extended_span_ratio").double,
            loweredSpanRatio = number("lowered_span_ratio").double,
            minimumConfidence = number("minimum_confidence").double,
            maximumGapNs = number("maximum_gap_ns").long,
            minimumEndpointSamples = number("minimum_endpoint_samples").int,
        )
    }

    fun run(input: Path, config: Path, output: Path) {
        require(!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "output already exists" }
        require(output != input && output != config) { "output must differ from inputs" }
        val engine = HandstandPushUpExperiment(parseConfig(Files.readString(config)))
        val frames = Files.newBufferedReader(input).use { ReplayPikePushUpPoseParser.parseFrames(it.lineSequence()) }
        Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            for (f in frames) {
                val r = engine.onFrame(HspuPoseFrame(f.timestampNs, f.width, f.height, f.landmarks))
                val row = buildJsonObject {
                    put("timestamp_ns", r.timestampNs); put("count", r.count); put("counted", r.counted)
                    put("event_timestamp_ns", r.eventTimestampNs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("phase", r.phase.name); put("reason", r.reason.name)
                    put("hip_above_shoulder_fraction", r.hipAboveShoulderFraction?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("shoulder_to_wrist_span", r.shoulderToWristSpan?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("span_ratio", r.spanRatio?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                writer.write(row.toString()); writer.newLine()
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.startsWith("--") && '=' in it })
        val options = args.map { it.removePrefix("--").split('=', limit = 2) }.associate { it[0] to it[1] }
        require(options.keys == setOf("posePath", "hspuConfigPath", "hspuOutputPath"))
        run(
            Path.of(options.getValue("posePath")),
            Path.of(options.getValue("hspuConfigPath")),
            Path.of(options.getValue("hspuOutputPath")),
        )
    }
}

/** Top-level shim so a Gradle JavaExec task can resolve `ReplayHspuPoseKt`. */
fun main(args: Array<String>) = ReplayHspuPose.main(args)
