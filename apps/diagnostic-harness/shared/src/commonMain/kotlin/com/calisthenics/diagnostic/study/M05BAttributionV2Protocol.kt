package com.calisthenics.diagnostic.study

object M05BAttributionV2Protocol {
    const val VERSION = "2.0.0"
    val TARGET_CADENCES_FPS = listOf(10, 15, 30)
    const val SOURCE_WARMUP_CALLBACKS = 30
    const val SOURCE_MEASURED_CALLBACKS = 300
    const val BACKEND_WARMUP_COMPLETIONS = 20
    const val BACKEND_WARMUP_ELAPSED_NS = 2_000_000_000L
    const val BACKEND_MEASURED_COMPLETIONS = 100
    const val ARM_CAP_NS = 60_000_000_000L
    const val COOLDOWN_SAMPLE_NS = 1_000_000_000L
    const val COOLDOWN_MAX_NS = 600_000_000_000L
    val REPLICATE_ORDERS = listOf(listOf(10, 15, 30), listOf(30, 15, 10))
    val TERMINAL_CLASSES = listOf(
        "admitted_completed",
        "admitted_inference_failed",
        "cadence_only",
        "backpressure_only",
        "cadence_and_backpressure",
        "preprocessing_failed",
        "session_stopping",
        "admission_state_divergence",
    )
}
