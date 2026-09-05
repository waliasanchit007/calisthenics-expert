package com.calisthenics.diagnostic.composition

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import com.calisthenics.diagnostic.study.AttributionV2CallbackPhase
import com.calisthenics.diagnostic.study.AttributionV2DeviceSource
import com.calisthenics.diagnostic.study.AttributionV2EndToEndRun
import com.calisthenics.diagnostic.study.AttributionV2EnvironmentBoundary
import com.calisthenics.diagnostic.study.AttributionV2EnvironmentSnapshot
import com.calisthenics.diagnostic.study.AttributionV2ArtifactRef
import com.calisthenics.diagnostic.study.AttributionV2FpsRange
import com.calisthenics.diagnostic.study.AttributionV2SessionBundle
import com.calisthenics.diagnostic.contract.Uuid
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

data class SourceCaptureResult(
    val elapsedNs: Long,
    val stoppedByCap: Boolean,
    val stopAcknowledged: Boolean = true,
)

interface M05BAttributionV2SourceFrame {
    val timestampNs: Long
    val aeTargetFpsRange: AttributionV2FpsRange?
    val deliveredWidth: Int get() = 1280
    val deliveredHeight: Int get() = 720
    val deliveredFormat: String get() = "YUV_420_888"
}

interface M05BAttributionV2SourceArm {
    fun capture(capNs: Long, onFrame: (M05BAttributionV2SourceFrame) -> Boolean): SourceCaptureResult
}

enum class BackendRunMode { CAPACITY, END_TO_END }

enum class BackendPhase { WARMUP, MEASURED }

data class BackendPhaseSnapshot(
    val phase: BackendPhase,
    val warmupCompletionCount: Int,
    val warmupElapsedNs: Long,
    val measuredCompletionCount: Int,
)

internal class M05BAttributionV2BackendPhaseMachine(
    private val warmupCompletions: Int,
    private val warmupElapsedNs: Long,
    private val measuredCompletions: Int,
) {
    private var phase = BackendPhase.WARMUP
    private var boundaryCompletionCount = 0
    private var boundaryElapsedNs = 0L
    private var observedCompletionCount = 0

    @Synchronized
    fun observe(completions: Int, elapsedNs: Long): BackendPhaseSnapshot {
        observedCompletionCount = maxOf(observedCompletionCount, completions)
        if (phase == BackendPhase.WARMUP &&
            completions >= warmupCompletions && elapsedNs >= warmupElapsedNs
        ) {
            phase = BackendPhase.MEASURED
            boundaryCompletionCount = completions
            boundaryElapsedNs = elapsedNs
        }
        return BackendPhaseSnapshot(
            phase = phase,
            warmupCompletionCount = if (phase == BackendPhase.WARMUP) completions else boundaryCompletionCount,
            warmupElapsedNs = if (phase == BackendPhase.WARMUP) elapsedNs else boundaryElapsedNs,
            measuredCompletionCount = if (phase == BackendPhase.MEASURED) {
                (completions - boundaryCompletionCount).coerceAtMost(measuredCompletions)
            } else {
                0
            },
        )
    }

    @Synchronized
    fun phaseAtTerminal(
        terminal: com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition,
        elapsedNs: Long,
    ): AttributionV2CallbackPhase {
        val wasWarmup = phase == BackendPhase.WARMUP
        val isCompletion = terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ||
            terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
        val priorCompletions = observedCompletionCount
        val completions = priorCompletions + if (isCompletion) 1 else 0
        observedCompletionCount = completions
        val transitionReady = completions >= warmupCompletions && elapsedNs >= warmupElapsedNs
        val terminalIsBoundaryCompletion = isCompletion && priorCompletions < warmupCompletions
        if (wasWarmup && transitionReady) {
            phase = BackendPhase.MEASURED
            boundaryCompletionCount = completions
            boundaryElapsedNs = elapsedNs
        }
        return if (wasWarmup && (!transitionReady || terminalIsBoundaryCompletion)) {
            AttributionV2CallbackPhase.WARMUP
        } else {
            AttributionV2CallbackPhase.MEASURED
        }
    }
}

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
    val armStartedNs: Long = 0L,
    val deliveredCallbackIds: List<Long> = emptyList(),
    val terminalCallbacks: List<AttributionV2Callback> = emptyList(),
    val submittedInferenceIds: List<Long> = emptyList(),
    val pendingInferenceIds: Set<Long> = emptySet(),
    val rawEvidenceOverflow: Boolean = false,
    val environment: AttributionV2EnvironmentBoundary? = null,
    val sessionBundle: AttributionV2SessionBundle? = null,
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

    /** Returns only digest/length descriptors after a durable export has been published. */
    fun publishedSessionBundle(sessionReference: String): AttributionV2SessionBundle? = null
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
        val environmentSnapshot: () -> AttributionV2EnvironmentSnapshot = {
            AttributionV2EnvironmentSnapshot()
        },
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
            sourceObservation = sourceObservation.copy(
                initialCooldownThermalStatuses = cooldown.samples.take(attributionV2CooldownMaximumSamples()),
                initialCooldownRecovered = cooldown.recovered,
            )
            if (!cooldown.recovered || cooldown.samples.size > attributionV2CooldownMaximumSamples()) {
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
        val startedNs = dependencies.clock.nowNs()
        val environmentStartRaw = dependencies.environmentSnapshot()
        val capture = dependencies.sourceArmFactory().capture(M05BAttributionV2Protocol.ARM_CAP_NS) { frame ->
            frames += frame
            frames.size < M05BAttributionV2Protocol.SOURCE_WARMUP_CALLBACKS +
                M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS
        }
        val runnerElapsedNs = dependencies.clock.nowNs() - startedNs
        val endedNs = dependencies.clock.nowNs()
        val firstFrame = frames.firstOrNull()
        val lastFrame = frames.lastOrNull() ?: firstFrame
        val environmentStart = firstFrame?.let { frame ->
            environmentStartRaw.copy(
                negotiatedCameraFpsRange = frame.aeTargetFpsRange ?: environmentStartRaw.negotiatedCameraFpsRange,
                deliveredWidth = frame.deliveredWidth,
                deliveredHeight = frame.deliveredHeight,
                deliveredFormat = frame.deliveredFormat,
            )
        } ?: environmentStartRaw
        val environmentEndRaw = dependencies.environmentSnapshot()
        val environmentEnd = lastFrame?.let { frame ->
            environmentEndRaw.copy(
                negotiatedCameraFpsRange = frame.aeTargetFpsRange ?: environmentEndRaw.negotiatedCameraFpsRange,
                deliveredWidth = frame.deliveredWidth,
                deliveredHeight = frame.deliveredHeight,
                deliveredFormat = frame.deliveredFormat,
            )
        } ?: environmentEndRaw
        val measured = frames.drop(M05BAttributionV2Protocol.SOURCE_WARMUP_CALLBACKS)
            .take(M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS)
        val blockers = buildList {
            if (capture.stoppedByCap ||
                capture.elapsedNs > M05BAttributionV2Protocol.ARM_CAP_NS ||
                runnerElapsedNs > M05BAttributionV2Protocol.ARM_CAP_NS ||
                !capture.stopAcknowledged ||
                measured.size != M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS
            ) {
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
            environment = AttributionV2EnvironmentBoundary(
                armStartedNs = startedNs,
                armEndedNs = endedNs,
                start = environmentStart,
                end = environmentEnd,
            ),
        )
    }

    private fun runBackendArm(boundaryThermalStatus: Int): AttributionV2BackendObservation {
        val environmentStart = dependencies.environmentSnapshot()
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
        val environmentEnd = dependencies.environmentSnapshot()
        val environment = result.environment ?: AttributionV2EnvironmentBoundary(
            armStartedNs = result.armStartedNs,
            armEndedNs = maxOf(result.armStartedNs, dependencies.clock.nowNs()),
            start = environmentStart,
            end = environmentEnd,
        )
        val blockers = buildList {
            result.failureCode?.let(::add)
            if (result.stoppedByCap || result.warmupCompletions < M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS ||
                result.warmupElapsedNs < M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS ||
                result.measuredServiceDurationsNs.size != M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS
            ) add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
            if (result.inferenceFailures != 0) add("ATTRIBUTION.INFERENCE_FAILURE")
            addAll(attributionV2BackendRunRawBlockers(result))
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
            armStartedNs = result.armStartedNs,
            deliveredCallbackIds = result.deliveredCallbackIds
                .take(M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM),
            terminalCallbacks = result.terminalCallbacks
                .take(M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM),
            submittedInferenceIds = result.submittedInferenceIds
                .take(M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM),
            pendingInferenceIds = result.pendingInferenceIds
                .take(M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM)
                .toSet(),
            rawEvidenceOverflow = result.rawEvidenceOverflow ||
                result.deliveredCallbackIds.size > M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM ||
                result.terminalCallbacks.size > M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM ||
                result.submittedInferenceIds.size > M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM ||
                result.pendingInferenceIds.size > M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM,
            environment = environment,
            sessionBundle = result.sessionBundle,
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
                    environment = result.environment ?: AttributionV2EnvironmentBoundary(
                        armStartedNs = result.armStartedNs,
                        armEndedNs = maxOf(result.armStartedNs, dependencies.clock.nowNs()),
                        start = dependencies.environmentSnapshot(),
                        end = dependencies.environmentSnapshot(),
                    ),
                    sessionBundle = result.sessionBundle,
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

private fun attributionV2CooldownMaximumSamples(): Int =
    (M05BAttributionV2Protocol.COOLDOWN_MAX_NS / M05BAttributionV2Protocol.COOLDOWN_SAMPLE_NS).toInt() + 1

private fun attributionV2BackendRunRawBlockers(result: BackendRunResult): List<String> = buildList {
    val max = M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM
    if (result.rawEvidenceOverflow ||
        result.deliveredCallbackIds.size > max ||
        result.terminalCallbacks.size > max ||
        result.submittedInferenceIds.size > max ||
        result.pendingInferenceIds.size > max
    ) add("ATTRIBUTION.CALLBACK_CONSERVATION")
    addAll(
        attributionV2CallbackBlockers(
            AttributionV2CallbackArm(
                deliveredCallbackIds = result.deliveredCallbackIds.take(max),
                terminalCallbacks = result.terminalCallbacks.take(max),
                submittedInferenceIds = result.submittedInferenceIds.take(max),
                pendingInferenceIds = result.pendingInferenceIds.take(max).toSet(),
            ),
        ),
    )
    val completed = result.terminalCallbacks.filter {
        it.terminalClass == AttributionV2TerminalClass.ADMITTED_COMPLETED ||
            it.terminalClass == AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED
    }
    val warmup = completed.filter { it.phase == AttributionV2CallbackPhase.WARMUP }
    val measured = completed.filter { it.phase == AttributionV2CallbackPhase.MEASURED }
    val warmupElapsedNs = warmup.mapNotNull { it.completionTimestampNs }
        .maxOrNull()?.minus(result.armStartedNs) ?: 0L
    if (warmup.size < M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS ||
        warmupElapsedNs < M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS ||
        measured.size != M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS
    ) add("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT")
    if (completed.count { it.terminalClass == AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED } != result.inferenceFailures) {
        add("ATTRIBUTION.INFERENCE_FAILURE")
    }
}

/** CameraX adapter used only by the internal v2 device gate. */
internal class M05BAttributionV2CameraXSourceArm(
    private val source: CameraXFrameSource,
) : M05BAttributionV2SourceArm {
    override fun capture(capNs: Long, onFrame: (M05BAttributionV2SourceFrame) -> Boolean): SourceCaptureResult {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val deadlineNs = startNs + capNs
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
        val acknowledged = finished.await(
            (deadlineNs - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L),
            TimeUnit.NANOSECONDS,
        )
        if (!acknowledged) stop(true)
        timer.cancel(false)
        capExecutor.shutdownNow()
        return SourceCaptureResult(
            elapsedNs = SystemClock.elapsedRealtimeNanos() - startNs,
            stoppedByCap = hitCap || SystemClock.elapsedRealtimeNanos() >= deadlineNs,
            stopAcknowledged = acknowledged,
        )
    }
}

private fun OwnedYuvFrame.toAttributionSourceFrame() = object : M05BAttributionV2SourceFrame {
    override val timestampNs: Long = this@toAttributionSourceFrame.timestampNs
    override val aeTargetFpsRange: AttributionV2FpsRange? = observedAeTargetFpsRange?.toAttributionRange()
    override val deliveredWidth: Int = this@toAttributionSourceFrame.width
    override val deliveredHeight: Int = this@toAttributionSourceFrame.height
    override val deliveredFormat: String = "YUV_420_888"
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

    override fun publishedSessionBundle(sessionReference: String): AttributionV2SessionBundle {
        require(sessionReference.matches(Regex("^exports/00000000-0000-4000-8000-[0-9a-f]{12}$")))
        fun artifact(name: String): AttributionV2ArtifactRef {
            val relativePath = "$sessionReference/$name"
            val bytes = File(diagnosticRoot, relativePath).readBytes()
            return AttributionV2ArtifactRef(
                relativePath = relativePath,
                lengthBytes = bytes.size.toLong(),
                sha256 = Sha256Digest.hex(bytes),
            )
        }
        return AttributionV2SessionBundle(
            sessionReference = sessionReference,
            manifest = artifact("manifest.json"),
            frames = artifact("frames.ndjson"),
            telemetry = artifact("telemetry.ndjson"),
        )
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

internal class AndroidM05BAttributionV2Environment(
    private val context: Context,
    private val powerManager: PowerManager,
    private val negotiatedFps: AttributionV2FpsRange = AttributionV2FpsRange(30, 30),
    private val deliveredWidth: Int = 1280,
    private val deliveredHeight: Int = 720,
    private val deliveredFormat: String = "YUV_420_888",
) {
    fun snapshot(): AttributionV2EnvironmentSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250)
            ?.toDouble()?.div(10.0) ?: 25.0
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = connectivity?.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } == true
        val powerSource = when (plugged) {
            0 -> "battery"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unknown"
        }
        return AttributionV2EnvironmentSnapshot(
            thermalStatus = powerManager.currentThermalStatus,
            batteryTemperatureC = temperature,
            batteryLevelPercent = if (level != null && scale != null) {
                (level.toDouble() * 100.0 / scale).coerceIn(0.0, 100.0)
            } else 0.0,
            powerSource = powerSource,
            charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
            ),
            wifiTransport = wifi,
            screenKeepAwake = powerManager.isInteractive,
            deviceIdle = powerManager.isDeviceIdleMode,
            negotiatedCameraFpsRange = negotiatedFps,
            deliveredWidth = deliveredWidth,
            deliveredHeight = deliveredHeight,
            deliveredFormat = deliveredFormat,
        )
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
    private val storage: M05BAttributionV2ArtifactStorage,
    private val sessionIdProvider: () -> Uuid,
    private val identity: AttributionV2RunIdentity,
) : M05BAttributionV2Backend {
    override fun run(spec: BackendRunSpec): BackendRunResult {
        val startedAtNs = SystemClock.elapsedRealtimeNanos()
        val phaseMachine = M05BAttributionV2BackendPhaseMachine(
            warmupCompletions = spec.warmupCompletions,
            warmupElapsedNs = spec.warmupElapsedNs,
            measuredCompletions = spec.measuredCompletions,
        )
        val observer = AttributionV2Observation { terminal, completedNs ->
            phaseMachine.phaseAtTerminal(terminal, completedNs - startedAtNs)
        }
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
            sessionIdProvider = sessionIdProvider,
            attributionV2Identity = identity,
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
        var completionCount = 0
        var phaseSnapshot = BackendPhaseSnapshot(BackendPhase.WARMUP, 0, 0L, 0)
        var completion: LiveCameraCompletion? = null
        try {
            while (SystemClock.elapsedRealtimeNanos() - startedAtNs < spec.armCapNs) {
                completionCount = observer.terminalSnapshot().count {
                    it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ||
                        it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
                }
                phaseSnapshot = phaseMachine.observe(
                    completions = completionCount,
                    elapsedNs = SystemClock.elapsedRealtimeNanos() - startedAtNs,
                )
                if (phaseSnapshot.measuredCompletionCount >= spec.measuredCompletions) break
                Thread.sleep(10L)
            }
        } finally {
            completion = composition.stop()
        }
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedAtNs
        val admissions = observer.admissionSnapshot()
        val terminals = observer.terminalSnapshot()
        phaseSnapshot = phaseMachine.observe(
            completions = terminals.count {
                it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ||
                    it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
            },
            elapsedNs = elapsedNs,
        )
        val byId = admissions.associateBy { it.callbackId }
        val completed = terminals.filter {
            it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ||
                it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
        }
        val warmup = completed.take(phaseSnapshot.warmupCompletionCount)
        val measured = completed.drop(phaseSnapshot.warmupCompletionCount).take(spec.measuredCompletions)
        val serviceDurations = measured.map { terminal ->
            val admission = byId[terminal.callbackId]
            if (admission == null) 0L else {
                (terminal.completedNs - admission.observedSessionNs).coerceAtLeast(0L)
            }
        }
        val maxRawCallbacks = M05BAttributionV2Protocol.RAW_CALLBACK_MAXIMUM
        val rawOverflow = admissions.size > maxRawCallbacks || terminals.size > maxRawCallbacks
        val boundedAdmissions = admissions.take(maxRawCallbacks)
        val boundedTerminals = terminals.take(maxRawCallbacks)
        val result = BackendRunResult(
            elapsedNs = elapsedNs,
            stoppedByCap = phaseSnapshot.measuredCompletionCount < spec.measuredCompletions && elapsedNs >= spec.armCapNs,
            warmupCompletions = phaseSnapshot.warmupCompletionCount,
            warmupElapsedNs = phaseSnapshot.warmupElapsedNs,
            measuredServiceDurationsNs = serviceDurations,
            inferenceFailures = measured.count {
                it.terminal == com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED
            },
            endToEndRun = if (spec.mode == BackendRunMode.END_TO_END) {
                admissions.toEndToEndRun(
                    terminals,
                    spec.cadenceFps ?: error("missing cadence"),
                    byId,
                    completion?.sessionId?.value?.let { "exports/$it" }
                        ?: "exports/00000000-0000-4000-8000-000000000000",
                    startedAtNs,
                )
            } else {
                null
            },
            initialThermalStatus = initialThermalStatus,
            finalThermalStatus = thermalStatus(),
            armStartedNs = startedAtNs,
            deliveredCallbackIds = boundedAdmissions.map { it.callbackId },
            submittedInferenceIds = boundedAdmissions
                .filter { it.productionClassification == LiveFrameClassification.SUBMIT }
                .map { it.callbackId },
            pendingInferenceIds = boundedAdmissions
                .filter { it.productionClassification == LiveFrameClassification.SUBMIT }
                .map { it.callbackId }
                .filterNot { callbackId -> boundedTerminals.any { it.callbackId == callbackId } }
                .toSet(),
            rawEvidenceOverflow = rawOverflow,
            sessionBundle = completion?.sessionId?.value?.let { sessionId ->
                storage.publishedSessionBundle("exports/$sessionId")
            },
        )
        return assembleAndroidM05BAttributionV2BackendResult(
            result = result,
            admissions = boundedAdmissions,
            terminals = boundedTerminals,
            cadenceFps = spec.cadenceFps,
            byId = byId,
        )
    }
}

internal fun assembleAndroidM05BAttributionV2BackendResult(
    result: BackendRunResult,
    admissions: List<AttributionV2ObservedAdmission>,
    terminals: List<AttributionV2ObservedTerminal>,
    cadenceFps: Int?,
    byId: Map<Long, AttributionV2ObservedAdmission>,
): BackendRunResult = result.copy(
    terminalCallbacks = attributionV2BackendTerminalCallbacks(
        admissions = admissions,
        terminals = terminals,
        cadenceFps = cadenceFps,
        byId = byId,
    ),
)

private fun List<AttributionV2ObservedAdmission>.toEndToEndRun(
    terminals: List<AttributionV2ObservedTerminal>,
    cadenceFps: Int,
    byId: Map<Long, AttributionV2ObservedAdmission>,
    sessionReference: String,
    armStartedNs: Long,
): AttributionV2EndToEndRun {
    val terminalCallbacks = toRawCallbacks(terminals, cadenceFps, byId)
    val submitted = filter { it.productionClassification == LiveFrameClassification.SUBMIT }
        .map { it.callbackId }
    val terminalIds = terminalCallbacks.map { it.callbackId }.toSet()
    return AttributionV2EndToEndRun(
        cadenceFps = cadenceFps,
        sessionReference = sessionReference,
        armStartedNs = armStartedNs,
        initialThermalStatus = 0,
        finalThermalStatus = 0,
        deliveredCallbackIds = map { it.callbackId },
        terminalCallbacks = terminalCallbacks,
        submittedInferenceIds = submitted,
        pendingInferenceIds = submitted.filterNot(terminalIds::contains).toSet(),
        measuredSpanNs = attributionV2MeasuredSpanNs(terminalCallbacks),
    )
}

internal fun attributionV2MeasuredSpanNs(callbacks: List<AttributionV2Callback>): Long {
    val measuredCaptures = callbacks
        .filter { it.phase == AttributionV2CallbackPhase.MEASURED }
        .map { it.captureTimestampNs }
    return if (measuredCaptures.size < 2) 0L else measuredCaptures.last() - measuredCaptures.first()
}

internal fun attributionV2BackendTerminalCallbacks(
    admissions: List<AttributionV2ObservedAdmission>,
    terminals: List<AttributionV2ObservedTerminal>,
    cadenceFps: Int?,
    byId: Map<Long, AttributionV2ObservedAdmission>,
): List<AttributionV2Callback> = admissions.toRawCallbacks(terminals, cadenceFps, byId)

private fun List<AttributionV2ObservedAdmission>.toRawCallbacks(
    terminals: List<AttributionV2ObservedTerminal>,
    cadenceFps: Int?,
    byId: Map<Long, AttributionV2ObservedAdmission>,
): List<AttributionV2Callback> = terminals.map { terminal ->
    val admission = byId[terminal.callbackId]
    if (admission == null) {
        return@map AttributionV2Callback(
            callbackId = terminal.callbackId,
            captureTimestampNs = 0L,
            productionClassification = LiveFrameClassification.SESSION_STOPPING,
            cadenceDue = false,
            slotAvailable = false,
            terminalClass = AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE,
            phase = terminal.phase,
            admissionObservedSessionNs = null,
            completionTimestampNs = terminal.completedNs,
        )
    }
    AttributionV2Callback(
        callbackId = terminal.callbackId,
        captureTimestampNs = admission.captureTimestampNs,
        productionClassification = admission.productionClassification,
        cadenceDue = cadenceFps?.let { admission.shadowSelections[it] } ?: admission.productionPredicates.cadenceDue,
        slotAvailable = admission.productionPredicates.slotAvailable,
        terminalClass = when (terminal.terminal) {
            com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_COMPLETED ->
                AttributionV2TerminalClass.ADMITTED_COMPLETED
            com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.INFERENCE_FAILED ->
                AttributionV2TerminalClass.ADMITTED_INFERENCE_FAILED
            com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.CADENCE_SELECTION ->
                predicateTerminal(admission, cadenceFps?.let { admission.shadowSelections[it] } ?: false)
            com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.BACKPRESSURE ->
                predicateTerminal(admission, cadenceFps?.let { admission.shadowSelections[it] } ?: false)
            com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.PREPROCESSING_FAILED ->
                AttributionV2TerminalClass.PREPROCESSING_FAILED
            com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition.SESSION_STOPPING ->
                AttributionV2TerminalClass.SESSION_STOPPING
            else -> AttributionV2TerminalClass.ADMISSION_STATE_DIVERGENCE
        },
        phase = terminal.phase,
        admissionObservedSessionNs = admission.observedSessionNs,
        completionTimestampNs = terminal.completedNs,
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
