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
import com.calisthenics.diagnostic.session.SessionRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Proves the CRITICAL lifecycle/threading fix (Task 5 follow-up): a `SessionRunner.abandon()`
 * call racing an in-flight `submit()` cannot dispose the owned inference backend — in production,
 * cannot close the native `PoseLandmarker` — until that in-flight call has actually returned, and
 * dispose happens exactly once even under concurrent abandon calls from multiple threads.
 *
 * Without [SingleThreadSessionDispatcher] (i.e. under `SessionRunner`'s default
 * `DirectSessionDispatcher`, which runs each call directly on whichever thread invoked it with no
 * serialization at all), this scenario is a live data race: `abandon()` can call `dispose()`
 * while `submit()`'s backend call is still blocked. This test only exercises the fixed dispatcher
 * — the race itself is not asserted here because a race is not something a single run can pin
 * deterministically. The fix is instead verified by mutation: temporarily replacing
 * [SingleThreadSessionDispatcher]'s single-thread serialization with a direct passthrough call
 * (equivalent to the pre-fix `DirectSessionDispatcher` behaviour) makes this test fail, because the
 * structural guarantee the assertions below depend on — a single-thread executor cannot start a
 * second queued task before a blocking first task returns — no longer holds.
 */
class SingleThreadSessionDispatcherTest {

    @Test
    fun concurrentAbandonCannotDisposeWhileInferenceIsInFlightAndDisposesExactlyOnce() {
        val insideInference = CountDownLatch(1)
        val releaseInference = CountDownLatch(1)
        val inferenceReturned = AtomicBoolean(false)
        val disposeCount = AtomicInteger(0)
        val disposeObservedInferenceReturned = AtomicBoolean(true)
        val submitError = AtomicReference<Throwable?>(null)
        val abandonErrors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        val inference = object : InferencePort, Disposable {
            override fun submit(
                submissionIndex: Long,
                canonicalInput: ByteArray,
                backendTimestampMs: Long,
                callback: (InferenceResult) -> Unit,
            ) {
                insideInference.countDown()
                assertTrue(releaseInference.await(5, TimeUnit.SECONDS), "test setup: release latch never fired")
                inferenceReturned.set(true)
                callback(InferenceResult.Success(inferenceEndNs = 1L, posePresent = false))
            }

            override fun dispose() {
                if (!inferenceReturned.get()) disposeObservedInferenceReturned.set(false)
                disposeCount.incrementAndGet()
            }
        }

        val dispatcher = SingleThreadSessionDispatcher()
        val sessionRunner = SessionRunner(
            configuration = SessionConfiguration(
                sessionId = Uuid("00000000-0000-4000-8000-000000000001"),
                schemaVersion = SemanticVersion("1.1.0"),
                imageWidth = 1,
                imageHeight = 1,
            ),
            inference = inference,
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

        // Submitted from a "replay" thread, exactly as RealReplayComposition.run() does.
        val submitThread = Thread {
            runCatching {
                sessionRunner.submit(0, 0, 0, byteArrayOf(1))
            }.onFailure(submitError::set)
        }.apply { start() }
        assertTrue(insideInference.await(5, TimeUnit.SECONDS), "inference never started")

        // Two concurrent "UI teardown" abandon() calls, exactly as MainActivity's
        // DisposableEffect could race a still-running replay.
        val abandonThreads = List(2) {
            Thread {
                runCatching { sessionRunner.abandon() }.onFailure(abandonErrors::add)
            }.apply { start() }
        }
        // Best-effort scheduling window so the abandon threads have a real chance to run before
        // the backend call is released. Not load-bearing for correctness: the dispatcher's
        // guarantee that dispose() cannot run first is structural (single-thread executor
        // ordering), not timing-dependent — this sleep only makes a defect more likely to surface
        // on a mutated/broken dispatcher, it is not what the assertions below rely on.
        Thread.sleep(300)

        releaseInference.countDown()
        submitThread.join(5_000)
        abandonThreads.forEach { it.join(5_000) }
        dispatcher.shutdown()

        assertTrue(
            disposeObservedInferenceReturned.get(),
            "dispose() ran while the in-flight inference call had not yet returned",
        )
        assertNull(submitError.get(), "replay submit failed")
        assertTrue(abandonErrors.isEmpty(), "UI teardown abandon failed: $abandonErrors")
        assertEquals(1, disposeCount.get(), "dispose() must run exactly once even under concurrent abandon() calls")
    }

    @Test
    fun externalDispatchAfterShutdownIsRejectedRatherThanRunningUnsafely() {
        val dispatcher = SingleThreadSessionDispatcher()
        assertEquals("replay-session-dispatcher", dispatcher.dispatch { Thread.currentThread().name })
        dispatcher.shutdown()

        val error = runCatching { dispatcher.dispatch { error("must not execute") } }.exceptionOrNull()

        assertTrue(error is java.util.concurrent.RejectedExecutionException)
        assertTrue(
            Thread.getAllStackTraces().keys.none { it.name == "replay-session-dispatcher" && it.isAlive },
            "dispatcher worker leaked after shutdown",
        )
    }
}
