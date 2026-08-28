package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.export.StorageLocationId
import com.calisthenics.diagnostic.export.StorageLocationRegistry
import com.calisthenics.diagnostic.export.StorageRoots
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Android entry point for the common transaction. The real app-private filesystem authority is
 * deliberately injected as [PurgeFilesystem], keeping paths, globs, and recursive deletion APIs
 * outside the call surface and allowing the capture owner to serialize this call with capture.
 */
class AndroidSyntheticPurge(
    filesystem: PurgeFilesystem,
    producerGitSha: String,
) {
    private val transaction = SyntheticPurge(filesystem, producerGitSha)

    @Synchronized
    fun purge(sessionId: String, operationId: String): PurgeRecord {
        val reserved = PurgeSessionId.parseOrNull(sessionId)
            ?: throw PurgeRefusal("PURGE.INVALID_SESSION_ID")
        return transaction.purge(reserved, operationId)
    }
}

/**
 * The production writer only needs a fail-closed tombstone read. Any exact journal entry under
 * the registry-derived tombstone root blocks the session, even if the entry is malformed or its
 * bytes cannot be read. The mutation-capable filesystem adapter remains a JVM/test authority in
 * Task 5a; this Android adapter exposes no deletion operation to production composition.
 */
class AndroidTombstoneFenceFilesystem(private val roots: StorageRoots) : PurgeFilesystem {
    override val registrySha256: String = StorageLocationRegistry.CANONICAL_REGISTRY_SHA256

    override fun readJournal(sessionId: PurgeSessionId): PurgeJournalRead = try {
        val root = Path.of(roots.tombstoneRoot).toAbsolutePath().normalize()
        val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) return PurgeJournalRead.Unreadable
        val journal = root.resolve("${sessionId.value}.purge-tombstone.json")
        if (!Files.exists(journal, NOFOLLOW_LINKS)) return PurgeJournalRead.Ready(emptyList())
        val journalAttributes = Files.readAttributes(journal, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!journalAttributes.isRegularFile || journalAttributes.isSymbolicLink) return PurgeJournalRead.Unreadable
        PurgeJournalRead.Unreadable
    } catch (_: Throwable) {
        PurgeJournalRead.Unreadable
    }

    override fun writeTemporaryJournal(sessionId: PurgeSessionId, records: List<PurgeTombstone>) = unavailable()
    override fun fsyncTemporaryJournal(sessionId: PurgeSessionId) = unavailable()
    override fun renameTemporaryJournal(sessionId: PurgeSessionId) = unavailable()
    override fun fsyncTombstoneRoot() = unavailable()
    override fun inventory(sessionId: PurgeSessionId, location: StorageLocationId): PurgeInventoryResult = unavailable()
    override fun deleteOwned(sessionId: PurgeSessionId, location: StorageLocationId): PurgeDeleteResult = unavailable()
    override fun postScan(sessionId: PurgeSessionId, location: StorageLocationId): PurgePostScan = unavailable()

    private fun unavailable(): Nothing = error("Android production fence exposes no purge mutation authority")
}
