package com.calisthenics.diagnostic.export

import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.contract.Uuid
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Proves a fresh [AndroidStoragePort] instance over the same [StorageRoots] and session ID as a
 * prior instance never returns or re-enters the prior instance's staging path, and never disturbs
 * the crash residue that instance left behind. A single in-process nonce counter alone cannot
 * prove this: it resets to zero on every fresh instance, so without collision-safe directory
 * creation, a second instance over the same roots/session after a process or port restart would
 * silently reopen `<session>.1.staging`, exactly the crash residue this test forces into
 * existence first.
 */
class AndroidStoragePortAttemptUniquenessTest {
    @Test
    fun freshPortInstanceNeverReentersPriorInstancesStagingResidueAfterRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDir = context.filesDir.absolutePath
        val base = "$filesDir/staging-attempt-uniqueness-probe/${UUID.randomUUID()}"
        val roots = StorageRoots(filesDir, "$base/staging", "$base/exports")
        val sessionId = Uuid(SESSION_ID)

        val firstInstancePort = AndroidStoragePort(roots)
        val firstAttempt = firstInstancePort.createStagingAttempt(sessionId)
        firstInstancePort.appendLine(firstAttempt, CRASH_RESIDUE_MARKER_LINE)
        firstInstancePort.flush(firstAttempt)
        firstInstancePort.fsyncFile(firstAttempt, StorageFile.FRAMES)
        // firstInstancePort is discarded here without renaming or writing an abort marker,
        // simulating a process/port restart that leaves `<session>.1.staging` behind as crash
        // residue — the exact state a second, fresh instance over the same roots/session must
        // never silently reopen.

        val secondInstancePort = AndroidStoragePort(roots)
        val secondAttempt = secondInstancePort.createStagingAttempt(sessionId)

        assertNotEquals(
            firstAttempt.path,
            secondAttempt.path,
            "a fresh port instance over the same roots/session must never return the prior " +
                "instance's staging path",
        )
        val residueFramesPath = Paths.get(firstAttempt.path, "frames.ndjson")
        assertEquals(
            listOf(CRASH_RESIDUE_MARKER_LINE),
            Files.readAllLines(residueFramesPath, StandardCharsets.UTF_8),
            "the first instance's crash residue must be left untouched by the second " +
                "instance's attempt creation",
        )
    }

    private companion object {
        const val SESSION_ID = "00000000-0000-4000-8000-000000000098"
        const val CRASH_RESIDUE_MARKER_LINE = """{"crash_residue_marker":"first-instance"}"""
    }
}
