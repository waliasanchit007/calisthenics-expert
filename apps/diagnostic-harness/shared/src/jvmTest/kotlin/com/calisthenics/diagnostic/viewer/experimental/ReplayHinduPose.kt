package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/** Offline only: reuse the existing strict pose-row reader, never the pike geometry. */
object ReplayHinduPose {
    fun parseConfig(text:String): HinduConfig {
        val j=Json.parseToJsonElement(text).jsonObject
        require(j.keys == setOf("side","minimum_confidence","maximum_gap_ns","minimum_endpoint_samples","minimum_hip_delta_fraction","minimum_endpoint_elbow_degrees","minimum_knee_degrees"))
        fun number(key:String): JsonPrimitive {
            val p=j.getValue(key).jsonPrimitive
            require(!p.isString)
            return p
        }
        return HinduConfig(
            ArmSide.valueOf(j.getValue("side").jsonPrimitive.content),
            number("minimum_confidence").double,number("maximum_gap_ns").long,
            number("minimum_endpoint_samples").int,number("minimum_hip_delta_fraction").double,
            number("minimum_endpoint_elbow_degrees").double,number("minimum_knee_degrees").double,
        )
    }

    @JvmStatic fun main(args:Array<String>) {
        require(args.size==3 && args.all { it.startsWith("--") && '=' in it })
        val options=args.map { it.removePrefix("--").split('=',limit=2) }.associate { it[0] to it[1] }
        require(options.keys==setOf("posePath","configPath","outputPath"))
        val input=Path.of(options.getValue("posePath"))
        val config=Path.of(options.getValue("configPath"))
        val output=Path.of(options.getValue("outputPath"))
        require(!Files.exists(output,java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "output already exists" }
        val engine=HinduPushUpExperiment(parseConfig(Files.readString(config)))
        val frames=Files.newBufferedReader(input).use { ReplayPikePushUpPoseParser.parseFrames(it.lineSequence()) }
        Files.newBufferedWriter(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { writer ->
            for(f in frames) {
                val r=engine.onFrame(HinduPoseFrame(f.timestampNs,f.width,f.height,f.landmarks))
                val row=buildJsonObject {
                    put("timestamp_ns",r.timestampNs); put("count",r.count); put("counted",r.counted)
                    put("event_timestamp_ns", r.eventTimestampNs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("phase",r.phase); put("reason",r.reason)
                    put("hip_delta_fraction",r.hipDeltaFraction?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("elbow_degrees",r.elbowDegrees?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                writer.write(row.toString()); writer.newLine()
            }
        }
    }
}
