package com.calisthenics.diagnostic.replay

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The two derivations behind a five-run device-gate record, kept in `commonMain` so host tests can
 * reach them rather than living inside the Android device test that emits the record.
 *
 * Both exist because the device test previously transcribed *declarations* instead of
 * *observations*: the fixture binding copied the metadata's declared plane digests, and nothing in
 * the record said whether any pose had been detected at all. A record built from declarations is
 * evidence about the fixture file, not about what the device replayed.
 */
class FiveRunReplayBindingTest {

    private fun fixture(vararg declaredOverrides: Pair<Int, String>): ReplayFixture {
        val overrides = declaredOverrides.toMap()
        val frames = (0 until 2).map { index ->
            val plane = "plane-$index".encodeToByteArray()
            ReplayFixtureFrame(
                frameIndex = index.toLong(),
                captureTimestampNs = index * 33_333_333L,
                plane = plane,
                declaredSha256 = overrides[index] ?: Sha256Digest.hex(plane),
            )
        }
        return ReplayFixture(frames = frames, frameWidth = 256, frameHeight = 256)
    }

    @Test
    fun theFixtureBindingRecordsDigestsComputedFromTheActualPlaneBytes() {
        val binding = fiveRunFixtureBindingOf("fixture-id", fixture())

        assertEquals("fixture-id", binding.fixtureId)
        assertEquals(listOf(0L, 1L), binding.frameIndices)
        assertEquals(
            listOf(
                Sha256Digest.hex("plane-0".encodeToByteArray()),
                Sha256Digest.hex("plane-1".encodeToByteArray()),
            ),
            binding.frameSha256,
        )
    }

    @Test
    fun theFixtureBindingIgnoresADeclaredDigestThatDisagreesWithTheBytes() {
        // `decodeReplayFixtureMetadata` refuses this fixture outright, so this pair can only be
        // constructed directly — which is exactly the path a device test that hand-rolls its own
        // loader takes. Even then, the binding must record the bytes, never the claim.
        val lie = "0".repeat(64)
        val binding = fiveRunFixtureBindingOf("fixture-id", fixture(0 to lie))

        assertNotEquals(lie, binding.frameSha256[0])
        assertEquals(Sha256Digest.hex("plane-0".encodeToByteArray()), binding.frameSha256[0])
    }

    @Test
    fun thePoseObservationIsDerivedFromTheRecordsTheSessionEmitted() {
        val records = listOf(record(posePresent = true, landmarks = 33), record(posePresent = false, landmarks = 0))

        val observation = fiveRunPoseObservationOf(records, poseDetectedRecords = 1L)

        assertEquals(listOf(true, false), observation.posePresentPerFrame)
        assertEquals(listOf(33, 0), observation.normalizedLandmarkCountPerFrame)
        assertEquals(listOf(33, 0), observation.worldLandmarkCountPerFrame)
        assertEquals(1L, observation.poseDetectedRecords)
    }

    @Test
    fun anEmptyPipelineDerivesAnObservationThatShowsItDetectedNothing() {
        val observation = fiveRunPoseObservationOf(
            listOf(record(posePresent = false, landmarks = 0), record(posePresent = false, landmarks = 0)),
            poseDetectedRecords = 0L,
        )

        assertEquals(listOf(false, false), observation.posePresentPerFrame)
        assertEquals(listOf(0, 0), observation.normalizedLandmarkCountPerFrame)
        assertEquals(0L, observation.poseDetectedRecords)
    }

    private fun record(posePresent: Boolean, landmarks: Int): RawPoseFrame = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b80"),
        frameIndex = 0,
        captureTimestampNs = 0,
        backendTimestampMs = 0,
        inferenceStartNs = 0,
        inferenceEndNs = 1,
        posePresent = posePresent,
        normalizedImageLandmarks = landmarkList(landmarks),
        worldLandmarks = landmarkList(landmarks),
        imageWidth = 256,
        imageHeight = 256,
        framesSkippedSinceLastEmittedRecord = 0,
    )

    private fun landmarkList(count: Int): List<Landmark>? = if (count == 0) {
        null
    } else {
        (0 until count).map { Landmark(it, null, 0.1, 0.2, 0.3, 0.9, 0.9) }
    }
}
