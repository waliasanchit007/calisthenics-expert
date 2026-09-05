package com.calisthenics.diagnostic.preview

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Test
import kotlin.test.*

/** Real packaged model + real viewer; synthetic pixels only, never starts the camera. */
class MovementViewerDeviceTest {
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
            scenario.onActivity { controller = checkNotNull(it.startViewer(ViewerInput.SYNTHETIC)) }
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
            scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(controller.snapshot().observation)
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
