package com.calisthenics.diagnostic.live

import com.calisthenics.diagnostic.contract.CadenceSample
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveAchievedCadenceTest {
    @Test
    fun zeroOrOneRecordCannotClaimACompletedCadenceWindow() {
        assertEquals(emptyList(), achievedInferenceFpsTimeseriesOf(emptyList()))
        assertEquals(emptyList(), achievedInferenceFpsTimeseriesOf(listOf(frame(250_000_000L))))
    }

    @Test
    fun completedWindowsIncludeObservedStallsAndDropTrailingPartialWindow() {
        val samples = achievedInferenceFpsTimeseriesOf(
            listOf(
                frame(100_000_000L),
                frame(900_000_000L),
                frame(2_100_000_000L),
                frame(2_400_000_000L),
            ),
        )

        assertEquals(
            listOf(CadenceSample(1_000_000_000L, 2.0), CadenceSample(2_000_000_000L, 0.0)),
            samples,
        )
    }

    @Test
    fun totalsReconcileToEmittedRecordsAndValuesAreFiniteCanonicalCounts() {
        val records = (0 until 6).map { frame((it * 1_000_000_000L) + 1L) }
        val samples = achievedInferenceFpsTimeseriesOf(records)

        assertEquals(5L, samples.sumOf { it.achievedInferenceFps.toLong() })
        assertTrue(samples.all { it.achievedInferenceFps.isFinite() && it.achievedInferenceFps % 1.0 == 0.0 })
        assertTrue(samples.all { it.tMonotonicNs >= 1_000_000_000L })
    }

    @Test
    fun incrementalAccumulatorIsByteEquivalentToTheReleasedBatchSemantics() {
        val records = listOf(
            frame(100_000_000L),
            frame(900_000_000L),
            frame(2_100_000_000L),
            frame(2_400_000_000L),
        )
        val accumulator = IncrementalCadenceAccumulator()
        records.forEach { accumulator.observe(it.captureTimestampNs) }

        assertEquals(achievedInferenceFpsTimeseriesOf(records), accumulator.completedSamples())
        assertEquals(0, accumulator.retainedRawTimestampCount)
    }

    @Test
    fun inputCardinalityDoesNotChangeRetainedStateForTheSameObservedDuration() {
        fun accumulate(count: Int): IncrementalCadenceAccumulator = IncrementalCadenceAccumulator().also { accumulator ->
            repeat(count) { index -> accumulator.observe(index.toLong() * 10_000_000_000L / count) }
        }

        val thousand = accumulate(1_000)
        val hundredThousand = accumulate(100_000)

        assertEquals(thousand.completedSamples().size, hundredThousand.completedSamples().size)
        assertEquals(0, thousand.retainedRawTimestampCount)
        assertEquals(0, hundredThousand.retainedRawTimestampCount)
    }

    private fun frame(timestampNs: Long) = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("00000000-0000-4000-8000-000000000001"),
        frameIndex = timestampNs,
        captureTimestampNs = timestampNs,
        backendTimestampMs = null,
        inferenceStartNs = timestampNs,
        inferenceEndNs = timestampNs,
        posePresent = false,
        normalizedImageLandmarks = null,
        worldLandmarks = null,
        imageWidth = 2,
        imageHeight = 2,
        framesSkippedSinceLastEmittedRecord = 0,
    )
}
