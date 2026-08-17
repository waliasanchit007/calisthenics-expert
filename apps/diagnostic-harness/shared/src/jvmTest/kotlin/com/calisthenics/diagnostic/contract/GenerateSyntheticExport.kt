package com.calisthenics.diagnostic.contract

import com.calisthenics.diagnostic.export.AbortMarker
import com.calisthenics.diagnostic.export.ExportRootCorruption
import com.calisthenics.diagnostic.export.StagingAttempt
import com.calisthenics.diagnostic.export.StagingResidue
import com.calisthenics.diagnostic.export.StorageFile
import com.calisthenics.diagnostic.export.StoragePort
import com.calisthenics.diagnostic.export.StorageRoots
import com.calisthenics.diagnostic.export.StreamReconciliation
import com.calisthenics.diagnostic.session.DeterministicCore
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SessionConfiguration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.Comparator

private const val SESSION_ID = "5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c"

fun main(args: Array<String>) {
    require(args.size == 1) { "expected exactly one output directory below shared/build/conformance" }
    val output = Paths.get(args[0]).toAbsolutePath().normalize()
    val normalizedOutput = output.toString().replace('\\', '/')
    require("/build/conformance/" in normalizedOutput) {
        "synthetic export output must be below shared/build/conformance: $output"
    }

    deleteTree(output)
    val buildOutput = output.parent
    val stagingRoot = buildOutput.resolve(".deterministic-core-staging")
    val exportsRoot = buildOutput.resolve(".deterministic-core-exports")
    deleteTree(stagingRoot)
    deleteTree(exportsRoot)
    Files.createDirectories(output)
    val storage = FileStorage(buildOutput, stagingRoot, exportsRoot)

    val result = DeterministicCore(
        configuration = SessionConfiguration(
            sessionId = Uuid(SESSION_ID),
            schemaVersion = SemanticVersion("1.1.0"),
            imageWidth = 256,
            imageHeight = 256,
        ),
        manifestTemplate = syntheticManifest(),
        storage = storage,
    ).run(
        sourceRgb24 = byteArrayOf(
            0, 10, 20,
            30, 40, 50,
            60, 70, 80,
            90, 100, 110,
        ),
        sourceWidth = 2,
        sourceHeight = 2,
    )
    require(result.finalization is FinalizationResult.Published) { "deterministic core did not publish" }
    require(storage.scanCommittedRoot().isEmpty()) { "deterministic core produced committed-root corruption" }
    require(storage.scanStagingResidues().isEmpty()) { "deterministic core left staging residue" }

    val committed = exportsRoot.resolve(SESSION_ID)
    Files.copy(committed.resolve("manifest.json"), output.resolve("manifest.json"))
    Files.copy(committed.resolve("frames.ndjson"), output.resolve("frames.ndjson"))
    decodeManifest(Files.readString(output.resolve("manifest.json"), StandardCharsets.UTF_8))
    Files.readAllLines(output.resolve("frames.ndjson"), StandardCharsets.UTF_8)
        .filter { it.isNotEmpty() }
        .forEach(::decodeFrame)
    deleteTree(stagingRoot)
    deleteTree(exportsRoot)
    println("generated ${output.resolve("manifest.json")}")
    println("generated ${output.resolve("frames.ndjson")}")
}

private fun syntheticManifest() = SessionManifest(
    schemaVersion = SemanticVersion("1.1.0"),
    sessionId = Uuid(SESSION_ID),
    sessionKind = "fixture_replay",
    participantPseudonymId = "synthetic-kotlin-export-01",
    captureProtocolVersion = SemanticVersion("1.0.0"),
    harnessGitSha = "0".repeat(39) + "6",
    device = Device("android", "synthetic-os-1", "synthetic-kotlin-device", null, false),
    posePipeline = PosePipeline(
        "synthetic_pose_backend",
        "1.1.0",
        "synthetic_pose_model",
        Sha256("0".repeat(63) + "7"),
        "cpu",
        "video",
        1,
        0.5,
        0.5,
        0.5,
        "mediapipe_pose_33_v1",
    ),
    camera = Camera("fixture", 2, 2, 0, false, 30.0, "rgb24", "sRGB", "full", null, "synthetic_fixture_monotonic_ns", "ordered_no_drop"),
    modelInput = ModelInput(256, 256, SemanticVersion("1.0.0"), SemanticVersion("1.0.0")),
    inferenceDrainTimeoutMs = 1,
    cadence = Cadence(30.0, listOf(CadenceSample(100_000_000, 30.0)), null),
    clock = Clock("synthetic_fixture_monotonic_ns", 100_000_000, "2026-08-17T00:00:00Z", "2026-08-17T00:00:01Z"),
    availableObservations = AvailableObservations(true, true),
    mediaReferences = listOf(MediaReference("conformance_fixture", "synthetic://kotlin-export", Sha256("0".repeat(63) + "8"), null, null)),
    counts = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0),
    machineInferenceFailures = emptyList(),
    captureQualityObserved = CaptureQualityObserved(true, true, true, "synthetic_uniform", false, "synthetic export; no participant capture"),
    studyContext = mapOf("environment" to kotlinx.serialization.json.JsonPrimitive("synthetic"), "angle_deg_nominal" to kotlinx.serialization.json.JsonPrimitive(0)),
)

private class FileStorage(
    buildOutput: Path,
    private val stagingRoot: Path,
    private val exportsRoot: Path,
) : StoragePort {
    override val roots = StorageRoots(
        filesDir = buildOutput.toAbsolutePath().normalize().toString(),
        stagingRoot = stagingRoot.toAbsolutePath().normalize().toString(),
        exportsRoot = exportsRoot.toAbsolutePath().normalize().toString(),
    )

    private val attempts = linkedMapOf<String, FileAttempt>()

    override fun createStagingAttempt(sessionId: Uuid): StagingAttempt {
        val attemptPath = stagingRoot.resolve("${sessionId.value}.1.staging")
        Files.createDirectories(attemptPath)
        val attempt = FileAttempt(attemptPath)
        attempts[attemptPath.toString()] = attempt
        return attempt
    }

    override fun appendLine(attempt: StagingAttempt, line: String) {
        val file = fileAttempt(attempt).framesPath
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    override fun flush(attempt: StagingAttempt) {
        fileAttempt(attempt).flushed = true
    }

    override fun fsyncFile(attempt: StagingAttempt, file: StorageFile) {
        val path = when (file) {
            StorageFile.FRAMES -> fileAttempt(attempt).framesPath
            StorageFile.MANIFEST -> fileAttempt(attempt).manifestPath
            StorageFile.ABORT_MARKER -> fileAttempt(attempt).abortPath
        }
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    override fun reconcile(attempt: StagingAttempt): StreamReconciliation {
        val lines = if (Files.exists(fileAttempt(attempt).framesPath)) {
            Files.readAllLines(fileAttempt(attempt).framesPath, StandardCharsets.UTF_8).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val frames = lines.map(::decodeFrame)
        return StreamReconciliation(frames.size.toLong(), frames.count { it.posePresent }.toLong())
    }

    override fun writeManifest(attempt: StagingAttempt, encodedManifest: String) {
        Files.writeString(fileAttempt(attempt).manifestPath, encodedManifest, StandardCharsets.UTF_8)
    }

    override fun fsyncDirectory(path: String) {
        FileChannel.open(Paths.get(path), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun destinationExists(destinationPath: String): Boolean = Files.exists(Paths.get(destinationPath))

    override fun renameNoReplace(attempt: StagingAttempt, destinationPath: String) {
        val destination = Paths.get(destinationPath)
        check(!Files.exists(destination)) { "destination already exists" }
        Files.createDirectories(destination.parent)
        Files.move(fileAttempt(attempt).directory, destination)
        fileAttempt(attempt).renamed = true
    }

    override fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts) {
        Files.writeString(
            fileAttempt(attempt).abortPath,
            "$reason;emitted=${sealedCounters.inferenceRecordsEmitted};pose=${sealedCounters.poseDetectedRecords}",
            StandardCharsets.UTF_8,
        )
    }

    override fun scanCommittedRoot(): List<ExportRootCorruption> = if (!Files.exists(exportsRoot)) {
        emptyList()
    } else {
        Files.list(exportsRoot).use { paths ->
            paths.filter { Files.isDirectory(it) && !Files.exists(it.resolve("manifest.json")) }
                .map { ExportRootCorruption(it.toString()) }
                .toList()
        }
    }

    override fun scanStagingResidues(): List<StagingResidue> = attempts.values
        .filter { !it.renamed }
        .map { attempt ->
            if (Files.exists(attempt.abortPath)) {
                StagingResidue.Abandoned(attempt.path, AbortMarker("abandoned", Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0)))
            } else {
                StagingResidue.CrashResidue(attempt.path)
            }
        }

    private fun fileAttempt(attempt: StagingAttempt): FileAttempt = attempts.getValue(attempt.path)
}

private class FileAttempt(val directory: Path) : StagingAttempt {
    override val path: String = directory.toString()
    val framesPath: Path = directory.resolve("frames.ndjson")
    val manifestPath: Path = directory.resolve("manifest.json")
    val abortPath: Path = directory.resolve("abort.marker")
    var flushed = false
    var renamed = false
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
