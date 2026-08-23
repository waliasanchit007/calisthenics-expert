package com.calisthenics.diagnostic.probe

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CameraReleaseQuiescenceTest {
    @Test
    fun delayedTargetAvailabilityUnblocksAndUnregisters() {
        val harness = Harness(callbackWhenWaiting = TARGET_CAMERA_ID)

        assertTrue(harness.await())
        assertTrue(harness.unregisterCount == 1)
    }

    @Test
    fun availabilityDeliveredDuringRegistrationIsAcceptedImmediately() {
        val harness = Harness(callbackWhenRegistered = TARGET_CAMERA_ID)

        assertTrue(harness.await())
        assertTrue(harness.unregisterCount == 1)
    }

    @Test
    fun wrongCameraAvailabilityDoesNotReleaseTarget() {
        val harness = Harness(callbackWhenWaiting = OTHER_CAMERA_ID)

        assertFalse(harness.await())
        assertTrue(harness.unregisterCount == 1)
    }

    @Test
    fun timeoutIsAClosedFailure() {
        val harness = Harness()

        assertFalse(harness.await())
        assertTrue(harness.unregisterCount == 1)
    }

    @Test
    fun staleCallbackFromPreviousWaitCannotReleaseNextWait() {
        val harness = Harness()

        assertFalse(harness.await())
        val staleCallback = harness.callbacks.single()

        assertFalse(harness.await())
        staleCallback(TARGET_CAMERA_ID)

        assertFalse(harness.signals[1].signalled)
        assertTrue(harness.unregisterCount == 2)
    }

    private class Harness(
        private val callbackWhenRegistered: String? = null,
        private val callbackWhenWaiting: String? = null,
    ) {
        val callbacks = mutableListOf<(String) -> Unit>()
        val signals = mutableListOf<FakeSignal>()
        var unregisterCount = 0

        private val quiescence = CameraReleaseQuiescence(
            registration = CameraAvailabilityRegistration { callback ->
                callbacks += callback
                callbackWhenRegistered?.let(callback)
                CameraAvailabilitySubscription { unregisterCount += 1 }
            },
            signalFactory = {
                FakeSignal().also { signal ->
                    signals += signal
                    signal.onAwait = { callbackWhenWaiting?.let { id -> callbacks.last()(id) } }
                }
            },
        )

        fun await(): Boolean = quiescence.await(TARGET_CAMERA_ID, TIMEOUT_MILLIS)
    }

    private class FakeSignal : CameraAvailabilitySignal {
        var signalled = false
        var onAwait: (() -> Unit)? = null

        override fun signal() {
            signalled = true
        }

        override fun await(timeoutMillis: Long): Boolean {
            onAwait?.invoke()
            return signalled
        }
    }

    private companion object {
        const val TARGET_CAMERA_ID = "0"
        const val OTHER_CAMERA_ID = "1"
        const val TIMEOUT_MILLIS = 100L
    }
}
