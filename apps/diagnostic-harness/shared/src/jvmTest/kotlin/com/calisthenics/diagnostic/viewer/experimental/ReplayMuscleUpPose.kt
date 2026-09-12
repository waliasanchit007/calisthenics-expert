package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/** Offline only: reuse the existing strict pose-row reader, never another movement's geometry. */
object ReplayMuscleUpPose {
    private val KEYS = setOf(
        "variant", "side",
        "minimum_hang_wrist_above_shoulder_fraction", "minimum_support_shoulder_above_wrist_fraction",
        "maximum_grip_drift_torso_lengths", "minimum_body_scale_ratio", "maximum_body_scale_ratio",
        "minimum_confidence", "maximum_gap_ns", "minimum_endpoint_samples",
        "minimum_reference_samples", "maximum_reference_wrist_spread_torso_lengths",
        "hang_lockout_elbow_degrees",
    )

    fun parseConfig(text: String): MuscleUpConfig {
        val j = Json.parseToJsonElement(text).jsonObject
        require(j.keys == KEYS) { "config keys must be exactly $KEYS" }
        fun number(key: String): JsonPrimitive {
            val p = j.getValue(key).jsonPrimitive
            require(!p.isString) { "$key must be a number" }
            return p
        }
        return MuscleUpConfig(
            variant = MuscleUpVariant.valueOf(j.getValue("variant").jsonPrimitive.content),
            side = ArmSide.valueOf(j.getValue("side").jsonPrimitive.content),
            minimumHangWristAboveShoulderFraction = number("minimum_hang_wrist_above_shoulder_fraction").double,
            minimumSupportShoulderAboveWristFraction = number("minimum_support_shoulder_above_wrist_fraction").double,
            maximumGripDriftTorsoLengths = number("maximum_grip_drift_torso_lengths").double,
            minimumBodyScaleRatio = number("minimum_body_scale_ratio").double,
            maximumBodyScaleRatio = number("maximum_body_scale_ratio").double,
            minimumConfidence = number("minimum_confidence").double,
            maximumGapNs = number("maximum_gap_ns").long,
            minimumEndpointSamples = number("minimum_endpoint_samples").int,
            minimumReferenceSamples = number("minimum_reference_samples").int,
            maximumReferenceWristSpreadTorsoLengths = number("maximum_reference_wrist_spread_torso_lengths").double,
            hangLockoutElbowDegrees = number("hang_lockout_elbow_degrees").double,
        )
    }

    fun run(input: Path, config: Path, output: Path) {
        require(!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "output already exists" }
        require(output != input && output != config) { "output must differ from inputs" }
        val engine = MuscleUpCycleExperiment(parseConfig(Files.readString(config)))
        val frames = Files.newBufferedReader(input).use { ReplayPikePushUpPoseParser.parseFrames(it.lineSequence()) }
        Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            for (f in frames) {
                val r = engine.onFrame(MuscleUpPoseFrame(f.timestampNs, f.width, f.height, f.landmarks))
                val row = buildJsonObject {
                    put("timestamp_ns", r.timestampNs); put("count", r.count); put("counted", r.counted)
                    put("unresolved_excursions", r.unresolvedExcursions)
                    put("phase", r.phase.name); put("reason", r.reason.name)
                    put("shoulder_above_wrist_fraction", r.shoulderAboveWristFraction?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("body_scale_ratio", r.bodyScaleRatio?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("grip_drift_torso_lengths", r.gripDriftTorsoLengths?.let { JsonPrimitive(it) } ?: JsonNull)
                    val x = r.completedExcursion
                    put("excursion_outcome", x?.outcome?.name?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("excursion_left_hang_ns", x?.leftHangAtNs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("excursion_support_ns", x?.supportObservedAtNs?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                writer.write(row.toString()); writer.newLine()
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.startsWith("--") && '=' in it })
        val options = args.map { it.removePrefix("--").split('=', limit = 2) }.associate { it[0] to it[1] }
        require(options.keys == setOf("posePath", "muscleUpConfigPath", "muscleUpOutputPath"))
        run(
            Path.of(options.getValue("posePath")),
            Path.of(options.getValue("muscleUpConfigPath")),
            Path.of(options.getValue("muscleUpOutputPath")),
        )
    }
}

/** Top-level shim so a Gradle JavaExec task can resolve `ReplayMuscleUpPoseKt`. */
fun main(args: Array<String>) = ReplayMuscleUpPose.main(args)
