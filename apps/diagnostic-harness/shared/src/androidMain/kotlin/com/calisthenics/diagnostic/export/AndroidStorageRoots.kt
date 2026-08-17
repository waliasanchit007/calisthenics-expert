package com.calisthenics.diagnostic.export

import android.content.Context
import android.system.ErrnoException
import android.system.Os
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
 */
object AndroidStorageRoots {
    private const val BASE_DIRECTORY_NAME = "diagnostic-harness"

    fun from(context: Context): StorageRootsResult {
        val filesDir = context.filesDir.absolutePath
        return try {
            val filesDirStat = Os.stat(filesDir)
            val base = "$filesDir/$BASE_DIRECTORY_NAME"
            val stagingRoot = "$base/staging"
            val exportsRoot = "$base/exports"
            Files.createDirectories(Paths.get(stagingRoot))
            Files.createDirectories(Paths.get(exportsRoot))
            val stagingMountStat = Os.stat(parentOf(stagingRoot))
            val exportsMountStat = Os.stat(parentOf(exportsRoot))
            if (stagingMountStat.st_dev != filesDirStat.st_dev || exportsMountStat.st_dev != filesDirStat.st_dev) {
                StorageRootsResult.Unavailable("staging_and_exports_not_same_mount")
            } else {
                StorageRootsResult.Ready(StorageRoots(filesDir, stagingRoot, exportsRoot))
            }
        } catch (error: ErrnoException) {
            StorageRootsResult.Unavailable("direct_boot_credential_encrypted_storage_unavailable")
        }
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/')
}
