package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult

/** Owned upright RGB image and its padded model input. Never persisted. */
data class ViewerFrame(val rgb: ByteArray, val width: Int, val height: Int, val modelRgb: ByteArray)
data class ViewerObservation(val frame: ViewerFrame, val landmarks: List<Landmark>, val posePresent: Boolean, val capturedNs: Long)
data class ViewerSnapshot(
    val observation: ViewerObservation?,
    val message: String,
    val submitted: Int,
    val skipped: Int,
    val completed: Int,
    val failure: String?,
)

/** One prepared frame and one displayed observation, with no queued image backlog or storage API. */
class ViewerSession(
    private val inference: InferencePort,
    private val freshnessNs: Long = 1_500_000_000L,
    private val inferenceTimeoutNs: Long = 5_000_000_000L,
) {
    private val lock = Any()
    private var closed = false
    private var failure: String? = null
    private var pending: Long? = null
    private var pendingSince = 0L
    private var lastBackendMs = -1L
    private var submitted = 0
    private var skipped = 0
    private var completed = 0
    private var latest: ViewerObservation? = null

    init { require(freshnessNs > 0 && inferenceTimeoutNs > 0) }

    fun offer(capturedNs: Long, prepare: () -> ViewerFrame) {
        val id = synchronized(lock) {
            if (closed || failure != null) return
            if (pending != null || capturedNs < 0 || capturedNs / 1_000_000 <= lastBackendMs) {
                skipped++
                return
            }
            submitted++.toLong().also { pending = it; pendingSince = capturedNs; lastBackendMs = capturedNs / 1_000_000 }
        }
        val frame = try { prepare() } catch (_: Exception) { fail("Frame preparation failed"); return }
        synchronized(lock) {
            if (closed || pending != id || failure != null) return
        }
        // The controller waits for the source callback to return before disposing inference.
        // Native submission must not block snapshots/stop on the main thread.
        try {
            inference.submit(id, frame.modelRgb, capturedNs / 1_000_000) { result ->
                synchronized(lock) {
                    if (closed || failure != null || pending != id) return@synchronized
                    pending = null
                    when (result) {
                        is InferenceResult.Success -> {
                            completed++
                            latest = ViewerObservation(frame, if (result.posePresent) result.normalizedImageLandmarks.orEmpty().toList() else emptyList(), result.posePresent, capturedNs)
                        }
                        is InferenceResult.Failure -> fail("Inference failed")
                    }
                }
            }
        } catch (_: Exception) { fail("Inference failed") }
    }

    fun snapshot(nowNs: Long): ViewerSnapshot = synchronized(lock) {
        if (pending != null && nowNs - pendingSince >= inferenceTimeoutNs) fail("Inference timed out")
        val stale = latest?.let { nowNs - it.capturedNs >= freshnessNs } ?: false
        if (stale) latest = null
        ViewerSnapshot(latest, when {
            failure != null -> failure!!
            closed -> "Stopped"
            latest?.posePresent == true -> "Pose present"
            latest != null -> "No pose"
            completed > 0 -> "Frame stale"
            else -> "Waiting for a frame"
        }, submitted, skipped, completed, failure)
    }

    fun fail(message: String) = synchronized(lock) {
        if (!closed) { failure = message; latest = null; pending = null }
    }

    fun close() = synchronized(lock) { closed = true; latest = null; pending = null }
}
