package com.calisthenics.diagnostic.viewer.experimental

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The adopted HSPU endpoint pass is a SECOND pass over an engine's existing prediction file. These
 * tests pin the properties that make that safe: it reads counts rather than deciding them, it never
 * lets one repetition's search reach into the next, and it refuses to overwrite anything.
 */
class ReplayEndpointTimingTest {
    private val configJson = """
        {
          "plateau_band_fraction":0.05,
          "admission_level":0.8,
          "reversal_samples":2,
          "signal_field":"span_ratio"
        }
    """.trimIndent()

    @Test
    fun parsesTheFrozenConfigStrictly() {
        val (config, field) = ReplayEndpointTiming.parseConfig(configJson)
        assertEquals(.05, config.plateauBandFraction)
        assertEquals(.80, config.admissionLevel)
        assertEquals(2, config.reversalSamples)
        assertEquals("span_ratio", field)
        // An unknown or missing key is a hard failure, never a silent default.
        assertFailsWith<IllegalArgumentException> {
            ReplayEndpointTiming.parseConfig(configJson.replace("\"reversal_samples\":2,", ""))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayEndpointTiming.parseConfig(configJson.replace("}", ",\"extra\":1}"))
        }
    }

    /** One frame per line, mimicking a real HSPU prediction file. */
    private fun row(ts: Long, ratio: Double?, counted: Boolean = false, event: Long? = null) = buildString {
        append("""{"timestamp_ns":$ts,"count":0,"counted":$counted,"reason":"NONE"""")
        append(""","span_ratio":${ratio ?: "null"}""")
        append(""","event_timestamp_ns":${event ?: "null"}}""")
    }

    /** Rise to a plateau, fall away, rise again: two repetitions back to back. */
    private fun twoRepFile(): List<String> {
        val out = mutableListOf<String>()
        val shape = listOf(.82, .90, .98, 1.0, 1.0, 1.0, .95, .70, .60)
        var t = 0L
        for (rep in 0 until 2) {
            shape.forEachIndexed { index, v ->
                // The count is confirmed one frame after the crossing, as the engine does.
                val counted = index == 1
                out += row(t, v, counted, if (counted) t - 100 else null)
                t += 100
            }
        }
        return out
    }

    private fun run(lines: List<String>, config: String = configJson): List<Map<String, String>> {
        val dir = Files.createTempDirectory("endpoint-replay")
        val input = dir.resolve("pred.jsonl")
        val cfg = dir.resolve("config.json")
        val out = dir.resolve("out.jsonl")
        Files.write(input, lines)
        Files.writeString(cfg, config)
        ReplayEndpointTiming.run(input, cfg, out)
        return Files.readAllLines(out).map { line ->
            Json.parseToJsonElement(line).jsonObject.mapValues { it.value.toString().trim('"') }
        }
    }

    @Test
    fun emitsOneEstimatePerCountedRepetitionAndNeverInventsOne() {
        val rows = run(twoRepFile())
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "2"), rows.map { it.getValue("rep") })
        assertTrue(rows.all { it.getValue("status") == "RESOLVED" })
    }

    /**
     * The search for repetition 1 must stop before repetition 2 begins. If it did not, the second
     * rise would be swept into the first plateau and the first endpoint would land in the wrong rep.
     */
    @Test
    fun oneRepetitionsSearchNeverReachesIntoTheNext() {
        val rows = run(twoRepFile())
        val firstDeparture = rows[0].getValue("estimated_departure_ns").toLong()
        val secondCrossing = rows[1].getValue("crossing_ns").toLong()
        assertTrue(firstDeparture < secondCrossing, "$firstDeparture must precede $secondCrossing")
        // Rep 1's plateau is frames 3..5 of the first block: 300..500ns.
        assertEquals(500L, firstDeparture)
    }

    /** The engine's own instants are carried through untouched, not recomputed. */
    @Test
    fun theEnginesCrossingAndConfirmationArePreserved() {
        val rows = run(twoRepFile())
        assertEquals(0L, rows[0].getValue("crossing_ns").toLong())
        assertEquals(100L, rows[0].getValue("confirmation_ns").toLong())
    }

    @Test
    fun aDropoutInsideTheExcursionIsReportedAsUnknownNotSkipped() {
        val lines = twoRepFile().toMutableList()
        lines[4] = row(400, null, counted = false) // unmeasurable frame on rep 1's plateau
        val rows = run(lines)
        assertEquals(2, rows.size) // the repetition is still listed; only its endpoint is withheld
        assertEquals("UNKNOWN_DROPOUT", rows[0].getValue("status"))
        assertEquals("null", rows[0].getValue("estimated_departure_ns"))
        assertEquals("RESOLVED", rows[1].getValue("status"))
    }

    @Test
    fun aCaptureGapInsideTheExcursionIsReportedAsADiscontinuity() {
        val lines = twoRepFile().toMutableList()
        lines[4] = lines[4].replace("\"reason\":\"NONE\"", "\"reason\":\"CAPTURE_GAP\"")
        assertEquals("UNKNOWN_DISCONTINUITY", run(lines)[0].getValue("status"))
    }

    @Test
    fun refusesToOverwriteAnExistingOutput() {
        val dir = Files.createTempDirectory("endpoint-replay-overwrite")
        val input = dir.resolve("pred.jsonl")
        val cfg = dir.resolve("config.json")
        val out = dir.resolve("out.jsonl")
        Files.write(input, twoRepFile())
        Files.writeString(cfg, configJson)
        Files.writeString(out, "existing")
        assertFailsWith<IllegalArgumentException> { ReplayEndpointTiming.run(input, cfg, out) }
        assertEquals("existing", Files.readString(out))
    }
}
