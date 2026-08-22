package com.calisthenics.diagnostic.probe

import com.calisthenics.diagnostic.replay.CameraCapabilityObservation
import com.calisthenics.diagnostic.replay.cameraObservationBlockingReasons
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
    @SerialName("underlying_image_available") val underlyingImageAvailable: Boolean,
    @SerialName("pixel_format") val pixelFormat: String,
    @SerialName("camerax_version") val cameraxVersion: String,
    @SerialName("probe_git_sha") val probeGitSha: String,
    @SerialName("directory_fsync_supported") val directoryFsyncSupported: Boolean,
    @SerialName("atomic_rename_supported") val atomicRenameSupported: Boolean,
) {
    /**
     * The observation this result describes, in the shared evidence vocabulary.
     *
     * `sourceColorMatrix` is this type's original name for what design section 8 and the evidence
     * record call the decoded colour *standard*; the mapping happens here, once, rather than at
     * every call site that has to bridge the two names.
     */
    fun toCameraObservation(): CameraCapabilityObservation = CameraCapabilityObservation(
        cameraId = cameraId,
        timestampSource = timestampSource,
        sourceDataspaceRaw = sourceDataspaceRaw,
        sourceColorStandard = sourceColorMatrix,
        sourceColorRange = sourceColorRange,
        underlyingImageAvailable = underlyingImageAvailable,
        pixelFormat = pixelFormat,
    )

    /**
     * Delegates to [cameraObservationBlockingReasons] in `shared/commonMain` rather than restating
     * the rule. This method used to hold its own copy, which already omitted both the
     * underlying-image bridge and the `DATASPACE_UNKNOWN` sentinel — exactly the divergence a
     * second copy produces.
     */
    fun isLiveAdmissible(): Boolean = cameraObservationBlockingReasons(toCameraObservation()).isEmpty()

    fun isStorageAdmissible(): Boolean = directoryFsyncSupported && atomicRenameSupported

    fun toCanonicalJson(): String = canonicalJson.encodeToString(serializer(), this)

    companion object {
        private val canonicalJson = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

        fun fromCanonicalJson(value: String): ProbeResult =
            canonicalJson.decodeFromString(serializer(), value)
    }
}
