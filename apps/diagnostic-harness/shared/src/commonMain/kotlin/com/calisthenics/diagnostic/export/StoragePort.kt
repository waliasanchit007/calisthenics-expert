package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
        require(isNormalizedAbsolute(filesDir)) { "filesDir must be a normalized absolute path" }
        require(isNormalizedAbsolute(diagnosticHarnessRoot)) { "diagnosticHarnessRoot must be a normalized absolute path" }
        require(diagnosticHarnessRoot == filesDir || isBelow(filesDir, diagnosticHarnessRoot)) {
            "diagnostic harness root must be at or below filesDir"
        }
        val harnessRelativeSegments = diagnosticHarnessRoot.removePrefix("$filesDir/")
            .split('/')
            .filter(String::isNotEmpty)
        require(harnessRelativeSegments.none { it in purgeableAndroidRootNames }) {
            "diagnostic harness root cannot be nested inside a registered purge root"
        }
        val expected = mapOf(
            exportsRoot to StorageLocationId.DEVICE_EXPORT,
            stagingRoot to StorageLocationId.DEVICE_STAGING,
            transferStagingRoot to StorageLocationId.DEVICE_TRANSFER_STAGING,
            tombstoneRoot to StorageLocationId.TOMBSTONE,
        )
        require(expected.keys.size == expected.size) { "registered Android roots must be distinct" }
        expected.forEach { (root, id) ->
            require(isNormalizedAbsolute(root)) { "$id root must be a normalized absolute path" }
            require(root == "$diagnosticHarnessRoot/${StorageLocationRegistry.androidChild(id)}") {
                "$id root must be the registry-derived child of diagnosticHarnessRoot"
            }
        }
    }

    private companion object {
        fun isBelow(parent: String, child: String): Boolean = child.startsWith("$parent/")

        fun isNormalizedAbsolute(path: String): Boolean =
            path.startsWith('/') && !path.endsWith('/') && path.split('/').drop(1).none {
                it.isEmpty() || it == "." || it == ".."
            }

        fun parentOf(path: String): String = path.substringBeforeLast('/')

        val purgeableAndroidRootNames = setOf(
            StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_EXPORT),
            StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_STAGING),
            StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_TRANSFER_STAGING),
            StorageLocationRegistry.androidChild(StorageLocationId.TOMBSTONE),
        )
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

/** Named streams owned by the durable writer. Platform paths never cross this boundary. */
enum class DurableArtifact {
    POSE_STREAM,
    TELEMETRY,
    RUN_ADMISSION,

    /**
     * The synchronization evidence binding this session's video to its pose stream.
     *
     * It is written into the attempt and commits with the session, so a published export can never
     * describe a synchronization that is not beside it.
     */
    SYNCHRONIZATION_EVIDENCE,

    /**
     * The synchronized video container for this session.
     *
     * Unlike the append-only streams, the platform muxer owns this file and writes it itself, so
     * it is opened through a descriptor rather than fed byte ranges. It still lives inside the
     * staging attempt and commits with the session: the encoder may not publish independently.
     */
    VIDEO,
}

data class ArtifactReconciliation(
    val byteLength: Long,
    val sha256: String,
    val recordCount: Long,
    val posePresentCount: Long?,
)

internal fun reconcileRunAdmissionBytes(bytes: ByteArray): ArtifactReconciliation {
    if (bytes.isEmpty()) return ArtifactReconciliation(0L, Sha256Digest.hex(bytes), 0L, null)
    try {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (error: Throwable) {
        throw DurableReconciliationException("run admission is not exactly one JSON object")
    }
    return ArtifactReconciliation(
        byteLength = bytes.size.toLong(),
        sha256 = Sha256Digest.hex(bytes),
        recordCount = 1L,
        posePresentCount = null,
    )
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

    /** Appends bytes to a named durable stream without exposing its platform path. */
    fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray)

    /** Flushes a named stream's userspace/channel buffers. */
    fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact)

    /** Forces a named stream to stable storage. */
    fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact)

    /** Atomically replaces the compact checkpoint using the platform's durable sequence. */
    fun replaceCheckpoint(attempt: StagingAttempt, encodedCheckpoint: String)

    /** Independently reads a finalized named stream and returns its digest/length/count. */
    fun reconcileArtifact(attempt: StagingAttempt, artifact: DurableArtifact): ArtifactReconciliation
}
