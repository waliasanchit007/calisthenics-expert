package com.calisthenics.diagnostic.preview

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Test
import kotlin.test.*

/** Synthetic by default; the named live test requires explicit operator authorization argument. */
class MovementViewerDeviceTest {
    /** Owner-authorized short self-test: retain aggregate timings only, no pixels/landmarks. */
    @Test fun nativeCameraPreviewStreamsIndependentlyAndStopsCleanly() {
        org.junit.Assume.assumeTrue("requires an authorized operator-only or empty scene",
            InstrumentationRegistry.getArguments().getString("viewerSelfTestAuthorized") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun files() = context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toSet()
        fun preview(view: android.view.View): androidx.camera.view.PreviewView? {
            if (view is androidx.camera.view.PreviewView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                preview(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        val before = files()
        ActivityScenario.launch(MovementViewerActivity::class.java).use { scenario ->
            lateinit var controller: MovementViewerController
            scenario.onActivity {
                it.attestedCameraScope = true
                controller = checkNotNull(it.startViewer(ViewerInput.CAMERA))
            }
            val deadline = System.nanoTime() + 20_000_000_000L
            while (controller.snapshot().completed < 2 && controller.snapshot().failure == null && System.nanoTime() < deadline) Thread.sleep(100)
            assertNull(controller.snapshot().failure)
            var detectedSamples = 0
            var visibleSamples = 0
            var heldSamples = 0
            var rawStaleSamples = 0
            var overlayDisappearances = 0
            var previouslyVisible = false
            repeat(100) {
                scenario.onActivity { activity ->
                    if (activity.snapshot?.observation?.posePresent == true) detectedSamples++
                    if (activity.snapshot?.message == "Frame stale") rawStaleSamples++
                    val overlay = activity.overlay
                    val visible = overlay != null && overlay.alpha > 0f
                    if (visible) visibleSamples++
                    if (overlay?.held == true) heldSamples++
                    if (previouslyVisible && !visible) overlayDisappearances++
                    previouslyVisible = visible
                }
                Thread.sleep(50)
            }
            val observed = controller.snapshot()
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Viewer timings only: previewFps=${controller.previewFps()}, ${observed.performance}; " +
                    "displaySamples=100, detected=$detectedSamples, visible=$visibleSamples, held=$heldSamples, " +
                    "rawStale=$rawStaleSamples, disappearances=$overlayDisappearances\n")
            })
            assertTrue(observed.completed >= 2, "real front-camera model callbacks must complete")
            assertTrue((controller.previewFps() ?: 0.0) > 0.0, "must observe native preview callbacks, not requested FPS")
            lateinit var native: androidx.camera.view.PreviewView
            scenario.onActivity {
                native = assertNotNull(preview(it.window.decorView), "camera must use a native PreviewView, not inference-frame bitmaps")
                assertEquals(androidx.camera.view.PreviewView.StreamState.STREAMING, native.previewStreamState.value)
                assertNotNull(native.sensorToViewTransform)
                it.stopViewer()
                assertNull(it.overlay, "Stop must clear the held overlay as well as raw observations")
            }
            Thread.sleep(750)
            assertNull(controller.snapshot().observation)
            scenario.onActivity {
                assertFalse(it.attestedCameraScope)
                assertEquals(androidx.camera.view.PreviewView.StreamState.IDLE, native.previewStreamState.value)
            }
        }
        assertEquals(before, files(), "live performance check must not persist observations")
    }
    @Test fun frontCameraIsPresentAndHasCompatibleTimestampMetadataWithoutOpeningIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val available = manager.cameraIdList.map { id ->
            id to manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
        }
        val id = com.calisthenics.diagnostic.camera.selectSourceCameraId(available, androidx.camera.core.CameraSelector.LENS_FACING_FRONT)
        val metadata = manager.getCameraCharacteristics(id)
        assertEquals(android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT,
            metadata.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING))
        assertEquals(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            metadata.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
    }
    @Test fun installedViewerCannotUseNetworkPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.INTERNET" in requested)
        assertFalse("android.permission.ACCESS_NETWORK_STATE" in requested)
    }
    @Test fun syntheticPoseIsVisibleAndStopClearsItWithoutCreatingObservationFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun files() = context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toSet()
        val before = files()
        ActivityScenario.launch(MovementViewerActivity::class.java).use { scenario ->
            lateinit var controller: MovementViewerController
            lateinit var activity: MovementViewerActivity
            scenario.onActivity { activity = it; controller = checkNotNull(it.startViewer(ViewerInput.SYNTHETIC)) }
            val end = System.nanoTime() + 30_000_000_000L
            var result = controller.snapshot()
            while (result.observation?.posePresent != true && result.failure == null && System.nanoTime() < end) {
                Thread.sleep(100)
                result = controller.snapshot()
            }
            assertNull(result.failure)
            assertTrue(result.observation?.posePresent == true, "packaged detector must actually fire")
            assertEquals(33, result.observation!!.landmarks.size)
            assertTrue(result.observation!!.frame.rgb.isNotEmpty())
            var measurementsSeen = false
            val measurementDeadline = System.nanoTime() + 5_000_000_000L
            while (!measurementsSeen && System.nanoTime() < measurementDeadline) {
                scenario.onActivity {
                    measurementsSeen = it.movementState.measurements != null && it.movementState.trace.isNotEmpty()
                    assertFalse(it.movementState.cameraActive, "synthetic demo must not enable pull-up calibration")
                    assertEquals(0,it.movementState.experiment.candidateCycles)
                }
                if (!measurementsSeen) Thread.sleep(50)
            }
            assertTrue(measurementsSeen, "fresh real-model output must reach common movement measurements")
            scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(controller.snapshot().observation)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertNull(activity.movementState.measurements)
                assertTrue(activity.movementState.trace.isEmpty())
                assertNull(activity.movementState.experiment.range)
                assertEquals(0,activity.movementState.experiment.candidateCycles)
            }
            Thread.sleep(300)
            assertNull(controller.snapshot().observation, "late native result must not restore an image")
        }
        assertEquals(before, files(), "viewer must not create pose, video, telemetry or study exports")
    }

    @Test fun cameraCannotStartWithoutFreshCameraScopeAttestation() {
        ActivityScenario.launch(MovementViewerActivity::class.java).use { scenario ->
            scenario.onActivity { assertNull(it.startViewer(ViewerInput.CAMERA)) }
        }
    }
}
