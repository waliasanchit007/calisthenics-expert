package com.calisthenics.diagnostic.viewer.experimental

/** Small shared phase machine for selected elbow-driven cycle experiments. */
internal enum class ElbowCyclePhase { WAITING, SUPPORTED, LOWERING, LOWERED, RAISING, UNCERTAIN }

/**
 * [onObservation] returns the timestamp at which a completed cycle's final endpoint was FIRST
 * satisfied, or null when no cycle completed on this observation.
 *
 * That is deliberately not the timestamp of the frame that returns it. Confirmation needs the
 * endpoint to be HELD for `minimumEndpointHoldNs`, so the confirming frame is always at least that
 * far after the event itself. Reporting the confirming frame makes every event systematically late
 * by that much, purely as an artefact of debouncing.
 * `HangingLegRaiseExperiment` has always recorded the first-endpoint instant; this brings the five
 * engines that share this machine into line with it, so "the event timestamp" means one thing.
 */
internal class ElbowCycleTiming(minimumEndpointSamples: Int) {
    /** The count read as the duration it expresses; see [sampleCountAsDurationNs]. */
    private val minimumEndpointHoldNs = sampleCountAsDurationNs(minimumEndpointSamples)
    private val endpointSampleFloor = endpointSampleFloor(minimumEndpointSamples)
    var phase: ElbowCyclePhase = ElbowCyclePhase.WAITING
        private set
    var count: Int = 0
        private set

    private var endpointSamples = 0
    private var endpointStartNs: Long? = null

    /**
     * True once the current run both spans [minimumEndpointHoldNs] and clears [endpointSampleFloor].
     */
    private fun endpointHeld(timestampNs: Long): Boolean {
        val start = endpointStartNs ?: return false
        return endpointSamples >= endpointSampleFloor && timestampNs - start >= minimumEndpointHoldNs
    }

    /** Counts this sample towards the current endpoint run, remembering when the run began. */
    private fun noteEndpoint(timestampNs: Long) {
        if (endpointSamples == 0) endpointStartNs = timestampNs
        endpointSamples++
    }

    fun onObservation(timestampNs: Long, isSupported: Boolean, isLowered: Boolean): Long? {
        if (phase == ElbowCyclePhase.UNCERTAIN) {
            phase = ElbowCyclePhase.WAITING
            endpointSamples = 0
            endpointStartNs = null
        }

        var eventNs: Long? = null
        when (phase) {
            ElbowCyclePhase.WAITING,
            ElbowCyclePhase.UNCERTAIN,
            -> {
                if (isSupported) {
                    noteEndpoint(timestampNs)
                    if (endpointHeld(timestampNs)) {
                        phase = ElbowCyclePhase.SUPPORTED
                    }
                } else endpointSamples = 0
            }
            ElbowCyclePhase.SUPPORTED -> {
                endpointSamples = 0
                phase = when {
                    isSupported -> ElbowCyclePhase.SUPPORTED
                    isLowered -> ElbowCyclePhase.WAITING
                    else -> ElbowCyclePhase.LOWERING
                }
            }
            ElbowCyclePhase.LOWERING -> {
                phase = when {
                    isLowered -> {
                        noteEndpoint(timestampNs)
                        if (endpointHeld(timestampNs)) {
                            endpointSamples = 0
                            ElbowCyclePhase.LOWERED
                        } else ElbowCyclePhase.LOWERING
                    }
                    isSupported -> {
                        endpointSamples = 0
                        ElbowCyclePhase.SUPPORTED
                    }
                    else -> {
                        endpointSamples = 0
                        ElbowCyclePhase.LOWERING
                    }
                }
            }
            ElbowCyclePhase.LOWERED -> {
                phase = when {
                    isLowered -> ElbowCyclePhase.LOWERED
                    isSupported -> {
                        endpointSamples = 0
                        ElbowCyclePhase.WAITING
                    }
                    else -> {
                        endpointSamples = 0
                        ElbowCyclePhase.RAISING
                    }
                }
            }
            ElbowCyclePhase.RAISING -> {
                phase = when {
                    isSupported -> {
                        noteEndpoint(timestampNs)
                        if (endpointHeld(timestampNs)) {
                            count++
                            // The event is where the run STARTED, not where it was confirmed.
                            eventNs = endpointStartNs
                            endpointSamples = 0
                            ElbowCyclePhase.SUPPORTED
                        } else ElbowCyclePhase.RAISING
                    }
                    isLowered -> {
                        endpointSamples = 0
                        ElbowCyclePhase.LOWERED
                    }
                    else -> {
                        endpointSamples = 0
                        ElbowCyclePhase.RAISING
                    }
                }
            }
        }
        return eventNs
    }

    fun invalidate() {
        phase = ElbowCyclePhase.UNCERTAIN
        endpointSamples = 0
        endpointStartNs = null
    }
}

internal fun ElbowCyclePhase.toDipPhase(): DipPhase = DipPhase.valueOf(name)
