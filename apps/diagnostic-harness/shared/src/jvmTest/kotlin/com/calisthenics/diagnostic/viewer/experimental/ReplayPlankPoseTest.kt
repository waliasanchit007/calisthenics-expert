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

class ReplayPlankPoseTest {
    private val configJson = """
        {
          "side":"LEFT",
          "straight_min_degrees":160.0,
          "straight_max_degrees":180.0,
          "minimum_confidence":0.8,
          "maximum_gap_ns":500,
          "minimum_entry_samples":2,
          "maximum_orientation_degrees_from_horizontal":45.0
        }
    """.trimIndent()

    @Test
    fun parsesSourceNormalizedFramesAndPreservesEmptyNoPose() {
        val frames = ReplayPlankPoseParser.parseFrames(sequenceOf(frame(0), frame(100, empty = true)))

        assertEquals(2, frames.size)
        assertEquals(11, frames[0].landmarks.first().index)
        assertTrue(frames[1].landmarks.isEmpty())
    }

    @Test
    fun rejectsMissingFieldsAndNonIncreasingTimestamps() {
        assertFailsWith<ReplayPlankPoseFormatException> {
            ReplayPlankPoseParser.parseFrames(sequenceOf(frame(0).replace("\"landmarks\"", "\"missing\"")))
        }
        assertFailsWith<ReplayPlankPoseFormatException> {
            ReplayPlankPoseParser.parseFrames(sequenceOf(frame(100), frame(100)))
        }
        assertFailsWith<ReplayPlankPoseFormatException> {
            ReplayPlankPoseParser.parseFrames(sequenceOf(frame(100), frame(99)))
        }
        assertFailsWith<ReplayPlankPoseFormatException> {
            ReplayPlankPoseParser.parseFrames(sequenceOf(frame(-1)))
        }
        assertFailsWith<ReplayPlankPoseFormatException> {
            ReplayPlankPoseParser.parseFrames(sequenceOf(frame(0).replace("0.95", "NaN")))
        }
        assertFailsWith<ReplayPlankPoseFormatException> {
            ReplayPlankPoseParser.parseFrames(sequenceOf(frame(0).replace("\"timestamp_ns\":0", "\"timestamp_ns\":\"0\"")))
        }
        assertFailsWith<ReplayPlankPoseFormatException> {
            ReplayPlankPoseParser.parseConfig(configJson.replace("\"straight_min_degrees\":160.0", "\"straight_min_degrees\":\"160.0\""))
        }
    }

    @Test
    fun replayWritesOneOutputRowPerFrameWithNullableMeasurementAndClosedHold() {
        val root = Files.createTempDirectory("plank-replay-test")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(
            posePath,
            listOf(frame(0), frame(100), frame(300, bent = true)).joinToString("\n") + "\n",
        )
        Files.writeString(configPath, configJson)

        ReplayPlankPose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(3, rows.size)
        val first = Json.parseToJsonElement(rows[0]).jsonObject
        assertEquals(0L, first.getValue("timestamp_ns").jsonPrimitive.longOrNull)
        assertTrue(first.containsKey("body_line_degrees"))
        assertTrue(first.containsKey("state"))
        assertTrue(first.containsKey("reason"))
        assertEquals(JsonNull, first["closed_hold"])

        val closingRow = Json.parseToJsonElement(rows[2]).jsonObject
        val closedHold = closingRow.getValue("closed_hold").jsonObject
        assertEquals(0L, closedHold.getValue("entry_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(100L, closedHold.getValue("exit_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(100L, closedHold.getValue("duration_ns").jsonPrimitive.longOrNull)
        assertEquals(100L, closedHold.getValue("observed_ns").jsonPrimitive.longOrNull)
    }

    @Test
    fun aHoldStillOpenAtEndOfInputIsReportedAsACensoredIntervalNotSilentlyDropped() {
        val root = Files.createTempDirectory("plank-replay-eof-censor")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(
            posePath,
            listOf(frame(0), frame(100), frame(300)).joinToString("\n") + "\n",
        )
        Files.writeString(configPath, configJson)

        ReplayPlankPose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(3, rows.size)
        assertEquals(JsonNull, Json.parseToJsonElement(rows[0]).jsonObject["closed_hold"])
        assertEquals(JsonNull, Json.parseToJsonElement(rows[1]).jsonObject["closed_hold"])

        val last = Json.parseToJsonElement(rows[2]).jsonObject
        assertEquals("HOLDING", last.getValue("state").jsonPrimitive.content)
        val closedHold = last.getValue("closed_hold").jsonObject
        assertEquals(0L, closedHold.getValue("entry_timestamp_ns").jsonPrimitive.longOrNull)
        // Censored at the last confirmed-good sample, never inventing coverage beyond it.
        assertEquals(300L, closedHold.getValue("exit_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(300L, closedHold.getValue("duration_ns").jsonPrimitive.longOrNull)
        assertEquals("CENSORED_AT_END_OF_INPUT", closedHold.getValue("end_reason").jsonPrimitive.content)
    }

    @Test
    fun strictConfigAndPathArgumentsFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { ReplayPlankPoseParser.parseConfig("{}") }
        assertFailsWith<IllegalArgumentException> {
            ReplayPlankPose.main(arrayOf("--posePath=/no/such/file", "--plankConfigPath=/no/config", "--plankOutputPath=/no/output"))
        }
    }

    @Test
    fun existingOutputIsNeverOverwritten() {
        val root = Files.createTempDirectory("plank-replay-existing-output")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, frame(0) + "\n")
        Files.writeString(configPath, configJson)
        Files.writeString(outputPath, "sentinel\n")

        assertFailsWith<IllegalArgumentException> { ReplayPlankPose.run(posePath, configPath, outputPath) }
        assertEquals("sentinel\n", Files.readString(outputPath))
    }

    @Test
    fun emptyPoseInputFailsWithoutCreatingOutput() {
        val root = Files.createTempDirectory("plank-replay-empty-input")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, "")
        Files.writeString(configPath, configJson)

        assertFailsWith<IllegalArgumentException> { ReplayPlankPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    // Landmarks describe a near-horizontal image-plane body line, matching this experiment's
    // side-view assumption; "bent" sags the hip while keeping shoulder/ankle level.
    private fun frame(timestampNs: Long, empty: Boolean = false, bent: Boolean = false) = """
        {
          "timestamp_ns":$timestampNs,
          "width":400,
          "height":200,
          "landmarks":${if (empty) "[]" else if (bent) """[
            {"index":11,"x":0.2,"y":0.4,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":23,"x":0.5,"y":0.6,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":27,"x":0.8,"y":0.4,"z":0.0,"visibility":0.95,"presence":0.95}
          ]""" else """[
            {"index":11,"x":0.2,"y":0.5,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":23,"x":0.5,"y":0.5,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":27,"x":0.8,"y":0.5,"z":0.0,"visibility":0.95,"presence":0.95}
          ]"""}
        }
    """.trimIndent().lines().joinToString("")
}
