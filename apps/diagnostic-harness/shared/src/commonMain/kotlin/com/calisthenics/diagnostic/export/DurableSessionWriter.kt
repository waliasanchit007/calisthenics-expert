package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.contract.encodeFrameLine
import com.calisthenics.diagnostic.hash.Sha256Digest

/**
 * Bounded writer for the pose and telemetry streams of one session attempt.
 *
 * The writer never retains pose records. A sealed result is a handoff of staged, reconciled
 * artifacts to the final publication coordinator; this class does not own manifest construction.
 */
interface DurableSessionWriter {
    val state: DurableWriterState
    fun open()
    fun appendPose(record: RawPoseFrame)
    fun appendTelemetry(encodedRecord: String)
    fun seal(finalAggregates: FinalSessionAggregates): SealedArtifacts
    fun abort(reason: String, counts: Counts)
}

class DefaultDurableSessionWriter(
    private val storage: StoragePort,
    private val sessionId: Uuid,
    private val monotonicNowNs: () -> Long = { 0L },
) : DurableSessionWriter {
    override var state: DurableWriterState = DurableWriterState.NEW
        private set

    var lastDurableRecordIndex: Long = -1L
        private set

    var lastAbortReason: String? = null
        private set

    private var attempt: StagingAttempt? = null
    private var poseCount = 0L
    private var posePresentCount = 0L
    private var durablePoseCount = 0L
    private var openedAtNs = 0L
    private var lastDurableAtNs = 0L
    private var digestState = Sha256State(0L, "0".repeat(64))
    private var telemetryWritten = false

    override fun open() {
        requireState(DurableWriterState.NEW)
        try {
            attempt = storage.createStagingAttempt(sessionId)
            openedAtNs = monotonicNowNs()
            lastDurableAtNs = openedAtNs
            state = DurableWriterState.OPEN
        } catch (error: Throwable) {
            state = DurableWriterState.FAILED
            throw storageFailure("createStagingAttempt", error)
        }
    }

    override fun appendPose(record: RawPoseFrame) {
        requireState(DurableWriterState.OPEN)
        require(record.sessionId == sessionId) { "pose record belongs to another session" }
        val encoded = encodeFrameLine(record)
        val bytes = encoded.encodeToByteArray()
        val currentAttempt = requireNotNull(attempt)
        try {
            storage.appendArtifact(currentAttempt, DurableArtifact.POSE_STREAM, bytes)
            poseCount++
            if (record.posePresent) posePresentCount++
            digestState = nextDigestState(digestState, bytes)
            val now = monotonicNowNs()
            if (poseCount - durablePoseCount >= POSE_FLUSH_THRESHOLD || now - lastDurableAtNs >= CLOCK_FLUSH_NS) {
                flushPose(currentAttempt, now)
            }
        } catch (error: DurableStorageException) {
            state = DurableWriterState.FAILED
            throw error
        } catch (error: Throwable) {
            state = DurableWriterState.FAILED
            throw storageFailure("appendArtifact:POSE_STREAM", error)
        }
    }

    override fun appendTelemetry(encodedRecord: String) {
        requireState(DurableWriterState.OPEN)
        val currentAttempt = requireNotNull(attempt)
        require(!encodedRecord.contains('\r') && encodedRecord.endsWith('\n')) {
            "telemetry records must be LF-terminated and single-line"
        }
        try {
            storage.appendArtifact(currentAttempt, DurableArtifact.TELEMETRY, encodedRecord.encodeToByteArray())
            telemetryWritten = true
        } catch (error: Throwable) {
            state = DurableWriterState.FAILED
            throw storageFailure("appendArtifact:TELEMETRY", error)
        }
    }

    override fun seal(finalAggregates: FinalSessionAggregates): SealedArtifacts {
        requireState(DurableWriterState.OPEN)
        state = DurableWriterState.SEALING
        val currentAttempt = requireNotNull(attempt)
        try {
            flushPose(currentAttempt, monotonicNowNs())
            if (telemetryWritten) {
                storage.flushArtifact(currentAttempt, DurableArtifact.TELEMETRY)
                storage.fsyncArtifact(currentAttempt, DurableArtifact.TELEMETRY)
            }
            if (finalAggregates.counts.inferenceRecordsEmitted != poseCount) {
                throw DurableReconciliationException("final emitted count does not match durable pose count")
            }
            if (finalAggregates.counts.poseDetectedRecords != posePresentCount) {
                throw DurableReconciliationException("final pose-present count does not match durable pose count")
            }
            val finalCheckpoint = checkpoint(finalAggregates.counts)
            storage.replaceCheckpoint(currentAttempt, encodeCheckpoint(finalCheckpoint))
            val reconciled = buildList {
                add(SealedArtifact(DurableArtifact.POSE_STREAM, storage.reconcileArtifact(currentAttempt, DurableArtifact.POSE_STREAM)))
                if (telemetryWritten) {
                    add(SealedArtifact(DurableArtifact.TELEMETRY, storage.reconcileArtifact(currentAttempt, DurableArtifact.TELEMETRY)))
                }
            }
            val poseReconciliation = reconciled.first().reconciliation
            if (poseReconciliation.recordCount != poseCount) {
                throw DurableReconciliationException("reconciled pose count does not match durable pose count")
            }
            if (poseReconciliation.posePresentCount != posePresentCount) {
                throw DurableReconciliationException("reconciled pose-present count does not match durable pose count")
            }
            state = DurableWriterState.PUBLISHED
            return SealedArtifacts(currentAttempt, reconciled, finalCheckpoint)
        } catch (error: DurableReconciliationException) {
            state = DurableWriterState.FAILED
            throw error
        } catch (error: DurableWriterStateException) {
            state = DurableWriterState.FAILED
            throw error
        } catch (error: DurableStorageException) {
            state = DurableWriterState.FAILED
            throw error
        } catch (error: Throwable) {
            state = DurableWriterState.FAILED
            throw storageFailure("seal", error)
        }
    }

    override fun abort(reason: String, counts: Counts) {
        requireState(DurableWriterState.OPEN, DurableWriterState.SEALING, DurableWriterState.FAILED)
        val currentAttempt = requireNotNull(attempt)
        val safeReason = reason.replace('\r', ' ').replace('\n', ' ').take(MAX_REASON_LENGTH)
        try {
            storage.writeAbortMarker(currentAttempt, safeReason, counts)
            storage.fsyncFile(currentAttempt, StorageFile.ABORT_MARKER)
            lastAbortReason = safeReason
            state = DurableWriterState.ABORTED
        } catch (error: Throwable) {
            state = DurableWriterState.FAILED
            throw storageFailure("abort", error)
        }
    }

    private fun flushPose(currentAttempt: StagingAttempt, nowNs: Long) {
        if (poseCount == durablePoseCount) return
        storage.flushArtifact(currentAttempt, DurableArtifact.POSE_STREAM)
        storage.fsyncArtifact(currentAttempt, DurableArtifact.POSE_STREAM)
        durablePoseCount = poseCount
        lastDurableRecordIndex = poseCount - 1
        lastDurableAtNs = maxOf(lastDurableAtNs, nowNs)
        val checkpoint = checkpoint(currentCounts())
        storage.replaceCheckpoint(currentAttempt, encodeCheckpoint(checkpoint))
    }

    private fun checkpoint(counts: Counts) = DurableCheckpoint(
        lastDurableRecordIndex = lastDurableRecordIndex,
        counts = counts,
        posePresentCount = posePresentCount,
        digestState = digestState,
    )

    private fun currentCounts() = Counts(
        cameraFramesReceived = poseCount,
        inferenceFramesSubmitted = poseCount,
        inferenceRecordsEmitted = poseCount,
        poseDetectedRecords = posePresentCount,
        framesSkippedBeforeInference = 0,
        framesSkippedBeforeInferenceByReason = emptyMap(),
        trailingFramesSkippedAfterLastEmittedRecord = 0,
        inferenceFailures = 0,
    )

    private fun encodeCheckpoint(value: DurableCheckpoint): String = buildString {
        append(value.lastDurableRecordIndex)
        append('|')
        append(value.counts.inferenceRecordsEmitted)
        append('|')
        append(value.posePresentCount)
        append('|')
        append(value.digestState.byteLength)
        append('|')
        append(value.digestState.digestHex)
        append('\n')
    }

    private fun nextDigestState(previous: Sha256State, bytes: ByteArray): Sha256State {
        val input = (previous.digestHex + ':').encodeToByteArray() + bytes
        return Sha256State(previous.byteLength + bytes.size, Sha256Digest.hex(input))
    }

    private fun requireState(vararg allowed: DurableWriterState) {
        if (state !in allowed) throw DurableWriterStateException("writer is $state; expected ${allowed.joinToString()}")
    }

    private fun storageFailure(operation: String, cause: Throwable) = DurableStorageException(operation, cause)

    private companion object {
        const val POSE_FLUSH_THRESHOLD = 150L
        const val CLOCK_FLUSH_NS = 5_000_000_000L
        const val MAX_REASON_LENGTH = 256
    }
}
