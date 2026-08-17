package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Uuid

/**
 * The roots are deliberately expressed as one app-private filesDir tree. Staging and exports
 * must therefore remain on the same filesystem mount; external storage is not a valid root.
 */
data class StorageRoots(
    val filesDir: String,
    val stagingRoot: String,
    val exportsRoot: String,
) {
    init {
        require(filesDir.startsWith('/') && !filesDir.endsWith('/')) { "filesDir must be a normalized absolute path" }
        require(isBelow(filesDir, stagingRoot)) { "staging root must be below filesDir" }
        require(isBelow(filesDir, exportsRoot)) { "exports root must be below filesDir" }
        require(stagingRoot != exportsRoot) { "staging and exports roots must be distinct" }
    }

    private companion object {
        fun isBelow(parent: String, child: String): Boolean = child.startsWith("$parent/")
    }
}

interface StagingAttempt {
    val path: String
}

enum class StorageFile {
    FRAMES,
    MANIFEST,
    ABORT_MARKER,
}

data class StreamReconciliation(
    val lineCount: Long,
    val posePresentCount: Long,
)

data class AbortMarker(
    val reason: String,
    val sealedCounters: Counts,
)

data class ExportRootCorruption(val path: String)

sealed interface StagingResidue {
    val path: String

    data class CrashResidue(override val path: String) : StagingResidue

    data class Abandoned(override val path: String, val abortMarker: AbortMarker) : StagingResidue
}

/**
 * Durable storage operations are intentionally small and explicit so the ordering can be tested
 * on the common JVM host. The exporter performs the existence check immediately before this
 * logical check-then-plain-rename; it is not an atomic no-replace primitive. Dispatcher
 * confinement makes the residual race acceptable for this single-process exporter.
 */
interface StoragePort {
    val roots: StorageRoots

    fun createStagingAttempt(sessionId: Uuid): StagingAttempt

    fun appendLine(attempt: StagingAttempt, line: String)

    fun flush(attempt: StagingAttempt)

    fun fsyncFile(attempt: StagingAttempt, file: StorageFile)

    fun reconcile(attempt: StagingAttempt): StreamReconciliation

    fun writeManifest(attempt: StagingAttempt, encodedManifest: String)

    fun fsyncDirectory(path: String)

    /** The explicit pre-check required before the logical check-then-plain-rename. */
    fun destinationExists(destinationPath: String): Boolean

    fun renameNoReplace(attempt: StagingAttempt, destinationPath: String)

    fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts)

    fun scanCommittedRoot(): List<ExportRootCorruption>

    fun scanStagingResidues(): List<StagingResidue>
}
