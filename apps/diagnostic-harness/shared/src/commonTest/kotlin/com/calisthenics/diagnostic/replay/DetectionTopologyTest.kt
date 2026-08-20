package com.calisthenics.diagnostic.replay

import com.calisthenics.diagnostic.contract.Landmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val POSE_LANDMARK_CARDINALITY = 33

private fun landmarks(seed: Int, count: Int = POSE_LANDMARK_CARDINALITY): List<Landmark> =
    (0 until count).map { index ->
        Landmark(
            index = index,
            name = null,
            x = 0.1 + index * 0.001 + seed * 0.01,
            y = 0.2 + index * 0.002 + seed * 0.01,
            z = -0.05 + index * 0.0005,
            visibility = 0.9,
            presence = 0.95,
        )
    }

private fun zeroLandmarks(count: Int = POSE_LANDMARK_CARDINALITY): List<Landmark> =
    (0 until count).map { index ->
        Landmark(index = index, name = null, x = 0.0, y = 0.0, z = 0.0, visibility = 0.0, presence = 0.0)
    }

private fun run(frameCount: Int = 6, poseCount: Int = 1): List<FrameTopology> =
    (0 until frameCount).map { index ->
        frameTopologyOf(
            frameIndex = index.toLong(),
            posePresent = true,
            poseCount = poseCount,
            normalizedLandmarks = landmarks(index),
            worldLandmarks = landmarks(index),
        )
    }

class DetectionTopologyTest {

    // Derivation ----------------------------------------------------------------------------
    @Test
    fun topologyRecordsCardinalityAndPresencePatternRatherThanCoordinateValues() {
        val topology = frameTopologyOf(
            frameIndex = 3L,
            posePresent = true,
            poseCount = 1,
            normalizedLandmarks = landmarks(3).mapIndexed { index, landmark ->
                if (index == 7) landmark.copy(presence = null) else landmark
            },
            worldLandmarks = landmarks(3),
        )

        assertEquals(3L, topology.frameIndex)
        assertEquals(1, topology.poseCount)
        assertEquals(POSE_LANDMARK_CARDINALITY, topology.normalizedLandmarkCount)
        assertEquals(POSE_LANDMARK_CARDINALITY, topology.worldLandmarkCount)
        assertEquals(POSE_LANDMARK_CARDINALITY, topology.presencePattern.size)
        assertEquals(false, topology.presencePattern[7])
        assertTrue(topology.presencePattern.filterIndexed { index, _ -> index != 7 }.all { it })
    }

    @Test
    fun anAbsentPoseIsRecordedAsZeroCardinality() {
        val topology = frameTopologyOf(
            frameIndex = 0L,
            posePresent = false,
            poseCount = 0,
            normalizedLandmarks = null,
            worldLandmarks = null,
        )

        assertEquals(0, topology.poseCount)
        assertEquals(0, topology.normalizedLandmarkCount)
        assertEquals(0, topology.worldLandmarkCount)
        assertEquals(emptyList(), topology.presencePattern)
    }

    // Check 5 -------------------------------------------------------------------------------
    @Test
    fun check5_identicalRunsAgreeOnPerFrameTopology() {
        val agreed = DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), run(), run()))

        assertEquals(run(), agreed)
    }

    @Test
    fun check5_aDivergentPoseCountIsRejected() {
        val second = run().mapIndexed { index, frame ->
            if (index == 4) frame.copy(poseCount = 2) else frame
        }

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), second))
        }
        assertTrue("poseCount" in failure.message!!, failure.message!!)
        assertTrue("frame 4" in failure.message!!, failure.message!!)
    }

    @Test
    fun check5_aDivergentNormalizedLandmarkCountIsRejected() {
        val second = run().mapIndexed { index, frame ->
            if (index == 2) frame.copy(normalizedLandmarkCount = 32) else frame
        }

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), second))
        }
        assertTrue("normalizedLandmarkCount" in failure.message!!, failure.message!!)
    }

    @Test
    fun check5_aDivergentWorldLandmarkCountIsRejected() {
        val second = run().mapIndexed { index, frame ->
            if (index == 1) frame.copy(worldLandmarkCount = 0) else frame
        }

        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), second))
        }
    }

    @Test
    fun check5_aDivergentPresencePatternIsRejected() {
        val second = run().mapIndexed { index, frame ->
            if (index == 5) frame.copy(presencePattern = frame.presencePattern.toMutableList().also { it[11] = false }) else frame
        }

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), second))
        }
        assertTrue("presencePattern" in failure.message!!, failure.message!!)
    }

    @Test
    fun check5_aDivergentPosePresentFlagIsRejected() {
        val second = run().mapIndexed { index, frame ->
            if (index == 3) frame.copy(posePresent = false) else frame
        }

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), second))
        }
        assertTrue("posePresent" in failure.message!!, failure.message!!)
    }

    @Test
    fun check5_divergentFrameIndexSequencesAreRejected() {
        val second = run().map { frame -> frame.copy(frameIndex = frame.frameIndex + 1) }

        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), second))
        }
    }

    @Test
    fun check5_divergentFrameCountsAreRejected() {
        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run(), run(frameCount = 5)))
        }
    }

    @Test
    fun check5_aSingleRunIsNotCrossRunEvidence() {
        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(run()))
        }
    }

    @Test
    fun check5_anEmptyRunIsNotEvidence() {
        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireIdenticalAcrossRuns(listOf(emptyList(), emptyList()))
        }
    }

    // Admission: detection must genuinely have fired ----------------------------------------
    @Test
    fun admission_acceptsARunWhereEveryFrameCarriesAPose() {
        DetectionTopologyCheck.requireDetectionFired(run())
    }

    @Test
    fun admission_rejectsAFrameWithoutAPose() {
        val frames = run().mapIndexed { index, frame ->
            if (index == 2) {
                frameTopologyOf(frame.frameIndex, posePresent = false, poseCount = 0, normalizedLandmarks = null, worldLandmarks = null)
            } else {
                frame
            }
        }

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireDetectionFired(frames)
        }
        assertTrue("frame 2" in failure.message!!, failure.message!!)
    }

    @Test
    fun admission_rejectsAFrameThatReportsNoPoseWhileStillCarryingLandmarks() {
        // Covers the presence guard on its own: with a full landmark list behind it, the
        // cardinality guard below cannot stand in for it.
        val frames = run().mapIndexed { index, frame ->
            if (index == 3) frame.copy(posePresent = false) else frame
        }

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireDetectionFired(frames)
        }
        assertTrue("frame 3" in failure.message!!, failure.message!!)
        assertTrue("posePresent=false" in failure.message!!, failure.message!!)
    }

    @Test
    fun admission_rejectsAZeroPoseCountWhileStillCarryingLandmarks() {
        val frames = run().mapIndexed { index, frame ->
            if (index == 1) frame.copy(poseCount = 0) else frame
        }

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireDetectionFired(frames)
        }
        assertTrue("poseCount=0" in failure.message!!, failure.message!!)
    }

    @Test
    fun admission_rejectsAPosePresentFlagWithNoLandmarksBehindIt() {
        val frames = run().mapIndexed { index, frame ->
            if (index == 0) frame.copy(normalizedLandmarkCount = 0, presencePattern = emptyList()) else frame
        }

        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireDetectionFired(frames)
        }
    }

    @Test
    fun admission_rejectsAnEmptyRun() {
        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireDetectionFired(emptyList())
        }
    }

    // Non-vacuity: a full-cardinality list of zeros is not detection -------------------------
    @Test
    fun nonVacuity_rejectsAnAllZeroLandmarkOutput() {
        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireNonVacuousLandmarkOutput(
                listOf(zeroLandmarks(), zeroLandmarks(), zeroLandmarks()),
            )
        }
        assertTrue("zero" in failure.message!!, failure.message!!)
    }

    @Test
    fun nonVacuity_rejectsAFrozenOutputRepeatedOnEveryFrame() {
        val frozen = landmarks(1)

        val failure = assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireNonVacuousLandmarkOutput(listOf(frozen, frozen, frozen))
        }
        assertTrue("frozen" in failure.message!!, failure.message!!)
    }

    @Test
    fun nonVacuity_acceptsDistinctNonZeroPerFrameOutput() {
        val digests = DetectionTopologyCheck.requireNonVacuousLandmarkOutput(
            listOf(landmarks(0), landmarks(1), landmarks(2)),
        )

        assertEquals(3, digests.size)
        assertEquals(3, digests.toSet().size)
        assertTrue(digests.all { it.length == 64 })
    }

    @Test
    fun nonVacuity_rejectsAnEmptyObservationSet() {
        assertFailsWith<DetectionTopologyException> {
            DetectionTopologyCheck.requireNonVacuousLandmarkOutput(emptyList())
        }
    }

    @Test
    fun nonVacuity_digestIsStableForTheSameOrderedObservation() {
        val first = DetectionTopologyCheck.requireNonVacuousLandmarkOutput(listOf(landmarks(0), landmarks(1)))
        val second = DetectionTopologyCheck.requireNonVacuousLandmarkOutput(listOf(landmarks(0), landmarks(1)))

        assertEquals(first, second)
    }
}
