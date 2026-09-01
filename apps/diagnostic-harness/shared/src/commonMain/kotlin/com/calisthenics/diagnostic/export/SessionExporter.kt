package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.encodeManifest
import com.calisthenics.diagnostic.purge.PurgeSessionId
import com.calisthenics.diagnostic.purge.SessionAdmissionDecision
import com.calisthenics.diagnostic.purge.SessionAdmissionFence
import com.calisthenics.diagnostic.purge.SessionAdmissionPhase
import com.calisthenics.diagnostic.session.EmittedRecordSink
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SealedSessionSnapshot
import com.calisthenics.diagnostic.session.SessionAborter
import com.calisthenics.diagnostic.session.SessionFinalizer
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySample
import com.calisthenics.diagnostic.telemetry.PipelineStageEvent
import com.calisthenics.diagnostic.telemetry.SustainedStageCounterSample
import com.calisthenics.diagnostic.telemetry.TelemetryRecord
import com.calisthenics.diagnostic.telemetry.TelemetrySink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Composition-owned aborts call this only after callback and telemetry producers are fenced. */
fun interface DeferredSessionAbort {
    fun completePendingAbort()
}

/** Publishes one sealed session using the fixed crash-consistent commit sequence. */
class SessionExporter(
    private val storage: StoragePort,
    private val manifestTemplate: SessionManifest,
    private val monotonicNowNs: () -> Long,
    private val manifestAtFinalization: (SessionManifest, SealedSessionSnapshot) -> SessionManifest = { manifest, _ -> manifest },
    private val writer: DurableSessionWriter = DefaultDurableSessionWriter(
        storage = storage,
        sessionId = manifestTemplate.sessionId,
        monotonicNowNs = monotonicNowNs,
    ),
    private val admissionFence: SessionAdmissionFence = SessionAdmissionFence { _, _ -> SessionAdmissionDecision.Admitted },
    private val deferAbortUntilFence: Boolean = false,
) : SessionFinalizer, SessionAborter, EmittedRecordSink, TelemetrySink, DeferredSessionAbort {
    private var finalizationResult: FinalizationResult? = null
    private var pendingAbort: Pair<SealedSessionSnapshot, String>? = null
    private var nextTelemetrySequence = 0L
    private var lastFixedTelemetrySequence: Long? = null
    private val telemetryMutex = Mutex()
    private val writerOpenAdmission = checkAdmission(SessionAdmissionPhase.WRITER_OPEN)

    /** A typed refusal is observable by composition without a constructor exception or a staging write. */
    val startRefusalCode: String?
        get() = (writerOpenAdmission as? SessionAdmissionDecision.Refused)?.code

    init {
        if (writerOpenAdmission is SessionAdmissionDecision.Admitted) writer.open()
    }

    /** The staging attempt this session is writing into, for platform sinks that own a file. */
    val openStagingAttempt: StagingAttempt?
        get() = writer.openStagingAttempt

    override fun appendPose(record: com.calisthenics.diagnostic.contract.RawPoseFrame) {
        writer.appendPose(record)
    }

    override fun append(record: TelemetryRecord) = serialized {
        val recordTimeNs = when (record) {
            is FixedTelemetrySample -> record.tSessionNs
            is PipelineStageEvent -> record.tSessionNs
            is SustainedStageCounterSample -> record.tSessionNs
        }
        val orderedRecord = when (record) {
            is FixedTelemetrySample -> record.copy(
                sequence = nextTelemetrySequence,
                tSessionNs = recordTimeNs,
            )
            is PipelineStageEvent -> record.copy(
                sequence = nextTelemetrySequence,
                tSessionNs = recordTimeNs,
            )
            is SustainedStageCounterSample -> record.copy(
                sequence = nextTelemetrySequence,
                tSessionNs = recordTimeNs,
                fixedTelemetrySequence = requireNotNull(lastFixedTelemetrySequence) {
                    "sustained stage counters require a fixed telemetry sample"
                },
            )
        }
        writer.appendTelemetry(orderedRecord.encodeNdjson())
        if (orderedRecord is FixedTelemetrySample) {
            lastFixedTelemetrySequence = orderedRecord.sequence
        }
        nextTelemetrySequence += 1L
    }

    override fun abort(snapshot: SealedSessionSnapshot, reason: String) = serialized {
        if (deferAbortUntilFence) {
            if (pendingAbort == null) pendingAbort = snapshot to reason
        } else {
            abortNow(snapshot, reason)
        }
    }

    override fun completePendingAbort(): Unit = serialized {
        pendingAbort?.let { pending ->
            pendingAbort = null
            abortNow(pending.first, pending.second)
        }
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
        startRefusalCode?.let { code ->
            return failed(snapshot, code).also { finalizationResult = it }
        }

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
            val finalAdmission = checkAdmission(SessionAdmissionPhase.FINAL_PUBLISH)
            if (finalAdmission is SessionAdmissionDecision.Refused) {
                throw AdmissionRefused(finalAdmission.code)
            }
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
        } catch (error: Throwable) {
            if (renamed) {
                FinalizationResult.Failed(
                    sealedSnapshot = snapshot,
                    failureCode = "uncertain_durability",
                    detail = sanitizedDetail("rename committed; uncertain durability"),
                )
            } else {
                abandon(attempt, snapshot)
                val code = when {
                    reconciliationFailed -> "stream_reconciliation_failed"
                    error is AdmissionRefused -> error.code
                    else -> "export_failed_before_publish"
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

    private fun checkAdmission(phase: SessionAdmissionPhase): SessionAdmissionDecision {
        val syntheticSession = PurgeSessionId.parseOrNull(manifestTemplate.sessionId.value)
            ?: return SessionAdmissionDecision.Admitted
        return runCatching { admissionFence.check(syntheticSession, phase) }
            .getOrElse { SessionAdmissionDecision.Refused("PURGE.SESSION_TOMBSTONED") }
    }

    private fun sanitizedDetail(reason: String): String =
        "$reason for pseudonym=${manifestTemplate.participantPseudonymId}"

    private fun <T> serialized(block: () -> T): T = runBlocking { telemetryMutex.withLock { block() } }

    private object StreamReconciliationMismatch : IllegalStateException()

    private object DestinationAlreadyExists : IllegalStateException()

    private class AdmissionRefused(val code: String) : IllegalStateException(code)
}
