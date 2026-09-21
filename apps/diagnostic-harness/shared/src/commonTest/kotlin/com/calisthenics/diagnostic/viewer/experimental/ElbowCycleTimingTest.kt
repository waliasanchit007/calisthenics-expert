package com.calisthenics.diagnostic.viewer.experimental

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shared cycle machine reports WHEN a cycle completed, not when it was confirmed.
 *
 * Confirmation needs the endpoint held for `minimumEndpointHoldNs`, which at the 30 fps reference
 * rate is `minimumEndpointSamples - 1` intervals. Reporting the confirming frame
 * made every event systematically late by exactly that much, in five engines at once, purely as a
 * debouncing artefact. These tests pin the corrected instant as a property of the machine, derived
 * from its own definition — no recording and no label is involved.
 */
/**
 * One frame interval, a hair under 30 fps. The machine gates on ELAPSED TIME, so a synthetic clock
 * has to tick at a rate a camera could actually produce; these fixtures used to tick every 100 ns.
 */
private const val FRAME_NS = 33_333_334L

class ElbowCycleTimingTest {
    private fun run(samples: Int, sequence: List<Pair<Boolean, Boolean>>, frameNs: Long = FRAME_NS): List<Long?> {
        val timing = ElbowCycleTiming(samples)
        return sequence.mapIndexed { index, (supported, lowered) ->
            timing.onObservation(index * frameNs, supported, lowered)
        }
    }

    // A full cycle: settle at support, descend, sit at the bottom, return to support.
    private fun cycle(dwell: Int) = buildList {
        repeat(dwell) { add(true to false) }   // supported
        add(false to false)                    // lowering
        repeat(dwell) { add(false to true) }   // lowered
        add(false to false)                    // raising
        repeat(dwell) { add(true to false) }   // back to support
    }

    @Test
    fun theEventIsTheFirstEndpointFrameNotTheConfirmingFrame() {
        for (samples in 2..5) {
            val events = run(samples, cycle(samples))
            val fired = events.withIndex().filter { it.value != null }
            assertEquals(1, fired.size, "exactly one cycle for samples=$samples")
            val (confirmingIndex, eventNs) = fired.single()
            // The return run starts immediately after the single raising frame.
            val firstReturnIndex = samples + 1 + samples + 1
            assertEquals(firstReturnIndex * FRAME_NS, assertNotNull(eventNs), "samples=$samples")
            // And the lateness it replaces is exactly the debounce width.
            assertEquals((samples - 1) * FRAME_NS, confirmingIndex * FRAME_NS - eventNs, "samples=$samples")
        }
    }

    @Test
    fun aRunBrokenBeforeConfirmationRestartsTheEventClock() {
        // Two support frames, a break, then a full run: the event is the start of the SECOND run.
        val sequence = cycle(3).dropLast(3) + listOf(true to false, false to false) +
            List(3) { true to false }
        val events = run(3, sequence)
        val fired = events.withIndex().single { it.value != null }
        val firstOfFinalRun = sequence.indices.last - 2
        assertEquals(firstOfFinalRun * FRAME_NS, assertNotNull(fired.value))
    }

    @Test
    fun invalidationClearsThePendingEventClock() {
        val timing = ElbowCycleTiming(2)
        cycle(2).dropLast(1).forEachIndexed { index, (s, l) -> timing.onObservation(index * FRAME_NS, s, l) }
        timing.invalidate()
        // Re-entering from UNCERTAIN must not resurrect a stale endpoint start.
        assertNull(timing.onObservation(10 * FRAME_NS, true, false))
        assertNull(timing.onObservation(11 * FRAME_NS, true, false))
        assertEquals(0, timing.count)
    }

    @Test
    fun countsAreUnchangedByTheCorrection() {
        // Three consecutive cycles still count three times, and each event precedes its confirmation.
        val sequence = cycle(2) + cycle(2).drop(2) + cycle(2).drop(2)
        val events = run(2, sequence)
        val fired = events.withIndex().filter { it.value != null }
        assertEquals(3, fired.size)
        fired.forEach { (index, eventNs) -> assertEquals(FRAME_NS, index * FRAME_NS - assertNotNull(eventNs)) }
    }
}
