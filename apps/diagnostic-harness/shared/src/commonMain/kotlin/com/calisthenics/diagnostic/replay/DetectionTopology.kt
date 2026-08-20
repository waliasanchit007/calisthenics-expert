package com.calisthenics.diagnostic.replay

import com.calisthenics.diagnostic.contract.CanonicalNumber
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.hash.Sha256Digest

/** Raised when cross-run output topology diverges, or when a run is not detection evidence at all. */
class DetectionTopologyException(message: String) : IllegalStateException(message)

/**
 * The per-frame output topology and cardinality of one detection run: exactly what check 5
 * compares.
 *
 * Landmark coordinate *values* are deliberately absent. They are check 6 observations, reported
 * and never thresholded; putting them here would smuggle a numeric comparison into a structural
 * check. What is compared is shape: whether a pose came back, how many, how many landmarks each
 * carries, and which landmarks the backend populated presence for.
 */
data class FrameTopology(
    val frameIndex: Long,
    val posePresent: Boolean,
    val poseCount: Int,
    val normalizedLandmarkCount: Int,
    val worldLandmarkCount: Int,
    val presencePattern: List<Boolean>,
)

/**
 * Derives one frame's topology from what the backend returned.
 *
 * Derivation lives in `commonMain` rather than in the Android device test so the mapping from a
 * backend result to compared topology is itself covered by host tests, and so the identical
 * derivation would be reused by a second platform backend.
 *
 * `presencePattern` records, per landmark and in order, whether the backend populated **both**
 * `visibility` and `presence`. MediaPipe returns these as optionals, so which landmarks carry them
 * is a structural property of the output — a tracker that starts eliding them changes the pattern
 * without changing any cardinality.
 */
fun frameTopologyOf(
    frameIndex: Long,
    posePresent: Boolean,
    poseCount: Int,
    normalizedLandmarks: List<Landmark>?,
    worldLandmarks: List<Landmark>?,
): FrameTopology = FrameTopology(
    frameIndex = frameIndex,
    posePresent = posePresent,
    poseCount = poseCount,
    normalizedLandmarkCount = normalizedLandmarks?.size ?: 0,
    worldLandmarkCount = worldLandmarks?.size ?: 0,
    presencePattern = normalizedLandmarks.orEmpty().map { it.visibility != null && it.presence != null },
)

/**
 * Check 5: output topology and cardinality are compared per frame across runs, on a fixture that
 * actually fires the detector.
 *
 * The three functions here are deliberately separate because they fail for different reasons and a
 * caller must not be able to satisfy one by weakening another:
 *
 * - [requireIdenticalAcrossRuns] is the cross-run comparison itself;
 * - [requireDetectionFired] is the admission criterion — a 5x1 fixture yields zero poses on every
 *   frame and would satisfy the comparison trivially;
 * - [requireNonVacuousLandmarkOutput] closes the remaining vacuity: a full-cardinality list of
 *   zeros, or one frozen result repeated on every frame, satisfies every cardinality comparison
 *   and every presence pattern while carrying no detection at all.
 */
object DetectionTopologyCheck {

    /**
     * Requires at least two runs whose per-frame topology is identical, and returns the agreed
     * topology. Divergence is reported as the failure it is, never smoothed over: design section 7
     * makes a topology failure under the pinned CPU delegate a material backend-nondeterminism
     * finding rather than a flaky test.
     */
    fun requireIdenticalAcrossRuns(runs: List<List<FrameTopology>>): List<FrameTopology> {
        if (runs.size < 2) {
            throw DetectionTopologyException(
                "cross-run topology equality needs at least two runs, got ${runs.size}",
            )
        }
        val reference = runs.first()
        if (reference.isEmpty()) {
            throw DetectionTopologyException("a replay run submitted no frames, so it is not evidence")
        }
        runs.drop(1).forEachIndexed { offset, candidate ->
            val runNumber = offset + 2
            if (candidate.size != reference.size) {
                throw DetectionTopologyException(
                    "run $runNumber emitted ${candidate.size} frames, run 1 emitted ${reference.size}",
                )
            }
            reference.forEachIndexed { position, expected ->
                val actual = candidate[position]
                if (actual.frameIndex != expected.frameIndex) {
                    throw DetectionTopologyException(
                        "run $runNumber position $position carries frame ${actual.frameIndex}, " +
                            "run 1 carries frame ${expected.frameIndex}",
                    )
                }
                divergentField(expected, actual)?.let { (field, values) ->
                    throw DetectionTopologyException(
                        "frame ${expected.frameIndex} $field diverged between run 1 and run " +
                            "$runNumber: $values",
                    )
                }
            }
        }
        return reference
    }

    /**
     * The admission criterion of design section 7: the detection fixture is admissible for the
     * topology and landmark-observation checks only once the pinned model actually produces a pose
     * on every frame. A `posePresent` flag with no landmarks behind it is rejected too — it is the
     * same vacuity wearing a boolean.
     */
    fun requireDetectionFired(frames: List<FrameTopology>) {
        if (frames.isEmpty()) {
            throw DetectionTopologyException("an empty run cannot show that detection fired")
        }
        frames.forEach { frame ->
            if (!frame.posePresent || frame.poseCount < 1) {
                throw DetectionTopologyException(
                    "detection did not fire on frame ${frame.frameIndex}: " +
                        "posePresent=${frame.posePresent} poseCount=${frame.poseCount}",
                )
            }
            if (frame.normalizedLandmarkCount < 1 || frame.presencePattern.isEmpty()) {
                throw DetectionTopologyException(
                    "frame ${frame.frameIndex} reports a pose with no landmarks behind it: " +
                        "normalizedLandmarkCount=${frame.normalizedLandmarkCount} " +
                        "presencePattern=${frame.presencePattern.size}",
                )
            }
        }
    }

    /**
     * Returns the per-frame observation digest of an ordered landmark output, rejecting the two
     * degenerate shapes that would otherwise pass every structural check.
     *
     * These are equality comparisons against a computed degenerate oracle and against each other.
     * No numeric magnitude is compared, no tolerance is invented, and the returned digests are
     * observations for the export — check 6 stays intact.
     */
    fun requireNonVacuousLandmarkOutput(perFrameLandmarks: List<List<Landmark>>): List<String> {
        if (perFrameLandmarks.isEmpty()) {
            throw DetectionTopologyException("no landmark output was observed, so there is nothing to admit")
        }
        val digests = perFrameLandmarks.map { observationDigest(it) }
        perFrameLandmarks.forEachIndexed { position, observed ->
            if (digests[position] == observationDigest(zeroed(observed.size))) {
                throw DetectionTopologyException(
                    "frame at position $position returned an all-zero landmark output of " +
                        "cardinality ${observed.size}; that is shape without detection",
                )
            }
        }
        if (digests.size > 1 && digests.toSet().size == 1) {
            throw DetectionTopologyException(
                "every frame returned a byte-identical landmark output; a frozen or reused result " +
                    "satisfies cardinality comparison while observing nothing",
            )
        }
        return digests
    }

    private fun divergentField(expected: FrameTopology, actual: FrameTopology): Pair<String, String>? = when {
        expected.posePresent != actual.posePresent ->
            "posePresent" to "${expected.posePresent} then ${actual.posePresent}"
        expected.poseCount != actual.poseCount ->
            "poseCount" to "${expected.poseCount} then ${actual.poseCount}"
        expected.normalizedLandmarkCount != actual.normalizedLandmarkCount ->
            "normalizedLandmarkCount" to
                "${expected.normalizedLandmarkCount} then ${actual.normalizedLandmarkCount}"
        expected.worldLandmarkCount != actual.worldLandmarkCount ->
            "worldLandmarkCount" to "${expected.worldLandmarkCount} then ${actual.worldLandmarkCount}"
        expected.presencePattern != actual.presencePattern ->
            "presencePattern" to "${expected.presencePattern} then ${actual.presencePattern}"
        else -> null
    }

    private fun zeroed(count: Int): List<Landmark> = (0 until count).map { index ->
        Landmark(index = index, name = null, x = 0.0, y = 0.0, z = 0.0, visibility = 0.0, presence = 0.0)
    }

    /**
     * Canonical, target-independent bytes for one frame's ordered landmark output. Reuses the
     * released canonical number formatting so a digest recorded on Android equals one recorded on
     * the JVM for the same values.
     */
    private fun observationDigest(observed: List<Landmark>): String {
        val canonical = StringBuilder()
        observed.forEach { landmark ->
            canonical.append(landmark.index).append(':')
            canonical.append(CanonicalNumber.format(landmark.x)).append(',')
            canonical.append(CanonicalNumber.format(landmark.y)).append(',')
            canonical.append(CanonicalNumber.format(landmark.z)).append(',')
            canonical.append(landmark.visibility?.let(CanonicalNumber::format) ?: "null").append(',')
            canonical.append(landmark.presence?.let(CanonicalNumber::format) ?: "null").append(';')
        }
        return Sha256Digest.hex(canonical.toString().encodeToByteArray())
    }
}
