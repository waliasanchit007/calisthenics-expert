package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.AvailableObservations
import com.calisthenics.diagnostic.contract.Cadence
import com.calisthenics.diagnostic.contract.Camera
import com.calisthenics.diagnostic.contract.CaptureQualityObserved
import com.calisthenics.diagnostic.contract.Clock
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Device
import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.MediaReference
import com.calisthenics.diagnostic.contract.ModelInput
import com.calisthenics.diagnostic.contract.PosePipeline
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.Sha256
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.contract.decodeManifest
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SealedSessionSnapshot
import com.calisthenics.diagnostic.session.TerminalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exporter-level assertions that are not port-conformance probes (design spec §6.2): fixed
 * operation order, second-finalize idempotency, and sanitized-error-detail. These characterize
 * `SessionExporter`'s own behaviour, not a storage backend, so they stay JVM/fake-only. Every
 * other probe that used to live here now lives in [StoragePortConformanceSuite], run against both
 * [FakeStorage] and the real `AndroidStoragePort`.
 */
class SessionExporterTest {
    @Test
    fun endedAtManifestValueIsProducedDuringFinalizationAfterReplayFramesAreReconciled() {
        val fake = FakeStorage()
        val storage = FaultInjectingStoragePort(fake)
        val beforeReplay = "2026-01-01T00:00:00Z"
        val afterReplay = "2026-01-01T00:00:10Z"
        val template = manifest().copy(clock = manifest().clock.copy(endedAtUtc = beforeReplay))
        val result = SessionExporter(
            storage,
            template,
            manifestAtFinalization = { current, sealedSnapshot ->
                assertTrue(storage.operations.contains("reconcile"))
                assertEquals(1L, sealedSnapshot.counts.inferenceRecordsEmitted)
                current.copy(clock = current.clock.copy(endedAtUtc = afterReplay))
            },
        ).finalize(snapshot(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Published>(result)
        val exportedManifest = decodeManifest(fake.attempts.single().manifest!!)
        assertEquals(afterReplay, exportedManifest.clock.endedAtUtc)
        assertTrue(storage.operations.indexOf("reconcile") < storage.operations.indexOf("writeManifest"))
    }

    @Test
    fun fixedCommitOrderIsObservable() {
        val storage = FaultInjectingStoragePort(FakeStorage())

        assertIs<FinalizationResult.Published>(export(storage, snapshot(listOf(frame(posePresent = true)))))

        assertEquals(
            listOf(
                "appendLine",
                "flush",
                "fsyncFile:FRAMES",
                "reconcile",
                "writeManifest",
                "fsyncFile:MANIFEST",
                "fsyncDirectory:staging",
                "destinationExists",
                "renameNoReplace",
                "fsyncDirectory:exports",
            ),
            storage.operations,
        )
    }

    @Test
    fun repeatedFinalizeJoinsFirstResultAndPublishesOnlyOnce() {
        val storage = FaultInjectingStoragePort(FakeStorage())
        val exporter = SessionExporter(storage, manifest())
        val sealed = snapshot(listOf(frame(posePresent = true)))

        val first = exporter.finalize(sealed)
        val second = exporter.finalize(sealed)

        assertEquals(first, second)
        assertEquals(1, storage.operations.count { it == "renameNoReplace" })
    }

    @Test
    fun validationErrorsAreSanitizedAndNeverExposeFrameOrFilesystemSecrets() {
        val storage = FaultInjectingStoragePort(FakeStorage(), dropAppends = true)
        val result = export(storage, snapshot(listOf(frame(posePresent = true))))

        val failure = assertIs<FinalizationResult.Failed>(result)
        assertTrue(failure.detail.contains("pseudonym=$PSEUDONYM"))
        assertFalse(failure.detail.contains("987654321"))
        assertFalse(failure.detail.contains("secret-path"))
        assertFalse(failure.detail.contains(SESSION_ID))
    }

    private fun export(storage: StoragePort, sealed: SealedSessionSnapshot): FinalizationResult =
        SessionExporter(storage, manifest()).finalize(sealed)

    private fun snapshot(records: List<RawPoseFrame>, counts: Counts = counts(records)): SealedSessionSnapshot =
        SealedSessionSnapshot(
            counts = counts,
            records = records,
            failures = emptyList(),
            terminalMap = records.mapIndexed { index, _ -> index.toLong() to TerminalState.EMITTED }.toMap(),
        )

    private fun counts(records: List<RawPoseFrame> = emptyList()): Counts =
        counts(records.size.toLong(), records.count { it.posePresent }.toLong())

    private fun counts(emitted: Long, posePresent: Long): Counts = Counts(
        cameraFramesReceived = emitted,
        inferenceFramesSubmitted = emitted,
        inferenceRecordsEmitted = emitted,
        poseDetectedRecords = posePresent,
        framesSkippedBeforeInference = 0,
        framesSkippedBeforeInferenceByReason = emptyMap(),
        trailingFramesSkippedAfterLastEmittedRecord = 0,
        inferenceFailures = 0,
    )

    private fun frame(posePresent: Boolean): RawPoseFrame = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid(SESSION_ID),
        frameIndex = 0,
        captureTimestampNs = 1_000_000,
        backendTimestampMs = 1,
        inferenceStartNs = 2,
        inferenceEndNs = 3,
        posePresent = posePresent,
        normalizedImageLandmarks = if (posePresent) listOf(Landmark(0, "nose", 987654321.0, 0.0, 0.0, null, null)) else null,
        worldLandmarks = null,
        imageWidth = 640,
        imageHeight = 480,
        framesSkippedSinceLastEmittedRecord = 0,
    )

    private fun manifest(): SessionManifest = SessionManifest(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid(SESSION_ID),
        sessionKind = "fixture_replay",
        participantPseudonymId = PSEUDONYM,
        captureProtocolVersion = SemanticVersion("1.0.0"),
        harnessGitSha = "a".repeat(40),
        device = Device("jvm", "test", "fake", null, false),
        posePipeline = PosePipeline("fake", "1", "model", Sha256("0".repeat(64)), "cpu", "video", 1, 0.5, 0.5, 0.5, "fake"),
        camera = Camera("back", 640, 480, 0, false, 30.0, null, null, null, null, "monotonic", "fake"),
        modelInput = ModelInput(256, 256, SemanticVersion("1.0.0"), null),
        inferenceDrainTimeoutMs = 1000,
        cadence = Cadence(15.0, emptyList(), null),
        clock = Clock("monotonic", 0, "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z"),
        availableObservations = AvailableObservations(true, false),
        mediaReferences = listOf(MediaReference("fixture", "fixture.rgb24", Sha256("0".repeat(64)), null, null)),
        counts = counts(),
        machineInferenceFailures = emptyList(),
        captureQualityObserved = CaptureQualityObserved(true, true, true, "known", false, null),
        studyContext = emptyMap(),
    )

    private companion object {
        const val SESSION_ID = "00000000-0000-4000-8000-000000000001"
        const val PSEUDONYM = "participant-pseudonym"
    }
}
