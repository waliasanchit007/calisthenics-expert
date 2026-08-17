package com.calisthenics.diagnostic.probe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StorageProbeResult(
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_build") val osBuild: String,
    @SerialName("filesystem_type") val filesystemType: String?,
    @SerialName("probe_git_sha") val probeGitSha: String,
    @SerialName("append_flush_fsync_supported") val appendFlushFsyncSupported: Boolean,
    @SerialName("directory_fsync_supported") val directoryFsyncSupported: Boolean,
    @SerialName("rename_within_filesdir_supported") val renameWithinFilesdirSupported: Boolean,
    @SerialName("rename_onto_populated_destination_rejected") val renameOntoPopulatedDestinationRejected: Boolean,
    @SerialName("rename_onto_empty_destination_refused_by_precheck")
    val renameOntoEmptyDestinationRefusedByPrecheck: Boolean,
) {
    /**
     * True only when every primitive above succeeded. This is evidence that the kernel accepted
     * each durability request (append/flush/fsync/fsync-directory/rename), not proof that the
     * underlying storage would survive a real power loss or `SIGKILL` at that instant — crash-time
     * atomicity is not observable from user space and is not claimed here.
     */
    fun isDurabilityAdmissible(): Boolean =
        appendFlushFsyncSupported &&
            directoryFsyncSupported &&
            renameWithinFilesdirSupported &&
            renameOntoPopulatedDestinationRejected &&
            renameOntoEmptyDestinationRefusedByPrecheck

    fun toCanonicalJson(): String = canonicalJson.encodeToString(serializer(), this)

    companion object {
        private val canonicalJson = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

        fun fromCanonicalJson(value: String): StorageProbeResult =
            canonicalJson.decodeFromString(serializer(), value)
    }
}
