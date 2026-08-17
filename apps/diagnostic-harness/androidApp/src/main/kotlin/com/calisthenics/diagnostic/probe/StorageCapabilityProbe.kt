package com.calisthenics.diagnostic.probe

import android.content.Context
import android.os.Build
import android.system.Os
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.export.AndroidStoragePort
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageFile
import com.calisthenics.diagnostic.export.StorageRoots
import com.calisthenics.diagnostic.export.StorageRootsResult
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

/** Outcome of running [StorageCapabilityProbe.run]. */
sealed interface StorageProbeOutcome {
    data class Completed(val result: StorageProbeResult) : StorageProbeOutcome

    /**
     * Root resolution itself was [StorageRootsResult.Unavailable]. The probe fails closed here:
     * it never falls back to an unprotected [Context.filesDir] read, and no primitive is
     * exercised against an alternate root, because that would validate a path the production
     * composition path can never actually use.
     */
    data class Unavailable(val reason: String) : StorageProbeOutcome
}

/**
 * Non-participant evidence that the storage primitives [AndroidStoragePort] relies on actually
 * work on this device's filesystem. Runs against its own scratch subtree under [Context.filesDir]
 * — sibling to, never inside, [AndroidStorageRoots]'s production staging/exports roots — so the
 * probe can never corrupt a real session.
 */
class StorageCapabilityProbe(
    private val context: Context,
    private val probeGitSha: String,
) {
    init {
        require(SHA_PATTERN.matches(probeGitSha)) { "probeGitSha must be a 40-hex commit SHA" }
    }

    fun run(): StorageProbeOutcome {
        val filesDir = when (val rootsResult = AndroidStorageRoots.from(context)) {
            is StorageRootsResult.Ready -> rootsResult.roots.filesDir
            is StorageRootsResult.Unavailable -> return StorageProbeOutcome.Unavailable(rootsResult.reason)
        }
        val runId = UUID.randomUUID().toString()
        val base = "$filesDir/diagnostic-harness-storage-probe/$runId"
        val roots = StorageRoots(filesDir, "$base/staging", "$base/exports")
        Files.createDirectories(Paths.get(roots.exportsRoot))
        val port = AndroidStoragePort(roots)
        val sessionId = Uuid(runId)

        val attempt1 = port.createStagingAttempt(sessionId)
        val appendFlushFsyncSupported = runCatching {
            port.appendLine(attempt1, "storage-capability-probe\n")
            port.flush(attempt1)
            port.fsyncFile(attempt1, StorageFile.FRAMES)
        }.isSuccess

        val directoryFsyncSupported = runCatching { port.fsyncDirectory(attempt1.path) }.isSuccess

        val successDestination = "${roots.exportsRoot}/success"
        val renameWithinFilesdirSupported = runCatching {
            port.renameNoReplace(attempt1, successDestination)
        }.isSuccess

        val populatedDestination = "${roots.exportsRoot}/populated"
        Files.createDirectories(Paths.get(populatedDestination))
        Files.write(Paths.get(populatedDestination, "manifest.json"), "{}".toByteArray(Charsets.UTF_8))
        val attempt2 = port.createStagingAttempt(sessionId)
        val renameOntoPopulatedDestinationRejected = runCatching {
            port.renameNoReplace(attempt2, populatedDestination)
        }.isFailure

        val emptyDestination = "${roots.exportsRoot}/empty"
        Files.createDirectories(Paths.get(emptyDestination))
        val attempt3 = port.createStagingAttempt(sessionId)
        val renameOntoEmptyDestinationRefusedByPrecheck = if (port.destinationExists(emptyDestination)) {
            true
        } else {
            runCatching { port.renameNoReplace(attempt3, emptyDestination) }
            false
        }

        return StorageProbeOutcome.Completed(
            StorageProbeResult(
                deviceModel = Build.MODEL,
                osBuild = Build.DISPLAY,
                filesystemType = filesystemType(filesDir),
                probeGitSha = probeGitSha,
                appendFlushFsyncSupported = appendFlushFsyncSupported,
                directoryFsyncSupported = directoryFsyncSupported,
                renameWithinFilesdirSupported = renameWithinFilesdirSupported,
                renameOntoPopulatedDestinationRejected = renameOntoPopulatedDestinationRejected,
                renameOntoEmptyDestinationRefusedByPrecheck = renameOntoEmptyDestinationRefusedByPrecheck,
            ),
        )
    }

    /** Persists evidence only for [StorageProbeOutcome.Completed]; an [StorageProbeOutcome.Unavailable] outcome has no result to persist. */
    fun runAndPersist(): StorageProbeOutcome {
        val outcome = run()
        val result = (outcome as? StorageProbeOutcome.Completed)?.result ?: return outcome
        val directory = context.cacheDir
        val staging = File(directory, TEMP_RESULT_FILE)
        val output = File(directory, RESULT_FILE)
        var renamed = false
        try {
            FileOutputStream(staging).use { stream ->
                stream.write(result.toCanonicalJson().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Os.rename(staging.absolutePath, output.absolutePath)
            renamed = true
            fsyncDirectory(directory)
        } catch (failure: Throwable) {
            staging.delete()
            if (renamed) output.delete()
            throw failure
        }
        return outcome
    }

    private fun fsyncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    /**
     * The filesystem-type column of the `/proc/mounts` entry whose mount point is the longest
     * prefix of [path]. `android.os.StatFs` exposes only block/space accounting, not filesystem
     * type, so it is not used here. Returns `null` — never a fabricated value — when
     * `/proc/mounts` is unreadable or no matching entry is found.
     */
    private fun filesystemType(path: String): String? = runCatching {
        val resolved = File(path).canonicalPath
        var bestMountPoint: String? = null
        var bestFsType: String? = null
        File("/proc/mounts").forEachLine { line ->
            val columns = line.split(' ')
            if (columns.size < 3) return@forEachLine
            val mountPoint = columns[1]
            val fsType = columns[2]
            val matches = resolved == mountPoint || resolved.startsWith(if (mountPoint == "/") "/" else "$mountPoint/")
            if (matches && (bestMountPoint == null || mountPoint.length > bestMountPoint!!.length)) {
                bestMountPoint = mountPoint
                bestFsType = fsType
            }
        }
        bestFsType
    }.getOrNull()

    companion object {
        const val RESULT_FILE = "storage-capability-probe.json"
        private const val TEMP_RESULT_FILE = ".storage-capability-probe.json.tmp"
        private val SHA_PATTERN = Regex("[0-9a-f]{40}")
    }
}
