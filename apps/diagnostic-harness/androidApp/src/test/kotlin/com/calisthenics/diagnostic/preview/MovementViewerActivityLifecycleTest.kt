package com.calisthenics.diagnostic.preview

import android.view.WindowManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MovementViewerActivityLifecycleTest {
    @Test fun screenshotsRemainAllowedOnLaunchStopAndRecreation() {
        val owner = Robolectric.buildActivity(MovementViewerActivity::class.java).setup()
        try {
            assertEquals(0, owner.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            owner.get().stopViewer()
            assertEquals(0, owner.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            owner.recreate()
            assertEquals(0, owner.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun startingEitherSourceDoesNotBlockScreenshots() {
        val owner = Robolectric.buildActivity(MovementViewerActivity::class.java).setup()
        try {
            val activity = owner.get()
            org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.CAMERA)
            for (input in ViewerInput.entries) {
                // Isolate source-start behavior from the independently tested launch policy.
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.attestedCameraScope = true
                assertNotNull(activity.startViewer(input))
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                    "$input must remain screen-capturable")
                activity.stopViewer()
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun closeRemainsSafeWhenStartupTeardownHasAlreadyStoppedTheWorker() {
        val owner = Robolectric.buildActivity(MovementViewerActivity::class.java).setup()
        val worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            val controller = MovementViewerController(owner.get(), owner.get(), ViewerInput.SYNTHETIC, worker)
            worker.shutdown()
            controller.close()
            assertNull(controller.snapshot().observation)
        } finally { worker.shutdownNow(); owner.pause().stop().destroy() }
    }
    @Test fun failureRevokesAttestationAndWakeLockWhileRetainingTheError() {
        val owner = Robolectric.buildActivity(MovementViewerActivity::class.java).setup()
        try {
            val activity = owner.get()
            activity.attestedCameraScope = true
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val failure = ViewerSnapshot(null, "Inference failed", 1, 0, 0, "Inference failed")
            activity.finishWithFailure(failure)
            assertFalse(activity.attestedCameraScope)
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertEquals(failure, activity.snapshot)
        } finally { owner.pause().stop().destroy() }
    }
}
