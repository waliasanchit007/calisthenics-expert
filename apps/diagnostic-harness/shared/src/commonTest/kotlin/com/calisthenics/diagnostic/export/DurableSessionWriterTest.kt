package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DurableSessionWriterTest {
    @Test
    fun writerStartsNewAndMustBeOpenedBeforeAcceptingPose() {
        val writer = DefaultDurableSessionWriter(FakeStorage(), sessionId())

        assertEquals(DurableWriterState.NEW, writer.state)
        assertFailsWith<DurableWriterStateException> { writer.appendPose(frame(0, 0)) }

        writer.open()

        assertEquals(DurableWriterState.OPEN, writer.state)
    }

    @Test
    fun oneHundredFiftyPoseRecordsTriggerExactlyOneFlushAndCheckpoint() {
        val storage = RecordingStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        writer.open()

        repeat(150) { index -> writer.appendPose(frame(index.toLong(), index.toLong() * 1_000_000L)) }

        assertEquals(1, storage.events.count { it == "flush:POSE_STREAM" })
        assertEquals(1, storage.events.count { it == "fsync:POSE_STREAM" })
        assertEquals(1, storage.events.count { it == "checkpoint:parent-fsync" })
        assertEquals(149L, writer.lastDurableRecordIndex)
    }

    @Test
    fun fiveSessionSecondsTriggerFlushBeforePoseCountThreshold() {
        var nowNs = 0L
        val storage = RecordingStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId(), monotonicNowNs = { nowNs })
        writer.open()

        writer.appendPose(frame(0, 0))
        nowNs = 5_000_000_000L
        writer.appendPose(frame(1, nowNs))

        assertEquals(1, storage.events.count { it == "flush:POSE_STREAM" })
        assertEquals(1, storage.events.count { it == "fsync:POSE_STREAM" })
        assertEquals(1L, writer.lastDurableRecordIndex)
    }

    @Test
    fun telemetryUsesTheSameBoundedDurabilityClockAndPersistsItsCheckpointProgress() {
        var nowNs = 0L
        val storage = RecordingStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId(), monotonicNowNs = { nowNs })
        writer.open()

        writer.appendTelemetry(telemetry(sequence = 0, timeNs = 0))
        nowNs = 5_000_000_000L
        writer.appendTelemetry(telemetry(sequence = 1, timeNs = nowNs))

        assertEquals(1, storage.events.count { it == "flush:TELEMETRY" })
        assertEquals(1, storage.events.count { it == "fsync:TELEMETRY" })
        assertEquals(1, storage.events.count { it == "checkpoint:parent-fsync" })
        assertTrue(storage.delegate.attempts.single().checkpoint!!.contains("|1|2\n"))
        assertEquals(
            telemetry(sequence = 0, timeNs = 0) + telemetry(sequence = 1, timeNs = nowNs),
            storage.delegate.attempts.single().durableArtifactBytes.getValue(DurableArtifact.TELEMETRY).decodeToString(),
        )
    }

    @Test
    fun checkpointReplacementIsTempWriteFsyncRenameAndParentFsync() {
        val storage = RecordingStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        writer.open()

        repeat(150) { index -> writer.appendPose(frame(index.toLong(), index.toLong())) }

        assertEquals(
            listOf(
                "checkpoint:temp-write",
                "checkpoint:fsync",
                "checkpoint:rename",
                "checkpoint:parent-fsync",
            ),
            storage.events.filter { it.startsWith("checkpoint:") },
        )
    }

    @Test
    fun sealRejectsEveryProducerAppendAndPublishesFinalArtifacts() {
        val storage = RecordingStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        writer.open()
        writer.appendPose(frame(0, 0))

        val sealed = writer.seal(FinalSessionAggregates(counts = counts(emitted = 1, posePresent = 1)))

        assertEquals(DurableWriterState.PUBLISHED, writer.state)
        assertIs<SealedArtifacts>(sealed)
        assertFailsWith<DurableWriterStateException> { writer.appendPose(frame(1, 1)) }
        assertFailsWith<DurableWriterStateException> { writer.appendTelemetry("late\n") }
    }

    @Test
    fun storageFailureIsTypedAndLeavesWriterFailedWithoutRetryLoop() {
        val storage = RecordingStorage(failOn = "append:POSE_STREAM")
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        writer.open()

        val failure = assertFailsWith<DurableStorageException> { writer.appendPose(frame(0, 0)) }

        assertEquals(DurableWriterState.FAILED, writer.state)
        assertEquals("appendArtifact:POSE_STREAM", failure.operation)
        assertEquals(1, storage.events.count { it == "append:POSE_STREAM" })
    }

    @Test
    fun independent_reread_rejects_pose_present_counter_corruption() {
        val delegate = FakeStorage()
        val storage = object : StoragePort by delegate {
            override fun reconcileArtifact(
                attempt: StagingAttempt,
                artifact: DurableArtifact,
            ): ArtifactReconciliation {
                val observed = delegate.reconcileArtifact(attempt, artifact)
                return if (artifact == DurableArtifact.POSE_STREAM) {
                    observed.copy(posePresentCount = 0)
                } else {
                    observed
                }
            }
        }
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        writer.open()
        writer.appendPose(frame(0, 0))

        assertFailsWith<DurableReconciliationException> {
            writer.seal(FinalSessionAggregates(counts = counts(emitted = 1, posePresent = 1)))
        }
        assertEquals(DurableWriterState.FAILED, writer.state)
    }

    @Test
    fun telemetryRereadRejectsDuplicateGapRegressingTimestampTruncatedLineAndCount() {
        val mutations = listOf(
            "duplicate" to "{\"sequence\":0,\"t_session_ns\":0}\n{\"sequence\":0,\"t_session_ns\":1}\n",
            "gap" to "{\"sequence\":0,\"t_session_ns\":0}\n{\"sequence\":2,\"t_session_ns\":1}\n",
            "regressing timestamp" to "{\"sequence\":0,\"t_session_ns\":1}\n{\"sequence\":1,\"t_session_ns\":0}\n",
            "truncated line" to "{\"sequence\":0,\"t_session_ns\":0}",
            "count" to "{\"sequence\":0,\"t_session_ns\":0}\n",
        )

        mutations.forEach { (name, corrupted) ->
            val storage = FakeStorage()
            val writer = DefaultDurableSessionWriter(storage, sessionId())
            writer.open()
            writer.appendTelemetry(telemetry(sequence = 0, timeNs = 0))
            writer.appendTelemetry(telemetry(sequence = 1, timeNs = 1))
            storage.attempts.single().artifactBytes[DurableArtifact.TELEMETRY] = corrupted.encodeToByteArray()

            assertFailsWith<DurableReconciliationException>(name) {
                writer.seal(FinalSessionAggregates(counts = counts(emitted = 0, posePresent = 0)))
            }
            assertEquals(DurableWriterState.FAILED, writer.state, name)
        }
    }

    @Test
    fun abortIsTerminalAndCarriesSanitizedReasonAndCounts() {
        val writer = DefaultDurableSessionWriter(RecordingStorage(), sessionId())
        writer.open()

        writer.abort("storage path must not be disclosed", counts(emitted = 0, posePresent = 0))

        assertEquals(DurableWriterState.ABORTED, writer.state)
        assertTrue(writer.lastAbortReason!!.contains("storage path"))
        assertFailsWith<DurableWriterStateException> { writer.open() }
    }

    private fun sessionId() = Uuid("00000000-0000-4000-8000-000000000042")

    private fun counts(emitted: Long, posePresent: Long): Counts = Counts(
        cameraFramesReceived = emitted,
        inferenceFramesSubmitted = emitted,
        inferenceRecordsEmitted = emitted,
        poseDetectedRecords = posePresent,
        framesSkippedBeforeInference = 0,
        framesSkippedBeforeInferenceByReason = emptyMap(),
        trailingFramesSkippedAfterLastEmittedRecord = 0,
        inferenceFailures = 0,
    )

    private fun frame(index: Long, timestampNs: Long): RawPoseFrame = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = sessionId(),
        frameIndex = index,
        captureTimestampNs = timestampNs,
        backendTimestampMs = timestampNs / 1_000_000L,
        inferenceStartNs = timestampNs,
        inferenceEndNs = timestampNs + 1,
        posePresent = true,
        normalizedImageLandmarks = listOf(Landmark(0, "nose", 0.5, 0.5, 0.0, null, null)),
        worldLandmarks = null,
        imageWidth = 256,
        imageHeight = 256,
        framesSkippedSinceLastEmittedRecord = 0,
    )

    private fun telemetry(sequence: Long, timeNs: Long): String =
        "{\"sequence\":$sequence,\"t_session_ns\":$timeNs}\n"
}

private class RecordingStorage(
    private val failOn: String? = null,
    val delegate: FakeStorage = FakeStorage(),
) : StoragePort by delegate {
    val events = mutableListOf<String>()

    fun record(operation: String) {
        events += operation
        if (operation == failOn) throw IllegalStateException("injected storage failure")
    }

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        record("append:$artifact")
        delegate.appendArtifact(attempt, artifact, bytes)
    }

    override fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        record("flush:$artifact")
        delegate.flushArtifact(attempt, artifact)
    }

    override fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        record("fsync:$artifact")
        delegate.fsyncArtifact(attempt, artifact)
    }

    override fun replaceCheckpoint(attempt: StagingAttempt, encodedCheckpoint: String) {
        record("checkpoint:temp-write")
        record("checkpoint:fsync")
        record("checkpoint:rename")
        record("checkpoint:parent-fsync")
        delegate.replaceCheckpoint(attempt, encodedCheckpoint)
    }

    override fun reconcileArtifact(attempt: StagingAttempt, artifact: DurableArtifact): ArtifactReconciliation =
        delegate.reconcileArtifact(attempt, artifact)
}
