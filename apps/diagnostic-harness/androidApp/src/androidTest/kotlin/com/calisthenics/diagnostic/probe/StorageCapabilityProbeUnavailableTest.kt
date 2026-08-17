package com.calisthenics.diagnostic.probe

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.Test

/**
 * Proves [StorageCapabilityProbe.run] fails closed on an unavailable storage root: it never falls
 * back to an unprotected [Context.filesDir] read, and no scratch subtree/primitive is attempted
 * against an alternate root, because that would validate a path the real composition path can
 * never actually use.
 */
class StorageCapabilityProbeUnavailableTest {
    @Test
    fun rootResolutionFailureNeverFallsBackToAlternateRootsOrProbeOperations() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        val scratchParent = File(realContext.filesDir, "diagnostic-harness-storage-probe")
        val entriesBefore = scratchParent.list()?.toSet() ?: emptySet()
        val context = ThrowingFilesDirContext(realContext)

        val outcome = StorageCapabilityProbe(
            context = context,
            probeGitSha = "0".repeat(40),
        ).run()

        val unavailable = assertIs<StorageProbeOutcome.Unavailable>(outcome)
        assertFalse(unavailable.reason.contains("/"), "reason must not expose a raw filesystem path")
        val entriesAfter = scratchParent.list()?.toSet() ?: emptySet()
        assertEquals(
            entriesBefore,
            entriesAfter,
            "no probe scratch subtree may be created when root resolution is unavailable",
        )
    }

    private class ThrowingFilesDirContext(base: Context) : ContextWrapper(base) {
        override fun getFilesDir(): File = throw IOException("simulated direct-boot filesDir unavailable")
    }
}
