package com.calisthenics.diagnostic.probe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.MainActivity
import com.calisthenics.diagnostic.replay.CameraCapabilityEnvironment
import com.calisthenics.diagnostic.replay.CameraCapabilityEvidence
import com.calisthenics.diagnostic.replay.CameraCapabilityProvenance
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.replay.cameraLiveAdmissionBlockingReasons
import com.calisthenics.diagnostic.replay.renderCameraCapabilityEvidence
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
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

                if (!result.directoryFsyncSupported || !result.atomicRenameSupported) {
                    assertFalse(result.isStorageAdmissible())
                }

                // Design section 8 evidence. The renderer computes admission from these
                // observations; nothing here declares it, and this test neither passes nor fails on
                // which way it came out. An UNKNOWN timestamp source, an unknown DataSpace, or a
                // bridge that produced no image are all successful observations that block live
                // capture, and the record has to be emitted either way — a blocking result is the
                // finding the milestone stops on, not a probe failure to be swallowed.
                val environment = CameraCapabilityEnvironment(
                    deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                    deviceIdentity = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                    deviceFingerprint = Build.FINGERPRINT,
                    apiLevel = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE,
                    abi = Build.SUPPORTED_ABIS.joinToString(","),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    marketingName = readMarketingName(),
                )
                val observation = result.toCameraObservation()
                val record = renderCameraCapabilityEvidence(
                    CameraCapabilityEvidence(
                        emittedBy = EMITTED_BY,
                        environment = environment,
                        provenance = CameraCapabilityProvenance(
                            osBuild = result.osBuild,
                            cameraxVersion = result.cameraxVersion,
                            probeGitSha = result.probeGitSha,
                        ),
                        observation = observation,
                    ),
                )

                assertContains(record, """"sanitized":true""")
                assertContains(record, """"non_participant_observation":true""")
                assertFalse(record.contains(targetContext.cacheDir.absolutePath))
                // Every reason the shared rule found on this device must appear in the record the
                // transcript checker reads, so a renderer wired to a different observation than the
                // probe actually made cannot pass silently.
                cameraLiveAdmissionBlockingReasons(environment, observation).forEach { reason ->
                    assertContains(record, reason)
                }

                // `println` never reaches `am instrument -w` output; the record must be reported as
                // an instrumentation status, exactly once, mirroring the five-run and process-kill
                // gates.
                instrumentation.sendStatus(
                    CAMERA_PROBE_STATUS_CODE,
                    Bundle().apply { putString(CAMERA_PROBE_TAG, record) },
                )
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

    private companion object {
        const val EMITTED_BY = "com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest"
        const val CAMERA_PROBE_TAG = "CameraCapabilityEvidence"
        const val CAMERA_PROBE_STATUS_CODE = 4
    }
}
