package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.ArtifactReference
import com.calisthenics.diagnostic.contract.MediaReference
import com.calisthenics.diagnostic.contract.Sha256
import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.SemanticVersion
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
import com.calisthenics.diagnostic.telemetry.SustainedLatencySummaryRecord
import com.calisthenics.diagnostic.telemetry.TelemetryRecord
import com.calisthenics.diagnostic.telemetry.TelemetrySink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Composition-owned aborts call this only after callback and telemetry producers are fenced. */
fun interface DeferredSessionAbort {
    fun completePendingAbort()
}

/** Narrow authority used by a pre-capture admission gate that must commit into this session. */
interface DurableAdmissionHost {
    val durableAdmissionWriter: DurableSessionWriter
}

/** Bounded publication evidence exposed without leaking the staging attempt or filesystem paths. */
interface DurablePublishedEvidenceHost {
    val publishedPoseRecordCount: Long?
}

/** Publishes one sealed session using the fixed crash-consistent commit sequence. */
/**
 * The container transform this session observed, measured after the muxer finalized.
 *
 * The digest of the container is known to the exporter at seal; the offset and drift are known
 * only by reading the finished container back. Both halves have to meet before the manifest can
 * describe the media, which is why this is supplied rather than derived here.
 */
data class ObservedVideoSynchronization(
    val offsetNs: Long,
    val driftNsPerS: Double,
)

/** Exact sealed identities and session bounds available while synchronization evidence is built. */
data class SynchronizationEvidenceBuildContext(
    val video: ArtifactReconciliation,
    val pose: ArtifactReconciliation,
    val snapshot: SealedSessionSnapshot,
    val firstPoseCaptureTimestampNs: Long,
    val lastPoseCaptureTimestampNs: Long,
)

/** Released role used by both the manifest and synchronization-evidence schemas. */
const val SESSION_VIDEO_ROLE = "session_video"
private const val VIDEO_PATH_REF = "video.mp4"
private const val POSE_STREAM_PATH_REF = "frames.ndjson"
private const val SYNCHRONIZATION_EVIDENCE_PATH_REF = "synchronization-evidence.json"

class SessionExporter(
    private val storage: StoragePort,
    private val manifestTemplate: SessionManifest,
    private val monotonicNowNs: () -> Long,
    private val manifestAtFinalization: (SessionManifest, SealedSessionSnapshot) -> SessionManifest = { manifest, _ -> manifest },
    private val observedVideoSynchronization: () -> ObservedVideoSynchronization? = { null },
    private val synchronizationEvidenceSchemaVersion: SemanticVersion = manifestTemplate.schemaVersion,
    private val synchronizationEvidenceBuilder: ((SynchronizationEvidenceBuildContext) -> ByteArray)? = null,
    private val writer: DurableSessionWriter = DefaultDurableSessionWriter(
        storage = storage,
        sessionId = manifestTemplate.sessionId,
        monotonicNowNs = monotonicNowNs,
    ),
    private val admissionFence: SessionAdmissionFence = SessionAdmissionFence { _, _ -> SessionAdmissionDecision.Admitted },
    private val deferAbortUntilFence: Boolean = false,
) : SessionFinalizer, SessionAborter, EmittedRecordSink, TelemetrySink, DeferredSessionAbort,
    DurableAdmissionHost, DurablePublishedEvidenceHost {
    private var finalizationResult: FinalizationResult? = null
    private var pendingAbort: Pair<SealedSessionSnapshot, String>? = null
    private var nextTelemetrySequence = 0L
    private var lastFixedTelemetrySequence: Long? = null
    private var firstPoseCaptureTimestampNs: Long? = null
    private var lastPoseCaptureTimestampNs: Long? = null
    override var publishedPoseRecordCount: Long? = null
        private set
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

    override val durableAdmissionWriter: DurableSessionWriter
        get() = writer

    /** Write this session's synchronization evidence into its own attempt. */
    fun appendSynchronizationEvidence(exactBytes: ByteArray) {
        writer.appendSynchronizationEvidence(exactBytes)
    }

    override fun appendPose(record: com.calisthenics.diagnostic.contract.RawPoseFrame) {
        if (firstPoseCaptureTimestampNs == null) firstPoseCaptureTimestampNs = record.captureTimestampNs
        lastPoseCaptureTimestampNs = record.captureTimestampNs
        writer.appendPose(record)
    }

    override fun append(record: TelemetryRecord) = serialized {
        val recordTimeNs = when (record) {
            is FixedTelemetrySample -> record.tSessionNs
            is PipelineStageEvent -> record.tSessionNs
            is SustainedStageCounterSample -> record.tSessionNs
            is SustainedLatencySummaryRecord -> record.tSessionNs
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
            is SustainedLatencySummaryRecord -> record.copy(
                sequence = nextTelemetrySequence,
                tSessionNs = recordTimeNs,
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

    /**
     * Describe the artifacts that actually sealed, by the digests reconciliation produced.
     *
     * The manifest can only claim media that is in the attempt beside it: every reference here is
     * built from a sealed artifact, never from an intention. A session that recorded no video
     * keeps an empty media list and states nothing about synchronization.
     */
    private fun bindSealedArtifacts(
        manifest: SessionManifest,
        sealed: SealedArtifacts,
    ): SessionManifest {
        val video = sealed.artifacts.firstOrNull { it.artifact == DurableArtifact.VIDEO } ?: return manifest
        val evidence = sealed.artifacts.firstOrNull {
            it.artifact == DurableArtifact.SYNCHRONIZATION_EVIDENCE
        }
        val synchronization = observedVideoSynchronization()
        val pose = sealed.artifacts.single { it.artifact == DurableArtifact.POSE_STREAM }
        return manifest.copy(
            mediaRequired = true,
            mediaReferences = manifest.mediaReferences + MediaReference(
                role = SESSION_VIDEO_ROLE,
                pathRef = VIDEO_PATH_REF,
                sha256 = Sha256(video.reconciliation.sha256),
                // The released contract requires offset and drift together or not at all.
                syncOffsetNs = synchronization?.offsetNs,
                syncDriftNsPerS = synchronization?.driftNsPerS,
            ),
            rawPoseStream = ArtifactReference(
                pathRef = POSE_STREAM_PATH_REF,
                sha256 = Sha256(pose.reconciliation.sha256),
                schemaVersion = manifest.schemaVersion,
            ),
            synchronizationEvidence = evidence?.let {
                ArtifactReference(
                    pathRef = SYNCHRONIZATION_EVIDENCE_PATH_REF,
                    sha256 = Sha256(it.reconciliation.sha256),
                    schemaVersion = synchronizationEvidenceSchemaVersion,
                )
            },
        )
    }

    override fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult {
        finalizationResult?.let { return it }
        startRefusalCode?.let { code ->
            return failed(snapshot, code).also { finalizationResult = it }
        }

        val buildSynchronizationEvidence = synchronizationEvidenceBuilder?.let { builder ->
            { video: ArtifactReconciliation, pose: ArtifactReconciliation ->
                val firstPose = firstPoseCaptureTimestampNs
                    ?: throw DurableReconciliationException(
                        "synchronized video requires at least one durable pose record",
                    )
                val lastPose = lastPoseCaptureTimestampNs
                    ?: throw DurableReconciliationException(
                        "synchronized video requires a final durable pose timestamp",
                    )
                builder(
                    SynchronizationEvidenceBuildContext(
                        video = video,
                        pose = pose,
                        snapshot = snapshot,
                        firstPoseCaptureTimestampNs = firstPose,
                        lastPoseCaptureTimestampNs = lastPose,
                    ),
                )
            }
        }
        val sealedArtifacts = try {
            writer.seal(FinalSessionAggregates(snapshot.counts), buildSynchronizationEvidence)
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

            val manifest = bindSealedArtifacts(
                manifestAtFinalization(
                    manifestTemplate.copy(
                        counts = snapshot.counts,
                        machineInferenceFailures = snapshot.failures,
                    ),
                    snapshot,
                ),
                sealedArtifacts,
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
                publishedPoseRecordCount = poseReconciliation.recordCount
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
