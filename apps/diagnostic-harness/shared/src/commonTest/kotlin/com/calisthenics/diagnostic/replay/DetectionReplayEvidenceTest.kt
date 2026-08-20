package com.calisthenics.diagnostic.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The instrumented replay run emits one sanitized evidence record, and `make
 * verify-harness-detection-replay` refuses to pass without it. That makes the record's exact shape
 * a contract between the device test and the host checker, so the rendering lives in `commonMain`
 * and is pinned here rather than being assembled ad hoc inside an instrumented test no host
 * verification can reach.
 *
 * Two properties matter beyond the shape. The record must be **deterministic** — the same run on
 * the same device renders the same bytes, or a committed artifact could never be compared against
 * a later run. And it must be **sanitized** — digests, topology, environment, and provenance only.
 * Landmark coordinates are check 6 observations and never leave the device.
 */
class DetectionReplayEvidenceTest {

    private fun environment() = DetectionReplayEnvironment(
        deviceClass = "emulator",
        deviceIdentity = "Google/sdk/emu/sdk",
        deviceFingerprint = "google/sdk:17/CP31/1:user/dev-keys",
        apiLevel = 37,
        androidRelease = "17",
        abi = "arm64-v8a",
    )

    private fun provenance() = DetectionReplayProvenanceBinding(
        modelAssetName = "pose_landmarker_full.task",
        modelSha256 = "aa",
        tasksVisionVersion = "1.0.0",
        runningMode = "VIDEO",
        delegate = "CPU",
        numPoses = 1,
        modelInputWidth = 256,
        modelInputHeight = 256,
    )

    private fun fixture() = DetectionReplayFixtureBinding(
        fixtureId = "fid",
        frameWidth = 256,
        frameHeight = 256,
        frameIndices = listOf(0L, 1L),
        frameSha256 = listOf("f0", "f1"),
    )

    private fun runBinding() = DetectionReplayRunBinding(
        runsPerInvocation = 3,
        freshPoseLandmarkerPerRun = true,
        backendTimestampsMs = listOf(0L, 33L),
        canonicalInputSha256 = listOf("c0", "c1"),
    )

    private fun topology(presencePopulated: Boolean = true) = listOf(0L, 1L).map { index ->
        FrameTopology(
            frameIndex = index,
            posePresent = true,
            poseCount = 1,
            normalizedLandmarkCount = 33,
            worldLandmarkCount = 33,
            presencePattern = List(33) { presencePopulated },
        )
    }

    private fun evidence(
        topology: List<FrameTopology> = topology(),
        digests: List<String> = listOf("d0", "d1"),
    ) = DetectionReplayEvidence(
        emittedBy = "com.calisthenics.diagnostic.mediapipe.DetectionTierReplayTopologyTest",
        environment = environment(),
        provenance = provenance(),
        fixture = fixture(),
        run = runBinding(),
        topology = topology,
        landmarkObservationSha256 = digests,
    )

    @Test
    fun rendersOneCanonicalSingleLineRecord() {
        val expected = """{"artifact_schema_version":"1.0.0",""" +
            """"record_kind":"detection_tier_replay_observation",""" +
            """"sanitized":true,"contains_landmark_coordinates":false,""" +
            """"emitted_by":"com.calisthenics.diagnostic.mediapipe.DetectionTierReplayTopologyTest",""" +
            """"environment":{"device_class":"emulator","device_identity":"Google/sdk/emu/sdk",""" +
            """"device_fingerprint":"google/sdk:17/CP31/1:user/dev-keys","api_level":37,""" +
            """"android_release":"17","abi":"arm64-v8a"},""" +
            """"provenance":{"model_asset_name":"pose_landmarker_full.task","model_sha256":"aa",""" +
            """"tasks_vision_version":"1.0.0","running_mode":"VIDEO","delegate":"CPU",""" +
            """"num_poses":1,"model_input_width":256,"model_input_height":256},""" +
            """"fixture":{"fixture_id":"fid","frame_width":256,"frame_height":256,""" +
            """"frame_count":2,"frame_indices":[0,1],"frame_sha256":["f0","f1"]},""" +
            """"run":{"runs_per_invocation":3,"fresh_pose_landmarker_per_run":true,""" +
            """"backend_timestamps_ms":[0,33],"canonical_input_sha256":["c0","c1"]},""" +
            """"topology":{"pose_present_frames":[0,1],"pose_count_per_frame":[1,1],""" +
            """"normalized_landmark_count_per_frame":[33,33],""" +
            """"world_landmark_count_per_frame":[33,33],""" +
            """"presence_pattern_fully_populated":true},""" +
            """"landmark_observation_sha256":["d0","d1"],""" +
            """"satisfies_s25_device_gate":false}"""

        assertEquals(expected, renderDetectionReplayEvidence(evidence()))
    }

    @Test
    fun isASingleLineSoOneAmInstrumentStatusEntryCarriesTheWholeRecord() {
        assertFalse(renderDetectionReplayEvidence(evidence()).contains('\n'))
    }

    @Test
    fun rendersByteIdenticalOutputForIdenticalInput() {
        assertEquals(
            renderDetectionReplayEvidence(evidence()),
            renderDetectionReplayEvidence(evidence()),
        )
    }

    @Test
    fun neverClaimsTheS25DeviceGate() {
        // A physical-device observation is still not the five-run process-kill gate.
        val physical = DetectionReplayEvidence(
            emittedBy = "suite",
            environment = environment().copy(deviceClass = "physical_device"),
            provenance = provenance(),
            fixture = fixture(),
            run = runBinding(),
            topology = topology(),
            landmarkObservationSha256 = listOf("d0", "d1"),
        )
        assertTrue(
            renderDetectionReplayEvidence(physical).contains(""""satisfies_s25_device_gate":false"""),
        )
    }

    @Test
    fun reportsAPresencePatternThatIsNotFullyPopulated() {
        val rendered = renderDetectionReplayEvidence(evidence(topology = topology(presencePopulated = false)))
        assertTrue(rendered.contains(""""presence_pattern_fully_populated":false"""))
    }

    @Test
    fun refusesToRenderATopologyThatDisagreesWithTheFixtureFrameCount() {
        // A record whose topology covers fewer frames than the fixture declares would let a
        // partial run look complete to the host checker.
        assertFailsWith<IllegalArgumentException> {
            renderDetectionReplayEvidence(evidence(topology = topology().take(1)))
        }
    }

    @Test
    fun refusesToRenderADigestListThatDisagreesWithTheFixtureFrameCount() {
        assertFailsWith<IllegalArgumentException> {
            renderDetectionReplayEvidence(evidence(digests = listOf("d0")))
        }
    }

    @Test
    fun refusesToRenderATopologyWhoseFrameIndicesAreNotTheFixtureFrames() {
        assertFailsWith<IllegalArgumentException> {
            renderDetectionReplayEvidence(
                evidence(topology = topology().map { it.copy(frameIndex = it.frameIndex + 10L) }),
            )
        }
    }
}
