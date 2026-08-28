package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.export.StorageLocationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntheticPurgeTest {
    private val sessionId = requireNotNull(PurgeSessionId.parseOrNull("00000000-0000-4000-8000-000000000001"))

    @Test
    fun intentAndTombstoneRootDurabilityPrecedeEveryDeleteAndReplayIsIdempotent() {
        val filesystem = FakePurgeFilesystem()
        val purge = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))

        val first = purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")
        val second = purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(PurgeState.PURGE_COMPLETE, first.state)
        assertEquals(first, second)
        assertEquals(3, filesystem.deletePasses)
        val firstDelete = filesystem.trace.indexOf("delete-device_export")
        val intentRootFsync = filesystem.trace.indexOf("fsync-tombstone-root")
        assertTrue(intentRootFsync >= 0, "the durable intent must fsync its tombstone root")
        assertTrue(intentRootFsync < firstDelete)
        assertEquals(
            2,
            filesystem.trace.take(firstDelete).count { it == "fsync-tombstone-root" },
            "intent and non-vacuous ownership evidence must both be durable before deletion",
        )
        assertEquals(
            listOf(PurgeState.INTENT_RECORDED, PurgeState.INTENT_RECORDED, PurgeState.PURGE_COMPLETE),
            filesystem.journal.map { it.state },
        )
        assertFalse(first.render().contains(sessionId.value))
        assertFalse(first.render().contains('/'))
    }

    @Test
    fun everyCrashBoundaryBeforeAndDuringDeletionResumesFromDurableIntentWithoutDeletingOutsideTheRegistry() {
        val boundaries = listOf(
            "write-temp",
            "fsync-temp",
            "rename-tombstone",
            "fsync-tombstone-root",
            "inventory-device_export",
            "delete-device_export",
            "post-scan-device_export",
        )

        boundaries.forEach { boundary ->
            val filesystem = FakePurgeFilesystem(crashAfter = boundary)
            val purge = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))

            runCatching { purge.purge(sessionId, "10000000-0000-4000-8000-000000000001") }
                .onSuccess { error("$boundary must crash the first call") }
            filesystem.crashAfter = null

            val replay = purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")
            assertEquals(PurgeState.PURGE_COMPLETE, replay.state, boundary)
            assertTrue(filesystem.deletedLocations.all { it in PurgeFilesystem.devicePurgeLocations }, boundary)
            assertTrue(filesystem.journal.last().operationSequence <= PurgeFilesystem.MAX_OPERATION_COUNT, boundary)
        }
    }

    @Test
    fun rejectedOwnershipHazardsAndVacuousInventoryDeleteNothing() {
        val hazards = listOf(
            PurgeBlocker.TRAVERSAL,
            PurgeBlocker.ABSOLUTE_PATH,
            PurgeBlocker.PREFIX_CONFUSION,
            PurgeBlocker.SYMLINK,
            PurgeBlocker.UNKNOWN_ROOT,
            PurgeBlocker.TYPE_CHANGED,
            PurgeBlocker.REGISTRY_DIGEST_MISMATCH,
            PurgeBlocker.PATH_ESCAPE,
            PurgeBlocker.NON_VACUOUS_INVENTORY,
        )

        hazards.forEach { blocker ->
            val filesystem = FakePurgeFilesystem(blocker = blocker)
            val record = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))
                .purge(sessionId, "10000000-0000-4000-8000-000000000001")

            assertEquals(PurgeState.PURGE_INCOMPLETE, record.state, blocker.name)
            assertEquals(0, filesystem.deletePasses, blocker.name)
            assertTrue(record.locationResults.any { it.terminalResult == PurgeTerminalResult.BLOCKED }, blocker.name)
        }
    }

    @Test
    fun zeroEntryPreInventoryIsBlockedByTheTransactionRatherThanTrustingAnAdapterProvidedBlocker() {
        val filesystem = FakePurgeFilesystem(entriesScanned = 0, ownedEntries = 0)

        val record = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))
            .purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(PurgeState.PURGE_INCOMPLETE, record.state)
        assertEquals(0, filesystem.deletePasses)
        assertTrue(record.locationResults.all { it.terminalResult == PurgeTerminalResult.BLOCKED })
    }

    @Test
    fun replayAfterADurableIntentMayCompleteWhenTheCrashAlreadyDeletedEveryOwnedTarget() {
        val filesystem = FakePurgeFilesystem(
            crashAfter = "delete-device_transfer_staging",
            reportsAlreadyAbsentAfterDelete = true,
        )
        val purge = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))

        runCatching { purge.purge(sessionId, "10000000-0000-4000-8000-000000000001") }
            .onSuccess { error("the first call must crash after the final delete") }
        filesystem.crashAfter = null

        val replay = purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(PurgeState.PURGE_COMPLETE, replay.state)
        assertTrue(replay.locationResults.all { it.terminalResult == PurgeTerminalResult.ALREADY_ABSENT })
    }

    @Test
    fun crashDuringFirstInventoryOverEmptyRootsDoesNotTurnDurableIntentIntoDeletionProof() {
        val filesystem = FakePurgeFilesystem(
            crashAfter = "inventory-device_export",
            ownedEntries = 0,
        )
        val purge = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))

        runCatching { purge.purge(sessionId, "10000000-0000-4000-8000-000000000001") }
            .onSuccess { error("the first call must crash during its first inventory") }
        filesystem.crashAfter = null

        val replay = purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(PurgeState.PURGE_INCOMPLETE, replay.state)
        assertEquals(0, filesystem.deletePasses)
        assertTrue(replay.locationResults.all { it.terminalResult == PurgeTerminalResult.BLOCKED })
    }

    @Test
    fun retryOfAnIncompleteIntentPreservesHistoryWithoutReinsertingThatIntent() {
        val filesystem = FakePurgeFilesystem(blocker = PurgeBlocker.NON_VACUOUS_INVENTORY)
        val purge = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))

        purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")
        purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(
            listOf(
                PurgeState.INTENT_RECORDED,
                PurgeState.PURGE_INCOMPLETE,
                PurgeState.PURGE_INCOMPLETE,
            ),
            filesystem.journal.map(PurgeTombstone::state),
        )
        assertEquals(listOf(1, 2, 3), filesystem.journal.map(PurgeTombstone::operationSequence))
    }

    @Test
    fun freshPostScanResidueChangesTerminalStateAndPublicRecordRemainsBoundedAndSanitized() {
        val filesystem = FakePurgeFilesystem(postScanOwnedRemaining = 1)

        val record = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))
            .purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(PurgeState.PURGE_INCOMPLETE, record.state)
        assertTrue(record.locationResults.any { it.terminalResult == PurgeTerminalResult.POST_SCAN_RESIDUE })
        assertTrue(record.locationResults.all { it.preInventory.entriesScanned > 0 && it.postScan.entriesScanned > 0 })
        assertTrue(record.render().length < 4096)
        assertFalse(record.render().contains("10000000-0000-4000-8000-000000000001"))
    }
}

private class FakePurgeFilesystem(
    var crashAfter: String? = null,
    private val blocker: PurgeBlocker? = null,
    private val postScanOwnedRemaining: Int = 0,
    private val entriesScanned: Int = 2,
    private val ownedEntries: Int = 1,
    private val reportsAlreadyAbsentAfterDelete: Boolean = false,
) : PurgeFilesystem {
    override val registrySha256: String = "b".repeat(64)
    val trace = mutableListOf<String>()
    val journal = mutableListOf<PurgeTombstone>()
    val deletedLocations = mutableListOf<StorageLocationId>()
    var deletePasses = 0
    private var temporaryJournal: List<PurgeTombstone>? = null

    override fun readJournal(sessionId: PurgeSessionId): PurgeJournalRead = PurgeJournalRead.Ready(journal.toList())

    override fun writeTemporaryJournal(sessionId: PurgeSessionId, records: List<PurgeTombstone>) {
        trace += "write-temp"
        temporaryJournal = records
        crash("write-temp")
    }

    override fun fsyncTemporaryJournal(sessionId: PurgeSessionId) {
        trace += "fsync-temp"
        crash("fsync-temp")
    }

    override fun renameTemporaryJournal(sessionId: PurgeSessionId) {
        trace += "rename-tombstone"
        journal.clear()
        journal += requireNotNull(temporaryJournal)
        crash("rename-tombstone")
    }

    override fun fsyncTombstoneRoot() {
        trace += "fsync-tombstone-root"
        crash("fsync-tombstone-root")
    }

    override fun inventory(sessionId: PurgeSessionId, location: StorageLocationId): PurgeInventoryResult {
        trace += "inventory-${location.wireName()}"
        crash("inventory-${location.wireName()}")
        return blocker?.let(PurgeInventoryResult::Blocked)
            ?: PurgeInventoryResult.Ready(PurgeInventory(
                entriesScanned = entriesScanned,
                ownedEntries = if (reportsAlreadyAbsentAfterDelete && location in deletedLocations) 0 else ownedEntries,
                ownedFiles = if (reportsAlreadyAbsentAfterDelete && location in deletedLocations) 0 else ownedEntries,
                ownedBytes = if (reportsAlreadyAbsentAfterDelete && location in deletedLocations) 0 else if (ownedEntries == 0) 0 else 17,
            ))
    }

    override fun deleteOwned(sessionId: PurgeSessionId, location: StorageLocationId): PurgeDeleteResult {
        trace += "delete-${location.wireName()}"
        deletePasses++
        deletedLocations += location
        crash("delete-${location.wireName()}")
        return if (reportsAlreadyAbsentAfterDelete) PurgeDeleteResult.ALREADY_ABSENT else PurgeDeleteResult.DELETED
    }

    override fun postScan(sessionId: PurgeSessionId, location: StorageLocationId): PurgePostScan {
        trace += "post-scan-${location.wireName()}"
        crash("post-scan-${location.wireName()}")
        return PurgePostScan(entriesScanned = 2, ownedRemaining = postScanOwnedRemaining)
    }

    private fun crash(boundary: String) {
        if (crashAfter == boundary) throw SimulatedCrash(boundary)
    }
}

private class SimulatedCrash(boundary: String) : RuntimeException(boundary)

private fun StorageLocationId.wireName(): String = name.lowercase()
