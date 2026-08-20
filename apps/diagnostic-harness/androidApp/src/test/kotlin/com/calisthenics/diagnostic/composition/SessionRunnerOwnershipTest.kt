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
import org.junit.Test

class SessionRunnerOwnershipTest {
    @Test
    fun clearReleasesCompletedRegistrationForTheNextSequentialRun() {
        val ownership = SessionRunnerOwnership()
        val first = runnerHarness("00000000-0000-4000-8000-000000000003")
        val second = runnerHarness("00000000-0000-4000-8000-000000000004")

        try {
            assertEquals(SessionRunnerRegistrationResult.ACCEPTED, ownership.register(first.runner))
            first.runner.abandon()
            first.dispatcher.shutdown()

            ownership.clear()

            assertEquals(SessionRunnerRegistrationResult.ACCEPTED, ownership.register(second.runner))
        } finally {
            second.runner.abandon()
            second.dispatcher.shutdown()
        }
    }

    @Test
    fun teardownBeforeRegistrationRejectsAndAbandonsWithoutBackendSubmission() {
        val ownership = SessionRunnerOwnership()
        ownership.teardown()
        ownership.clear()

        val submissionCount = AtomicInteger(0)
        val disposeCount = AtomicInteger(0)
        val dispatcher = SingleThreadSessionDispatcher()
        val runner = SessionRunner(
            configuration = SessionConfiguration(
                sessionId = Uuid("00000000-0000-4000-8000-000000000002"),
                schemaVersion = SemanticVersion("1.1.0"),
                imageWidth = 1,
                imageHeight = 1,
            ),
            inference = object : InferencePort, Disposable {
                override fun submit(
                    submissionIndex: Long,
                    canonicalInput: ByteArray,
                    backendTimestampMs: Long,
                    callback: (InferenceResult) -> Unit,
                ) {
                    submissionCount.incrementAndGet()
                }

                override fun dispose() {
                    disposeCount.incrementAndGet()
                }
            },
            deadlineScheduler = object : DeadlineScheduler {
                override fun schedule(deadlineNs: Long, action: () -> Unit): Disposable = object : Disposable {
                    override fun dispose() = Unit
                }
            },
            drainTimeoutNs = 1_000_000_000L,
            finalizer = object : SessionFinalizer {
                override fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult =
                    FinalizationResult.Failed(snapshot, "test", "test")
            },
            dispatcher = dispatcher,
        )

        try {
            assertEquals(
                SessionRunnerRegistrationResult.REJECTED,
                ownership.register(runner),
            )

            assertEquals(0, submissionCount.get(), "rejected registration must not start replay/backend submission")
            assertEquals(1, disposeCount.get(), "rejected registration must abandon the runner exactly once")
            assertEquals(SessionLifecycle.FAILED, runner.snapshot().lifecycle)
        } finally {
            dispatcher.shutdown()
        }
    }

    private data class RunnerHarness(
        val runner: SessionRunner,
        val dispatcher: SingleThreadSessionDispatcher,
    )

    private fun runnerHarness(sessionId: String): RunnerHarness {
        val dispatcher = SingleThreadSessionDispatcher()
        val runner = SessionRunner(
            configuration = SessionConfiguration(
                sessionId = Uuid(sessionId),
                schemaVersion = SemanticVersion("1.1.0"),
                imageWidth = 1,
                imageHeight = 1,
            ),
            inference = object : InferencePort, Disposable {
                override fun submit(
                    submissionIndex: Long,
                    canonicalInput: ByteArray,
                    backendTimestampMs: Long,
                    callback: (InferenceResult) -> Unit,
                ) = Unit

                override fun dispose() = Unit
            },
            deadlineScheduler = noOpDeadlineScheduler(),
            drainTimeoutNs = 1_000_000_000L,
            finalizer = object : SessionFinalizer {
                override fun finalize(snapshot: SealedSessionSnapshot): FinalizationResult =
                    FinalizationResult.Failed(snapshot, "test", "test")
            },
            dispatcher = dispatcher,
        )
        return RunnerHarness(runner, dispatcher)
    }

    private fun noOpDeadlineScheduler(): DeadlineScheduler = object : DeadlineScheduler {
        override fun schedule(deadlineNs: Long, action: () -> Unit): Disposable = object : Disposable {
            override fun dispose() = Unit
        }
    }
}
