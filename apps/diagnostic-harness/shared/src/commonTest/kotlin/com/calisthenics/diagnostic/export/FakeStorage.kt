package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.decodeFrame
import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A pure in-memory [StoragePort] implementation with no test-only surface of its own. */
class FakeStorage(
    override val roots: StorageRoots = StorageRoots(
        filesDir = "/files/diagnostic-harness",
        stagingRoot = "/files/diagnostic-harness/staging",
        exportsRoot = "/files/diagnostic-harness/exports",
    ),
) : StoragePort {
    val attempts = mutableListOf<FakeStagingAttempt>()
    val committedDirectories = linkedMapOf<String, FakeCommittedDirectory>()
    val rootDirectories = linkedSetOf<String>()

    private var nextNonce = 1

    override fun createStagingAttempt(sessionId: com.calisthenics.diagnostic.contract.Uuid): StagingAttempt {
        val path = "${roots.stagingRoot}/${sessionId.value}.${nextNonce++}.staging"
        val attempt = FakeStagingAttempt(path)
        attempts += attempt
        return attempt
    }

    override fun appendLine(attempt: StagingAttempt, line: String) {
        fakeAttempt(attempt).frameLines += line
    }

    override fun flush(attempt: StagingAttempt) {
        fakeAttempt(attempt).flushed = true
    }

    override fun fsyncFile(attempt: StagingAttempt, file: StorageFile) {
        fakeAttempt(attempt).fsyncedFiles += file
    }

    override fun reconcile(attempt: StagingAttempt): StreamReconciliation {
        val fake = fakeAttempt(attempt)
        val frames = fake.frameLines.map(::decodeFrame)
        return StreamReconciliation(
            lineCount = frames.size.toLong(),
            posePresentCount = frames.count { it.posePresent }.toLong(),
        )
    }

    override fun writeManifest(attempt: StagingAttempt, encodedManifest: String) {
        fakeAttempt(attempt).manifest = encodedManifest
    }

    override fun fsyncDirectory(path: String) {
        if (path != roots.exportsRoot) {
            fakeAttemptByPath(path).stagingDirectoryFsynced = true
        }
    }

    override fun destinationExists(destinationPath: String): Boolean = rootDirectories.contains(destinationPath)

    override fun renameNoReplace(attempt: StagingAttempt, destinationPath: String) {
        check(destinationPath.startsWith("${roots.exportsRoot}/")) { "destination outside exports root" }
        val fake = fakeAttempt(attempt)
        check(fake.manifest != null) { "manifest missing" }
        rootDirectories += destinationPath
        committedDirectories[destinationPath] = FakeCommittedDirectory(fake.frameLines.toList(), fake.manifest!!)
        fake.renamed = true
    }

    override fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts) {
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

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        val fake = fakeAttempt(attempt)
        val prior = fake.artifactBytes[artifact] ?: ByteArray(0)
        fake.artifactBytes[artifact] = prior + bytes
    }

    override fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        fakeAttempt(attempt).flushedArtifacts += artifact
    }

    override fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        val fake = fakeAttempt(attempt)
        fake.fsyncedArtifacts += artifact
        fake.durableArtifactBytes[artifact] = fake.artifactBytes[artifact] ?: ByteArray(0)
    }

    override fun replaceCheckpoint(attempt: StagingAttempt, encodedCheckpoint: String) {
        val fake = fakeAttempt(attempt)
        fake.checkpoint = encodedCheckpoint
        fake.checkpointReplacements++
    }

    override fun reconcileArtifact(attempt: StagingAttempt, artifact: DurableArtifact): ArtifactReconciliation {
        val bytes = fakeAttempt(attempt).artifactBytes[artifact] ?: ByteArray(0)
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
        return ArtifactReconciliation(
            byteLength = bytes.size.toLong(),
            sha256 = Sha256Digest.hex(bytes),
            recordCount = when (artifact) {
                DurableArtifact.POSE_STREAM -> lines.size.toLong()
                DurableArtifact.TELEMETRY -> reconcileTelemetry(bytes)
            },
            posePresentCount = if (artifact == DurableArtifact.POSE_STREAM) {
                lines.count { decodeFrame("$it\n").posePresent }.toLong()
            } else {
                null
            },
        )
    }

    private fun fakeAttempt(attempt: StagingAttempt): FakeStagingAttempt = attempts.single { it.path == attempt.path }

    private fun fakeAttemptByPath(path: String): FakeStagingAttempt = attempts.single { it.path == path }

    private fun reconcileTelemetry(bytes: ByteArray): Long {
        if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) {
            throw DurableReconciliationException("telemetry NDJSON ends with a truncated line")
        }
        var expectedSequence = 0L
        var previousTimeNs = -1L
        bytes.decodeToString().split('\n').dropLast(1).forEach { line ->
            if (line.isEmpty()) throw DurableReconciliationException("telemetry NDJSON contains an empty line")
            val objectValue = try {
                Json.parseToJsonElement(line).jsonObject
            } catch (error: Throwable) {
                throw DurableReconciliationException("telemetry NDJSON contains an invalid record")
            }
            val sequence = try {
                objectValue.getValue("sequence").jsonPrimitive.long
            } catch (error: Throwable) {
                throw DurableReconciliationException("telemetry NDJSON record has no sequence")
            }
            val timeNs = try {
                objectValue.getValue("t_session_ns").jsonPrimitive.long
            } catch (error: Throwable) {
                throw DurableReconciliationException("telemetry NDJSON record has no session time")
            }
            if (sequence != expectedSequence) {
                throw DurableReconciliationException("telemetry NDJSON sequence is not contiguous from zero")
            }
            if (timeNs < previousTimeNs) {
                throw DurableReconciliationException("telemetry NDJSON session time regressed")
            }
            expectedSequence++
            previousTimeNs = timeNs
        }
        return expectedSequence
    }
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
    val artifactBytes: MutableMap<DurableArtifact, ByteArray> = mutableMapOf(),
    val durableArtifactBytes: MutableMap<DurableArtifact, ByteArray> = mutableMapOf(),
    val flushedArtifacts: MutableSet<DurableArtifact> = mutableSetOf(),
    val fsyncedArtifacts: MutableSet<DurableArtifact> = mutableSetOf(),
    var checkpoint: String? = null,
    var checkpointReplacements: Int = 0,
) : StagingAttempt

data class FakeCommittedDirectory(val frameLines: List<String>, val manifest: String)
