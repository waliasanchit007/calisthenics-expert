package com.calisthenics.diagnostic.viewer.experimental

/**
 * OFFLINE endpoint-timing estimate. Specification: `endpoint-timing-spec-v1.md`, frozen before this
 * file was written.
 *
 * This is an experiment, additive by construction. It consumes a repetition that some engine has
 * ALREADY decided, and reports a better-placed instant for it. It cannot change a count, a cycle
 * identity or a threshold, because it is never consulted while counting.
 *
 * Why it exists: a threshold-crossing engine reports where it DETECTED the endpoint, which on a
 * continuous trajectory is not where the movement ENDED. On one measured HSPU repetition the span
 * ratio crossed 0.80 at 4.371 s while still rising, and did not stop rising until 4.81 s. The
 * crossing is a detection instant; it was being read as an event instant.
 *
 * IT REQUIRES LOOKAHEAD. The estimate is only final once the signal has reversed, so it is NOT
 * available at the moment it describes. [EndpointEstimate.confirmationDelayNs] states that latency
 * explicitly rather than hiding it.
 */
data class EndpointTimingConfig(
    /**
     * Half-width of the plateau band, as a fraction of the excursion amplitude
     * (extremum - [admissionLevel]).
     *
     * FROZEN at 0.05 from SIGNAL NOISE ONLY, with no annotation consulted: per-frame absolute
     * change on near-top frames measured median 0.0024 and p90 0.0099, and 5% of a 0.1996 excursion
     * is 0.0100 — the smallest band that ordinary single-frame jitter will not fragment.
     */
    val plateauBandFraction: Double,
    /**
     * The level that admits the excursion AND defines its reversal. Deliberately the engine's
     * existing threshold, so this estimator introduces no new threshold of its own.
     */
    val admissionLevel: Double,
    /** Consecutive usable frames below [admissionLevel] that confirm reversal. Mirrors the engine. */
    val reversalSamples: Int,
) {
    init {
        require(plateauBandFraction.isFinite() && plateauBandFraction > 0.0 && plateauBandFraction < 1.0) {
            "plateau_band_fraction must be finite and in (0, 1)"
        }
        require(admissionLevel.isFinite()) { "admission_level must be finite" }
        require(reversalSamples >= 1) { "reversal_samples must be at least 1" }
    }
}

/** One observation inside the excursion. [usable] false means the frame was not measurable. */
data class EndpointSample(val timestampNs: Long, val value: Double?, val usable: Boolean)

enum class EndpointStatus {
    RESOLVED,
    UNKNOWN_DROPOUT,
    UNKNOWN_NO_REVERSAL,
    UNKNOWN_DISCONTINUITY,
    UNKNOWN_DOUBLE_PEAK,
    UNKNOWN_TOO_FEW_SAMPLES,
}

/**
 * All three instants are reported side by side and none replaces another.
 *
 * @param crossingNs the engine's existing threshold crossing (first arrival), passed through.
 * @param confirmationNs the engine's existing confirmation frame, passed through.
 * @param extremumNs argmax of the signal in the excursion; first maximum on a tie.
 * @param plateauArrivalNs first frame inside the plateau band.
 * @param estimatedDepartureNs last frame inside the plateau band — the estimator matched to an
 *   annotation that marks descent onset. Null unless [status] is [EndpointStatus.RESOLVED].
 * @param uncertaintyNs plateau width: departure minus arrival. A unique instant is NOT claimed
 *   inside it.
 * @param confirmationDelayNs reversal confirmation minus the estimate: the lookahead a live system
 *   would have to wait. Reported separately from accuracy, never netted against it.
 */
data class EndpointEstimate(
    val crossingNs: Long,
    val confirmationNs: Long,
    val extremumNs: Long?,
    val plateauArrivalNs: Long?,
    val estimatedDepartureNs: Long?,
    val uncertaintyNs: Long?,
    val reversalConfirmedNs: Long?,
    val confirmationDelayNs: Long?,
    val status: EndpointStatus,
    /** On [EndpointStatus.UNKNOWN_DOUBLE_PEAK], every candidate run's last frame. Never chosen between. */
    val doublePeakCandidatesNs: List<Long> = emptyList(),
)

/**
 * [samples] must be exactly the frames of ONE excursion, in order: from the engine's threshold
 * crossing up to and including the frames that confirm reversal. The caller is responsible for not
 * spanning two repetitions; this function never looks outside what it is given, and never bridges a
 * gap inside it.
 */
fun estimateEndpoint(
    crossingNs: Long,
    confirmationNs: Long,
    samples: List<EndpointSample>,
    config: EndpointTimingConfig,
    endedByDiscontinuity: Boolean = false,
): EndpointEstimate {
    fun unknown(status: EndpointStatus, candidates: List<Long> = emptyList()) = EndpointEstimate(
        crossingNs, confirmationNs, null, null, null, null, null, null, status, candidates,
    )

    if (endedByDiscontinuity) return unknown(EndpointStatus.UNKNOWN_DISCONTINUITY)
    // Never bridge a missing observation: one unusable frame inside the excursion forfeits it.
    if (samples.any { !it.usable || it.value == null || !it.value.isFinite() }) {
        return unknown(EndpointStatus.UNKNOWN_DROPOUT)
    }
    if (samples.size < 2) return unknown(EndpointStatus.UNKNOWN_TOO_FEW_SAMPLES)

    // Reversal: the first index after which `reversalSamples` consecutive frames sit below the level.
    var run = 0
    var reversalIndex: Int? = null
    for ((index, sample) in samples.withIndex()) {
        if (requireNotNull(sample.value) < config.admissionLevel) {
            run++
            if (run >= config.reversalSamples) { reversalIndex = index; break }
        } else {
            run = 0
        }
    }
    val reversalAt = reversalIndex ?: return unknown(EndpointStatus.UNKNOWN_NO_REVERSAL)

    // The plateau is searched only over the rise-and-hold, never over the reversal itself.
    val excursion = samples.subList(0, reversalAt)
    if (excursion.size < 2) return unknown(EndpointStatus.UNKNOWN_TOO_FEW_SAMPLES)
    val extremum = excursion.maxOf { requireNotNull(it.value) }
    val extremumSample = excursion.first { requireNotNull(it.value) == extremum } // first max on a tie
    val band = config.plateauBandFraction * (extremum - config.admissionLevel)
    if (band <= 0.0) return unknown(EndpointStatus.UNKNOWN_TOO_FEW_SAMPLES)
    val floor = extremum - band

    // Contiguous runs inside the band. A second run is only a DOUBLE PEAK if the signal dipped more
    // than one band width below the floor between them; shallower dips are plateau noise.
    val runs = mutableListOf<Pair<Int, Int>>()
    var start: Int? = null
    var deepestBetween = Double.MAX_VALUE
    for ((index, sample) in excursion.withIndex()) {
        val value = requireNotNull(sample.value)
        if (value >= floor) {
            if (start == null) {
                if (runs.isNotEmpty() && deepestBetween >= floor - band) {
                    // Shallow dip: the plateau continues rather than restarting.
                    start = runs.removeAt(runs.size - 1).first
                } else {
                    start = index
                }
            }
        } else {
            if (start != null) { runs += start to index - 1; start = null; deepestBetween = value }
            else deepestBetween = minOf(deepestBetween, value)
        }
    }
    if (start != null) runs += start to excursion.size - 1
    if (runs.isEmpty()) return unknown(EndpointStatus.UNKNOWN_TOO_FEW_SAMPLES)
    if (runs.size > 1) {
        return unknown(EndpointStatus.UNKNOWN_DOUBLE_PEAK, runs.map { excursion[it.second].timestampNs })
    }

    val (first, last) = runs.single()
    val arrival = excursion[first].timestampNs
    val departure = excursion[last].timestampNs
    val reversalConfirmed = samples[reversalAt].timestampNs
    return EndpointEstimate(
        crossingNs = crossingNs,
        confirmationNs = confirmationNs,
        extremumNs = extremumSample.timestampNs,
        plateauArrivalNs = arrival,
        estimatedDepartureNs = departure,
        uncertaintyNs = departure - arrival,
        reversalConfirmedNs = reversalConfirmed,
        confirmationDelayNs = reversalConfirmed - departure,
        status = EndpointStatus.RESOLVED,
    )
}
