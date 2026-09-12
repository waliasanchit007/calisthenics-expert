package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class ReplayLungePoseTest {
    private val configJson = """
        {
          "side":"LEFT",
          "extended_degrees":160.0,
          "flexed_degrees":100.0,
          "minimum_confidence":0.8,
          "maximum_gap_ns":500,
          "minimum_endpoint_samples":2
        }
    """.trimIndent()

    @Test
    fun parsesSourceNormalizedFramesAndPreservesEmptyNoPose() {
        val frames = ReplayLungePoseParser.parseFrames(sequenceOf(frame(0), frame(100, empty = true)))

        assertEquals(2, frames.size)
        assertEquals(23, frames[0].landmarks.first().index)
        assertTrue(frames[1].landmarks.isEmpty())
    }

    @Test
    fun rejectsMissingFieldsAndNonIncreasingTimestamps() {
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseFrames(sequenceOf(frame(0).replace("\"landmarks\"", "\"missing\"")))
        }
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseFrames(sequenceOf(frame(100), frame(100)))
        }
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseFrames(sequenceOf(frame(100), frame(99)))
        }
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseFrames(sequenceOf(frame(-1)))
        }
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseFrames(sequenceOf(frame(0).replace("0.95", "NaN")))
        }
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseFrames(sequenceOf(frame(0).replace("\"timestamp_ns\":0", "\"timestamp_ns\":\"0\"")))
        }
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseConfig(configJson.replace("\"extended_degrees\":160.0", "\"extended_degrees\":\"160.0\""))
        }
    }

    @Test
    fun replayWritesOneOutputRowPerFrameWithNullableMeasurementAndCount() {
        val root = Files.createTempDirectory("lunge-replay-test")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, listOf(frame(0), frame(100, empty = true)).joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplayLungePose.run(posePath, configPath, outputPath)

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
        assertFailsWith<IllegalArgumentException> { ReplayLungePoseParser.parseConfig("{}") }
        assertFailsWith<IllegalArgumentException> {
            ReplayLungePose.main(arrayOf("--posePath=/no/such/file", "--lungeConfigPath=/no/config", "--lungeOutputPath=/no/output"))
        }
    }

    @Test
    fun existingOutputIsNeverOverwritten() {
        val root = Files.createTempDirectory("lunge-replay-existing-output")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, frame(0) + "\n")
        Files.writeString(configPath, configJson)
        Files.writeString(outputPath, "sentinel\n")

        assertFailsWith<IllegalArgumentException> { ReplayLungePose.run(posePath, configPath, outputPath) }
        assertEquals("sentinel\n", Files.readString(outputPath))
    }

    @Test
    fun emptyPoseInputFailsWithoutCreatingOutput() {
        val root = Files.createTempDirectory("lunge-replay-empty-input")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, "")
        Files.writeString(configPath, configJson)

        assertFailsWith<IllegalArgumentException> { ReplayLungePose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    @Test
    fun legacyConfigWithoutAdmissionProfileParsesAsTheSingleLegBaseline() {
        assertEquals(
            LungeAdmissionProfile.SINGLE_LEG_BASELINE,
            ReplayLungePoseParser.parseConfig(configJson).admissionProfile,
        )
    }

    @Test
    fun bothKnownAdmissionProfileStringsParseExactly() {
        assertEquals(
            LungeAdmissionProfile.SINGLE_LEG_BASELINE,
            ReplayLungePoseParser.parseConfig(withProfile("\"SINGLE_LEG_BASELINE\"")).admissionProfile,
        )
        assertEquals(
            LungeAdmissionProfile.CONTRALATERAL_NOT_EXTENDED_V1,
            ReplayLungePoseParser.parseConfig(withProfile("\"CONTRALATERAL_NOT_EXTENDED_V1\"")).admissionProfile,
        )
    }

    @Test
    fun unknownOrNonStringAdmissionProfileValuesAreRejected() {
        listOf(
            "\"contralateral_not_extended_v1\"",
            "\"CONTRALATERAL_NOT_EXTENDED\"",
            "\"\"",
            "null",
            "true",
            "1",
            "[\"CONTRALATERAL_NOT_EXTENDED_V1\"]",
        ).forEach { value ->
            assertFailsWith<ReplayLungePoseFormatException>(value) {
                ReplayLungePoseParser.parseConfig(withProfile(value))
            }
        }
    }

    @Test
    fun previouslyRequiredKeysAndUnknownKeyRejectionSurviveTheOptionalProfileKey() {
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseConfig(withProfile("\"SINGLE_LEG_BASELINE\"").replace("\"side\":\"LEFT\",", ""))
        }
        assertFailsWith<ReplayLungePoseFormatException> {
            ReplayLungePoseParser.parseConfig(withProfile("\"SINGLE_LEG_BASELINE\"").replace("{", "{\"unexpected\":1,"))
        }
    }

    @Test
    fun replayEmitsTheContralateralDiagnosticOnlyUnderTheOptInProfile() {
        val root = Files.createTempDirectory("lunge-replay-profile")
        val posePath = root.resolve("pose.jsonl")
        Files.writeString(posePath, listOf(bilateralFrame(0), bilateralFrame(100)).joinToString("\n") + "\n")

        val baselineOutput = root.resolve("baseline.jsonl")
        val baselineConfig = root.resolve("baseline.json")
        Files.writeString(baselineConfig, configJson)
        ReplayLungePose.run(posePath, baselineConfig, baselineOutput)
        Files.readAllLines(baselineOutput).forEach { row ->
            assertEquals(
                false,
                Json.parseToJsonElement(row).jsonObject.containsKey("contralateral_knee_degrees"),
                "baseline replay rows must keep their existing schema exactly",
            )
        }

        val optInOutput = root.resolve("optin.jsonl")
        val optInConfig = root.resolve("optin.json")
        Files.writeString(optInConfig, withProfile("\"CONTRALATERAL_NOT_EXTENDED_V1\""))
        ReplayLungePose.run(posePath, optInConfig, optInOutput)
        Files.readAllLines(optInOutput).forEach { row ->
            val output = Json.parseToJsonElement(row).jsonObject
            assertTrue(output.containsKey("knee_degrees"), "legacy fields must be kept")
            assertTrue(output.containsKey("reason"))
            assertEquals(
                150.0,
                output.getValue("contralateral_knee_degrees").jsonPrimitive.doubleOrNull!!,
                1e-5,
            )
        }
    }

    private fun withProfile(value: String) = configJson.replace("{", "{\n  \"admission_profile\":$value,")

    // Both legs present with real landmark indices: tracked LEFT straight, RIGHT at 150 degrees.
    private fun bilateralFrame(timestampNs: Long) = """
        {
          "timestamp_ns":$timestampNs,
          "width":400,
          "height":400,
          "landmarks":[
            {"index":23,"x":0.35,"y":0.30,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":25,"x":0.35,"y":0.55,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":27,"x":0.35,"y":0.80,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":24,"x":0.55,"y":0.30,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":26,"x":0.55,"y":0.55,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":28,"x":0.675,"y":0.76650635,"z":0.0,"visibility":0.95,"presence":0.95}
          ]
        }
    """.trimIndent().lines().joinToString("")

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
