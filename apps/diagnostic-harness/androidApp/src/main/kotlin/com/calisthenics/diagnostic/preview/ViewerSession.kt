package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.camera.AnalysisFrameTransform
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Paired RGB for synthetic mode (empty for native preview), plus model input. Never persisted. */
data class ViewerFrame(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val modelRgb: ByteArray,
    val analysisTransform: AnalysisFrameTransform? = null,
)
data class ViewerObservation(val frame: ViewerFrame, val landmarks: List<Landmark>, val posePresent: Boolean, val capturedNs: Long)
data class ViewerSnapshot(
    val observation: ViewerObservation?,
    val message: String,
    val submitted: Int,
    val skipped: Int,
    val completed: Int,
    val failure: String?,
    val performance: ViewerPerformance = ViewerPerformance(),
)

/** One prepared frame and one displayed observation, with no queued image backlog or storage API. */
class ViewerSession(
    private val inference: InferencePort,
    private val freshnessNs: Long = 1_500_000_000L,
    private val inferenceTimeoutNs: Long = 5_000_000_000L,
    private val clockNs: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val _snapshots = MutableStateFlow(ViewerSnapshot(null, "Waiting for a frame", 0, 0, 0, null))
    val snapshots: StateFlow<ViewerSnapshot> = _snapshots.asStateFlow()
    private var closed = false
    private var failure: String? = null
    private var pending: Long? = null
    private var pendingSince = 0L
    private var lastBackendMs = -1L
    private var submitted = 0
    private var skipped = 0
    private var completed = 0
    private var latest: ViewerObservation? = null
    private var cameraOffers = 0
    private var performanceCompletions = 0
    private var observationStartedNs: Long? = null
    private var lastCaptureToOfferMs: Double? = null
    private var lastAdmissionMs: Double? = null
    private var lastPreparationMs: Double? = null
    private var lastSubmitGapMs: Double? = null
    private var lastInferenceMs: Double? = null
    private var lastCaptureToResultMs: Double? = null
    private var lastPoseCapturedNs: Long? = null

    init { require(freshnessNs > 0 && inferenceTimeoutNs > 0) }

    fun offer(capturedNs: Long, prepare: () -> ViewerFrame) {
        val offeredAtNs = clockNs()
        val id = synchronized(lock) {
            if (closed || failure != null) return
            cameraOffers++
            observationStartedNs = observationStartedNs ?: offeredAtNs
            if (pending != null || capturedNs < 0 || capturedNs / 1_000_000 <= lastBackendMs) {
                skipped++
                publishLocked(offeredAtNs)
                return
            }
            submitted++.toLong().also {
                pending = it
                pendingSince = capturedNs
                lastBackendMs = capturedNs / 1_000_000
                publishLocked(offeredAtNs)
            }
        }
        val preparationStartedNs = clockNs()
        val frame = try { prepare() } catch (_: Exception) { fail("Frame preparation failed"); return }
        val preparedAtNs = clockNs()
        synchronized(lock) {
            if (closed || pending != id || failure != null) return
            // Contiguous with the stages either side of it: capture -> offer -> preparation start.
            lastCaptureToOfferMs = elapsedMs(offeredAtNs - capturedNs)
            lastAdmissionMs = elapsedMs(preparationStartedNs - offeredAtNs)
            lastPreparationMs = elapsedMs(preparedAtNs - preparationStartedNs)
            publishLocked(preparedAtNs)
        }
        // The controller waits for the source callback to return before disposing inference.
        // Native submission must not block snapshots/stop on the main thread.
        val inferenceStartedNs = clockNs()
        synchronized(lock) {
            if (!closed && pending == id && failure == null) {
                lastSubmitGapMs = elapsedMs(inferenceStartedNs - preparedAtNs)
            }
        }
        try {
            inference.submit(id, frame.modelRgb, capturedNs / 1_000_000) { result ->
                val completedAtNs = clockNs()
                synchronized(lock) {
                    if (closed || failure != null || pending != id) return@synchronized
                    pending = null
                    performanceCompletions++
                    lastInferenceMs = elapsedMs(completedAtNs - inferenceStartedNs)
                    lastCaptureToResultMs = elapsedMs(completedAtNs - capturedNs)
                    when (result) {
                        is InferenceResult.Success -> {
                            completed++
                            lastPoseCapturedNs = capturedNs
                            latest = ViewerObservation(frame, if (result.posePresent) result.normalizedImageLandmarks.orEmpty().toList() else emptyList(), result.posePresent, capturedNs)
                        }
                        is InferenceResult.Failure -> {
                            failure = "Inference failed"
                            latest = null
                        }
                    }
                    publishLocked(completedAtNs)
                }
            }
        } catch (_: Exception) { fail("Inference failed") }
    }

    fun snapshot(nowNs: Long): ViewerSnapshot = synchronized(lock) {
        var changed = false
        if (pending != null && nowNs - pendingSince >= inferenceTimeoutNs) {
            failure = "Inference timed out"
            latest = null
            pending = null
            changed = true
        }
        val stale = latest?.let { nowNs - it.capturedNs >= freshnessNs } ?: false
        if (stale) {
            latest = null
            changed = true
        }
        return@synchronized buildSnapshotLocked(nowNs).also { if (changed) _snapshots.value = it }
    }

    fun fail(message: String) = synchronized(lock) {
        if (!closed) {
            failure = message
            latest = null
            pending = null
            publishLocked(clockNs())
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            latest = null
            pending = null
            publishLocked(clockNs())
        }
    }

    private fun publishLocked(nowNs: Long) {
        _snapshots.value = buildSnapshotLocked(nowNs)
    }

    private fun buildSnapshotLocked(nowNs: Long): ViewerSnapshot = ViewerSnapshot(latest, when {
            failure != null -> failure!!
            closed -> "Stopped"
            latest?.posePresent == true -> "Pose present"
            latest != null -> "No pose"
            completed > 0 -> "Frame stale"
            else -> "Waiting for a frame"
        }, submitted, skipped, completed, failure, performanceLocked(nowNs))

    private fun performanceLocked(nowNs: Long): ViewerPerformance {
        val elapsedNs = observationStartedNs?.let { nowNs - it }?.takeIf { it > 0L }
        return ViewerPerformance(
            cameraOffers = cameraOffers,
            completions = performanceCompletions,
            offerFps = elapsedNs?.let { cameraOffers * 1_000_000_000.0 / it },
            poseFps = elapsedNs?.let { performanceCompletions * 1_000_000_000.0 / it },
            captureToOfferMs = lastCaptureToOfferMs,
            admissionMs = lastAdmissionMs,
            preparationMs = lastPreparationMs,
            submitGapMs = lastSubmitGapMs,
            inferenceMs = lastInferenceMs,
            captureToResultMs = lastCaptureToResultMs,
            poseAgeMs = lastPoseCapturedNs?.let { elapsedMs(nowNs - it) },
        )
    }

    private fun elapsedMs(elapsedNs: Long): Double? = if (elapsedNs >= 0L) elapsedNs / 1_000_000.0 else null
}
