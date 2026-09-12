package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.lang.reflect.Modifier
import kotlin.math.cos
import kotlin.math.sin
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

class ReplayHangingLegRaisePoseTest {
    /** Frozen key set and ordering, with the two short-sequence overrides used by these tests. */
    private val configJson = """
        {
          "mode":"SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED",
          "side":"LEFT",
          "lowered_hip_min_degrees":150.0,
          "raised_hip_max_degrees":110.0,
          "minimum_hip_close_degrees":45.0,
          "raised_knee_min_degrees":140.0,
          "hanging_trunk_min_degrees":55.0,
          "minimum_support_rise_ratio":0.35,
          "maximum_support_travel_ratio":0.30,
          "minimum_confidence":0.5,
          "maximum_gap_ns":500,
          "minimum_endpoint_samples":2
        }
    """.trimIndent()

    @Test
    fun parsesTheFrozenOnDiskConfigTextExactlyAsWritten() {
        // Byte-for-byte copy of offline-corpus/coverage-first-20260909/dynamic/
        // hanging-leg-raise-config-v1.json. Pins the corpus schema so the frozen file cannot drift
        // away from the parser without a test failure.
        val frozen = """
            {
              "mode": "SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED",
              "side": "LEFT",
              "lowered_hip_min_degrees": 150.0,
              "raised_hip_max_degrees": 110.0,
              "minimum_hip_close_degrees": 45.0,
              "raised_knee_min_degrees": 140.0,
              "hanging_trunk_min_degrees": 55.0,
              "minimum_support_rise_ratio": 0.35,
              "maximum_support_travel_ratio": 0.30,
              "minimum_confidence": 0.5,
              "maximum_gap_ns": 100000000,
              "minimum_endpoint_samples": 3
            }
        """.trimIndent()

        val config = ReplayHangingLegRaisePoseParser.parseConfig(frozen)

        assertEquals(HangingLegRaiseMode.SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED, config.mode)
        assertEquals(ArmSide.LEFT, config.side)
        assertEquals(150.0, config.loweredHipMinDegrees, 1e-9)
        assertEquals(110.0, config.raisedHipMaxDegrees, 1e-9)
        assertEquals(45.0, config.minimumHipCloseDegrees, 1e-9)
        assertEquals(140.0, config.raisedKneeMinDegrees, 1e-9)
        assertEquals(55.0, config.hangingTrunkMinDegrees, 1e-9)
        assertEquals(0.35, config.minimumSupportRiseRatio, 1e-9)
        assertEquals(0.30, config.maximumSupportTravelRatio, 1e-9)
        assertEquals(0.5, config.minimumConfidence, 1e-9)
        assertEquals(100_000_000L, config.maximumGapNs)
        assertEquals(3, config.minimumEndpointSamples)
    }

    @Test
    fun parsesSourceNormalizedFramesAndPreservesAnEmptyNoPoseRow() {
        val frames = ReplayHangingLegRaisePoseParser.parseFrames(
            sequenceOf(lowered(0), frame(100, "[]")),
        )

        assertEquals(2, frames.size)
        assertEquals(5, frames[0].landmarks.size)
        assertEquals(400, frames[0].width)
        assertTrue(frames[1].landmarks.isEmpty())
    }

    @Test
    fun rejectsMalformedConfigAndFrames() {
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseConfig("{}")
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseConfig(
                configJson.replace("\"minimum_confidence\"", "\"confidence\""),
            )
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseConfig(configJson.dropLast(1) + ",\"extra\":1}")
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseConfig(configJson.replace("\"LEFT\"", "\"BOTH\""))
        }
        // The selected mode must be declared explicitly and must be a known mode: an unknown or
        // absent mode may never fall back to a default.
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseConfig(
                configJson.replace("\"SIDE_VIEW_OVERHEAD_SUPPORT_SELECTED\"", "\"AUTOMATIC\""),
            )
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseConfig(
                configJson.replace("\"raised_hip_max_degrees\":110.0", "\"raised_hip_max_degrees\":\"110\""),
            )
        }
        // A config that parses but violates the frozen ordering must not silently become a default.
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseConfig(
                configJson.replace("\"raised_hip_max_degrees\":110.0", "\"raised_hip_max_degrees\":160.0"),
            )
        }

        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseFrames(
                sequenceOf(lowered(0).replace("\"landmarks\"", "\"points\"")),
            )
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseFrames(sequenceOf(lowered(100), lowered(100)))
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseFrames(sequenceOf(lowered(100), lowered(99)))
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseFrames(sequenceOf(lowered(-1)))
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseFrames(sequenceOf(lowered(0).replace("0.95", "NaN")))
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseFrames(sequenceOf("   "))
        }
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePoseParser.parseFrames(sequenceOf("{oops"))
        }
    }

    @Test
    fun writesExactlyOneStrictlyKeyedRowPerInputFrameAndCountsTheCycle() {
        val root = Files.createTempDirectory("hanging-leg-raise-cycle")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, cycleRows().joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)

        ReplayHangingLegRaisePose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(8, rows.size)
        val first = Json.parseToJsonElement(rows[0]).jsonObject
        assertEquals(
            setOf(
                "timestamp_ns", "phase", "count", "reason", "counted", "hanging",
                "hip_degrees", "knee_degrees", "trunk_elevation_degrees", "support_rise_ratio",
                "closed_cycle",
            ),
            first.keys,
        )
        assertEquals(0L, first.getValue("timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(175.0, first.getValue("hip_degrees").jsonPrimitive.doubleOrNull!!, 1e-4)
        assertEquals(180.0, first.getValue("knee_degrees").jsonPrimitive.doubleOrNull!!, 1e-4)
        assertEquals(90.0, first.getValue("trunk_elevation_degrees").jsonPrimitive.doubleOrNull!!, 1e-4)
        assertEquals(0.5, first.getValue("support_rise_ratio").jsonPrimitive.doubleOrNull!!, 1e-4)
        assertEquals("true", first.getValue("hanging").jsonPrimitive.content)
        assertEquals(JsonNull, first["closed_cycle"])

        val last = Json.parseToJsonElement(rows[7]).jsonObject
        assertEquals(1, last.getValue("count").jsonPrimitive.content.toInt())
        assertEquals("true", last.getValue("counted").jsonPrimitive.content)
        assertEquals("LOWERED", last.getValue("phase").jsonPrimitive.content)
        assertEquals("NONE", last.getValue("reason").jsonPrimitive.content)
        val cycle = last.getValue("closed_cycle").jsonObject
        assertEquals(
            setOf(
                "departure_timestamp_ns", "raised_timestamp_ns", "return_timestamp_ns",
                "outcome", "minimum_hip_degrees", "observed_hip_close_degrees",
            ),
            cycle.keys,
        )
        assertEquals("COUNTED", cycle.getValue("outcome").jsonPrimitive.content)
        assertEquals(200L, cycle.getValue("departure_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(300L, cycle.getValue("raised_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(600L, cycle.getValue("return_timestamp_ns").jsonPrimitive.longOrNull)
    }

    @Test
    fun aBentKneeTuckRowSequenceIsWrittenAsATypedRejectionAndNeverCounts() {
        val root = Files.createTempDirectory("hanging-leg-raise-tuck")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(
            posePath,
            listOf(
                lowered(0), lowered(100), mid(200),
                tuck(300), tuck(400), mid(500),
                lowered(600), lowered(700),
            ).joinToString("\n") + "\n",
        )
        Files.writeString(configPath, configJson)

        ReplayHangingLegRaisePose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(8, rows.size)
        val rejection = rows[3].getValue("closed_cycle").jsonObject
        assertEquals("KNEE_FLEXION_REJECTED", rejection.getValue("outcome").jsonPrimitive.content)
        assertEquals(JsonNull, rejection["raised_timestamp_ns"])
        assertEquals(0, rows.last().getValue("count").jsonPrimitive.content.toInt())
        // A rejected tuck is still an admitted hanging observation: the trace must say the subject
        // was hanging and the count was refused, not that the frames were unusable.
        assertTrue(rows.all { it.getValue("hanging").jsonPrimitive.content == "true" })
        assertTrue(rows.all { it.getValue("reason").jsonPrimitive.content == "NONE" })
    }

    @Test
    fun anOpenCandidateAtEndOfInputIsAttachedToTheLastRowAsCensored() {
        val root = Files.createTempDirectory("hanging-leg-raise-censor")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(
            posePath,
            listOf(lowered(0), lowered(100), mid(200), raised(300), raised(400))
                .joinToString("\n") + "\n",
        )
        Files.writeString(configPath, configJson)

        ReplayHangingLegRaisePose.run(posePath, configPath, outputPath)

        val rows = Files.readAllLines(outputPath)
        assertEquals(5, rows.size, "exactly one row per input frame, never an extra censoring row")
        val last = Json.parseToJsonElement(rows[4]).jsonObject
        val cycle = last.getValue("closed_cycle").jsonObject
        assertEquals("CENSORED_AT_END_OF_INPUT", cycle.getValue("outcome").jsonPrimitive.content)
        assertEquals(200L, cycle.getValue("departure_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(300L, cycle.getValue("raised_timestamp_ns").jsonPrimitive.longOrNull)
        assertEquals(JsonNull, cycle["return_timestamp_ns"])
        assertEquals(0, last.getValue("count").jsonPrimitive.content.toInt())
    }

    @Test
    fun existingOutputIsNeverOverwrittenAndOutputMayNotAliasAnInput() {
        val root = Files.createTempDirectory("hanging-leg-raise-output")
        val posePath = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val outputPath = root.resolve("output.jsonl")
        Files.writeString(posePath, cycleRows().joinToString("\n") + "\n")
        Files.writeString(configPath, configJson)
        Files.writeString(outputPath, "existing\n")

        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.run(posePath, configPath, outputPath)
        }
        assertEquals(listOf("existing"), Files.readAllLines(outputPath))

        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.run(posePath, configPath, posePath)
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.run(posePath, configPath, root.resolve("missing/output.jsonl"))
        }
    }

    @Test
    fun emptyOrMalformedPoseInputFailsWithoutCreatingOutput() {
        val root = Files.createTempDirectory("hanging-leg-raise-empty")
        val configPath = root.resolve("config.json")
        Files.writeString(configPath, configJson)

        val emptyPose = root.resolve("empty.jsonl")
        Files.writeString(emptyPose, "")
        val emptyOutput = root.resolve("empty-output.jsonl")
        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.run(emptyPose, configPath, emptyOutput)
        }
        assertTrue(Files.notExists(emptyOutput))

        val badPose = root.resolve("bad.jsonl")
        Files.writeString(badPose, lowered(0) + "\n{oops\n")
        val badOutput = root.resolve("bad-output.jsonl")
        assertFailsWith<ReplayHangingLegRaisePoseFormatException> {
            ReplayHangingLegRaisePose.run(badPose, configPath, badOutput)
        }
    }

    @Test
    fun mainRequiresExactlyTheThreeNamedPathArguments() {
        assertFailsWith<IllegalArgumentException> { ReplayHangingLegRaisePose.main(arrayOf()) }
        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.main(arrayOf("--posePath=/a", "--hangingLegRaiseConfigPath=/b"))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.main(
                arrayOf("--posePath=/a", "--hangingLegRaiseConfigPath=/b", "--wrong=/c"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.main(arrayOf("posePath=/a", "config=/b", "output=/c"))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayHangingLegRaisePose.main(
                arrayOf("--posePath=/a", "--posePath=/a", "--hangingLegRaiseOutputPath=/c"),
            )
        }
    }

    @Test
    fun generatedJvmEntrypointIsStaticAndReturnsVoid() {
        val method = Class.forName(
            "com.calisthenics.diagnostic.viewer.experimental.ReplayHangingLegRaisePoseKt",
        ).getDeclaredMethod("main", Array<String>::class.java)

        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(Void.TYPE, method.returnType)
    }

    private fun cycleRows() = listOf(
        lowered(0), lowered(100), mid(200),
        raised(300), raised(400), mid(500),
        lowered(600), lowered(700),
    )

    private fun lowered(timestampNs: Long) = frame(timestampNs, landmarks(hipDegrees = 175.0))

    private fun mid(timestampNs: Long) = frame(timestampNs, landmarks(hipDegrees = 130.0))

    private fun raised(timestampNs: Long) = frame(timestampNs, landmarks(hipDegrees = 85.0))

    private fun tuck(timestampNs: Long) =
        frame(timestampNs, landmarks(hipDegrees = 85.0, kneeDegrees = 75.0))

    /**
     * Same construction as the shared behavioural test: a square 400x400 side-view hanging pose whose
     * measured hip angle, knee angle, trunk elevation and support-rise ratio equal the requested
     * values, built by placement rather than by calling the code under test.
     */
    private fun landmarks(
        hipDegrees: Double,
        kneeDegrees: Double = 180.0,
        trunkDegrees: Double = 90.0,
        supportRiseRatio: Double = 0.5,
    ): String {
        val radians = { degrees: Double -> degrees * kotlin.math.PI / 180.0 }
        val trunkRadius = 0.25
        val shoulderX = 0.5 - trunkRadius * cos(radians(trunkDegrees))
        val shoulderY = 0.5 - trunkRadius * sin(radians(trunkDegrees))
        val kneeRay = radians(180.0 + trunkDegrees + hipDegrees)
        val kneeX = 0.5 + 0.2 * cos(kneeRay)
        val kneeY = 0.5 + 0.2 * sin(kneeRay)
        val ankleRay = kneeRay + radians(180.0 + kneeDegrees)
        val ankleX = kneeX + 0.14 * cos(ankleRay)
        val ankleY = kneeY + 0.14 * sin(ankleRay)
        val points = listOf(
            11 to (shoulderX to shoulderY),
            15 to (shoulderX to (shoulderY - supportRiseRatio * trunkRadius)),
            23 to (0.5 to 0.5),
            25 to (kneeX to kneeY),
            27 to (ankleX to ankleY),
        )
        return points.joinToString(",", "[", "]") { (index, point) ->
            """{"index":$index,"x":${point.first},"y":${point.second},"z":0.0,""" +
                """"visibility":0.95,"presence":0.95}"""
        }
    }

    private fun frame(timestampNs: Long, landmarks: String) =
        """{"timestamp_ns":$timestampNs,"width":400,"height":400,"landmarks":$landmarks}"""
}
