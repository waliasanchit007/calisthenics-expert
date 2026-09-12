package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/**
 * Offline only. Posture admission (PostureAdmission.kt) decides WHICH shape counts; the existing,
 * unchanged StaticSkillObservation decides stillness, entry, exit and censoring. Writes one row per
 * frame, then a final `{"intervals": [...]}` row.
 */
object ReplayPostureHoldPose {
    private val KEYS = setOf(
        "posture", "side", "minimum_confidence",
        "straight_min_degrees", "straight_max_degrees", "maximum_orientation_degrees_from_horizontal",
        "l_sit_hip_min_degrees", "l_sit_hip_max_degrees", "l_sit_knee_min_degrees",
        "l_sit_maximum_leg_degrees_from_horizontal", "maximum_trunk_degrees_from_vertical",
        "wall_sit_knee_min_degrees", "wall_sit_knee_max_degrees", "wall_sit_maximum_thigh_degrees_from_horizontal",
        "maximum_torso_point_speed_torso_lengths_per_second", "maximum_gap_ns", "minimum_still_ns",
        "minimum_moving_samples_to_close", "motion_baseline_ns",
    )

    fun parse(text: String): Pair<PostureAdmissionConfig, StaticSkillConfig> {
        val j = Json.parseToJsonElement(text).jsonObject
        require(j.keys == KEYS) { "config keys must be exactly $KEYS" }
        fun d(key: String): Double {
            val p = j.getValue(key).jsonPrimitive
            require(!p.isString) { "$key must be a number" }
            return p.double
        }
        val side = ArmSide.valueOf(j.getValue("side").jsonPrimitive.content)
        val admission = PostureAdmissionConfig(
            posture = HoldPosture.valueOf(j.getValue("posture").jsonPrimitive.content),
            side = side, minimumConfidence = d("minimum_confidence"),
            straightMinDegrees = d("straight_min_degrees"), straightMaxDegrees = d("straight_max_degrees"),
            maximumOrientationDegreesFromHorizontal = d("maximum_orientation_degrees_from_horizontal"),
            lSitHipMinDegrees = d("l_sit_hip_min_degrees"), lSitHipMaxDegrees = d("l_sit_hip_max_degrees"),
            lSitKneeMinDegrees = d("l_sit_knee_min_degrees"),
            lSitMaximumLegDegreesFromHorizontal = d("l_sit_maximum_leg_degrees_from_horizontal"),
            maximumTrunkDegreesFromVertical = d("maximum_trunk_degrees_from_vertical"),
            wallSitKneeMinDegrees = d("wall_sit_knee_min_degrees"), wallSitKneeMaxDegrees = d("wall_sit_knee_max_degrees"),
            wallSitMaximumThighDegreesFromHorizontal = d("wall_sit_maximum_thigh_degrees_from_horizontal"),
        )
        val still = StaticSkillConfig(
            minimumConfidence = d("minimum_confidence"),
            maximumTorsoPointSpeedTorsoLengthsPerSecond = d("maximum_torso_point_speed_torso_lengths_per_second"),
            maximumGapNs = j.getValue("maximum_gap_ns").jsonPrimitive.long,
            minimumStillNs = j.getValue("minimum_still_ns").jsonPrimitive.long,
            minimumMovingSamplesToClose = j.getValue("minimum_moving_samples_to_close").jsonPrimitive.int,
            motionBaselineNs = j.getValue("motion_baseline_ns").jsonPrimitive.long,
            mode = StaticSkillMode.TORSO_STILL, side = side,
        )
        return admission to still
    }

    private fun StaticSkillInterval.toJson() = buildJsonObject {
        put("entry_ns", entryTimestampNs); put("exit_ns", exitTimestampNs); put("duration_ns", durationNs)
        put("observed_ns", observedNs); put("end_reason", endReason.name); put("left_censored", leftCensored)
    }

    fun run(input: Path, config: Path, output: Path) {
        require(!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "output already exists" }
        require(output != input && output != config) { "output must differ from inputs" }
        val (admissionConfig, stillConfig) = parse(Files.readString(config))
        val engine = StaticSkillObservation(stillConfig)
        val intervals = mutableListOf<StaticSkillInterval>()
        val frames = Files.newBufferedReader(input).use { ReplayPikePushUpPoseParser.parseFrames(it.lineSequence()) }
        Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            for (f in frames) {
                val frame = StaticSkillPoseFrame(f.timestampNs, f.width, f.height, f.landmarks)
                val admission = admitPosture(frame, admissionConfig)
                val r = engine.onFrame(frame, admission.admitted)
                r.closedInterval?.let { intervals += it }
                val row = buildJsonObject {
                    put("timestamp_ns", r.timestampNs); put("admitted", admission.admitted)
                    put("posture_reason", admission.reason.name); put("state", r.state.name); put("reason", r.reason.name)
                    put("hip_degrees", admission.hipDegrees?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("knee_degrees", admission.kneeDegrees?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                writer.write(row.toString()); writer.newLine()
            }
            engine.finish()?.let { intervals += it }
            writer.write(buildJsonObject { put("intervals", JsonArray(intervals.map { it.toJson() })) }.toString())
            writer.newLine()
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.startsWith("--") && '=' in it })
        val o = args.map { it.removePrefix("--").split('=', limit = 2) }.associate { it[0] to it[1] }
        require(o.keys == setOf("posePath", "postureHoldConfigPath", "postureHoldOutputPath"))
        run(Path.of(o.getValue("posePath")), Path.of(o.getValue("postureHoldConfigPath")), Path.of(o.getValue("postureHoldOutputPath")))
    }
}

/** Top-level shim so a Gradle JavaExec task can resolve `ReplayPostureHoldPoseKt`. */
fun main(args: Array<String>) = ReplayPostureHoldPose.main(args)
