package com.calisthenics.diagnostic.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageLocationRegistryTest {
    @Test
    fun registryIsTheExactClosedM05bLocationSet() {
        assertEquals("1.0.0", StorageLocationRegistry.VERSION)
        assertEquals(
            listOf(
                StorageLocationId.DEVICE_EXPORT,
                StorageLocationId.DEVICE_STAGING,
                StorageLocationId.DEVICE_TRANSFER_STAGING,
                StorageLocationId.STUDY_STORE_SOURCE,
                StorageLocationId.STUDY_STORE_DERIVED,
                StorageLocationId.STUDY_STORE_REPORT,
                StorageLocationId.TOMBSTONE,
            ),
            StorageLocationRegistry.entries.map(StorageLocation::id),
        )
    }

    @Test
    fun androidLocationsAreSingleSegmentChildrenAndExcludedFromBackupAndTransfer() {
        val androidEntries = StorageLocationRegistry.entries.filter { it.owner == StorageOwner.ANDROID_APP }

        assertEquals(
            setOf(
                StorageLocationId.DEVICE_EXPORT,
                StorageLocationId.DEVICE_STAGING,
                StorageLocationId.DEVICE_TRANSFER_STAGING,
                StorageLocationId.TOMBSTONE,
            ),
            androidEntries.mapTo(mutableSetOf(), StorageLocation::id),
        )
        androidEntries.forEach { entry ->
            assertFalse('/' in entry.relativeRoot)
            assertEquals(BackupDisposition.EXCLUDED, entry.cloudBackup)
            assertEquals(BackupDisposition.EXCLUDED, entry.deviceTransfer)
        }
    }

    @Test
    fun tombstoneCannotParticipateInArtifactPurge() {
        val tombstone = StorageLocationRegistry.require(StorageLocationId.TOMBSTONE)

        assertFalse(tombstone.devicePurge)
        assertFalse(tombstone.hostRehearsalPurge)
        assertFalse(tombstone.tombstoneEligible)
        assertEquals(setOf(ArtifactClass.PURGE_TOMBSTONE), tombstone.artifactClasses)
    }

    @Test
    fun androidChildRejectsNonAndroidLocations() {
        assertEquals("exports", StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_EXPORT))
        assertTrue(
            runCatching {
                StorageLocationRegistry.androidChild(StorageLocationId.STUDY_STORE_SOURCE)
            }.isFailure,
        )
    }

    @Test
    fun storageRootsRequireEveryAndroidRegistryChildBelowOneParent() {
        val roots = StorageRoots(
            filesDir = "/files",
            diagnosticHarnessRoot = "/files/diagnostic-harness",
            stagingRoot = "/files/diagnostic-harness/staging",
            exportsRoot = "/files/diagnostic-harness/exports",
            transferStagingRoot = "/files/diagnostic-harness/transfer-staging",
            tombstoneRoot = "/files/diagnostic-harness/tombstones",
        )

        assertEquals(
            setOf("staging", "exports", "transfer-staging", "tombstones"),
            roots.registeredAndroidRoots.mapTo(mutableSetOf()) { it.substringAfterLast('/') },
        )
        assertTrue(
            runCatching {
                roots.copy(transferStagingRoot = "/files/unregistered")
            }.isFailure,
        )
    }
}
