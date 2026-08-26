package com.calisthenics.diagnostic.export

enum class StorageLocationId {
    DEVICE_EXPORT,
    DEVICE_STAGING,
    DEVICE_TRANSFER_STAGING,
    STUDY_STORE_SOURCE,
    STUDY_STORE_DERIVED,
    STUDY_STORE_REPORT,
    TOMBSTONE,
}

enum class StorageOwner { ANDROID_APP, HOST_TRANSFER, EXTERNAL_STUDY_STORE }

enum class BackupDisposition { EXCLUDED, NOT_APPLICABLE }

enum class ArtifactClass {
    SESSION_BUNDLE,
    STAGING_ATTEMPT,
    TRANSFER_BUNDLE,
    SOURCE_ARTIFACT,
    DERIVED_ARTIFACT,
    STUDY_REPORT,
    PURGE_TOMBSTONE,
}

data class StorageLocation(
    val id: StorageLocationId,
    val owner: StorageOwner,
    val relativeRoot: String,
    val artifactClasses: Set<ArtifactClass>,
    val publishable: Boolean,
    val devicePurge: Boolean,
    val hostRehearsalPurge: Boolean,
    val cloudBackup: BackupDisposition,
    val deviceTransfer: BackupDisposition,
    val tombstoneEligible: Boolean,
    val postPurgeScan: Boolean,
)

object StorageLocationRegistry {
    const val VERSION: String = "1.0.0"

    val entries: List<StorageLocation> = listOf(
        location(StorageLocationId.DEVICE_EXPORT, StorageOwner.ANDROID_APP, "exports", ArtifactClass.SESSION_BUNDLE, publishable = true, devicePurge = true, tombstoneEligible = true, postPurgeScan = true),
        location(StorageLocationId.DEVICE_STAGING, StorageOwner.ANDROID_APP, "staging", ArtifactClass.STAGING_ATTEMPT, devicePurge = true, tombstoneEligible = true, postPurgeScan = true),
        location(StorageLocationId.DEVICE_TRANSFER_STAGING, StorageOwner.ANDROID_APP, "transfer-staging", ArtifactClass.TRANSFER_BUNDLE, devicePurge = true, tombstoneEligible = true, postPurgeScan = true),
        location(StorageLocationId.STUDY_STORE_SOURCE, StorageOwner.EXTERNAL_STUDY_STORE, "source", ArtifactClass.SOURCE_ARTIFACT, hostRehearsalPurge = true, tombstoneEligible = true, postPurgeScan = true),
        location(StorageLocationId.STUDY_STORE_DERIVED, StorageOwner.EXTERNAL_STUDY_STORE, "derived", ArtifactClass.DERIVED_ARTIFACT, hostRehearsalPurge = true, tombstoneEligible = true, postPurgeScan = true),
        location(StorageLocationId.STUDY_STORE_REPORT, StorageOwner.EXTERNAL_STUDY_STORE, "report", ArtifactClass.STUDY_REPORT, hostRehearsalPurge = true, tombstoneEligible = true, postPurgeScan = true),
        location(StorageLocationId.TOMBSTONE, StorageOwner.ANDROID_APP, "tombstones", ArtifactClass.PURGE_TOMBSTONE),
    )

    private val byId = entries.associateBy(StorageLocation::id)

    fun require(id: StorageLocationId): StorageLocation = checkNotNull(byId[id])

    fun androidChild(id: StorageLocationId): String {
        val location = require(id)
        require(location.owner == StorageOwner.ANDROID_APP) { "$id is not Android-owned" }
        return location.relativeRoot
    }

    private fun location(
        id: StorageLocationId,
        owner: StorageOwner,
        relativeRoot: String,
        artifactClass: ArtifactClass,
        publishable: Boolean = false,
        devicePurge: Boolean = false,
        hostRehearsalPurge: Boolean = false,
        tombstoneEligible: Boolean = false,
        postPurgeScan: Boolean = false,
    ): StorageLocation {
        val disposition = if (owner == StorageOwner.ANDROID_APP) BackupDisposition.EXCLUDED else BackupDisposition.NOT_APPLICABLE
        return StorageLocation(
            id = id,
            owner = owner,
            relativeRoot = relativeRoot,
            artifactClasses = setOf(artifactClass),
            publishable = publishable,
            devicePurge = devicePurge,
            hostRehearsalPurge = hostRehearsalPurge,
            cloudBackup = disposition,
            deviceTransfer = disposition,
            tombstoneEligible = tombstoneEligible,
            postPurgeScan = postPurgeScan,
        )
    }
}
