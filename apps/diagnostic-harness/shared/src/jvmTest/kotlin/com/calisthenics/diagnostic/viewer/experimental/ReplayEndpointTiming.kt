package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/**
 * Offline only. Reads an EXISTING engine prediction file and adds an endpoint estimate per counted
 * repetition. It never re-runs the engine, so counts and cycle identities cannot move: they are
 * read from the file as facts. Specification: `endpoint-timing-spec-v1.md`.
 */
object ReplayEndpointTiming {
    private val KEYS = setOf("plateau_band_fraction", "admission_level", "reversal_samples", "signal_field")

    fun parseConfig(text: String): Pair<EndpointTimingConfig, String> {
        val j = Json.parseToJsonElement(text).jsonObject
        require(j.keys == KEYS) { "config keys must be exactly $KEYS" }
        fun number(key: String): JsonPrimitive {
            val p = j.getValue(key).jsonPrimitive
            require(!p.isString) { "$key must be a number" }
            return p
        }
        return EndpointTimingConfig(
            plateauBandFraction = number("plateau_band_fraction").double,
            admissionLevel = number("admission_level").double,
            reversalSamples = number("reversal_samples").int,
        ) to j.getValue("signal_field").jsonPrimitive.content
    }

    private class Row(val ts: Long, val value: Double?, val reason: String, val counted: Boolean, val event: Long?)

    fun run(input: Path, config: Path, output: Path) {
        require(!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "output already exists" }
        require(output != input && output != config) { "output must differ from inputs" }
        val (timingConfig, signalField) = parseConfig(Files.readString(config))
        val rows = Files.readAllLines(input).mapNotNull { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val ts = (o["timestamp_ns"] ?: return@mapNotNull null).jsonPrimitive.long
            Row(
                ts = ts,
                value = o[signalField]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double,
                reason = o["reason"]?.jsonPrimitive?.content ?: "NONE",
                counted = o["counted"]?.jsonPrimitive?.boolean ?: false,
                event = o["event_timestamp_ns"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long,
            )
        }
        val countedIndices = rows.indices.filter { rows[it].counted }
        Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
            for ((ordinal, index) in countedIndices.withIndex()) {
                val row = rows[index]
                val crossing = row.event ?: row.ts
                val from = rows.indexOfFirst { it.ts >= crossing }
                // Hard upper bound: never reach into the next counted repetition.
                val nextCrossing = countedIndices.getOrNull(ordinal + 1)
                    ?.let { rows[it].event ?: rows[it].ts } ?: Long.MAX_VALUE
                val window = rows.subList(from, rows.size).takeWhile { it.ts < nextCrossing }
                val discontinuity = window.any {
                    it.reason == "CAPTURE_GAP" || it.reason == "DUPLICATE_TIMESTAMP" ||
                        it.reason == "BACKWARDS_TIMESTAMP" || it.reason == "INVALID_TIMESTAMP"
                }
                val samples = window.map { EndpointSample(it.ts, it.value, it.value != null) }
                val estimate = estimateEndpoint(crossing, row.ts, samples, timingConfig, discontinuity)
                writer.write(
                    buildJsonObject {
                        put("rep", ordinal + 1)
                        put("crossing_ns", estimate.crossingNs)
                        put("confirmation_ns", estimate.confirmationNs)
                        put("extremum_ns", estimate.extremumNs?.let(::JsonPrimitive) ?: JsonNull)
                        put("plateau_arrival_ns", estimate.plateauArrivalNs?.let(::JsonPrimitive) ?: JsonNull)
                        put("estimated_departure_ns", estimate.estimatedDepartureNs?.let(::JsonPrimitive) ?: JsonNull)
                        put("uncertainty_ns", estimate.uncertaintyNs?.let(::JsonPrimitive) ?: JsonNull)
                        put("reversal_confirmed_ns", estimate.reversalConfirmedNs?.let(::JsonPrimitive) ?: JsonNull)
                        put("confirmation_delay_ns", estimate.confirmationDelayNs?.let(::JsonPrimitive) ?: JsonNull)
                        put("status", estimate.status.name)
                        put("double_peak_candidates_ns", JsonArray(estimate.doublePeakCandidatesNs.map(::JsonPrimitive)))
                    }.toString(),
                )
                writer.newLine()
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && args.all { it.startsWith("--") && '=' in it })
        val o = args.map { it.removePrefix("--").split('=', limit = 2) }.associate { it[0] to it[1] }
        require(o.keys == setOf("predictionsPath", "endpointConfigPath", "endpointOutputPath"))
        run(
            Path.of(o.getValue("predictionsPath")),
            Path.of(o.getValue("endpointConfigPath")),
            Path.of(o.getValue("endpointOutputPath")),
        )
    }
}

/** Top-level shim so a JavaExec task can resolve `ReplayEndpointTimingKt`. */
fun main(args: Array<String>) = ReplayEndpointTiming.main(args)
