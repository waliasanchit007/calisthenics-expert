package com.calisthenics.diagnostic.preview

/** Bounded, RAM-only observations about the current viewer session. */
data class ViewerPerformance(
    val cameraOffers: Int = 0,
    val completions: Int = 0,
    val offerFps: Double? = null,
    val poseFps: Double? = null,
    val preparationMs: Double? = null,
    val inferenceMs: Double? = null,
    val captureToResultMs: Double? = null,
    val poseAgeMs: Double? = null,
)
