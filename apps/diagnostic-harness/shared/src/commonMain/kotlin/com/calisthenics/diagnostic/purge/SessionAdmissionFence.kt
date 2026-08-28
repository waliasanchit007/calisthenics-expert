package com.calisthenics.diagnostic.purge

/** Checked once before opening a writer and again immediately before its final publication rename. */
enum class SessionAdmissionPhase { WRITER_OPEN, FINAL_PUBLISH }

sealed interface SessionAdmissionDecision {
    data object Admitted : SessionAdmissionDecision
    data class Refused(val code: String) : SessionAdmissionDecision
}

fun interface SessionAdmissionFence {
    fun check(sessionId: PurgeSessionId, phase: SessionAdmissionPhase): SessionAdmissionDecision
}

/** Any journal entry, including an unreadable or incomplete intent, permanently blocks re-admission. */
class TombstoneSessionAdmissionFence(private val filesystem: PurgeFilesystem) : SessionAdmissionFence {
    override fun check(sessionId: PurgeSessionId, phase: SessionAdmissionPhase): SessionAdmissionDecision =
        when (val journal = filesystem.readJournal(sessionId)) {
            PurgeJournalRead.Unreadable -> SessionAdmissionDecision.Refused("PURGE.SESSION_TOMBSTONED")
            is PurgeJournalRead.Rejected -> SessionAdmissionDecision.Refused("PURGE.SESSION_TOMBSTONED")
            is PurgeJournalRead.Ready -> if (journal.records.isEmpty()) {
                SessionAdmissionDecision.Admitted
            } else {
                SessionAdmissionDecision.Refused("PURGE.SESSION_TOMBSTONED")
            }
        }
}
