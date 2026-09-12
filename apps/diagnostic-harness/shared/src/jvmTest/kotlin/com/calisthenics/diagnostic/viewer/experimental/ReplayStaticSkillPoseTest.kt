package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Replay harness over an existing pose cache. It composes the already-existing
 * [measurePlankBodyLine] admission (straight, near-horizontal body line, rules out upright
 * standing) with the new [StaticSkillObservation] stationarity measurement, so no geometry is
 * duplicated. Fixtures are synthetic and self-contained: the suite never reads out-of-Git media.
 */
class ReplayStaticSkillPoseTest {
    private val configJson = """
        {
          "side":"LEFT",
          "straight_min_degrees":160.0,
          "straight_max_degrees":180.0,
          "minimum_confidence":0.5,
          "maximum_orientation_degrees_from_horizontal":30.0,
          "maximum_torso_point_speed_torso_lengths_per_second":0.3,
          "maximum_gap_ns":100000000,
          "minimum_still_ns":300000000,
          "mode":"TORSO_STILL"
        }
    """.trimIndent()

    @Test
    fun parsesConfigAndRejectsUnknownOrMissingKeys() {
        val config = ReplayStaticSkillPoseParser.parseConfig(configJson)
        assertEquals(ArmSide.LEFT, config.side)
        assertEquals(StaticSkillMode.TORSO_STILL, config.mode)
        assertEquals(300_000_000L, config.minimumStillNs)

        assertFailsWith<ReplayStaticSkillPoseFormatException> {
            ReplayStaticSkillPoseParser.parseConfig(
                configJson.replace("\"mode\":\"TORSO_STILL\"", "\"mode\":\"BACK_LEVER\""),
            )
        }
        assertFailsWith<ReplayStaticSkillPoseFormatException> {
            ReplayStaticSkillPoseParser.parseConfig(configJson.dropLast(1) + ",\"extra\":1}")
        }
        assertFailsWith<ReplayStaticSkillPoseFormatException> {
            ReplayStaticSkillPoseParser.parseConfig(
                configJson.replace("\"minimum_still_ns\":300000000,", ""),
            )
        }
    }

    @Test
    fun rejectsMalformedFramesAndNonIncreasingTimestamps() {
        val frames = ReplayStaticSkillPoseParser.parseFrames(
            sequenceOf(frame(0L), frame(FRAME_NS)),
        )
        assertEquals(2, frames.size)
        assertEquals(FRAME_NS, frames[1].timestampNs)

        assertFailsWith<ReplayStaticSkillPoseFormatException> {
            ReplayStaticSkillPoseParser.parseFrames(sequenceOf(frame(FRAME_NS), frame(0L)))
        }
        assertFailsWith<ReplayStaticSkillPoseFormatException> {
            ReplayStaticSkillPoseParser.parseFrames(sequenceOf(frame(0L), frame(0L)))
        }
        assertFailsWith<ReplayStaticSkillPoseFormatException> {
            ReplayStaticSkillPoseParser.parseFrames(sequenceOf(""))
        }
    }

    @Test
    fun aSustainedStillAdmittedRunWritesOneRowPerFrameAndExactlyOneInterval() {
        val root = Files.createTempDirectory("static-skill-replay")
        val pose = root.resolve("poses.jsonl")
        val config = root.resolve("config.json")
        val output = root.resolve("out.jsonl")
        Files.writeString(pose, (0 until 20).joinToString("\n") { stillFrame(it) } + "\n")
        Files.writeString(config, configJson)

        ReplayStaticSkillPose.run(pose, config, output)

        val rows = Files.readAllLines(output)
        assertEquals(20, rows.size)

        val intervals = rows.map { Json.parseToJsonElement(it).jsonObject.getValue("closed_interval") }
            .filter { it != JsonNull }
        assertEquals(1, intervals.size)
        val interval = intervals.single().jsonObject
        assertEquals(0L, interval.getValue("entry_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(19 * FRAME_NS, interval.getValue("exit_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(
            StaticSkillReason.CENSORED_AT_END_OF_INPUT.name,
            interval.getValue("end_reason").jsonPrimitive.content,
        )
        // Already mid-hold at the first frame, so the onset is unknown and duration is a bound.
        assertEquals(true, interval.getValue("left_censored").jsonPrimitive.booleanOrNull)
        assertEquals(
            StaticSkillLabel.TORSO_STILL.name,
            interval.getValue("label").jsonPrimitive.content,
        )

        val last = Json.parseToJsonElement(rows.last()).jsonObject
        assertEquals(StaticSkillState.STILL.name, last.getValue("state").jsonPrimitive.content)
        assertEquals(true, last.getValue("body_line_admitted").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun anUprightStandingRunIsNeverAdmittedAndProducesNoInterval() {
        // Standing is perfectly still and geometrically straight. Only the body-line orientation
        // gate separates it from a horizontal hold, so this is the decisive negative case.
        val root = Files.createTempDirectory("static-skill-replay-standing")
        val pose = root.resolve("poses.jsonl")
        val config = root.resolve("config.json")
        val output = root.resolve("out.jsonl")
        Files.writeString(pose, (0 until 20).joinToString("\n") { standingFrame(it) } + "\n")
        Files.writeString(config, configJson)

        ReplayStaticSkillPose.run(pose, config, output)

        val rows = Files.readAllLines(output).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(20, rows.size)
        assertTrue(rows.all { it.getValue("closed_interval") == JsonNull })
        assertTrue(rows.all { it.getValue("body_line_admitted").jsonPrimitive.booleanOrNull == false })
        assertTrue(
            rows.drop(1).all {
                it.getValue("reason").jsonPrimitive.content ==
                    StaticSkillReason.BODY_LINE_NOT_ADMITTED.name
            },
        )
    }

    @Test
    fun aHorizontalButMovingRunProducesNoInterval() {
        // Straight and near-horizontal but travelling: this is what the existing plank gates alone
        // would admit, and precisely what the stationarity measurement exists to reject.
        val root = Files.createTempDirectory("static-skill-replay-moving")
        val pose = root.resolve("poses.jsonl")
        val config = root.resolve("config.json")
        val output = root.resolve("out.jsonl")
        Files.writeString(pose, (0 until 20).joinToString("\n") { movingFrame(it) } + "\n")
        Files.writeString(config, configJson)

        ReplayStaticSkillPose.run(pose, config, output)

        val rows = Files.readAllLines(output).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(20, rows.size)
        assertTrue(rows.all { it.getValue("closed_interval") == JsonNull })
        assertTrue(rows.all { it.getValue("body_line_admitted").jsonPrimitive.booleanOrNull == true })
        assertTrue(
            rows.drop(1).all {
                it.getValue("reason").jsonPrimitive.content == StaticSkillReason.MOVING.name
            },
        )
    }

    @Test
    fun unselectedModeReplayNeverWritesASkillIdentity() {
        val root = Files.createTempDirectory("static-skill-replay-unselected")
        val pose = root.resolve("poses.jsonl")
        val config = root.resolve("config.json")
        val output = root.resolve("out.jsonl")
        Files.writeString(pose, (0 until 20).joinToString("\n") { stillFrame(it) } + "\n")
        Files.writeString(
            config,
            configJson.replace("\"TORSO_STILL\"", "\"UNSELECTED\""),
        )

        ReplayStaticSkillPose.run(pose, config, output)

        val interval = Files.readAllLines(output)
            .map { Json.parseToJsonElement(it).jsonObject.getValue("closed_interval") }
            .single { it != JsonNull }
            .jsonObject
        assertEquals(
            StaticSkillLabel.UNCERTAIN.name,
            interval.getValue("label").jsonPrimitive.content,
        )
    }

    @Test
    fun aGapBeyondToleranceIsReportedAsCaptureGapNotStitched() {
        val root = Files.createTempDirectory("static-skill-replay-gap")
        val pose = root.resolve("poses.jsonl")
        val config = root.resolve("config.json")
        val output = root.resolve("out.jsonl")
        val lines = (0 until 20).map { stillFrame(it) } +
            listOf(stillFrameAt(19 * FRAME_NS + 200_000_000L, 19))
        Files.writeString(pose, lines.joinToString("\n") + "\n")
        Files.writeString(config, configJson)

        ReplayStaticSkillPose.run(pose, config, output)

        val interval = Files.readAllLines(output)
            .map { Json.parseToJsonElement(it).jsonObject.getValue("closed_interval") }
            .single { it != JsonNull }
            .jsonObject
        assertEquals(
            StaticSkillReason.CAPTURE_GAP.name,
            interval.getValue("end_reason").jsonPrimitive.content,
        )
        assertEquals(19 * FRAME_NS, interval.getValue("exit_timestamp_ns").jsonPrimitive.longOrNull)
    }

    @Test
    fun existingOutputIsNeverOverwritten() {
        val root = Files.createTempDirectory("static-skill-replay-existing")
        val pose = root.resolve("poses.jsonl")
        val config = root.resolve("config.json")
        val output = root.resolve("out.jsonl")
        Files.writeString(pose, stillFrame(0) + "\n")
        Files.writeString(config, configJson)
        Files.writeString(output, "existing\n")

        assertFailsWith<IllegalArgumentException> { ReplayStaticSkillPose.run(pose, config, output) }
        assertEquals(listOf("existing"), Files.readAllLines(output))
    }

    @Test
    fun emptyPoseInputFailsWithoutCreatingOutput() {
        val root = Files.createTempDirectory("static-skill-replay-empty")
        val pose = root.resolve("poses.jsonl")
        val config = root.resolve("config.json")
        val output = root.resolve("out.jsonl")
        Files.writeString(pose, "")
        Files.writeString(config, configJson)

        assertFailsWith<IllegalArgumentException> { ReplayStaticSkillPose.run(pose, config, output) }
        assertTrue(!Files.exists(output))
    }

    @Test
    fun strictCommandLineArgumentsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { ReplayStaticSkillPose.main(arrayOf("--posePath=/a")) }
        assertFailsWith<IllegalArgumentException> {
            ReplayStaticSkillPose.main(
                arrayOf("--posePath=/a", "--staticSkillConfigPath=/b", "--unknown=/c"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayStaticSkillPose.main(arrayOf("posePath=/a", "config=/b", "out=/c"))
        }
    }

    private fun stillFrame(index: Int) = stillFrameAt(index * FRAME_NS, index)

    /** 0.5px of drift per frame on a 100px torso: 0.15 torso-lengths/s, inside the 0.3 gate. */
    private fun stillFrameAt(timestampNs: Long, index: Int) =
        horizontalFrame(timestampNs, offsetPx = .5 * index)

    /** 5px per frame: 1.5 torso-lengths/s, five times the gate. */
    private fun movingFrame(index: Int) =
        horizontalFrame(index * FRAME_NS, offsetPx = 5.0 * index)

    private fun frame(timestampNs: Long) = horizontalFrame(timestampNs, offsetPx = 0.0)

    /**
     * Shoulder(11)/hip(23)/ankle(27) collinear and horizontal, so the existing body-line
     * measurement reads 180 degrees at 0 degrees from horizontal. Landmarks 12/24 give the
     * four-point torso centroid at (350 + offset, 190) with a 100px shoulder-mid-to-hip-mid scale.
     */
    private fun horizontalFrame(timestampNs: Long, offsetPx: Double): String = jsonl(
        timestampNs,
        listOf(
            Triple(11, 300.0 + offsetPx, 180.0),
            Triple(12, 300.0 + offsetPx, 200.0),
            Triple(23, 400.0 + offsetPx, 180.0),
            Triple(24, 400.0 + offsetPx, 200.0),
            Triple(27, 500.0 + offsetPx, 180.0),
        ),
    )

    /** Shoulder/hip/ankle vertical: geometrically straight, perfectly still, and not a hold. */
    private fun standingFrame(index: Int): String = jsonl(
        index * FRAME_NS,
        listOf(
            Triple(11, 350.0 + .5 * index, 100.0),
            Triple(12, 370.0 + .5 * index, 100.0),
            Triple(23, 350.0 + .5 * index, 200.0),
            Triple(24, 370.0 + .5 * index, 200.0),
            Triple(27, 350.0 + .5 * index, 300.0),
        ),
    )

    private fun jsonl(timestampNs: Long, points: List<Triple<Int, Double, Double>>): String {
        val landmarks = points.joinToString(",") { (index, xPx, yPx) ->
            """{"index":$index,"x":${xPx / WIDTH},"y":${yPx / HEIGHT},"z":0.0,""" +
                """"visibility":0.9,"presence":0.9}"""
        }
        return """{"timestamp_ns":$timestampNs,"width":$WIDTH,"height":$HEIGHT,""" +
            """"landmarks":[$landmarks]}"""
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val FRAME_NS = 33_333_333L
    }
}
