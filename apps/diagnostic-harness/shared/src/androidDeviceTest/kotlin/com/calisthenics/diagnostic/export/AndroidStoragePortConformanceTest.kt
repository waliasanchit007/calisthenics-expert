package com.calisthenics.diagnostic.export

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Device-covered concrete instantiation of [StoragePortConformanceSuite] against the real
 * [AndroidStoragePort], rooted at a scratch subtree of the instrumented test's own
 * `context.filesDir`. Runs every probe the shared suite defines, with no platform-conditional
 * skip, plus the Android-only same-mount assertion design spec §6.1 point 4 reserves for this
 * concrete suite (never claimed against the fake).
 */
class AndroidStoragePortConformanceTest : StoragePortConformanceSuite() {
    private val testHarness = AndroidStoragePortTestHarness(InstrumentationRegistry.getInstrumentation().targetContext)

    override fun harness(): StoragePortTestHarness = testHarness

    @Test
    fun stagingAndExportsShareTheSameFilesystemMountAsFilesDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = assertIs<StorageRootsResult.Ready>(AndroidStorageRoots.from(context)).roots

        val filesDirDev = Os.stat(roots.filesDir).st_dev
        val stagingDev = Os.stat(roots.stagingRoot.substringBeforeLast('/')).st_dev
        val exportsDev = Os.stat(roots.exportsRoot.substringBeforeLast('/')).st_dev

        assertEquals(filesDirDev, stagingDev)
        assertEquals(filesDirDev, exportsDev)
    }
}
