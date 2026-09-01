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
import com.calisthenics.diagnostic.video.DemuxedVideoEvidence
import com.calisthenics.diagnostic.video.VideoDemuxVerifier
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/**
 * The physical S25 synchronized-video producer.
 *
 * Like its siblings it emits a record for blocked observations too: this test owns only the
 * bounded non-participant capture, the demux readback, and the typed status-channel handoff. The
 * host checker owns the verdict, including whether the observed codec matches the admitted Task 3
 * probe.
 *
 * The run is short and non-participant. Nothing here attests that a person was absent; that
 * remains a separate physical protocol, and the operator attestation is passed through unchanged.
 */
class SynchronizedVideoDeviceGateTest {

    @Test
    fun recordsOneShortSynchronizedVideoSessionAndEmitsExactlyOneSanitizedRecord() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var lifecycleOwner: InstrumentationLifecycleOwner? = null
        var composition: LiveCameraComposition? = null
        val blocking = mutableListOf<String>()
        var demuxed: DemuxedVideoEvidence? = null
        var sessionId = "unknown"
        var observation = CameraCapabilityObservation(
            cameraId = "unknown",
            timestampSource = "UNKNOWN",
            sourceDataspaceRaw = null,
            sourceColorStandard = null,
            sourceColorRange = null,
            underlyingImageAvailable = false,
            pixelFormat = "UNKNOWN",
        )

        // App-private, outside every registered export root: this gate observes the encoder, it
        // does not publish a study artifact.
        val videoFile = File(context.filesDir, "m05b-synchronized-video-gate.mp4")
        videoFile.delete()

        try {
            grantCameraPermission(instrumentation, context.packageName)
            val owner = InstrumentationLifecycleOwner()
            lifecycleOwner = owner
            instrumentation.runOnMainSync { owner.resume() }

            observation = CameraCapabilityProbe(
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
                videoOutputPath = videoFile.absolutePath,
            )

            when (val started = composition.start(CADENCE, operatorAttestedNoPerson = true)) {
                is LiveCameraStartResult.Unavailable -> blocking += "live_start_unavailable"
                is LiveCameraStartResult.Started -> {
                    awaitBoundedCapture(composition)
                    sessionId = composition.manifest()?.sessionId?.value ?: "unknown"
                    composition.stop()
                }
            }

            if (blocking.isEmpty()) {
                if (!videoFile.isFile || videoFile.length() == 0L) {
                    blocking += "video_container_absent"
                } else {
                    demuxed = runCatching { VideoDemuxVerifier.verify(videoFile.absolutePath) }
                        .getOrElse { error ->
                            // Report why. A generic failure reason hid a real encoder defect
                            // behind an empty-container symptom for two device runs.
                            val detail = error.message?.take(120)?.replace(Regex("[^A-Za-z0-9 _.:=,-]"), "")
                            blocking += "video_demux_failed:" + (detail ?: error.javaClass.simpleName)
                            null
                        }
                }
            }

            val accounting = composition?.videoAccounting()
            if (accounting != null && accounting.encoded <= 0) blocking += "no_frame_reached_the_encoder"
            if (accounting != null && composition?.videoWriterResult() == null) {
                blocking += "video_writer_never_finalized"
            }
            if (demuxed != null && demuxed.videoPtsUs.isEmpty()) blocking += "container_has_no_video_samples"
            if (demuxed != null && demuxed.videoPtsUs.size != demuxed.metadataPtsUs.size) {
                blocking += "video_and_metadata_sample_counts_disagree"
            }
        } catch (error: Throwable) {
            blocking += "gate_threw:${error.javaClass.simpleName}"
        } finally {
            // Record a finalization failure rather than swallowing it. Swallowing this hid the
            // real encoder defect behind a generic empty-container symptom for two device runs.
            runCatching { composition?.abandon() }
                .onFailure { blocking += "video_finalization_failed:" + it.javaClass.simpleName }
            lifecycleOwner?.let { owner -> instrumentation.runOnMainSync { owner.destroy() } }
        }

        val record = renderRecord(
            sessionId = sessionId,
            observation = observation,
            demuxed = demuxed,
            videoFile = videoFile,
            accounting = composition?.videoAccounting(),
            writerResult = composition?.videoWriterResult(),
            muxerDiagnostics = composition?.videoMuxerDiagnostics().orEmpty(),
            encoderName = composition?.videoEncoderName(),
            blocking = blocking.distinct().sorted(),
        )
        // Deliberately left in place: the host pulls and verifies it byte-exactly, which is
        // stronger than the device asserting its own digest. The next run deletes it first.

        instrumentation.sendStatus(STATUS_CODE, Bundle().apply { putString(STATUS_TAG, record) })
    }

    private fun awaitBoundedCapture(composition: LiveCameraComposition) {
        val deadline = System.nanoTime() + CAPTURE_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val snapshot = composition.snapshot()
            val encoded = composition.videoAccounting()?.encoded ?: 0
            if (snapshot != null &&
                snapshot.counts.cameraFramesReceived > 0L &&
                encoded >= MINIMUM_ENCODED_FRAMES
            ) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun renderRecord(
        sessionId: String,
        observation: CameraCapabilityObservation,
        demuxed: DemuxedVideoEvidence?,
        videoFile: File,
        accounting: com.calisthenics.diagnostic.video.VideoAccounting?,
        writerResult: com.calisthenics.diagnostic.video.VideoWriterResult?,
        muxerDiagnostics: Map<String, Long>,
        encoderName: String?,
        blocking: List<String>,
    ): String = buildJsonObject {
        put("artifact_schema_version", "1.0.0")
        put("record_kind", "synchronized_video_device_gate_observation")
        put("sanitized", true)
        put("contains_landmark_coordinates", false)
        put("non_participant_observation", true)
        put("observation_subject", "device_synchronized_video_publication")
        put("session_id", sessionId)
        putJsonObject("environment") {
            put("device_class", androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE))
            put("device_identity", "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}")
            put("device_fingerprint", Build.FINGERPRINT)
            put("api_level", Build.VERSION.SDK_INT)
            put("android_release", Build.VERSION.RELEASE)
            put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("marketing_name", readMarketingName())
        }
        putJsonObject("provenance") {
            // Observed, not asserted. The host compares these against the admitted probe.
            put("harness_git_sha", BuildConfig.PROBE_GIT_SHA)
            // The encoder component name, matching what the Task 3 probe recorded. The demuxed
            // track MIME is reported separately; they are different identifiers.
            put("codec", encoderName)
            put("demuxed_track_mime", demuxed?.codec)
            put("container", demuxed?.container)
            put("camera_id", observation.cameraId)
            put("camera_timestamp_source", observation.timestampSource)
        }
        putJsonObject("artifacts") {
            // A path ref, never an absolute path: the record must stay sanitized.
            put("video_path_ref", videoFile.name)
            put("video_sha256", demuxed?.sha256 ?: sha256Of(videoFile))
            put("video_byte_length", demuxed?.byteLength ?: videoFile.length())
        }
        putJsonObject("counts") {
            put("accepted_frames", accounting?.accepted)
            put("encoded_video_frames", accounting?.encoded)
            put("dropped_before_encoder", accounting?.droppedBeforeEncoder)
            put("encoder_failures", accounting?.encoderFailures)
            put("demuxed_video_samples", demuxed?.videoPtsUs?.size)
            put("demuxed_metadata_samples", demuxed?.metadataPtsUs?.size)
            // Whether finalization actually ran, so an empty container is never unexplained.
            put("writer_finalized", writerResult != null)
            put("muxed_samples", writerResult?.muxedPtsUs?.size)
            muxerDiagnostics.forEach { (key, value) -> put(key, value) }
        }
        putJsonArray("video_pts_us") { demuxed?.videoPtsUs?.forEach { add(it) } }
        putJsonArray("samples") {
            demuxed?.metadataPayloads?.forEachIndexed { index, payload ->
                add(
                    buildJsonObject {
                        put("frame_index", payload.frameIndex)
                        put("rebased_capture_timestamp_ns", payload.rebasedCaptureNs)
                        put("submitted_pts_us", payload.submittedPtsUs)
                        put("demuxed_pts_us", demuxed.videoPtsUs.getOrNull(index))
                    },
                )
            }
        }
        putJsonArray("blocking_reasons") { blocking.forEach { add(it) } }
        put("blocking_result", blocking.isNotEmpty())
    }.toString()

    private fun sha256Of(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(block)
                if (read <= 0) break
                digest.update(block, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun grantCameraPermission(instrumentation: android.app.Instrumentation, packageName: String) {
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
        const val STATUS_TAG = "SynchronizedVideoEvidence"
        const val STATUS_CODE = 4
        private const val CADENCE = 10
        private const val PROBE_TIMEOUT_MILLIS = 10_000L
        private const val CAPTURE_TIMEOUT_MILLIS = 20_000L
        private const val POLL_MILLIS = 50L
        private const val MINIMUM_ENCODED_FRAMES = 5
    }
}
