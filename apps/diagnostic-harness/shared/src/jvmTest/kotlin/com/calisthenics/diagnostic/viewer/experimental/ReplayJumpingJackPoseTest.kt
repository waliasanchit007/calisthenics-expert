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

class ReplayJumpingJackPoseTest {
    private val configJson = """
        {
          "arms_up_degrees":150.0,
          "arms_down_degrees":30.0,
          "legs_spread_ratio":2.5,
          "legs_together_ratio":1.0,
          "minimum_confidence":0.8,
          "maximum_gap_ns":500,
          "minimum_endpoint_samples":2
        }
    """.trimIndent()

    @Test
    fun parsesSourceNormalizedFramesAndPreservesEmptyNoPose() {
        val frames = ReplayJumpingJackPoseParser.parseFrames(sequenceOf(frame(0), frame(100, empty = true)))

        assertEquals(2, frames.size)
        assertEquals(11, frames[0].landmarks.first().index)
        assertTrue(frames[1].landmarks.isEmpty())
    }

    @Test
    fun rejectsMissingFieldsAndNonIncreasingTimestamps() {
        assertFailsWith<ReplayJumpingJackPoseFormatException> {
            ReplayJumpingJackPoseParser.parseFrames(sequenceOf(frame(0).replace("\"landmarks\"", "\"missing\"")))
        }
        assertFailsWith<ReplayJumpingJackPoseFormatException> {
            ReplayJumpingJackPoseParser.parseFrames(sequenceOf(frame(100), frame(100)))
        }
        assertFailsWith<ReplayJumpingJackPoseFormatException> {
            ReplayJumpingJackPoseParser.parseFrames(sequenceOf(frame(100), frame(99)))
        }
        assertFailsWith<ReplayJumpingJackPoseFormatException> {
            ReplayJumpingJackPoseParser.parseFrames(sequenceOf(frame(-1)))
        }
        assertFailsWith<ReplayJumpingJackPoseFormatException> {
            ReplayJumpingJackPoseParser.parseFrames(sequenceOf(frame(0).replace("0.95", "NaN")))
        }
        assertFailsWith<ReplayJumpingJackPoseFormatException> {
            ReplayJumpingJackPoseParser.parseFrames(
                sequenceOf(frame(0).replace("\"timestamp_ns\":0", "\"timestamp_ns\":\"0\"")),
            )
        }
        assertFailsWith<ReplayJumpingJackPoseFormatException> {
            ReplayJumpingJackPoseParser.parseConfig(
                configJson.replace("\"arms_up_degrees\":150.0", "\"arms_up_degrees\":\"150.0\""),
            )
        }
    }

    @Test
    fun replayWritesOneOutputRowPerFrameWithNullableMeasurementAndCount() {
        val root = Files.createTempDirectory("jumping-jack-replay-test")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, listOf(frame(0), frame(100, empty = true)).joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplayJumpingJackPose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(2, rows.size)
        val output = Json.parseToJsonElement(rows[0]).jsonObject
        assertEquals(0L, output.getValue("timestamp_ns").jsonPrimitive.longOrNull)
        assertTrue(output.containsKey("arm_degrees"))
        assertTrue(output.containsKey("leg_spread_ratio"))
        assertTrue(output.containsKey("phase"))
        assertTrue(output.containsKey("count"))
        assertTrue(output.containsKey("reason"))
        assertTrue(output.containsKey("counted"))
        assertEquals(JsonNull, Json.parseToJsonElement(rows[1]).jsonObject["arm_degrees"])
        assertEquals(JsonNull, Json.parseToJsonElement(rows[1]).jsonObject["leg_spread_ratio"])
    }

    @Test
    fun strictConfigAndPathArgumentsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { ReplayJumpingJackPoseParser.parseConfig("{}") }
        assertFailsWith<IllegalArgumentException> {
            ReplayJumpingJackPose.main(
                arrayOf(
                    "--posePath=/no/such/file",
                    "--jumpingJackConfigPath=/no/config",
                    "--jumpingJackOutputPath=/no/output",
                ),
            )
        }
    }

    @Test
    fun existingOutputIsNeverOverwritten() {
        val root = Files.createTempDirectory("jumping-jack-replay-existing-output")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, frame(0) + "\n")
        Files.writeString(configPath, configJson)
        Files.writeString(outputPath, "sentinel\n")

        assertFailsWith<IllegalArgumentException> { ReplayJumpingJackPose.run(posePath, configPath, outputPath) }
        assertEquals("sentinel\n", Files.readString(outputPath))
    }

    @Test
    fun practiceReplayLearnsArmsDownAndEmitsCalibrationMetadataOnTheFirstRow() {
        val thetas = listOf(20.0, 20.0, 70.0, 120.0, 120.0, 70.0, 20.0, 20.0)
        val legs = listOf(.2, .2, .8, 1.5, 1.5, .8, .2, .2)
        val root = Files.createTempDirectory("jumping-jack-practice-replay")
        Files.writeString(
            root.resolve("pose.jsonl"),
            thetas.indices.joinToString("\n") { i -> practiceFrame(i * 100L, thetas[i], legs[i]) } + "\n" +
                practiceFrame(800, 120.0, 1.5),
        )
        Files.writeString(root.resolve("config.json"), practiceConfigJson)

        ReplayJumpingJackPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))

        val rows = Files.readAllLines(root.resolve("output.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        val calibration = rows.first()["calibration"]!!.jsonObject
        assertEquals("supervised_arms_down_projected_range_v1", calibration["profile"]!!.jsonPrimitive.content)
        assertEquals(35.0, calibration["arms_down_degrees"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(100.0, calibration["arms_up_degrees"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(rows.take(8).all { it["reason"]!!.jsonPrimitive.content == "CALIBRATION_PRACTICE" })
        assertTrue(rows.take(8).all { it["count"]!!.jsonPrimitive.content == "0" })
        assertEquals("false", rows[7]["evaluation"]!!.jsonPrimitive.content)
        assertEquals("true", rows[8]["evaluation"]!!.jsonPrimitive.content)
    }

    @Test
    fun practiceIsExcludedAndFutureFramesNeverRefitTheLearnedBand() {
        val thetas = listOf(20.0, 20.0, 70.0, 120.0, 120.0, 70.0, 20.0, 20.0)
        val legs = listOf(.2, .2, .8, 1.5, 1.5, .8, .2, .2)
        fun replay(evaluationThetas: List<Double>, evaluationLegs: List<Double>): List<kotlinx.serialization.json.JsonObject> {
            val root = Files.createTempDirectory("jumping-jack-practice-refit")
            val lines = (thetas.indices.map { i -> practiceFrame(i * 100L, thetas[i], legs[i]) }) +
                evaluationThetas.indices.map { i ->
                    practiceFrame((8 + i) * 100L, evaluationThetas[i], evaluationLegs[i])
                }
            Files.writeString(root.resolve("pose.jsonl"), lines.joinToString("\n"))
            Files.writeString(root.resolve("config.json"), practiceConfigJson)
            ReplayJumpingJackPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))
            return Files.readAllLines(root.resolve("output.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        }
        val result = replay(thetas, legs)
        assertEquals(16, result.size)
        assertEquals("1", result.last()["count"]!!.jsonPrimitive.content)
        // Evaluation frames that would blow past the old range, if they could refit the band,
        // must still produce the exact same recorded calibration on the first row.
        assertEquals(
            result.first()["calibration"],
            replay(listOf(170.0, 170.0, 5.0, 5.0, 5.0, 5.0, 170.0, 170.0), legs).first()["calibration"],
        )
    }

    @Test
    fun invalidPracticeAbortsWithoutPredictionArtifactOrFallbackCounts() {
        for (window in listOf(
            """{"start_ns":0,"end_ns":700,"minimum_range_degrees":20,"endpoint_fraction":0.15}""",
            """{"start_ns":1000,"end_ns":1700,"minimum_range_degrees":20,"endpoint_fraction":0.15}""",
            """{"start_ns":700,"end_ns":0,"minimum_range_degrees":20,"endpoint_fraction":0.15}""",
            """{"start_ns":0,"end_ns":700,"minimum_range_degrees":20,"endpoint_fraction":0.15,"guess":true}""",
        )) {
            val root = Files.createTempDirectory("jumping-jack-invalid-practice")
            Files.writeString(
                root.resolve("pose.jsonl"),
                (0..10).joinToString("\n") { practiceFrame(it * 100L, 20.0, .2) },
            )
            Files.writeString(
                root.resolve("config.json"),
                baseConfigJson.trimEnd().removeSuffix("}") + ",\"practice_window\":$window}",
            )
            val error = assertFailsWith<IllegalArgumentException> {
                ReplayJumpingJackPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))
            }
            assertTrue(error.message.orEmpty().isNotBlank(), error.message)
            assertEquals(false, Files.exists(root.resolve("output.jsonl")))
        }
    }

    @Test
    fun causalSearchStopsAtFirstSupportedReturnBeforeDeadlineAndLeavesOnlyLaterRowsForEvaluation() {
        val thetas = listOf(20.0, 20.0, 120.0, 120.0, 20.0, 20.0, 120.0)
        val legs = listOf(.2, .2, 1.5, 1.5, .2, .2, 1.5)
        val root = Files.createTempDirectory("jumping-jack-causal-search")
        Files.writeString(
            root.resolve("pose.jsonl"),
            thetas.indices.joinToString("\n") { index -> practiceFrame(index * 100L, thetas[index], legs[index]) },
        )
        Files.writeString(root.resolve("config.json"), causalSearchConfigJson)

        ReplayJumpingJackPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))

        val rows = Files.readAllLines(root.resolve("output.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        val calibration = rows.first().getValue("calibration").jsonObject
        assertEquals("causal_first_supported_return_v1", calibration.getValue("profile").jsonPrimitive.content)
        assertEquals("1000", calibration.getValue("deadline_ns").jsonPrimitive.content)
        assertEquals("500", calibration.getValue("end_ns").jsonPrimitive.content)
        assertEquals(35.0, calibration.getValue("arms_down_degrees").jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(rows.take(6).all { it.getValue("reason").jsonPrimitive.content == "CALIBRATION_PRACTICE" })
        assertTrue(rows.take(6).all { it.getValue("count").jsonPrimitive.content == "0" })
        assertTrue(rows.take(6).all { it.getValue("evaluation").jsonPrimitive.content == "false" })
        assertEquals("true", rows[6].getValue("evaluation").jsonPrimitive.content)
    }

    @Test
    fun causalSearchRejectsInvalidModesAndAbortsBeforeOutputForMissingCoverageOrNoResult() {
        val cases = listOf(
            baseConfigJson.trimEnd().removeSuffix("}") +
                ",\"practice_window\":{\"start_ns\":0,\"end_ns\":700,\"minimum_range_degrees\":20,\"endpoint_fraction\":0.15}" +
                ",\"practice_search\":{\"start_ns\":0,\"deadline_ns\":1000,\"minimum_range_degrees\":20,\"endpoint_fraction\":0.15}}",
            baseConfigJson.trimEnd().removeSuffix("}") +
                ",\"practice_search\":{\"start_ns\":true,\"deadline_ns\":1000,\"minimum_range_degrees\":20,\"endpoint_fraction\":0.15}}",
            baseConfigJson.trimEnd().removeSuffix("}") +
                ",\"practice_search\":null}",
            baseConfigJson.trimEnd().removeSuffix("}") +
                ",\"practice_search\":{\"start_ns\":\"0\",\"deadline_ns\":1000,\"minimum_range_degrees\":20,\"endpoint_fraction\":0.15}}",
            baseConfigJson.trimEnd().removeSuffix("}") +
                ",\"practice_search\":{\"start_ns\":0,\"deadline_ns\":0,\"minimum_range_degrees\":20,\"endpoint_fraction\":0.15}}",
            baseConfigJson.trimEnd().removeSuffix("}") +
                ",\"practice_search\":{\"start_ns\":0,\"deadline_ns\":1000,\"minimum_range_degrees\":0,\"endpoint_fraction\":0.15}}",
        )
        for (config in cases) {
            val root = Files.createTempDirectory("jumping-jack-causal-invalid-config")
            Files.writeString(root.resolve("pose.jsonl"), practiceFrame(0, 20.0, .2))
            Files.writeString(root.resolve("config.json"), config)
            assertFailsWith<IllegalArgumentException> {
                ReplayJumpingJackPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))
            }
            assertEquals(false, Files.exists(root.resolve("output.jsonl")))
        }

        val firstSearchGapConfig = baseConfigJson.trimEnd().removeSuffix("}") +
            ",\"practice_search\":{\"start_ns\":100,\"deadline_ns\":1000,\"minimum_range_degrees\":20,\"endpoint_fraction\":0.15}}"
        for ((config, lines) in listOf(
            causalSearchConfigJson to listOf(practiceFrame(100, 20.0, .2)),
            firstSearchGapConfig to listOf(practiceFrame(0, 20.0, .2), practiceFrame(400, 20.0, .2)),
            causalSearchConfigJson to (0..7).map { practiceFrame(it * 100L, 20.0, .2) },
        )) {
            val root = Files.createTempDirectory("jumping-jack-causal-abort")
            Files.writeString(root.resolve("pose.jsonl"), lines.joinToString("\n"))
            Files.writeString(root.resolve("config.json"), config)
            assertFailsWith<IllegalArgumentException> {
                ReplayJumpingJackPose.run(root.resolve("pose.jsonl"), root.resolve("config.json"), root.resolve("output.jsonl"))
            }
            assertEquals(false, Files.exists(root.resolve("output.jsonl")))
        }
    }

    private val baseConfigJson = """
        {"arms_up_degrees":100.0,"arms_down_degrees":25.0,"legs_spread_ratio":1.4,
        "legs_together_ratio":0.3,"minimum_confidence":0.5,"maximum_gap_ns":250,
        "minimum_endpoint_samples":2}
    """.trimIndent()

    private val practiceConfigJson = baseConfigJson.trimEnd().removeSuffix("}") +
        ""","practice_window":{"start_ns":0,"end_ns":700,"minimum_range_degrees":20,"endpoint_fraction":0.15}}"""

    private val causalSearchConfigJson = baseConfigJson.trimEnd().removeSuffix("}") +
        ""","practice_search":{"start_ns":0,"deadline_ns":1000,"minimum_range_degrees":20,"endpoint_fraction":0.15}}"""

    private fun practiceFrame(timestampNs: Long, armThetaDegrees: Double, legSpreadRatio: Double): String {
        val radians = armThetaDegrees * kotlin.math.PI / 180.0
        val wristX = .25 * kotlin.math.sin(radians)
        val wristY = .25 * kotlin.math.cos(radians)
        val ankleOffset = legSpreadRatio * .10 / 2.0
        val landmarks = listOf(
            Triple(11, .45, .30), Triple(12, .55, .30),
            Triple(15, .45 + wristX, .30 + wristY), Triple(16, .55 + wristX, .30 + wristY),
            Triple(23, .45, .55), Triple(24, .55, .55),
            Triple(27, .5 - ankleOffset, .90), Triple(28, .5 + ankleOffset, .90),
        ).joinToString(",") { (index, x, y) ->
            """{"index":$index,"x":$x,"y":$y,"z":0,"visibility":0.95,"presence":0.95}"""
        }
        return """{"timestamp_ns":$timestampNs,"width":400,"height":400,"landmarks":[$landmarks]}"""
    }

    @Test
    fun emptyPoseInputFailsWithoutCreatingOutput() {
        val root = Files.createTempDirectory("jumping-jack-replay-empty-input")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, "")
        Files.writeString(configPath, configJson)

        assertFailsWith<IllegalArgumentException> { ReplayJumpingJackPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    private fun frame(timestampNs: Long, empty: Boolean = false) = """
        {
          "timestamp_ns":$timestampNs,
          "width":400,
          "height":400,
          "landmarks":${if (empty) "[]" else """[
            {"index":11,"x":0.45,"y":0.30,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":12,"x":0.55,"y":0.30,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":15,"x":0.44,"y":0.58,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":16,"x":0.56,"y":0.58,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":23,"x":0.47,"y":0.55,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":24,"x":0.53,"y":0.55,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":27,"x":0.48,"y":0.90,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":28,"x":0.52,"y":0.90,"z":0.0,"visibility":0.95,"presence":0.95}
          ]"""}
        }
    """.trimIndent().lines().joinToString("")
}
