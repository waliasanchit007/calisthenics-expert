package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.session.SessionRunner
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

enum class SessionRunnerRegistrationResult {
    ACCEPTED,
    REJECTED,
}

/**
 * Owns the one UI-to-session handoff. Registration and teardown are one CAS state machine, so a
 * teardown before registration cannot be lost between an independent flag check and reference
 * write. A normal completion releases [State.Registered] back to [State.Open] for the next run;
 * [State.TornDown] remains terminal. The runner stored in [State.TornDown] is the exact runner
 * that won registration and is abandoned once; a runner rejected after teardown is abandoned by
 * the registering caller.
 */
internal class SessionRunnerOwnership {
    private sealed interface State {
        data object Open : State
        data class Registered(val runner: SessionRunner) : State
        data class TornDown(val runner: SessionRunner?) : State
    }

    private val state = AtomicReference<State>(State.Open)

    fun register(runner: SessionRunner): SessionRunnerRegistrationResult {
        while (true) {
            when (val current = state.get()) {
                State.Open -> if (state.compareAndSet(current, State.Registered(runner))) {
                    return SessionRunnerRegistrationResult.ACCEPTED
                }

                is State.Registered,
                is State.TornDown,
                -> {
                    abandonSafely(runner)
                    return SessionRunnerRegistrationResult.REJECTED
                }
            }
        }
    }

    fun teardown() {
        while (true) {
            val current = state.get()
            val next = when (current) {
                State.Open -> State.TornDown(null)
                is State.Registered -> State.TornDown(current.runner)
                is State.TornDown,
                -> return
            }
            if (state.compareAndSet(current, next)) {
                next.runner?.let(::abandonSafely)
                return
            }
        }
    }

    fun clear() {
        while (true) {
            val current = state.get()
            val next = when (current) {
                is State.Registered -> State.Open

                State.Open,
                is State.TornDown,
                -> return
            }
            if (state.compareAndSet(current, next)) return
        }
    }

    private fun abandonSafely(runner: SessionRunner) {
        try {
            runner.abandon()
        } catch (_: RejectedExecutionException) {
            // The runner already reached a terminal state and shut down its dispatcher.
        }
    }
}
