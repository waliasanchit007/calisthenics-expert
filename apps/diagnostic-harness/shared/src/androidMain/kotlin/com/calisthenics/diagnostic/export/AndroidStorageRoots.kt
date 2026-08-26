package com.calisthenics.diagnostic.export

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/** Typed outcome of resolving [StorageRoots] against a real Android [Context]. */
sealed interface StorageRootsResult {
    data class Ready(val roots: StorageRoots) : StorageRootsResult

    data class Unavailable(val reason: String) : StorageRootsResult
}

/**
 * Derives the app-private storage layout from [Context.filesDir], failing closed with a typed
 * [StorageRootsResult.Unavailable] for the two hazards that cannot be trusted by construction
 * alone: direct-boot credential-encrypted storage being unavailable before first unlock, and
 * staging/exports landing on a filesystem mount different from filesDir.
 *
 * The entire resolution path — including the initial [Context.filesDir] access itself, which can
 * throw before first unlock — runs inside the protected region below. Only the expected
 * Android/filesystem/setup exception families are converted to a sanitized, typed reason; no raw
 * path or exception detail is exposed, and no fatal JVM error ([Error] and its subtypes) is ever
 * caught here.
 */
object AndroidStorageRoots {
    private const val BASE_DIRECTORY_NAME = "diagnostic-harness"

    fun from(context: Context): StorageRootsResult {
        return try {
            val filesDir = context.filesDir.absolutePath
            val filesDirStat = Os.stat(filesDir)
            val base = "$filesDir/$BASE_DIRECTORY_NAME"
            val stagingRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_STAGING)}"
            val exportsRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_EXPORT)}"
            val transferStagingRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_TRANSFER_STAGING)}"
            val tombstoneRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.TOMBSTONE)}"
            listOf(stagingRoot, exportsRoot, transferStagingRoot, tombstoneRoot).forEach {
                Files.createDirectories(Paths.get(it))
            }
            val stagingMountStat = Os.stat(parentOf(stagingRoot))
            val exportsMountStat = Os.stat(parentOf(exportsRoot))
            if (stagingMountStat.st_dev != filesDirStat.st_dev || exportsMountStat.st_dev != filesDirStat.st_dev) {
                StorageRootsResult.Unavailable("staging_and_exports_not_same_mount")
            } else {
                StorageRootsResult.Ready(
                    StorageRoots(
                        filesDir = filesDir,
                        stagingRoot = stagingRoot,
                        exportsRoot = exportsRoot,
                        diagnosticHarnessRoot = base,
                        transferStagingRoot = transferStagingRoot,
                        tombstoneRoot = tombstoneRoot,
                    ),
                )
            }
        } catch (error: ErrnoException) {
            StorageRootsResult.Unavailable("direct_boot_credential_encrypted_storage_unavailable")
        } catch (error: IOException) {
            StorageRootsResult.Unavailable("storage_root_resolution_failed")
        } catch (error: IllegalStateException) {
            StorageRootsResult.Unavailable("storage_root_resolution_failed")
        } catch (error: IllegalArgumentException) {
            StorageRootsResult.Unavailable("storage_root_resolution_failed")
        } catch (error: SecurityException) {
            StorageRootsResult.Unavailable("storage_root_resolution_failed")
        }
    }

    /** Creates an isolated capability-probe layout below the registered staging root. */
    fun forProbe(roots: StorageRoots, runId: String): StorageRoots {
        require(runId.matches(Regex("^[0-9a-f-]{36}$"))) { "runId must be a lowercase UUID" }
        val base = "${roots.stagingRoot}/storage-probe/$runId"
        return StorageRoots(
            filesDir = roots.filesDir,
            stagingRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_STAGING)}",
            exportsRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_EXPORT)}",
            diagnosticHarnessRoot = base,
            transferStagingRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.DEVICE_TRANSFER_STAGING)}",
            tombstoneRoot = "$base/${StorageLocationRegistry.androidChild(StorageLocationId.TOMBSTONE)}",
        )
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/')
}
