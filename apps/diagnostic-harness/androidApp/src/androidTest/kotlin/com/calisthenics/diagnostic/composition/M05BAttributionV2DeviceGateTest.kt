package com.calisthenics.diagnostic.composition

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.camera.CameraXFrameSource
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.model.parseModelProvenance
import com.calisthenics.diagnostic.probe.CameraCapabilityProbe
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.study.AttributionV2DeviceSource
import com.calisthenics.diagnostic.study.AttributionV2RunIdentity
import com.calisthenics.diagnostic.study.M05BAttributionV2Protocol
import java.io.File
import java.util.UUID
import kotlin.test.assertIs
import org.junit.Test

/** Internal device gate for bounded, non-participant attribution-v2 reference output. */
class M05BAttributionV2DeviceGateTest {
    @Test
    fun runBoundedAttributionV2AndEmitReferenceOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantCameraPermission(instrumentation, context.packageName)
        val lifecycleOwner = InstrumentationLifecycleOwner()
        instrumentation.runOnMainSync { lifecycleOwner.resume() }
        try {
            val capability = CameraCapabilityProbe(
                context = context,
                lifecycleOwner = lifecycleOwner,
                probeGitSha = BuildConfig.PROBE_GIT_SHA,
            ).run().toCameraObservation()
            val roots = assertIs<StorageRootsResult.Ready>(AndroidStorageRoots.from(context)).roots
            val model = parseModelProvenance(
                context.assets.open("replay/live_model_provenance.json").use { it.readBytes().decodeToString() },
            )
            val testContext = instrumentation.context
            val identity = AttributionV2RunIdentity(
                appGitSha = BuildConfig.PROBE_GIT_SHA,
                probeGitSha = BuildConfig.PROBE_GIT_SHA,
                appApkSha256 = Sha256Digest.hex(File(context.applicationInfo.sourceDir).readBytes()),
                testApkSha256 = Sha256Digest.hex(File(testContext.applicationInfo.sourceDir).readBytes()),
                modelSha256 = model.sha256,
                runtimeVersion = Build.VERSION.RELEASE,
                backendVersion = model.tasksVisionVersion,
                delegate = model.delegate.lowercase(),
            )
            val thermalManager = checkNotNull(context.getSystemService(PowerManager::class.java))
            val runner = M05BAttributionV2Runner(
                M05BAttributionV2Runner.Dependencies(
                    protocolSha256 = CANONICAL_PROTOCOL_SHA256,
                    device = AttributionV2DeviceSource(
                        deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                        deviceIdentity = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                        manufacturer = Build.MANUFACTURER,
                        model = Build.MODEL,
                        apiLevel = Build.VERSION.SDK_INT,
                        androidRelease = Build.VERSION.RELEASE,
                        osBuild = Build.ID,
                    ),
                    identity = identity,
                    sourceArmFactory = {
                        M05BAttributionV2CameraXSourceArm(CameraXFrameSource(context, lifecycleOwner))
                    },
                    backendArmFactory = {
                        AndroidM05BAttributionV2Backend(
                            context = context,
                            lifecycleOwner = lifecycleOwner,
                            cameraCapability = capability,
                            permissionGranted = true,
                            thermalStatus = { thermalManager.currentThermalStatus },
                        )
                    },
                    selector = M05BAttributionV2Selector { timestamps, cadence ->
                        com.calisthenics.diagnostic.study.productionSelectorTimestamps(timestamps, cadence)
                    },
                    thermal = AndroidM05BAttributionV2Thermal(thermalManager),
                    storage = AndroidM05BAttributionV2ArtifactStorage(File(roots.diagnosticHarnessRoot)),
                    statusSink = M05BAttributionV2StatusSink { record ->
                        instrumentation.sendStatus(
                            STATUS_CODE,
                            Bundle().apply { putString(STATUS_TAG, record) },
                        )
                    },
                ),
            )
            runner.run(UUID.randomUUID().toString())
        } finally {
            instrumentation.runOnMainSync { lifecycleOwner.destroy() }
        }
    }

    private fun grantCameraPermission(instrumentation: android.app.Instrumentation, packageName: String) {
        if (ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
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
        const val STATUS_TAG = "M05BAttributionV2Evidence"
        const val STATUS_CODE = 4
        const val CANONICAL_PROTOCOL_SHA256 = "b2cd9efec89186806b0195db1dcbfa130450973ebbb25a256696c736eaf96707"
    }
}
