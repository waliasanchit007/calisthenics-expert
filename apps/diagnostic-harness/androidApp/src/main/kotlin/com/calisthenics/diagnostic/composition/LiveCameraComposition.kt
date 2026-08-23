package com.calisthenics.diagnostic.composition

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.camera.CameraXFrameSource
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
import com.calisthenics.diagnostic.export.SessionExporter
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.live.LiveFrameAdmission
import com.calisthenics.diagnostic.live.achievedInferenceFpsTimeseriesOf
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerConstructionEvidence
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerLiveAdapterFactory
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerLiveInferencePort
import com.calisthenics.diagnostic.mediapipe.realLiveDetectorConstructor
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.ModelProvenanceException
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.model.requireLiveModelProvenance
import com.calisthenics.diagnostic.preprocessing.scalePadRgb24
import com.calisthenics.diagnostic.preprocessing.uprightDimensions
import com.calisthenics.diagnostic.preprocessing.yuv420ToUprightRgb24
import com.calisthenics.diagnostic.preprocessing.Yuv420Frame
import com.calisthenics.diagnostic.preprocessing.YuvPlane
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.session.DeadlineScheduler
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import com.calisthenics.diagnostic.session.SessionFinalizer
import com.calisthenics.diagnostic.session.SessionRunner
import com.calisthenics.diagnostic.session.SessionRunnerSnapshot
import com.calisthenics.diagnostic.session.SessionConfiguration
import com.calisthenics.diagnostic.session.SkipReason
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    data class Initialization(val detail: String) : LiveUnavailableReason {
        override val code: String = "live_initialization_failed"
    }
}

sealed interface LiveInternalFailure {
    val code: String

    data object LiveInferenceStalled : LiveInternalFailure {
        override val code: String = "live_inference_stalled"
    }
}

data class LiveCameraCompletion(
    val sessionId: Uuid,
    val finalization: FinalizationResult,
    val snapshot: SessionRunnerSnapshot,
    val internalFailure: LiveInternalFailure? = null,
)

data class LiveInference(
    val port: InferencePort,
    val disposable: Disposable,
    val constructionEvidence: PoseLandmarkerConstructionEvidence,
)

interface LiveClock {
    fun nowNs(): Long
}

interface LiveScheduler {
    /** Schedules [action] after an epoch-free duration; this scheduler owns no clock domain. */
    fun scheduleAfter(delayNs: Long, action: () -> Unit): Disposable
    fun shutdown() = Unit
}

interface LiveFrameSource {
    fun start(onFrame: (OwnedYuvFrame) -> Unit, onFailure: (Throwable) -> Unit)
    fun stop()
}

fun interface LiveSessionFinalizerFactory {
    fun create(
        manifestTemplate: SessionManifest,
        manifestAtFinalization: (SessionManifest, com.calisthenics.diagnostic.session.SealedSessionSnapshot) -> SessionManifest,
    ): SessionFinalizer
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
        val sourceFactory: () -> LiveFrameSource,
        val inferenceFactory: (nowNs: () -> Long) -> LiveInference,
        val sessionFinalizerFactory: LiveSessionFinalizerFactory,
        val sessionIdProvider: () -> Uuid = { Uuid(randomSessionId()) },
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
        val participantPseudonymId: String = "device-live",
        val cameraFacing: String = "back",
        val orientationDegrees: Int = 0,
        val mirrored: Boolean = false,
        val harnessGitSha: String = "0000000000000000000000000000000000000000",
        val provenance: ModelProvenance? = null,
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
    private var manifest: SessionManifest? = null
    private var frameIndex = 0L
    private var observedSourceWidth: Int? = null
    private var observedSourceHeight: Int? = null
    private var stopping = false
    private var stallHandle: Disposable? = null
    private var stalled = false
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

    fun start(configuredInferenceFps: Int, onProgress: (SessionRunnerSnapshot) -> Unit = {}): LiveCameraStartResult {
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
            val id = dependencies.sessionIdProvider()
            sessionId = id
            createdSessionId = id
            try {
                val liveInference = dependencies.inferenceFactory(::relativeNowNs)
                inference = liveInference
                val template = buildManifest(
                    id,
                    configuredInferenceFps,
                    liveInference.constructionEvidence,
                    dependencies.wallClockUtc(),
                )
                manifest = template
                val trackedInference = TerminalTrackingInferencePort(
                    delegate = liveInference.port,
                    onTerminal = { submissionIndex -> onInferenceTerminal(submissionIndex) },
                )
                val liveDeadlineScheduler = LiveDeadlineScheduler(dependencies.scheduler, ::relativeNowNs)
                val runnerDispatcher = SingleThreadSessionDispatcher()
                sessionDispatcher = runnerDispatcher
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
                    finalizer = dependencies.sessionFinalizerFactory.create(template) { finalized, sealed ->
                        synchronized(monitor) {
                            val completed = finalized.copy(
                                camera = finalized.camera.copy(
                                    captureWidth = observedSourceWidth ?: finalized.camera.captureWidth,
                                    captureHeight = observedSourceHeight ?: finalized.camera.captureHeight,
                                ),
                                cadence = finalized.cadence.copy(
                                    achievedInferenceFpsTimeseries = achievedInferenceFpsTimeseriesOf(sealed.records),
                                ),
                                clock = finalized.clock.copy(endedAtUtc = dependencies.wallClockUtc()),
                            )
                            manifest = completed
                            completed
                        }
                    },
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
                onFrame = { frame -> handleFrame(frame, onProgress) },
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
        if (!waitForSourceStart) stopReleasedSource(plan.source)
        val release = {
            if (waitForSourceStart) {
                sourceStartFinished.await()
                stopReleasedSource(plan.source)
            }
            performRelease(plan, sourceAlreadyStopped = true)
            synchronized(monitor) { monitor.notifyAll() }
        }
        if (waitForSourceStart || plan.runner != null) {
            Thread {
                release()
            }.start()
        } else {
            release()
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

    internal val internalFailure: LiveInternalFailure?
        get() = synchronized(monitor) {
            if (stalled) LiveInternalFailure.LiveInferenceStalled else null
        }

    private data class FrameWork(
        val runner: SessionRunner,
        val index: Long,
        val timestampNs: Long,
        val classification: com.calisthenics.diagnostic.live.LiveFrameClassification,
        val canonical: ByteArray?,
    )

    private fun handleFrame(frame: OwnedYuvFrame, onProgress: (SessionRunnerSnapshot) -> Unit) {
        val work = synchronized(monitor) {
            val currentRunner = runner ?: return
            val currentAdmission = admission ?: return
            val rebasedCaptureNs = frame.timestampNs - sessionStartNs
            val index = frameIndex++
            if (rebasedCaptureNs < 0L) {
                return@synchronized FrameWork(
                    runner = currentRunner,
                    index = index,
                    timestampNs = 0L,
                    classification = com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED,
                    canonical = null,
                )
            }
            observedSourceWidth = observedSourceWidth ?: frame.width
            observedSourceHeight = observedSourceHeight ?: frame.height
            manifest = manifest?.copy(
                camera = manifest!!.camera.copy(
                    captureWidth = observedSourceWidth!!,
                    captureHeight = observedSourceHeight!!,
                ),
            )
            val canonical = try {
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
                scalePadRgb24(
                    source = upright,
                    sourceWidth = uprightWidth,
                    sourceHeight = uprightHeight,
                    targetWidth = dependencies.modelInputWidth,
                    targetHeight = dependencies.modelInputHeight,
                )
            } catch (_: Throwable) {
                val classification = currentAdmission.classify(rebasedCaptureNs, preprocessingSucceeded = false)
                return@synchronized FrameWork(currentRunner, index, rebasedCaptureNs, classification, null)
            }

            FrameWork(
                runner = currentRunner,
                index = index,
                timestampNs = rebasedCaptureNs,
                classification = currentAdmission.classify(rebasedCaptureNs),
                canonical = canonical,
            )
        }

        when (work.classification) {
            com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT -> {
                val inferenceStartNs = relativeNowNs()
                val submissionIndex = work.runner.submit(
                    work.index,
                    work.timestampNs,
                    inferenceStartNs,
                    checkNotNull(work.canonical),
                )
                if (submissionIndex != null) {
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
                }
            }

            else -> work.runner.skip(work.index, work.timestampNs, work.classification.toSkipReason())
        }
        onProgress(work.runner.snapshot())
    }

    private fun com.calisthenics.diagnostic.live.LiveFrameClassification.toSkipReason(): SkipReason = when (this) {
        com.calisthenics.diagnostic.live.LiveFrameClassification.CADENCE_SELECTION -> SkipReason.CADENCE_SELECTION
        com.calisthenics.diagnostic.live.LiveFrameClassification.BACKPRESSURE -> SkipReason.BACKPRESSURE
        com.calisthenics.diagnostic.live.LiveFrameClassification.PREPROCESSING_FAILED -> SkipReason.PREPROCESSING_FAILED
        com.calisthenics.diagnostic.live.LiveFrameClassification.SESSION_STOPPING -> SkipReason.SESSION_STOPPING
        com.calisthenics.diagnostic.live.LiveFrameClassification.SUBMIT -> error("submitted frame has no skip reason")
    }

    private fun onInferenceTerminal(submissionIndex: Long) {
        val currentRunner = synchronized(monitor) {
            terminalSubmissionIndices += submissionIndex
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
        currentRunner?.let(::finishIfSealed)
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun onInferenceStall(submissionIndex: Long, onProgress: (SessionRunnerSnapshot) -> Unit) {
        val pendingRunner = synchronized(monitor) {
            if (stopping || completion != null) return
            runner
        }
        if (pendingRunner == null || !pendingRunner.hasPendingInference()) return
        val stopWork = synchronized(monitor) {
            if (stopping || completion != null || terminalSubmissionIndices.contains(submissionIndex)) return
            stalled = true
            requestStopLocked()
        }
        performStop(stopWork, onProgress)
    }

    private fun handleSourceFailure(failure: Throwable, onProgress: (SessionRunnerSnapshot) -> Unit) {
        val stopWork = synchronized(monitor) {
            if (completion != null) return
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
        if (state.lifecycle != com.calisthenics.diagnostic.session.SessionLifecycle.SEALED) return
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
                internalFailure = if (stalled) LiveInternalFailure.LiveInferenceStalled else null,
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
        stopReleasedSource(stopWork.source)
        val stoppedSnapshot = stopWork.runner?.stop(relativeNowNs())
        if (stoppedSnapshot != null) onProgress?.invoke(stoppedSnapshot)
        stopWork.runner?.let(::finishIfSealed)
    }

    private fun stopReleasedSource(source: LiveFrameSource?) {
        runCatching { source?.stop() }
    }

    private data class ResourceReleasePlan(
        val source: LiveFrameSource?,
        val runner: SessionRunner?,
        val inference: LiveInference?,
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
            dispatcher = sessionDispatcher,
            scheduler = dependencies.scheduler,
            abandonRunner = abandonRunner,
        )
    }

    private fun performRelease(plan: ResourceReleasePlan?, sourceAlreadyStopped: Boolean = false) {
        if (plan == null) return
        if (!sourceAlreadyStopped) stopReleasedSource(plan.source)
        if (plan.abandonRunner) {
            plan.runner?.let { runnerToAbandon -> runCatching { runnerToAbandon.abandon() } }
        }
        runCatching { plan.inference?.disposable?.dispose() }
        runCatching { plan.dispatcher?.shutdown() }
        runCatching { plan.scheduler.shutdown() }
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

    private var terminalSubmissionIndices = mutableSetOf<Long>()

    private fun inferenceTerminal(submissionIndex: Long): Boolean = synchronized(monitor) {
        terminalSubmissionIndices.contains(submissionIndex)
    }

    private fun relativeNowNs(): Long = (dependencies.clock.nowNs() - sessionStartNs).coerceAtLeast(0L)

    private fun buildManifest(
        id: Uuid,
        configuredInferenceFps: Int,
        evidence: PoseLandmarkerConstructionEvidence,
        startedAtUtc: String,
    ): SessionManifest {
        val metadata = dependencies.manifestMetadata
        val provenance = metadata.provenance
        val studyContext: Map<String, JsonElement> = mapOf(
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
        )
        return SessionManifest(
            schemaVersion = SemanticVersion("1.1.0"),
            sessionId = id,
            sessionKind = LIVE_SESSION_KIND,
            participantPseudonymId = metadata.participantPseudonymId,
            captureProtocolVersion = SemanticVersion("1.0.0"),
            harnessGitSha = metadata.harnessGitSha,
            device = metadata.device,
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
                timestampSource = "session_relative_monotonic_ns",
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
                    sessionFinalizerFactory = LiveSessionFinalizerFactory { template, atFinalization ->
                        SessionExporter(AndroidStoragePort(roots), template, atFinalization)
                    },
                    manifestMetadata = LiveManifestMetadata(
                        harnessGitSha = BuildConfig.PROBE_GIT_SHA,
                        provenance = provenance,
                        device = Device(
                            platform = "android",
                            osVersion = Build.VERSION.RELEASE,
                            deviceModel = Build.MODEL,
                            chipset = runtimeChipset(),
                            thermalApiAvailable = false,
                        ),
                    ),
                    registerRunner = onSessionRunnerCreated,
                    onCompletion = onCompletion,
                ),
            )
        }

        private val sessionSequence = AtomicLong(0)

        private fun randomSessionId(): String {
            val n = sessionSequence.incrementAndGet()
            return "00000000-0000-4000-8000-${(n xor System.nanoTime()).toULong().toString(16).takeLast(12).padStart(12, '0')}"
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
        override fun start(onFrame: (OwnedYuvFrame) -> Unit, onFailure: (Throwable) -> Unit) = delegate.start(onFrame, onFailure)
        override fun stop() = delegate.stop()
    }
}

private class TerminalTrackingInferencePort(
    private val delegate: InferencePort,
    private val onTerminal: (Long) -> Unit,
) : InferencePort {
    override fun submit(
        submissionIndex: Long,
        canonicalInput: ByteArray,
        backendTimestampMs: Long,
        callback: (InferenceResult) -> Unit,
    ) = delegate.submit(submissionIndex, canonicalInput, backendTimestampMs) { result ->
        callback(result)
        onTerminal(submissionIndex)
    }

    override fun cancel(submissionIndex: Long) = delegate.cancel(submissionIndex)
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
