package com.calisthenics.diagnostic.composition

import android.content.Context
import com.calisthenics.diagnostic.conformance.SyntheticFixture
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.export.AndroidStoragePort
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StoragePort
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.session.DeterministicCore
import com.calisthenics.diagnostic.session.DeterministicCoreResult
import com.calisthenics.diagnostic.session.SessionConfiguration

/** Outcome of running the installable Android composition's deterministic session. */
sealed interface CompositionResult {
    data class Completed(val result: DeterministicCoreResult) : CompositionResult
    data class StorageUnavailable(val reason: String) : CompositionResult
}

/**
 * Wires the real [AndroidStorageRoots]/[AndroidStoragePort] and [DeterministicCore] together
 * against [SyntheticFixture], the same fixture the JVM conformance export uses, so both hosts run
 * an identical deterministic session and produce byte-identical exports (design spec §8.2-§8.3).
 */
class HarnessComposition(
    private val context: Context,
    private val storageDecorator: (StoragePort) -> StoragePort = { it },
) {
    fun runDeterministicSession(): CompositionResult {
        val roots = when (val rootsResult = AndroidStorageRoots.from(context)) {
            is StorageRootsResult.Ready -> rootsResult.roots
            is StorageRootsResult.Unavailable -> return CompositionResult.StorageUnavailable(rootsResult.reason)
        }
        val result = DeterministicCore(
            configuration = SessionConfiguration(
                sessionId = SyntheticFixture.sessionId,
                schemaVersion = SemanticVersion("1.1.0"),
                imageWidth = 256,
                imageHeight = 256,
            ),
            manifestTemplate = SyntheticFixture.manifestTemplate(),
            storage = storageDecorator(AndroidStoragePort(roots)),
        ).run(
            sourceRgb24 = SyntheticFixture.sourceRgb24,
            sourceWidth = SyntheticFixture.sourceWidth,
            sourceHeight = SyntheticFixture.sourceHeight,
        )
        return CompositionResult.Completed(result)
    }
}
