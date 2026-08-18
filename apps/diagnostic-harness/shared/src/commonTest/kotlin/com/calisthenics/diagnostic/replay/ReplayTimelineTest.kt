package com.calisthenics.diagnostic.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun frames(vararg captureNs: Long): List<FixtureFrame> =
    captureNs.mapIndexed { index, ns -> FixtureFrame(index.toLong(), ns) }

class ReplayTimelineTest {
    @Test
    fun rebasesFirstFrameOntoSessionStartAndPreservesOffsets() {
        val timeline = ReplayTimeline(frames(1_000L, 34_000L, 67_000L), sessionStartNs = 500_000L)

        assertEquals(listOf(500_000L, 533_000L, 566_000L), timeline.rebased().map { it.rebasedCaptureNs })
        assertEquals(listOf(0L, 1L, 2L), timeline.rebased().map { it.frameIndex })
    }

    @Test
    fun rejectsAnEmptyFixture() {
        assertFailsWith<ReplayTimelineException> { ReplayTimeline(emptyList(), 0L) }
    }

    @Test
    fun rejectsNonMonotonicFixtureCapture() {
        assertFailsWith<ReplayTimelineException> { ReplayTimeline(frames(10L, 10L), 0L) }
        assertFailsWith<ReplayTimelineException> { ReplayTimeline(frames(20L, 10L), 0L) }
    }

    @Test
    fun rejectsNonContiguousFrameIndices() {
        val gapped = listOf(FixtureFrame(0L, 10L), FixtureFrame(2L, 20L))
        assertFailsWith<ReplayTimelineException> { ReplayTimeline(gapped, 0L) }
    }

    @Test
    fun pacerSubmitsEveryFrameInOrderWhenOnTime() {
        val clock = FakeReplayClock(startNs = 0L)
        val pacer = ReplayPacer(clock)
        val submitted = mutableListOf<Long>()

        pacer.pace(ReplayTimeline(frames(0L, 10L, 20L), sessionStartNs = 0L)) { paced ->
            submitted += paced.frameIndex
            PacedOutcome(inferenceStartNs = clock.nowNs())
        }

        assertEquals(listOf(0L, 1L, 2L), submitted)
    }

    @Test
    fun pacerNeverDropsWhenProcessingRunsLate() {
        val clock = FakeReplayClock(startNs = 0L)
        val pacer = ReplayPacer(clock)
        val submitted = mutableListOf<Long>()

        // Each frame costs far more than the fixture interval: the pacer must fall behind, not drop.
        val timeline = ReplayTimeline(frames(0L, 10L, 20L, 30L), sessionStartNs = 0L)
        val overrunAtEntry = mutableListOf<Boolean>()
        pacer.pace(timeline) { paced ->
            overrunAtEntry += clock.overran(paced.rebasedCaptureNs)
            val start = clock.nowNs()
            clock.advance(1_000L)
            submitted += paced.frameIndex
            PacedOutcome(inferenceStartNs = start)
        }

        assertEquals(listOf(0L, 1L, 2L, 3L), submitted)
        // Frames 1..3 must be reached with the clock already past their instant; without this the
        // "never drops" claim is vacuous because a drop-filter would never have anything to drop.
        assertEquals(listOf(false, true, true, true), overrunAtEntry)
    }

    @Test
    fun inferenceStartIsNeverBeforeTheRebasedCaptureInstant() {
        val clock = FakeReplayClock(startNs = 0L)
        val pacer = ReplayPacer(clock)
        val observed = mutableListOf<Pair<Long, Long>>()

        pacer.pace(ReplayTimeline(frames(0L, 10L, 20L, 30L), sessionStartNs = 5L)) { paced ->
            val start = clock.nowNs()
            clock.advance(7L)
            observed += paced.rebasedCaptureNs to start
            PacedOutcome(inferenceStartNs = start)
        }

        assertTrue(observed.all { (capture, start) -> start >= capture }, "observed=$observed")
    }

    @Test
    fun pacerRejectsAnInferenceStartBeforeTheRebasedCapture() {
        val clock = FakeReplayClock(startNs = 0L)
        val pacer = ReplayPacer(clock)

        val error = assertFailsWith<ReplayTimelineException> {
            pacer.pace(ReplayTimeline(frames(0L, 10L), sessionStartNs = 100L)) { paced ->
                // Deliberately report a start instant before the frame's rebased capture.
                PacedOutcome(inferenceStartNs = paced.rebasedCaptureNs - 1L)
            }
        }
        assertTrue(error.message!!.contains("precedes rebased capture"), error.message!!)
    }

    @Test
    fun pacerWaitsRatherThanBusyRunningWhenAhead() {
        val clock = FakeReplayClock(startNs = 0L)
        val pacer = ReplayPacer(clock)

        pacer.pace(ReplayTimeline(frames(0L, 1_000L), sessionStartNs = 0L)) { paced ->
            PacedOutcome(inferenceStartNs = clock.nowNs())
        }

        assertEquals(listOf(0L, 1_000L), clock.waitedUntil)
    }
}

private class FakeReplayClock(startNs: Long) : ReplayClock {
    private var current = startNs
    val waitedUntil = mutableListOf<Long>()

    override fun nowNs(): Long = current

    override fun waitUntil(deadlineNs: Long) {
        waitedUntil += deadlineNs
        // A real monotonic clock cannot travel backwards to meet a deadline it has already
        // overrun. Modelling that is what lets "never drops when late" actually fail.
        if (deadlineNs > current) current = deadlineNs
    }

    fun overran(deadlineNs: Long): Boolean = current > deadlineNs

    fun advance(deltaNs: Long) {
        current += deltaNs
    }
}
