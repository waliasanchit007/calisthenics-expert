package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.decodeFrame

class FakeStorage(
    override val roots: StorageRoots = StorageRoots(
        filesDir = "/files/diagnostic-harness",
        stagingRoot = "/files/diagnostic-harness/staging",
        exportsRoot = "/files/diagnostic-harness/exports",
    ),
    private val failOnOrdinal: Int? = null,
    private val failOnOrdinals: Set<Int> = emptySet(),
    private val dropAppends: Boolean = false,
) : StoragePort {
    val operations = mutableListOf<String>()
    val attempts = mutableListOf<FakeStagingAttempt>()
    val committedDirectories = linkedMapOf<String, FakeCommittedDirectory>()
    val rootDirectories = linkedSetOf<String>()

    private var ordinal = 0
    private var nextNonce = 1

    override fun createStagingAttempt(sessionId: com.calisthenics.diagnostic.contract.Uuid): StagingAttempt {
        val path = "${roots.stagingRoot}/${sessionId.value}.${nextNonce++}.staging"
        val attempt = FakeStagingAttempt(path)
        attempts += attempt
        return attempt
    }

    override fun appendLine(attempt: StagingAttempt, line: String) {
        before("appendLine")
        val fake = fakeAttempt(attempt)
        if (!dropAppends) fake.frameLines += line
    }

    override fun flush(attempt: StagingAttempt) {
        before("flush")
        fakeAttempt(attempt).flushed = true
    }

    override fun fsyncFile(attempt: StagingAttempt, file: StorageFile) {
        before("fsyncFile:$file")
        fakeAttempt(attempt).fsyncedFiles += file
    }

    override fun reconcile(attempt: StagingAttempt): StreamReconciliation {
        before("reconcile")
        val fake = fakeAttempt(attempt)
        val frames = fake.frameLines.map(::decodeFrame)
        return StreamReconciliation(
            lineCount = frames.size.toLong(),
            posePresentCount = frames.count { it.posePresent }.toLong(),
        )
    }

    override fun writeManifest(attempt: StagingAttempt, encodedManifest: String) {
        before("writeManifest")
        fakeAttempt(attempt).manifest = encodedManifest
    }

    override fun fsyncDirectory(path: String) {
        val operation = if (path == roots.exportsRoot) "fsyncDirectory:exports" else "fsyncDirectory:staging"
        before(operation)
        if (path != roots.exportsRoot) {
            fakeAttemptByPath(path).stagingDirectoryFsynced = true
        }
    }

    override fun destinationExists(destinationPath: String): Boolean {
        before("destinationExists")
        return rootDirectories.contains(destinationPath)
    }

    override fun renameNoReplace(attempt: StagingAttempt, destinationPath: String) {
        before("renameNoReplace")
        check(destinationPath.startsWith("${roots.exportsRoot}/")) { "destination outside exports root" }
        val fake = fakeAttempt(attempt)
        check(fake.manifest != null) { "manifest missing" }
        rootDirectories += destinationPath
        committedDirectories[destinationPath] = FakeCommittedDirectory(fake.frameLines.toList(), fake.manifest!!)
        fake.renamed = true
    }

    override fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts) {
        before("writeAbortMarker")
        fakeAttempt(attempt).abortMarker = AbortMarker(reason, sealedCounters)
    }

    override fun scanCommittedRoot(): List<ExportRootCorruption> = rootDirectories
        .filter { committedDirectories[it]?.manifest == null }
        .map { ExportRootCorruption(it) }

    override fun scanStagingResidues(): List<StagingResidue> = attempts
        .filter { !it.renamed }
        .map { attempt ->
            val marker = attempt.abortMarker
            if (marker == null) {
                StagingResidue.CrashResidue(attempt.path)
            } else {
                StagingResidue.Abandoned(attempt.path, marker)
            }
        }

    fun precreateDestination(path: String, empty: Boolean) {
        rootDirectories += path
        if (!empty) {
            committedDirectories[path] = FakeCommittedDirectory(listOf("untouched"), "untouched")
        }
    }

    fun abortMarkerFor(path: String): AbortMarker? = fakeAttemptByPath(path).abortMarker

    private fun before(operation: String) {
        ordinal++
        operations += operation
        if (ordinal == failOnOrdinal || ordinal in failOnOrdinals) throw StorageFault(operation)
    }

    private fun fakeAttempt(attempt: StagingAttempt): FakeStagingAttempt = attempts.single { it.path == attempt.path }

    private fun fakeAttemptByPath(path: String): FakeStagingAttempt = attempts.single { it.path == path }
}

data class FakeStagingAttempt(
    override val path: String,
    val frameLines: MutableList<String> = mutableListOf(),
    val fsyncedFiles: MutableSet<StorageFile> = mutableSetOf(),
    var flushed: Boolean = false,
    var stagingDirectoryFsynced: Boolean = false,
    var manifest: String? = null,
    var abortMarker: AbortMarker? = null,
    var renamed: Boolean = false,
) : StagingAttempt

data class FakeCommittedDirectory(val frameLines: List<String>, val manifest: String)

class StorageFault(operation: String) : IllegalStateException("fault injected at $operation with secret-path")
