package com.calisthenics.diagnostic.replay

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The five-run S25 device gate (design section 7 / Task 5 brief) is a stronger claim than the
 * Task 4 detection-tier observation: it asserts a *specific* physical device satisfied *five*
 * consistent, exported, host-validated replay runs of the same fixture. [renderFiveRunReplayEvidence]
 * computes `satisfies_s25_device_gate` itself from the supplied fields rather than accepting it as a
 * caller-declared flag, so an emulator run — or an incomplete/inconsistent set of runs — can never be
 * presented as satisfying the gate no matter what a caller claims.
 */
class FiveRunReplayEvidenceTest {

    private fun environment(
        deviceClass: String = "physical_device",
        marketingName: String = "Galaxy S25 Ultra",
        model: String = "SM-S938B",
        manufacturer: String = "samsung",
    ) =
        FiveRunReplayEnvironment(
            deviceClass = deviceClass,
            deviceIdentity = "samsung/e3q/e3q/e3q",
            deviceFingerprint = "samsung/e3qxxx/e3q:16/BP2A.250705.008/S938BXXU4BYH2:user/release-keys",
            apiLevel = 36,
            androidRelease = "16",
            abi = "arm64-v8a",
            manufacturer = manufacturer,
            model = model,
            marketingName = marketingName,
        )

    private fun provenance() = FiveRunReplayProvenanceBinding(
        modelAssetName = "pose_landmarker_full.task",
        modelSha256 = "aa",
        tasksVisionVersion = "1.0.0",
        runningMode = "VIDEO",
        delegate = "CPU",
        modelInputWidth = 256,
        modelInputHeight = 256,
    )

    private fun fixture() = FiveRunReplayFixtureBinding(
        fixtureId = "fid",
        frameWidth = 256,
        frameHeight = 256,
        frameIndices = listOf(0L, 1L),
        frameSha256 = listOf("f0", "f1"),
    )

    /** Two frames, a pose detected on both, 33 landmarks behind each: what a real run looks like. */
    private fun detected() = FiveRunReplayPoseObservation(
        posePresentPerFrame = listOf(true, true),
        normalizedLandmarkCountPerFrame = listOf(33, 33),
        worldLandmarkCountPerFrame = listOf(33, 33),
        poseDetectedRecords = 2L,
    )

    /**
     * The same two frames through a pipeline that detected nothing. Every input-side field — frame
     * indices, backend timestamps, canonical input digests — is byte-identical to [detected], which
     * is exactly why the gate cannot be left to those fields alone.
     */
    private fun undetected() = FiveRunReplayPoseObservation(
        posePresentPerFrame = listOf(false, false),
        normalizedLandmarkCountPerFrame = listOf(0, 0),
        worldLandmarkCountPerFrame = listOf(0, 0),
        poseDetectedRecords = 0L,
    )

    private fun run(
        index: Int,
        published: Boolean = true,
        validated: Boolean = true,
        pose: FiveRunReplayPoseObservation = detected(),
    ) = FiveRunReplayRunRecord(
        runIndex = index,
        sessionId = "5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8${index}",
        published = published,
        submittedIndices = listOf(0L, 1L),
        backendTimestampsMs = listOf(0L, 33L),
        canonicalInputSha256 = listOf("c0", "c1"),
        pose = pose,
        manifestSha256 = "m$index",
        framesSha256 = "n$index",
        validatedByReferenceCli = validated,
    )

    private fun fiveConsistentRuns(): List<FiveRunReplayRunRecord> = (1..5).map { run(it) }

    private fun evidence(
        environment: FiveRunReplayEnvironment = environment(),
        runs: List<FiveRunReplayRunRecord> = fiveConsistentRuns(),
    ) = FiveRunReplayEvidence(
        emittedBy = "com.calisthenics.diagnostic.replay.FiveRunReplayDeviceGateTest",
        environment = environment,
        provenance = provenance(),
        fixture = fixture(),
        runs = runs,
    )

    @Test
    fun fiveConsistentPublishedValidatedRunsOnAPhysicalS25SatisfyTheGate() {
        val rendered = renderFiveRunReplayEvidence(evidence())
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":true"""))
        assertTrue(rendered.contains(""""runs_completed":5"""))
    }

    @Test
    fun anEmulatorNeverSatisfiesTheGateEvenWithFiveConsistentRuns() {
        val rendered = renderFiveRunReplayEvidence(evidence(environment = environment(deviceClass = "emulator")))
        assertFalse(rendered.contains(""""satisfies_s25_device_gate":true"""))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
    }

    @Test
    fun aPhysicalDeviceThatIsNotAnS25UltraNeverSatisfiesTheGate() {
        // Both identifiers must say "not an S25 Ultra": authorization accepts either the model
        // family or the marketing name, so a contradictory environment is not this test's subject.
        val rendered = renderFiveRunReplayEvidence(
            evidence(environment = environment(marketingName = "Galaxy A15", model = "SM-A156B")),
        )
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
    }

    @Test
    fun fewerThanFiveRunsNeverSatisfiesTheGate() {
        val rendered = renderFiveRunReplayEvidence(evidence(runs = fiveConsistentRuns().take(4)))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
        assertTrue(rendered.contains(""""runs_completed":4"""))
    }

    @Test
    fun anUnpublishedRunNeverSatisfiesTheGate() {
        val runs = fiveConsistentRuns().toMutableList()
        runs[2] = run(3, published = false)
        val rendered = renderFiveRunReplayEvidence(evidence(runs = runs))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
    }

    @Test
    fun aRunNotValidatedByTheReferenceCliNeverSatisfiesTheGate() {
        val runs = fiveConsistentRuns().toMutableList()
        runs[4] = run(5, validated = false)
        val rendered = renderFiveRunReplayEvidence(evidence(runs = runs))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
    }

    @Test
    fun runsThatSubmittedDifferentIndicesNeverSatisfyTheGate() {
        val runs = fiveConsistentRuns().toMutableList()
        runs[1] = runs[1].copy(submittedIndices = listOf(0L))
        val rendered = renderFiveRunReplayEvidence(evidence(runs = runs))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
    }

    @Test
    fun runsThatSubmittedDifferentBackendTimestampsNeverSatisfyTheGate() {
        val runs = fiveConsistentRuns().toMutableList()
        runs[1] = runs[1].copy(backendTimestampsMs = listOf(0L, 34L))
        val rendered = renderFiveRunReplayEvidence(evidence(runs = runs))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
    }

    @Test
    fun runsThatSubmittedDifferentCanonicalDigestsNeverSatisfyTheGate() {
        val runs = fiveConsistentRuns().toMutableList()
        runs[1] = runs[1].copy(canonicalInputSha256 = listOf("c0", "different"))
        val rendered = renderFiveRunReplayEvidence(evidence(runs = runs))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
    }

    @Test
    fun fiveIdenticalRunsThatDetectedNoPoseNeverSatisfyTheGate() {
        // The finding: an empty pose pipeline reproduces every input-side determinism property
        // perfectly, so without a detection conjunct the gate certifies five runs of nothing.
        val rendered = renderFiveRunReplayEvidence(evidence(runs = (1..5).map { run(it, pose = undetected()) }))
        assertTrue(rendered.contains(""""satisfies_s25_device_gate":false"""))
        assertTrue(rendered.contains(""""pose_detected_records":0"""))
    }

    @Test
    fun aSingleRunWithoutADetectedPoseNeverSatisfiesTheGate() {
        val runs = fiveConsistentRuns().toMutableList()
        runs[3] = run(4, pose = undetected())
        assertTrue(
            renderFiveRunReplayEvidence(evidence(runs = runs)).contains(""""satisfies_s25_device_gate":false"""),
        )
    }

    @Test
    fun aDetectedRecordCountNoRecordSupportsNeverSatisfiesTheGate() {
        // A caller cannot buy the gate with a number: the count must match the per-frame pattern.
        val runs = (1..5).map { run(it, pose = undetected().copy(poseDetectedRecords = 2L)) }
        assertTrue(
            renderFiveRunReplayEvidence(evidence(runs = runs)).contains(""""satisfies_s25_device_gate":false"""),
        )
    }

    @Test
    fun aPosePresentFrameWithNoLandmarksBehindItNeverSatisfiesTheGate() {
        val runs = (1..5).map { run(it, pose = detected().copy(normalizedLandmarkCountPerFrame = listOf(33, 0))) }
        assertTrue(
            renderFiveRunReplayEvidence(evidence(runs = runs)).contains(""""satisfies_s25_device_gate":false"""),
        )
    }

    @Test
    fun aRunEmittingFewerRecordsThanItSubmittedFramesNeverSatisfiesTheGate() {
        val runs = (1..5).map {
            run(
                it,
                pose = FiveRunReplayPoseObservation(
                    posePresentPerFrame = listOf(true),
                    normalizedLandmarkCountPerFrame = listOf(33),
                    worldLandmarkCountPerFrame = listOf(33),
                    poseDetectedRecords = 1L,
                ),
            )
        }
        assertTrue(
            renderFiveRunReplayEvidence(evidence(runs = runs)).contains(""""satisfies_s25_device_gate":false"""),
        )
    }

    @Test
    fun runsWhoseDetectionPatternDivergesNeverSatisfyTheGate() {
        // Detection topology is inside the cross-run equality set: agreeing on the inputs while
        // disagreeing on what came back is a backend-nondeterminism finding, not a pass.
        val runs = fiveConsistentRuns().toMutableList()
        runs[2] = run(
            3,
            pose = detected().copy(posePresentPerFrame = listOf(true, false), poseDetectedRecords = 1L),
        )
        assertTrue(
            renderFiveRunReplayEvidence(evidence(runs = runs)).contains(""""satisfies_s25_device_gate":false"""),
        )
    }

    @Test
    fun runsWhoseLandmarkCardinalityDivergesNeverSatisfyTheGate() {
        val runs = fiveConsistentRuns().toMutableList()
        runs[1] = run(2, pose = detected().copy(worldLandmarkCountPerFrame = listOf(33, 32)))
        assertTrue(
            renderFiveRunReplayEvidence(evidence(runs = runs)).contains(""""satisfies_s25_device_gate":false"""),
        )
    }

    @Test
    fun recordsTheObservedDetectionEvidenceInTheRenderedRecord() {
        val rendered = renderFiveRunReplayEvidence(evidence())
        assertTrue(rendered.contains(""""pose_present_per_frame":[true,true]"""))
        assertTrue(rendered.contains(""""normalized_landmark_count_per_frame":[33,33]"""))
        assertTrue(rendered.contains(""""world_landmark_count_per_frame":[33,33]"""))
        assertTrue(rendered.contains(""""pose_detected_records":2"""))
    }

    @Test
    fun rendersByteIdenticalOutputForIdenticalInput() {
        assertTrue(renderFiveRunReplayEvidence(evidence()) == renderFiveRunReplayEvidence(evidence()))
    }

    @Test
    fun isASingleLineRecord() {
        assertFalse(renderFiveRunReplayEvidence(evidence()).contains('\n'))
    }

    @Test
    fun refusesToRenderZeroRuns() {
        assertFailsWith<IllegalArgumentException> { renderFiveRunReplayEvidence(evidence(runs = emptyList())) }
    }

    @Test
    fun authorizesTheS25UltraByModelWhenMarketingNameIsAbsent() {
        // Retail S25 Ultra firmware may report ro.config.marketing_name as empty. A
        // marketing-name-only rule rejected the very hardware this gate exists to authorize.
        assertTrue(
            isAuthorizedFiveRunGateTarget(environment(marketingName = "", model = "SM-S938B")),
        )
    }

    @Test
    fun authorizesEveryS25UltraRegionalModel() {
        for (model in listOf("SM-S938B", "SM-S938U", "SM-S938N", "SM-S938W", "sm-s938b")) {
            assertTrue(
                isAuthorizedFiveRunGateTarget(environment(marketingName = "", model = model)),
                "expected $model to be authorized",
            )
        }
    }

    @Test
    fun stillAuthorizesOnMarketingNameWhenTheModelIsUnrecognized() {
        assertTrue(
            isAuthorizedFiveRunGateTarget(environment(marketingName = "Galaxy S25 Ultra", model = "")),
        )
    }

    @Test
    fun rejectsAnEmulatorRegardlessOfModelOrMarketingName() {
        assertFalse(
            isAuthorizedFiveRunGateTarget(
                environment(deviceClass = "emulator", marketingName = "Galaxy S25 Ultra", model = "SM-S938B"),
            ),
        )
    }

    @Test
    fun rejectsAnotherSamsungPhysicalDevice() {
        assertFalse(
            isAuthorizedFiveRunGateTarget(environment(marketingName = "", model = "SM-S931B")),
        )
    }

    @Test
    fun rejectsANonSamsungDeviceEvenWhenTheModelPrefixMatches() {
        assertFalse(
            isAuthorizedFiveRunGateTarget(
                environment(marketingName = "", model = "SM-S938B", manufacturer = "notsamsung"),
            ),
        )
    }
}
