package com.calisthenics.diagnostic.composition

import android.content.Context
import android.os.Build
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.contract.AvailableObservations
import com.calisthenics.diagnostic.contract.Cadence
import com.calisthenics.diagnostic.contract.CadenceSample
import com.calisthenics.diagnostic.contract.Camera
import com.calisthenics.diagnostic.contract.CaptureQualityObserved
import com.calisthenics.diagnostic.contract.Clock
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Device
import com.calisthenics.diagnostic.contract.MediaReference
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
import com.calisthenics.diagnostic.mediapipe.PoseLandmarkerReplayAdapterFactory
import com.calisthenics.diagnostic.mediapipe.realDetectorConstructor
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.ModelProvenanceException
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.replay.ReplayClock
import com.calisthenics.diagnostic.replay.ReplayFixture
import com.calisthenics.diagnostic.replay.ReplayRunner
import com.calisthenics.diagnostic.replay.SubmittedFrame
import com.calisthenics.diagnostic.replay.decodeReplayFixtureMetadata
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.DeadlineScheduler
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SessionConfiguration
import com.calisthenics.diagnostic.session.SessionRunner
import com.calisthenics.diagnostic.session.SessionRunnerSnapshot
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The one production replay fixture this composition can drive (Task 5 brief). */
enum class RealReplayFixtureSelection(val assetSubdirectory: String) {
    DETECTION_TIER("detection"),
}

/** Outcome of one call to [RealReplayComposition.run]. */
sealed interface RealReplayResult {
    data class Completed(
        val sessionId: Uuid,
        val finalization: FinalizationResult,
        val snapshot: SessionRunnerSnapshot,
    ) : RealReplayResult

    data class CapabilityUnavailable(val reason: String) : RealReplayResult

    data class Cancelled(val reason: String) : RealReplayResult
}

/**
 * Wires the real MediaPipe VIDEO replay adapter (Task 3), the real deterministic replay driver
 * (Task 1), and the real [AndroidStoragePort]/[SessionExporter] export path (design section 10)
 * together against a packaged production fixture, so the diagnostic harness UI can run one
 * genuine, exportable replay session end to end.
 *
 * This is deliberately a distinct composition from [HarnessComposition]: that class exists for the
 * Slice 3 installable-composition export-parity check and drives [SyntheticFixture][com.calisthenics.diagnostic.conformance.SyntheticFixture]
 * through the scripted [com.calisthenics.diagnostic.session.DeterministicCore] fake. Task 5's
 * constraint is that the real UI/export path must never be backed by that fake, so this class is
 * additive rather than a modification of it.
 */
class RealReplayComposition(
    private val context: Context,
    private val sessionIdProvider: () -> Uuid = { Uuid(randomSessionId()) },
) {

    /**
     * Runs one complete replay session. [onSessionRunnerCreated] is invoked once the
     * [SessionRunner] exists so a caller (the UI layer) can atomically admit or reject ownership.
     * A rejected registration abandons the runner and returns [RealReplayResult.Cancelled] before
     * replay can submit a frame.
     * [onProgress] is invoked synchronously, on the calling thread, after every submitted frame.
     */
    fun run(
        fixture: RealReplayFixtureSelection,
        onSessionRunnerCreated: (SessionRunner) -> SessionRunnerRegistrationResult = {
            SessionRunnerRegistrationResult.ACCEPTED
        },
        onProgress: (SessionRunnerSnapshot) -> Unit = {},
        onSubmittedFrame: (SubmittedFrame) -> Unit = {},
    ): RealReplayResult {
        val provenance = try {
            parseModelProvenance(readAsset("replay/model_provenance.json").decodeToString())
        } catch (error: ModelProvenanceException) {
            return RealReplayResult.CapabilityUnavailable("model_provenance_unreadable: ${error.message}")
        }

        val fixtureAndMetadata = try {
            val fixtureMetadataBytes = readAsset("replay/${fixture.assetSubdirectory}/fixture_metadata.json")
            val replayFixture = decodeReplayFixtureMetadata(
                fixtureMetadataBytes.decodeToString(),
            ) { path -> readAsset("replay/${fixture.assetSubdirectory}/$path") }
            replayFixture to fixtureMetadataBytes
        } catch (error: Exception) {
            return RealReplayResult.CapabilityUnavailable("replay_fixture_unreadable: ${error.message}")
        }
        val replayFixture = fixtureAndMetadata.first
        val fixtureMetadataBytes = fixtureAndMetadata.second

        val roots = when (val result = AndroidStorageRoots.from(context)) {
            is StorageRootsResult.Ready -> result.roots
            is StorageRootsResult.Unavailable -> return RealReplayResult.CapabilityUnavailable(result.reason)
        }

        val clock = SessionRelativeElapsedRealtimeClock()
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = provenance,
            modelInputWidth = replayFixture.frameWidth,
            modelInputHeight = replayFixture.frameHeight,
            readModelBytes = { readAsset("replay/${provenance.assetName}") },
            nowNs = clock::nowNs,
            constructDetector = realDetectorConstructor(context),
        )

        val inference = try {
            factory.create()
        } catch (error: ModelProvenanceException) {
            return RealReplayResult.CapabilityUnavailable("model_asset_verification_failed: ${error.message}")
        }

        val sessionId = sessionIdProvider()
        val startedAt = Instant.now()
        val drainTimeoutNs = 5_000_000_000L
        val scheduler = ExecutorDeadlineScheduler(clock)
        val dispatcher = SingleThreadSessionDispatcher()

        val sessionRunner = SessionRunner(
            configuration = SessionConfiguration(
                sessionId = sessionId,
                schemaVersion = SemanticVersion("1.1.0"),
                imageWidth = replayFixture.frameWidth,
                imageHeight = replayFixture.frameHeight,
            ),
            inference = inference,
            deadlineScheduler = scheduler,
            drainTimeoutNs = drainTimeoutNs,
            finalizer = SessionExporter(
                AndroidStoragePort(roots),
                manifestTemplate(
                    sessionId,
                    provenance,
                    replayFixture,
                    drainTimeoutNs,
                    startedAt,
                    fixtureMetadataBytes,
                ),
                manifestAtFinalization = { manifest, _ ->
                    manifest.copy(clock = manifest.clock.copy(endedAtUtc = Instant.now().toString()))
                },
            ),
            dispatcher = dispatcher,
        )
        var terminal = false
        try {
            if (onSessionRunnerCreated(sessionRunner) != SessionRunnerRegistrationResult.ACCEPTED) {
                return RealReplayResult.Cancelled("session_registration_rejected")
            }

            ReplayRunner(clock, provenance, replayFixture.frameWidth, replayFixture.frameHeight) { submitted, canonical ->
                val inferenceStartNs = clock.nowNs()
                sessionRunner.submit(submitted.frameIndex, submitted.rebasedCaptureNs, inferenceStartNs, canonical)
                onSubmittedFrame(submitted)
                onProgress(sessionRunner.snapshot())
            }.run(replayFixture)

            sessionRunner.stop(nowNs = clock.nowNs())
            val finalization = sessionRunner.finalize()
            terminal = true
            // Teardown while this replay was still running (the UI leaving composition mid-run)
            // abandons the runner, so stop/finalize has nothing left to publish. That is a
            // cancellation, not a completed run with a failed export, and it must never surface as
            // an exception escaping this call: the caller is a UI coroutine whose only recovery is
            // a typed result.
            if (finalization is FinalizationResult.Cancelled) {
                return RealReplayResult.Cancelled(finalization.reason)
            }
            return RealReplayResult.Completed(sessionId, finalization, sessionRunner.snapshot())
        } finally {
            if (!terminal) sessionRunner.abandon()
            scheduler.shutdown()
            dispatcher.shutdown()
        }
    }

    private fun readAsset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private fun manifestTemplate(
        sessionId: Uuid,
        provenance: ModelProvenance,
        fixture: ReplayFixture,
        drainTimeoutNs: Long,
        startedAt: Instant,
        fixtureMetadataBytes: ByteArray,
    ): SessionManifest {
        fun optionInt(key: String): Int = (provenance.taskOptions.getValue(key) as Number).toInt()
        fun optionDouble(key: String): Double = (provenance.taskOptions.getValue(key) as Number).toDouble()

        return SessionManifest(
            schemaVersion = SemanticVersion("1.1.0"),
            sessionId = sessionId,
            sessionKind = "fixture_replay",
            participantPseudonymId = "device-replay-${Build.MODEL}",
            captureProtocolVersion = SemanticVersion("1.0.0"),
            harnessGitSha = BuildConfig.PROBE_GIT_SHA,
            device = Device(
                platform = "android",
                osVersion = Build.VERSION.RELEASE,
                deviceModel = Build.MODEL,
                chipset = Build.HARDWARE,
                thermalApiAvailable = false,
            ),
            posePipeline = PosePipeline(
                backendName = "mediapipe_pose_landmarker",
                backendVersion = provenance.tasksVisionVersion,
                modelAssetName = provenance.assetName,
                modelAssetSha256 = Sha256(provenance.sha256),
                delegate = provenance.delegate.lowercase(),
                runningMode = provenance.runningMode.lowercase(),
                numPoses = optionInt("num_poses"),
                minPoseDetectionConfidence = optionDouble("min_pose_detection_confidence"),
                minPosePresenceConfidence = optionDouble("min_pose_presence_confidence"),
                minTrackingConfidence = optionDouble("min_tracking_confidence"),
                landmarkTopology = "mediapipe_pose_33_v1",
            ),
            camera = Camera(
                facing = "fixture",
                captureWidth = fixture.frameWidth,
                captureHeight = fixture.frameHeight,
                orientationDegrees = 0,
                mirrored = false,
                requestedCameraFps = 30.0,
                sourcePixelFormat = "rgb24",
                sourceColorMatrix = "sRGB",
                sourceColorRange = "full",
                sourceDataspaceRaw = null,
                timestampSource = "replay_session_relative_monotonic_ns",
                deliverySemantics = "ordered_no_drop",
            ),
            modelInput = ModelInput(
                modelInputWidth = fixture.frameWidth,
                modelInputHeight = fixture.frameHeight,
                livePreprocessingContractVersion = SemanticVersion("1.0.0"),
                fixturePreprocessingContractVersion = SemanticVersion("1.0.0"),
            ),
            inferenceDrainTimeoutMs = drainTimeoutNs / 1_000_000L,
            cadence = Cadence(
                configuredInferenceFps = 30.0,
                achievedInferenceFpsTimeseries = listOf(CadenceSample(0L, 30.0)),
                trackerUpdateCadenceHz = null,
            ),
            clock = Clock(
                monotonicTimeBase = "replay_session_relative_monotonic_ns",
                t0MonotonicNs = ReplayRunner.REPLAY_TIME_BASE_NS,
                startedAtUtc = startedAt.toString(),
                endedAtUtc = startedAt.toString(),
            ),
            availableObservations = AvailableObservations(
                normalizedImageLandmarksAvailable = true,
                worldLandmarksAvailable = true,
            ),
            mediaReferences = listOf(
                MediaReference(
                    role = "conformance_fixture",
                    pathRef = "packaged-asset://replay/detection/fixture_metadata.json",
                    sha256 = fixtureMetadataSha256(fixtureMetadataBytes),
                    syncOffsetNs = null,
                    syncDriftNsPerS = null,
                ),
            ),
            counts = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0),
            machineInferenceFailures = emptyList(),
            captureQualityObserved = CaptureQualityObserved(
                fullBodyVisibleAtHang = true,
                requiredJointsVisible = true,
                subjectScaleAcceptable = true,
                lightingClass = "synthetic_uniform",
                visibleOcclusion = false,
                setupFailureLog = null,
            ),
            studyContext = emptyMap(),
        )
    }
}

internal fun fixtureMetadataSha256(metadataBytes: ByteArray): Sha256 =
    Sha256(Sha256Digest.hex(metadataBytes))

/**
 * A real, session-relative monotonic clock: every reading is `elapsedRealtimeNanos - sessionStart`,
 * matching design section 7's requirement that inference clock readings share the same rebased
 * base as capture, never a device-absolute value.
 */
private class SessionRelativeElapsedRealtimeClock : ReplayClock {
    private val sessionStart = android.os.SystemClock.elapsedRealtimeNanos()

    override fun nowNs(): Long = android.os.SystemClock.elapsedRealtimeNanos() - sessionStart

    override fun waitUntil(deadlineNs: Long) {
        while (true) {
            val remaining = deadlineNs - nowNs()
            if (remaining <= 0L) return
            Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
    }
}

/**
 * A real [DeadlineScheduler] for the replay drain fence. Synchronous replay resolves every
 * submission before [SessionRunner.stop] is even called, so this deadline is not expected to fire
 * in practice; it exists so the drain fence is real rather than stubbed, matching production
 * semantics. Owns one single-thread executor per run, shut down by the caller once the run ends.
 */
private class ExecutorDeadlineScheduler(private val clock: ReplayClock) : DeadlineScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    override fun schedule(deadlineNs: Long, action: () -> Unit): Disposable {
        val delayNs = (deadlineNs - clock.nowNs()).coerceAtLeast(0L)
        val future = executor.schedule({ action() }, delayNs, TimeUnit.NANOSECONDS)
        return object : Disposable {
            override fun dispose() {
                future.cancel(false)
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}

private val sessionIdSequence = AtomicLong(0)

/**
 * A fresh session UUID per run. Not [java.util.UUID.randomUUID] directly: that reads
 * `/dev/urandom`, which is unnecessary entropy cost for a value whose only requirement is
 * uniqueness across runs on one device, and this stays trivially host-testable.
 */
private fun randomSessionId(): String {
    val unique = sessionIdSequence.incrementAndGet()
    val nowNs = System.nanoTime()
    val hex = (unique xor nowNs).toULong().toString(16).padStart(16, '0').takeLast(12)
    return "00000000-0000-4000-8000-$hex"
}
