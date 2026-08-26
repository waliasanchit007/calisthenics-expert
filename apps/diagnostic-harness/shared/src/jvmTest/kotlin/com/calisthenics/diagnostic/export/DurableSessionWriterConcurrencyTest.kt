package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableSessionWriterConcurrencyTest {
    @Test
    fun appendRacingSealIsSerializedBeforeFinalCountsAndPublication() {
        val storage = BlockingAppendStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        writer.open()
        val appendFailure = AtomicReference<Throwable?>(null)
        val sealingResult = AtomicReference<SealedArtifacts?>(null)
        val sealingFailure = AtomicReference<Throwable?>(null)

        val producer = Thread {
            try {
                writer.appendPose(frame())
            } catch (error: Throwable) {
                appendFailure.set(error)
            }
        }
        producer.start()
        assertTrue(storage.appendEntered.await(5, TimeUnit.SECONDS))

        val sealer = Thread {
            try {
                sealingResult.set(writer.seal(FinalSessionAggregates(counts = onePoseCounts())))
            } catch (error: Throwable) {
                sealingFailure.set(error)
            }
        }
        sealer.start()
        Thread.sleep(100)
        storage.releaseAppend.countDown()
        producer.join(5_000)
        sealer.join(5_000)

        assertNull(appendFailure.get())
        assertNull(sealingFailure.get())
        assertTrue(sealingResult.get() != null)
        assertEquals(DurableWriterState.PUBLISHED, writer.state)
        assertEquals(1L, sealingResult.get()!!.artifacts.single().reconciliation.recordCount)
    }

    private fun sessionId() = Uuid("00000000-0000-4000-8000-000000000043")

    private fun onePoseCounts() = Counts(
        cameraFramesReceived = 1,
        inferenceFramesSubmitted = 1,
        inferenceRecordsEmitted = 1,
        poseDetectedRecords = 1,
        framesSkippedBeforeInference = 0,
        framesSkippedBeforeInferenceByReason = emptyMap(),
        trailingFramesSkippedAfterLastEmittedRecord = 0,
        inferenceFailures = 0,
    )

    private fun frame() = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = sessionId(),
        frameIndex = 0,
        captureTimestampNs = 0,
        backendTimestampMs = 0,
        inferenceStartNs = 0,
        inferenceEndNs = 1,
        posePresent = true,
        normalizedImageLandmarks = listOf(Landmark(0, "nose", 0.5, 0.5, 0.0, null, null)),
        worldLandmarks = null,
        imageWidth = 256,
        imageHeight = 256,
        framesSkippedSinceLastEmittedRecord = 0,
    )
}

private class BlockingAppendStorage(
    private val delegate: FakeStorage = FakeStorage(),
) : StoragePort by delegate {
    val appendEntered = CountDownLatch(1)
    val releaseAppend = CountDownLatch(1)

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        if (artifact == DurableArtifact.POSE_STREAM) {
            appendEntered.countDown()
            check(releaseAppend.await(5, TimeUnit.SECONDS))
        }
        delegate.appendArtifact(attempt, artifact, bytes)
    }
}
