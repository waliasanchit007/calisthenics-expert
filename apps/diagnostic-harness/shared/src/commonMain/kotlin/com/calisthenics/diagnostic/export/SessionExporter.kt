package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.encodeManifest
import com.calisthenics.diagnostic.session.EmittedRecordSink
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SealedSessionSnapshot
import com.calisthenics.diagnostic.session.SessionAborter
import com.calisthenics.diagnostic.session.SessionFinalizer
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySample
import com.calisthenics.diagnostic.telemetry.PipelineStageEvent
import com.calisthenics.diagnostic.telemetry.TelemetryRecord
import com.calisthenics.diagnostic.telemetry.TelemetrySink

/** Composition-owned aborts call this only after callback and telemetry producers are fenced. */
fun interface DeferredSessionAbort {
    fun completePendingAbort()
}

/** Publishes one sealed session using the fixed crash-consistent commit sequence. */
class SessionExporter(
    private val storage: StoragePort,
    private val manifestTemplate: SessionManifest,
    private val manifestAtFinalization: (SessionManifest, SealedSessionSnapshot) -> SessionManifest = { manifest, _ -> manifest },
    private val writer: DurableSessionWriter = DefaultDurableSessionWriter(
        storage = storage,
        sessionId = manifestTemplate.sessionId,
    ),
    private val deferAbortUntilFence: Boolean = false,
) : SessionFinalizer, SessionAborter, EmittedRecordSink, TelemetrySink, DeferredSessionAbort {
    private var finalizationResult: FinalizationResult? = null
    private var pendingAbort: Pair<SealedSessionSnapshot, String>? = null
    private var nextTelemetrySequence = 0L
    private var lastTelemetryTimeNs = 0L

    init {
        writer.open()
    }

    override fun appendPose(record: com.calisthenics.diagnostic.contract.RawPoseFrame) {
        writer.appendPose(record)
    }

    @Synchronized
    override fun append(record: TelemetryRecord) {
        val recordTimeNs = when (record) {
            is FixedTelemetrySample -> record.tSessionNs
            is PipelineStageEvent -> record.tSessionNs
        }
        val orderedTimeNs = maxOf(lastTelemetryTimeNs, recordTimeNs)
        val orderedRecord = when (record) {
            is FixedTelemetrySample -> record.copy(
                sequence = nextTelemetrySequence,
                tSessionNs = orderedTimeNs,
            )
            is PipelineStageEvent -> record.copy(
                sequence = nextTelemetrySequence,
                tSessionNs = orderedTimeNs,
            )
        }
        writer.appendTelemetry(orderedRecord.encodeNdjson())
        nextTelemetrySequence += 1L
        lastTelemetryTimeNs = orderedTimeNs
    }

    @Synchronized
    override fun abort(snapshot: SealedSessionSnapshot, reason: String) {
        if (deferAbortUntilFence) {
            if (pendingAbort == null) pendingAbort = snapshot to reason
            return
        }
        abortNow(snapshot, reason)
    }

    @Synchronized
    override fun completePendingAbort() {
        val pending = pendingAbort ?: return
        pendingAbort = null
        abortNow(pending.first, pending.second)
    }

    private fun abortNow(snapshot: SealedSessionSnapshot, reason: String) {
        if (writer.state == DurableWriterState.OPEN || writer.state == DurableWriterState.SEALING ||
            writer.state == DurableWriterState.FAILED
        ) {
            writer.abort(reason, snapshot.counts)
        }
    }

    override fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult {
        finalizationResult?.let { return it }

        val sealedArtifacts = try {
            writer.seal(FinalSessionAggregates(snapshot.counts))
        } catch (_: DurableReconciliationException) {
            abortWriter(snapshot, sanitizedDetail("abandoned export attempt"))
            return failed(snapshot, "stream_reconciliation_failed").also { finalizationResult = it }
        } catch (_: Throwable) {
            abortWriter(snapshot, sanitizedDetail("abandoned export attempt"))
            return failed(snapshot, "stream_sealing_failed").also { finalizationResult = it }
        }
        val attempt = sealedArtifacts.attempt

        var renamed = false
        var reconciliationFailed = false
        val result = try {
            val poseReconciliation = sealedArtifacts.artifacts
                .single { it.artifact == DurableArtifact.POSE_STREAM }
                .reconciliation
            if (poseReconciliation.recordCount != snapshot.counts.inferenceRecordsEmitted
            ) {
                reconciliationFailed = true
                throw StreamReconciliationMismatch
            }

            val manifest = manifestAtFinalization(
                manifestTemplate.copy(
                    counts = snapshot.counts,
                    machineInferenceFailures = snapshot.failures,
                ),
                snapshot,
            )
            storage.writeManifest(attempt, encodeManifest(manifest))
            storage.fsyncFile(attempt, StorageFile.MANIFEST)
            storage.fsyncDirectory(attempt.path)

            val destination = "${storage.roots.exportsRoot}/${manifest.sessionId.value}"
            if (storage.destinationExists(destination)) throw DestinationAlreadyExists
            storage.renameNoReplace(attempt, destination)
            renamed = true
            try {
                storage.fsyncDirectory(storage.roots.exportsRoot)
                FinalizationResult.Published(snapshot)
            } catch (_: Throwable) {
                FinalizationResult.Failed(
                    sealedSnapshot = snapshot,
                    failureCode = "uncertain_durability",
                    detail = sanitizedDetail("rename committed; uncertain durability"),
                )
            }
        } catch (_: Throwable) {
            if (renamed) {
                FinalizationResult.Failed(
                    sealedSnapshot = snapshot,
                    failureCode = "uncertain_durability",
                    detail = sanitizedDetail("rename committed; uncertain durability"),
                )
            } else {
                abandon(attempt, snapshot)
                val code = if (reconciliationFailed) {
                    "stream_reconciliation_failed"
                } else {
                    "export_failed_before_publish"
                }
                failed(snapshot, code)
            }
        }

        finalizationResult = result
        return result
    }

    private fun abandon(attempt: StagingAttempt, snapshot: SealedSessionSnapshot) {
        val reason = sanitizedDetail("abandoned export attempt")
        try {
            storage.writeAbortMarker(attempt, reason, snapshot.counts)
            storage.fsyncFile(attempt, StorageFile.ABORT_MARKER)
        } catch (_: Throwable) {
            // Marker absence is the deliberate crash-residue classification.
        }
    }

    private fun abortWriter(snapshot: SealedSessionSnapshot, reason: String) {
        runCatching { abort(snapshot, reason) }
    }

    private fun failed(snapshot: SealedSessionSnapshot, code: String): FinalizationResult.Failed =
        FinalizationResult.Failed(snapshot, code, sanitizedDetail(code))

    private fun sanitizedDetail(reason: String): String =
        "$reason for pseudonym=${manifestTemplate.participantPseudonymId}"

    private object StreamReconciliationMismatch : IllegalStateException()

    private object DestinationAlreadyExists : IllegalStateException()
}
