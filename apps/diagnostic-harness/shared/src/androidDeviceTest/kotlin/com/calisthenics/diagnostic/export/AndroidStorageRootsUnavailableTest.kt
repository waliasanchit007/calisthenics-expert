package com.calisthenics.diagnostic.export

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Proves [AndroidStorageRoots.from] fails closed with a typed [StorageRootsResult.Unavailable]
 * when root resolution cannot complete, instead of letting the exception escape or silently
 * falling back to an unprotected [Context.filesDir] read. `filesDir` access is forced to throw
 * via a [ContextWrapper] override, standing in for the direct-boot/credential-encrypted-storage
 * condition this method must convert rather than propagate.
 */
class AndroidStorageRootsUnavailableTest {
    @Test
    fun filesDirAccessFailureIsConvertedToTypedUnavailableNotAnEscapedException() {
        val context = ThrowingFilesDirContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ) { throw IOException("simulated direct-boot filesDir unavailable") }

        val result = AndroidStorageRoots.from(context)

        val unavailable = assertIs<StorageRootsResult.Unavailable>(result)
        assertFalse(unavailable.reason.contains("/"), "reason must not expose a raw filesystem path")
    }

    @Test
    fun setupExceptionDuringRootResolutionIsConvertedToTypedUnavailable() {
        val context = ThrowingFilesDirContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ) { throw SecurityException("simulated storage access denial") }

        val result = AndroidStorageRoots.from(context)

        assertIs<StorageRootsResult.Unavailable>(result)
    }

    private class ThrowingFilesDirContext(
        base: Context,
        private val failure: () -> File,
    ) : ContextWrapper(base) {
        override fun getFilesDir(): File = failure()
    }
}
