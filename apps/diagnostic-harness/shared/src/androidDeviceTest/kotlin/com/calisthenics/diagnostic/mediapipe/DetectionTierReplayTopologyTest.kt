package com.calisthenics.diagnostic.mediapipe

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.ModelProvenance
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.replay.DetectionReplayEnvironment
import com.calisthenics.diagnostic.replay.DetectionReplayEvidence
import com.calisthenics.diagnostic.replay.DetectionReplayFixtureBinding
import com.calisthenics.diagnostic.replay.DetectionReplayProvenanceBinding
import com.calisthenics.diagnostic.replay.DetectionReplayRunBinding
import com.calisthenics.diagnostic.replay.DetectionTopologyCheck
import com.calisthenics.diagnostic.replay.FrameTopology
import com.calisthenics.diagnostic.replay.ReplayClock
import com.calisthenics.diagnostic.replay.ReplayFixture
import com.calisthenics.diagnostic.replay.ReplayFixtureFrame
import com.calisthenics.diagnostic.replay.ReplayRunner
import com.calisthenics.diagnostic.replay.frameTopologyOf
import com.calisthenics.diagnostic.replay.renderDetectionReplayEvidence
import com.calisthenics.diagnostic.session.InferenceResult
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `am instrument` status key the sanitized observation record is published under, and the
 * logcat tag it is mirrored to. `make verify-harness-detection-replay` refuses to pass unless
 * exactly one record arrives under this key, so it is a contract with
 * `observation_contracts.detection_evidence.EVIDENCE_STATUS_KEY`, not a log label.
 */
private const val EVIDENCE_TAG = "ReplayDetectionEvidence"

/** An unused `am instrument` status code, so a record can never be read as a test outcome. */
private const val EVIDENCE_STATUS_CODE = 2
private const val MODEL_INPUT_EDGE = 256
private const val RUN_COUNT = 3

/**
 * Reads one staged device-test resource. The model binary and the detection fixture planes are
 * staged into generated device-test resources by `:shared:stageDeviceTestReplayResources`, which
 * SHA-verifies the model before staging; the fixture planes are re-verified against their declared
 * digests below, so the device provably reads the same bytes `make verify` hashes.
 */
private fun resourceBytes(path: String): ByteArray {
    val loader = requireNotNull(DetectionTierReplayTopologyTest::class.java.classLoader) {
        "no classloader is available to read staged device-test resources"
    }
    val stream = requireNotNull(loader.getResourceAsStream(path)) {
        "staged device-test resource $path is missing; run make fetch-model-asset and reassemble"
    }
    return stream.use { it.readBytes() }
}

/**
 * A real monotonic clock expressed against the session-relative base the design pins to zero:
 * every reading is `realNow - realSessionStart`, so `inference_start_ns` is comparable with the
 * rebased capture instants rather than being a device-absolute value that would make
 * `FRAME.INFERENCE_TIME_ORDER` vacuous.
 */
private class SessionRelativeClock : ReplayClock {
    private val sessionStart = System.nanoTime()

    override fun nowNs(): Long = System.nanoTime() - sessionStart

    override fun waitUntil(deadlineNs: Long) {
        while (true) {
            val remaining = deadlineNs - nowNs()
            if (remaining <= 0L) return
            Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
    }
}

/** One frame's outcome as observed through the production adapter. */
private data class ObservedFrame(
    val topology: FrameTopology,
    val normalizedLandmarks: List<Landmark>,
)

/** What one complete replay run produced, plus the evidence recorded at its construction site. */
private data class ObservedRun(
    val frames: List<ObservedFrame>,
    val constructionEvidence: PoseLandmarkerConstructionEvidence,
    val nativeConstructions: Int,
    val submittedIndices: List<Long>,
    val backendTimestampsMs: List<Long>,
    val canonicalInputDigests: List<String>,
)

/**
 * Records every detection the native backend returned without standing in for it. The wrapped
 * detector is the production [RealPoseLandmarkerVideoDetector] built by [realDetectorConstructor],
 * so `PoseLandmarker.createFromOptions` and `detectForVideo` genuinely execute; this only observes
 * pose cardinality, which the `InferenceResult` contract does not carry.
 */
private class RecordingDetector(private val delegate: PoseLandmarkerVideoDetector) : PoseLandmarkerVideoDetector {
    val detections = mutableListOf<PoseLandmarkerDetection>()

    override fun detectForVideo(image: MPImage, backendTimestampMs: Long): PoseLandmarkerDetection =
        delegate.detectForVideo(image, backendTimestampMs).also { detections += it }

    override fun close() = delegate.close()
}

/**
 * Task 4: the detection-firing question, answered on a real device against the pinned model.
 *
 * This is the one place where `PoseLandmarker.createFromOptions` and `detectForVideo` actually run.
 * Every host test above this point substitutes the [PoseLandmarkerVideoDetector] seam, so nothing
 * else in the repository can show that the pinned model loads, that the synthetic render fires the
 * detector, or that the output topology repeats. Those three questions are exactly what design
 * section 7's check 5 and the fixture's admission criterion depend on.
 *
 * Emulator and physical-device results are **different observations**. This test records which one
 * it ran on, derived from `Build`, and never claims one as the other; the five-run Samsung Galaxy
 * S25 Ultra gate is separate and is not satisfied by anything here.
 */
class DetectionTierReplayTopologyTest {

    private val provenance: ModelProvenance =
        parseModelProvenance(resourceBytes("replay/model_provenance.json").decodeToString())

    private val fixtureMetadata: JsonObject = Json.parseToJsonElement(
        resourceBytes("replay/detection/fixture_metadata.json").decodeToString(),
    ).jsonObject

    private val fixture: ReplayFixture = loadDetectionFixture(fixtureMetadata)

    @Test
    fun theStagedFixturePlanesAreTheDeclaredBytes() {
        // A run against silently different pixels would be evidence about nothing in particular.
        assertEquals(6, fixture.frames.size)
        fixture.frames.forEach { frame ->
            assertEquals(
                frame.declaredSha256,
                Sha256Digest.hex(frame.plane),
                "staged plane ${frame.frameIndex} does not match its declared digest",
            )
        }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), fixture.declaredIndices)
        assertEquals(MODEL_INPUT_EDGE, fixture.frameWidth)
        assertEquals(MODEL_INPUT_EDGE, fixture.frameHeight)
    }

    @Test
    fun thePinnedModelFiresOnEveryFrameWithIdenticalTopologyAcrossRuns() {
        val runs = (1..RUN_COUNT).map { replayOnce() }

        // Fresh instance per run: each run constructed exactly one native PoseLandmarker of its
        // own, so cross-run topology comparison is not contaminated by carried tracker state.
        runs.forEach { assertEquals(1, it.nativeConstructions) }

        // Check 4 evidence, read at the construction site rather than off the manifest.
        runs.forEach { run ->
            assertEquals("CPU", run.constructionEvidence.delegate)
            assertEquals("VIDEO", run.constructionEvidence.runningMode)
            assertEquals(provenance.sha256, run.constructionEvidence.modelSha256)
            assertEquals("1.0.0", run.constructionEvidence.tasksVisionVersion)
            assertEquals(1, run.constructionEvidence.numPoses)
            assertEquals(MODEL_INPUT_EDGE, run.constructionEvidence.modelInputWidth)
            assertEquals(MODEL_INPUT_EDGE, run.constructionEvidence.modelInputHeight)
        }

        // Admission criterion: the render must actually fire the detector, on every frame.
        runs.forEach { DetectionTopologyCheck.requireDetectionFired(it.frames.map { frame -> frame.topology }) }

        // Non-vacuity: a full-cardinality list of zeros, or one frozen result repeated on every
        // frame, would satisfy every cardinality comparison while observing nothing.
        val digestsPerRun = runs.map { run ->
            DetectionTopologyCheck.requireNonVacuousLandmarkOutput(run.frames.map { it.normalizedLandmarks })
        }

        // Check 5 itself.
        val agreed = DetectionTopologyCheck.requireIdenticalAcrossRuns(runs.map { it.frames.map { frame -> frame.topology } })

        // Checks 1-3 on the real backend path, not only on the host substitute.
        runs.forEach { assertEquals(fixture.declaredIndices, it.submittedIndices) }
        runs.forEach { assertEquals(runs.first().backendTimestampsMs, it.backendTimestampsMs) }
        runs.forEach { assertEquals(runs.first().canonicalInputDigests, it.canonicalInputDigests) }
        assertEquals(listOf(0L, 33L, 34L, 66L, 100L, 133L), runs.first().backendTimestampsMs)

        // Landmark digests are observations. They are reported, and compared for equality against
        // each other, never against an invented pass value.
        digestsPerRun.forEach { assertEquals(digestsPerRun.first(), it) }

        emitEvidence(agreed, digestsPerRun.first(), runs.first())
    }

    @Test
    fun theRecordedDeviceClassIsDerivedFromBuildRatherThanAsserted() {
        // Emulator evidence must never be presented as physical-device evidence, so the label is
        // derived from Build and pinned here against both shapes.
        assertEquals(
            "emulator",
            androidDeviceClass("google/sdk_gphone16k_arm64/emu64a16k:17/CP31.260623.009/1:user/dev-keys", "ranchu"),
        )
        assertEquals(
            "physical_device",
            androidDeviceClass("samsung/e3qxxx/e3q:16/BP2A.250705.008/S938BXXU4BYH2:user/release-keys", "qcom"),
        )
        assertEquals(androidDeviceClass(Build.FINGERPRINT, Build.HARDWARE), observedDeviceClass())
    }

    private fun replayOnce(): ObservedRun {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clock = SessionRelativeClock()
        var constructions = 0
        var recorder: RecordingDetector? = null
        val factory = PoseLandmarkerReplayAdapterFactory(
            provenance = provenance,
            modelInputWidth = MODEL_INPUT_EDGE,
            modelInputHeight = MODEL_INPUT_EDGE,
            readModelBytes = { resourceBytes("replay/${provenance.assetName}") },
            // The same session-relative base the pacer uses: design section 7 requires *every*
            // inference clock reading to be `realNow - realSessionStart`, not only capture.
            nowNs = clock::nowNs,
            constructDetector = { options ->
                constructions += 1
                RecordingDetector(realDetectorConstructor(context)(options)).also { recorder = it }
            },
        )

        val port = factory.create()
        val results = mutableListOf<InferenceResult>()
        val report = try {
            ReplayRunner(clock, provenance, MODEL_INPUT_EDGE, MODEL_INPUT_EDGE) { submitted, canonical ->
                port.submit(submitted.frameIndex, canonical, submitted.backendTimestampMs) { results += it }
            }.run(fixture)
        } finally {
            port.dispose()
        }

        val detections = requireNotNull(recorder).detections
        assertEquals(fixture.frames.size, results.size, "one record per submitted frame")

        val frames = fixture.declaredIndices.mapIndexed { position, frameIndex ->
            val result = results[position]
            assertTrue(
                result is InferenceResult.Success,
                "frame $frameIndex failed on the real backend: $result",
            )
            val success = result as InferenceResult.Success
            ObservedFrame(
                topology = frameTopologyOf(
                    frameIndex = frameIndex,
                    posePresent = success.posePresent,
                    poseCount = detections[position].poseCount,
                    normalizedLandmarks = success.normalizedImageLandmarks,
                    worldLandmarks = success.worldLandmarks,
                ),
                normalizedLandmarks = success.normalizedImageLandmarks.orEmpty(),
            )
        }

        assertEquals(fixture.frames.size, detections.size, "one native detectForVideo call per frame")

        return ObservedRun(
            frames = frames,
            constructionEvidence = assertNotNull(
                factory.lastConstructionEvidence,
                "a successful run must publish its construction-site evidence",
            ),
            nativeConstructions = constructions,
            submittedIndices = report.submittedIndices,
            backendTimestampsMs = report.submitted.map { it.backendTimestampMs },
            canonicalInputDigests = report.submitted.map { it.canonicalInputSha256 },
        )
    }

    private fun loadDetectionFixture(metadata: JsonObject): ReplayFixture {
        val frames = metadata.getValue("frames").jsonArray.map { element ->
            val descriptor = element.jsonObject
            val path = descriptor.getValue("path").jsonPrimitive.content
            ReplayFixtureFrame(
                frameIndex = descriptor.getValue("frame_index").jsonPrimitive.long,
                captureTimestampNs = descriptor.getValue("timestamp_ns").jsonPrimitive.long,
                plane = resourceBytes("replay/detection/$path"),
                declaredSha256 = descriptor.getValue("sha256").jsonPrimitive.content,
            )
        }
        return ReplayFixture(
            frames = frames,
            frameWidth = metadata.getValue("frame_width").jsonPrimitive.int,
            frameHeight = metadata.getValue("frame_height").jsonPrimitive.int,
        )
    }

    private fun observedDeviceClass(): String = androidDeviceClass(Build.FINGERPRINT, Build.HARDWARE)

    /**
     * Emits the one sanitized observation record this run produced.
     *
     * The record is what `make verify-harness-detection-replay` requires: without it, a wrapper
     * reading only a JUnit success line would accept a run that executed nothing. It is
     * transcribed from what the device actually reported — construction-site provenance, the
     * fixture's declared plane digests, the submitted canonical inputs, and the agreed topology —
     * never from what the run was expected to report.
     *
     * It carries digests, cardinality, presence structure, environment and provenance, and no
     * landmark coordinate values: those are check 6 observations and stay on the device. Rendering
     * lives in `commonMain` so the exact record shape is pinned by host tests.
     *
     * `sendStatus` rather than logcat alone, because the record has to reach the host inside this
     * run's own `am instrument` transcript: a logcat scrape could pick up a record left by an
     * earlier run.
     */
    private fun emitEvidence(
        agreed: List<FrameTopology>,
        landmarkDigests: List<String>,
        run: ObservedRun,
    ) {
        val evidence = run.constructionEvidence
        val record = renderDetectionReplayEvidence(
            DetectionReplayEvidence(
                emittedBy = "com.calisthenics.diagnostic.mediapipe.DetectionTierReplayTopologyTest",
                environment = DetectionReplayEnvironment(
                    deviceClass = observedDeviceClass(),
                    deviceIdentity =
                        "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                    deviceFingerprint = Build.FINGERPRINT,
                    apiLevel = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE,
                    abi = Build.SUPPORTED_ABIS.joinToString(","),
                ),
                provenance = DetectionReplayProvenanceBinding(
                    modelAssetName = provenance.assetName,
                    modelSha256 = evidence.modelSha256,
                    tasksVisionVersion = evidence.tasksVisionVersion,
                    runningMode = evidence.runningMode,
                    delegate = evidence.delegate,
                    numPoses = evidence.numPoses,
                    modelInputWidth = evidence.modelInputWidth,
                    modelInputHeight = evidence.modelInputHeight,
                ),
                fixture = DetectionReplayFixtureBinding(
                    fixtureId = fixtureMetadata.getValue("fixture_id").jsonPrimitive.content,
                    frameWidth = fixture.frameWidth,
                    frameHeight = fixture.frameHeight,
                    frameIndices = fixture.declaredIndices,
                    frameSha256 = fixture.frames.map { it.declaredSha256 },
                ),
                run = DetectionReplayRunBinding(
                    runsPerInvocation = RUN_COUNT,
                    freshPoseLandmarkerPerRun = true,
                    backendTimestampsMs = run.backendTimestampsMs,
                    canonicalInputSha256 = run.canonicalInputDigests,
                ),
                topology = agreed,
                landmarkObservationSha256 = landmarkDigests,
            ),
        )

        InstrumentationRegistry.getInstrumentation().sendStatus(
            EVIDENCE_STATUS_CODE,
            Bundle().apply { putString(EVIDENCE_TAG, record) },
        )
        Log.i(EVIDENCE_TAG, record)
    }
}

/**
 * Derives the device class from `Build` markers. AOSP emulator images report a `generic`/`ranchu`
 * hardware and an `sdk_`/`emu` fingerprint; no physical Samsung device does.
 */
private fun androidDeviceClass(fingerprint: String, hardware: String): String {
    val markers = listOf("generic", "ranchu", "goldfish", "sdk_gphone", "emu64", "/sdk", "vbox")
    val haystack = "$fingerprint $hardware".lowercase()
    return if (markers.any { it in haystack }) "emulator" else "physical_device"
}
