package com.calisthenics.diagnostic.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidStoragePortArchitectureTest {
    @Test
    fun artifactReconciliationStreamsInsteadOfReadingPoseOrTelemetryWhole() {
        val source = sourceFile().readText()
        val reconciliation = source.substringAfter("override fun reconcileArtifact")
            .substringBefore("private fun framesPath")

        assertFalse(reconciliation.contains("Files.readAllBytes"))
        assertFalse(reconciliation.contains("Files.readAllLines"))
    }

    @Test
    fun artifactChannelAppendAndCloseHaveOneExplicitSynchronizationOwner() {
        val source = sourceFile().readText()

        assertTrue(source.contains("artifactChannelLock"))
        assertTrue(source.contains("synchronized(artifactChannelLock)"))
    }

    private fun sourceFile(): File {
        val workingDirectory = System.getProperty("user.dir").orEmpty()
        return generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .flatMap { directory ->
                sequenceOf(
                    directory.resolve("src/androidMain/kotlin/com/calisthenics/diagnostic/export/AndroidStoragePort.kt"),
                    directory.resolve("shared/src/androidMain/kotlin/com/calisthenics/diagnostic/export/AndroidStoragePort.kt"),
                    directory.resolve("apps/diagnostic-harness/shared/src/androidMain/kotlin/com/calisthenics/diagnostic/export/AndroidStoragePort.kt"),
                )
            }
            .firstOrNull(File::isFile)
            ?: error("AndroidStoragePort source was not found from $workingDirectory")
    }
}
