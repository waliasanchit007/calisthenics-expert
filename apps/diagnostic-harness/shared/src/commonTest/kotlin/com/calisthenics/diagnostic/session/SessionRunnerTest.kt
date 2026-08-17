package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        assertEquals(TerminalState.FAILED, runner.snapshot().sealedSnapshot!!.terminalMap[submission])
        assertEquals("cancelled", runner.snapshot().sealedSnapshot!!.failures.single().classification)

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
        var cancelled = 0
            private set

        override fun submit(
            submissionIndex: Long,
            canonicalInput: ByteArray,
            backendTimestampMs: Long,
            callback: (InferenceResult) -> Unit,
        ) {
            callbacks[submissionIndex] = callback
        }

        override fun cancel(submissionIndex: Long) {
            cancelled++
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
}
