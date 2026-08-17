package com.calisthenics.diagnostic.probe

import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.android.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class StorageCapabilityProbeTest {
    @Test
    fun executesRealProbeAndPersistsRetrievableSanitizedResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val worker = Executors.newSingleThreadExecutor()
        try {
            val outcome = worker.submit<StorageProbeOutcome> {
                check(Thread.currentThread() !== LooperHolder.mainThread) {
                    "probe must run off the Android main thread"
                }
                StorageCapabilityProbe(
                    context = targetContext,
                    probeGitSha = BuildConfig.PROBE_GIT_SHA,
                ).runAndPersist()
            }.get(15, TimeUnit.SECONDS)
            val result = assertIs<StorageProbeOutcome.Completed>(outcome).result

            val evidence = File(targetContext.cacheDir, StorageCapabilityProbe.RESULT_FILE)
            assertTrue(evidence.isFile)
            assertEquals(result, StorageProbeResult.fromCanonicalJson(evidence.readText()))
            assertTrue(Regex("[0-9a-f]{40}").matches(result.probeGitSha))
            assertFalse(evidence.readText().contains(targetContext.cacheDir.absolutePath))

            assertTrue(result.appendFlushFsyncSupported)
            assertTrue(result.directoryFsyncSupported)
            assertTrue(result.renameWithinFilesdirSupported)
            assertTrue(result.renameOntoPopulatedDestinationRejected)
            assertTrue(result.renameOntoEmptyDestinationRefusedByPrecheck)
            assertTrue(result.isDurabilityAdmissible())
        } finally {
            worker.shutdownNow()
            check(worker.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    private object LooperHolder {
        val mainThread = android.os.Looper.getMainLooper().thread
    }
}
