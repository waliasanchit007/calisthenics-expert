package com.calisthenics.diagnostic.export

import android.system.Os
import android.system.OsConstants
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.decodeFrame
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.contract.decodeFrame
import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Android primitives for the [StoragePort] contract. Every method is a
 * standalone `java.io`/`java.nio.file`/`android.system.Os` primitive; none of them sequence a
 * second [StoragePort] method to do their job — that ordering stays in `SessionExporter`.
 */
class AndroidStoragePort(override val roots: StorageRoots) : StoragePort {
    private val nonce = AtomicInteger(0)
    private val frameChannels = mutableMapOf<String, FileChannel>()
    private val artifactChannels = mutableMapOf<Pair<String, DurableArtifact>, FileChannel>()
    private val renamedPaths = mutableSetOf<String>()

    /**
     * A fresh port instance starts its [nonce] counter back at zero, so the counter alone cannot
     * guarantee a path a prior instance (or a crashed prior instance's residue) has not already
     * claimed — across a process/port restart, `Files.createDirectories` would otherwise reopen
     * that old residue silently. Uniqueness is therefore enforced by exclusive single-directory
     * creation: each candidate nonce is claimed with `Files.createDirectory`, retrying past
     * `FileAlreadyExistsException` until an unclaimed path is found. `AtomicInteger.incrementAndGet`
     * keeps concurrent attempts within one instance collision-free.
     */
    override fun createStagingAttempt(sessionId: Uuid): StagingAttempt {
        Files.createDirectories(Paths.get(roots.stagingRoot))
        while (true) {
            val candidateNonce = nonce.incrementAndGet()
            val path = "${roots.stagingRoot}/${sessionId.value}.$candidateNonce.staging"
            try {
                Files.createDirectory(Paths.get(path))
                return AndroidStagingAttempt(path)
            } catch (_: FileAlreadyExistsException) {
                // Another instance (this one's own concurrent caller, a still-live sibling
                // instance, or a prior crashed instance's residue) already claimed this nonce;
                // try the next one rather than silently reopening it.
            }
        }
    }

    override fun appendLine(attempt: StagingAttempt, line: String) {
        val channel = frameChannels.getOrPut(attempt.path) {
            FileChannel.open(
                framesPath(attempt),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            )
        }
        val buffer = ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8))
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun flush(attempt: StagingAttempt) {
        frameChannels[attempt.path]?.force(false)
    }

    override fun fsyncFile(attempt: StagingAttempt, file: StorageFile) {
        if (file == StorageFile.FRAMES) {
            frameChannels.remove(attempt.path)?.close()
        }
        // A zero-frame session never calls appendLine, so frames.ndjson may not exist yet; CREATE
        // is a no-op when the file is already present from an earlier write.
        FileChannel.open(pathFor(attempt, file), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            .use { it.force(true) }
    }

    override fun reconcile(attempt: StagingAttempt): StreamReconciliation {
        val framesFile = framesPath(attempt)
        val lines = if (Files.exists(framesFile)) {
            Files.readAllLines(framesFile, StandardCharsets.UTF_8).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val frames = lines.map(::decodeFrame)
        return StreamReconciliation(
            lineCount = frames.size.toLong(),
            posePresentCount = frames.count { it.posePresent }.toLong(),
        )
    }

    override fun writeManifest(attempt: StagingAttempt, encodedManifest: String) {
        Files.write(manifestPath(attempt), encodedManifest.toByteArray(StandardCharsets.UTF_8))
    }

    override fun fsyncDirectory(path: String) {
        val fd = Os.open(path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    override fun destinationExists(destinationPath: String): Boolean = Files.exists(Paths.get(destinationPath))

    override fun renameNoReplace(attempt: StagingAttempt, destinationPath: String) {
        Os.rename(attempt.path, destinationPath)
        renamedPaths += attempt.path
    }

    override fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts) {
        Files.write(
            abortMarkerPath(attempt),
            encodeAbortMarker(reason, sealedCounters).toByteArray(StandardCharsets.UTF_8),
        )
    }

    override fun scanCommittedRoot(): List<ExportRootCorruption> {
        val exportsDir = Paths.get(roots.exportsRoot)
        if (!Files.exists(exportsDir)) return emptyList()
        return Files.list(exportsDir).use { paths ->
            paths
                .filter { Files.isDirectory(it) && !Files.exists(it.resolve(MANIFEST_FILE_NAME)) }
                .map { ExportRootCorruption(it.toString()) }
                .toList()
        }
    }

    override fun scanStagingResidues(): List<StagingResidue> {
        val stagingDir = Paths.get(roots.stagingRoot)
        if (!Files.exists(stagingDir)) return emptyList()
        return Files.list(stagingDir).use { paths ->
            paths
                .filter { it.toString() !in renamedPaths }
                .map { child ->
                    val markerPath = child.resolve(ABORT_MARKER_FILE_NAME)
                    if (Files.exists(markerPath)) {
                        val marker = decodeAbortMarker(String(Files.readAllBytes(markerPath), StandardCharsets.UTF_8))
                        StagingResidue.Abandoned(child.toString(), marker)
                    } else {
                        StagingResidue.CrashResidue(child.toString())
                    }
                }
                .toList()
        }
    }

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        val channel = artifactChannels.getOrPut(attempt.path to artifact) {
            FileChannel.open(
                artifactPath(attempt, artifact),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            )
        }
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        artifactChannels[attempt.path to artifact]?.force(false)
    }

    override fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        artifactChannels.remove(attempt.path to artifact)?.let { channel ->
            channel.force(true)
            channel.close()
        }
        val path = artifactPath(attempt, artifact)
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { it.force(true) }
    }

    override fun replaceCheckpoint(attempt: StagingAttempt, encodedCheckpoint: String) {
        val checkpoint = checkpointPath(attempt)
        val temporary = checkpoint.resolveSibling("${checkpoint.fileName}.tmp")
        Files.write(
            temporary,
            encodedCheckpoint.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Os.rename(temporary.toString(), checkpoint.toString())
        fsyncDirectory(attempt.path)
    }

    override fun reconcileArtifact(attempt: StagingAttempt, artifact: DurableArtifact): ArtifactReconciliation {
        val bytes = if (Files.exists(artifactPath(attempt, artifact))) {
            Files.readAllBytes(artifactPath(attempt, artifact))
        } else {
            ByteArray(0)
        }
        val lines = bytes.toString(StandardCharsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
        return ArtifactReconciliation(
            byteLength = bytes.size.toLong(),
            sha256 = Sha256Digest.hex(bytes),
            recordCount = lines.size.toLong(),
            posePresentCount = if (artifact == DurableArtifact.POSE_STREAM) {
                lines.count { decodeFrame("$it\n").posePresent }.toLong()
            } else {
                null
            },
        )
    }

    private fun framesPath(attempt: StagingAttempt): Path = Paths.get(attempt.path, FRAMES_FILE_NAME)

    private fun artifactPath(attempt: StagingAttempt, artifact: DurableArtifact): Path =
        Paths.get(attempt.path, when (artifact) {
            DurableArtifact.POSE_STREAM -> FRAMES_FILE_NAME
            DurableArtifact.TELEMETRY -> TELEMETRY_FILE_NAME
        })

    private fun checkpointPath(attempt: StagingAttempt): Path = Paths.get(attempt.path, CHECKPOINT_FILE_NAME)

    private fun manifestPath(attempt: StagingAttempt): Path = Paths.get(attempt.path, MANIFEST_FILE_NAME)

    private fun abortMarkerPath(attempt: StagingAttempt): Path = Paths.get(attempt.path, ABORT_MARKER_FILE_NAME)

    private fun pathFor(attempt: StagingAttempt, file: StorageFile): Path = when (file) {
        StorageFile.FRAMES -> framesPath(attempt)
        StorageFile.MANIFEST -> manifestPath(attempt)
        StorageFile.ABORT_MARKER -> abortMarkerPath(attempt)
    }

    private class AndroidStagingAttempt(override val path: String) : StagingAttempt

    private companion object {
        const val FRAMES_FILE_NAME = "frames.ndjson"
        const val TELEMETRY_FILE_NAME = "telemetry.ndjson"
        const val CHECKPOINT_FILE_NAME = "checkpoint"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val ABORT_MARKER_FILE_NAME = "abort-marker.json"

        fun encodeAbortMarker(reason: String, counts: Counts): String {
            val json = buildJsonObject {
                put("reason", reason)
                put("camera_frames_received", counts.cameraFramesReceived)
                put("inference_frames_submitted", counts.inferenceFramesSubmitted)
                put("inference_records_emitted", counts.inferenceRecordsEmitted)
                put("pose_detected_records", counts.poseDetectedRecords)
                put("frames_skipped_before_inference", counts.framesSkippedBeforeInference)
                putJsonObject("frames_skipped_before_inference_by_reason") {
                    counts.framesSkippedBeforeInferenceByReason.forEach { (key, value) -> put(key, value) }
                }
                put(
                    "trailing_frames_skipped_after_last_emitted_record",
                    counts.trailingFramesSkippedAfterLastEmittedRecord,
                )
                put("inference_failures", counts.inferenceFailures)
            }
            return json.toString()
        }

        fun decodeAbortMarker(text: String): AbortMarker {
            val root = Json.parseToJsonElement(text).jsonObject
            val counts = Counts(
                cameraFramesReceived = root.getValue("camera_frames_received").jsonPrimitive.long,
                inferenceFramesSubmitted = root.getValue("inference_frames_submitted").jsonPrimitive.long,
                inferenceRecordsEmitted = root.getValue("inference_records_emitted").jsonPrimitive.long,
                poseDetectedRecords = root.getValue("pose_detected_records").jsonPrimitive.long,
                framesSkippedBeforeInference = root.getValue("frames_skipped_before_inference").jsonPrimitive.long,
                framesSkippedBeforeInferenceByReason = root.getValue("frames_skipped_before_inference_by_reason")
                    .jsonObject.mapValues { (_, value) -> value.jsonPrimitive.long },
                trailingFramesSkippedAfterLastEmittedRecord =
                    root.getValue("trailing_frames_skipped_after_last_emitted_record").jsonPrimitive.long,
                inferenceFailures = root.getValue("inference_failures").jsonPrimitive.long,
            )
            return AbortMarker(root.getValue("reason").jsonPrimitive.content, counts)
        }
    }
}
