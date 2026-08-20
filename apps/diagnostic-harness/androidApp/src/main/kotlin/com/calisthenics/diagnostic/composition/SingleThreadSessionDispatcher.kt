package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.session.SessionDispatcher
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Serializes every [com.calisthenics.diagnostic.session.SessionRunner] operation onto one
 * dedicated thread for the lifetime of a single replay run.
 *
 * `RealReplayComposition.run()` drives `submit()`/`stop()`/`finalize()` from the calling (IO)
 * thread, the drain-deadline scheduler fires `postDeadline()` from its own timer thread, and the
 * UI layer may call `SessionRunner.abandon()` from the main thread on teardown/cancellation (the
 * Task 5 brief's "bind UI teardown to abandon() exactly once"). `SessionRunner`'s default
 * [com.calisthenics.diagnostic.session.DirectSessionDispatcher] does not serialize any of that: it
 * runs each call directly on whichever thread invoked it. Without a shared serializing dispatcher,
 * an `abandon()` racing an in-flight `submit()` could mutate `SessionRunner`'s pending-submission
 * bookkeeping and terminal map concurrently with the submit's own mutation of the same state, and
 * could call `PoseLandmarker.close()` (via the terminal-transition dispose path) while a different
 * thread is still inside `detectForVideo()` on that same native instance.
 *
 * [dispatch] blocks the calling thread until the action has completed on the dispatcher thread —
 * a plain single-thread [java.util.concurrent.ExecutorService] runs its queued tasks strictly one
 * at a time, so a call queued behind an in-flight `submit()` cannot begin until that submit's
 * entire handler (including a blocking backend call) has returned. This preserves
 * `SessionRunner`'s synchronous call contract regardless of which thread calls in, and is what
 * makes cross-thread ordering a hard guarantee rather than a race.
 *
 * Scoped to exactly one run: the caller must call [shutdown] once that run reaches a terminal
 * state, so no thread is leaked past a completed run. A [dispatch] attempted after [shutdown] can
 * only be a late `abandon()` from UI teardown racing a run that already finished on its own —
 * [shutdown] is only ever called after this run's own thread has issued its last dispatch, so by
 * the time any other caller could observe rejection the session is already `PUBLISHED` or
 * `FAILED`, `SessionRunner.abandon()` is specified as a no-op in that case, and no writer is left
 * running concurrently. Running that call from an external thread would violate state
 * confinement, so the dispatcher rejects it and the UI teardown path treats that expected late
 * rejection as already-satisfied abandonment.
 */
internal class SingleThreadSessionDispatcher : SessionDispatcher {
    private val workerThread = AtomicReference<Thread?>(null)
    private val shutdownRequested = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            workerThread.set(Thread.currentThread())
            runnable.run()
        }, "replay-session-dispatcher")
    }

    override fun <T> dispatch(action: () -> T): T {
        if (Thread.currentThread() === workerThread.get()) return action()
        if (shutdownRequested.get()) throw RejectedExecutionException("session dispatcher is shut down")
        return try {
            executor.submit(Callable { action() }).get()
        } catch (wrapped: ExecutionException) {
            throw wrapped.cause ?: wrapped
        } catch (rejected: RejectedExecutionException) {
            throw rejected
        }
    }

    fun shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
                "session dispatcher worker did not terminate"
            }
        }
    }
}
