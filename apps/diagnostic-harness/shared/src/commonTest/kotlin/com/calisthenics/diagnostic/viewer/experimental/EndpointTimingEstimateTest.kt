package com.calisthenics.diagnostic.viewer.experimental

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic trajectories for the offline endpoint estimate, written against
 * `endpoint-timing-spec-v1.md` and not against any recording.
 *
 * Every fixture here is SYNTHETIC and is not evidence of accuracy on video. What these pin is that
 * the definition behaves as specified — including every case in which it must refuse to name an
 * endpoint at all.
 */
class EndpointTimingEstimateTest {
    private val config = EndpointTimingConfig(
        plateauBandFraction = .05,
        admissionLevel = .80,
        reversalSamples = 2,
    )

    private val frameNs = 100L
    private fun samples(values: List<Double?>, usableFrom: Int = 0) = values.mapIndexed { index, v ->
        EndpointSample((usableFrom + index) * frameNs, v, v != null)
    }

    /** Rise to a 1.00 plateau, hold, then fall away. Crossing is index 0. */
    private fun plateau(hold: Int, noise: List<Double> = emptyList()) = buildList {
        addAll(listOf(.82, .88, .94, .98))
        repeat(hold) { add(1.0 - (noise.getOrNull(it) ?: 0.0)) }
        addAll(listOf(.96, .90, .78, .70))
    }

    private fun estimate(values: List<Double?>, ended: Boolean = false) =
        estimateEndpoint(0L, 400L, samples(values), config, ended)

    @Test
    fun departureIsTheLastPlateauFrameAndArrivalTheFirst() {
        val r = estimate(plateau(hold = 5))
        assertEquals(EndpointStatus.RESOLVED, r.status)
        // Band = 0.05 * (1.00 - 0.80) = 0.01, so the plateau band is [0.99, 1.00]: indices 4..8.
        assertEquals(4 * frameNs, r.plateauArrivalNs)
        assertEquals(8 * frameNs, r.estimatedDepartureNs)
        assertEquals(4 * frameNs, r.uncertaintyNs)
        assertEquals(4 * frameNs, r.extremumNs) // first maximum on a tie
    }

    /** Plateau noise inside the band must not fragment the plateau or move the endpoint. */
    @Test
    fun perFrameNoiseInsideTheBandDoesNotFragmentThePlateau() {
        val r = estimate(plateau(hold = 6, noise = listOf(.0, .004, .002, .008, .001, .003)))
        assertEquals(EndpointStatus.RESOLVED, r.status)
        assertEquals(4 * frameNs, r.plateauArrivalNs)
        assertEquals(9 * frameNs, r.estimatedDepartureNs)
    }

    /** A shallow dip below the band is still one plateau; a deep one is two peaks and is refused. */
    @Test
    fun aShallowDipContinuesThePlateauButADeepDipIsRefusedAsADoublePeak() {
        val shallow = estimate(listOf(.82, .90, .98, 1.0, .985, 1.0, .96, .88, .76, .70))
        assertEquals(EndpointStatus.RESOLVED, shallow.status)
        assertEquals(5 * frameNs, shallow.estimatedDepartureNs)

        val deep = estimate(listOf(.82, .90, .98, 1.0, .93, 1.0, .96, .88, .76, .70))
        assertEquals(EndpointStatus.UNKNOWN_DOUBLE_PEAK, deep.status)
        assertNull(deep.estimatedDepartureNs)
        // Both candidates are surfaced; neither is chosen for the caller.
        assertEquals(listOf(3 * frameNs, 5 * frameNs), deep.doublePeakCandidatesNs)
    }

    @Test
    fun aDropoutAnywhereInTheExcursionForfeitsTheEndpoint() {
        val values: MutableList<Double?> = plateau(hold = 5).toMutableList<Double?>()
        values[6] = null // one unmeasurable frame on the plateau
        val r = estimate(values)
        assertEquals(EndpointStatus.UNKNOWN_DROPOUT, r.status)
        assertNull(r.estimatedDepartureNs)
        // The engine's own instants still pass through untouched.
        assertEquals(0L, r.crossingNs)
        assertEquals(400L, r.confirmationNs)
    }

    /** A partial repetition never reverses, so it never yields an endpoint. */
    @Test
    fun anIncompleteReversalIsRefused() {
        val r = estimate(listOf(.82, .90, .98, 1.0, 1.0, .99, .98))
        assertEquals(EndpointStatus.UNKNOWN_NO_REVERSAL, r.status)
        assertNull(r.estimatedDepartureNs)
        assertNull(r.confirmationDelayNs)
    }

    /**
     * One frame below the level is not a reversal when two consecutive are required: the excursion
     * continues past an isolated dip rather than being cut short at it.
     */
    @Test
    fun aSingleFrameBelowTheLevelIsNotAReversal() {
        val r = estimate(listOf(.82, .90, .98, 1.0, 1.0, .96, .79, .96, .90, .78, .70))
        assertEquals(EndpointStatus.RESOLVED, r.status)
        assertEquals(4 * frameNs, r.estimatedDepartureNs)
        // Reversal is only confirmed by the LAST two frames, well after the isolated 0.79.
        assertEquals(10 * frameNs, r.reversalConfirmedNs)
    }

    /**
     * A deep isolated dip in the middle of a plateau is refused, not resolved. Whether it is a
     * tracking glitch or a genuine double bounce cannot be told from the signal, and the spec
     * requires recording that rather than picking one of the two peaks.
     */
    @Test
    fun aDeepDipInsideThePlateauIsRefusedRatherThanResolved() {
        val r = estimate(listOf(.82, .90, 1.0, 1.0, .79, .98, 1.0, .97, .70, .60))
        assertEquals(EndpointStatus.UNKNOWN_DOUBLE_PEAK, r.status)
        assertNull(r.estimatedDepartureNs)
        assertEquals(listOf(3 * frameNs, 6 * frameNs), r.doublePeakCandidatesNs)
    }

    @Test
    fun aClipThatEndsMidExcursionIsRefusedNotTruncated() {
        val r = estimate(listOf(.82, .90, .98, 1.0, 1.0))
        assertEquals(EndpointStatus.UNKNOWN_NO_REVERSAL, r.status)
    }

    @Test
    fun aCycleEndedByADiscontinuityIsRefusedBeforeAnythingIsMeasured() {
        val r = estimate(plateau(hold = 5), ended = true)
        assertEquals(EndpointStatus.UNKNOWN_DISCONTINUITY, r.status)
        assertNull(r.extremumNs)
    }

    @Test
    fun tooFewSamplesIsRefused() {
        assertEquals(EndpointStatus.UNKNOWN_TOO_FEW_SAMPLES, estimate(listOf(1.0)).status)
    }

    /**
     * The lookahead is stated, not hidden: the estimate is only final once reversal is confirmed,
     * which is strictly after the instant it describes.
     */
    @Test
    fun confirmationDelayIsReportedAndIsAlwaysPositive() {
        val r = estimate(plateau(hold = 5))
        val departure = assertNotNull(r.estimatedDepartureNs)
        val reversal = assertNotNull(r.reversalConfirmedNs)
        assertTrue(reversal > departure, "reversal must follow the endpoint it confirms")
        assertEquals(reversal - departure, r.confirmationDelayNs)
    }

    /** Accuracy and latency are separate numbers and must not be combined into one. */
    @Test
    fun theEnginesOwnInstantsArePreservedAlongsideTheEstimate() {
        val r = estimateEndpoint(1_000L, 1_400L, samples(plateau(hold = 5)), config)
        assertEquals(1_000L, r.crossingNs)
        assertEquals(1_400L, r.confirmationNs)
        assertTrue(assertNotNull(r.estimatedDepartureNs) != r.crossingNs)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertFailsWith<IllegalArgumentException> { config.copy(plateauBandFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(plateauBandFraction = 1.0) }
        assertFailsWith<IllegalArgumentException> { config.copy(reversalSamples = 0) }
        assertFailsWith<IllegalArgumentException> { config.copy(admissionLevel = Double.NaN) }
    }
}
