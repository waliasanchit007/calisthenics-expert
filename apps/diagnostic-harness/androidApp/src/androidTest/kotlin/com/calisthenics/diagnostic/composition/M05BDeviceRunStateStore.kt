package com.calisthenics.diagnostic.composition

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.calisthenics.diagnostic.export.StorageRoots
import com.calisthenics.diagnostic.hash.Sha256Digest
import com.calisthenics.diagnostic.study.M05BArtifactCodec
import com.calisthenics.diagnostic.study.M05BBoundAttemptEntry
import com.calisthenics.diagnostic.study.M05BFrozenRunInputs
import com.calisthenics.diagnostic.study.M05BPublishedAttempt
import com.calisthenics.diagnostic.study.M05BPublishedAttemptRecovery
import com.calisthenics.diagnostic.study.M05BAttemptOutcome
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Device-owned durable control state for the exact Task 7 inputs and Task 8 attempt chain. */
internal class M05BDeviceRunStateStore(
    private val context: Context,
    private val roots: StorageRoots,
) {
    fun loadFrozenInputs(): M05BFrozenRunInputs =
        M05BFrozenRunInputs.decode(frozenInputsFile().readBytes())

    /**
     * Recovers every published M0.5B session before a new admission is derived.
     *
     * Publication of the export is authoritative. If Wi-Fi or the test process disappeared after
     * publication, the exact run-admission bytes still deterministically imply the completed
     * attempt entry, so the same cadence cannot be issued again.
     */
    fun recoverPublishedAttempts(): List<M05BBoundAttemptEntry> {
        val inputs = loadFrozenInputs()
        var history = readAttemptHistory()
        val published = File(roots.exportsRoot).listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull(::publishedAttemptFrom)
            .sortedBy(M05BPublishedAttempt::attemptSequence)
            .toList()
        for (attempt in published) {
            val recordedOutcome = history.singleOrNull {
                it.typed.admissionSha256 == attempt.admissionSha256
            }?.typed?.outcome
            val recovered = M05BPublishedAttemptRecovery.recover(
                inputs.order.typed,
                history,
                attempt.copy(outcome = recordedOutcome ?: M05BAttemptOutcome.BLOCKED),
            )
            if (recovered.size > history.size) persistAttemptEntry(recovered.last())
            history = recovered
        }
        return history
    }

    fun recordPublishedAttempt(published: M05BPublishedAttempt): List<M05BBoundAttemptEntry> {
        val inputs = loadFrozenInputs()
        val history = readAttemptHistory()
        val recovered = M05BPublishedAttemptRecovery.recover(inputs.order.typed, history, published)
        if (recovered.size > history.size) persistAttemptEntry(recovered.last())
        return recovered
    }

    private fun publishedAttemptFrom(export: File): M05BPublishedAttempt? {
        val admission = File(export, "run-admission.json")
        if (!admission.isFile) return null
        val exactBytes = admission.readBytes()
        val value = Json.parseToJsonElement(exactBytes.decodeToString()).jsonObject
        if (value["record_kind"]?.jsonPrimitive?.content != "m05b_run_admission") return null
        require(value.getValue("admission_status").jsonPrimitive.content == "admitted")
        require(value.getValue("session_id").jsonPrimitive.content == export.name)
        return M05BPublishedAttempt(
            sessionId = export.name,
            attemptSequence = value.getValue("attempt_sequence").jsonPrimitive.int,
            cadenceFps = value.getValue("cadence_fps").jsonPrimitive.int,
            admissionSha256 = Sha256Digest.hex(exactBytes),
            outcome = M05BAttemptOutcome.BLOCKED,
        )
    }

    private fun readAttemptHistory(): List<M05BBoundAttemptEntry> {
        val directory = attemptDirectory()
        if (!directory.exists()) return emptyList()
        return directory.listFiles { file -> file.isFile && file.name.matches(ATTEMPT_FILE) }
            .orEmpty()
            .sortedBy(File::getName)
            .map { M05BArtifactCodec.decodeAttemptEntry(it.readBytes()) }
    }

    private fun persistAttemptEntry(entry: M05BBoundAttemptEntry) {
        val directory = attemptDirectory()
        if (!directory.exists()) {
            check(directory.mkdirs()) { "attempt-chain directory could not be created" }
            fsyncDirectory(directory.parentFile!!)
        }
        val destination = File(directory, "attempt-${entry.typed.attemptSequence.toString().padStart(4, '0')}.json")
        if (destination.exists()) {
            require(destination.readBytes().contentEquals(entry.exactBytes)) {
                "existing attempt entry conflicts with deterministic recovery"
            }
            return
        }
        val temporary = File(directory, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(entry.exactBytes)
            output.flush()
            output.fd.sync()
        }
        Os.rename(temporary.path, destination.path)
        fsyncDirectory(directory)
    }

    private fun frozenInputsFile(): File = File(context.filesDir, "m05b-frozen-run-inputs.json")

    private fun attemptDirectory(): File = File(context.filesDir, "m05b-run-state/attempt-chain")

    private fun fsyncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private companion object {
        val ATTEMPT_FILE = Regex("^attempt-[0-9]{4}\\.json$")
    }
}
