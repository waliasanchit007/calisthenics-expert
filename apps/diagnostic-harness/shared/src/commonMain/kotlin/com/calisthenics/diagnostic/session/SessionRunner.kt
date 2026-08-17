package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.MachineInferenceFailure
import com.calisthenics.diagnostic.contract.RawPoseFrame
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
    records: List<RawPoseFrame>,
    failures: List<MachineInferenceFailure>,
    terminalMap: Map<Long, TerminalState>,
) {
    val records: List<RawPoseFrame> = records.toList()
    val failures: List<MachineInferenceFailure> = failures.toList()
    val terminalMap: Map<Long, TerminalState> = terminalMap.toMap()

    override fun equals(other: Any?): Boolean = other is SealedSessionSnapshot &&
        counts == other.counts && records == other.records && failures == other.failures && terminalMap == other.terminalMap

    override fun hashCode(): Int = (((counts.hashCode() * 31) + records.hashCode()) * 31 + failures.hashCode()) * 31 + terminalMap.hashCode()

    override fun toString(): String = "SealedSessionSnapshot(counts=$counts, records=$records, failures=$failures, terminalMap=$terminalMap)"
}

sealed interface FinalizationResult {
    val sealedSnapshot: SealedSessionSnapshot

    data class Published(override val sealedSnapshot: SealedSessionSnapshot) : FinalizationResult

    data class Failed(
        override val sealedSnapshot: SealedSessionSnapshot,
        val failureCode: String,
        val detail: String,
    ) : FinalizationResult
}

fun interface SessionFinalizer {
    fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult
}

data class SessionRunnerSnapshot(
    val lifecycle: SessionLifecycle,
    val transitionHistory: List<SessionLifecycle>,
    val drainDeadlineNs: Long?,
    val counts: SessionCounts,
    val records: List<RawPoseFrame>,
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
    private val ledger = SessionLedger(sessionConfiguration, dispatcher)
    private val pendingSubmissionIndices = linkedSetOf<Long>()
    private val terminalStates = linkedMapOf<Long, TerminalState>()
    private val transitionHistory = mutableListOf(SessionLifecycle.RUNNING)
    private var lifecycle = SessionLifecycle.RUNNING
    private var nextSubmissionIndex = 0L
    private var previousBackendTimestampMs: Long? = null
    private var drainDeadlineNs: Long? = null
    private var deadlineHandle: Disposable? = null
    private var diagnosticDropsAfterSeal = 0L
    private var sealedSnapshot: SealedSessionSnapshot? = null
    private var finalizationResult: FinalizationResult? = null

    init {
        require(drainTimeoutNs >= 0) { "drain timeout must be non-negative" }
    }

    fun submit(
        frameIndex: Long,
        captureTimestampNs: Long,
        inferenceStartNs: Long,
        input: ByteArray,
    ): Long? = admit(RunnerEvent.Submit(frameIndex, captureTimestampNs, inferenceStartNs, input.copyOf())) {
        require(inferenceStartNs >= captureTimestampNs) {
            "inference must start at or after capture"
        }
        if (lifecycle != SessionLifecycle.RUNNING) {
            ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, imageWidth(), imageHeight()))
            ledger.accept(SessionEvent.FrameSkipped(SkipReason.SESSION_STOPPING))
            return@admit null
        }

        val submissionIndex = nextSubmissionIndex
        val backendTimestampMs = nextBackendTimestampMs(captureTimestampNs, previousBackendTimestampMs)
        ledger.accept(SessionEvent.FrameReceived(frameIndex, captureTimestampNs, imageWidth(), imageHeight()))
        ledger.accept(SessionEvent.InferenceSubmitted(submissionIndex, inferenceStartNs))
        nextSubmissionIndex++
        previousBackendTimestampMs = backendTimestampMs
        pendingSubmissionIndices += submissionIndex
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

    fun cancel(submissionIndex: Long): Unit = admit(RunnerEvent.Cancel(submissionIndex)) {
        check(submissionIndex in pendingSubmissionIndices) { "submission is not pending" }
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
                check(submissionIndex in pendingSubmissionIndices) { "submission has no pending inference call" }
                ledger.accept(
                    SessionEvent.InferenceSucceeded(
                        submissionIndex = submissionIndex,
                        inferenceEndNs = result.inferenceEndNs,
                        posePresent = result.posePresent,
                        normalizedImageLandmarks = result.normalizedImageLandmarks,
                        worldLandmarks = result.worldLandmarks,
                    ),
                )
                pendingSubmissionIndices -= submissionIndex
                terminalStates[submissionIndex] = TerminalState.EMITTED
            }

            is InferenceResult.Failure -> classifyFailure(submissionIndex, result)
        }
        sealIfQuiescent()
    }

    private fun classifyFailure(submissionIndex: Long, result: InferenceResult.Failure) {
        check(submissionIndex in pendingSubmissionIndices) { "submission has no pending inference call" }
        ledger.accept(
            SessionEvent.InferenceFailed(
                submissionIndex = submissionIndex,
                failureSource = result.failureSource,
                classification = result.classification,
                sourceErrorCode = result.sourceErrorCode,
                detail = result.detail,
            ),
        )
        pendingSubmissionIndices -= submissionIndex
        terminalStates[submissionIndex] = if (result.classification == "session_teardown_timeout") {
            TerminalState.TIMED_OUT
        } else {
            TerminalState.FAILED
        }
        sealIfQuiescent()
    }

    private fun expireDeadline() {
        if (lifecycle != SessionLifecycle.DRAINING) return
        pendingSubmissionIndices.toList().forEach { submissionIndex ->
            classifyFailure(
                submissionIndex,
                InferenceResult.Failure("harness", "session_teardown_timeout"),
            )
        }
        sealIfQuiescent()
    }

    private fun sealIfQuiescent() {
        if (lifecycle == SessionLifecycle.DRAINING && pendingSubmissionIndices.isEmpty()) {
            deadlineHandle?.dispose()
            deadlineHandle = null
            ledger.accept(SessionEvent.StopRequested)
            seal()
        }
    }

    private fun seal(): SealedSessionSnapshot {
        sealedSnapshot?.let { return it }
        check(pendingSubmissionIndices.isEmpty()) { "cannot seal with pending submissions" }
        val ledgerSnapshot = ledger.snapshot()
        return SealedSessionSnapshot(
            counts = ledgerSnapshot.counts,
            records = ledgerSnapshot.records,
            failures = ledgerSnapshot.failures,
            terminalMap = terminalStates,
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
                records = ledgerSnapshot.records,
                failures = ledgerSnapshot.failures,
                terminalMap = terminalStates,
            )
        }
        if (lifecycle != SessionLifecycle.FAILED) transitionTo(SessionLifecycle.FAILED)
    }

    private fun transitionTo(next: SessionLifecycle) {
        if (lifecycle == next) return
        check(transitionAllowed(lifecycle, next)) { "invalid lifecycle transition $lifecycle -> $next" }
        lifecycle = next
        transitionHistory += next
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

    private fun snapshotInternal(): SessionRunnerSnapshot {
        val ledgerSnapshot = ledger.snapshot()
        return SessionRunnerSnapshot(
            lifecycle = lifecycle,
            transitionHistory = transitionHistory.toList(),
            drainDeadlineNs = drainDeadlineNs,
            counts = sealedSnapshot?.counts ?: ledgerSnapshot.counts,
            records = sealedSnapshot?.records ?: ledgerSnapshot.records,
            failures = sealedSnapshot?.failures ?: ledgerSnapshot.failures,
            diagnosticDropsAfterSeal = diagnosticDropsAfterSeal,
            sealedSnapshot = sealedSnapshot,
            finalizationResult = finalizationResult,
        )
    }
}
