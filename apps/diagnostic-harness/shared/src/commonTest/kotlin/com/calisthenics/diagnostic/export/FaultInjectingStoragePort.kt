package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Uuid

/**
 * Wraps any [StoragePort] delegate — fake or real — recording an operation label and throwing
 * before delegating when the ordinal matches an injected failure set. This is `FakeStorage`'s
 * former `before()` hook, promoted out of the fake and generalized to any backend.
 *
 * Ordinal/label coverage is exact, per design spec §6.1 point 1 / §6.2: the legacy export
 * primitives and the named durable-artifact primitives consume an ordinal and are fault-injectable.
 * The labels identify the operation and named artifact without exposing filesystem paths.
 * [createStagingAttempt], [scanCommittedRoot], and [scanStagingResidues] delegate directly,
 * consuming no ordinal and never eligible for injected failure — they are not part of
 * `SessionExporter`'s six-step commit sequence the fake's original ordinal gate covered.
 */
class FaultInjectingStoragePort(
    private val delegate: StoragePort,
    private val failOnOrdinal: Int? = null,
    private val failOnOrdinals: Set<Int> = emptySet(),
    private val dropAppends: Boolean = false,
) : StoragePort {
    override val roots: StorageRoots get() = delegate.roots

    val operations = mutableListOf<String>()
    private var ordinal = 0

    override fun createStagingAttempt(sessionId: Uuid): StagingAttempt = delegate.createStagingAttempt(sessionId)

    override fun appendLine(attempt: StagingAttempt, line: String) {
        before("appendLine")
        if (!dropAppends) delegate.appendLine(attempt, line)
    }

    override fun flush(attempt: StagingAttempt) {
        before("flush")
        delegate.flush(attempt)
    }

    override fun fsyncFile(attempt: StagingAttempt, file: StorageFile) {
        before("fsyncFile:$file")
        delegate.fsyncFile(attempt, file)
    }

    override fun reconcile(attempt: StagingAttempt): StreamReconciliation {
        before("reconcile")
        return delegate.reconcile(attempt)
    }

    override fun writeManifest(attempt: StagingAttempt, encodedManifest: String) {
        before("writeManifest")
        delegate.writeManifest(attempt, encodedManifest)
    }

    override fun fsyncDirectory(path: String) {
        val operation = if (path == roots.exportsRoot) "fsyncDirectory:exports" else "fsyncDirectory:staging"
        before(operation)
        delegate.fsyncDirectory(path)
    }

    override fun destinationExists(destinationPath: String): Boolean {
        before("destinationExists")
        return delegate.destinationExists(destinationPath)
    }

    override fun renameNoReplace(attempt: StagingAttempt, destinationPath: String) {
        before("renameNoReplace")
        delegate.renameNoReplace(attempt, destinationPath)
    }

    override fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts) {
        before("writeAbortMarker")
        delegate.writeAbortMarker(attempt, reason, sealedCounters)
    }

    override fun scanCommittedRoot(): List<ExportRootCorruption> = delegate.scanCommittedRoot()

    override fun scanStagingResidues(): List<StagingResidue> = delegate.scanStagingResidues()

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        before("appendArtifact:$artifact")
        if (!dropAppends) delegate.appendArtifact(attempt, artifact, bytes)
    }

    override fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        before("flushArtifact:$artifact")
        delegate.flushArtifact(attempt, artifact)
    }

    override fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        before("fsyncArtifact:$artifact")
        delegate.fsyncArtifact(attempt, artifact)
    }

    override fun replaceCheckpoint(attempt: StagingAttempt, encodedCheckpoint: String) {
        before("replaceCheckpoint")
        delegate.replaceCheckpoint(attempt, encodedCheckpoint)
    }

    override fun reconcileArtifact(attempt: StagingAttempt, artifact: DurableArtifact): ArtifactReconciliation {
        before("reconcileArtifact:$artifact")
        return delegate.reconcileArtifact(attempt, artifact)
    }

    private fun before(operation: String) {
        ordinal++
        operations += operation
        if (ordinal == failOnOrdinal || ordinal in failOnOrdinals) throw StorageFault(operation)
    }
}

class StorageFault(operation: String) : IllegalStateException("fault injected at $operation with secret-path")
