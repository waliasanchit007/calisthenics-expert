package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.lang.reflect.InvocationTargetException

class SessionRunnerTest {
    @Test
    fun lifecycleIsMonotonicAndOnlyAdmissionChecksTheFence() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val finalizer = RecordingFinalizer()
        val runner = runner(scheduler, inference, finalizer)

        val submission = runner.submit(frameIndex = 0, captureTimestampNs = 100, inferenceStartNs = 110, input = byteArrayOf(1))!!
        assertEquals(SessionLifecycle.RUNNING, runner.snapshot().lifecycle)

        runner.stop(nowNs = 200)
        assertEquals(SessionLifecycle.DRAINING, runner.snapshot().lifecycle)
        inference.complete(submission, InferenceResult.Success(inferenceEndNs = 210, posePresent = true))
        assertEquals(SessionLifecycle.SEALED, runner.snapshot().lifecycle)

        val beforeDrop = runner.snapshot()
        runner.submit(frameIndex = 1, captureTimestampNs = 220, inferenceStartNs = 230, input = byteArrayOf(2))
        assertEquals(beforeDrop.sealedSnapshot, runner.snapshot().sealedSnapshot)
        assertEquals(1, runner.snapshot().diagnosticDropsAfterSeal)

        scheduler.advanceTo(1_200)
        assertEquals(SessionLifecycle.SEALED, runner.snapshot().lifecycle)
        val result = runner.finalize()
        assertEquals(SessionLifecycle.PUBLISHED, runner.snapshot().lifecycle)
        assertEquals(result.sealedSnapshot, finalizer.calls.single())
        assertEquals(
            listOf(SessionLifecycle.RUNNING, SessionLifecycle.DRAINING, SessionLifecycle.SEALED, SessionLifecycle.ASSEMBLING, SessionLifecycle.PUBLISHED),
            runner.snapshot().transitionHistory,
        )
    }

    @Test
    fun deadlineIsPostedAndAtDeadlineOrderingDeterminesAdmission() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val runner = runner(scheduler, inference, RecordingFinalizer())
        val first = runner.submit(0, 100, 110, byteArrayOf(1))!!
        runner.stop(nowNs = 200)

        scheduler.advanceTo(1_200)
        assertEquals(SessionLifecycle.SEALED, runner.snapshot().lifecycle)
        assertEquals(TerminalState.TIMED_OUT, runner.snapshot().sealedSnapshot!!.terminalMap[first])
        val sealedCounts = runner.snapshot().sealedSnapshot!!.counts
        inference.complete(first, InferenceResult.Success(1_201, posePresent = true))
        assertEquals(sealedCounts, runner.snapshot().sealedSnapshot!!.counts)
        assertEquals(1, runner.snapshot().diagnosticDropsAfterSeal)

        val callbackFirstScheduler = VirtualDeadlineScheduler()
        val callbackFirstInference = RecordingInferencePort()
        val callbackFirst = runner(callbackFirstScheduler, callbackFirstInference, RecordingFinalizer())
        val callbackFirstIndex = callbackFirst.submit(0, 100, 110, byteArrayOf(1))!!
        callbackFirst.stop(nowNs = 200)
        callbackFirstInference.complete(callbackFirstIndex, InferenceResult.Success(1_200, posePresent = false))
        callbackFirstScheduler.advanceTo(1_200)
        assertEquals(TerminalState.EMITTED, callbackFirst.snapshot().sealedSnapshot!!.terminalMap[callbackFirstIndex])
    }

    @Test
    fun deadlineExpiryEmitsReleasedHarnessTimeoutVocabulary() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val runner = runner(scheduler, inference, RecordingFinalizer())
        val submission = runner.submit(0, 100, 110, byteArrayOf(1))!!

        runner.stop(nowNs = 200)
        scheduler.advanceTo(1_200)

        val failure = runner.snapshot().sealedSnapshot!!.failures.single()
        assertEquals(submission, failure.submissionIndex)
        assertEquals("harness", failure.failureSource)
        assertEquals("session_teardown_timeout", failure.classification)
        assertEquals(TerminalState.TIMED_OUT, runner.snapshot().sealedSnapshot!!.terminalMap[submission])
    }

    @Test
    fun deadlineExpiryCancelsEachPendingInferenceBeforeRecordingTheTimeout() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val runner = runner(scheduler, inference, RecordingFinalizer())
        val submission = runner.submit(0, 100, 110, byteArrayOf(1))!!

        runner.stop(nowNs = 200)
        scheduler.advanceTo(1_200)

        assertEquals(listOf("submit:$submission", "cancel:$submission"), inference.events)
        assertEquals("session_teardown_timeout", runner.snapshot().sealedSnapshot!!.failures.single().classification)
    }

    @Test
    fun pendingInferenceAdvisoryRemainsOccupiedUntilACompletionOrDrainTimeout() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val runner = runner(scheduler, inference, RecordingFinalizer())

        assertEquals(false, hasPendingInference(runner))
        val submission = runner.submit(0, 100, 110, byteArrayOf(1))!!
        assertEquals(true, hasPendingInference(runner))

        // The backend call has returned, but no asynchronous terminal callback has arrived.
        assertEquals(true, hasPendingInference(runner))

        inference.complete(submission, InferenceResult.Success(120, posePresent = false))
        assertEquals(false, hasPendingInference(runner))

        val timedOut = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer())
        val timedOutSubmission = timedOut.submit(0, 100, 110, byteArrayOf(1))!!
        timedOut.stop(nowNs = 200)
        timedOut.postDeadline()
        assertEquals(timedOutSubmission, timedOut.snapshot().sealedSnapshot!!.terminalMap.keys.single())
        assertEquals(false, hasPendingInference(timedOut))
    }

    @Test
    fun terminalClassificationIsSingleAssignmentAndReassignmentFailsSession() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val runner = runner(scheduler, inference, RecordingFinalizer())
        val submission = runner.submit(0, 100, 110, byteArrayOf(1))!!
        inference.complete(submission, InferenceResult.Success(120, posePresent = false))

        assertFailsWith<IllegalStateException> {
            inference.complete(submission, InferenceResult.Failure("backend", "duplicate"))
        }
        assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)
        assertEquals(TerminalState.EMITTED, runner.snapshot().sealedSnapshot!!.terminalMap[submission])
        assertEquals(1, runner.snapshot().sealedSnapshot!!.counts.inferenceRecordsEmitted)
        assertEquals(0, runner.snapshot().sealedSnapshot!!.counts.inferenceFailures)
    }

    @Test
    fun acceptedCountersAndSealedSnapshotCannotBeChangedAfterSeal() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val runner = runner(scheduler, inference, RecordingFinalizer())
        val emitted = runner.submit(0, 100, 110, byteArrayOf(1))!!
        inference.complete(emitted, InferenceResult.Success(120, posePresent = true))
        runner.skip(frameIndex = 1, captureTimestampNs = 130, reason = SkipReason.SESSION_STOPPING)
        val failed = runner.submit(2, 140, 150, byteArrayOf(2))!!
        inference.complete(failed, InferenceResult.Failure("backend", "backend_call_failed"))
        runner.stop(nowNs = 200)

        val sealed = assertNotNull(runner.snapshot().sealedSnapshot)
        assertEquals(3, sealed.counts.cameraFramesReceived)
        assertEquals(2, sealed.counts.inferenceFramesSubmitted)
        assertEquals(1, sealed.counts.inferenceRecordsEmitted)
        assertEquals(1, sealed.counts.inferenceFailures)
        assertEquals(1, sealed.counts.poseDetectedRecords)
        assertEquals(1, sealed.counts.framesSkippedBeforeInference)
        assertEquals(TerminalState.EMITTED, sealed.terminalMap[emitted])
        assertEquals(TerminalState.FAILED, sealed.terminalMap[failed])

        val afterSeal = runner.snapshot()
        runner.skip(frameIndex = 3, captureTimestampNs = 210, reason = SkipReason.BACKPRESSURE)
        assertEquals(sealed, runner.snapshot().sealedSnapshot)
        assertEquals(afterSeal.sealedSnapshot, runner.snapshot().sealedSnapshot)
    }

    @Test
    fun repeatedStopAndDoubleFinalizeJoinFirstResultAndRunFinalizationOnce() {
        val scheduler = VirtualDeadlineScheduler()
        val finalizer = RecordingFinalizer()
        val runner = runner(scheduler, RecordingInferencePort(), finalizer)

        runner.stop(nowNs = 200)
        runner.stop(nowNs = 300)
        assertEquals(SessionLifecycle.SEALED, runner.snapshot().lifecycle)
        val first = runner.finalize()
        val second = runner.finalize()

        assertSame(first, second)
        assertEquals(1, finalizer.invocations)
        assertEquals(1, runner.snapshot().transitionHistory.count { it == SessionLifecycle.ASSEMBLING })
        assertEquals(1, runner.snapshot().transitionHistory.count { it == SessionLifecycle.PUBLISHED })
    }

    @Test
    fun finalizerFailureEndsAtFailedWithoutChangingTheSealedSnapshot() {
        val scheduler = VirtualDeadlineScheduler()
        val finalizer = RecordingFinalizer(publish = false)
        val runner = runner(scheduler, RecordingInferencePort(), finalizer)

        runner.stop(nowNs = 200)
        val sealed = assertNotNull(runner.snapshot().sealedSnapshot)
        val result = runner.finalize()

        assertTrue(result is FinalizationResult.Failed)
        assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)
        assertEquals(sealed, result.sealedSnapshot)
        assertEquals(sealed, runner.snapshot().sealedSnapshot)
        assertEquals(
            listOf(SessionLifecycle.RUNNING, SessionLifecycle.DRAINING, SessionLifecycle.SEALED, SessionLifecycle.ASSEMBLING, SessionLifecycle.FAILED),
            runner.snapshot().transitionHistory,
        )
    }

    @Test
    fun cancellationClassifiesOnceAndStoppingSkipsFramesWithoutSubmittingThem() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val runner = runner(scheduler, inference, RecordingFinalizer())
        val submission = runner.submit(0, 100, 110, byteArrayOf(1))!!

        runner.cancel(submission)
        assertEquals(1, inference.cancelled)
        runner.stop(nowNs = 200)
        assertEquals("harness", runner.snapshot().sealedSnapshot!!.failures.single().failureSource)
        assertEquals("session_teardown_timeout", runner.snapshot().sealedSnapshot!!.failures.single().classification)
        assertEquals(TerminalState.TIMED_OUT, runner.snapshot().sealedSnapshot!!.terminalMap[submission])

        val stoppingRunner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer())
        val pending = stoppingRunner.submit(0, 100, 110, byteArrayOf(1))!!
        stoppingRunner.stop(nowNs = 200)
        stoppingRunner.skip(1, 210, SkipReason.SESSION_STOPPING)
        assertEquals(SessionLifecycle.DRAINING, stoppingRunner.snapshot().lifecycle)
        assertEquals(2, stoppingRunner.snapshot().counts.cameraFramesReceived)
        assertEquals(1, stoppingRunner.snapshot().counts.framesSkippedBeforeInference)
        stoppingRunner.postDeadline()
        assertEquals(TerminalState.TIMED_OUT, stoppingRunner.snapshot().sealedSnapshot!!.terminalMap[pending])
    }

    @Test
    fun rejectedSubmissionFailsClosedBeforeLedgerMutationAndNeverFinalizes() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = RecordingInferencePort()
        val finalizer = RecordingFinalizer()
        val runner = runner(scheduler, inference, finalizer)

        assertFailsWith<IllegalArgumentException> {
            runner.submit(frameIndex = 0, captureTimestampNs = 100, inferenceStartNs = 99, input = byteArrayOf(1))
        }

        val snapshot = runner.snapshot()
        assertEquals(SessionLifecycle.FAILED, snapshot.lifecycle)
        assertEquals(0, snapshot.counts.cameraFramesReceived)
        assertEquals(0, snapshot.counts.inferenceFramesSubmitted)
        assertEquals(0, snapshot.counts.framesSkippedBeforeInference)
        assertTrue(snapshot.records.isEmpty())
        assertTrue(snapshot.failures.isEmpty())
        assertEquals(0, inference.submissions)

        runner.stop(nowNs = 200)
        assertFailsWith<IllegalStateException> { runner.finalize() }
        assertEquals(0, finalizer.invocations)
    }

    // Review finding (Critical, re-review): the returned run owner's Disposable ownership must be
    // bound to the existing SessionRunner terminal surface — successful publication and every
    // FAILED transition dispose the owned inference exactly once — and an explicit idempotent
    // abandonment path must reach FAILED and dispose it too, without affecting non-Disposable ports.
    @Test
    fun successfulPublicationDisposesTheOwnedInferenceExactlyOnce() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = DisposableRecordingInferencePort()
        val runner = runnerWith(scheduler, inference, RecordingFinalizer())

        val submission = runner.submit(0, 100, 110, byteArrayOf(1))!!
        runner.stop(nowNs = 200)
        inference.complete(submission, InferenceResult.Success(inferenceEndNs = 210, posePresent = false))
        assertEquals(SessionLifecycle.SEALED, runner.snapshot().lifecycle)
        assertEquals(0, inference.disposeCalls, "must not dispose before the session actually publishes")

        val result = runner.finalize()

        assertTrue(result is FinalizationResult.Published)
        assertEquals(SessionLifecycle.PUBLISHED, runner.snapshot().lifecycle)
        assertEquals(1, inference.disposeCalls)

        // A repeated finalize() call joins the first result (existing behaviour); disposal must
        // not fire again either.
        runner.finalize()
        assertEquals(1, inference.disposeCalls)
    }

    @Test
    fun finalizerFailureDisposesTheOwnedInferenceExactlyOnce() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = DisposableRecordingInferencePort()
        val runner = runnerWith(scheduler, inference, RecordingFinalizer(publish = false))

        runner.stop(nowNs = 200)
        assertEquals(0, inference.disposeCalls)

        val result = runner.finalize()

        assertTrue(result is FinalizationResult.Failed)
        assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)
        assertEquals(1, inference.disposeCalls)
    }

    @Test
    fun abandonReachesFailedAndDisposesTheOwnedInferenceExactlyOnce() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = DisposableRecordingInferencePort()
        val runner = runnerWith(scheduler, inference, RecordingFinalizer())
        runner.submit(0, 100, 110, byteArrayOf(1))

        val snapshot = runner.abandon()

        assertEquals(SessionLifecycle.FAILED, snapshot.lifecycle)
        assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)
        assertEquals(1, inference.disposeCalls)

        // Idempotent: calling abandon() again must neither throw nor dispose a second time.
        val second = runner.abandon()
        assertEquals(SessionLifecycle.FAILED, second.lifecycle)
        assertEquals(1, inference.disposeCalls)
    }

    @Test
    fun abandonAfterSuccessfulPublicationIsANoOp() {
        val scheduler = VirtualDeadlineScheduler()
        val inference = DisposableRecordingInferencePort()
        val runner = runnerWith(scheduler, inference, RecordingFinalizer())
        runner.stop(nowNs = 200)
        runner.finalize()
        assertEquals(SessionLifecycle.PUBLISHED, runner.snapshot().lifecycle)
        assertEquals(1, inference.disposeCalls)

        val snapshot = runner.abandon()

        assertEquals(
            SessionLifecycle.PUBLISHED,
            snapshot.lifecycle,
            "abandon() must never retroactively fail an already-published session",
        )
        assertEquals(1, inference.disposeCalls, "abandon() must never re-dispose an already-terminal session")
    }

    @Test
    fun nonDisposableInferencePortsAreUnaffectedThroughSuccessFailureAndAbandonment() {
        // RecordingInferencePort intentionally does not implement Disposable: the terminal-path
        // binding must be a no-op for it in every case, preserving pre-existing behaviour exactly.
        val successRunner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer())
        successRunner.stop(nowNs = 200)
        assertTrue(successRunner.finalize() is FinalizationResult.Published)

        val failRunner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer(publish = false))
        failRunner.stop(nowNs = 200)
        assertTrue(failRunner.finalize() is FinalizationResult.Failed)

        val abandonRunner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer())
        assertEquals(SessionLifecycle.FAILED, abandonRunner.abandon().lifecycle)
    }

    @Test
    fun abandonmentDuringAnActiveRunMakesTheFollowingStopAndFinalizeATypedCancellation() {
        // The teardown race, in the exact order the replay composition performs it: a run is
        // in flight, the owner abandons it, and the composition's own completion path then calls
        // stop() and finalize(). That sequence used to throw IllegalStateException("session must be
        // sealed before finalization") straight out of the run.
        val scheduler = VirtualDeadlineScheduler()
        val inference = DisposableRecordingInferencePort()
        val finalizer = RecordingFinalizer()
        val runner = SessionRunner(
            configuration = SessionConfiguration(TEST_SESSION_ID, TEST_SCHEMA_VERSION, TEST_WIDTH, TEST_HEIGHT),
            inference = inference,
            deadlineScheduler = scheduler,
            drainTimeoutNs = 1_000,
            finalizer = finalizer,
            dispatcher = DirectSessionDispatcher,
        )

        val submission = runner.submit(frameIndex = 0, captureTimestampNs = 100, inferenceStartNs = 110, input = byteArrayOf(1))!!
        inference.complete(submission, InferenceResult.Success(inferenceEndNs = 120, posePresent = true))

        runner.abandon()
        assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)

        // Neither call may throw, and the outcome must say what happened rather than looking like
        // a completed run.
        runner.stop(nowNs = 200)
        val result = runner.finalize()

        val cancelled = assertIs<FinalizationResult.Cancelled>(result)
        assertEquals(SessionRunner.ABANDONED_BEFORE_FINALIZATION, cancelled.reason)
        assertEquals(0, finalizer.invocations, "an abandoned session must never be published")
        assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)
        assertEquals(1, inference.disposeCalls, "the owned backend is still disposed exactly once")
    }

    @Test
    fun finalizingAnAbandonedSessionIsIdempotentAndStillNeverPublishes() {
        val finalizer = RecordingFinalizer()
        val runner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), finalizer)
        runner.abandon()

        val first = runner.finalize()
        val second = runner.finalize()

        assertIs<FinalizationResult.Cancelled>(first)
        assertSame(first, second, "a second finalize must return the same recorded outcome")
        assertEquals(0, finalizer.invocations)
        assertEquals(
            listOf(SessionLifecycle.RUNNING, SessionLifecycle.FAILED),
            runner.snapshot().transitionHistory,
            "cancellation must not invent an ASSEMBLING transition it never made",
        )
    }

    @Test
    fun aRealFinalizationFailureIsStillReportedAsAFailureNotACancellation() {
        // Only the abandonment path maps to cancellation. A session that genuinely reached the
        // finalizer and failed there must keep reporting itself as failed, or an export defect
        // would be indistinguishable from a torn-down run.
        val runner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer(publish = false))
        runner.stop(nowNs = 200)

        val result = runner.finalize()

        assertIs<FinalizationResult.Failed>(result)
        assertEquals("injected", result.failureCode)
    }

    @Test
    fun abandoningAfterAFinalizationFailureDoesNotRelabelItAsCancelled() {
        val runner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer(publish = false))
        runner.stop(nowNs = 200)
        assertIs<FinalizationResult.Failed>(runner.finalize())

        runner.abandon()

        assertIs<FinalizationResult.Failed>(runner.finalize())
    }

    @Test
    fun aSessionFailedByAnInternalErrorIsNeverReportedAsACancellation() {
        // "Only the teardown race maps to cancellation." A session driven into FAILED by a genuine
        // internal defect was not abandoned by anyone, so finalize() must still fail loudly rather
        // than quietly reporting the defect as an intentional cancellation.
        val runner = runner(VirtualDeadlineScheduler(), RecordingInferencePort(), RecordingFinalizer())

        assertFailsWith<IllegalStateException> { runner.cancel(submissionIndex = 99) }
        assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)

        assertFailsWith<IllegalStateException> { runner.finalize() }
    }

    @Test
    fun finalizingANonSealedSessionThatWasNeverAbandonedStillFailsLoudly() {
        // The check this fix narrows must still fire everywhere else: finalizing a running session
        // is a caller defect, not a cancellation.
        val inference = RecordingInferencePort()
        val runner = runner(VirtualDeadlineScheduler(), inference, RecordingFinalizer())
        runner.submit(frameIndex = 0, captureTimestampNs = 100, inferenceStartNs = 110, input = byteArrayOf(1))

        assertFailsWith<IllegalStateException> { runner.finalize() }
    }

    private fun runner(
        scheduler: VirtualDeadlineScheduler,
        inference: RecordingInferencePort,
        finalizer: RecordingFinalizer,
    ) = SessionRunner(
        configuration = SessionConfiguration(TEST_SESSION_ID, TEST_SCHEMA_VERSION, TEST_WIDTH, TEST_HEIGHT),
        inference = inference,
        deadlineScheduler = scheduler,
        drainTimeoutNs = 1_000,
        finalizer = finalizer,
        dispatcher = DirectSessionDispatcher,
    )

    private fun runnerWith(
        scheduler: DeadlineScheduler,
        inference: InferencePort,
        finalizer: SessionFinalizer,
    ) = SessionRunner(
        configuration = SessionConfiguration(TEST_SESSION_ID, TEST_SCHEMA_VERSION, TEST_WIDTH, TEST_HEIGHT),
        inference = inference,
        deadlineScheduler = scheduler,
        drainTimeoutNs = 1_000,
        finalizer = finalizer,
        dispatcher = DirectSessionDispatcher,
    )

    private class VirtualDeadlineScheduler : DeadlineScheduler {
        private data class Entry(val deadlineNs: Long, val action: () -> Unit, var cancelled: Boolean = false)

        private val entries = mutableListOf<Entry>()

        override fun schedule(deadlineNs: Long, action: () -> Unit): Disposable = object : Disposable {
            private val entry = Entry(deadlineNs, action)

            init {
                entries += entry
            }

            override fun dispose() {
                entry.cancelled = true
            }
        }

        fun advanceTo(nowNs: Long) {
            entries.filter { !it.cancelled && it.deadlineNs <= nowNs }
                .sortedBy { it.deadlineNs }
                .toList()
                .forEach { entry ->
                    entry.cancelled = true
                    entry.action()
                }
        }
    }

    private class RecordingInferencePort : InferencePort {
        private val callbacks = linkedMapOf<Long, (InferenceResult) -> Unit>()
        var submissions = 0
            private set
        var cancelled = 0
            private set
        val events = mutableListOf<String>()

        override fun submit(
            submissionIndex: Long,
            canonicalInput: ByteArray,
            backendTimestampMs: Long,
            callback: (InferenceResult) -> Unit,
        ) {
            submissions++
            events += "submit:$submissionIndex"
            callbacks[submissionIndex] = callback
        }

        override fun cancel(submissionIndex: Long) {
            cancelled++
            events += "cancel:$submissionIndex"
        }

        fun complete(submissionIndex: Long, result: InferenceResult) {
            callbacks.getValue(submissionIndex).invoke(result)
        }
    }

    /** An [InferencePort] that also owns a disposable resource, mirroring the shape of the real
     * `androidMain` MediaPipe adapter (`PoseLandmarkerReplayInferencePort`) without depending on it. */
    private class DisposableRecordingInferencePort : InferencePort, Disposable {
        private val callbacks = linkedMapOf<Long, (InferenceResult) -> Unit>()
        var disposeCalls = 0
            private set

        override fun submit(
            submissionIndex: Long,
            canonicalInput: ByteArray,
            backendTimestampMs: Long,
            callback: (InferenceResult) -> Unit,
        ) {
            callbacks[submissionIndex] = callback
        }

        override fun cancel(submissionIndex: Long) = Unit

        override fun dispose() {
            disposeCalls++
        }

        fun complete(submissionIndex: Long, result: InferenceResult) {
            callbacks.getValue(submissionIndex).invoke(result)
        }
    }

    private class RecordingFinalizer(private val publish: Boolean = true) : SessionFinalizer {
        val calls = mutableListOf<SealedSessionSnapshot>()
        var invocations = 0
            private set

        override fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult {
            invocations++
            calls += snapshot
            return if (publish) {
                FinalizationResult.Published(snapshot)
            } else {
                FinalizationResult.Failed(snapshot, "injected", "injected finalization failure")
            }
        }
    }

    private companion object {
        val TEST_SESSION_ID = Uuid("12345678-1234-4234-8234-123456789abc")
        val TEST_SCHEMA_VERSION = SemanticVersion("1.1.0")
        const val TEST_WIDTH = 256
        const val TEST_HEIGHT = 256
    }

    private fun hasPendingInference(runner: SessionRunner): Boolean = try {
        runner.javaClass.getMethod("hasPendingInference").invoke(runner) as Boolean
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
