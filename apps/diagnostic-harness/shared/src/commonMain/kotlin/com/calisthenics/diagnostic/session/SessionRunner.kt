package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.MachineInferenceFailure
import com.calisthenics.diagnostic.contract.nextBackendTimestampMs

enum class SessionLifecycle {
    RUNNING,
    DRAINING,
    SEALED,
    ASSEMBLING,
    PUBLISHED,
    FAILED,
}

enum class TerminalState {
    EMITTED,
    FAILED,
    TIMED_OUT,
}

class SealedSessionSnapshot internal constructor(
    val counts: SessionCounts,
    val boundedState: BoundedSessionSnapshot,
) {
    val failures = listOfNotNull(boundedState.failure)

    override fun equals(other: Any?): Boolean = other is SealedSessionSnapshot &&
        counts == other.counts && boundedState == other.boundedState

    override fun hashCode(): Int = counts.hashCode() * 31 + boundedState.hashCode()

    override fun toString(): String = "SealedSessionSnapshot(counts=$counts, boundedState=$boundedState)"
}

sealed interface FinalizationResult {
    val sealedSnapshot: SealedSessionSnapshot

    data class Published(override val sealedSnapshot: SealedSessionSnapshot) : FinalizationResult

    data class Failed(
        override val sealedSnapshot: SealedSessionSnapshot,
        val failureCode: String,
        val detail: String,
    ) : FinalizationResult

    /**
     * The session was abandoned before it could be finalized: nothing was published and no
     * finalizer ran.
     *
     * This is the typed outcome of the teardown race. A caller that abandons a run mid-flight — UI
     * teardown while a replay is still submitting frames — leaves the session `FAILED`, and the
     * `stop()`/`finalize()` sequence that follows in the caller's own completion path then has
     * nothing legitimate left to do. Before this existed, that sequence threw
     * `IllegalStateException("session must be sealed before finalization")` out of the run, which is
     * a crash rather than an outcome.
     *
     * It is deliberately distinct from [Failed]: that means a session which genuinely reached
     * finalization and failed there. Conflating the two would report an abandoned run as an export
     * defect and leave a torn-down run indistinguishable from a real storage failure in the
     * evidence. Only the abandonment path produces this; every other route to `FAILED` still
     * surfaces as itself.
     */
    data class Cancelled(
        override val sealedSnapshot: SealedSessionSnapshot,
        val reason: String,
    ) : FinalizationResult
}

fun interface SessionFinalizer {
    fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult
}

/** Optional pre-publication abort hook for finalizers that own durable staging state. */
fun interface SessionAborter {
    fun abort(snapshot: SealedSessionSnapshot, reason: String)
}

data class SessionRunnerSnapshot(
    val lifecycle: SessionLifecycle,
    val transitionHistory: List<SessionLifecycle>,
    val drainDeadlineNs: Long?,
    val counts: SessionCounts,
    val boundedState: BoundedSessionSnapshot,
    val failures: List<MachineInferenceFailure>,
    val diagnosticDropsAfterSeal: Long,
    val sealedSnapshot: SealedSessionSnapshot?,
    val finalizationResult: FinalizationResult?,
)

class SessionRunner(
    configuration: SessionConfiguration,
    private val inference: InferencePort,
    private val deadlineScheduler: DeadlineScheduler,
    private val drainTimeoutNs: Long,
    private val finalizer: SessionFinalizer,
    private val dispatcher: SessionDispatcher = DirectSessionDispatcher,
    poseRecordSink: EmittedRecordSink = (finalizer as? EmittedRecordSink) ?: EmittedRecordSink {},
) {
    private sealed interface RunnerEvent {
        data class Submit(
            val frameIndex: Long,
            val captureTimestampNs: Long,
            val inferenceStartNs: Long,
            val input: ByteArray,
        ) : RunnerEvent

        data class Skip(
            val frameIndex: Long,
            val captureTimestampNs: Long,
            val reason: SkipReason,
        ) : RunnerEvent

        data class Callback(val submissionIndex: Long, val result: InferenceResult) : RunnerEvent
        data class Cancel(val submissionIndex: Long) : RunnerEvent
        data object DeadlineExpired : RunnerEvent
    }

    private val sessionConfiguration = configuration
    private val ledger = SessionLedger(sessionConfiguration, dispatcher, poseRecordSink)
    private var pendingSubmissionIndex: Long? = null
    private val transitionHistory = mutableListOf(SessionLifecycle.RUNNING)
    private var lifecycle = SessionLifecycle.RUNNING
    private var nextSubmissionIndex = 0L
    private var previousBackendTimestampMs: Long? = null
    private var drainDeadlineNs: Long? = null
    private var deadlineHandle: Disposable? = null
    private var diagnosticDropsAfterSeal = 0L
    private var sealedSnapshot: SealedSessionSnapshot? = null
    private var finalizationResult: FinalizationResult? = null
    private var inferenceDisposed = false
    private var abandonedBeforeFinalization = false

    init {
        require(drainTimeoutNs >= 0) { "drain timeout must be non-negative" }
    }

    fun submit(
        frameIndex: Long,
        captureTimestampNs: Long,
        inferenceStartNs: Long,
        input: ByteArray,
    ): Long? = admit(RunnerEvent.Submit(frameIndex, captureTimestampNs, inferenceStartNs, input)) {
        require(inferenceStartNs >= captureTimestampNs) {
            "inference must start at or after capture"
        }
        if (lifecycle != SessionLifecycle.RUNNING) {
            ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, imageWidth(), imageHeight()))
            ledger.accept(SessionEvent.FrameSkipped(SkipReason.SESSION_STOPPING))
            return@admit null
        }
        if (pendingSubmissionIndex != null) {
            ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, imageWidth(), imageHeight()))
            ledger.accept(SessionEvent.FrameSkipped(SkipReason.BACKPRESSURE))
            return@admit null
        }

        val submissionIndex = nextSubmissionIndex
        val backendTimestampMs = nextBackendTimestampMs(captureTimestampNs, previousBackendTimestampMs)
        ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, imageWidth(), imageHeight()))
        ledger.accept(SessionEvent.InferenceSubmitted(submissionIndex, inferenceStartNs))
        nextSubmissionIndex++
        previousBackendTimestampMs = backendTimestampMs
        pendingSubmissionIndex = submissionIndex
        try {
            inference.submit(submissionIndex, input.copyOf(), backendTimestampMs) { result ->
                postInferenceResult(submissionIndex, result)
            }
        } catch (error: Throwable) {
            classifyFailure(
                submissionIndex,
                InferenceResult.Failure(
                    failureSource = "backend",
                    classification = "backend_call_failed",
                    detail = error.message,
                ),
            )
        }
        submissionIndex
    }

    fun skip(frameIndex: Long, captureTimestampNs: Long, reason: SkipReason): Unit =
        admit(RunnerEvent.Skip(frameIndex, captureTimestampNs, reason)) {
            ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, imageWidth(), imageHeight()))
            ledger.accept(SessionEvent.FrameSkipped(reason))
        }.let { }

    fun stop(nowNs: Long): SessionRunnerSnapshot = dispatcher.dispatch {
        when (lifecycle) {
            SessionLifecycle.RUNNING -> {
                transitionTo(SessionLifecycle.DRAINING)
                val deadline = nowNs + drainTimeoutNs
                drainDeadlineNs = deadline
                deadlineHandle = deadlineScheduler.schedule(deadline) { postDeadline() }
                sealIfQuiescent()
            }

            SessionLifecycle.DRAINING -> Unit
            SessionLifecycle.SEALED,
            SessionLifecycle.ASSEMBLING,
            SessionLifecycle.PUBLISHED,
            SessionLifecycle.FAILED,
            -> Unit
        }
        snapshotInternal()
    }

    fun postDeadline(): Unit = admit(RunnerEvent.DeadlineExpired) { expireDeadline() }.let { }

    /** Conservative dispatcher-confined view; the port's own slot remains authoritative. */
    fun hasPendingInference(): Boolean = dispatcher.dispatch { pendingSubmissionIndex != null }

    fun cancel(submissionIndex: Long): Unit = admit(RunnerEvent.Cancel(submissionIndex)) {
        check(submissionIndex == pendingSubmissionIndex) { "submission is not pending" }
        inference.cancel(submissionIndex)
        classifyFailure(
            submissionIndex,
            InferenceResult.Failure("harness", "session_teardown_timeout"),
        )
    }.let { }

    fun postInferenceResult(submissionIndex: Long, result: InferenceResult): Unit =
        admit(RunnerEvent.Callback(submissionIndex, result)) { classifyResult(submissionIndex, result) }.let { }

    fun finalize(): FinalizationResult = dispatcher.dispatch {
        finalizationResult?.let { return@dispatch it }
        // The teardown race, resolved as an outcome rather than a crash. A caller that abandoned
        // this run mid-flight still runs its own stop/finalize completion path, and this is the
        // only lifecycle state in which that path has nothing legitimate to do: the session is
        // already terminal, its snapshot is already sealed, and publishing it would export a
        // session the owner gave up on. Decided inside the dispatcher, so the decision cannot race
        // an abandonment arriving from another thread; and scoped strictly to abandonment, so a
        // genuine internal failure still surfaces through the check below rather than being
        // relabelled as a cancellation.
        if (lifecycle == SessionLifecycle.FAILED && abandonedBeforeFinalization) {
            val snapshot = checkNotNull(sealedSnapshot) { "an abandoned session must carry a sealed snapshot" }
            return@dispatch FinalizationResult.Cancelled(snapshot, ABANDONED_BEFORE_FINALIZATION)
                .also { finalizationResult = it }
        }
        check(lifecycle == SessionLifecycle.SEALED) { "session must be sealed before finalization" }
        val snapshot = sealedSnapshot ?: seal()
        transitionTo(SessionLifecycle.ASSEMBLING)
        val result = try {
            finalizer.finalize(snapshot)
        } catch (error: Throwable) {
            FinalizationResult.Failed(snapshot, "finalization_failed", error.message ?: "finalizer failed")
        }
        finalizationResult = result
        transitionTo(if (result is FinalizationResult.Published) SessionLifecycle.PUBLISHED else SessionLifecycle.FAILED)
        result
    }

    fun snapshot(): SessionRunnerSnapshot = dispatcher.dispatch { snapshotInternal() }

    /**
     * Explicit, idempotent abandonment: forces a session that has not already reached a terminal
     * state (`PUBLISHED` or `FAILED`) into `FAILED`, via the same [failSession] path an unexpected
     * internal error already uses. This is for a run the caller is giving up on outside the normal
     * stop/drain/finalize sequence — e.g. tearing down a run whose owned resources must not be
     * leaked even though it never reached completion. Disposing the owned inference happens once,
     * from the single [transitionTo] terminal-transition point shared with [finalize]; see
     * [disposeInferenceIfOwned]. Safe to call repeatedly, including after the session has already
     * reached `FAILED` on its own, or after it has already `PUBLISHED` (a no-op in that case: a
     * successfully published session can never be retroactively failed).
     *
     * An abandonment that actually forced the session terminal is remembered, so a later
     * [finalize] on the caller's normal completion path returns
     * [FinalizationResult.Cancelled] instead of throwing. The flag is set only on that branch: a
     * session already `FAILED` for its own reasons is not retroactively reclassified as abandoned,
     * so a real failure keeps reporting itself as one.
     */
    fun abandon(): SessionRunnerSnapshot = dispatcher.dispatch {
        if (lifecycle != SessionLifecycle.PUBLISHED && lifecycle != SessionLifecycle.FAILED) {
            abandonedBeforeFinalization = true
            failSession()
        }
        snapshotInternal()
    }

    private fun <T> admit(event: RunnerEvent, handler: () -> T): T? = dispatcher.dispatch {
        if (lifecycle == SessionLifecycle.SEALED || lifecycle == SessionLifecycle.ASSEMBLING ||
            lifecycle == SessionLifecycle.PUBLISHED || lifecycle == SessionLifecycle.FAILED
        ) {
            diagnosticDropsAfterSeal++
            return@dispatch null
        }
        try {
            handler()
        } catch (error: Throwable) {
            failSession()
            throw error
        }
    }

    private fun classifyResult(submissionIndex: Long, result: InferenceResult) {
        when (result) {
            is InferenceResult.Success -> {
                check(submissionIndex == pendingSubmissionIndex) { "submission has no pending inference call" }
                ledger.accept(
                    SessionEvent.InferenceSucceeded(
                        submissionIndex = submissionIndex,
                        inferenceEndNs = result.inferenceEndNs,
                        posePresent = result.posePresent,
                        normalizedImageLandmarks = result.normalizedImageLandmarks,
                        worldLandmarks = result.worldLandmarks,
                    ),
                )
                pendingSubmissionIndex = null
            }

            is InferenceResult.Failure -> classifyFailure(submissionIndex, result)
        }
        sealIfQuiescent()
    }

    private fun classifyFailure(submissionIndex: Long, result: InferenceResult.Failure) {
        check(submissionIndex == pendingSubmissionIndex) { "submission has no pending inference call" }
        ledger.accept(
            SessionEvent.InferenceFailed(
                submissionIndex = submissionIndex,
                failureSource = result.failureSource,
                classification = result.classification,
                sourceErrorCode = result.sourceErrorCode,
                detail = result.detail,
            ),
        )
        pendingSubmissionIndex = null
        failSession()
    }

    private fun expireDeadline() {
        if (lifecycle != SessionLifecycle.DRAINING) return
        pendingSubmissionIndex?.let { submissionIndex ->
            inference.cancel(submissionIndex)
            classifyFailure(
                submissionIndex,
                InferenceResult.Failure("harness", "session_teardown_timeout"),
            )
        }
        sealIfQuiescent()
    }

    private fun sealIfQuiescent() {
        if (lifecycle == SessionLifecycle.DRAINING && pendingSubmissionIndex == null) {
            deadlineHandle?.dispose()
            deadlineHandle = null
            ledger.accept(SessionEvent.StopRequested)
            seal()
        }
    }

    private fun seal(): SealedSessionSnapshot {
        sealedSnapshot?.let { return it }
        check(pendingSubmissionIndex == null) { "cannot seal with pending submissions" }
        val ledgerSnapshot = ledger.snapshot()
        return SealedSessionSnapshot(
            counts = ledgerSnapshot.counts,
            boundedState = ledgerSnapshot.boundedState,
        ).also {
            sealedSnapshot = it
            if (lifecycle == SessionLifecycle.DRAINING) transitionTo(SessionLifecycle.SEALED)
        }
    }

    private fun failSession() {
        if (sealedSnapshot == null) {
            val ledgerSnapshot = ledger.snapshot()
            sealedSnapshot = SealedSessionSnapshot(
                counts = ledgerSnapshot.counts,
                boundedState = ledgerSnapshot.boundedState,
            )
        }
        runCatching {
            (finalizer as? SessionAborter)?.abort(checkNotNull(sealedSnapshot), "session_failed")
        }
        if (!abandonedBeforeFinalization && finalizationResult == null) {
            finalizationResult = FinalizationResult.Failed(
                sealedSnapshot = checkNotNull(sealedSnapshot),
                failureCode = "session_aborted",
                detail = "session aborted before publication",
            )
        }
        if (lifecycle != SessionLifecycle.FAILED) transitionTo(SessionLifecycle.FAILED)
    }

    private fun transitionTo(next: SessionLifecycle) {
        if (lifecycle == next) return
        check(transitionAllowed(lifecycle, next)) { "invalid lifecycle transition $lifecycle -> $next" }
        lifecycle = next
        transitionHistory += next
        if (next == SessionLifecycle.PUBLISHED || next == SessionLifecycle.FAILED) {
            disposeInferenceIfOwned()
        }
    }

    /**
     * The single terminal-transition binding point: successful publication and every path into
     * `FAILED` (an internal error via [failSession], a finalizer failure, or explicit [abandon])
     * all pass through [transitionTo], so disposal is guaranteed exactly once regardless of which
     * terminal path a session actually takes. [inference] is disposed only when it implements
     * [Disposable] — a plain [InferencePort] implementation is completely unaffected.
     */
    private fun disposeInferenceIfOwned() {
        if (inferenceDisposed) return
        inferenceDisposed = true
        (inference as? Disposable)?.dispose()
    }

    private fun transitionAllowed(current: SessionLifecycle, next: SessionLifecycle): Boolean = when (current) {
        SessionLifecycle.RUNNING -> next == SessionLifecycle.DRAINING || next == SessionLifecycle.FAILED
        SessionLifecycle.DRAINING -> next == SessionLifecycle.SEALED || next == SessionLifecycle.FAILED
        SessionLifecycle.SEALED -> next == SessionLifecycle.ASSEMBLING || next == SessionLifecycle.FAILED
        SessionLifecycle.ASSEMBLING -> next == SessionLifecycle.PUBLISHED || next == SessionLifecycle.FAILED
        SessionLifecycle.PUBLISHED,
        SessionLifecycle.FAILED,
        -> false
    }

    private fun imageWidth(): Int = sessionConfiguration.imageWidth

    private fun imageHeight(): Int = sessionConfiguration.imageHeight

    companion object {
        /** The stable reason recorded on a [FinalizationResult.Cancelled] produced by [abandon]. */
        const val ABANDONED_BEFORE_FINALIZATION: String = "session_abandoned_before_finalization"
    }

    private fun snapshotInternal(): SessionRunnerSnapshot {
        val ledgerSnapshot = ledger.snapshot()
        return SessionRunnerSnapshot(
            lifecycle = lifecycle,
            transitionHistory = transitionHistory.toList(),
            drainDeadlineNs = drainDeadlineNs,
            counts = sealedSnapshot?.counts ?: ledgerSnapshot.counts,
            boundedState = sealedSnapshot?.boundedState ?: ledgerSnapshot.boundedState,
            failures = sealedSnapshot?.failures ?: ledgerSnapshot.failures,
            diagnosticDropsAfterSeal = diagnosticDropsAfterSeal,
            sealedSnapshot = sealedSnapshot,
            finalizationResult = finalizationResult,
        )
    }
}
