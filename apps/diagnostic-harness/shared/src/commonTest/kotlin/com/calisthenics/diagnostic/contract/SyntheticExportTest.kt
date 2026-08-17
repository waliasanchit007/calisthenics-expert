package com.calisthenics.diagnostic.contract

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntheticExportTest {
    @Test
    fun syntheticSessionUsesReleasedEncodersDeterministically() {
        val manifest = syntheticManifest()
        val frames = listOf(
            syntheticFrame(frameIndex = 0, captureTimestampNs = 100_000_000, backendTimestampMs = 100, posePresent = false),
            syntheticFrame(frameIndex = 1, captureTimestampNs = 133_333_333, backendTimestampMs = 133, posePresent = true),
        )

        val manifestJson = encodeManifest(manifest)
        val framesNdjson = frames.joinToString(separator = "") { encodeFrameLine(it) }

        assertEquals(manifestJson, encodeManifest(decodeManifest(manifestJson)))
        assertEquals(framesNdjson, frames.joinToString(separator = "") { encodeFrameLine(decodeFrame(it.let(::encodeFrameLine))) })
        assertTrue(framesNdjson.endsWith("\n"))
        assertEquals(33, frames.last().normalizedImageLandmarks!!.size)
    }

    private fun syntheticManifest() = SessionManifest(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c"),
        sessionKind = "fixture_replay",
        participantPseudonymId = "synthetic-kotlin-export-01",
        captureProtocolVersion = SemanticVersion("1.0.0"),
        harnessGitSha = "0".repeat(39) + "6",
        device = Device("android", "synthetic-os-1", "synthetic-kotlin-device", null, true),
        posePipeline = PosePipeline(
            "synthetic_pose_backend",
            "1.1.0",
            "synthetic_pose_model",
            Sha256("0".repeat(63) + "7"),
            "cpu",
            "video",
            1,
            0.5,
            0.5,
            0.5,
            "mediapipe_pose_33_v1",
        ),
        camera = Camera("fixture", 640, 480, 0, false, 30.0, "rgb24", "sRGB", "full", null, "synthetic_fixture_monotonic_ns", "ordered_no_drop"),
        modelInput = ModelInput(256, 256, SemanticVersion("1.0.0"), SemanticVersion("1.0.0")),
        inferenceDrainTimeoutMs = 2000,
        cadence = Cadence(30.0, listOf(CadenceSample(100_000_000, 30.0)), null),
        clock = Clock("synthetic_fixture_monotonic_ns", 100_000_000, "2026-08-17T00:00:00Z", "2026-08-17T00:00:01Z"),
        availableObservations = AvailableObservations(true, true),
        mediaReferences = listOf(MediaReference("conformance_fixture", "synthetic://kotlin-export", Sha256("0".repeat(63) + "8"), null, null)),
        counts = Counts(2, 2, 2, 1, 0, mapOf("backpressure" to 0, "cadence_selection" to 0, "session_stopping" to 0), 0, 0),
        machineInferenceFailures = emptyList(),
        captureQualityObserved = CaptureQualityObserved(true, true, true, "synthetic_uniform", false, "synthetic export; no participant capture"),
        studyContext = mapOf("environment" to JsonPrimitive("synthetic"), "angle_deg_nominal" to JsonPrimitive(0)),
    )

    private fun syntheticFrame(
        frameIndex: Long,
        captureTimestampNs: Long,
        backendTimestampMs: Long,
        posePresent: Boolean,
    ) = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c"),
        frameIndex = frameIndex,
        captureTimestampNs = captureTimestampNs,
        backendTimestampMs = backendTimestampMs,
        inferenceStartNs = captureTimestampNs + 1,
        inferenceEndNs = captureTimestampNs + 2,
        posePresent = posePresent,
        normalizedImageLandmarks = if (posePresent) landmarks(scale = 0.01) else null,
        worldLandmarks = if (posePresent) landmarks(scale = 1.0) else null,
        imageWidth = 640,
        imageHeight = 480,
        framesSkippedSinceLastEmittedRecord = 0,
    )

    private fun landmarks(scale: Double) = (0 until 33).map { index ->
        Landmark(index, null, index * scale, index * scale + scale, index * scale + scale * 2, 0.9, 0.8)
    }
}
