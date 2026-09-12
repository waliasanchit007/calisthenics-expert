package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReplayPikePushUpPoseTest {
    private val configJson = """
        {
          "side":"LEFT",
          "extended_elbow_degrees":155.0,
          "flexed_elbow_degrees":100.0,
          "minimum_confidence":0.5,
          "maximum_gap_ns":250000000,
          "minimum_endpoint_samples":2,
          "maximum_hip_angle_degrees":130.0,
          "minimum_knee_angle_degrees":150.0,
          "minimum_hip_above_shoulder_fraction":0.03
        }
    """.trimIndent()

    @Test
    fun parsesTheFrozenDevelopmentalConfigStrictly() {
        val config = ReplayPikePushUpPoseParser.parseConfig(configJson)

        assertEquals(ArmSide.LEFT, config.side)
        assertEquals(155.0, config.extendedElbowDegrees, 1e-9)
        assertEquals(100.0, config.flexedElbowDegrees, 1e-9)
        assertEquals(250_000_000L, config.maximumGapNs)
        assertEquals(130.0, config.maximumHipAngleDegrees, 1e-9)
        assertEquals(150.0, config.minimumKneeAngleDegrees, 1e-9)
        assertEquals(0.03, config.minimumHipAboveShoulderFraction, 1e-9)

        assertFailsWith<ReplayPikePushUpPoseFormatException> {
            ReplayPikePushUpPoseParser.parseConfig(configJson.replace("\"side\":\"LEFT\"", "\"side\":\"BOTH\""))
        }
        assertFailsWith<ReplayPikePushUpPoseFormatException> {
            ReplayPikePushUpPoseParser.parseConfig(configJson.dropLast(1) + ",\"extra\":true}")
        }
    }

    @Test
    fun actualJvmMainEntrypointReadsRowsAndWritesOneOutputRowPerInputFrame() {
        val root = Files.createTempDirectory("pike-push-up-replay")
        val posePath = root.resolve("poses.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, replayRows().joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplayPikePushUpPose.main(
            arrayOf(
                "--posePath=$posePath",
                "--pikePushUpConfigPath=$configPath",
                "--pikePushUpOutputPath=$outputPath",
            ),
        )

        val rows = Files.readAllLines(outputPath).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(8, rows.size)
        assertTrue(rows.all { it.keys == expectedKeys })
        assertEquals("1", rows.last().getValue("count").jsonPrimitive.content)
        assertEquals("true", rows.last().getValue("counted").jsonPrimitive.content)
    }

    @Test
    fun objectMainIsAJavaLaunchableStaticEntrypoint() {
        val method = ReplayPikePushUpPose::class.java.getMethod("main", Array<String>::class.java)

        assertTrue(Modifier.isStatic(method.modifiers))
    }

    @Test
    fun parserRejectsNonIncreasingRowsButLeavesRequiredJointAdmissionToGeometry() {
        val row = replayRows().first()
        assertFailsWith<ReplayPikePushUpPoseFormatException> {
            ReplayPikePushUpPoseParser.parseFrames(sequenceOf(row, row))
        }
        assertEquals(1, ReplayPikePushUpPoseParser.parseFrames(
            sequenceOf(row.replace("\"index\":27", "\"index\":28")),
        ).size)
    }

    private val expectedKeys = setOf(
        "timestamp_ns", "elbow_degrees", "hip_angle_degrees", "knee_angle_degrees",
        "hip_above_shoulder_fraction", "phase", "count", "reason", "counted", "event_timestamp_ns",
    )

    private fun replayRows(): List<String> {
        val angles = listOf(160.0, 160.0, 130.0, 90.0, 90.0, 130.0, 160.0, 160.0)
        return angles.mapIndexed { index, angle ->
            val shoulder = .35 to .55
            val elbow = .35 to .70
            val limb = .15
            val radians = angle * Math.PI / 180.0
            val wrist = (elbow.first + limb * kotlin.math.sin(radians)) to
                (elbow.second - limb * kotlin.math.cos(radians))
            val points = listOf(
                11 to shoulder, 13 to elbow, 15 to wrist,
                23 to (.55 to .25), 25 to (.65 to .55), 27 to (.75 to .75),
            )
            val landmarks = points.joinToString(",") { (joint, point) ->
                """{"index":$joint,"x":${point.first},"y":${point.second},"z":0.0,"visibility":0.9,"presence":0.9}"""
            }
            """{"timestamp_ns":${index * 100_000_000L},"width":500,"height":500,"landmarks":[$landmarks]}"""
        }
    }
}
