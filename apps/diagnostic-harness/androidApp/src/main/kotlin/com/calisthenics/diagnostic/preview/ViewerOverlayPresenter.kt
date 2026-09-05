package com.calisthenics.diagnostic.preview

internal data class ViewerOverlay(val observation: ViewerObservation, val alpha: Float, val held: Boolean)

/** Display-only continuity. Never feeds detection, measurements or the raw session snapshot. */
internal class ViewerOverlayPresenter {
    private var lastPose: ViewerObservation? = null

    fun present(observation: ViewerObservation?, nowNs: Long): ViewerOverlay? {
        if (observation?.posePresent == true && observation.landmarks.isNotEmpty()) {
            val age = nowNs - observation.capturedNs
            if (age in 0 until MAX_AGE_NS && observation.capturedNs >= (lastPose?.capturedNs ?: Long.MIN_VALUE)) {
                lastPose = observation
            }
        }
        val pose = lastPose ?: return null
        val age = nowNs - pose.capturedNs
        if (age !in 0 until MAX_AGE_NS) { clear(); return null }
        val alpha = if (age <= FADE_START_NS) 1f
            else (MAX_AGE_NS - age).toFloat() / (MAX_AGE_NS - FADE_START_NS)
        return ViewerOverlay(pose, alpha, observation !== pose)
    }

    fun clear() { lastPose = null }

    private companion object {
        // UX limits only, not biomechanical accuracy or observation-admission thresholds.
        const val FADE_START_NS = 350_000_000L
        const val MAX_AGE_NS = 500_000_000L
    }
}
