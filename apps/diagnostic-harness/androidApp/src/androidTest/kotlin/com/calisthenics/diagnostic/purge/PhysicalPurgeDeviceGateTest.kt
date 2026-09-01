package com.calisthenics.diagnostic.purge

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
import com.calisthenics.diagnostic.composition.LiveCameraComposition
import com.calisthenics.diagnostic.composition.LiveCameraStartResult
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.probe.CameraCapabilityProbe
import com.calisthenics.diagnostic.probe.readMarketingName
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.video.VideoDemuxVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The only physical Task 5b deletion rehearsal. It creates and deletes one complete reserved-ID
 * synthetic session. The host independently scans the registered roots and validates the durable
 * tombstone; this device result is an observation, never the final verdict.
 */
class PhysicalPurgeDeviceGateTest {
    @Test
    fun publishesAndPurgesOneCompleteVideoEnabledSyntheticSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val expectedAppSha256 = requireNotNull(arguments.getString("app_apk_sha256"))
        val expectedTestSha256 = requireNotNull(arguments.getString("test_apk_sha256"))
        val roots = requireStorageRoots(context)
        var owner: InstrumentationLifecycleOwner? = null
        var composition: LiveCameraComposition? = null

        try {
            grantCameraPermission(instrumentation, context.packageName)
            owner = InstrumentationLifecycleOwner()
            instrumentation.runOnMainSync { owner.resume() }
            val observation = CameraCapabilityProbe(
                context = context,
                lifecycleOwner = owner,
                timeoutMillis = PROBE_TIMEOUT_MILLIS,
                probeGitSha = BuildConfig.PROBE_GIT_SHA,
            ).run().toCameraObservation()
            composition = LiveCameraComposition.fromAndroid(
                context = context,
                lifecycleOwner = owner,
                cameraCapability = observation,
                permissionGranted = true,
                recordVideo = true,
            )
            assertIs<LiveCameraStartResult.Started>(
                composition.start(CADENCE_FPS, operatorAttestedNoPerson = true),
            )
            awaitBoundedCapture(composition)
            val sessionId = requireNotNull(composition.manifest()?.sessionId?.value)
            assertIs<FinalizationResult.Published>(composition.stop().finalization)

            val artifacts = requireCompleteVideoSession(roots, sessionId)
            createTestOwnedNonExportResidues(roots, sessionId)
            val filesystem = FilePurgeFilesystem(roots)
            val purgeRecord = AndroidSyntheticPurge(filesystem, BuildConfig.PROBE_GIT_SHA).purge(
                sessionId = sessionId,
                operationId = UUID.randomUUID().toString(),
            )
            assertEquals(PurgeState.PURGE_COMPLETE, purgeRecord.state)
            val reserved = requireNotNull(PurgeSessionId.parseOrNull(sessionId))
            PurgeFilesystem.devicePurgeLocations.forEach { location ->
                assertTrue(filesystem.postScan(reserved, location).ownedRemaining == 0)
            }

            val tombstone = File(
                roots.tombstoneRoot,
                "$sessionId.purge-tombstone.json",
            )
            assertTrue(tombstone.isFile && tombstone.length() > 0L)
            val refusal = assertIs<SessionAdmissionDecision.Refused>(
                TombstoneSessionAdmissionFence(filesystem).check(
                    reserved,
                    SessionAdmissionPhase.WRITER_OPEN,
                ),
            )
            assertEquals("PURGE.SESSION_TOMBSTONED", refusal.code)

            val record = PhysicalPurgeObservation(
                sessionId = sessionId,
                harnessGitSha = BuildConfig.PROBE_GIT_SHA,
                appApkSha256 = expectedAppSha256,
                testApkSha256 = expectedTestSha256,
                environment = PhysicalPurgeEnvironment(
                    deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    marketingName = readMarketingName(),
                    apiLevel = Build.VERSION.SDK_INT,
                ),
                artifacts = artifacts,
                purgeRecord = purgeRecord,
                tombstoneSha256 = sha256Of(tombstone),
                tombstoneByteLength = tombstone.length(),
                readmissionRefusalCode = refusal.code,
                blockingReasons = emptyList(),
            ).render()
            instrumentation.sendStatus(
                STATUS_CODE,
                Bundle().apply { putString(STATUS_TAG, record) },
            )
        } finally {
            runCatching { composition?.abandon() }
            owner?.let { lifecycleOwner ->
                instrumentation.runOnMainSync { lifecycleOwner.destroy() }
            }
        }
    }

    private fun requireCompleteVideoSession(
        roots: StorageRoots,
        sessionId: String,
    ): PhysicalPurgeArtifactPreconditions {
        val committed = File(roots.exportsRoot, sessionId)
        val manifest = File(committed, "manifest.json")
        val video = File(committed, "video.mp4")
        val pose = File(committed, "frames.ndjson")
        val synchronization = File(committed, "synchronization-evidence.json")
        assertTrue(manifest.isFile && manifest.length() > 0L)
        assertTrue(video.isFile && video.length() > 0L)
        assertTrue(pose.isFile && pose.length() > 0L)
        assertTrue(synchronization.isFile && synchronization.length() > 0L)
        val demuxed = VideoDemuxVerifier.verify(video.absolutePath)
        assertTrue(demuxed.videoPtsUs.isNotEmpty())
        assertEquals(demuxed.videoPtsUs.size, demuxed.metadataPtsUs.size)
        return PhysicalPurgeArtifactPreconditions(
            manifestPresent = true,
            videoPresent = true,
            poseStreamPresent = true,
            synchronizationEvidencePresent = true,
        )
    }

    private fun createTestOwnedNonExportResidues(roots: StorageRoots, sessionId: String) {
        val staging = Path.of(roots.stagingRoot).resolve("$sessionId.1.staging")
        val transfer = Path.of(roots.transferStagingRoot).resolve("$sessionId.transfer.bundle")
        listOf(staging, transfer).forEach { target ->
            Files.createDirectories(target)
            Files.write(target.resolve("owned.bin"), SYNTHETIC_BYTES)
        }
    }

    private fun awaitBoundedCapture(composition: LiveCameraComposition) {
        val deadline = System.nanoTime() + CAPTURE_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (composition.snapshot()?.counts?.cameraFramesReceived?.let { it > 0L } == true &&
                (composition.videoAccounting()?.encoded ?: 0) >= MINIMUM_ENCODED_FRAMES
            ) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        error("bounded synthetic capture did not produce enough encoded frames")
    }

    private fun requireStorageRoots(context: android.content.Context): StorageRoots =
        when (val result = AndroidStorageRoots.from(context)) {
            is StorageRootsResult.Ready -> result.roots
            is StorageRootsResult.Unavailable -> error(result.reason)
        }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(block)
                if (read <= 0) break
                digest.update(block, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun grantCameraPermission(
        instrumentation: android.app.Instrumentation,
        packageName: String,
    ) {
        if (ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
        }
    }

    private class InstrumentationLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        const val STATUS_TAG = "M05BPhysicalPurgeEvidence"
        const val STATUS_CODE = 4
        private const val CADENCE_FPS = 10
        private const val PROBE_TIMEOUT_MILLIS = 10_000L
        private const val CAPTURE_TIMEOUT_MILLIS = 20_000L
        private const val POLL_MILLIS = 50L
        private const val MINIMUM_ENCODED_FRAMES = 5
        private val SYNTHETIC_BYTES = "m05b-synthetic-purge-owned".encodeToByteArray()
    }
}
