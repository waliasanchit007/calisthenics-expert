package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Offline replay of extracted dip pose frames against an explicit prototype config.
 *
 * There is no practice-calibration path here on purpose: the dip thresholds are stated in the
 * config and frozen, so a replay cannot quietly refit them to the clip it is scoring.
 */
class ReplayDipPoseTest {
    private val config = """{"side":"LEFT","extended_elbow_degrees":155,"flexed_elbow_degrees":100,
        "minimum_shoulder_above_wrist_fraction":0.05,"maximum_torso_tilt_degrees":45,
        "minimum_confidence":0.5,"maximum_gap_ns":250,"minimum_endpoint_samples":2}"""

    private val oneCycle = listOf(160.0, 160.0, 130.0, 90.0, 90.0, 130.0, 160.0, 160.0)

    @Test
    fun replaysAnUprightArmsDownCycleAndReportsTheCountedFrame() {
        val rows = replay(oneCycle.mapIndexed { index, angle -> dipFrame(index * 100L, angle) })

        assertEquals(8, rows.size)
        assertEquals("1", rows.last()["count"]!!.jsonPrimitive.content)
        assertEquals("SUPPORTED", rows.last()["phase"]!!.jsonPrimitive.content)
        assertEquals("NONE", rows.last()["reason"]!!.jsonPrimitive.content)
        assertEquals("true", rows.last()["counted"]!!.jsonPrimitive.content)
        assertEquals(1, rows.count { it["counted"]!!.jsonPrimitive.content == "true" })
        // The event is the first frame of the confirming support run, not the confirming frame:
        // with minimum_endpoint_samples = 2 and 100ns spacing that is exactly one interval earlier.
        val counted = rows.single { it["counted"]!!.jsonPrimitive.content == "true" }
        assertEquals(
            counted["timestamp_ns"]!!.jsonPrimitive.long - 100L,
            counted["event_timestamp_ns"]!!.jsonPrimitive.long,
        )
        assertTrue(rows.filter { it["counted"]!!.jsonPrimitive.content == "false" }
            .all { it["event_timestamp_ns"] is kotlinx.serialization.json.JsonNull })
        assertTrue(rows.all { it.keys == expectedRowKeys })
        // Every admitted row must carry the geometry the admission was based on.
        assertTrue(
            rows.all {
                it["elbow_degrees"]!!.jsonPrimitive.doubleOrNull != null &&
                    it["shoulder_above_wrist_fraction"]!!.jsonPrimitive.doubleOrNull!! > 0.05 &&
                    it["torso_tilt_degrees"]!!.jsonPrimitive.doubleOrNull!! < 45.0
            },
        )
    }

    @Test
    fun twoConsecutiveCyclesCountTwiceAndASustainedBottomDoesNotInflateTheCount() {
        val doubled = oneCycle + oneCycle.drop(2)
        assertEquals("2", replay(doubled.mapIndexed { i, a -> dipFrame(i * 100L, a) }).last()["count"]!!
            .jsonPrimitive.content)

        val heldBottom = listOf(160.0, 160.0, 130.0, 90.0, 90.0, 90.0, 90.0, 90.0, 130.0, 160.0, 160.0)
        assertEquals("1", replay(heldBottom.mapIndexed { i, a -> dipFrame(i * 100L, a) }).last()["count"]!!
            .jsonPrimitive.content)
    }

    @Test
    fun anOverheadPullUpSequenceIsRefusedRatherThanCounted() {
        val rows = replay(oneCycle.mapIndexed { index, angle -> overheadFrame(index * 100L, angle) })

        assertEquals("0", rows.last()["count"]!!.jsonPrimitive.content)
        assertTrue(rows.all { it["reason"]!!.jsonPrimitive.content == "SHOULDER_NOT_ABOVE_WRIST" })
        assertTrue(rows.all { it["elbow_degrees"]!! == JsonNull })
    }

    @Test
    fun aHorizontalPushUpSequenceIsRefusedRatherThanCounted() {
        val rows = replay(oneCycle.mapIndexed { index, angle -> dipFrame(index * 100L, angle, torsoTiltDegrees = 88.0) })

        assertEquals("0", rows.last()["count"]!!.jsonPrimitive.content)
        assertTrue(rows.all { it["reason"]!!.jsonPrimitive.content == "TORSO_NOT_UPRIGHT" })
    }

    @Test
    fun aCaptureGapIsReportedAndBreaksTheCandidateCycle() {
        val frames = oneCycle.mapIndexed { index, angle ->
            dipFrame(if (index >= 5) index * 100L + 5_000L else index * 100L, angle)
        }
        val rows = replay(frames)

        assertEquals("CAPTURE_GAP", rows[5]["reason"]!!.jsonPrimitive.content)
        assertEquals("0", rows.last()["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun theConfigIsParsedStrictly() {
        val rejected = listOf(
            config.replace("\"side\":\"LEFT\"", "\"side\":\"BOTH\""),
            config.replace("\"minimum_confidence\":0.5,", ""),
            config.dropLast(1) + ",\"guess\":true}",
            config.replace("\"flexed_elbow_degrees\":100", "\"flexed_elbow_degrees\":170"),
            config.replace("\"maximum_torso_tilt_degrees\":45", "\"maximum_torso_tilt_degrees\":95"),
            config.replace("\"maximum_gap_ns\":250", "\"maximum_gap_ns\":0"),
            "[]",
        )
        rejected.forEach { text ->
            assertFailsWith<ReplayDipPoseFormatException>(text) { ReplayDipPoseParser.parseConfig(text) }
        }
        assertEquals(ArmSide.LEFT, ReplayDipPoseParser.parseConfig(config).side)
    }

    @Test
    fun poseFramesAreParsedStrictly() {
        val good = dipFrame(0, 160.0)
        assertFailsWith<ReplayDipPoseFormatException> {
            ReplayDipPoseParser.parseFrames(sequenceOf(good, dipFrame(0, 160.0)))
        }
        assertFailsWith<ReplayDipPoseFormatException> {
            ReplayDipPoseParser.parseFrames(sequenceOf(good, ""))
        }
        assertFailsWith<ReplayDipPoseFormatException> {
            ReplayDipPoseParser.parseFrames(sequenceOf(good.replace("\"z\":0.0", "\"depth\":0.0")))
        }
        assertFailsWith<ReplayDipPoseFormatException> {
            ReplayDipPoseParser.parseFrames(sequenceOf(good.replace("\"width\":400", "\"width\":0")))
        }
        assertEquals(1, ReplayDipPoseParser.parseFrames(sequenceOf(good)).size)
    }

    @Test
    fun replayNeverOverwritesAnExistingArtifactOrItsOwnInputs() {
        val root = Files.createTempDirectory("dip-replay-guard")
        val pose = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        Files.writeString(pose, dipFrame(0, 160.0))
        Files.writeString(configPath, config)
        val output = root.resolve("output.jsonl")
        Files.writeString(output, "stale")

        assertFailsWith<IllegalArgumentException> { ReplayDipPose.run(pose, configPath, output) }
        assertFailsWith<IllegalArgumentException> { ReplayDipPose.run(pose, configPath, pose) }
        assertFailsWith<IllegalArgumentException> {
            ReplayDipPose.run(root.resolve("missing.jsonl"), configPath, root.resolve("fresh.jsonl"))
        }
        assertEquals("stale", Files.readString(output).trim())
    }

    @Test
    fun theCommandLineRequiresExactlyTheThreeNamedPaths() {
        assertFailsWith<IllegalArgumentException> { ReplayDipPose.main(arrayOf("--posePath=/a")) }
        assertFailsWith<IllegalArgumentException> {
            ReplayDipPose.main(arrayOf("--posePath=/a", "--dipConfigPath=/b", "--other=/c"))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayDipPose.main(arrayOf("--posePath=/a", "--posePath=/a", "--dipOutputPath=/c"))
        }
    }

    private val expectedRowKeys = setOf(
        "timestamp_ns", "elbow_degrees", "shoulder_above_wrist_fraction", "torso_tilt_degrees",
        "phase", "count", "reason", "counted", "event_timestamp_ns",
    )

    private fun replay(frames: List<String>): List<JsonObject> {
        val root = Files.createTempDirectory("dip-replay")
        val pose = root.resolve("pose.jsonl")
        val configPath = root.resolve("config.json")
        val output = root.resolve("output.jsonl")
        Files.writeString(pose, frames.joinToString("\n"))
        Files.writeString(configPath, config)
        ReplayDipPose.run(pose, configPath, output)
        return Files.readAllLines(output).map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun dipFrame(timestampNs: Long, elbowDegrees: Double, torsoTiltDegrees: Double = 0.0): String {
        val elbowX = .5
        val elbowY = .55
        val limb = .20
        val shoulderY = elbowY - limb
        val radians = elbowDegrees * PI / 180.0
        val tilt = torsoTiltDegrees * PI / 180.0
        return frameJson(
            timestampNs,
            shoulder = elbowX to shoulderY,
            elbow = elbowX to elbowY,
            wrist = (elbowX + limb * sin(radians)) to (elbowY - limb * cos(radians)),
            hip = (elbowX + .35 * sin(tilt)) to (shoulderY + .35 * cos(tilt)),
        )
    }

    private fun overheadFrame(timestampNs: Long, elbowDegrees: Double): String {
        val elbowX = .5
        val elbowY = .45
        val limb = .20
        val shoulderY = elbowY + limb
        val radians = elbowDegrees * PI / 180.0
        return frameJson(
            timestampNs,
            shoulder = elbowX to shoulderY,
            elbow = elbowX to elbowY,
            wrist = (elbowX + limb * sin(radians)) to (elbowY + limb * cos(radians)),
            hip = elbowX to (shoulderY + .3),
        )
    }

    private fun frameJson(
        timestampNs: Long,
        shoulder: Pair<Double, Double>,
        elbow: Pair<Double, Double>,
        wrist: Pair<Double, Double>,
        hip: Pair<Double, Double>,
    ): String {
        fun landmark(index: Int, point: Pair<Double, Double>) =
            """{"index":$index,"x":${point.first},"y":${point.second},"z":0.0,""" +
                """"visibility":0.9,"presence":0.9}"""
        val landmarks = listOf(
            landmark(11, shoulder), landmark(13, elbow), landmark(15, wrist), landmark(23, hip),
        ).joinToString(",")
        return """{"timestamp_ns":$timestampNs,"width":400,"height":400,"landmarks":[$landmarks]}"""
    }
}
