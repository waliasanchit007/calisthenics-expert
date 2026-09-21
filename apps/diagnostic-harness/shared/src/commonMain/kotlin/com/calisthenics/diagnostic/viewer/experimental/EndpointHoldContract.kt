package com.calisthenics.diagnostic.viewer.experimental

/**
 * A frozen `minimum*Samples` parameter is a frame-rate-dependent way of writing down a DURATION:
 * "the endpoint must be held", "the inversion must have settled". Read as a count it means
 * different things at different capture rates, which is what the 2026-09-16 decimation study
 * measured — the hanging knee raise fell 6 -> 0 and the handstand push-up 5 -> 0 between 30 fps and
 * 10 fps purely because of it, while the four engines whose count is 2 were untouched.
 *
 * The engines therefore convert each count once, here, and gate on elapsed time thereafter.
 *
 * [REFERENCE_FRAME_INTERVAL_NS] is 30 fps because that is the FASTEST native rate in the frozen
 * corpus (Update 42 measured 23.98-30.00 fps). Converting at the fastest rate yields the shortest
 * duration, which is the most permissive reading available — the one choice that cannot make any
 * engine stricter than it already is on any corpus clip. It is not a claim that every frozen count
 * was tuned against 30 fps footage: dip was tuned at 25 fps and the straight-leg raise at 23.98.
 */
internal const val REFERENCE_FRAME_INTERVAL_NS = 33_333_333L

/** The duration a run of [samples] consecutive frames spans at [REFERENCE_FRAME_INTERVAL_NS]. */
internal fun sampleCountAsDurationNs(samples: Int): Long =
    (samples - 1).toLong() * REFERENCE_FRAME_INTERVAL_NS

/**
 * The sample floor kept alongside the duration, so that one frame can never confirm an endpoint
 * however long the gap before it — the debounce the count rule provided.
 *
 * It is `minOf(2, samples)` rather than a flat 2 so that a config asking for a SINGLE sample still
 * gets a single sample. `HangingLegRaiseConfig` and `HandstandHoldConfig` both permit 1, and
 * raising those to 2 would be a silent behaviour change rather than a change of units.
 */
internal fun endpointSampleFloor(samples: Int): Int = if (samples < 2) samples else 2
