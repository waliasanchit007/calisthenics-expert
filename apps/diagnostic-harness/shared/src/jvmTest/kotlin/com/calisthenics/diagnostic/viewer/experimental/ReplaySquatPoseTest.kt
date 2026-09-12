package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class ReplaySquatPoseTest {
    @Test
    fun motionWitnessProfileMustBeExplicitAndReachTheActualCounter() {
        val practice = listOf(175.0,175.0,150.0,125.0,125.0,150.0,175.0,175.0)
        val left = practice + practice
        val right = practice + listOf(175.0,175.0,160.0,145.0,145.0,160.0,175.0,175.0)
        val root = Files.createTempDirectory("squat-motion-profile")
        Files.writeString(root.resolve("pose.jsonl"), left.mapIndexed { i, angle ->
            bilateralFrame(i*100L, angle, right[i])
        }.joinToString("\n"))
        Files.writeString(root.resolve("config.json"), configJson.dropLast(1) + """,
            "practice_window":{"start_ns":0,"end_ns":700,"minimum_range_degrees":20,
            "endpoint_fraction":0.2,"opposite_minimum_excursion_degrees":20}}""")
        ReplaySquatPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))
        val rows = Files.readAllLines(root.resolve("output.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("1", rows.last()["count"]!!.jsonPrimitive.content)
        assertEquals("supervised_per_side_motion_witness_v1", rows.first()["calibration"]!!.jsonObject["profile"]!!.jsonPrimitive.content)
    }

    @Test
    fun practiceIsExcludedAndFutureFramesNeverRefitItsRange() {
        val angles = listOf(175.0, 175.0, 150.0, 125.0, 125.0, 150.0, 175.0, 175.0)
        val config = """{"side":"LEFT","extended_degrees":150,"flexed_degrees":110,
            "minimum_confidence":0.5,"maximum_gap_ns":250,"minimum_endpoint_samples":2,
            "practice_window":{"start_ns":0,"end_ns":700,"minimum_range_degrees":20,"endpoint_fraction":0.2}}"""
        fun replay(values: List<Double>, evaluationGap: Long = 0): List<kotlinx.serialization.json.JsonObject> {
            val root = Files.createTempDirectory("squat-practice-replay")
            val pose = root.resolve("poses.jsonl")
            Files.writeString(pose, values.mapIndexed { i, angle ->
                bilateralFrame(i * 100L + if (i >= 8) evaluationGap else 0, angle)
            }.joinToString("\n"))
            Files.writeString(root.resolve("config.json"), config)
            ReplaySquatPose.run(pose, root.resolve("config.json"), root.resolve("output.jsonl"))
            return Files.readAllLines(root.resolve("output.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        }
        val result = replay(angles + angles)
        assertEquals(16, result.size)
        assertTrue(result.take(8).all { it["count"]!!.jsonPrimitive.content == "0" })
        assertTrue(result.take(8).all { it["reason"]!!.jsonPrimitive.content == "CALIBRATION_PRACTICE" })
        assertEquals("1", result.last()["count"]!!.jsonPrimitive.content)
        assertEquals("false", result[7]["evaluation"]!!.jsonPrimitive.content)
        assertEquals("true", result[8]["evaluation"]!!.jsonPrimitive.content)
        assertEquals(result.first()["calibration"], replay(angles + listOf(30.0, 30.0, 175.0, 175.0)).first()["calibration"])
        assertTrue(result.first()["calibration"]!!.jsonObject.containsKey("opposite_flexed_degrees"))
        // Calibration already ended in a verified standing run. Losing that endpoint
        // misses the next descent; only that endpoint, never practice's rep, may carry over.
        assertEquals("1", replay(angles + angles.drop(2)).last()["count"]!!.jsonPrimitive.content)
        assertEquals("0", replay(angles + angles.drop(2), evaluationGap = 1000).last()["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidPracticeAbortsWithoutPredictionArtifactOrFallbackCounts() {
        for (window in listOf(
            """{"start_ns":0,"end_ns":700,"minimum_range_degrees":20,"endpoint_fraction":0.2}""",
            """{"start_ns":1000,"end_ns":1700,"minimum_range_degrees":20,"endpoint_fraction":0.2}""",
            """{"start_ns":700,"end_ns":0,"minimum_range_degrees":20,"endpoint_fraction":0.2}""",
            """{"start_ns":0,"end_ns":700,"minimum_range_degrees":20,"endpoint_fraction":0.2,"guess":true}""",
        )) {
            val root = Files.createTempDirectory("squat-invalid-practice")
            Files.writeString(root.resolve("pose.jsonl"), (0..10).joinToString("\n") { bilateralFrame(it * 100L, 175.0) })
            Files.writeString(root.resolve("config.json"), configJson.dropLast(1) + ",\"practice_window\":$window}")
            val error = assertFailsWith<IllegalArgumentException> {
                ReplaySquatPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))
            }
            assertTrue(error.message.orEmpty().contains("practice"), error.message)
            assertEquals(false, Files.exists(root.resolve("output.jsonl")))
        }
    }

    private fun bilateralFrame(timestamp: Long, angle: Double, right: Double = 175.0 - (175.0 - angle) / 2): String {
        val landmarks = listOf(23 to angle, 24 to right).flatMap { (hip, degrees) ->
            val radians = degrees * kotlin.math.PI / 180
            listOf(Triple(hip, .5, .25), Triple(hip + 2, .5, .5),
                Triple(hip + 4, .5 + .25 * kotlin.math.sin(radians), .5 - .25 * kotlin.math.cos(radians)))
        }.joinToString(",") { (index, x, y) ->
            """{"index":$index,"x":$x,"y":$y,"z":0,"visibility":0.95,"presence":0.95}"""
        }
        return """{"timestamp_ns":$timestamp,"width":100,"height":100,"landmarks":[$landmarks]}"""
    }

    private val configJson = """
        {
          "side":"LEFT",
          "extended_degrees":160.0,
          "flexed_degrees":80.0,
          "minimum_confidence":0.8,
          "maximum_gap_ns":500,
          "minimum_endpoint_samples":2
        }
    """.trimIndent()

    @Test
    fun parsesSourceNormalizedFramesAndPreservesEmptyNoPose() {
        val frames = ReplaySquatPoseParser.parseFrames(sequenceOf(frame(0), frame(100, empty = true)))

        assertEquals(2, frames.size)
        assertEquals(23, frames[0].landmarks.first().index)
        assertTrue(frames[1].landmarks.isEmpty())
    }

    @Test
    fun rejectsMissingFieldsAndNonIncreasingTimestamps() {
        assertFailsWith<ReplaySquatPoseFormatException> {
            ReplaySquatPoseParser.parseFrames(sequenceOf(frame(0).replace("\"landmarks\"", "\"missing\"")))
        }
        assertFailsWith<ReplaySquatPoseFormatException> {
            ReplaySquatPoseParser.parseFrames(sequenceOf(frame(100), frame(100)))
        }
        assertFailsWith<ReplaySquatPoseFormatException> {
            ReplaySquatPoseParser.parseFrames(sequenceOf(frame(100), frame(99)))
        }
        assertFailsWith<ReplaySquatPoseFormatException> {
            ReplaySquatPoseParser.parseFrames(sequenceOf(frame(-1)))
        }
        assertFailsWith<ReplaySquatPoseFormatException> {
            ReplaySquatPoseParser.parseFrames(sequenceOf(frame(0).replace("0.95", "NaN")))
        }
        assertFailsWith<ReplaySquatPoseFormatException> {
            ReplaySquatPoseParser.parseFrames(sequenceOf(frame(0).replace("\"timestamp_ns\":0", "\"timestamp_ns\":\"0\"")))
        }
        assertFailsWith<ReplaySquatPoseFormatException> {
            ReplaySquatPoseParser.parseConfig(configJson.replace("\"extended_degrees\":160.0", "\"extended_degrees\":\"160.0\""))
        }
    }

    @Test
    fun replayWritesOneOutputRowPerFrameWithNullableMeasurementAndCount() {
        val root = Files.createTempDirectory("squat-replay-test")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, listOf(frame(0), frame(100, empty = true)).joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplaySquatPose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(2, rows.size)
        val output = Json.parseToJsonElement(rows[0]).jsonObject
        assertEquals(0L, output.getValue("timestamp_ns").jsonPrimitive.longOrNull)
        assertTrue(output.containsKey("knee_degrees"))
        assertTrue(output.containsKey("phase"))
        assertTrue(output.containsKey("count"))
        assertTrue(output.containsKey("reason"))
        assertTrue(output.containsKey("counted"))
        assertEquals(JsonNull, Json.parseToJsonElement(rows[1]).jsonObject["knee_degrees"])
    }

    @Test
    fun strictConfigAndPathArgumentsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { ReplaySquatPoseParser.parseConfig("{}") }
        assertFailsWith<IllegalArgumentException> {
            ReplaySquatPose.main(arrayOf("--posePath=/no/such/file", "--squatConfigPath=/no/config", "--squatOutputPath=/no/output"))
        }
    }

    @Test
    fun existingOutputIsNeverOverwritten() {
        val root = Files.createTempDirectory("squat-replay-existing-output")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, frame(0) + "\n")
        Files.writeString(configPath, configJson)
        Files.writeString(outputPath, "sentinel\n")

        assertFailsWith<IllegalArgumentException> { ReplaySquatPose.run(posePath, configPath, outputPath) }
        assertEquals("sentinel\n", Files.readString(outputPath))
    }

    @Test
    fun emptyPoseInputFailsWithoutCreatingOutput() {
        val root = Files.createTempDirectory("squat-replay-empty-input")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, "")
        Files.writeString(configPath, configJson)

        assertFailsWith<IllegalArgumentException> { ReplaySquatPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    private fun frame(timestampNs: Long, empty: Boolean = false) = """
        {
          "timestamp_ns":$timestampNs,
          "width":400,
          "height":200,
          "landmarks":${if (empty) "[]" else """[
            {"index":23,"x":0.5,"y":0.25,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":25,"x":0.5,"y":0.5,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":27,"x":0.52,"y":0.75,"z":0.0,"visibility":0.95,"presence":0.95}
          ]"""}
        }
    """.trimIndent().lines().joinToString("")
}
