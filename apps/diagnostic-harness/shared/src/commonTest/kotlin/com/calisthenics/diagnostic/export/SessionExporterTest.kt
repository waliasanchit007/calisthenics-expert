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
import com.calisthenics.diagnostic.session.FinalizationResult
import com.calisthenics.diagnostic.session.SealedSessionSnapshot
import com.calisthenics.diagnostic.session.TerminalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionExporterTest {
    @Test
    fun persistenceReconcilesBothSealedCountersBeforePublishing() {
        val frame = frame(posePresent = true)

        val inferenceMismatch = export(
            FakeStorage(),
            snapshot(listOf(frame), counts = counts(emitted = 2, posePresent = 1)),
        )
        val poseMismatch = export(
            FakeStorage(),
            snapshot(listOf(frame), counts = counts(emitted = 1, posePresent = 0)),
        )

        assertIs<FinalizationResult.Failed>(inferenceMismatch)
        assertIs<FinalizationResult.Failed>(poseMismatch)
        val inferenceFailure = assertIs<FinalizationResult.Failed>(inferenceMismatch)
        val poseFailure = assertIs<FinalizationResult.Failed>(poseMismatch)
        assertEquals("stream_reconciliation_failed", inferenceFailure.failureCode)
        assertEquals("stream_reconciliation_failed", poseFailure.failureCode)
    }

    @Test
    fun silentDropWriterIsCaughtByIndependentRecount() {
        val storage = FakeStorage(dropAppends = true)

        val result = export(storage, snapshot(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(result)
        assertTrue(storage.committedDirectories.isEmpty())
        assertEquals(0L, storage.attempts.single().frameLines.size.toLong())
    }

    @Test
    fun fixedCommitOrderIsObservable() {
        val storage = FakeStorage()

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
    fun everyOrdinalFaultPreventsValidPublishExceptReportsUncertainDurabilityAfterRename() {
        val baseline = FakeStorage()
        export(baseline, snapshot(listOf(frame(posePresent = true))))
        val operationCount = baseline.operations.size

        for (ordinal in 1..operationCount) {
            val storage = FakeStorage(failOnOrdinal = ordinal)
            val result = export(storage, snapshot(listOf(frame(posePresent = true))))

            assertFalse(result is FinalizationResult.Published, "ordinal $ordinal unexpectedly published")
            if (ordinal < 9) {
                assertTrue(storage.committedDirectories.isEmpty(), "ordinal $ordinal created an export")
                assertNotNull(storage.attempts.single().abortMarker, "ordinal $ordinal lacks abort marker")
                assertTrue(StorageFile.ABORT_MARKER in storage.attempts.single().fsyncedFiles)
            } else if (ordinal == 9) {
                assertTrue(storage.committedDirectories.isEmpty())
            } else {
                val uncertain = assertIs<FinalizationResult.Failed>(result)
                assertEquals("uncertain_durability", uncertain.failureCode)
                assertEquals(1, storage.committedDirectories.size)
            }
        }
    }

    @Test
    fun scannerReportsEveryExportsDirectoryWithoutManifestIncludingEmptyDirectories() {
        val storage = FakeStorage()
        storage.precreateDestination("/files/diagnostic-harness/exports/empty", empty = true)
        storage.precreateDestination("/files/diagnostic-harness/exports/missing", empty = true)

        val corruptions = storage.scanCommittedRoot()

        assertEquals(
            setOf(
                "/files/diagnostic-harness/exports/empty",
                "/files/diagnostic-harness/exports/missing",
            ),
            corruptions.map { it.path }.toSet(),
        )
    }

    @Test
    fun nonEmptyDestinationFailsUntouched() {
        val storage = FakeStorage()
        val destination = "/files/diagnostic-harness/exports/$SESSION_ID"
        storage.precreateDestination(destination, empty = false)

        val result = export(storage, snapshot(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(result)
        assertEquals("untouched", storage.committedDirectories[destination]?.manifest)
        assertEquals(listOf("untouched"), storage.committedDirectories[destination]?.frameLines)
    }

    @Test
    fun emptyDestinationIsRejectedByExplicitExistencePrecheck() {
        val storage = FakeStorage()
        val destination = "/files/diagnostic-harness/exports/$SESSION_ID"
        storage.precreateDestination(destination, empty = true)

        val result = export(storage, snapshot(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(result)
        assertTrue(storage.committedDirectories[destination] == null)
        assertTrue(storage.scanCommittedRoot().any { it.path == destination })
    }

    @Test
    fun failedAttemptsAreUniqueAbandonedAndCarryFsyncedSanitizedCounters() {
        val storage = FakeStorage(failOnOrdinals = setOf(1, 4))

        val first = export(storage, snapshot(listOf(frame(posePresent = true))))
        val second = export(storage, snapshot(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(first)
        assertIs<FinalizationResult.Failed>(second)
        assertEquals(2, storage.attempts.size)
        assertTrue(storage.attempts[0].path != storage.attempts[1].path)
        val firstMarker = assertNotNull(storage.attempts[0].abortMarker)
        assertEquals(counts(emitted = 1, posePresent = 1), firstMarker.sealedCounters)
        assertTrue(firstMarker.reason.contains("pseudonym=$PSEUDONYM"))
        assertFalse(firstMarker.reason.contains("secret-path"))
        assertEquals(2, storage.scanStagingResidues().size)
        assertTrue(storage.scanStagingResidues().all { it is StagingResidue.Abandoned })
    }

    @Test
    fun markerAbsenceClassifiesCrashResidue() {
        val storage = FakeStorage()
        val attempt = storage.createStagingAttempt(Uuid(SESSION_ID))

        assertEquals(StagingResidue.CrashResidue(attempt.path), storage.scanStagingResidues().single())
    }

    @Test
    fun stagingAndExportsMustBeBelowFilesDirAndZeroFrameSessionsPublish() {
        val storage = FakeStorage()

        assertIs<FinalizationResult.Published>(export(storage, snapshot(emptyList(), counts = counts())))
        assertTrue(storage.committedDirectories.containsKey("/files/diagnostic-harness/exports/$SESSION_ID"))
        assertEquals("/files/diagnostic-harness", storage.roots.filesDir)
        assertTrue(storage.roots.stagingRoot.startsWith("${storage.roots.filesDir}/"))
        assertTrue(storage.roots.exportsRoot.startsWith("${storage.roots.filesDir}/"))
        assertFailsWith<IllegalArgumentException> {
            StorageRoots(storage.roots.filesDir, "/external/staging", storage.roots.exportsRoot)
        }
    }

    @Test
    fun parentFsyncFailureReportsUncertainDurabilityWithoutDeletingCommittedEntry() {
        val storage = FakeStorage(failOnOrdinal = 10)

        val result = export(storage, snapshot(listOf(frame(posePresent = true))))

        val uncertain = assertIs<FinalizationResult.Failed>(result)
        assertEquals("uncertain_durability", uncertain.failureCode)
        assertTrue(uncertain.detail.contains("uncertain durability"))
        assertEquals(1, storage.committedDirectories.size)
        assertTrue(storage.scanStagingResidues().isEmpty())
    }

    @Test
    fun repeatedFinalizeJoinsFirstResultAndPublishesOnlyOnce() {
        val storage = FakeStorage()
        val exporter = SessionExporter(storage, manifest())
        val sealed = snapshot(listOf(frame(posePresent = true)))

        val first = exporter.finalize(sealed)
        val second = exporter.finalize(sealed)

        assertEquals(first, second)
        assertEquals(1, storage.operations.count { it == "renameNoReplace" })
    }

    @Test
    fun validationErrorsAreSanitizedAndNeverExposeFrameOrFilesystemSecrets() {
        val storage = FakeStorage(dropAppends = true)
        val result = export(storage, snapshot(listOf(frame(posePresent = true))))

        val failure = assertIs<FinalizationResult.Failed>(result)
        assertTrue(failure.detail.contains("pseudonym=$PSEUDONYM"))
        assertFalse(failure.detail.contains("987654321"))
        assertFalse(failure.detail.contains("secret-path"))
        assertFalse(failure.detail.contains(SESSION_ID))
    }

    private fun export(storage: FakeStorage, sealed: SealedSessionSnapshot): FinalizationResult =
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
