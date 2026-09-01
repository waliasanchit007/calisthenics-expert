package com.calisthenics.diagnostic.contract

import com.calisthenics.diagnostic.conformance.SyntheticFixture
import com.calisthenics.diagnostic.export.AbortMarker
import com.calisthenics.diagnostic.export.ExportRootCorruption
import com.calisthenics.diagnostic.export.StagingAttempt
import com.calisthenics.diagnostic.export.StagingResidue
import com.calisthenics.diagnostic.export.StorageFile
import com.calisthenics.diagnostic.export.StorageLocationId
import com.calisthenics.diagnostic.export.StorageLocationRegistry
import com.calisthenics.diagnostic.export.StoragePort
import com.calisthenics.diagnostic.export.DurableArtifact
import com.calisthenics.diagnostic.export.ArtifactReconciliation
import com.calisthenics.diagnostic.export.DefaultDurableSessionWriter
import com.calisthenics.diagnostic.export.StorageRoots
import com.calisthenics.diagnostic.export.StreamReconciliation
import com.calisthenics.diagnostic.session.DeterministicCore
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SessionConfiguration
import com.calisthenics.diagnostic.study.M05BArtifactCodec
import com.calisthenics.diagnostic.study.M05BAttemptChain
import com.calisthenics.diagnostic.study.M05BBatteryObservation
import com.calisthenics.diagnostic.study.M05BDurableAdmissionBoundary
import com.calisthenics.diagnostic.study.M05BExecutionOrder
import com.calisthenics.diagnostic.study.M05BObservedStart
import com.calisthenics.diagnostic.study.M05BStartEnvelope
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.Comparator

private val SESSION_ID = SyntheticFixture.sessionId.value
private const val M05B_ADMISSION_SESSION_ID = "00000000-0000-4000-8000-000000000001"
private const val M05B_PRODUCER_GIT_SHA = "dddddddddddddddddddddddddddddddddddddddd"

fun main(args: Array<String>) {
    require(args.size == 1) { "expected exactly one output directory below shared/build/conformance" }
    val output = Paths.get(args[0]).toAbsolutePath().normalize()
    val normalizedOutput = output.toString().replace('\\', '/')
    require("/build/conformance/" in normalizedOutput) {
        "synthetic export output must be below shared/build/conformance: $output"
    }

    deleteTree(output)
    val buildOutput = output.parent
    val diagnosticHarnessRoot = buildOutput.resolve(".deterministic-core-storage")
    val stagingRoot = diagnosticHarnessRoot.resolve(
        StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_STAGING),
    )
    val exportsRoot = diagnosticHarnessRoot.resolve(
        StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_EXPORT),
    )
    deleteTree(diagnosticHarnessRoot)
    Files.createDirectories(output)
    val storage = FileStorage(buildOutput, stagingRoot, exportsRoot)

    val result = DeterministicCore(
        configuration = SessionConfiguration(
            sessionId = Uuid(SESSION_ID),
            schemaVersion = SemanticVersion("1.1.0"),
            imageWidth = 256,
            imageHeight = 256,
        ),
        manifestTemplate = SyntheticFixture.manifestTemplate(),
        storage = storage,
    ).run(
        sourceRgb24 = SyntheticFixture.sourceRgb24,
        sourceWidth = SyntheticFixture.sourceWidth,
        sourceHeight = SyntheticFixture.sourceHeight,
    )
    require(result.finalization is FinalizationResult.Published) { "deterministic core did not publish" }
    require(storage.scanCommittedRoot().isEmpty()) { "deterministic core produced committed-root corruption" }
    require(storage.scanStagingResidues().isEmpty()) { "deterministic core left staging residue" }

    val committed = exportsRoot.resolve(SESSION_ID)
    Files.copy(committed.resolve("manifest.json"), output.resolve("manifest.json"))
    Files.copy(committed.resolve("frames.ndjson"), output.resolve("frames.ndjson"))
    val m05bArtifacts = generateM05BArtifacts(storage)
    Files.write(output.resolve("start-envelope.json"), m05bArtifacts.envelopeBytes)
    Files.write(output.resolve("execution-order.json"), m05bArtifacts.orderBytes)
    Files.write(output.resolve("run-admission.json"), m05bArtifacts.admissionBytes)
    decodeManifest(Files.readString(output.resolve("manifest.json"), StandardCharsets.UTF_8))
    Files.readAllLines(output.resolve("frames.ndjson"), StandardCharsets.UTF_8)
        .filter { it.isNotEmpty() }
        .forEach(::decodeFrame)
    deleteTree(diagnosticHarnessRoot)
    println("generated ${output.resolve("manifest.json")}")
    println("generated ${output.resolve("frames.ndjson")}")
    println("generated ${output.resolve("start-envelope.json")}")
    println("generated ${output.resolve("execution-order.json")}")
    println("generated ${output.resolve("run-admission.json")}")
}

private data class GeneratedM05BArtifacts(
    val envelopeBytes: ByteArray,
    val orderBytes: ByteArray,
    val admissionBytes: ByteArray,
)

private fun generateM05BArtifacts(storage: StoragePort): GeneratedM05BArtifacts {
    val order = M05BExecutionOrder(listOf(10, 15, 30), "m05b-synthetic-order", M05B_PRODUCER_GIT_SHA)
    val orderArtifact = M05BArtifactCodec.bindOrder(order, "execution-order.json")
    val envelope = M05BStartEnvelope(
        envelopeId = "m05b-synthetic-envelope",
        procedureVersion = "1.0.0",
        procedureSha256 = "e".repeat(64),
        appSha256 = "a".repeat(64),
        modelSha256 = "b".repeat(64),
        runtimeVersion = "mediapipe-tasks-vision-1.0.0",
        delegate = "CPU",
        preprocessingVersion = "1.0.0",
        deviceUnitDigest = "c".repeat(64),
        deviceClass = "physical_device",
        manufacturer = "samsung",
        model = "SM-S938B",
        deviceBuild = "synthetic-build",
        apiLevel = 36,
        charging = false,
        powerSource = "battery",
        hostBatteryOverrideRejected = true,
        powerSaveMode = false,
        baselineBatteryObservation = M05BBatteryObservation("percent", 80.0),
        hostAdbTransport = "wifi",
        appTransport = "wifi",
        networkIdentityPersisted = false,
        ambientToken = "ambient-01",
        ambientConstancy = "constant",
        ambientConstancySource = "operator_attestation_end_of_baseline",
        keepAwake = true,
        brightnessMode = "manual",
        brightnessRaw = 0.5,
        brightnessScaleMin = 0.0,
        brightnessScaleMax = 1.0,
        cameraFps = 30,
        inferenceCadencesFps = listOf(10, 15, 30),
        durationNs = 1_500_000_000_000L,
        overshootNs = 1_000_000_000L,
        cooldownMaxDurationNs = 600_000_000_000L,
        cooldownSamplingIntervalNs = 1_000_000_000L,
        thermalStatus = "none",
        batteryTemperatureMinC = 29.0,
        batteryTemperatureMaxC = 30.0,
        batteryTemperatureRangeC = 1.0,
        orderPath = orderArtifact.path,
        orderSha256 = orderArtifact.sha256,
        producerGitSha = M05B_PRODUCER_GIT_SHA,
    )
    val observed = M05BObservedStart(
        appSha256 = envelope.appSha256,
        modelSha256 = envelope.modelSha256,
        runtimeVersion = envelope.runtimeVersion,
        delegate = envelope.delegate,
        preprocessingVersion = envelope.preprocessingVersion,
        deviceUnitDigest = envelope.deviceUnitDigest,
        deviceClass = envelope.deviceClass,
        manufacturer = envelope.manufacturer,
        model = envelope.model,
        deviceBuild = envelope.deviceBuild,
        apiLevel = envelope.apiLevel,
        charging = envelope.charging,
        powerSource = envelope.powerSource,
        hostBatteryOverrideRejected = envelope.hostBatteryOverrideRejected,
        powerSaveMode = envelope.powerSaveMode,
        batteryObservation = M05BBatteryObservation("percent", 79.0),
        deviceIdleMode = false,
        hostAdbTransport = envelope.hostAdbTransport,
        appTransport = envelope.appTransport,
        networkIdentityPersisted = envelope.networkIdentityPersisted,
        ambientToken = envelope.ambientToken,
        ambientConstancy = envelope.ambientConstancy,
        ambientConstancyOperatorSupplied = true,
        keepAwake = envelope.keepAwake,
        brightnessMode = envelope.brightnessMode,
        brightnessRaw = envelope.brightnessRaw,
        brightnessScaleMin = envelope.brightnessScaleMin,
        brightnessScaleMax = envelope.brightnessScaleMax,
        thermalStatus = envelope.thermalStatus,
        batteryTemperatureC = 29.5,
    )
    val writer = DefaultDurableSessionWriter(storage, Uuid(M05B_ADMISSION_SESSION_ID))
    var captureStarted = false
    val envelopeArtifact = M05BArtifactCodec.bindEnvelope(envelope, "start-envelope.json")
    val outcome = M05BDurableAdmissionBoundary(writer).admitAndStart(
        envelopeArtifact = envelopeArtifact,
        orderArtifact = orderArtifact,
        observedStart = observed,
        attemptHistory = emptyList(),
        inputClass = "empty_scene",
        operatorAttestedNoPerson = true,
        startCapture = { captureStarted = true },
    )
    require(outcome.result.admitted && captureStarted) { "synthetic M0.5B admission was not admitted" }
    return GeneratedM05BArtifacts(
        envelopeBytes = envelopeArtifact.exactBytes,
        orderBytes = orderArtifact.exactBytes,
        admissionBytes = outcome.admissionBytes,
    )
}

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

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        Files.write(
            artifactPath(attempt, artifact),
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    override fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact) = Unit

    override fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        FileChannel.open(artifactPath(attempt, artifact), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            .use { it.force(true) }
    }

    override fun replaceCheckpoint(attempt: StagingAttempt, encodedCheckpoint: String) {
        val target = fileAttempt(attempt).checkpointPath
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temporary, encodedCheckpoint, StandardCharsets.UTF_8)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        fsyncDirectory(fileAttempt(attempt).directory.toString())
    }

    override fun reconcileArtifact(attempt: StagingAttempt, artifact: DurableArtifact): ArtifactReconciliation {
        val bytes = if (Files.exists(artifactPath(attempt, artifact))) {
            Files.readAllBytes(artifactPath(attempt, artifact))
        } else {
            ByteArray(0)
        }
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
        return ArtifactReconciliation(
            bytes.size.toLong(),
            com.calisthenics.diagnostic.hash.Sha256Digest.hex(bytes),
            lines.size.toLong(),
            if (artifact == DurableArtifact.POSE_STREAM) {
                lines.count { decodeFrame("$it\n").posePresent }.toLong()
            } else {
                null
            },
        )
    }

    private fun fileAttempt(attempt: StagingAttempt): FileAttempt = attempts.getValue(attempt.path)

    private fun artifactPath(attempt: StagingAttempt, artifact: DurableArtifact): Path = when (artifact) {
        DurableArtifact.POSE_STREAM -> fileAttempt(attempt).framesPath
        DurableArtifact.TELEMETRY -> fileAttempt(attempt).telemetryPath
        DurableArtifact.RUN_ADMISSION -> fileAttempt(attempt).admissionPath
        // The synthetic export is pose-only and deterministic; it records no video. The path is
        // still named so the artifact set stays total rather than relying on an else branch.
        DurableArtifact.VIDEO -> fileAttempt(attempt).framesPath.resolveSibling("video.mp4")
    }
}

private class FileAttempt(val directory: Path) : StagingAttempt {
    override val path: String = directory.toString()
    val framesPath: Path = directory.resolve("frames.ndjson")
    val telemetryPath: Path = directory.resolve("telemetry.ndjson")
    val admissionPath: Path = directory.resolve("run-admission.json")
    val checkpointPath: Path = directory.resolve("checkpoint")
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
