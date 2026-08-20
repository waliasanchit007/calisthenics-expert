package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.session.DeadlineScheduler
import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.InferencePort
import com.calisthenics.diagnostic.session.InferenceResult
import com.calisthenics.diagnostic.session.SealedSessionSnapshot
import com.calisthenics.diagnostic.session.SessionConfiguration
import com.calisthenics.diagnostic.session.SessionFinalizer
import com.calisthenics.diagnostic.session.SessionLifecycle
import com.calisthenics.diagnostic.session.SessionRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

/**
 * The UI-teardown-during-an-active-replay race, driven through the real ownership handoff and the
 * real serializing dispatcher the replay composition uses.
 *
 * `MainActivity` binds `SessionRunnerOwnership.teardown()` to the composable leaving composition,
 * so an activity finishing mid-run abandons the registered runner while `RealReplayComposition.run`
 * is still inside its frame loop. That composition then continues to its own completion path —
 * `stop()` then `finalize()` — because the loop's submissions are simply dropped rather than
 * raising. Before the typed cancellation existed, `finalize()` threw
 * `IllegalStateException("session must be sealed before finalization")` out of the run: an
 * unhandled exception on the UI's IO coroutine, from a lifecycle event the user is entitled to
 * cause at any moment.
 *
 * The ordering here is deterministic rather than raced: every operation is serialized by
 * [SingleThreadSessionDispatcher], and teardown is issued between two frames, which is exactly what
 * the main thread does while the IO thread sits between submissions.
 */
class TeardownDuringActiveRunTest {

    @Test
    fun teardownBetweenFramesEndsTheRunAsACancellationWithoutPublishingOrThrowing() {
        val ownership = SessionRunnerOwnership()
        val dispatcher = SingleThreadSessionDispatcher()
        val inference = ImmediateInferencePort()
        val finalizer = CountingFinalizer()
        val runner = runner(dispatcher, inference, finalizer)

        try {
            assertEquals(SessionRunnerRegistrationResult.ACCEPTED, ownership.register(runner))

            // Frame 0 completes normally: the run is genuinely active, not merely constructed.
            runner.submit(frameIndex = 0, captureTimestampNs = 0, inferenceStartNs = 1, input = byteArrayOf(1))
            assertEquals(SessionLifecycle.RUNNING, runner.snapshot().lifecycle)

            // The UI leaves composition here.
            ownership.teardown()
            assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)

            // The replay loop is unaware and submits its next frame; the composition then runs its
            // normal completion path. None of this may throw.
            val dropped = runner.submit(frameIndex = 1, captureTimestampNs = 33, inferenceStartNs = 34, input = byteArrayOf(2))
            assertNull(dropped, "a submission after teardown must be dropped, not admitted")

            runner.stop(nowNs = 100)
            val cancelled = assertIs<FinalizationResult.Cancelled>(runner.finalize())

            assertEquals(SessionRunner.ABANDONED_BEFORE_FINALIZATION, cancelled.reason)
            assertEquals(0, finalizer.invocations.get(), "a torn-down run must never publish an export")
            assertEquals(1, inference.disposeCalls.get(), "the owned backend is disposed exactly once")
            assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun aRunThatCompletesWithoutTeardownStillPublishes() {
        // The narrow scope of the cancellation, on the same harness: nothing about the fix changes
        // an ordinary run's outcome.
        val ownership = SessionRunnerOwnership()
        val dispatcher = SingleThreadSessionDispatcher()
        val finalizer = CountingFinalizer()
        val runner = runner(dispatcher, ImmediateInferencePort(), finalizer)

        try {
            assertEquals(SessionRunnerRegistrationResult.ACCEPTED, ownership.register(runner))
            runner.submit(frameIndex = 0, captureTimestampNs = 0, inferenceStartNs = 1, input = byteArrayOf(1))
            runner.stop(nowNs = 100)

            assertIs<FinalizationResult.Published>(runner.finalize())
            assertEquals(1, finalizer.invocations.get())

            ownership.clear()
        } finally {
            dispatcher.shutdown()
        }
    }

    private fun runner(
        dispatcher: SingleThreadSessionDispatcher,
        inference: InferencePort,
        finalizer: SessionFinalizer,
    ) = SessionRunner(
        configuration = SessionConfiguration(
            sessionId = Uuid("00000000-0000-4000-8000-00000000000a"),
            schemaVersion = SemanticVersion("1.1.0"),
            imageWidth = 256,
            imageHeight = 256,
        ),
        inference = inference,
        deadlineScheduler = object : DeadlineScheduler {
            override fun schedule(deadlineNs: Long, action: () -> Unit): Disposable = object : Disposable {
                override fun dispose() = Unit
            }
        },
        drainTimeoutNs = 1_000_000_000L,
        finalizer = finalizer,
        dispatcher = dispatcher,
    )

    /** Resolves every submission synchronously, like the real synchronous VIDEO replay adapter. */
    private class ImmediateInferencePort : InferencePort, Disposable {
        val disposeCalls = AtomicInteger(0)

        override fun submit(
            submissionIndex: Long,
            canonicalInput: ByteArray,
            backendTimestampMs: Long,
            callback: (InferenceResult) -> Unit,
        ) = callback(InferenceResult.Success(inferenceEndNs = backendTimestampMs + 1, posePresent = true))

        override fun cancel(submissionIndex: Long) = Unit

        override fun dispose() {
            disposeCalls.incrementAndGet()
        }
    }

    private class CountingFinalizer : SessionFinalizer {
        val invocations = AtomicInteger(0)

        override fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult {
            invocations.incrementAndGet()
            return FinalizationResult.Published(snapshot)
        }
    }
}
