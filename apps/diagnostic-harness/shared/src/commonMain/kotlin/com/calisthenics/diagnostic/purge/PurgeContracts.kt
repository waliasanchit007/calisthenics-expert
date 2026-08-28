package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.export.StorageLocationId
import com.calisthenics.diagnostic.hash.Sha256Digest
import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The sole synthetic namespace Task 5a is authorized to delete. */
@JvmInline
value class PurgeSessionId private constructor(val value: String) {
    companion object {
        private val reservedPattern = Regex("^00000000-0000-4000-8000-[0-9a-f]{12}$")

        fun parseOrNull(value: String): PurgeSessionId? =
            value.takeIf { reservedPattern.matches(it) }?.let(::PurgeSessionId)
    }
}

enum class PurgeState {
    INTENT_RECORDED,
    PURGE_INCOMPLETE,
    PURGE_COMPLETE,
}

/** Closed blockers reported by a registry-bound filesystem adapter, never supplied by callers. */
enum class PurgeBlocker {
    TRAVERSAL,
    ABSOLUTE_PATH,
    PREFIX_CONFUSION,
    SYMLINK,
    UNKNOWN_ROOT,
    TYPE_CHANGED,
    REGISTRY_DIGEST_MISMATCH,
    PATH_ESCAPE,
    NON_VACUOUS_INVENTORY,
}

enum class PurgeTerminalResult {
    PENDING,
    DELETED,
    ALREADY_ABSENT,
    BLOCKED,
    DELETE_FAILED,
    POST_SCAN_RESIDUE,
}

data class PurgeInventory(
    val entriesScanned: Int,
    val ownedEntries: Int,
    val ownedFiles: Int,
    val ownedBytes: Long,
)

data class PurgePostScan(val entriesScanned: Int, val ownedRemaining: Int)

sealed interface PurgeInventoryResult {
    data class Ready(val inventory: PurgeInventory) : PurgeInventoryResult
    data class Blocked(val blocker: PurgeBlocker) : PurgeInventoryResult
}

enum class PurgeDeleteResult { DELETED, ALREADY_ABSENT, FAILED }

data class PurgeLocationResult(
    val location: StorageLocationId,
    val preInventory: PurgeInventory,
    val terminalResult: PurgeTerminalResult,
    val postScan: PurgePostScan,
)

data class PurgeTombstone(
    val sessionId: PurgeSessionId,
    val operationId: String,
    val operationSequence: Int,
    val state: PurgeState,
    val registrySha256: String,
    val locationResults: List<PurgeLocationResult>,
    val previousRecordSha256: String?,
    val producerGitSha: String,
)

/** The exact LF-terminated JSON line persisted by every Task 5a tombstone adapter. */
fun PurgeTombstone.persistedLine(): ByteArray = buildJsonObject {
    put("purge_tombstone_schema_version", "1.0.0-draft")
    put("policy_status", "proposal")
    put("deletion_scope", "registered_device_roots_only")
    put("session_id", sessionId.value)
    put("pseudonym_class", "synthetic_m05")
    put("operation_id", operationId)
    put("operation_sequence", operationSequence)
    put("state", state.name)
    put("registry_version", "1.0.0")
    put("registry_sha256", registrySha256)
    putJsonArray("locations") { locationResults.forEach { add(locationJson(it)) } }
    if (previousRecordSha256 == null) {
        put("previous_record_sha256", JsonNull)
    } else {
        put("previous_record_sha256", previousRecordSha256)
    }
    put("producer_git_sha", producerGitSha)
}.toString().plus('\n').encodeToByteArray()

/** The exact persisted bytes of the immediately preceding line, never a model-field surrogate. */
fun PurgeTombstone.persistedLineSha256(): String = Sha256Digest.hex(persistedLine())

private fun locationJson(result: PurgeLocationResult) = buildJsonObject {
    put("location_id", result.location.name.lowercase())
    put("pre_inventory", inventoryJson(result.preInventory))
    put("terminal_result", result.terminalResult.name.lowercase())
    put("post_scan", buildJsonObject {
        put("entries_scanned", result.postScan.entriesScanned)
        put("owned_remaining", result.postScan.ownedRemaining)
    })
}

private fun inventoryJson(inventory: PurgeInventory) = buildJsonObject {
    put("entries_scanned", inventory.entriesScanned)
    put("owned_entries", inventory.ownedEntries)
    put("owned_files", inventory.ownedFiles)
    put("owned_bytes", inventory.ownedBytes)
}

sealed interface PurgeJournalRead {
    data class Ready(val records: List<PurgeTombstone>) : PurgeJournalRead
    data class Rejected(val blocker: PurgeBlocker) : PurgeJournalRead
    data object Unreadable : PurgeJournalRead
}

/**
 * The only filesystem authority available to common purge logic. It exposes no path, root, glob,
 * or recursive-delete input: every operation is scoped to [devicePurgeLocations] and the parsed
 * reserved [PurgeSessionId]. Android app-private single-writer serialization is the remaining
 * TOCTOU assumption; adapters still classify no-follow entries and recheck device/inode at unlink.
 */
interface PurgeFilesystem {
    val registrySha256: String

    fun readJournal(sessionId: PurgeSessionId): PurgeJournalRead
    fun writeTemporaryJournal(sessionId: PurgeSessionId, records: List<PurgeTombstone>)
    fun fsyncTemporaryJournal(sessionId: PurgeSessionId)
    fun renameTemporaryJournal(sessionId: PurgeSessionId)
    fun fsyncTombstoneRoot()
    fun inventory(sessionId: PurgeSessionId, location: StorageLocationId): PurgeInventoryResult
    fun deleteOwned(sessionId: PurgeSessionId, location: StorageLocationId): PurgeDeleteResult
    fun postScan(sessionId: PurgeSessionId, location: StorageLocationId): PurgePostScan

    companion object {
        const val MAX_OPERATION_COUNT = 64
        val devicePurgeLocations = listOf(
            StorageLocationId.DEVICE_EXPORT,
            StorageLocationId.DEVICE_STAGING,
            StorageLocationId.DEVICE_TRANSFER_STAGING,
        )
    }
}

data class PurgeRecord(
    val state: PurgeState,
    val operationSequence: Int,
    val locationResults: List<PurgeLocationResult>,
) {
    /** Bounded public evidence with closed values/counts only; persistent IDs never escape. */
    fun render(): String = buildString {
        append("purge_record_schema_version=1.0.0-draft;")
        append("policy_status=proposal;")
        append("deletion_scope=registered_device_roots_only;")
        append("state=")
        append(state.name)
        append(";operation_sequence=")
        append(operationSequence)
        locationResults.forEach { result ->
            append(";location=")
            append(result.location.name.lowercase())
            append(",result=")
            append(result.terminalResult.name.lowercase())
            append(",pre=")
            append(result.preInventory.entriesScanned)
            append(',')
            append(result.preInventory.ownedEntries)
            append(',')
            append(result.preInventory.ownedFiles)
            append(',')
            append(result.preInventory.ownedBytes)
            append(",post=")
            append(result.postScan.entriesScanned)
            append(',')
            append(result.postScan.ownedRemaining)
        }
    }
}

class PurgeRefusal(val code: String) : IllegalStateException(code)
