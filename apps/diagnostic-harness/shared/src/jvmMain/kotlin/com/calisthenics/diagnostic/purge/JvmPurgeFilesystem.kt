package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.contract.decodeManifest
import com.calisthenics.diagnostic.export.StorageLocationId
import com.calisthenics.diagnostic.export.StorageLocationRegistry
import com.calisthenics.diagnostic.export.StorageRoots
import com.calisthenics.diagnostic.hash.Sha256Digest
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * JVM-host implementation of the narrow purge filesystem port. It resolves only the four
 * registry-derived Android roots supplied by [StorageRoots]; callers cannot pass a path, glob, or
 * root. NOFOLLOW classification and device/inode rechecks narrow the Android app-private
 * single-writer TOCTOU assumption, but do not claim immunity from a malicious concurrent writer.
 */
class JvmPurgeFilesystem(private val roots: StorageRoots) : PurgeFilesystem {
    override val registrySha256: String = StorageLocationRegistry.CANONICAL_REGISTRY_SHA256

    private val rootPaths = mapOf(
        StorageLocationId.DEVICE_EXPORT to normalized(roots.exportsRoot),
        StorageLocationId.DEVICE_STAGING to normalized(roots.stagingRoot),
        StorageLocationId.DEVICE_TRANSFER_STAGING to normalized(roots.transferStagingRoot),
        StorageLocationId.TOMBSTONE to normalized(roots.tombstoneRoot),
    )
    private val tombstoneRoot = requireNotNull(rootPaths[StorageLocationId.TOMBSTONE])

    init {
        val base = normalized(roots.diagnosticHarnessRoot)
        rootPaths.forEach { (location, path) ->
            require(path.parent == base) { "$location must be a direct registry-derived diagnostic-harness child" }
            require(path.fileName.toString() == StorageLocationRegistry.androidChild(location)) {
                "$location root is not bound to the canonical registry child"
            }
        }
    }

    override fun readJournal(sessionId: PurgeSessionId): PurgeJournalRead = try {
        requireDirectory(tombstoneRoot)
        val journal = journalPath(sessionId)
        if (!Files.exists(journal, NOFOLLOW_LINKS)) return PurgeJournalRead.Ready(emptyList())
        val snapshot = requireRegularFile(journal)
        if (snapshot == null) return PurgeJournalRead.Unreadable
        val records = decodeJournal(Files.readAllBytes(journal))
        if (records.any { it.registrySha256 != registrySha256 }) {
            PurgeJournalRead.Rejected(PurgeBlocker.REGISTRY_DIGEST_MISMATCH)
        } else {
            validateJournal(sessionId, records)
            PurgeJournalRead.Ready(records)
        }
    } catch (_: Throwable) {
        PurgeJournalRead.Unreadable
    }

    override fun writeTemporaryJournal(sessionId: PurgeSessionId, records: List<PurgeTombstone>) {
        validateJournal(sessionId, records)
        requireDirectory(tombstoneRoot)
        val temporary = temporaryJournalPath(sessionId)
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            require(requireRegularFile(temporary) != null) { "purge journal temporary file is not regular" }
            Files.delete(temporary)
            fsyncTombstoneRoot()
        }
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(encodeJournal(records))
            while (bytes.hasRemaining()) channel.write(bytes)
        }
    }

    override fun fsyncTemporaryJournal(sessionId: PurgeSessionId) {
        val temporary = temporaryJournalPath(sessionId)
        require(requireRegularFile(temporary) != null) { "purge journal temporary file is not regular" }
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
    }

    override fun renameTemporaryJournal(sessionId: PurgeSessionId) {
        val temporary = temporaryJournalPath(sessionId)
        val journal = journalPath(sessionId)
        require(requireRegularFile(temporary) != null) { "purge journal temporary file is not regular" }
        if (Files.exists(journal, NOFOLLOW_LINKS)) {
            require(requireRegularFile(journal) != null) { "purge journal is not regular" }
        }
        Files.move(temporary, journal, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    override fun fsyncTombstoneRoot() {
        FileChannel.open(tombstoneRoot, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }

    override fun inventory(sessionId: PurgeSessionId, location: StorageLocationId): PurgeInventoryResult {
        val targets = resolveOwnedTargets(sessionId, location)
        return when (targets) {
            is TargetResolution.Blocked -> PurgeInventoryResult.Blocked(targets.blocker)
            is TargetResolution.Ready -> PurgeInventoryResult.Ready(targets.inventory)
        }
    }

    override fun deleteOwned(sessionId: PurgeSessionId, location: StorageLocationId): PurgeDeleteResult {
        val targets = resolveOwnedTargets(sessionId, location)
        if (targets !is TargetResolution.Ready) return PurgeDeleteResult.FAILED
        if (targets.targets.isEmpty()) return PurgeDeleteResult.ALREADY_ABSENT
        return if (targets.targets.all(::deleteOwnedTree)) PurgeDeleteResult.DELETED else PurgeDeleteResult.FAILED
    }

    override fun postScan(sessionId: PurgeSessionId, location: StorageLocationId): PurgePostScan =
        when (val targets = resolveOwnedTargets(sessionId, location)) {
            is TargetResolution.Blocked -> PurgePostScan(entriesScanned = 0, ownedRemaining = 1)
            is TargetResolution.Ready -> PurgePostScan(
                entriesScanned = targets.inventory.entriesScanned + 1,
                ownedRemaining = targets.inventory.ownedEntries,
            )
        }

    private fun resolveOwnedTargets(sessionId: PurgeSessionId, location: StorageLocationId): TargetResolution {
        if (location !in PurgeFilesystem.devicePurgeLocations) return TargetResolution.Blocked(PurgeBlocker.UNKNOWN_ROOT)
        if (registrySha256 != StorageLocationRegistry.CANONICAL_REGISTRY_SHA256) {
            return TargetResolution.Blocked(PurgeBlocker.REGISTRY_DIGEST_MISMATCH)
        }
        val root = rootPaths[location] ?: return TargetResolution.Blocked(PurgeBlocker.UNKNOWN_ROOT)
        val rootSnapshot = snapshot(root) ?: return TargetResolution.Blocked(PurgeBlocker.TYPE_CHANGED)
        if (rootSnapshot.kind == NodeKind.SYMLINK) return TargetResolution.Blocked(PurgeBlocker.SYMLINK)
        if (rootSnapshot.kind != NodeKind.DIRECTORY) return TargetResolution.Blocked(PurgeBlocker.TYPE_CHANGED)

        val directChildren = try {
            Files.newDirectoryStream(root).use { entries -> entries.toList() }
        } catch (_: Throwable) {
            return TargetResolution.Blocked(PurgeBlocker.TYPE_CHANGED)
        }
        val candidates = when (location) {
            StorageLocationId.DEVICE_EXPORT -> directChildren.filter { it.fileName.toString() == sessionId.value }
            StorageLocationId.DEVICE_STAGING -> directChildren.filter { isStagingAttemptFor(it.fileName.toString(), sessionId) }
            StorageLocationId.DEVICE_TRANSFER_STAGING -> directChildren.filter {
                it.fileName.toString() == transferBundleName(sessionId)
            }
            else -> return TargetResolution.Blocked(PurgeBlocker.UNKNOWN_ROOT)
        }
        if (candidates.any { it.parent != root || it.isAbsolute && it.normalize().parent != root }) {
            return TargetResolution.Blocked(PurgeBlocker.PATH_ESCAPE)
        }

        if (location == StorageLocationId.DEVICE_EXPORT && candidates.any { !isOwnedCommittedExport(it, sessionId) }) {
            return TargetResolution.Blocked(PurgeBlocker.TYPE_CHANGED)
        }

        var ownedEntries = 0
        var ownedFiles = 0
        var ownedBytes = 0L
        candidates.forEach { candidate ->
            val tree = scanOwnedTree(candidate) ?: return TargetResolution.Blocked(blockerFor(candidate))
            ownedEntries += tree.nodes.size
            ownedFiles += tree.nodes.count { it.snapshot.kind == NodeKind.FILE }
            ownedBytes += tree.nodes.filter { it.snapshot.kind == NodeKind.FILE }.sumOf { it.snapshot.byteLength }
        }
        return TargetResolution.Ready(
            targets = candidates,
            inventory = PurgeInventory(
                entriesScanned = directChildren.size + ownedEntries - candidates.size,
                ownedEntries = ownedEntries,
                ownedFiles = ownedFiles,
                ownedBytes = ownedBytes,
            ),
        )
    }

    private fun scanOwnedTree(root: Path): OwnedTree? {
        val nodes = mutableListOf<OwnedNode>()
        fun visit(path: Path): Boolean {
            val node = snapshot(path) ?: return false
            if (node.kind == NodeKind.SYMLINK || node.kind == NodeKind.OTHER) return false
            nodes += OwnedNode(path, node)
            if (node.kind == NodeKind.DIRECTORY) {
                val children = try {
                    Files.newDirectoryStream(path).use { entries -> entries.toList() }
                } catch (_: Throwable) {
                    return false
                }
                if (children.any { it.parent != path }) return false
                children.forEach { child -> if (!visit(child)) return false }
            }
            return true
        }
        if (!visit(root)) return null
        if (nodes.firstOrNull()?.snapshot?.kind != NodeKind.DIRECTORY) return null
        return OwnedTree(nodes)
    }

    private fun isOwnedCommittedExport(export: Path, sessionId: PurgeSessionId): Boolean = try {
        if (snapshot(export)?.kind != NodeKind.DIRECTORY) return false
        val manifest = export.resolve("manifest.json")
        if (requireRegularFile(manifest) == null) return false
        val decoded = decodeManifest(Files.readString(manifest))
        decoded.sessionKind == "live_conformance" &&
            decoded.sessionId.value == sessionId.value &&
            decoded.participantPseudonymId == "synthetic-m05-${sessionId.value}"
    } catch (_: Throwable) {
        false
    }

    private fun deleteOwnedTree(root: Path): Boolean {
        val tree = scanOwnedTree(root) ?: return false
        tree.nodes.asReversed().forEach { node ->
            val current = snapshot(node.path) ?: return false
            if (!current.sameFilesystemObject(node.snapshot)) return false
            try {
                Files.delete(node.path)
                FileChannel.open(node.path.parent, StandardOpenOption.READ).use { channel -> channel.force(true) }
            } catch (_: Throwable) {
                return false
            }
        }
        return true
    }

    private fun requireDirectory(path: Path) {
        val node = snapshot(path) ?: error("required purge root is absent")
        require(node.kind == NodeKind.DIRECTORY) { "purge root must be a non-link directory" }
    }

    private fun requireRegularFile(path: Path): NodeSnapshot? = snapshot(path)?.takeIf { it.kind == NodeKind.FILE }

    private fun snapshot(path: Path): NodeSnapshot? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val kind = when {
            attributes.isSymbolicLink -> NodeKind.SYMLINK
            attributes.isDirectory -> NodeKind.DIRECTORY
            attributes.isRegularFile -> NodeKind.FILE
            else -> NodeKind.OTHER
        }
        val unix = Files.readAttributes(path, "unix:dev,ino", NOFOLLOW_LINKS)
        val device = unix["dev"] as? Number ?: return null
        val inode = unix["ino"] as? Number ?: return null
        NodeSnapshot(kind, attributes.size(), device.toLong(), inode.toLong())
    } catch (_: Throwable) {
        null
    }

    private fun blockerFor(path: Path): PurgeBlocker = when (snapshot(path)?.kind) {
        NodeKind.SYMLINK -> PurgeBlocker.SYMLINK
        null -> PurgeBlocker.TYPE_CHANGED
        else -> PurgeBlocker.TYPE_CHANGED
    }

    private fun validateJournal(sessionId: PurgeSessionId, records: List<PurgeTombstone>) {
        require(records.isNotEmpty()) { "durable journal cannot be empty" }
        require(records.size <= PurgeFilesystem.MAX_OPERATION_COUNT) { "purge journal exceeds bounded operation count" }
        records.forEachIndexed { index, record ->
            require(record.sessionId == sessionId) { "journal session does not match target" }
            require(record.operationSequence == index + 1) { "journal operation sequence is not contiguous" }
            require(record.registrySha256 == registrySha256) { "journal registry digest differs from canonical registry" }
            val expectedPrevious = records.getOrNull(index - 1)?.persistedLineSha256()
            require(record.previousRecordSha256 == expectedPrevious) { "journal hash link is invalid" }
        }
    }

    private fun encodeJournal(records: List<PurgeTombstone>): ByteArray =
        records.fold(ByteArray(0)) { bytes, record -> bytes + record.persistedLine() }

    private fun decodeJournal(bytes: ByteArray): List<PurgeTombstone> {
        require(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) { "journal must be LF-terminated" }
        val records = mutableListOf<PurgeTombstone>()
        var offset = 0
        while (offset < bytes.size) {
            val end = bytes.indexOf('\n'.code.toByte(), offset)
            require(end >= offset) { "journal line is not LF-terminated" }
            val line = bytes.copyOfRange(offset, end + 1)
            val record = decodeTombstone(Json.parseToJsonElement(line.decodeToString()).jsonObject)
            val expectedPrevious = records.lastOrNull()?.let { previous ->
                Sha256Digest.hex(bytes.copyOfRange(0, offset).takeLastLine())
            }
            require(record.previousRecordSha256 == expectedPrevious) { "journal exact-byte hash link is invalid" }
            records += record
            offset = end + 1
        }
        require(records.isNotEmpty()) { "durable journal cannot be empty" }
        return records
    }

    private fun ByteArray.takeLastLine(): ByteArray {
        val previousEnd = size - 1
        val previousStart = lastIndexOf('\n'.code.toByte(), previousEnd - 1) + 1
        return copyOfRange(previousStart, previousEnd + 1)
    }

    private fun ByteArray.indexOf(value: Byte, fromIndex: Int): Int {
        for (index in fromIndex until size) if (this[index] == value) return index
        return -1
    }

    private fun ByteArray.lastIndexOf(value: Byte, fromIndex: Int): Int {
        for (index in fromIndex downTo 0) if (this[index] == value) return index
        return -1
    }

    private fun decodeTombstone(source: JsonObject): PurgeTombstone {
        require(source.keys == TOMBSTONE_KEYS) { "tombstone keys differ from the draft schema" }
        require(source.string("purge_tombstone_schema_version") == "1.0.0-draft")
        require(source.string("policy_status") == "proposal")
        require(source.string("deletion_scope") == "registered_device_roots_only")
        require(source.string("pseudonym_class") == "synthetic_m05")
        require(source.string("registry_version") == "1.0.0")
        val sessionId = source.string("session_id")
        require(UUID_V4.matches(sessionId)) { "session ID is not a lowercase UUIDv4" }
        val parsedSession = requireNotNull(PurgeSessionId.parseOrNull(sessionId))
        val operationId = source.string("operation_id")
        require(UUID_V4.matches(operationId)) { "operation ID is not a lowercase UUIDv4" }
        val operationSequence = source.integer("operation_sequence", 1, PurgeFilesystem.MAX_OPERATION_COUNT)
        val registryDigest = source.string("registry_sha256")
        require(SHA256.matches(registryDigest)) { "registry digest is not lowercase SHA-256" }
        val locationResults = source.getValue("locations").jsonArray.map { decodeLocation(it.jsonObject) }
        require(locationResults.map(PurgeLocationResult::location) == PurgeFilesystem.devicePurgeLocations) {
            "journal locations differ from the ordered registered device purge set"
        }
        val previousRecordSha256 = when (val previous = source.getValue("previous_record_sha256")) {
            JsonNull -> null
            is JsonPrimitive -> {
                require(previous.isString && SHA256.matches(previous.content)) {
                    "previous record digest is not lowercase SHA-256"
                }
                previous.content
            }
            else -> error("previous record digest must be a string or null")
        }
        val producerGitSha = source.string("producer_git_sha")
        require(GIT_SHA.matches(producerGitSha)) { "producer Git SHA is not lowercase SHA-1" }
        return PurgeTombstone(
            sessionId = parsedSession,
            operationId = operationId,
            operationSequence = operationSequence,
            state = PurgeState.valueOf(source.string("state")),
            registrySha256 = registryDigest,
            locationResults = locationResults,
            previousRecordSha256 = previousRecordSha256,
            producerGitSha = producerGitSha,
        ).also(::validateRecordSemantics)
    }

    private fun decodeLocation(source: JsonObject): PurgeLocationResult {
        require(source.keys == LOCATION_KEYS) { "location keys differ from the draft schema" }
        val inventory = source.getValue("pre_inventory").jsonObject
        val postScan = source.getValue("post_scan").jsonObject
        require(inventory.keys == INVENTORY_KEYS) { "inventory keys differ from the draft schema" }
        require(postScan.keys == POST_SCAN_KEYS) { "post-scan keys differ from the draft schema" }
        return PurgeLocationResult(
            location = StorageLocationId.entries.single { it.name.lowercase() == source.string("location_id") },
            preInventory = PurgeInventory(
                entriesScanned = inventory.integer("entries_scanned", 0, MAX_ENTRY_COUNT),
                ownedEntries = inventory.integer("owned_entries", 0, MAX_ENTRY_COUNT),
                ownedFiles = inventory.integer("owned_files", 0, MAX_ENTRY_COUNT),
                ownedBytes = inventory.longValue("owned_bytes", 0, MAX_OWNED_BYTES),
            ),
            terminalResult = PurgeTerminalResult.entries.first { it.name.lowercase() == source.string("terminal_result") },
            postScan = PurgePostScan(
                entriesScanned = postScan.integer("entries_scanned", 0, MAX_ENTRY_COUNT),
                ownedRemaining = postScan.integer("owned_remaining", 0, MAX_ENTRY_COUNT),
            ),
        )
    }

    private fun validateRecordSemantics(record: PurgeTombstone) {
        if (record.state != PurgeState.INTENT_RECORDED) {
            require(record.locationResults.all {
                it.preInventory.entriesScanned >= 1 && it.postScan.entriesScanned >= 1
            }) { "terminal tombstone inventories must be non-vacuous" }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { value ->
        require(value.isString) { "$key must be a JSON string" }
        value.content
    }

    private fun JsonObject.integer(key: String, minimum: Int, maximum: Int): Int =
        getValue(key).jsonPrimitive.let { value ->
            require(!value.isString) { "$key must be a JSON integer" }
            value.int.also { require(it in minimum..maximum) { "$key is outside the draft schema bounds" } }
        }

    private fun JsonObject.longValue(key: String, minimum: Long, maximum: Long): Long =
        getValue(key).jsonPrimitive.let { value ->
            require(!value.isString) { "$key must be a JSON integer" }
            value.long.also { require(it in minimum..maximum) { "$key is outside the draft schema bounds" } }
        }

    private fun journalPath(sessionId: PurgeSessionId): Path = tombstoneRoot.resolve("${sessionId.value}.purge-tombstone.json")
    private fun temporaryJournalPath(sessionId: PurgeSessionId): Path = tombstoneRoot.resolve("${sessionId.value}.purge-tombstone.tmp")

    private fun normalized(path: String): Path = Path.of(path).toAbsolutePath().normalize()
    private fun isStagingAttemptFor(name: String, sessionId: PurgeSessionId): Boolean =
        name.matches(Regex("^${Regex.escape(sessionId.value)}\\.[0-9]+\\.staging$"))

    companion object {
        fun transferBundleName(sessionId: PurgeSessionId): String = "${sessionId.value}.transfer.bundle"

        private val TOMBSTONE_KEYS = setOf(
            "purge_tombstone_schema_version",
            "policy_status",
            "deletion_scope",
            "session_id",
            "pseudonym_class",
            "operation_id",
            "operation_sequence",
            "state",
            "registry_version",
            "registry_sha256",
            "locations",
            "previous_record_sha256",
            "producer_git_sha",
        )
        private val LOCATION_KEYS = setOf("location_id", "pre_inventory", "terminal_result", "post_scan")
        private val INVENTORY_KEYS = setOf("entries_scanned", "owned_entries", "owned_files", "owned_bytes")
        private val POST_SCAN_KEYS = setOf("entries_scanned", "owned_remaining")
        private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val GIT_SHA = Regex("^[0-9a-f]{40}$")
        private const val MAX_ENTRY_COUNT = 1_000_000
        private const val MAX_OWNED_BYTES = 1_073_741_824L
    }

    private sealed interface TargetResolution {
        data class Ready(val targets: List<Path>, val inventory: PurgeInventory) : TargetResolution
        data class Blocked(val blocker: PurgeBlocker) : TargetResolution
    }

    private data class OwnedTree(val nodes: List<OwnedNode>)
    private data class OwnedNode(val path: Path, val snapshot: NodeSnapshot)
    private data class NodeSnapshot(val kind: NodeKind, val byteLength: Long, val device: Long, val inode: Long) {
        fun sameFilesystemObject(other: NodeSnapshot): Boolean =
            kind == other.kind && device == other.device && inode == other.inode
    }
    private enum class NodeKind { FILE, DIRECTORY, SYMLINK, OTHER }
}
