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
    val diagnosticHarnessRoot: String = parentOf(stagingRoot),
    val transferStagingRoot: String = "$diagnosticHarnessRoot/${StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_TRANSFER_STAGING)}",
    val tombstoneRoot: String = "$diagnosticHarnessRoot/${StorageLocationRegistry.androidChild(StorageLocationId.TOMBSTONE)}",
) {
    val registeredAndroidRoots: List<String>
        get() = listOf(exportsRoot, stagingRoot, transferStagingRoot, tombstoneRoot)

    init {
        require(filesDir.startsWith('/') && !filesDir.endsWith('/')) { "filesDir must be a normalized absolute path" }
        require(diagnosticHarnessRoot == filesDir || isBelow(filesDir, diagnosticHarnessRoot)) {
            "diagnostic harness root must be at or below filesDir"
        }
        val expected = mapOf(
            exportsRoot to StorageLocationId.DEVICE_EXPORT,
            stagingRoot to StorageLocationId.DEVICE_STAGING,
            transferStagingRoot to StorageLocationId.DEVICE_TRANSFER_STAGING,
            tombstoneRoot to StorageLocationId.TOMBSTONE,
        )
        require(expected.keys.size == expected.size) { "registered Android roots must be distinct" }
        expected.forEach { (root, id) ->
            require(root == "$diagnosticHarnessRoot/${StorageLocationRegistry.androidChild(id)}") {
                "$id root must be the registry-derived child of diagnosticHarnessRoot"
            }
        }
    }

    private companion object {
        fun isBelow(parent: String, child: String): Boolean = child.startsWith("$parent/")

        fun parentOf(path: String): String = path.substringBeforeLast('/')
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
