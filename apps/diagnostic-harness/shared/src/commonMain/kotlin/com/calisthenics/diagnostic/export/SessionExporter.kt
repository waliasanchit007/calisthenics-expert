package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.encodeFrameLine
import com.calisthenics.diagnostic.contract.encodeManifest
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SealedSessionSnapshot
import com.calisthenics.diagnostic.session.SessionFinalizer

/** Publishes one sealed session using the fixed crash-consistent commit sequence. */
class SessionExporter(
    private val storage: StoragePort,
    private val manifestTemplate: SessionManifest,
) : SessionFinalizer {
    private var finalizationResult: FinalizationResult? = null

    override fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult {
        finalizationResult?.let { return it }

        val attempt = try {
            storage.createStagingAttempt(manifestTemplate.sessionId)
        } catch (_: Throwable) {
            return failed(snapshot, "staging_creation_failed").also { finalizationResult = it }
        }

        var renamed = false
        var reconciliationFailed = false
        val result = try {
            snapshot.records.forEach { record ->
                storage.appendLine(attempt, encodeFrameLine(record))
            }
            storage.flush(attempt)
            storage.fsyncFile(attempt, StorageFile.FRAMES)

            val reconciliation = storage.reconcile(attempt)
            if (reconciliation.lineCount != snapshot.counts.inferenceRecordsEmitted ||
                reconciliation.posePresentCount != snapshot.counts.poseDetectedRecords
            ) {
                reconciliationFailed = true
                throw StreamReconciliationMismatch
            }

            val manifest = manifestTemplate.copy(
                counts = snapshot.counts,
                machineInferenceFailures = snapshot.failures,
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

    private fun failed(snapshot: SealedSessionSnapshot, code: String): FinalizationResult.Failed =
        FinalizationResult.Failed(snapshot, code, sanitizedDetail(code))

    private fun sanitizedDetail(reason: String): String =
        "$reason for pseudonym=${manifestTemplate.participantPseudonymId}"

    private object StreamReconciliationMismatch : IllegalStateException()

    private object DestinationAlreadyExists : IllegalStateException()
}
