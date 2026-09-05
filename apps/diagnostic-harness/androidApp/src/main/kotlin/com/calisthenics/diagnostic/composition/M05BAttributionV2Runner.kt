package com.calisthenics.diagnostic.composition

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.calisthenics.diagnostic.camera.CameraXFrameSource
import com.calisthenics.diagnostic.camera.SourceFpsRange
import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.live.LiveFrameAdmissionPort
import com.calisthenics.diagnostic.live.LiveFrameClassification
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.study.AttributionV2ArmStatus
import com.calisthenics.diagnostic.study.AttributionV2BackendObservation
import com.calisthenics.diagnostic.study.AttributionV2Callback
import com.calisthenics.diagnostic.study.AttributionV2CallbackArm
import com.calisthenics.diagnostic.study.AttributionV2DeviceSource
import com.calisthenics.diagnostic.study.AttributionV2EndToEndRun
import com.calisthenics.diagnostic.study.AttributionV2FpsRange
import com.calisthenics.diagnostic.study.AttributionV2ReplicateObservation
import com.calisthenics.diagnostic.study.AttributionV2RunIdentity
import com.calisthenics.diagnostic.study.AttributionV2SelectorObservation
import com.calisthenics.diagnostic.study.AttributionV2SourceObservation
import com.calisthenics.diagnostic.study.AttributionV2TerminalClass
import com.calisthenics.diagnostic.study.M05BAttributionV2DeviceObservation
import com.calisthenics.diagnostic.study.M05BAttributionV2Protocol
import com.calisthenics.diagnostic.study.attributionV2CallbackBlockers
import com.calisthenics.diagnostic.study.backendSupports
import com.calisthenics.diagnostic.study.deriveM05BAttributionV2BlockingCodes
import com.calisthenics.diagnostic.study.productionSelectorTimestamps
import com.calisthenics.diagnostic.study.sourceSupports
import com.calisthenics.diagnostic.hash.Sha256Digest
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SourceCaptureResult(val elapsedNs: Long, val stoppedByCap: Boolean)

interface M05BAttributionV2SourceFrame {
    val timestampNs: Long
    val aeTargetFpsRange: AttributionV2FpsRange?
}

interface M05BAttributionV2SourceArm {
    fun capture(capNs: Long, onFrame: (M05BAttributionV2SourceFrame) -> Boolean): SourceCaptureResult
}

enum class BackendRunMode { CAPACITY, END_TO_END }

data class BackendRunSpec(
    val mode: BackendRunMode,
    val cadenceFps: Int?,
    val armCapNs: Long,
    val warmupCompletions: Int,
    val warmupElapsedNs: Long,
    val measuredCompletions: Int,
)

data class BackendRunResult(
    val elapsedNs: Long,
    val stoppedByCap: Boolean,
    val warmupCompletions: Int,
    val warmupElapsedNs: Long,
    val measuredServiceDurationsNs: List<Long>,
    val inferenceFailures: Int,
    val endToEndRun: AttributionV2EndToEndRun? = null,
    val initialThermalStatus: Int = 0,
    val finalThermalStatus: Int = initialThermalStatus,
    val failureCode: String? = null,
)

interface M05BAttributionV2Backend {
    fun run(spec: BackendRunSpec): BackendRunResult
}

fun interface M05BAttributionV2Selector {
    fun select(timestampsNs: List<Long>, cadenceFps: Int): List<Long>
}

data class ThermalCooldown(val samples: List<Int>, val recovered: Boolean)

interface M05BAttributionV2Thermal {
    fun boundaryStatus(): Int

    fun awaitBoundary(
        status: Int,
        sampleIntervalNs: Long,
        maximumDurationNs: Long,
    ): ThermalCooldown
}

interface M05BAttributionV2ArtifactStorage {
    fun createTemporary(runId: String): String
    fun writeTemporary(token: String, bytes: ByteArray)
    fun fsyncTemporary(token: String)
    fun renameTemporary(token: String, runId: String): String
    fun fsyncDirectory(runId: String)
    fun reopen(relativePath: String): ByteArray
}

fun interface M05BAttributionV2StatusSink {
    fun send(record: String)
}

fun interface M05BAttributionV2Clock {
    fun nowNs(): Long
}

data class M05BAttributionV2Reference(
    val relativePath: String,
    val sha256: String,
    val lengthBytes: Int,
)

data class M05BAttributionV2RunResult(
    val observation: M05BAttributionV2DeviceObservation,
    val reference: M05BAttributionV2Reference,
    val statusRecord: String,
)

class M05BAttributionV2Runner(
    private val dependencies: Dependencies,
) {
    data class Dependencies(
        val protocolSha256: String,
        val device: AttributionV2DeviceSource,
        val identity: AttributionV2RunIdentity,
        val sourceArmFactory: () -> M05BAttributionV2SourceArm,
        val backendArmFactory: () -> M05BAttributionV2Backend,
        val selector: M05BAttributionV2Selector,
        val thermal: M05BAttributionV2Thermal,
        val storage: M05BAttributionV2ArtifactStorage,
        val statusSink: M05BAttributionV2StatusSink,
        val clock: M05BAttributionV2Clock = M05BAttributionV2Clock { SystemClock.elapsedRealtimeNanos() },
    )

    fun run(runId: String): M05BAttributionV2RunResult {
        require(runId.matches(RUN_ID)) { "runId must be a safe relative identifier" }
        val boundaryThermalStatus = dependencies.thermal.boundaryStatus()
        val source = runSourceArm(boundaryThermalStatus)
        var backend: AttributionV2BackendObservation? = null
        var selector: AttributionV2SelectorObservation? = null
        val replicates = mutableListOf<AttributionV2ReplicateObservation>()
        var sourceObservation = source

        if (sourceObservation.blockingCodes.isEmpty()) {
            val cooldown = dependencies.thermal.awaitBoundary(
                boundaryThermalStatus,
                M05BAttributionV2Protocol.COOLDOWN_SAMPLE_NS,
                M05BAttributionV2Protocol.COOLDOWN_MAX_NS,
            )
            if (!cooldown.recovered) {
                sourceObservation = sourceObservation.copy(
                    status = AttributionV2ArmStatus.BLOCKED,
                    blockingCodes = (sourceObservation.blockingCodes + "ATTRIBUTION.THERMAL_STATE_MISMATCH")
                        .distinct().sorted(),
                )
            } else {
                backend = runBackendArm(boundaryThermalStatus)
            }
        }

        val arm0Blockers = sourceObservation.blockingCodes + (backend?.blockingCodes.orEmpty())
        if (backend != null && arm0Blockers.isEmpty()) {
            selector = runSelectorArm(sourceObservation.measuredTimestampsNs)
            if (selector.blockingCodes.isEmpty()) {
                replicates += runReplicates(boundaryThermalStatus)
            }
        }

        var observation = M05BAttributionV2DeviceObservation(
            protocolSha256 = dependencies.protocolSha256,
            device = dependencies.device,
            identity = dependencies.identity,
            sourceArm = sourceObservation,
            backendArm = backend,
            selectorArm = selector,
            endToEndReplicates = replicates,
            blockingCodes = emptyList(),
        )
        observation = observation.copy(blockingCodes = deriveM05BAttributionV2BlockingCodes(observation))
        val observationBytes = com.calisthenics.diagnostic.study.renderM05BAttributionV2Evidence(observation)
            .encodeToByteArray()
        val temporary = dependencies.storage.createTemporary(runId)
        dependencies.storage.writeTemporary(temporary, observationBytes)
        dependencies.storage.fsyncTemporary(temporary)
        val relativePath = dependencies.storage.renameTemporary(temporary, runId)
        dependencies.storage.fsyncDirectory(runId)
        val reopenedBytes = dependencies.storage.reopen(relativePath)
        val reference = M05BAttributionV2Reference(
            relativePath = relativePath,
            sha256 = Sha256Digest.hex(reopenedBytes),
            lengthBytes = reopenedBytes.size,
        )
        val statusRecord = buildJsonObject {
            put("record_kind", "m05b_attribution_v2_reference")
            put("protocol_sha256", dependencies.protocolSha256)
            put("relative_path", reference.relativePath)
            put("sha256", reference.sha256)
            put("length_bytes", reference.lengthBytes)
        }.toString()
        dependencies.statusSink.send(statusRecord)
        return M05BAttributionV2RunResult(observation, reference, statusRecord)
    }

    private fun runSourceArm(boundaryThermalStatus: Int): AttributionV2SourceObservation {
        val frames = mutableListOf<M05BAttributionV2SourceFrame>()
        val capture = dependencies.sourceArmFactory().capture(M05BAttributionV2Protocol.ARM_CAP_NS) { frame ->
            frames += frame
            frames.size < M05BAttributionV2Protocol.SOURCE_WARMUP_CALLBACKS +
                M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS
        }
        val measured = frames.drop(M05BAttributionV2Protocol.SOURCE_WARMUP_CALLBACKS)
            .take(M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS)
        val blockers = buildList {
            if (capture.stoppedByCap || measured.size != M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS) {
                add("ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT")
            }
            if (frames.any { it.aeTargetFpsRange == null } ||
                frames.mapNotNull { it.aeTargetFpsRange }.distinct().size != 1 ||
                frames.firstOrNull()?.aeTargetFpsRange != AttributionV2FpsRange(30, 30)
            ) add("ATTRIBUTION.SOURCE_CONFIGURATION")
            if (measured.zipWithNext().any { (first, second) -> second.timestampNs <= first.timestampNs }) {
                add("ATTRIBUTION.SOURCE_TIMESTAMP_INVALID")
            }
            if (measured.size == M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS &&
                M05BAttributionV2Protocol.TARGET_CADENCES_FPS.any {
                    !sourceSupports(measured.map { it.timestampNs }, it)
                }
            ) add("ATTRIBUTION.SOURCE_LIMITED")
        }.distinct().sorted()
        return AttributionV2SourceObservation(
            status = if (blockers.isEmpty()) AttributionV2ArmStatus.RECORDED else AttributionV2ArmStatus.BLOCKED,
            blockingCodes = blockers,
            warmupCount = frames.size.coerceAtMost(M05BAttributionV2Protocol.SOURCE_WARMUP_CALLBACKS),
            measuredTimestampsNs = measured.map { it.timestampNs },
            observedAeTargetFps = frames.firstOrNull()?.aeTargetFpsRange,
            thermalStatus = boundaryThermalStatus,
        )
    }

    private fun runBackendArm(boundaryThermalStatus: Int): AttributionV2BackendObservation {
        val result = dependencies.backendArmFactory().run(
            BackendRunSpec(
                mode = BackendRunMode.CAPACITY,
                cadenceFps = null,
                armCapNs = M05BAttributionV2Protocol.ARM_CAP_NS,
                warmupCompletions = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS,
                warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
                measuredCompletions = M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS,
            ),
        )
        val blockers = buildList {
            result.failureCode?.let(::add)
            if (result.stoppedByCap || result.warmupCompletions < M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS ||
                result.warmupElapsedNs < M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS ||
                result.measuredServiceDurationsNs.size != M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS
            ) add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
            if (result.inferenceFailures != 0) add("ATTRIBUTION.INFERENCE_FAILURE")
            if (M05BAttributionV2Protocol.TARGET_CADENCES_FPS.any {
                    !backendSupports(result.measuredServiceDurationsNs, it)
                }
            ) add("ATTRIBUTION.BACKEND_LIMITED")
        }.distinct().sorted()
        return AttributionV2BackendObservation(
            status = if (blockers.isEmpty()) AttributionV2ArmStatus.RECORDED else AttributionV2ArmStatus.BLOCKED,
            blockingCodes = blockers,
            warmupCount = result.warmupCompletions.coerceAtMost(M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS),
            warmupElapsedNs = result.warmupElapsedNs,
            measuredServiceDurationsNs = result.measuredServiceDurationsNs
                .take(M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS),
            inferenceFailures = result.inferenceFailures,
            thermalStatus = result.initialThermalStatus.takeIf { it >= 0 } ?: boundaryThermalStatus,
        )
    }

    private fun runSelectorArm(timestampsNs: List<Long>): AttributionV2SelectorObservation {
        val productionSelections = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith { cadence ->
            dependencies.selector.select(timestampsNs, cadence)
        }
        val hostSelections = M05BAttributionV2Protocol.TARGET_CADENCES_FPS.associateWith { cadence ->
            productionSelectorTimestamps(timestampsNs, cadence)
        }
        val blockers = buildList {
            if (productionSelections != hostSelections ||
                M05BAttributionV2Protocol.TARGET_CADENCES_FPS.zipWithNext().any { (lower, upper) ->
                    productionSelections.getValue(lower).size >= productionSelections.getValue(upper).size
                }
            ) add("ATTRIBUTION.SELECTOR_DIVERGENCE")
        }
        return AttributionV2SelectorObservation(
            status = if (blockers.isEmpty()) AttributionV2ArmStatus.RECORDED else AttributionV2ArmStatus.BLOCKED,
            blockingCodes = blockers,
            productionSelections = productionSelections,
            hostSelections = hostSelections,
        )
    }

    private fun runReplicates(boundaryThermalStatus: Int): List<AttributionV2ReplicateObservation> {
        val replicates = mutableListOf<AttributionV2ReplicateObservation>()
        for (order in M05BAttributionV2Protocol.REPLICATE_ORDERS) {
            val runs = mutableListOf<AttributionV2EndToEndRun>()
            val cooldownStatuses = mutableListOf<Int>()
            val blockers = mutableListOf<String>()
            for (cadence in order) {
                val cooldown = dependencies.thermal.awaitBoundary(
                    boundaryThermalStatus,
                    M05BAttributionV2Protocol.COOLDOWN_SAMPLE_NS,
                    M05BAttributionV2Protocol.COOLDOWN_MAX_NS,
                )
                cooldownStatuses += cooldown.samples
                if (!cooldown.recovered) {
                    blockers += "ATTRIBUTION.THERMAL_STATE_MISMATCH"
                    break
                }
                val result = dependencies.backendArmFactory().run(
                    BackendRunSpec(
                        mode = BackendRunMode.END_TO_END,
                        cadenceFps = cadence,
                        armCapNs = M05BAttributionV2Protocol.ARM_CAP_NS,
                        warmupCompletions = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS,
                        warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
                        measuredCompletions = M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS,
                    ),
                )
                result.failureCode?.let { blockers += it }
                if (result.stoppedByCap || result.warmupCompletions < M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS ||
                    result.warmupElapsedNs < M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS ||
                    result.measuredServiceDurationsNs.size != M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS
                ) blockers += "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT"
                if (result.inferenceFailures != 0) blockers += "ATTRIBUTION.INFERENCE_FAILURE"
                val run = result.endToEndRun?.copy(
                    cadenceFps = cadence,
                    initialThermalStatus = result.initialThermalStatus,
                    finalThermalStatus = result.finalThermalStatus,
                )
                if (run == null) {
                    blockers += "ATTRIBUTION.REPLICATE_DISAGREEMENT"
                } else {
                    runs += run
                    blockers += attributionV2CallbackBlockers(
                        AttributionV2CallbackArm(
                            deliveredCallbackIds = run.deliveredCallbackIds,
                            terminalCallbacks = run.terminalCallbacks,
                            submittedInferenceIds = run.submittedInferenceIds,
                            pendingInferenceIds = run.pendingInferenceIds,
                        ),
                    )
                }
            }
            val distinctBlockers = blockers.distinct().sorted()
            replicates += AttributionV2ReplicateObservation(
                order = order,
                status = if (distinctBlockers.isEmpty()) AttributionV2ArmStatus.RECORDED else AttributionV2ArmStatus.BLOCKED,
                blockingCodes = distinctBlockers,
                cooldownThermalStatuses = cooldownStatuses,
                runs = runs,
            )
            if ("ATTRIBUTION.THERMAL_STATE_MISMATCH" in distinctBlockers) break
        }
        return replicates
    }

    private companion object {
        val RUN_ID = Regex("^[A-Za-z0-9._-]+$")
    }
}

/** CameraX adapter used only by the internal v2 device gate. */
internal class M05BAttributionV2CameraXSourceArm(
    private val source: CameraXFrameSource,
) : M05BAttributionV2SourceArm {
    override fun capture(capNs: Long, onFrame: (M05BAttributionV2SourceFrame) -> Boolean): SourceCaptureResult {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val finished = CountDownLatch(1)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val capExecutor = Executors.newSingleThreadScheduledExecutor()
        var hitCap = false
        fun stop(cap: Boolean) {
            if (stopped.compareAndSet(false, true)) {
                hitCap = cap
                source.stop { finished.countDown() }
            }
        }
        val timer = capExecutor.schedule({ stop(true) }, capNs, TimeUnit.NANOSECONDS)
        source.start(
            onSourceStarted = {},
            onFrame = { frame ->
                val continueCapture = onFrame(frame.toAttributionSourceFrame())
                if (!continueCapture) stop(false)
            },
            onFrameRejected = { timestamp ->
                if (timestamp != null && SystemClock.elapsedRealtimeNanos() - startNs >= capNs) stop(true)
            },
            onFailure = { stop(false) },
        )
        finished.await(capNs + 5_000_000_000L, TimeUnit.NANOSECONDS)
        stop(hitCap)
        timer.cancel(false)
        capExecutor.shutdownNow()
        return SourceCaptureResult(SystemClock.elapsedRealtimeNanos() - startNs, hitCap)
    }
}

private fun OwnedYuvFrame.toAttributionSourceFrame() = object : M05BAttributionV2SourceFrame {
    override val timestampNs: Long = this@toAttributionSourceFrame.timestampNs
    override val aeTargetFpsRange: AttributionV2FpsRange? = observedAeTargetFpsRange?.toAttributionRange()
}

private fun SourceFpsRange.toAttributionRange() = AttributionV2FpsRange(lower, upper)

/** App-private atomic writer. The runner owns the required durability order. */
internal class AndroidM05BAttributionV2ArtifactStorage(
    private val diagnosticRoot: File,
) : M05BAttributionV2ArtifactStorage {
    private val temporaryFiles = mutableMapOf<String, File>()

    override fun createTemporary(runId: String): String {
        require(runId.matches(Regex("^[A-Za-z0-9._-]+$")))
        val directory = File(diagnosticRoot, "attribution-v2/$runId")
        check(directory.mkdirs() || directory.isDirectory)
        val token = "$runId.tmp"
        val file = File(directory, "observation.json.tmp")
        check(!file.exists()) { "attribution-v2 observation already staged" }
        temporaryFiles[token] = file
        return token
    }

    override fun writeTemporary(token: String, bytes: ByteArray) {
        val file = checkNotNull(temporaryFiles[token])
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    override fun fsyncTemporary(token: String) {
        val file = checkNotNull(temporaryFiles[token])
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    override fun renameTemporary(token: String, runId: String): String {
        val file = checkNotNull(temporaryFiles.remove(token))
        val target = File(file.parentFile, "observation.json")
        check(!target.exists()) { "attribution-v2 observation already published" }
        check(file.renameTo(target)) { "attribution-v2 observation rename failed" }
        return "attribution-v2/$runId/observation.json"
    }

    override fun fsyncDirectory(runId: String) {
        val directory = File(diagnosticRoot, "attribution-v2/$runId")
        val descriptor = android.system.Os.open(
            directory.absolutePath,
            android.system.OsConstants.O_RDONLY,
            0,
        )
        try {
            android.system.Os.fsync(descriptor)
        } finally {
            android.system.Os.close(descriptor)
        }
    }

    override fun reopen(relativePath: String): ByteArray {
        require(relativePath.matches(Regex("^attribution-v2/[A-Za-z0-9._-]+/observation\\.json$")))
        return File(diagnosticRoot, relativePath).readBytes()
    }
}

internal class AndroidM05BAttributionV2Thermal(
    private val powerManager: PowerManager,
) : M05BAttributionV2Thermal {
    override fun boundaryStatus(): Int = powerManager.currentThermalStatus

    override fun awaitBoundary(status: Int, sampleIntervalNs: Long, maximumDurationNs: Long): ThermalCooldown {
        val samples = mutableListOf<Int>()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        while (SystemClock.elapsedRealtimeNanos() - startedNs <= maximumDurationNs) {
            val current = powerManager.currentThermalStatus
            samples += current
            if (current == status) return ThermalCooldown(samples, true)
            Thread.sleep((sampleIntervalNs / 1_000_000L).coerceAtLeast(1L))
        }
        return ThermalCooldown(samples, false)
    }
}

/** The Arm 0B policy: cadence is always due, but the production single-flight slot remains real. */
internal class M05BAttributionV2SingleFlightAdmission : LiveFrameAdmissionPort {
    private var candidateReserved = false
    private var submissionInFlight = false
    private var stopping = false

    override fun predicates(captureTimestampNs: Long) = com.calisthenics.diagnostic.live.LiveAdmissionPredicates(
        cadenceDue = true,
        slotAvailable = !stopping && !candidateReserved && !submissionInFlight,
    )

    override fun reserve(captureTimestampNs: Long): LiveFrameClassification {
        if (stopping) return LiveFrameClassification.SESSION_STOPPING
        if (candidateReserved || submissionInFlight) return LiveFrameClassification.BACKPRESSURE
        candidateReserved = true
        return LiveFrameClassification.SUBMIT
    }

    override fun commitCandidate(preprocessingSucceeded: Boolean): LiveFrameClassification {
        check(candidateReserved)
        candidateReserved = false
        if (stopping) return LiveFrameClassification.SESSION_STOPPING
        if (!preprocessingSucceeded) return LiveFrameClassification.PREPROCESSING_FAILED
        submissionInFlight = true
        return LiveFrameClassification.SUBMIT
    }

    override fun completeSubmission() {
        check(submissionInFlight)
        submissionInFlight = false
    }

    override fun stop() { stopping = true }
}

/** Real composition adapter. It is constructed only by [M05BAttributionV2DeviceGateTest]. */
internal class AndroidM05BAttributionV2Backend(
    private val context: Context,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    private val cameraCapability: CameraCapabilityObservation,
    private val permissionGranted: Boolean,
    private val thermalStatus: () -> Int,
) : M05BAttributionV2Backend {
    override fun run(spec: BackendRunSpec): BackendRunResult {
        val observer = AttributionV2Observation()
        val startedAtNs = SystemClock.elapsedRealtimeNanos()
        val initialThermalStatus = thermalStatus()
        val composition = LiveCameraComposition.fromAndroid(
            context = context,
            lifecycleOwner = lifecycleOwner,
            cameraCapability = cameraCapability,
            permissionGranted = permissionGranted,
            attributionV2Observer = observer,
            frameAdmissionFactory = if (spec.mode == BackendRunMode.CAPACITY) {
                { M05BAttributionV2SingleFlightAdmission() }
            } else {
                { cadence -> com.calisthenics.diagnostic.live.LiveFrameAdmission(cadence) }
            },
        )
        val start = composition.start(
            configuredInferenceFps = spec.cadenceFps ?: 30,
            operatorAttestedNoPerson = true,
            recordingMode = com.calisthenics.diagnostic.telemetry.PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION,
        )
        if (start !is LiveCameraStartResult.Started) {
            composition.abandon()
            return BackendRunResult(
                elapsedNs = SystemClock.elapsedRealtimeNanos() - startedAtNs,
                stoppedByCap = false,
                warmupCompletions = 0,
                warmupElapsedNs = 0L,
                measuredServiceDurationsNs = emptyList(),
                inferenceFailures = 0,
                initialThermalStatus = initialThermalStatus,
                finalThermalStatus = thermalStatus(),
                failureCode = "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT",
            )
        }
        val requiredCompletions = spec.warmupCompletions + spec.measuredCompletions
        var completionCount = 0
        var completion: LiveCameraCompletion? = null
        try {
            while (SystemClock.elapsedRealtimeNanos() - startedAtNs < spec.armCapNs) {
                completionCount = observer.terminalSnapshot().count {
                    it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ||
                        it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
                }
                if (completionCount >= requiredCompletions) break
                Thread.sleep(10L)
            }
        } finally {
            completion = composition.stop()
        }
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedAtNs
        val admissions = observer.admissionSnapshot()
        val terminals = observer.terminalSnapshot()
        val byId = admissions.associateBy { it.callbackId }
        val completed = terminals.filter {
            it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ||
                it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
        }
        val warmup = completed.take(spec.warmupCompletions)
        val measured = completed.drop(spec.warmupCompletions).take(spec.measuredCompletions)
        val serviceDurations = measured.map { terminal ->
            val admission = checkNotNull(byId[terminal.callbackId])
            (terminal.completedNs - admission.observedSessionNs).coerceAtLeast(0L)
        }
        return BackendRunResult(
            elapsedNs = elapsedNs,
            stoppedByCap = completionCount < requiredCompletions && elapsedNs >= spec.armCapNs,
            warmupCompletions = warmup.size,
            warmupElapsedNs = warmup.lastOrNull()?.completedNs ?: 0L,
            measuredServiceDurationsNs = serviceDurations,
            inferenceFailures = measured.count {
                it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
            },
            endToEndRun = if (spec.mode == BackendRunMode.END_TO_END) {
                admissions.toEndToEndRun(
                    terminals,
                    spec.cadenceFps ?: error("missing cadence"),
                    byId,
                    "exports/${checkNotNull(completion).sessionId.value}",
                )
            } else {
                null
            },
            initialThermalStatus = initialThermalStatus,
            finalThermalStatus = thermalStatus(),
        )
    }
}

private fun List<AttributionV2ObservedAdmission>.toEndToEndRun(
    terminals: List<AttributionV2ObservedTerminal>,
    cadenceFps: Int,
    byId: Map<Long, AttributionV2ObservedAdmission>,
    sessionReference: String,
): AttributionV2EndToEndRun {
    fun terminalClass(
        admission: AttributionV2ObservedAdmission,
        terminal: com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition,
    ): AttributionV2TerminalClass = when (terminal) {
        com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ->
            AttributionV2TerminalClass.ADMITTED_COMPLETED
        com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED ->
            AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED
        com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.CADENCE_SELECTION ->
            predicateTerminal(admission, admission.shadowSelections[cadenceFps] ?: false)
        com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.BACKPRESSURE ->
            predicateTerminal(admission, admission.shadowSelections[cadenceFps] ?: false)
        com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.PREPROCESSING_FAILED ->
            AttributionV2TerminalClass.PREPROCESSING_FAILED
        com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.SESSION_STOPPING ->
            AttributionV2TerminalClass.SESSION_STOPPING
        else -> AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE
    }

    val terminalCallbacks = terminals.map { terminal ->
        val admission = checkNotNull(byId[terminal.callbackId])
        AttributionV2Callback(
            callbackId = terminal.callbackId,
            captureTimestampNs = admission.captureTimestampNs,
            productionClassification = admission.productionClassification,
            cadenceDue = admission.shadowSelections[cadenceFps] ?: false,
            slotAvailable = admission.productionPredicates.slotAvailable,
            terminalClass = terminalClass(admission, terminal.terminal),
        )
    }
    val submitted = filter { it.productionClassification == LiveFrameClassification.SUBMIT }
        .map { it.callbackId }
    val terminalIds = terminalCallbacks.map { it.callbackId }.toSet()
    return AttributionV2EndToEndRun(
        cadenceFps = cadenceFps,
        sessionReference = sessionReference,
        initialThermalStatus = 0,
        finalThermalStatus = 0,
        deliveredCallbackIds = map { it.callbackId },
        terminalCallbacks = terminalCallbacks,
        submittedInferenceIds = submitted,
        pendingInferenceIds = submitted.filterNot(terminalIds::contains).toSet(),
        measuredSpanNs = map { it.captureTimestampNs }.let { timestamps ->
            if (timestamps.size < 2) 0L else timestamps.last() - timestamps.first()
        },
    )
}

private fun predicateTerminal(
    admission: AttributionV2ObservedAdmission,
    cadenceDue: Boolean,
): AttributionV2TerminalClass = when {
    !cadenceDue && admission.productionPredicates.slotAvailable -> AttributionV2TerminalClass.CADENCE_ONLY
    cadenceDue && !admission.productionPredicates.slotAvailable -> AttributionV2TerminalClass.BACKPRESSURE_ONLY
    !cadenceDue && !admission.productionPredicates.slotAvailable -> AttributionV2TerminalClass.CADENCE_AND_BACKPRESSURE
    else -> AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE
}
