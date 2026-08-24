package com.calisthenics.diagnostic.composition

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.probe.CameraCapabilityProbe
import com.calisthenics.diagnostic.probe.readMarketingName
import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.live.LiveCaptureCameraObservation
import com.calisthenics.diagnostic.live.LiveCaptureCadenceDiagnostics
import com.calisthenics.diagnostic.live.LiveCaptureCounts
import com.calisthenics.diagnostic.live.LiveCaptureEnvironment
import com.calisthenics.diagnostic.live.LiveCaptureEvidence
import com.calisthenics.diagnostic.live.LiveCaptureFailureIdentity
import com.calisthenics.diagnostic.live.LiveCaptureManifestDevice
import com.calisthenics.diagnostic.live.sanitizeLiveCaptureFailureDetail
import com.calisthenics.diagnostic.live.renderLiveCaptureEvidence
import com.calisthenics.diagnostic.session.FinalizationResult
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
        var lifecycleOwner: InstrumentationLifecycleOwner? = null
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
        var runFailureDetail: String? = null
        var instrumentationSucceeded = false

        try {
            grantCameraPermission(instrumentation, context.packageName)
            val owner = InstrumentationLifecycleOwner()
            lifecycleOwner = owner
            instrumentation.runOnMainSync { owner.resume() }
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
            when (val started = composition!!.start(10, operatorAttestedNoPerson = true)) {
                is LiveCameraStartResult.Unavailable -> {
                    runFailureCode = started.reason.code
                    runFailureDetail = when (val reason = started.reason) {
                        is LiveUnavailableReason.Initialization ->
                            sanitizeLiveCaptureFailureDetail(reason.detail)
                        is LiveUnavailableReason.CameraCapability ->
                            reason.blockingReasons.joinToString(",")
                        LiveUnavailableReason.CameraPermissionDenied -> null
                        LiveUnavailableReason.OperatorAttestationRequired -> null
                        is LiveUnavailableReason.ThermalApi -> sanitizeLiveCaptureFailureDetail(reason.detail)
                    }
                }
                is LiveCameraStartResult.Started -> {
                    val deadline = System.nanoTime() + CAPTURE_TIMEOUT_MILLIS * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        val snapshot = composition!!.snapshot()
                        if (snapshot != null &&
                            snapshot.counts.cameraFramesReceived > 0L &&
                            snapshot.counts.inferenceFramesSubmitted > 0L &&
                            snapshot.counts.inferenceRecordsEmitted > 0L &&
                            composition!!.observedCaptureSpanNs()?.let { it >= MINIMUM_CAPTURE_SPAN_NANOS } == true
                        ) break
                        Thread.sleep(POLL_MILLIS)
                    }
                    val snapshot = composition!!.snapshot()
                    if (snapshot == null ||
                        snapshot.counts.cameraFramesReceived <= 0L ||
                        snapshot.counts.inferenceFramesSubmitted <= 0L ||
                        snapshot.counts.inferenceRecordsEmitted <= 0L ||
                        composition!!.observedCaptureSpanNs()?.let { it >= MINIMUM_CAPTURE_SPAN_NANOS } != true
                    ) {
                        runFailureCode = com.calisthenics.diagnostic.liveCaptureFailureCodeOr(
                            composition!!.internalFailure,
                            "live_capture_timeout",
                        )
                    }
                    completion = composition!!.stop()
                    runFailureCode = com.calisthenics.diagnostic.liveCaptureFailureCodeOr(
                        completion!!.internalFailure,
                        runFailureCode,
                    )
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
            lifecycleOwner?.let { owner -> instrumentation.runOnMainSync { owner.destroy() } }
            val snapshot = completion?.snapshot ?: composition?.snapshot()
            val manifest = composition?.manifest()
            val telemetry = composition?.captureEvidenceTelemetry()
            val counts = snapshot?.counts
            val classificationGap = counts?.let {
                it.cameraFramesReceived - it.inferenceFramesSubmitted - it.framesSkippedBeforeInference
            } ?: 0L
            val cadenceDiagnostics = completion?.cadenceDiagnostics
                ?: LiveCaptureCadenceDiagnostics.fromAdmittedTimestamps(
                    timestampsNs = emptyList(),
                    requestedCameraFps = 30,
                    deliveredCallbackClassificationGap = classificationGap,
                )
            val evidence = LiveCaptureEvidence(
                emittedBy = EMITTED_BY,
                sessionId = completion?.sessionId?.value ?: manifest?.sessionId?.value ?: UNKNOWN_SESSION_ID,
                harnessGitSha = BuildConfig.PROBE_GIT_SHA,
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
                    captureWidth = telemetry?.captureWidth,
                    captureHeight = telemetry?.captureHeight,
                    orientationDegrees = telemetry?.orientationDegrees,
                    mirrored = telemetry?.mirrored,
                ),
                thermal = thermalEvidenceFromTelemetry(telemetry),
                counts = LiveCaptureCounts(
                    cameraFramesReceived = counts?.cameraFramesReceived ?: 0L,
                    inferenceFramesSubmitted = counts?.inferenceFramesSubmitted ?: 0L,
                    inferenceRecordsEmitted = counts?.inferenceRecordsEmitted ?: 0L,
                    deliveredCallbackClassificationGap = classificationGap,
                    cadenceDiagnostics = cadenceDiagnostics,
                    inferenceFailures = counts?.inferenceFailures ?: 0L,
                    framesSkippedBeforeInference = counts?.framesSkippedBeforeInference ?: 0L,
                    framesSkippedBeforeInferenceByReason = counts?.framesSkippedBeforeInferenceByReason ?: emptyMap(),
                ),
                referenceValidationSucceeded = false,
                instrumentationSucceeded = instrumentationSucceeded,
                runFailureCode = runFailureCode,
                runFailureDetail = runFailureDetail,
                inferenceFailureIdentities = snapshot?.failures?.map {
                    LiveCaptureFailureIdentity(
                        submissionIndex = it.submissionIndex,
                        failureSource = it.failureSource,
                        classification = it.classification,
                        sourceErrorCode = it.sourceErrorCode,
                    )
                } ?: emptyList(),
                m05Admission = composition?.m05Admission(),
            )
            instrumentation.sendStatus(
                STATUS_CODE,
                Bundle().apply { putString(STATUS_TAG, renderLiveCaptureEvidence(evidence)) },
            )
        }
    }

    private class InstrumentationLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun resume() {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            registry.currentState = Lifecycle.State.DESTROYED
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
        private const val MINIMUM_CAPTURE_SPAN_NANOS = 3_000_000_000L
        private const val POLL_MILLIS = 100L
    }
}
