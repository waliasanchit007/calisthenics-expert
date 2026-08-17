package com.calisthenics.diagnostic.conformance

import com.calisthenics.diagnostic.contract.AvailableObservations
import com.calisthenics.diagnostic.contract.Cadence
import com.calisthenics.diagnostic.contract.CadenceSample
import com.calisthenics.diagnostic.contract.Camera
import com.calisthenics.diagnostic.contract.CaptureQualityObserved
import com.calisthenics.diagnostic.contract.Clock
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Device
import com.calisthenics.diagnostic.contract.MediaReference
import com.calisthenics.diagnostic.contract.ModelInput
import com.calisthenics.diagnostic.contract.PosePipeline
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Sha256
import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.Uuid

/**
 * The synthetic fixture literals used by the JVM conformance export
 * (`GenerateSyntheticExport.kt`) and the installable Android composition parity test, kept in one
 * place so both hosts run the identical deterministic session.
 */
object SyntheticFixture {
    private const val SESSION_ID = "5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c"

    val sessionId: Uuid = Uuid(SESSION_ID)

    val sourceRgb24: ByteArray = byteArrayOf(
        0, 10, 20,
        30, 40, 50,
        60, 70, 80,
        90, 100, 110,
    )

    val sourceWidth: Int = 2
    val sourceHeight: Int = 2

    fun manifestTemplate() = SessionManifest(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = sessionId,
        sessionKind = "fixture_replay",
        participantPseudonymId = "synthetic-kotlin-export-01",
        captureProtocolVersion = SemanticVersion("1.0.0"),
        harnessGitSha = "0".repeat(39) + "6",
        device = Device("android", "synthetic-os-1", "synthetic-kotlin-device", null, false),
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
        camera = Camera("fixture", 2, 2, 0, false, 30.0, "rgb24", "sRGB", "full", null, "synthetic_fixture_monotonic_ns", "ordered_no_drop"),
        modelInput = ModelInput(256, 256, SemanticVersion("1.0.0"), SemanticVersion("1.0.0")),
        inferenceDrainTimeoutMs = 1,
        cadence = Cadence(30.0, listOf(CadenceSample(100_000_000, 30.0)), null),
        clock = Clock("synthetic_fixture_monotonic_ns", 100_000_000, "2026-08-17T00:00:00Z", "2026-08-17T00:00:01Z"),
        availableObservations = AvailableObservations(true, true),
        mediaReferences = listOf(MediaReference("conformance_fixture", "synthetic://kotlin-export", Sha256("0".repeat(63) + "8"), null, null)),
        counts = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0),
        machineInferenceFailures = emptyList(),
        captureQualityObserved = CaptureQualityObserved(true, true, true, "synthetic_uniform", false, "synthetic export; no participant capture"),
        studyContext = mapOf("environment" to kotlinx.serialization.json.JsonPrimitive("synthetic"), "angle_deg_nominal" to kotlinx.serialization.json.JsonPrimitive(0)),
    )
}
