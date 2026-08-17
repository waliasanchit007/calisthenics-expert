package com.calisthenics.diagnostic.composition

import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.conformance.SyntheticFixture
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.session.FinalizationResult
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Runs the installable composition against a real Android [android.content.Context] and asserts
 * its export is byte-for-byte identical to the JVM conformance export of the same
 * [SyntheticFixture] session (design spec §8.4, §11). Any divergence is a blocking finding for
 * this milestone.
 */
class InstallableCompositionExportParityTest {
    @Test
    fun installableCompositionProducesByteIdenticalExportToJvmFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext

        val result = HarnessComposition(targetContext).runDeterministicSession()
        val completed = result as CompositionResult.Completed
        assertTrue(completed.result.finalization is FinalizationResult.Published)

        val roots = (AndroidStorageRoots.from(targetContext) as StorageRootsResult.Ready).roots
        val committed = File("${roots.exportsRoot}/${SyntheticFixture.sessionId.value}")
        val producedManifest = File(committed, "manifest.json").readBytes()
        val producedFrames = File(committed, "frames.ndjson").readBytes()

        val bundledManifest = instrumentation.context.assets
            .open("synthetic-session/manifest.json")
            .use { it.readBytes() }
        val bundledFrames = instrumentation.context.assets
            .open("synthetic-session/frames.ndjson")
            .use { it.readBytes() }

        assertContentEquals(bundledManifest, producedManifest, "manifest.json diverged from the bundled JVM export")
        assertContentEquals(bundledFrames, producedFrames, "frames.ndjson diverged from the bundled JVM export")
    }
}
