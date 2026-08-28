package com.calisthenics.diagnostic.purge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionAdmissionFenceTest {
    private val sessionId = requireNotNull(PurgeSessionId.parseOrNull("00000000-0000-4000-8000-000000000001"))

    @Test
    fun intentIncompleteAndUnreadableTombstonesRefuseBothWriterOpenAndFinalPublish() {
        listOf(
            PurgeJournalRead.Ready(listOf(tombstone(PurgeState.INTENT_RECORDED))),
            PurgeJournalRead.Ready(listOf(tombstone(PurgeState.PURGE_INCOMPLETE))),
            PurgeJournalRead.Unreadable,
        ).forEach { journal ->
            val fence = TombstoneSessionAdmissionFence(object : PurgeFilesystem by FenceFilesystem(journal) {})

            assertEquals(SessionAdmissionDecision.Refused("PURGE.SESSION_TOMBSTONED"), fence.check(sessionId, SessionAdmissionPhase.WRITER_OPEN))
            assertEquals(SessionAdmissionDecision.Refused("PURGE.SESSION_TOMBSTONED"), fence.check(sessionId, SessionAdmissionPhase.FINAL_PUBLISH))
        }
    }

    @Test
    fun emptyJournalAdmitsBothPhases() {
        val fence = TombstoneSessionAdmissionFence(FenceFilesystem(PurgeJournalRead.Ready(emptyList())))

        assertIs<SessionAdmissionDecision.Admitted>(fence.check(sessionId, SessionAdmissionPhase.WRITER_OPEN))
        assertIs<SessionAdmissionDecision.Admitted>(fence.check(sessionId, SessionAdmissionPhase.FINAL_PUBLISH))
    }

    private fun tombstone(state: PurgeState) = PurgeTombstone(
        sessionId, "10000000-0000-4000-8000-000000000001", 1, state, "b".repeat(64), emptyList(), null, "a".repeat(40),
    )
}

private class FenceFilesystem(private val journal: PurgeJournalRead) : PurgeFilesystem {
    override val registrySha256 = "b".repeat(64)
    override fun readJournal(sessionId: PurgeSessionId) = journal
    override fun writeTemporaryJournal(sessionId: PurgeSessionId, records: List<PurgeTombstone>) = error("unused")
    override fun fsyncTemporaryJournal(sessionId: PurgeSessionId) = error("unused")
    override fun renameTemporaryJournal(sessionId: PurgeSessionId) = error("unused")
    override fun fsyncTombstoneRoot() = error("unused")
    override fun inventory(sessionId: PurgeSessionId, location: com.calisthenics.diagnostic.export.StorageLocationId) = error("unused")
    override fun deleteOwned(sessionId: PurgeSessionId, location: com.calisthenics.diagnostic.export.StorageLocationId) = error("unused")
    override fun postScan(sessionId: PurgeSessionId, location: com.calisthenics.diagnostic.export.StorageLocationId) = error("unused")
}
