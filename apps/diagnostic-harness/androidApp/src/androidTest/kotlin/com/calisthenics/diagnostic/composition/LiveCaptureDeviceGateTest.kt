package com.calisthenics.diagnostic.composition

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.MainActivity
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.probe.CameraCapabilityProbe
import com.calisthenics.diagnostic.probe.readMarketingName
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.live.LiveCaptureCameraObservation
import com.calisthenics.diagnostic.live.LiveCaptureCounts
import com.calisthenics.diagnostic.live.LiveCaptureEnvironment
import com.calisthenics.diagnostic.live.LiveCaptureEvidence
import com.calisthenics.diagnostic.live.LiveCaptureManifestDevice
import com.calisthenics.diagnostic.live.renderLiveCaptureEvidence
import com.calisthenics.diagnostic.session.FinalizationResult
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * The physical S25 live evidence producer. It deliberately emits a record for blocked observations
 * too: the host checker decides whether the observed run is admissible, while this test only owns
 * the bounded capture and the typed status-channel handoff.
 */
class LiveCaptureDeviceGateTest {
    @Test
    fun runsBoundedTenFpsLiveCaptureAndEmitsExactlyOneSanitizedEvidenceRecord() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var scenario: ActivityScenario<MainActivity>? = null
        var activity: MainActivity? = null
        var composition: LiveCameraComposition? = null
        var completion: LiveCameraCompletion? = null
        var observation = CameraCapabilityObservation(
            cameraId = "unknown",
            timestampSource = "UNKNOWN",
            sourceDataspaceRaw = null,
            sourceColorStandard = null,
            sourceColorRange = null,
            underlyingImageAvailable = false,
            pixelFormat = "UNKNOWN",
        )
        var runFailureCode: String? = null
        var instrumentationSucceeded = false

        try {
            grantCameraPermission(instrumentation, context.packageName)
            val launchedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = launchedScenario
            launchedScenario.onActivity { activity = it }
            val owner = checkNotNull(activity)
            val probe = CameraCapabilityProbe(
                context = context,
                lifecycleOwner = owner,
                timeoutMillis = PROBE_TIMEOUT_MILLIS,
                probeGitSha = BuildConfig.PROBE_GIT_SHA,
            ).run()
            observation = probe.toCameraObservation()
            composition = LiveCameraComposition.fromAndroid(
                context = context,
                lifecycleOwner = owner,
                cameraCapability = observation,
                permissionGranted = true,
            )
            when (val started = composition!!.start(10)) {
                is LiveCameraStartResult.Unavailable -> runFailureCode = "live_capture_failed"
                is LiveCameraStartResult.Started -> {
                    val deadline = System.nanoTime() + CAPTURE_TIMEOUT_MILLIS * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        val snapshot = composition!!.snapshot()
                        if (snapshot != null &&
                            snapshot.counts.cameraFramesReceived > 0L &&
                            snapshot.counts.inferenceFramesSubmitted > 0L &&
                            snapshot.counts.inferenceRecordsEmitted > 0L
                        ) break
                        Thread.sleep(POLL_MILLIS)
                    }
                    val snapshot = composition!!.snapshot()
                    if (snapshot == null ||
                        snapshot.counts.cameraFramesReceived <= 0L ||
                        snapshot.counts.inferenceFramesSubmitted <= 0L ||
                        snapshot.counts.inferenceRecordsEmitted <= 0L
                    ) {
                        runFailureCode = if (composition!!.internalFailure?.code == "live_inference_stalled") {
                            "live_inference_stalled"
                        } else {
                            "live_capture_timeout"
                        }
                    }
                    completion = composition!!.stop()
                    if (completion!!.internalFailure?.code == "live_inference_stalled") {
                        runFailureCode = "live_inference_stalled"
                    }
                    if (completion!!.finalization !is FinalizationResult.Published && runFailureCode == null) {
                        runFailureCode = "live_capture_failed"
                    }
                }
            }
            instrumentationSucceeded = true
        } catch (_: Throwable) {
            runFailureCode = runFailureCode ?: "live_capture_failed"
        } finally {
            if (completion == null) composition?.abandon()
            val snapshot = completion?.snapshot ?: composition?.snapshot()
            val manifest = composition?.manifest()
            val counts = snapshot?.counts
            val unclassified = counts?.let {
                it.cameraFramesReceived - it.inferenceFramesSubmitted - it.framesSkippedBeforeInference
            } ?: 0L
            val evidence = LiveCaptureEvidence(
                emittedBy = EMITTED_BY,
                sessionId = completion?.sessionId?.value ?: manifest?.sessionId?.value ?: UNKNOWN_SESSION_ID,
                environment = LiveCaptureEnvironment(
                    deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                    deviceIdentity = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                    deviceFingerprint = Build.FINGERPRINT,
                    apiLevel = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE,
                    abi = Build.SUPPORTED_ABIS.joinToString(","),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    marketingName = readMarketingName(),
                    chipset = runtimeChipset(),
                ),
                manifestDevice = manifest?.device?.let {
                    LiveCaptureManifestDevice(it.platform, it.osVersion, it.deviceModel, it.chipset)
                } ?: LiveCaptureManifestDevice("unknown", "unknown", "unknown", null),
                camera = LiveCaptureCameraObservation(
                    cameraId = observation.cameraId,
                    timestampSource = observation.timestampSource,
                    sourceDataspaceRaw = observation.sourceDataspaceRaw,
                    sourceColorStandard = observation.sourceColorStandard,
                    sourceColorRange = observation.sourceColorRange,
                    underlyingImageAvailable = observation.underlyingImageAvailable,
                    pixelFormat = observation.pixelFormat,
                ),
                counts = LiveCaptureCounts(
                    cameraFramesReceived = counts?.cameraFramesReceived ?: 0L,
                    inferenceFramesSubmitted = counts?.inferenceFramesSubmitted ?: 0L,
                    inferenceRecordsEmitted = counts?.inferenceRecordsEmitted ?: 0L,
                    unclassifiedDeliveredCallbacks = unclassified,
                ),
                referenceValidationSucceeded = false,
                instrumentationSucceeded = instrumentationSucceeded,
                runFailureCode = runFailureCode,
            )
            instrumentation.sendStatus(
                STATUS_CODE,
                Bundle().apply { putString(STATUS_TAG, renderLiveCaptureEvidence(evidence)) },
            )
            scenario?.close()
        }
    }

    private fun grantCameraPermission(instrumentation: android.app.Instrumentation, packageName: String) {
        if (ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
        }
    }

    private fun runtimeChipset(): String =
        Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: Build.HARDWARE

    companion object {
        const val STATUS_TAG = "LiveCaptureEvidence"
        const val STATUS_CODE = 4
        private const val EMITTED_BY = "com.calisthenics.diagnostic.composition.LiveCaptureDeviceGateTest"
        private const val UNKNOWN_SESSION_ID = "00000000-0000-4000-8000-000000000000"
        private const val PROBE_TIMEOUT_MILLIS = 5_000L
        private const val CAPTURE_TIMEOUT_MILLIS = 15_000L
        private const val POLL_MILLIS = 100L
    }
}
