package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.export.StorageLocationId

/**
 * Deterministic, registry-only synthetic purge transaction. The caller supplies a reserved ID and
 * operation UUID, never paths; the [PurgeFilesystem] adapter owns no-follow path resolution.
 */
class SyntheticPurge(
    private val filesystem: PurgeFilesystem,
    private val producerGitSha: String,
) {
    fun purge(sessionId: PurgeSessionId, operationId: String): PurgeRecord {
        require(operationId.matches(UUID_PATTERN)) { "operation ID must be a lowercase UUID" }
        require(producerGitSha.matches(GIT_SHA_PATTERN)) { "producer Git SHA must be lowercase SHA-1" }
        val existing = when (val journal = filesystem.readJournal(sessionId)) {
            PurgeJournalRead.Unreadable -> throw PurgeRefusal("PURGE.DURABILITY_UNCERTAIN")
            is PurgeJournalRead.Rejected -> throw PurgeRefusal("PURGE.${journal.blocker.name}")
            is PurgeJournalRead.Ready -> journal.records
        }
        require(existing.size <= PurgeFilesystem.MAX_OPERATION_COUNT) { "purge journal exceeds bounded operation count" }
        existing.lastOrNull { it.state == PurgeState.PURGE_COMPLETE }?.let { return it.publicRecord() }
        val existingIntent = existing.firstOrNull { it.state == PurgeState.INTENT_RECORDED }
        val intent = existingIntent ?: appendIntent(existing, sessionId, operationId)
        if (existingIntent != null) filesystem.fsyncTombstoneRoot()
        return executeFromDurableIntent(existingWithIntent(existing, intent), intent)
    }

    private fun appendIntent(
        existing: List<PurgeTombstone>,
        sessionId: PurgeSessionId,
        operationId: String,
    ): PurgeTombstone {
        val intent = tombstone(
            sessionId = sessionId,
            operationId = operationId,
            operationSequence = nextSequence(existing),
            state = PurgeState.INTENT_RECORDED,
            locationResults = PurgeFilesystem.devicePurgeLocations.map { location ->
                PurgeLocationResult(location, EMPTY_INVENTORY, PurgeTerminalResult.PENDING, EMPTY_POST_SCAN)
            },
            previousRecordSha256 = existing.lastOrNull()?.persistedLineSha256(),
        )
        persist(existing + intent, sessionId)
        return intent
    }

    private fun executeFromDurableIntent(
        durableRecords: List<PurgeTombstone>,
        intent: PurgeTombstone,
    ): PurgeRecord {
        val inventoryResults = PurgeFilesystem.devicePurgeLocations.associateWith { location ->
            filesystem.inventory(intent.sessionId, location)
        }
        val priorOwnershipEvidence = durableRecords.lastOrNull { it.provesOwnedInventory() }
        val hasInventoryBlocker = inventoryResults.values.any { it is PurgeInventoryResult.Blocked }
        val currentInventoryIsVacuous = inventoryResults.values
            .filterIsInstance<PurgeInventoryResult.Ready>()
            .any { it.inventory.ownedEntries < 1 }
        val hasBlocker = hasInventoryBlocker || (priorOwnershipEvidence == null && currentInventoryIsVacuous)
        val ownershipEvidence = if (priorOwnershipEvidence == null && !hasBlocker) {
            appendOwnershipEvidence(durableRecords, intent, inventoryResults)
        } else {
            priorOwnershipEvidence
        }
        val recordsBeforeTerminal = if (ownershipEvidence != null && ownershipEvidence !in durableRecords) {
            durableRecords + ownershipEvidence
        } else {
            durableRecords
        }

        val results = PurgeFilesystem.devicePurgeLocations.map { location ->
            val inventoryResult = requireNotNull(inventoryResults[location])
            val inventory = ownershipEvidence?.locationResults
                ?.single { it.location == location }
                ?.preInventory
                ?: (inventoryResult as? PurgeInventoryResult.Ready)?.inventory
                ?: EMPTY_INVENTORY
            if (hasBlocker) {
                PurgeLocationResult(location, inventory, PurgeTerminalResult.BLOCKED, filesystem.postScan(intent.sessionId, location))
            } else {
                val deleteResult = filesystem.deleteOwned(intent.sessionId, location)
                val postScan = filesystem.postScan(intent.sessionId, location)
                val terminal = when {
                    postScan.ownedRemaining > 0 -> PurgeTerminalResult.POST_SCAN_RESIDUE
                    deleteResult == PurgeDeleteResult.DELETED -> PurgeTerminalResult.DELETED
                    deleteResult == PurgeDeleteResult.ALREADY_ABSENT -> PurgeTerminalResult.ALREADY_ABSENT
                    else -> PurgeTerminalResult.DELETE_FAILED
                }
                PurgeLocationResult(location, inventory, terminal, postScan)
            }
        }
        val state = if (results.all { it.terminalResult in setOf(PurgeTerminalResult.DELETED, PurgeTerminalResult.ALREADY_ABSENT) }) {
            PurgeState.PURGE_COMPLETE
        } else {
            PurgeState.PURGE_INCOMPLETE
        }
        val terminal = tombstone(
            sessionId = intent.sessionId,
            operationId = intent.operationId,
            operationSequence = nextSequence(recordsBeforeTerminal),
            state = state,
            locationResults = results,
            previousRecordSha256 = recordsBeforeTerminal.last().persistedLineSha256(),
        )
        persist(recordsBeforeTerminal + terminal, intent.sessionId)
        return terminal.publicRecord()
    }

    private fun appendOwnershipEvidence(
        existing: List<PurgeTombstone>,
        intent: PurgeTombstone,
        inventoryResults: Map<StorageLocationId, PurgeInventoryResult>,
    ): PurgeTombstone {
        val evidence = tombstone(
            sessionId = intent.sessionId,
            operationId = intent.operationId,
            operationSequence = nextSequence(existing),
            state = PurgeState.INTENT_RECORDED,
            locationResults = PurgeFilesystem.devicePurgeLocations.map { location ->
                val inventory = (requireNotNull(inventoryResults[location]) as PurgeInventoryResult.Ready).inventory
                require(inventory.ownedEntries > 0) { "ownership evidence must be non-vacuous" }
                PurgeLocationResult(location, inventory, PurgeTerminalResult.PENDING, EMPTY_POST_SCAN)
            },
            previousRecordSha256 = existing.last().persistedLineSha256(),
        )
        persist(existing + evidence, intent.sessionId)
        return evidence
    }

    private fun PurgeTombstone.provesOwnedInventory(): Boolean =
        state == PurgeState.INTENT_RECORDED &&
            locationResults.map(PurgeLocationResult::location) == PurgeFilesystem.devicePurgeLocations &&
            locationResults.all {
                it.terminalResult == PurgeTerminalResult.PENDING && it.preInventory.ownedEntries > 0
            }

    private fun existingWithIntent(existing: List<PurgeTombstone>, intent: PurgeTombstone): List<PurgeTombstone> =
        if (intent in existing) existing else existing + intent

    private fun persist(records: List<PurgeTombstone>, sessionId: PurgeSessionId) {
        require(records.size <= PurgeFilesystem.MAX_OPERATION_COUNT) { "purge journal exceeds bounded operation count" }
        filesystem.writeTemporaryJournal(sessionId, records)
        filesystem.fsyncTemporaryJournal(sessionId)
        filesystem.renameTemporaryJournal(sessionId)
        filesystem.fsyncTombstoneRoot()
    }

    private fun tombstone(
        sessionId: PurgeSessionId,
        operationId: String,
        operationSequence: Int,
        state: PurgeState,
        locationResults: List<PurgeLocationResult>,
        previousRecordSha256: String?,
    ) = PurgeTombstone(
        sessionId = sessionId,
        operationId = operationId,
        operationSequence = operationSequence,
        state = state,
        registrySha256 = filesystem.registrySha256,
        locationResults = locationResults,
        previousRecordSha256 = previousRecordSha256,
        producerGitSha = producerGitSha,
    )

    private fun nextSequence(records: List<PurgeTombstone>): Int = (records.lastOrNull()?.operationSequence ?: 0) + 1

    private fun PurgeTombstone.publicRecord() = PurgeRecord(state, operationSequence, locationResults)

    private companion object {
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val GIT_SHA_PATTERN = Regex("^[0-9a-f]{40}$")
        val EMPTY_INVENTORY = PurgeInventory(0, 0, 0, 0)
        val EMPTY_POST_SCAN = PurgePostScan(0, 0)
    }
}
