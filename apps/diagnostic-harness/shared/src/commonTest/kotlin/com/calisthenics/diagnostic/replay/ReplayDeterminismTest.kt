package com.calisthenics.diagnostic.replay

import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.ModelProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WIDTH = 4
private const val HEIGHT = 4
private const val TARGET = 4

// Frames 1 and 2 share millisecond 33, exercising the `prev + 1` collision branch, mirroring the
// committed fixture timelines.
private val TIMELINE = listOf(0L, 33_333_333L, 33_333_500L, 66_666_666L)

private fun plane(seed: Int): ByteArray =
    ByteArray(WIDTH * HEIGHT * 3) { index -> ((index * 7 + seed * 31) % 251).toByte() }

private fun fixture(): ReplayFixture = ReplayFixture(
    frames = TIMELINE.mapIndexed { index, captureNs ->
        val bytes = plane(index)
        ReplayFixtureFrame(index.toLong(), captureNs, bytes, Sha256Digest.hex(bytes))
    },
    frameWidth = WIDTH,
    frameHeight = HEIGHT,
)

private val PROVENANCE = ModelProvenance(
    assetName = "pose_landmarker_full.task",
    sha256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1",
    byteCount = 9_398_198L,
    sourceUrl = "https://example.invalid/pose_landmarker_full.task",
    modelFamily = "pose_landmarker",
    modelVariant = "full",
    quantization = "float16",
    upstreamRevision = "1",
    tasksVisionVersion = "1.0.0",
    runningMode = "VIDEO",
    delegate = "CPU",
    taskOptions = mapOf("num_poses" to 1, "output_segmentation_masks" to false),
)

private class StepClock : ReplayClock {
    private var current = 0L
    override fun nowNs(): Long = current
    override fun waitUntil(deadlineNs: Long) {
        if (deadlineNs > current) current = deadlineNs
    }
    fun advance(deltaNs: Long) { current += deltaNs }
}

private fun runOnce(
    provenance: ModelProvenance = PROVENANCE,
    target: Int = TARGET,
    perFrameCostNs: Long = 5_000L,
): ReplayRunReport {
    val clock = StepClock()
    val runner = ReplayRunner(clock, provenance, target, target) { _, _ -> clock.advance(perFrameCostNs) }
    return runner.run(fixture())
}

class ReplayDeterminismTest {

    // Check 1 -------------------------------------------------------------------------------
    @Test
    fun check1_submittedIndicesEqualTheFixturesDeclaredIndices() {
        val declared = fixture().declaredIndices
        val report = runOnce()

        // The oracle is the fixture's declared list, not a second run: two runs that both drop
        // the same frame would agree with each other and prove nothing.
        assertEquals(declared, report.submittedIndices)
    }

    @Test
    fun check1_survivesProcessingThatRunsFarBehindTheFixtureInterval() {
        val report = runOnce(perFrameCostNs = 5_000_000L)
        assertEquals(fixture().declaredIndices, report.submittedIndices)
    }

    // Check 2 -------------------------------------------------------------------------------
    @Test
    fun check2_canonicalPlaneDigestsMatchAnAbsoluteGoldenAndRepeatAcrossRuns() {
        val golden = fixture().frames.map { it.declaredSha256 }
        val first = runOnce()
        val second = runOnce(perFrameCostNs = 900_000L)

        // Target equals source here, so canonical preprocessing is identity and the canonical
        // digest must equal the fixture's declared plane digest. Absolute, not merely stable.
        assertEquals(golden, first.submitted.map { it.canonicalInputSha256 })
        assertEquals(
            first.submitted.map { it.canonicalInputSha256 },
            second.submitted.map { it.canonicalInputSha256 },
        )
    }

    @Test
    fun check2_digestsAreNotDegenerate() {
        val digests = runOnce().submitted.map { it.canonicalInputSha256 }
        // An all-zero or reused buffer would hash identically every frame and pass a naive
        // cross-run comparison.
        assertEquals(digests.size, digests.toSet().size)
    }

    // Check 3 -------------------------------------------------------------------------------
    @Test
    fun check3_backendTimestampsAndGeometryRepeatAcrossRuns() {
        val first = runOnce()
        val second = runOnce(perFrameCostNs = 12_345L)

        assertEquals(first.submitted.map { it.backendTimestampMs }, second.submitted.map { it.backendTimestampMs })
        assertEquals(first.submitted.map { it.rebasedCaptureNs }, second.submitted.map { it.rebasedCaptureNs })
        assertEquals(first.geometry, second.geometry)
    }

    @Test
    fun check3_backendTimestampsSatisfyTheShippedValidatorRule() {
        val submitted = runOnce().submitted

        // FRAME.BACKEND_TIMESTAMP_ORDER: strictly increasing, and each >= floor(capture / 10^6).
        submitted.zipWithNext { previous, next ->
            assertTrue(next.backendTimestampMs > previous.backendTimestampMs, "not strictly increasing: $submitted")
        }
        submitted.forEach {
            assertTrue(
                it.backendTimestampMs >= it.rebasedCaptureNs / 1_000_000L,
                "backend ${it.backendTimestampMs} below floor for capture ${it.rebasedCaptureNs}",
            )
        }
    }

    @Test
    fun check3_theCollisionPairTakesThePreviousPlusOneBranch() {
        val submitted = runOnce().submitted
        // Frames 1 and 2 both floor to 33 ms; the second must be lifted to 34.
        assertEquals(listOf(0L, 33L, 34L, 66L), submitted.map { it.backendTimestampMs })
    }

    @Test
    fun check3_captureTimestampsArePinnedToTheZeroTimeBase() {
        // Rebased capture is now inside the equality set, which requires t0 = 0.
        assertEquals(TIMELINE, runOnce().submitted.map { it.rebasedCaptureNs })
    }

    // Check 4 -------------------------------------------------------------------------------
    @Test
    fun check4_recordedProvenanceIsWhatWasHandedToTheBackend() {
        val recorded = runOnce().recordedProvenance

        assertEquals(PROVENANCE.sha256, recorded.modelSha256)
        assertEquals("1.0.0", recorded.tasksVisionVersion)
        assertEquals("VIDEO", recorded.runningMode)
        assertEquals("CPU", recorded.delegate)
        assertEquals(PROVENANCE.taskOptions, recorded.taskOptions)
    }

    @Test
    fun check4_aGpuDelegateIsRecordedRatherThanSilentlyPresentedAsCpu() {
        val recorded = runOnce(provenance = PROVENANCE.copy(delegate = "GPU")).recordedProvenance
        assertEquals("GPU", recorded.delegate)
    }
}
