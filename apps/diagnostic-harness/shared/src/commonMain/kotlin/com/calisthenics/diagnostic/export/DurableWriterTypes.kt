package com.calisthenics.diagnostic.export

import com.calisthenics.diagnostic.contract.Counts

enum class DurableWriterState {
    NEW,
    OPEN,
    SEALING,
    PUBLISHED,
    ABORTED,
    FAILED,
    UNCERTAIN_DURABILITY,
}

/** Compact, bounded digest state persisted in a checkpoint. Final artifact digests come from a re-read. */
data class Sha256State(
    val byteLength: Long,
    val digestHex: String,
)

data class DurableCheckpoint(
    val lastDurableRecordIndex: Long,
    val counts: Counts,
    val posePresentCount: Long,
    val digestState: Sha256State,
)

data class FinalSessionAggregates(val counts: Counts)

data class SealedArtifact(
    val artifact: DurableArtifact,
    val reconciliation: ArtifactReconciliation,
)

data class SealedArtifacts(
    val attempt: StagingAttempt,
    val artifacts: List<SealedArtifact>,
    val checkpoint: DurableCheckpoint,
)

class DurableWriterStateException(message: String) : IllegalStateException(message)

class DurableReconciliationException(message: String) : IllegalStateException(message)

class DurableStorageException(
    val operation: String,
    cause: Throwable,
) : IllegalStateException("durable storage operation failed: $operation", cause)
