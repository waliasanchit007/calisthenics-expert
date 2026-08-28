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
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.telemetry.PipelineTelemetryRecordingMode
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.hash.Sha256Digest
import java.io.File
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Produces only typed references to three short, non-participant live sessions.
 *
 * The durable manifest, pose stream, and SHORT_ATTRIBUTION telemetry stream are the authority;
 * the host checker transfers and validates those exact bytes. This test deliberately never keeps
 * stage events in memory and never declares an attribution verdict from instrumentation.
 */
class M05BAttributionDeviceGateTest {

    @Test
    fun publishesFreshBoundedRunsInTenFifteenThirtyOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantCameraPermission(instrumentation, context.packageName)
        val lifecycleOwner = InstrumentationLifecycleOwner()
        instrumentation.runOnMainSync { lifecycleOwner.resume() }
        val capability = CameraCapabilityProbe(
            context = context,
            lifecycleOwner = lifecycleOwner,
            probeGitSha = BuildConfig.PROBE_GIT_SHA,
        ).run().toCameraObservation()
        val roots = assertIs<StorageRootsResult.Ready>(AndroidStorageRoots.from(context)).roots
        val provenance = parseModelProvenance(
            context.assets.open("replay/live_model_provenance.json").use { it.readBytes().decodeToString() },
        )
        val identity = buildIdentity(context, provenance)
        val sessions = mutableListOf<Map<String, Any>>()

        try {
            for (cadence in CADENCES) {
                // The composition is intentionally created inside the loop: detector construction,
                // runner state, callback accounting, and telemetry sequence all belong to one run.
                val composition = LiveCameraComposition.fromAndroid(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    cameraCapability = capability,
                    permissionGranted = true,
                )
                var completion: LiveCameraCompletion? = null
                try {
                    val started = composition.start(
                        configuredInferenceFps = cadence,
                        operatorAttestedNoPerson = true,
                        recordingMode = PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION,
                    )
                    assertIs<LiveCameraStartResult.Started>(started, "cadence=$cadence must start")
                    awaitBoundedCapture(composition, cadence)
                    val finished = composition.stop()
                    completion = finished
                    assertIs<FinalizationResult.Published>(
                        finished.finalization,
                        "cadence=$cadence must publish before the next run",
                    )
                    val sessionId = finished.sessionId.value
                    val export = File(roots.exportsRoot, sessionId)
                    val manifest = File(export, "manifest.json")
                    val frames = File(export, "frames.ndjson")
                    val telemetry = File(export, "telemetry.ndjson")
                    assertTrue(manifest.isFile, "cadence=$cadence manifest must be durable")
                    assertTrue(frames.isFile, "cadence=$cadence frames must be durable")
                    assertTrue(telemetry.isFile, "cadence=$cadence telemetry must be durable")
                    sessions += mapOf(
                        "session_id" to sessionId,
                        "export_relative_path" to "exports/$sessionId",
                        "requested_camera_fps" to 30,
                        "configured_inference_fps" to cadence,
                        "manifest_sha256" to Sha256Digest.hex(manifest.readBytes()),
                        "frames_sha256" to Sha256Digest.hex(frames.readBytes()),
                        "telemetry_sha256" to Sha256Digest.hex(telemetry.readBytes()),
                    )
                } finally {
                    if (completion == null) composition.abandon()
                }
            }
        } finally {
            instrumentation.runOnMainSync { lifecycleOwner.destroy() }
        }

        assertTrue(sessions.map { it["configured_inference_fps"] } == CADENCES)
        val record = buildRecord(
            device = mapOf(
                "device_class" to androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                "device_identity" to "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                "device_fingerprint" to Build.FINGERPRINT,
                "api_level" to Build.VERSION.SDK_INT,
                "android_release" to Build.VERSION.RELEASE,
                "abi" to Build.SUPPORTED_ABIS.joinToString(","),
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "marketing_name" to readMarketingName(),
            ),
            identity = identity,
            sessions = sessions,
        )
        instrumentation.sendStatus(
            STATUS_CODE,
            Bundle().apply { putString(STATUS_TAG, record) },
        )
    }

    private fun awaitBoundedCapture(composition: LiveCameraComposition, cadence: Int) {
        val deadline = System.nanoTime() + CAPTURE_TIMEOUT_NS
        while (System.nanoTime() < deadline) {
            val snapshot = composition.snapshot()
            if (snapshot != null && snapshot.counts.cameraFramesReceived > 0L &&
                snapshot.counts.inferenceFramesSubmitted > 0L &&
                composition.observedCaptureSpanNs()?.let { it >= MINIMUM_CAPTURE_SPAN_NS } == true
            ) return
            Thread.sleep(POLL_MILLIS)
        }
        error("cadence=$cadence did not produce a bounded live observation")
    }

    private fun buildIdentity(
        context: android.content.Context,
        provenance: com.calisthenics.diagnostic.model.ModelProvenance,
    ): Map<String, String> = mapOf(
        "app_git_sha" to BuildConfig.PROBE_GIT_SHA,
        "probe_git_sha" to BuildConfig.PROBE_GIT_SHA,
        "apk_sha256" to Sha256Digest.hex(File(context.applicationInfo.sourceDir).readBytes()),
        "model_sha256" to provenance.sha256,
        "runtime_version" to Build.VERSION.RELEASE,
        "backend_version" to provenance.tasksVisionVersion,
        "delegate" to provenance.delegate.lowercase(),
    )

    private fun buildRecord(
        device: Map<String, Any>,
        identity: Map<String, String>,
        sessions: List<Map<String, Any>>,
    ): String = kotlinx.serialization.json.buildJsonObject {
        put("record_kind", RECORD_KIND)
        put("suite", SUITE)
        put("device", kotlinx.serialization.json.buildJsonObject {
            device.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    else -> error("unsupported device field: $key")
                }
            }
        })
        put("identity", kotlinx.serialization.json.buildJsonObject {
            identity.forEach { (key, value) -> put(key, value) }
        })
        putJsonArray("sessions") {
            sessions.forEach { session ->
                add(kotlinx.serialization.json.buildJsonObject {
                    session.forEach { (key, value) ->
                        when (value) {
                            is String -> put(key, value)
                            is Int -> put(key, value)
                            else -> error("unsupported session field: $key")
                        }
                    }
                })
            }
        }
    }.toString()

    private fun grantCameraPermission(
        instrumentation: android.app.Instrumentation,
        packageName: String,
    ) {
        if (ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
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

    companion object {
        const val STATUS_TAG = "M05BAttributionEvidence"
        const val STATUS_CODE = 4
        const val SUITE = "com.calisthenics.diagnostic.composition.M05BAttributionDeviceGateTest"
        const val RECORD_KIND = "m05b_short_attribution_session_references"
        private val CADENCES = listOf(10, 15, 30)
        private const val CAPTURE_TIMEOUT_NS = 15_000_000_000L
        private const val MINIMUM_CAPTURE_SPAN_NS = 1_000_000_000L
        private const val POLL_MILLIS = 100L
    }
}
