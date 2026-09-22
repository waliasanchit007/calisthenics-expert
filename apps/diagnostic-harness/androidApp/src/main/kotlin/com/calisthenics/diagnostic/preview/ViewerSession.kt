package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.camera.AnalysisFrameTransform
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

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

/**
 * How preparation and inference share frames (ADR-0007).
 *
 * [SERIAL] prepares a frame only when no inference is in flight, so the two stages run one after
 * the other. [OVERLAPPED] prepares every admitted frame while the previous one infers, joined by a
 * one-slot latest-wins mailbox. Exactly one inference is in flight in both.
 */
enum class ViewerPipelineMode { SERIAL, OVERLAPPED }

/** One prepared frame and one displayed observation, with no queued image backlog or storage API. */
class ViewerSession(
    private val inference: InferencePort,
    private val freshnessNs: Long = 1_500_000_000L,
    private val inferenceTimeoutNs: Long = 5_000_000_000L,
    private val clockNs: () -> Long = System::nanoTime,
    val mode: ViewerPipelineMode = ViewerPipelineMode.SERIAL,
) {
    private val lock = Any()

    /** A frame that has been prepared, with the instants its stage timings are measured between. */
    private class Prepared(
        val id: Long,
        val frame: ViewerFrame,
        val capturedNs: Long,
        val offeredAtNs: Long,
        val preparationStartedNs: Long,
        val preparedAtNs: Long,
    )

    /**
     * Every completed observation, exactly once, in capture order: the ANALYSIS feed (ADR-0007
     * decision 2). Unlike [snapshots] it is neither conflated nor filtered for display freshness, so
     * a late result cannot be deleted before analysis sees it.
     */
    private val completionChannel = Channel<ViewerObservation>(COMPLETION_BUFFER)
    val completions: Flow<ViewerObservation> = completionChannel.receiveAsFlow()
    private var completionsDropped = 0
    /** Prepared in OVERLAPPED mode, then replaced in the mailbox by a newer frame before submission. */
    private var superseded = 0
    private var waiting: Prepared? = null
    private var nextFrameId = 0L
    private var lastAcceptedMs = -1L
    /** Native submissions between their claim and the port's return — see [awaitNativeSubmissionsIdle]. */
    private var nativeSubmissions = 0
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

    fun offer(capturedNs: Long, prepare: () -> ViewerFrame) =
        if (mode == ViewerPipelineMode.SERIAL) offerSerial(capturedNs, prepare)
        else offerOverlapped(capturedNs, prepare)

    /** Today's behaviour, unchanged: a frame offered while one is in flight is skipped unprepared. */
    private fun offerSerial(capturedNs: Long, prepare: () -> ViewerFrame) {
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
            nativeSubmissions++
            publishLocked(preparedAtNs)
        }
        submitNative(Prepared(id, frame, capturedNs, offeredAtNs, preparationStartedNs, preparedAtNs))
    }

    /**
     * ADR-0007: every admitted frame is prepared, even while an inference is in flight. It is
     * submitted at once if inference is idle, and otherwise waits in a one-slot mailbox where a newer
     * frame replaces it. There is never a queue, and the waiting frame is always the newest.
     */
    private fun offerOverlapped(capturedNs: Long, prepare: () -> ViewerFrame) {
        val offeredAtNs = clockNs()
        val id = synchronized(lock) {
            if (closed || failure != null) return
            cameraOffers++
            observationStartedNs = observationStartedNs ?: offeredAtNs
            // Acceptance is strictly ordered, so every later submission is too.
            if (capturedNs < 0 || capturedNs / 1_000_000 <= lastAcceptedMs) {
                skipped++
                publishLocked(offeredAtNs)
                return
            }
            lastAcceptedMs = capturedNs / 1_000_000
            nextFrameId++
        }
        val preparationStartedNs = clockNs()
        val frame = try { prepare() } catch (_: Exception) { fail("Frame preparation failed"); return }
        val preparedAtNs = clockNs()
        val prepared = Prepared(id, frame, capturedNs, offeredAtNs, preparationStartedNs, preparedAtNs)
        val now = synchronized(lock) {
            if (closed || failure != null) return
            publishLocked(preparedAtNs)
            if (pending == null) claimLocked(prepared)
            else {
                if (waiting != null) superseded++
                waiting = prepared
                null
            }
        }
        now?.let(::submitNative)
    }

    /**
     * Takes the single in-flight slot for [p], or refuses a frame that is not newer than the last
     * submission. Called only under [lock] and only while open, which is what makes the fence in
     * [awaitNativeSubmissionsIdle] sound: after close the count can only fall.
     */
    private fun claimLocked(p: Prepared): Prepared? {
        if (p.capturedNs / 1_000_000 <= lastBackendMs) { skipped++; return null }
        pending = p.id
        pendingSince = p.capturedNs
        lastBackendMs = p.capturedNs / 1_000_000
        submitted++
        nativeSubmissions++
        return p
    }

    // The controller waits for the source callback to return before disposing inference, and in
    // OVERLAPPED mode also for [awaitNativeSubmissionsIdle]. Native submission must not block
    // snapshots/stop on the main thread.
    private fun submitNative(p: Prepared) {
        val inferenceStartedNs = clockNs()
        try {
            inference.submit(p.id, p.frame.modelRgb, p.capturedNs / 1_000_000) { result ->
                onCompleted(p, inferenceStartedNs, result)
            }
        } catch (_: Exception) {
            // A port that rejects because the session is already closing is not a failure.
            if (synchronized(lock) { !closed }) fail("Inference failed")
        } finally {
            synchronized(lock) { nativeSubmissions-- }
        }
    }

    private fun onCompleted(p: Prepared, inferenceStartedNs: Long, result: InferenceResult) {
        val completedAtNs = clockNs()
        var emitted: ViewerObservation? = null
        val next: Prepared? = synchronized(lock) {
            if (closed || failure != null || pending != p.id) return
            pending = null
            performanceCompletions++
            // All six are published together, for THIS frame, at its completion. Writing the
            // early stages as they happen would let a snapshot taken while the next frame is
            // being prepared mix that frame's first stages with this frame's inference and
            // total — and the five would no longer sum to the total they claim to partition.
            // In OVERLAPPED mode submit_gap also carries the frame's wait in the mailbox.
            lastCaptureToOfferMs = elapsedMs(p.offeredAtNs - p.capturedNs)
            lastAdmissionMs = elapsedMs(p.preparationStartedNs - p.offeredAtNs)
            lastPreparationMs = elapsedMs(p.preparedAtNs - p.preparationStartedNs)
            lastSubmitGapMs = elapsedMs(inferenceStartedNs - p.preparedAtNs)
            lastInferenceMs = elapsedMs(completedAtNs - inferenceStartedNs)
            lastCaptureToResultMs = elapsedMs(completedAtNs - p.capturedNs)
            when (result) {
                is InferenceResult.Success -> {
                    completed++
                    lastPoseCapturedNs = p.capturedNs
                    latest = ViewerObservation(p.frame, if (result.posePresent) result.normalizedImageLandmarks.orEmpty().toList() else emptyList(), result.posePresent, p.capturedNs)
                    emitted = latest
                }
                is InferenceResult.Failure -> {
                    failure = "Inference failed"
                    latest = null
                    waiting = null
                }
            }
            publishLocked(completedAtNs)
            val w = waiting
            waiting = null
            if (w != null && failure == null) claimLocked(w) else null
        }
        emitted?.let { observation ->
            val sent = completionChannel.trySend(observation)
            if (sent.isFailure && !sent.isClosed) synchronized(lock) { completionsDropped++ }
        }
        next?.let(::submitNative)
    }

    /**
     * Blocks until no native submission is between its claim and the port's return, or [timeoutMs]
     * passes. The port reserves its slot under its own monitor but calls the detector outside it, and
     * its dispose closes the detector outside it too; in OVERLAPPED mode a submission can run on the
     * port's callback thread, which the camera-source quiescence the controller already waits for
     * does not cover. Call it after [close], off the main thread, before disposing the port.
     */
    fun awaitNativeSubmissionsIdle(timeoutMs: Long = 2_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (synchronized(lock) { nativeSubmissions == 0 }) return true
            if (System.nanoTime() > deadline) return false
            Thread.sleep(1)
        }
    }

    fun snapshot(nowNs: Long): ViewerSnapshot = synchronized(lock) {
        var changed = false
        if (pending != null && nowNs - pendingSince >= inferenceTimeoutNs) {
            failure = "Inference timed out"
            latest = null
            pending = null
            waiting = null
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
            waiting = null
            publishLocked(clockNs())
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            latest = null
            pending = null
            waiting = null
            completionChannel.close()
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
            pipelineMode = mode.name,
            superseded = superseded,
            completionsDropped = completionsDropped,
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

    private companion object {
        /** Completions ahead of the analysis consumer before one is dropped and counted. */
        const val COMPLETION_BUFFER = 32
    }
}
