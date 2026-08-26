package com.calisthenics.diagnostic.export

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.composition.HarnessComposition
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.conformance.SyntheticFixture
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.probe.readMarketingName
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.replay.ProcessKillEnvironment
import com.calisthenics.diagnostic.replay.ProcessKillEvidence
import com.calisthenics.diagnostic.replay.ProcessKillObservation
import com.calisthenics.diagnostic.replay.renderProcessKillEvidence
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Design spec §9.1: a process that kills itself mid-export cannot also report a JUnit result for
 * that same invocation, so this is two separate `am instrument` invocations against the same
 * on-device staging/exports roots (sequenced by the `verify-harness-s25-process-kill` Makefile
 * target), not two methods relying on in-process continuation between them.
 */
class ProcessKillDuringExportTest {
    @Test
    fun killDuringCommit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Process.killProcess ends this instrumentation process synchronously, before any
        // assertion following this call could ever run or be reported — that is the expected
        // outcome of this invocation, not a probe failure. Acceptance is decided entirely by
        // verifyNoPartialEntryAndResidueClassification, the second invocation.
        HarnessComposition(
            context = context,
            storageDecorator = { real -> ProcessKillingStoragePort(real, killOnLabel = "destinationExists") },
        ).runDeterministicSession()
    }

    @Test
    fun verifyNoPartialEntryAndResidueClassification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = (AndroidStorageRoots.from(context) as StorageRootsResult.Ready).roots
        val port = AndroidStoragePort(roots)

        val corruptions = port.scanCommittedRoot()
        assertTrue(
            corruptions.isEmpty(),
            "no partial entry may appear under the valid exports root after a mid-commit kill: $corruptions",
        )

        val residues = port.scanStagingResidues().filter { it.path.contains(SyntheticFixture.sessionId.value) }
        assertEquals(1, residues.size, "exactly one abandoned staging attempt is expected from killDuringCommit")
        assertIs<StagingResidue.CrashResidue>(
            residues.single(),
            "a process killed before commit completes must classify as CrashResidue, not Abandoned",
        )

        // Emit the sanitized record for the human recording step, exactly as the five-run gate
        // does. The renderer computes the gate flag from these observations; nothing here declares
        // it. `process_crash_confirmed` is supplied by the harness that saw the transcript, so it
        // is passed as false here and set true only by the Makefile target, which is the only
        // layer that actually witnessed the crash.
        val record = renderProcessKillEvidence(
            ProcessKillEvidence(
                emittedBy = "com.calisthenics.diagnostic.export.ProcessKillDuringExportTest",
                harnessGitSha = BuildConfig.PROBE_GIT_SHA,
                environment = ProcessKillEnvironment(
                    deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                    deviceIdentity = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                    deviceFingerprint = Build.FINGERPRINT,
                    apiLevel = Build.VERSION.SDK_INT,
                    androidRelease = Build.VERSION.RELEASE,
                    abi = Build.SUPPORTED_ABIS.joinToString(","),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    marketingName = readMarketingName(),
                ),
                sessionId = SyntheticFixture.sessionId.value,
                observation = ProcessKillObservation(
                    killedAtOperation = "destinationExists",
                    processCrashConfirmed = false,
                    exportRootCorruptions = corruptions.map { it.path },
                    stagingResidueCount = residues.size,
                    stagingResidueClassification = "CrashResidue",
                ),
            ),
        )
        // `println` never reaches `am instrument -w` output; the record must be reported as an
        // instrumentation status, mirroring the five-run gate.
        InstrumentationRegistry.getInstrumentation().sendStatus(
            PROCESS_KILL_STATUS_CODE,
            Bundle().apply { putString(PROCESS_KILL_TAG, record) },
        )
        Log.i(PROCESS_KILL_TAG, record)
    }

    private companion object {
        const val PROCESS_KILL_TAG = "ProcessKillEvidence"
        const val PROCESS_KILL_STATUS_CODE = 4
    }
}

/**
 * Mirrors [FaultInjectingStoragePort]'s exact nine-op ordinal/label model (design spec §6.1/§6.2,
 * this file's `shared/commonTest` counterpart) but kills the current process instead of throwing
 * when the matching operation label is reached. A separate class only because `shared`'s
 * `commonTest` fault-injection fixture is not visible across the module boundary to `androidApp`'s
 * `androidTest` source set; the ordinal/label sequence is identical, not a third model.
 */
private class ProcessKillingStoragePort(
    private val delegate: StoragePort,
    private val killOnLabel: String,
) : StoragePort {
    override val roots: StorageRoots get() = delegate.roots

    override fun createStagingAttempt(sessionId: Uuid): StagingAttempt = delegate.createStagingAttempt(sessionId)

    override fun appendLine(attempt: StagingAttempt, line: String) {
        before("appendLine")
        delegate.appendLine(attempt, line)
    }

    override fun flush(attempt: StagingAttempt) {
        before("flush")
        delegate.flush(attempt)
    }

    override fun fsyncFile(attempt: StagingAttempt, file: StorageFile) {
        before("fsyncFile:$file")
        delegate.fsyncFile(attempt, file)
    }

    override fun reconcile(attempt: StagingAttempt): StreamReconciliation {
        before("reconcile")
        return delegate.reconcile(attempt)
    }

    override fun writeManifest(attempt: StagingAttempt, encodedManifest: String) {
        before("writeManifest")
        delegate.writeManifest(attempt, encodedManifest)
    }

    override fun fsyncDirectory(path: String) {
        val operation = if (path == roots.exportsRoot) "fsyncDirectory:exports" else "fsyncDirectory:staging"
        before(operation)
        delegate.fsyncDirectory(path)
    }

    override fun destinationExists(destinationPath: String): Boolean {
        before("destinationExists")
        return delegate.destinationExists(destinationPath)
    }

    override fun renameNoReplace(attempt: StagingAttempt, destinationPath: String) {
        before("renameNoReplace")
        delegate.renameNoReplace(attempt, destinationPath)
    }

    override fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts) {
        before("writeAbortMarker")
        delegate.writeAbortMarker(attempt, reason, sealedCounters)
    }

    override fun scanCommittedRoot(): List<ExportRootCorruption> = delegate.scanCommittedRoot()

    override fun scanStagingResidues(): List<StagingResidue> = delegate.scanStagingResidues()

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        before("appendArtifact:$artifact")
        delegate.appendArtifact(attempt, artifact, bytes)
    }

    override fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        before("flushArtifact:$artifact")
        delegate.flushArtifact(attempt, artifact)
    }

    override fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        before("fsyncArtifact:$artifact")
        delegate.fsyncArtifact(attempt, artifact)
    }

    override fun replaceCheckpoint(attempt: StagingAttempt, encodedCheckpoint: String) {
        before("replaceCheckpoint")
        delegate.replaceCheckpoint(attempt, encodedCheckpoint)
    }

    override fun reconcileArtifact(attempt: StagingAttempt, artifact: DurableArtifact): ArtifactReconciliation {
        before("reconcileArtifact:$artifact")
        return delegate.reconcileArtifact(attempt, artifact)
    }

    private fun before(operation: String) {
        if (operation == killOnLabel) Process.killProcess(Process.myPid())
    }
}
