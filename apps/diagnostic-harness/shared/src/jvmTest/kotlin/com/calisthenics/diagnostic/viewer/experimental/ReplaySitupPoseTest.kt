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

class ReplaySitupPoseTest {
    private val configJson = """
        {
          "side":"LEFT",
          "reclined_hip_min_degrees":130.0,
          "raised_hip_max_degrees":85.0,
          "reclined_trunk_max_degrees":35.0,
          "raised_trunk_min_degrees":45.0,
          "minimum_trunk_rise_degrees":25.0,
          "minimum_confidence":0.5,
          "maximum_gap_ns":500,
          "minimum_endpoint_samples":2
        }
    """.trimIndent()

    @Test
    fun parsesSourceNormalizedFramesAndPreservesAnEmptyNoPoseRow() {
        val frames = ReplaySitupPoseParser.parseFrames(sequenceOf(reclined(0), frame(100, "[]")))

        assertEquals(2, frames.size)
        assertEquals(11, frames[0].landmarks.first().index)
        assertEquals(400, frames[0].width)
        assertTrue(frames[1].landmarks.isEmpty())
    }

    @Test
    fun rejectsMalformedConfigAndFrames() {
        assertFailsWith<ReplaySitupPoseFormatException> { ReplaySitupPoseParser.parseConfig("{}") }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseConfig(configJson.replace("\"minimum_confidence\"", "\"confidence\""))
        }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseConfig(configJson.dropLast(1) + ",\"extra\":1}")
        }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseConfig(configJson.replace("\"LEFT\"", "\"BOTH\""))
        }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseConfig(configJson.replace("\"raised_hip_max_degrees\":85.0", "\"raised_hip_max_degrees\":\"85\""))
        }
        // A config that parses but violates the frozen ordering must not silently become a default.
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseConfig(configJson.replace("\"raised_hip_max_degrees\":85.0", "\"raised_hip_max_degrees\":140.0"))
        }

        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseFrames(sequenceOf(reclined(0).replace("\"landmarks\"", "\"points\"")))
        }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseFrames(sequenceOf(reclined(100), reclined(100)))
        }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseFrames(sequenceOf(reclined(100), reclined(99)))
        }
        assertFailsWith<ReplaySitupPoseFormatException> { ReplaySitupPoseParser.parseFrames(sequenceOf(reclined(-1))) }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseFrames(sequenceOf(reclined(0).replace("0.95", "NaN")))
        }
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPoseParser.parseFrames(sequenceOf(reclined(0).replace("\"timestamp_ns\":0", "\"timestamp_ns\":\"0\"")))
        }
        assertFailsWith<ReplaySitupPoseFormatException> { ReplaySitupPoseParser.parseFrames(sequenceOf("   ")) }
        assertFailsWith<ReplaySitupPoseFormatException> { ReplaySitupPoseParser.parseFrames(sequenceOf("{oops")) }
    }

    @Test
    fun writesExactlyOneStrictlyKeyedRowPerInputFrameAndCountsTheCycle() {
        val root = Files.createTempDirectory("situp-replay-cycle")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, cycleRows().joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplaySitupPose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(8, rows.size)
        val first = Json.parseToJsonElement(rows[0]).jsonObject
        assertEquals(
            setOf(
                "timestamp_ns", "phase", "count", "reason", "counted",
                "hip_degrees", "torso_elevation_degrees", "closed_cycle",
            ),
            first.keys,
        )
        assertEquals(0L, first.getValue("timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(150.0, first.getValue("hip_degrees").jsonPrimitive.doubleOrNull!!, 1e-4)
        assertEquals(0.0, first.getValue("torso_elevation_degrees").jsonPrimitive.doubleOrNull!!, 1e-4)
        assertEquals(JsonNull, first["closed_cycle"])
        assertEquals(false, Json.parseToJsonElement(rows[6]).jsonObject.getValue("counted").jsonPrimitive.content.toBoolean())

        val last = Json.parseToJsonElement(rows[7]).jsonObject
        assertEquals(1, last.getValue("count").jsonPrimitive.content.toInt())
        assertEquals("true", last.getValue("counted").jsonPrimitive.content)
        assertEquals("RECLINED", last.getValue("phase").jsonPrimitive.content)
        assertEquals("NONE", last.getValue("reason").jsonPrimitive.content)
        val cycle = last.getValue("closed_cycle").jsonObject
        assertEquals(
            setOf(
                "departure_timestamp_ns", "raised_timestamp_ns", "return_timestamp_ns",
                "outcome", "minimum_hip_degrees", "observed_trunk_rise_degrees",
            ),
            cycle.keys,
        )
        assertEquals("COUNTED", cycle.getValue("outcome").jsonPrimitive.content)
        assertEquals(200L, cycle.getValue("departure_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(300L, cycle.getValue("raised_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(600L, cycle.getValue("return_timestamp_ns").jsonPrimitive.longOrNull)
    }

    @Test
    fun anOpenCandidateAtEndOfInputIsAttachedToTheLastRowAsCensored() {
        val root = Files.createTempDirectory("situp-replay-censor")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, cycleRows().take(5).joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplaySitupPose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(5, rows.size)
        rows.dropLast(1).forEach { assertEquals(JsonNull, Json.parseToJsonElement(it).jsonObject["closed_cycle"]) }
        val last = Json.parseToJsonElement(rows[4]).jsonObject
        assertEquals("RAISED", last.getValue("phase").jsonPrimitive.content)
        assertEquals(0, last.getValue("count").jsonPrimitive.content.toInt())
        val censored = last.getValue("closed_cycle").jsonObject
        assertEquals("CENSORED_AT_END_OF_INPUT", censored.getValue("outcome").jsonPrimitive.content)
        assertEquals(200L, censored.getValue("departure_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(300L, censored.getValue("raised_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(JsonNull, censored["return_timestamp_ns"])
    }

    @Test
    fun existingOutputIsNeverOverwrittenAndOutputMayNotAliasAnInput() {
        val root = Files.createTempDirectory("situp-replay-output-guard")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, reclined(0) + "\n")
        Files.writeString(configPath, configJson)
        Files.writeString(outputPath, "sentinel\n")

        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals("sentinel\n", Files.readString(outputPath))
        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, posePath) }
        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, configPath) }
        assertEquals(configJson, Files.readString(configPath))
    }

    @Test
    fun emptyOrMalformedPoseInputFailsWithoutCreatingOutput() {
        val root = Files.createTempDirectory("situp-replay-bad-input")
        val configPath = root.resolve("config.json")
        Files.writeString(configPath, configJson)

        val emptyPose = root.resolve("empty.jsonl")
        Files.writeString(emptyPose, "")
        val emptyOutput = root.resolve("empty-output.jsonl")
        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(emptyPose, configPath, emptyOutput) }
        assertEquals(false, Files.exists(emptyOutput))

        val malformedPose = root.resolve("malformed.jsonl")
        Files.writeString(malformedPose, reclined(0) + "\n{oops\n")
        val malformedOutput = root.resolve("malformed-output.jsonl")
        assertFailsWith<ReplaySitupPoseFormatException> {
            ReplaySitupPose.run(malformedPose, configPath, malformedOutput)
        }
    }

    @Test
    fun mainRequiresExactlyTheThreeNamedPathArguments() {
        assertFailsWith<IllegalArgumentException> {
            ReplaySitupPose.main(arrayOf("--posePath=/no/pose", "--situpConfigPath=/no/config", "--situpOutputPath=/no/output"))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplaySitupPose.main(arrayOf("--posePath=/no/pose", "--configPath=/no/config", "--situpOutputPath=/no/output"))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplaySitupPose.main(arrayOf("--posePath=/no/pose", "--situpConfigPath=/no/config"))
        }
    }

    // --- side_admission_window (opt-in bounded sit-up side admission) ---
    //
    // These exercise the optional `side_admission_window` config field described in
    // docs/superpowers/sdd/2026-09-09-overnight-movement-evidence/situp-admission/design.md.
    // They are expected to fail until ReplaySitupPose.kt gains admission wiring; a
    // ReplaySitupPoseFormatException here (config keys mismatch) is the currently-correct RED,
    // not a bug in these tests.

    @Test
    fun baselineOutputWithoutASideAdmissionWindowIsByteIdenticalToTheExistingSchema() {
        val root = Files.createTempDirectory("situp-replay-admission-baseline")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, listOf(reclined(0), reclined(100)).joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplaySitupPose.run(posePath, configPath, outputPath)

        val row = Json.parseToJsonElement(Files.readAllLines(outputPath).first()).jsonObject
        assertEquals(
            setOf(
                "timestamp_ns", "phase", "count", "reason", "counted",
                "hip_degrees", "torso_elevation_degrees", "closed_cycle",
            ),
            row.keys,
        )
    }

    @Test
    fun onlyLeftEligibleInTheDeclaredWindowIsSelectedEvenWhenConfiguredSideIsRight() {
        val root = Files.createTempDirectory("situp-replay-admission-select")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        val rows = listOf(reclined(0), reclined(50)) + offsetCycleRows(200)
        Files.writeString(posePath, rows.joinToString("\n") + "\n")
        Files.writeString(configPath, windowConfigJson(side = "RIGHT", startNs = 0, endNs = 100))

        ReplaySitupPose.run(posePath, configPath, outputPath)

        val outputRows = Files.readAllLines(outputPath).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(rows.size, outputRows.size)

        val prefix = outputRows.take(2)
        prefix.forEach { row ->
            assertEquals("WAITING", row.getValue("phase").jsonPrimitive.content)
            assertEquals(0, row.getValue("count").jsonPrimitive.content.toInt())
            assertEquals("false", row.getValue("counted").jsonPrimitive.content)
            assertEquals("SIDE_ADMISSION", row.getValue("reason").jsonPrimitive.content)
            assertEquals(JsonNull, row["hip_degrees"])
            assertEquals(JsonNull, row["torso_elevation_degrees"])
            assertEquals(JsonNull, row["closed_cycle"])
            assertEquals("false", row.getValue("evaluation").jsonPrimitive.content)
        }

        val admission = outputRows.first().getValue("side_admission").jsonObject
        assertEquals(
            setOf(
                "profile", "start_ns", "end_ns", "preferred_side", "selected_side",
                "minimum_samples", "observed_samples", "left_usable_samples", "right_usable_samples",
            ),
            admission.keys,
        )
        assertEquals("tracking_only_fixed_side_v1", admission.getValue("profile").jsonPrimitive.content)
        assertEquals(0L, admission.getValue("start_ns").jsonPrimitive.longOrNull)
        assertEquals(100L, admission.getValue("end_ns").jsonPrimitive.longOrNull)
        assertEquals("RIGHT", admission.getValue("preferred_side").jsonPrimitive.content)
        assertEquals("LEFT", admission.getValue("selected_side").jsonPrimitive.content)
        assertEquals(2, admission.getValue("minimum_samples").jsonPrimitive.content.toInt())
        assertEquals(2, admission.getValue("observed_samples").jsonPrimitive.content.toInt())
        assertEquals(2, admission.getValue("left_usable_samples").jsonPrimitive.content.toInt())
        assertEquals(0, admission.getValue("right_usable_samples").jsonPrimitive.content.toInt())

        outputRows.drop(2).forEach { row ->
            assertEquals("true", row.getValue("evaluation").jsonPrimitive.content)
        }
        assertEquals(1, outputRows.last().getValue("count").jsonPrimitive.content.toInt())
    }

    @Test
    fun theSelectedSideStaysFixedForEvaluationEvenWhenLaterFramesWouldOnlySuitTheOtherSide() {
        val root = Files.createTempDirectory("situp-replay-admission-no-leak")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        // Window only supports LEFT; every evaluation-side frame only supports RIGHT. LEFT stays
        // the selected side, so every evaluation row must report a LEFT-geometry failure reason,
        // never a silent re-selection onto RIGHT.
        val rows = listOf(reclined(0), reclined(50), frame(200, rightLandmarks("0.75,0.5", "0.3267949,0.4")))
        Files.writeString(posePath, rows.joinToString("\n") + "\n")
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 0, endNs = 100))

        ReplaySitupPose.run(posePath, configPath, outputPath)

        val last = Json.parseToJsonElement(Files.readAllLines(outputPath).last()).jsonObject
        assertEquals("true", last.getValue("evaluation").jsonPrimitive.content)
        assertEquals("MISSING_LANDMARK", last.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun aFullCycleShapeEntirelyInsideTheDeclaredWindowIsNeverCountedByThePrefix() {
        val root = Files.createTempDirectory("situp-replay-admission-prefix-not-counted")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        // The whole reclined -> raised -> reclined shape sits at or before end_ns=700; a fresh
        // counter fed all of it would count 1, but every one of these rows is prefix.
        val windowRows = cycleRows()
        val evaluationRows = listOf(reclined(800), reclined(900))
        Files.writeString(posePath, (windowRows + evaluationRows).joinToString("\n") + "\n")
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 0, endNs = 700))

        ReplaySitupPose.run(posePath, configPath, outputPath)

        val outputRows = Files.readAllLines(outputPath).map { Json.parseToJsonElement(it).jsonObject }
        outputRows.take(windowRows.size).forEach { row ->
            assertEquals(0, row.getValue("count").jsonPrimitive.content.toInt())
            assertEquals("false", row.getValue("counted").jsonPrimitive.content)
        }
        assertEquals(0, outputRows.last().getValue("count").jsonPrimitive.content.toInt())
    }

    @Test
    fun aClipThatStartsAfterTheDeclaredWindowStartAbortsEvenWithEnoughSetupObservations() {
        val root = Files.createTempDirectory("situp-replay-admission-late-start")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        // start=0/end=100, but the earliest frame is 50: the clip never covers the declared start.
        Files.writeString(
            posePath,
            listOf(reclined(50), reclined(100), reclined(200)).joinToString("\n") + "\n",
        )
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 0, endNs = 100))

        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    @Test
    fun aClipWithNoFrameAfterTheDeclaredEndAbortsForMissingEvaluation() {
        val root = Files.createTempDirectory("situp-replay-admission-no-evaluation")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        // start=0/end=100, but every frame is at or before end: there is nothing to evaluate.
        Files.writeString(posePath, listOf(reclined(0), reclined(50), reclined(100)).joinToString("\n") + "\n")
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 0, endNs = 100))

        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    @Test
    fun theFirstWindowObservationMustBeWithinMaximumGapOfTheDeclaredStart() {
        val root = Files.createTempDirectory("situp-replay-admission-late-window-start")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        // start=100/end=900; the clip covers start (frame 0), but the first observation actually
        // inside [100, 900] is 800, 700ns after start_ns -- past the 500ns synthetic gap limit.
        Files.writeString(
            posePath,
            listOf(reclined(0), reclined(800), reclined(900), reclined(1000)).joinToString("\n") + "\n",
        )
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 100, endNs = 900))

        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    @Test
    fun theLastWindowObservationMustBeWithinMaximumGapOfTheDeclaredEnd() {
        val root = Files.createTempDirectory("situp-replay-admission-early-window-end")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        // start=0/end=1000; there is a later evaluation frame (1100), but the last observation
        // inside [0, 1000] is 100, 900ns before end_ns -- past the 500ns synthetic gap limit.
        Files.writeString(
            posePath,
            listOf(reclined(0), reclined(100), reclined(1100)).joinToString("\n") + "\n",
        )
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 0, endNs = 1000))

        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    @Test
    fun aDeclaredWindowWhereNeitherSideIsEligibleAbortsBeforeCreatingOutput() {
        val root = Files.createTempDirectory("situp-replay-admission-ineligible")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        val lowConfidenceWindow = frame(
            0,
            landmarks(shoulder = "0.25,0.5", knee = "0.6732051,0.4").replace("0.95", "0.1"),
        )
        // Two setup observations and later evaluation satisfy the window shape; only
        // missing tracking should make this abort, not too few setup samples.
        Files.writeString(posePath, listOf(lowConfidenceWindow, frame(50, "[]"), reclined(200)).joinToString("\n") + "\n")
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 0, endNs = 100))

        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    @Test
    fun aMalformedSideAdmissionWindowIsRejectedWithoutCreatingOutput() {
        val root = Files.createTempDirectory("situp-replay-admission-malformed")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, listOf(reclined(0), reclined(50)).joinToString("\n") + "\n")

        // end_ns <= start_ns.
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 100, endNs = 100))
        assertFailsWith<IllegalArgumentException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))

        // Boolean instead of an integer pair.
        Files.writeString(configPath, configJson.dropLast(1) + ",\"side_admission_window\":true}")
        assertFailsWith<ReplaySitupPoseFormatException> { ReplaySitupPose.run(posePath, configPath, outputPath) }
        assertEquals(false, Files.exists(outputPath))
    }

    @Test
    fun existingFinishAndCensoredBehaviorIsPreservedWhenASideAdmissionWindowIsUsed() {
        val root = Files.createTempDirectory("situp-replay-admission-censored")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        val rows = listOf(reclined(0), reclined(50)) + offsetCycleRows(200).take(3)
        Files.writeString(posePath, rows.joinToString("\n") + "\n")
        Files.writeString(configPath, windowConfigJson(side = "LEFT", startNs = 0, endNs = 100))

        ReplaySitupPose.run(posePath, configPath, outputPath)

        val outputRows = Files.readAllLines(outputPath).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(rows.size, outputRows.size)
        val last = outputRows.last()
        val censored = last.getValue("closed_cycle").jsonObject
        assertEquals("CENSORED_AT_END_OF_INPUT", censored.getValue("outcome").jsonPrimitive.content)
    }

    // Hand-derived side-view landmarks in a 400x400 source: reclined reads 150 degrees of hip angle
    // at 0 degrees of trunk elevation, the intermediate pose 110/20, and raised 70/60.
    private fun cycleRows() = listOf(
        reclined(0), reclined(100), mid(200), raised(300),
        raised(400), mid(500), reclined(600), reclined(700),
    )

    private fun reclined(timestampNs: Long) = frame(
        timestampNs,
        landmarks(shoulder = "0.25,0.5", knee = "0.6732051,0.4"),
    )

    private fun mid(timestampNs: Long) = frame(
        timestampNs,
        landmarks(shoulder = "0.2650768,0.414495", knee = "0.6285575,0.3467911"),
    )

    private fun raised(timestampNs: Long) = frame(
        timestampNs,
        landmarks(shoulder = "0.375,0.2834936", knee = "0.6285575,0.3467911"),
    )

    private fun landmarks(shoulder: String, knee: String): String {
        val (shoulderX, shoulderY) = shoulder.split(",")
        val (kneeX, kneeY) = knee.split(",")
        return """[
            {"index":11,"x":$shoulderX,"y":$shoulderY,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":23,"x":0.5,"y":0.5,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":25,"x":$kneeX,"y":$kneeY,"z":0.0,"visibility":0.95,"presence":0.95}
        ]"""
    }

    private fun offsetCycleRows(startNs: Long) = listOf(
        reclined(startNs), reclined(startNs + 100), mid(startNs + 200), raised(startNs + 300),
        raised(startNs + 400), mid(startNs + 500), reclined(startNs + 600), reclined(startNs + 700),
    )

    private fun rightLandmarks(shoulder: String, knee: String): String {
        val (shoulderX, shoulderY) = shoulder.split(",")
        val (kneeX, kneeY) = knee.split(",")
        return """[
            {"index":12,"x":$shoulderX,"y":$shoulderY,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":24,"x":0.5,"y":0.5,"z":0.0,"visibility":0.95,"presence":0.95},
            {"index":26,"x":$kneeX,"y":$kneeY,"z":0.0,"visibility":0.95,"presence":0.95}
        ]"""
    }

    private fun windowConfigJson(side: String, startNs: Long, endNs: Long) = """
        {
          "side":"$side",
          "reclined_hip_min_degrees":130.0,
          "raised_hip_max_degrees":85.0,
          "reclined_trunk_max_degrees":35.0,
          "raised_trunk_min_degrees":45.0,
          "minimum_trunk_rise_degrees":25.0,
          "minimum_confidence":0.5,
          "maximum_gap_ns":500,
          "minimum_endpoint_samples":2,
          "side_admission_window":{"start_ns":$startNs,"end_ns":$endNs}
        }
    """.trimIndent()

    private fun frame(timestampNs: Long, landmarks: String) = """
        {
          "timestamp_ns":$timestampNs,
          "width":400,
          "height":400,
          "landmarks":$landmarks
        }
    """.trimIndent().lines().joinToString("")
}
