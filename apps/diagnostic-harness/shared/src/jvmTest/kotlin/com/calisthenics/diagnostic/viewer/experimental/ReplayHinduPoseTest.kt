package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class ReplayHinduPoseTest {
    private val config="""{"side":"LEFT","minimum_confidence":0.5,"maximum_gap_ns":250000000,"minimum_endpoint_samples":2,"minimum_hip_delta_fraction":0.03,"minimum_endpoint_elbow_degrees":150.0,"minimum_knee_degrees":150.0}"""
    @Test fun realEntrypointWritesAlignedRowsAndCountsOnlyTheCompleteCycle() {
        assertTrue(Modifier.isStatic(ReplayHinduPose::class.java.getMethod("main",Array<String>::class.java).modifiers))
        val dir=Files.createTempDirectory("hindu-replay")
        val poses=dir.resolve("poses.jsonl"); val settings=dir.resolve("config.json"); val output=dir.resolve("out.jsonl")
        val hips=listOf(.15,.15,.4,.6,.6,.4,.15,.15)
        Files.writeString(poses,hips.mapIndexed { i,h ->
            val points=listOf(11 to (.3 to .4),13 to (.3 to .6),15 to (.3 to .8),23 to (.55 to h),25 to (.7 to ((h+.8)/2)),27 to (.85 to .8))
            val landmarks=points.joinToString(",") { (j,p) -> """{"index":$j,"x":${p.first},"y":${p.second},"z":0,"visibility":0.9,"presence":0.9}""" }
            """{"timestamp_ns":${i*100000000L},"width":500,"height":500,"landmarks":[$landmarks]}"""
        }.joinToString("\n"))
        Files.writeString(settings,config)
        ReplayHinduPose.main(arrayOf("--posePath=$poses","--configPath=$settings","--outputPath=$output"))
        val rows=Files.readAllLines(output).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(8,rows.size)
        assertEquals(1,rows.last().getValue("count").jsonPrimitive.int)
        assertEquals((0..7).map { it*100000000L },rows.map { it.getValue("timestamp_ns").jsonPrimitive.long })
        assertFailsWith<IllegalArgumentException> {
            ReplayHinduPose.main(arrayOf("--posePath=$poses","--configPath=$settings","--outputPath=$output"))
        }
    }
    @Test fun configRejectsUnknownFieldsAndOutOfRangeConfidence() {
        assertFailsWith<IllegalArgumentException> { ReplayHinduPose.parseConfig(config.dropLast(1)+",\"unknown\":1}") }
        assertFailsWith<IllegalArgumentException> { ReplayHinduPose.parseConfig(config.replace("0.5","1.5")) }
    }
}
