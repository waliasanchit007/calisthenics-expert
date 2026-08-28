package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.export.StorageRoots
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidTombstoneFenceFilesystemTest {
    private val temporaryRoots = mutableListOf<Path>()
    private val sessionId = requireNotNull(PurgeSessionId.parseOrNull("00000000-0000-4000-8000-000000000001"))

    @AfterTest
    fun removeTemporaryRoots() {
        temporaryRoots.forEach(::deleteTree)
    }

    @Test
    fun existingOrUnreadableExactTombstoneJournalRefusesBothProductionAdmissionPhases() {
        val roots = roots()
        val filesystem = AndroidTombstoneFenceFilesystem(roots)
        val fence = TombstoneSessionAdmissionFence(filesystem)

        assertIs<SessionAdmissionDecision.Admitted>(fence.check(sessionId, SessionAdmissionPhase.WRITER_OPEN))
        Files.write(
            Path.of(roots.tombstoneRoot).resolve("${sessionId.value}.purge-tombstone.json"),
            "not-a-journal".encodeToByteArray(),
        )

        assertEquals(
            SessionAdmissionDecision.Refused("PURGE.SESSION_TOMBSTONED"),
            fence.check(sessionId, SessionAdmissionPhase.FINAL_PUBLISH),
        )
    }

    private fun roots(): StorageRoots {
        val temporary = Files.createTempDirectory("android-tombstone-fence-")
        temporaryRoots.add(temporary)
        val filesDir = temporary.resolve("files")
        val base = filesDir.resolve("diagnostic-harness")
        listOf("staging", "exports", "transfer-staging", "tombstones").forEach { child ->
            Files.createDirectories(base.resolve(child))
        }
        return StorageRoots(
            filesDir = filesDir.toString(),
            diagnosticHarnessRoot = base.toString(),
            stagingRoot = base.resolve("staging").toString(),
            exportsRoot = base.resolve("exports").toString(),
            transferStagingRoot = base.resolve("transfer-staging").toString(),
            tombstoneRoot = base.resolve("tombstones").toString(),
        )
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
