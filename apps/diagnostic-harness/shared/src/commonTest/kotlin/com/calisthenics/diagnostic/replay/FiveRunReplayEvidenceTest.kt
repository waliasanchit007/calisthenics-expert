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

    private fun environment(deviceClass: String = "physical_device", marketingName: String = "Galaxy S25 Ultra") =
        FiveRunReplayEnvironment(
            deviceClass = deviceClass,
            deviceIdentity = "samsung/e3q/e3q/e3q",
            deviceFingerprint = "samsung/e3qxxx/e3q:16/BP2A.250705.008/S938BXXU4BYH2:user/release-keys",
            apiLevel = 36,
            androidRelease = "16",
            abi = "arm64-v8a",
            manufacturer = "samsung",
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

    private fun run(index: Int, published: Boolean = true, validated: Boolean = true) = FiveRunReplayRunRecord(
        runIndex = index,
        sessionId = "5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8${index}",
        published = published,
        submittedIndices = listOf(0L, 1L),
        backendTimestampsMs = listOf(0L, 33L),
        canonicalInputSha256 = listOf("c0", "c1"),
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
        val rendered = renderFiveRunReplayEvidence(evidence(environment = environment(marketingName = "Galaxy A15")))
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
}
