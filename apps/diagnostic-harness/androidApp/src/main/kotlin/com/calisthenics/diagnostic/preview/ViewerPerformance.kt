package com.calisthenics.diagnostic.preview

/**
 * Bounded, RAM-only observations about the current viewer session.
 *
 * The five stage durations partition [captureToResultMs] EXACTLY: the instants they are measured
 * between are contiguous, so no cost can hide between two of them. That is the point — the
 * 2026-09-15 sessions showed 38 ms of inference inside a 171 ms end-to-end latency, and a partition
 * is the only way to say where the other 133 ms goes without guessing.
 *
 * All of them share one clock. `CameraXFrameSource` refuses to bind a camera whose
 * `SENSOR_INFO_TIMESTAMP_SOURCE` is not `REALTIME`, so the sensor timestamp and
 * `SystemClock.elapsedRealtimeNanos` are the same time base and subtracting them is meaningful.
 */
data class ViewerPerformance(
    val cameraOffers: Int = 0,
    val completions: Int = 0,
    val offerFps: Double? = null,
    val poseFps: Double? = null,
    /** `SERIAL` or `OVERLAPPED` (ADR-0007); null only for a performance record with no session. */
    val pipelineMode: String? = null,
    /** OVERLAPPED only: frames prepared and then replaced in the mailbox before submission. */
    val superseded: Int = 0,
    /** Completions the analysis stream could not buffer. Expected to stay 0; counted, not hidden. */
    val completionsDropped: Int = 0,
    /** Sensor capture -> the analyser callback. Camera pipeline latency; we do not control it. */
    val captureToOfferMs: Double? = null,
    /** The analyser callback -> the start of frame preparation. Our own bookkeeping and lock. */
    val admissionMs: Double? = null,
    val preparationMs: Double? = null,
    /** Prepared -> the inference submit call. Post-preparation lock and dispatch. */
    val submitGapMs: Double? = null,
    /** Submit -> the result callback. Includes any native queueing, not only inference. */
    val inferenceMs: Double? = null,
    val captureToResultMs: Double? = null,
    val poseAgeMs: Double? = null,
)
