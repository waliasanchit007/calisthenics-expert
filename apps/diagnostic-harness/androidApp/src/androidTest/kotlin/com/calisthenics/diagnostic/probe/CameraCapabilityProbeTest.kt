package com.calisthenics.diagnostic.probe

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.MainActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CameraCapabilityProbeTest {
    @Test
    fun executesRealProbeAndPersistsRetrievableSanitizedResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        grantCameraPermission(instrumentation, targetContext.packageName)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<ProbeResult> {
                    check(Thread.currentThread() !== LooperHolder.mainThread) {
                        "probe must run off the Android main thread"
                    }
                    CameraCapabilityProbe(
                        context = targetContext,
                        lifecycleOwner = checkNotNull(activity),
                        probeGitSha = BuildConfig.PROBE_GIT_SHA,
                    ).runAndPersist()
                }.get(15, TimeUnit.SECONDS)

                val evidence = File(targetContext.cacheDir, CameraCapabilityProbe.RESULT_FILE)
                assertTrue(evidence.isFile)
                assertEquals(result, ProbeResult.fromCanonicalJson(evidence.readText()))
                assertTrue(Regex("[0-9a-f]{40}").matches(result.probeGitSha))
                assertFalse(evidence.readText().contains(targetContext.cacheDir.absolutePath))

                if (result.timestampSource != "REALTIME" ||
                    result.sourceColorMatrix == null ||
                    result.sourceColorRange == null
                ) {
                    assertFalse(result.isLiveAdmissible())
                }
                if (!result.directoryFsyncSupported || !result.atomicRenameSupported) {
                    assertFalse(result.isStorageAdmissible())
                }
            } finally {
                worker.shutdownNow()
                check(worker.awaitTermination(15, TimeUnit.SECONDS))
            }
        } finally {
            scenario.close()
        }
    }

    private fun grantCameraPermission(instrumentation: android.app.Instrumentation, packageName: String) {
        if (ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
        }
    }

    private object LooperHolder {
        val mainThread = android.os.Looper.getMainLooper().thread
    }
}
