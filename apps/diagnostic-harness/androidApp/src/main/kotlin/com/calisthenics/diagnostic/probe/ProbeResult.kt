package com.calisthenics.diagnostic.probe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProbeResult(
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_build") val osBuild: String,
    @SerialName("camera_id") val cameraId: String,
    @SerialName("timestamp_source") val timestampSource: String,
    @SerialName("source_dataspace_raw") val sourceDataspaceRaw: Int?,
    @SerialName("source_color_matrix") val sourceColorMatrix: String?,
    @SerialName("source_color_range") val sourceColorRange: String?,
    @SerialName("pixel_format") val pixelFormat: String,
    @SerialName("camerax_version") val cameraxVersion: String,
    @SerialName("probe_git_sha") val probeGitSha: String,
    @SerialName("directory_fsync_supported") val directoryFsyncSupported: Boolean,
    @SerialName("atomic_rename_supported") val atomicRenameSupported: Boolean,
) {
    fun isLiveAdmissible(): Boolean =
        timestampSource == "REALTIME" &&
            sourceColorMatrix != null &&
            sourceColorRange != null

    fun isStorageAdmissible(): Boolean = directoryFsyncSupported && atomicRenameSupported

    fun toCanonicalJson(): String = canonicalJson.encodeToString(serializer(), this)

    companion object {
        private val canonicalJson = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }
    }
}
