package com.calisthenics.diagnostic.composition

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.camera.CameraXFrameSource
import com.calisthenics.diagnostic.camera.NegotiatedAnalysisResolution
import com.calisthenics.diagnostic.thermal.AndroidLiveThermalProbe
import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.contract.AvailableObservations
import com.calisthenics.diagnostic.contract.Cadence
import com.calisthenics.diagnostic.contract.Camera
import com.calisthenics.diagnostic.contract.CaptureQualityObserved
import com.calisthenics.diagnostic.contract.Clock
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Device
import com.calisthenics.diagnostic.contract.ModelInput
import com.calisthenics.diagnostic.contract.PosePipeline
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Sha256
import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.export.AndroidStoragePort
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.DeferredSessionAbort
import com.calisthenics.diagnostic.export.SessionExporter
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.live.LiveFrameAdmission
import com.calisthenics.diagnostic.live.LiveCaptureCadenceAccumulator
import com.calisthenics.diagnostic.live.LiveCaptureCadenceDiagnostics
import com.calisthenics.diagnostic.live.LiveCaptureThermalObservation
import com.calisthenics.diagnostic.live.LiveCaptureThermalSummary
import com.calisthenics.diagnostic.live.asLiveEvidenceJson
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerConstructionEvidence
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerLiveAdapterFactory
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerLiveInferencePort
import com.calisthenics.diagnostic.mediapipe.realLiveDetectorConstructor
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.ModelProvenanceException
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.model.requireLiveModelProvenance
import com.calisthenics.diagnostic.purge.AndroidTombstoneFenceFilesystem
import com.calisthenics.diagnostic.purge.TombstoneSessionAdmissionFence
import com.calisthenics.diagnostic.preprocessing.scalePadRgb24
import com.calisthenics.diagnostic.preprocessing.uprightDimensions
import com.calisthenics.diagnostic.preprocessing.yuv420ToUprightRgb24
import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.Yuv420Frame
import com.calisthenics.diagnostic.preprocessing.YuvPlane
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.session.DeadlineScheduler
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import com.calisthenics.diagnostic.session.SessionFinalizer
import com.calisthenics.diagnostic.session.SessionLifecycle
import com.calisthenics.diagnostic.session.SessionRunner
import com.calisthenics.diagnostic.session.SessionRunnerSnapshot
import com.calisthenics.diagnostic.session.SessionConfiguration
import com.calisthenics.diagnostic.session.SessionSealFence
import com.calisthenics.diagnostic.session.SkipReason
import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition
import com.calisthenics.diagnostic.telemetry.DeliveredCallbackAccounting
import com.calisthenics.diagnostic.telemetry.DeliveredCallbackLedger
import com.calisthenics.diagnostic.telemetry.FixedTelemetryScheduler
import com.calisthenics.diagnostic.telemetry.PipelineDisposition
import com.calisthenics.diagnostic.telemetry.PipelineStage
import com.calisthenics.diagnostic.telemetry.PipelineStageRecorder
import com.calisthenics.diagnostic.telemetry.PipelineTelemetryRecordingMode
import com.calisthenics.diagnostic.telemetry.TelemetrySink
import com.calisthenics.diagnostic.telemetry.pipelineTerminalEvent
import com.calisthenics.diagnostic.telemetry.AndroidFixedTelemetryProbe
import com.calisthenics.diagnostic.study.M05Admission
import com.calisthenics.diagnostic.study.M05AdmissionEvidence
import com.calisthenics.diagnostic.study.M05AdmissionResult
import com.calisthenics.diagnostic.study.M05InputClass
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val LIVE_SESSION_KIND = "live_conformance"
private const val LIVE_PREPROCESSING_VERSION = "1.0.0"
private const val MODEL_INPUT_WIDTH = 256
private const val MODEL_INPUT_HEIGHT = 256
private const val REQUESTED_CAMERA_WIDTH = 1280
private const val REQUESTED_CAMERA_HEIGHT = 720
private const val STALL_TIMEOUT_NS = 5_000_000_000L
private const val DRAIN_TIMEOUT_NS = 5_000_000_000L
private val LIVE_M05_INPUT_CLASS = M05InputClass.EMPTY_SCENE

sealed interface LiveCameraStartResult {
    data class Started(val sessionId: Uuid) : LiveCameraStartResult

    data class Unavailable(val reason: LiveUnavailableReason) : LiveCameraStartResult
}

sealed interface LiveUnavailableReason {
    val code: String

    data class CameraCapability(val blockingReasons: List<String>) : LiveUnavailableReason {
        override val code: String = "camera_capability_not_admitted"
    }

    data object CameraPermissionDenied : LiveUnavailableReason {
        override val code: String = "camera_permission_denied"
    }

    data object OperatorAttestationRequired : LiveUnavailableReason {
        override val code: String = "operator_no_person_attestation_required"
    }

    data class ThermalApi(val detail: String) : LiveUnavailableReason {
        override val code: String = "thermal_api_unavailable"
    }

    data class Initialization(val detail: String) : LiveUnavailableReason {
        override val code: String = "live_initialization_failed"
    }
}

sealed interface LiveInternalFailure {
    val code: String

    data object LiveInferenceStalled : LiveInternalFailure {
        override val code: String = "live_inference_stalled"
    }

    data class SourceFailure(override val code: String) : LiveInternalFailure

    data class TelemetryFailure(
        override val code: String,
        val sanitizedCause: String,
        val cause: Throwable,
    ) : LiveInternalFailure
}

data class LiveCameraCompletion(
    val sessionId: Uuid,
    val finalization: FinalizationResult,
    val snapshot: SessionRunnerSnapshot,
    val internalFailure: LiveInternalFailure? = null,
    val cadenceDiagnostics: LiveCaptureCadenceDiagnostics? = null,
    val thermalObservations: List<LiveThermalObservation> = emptyList(),
)

data class LiveInference(
    val port: InferencePort,
    val disposable: Disposable,
    val constructionEvidence: PoseLandmarkerConstructionEvidence,
)

data class LiveThermalObservation(val tMonotonicNs: Long, val status: Int) {
    init {
        require(tMonotonicNs >= 0L) { "thermal observation time must be non-negative" }
    }
}

/** Immutable, sanitized diagnostics exported by the physical device gate after composition stops. */
data class LiveCaptureEvidenceTelemetry(
    val captureWidth: Int?,
    val captureHeight: Int?,
    val orientationDegrees: Int?,
    val mirrored: Boolean?,
    val thermalApiAvailable: Boolean,
    val thermalUnavailableReason: String?,
    val thermalObservations: List<LiveThermalObservation>,
)

/** Converts optional composition telemetry without conflating a present null reason with absence. */
internal fun thermalEvidenceFromTelemetry(
    telemetry: LiveCaptureEvidenceTelemetry?,
): LiveCaptureThermalSummary = telemetry?.let { present ->
    LiveCaptureThermalSummary(
        apiAvailable = present.thermalApiAvailable,
        unavailableReason = present.thermalUnavailableReason,
        observations = present.thermalObservations.map { sample ->
            LiveCaptureThermalObservation(sample.tMonotonicNs, sample.status)
        },
    )
} ?: LiveCaptureThermalSummary(
    apiAvailable = false,
    unavailableReason = "composition_telemetry_unavailable",
    observations = emptyList(),
)

interface LiveThermalProbe : Disposable {
    val available: Boolean
    val unavailableReason: String?
    fun start(nowNs: () -> Long)
    fun observations(): List<LiveThermalObservation>
}

interface LiveClock {
    fun nowNs(): Long
}

interface LiveScheduler {
    /** Schedules [action] after an epoch-free duration; this scheduler owns no clock domain. */
    fun scheduleAfter(delayNs: Long, action: () -> Unit): Disposable
    fun shutdown() = Unit
}

interface LiveFrameSource {
    fun start(
        onSourceStarted: (LiveSourceResolution) -> Unit,
        onFrame: (OwnedYuvFrame) -> Unit,
        onFrameRejected: (timestampNs: Long?) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    /**
     * Fences new frame callbacks and invokes [onCallbacksQuiesced] after source callback ownership
     * has quiesced. Implementations wait for callbacks entered by other threads, but must hand off
     * a callback that requests stop from within its terminal composition call without self-waiting.
     */
    fun stop(onCallbacksQuiesced: () -> Unit)
}

data class LiveSourceResolution(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "source dimensions must be positive" }
    }
}

fun interface LiveSessionFinalizerFactory {
    fun create(
        manifestTemplate: SessionManifest,
        manifestAtFinalization: (SessionManifest, com.calisthenics.diagnostic.session.SealedSessionSnapshot) -> SessionManifest,
        monotonicNowNs: () -> Long,
    ): SessionFinalizer
}

/** Named Task-4 handoff. It intentionally does nothing until encoder fan-out exists. */
fun interface LiveEncoderFinalizationSlot {
    fun finalizeEncoder()
}

private class LiveDeadlineScheduler(
    private val scheduler: LiveScheduler,
    private val relativeNowNs: () -> Long,
) : DeadlineScheduler {
    var afterDeadline: () -> Unit = {}

    override fun schedule(deadlineNs: Long, action: () -> Unit): Disposable =
        scheduler.scheduleAfter((deadlineNs - relativeNowNs()).coerceAtLeast(0L)) {
            action()
            afterDeadline()
        }
}

/**
 * The shared live-session controller. Android construction is exposed by [fromAndroid]; the
 * dependency constructor is deliberately host-testable and keeps the lifecycle invariants out of
 * CameraX and MediaPipe callback threads.
 */
class LiveCameraComposition internal constructor(
    private val dependencies: Dependencies,
) {
    class Dependencies internal constructor(
        val cameraCapability: CameraCapabilityObservation,
        val permissionGranted: Boolean,
        val modelInputWidth: Int,
        val modelInputHeight: Int,
        val clock: LiveClock,
        val scheduler: LiveScheduler,
        internal val sessionDispatcherFactory: () -> SingleThreadSessionDispatcher = {
            SingleThreadSessionDispatcher()
        },
        val sourceFactory: () -> LiveFrameSource,
        val inferenceFactory: (nowNs: () -> Long) -> LiveInference,
        val thermalProbeFactory: () -> LiveThermalProbe,
        val telemetryProbeFactory: () -> LiveTelemetryProbe,
        val telemetrySchedulerFactory: () -> FixedTelemetryScheduler = { DedicatedTelemetryScheduler() },
        val telemetryDispatcherFactory: (TelemetrySink) -> BoundedTelemetryDispatcher = {
            BoundedTelemetryDispatcher(it)
        },
        val beforeRunnerReady: ((OwnedYuvFrame) -> Unit) -> Unit = {},
        val encoderFinalizationSlot: LiveEncoderFinalizationSlot = LiveEncoderFinalizationSlot {},
        /**
         * Per-run video writer factory, or null when the run records no video.
         *
         * Video is enabled only for short synchronization and purge runs; sustained
         * characterization records `video_enabled=false`, so null is the normal case there.
         */
        val videoWriterFactory: (() -> com.calisthenics.diagnostic.video.LiveVideoWriter)? = null,
        val sessionFinalizerFactory: LiveSessionFinalizerFactory,
        val sessionIdProvider: () -> Uuid = M05SessionIdGenerator::next,
        val manifestMetadata: LiveManifestMetadata = LiveManifestMetadata(),
        val drainTimeoutNs: Long = DRAIN_TIMEOUT_NS,
        val registerRunner: (SessionRunner) -> SessionRunnerRegistrationResult = {
            SessionRunnerRegistrationResult.ACCEPTED
        },
        val beforeSourceStart: () -> Unit = {},
        val onCompletion: (LiveCameraCompletion) -> Unit = {},
        val wallClockUtc: () -> String = { Instant.now().toString() },
    ) {
        init {
            require(modelInputWidth > 0 && modelInputHeight > 0)
            require(drainTimeoutNs >= 0)
        }
    }

    data class LiveManifestMetadata(
        val cameraFacing: String = "back",
        val orientationDegrees: Int = 0,
        val mirrored: Boolean = false,
        val harnessGitSha: String = "0000000000000000000000000000000000000000",
        val provenance: ModelProvenance? = null,
        /** Candidate identity copied into the manifest's extensible study context. */
        val attributionIdentity: Map<String, String> = emptyMap(),
        val device: Device = Device("android", "unknown", "unknown", null, false),
        val startedAtUtc: String = Instant.now().toString(),
    )

    private val monitor = java.lang.Object()
    private var sessionId: Uuid? = null
    private var sessionStartNs = 0L
    private var admission: LiveFrameAdmission? = null
    private var source: LiveFrameSource? = null
    private var runner: SessionRunner? = null
    private var inference: LiveInference? = null
    private var thermalProbe: LiveThermalProbe? = null
    private var sessionFinalizer: SessionFinalizer? = null
    private var telemetryController: LiveTelemetryController? = null
    private var telemetryStarted = false
    private var stageRecorder: PipelineStageRecorder? = null
    private var manifest: SessionManifest? = null
    private var m05Admission: M05AdmissionResult? = null
    private var frameIndex = 0L
    private val deliveredCallbacks = DeliveredCallbackLedger()
    private var callbacksForwardedToRunner = 0L
    private var lastDeliveredTimestampNs: Long? = null
    private data class PendingInferenceCallback(val submissionIndex: Long, val frameIndex: Long)
    private var pendingInferenceCallback: PendingInferenceCallback? = null
    private var nextExpectedSubmissionIndex = 0L
    private var finalizationFenceCompleted = false
    private data class SourceGeometry(
        val width: Int,
        val height: Int,
        val rotation: FrameRotation,
        val mirrored: Boolean,
    )

    private var observedSourceGeometry: SourceGeometry? = null
    private var negotiatedSourceResolution: LiveSourceResolution? = null
    private val admittedCallbackCadence = LiveCaptureCadenceAccumulator(CameraXFrameSource.TARGET_CAMERA_FPS)
    private var cadenceDiagnostics: LiveCaptureCadenceDiagnostics? = null
    private var stopping = false
    private var stallHandle: Disposable? = null
    private var stalled = false
    private var sourceFailure: LiveInternalFailure.SourceFailure? = null
    private var telemetryFailure: LiveInternalFailure.TelemetryFailure? = null
    private var completion: LiveCameraCompletion? = null
    private var finalizationStarted = false
    private var sessionDispatcher: SingleThreadSessionDispatcher? = null
    private var sourceStopped = false
    private enum class SourceLifecycle {
        NOT_PUBLISHED,
        PUBLISHED,
        STARTING,
        STARTED,
        TEARDOWN_REQUESTED,
    }

    private val sourceLifecycle = AtomicReference(SourceLifecycle.NOT_PUBLISHED)
    private val sourceStartFinished = CountDownLatch(1)
    private var resourcesReleased = false
    private var abandonRequested = false

    fun start(
        configuredInferenceFps: Int,
        operatorAttestedNoPerson: Boolean,
        recordingMode: PipelineTelemetryRecordingMode = PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION,
        onProgress: (SessionRunnerSnapshot) -> Unit = {},
    ): LiveCameraStartResult {
        var unavailable: LiveCameraStartResult.Unavailable? = null
        var releasePlan: ResourceReleasePlan? = null
        var createdRunner: SessionRunner? = null
        var createdSessionId: Uuid? = null
        var liveSource: LiveFrameSource? = null
        synchronized(monitor) {
            if (resourcesReleased) {
                return LiveCameraStartResult.Unavailable(LiveUnavailableReason.Initialization("live_session_closed"))
            }
            if (configuredInferenceFps !in setOf(10, 15, 30)) {
                unavailable = LiveCameraStartResult.Unavailable(LiveUnavailableReason.Initialization("unsupported_inference_fps"))
                releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
                return@synchronized
            }
            if (sessionId != null) {
                return LiveCameraStartResult.Unavailable(LiveUnavailableReason.Initialization("live_session_already_started"))
            }
            if (!operatorAttestedNoPerson) {
                return LiveCameraStartResult.Unavailable(LiveUnavailableReason.OperatorAttestationRequired)
            }
            if (!dependencies.permissionGranted) {
                unavailable = LiveCameraStartResult.Unavailable(LiveUnavailableReason.CameraPermissionDenied)
                releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
                return@synchronized
            }
            val blockingReasons = exactCameraCapabilityBlockingReasons(dependencies.cameraCapability)
            if (blockingReasons.isNotEmpty()) {
                unavailable = LiveCameraStartResult.Unavailable(LiveUnavailableReason.CameraCapability(blockingReasons))
                releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
                return@synchronized
            }
            sessionStartNs = dependencies.clock.nowNs()
            val liveThermalProbe = try {
                dependencies.thermalProbeFactory()
            } catch (error: Throwable) {
                unavailable = LiveCameraStartResult.Unavailable(
                    LiveUnavailableReason.ThermalApi(error.message ?: "thermal_api_unavailable"),
                )
                releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
                return@synchronized
            }
            thermalProbe = liveThermalProbe
            if (!liveThermalProbe.available) {
                unavailable = LiveCameraStartResult.Unavailable(
                    LiveUnavailableReason.ThermalApi(liveThermalProbe.unavailableReason ?: "thermal_api_unavailable"),
                )
                releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
                return@synchronized
            }
            try {
                liveThermalProbe.start(::relativeNowNs)
            } catch (error: Throwable) {
                unavailable = LiveCameraStartResult.Unavailable(
                    LiveUnavailableReason.ThermalApi(error.message ?: "thermal_api_unavailable"),
                )
                releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
                return@synchronized
            }
            val id = dependencies.sessionIdProvider()
            sessionId = id
            createdSessionId = id
            try {
                val initialM05Admission = evaluateM05Admission(id, operatorAttestedNoPerson, submittedFrameCount = 0, detectionActiveFrameCount = 0)
                m05Admission = initialM05Admission
                val liveInference = dependencies.inferenceFactory(::relativeNowNs)
                inference = liveInference
                val template = buildManifest(
                    id,
                    configuredInferenceFps,
                    liveInference.constructionEvidence,
                    dependencies.wallClockUtc(),
                    initialM05Admission,
                )
                manifest = template
                val runnerDispatcher = dependencies.sessionDispatcherFactory()
                sessionDispatcher = runnerDispatcher
                val createdFinalizer = dependencies.sessionFinalizerFactory.create(
                    manifestTemplate = template,
                    manifestAtFinalization = { finalized, sealed ->
                        manifestAtFinalization(finalized, sealed, operatorAttestedNoPerson)
                    },
                    monotonicNowNs = ::relativeNowNs,
                )
                (createdFinalizer as? SessionExporter)?.startRefusalCode?.let { code ->
                    unavailable = LiveCameraStartResult.Unavailable(
                        LiveUnavailableReason.Initialization(code),
                    )
                    releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
                    return@synchronized
                }
                check(createdFinalizer is TelemetrySink) {
                    "live session finalizer must expose the narrow telemetry sink"
                }
                sessionFinalizer = createdFinalizer
                val telemetryDispatcher = dependencies.telemetryDispatcherFactory(createdFinalizer)
                val recorder = PipelineStageRecorder(::relativeNowNs, telemetryDispatcher, recordingMode)
                stageRecorder = recorder
                telemetryController = LiveTelemetryController(
                    nowNs = ::relativeNowNs,
                    scheduler = dependencies.telemetrySchedulerFactory(),
                    probe = dependencies.telemetryProbeFactory(),
                    sink = recorder,
                    dispatcher = telemetryDispatcher,
                )
                dependencies.beforeRunnerReady { frame -> handleFrame(frame) }
                val trackedInference = TerminalTrackingInferencePort(
                    delegate = liveInference.port,
                    onResult = ::onInferenceResult,
                    onSettled = ::onInferenceTerminal,
                    onCancelled = ::onInferenceCancelled,
                )
                val liveDeadlineScheduler = LiveDeadlineScheduler(dependencies.scheduler, ::relativeNowNs)
                val sessionRunner = SessionRunner(
                    configuration = SessionConfiguration(
                        sessionId = id,
                        schemaVersion = SemanticVersion("1.1.0"),
                        imageWidth = dependencies.modelInputWidth,
                        imageHeight = dependencies.modelInputHeight,
                    ),
                    inference = trackedInference,
                    deadlineScheduler = liveDeadlineScheduler,
                    drainTimeoutNs = dependencies.drainTimeoutNs,
                    finalizer = createdFinalizer,
                    sealFence = SessionSealFence(::runPreSealFence),
                    dispatcher = runnerDispatcher,
                )
                runner = sessionRunner
                createdRunner = sessionRunner
                liveDeadlineScheduler.afterDeadline = {
                    val currentRunner = synchronized(monitor) { this@LiveCameraComposition.runner }
                    currentRunner?.let(::finishIfSealed)
                }
            } catch (error: Throwable) {
                unavailable = LiveCameraStartResult.Unavailable(
                    LiveUnavailableReason.Initialization(error.message ?: "live_initialization_failed"),
                )
                releasePlan = captureResourceReleasePlanLocked(abandonRunner = true)
            }
        }
        releasePlan?.let(::performRelease)
        unavailable?.let { return it }

        val runnerToRegister = checkNotNull(createdRunner)
        val registration = runCatching { dependencies.registerRunner(runnerToRegister) }
            .getOrElse { SessionRunnerRegistrationResult.REJECTED }
        if (registration != SessionRunnerRegistrationResult.ACCEPTED) {
            val registrationPlan = synchronized(monitor) {
                unavailable = LiveCameraStartResult.Unavailable(
                    LiveUnavailableReason.Initialization("session_registration_rejected"),
                )
                captureResourceReleasePlanLocked(abandonRunner = true)
            }
            performRelease(registrationPlan)
            return checkNotNull(unavailable)
        }

        var sourceFailure: LiveCameraStartResult.Unavailable? = null
        val sourcePlan = synchronized(monitor) {
            if (resourcesReleased || abandonRequested) {
                sourceFailure = LiveCameraStartResult.Unavailable(LiveUnavailableReason.Initialization("live_session_closed"))
                null
            } else {
                try {
                    liveSource = dependencies.sourceFactory()
                    source = liveSource
                    sourceLifecycle.set(SourceLifecycle.PUBLISHED)
                    admission = LiveFrameAdmission(configuredInferenceFps)
                    checkNotNull(telemetryController).start()
                    telemetryStarted = true
                    null
                } catch (error: Throwable) {
                    sourceFailure = LiveCameraStartResult.Unavailable(
                        LiveUnavailableReason.Initialization(error.message ?: "live_initialization_failed"),
                    )
                    captureResourceReleasePlanLocked(abandonRunner = true)
                }
            }
        }
        sourcePlan?.let(::performRelease)
        sourceFailure?.let { return it }

        val startedSource = checkNotNull(liveSource)
        try {
            dependencies.beforeSourceStart()
        } catch (error: Throwable) {
            val startPlan = synchronized(monitor) {
                val result = LiveCameraStartResult.Unavailable(
                    LiveUnavailableReason.Initialization(error.message ?: "live_source_start_failed"),
                )
                unavailable = result
                captureResourceReleasePlanLocked(abandonRunner = true)
            }
            performRelease(startPlan)
            return checkNotNull(unavailable)
        }
        if (!sourceLifecycle.compareAndSet(SourceLifecycle.PUBLISHED, SourceLifecycle.STARTING)) {
            return LiveCameraStartResult.Unavailable(LiveUnavailableReason.Initialization("live_session_closed"))
        }
        val startError = runCatching {
            startedSource.start(
                onSourceStarted = ::handleSourceStarted,
                onFrame = { frame -> handleFrame(frame, onProgress) },
                onFrameRejected = { timestampNs -> handleRejectedFrame(timestampNs, onProgress) },
                onFailure = { failure -> handleSourceFailure(failure, onProgress) },
            )
        }.exceptionOrNull()
        sourceLifecycle.compareAndSet(SourceLifecycle.STARTING, SourceLifecycle.STARTED)
        sourceStartFinished.countDown()
        if (startError != null) {
            val startPlan = synchronized(monitor) {
                val result = LiveCameraStartResult.Unavailable(
                    LiveUnavailableReason.Initialization(startError.message ?: "live_source_start_failed"),
                )
                unavailable = result
                captureResourceReleasePlanLocked(abandonRunner = true)
            }
            performRelease(startPlan)
            return checkNotNull(unavailable)
        }
        if (sourceLifecycle.get() == SourceLifecycle.TEARDOWN_REQUESTED) {
            return LiveCameraStartResult.Unavailable(LiveUnavailableReason.Initialization("live_session_closed"))
        }
        return LiveCameraStartResult.Started(checkNotNull(createdSessionId))
    }

    /** Releases live-owned resources without entering the five-second drain path. */
    fun abandon() {
        val waitForSourceStart = synchronized(monitor) {
            if (resourcesReleased || abandonRequested) return
            abandonRequested = true
            stopping = true
            admission?.stop()
            disposeStallHandleLocked()
            requestSourceTeardownLocked()
        }
        val plan = synchronized(monitor) {
            captureResourceReleasePlanLocked(abandonRunner = true)
        }
        if (plan == null) return
        val release = {
            performRelease(plan, sourceAlreadyStopped = true)
            synchronized(monitor) { monitor.notifyAll() }
        }
        val releaseAfterQuiescence = {
            if (waitForSourceStart || plan.runner != null) Thread(release).start() else release()
        }
        val stopAndRelease = { stopReleasedSource(plan.source, releaseAfterQuiescence) }
        if (waitForSourceStart) {
            Thread {
                sourceStartFinished.await()
                stopAndRelease()
            }.start()
        } else {
            stopAndRelease()
        }
    }

    fun stop(): LiveCameraCompletion {
        requestStop()
        val deadlineMillis = (dependencies.drainTimeoutNs / 1_000_000L) + 1_000L
        synchronized(monitor) {
            if (completion == null) {
                try {
                    monitor.wait(deadlineMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return completion ?: error("live session teardown did not reach completion")
        }
    }

    fun completion(): LiveCameraCompletion? = synchronized(monitor) { completion }

    fun snapshot(): SessionRunnerSnapshot? {
        val completed = synchronized(monitor) { completion?.snapshot }
        if (completed != null) return completed
        val currentRunner = synchronized(monitor) { runner }
        return currentRunner?.snapshot()
    }

    fun manifest(): SessionManifest? = synchronized(monitor) { manifest }

    fun m05Admission(): M05AdmissionResult? = synchronized(monitor) { m05Admission }

    /** Observed delivery span, used by the physical gate after the first delivered callback. */
    fun observedCaptureSpanNs(): Long? = synchronized(monitor) {
        admittedCallbackCadence.observedSpanNs()
    }

    fun latestThermalObservation(): LiveThermalObservation? = synchronized(monitor) {
        thermalProbe?.observations()?.lastOrNull()
    }

    /** Returns a copy of the admitted source geometry and session-relative thermal diagnostics. */
    fun captureEvidenceTelemetry(): LiveCaptureEvidenceTelemetry? = synchronized(monitor) {
        val probe = thermalProbe ?: return null
        val geometry = observedSourceGeometry
        val resolution = negotiatedSourceResolution
        LiveCaptureEvidenceTelemetry(
            captureWidth = geometry?.width ?: resolution?.width,
            captureHeight = geometry?.height ?: resolution?.height,
            orientationDegrees = geometry?.rotation?.degrees,
            mirrored = geometry?.mirrored,
            thermalApiAvailable = probe.available,
            thermalUnavailableReason = probe.unavailableReason,
            thermalObservations = probe.observations().toList(),
        )
    }

    internal val internalFailure: LiveInternalFailure?
        get() = synchronized(monitor) {
            telemetryFailure ?: sourceFailure ?: if (stalled) LiveInternalFailure.LiveInferenceStalled else null
        }

    private fun handleSourceStarted(resolution: LiveSourceResolution) {
        synchronized(monitor) {
            // A source may finish an external asynchronous start after teardown has fenced it.
            // Do not revive the session or convert the already-requested teardown into a new error.
            if (stopping || resourcesReleased) return
            val previous = negotiatedSourceResolution
            require(previous == null || previous == resolution) { "negotiated source resolution changed" }
            negotiatedSourceResolution = resolution
            manifest = manifest?.copy(
                camera = manifest!!.camera.copy(
                    captureWidth = resolution.width,
                    captureHeight = resolution.height,
                ),
            )
        }
    }

    private data class FrameWork(
        val runner: SessionRunner,
        val index: Long,
        val timestampNs: Long,
        val classification: com.calisthenics.diagnostic.live.LiveFrameClassification,
        val canonical: ByteArray?,
        val terminalDisposition: CallbackTerminalDisposition?,
    )

    private data class ReservedFrame(
        val runner: SessionRunner,
        val index: Long,
        val timestampNs: Long,
        val frame: OwnedYuvFrame,
    )

    private data class PendingVideoFrame(
        val index: Long,
        val rebasedCaptureNs: Long,
        val frame: OwnedYuvFrame,
    )

    internal fun handleFrame(
        frame: OwnedYuvFrame,
        onProgress: (SessionRunnerSnapshot) -> Unit = {},
    ) {
        // Per-callback, never a field: concurrent camera callbacks must not share this slot.
        var pendingVideoFrame: PendingVideoFrame? = null
        val reservation = synchronized(monitor) {
            val index = beginDeliveredCallbackLocked()
            val currentRunner = runner
            val currentAdmission = admission
            if (currentRunner == null || currentAdmission == null) {
                closeDeliveredCallbackLocked(index, CallbackTerminalDisposition.SOURCE_NOT_READY)
                return@synchronized null
            }
            val rebasedCaptureNs = frame.timestampNs - sessionStartNs
            val previousTimestamp = lastDeliveredTimestampNs
            if (stopping) {
                val terminalTimestamp = maxOf(previousTimestamp ?: 0L, rebasedCaptureNs.coerceAtLeast(0L))
                observeDeliveredTimestampLocked(terminalTimestamp)
                return@synchronized FrameWork(
                    runner = currentRunner,
                    index = index,
                    timestampNs = terminalTimestamp,
                    classification = com.calisthenics.diagnostic.live.LiveFrameClassification.SESSION_STOPPING,
                    canonical = null,
                    terminalDisposition = CallbackTerminalDisposition.SESSION_STOPPING,
                )
            }
            if (rebasedCaptureNs < 0L || previousTimestamp?.let { rebasedCaptureNs < it } == true) {
                observeDeliveredTimestampLocked(previousTimestamp ?: 0L)
                return@synchronized FrameWork(
                    runner = currentRunner,
                    index = index,
                    timestampNs = previousTimestamp ?: 0L,
                    classification = com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED,
                    canonical = null,
                    terminalDisposition = CallbackTerminalDisposition.CLOCK_FAULT,
                )
            }
            observeDeliveredTimestampLocked(rebasedCaptureNs)
            stageRecorder?.record(index, PipelineStage.YUV_OWNERSHIP, PipelineDisposition.ACQUIRED)
            val negotiated = negotiatedSourceResolution
            if (negotiated == null || frame.width != negotiated.width || frame.height != negotiated.height) {
                return@synchronized FrameWork(
                    currentRunner,
                    index,
                    rebasedCaptureNs,
                    com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED,
                    null,
                    CallbackTerminalDisposition.SOURCE_LOSS,
                )
            }
            val geometry = SourceGeometry(frame.width, frame.height, frame.rotation, frame.mirrored)
            val previousGeometry = observedSourceGeometry
            if (previousGeometry != null && previousGeometry != geometry) {
                return@synchronized FrameWork(
                    currentRunner,
                    index,
                    rebasedCaptureNs,
                    com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED,
                    null,
                    CallbackTerminalDisposition.SOURCE_LOSS,
                )
            }
            observedSourceGeometry = geometry
            // Video fans out from every geometry-valid delivered frame, independently of the
            // inference cadence decision below. The camera runs at 30 FPS while inference is
            // decimated to 10/15/30, which is exactly why the evidence counts distinguish
            // delivered_camera_callbacks, encoded_video_frames, and submitted_inference_frames.
            // Encoding a frame is recorded here but performed outside this monitor.
            pendingVideoFrame = PendingVideoFrame(index, rebasedCaptureNs, frame)
            manifest = manifest?.copy(
                camera = manifest!!.camera.copy(
                    captureWidth = geometry.width,
                    captureHeight = geometry.height,
                    orientationDegrees = geometry.rotation.degrees,
                    mirrored = geometry.mirrored,
                ),
            )
            when (val classification = currentAdmission.reserve(rebasedCaptureNs)) {
                com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT ->
                    ReservedFrame(currentRunner, index, rebasedCaptureNs, frame)

                else -> FrameWork(
                    currentRunner,
                    index,
                    rebasedCaptureNs,
                    classification,
                    null,
                    classification.toTerminalDisposition(),
                )
            }
        }

        // Outside the monitor: encoding a frame must never hold the session lock.
        pendingVideoFrame?.let { encodeVideoFrame(it) }

        if (reservation == null) return

        val work = when (reservation) {
            is FrameWork -> reservation
            is ReservedFrame -> {
                synchronized(monitor) {
                    stageRecorder?.record(
                        reservation.index,
                        PipelineStage.PREPROCESSING,
                        PipelineDisposition.STARTED,
                    )
                }
                val canonical = runCatching { canonicalize(reservation.frame) }.getOrNull()
                synchronized(monitor) {
                    val classification = admission?.commitCandidate(canonical != null)
                        ?: com.calisthenics.diagnostic.live.LiveFrameClassification.SESSION_STOPPING
                    if (canonical != null) {
                        stageRecorder?.record(
                            reservation.index,
                            PipelineStage.PREPROCESSING,
                            PipelineDisposition.COMPLETED,
                        )
                    }
                    FrameWork(
                        runner = reservation.runner,
                        index = reservation.index,
                        timestampNs = reservation.timestampNs,
                        classification = classification,
                        canonical = if (classification == com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT) canonical else null,
                        terminalDisposition = if (classification == com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT) {
                            null
                        } else {
                            classification.toTerminalDisposition()
                        },
                    )
                }
            }

            else -> error("unexpected live frame reservation")
        }

        when (work.classification) {
            com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT -> {
                val inferenceStartNs = relativeNowNs()
                synchronized(monitor) {
                    check(pendingInferenceCallback == null) { "an inference callback is already pending" }
                    pendingInferenceCallback = PendingInferenceCallback(nextExpectedSubmissionIndex, work.index)
                    callbacksForwardedToRunner += 1L
                    stageRecorder?.record(work.index, PipelineStage.INFERENCE, PipelineDisposition.SUBMITTED)
                }
                val submissionIndex = work.runner.submit(
                    work.index,
                    work.timestampNs,
                    inferenceStartNs,
                    checkNotNull(work.canonical),
                )
                if (submissionIndex != null) {
                    synchronized(monitor) {
                        check(submissionIndex == nextExpectedSubmissionIndex) {
                            "runner submission index diverged from callback attribution"
                        }
                        nextExpectedSubmissionIndex += 1L
                    }
                    val terminal = synchronized(monitor) { inferenceTerminal(submissionIndex) }
                    if (!terminal) {
                        val newStallHandle = dependencies.scheduler.scheduleAfter(STALL_TIMEOUT_NS) {
                            onInferenceStall(submissionIndex, onProgress)
                        }
                        val keepHandle = synchronized(monitor) {
                            if (resourcesReleased || stopping || inferenceTerminal(submissionIndex)) {
                                false
                            } else {
                                stallHandle?.dispose()
                                stallHandle = newStallHandle
                                true
                            }
                        }
                        if (!keepHandle) newStallHandle.dispose()
                    }
                } else {
                    synchronized(monitor) {
                        if (pendingInferenceCallback?.frameIndex == work.index) {
                            pendingInferenceCallback = null
                            runCatching { admission?.completeSubmission() }
                            closeDeliveredCallbackLocked(
                                work.index,
                                CallbackTerminalDisposition.SESSION_STOPPING,
                            )
                        }
                    }
                }
            }

            else -> {
                synchronized(monitor) {
                    closeDeliveredCallbackLocked(
                        work.index,
                        checkNotNull(work.terminalDisposition),
                    )
                    callbacksForwardedToRunner += 1L
                }
                work.runner.skip(work.index, work.timestampNs, work.classification.toSkipReason())
            }
        }
        onProgress(work.runner.snapshot())
    }

    private fun handleRejectedFrame(
        sourceTimestampNs: Long?,
        onProgress: (SessionRunnerSnapshot) -> Unit,
    ) {
        val work = synchronized(monitor) {
            val index = beginDeliveredCallbackLocked()
            val currentRunner = runner
            val currentAdmission = admission
            if (currentRunner == null || currentAdmission == null) {
                closeDeliveredCallbackLocked(index, CallbackTerminalDisposition.SOURCE_NOT_READY)
                return@synchronized null
            }
            val rebased = sourceTimestampNs?.minus(sessionStartNs)
            val previous = lastDeliveredTimestampNs
            val clockFault = rebased == null || rebased < 0L || previous?.let { rebased < it } == true
            val timestamp = if (clockFault) previous ?: 0L else checkNotNull(rebased)
            observeDeliveredTimestampLocked(timestamp)
            val disposition = if (clockFault && sourceTimestampNs != null) {
                CallbackTerminalDisposition.CLOCK_FAULT
            } else {
                CallbackTerminalDisposition.SOURCE_LOSS
            }
            closeDeliveredCallbackLocked(index, disposition)
            callbacksForwardedToRunner += 1L
            FrameWork(
                currentRunner,
                index,
                timestamp,
                com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED,
                null,
                disposition,
            )
        } ?: return
        work.runner.skip(work.index, work.timestampNs, work.classification.toSkipReason())
        onProgress(work.runner.snapshot())
    }

    private fun beginDeliveredCallbackLocked(): Long {
        val index = frameIndex++
        deliveredCallbacks.begin(index)
        stageRecorder?.record(index, PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED)
        return index
    }

    private fun observeDeliveredTimestampLocked(timestampNs: Long) {
        val observedTimestamp = maxOf(lastDeliveredTimestampNs ?: 0L, timestampNs)
        admittedCallbackCadence.observe(observedTimestamp)
        lastDeliveredTimestampNs = observedTimestamp
    }

    private fun closeDeliveredCallbackLocked(
        index: Long,
        disposition: CallbackTerminalDisposition,
    ) {
        deliveredCallbacks.close(index, disposition)
        val terminal = disposition.pipelineTerminalEvent()
        stageRecorder?.record(index, terminal.stage, terminal.disposition)
    }

    internal fun callbackAccounting(): DeliveredCallbackAccounting = synchronized(monitor) {
        deliveredCallbacks.snapshot()
    }

    /** Lazily constructed per run, and only when this run records video. */
    private var videoWriter: com.calisthenics.diagnostic.video.LiveVideoWriter? = null
    private var videoWriterFinished = false
    private val videoDispositions =
        mutableListOf<Pair<Long, com.calisthenics.diagnostic.video.VideoDisposition>>()

    private fun encodeVideoFrame(pending: PendingVideoFrame) {
        val factory = dependencies.videoWriterFactory ?: return
        val writer = synchronized(monitor) {
            if (videoWriterFinished) return
            videoWriter ?: factory().also { videoWriter = it }
        }
        val disposition = writer.accept(pending.index, pending.rebasedCaptureNs, pending.frame)
        synchronized(monitor) { videoDispositions += pending.index to disposition }
    }

    /**
     * Finish the video writer before the pre-seal fence continues.
     *
     * Ordering matters: the encoder and muxer must be finalized before telemetry is joined and the
     * session sealed, so no sample can arrive after the manifest describes the run.
     */
    private fun finalizeVideoWriter() {
        val writer = synchronized(monitor) {
            if (videoWriterFinished) return
            videoWriterFinished = true
            videoWriter
        } ?: return
        writer.finish()
    }

    /** Per-run video accounting, or null when this run recorded no video. */
    internal fun videoAccounting(): com.calisthenics.diagnostic.video.VideoAccounting? =
        synchronized(monitor) { videoWriter?.accounting() }

    private fun canonicalize(frame: OwnedYuvFrame): ByteArray {
        val upright = yuv420ToUprightRgb24(
            Yuv420Frame(
                width = frame.width,
                height = frame.height,
                y = YuvPlane(frame.y.bytes, frame.y.rowStride, frame.y.pixelStride),
                u = YuvPlane(frame.u.bytes, frame.u.rowStride, frame.u.pixelStride),
                v = YuvPlane(frame.v.bytes, frame.v.rowStride, frame.v.pixelStride),
                colorStandard = frame.colorStandard,
                colorRange = frame.colorRange,
                rotation = frame.rotation,
                mirrored = frame.mirrored,
            ),
        )
        val (uprightWidth, uprightHeight) = uprightDimensions(frame.width, frame.height, frame.rotation)
        return scalePadRgb24(
            source = upright,
            sourceWidth = uprightWidth,
            sourceHeight = uprightHeight,
            targetWidth = dependencies.modelInputWidth,
            targetHeight = dependencies.modelInputHeight,
        )
    }

    private fun com.calisthenics.diagnostic.live.LiveFrameClassification.toSkipReason(): SkipReason = when (this) {
        com.calisthenics.diagnostic.live.LiveFrameClassification.CADENCE_SELECTION -> SkipReason.CADENCE_SELECTION
        com.calisthenics.diagnostic.live.LiveFrameClassification.BACKPRESSURE -> SkipReason.BACKPRESSURE
        com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED -> SkipReason.PREPROCESSING_FAILED
        com.calisthenics.diagnostic.live.LiveFrameClassification.SESSION_STOPPING -> SkipReason.SESSION_STOPPING
        com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT -> error("submitted frame has no skip reason")
    }

    private fun com.calisthenics.diagnostic.live.LiveFrameClassification.toTerminalDisposition(): CallbackTerminalDisposition =
        when (this) {
            com.calisthenics.diagnostic.live.LiveFrameClassification.CADENCE_SELECTION ->
                CallbackTerminalDisposition.CADENCE_SELECTION
            com.calisthenics.diagnostic.live.LiveFrameClassification.BACKPRESSURE ->
                CallbackTerminalDisposition.BACKPRESSURE
            com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED ->
                CallbackTerminalDisposition.PREPROCESSING_FAILED
            com.calisthenics.diagnostic.live.LiveFrameClassification.SESSION_STOPPING ->
                CallbackTerminalDisposition.SESSION_STOPPING
            com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT ->
                error("submitted frame is not terminal before inference")
        }

    private fun onInferenceResult(submissionIndex: Long, result: InferenceResult) {
        synchronized(monitor) {
            val pending = checkNotNull(pendingInferenceCallback) {
                "inference result has no originating delivered callback"
            }
            check(pending.submissionIndex == submissionIndex) {
                "inference result does not match its originating delivered callback"
            }
            closeDeliveredCallbackLocked(
                pending.frameIndex,
                if (result is InferenceResult.Success) {
                    CallbackTerminalDisposition.INFERENCE_COMPLETED
                } else {
                    CallbackTerminalDisposition.INFERENCE_FAILED
                },
            )
            pendingInferenceCallback = null
        }
        if (result is InferenceResult.Failure) fenceSourceCallbacksBeforeInferenceFailure()
    }

    private fun onInferenceTerminal(submissionIndex: Long) {
        val currentRunner = synchronized(monitor) {
            terminalSubmissionHighWaterMark = maxOf(terminalSubmissionHighWaterMark ?: -1L, submissionIndex)
            try {
                admission?.completeSubmission()
            } catch (_: IllegalStateException) {
                // A stalled or already-terminal callback is harmless after the admission fence.
            }
            if (stallHandle != null) {
                stallHandle?.dispose()
                stallHandle = null
            }
            runner
        }
        val state = currentRunner?.snapshot()
        if (state?.lifecycle == SessionLifecycle.FAILED) {
            requestStop()
            currentRunner.let(::finishIfSealed)
        } else {
            currentRunner?.let(::finishIfSealed)
        }
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun onInferenceCancelled(submissionIndex: Long) {
        synchronized(monitor) {
            val pending = checkNotNull(pendingInferenceCallback) {
                "cancelled inference has no originating delivered callback"
            }
            check(pending.submissionIndex == submissionIndex) {
                "cancelled inference does not match its originating delivered callback"
            }
            closeDeliveredCallbackLocked(pending.frameIndex, CallbackTerminalDisposition.INFERENCE_FAILED)
            pendingInferenceCallback = null
            terminalSubmissionHighWaterMark = maxOf(terminalSubmissionHighWaterMark ?: -1L, submissionIndex)
            runCatching { admission?.completeSubmission() }
            disposeStallHandleLocked()
        }
    }

    private fun fenceSourceCallbacksBeforeInferenceFailure() {
        val sourceToFence = synchronized(monitor) {
            if (stopping) return
            stopping = true
            admission?.stop()
            disposeStallHandleLocked()
            requestSourceTeardownLocked()
            takeSourceToStopLocked()
        }
        val callbacksQuiesced = CountDownLatch(1)
        stopReleasedSource(sourceToFence) { callbacksQuiesced.countDown() }
        callbacksQuiesced.await()
    }

    private fun onInferenceStall(submissionIndex: Long, onProgress: (SessionRunnerSnapshot) -> Unit) {
        val pendingRunner = synchronized(monitor) {
            if (stopping || completion != null) return
            runner
        }
        if (pendingRunner == null || !pendingRunner.hasPendingInference()) return
        val stopWork = synchronized(monitor) {
            if (stopping || completion != null || inferenceTerminalLocked(submissionIndex)) return
            stalled = true
            requestStopLocked()
        }
        performStop(stopWork, onProgress)
    }

    private fun handleSourceFailure(failure: Throwable, onProgress: (SessionRunnerSnapshot) -> Unit) {
        val stopWork = synchronized(monitor) {
            if (completion != null) return
            if (failure is TelemetryDispatchException) {
                recordTelemetryFailureLocked(failure)
            } else if (sourceFailure == null) {
                sourceFailure = LiveInternalFailure.SourceFailure("live_source_failed")
            }
            requestStopLocked()
        }
        performStop(stopWork, onProgress)
    }

    private fun requestStop() {
        val stopWork = synchronized(monitor) { requestStopLocked() }
        performStop(stopWork)
    }

    private data class StopWork(
        val source: LiveFrameSource?,
        val runner: SessionRunner?,
        val waitForSourceStart: Boolean,
    )

    private fun requestStopLocked(): StopWork? {
        if (stopping) return null
        stopping = true
        admission?.stop()
        disposeStallHandleLocked()
        val waitForSourceStart = requestSourceTeardownLocked()
        return StopWork(
            source = takeSourceToStopLocked(),
            runner = runner,
            waitForSourceStart = waitForSourceStart,
        )
    }

    private fun finishIfSealed(currentRunner: SessionRunner) {
        val state = currentRunner.snapshot()
        if (state.lifecycle != com.calisthenics.diagnostic.session.SessionLifecycle.SEALED &&
            state.lifecycle != com.calisthenics.diagnostic.session.SessionLifecycle.FAILED
        ) {
            return
        }
        if (state.lifecycle == SessionLifecycle.FAILED) {
            try {
                runPreSealFence(state)
            } catch (error: Throwable) {
                synchronized(monitor) { recordTelemetryFailureLocked(error) }
            }
        }
        synchronized(monitor) {
            if (finalizationStarted || completion != null || abandonRequested) return
            finalizationStarted = true
        }
        val finalization = currentRunner.finalize()
        val finalSnapshot = currentRunner.snapshot()
        val finishedAndPlan = synchronized(monitor) {
            if (completion != null || abandonRequested) return
            val finished = LiveCameraCompletion(
                sessionId = checkNotNull(sessionId),
                finalization = finalization,
                snapshot = finalSnapshot,
                internalFailure = telemetryFailure ?: sourceFailure ?: if (stalled) LiveInternalFailure.LiveInferenceStalled else null,
                cadenceDiagnostics = cadenceDiagnostics,
                thermalObservations = thermalProbe?.observations().orEmpty(),
            )
            completion = finished
            finished to captureResourceReleasePlanLocked(abandonRunner = false)
        }
        val finished = finishedAndPlan.first
        performRelease(finishedAndPlan.second)
        runCatching { dependencies.onCompletion(finished) }
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun performStop(stopWork: StopWork?, onProgress: ((SessionRunnerSnapshot) -> Unit)? = null) {
        if (stopWork == null) return
        if (stopWork.waitForSourceStart) sourceStartFinished.await()
        stopReleasedSource(stopWork.source) {
            val stoppedSnapshot = try {
                stopWork.runner?.stop(relativeNowNs())
            } catch (error: Throwable) {
                synchronized(monitor) { recordTelemetryFailureLocked(error) }
                stopWork.runner?.snapshot()
            }
            if (stoppedSnapshot != null) onProgress?.invoke(stoppedSnapshot)
            stopWork.runner?.let(::finishIfSealed)
        }
    }

    private fun stopReleasedSource(
        source: LiveFrameSource?,
        onCallbacksQuiesced: () -> Unit = {},
    ) {
        val signalDelivered = AtomicBoolean(false)
        val signal = {
            if (signalDelivered.compareAndSet(false, true)) onCallbacksQuiesced()
        }
        runCatching {
            if (source == null) signal() else source.stop(signal)
        }.onFailure { signal() }
    }

    private data class ResourceReleasePlan(
        val source: LiveFrameSource?,
        val runner: SessionRunner?,
        val inference: LiveInference?,
        val thermalProbe: LiveThermalProbe?,
        val telemetryController: LiveTelemetryController?,
        val finalizer: SessionFinalizer?,
        val dispatcher: SingleThreadSessionDispatcher?,
        val scheduler: LiveScheduler,
        val abandonRunner: Boolean,
    )

    private fun captureResourceReleasePlanLocked(abandonRunner: Boolean): ResourceReleasePlan? {
        if (resourcesReleased) return null
        resourcesReleased = true
        disposeStallHandleLocked()
        val sourceToStop = takeSourceToStopLocked()
        return ResourceReleasePlan(
            source = sourceToStop,
            runner = runner,
            inference = inference,
            thermalProbe = thermalProbe,
            telemetryController = telemetryController,
            finalizer = sessionFinalizer,
            dispatcher = sessionDispatcher,
            scheduler = dependencies.scheduler,
            abandonRunner = abandonRunner,
        )
    }

    private fun performRelease(plan: ResourceReleasePlan?, sourceAlreadyStopped: Boolean = false) {
        if (plan == null) return
        val release = {
            runCatching { plan.telemetryController?.stopAndJoinBeforeSeal() }
            if (plan.abandonRunner) {
                plan.runner?.let { runnerToAbandon -> runCatching { runnerToAbandon.abandon() } }
            }
            runCatching { (plan.finalizer as? DeferredSessionAbort)?.completePendingAbort() }
            runCatching { plan.inference?.disposable?.dispose() }
            runCatching { plan.thermalProbe?.dispose() }
            runCatching { plan.dispatcher?.shutdown() }
            runCatching { plan.scheduler.shutdown() }
            Unit
        }
        if (sourceAlreadyStopped) release() else stopReleasedSource(plan.source, release)
    }

    private fun takeSourceToStopLocked(): LiveFrameSource? {
        if (sourceStopped) return null
        sourceStopped = true
        return source
    }

    private fun requestSourceTeardownLocked(): Boolean {
        while (true) {
            val current = sourceLifecycle.get()
            when (current) {
                SourceLifecycle.STARTING -> if (sourceLifecycle.compareAndSet(current, SourceLifecycle.TEARDOWN_REQUESTED)) return true
                SourceLifecycle.NOT_PUBLISHED,
                SourceLifecycle.PUBLISHED,
                SourceLifecycle.STARTED,
                -> if (sourceLifecycle.compareAndSet(current, SourceLifecycle.TEARDOWN_REQUESTED)) return false

                SourceLifecycle.TEARDOWN_REQUESTED -> return false
            }
        }
    }

    private fun disposeStallHandleLocked() {
        runCatching { stallHandle?.dispose() }
        stallHandle = null
    }

    private var terminalSubmissionHighWaterMark: Long? = null

    private fun inferenceTerminal(submissionIndex: Long): Boolean = synchronized(monitor) {
        inferenceTerminalLocked(submissionIndex)
    }

    private fun inferenceTerminalLocked(submissionIndex: Long): Boolean =
        terminalSubmissionHighWaterMark?.let { submissionIndex <= it } ?: false

    private fun relativeNowNs(): Long = (dependencies.clock.nowNs() - sessionStartNs).coerceAtLeast(0L)

    private fun runPreSealFence(runnerSnapshot: SessionRunnerSnapshot) {
        synchronized(monitor) {
            if (finalizationFenceCompleted) return
        }
        finalizeVideoWriter()
        dependencies.encoderFinalizationSlot.finalizeEncoder()
        val controller = synchronized(monitor) { telemetryController.takeIf { telemetryStarted } }
        try {
            controller?.stopAndJoinBeforeSeal()
        } catch (error: Throwable) {
            synchronized(monitor) {
                recordTelemetryFailureLocked(error)
                finalizationFenceCompleted = true
            }
            throw error
        }
        synchronized(monitor) {
            deliveredCallbacks.assertClosed(
                callbacksForwardedToRunner = callbacksForwardedToRunner,
                cameraFramesReceived = runnerSnapshot.counts.cameraFramesReceived,
                diagnosticDropsAfterSeal = runnerSnapshot.diagnosticDropsAfterSeal,
            )
            stageRecorder?.assertReconciles(deliveredCallbacks.snapshot())
            finalizationFenceCompleted = true
        }
    }

    private fun recordTelemetryFailureLocked(error: Throwable) {
        if (telemetryFailure != null) return
        val dispatchFailure = generateSequence(error as Throwable?) { it.cause }
            .filterIsInstance<TelemetryDispatchException>()
            .firstOrNull()
        val code = when (dispatchFailure?.failureCode) {
            TelemetryDispatchFailureCode.QUEUE_OVERFLOW -> "live_telemetry_queue_overflow"
            TelemetryDispatchFailureCode.SINK_FAILURE -> "live_telemetry_sink_failed"
            TelemetryDispatchFailureCode.JOIN_FAILURE -> "live_telemetry_join_failed"
            TelemetryDispatchFailureCode.POST_FENCE_APPEND -> "live_telemetry_post_fence_append"
            null -> "live_telemetry_sampler_failed"
        }
        val originalCause = dispatchFailure?.cause ?: error
        val causeName = originalCause.javaClass.simpleName.ifBlank { "Throwable" }
        val causeMessage = originalCause.message.orEmpty()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(160)
        telemetryFailure = LiveInternalFailure.TelemetryFailure(
            code = code,
            sanitizedCause = if (causeMessage.isBlank()) causeName else "$causeName: $causeMessage",
            cause = originalCause,
        )
    }

    private fun manifestAtFinalization(
        finalized: SessionManifest,
        sealed: com.calisthenics.diagnostic.session.SealedSessionSnapshot,
        operatorAttestedNoPerson: Boolean,
    ): SessionManifest = synchronized(monitor) {
        check(finalizationFenceCompleted) { "callback accounting must be fenced before finalization" }
        val geometry = checkNotNull(observedSourceGeometry) { "source_geometry_not_observed" }
        val classificationGap = sealed.counts.cameraFramesReceived -
            sealed.counts.inferenceFramesSubmitted -
            sealed.counts.framesSkippedBeforeInference
        val cadenceDiagnostics = admittedCallbackCadence.snapshot(classificationGap)
        check(cadenceDiagnostics.deliveredCallbackCount == sealed.counts.cameraFramesReceived) {
            "camera cadence delivery count must match cameraFramesReceived"
        }
        this@LiveCameraComposition.cadenceDiagnostics = cadenceDiagnostics
        val activeThermalProbe = checkNotNull(thermalProbe)
        val thermalObservations = activeThermalProbe.observations()
        val finalizedM05Admission = evaluateM05Admission(
            id = checkNotNull(sessionId),
            operatorAttestedNoPerson = operatorAttestedNoPerson,
            submittedFrameCount = sealed.counts.inferenceFramesSubmitted,
            detectionActiveFrameCount = sealed.counts.poseDetectedRecords,
        )
        m05Admission = finalizedM05Admission
        finalized.copy(
            sessionKind = finalizedM05Admission.sessionKind,
            participantPseudonymId = finalizedM05Admission.participantPseudonymId,
            camera = finalized.camera.copy(
                captureWidth = geometry.width,
                captureHeight = geometry.height,
                orientationDegrees = geometry.rotation.degrees,
                mirrored = geometry.mirrored,
            ),
            availableObservations = AvailableObservations(
                normalizedImageLandmarksAvailable = sealed.boundedState.normalizedImageLandmarksAvailable,
                worldLandmarksAvailable = sealed.boundedState.worldLandmarksAvailable,
            ),
            cadence = finalized.cadence.copy(
                achievedInferenceFpsTimeseries = sealed.boundedState.achievedInferenceFpsTimeseries,
            ),
            clock = finalized.clock.copy(endedAtUtc = dependencies.wallClockUtc()),
            studyContext = finalized.studyContext + mapOf(
                "m05_admission" to finalizedM05Admission.asLiveEvidenceJson(),
                "camera_cadence_diagnostics" to cadenceDiagnostics.asJson(),
                "thermal_observations" to buildJsonObject {
                    put("api_available", activeThermalProbe.available)
                    put("unavailable_reason", activeThermalProbe.unavailableReason)
                    put("samples", buildJsonArray {
                        thermalObservations.forEach { observation ->
                            add(buildJsonObject {
                                put("t_monotonic_ns", observation.tMonotonicNs)
                                put("status", observation.status)
                            })
                        }
                    })
                },
            ),
        ).also { completed -> manifest = completed }
    }

    private fun buildManifest(
        id: Uuid,
        configuredInferenceFps: Int,
        evidence: PoseLandmarkerConstructionEvidence,
        startedAtUtc: String,
        m05Admission: M05AdmissionResult,
    ): SessionManifest {
        val metadata = dependencies.manifestMetadata
        val provenance = metadata.provenance
        val studyContext: Map<String, JsonElement> = mapOf(
            "m05_admission" to m05Admission.asLiveEvidenceJson(),
            "mediapipe_construction_evidence" to buildJsonObject {
                put("model_sha256", evidence.modelSha256)
                put("tasks_vision_version", evidence.tasksVisionVersion)
                put("running_mode", evidence.runningMode)
                put("delegate", evidence.delegate)
                put("num_poses", evidence.numPoses)
                put("min_pose_detection_confidence", evidence.minPoseDetectionConfidence)
                put("min_pose_presence_confidence", evidence.minPosePresenceConfidence)
                put("min_tracking_confidence", evidence.minTrackingConfidence)
                put("output_segmentation_masks", evidence.outputSegmentationMasks)
                put("model_input_width", evidence.modelInputWidth)
                put("model_input_height", evidence.modelInputHeight)
            },
        ) + metadata.attributionIdentity.takeIf { it.isNotEmpty() }?.let { identity ->
            mapOf("m05b_attribution_identity" to buildJsonObject {
                identity.entries.sortedBy { it.key }.forEach { (key, value) -> put(key, value) }
            })
        }.orEmpty()
        return SessionManifest(
            schemaVersion = SemanticVersion("1.1.0"),
            sessionId = id,
            sessionKind = m05Admission.sessionKind,
            participantPseudonymId = m05Admission.participantPseudonymId,
            captureProtocolVersion = SemanticVersion("1.0.0"),
            harnessGitSha = metadata.harnessGitSha,
            device = metadata.device.copy(thermalApiAvailable = thermalProbe?.available ?: false),
            posePipeline = PosePipeline(
                backendName = "mediapipe_pose_landmarker",
                backendVersion = evidence.tasksVisionVersion,
                modelAssetName = provenance?.assetName ?: "unknown",
                modelAssetSha256 = Sha256(provenance?.sha256 ?: evidence.modelSha256),
                delegate = evidence.delegate.lowercase(),
                runningMode = evidence.runningMode.lowercase(),
                numPoses = evidence.numPoses,
                minPoseDetectionConfidence = evidence.minPoseDetectionConfidence.toDouble(),
                minPosePresenceConfidence = evidence.minPosePresenceConfidence.toDouble(),
                minTrackingConfidence = evidence.minTrackingConfidence.toDouble(),
                landmarkTopology = "mediapipe_pose_33_v1",
            ),
            camera = Camera(
                facing = metadata.cameraFacing,
                captureWidth = REQUESTED_CAMERA_WIDTH,
                captureHeight = REQUESTED_CAMERA_HEIGHT,
                orientationDegrees = metadata.orientationDegrees,
                mirrored = metadata.mirrored,
                requestedCameraFps = CameraXFrameSource.TARGET_CAMERA_FPS.toDouble(),
                sourcePixelFormat = "YUV_420_888",
                sourceColorMatrix = dependencies.cameraCapability.sourceColorStandard,
                sourceColorRange = dependencies.cameraCapability.sourceColorRange,
                sourceDataspaceRaw = dependencies.cameraCapability.sourceDataspaceRaw?.toLong(),
                timestampSource = dependencies.cameraCapability.timestampSource,
                deliverySemantics = "keep_only_latest_observable_deliveries",
            ),
            modelInput = ModelInput(
                modelInputWidth = dependencies.modelInputWidth,
                modelInputHeight = dependencies.modelInputHeight,
                livePreprocessingContractVersion = SemanticVersion(LIVE_PREPROCESSING_VERSION),
                fixturePreprocessingContractVersion = null,
            ),
            inferenceDrainTimeoutMs = dependencies.drainTimeoutNs / 1_000_000L,
            cadence = Cadence(
                configuredInferenceFps = configuredInferenceFps.toDouble(),
                achievedInferenceFpsTimeseries = emptyList(),
                trackerUpdateCadenceHz = null,
            ),
            clock = Clock(
                monotonicTimeBase = "session_relative_monotonic_ns",
                t0MonotonicNs = 0L,
                startedAtUtc = startedAtUtc,
                endedAtUtc = startedAtUtc,
            ),
            availableObservations = AvailableObservations(false, false),
            mediaReferences = emptyList(),
            counts = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0),
            machineInferenceFailures = emptyList(),
            captureQualityObserved = CaptureQualityObserved(false, false, false, "unobserved", false, null),
            studyContext = studyContext,
        )
    }

    private fun evaluateM05Admission(
        id: Uuid,
        operatorAttestedNoPerson: Boolean,
        submittedFrameCount: Long,
        detectionActiveFrameCount: Long,
    ): M05AdmissionResult = M05Admission.evaluate(
        M05AdmissionEvidence(
            sessionId = id.value,
            sessionKind = LIVE_SESSION_KIND,
            inputClass = LIVE_M05_INPUT_CLASS,
            operatorAttestedNoPerson = operatorAttestedNoPerson,
            submittedFrameCount = submittedFrameCount,
            detectionActiveFrameCount = detectionActiveFrameCount,
        ),
    )

    companion object {
        fun fromAndroid(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            cameraCapability: CameraCapabilityObservation,
            permissionGranted: Boolean,
            onSessionRunnerCreated: (SessionRunner) -> SessionRunnerRegistrationResult = {
                SessionRunnerRegistrationResult.ACCEPTED
            },
            onCompletion: (LiveCameraCompletion) -> Unit = {},
        ): LiveCameraComposition {
            val roots = when (val result = AndroidStorageRoots.from(context)) {
                is StorageRootsResult.Ready -> result.roots
                is StorageRootsResult.Unavailable -> error(result.reason)
            }
            val replayProvenance = parseModelProvenance(
                context.assets.open("replay/model_provenance.json").use { it.readBytes().decodeToString() },
            )
            val liveProvenance = parseModelProvenance(
                context.assets.open("replay/live_model_provenance.json")
                    .use { it.readBytes().decodeToString() },
            )
            val provenance = requireLiveModelProvenance(replayProvenance, liveProvenance)
            val clock = AndroidLiveClock()
            val thermalProbe = AndroidLiveThermalProbe.from(context)
            return LiveCameraComposition(
                Dependencies(
                    cameraCapability = cameraCapability,
                    permissionGranted = permissionGranted,
                    modelInputWidth = MODEL_INPUT_WIDTH,
                    modelInputHeight = MODEL_INPUT_HEIGHT,
                    clock = clock,
                    scheduler = ExecutorLiveScheduler(),
                    sourceFactory = { AndroidLiveFrameSource(CameraXFrameSource(context, lifecycleOwner)) },
                    inferenceFactory = { nowNs ->
                        val factory = PoseLandmarkerLiveAdapterFactory(
                            provenance = provenance,
                            modelInputWidth = MODEL_INPUT_WIDTH,
                            modelInputHeight = MODEL_INPUT_HEIGHT,
                            readModelBytes = { context.assets.open("replay/${provenance.assetName}").use { it.readBytes() } },
                            nowNs = nowNs,
                            constructDetector = realLiveDetectorConstructor(context),
                        )
                        val port = factory.create()
                        LiveInference(port, port, checkNotNull(factory.lastConstructionEvidence))
                    },
                    thermalProbeFactory = { thermalProbe },
                    telemetryProbeFactory = {
                        AndroidFixedTelemetryProbe(context, lifecycleOwner)
                    },
                    sessionFinalizerFactory = LiveSessionFinalizerFactory { template, atFinalization, monotonicNowNs ->
                        SessionExporter(
                            storage = AndroidStoragePort(roots),
                            manifestTemplate = template,
                            monotonicNowNs = monotonicNowNs,
                            manifestAtFinalization = atFinalization,
                            admissionFence = TombstoneSessionAdmissionFence(AndroidTombstoneFenceFilesystem(roots)),
                            deferAbortUntilFence = true,
                        )
                    },
                    manifestMetadata = LiveManifestMetadata(
                        harnessGitSha = BuildConfig.PROBE_GIT_SHA,
                        provenance = provenance,
                        attributionIdentity = mapOf(
                            "app_git_sha" to BuildConfig.PROBE_GIT_SHA,
                            "probe_git_sha" to BuildConfig.PROBE_GIT_SHA,
                            "apk_sha256" to Sha256Digest.hex(File(context.applicationInfo.sourceDir).readBytes()),
                            "model_sha256" to provenance.sha256,
                            "runtime_version" to Build.VERSION.RELEASE,
                            "backend_version" to provenance.tasksVisionVersion,
                            "delegate" to provenance.delegate.lowercase(),
                        ),
                        device = Device(
                            platform = "android",
                            osVersion = Build.VERSION.RELEASE,
                            deviceModel = Build.MODEL,
                            chipset = runtimeChipset(),
                            thermalApiAvailable = thermalProbe.available,
                        ),
                    ),
                    registerRunner = onSessionRunnerCreated,
                    onCompletion = onCompletion,
                ),
            )
        }

        /** SOC_MODEL is preferred when populated; older/vendor builds fall back to HARDWARE. */
        private fun runtimeChipset(): String? =
            Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                ?: Build.HARDWARE.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    }

    internal class AndroidLiveClock(
        private val elapsedRealtimeNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    ) : LiveClock {
        override fun nowNs(): Long = elapsedRealtimeNanos()
    }

    private class ExecutorLiveScheduler : LiveScheduler {
        private val executor = Executors.newSingleThreadScheduledExecutor()

        override fun scheduleAfter(delayNs: Long, action: () -> Unit): Disposable {
            require(delayNs >= 0L) { "delayNs must be non-negative" }
            val future = executor.schedule({ action() }, delayNs, TimeUnit.NANOSECONDS)
            return object : Disposable {
                override fun dispose() { future.cancel(false) }
            }
        }

        override fun shutdown() {
            executor.shutdownNow()
        }
    }

    private class AndroidLiveFrameSource(private val delegate: CameraXFrameSource) : LiveFrameSource {
        override fun start(
            onSourceStarted: (LiveSourceResolution) -> Unit,
            onFrame: (OwnedYuvFrame) -> Unit,
            onFrameRejected: (timestampNs: Long?) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) = delegate.start(
            onSourceStarted = { resolution -> onSourceStarted(resolution.toLiveSourceResolution()) },
            onFrame = onFrame,
            onFrameRejected = onFrameRejected,
            onFailure = onFailure,
        )
        override fun stop(onCallbacksQuiesced: () -> Unit) = delegate.stop(onCallbacksQuiesced)
    }
}

private fun NegotiatedAnalysisResolution.toLiveSourceResolution() = LiveSourceResolution(width, height)

private val FrameRotation.degrees: Int
    get() = when (this) {
        FrameRotation.R0 -> 0
        FrameRotation.R90 -> 90
        FrameRotation.R180 -> 180
        FrameRotation.R270 -> 270
    }

private class TerminalTrackingInferencePort(
    private val delegate: InferencePort,
    private val onResult: (Long, InferenceResult) -> Unit,
    private val onSettled: (Long) -> Unit,
    private val onCancelled: (Long) -> Unit,
) : InferencePort {
    override fun submit(
        submissionIndex: Long,
        canonicalInput: ByteArray,
        backendTimestampMs: Long,
        callback: (InferenceResult) -> Unit,
    ) {
        try {
            delegate.submit(submissionIndex, canonicalInput, backendTimestampMs) { result ->
                onResult(submissionIndex, result)
                try {
                    callback(result)
                } finally {
                    onSettled(submissionIndex)
                }
            }
        } catch (error: Throwable) {
            val result = InferenceResult.Failure(
                failureSource = "backend",
                classification = "backend_call_failed",
                detail = error.message,
            )
            onResult(submissionIndex, result)
            try {
                callback(result)
            } finally {
                onSettled(submissionIndex)
            }
        }
    }

    override fun cancel(submissionIndex: Long) {
        onCancelled(submissionIndex)
        delegate.cancel(submissionIndex)
    }
}

private fun exactCameraCapabilityBlockingReasons(observation: CameraCapabilityObservation): List<String> = buildList {
    if (observation.cameraId != OwnedYuvFrame.CAMERA_ID) add("camera_id_not_0")
    if (observation.timestampSource != OwnedYuvFrame.ADMITTED_TIMESTAMP_SOURCE) add("timestamp_source_not_realtime")
    if (observation.sourceDataspaceRaw != OwnedYuvFrame.ADMITTED_RAW_DATASPACE) add("raw_dataspace_not_admitted")
    if (observation.sourceColorStandard != "BT601") add("colour_standard_not_bt601")
    if (observation.sourceColorRange != "FULL") add("colour_range_not_full")
    if (!observation.underlyingImageAvailable) add("underlying_image_bridge_unavailable")
    if (observation.pixelFormat != "YUV_420_888") add("pixel_format_not_yuv_420_888")
}
