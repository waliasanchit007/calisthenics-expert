package com.calisthenics.diagnostic.probe

import android.content.Context
import android.hardware.camera2.CameraManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal fun interface CameraAvailabilityRegistration {
    fun register(onCameraAvailable: (String) -> Unit): CameraAvailabilitySubscription
}

internal fun interface CameraAvailabilitySubscription {
    fun close()
}

internal interface CameraAvailabilitySignal {
    fun signal()

    fun await(timeoutMillis: Long): Boolean
}

/**
 * Waits for one exact camera ID to become available after a CameraX unbind operation.
 *
 * The registration and signal are injected so the lifecycle rule can be tested without a device.
 * Each wait owns a fresh signal and callback, so a late callback from an earlier wait cannot
 * release a later wait.
 */
internal class CameraReleaseQuiescence(
    private val registration: CameraAvailabilityRegistration,
    private val signalFactory: () -> CameraAvailabilitySignal = { CountDownLatchSignal() },
) {
    fun await(cameraId: String, timeoutMillis: Long): Boolean {
        require(cameraId.isNotEmpty()) { "cameraId must not be empty" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        val signal = signalFactory()
        val subscription = registration.register { availableCameraId ->
            if (availableCameraId == cameraId) signal.signal()
        }
        return try {
            signal.await(timeoutMillis)
        } finally {
            subscription.close()
        }
    }
}

internal class AndroidCameraAvailabilityRegistration(
    context: Context,
    private val executor: Executor,
) : CameraAvailabilityRegistration {
    private val cameraManager = checkNotNull(context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager) {
        "camera service unavailable"
    }

    override fun register(onCameraAvailable: (String) -> Unit): CameraAvailabilitySubscription {
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                onCameraAvailable(cameraId)
            }
        }
        cameraManager.registerAvailabilityCallback(executor, callback)
        return CameraAvailabilitySubscription {
            cameraManager.unregisterAvailabilityCallback(callback)
        }
    }
}

private class CountDownLatchSignal : CameraAvailabilitySignal {
    private val latch = CountDownLatch(1)

    override fun signal() {
        latch.countDown()
    }

    override fun await(timeoutMillis: Long): Boolean = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
}
