package com.calisthenics.diagnostic.export

import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.contract.encodeFrameLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SESSION_ID = "00000000-0000-4000-8000-000000000099"

class AndroidStoragePortSmokeTest {
    @Test
    fun exercisesAllTwelvePrimitivesThroughACompletePublish() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = (AndroidStorageRoots.from(context) as StorageRootsResult.Ready).roots
        val port = AndroidStoragePort(roots)
        val attempt = port.createStagingAttempt(Uuid(SESSION_ID))
        port.appendLine(attempt, encodeFrameLine(frame()))
        port.flush(attempt)
        port.fsyncFile(attempt, StorageFile.FRAMES)
        val reconciled = port.reconcile(attempt)
        assertEquals(1L, reconciled.lineCount)
        port.writeManifest(attempt, "{}")
        port.fsyncFile(attempt, StorageFile.MANIFEST)
        port.fsyncDirectory(attempt.path)
        val destination = "${roots.exportsRoot}/$SESSION_ID"
        assertFalse(port.destinationExists(destination))
        port.renameNoReplace(attempt, destination)
        assertTrue(port.destinationExists(destination))
        assertTrue(port.scanCommittedRoot().isEmpty())
    }

    private fun frame(): RawPoseFrame = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid(SESSION_ID),
        frameIndex = 0,
        captureTimestampNs = 1_000_000,
        backendTimestampMs = 1,
        inferenceStartNs = 2,
        inferenceEndNs = 3,
        posePresent = false,
        normalizedImageLandmarks = null,
        worldLandmarks = null,
        imageWidth = 640,
        imageHeight = 480,
        framesSkippedSinceLastEmittedRecord = 0,
    )
}
