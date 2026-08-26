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
import com.calisthenics.diagnostic.session.BoundedSessionState
import com.calisthenics.diagnostic.session.SealedSessionSnapshot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every port-level probe design spec §6.2 enumerates, written once against
 * [StoragePortTestHarness] so the identical test bodies run against both the JVM fake
 * ([FakeStoragePortConformanceTest]) and the real Android port (`AndroidStoragePortConformanceTest`).
 *
 * The root-enforcement probe below asserts lexical `filesDir` containment and root distinctness
 * only — never same-mount identity, which is Android-specific evidence proved solely by the
 * concrete `AndroidStoragePortConformanceTest` via `AndroidStorageRoots.from`/`st_dev` (design
 * spec §6.1 point 4). No probe here is skipped or platform-conditional.
 *
 * Fault-injection coverage of the nine fault-injectable primitives is split across two probes:
 * [everyOrdinalFaultPreventsValidPublishExceptReportsUncertainDurabilityAfterRename] sweeps every
 * ordinal of a *successful*-path export, which exercises eight of the nine — `writeAbortMarker` is
 * never called on a successful path, so it cannot appear in that sweep. The ninth,
 * [writeAbortMarkerFailureAfterCommitPrimitiveFailureClassifiesCrashResidue], is a dedicated
 * abort-path probe that forces a commit primitive to fail (driving `SessionExporter` into its
 * abandon path) and then forces the resulting `writeAbortMarker` call to fail too. Together the two
 * probes cover all nine fault-injectable operation types; neither alone does.
 */
abstract class StoragePortConformanceSuite {
    abstract fun harness(): StoragePortTestHarness

    @AfterTest
    fun disposeHarness() {
        harness().dispose()
    }

    @Test
    fun persistenceReconcilesBothSealedCountersBeforePublishing() {
        val frame = frame(posePresent = true)

        val inferenceMismatch = export(
            harness().freshPort(),
            fixture(listOf(frame), counts = counts(emitted = 2, posePresent = 1)),
        )
        val poseMismatch = export(
            harness().freshPort(),
            fixture(listOf(frame), counts = counts(emitted = 1, posePresent = 0)),
        )

        val inferenceFailure = assertIs<FinalizationResult.Failed>(inferenceMismatch)
        val poseFailure = assertIs<FinalizationResult.Failed>(poseMismatch)
        assertEquals("stream_reconciliation_failed", inferenceFailure.failureCode)
        assertEquals("stream_reconciliation_failed", poseFailure.failureCode)
    }

    @Test
    fun silentDropWriterIsCaughtByIndependentRecount() {
        val port = FaultInjectingStoragePort(harness().freshPort(), dropAppends = true)

        val result = export(port, fixture(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(result)
    }

    @Test
    fun everyOrdinalFaultPreventsValidPublishExceptReportsUncertainDurabilityAfterRename() {
        val baselineDelegate = harness().freshPort()
        val baseline = FaultInjectingStoragePort(baselineDelegate)
        export(baseline, fixture(listOf(frame(posePresent = true))))
        val operationCount = baseline.operations.size
        val renameOrdinal = baseline.operations.indexOf("renameNoReplace") + 1

        for (ordinal in 1..operationCount) {
            val delegate = harness().freshPort()
            val port = FaultInjectingStoragePort(delegate, failOnOrdinal = ordinal)
            val result = export(port, fixture(listOf(frame(posePresent = true))))
            val destination = "${delegate.roots.exportsRoot}/$SESSION_ID"

            assertFalse(result is FinalizationResult.Published, "ordinal $ordinal unexpectedly published")
            if (ordinal <= renameOrdinal) {
                assertFalse(delegate.destinationExists(destination), "ordinal $ordinal created an export")
                assertTrue(
                    delegate.scanStagingResidues().any { it is StagingResidue.Abandoned },
                    "ordinal $ordinal lacks abort marker",
                )
            } else {
                val uncertain = assertIs<FinalizationResult.Failed>(result)
                assertEquals("uncertain_durability", uncertain.failureCode)
                assertTrue(delegate.destinationExists(destination))
                assertTrue(delegate.scanStagingResidues().isEmpty())
            }
        }
    }

    @Test
    fun scannerReportsEveryExportsDirectoryWithoutManifestIncludingEmptyDirectories() {
        val port = harness().freshPort()
        val empty = "${port.roots.exportsRoot}/empty"
        val missing = "${port.roots.exportsRoot}/missing"
        harness().precreateDestination(empty, populated = false)
        harness().precreateDestination(missing, populated = false)

        val corruptions = port.scanCommittedRoot()

        assertEquals(setOf(empty, missing), corruptions.map { it.path }.toSet())
    }

    @Test
    fun nonEmptyDestinationFailsUntouched() {
        val port = harness().freshPort()
        val destination = "${port.roots.exportsRoot}/$SESSION_ID"
        harness().precreateDestination(destination, populated = true)
        val before = harness().destinationFingerprint(destination)

        val result = export(port, fixture(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(result)
        val after = harness().destinationFingerprint(destination)
        assertEquals(before, after)
    }

    @Test
    fun emptyDestinationIsRejectedByExplicitExistencePrecheck() {
        val port = harness().freshPort()
        val destination = "${port.roots.exportsRoot}/$SESSION_ID"
        harness().precreateDestination(destination, populated = false)

        val result = export(port, fixture(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(result)
        assertTrue(port.scanCommittedRoot().any { it.path == destination })
    }

    @Test
    fun failedAttemptsAreUniqueAbandonedAndCarryFsyncedSanitizedCounters() {
        val port = FaultInjectingStoragePort(harness().freshPort(), failOnOrdinals = setOf(1, 4))

        val first = export(port, fixture(listOf(frame(posePresent = true))))
        val second = export(port, fixture(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(first)
        assertIs<FinalizationResult.Failed>(second)
        val residues = port.scanStagingResidues()
        assertEquals(2, residues.size)
        assertTrue(residues.all { it is StagingResidue.Abandoned })
        val markers = residues.filterIsInstance<StagingResidue.Abandoned>().map { it.abortMarker }
        assertTrue(markers.all { it.sealedCounters == counts(emitted = 1, posePresent = 1) })
        assertTrue(markers.all { it.reason.contains("pseudonym=$PSEUDONYM") })
        assertTrue(markers.all { !it.reason.contains("secret-path") })
        assertTrue(residues.map { it.path }.toSet().size == 2, "attempts must be unique")
    }

    @Test
    fun writeAbortMarkerFailureAfterCommitPrimitiveFailureClassifiesCrashResidue() {
        val probeDelegate = harness().freshPort()
        val probePort = FaultInjectingStoragePort(probeDelegate, failOnOrdinal = 1)
        export(probePort, fixture(listOf(frame(posePresent = true))))
        val markerOrdinal = probePort.operations.indexOf("writeAbortMarker") + 1
        assertTrue(
            markerOrdinal > 0,
            "writeAbortMarker was not observed after the commit primitive failure at ordinal 1",
        )

        val delegate = harness().freshPort()
        val port = FaultInjectingStoragePort(delegate, failOnOrdinals = setOf(1, markerOrdinal))
        val result = export(port, fixture(listOf(frame(posePresent = true))))

        assertIs<FinalizationResult.Failed>(result)
        assertTrue(
            port.operations.contains("writeAbortMarker"),
            "the writeAbortMarker call must be observed/faulted, not skipped",
        )
        val residues = delegate.scanStagingResidues()
        assertEquals(1, residues.size)
        assertIs<StagingResidue.CrashResidue>(
            residues.single(),
            "a faulted writeAbortMarker call must leave no marker, classifying as CrashResidue",
        )
    }

    @Test
    fun markerAbsenceClassifiesCrashResidue() {
        val port = harness().freshPort()
        val attempt = port.createStagingAttempt(Uuid(SESSION_ID))

        assertEquals(StagingResidue.CrashResidue(attempt.path), port.scanStagingResidues().single())
    }

    @Test
    fun stagingAndExportsRootsAreLexicallyContainedAndDistinct() {
        val port = harness().freshPort()

        assertIs<FinalizationResult.Published>(export(port, fixture(emptyList(), counts = counts())))
        assertTrue(port.destinationExists("${port.roots.exportsRoot}/$SESSION_ID"))
        assertTrue(port.roots.stagingRoot.startsWith("${port.roots.filesDir}/"))
        assertTrue(port.roots.exportsRoot.startsWith("${port.roots.filesDir}/"))
        assertTrue(port.roots.stagingRoot != port.roots.exportsRoot)
        assertFailsWith<IllegalArgumentException> {
            StorageRoots(port.roots.filesDir, "/external/staging", port.roots.exportsRoot)
        }
    }

    @Test
    fun parentFsyncFailureReportsUncertainDurabilityWithoutDeletingCommittedEntry() {
        val delegate = harness().freshPort()
        val port = FaultInjectingStoragePort(delegate)
        val baselineExport = export(port, fixture(listOf(frame(posePresent = true))))
        assertIs<FinalizationResult.Published>(baselineExport)
        val finalFsyncOrdinal = port.operations.size

        val secondDelegate = harness().freshPort()
        val secondPort = FaultInjectingStoragePort(secondDelegate, failOnOrdinal = finalFsyncOrdinal)
        val result = export(secondPort, fixture(listOf(frame(posePresent = true))))

        val uncertain = assertIs<FinalizationResult.Failed>(result)
        assertEquals("uncertain_durability", uncertain.failureCode)
        assertTrue(uncertain.detail.contains("uncertain durability"))
        assertTrue(secondDelegate.destinationExists("${secondDelegate.roots.exportsRoot}/$SESSION_ID"))
        assertTrue(secondDelegate.scanStagingResidues().isEmpty())
    }

    private data class SnapshotFixture(val sealed: SealedSessionSnapshot, val records: List<RawPoseFrame>)

    private fun export(storage: StoragePort, fixture: SnapshotFixture): FinalizationResult =
        SessionExporter(storage, manifest()).let { exporter ->
            try {
                fixture.records.forEach(exporter::appendPose)
                exporter.finalize(fixture.sealed)
            } catch (_: Throwable) {
                runCatching { exporter.abort(fixture.sealed, "abandoned export for pseudonym=$PSEUDONYM") }
                FinalizationResult.Failed(fixture.sealed, "stream_write_failed", "stream write failed")
            }
        }

    private fun fixture(records: List<RawPoseFrame>, counts: Counts = counts(records)): SnapshotFixture =
        SnapshotFixture(
            sealed = SealedSessionSnapshot(counts = counts, boundedState = boundedState(records)),
            records = records,
        )

    private fun boundedState(records: List<RawPoseFrame>) = BoundedSessionState().apply {
        records.forEachIndexed { index, record ->
            submitted(index.toLong())
            emitted(index.toLong(), record)
        }
    }.snapshot()

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
