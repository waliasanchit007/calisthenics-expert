package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.AvailableObservations
import com.calisthenics.diagnostic.contract.Cadence
import com.calisthenics.diagnostic.contract.Camera
import com.calisthenics.diagnostic.contract.CaptureQualityObserved
import com.calisthenics.diagnostic.contract.Clock
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.decodeFrame
import com.calisthenics.diagnostic.contract.decodeManifest
import com.calisthenics.diagnostic.contract.Device
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.MediaReference
import com.calisthenics.diagnostic.contract.ModelInput
import com.calisthenics.diagnostic.contract.PosePipeline
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.Sha256
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.export.FakeStorage
import com.calisthenics.diagnostic.session.FinalizationResult.Published
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeterministicCoreTest {
    @Test
    fun completeEventScriptPublishesAnAtomicReconciledExportDeterministically() {
        val firstStorage = FakeStorage()
        val first = runScript(firstStorage)
        val secondStorage = FakeStorage()
        val second = runScript(secondStorage)

        assertTrue(first.finalization is Published)
        assertTrue(second.finalization is Published)
        assertEquals(first.snapshot, second.snapshot)
        assertEquals(1L, first.snapshot.diagnosticDropsAfterSeal)

        val firstExport = firstStorage.committedDirectories.values.single()
        val secondExport = secondStorage.committedDirectories.values.single()
        assertEquals(firstExport.manifest, secondExport.manifest)
        assertEquals(firstExport.frameLines, secondExport.frameLines)
        assertEquals(firstStorage.attempts.single().path.endsWith(".staging"), true)
        assertTrue(firstStorage.scanStagingResidues().isEmpty())
        assertTrue(secondStorage.scanStagingResidues().isEmpty())

        val sealed = assertNotNull(first.snapshot.sealedSnapshot)
        val exportedManifest = decodeManifest(firstExport.manifest)
        val exportedFrames = firstExport.frameLines.map(::decodeFrame)
        assertEquals(sealed.counts, exportedManifest.counts)
        assertEquals(first.finalization.sealedSnapshot.failures, exportedManifest.machineInferenceFailures)
        assertEquals(sealed.records, exportedFrames)
        assertEquals(
            Counts(
                cameraFramesReceived = 5,
                inferenceFramesSubmitted = 4,
                inferenceRecordsEmitted = 2,
                poseDetectedRecords = 1,
                framesSkippedBeforeInference = 1,
                framesSkippedBeforeInferenceByReason = mapOf(
                    "backpressure" to 1,
                    "cadence_selection" to 0,
                    "preprocessing_failed" to 0,
                    "session_stopping" to 0,
                ),
                trailingFramesSkippedAfterLastEmittedRecord = 0,
                inferenceFailures = 2,
            ),
            sealed.counts,
        )
        assertEquals(
            mapOf(
                0L to TerminalState.EMITTED,
                1L to TerminalState.EMITTED,
                2L to TerminalState.FAILED,
                3L to TerminalState.TIMED_OUT,
            ),
            sealed.terminalMap,
        )
        assertEquals(listOf(false, true), sealed.records.map { it.posePresent })
        assertEquals(1L, sealed.records.last().framesSkippedSinceLastEmittedRecord)
        assertEquals(listOf("backend_call_failed", "session_teardown_timeout"), sealed.failures.map { it.classification })
        assertEquals(listOf("backend", "harness"), sealed.failures.map { it.failureSource })
        assertEquals(sealed, first.finalization.sealedSnapshot)
        assertEquals(1, firstStorage.committedDirectories.size)
        assertTrue(firstStorage.scanCommittedRoot().isEmpty())
    }

    private fun runScript(storage: FakeStorage): DeterministicCoreResult = DeterministicCore(
        configuration = SessionConfiguration(
            sessionId = SESSION_ID,
            schemaVersion = SCHEMA_VERSION,
            imageWidth = MODEL_WIDTH,
            imageHeight = MODEL_HEIGHT,
        ),
        manifestTemplate = manifest(),
        storage = storage,
        dispatcher = DirectSessionDispatcher,
    ).run(
        sourceRgb24 = byteArrayOf(
            0, 10, 20,
            30, 40, 50,
            60, 70, 80,
            90, 100, 110,
        ),
        sourceWidth = 2,
        sourceHeight = 2,
    )

    private fun manifest() = SessionManifest(
        schemaVersion = SCHEMA_VERSION,
        sessionId = SESSION_ID,
        sessionKind = "fixture_replay",
        participantPseudonymId = "synthetic-deterministic-core",
        captureProtocolVersion = SemanticVersion("1.0.0"),
        harnessGitSha = "a".repeat(40),
        device = Device("jvm", "test", "deterministic-core", null, false),
        posePipeline = PosePipeline(
            "fake_deterministic_backend",
            "1.0.0",
            "synthetic",
            Sha256("0".repeat(64)),
            "cpu",
            "video",
            1,
            0.5,
            0.5,
            0.5,
            "mediapipe_pose_33_v1",
        ),
        camera = Camera("fixture", 2, 2, 0, false, 30.0, "rgb24", "sRGB", "full", null, "synthetic", "ordered_no_drop"),
        modelInput = ModelInput(MODEL_WIDTH, MODEL_HEIGHT, SemanticVersion("1.0.0"), SemanticVersion("1.0.0")),
        inferenceDrainTimeoutMs = 1,
        cadence = Cadence(30.0, emptyList(), null),
        clock = Clock("synthetic", 100, "2026-08-17T00:00:00Z", "2026-08-17T00:00:01Z"),
        availableObservations = AvailableObservations(true, true),
        mediaReferences = listOf(MediaReference("conformance_fixture", "synthetic://deterministic-core", Sha256("1".repeat(64)), null, null)),
        counts = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0),
        machineInferenceFailures = emptyList(),
        captureQualityObserved = CaptureQualityObserved(true, true, true, "synthetic_uniform", false, "synthetic core; no participant capture"),
        studyContext = emptyMap(),
    )

    private companion object {
        val SESSION_ID = Uuid("12345678-1234-4234-8234-123456789abc")
        val SCHEMA_VERSION = SemanticVersion("1.1.0")
        const val MODEL_WIDTH = 4
        const val MODEL_HEIGHT = 4
    }
}
