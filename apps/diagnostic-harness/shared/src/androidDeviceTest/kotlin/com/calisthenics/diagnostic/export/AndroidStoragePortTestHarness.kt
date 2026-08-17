package com.calisthenics.diagnostic.export

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

/**
 * [StoragePortTestHarness] backed by the real [AndroidStoragePort], rooted at a scratch subtree
 * of the instrumented test's own `context.filesDir`, never the real app roots. Each [freshPort]
 * call allocates its own isolated root triple so concurrent/repeated ports within one test never
 * collide on real filesystem paths — `precreateDestination`/`destinationFingerprint` operate
 * directly on `java.io`/`java.nio.file`, entirely outside the port under test.
 */
class AndroidStoragePortTestHarness(context: Context) : StoragePortTestHarness {
    private val baseDir: Path = Paths.get(context.filesDir.absolutePath, "conformance-test", "harness-${HARNESS_COUNTER.incrementAndGet()}")
    private val portCounter = AtomicInteger(0)

    override fun freshPort(): StoragePort {
        val portRoot = baseDir.resolve("port-${portCounter.incrementAndGet()}")
        val filesDir = portRoot.toString()
        val stagingRoot = "$filesDir/staging"
        val exportsRoot = "$filesDir/exports"
        Files.createDirectories(Paths.get(stagingRoot))
        Files.createDirectories(Paths.get(exportsRoot))
        return AndroidStoragePort(StorageRoots(filesDir, stagingRoot, exportsRoot))
    }

    override fun precreateDestination(destinationPath: String, populated: Boolean) {
        val path = Paths.get(destinationPath)
        Files.createDirectories(path)
        if (populated) {
            Files.write(path.resolve(MANIFEST_FILE_NAME), UNTOUCHED_MANIFEST.toByteArray(StandardCharsets.UTF_8))
            Files.write(path.resolve(FRAMES_FILE_NAME), "$UNTOUCHED_FRAME_LINE\n".toByteArray(StandardCharsets.UTF_8))
        }
    }

    override fun destinationFingerprint(destinationPath: String): DestinationFingerprint? {
        val path = Paths.get(destinationPath)
        if (!Files.exists(path)) return null
        val manifestPath = path.resolve(MANIFEST_FILE_NAME)
        val framesPath = path.resolve(FRAMES_FILE_NAME)
        val manifest = if (Files.exists(manifestPath)) {
            String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8)
        } else {
            ""
        }
        val frameLines = if (Files.exists(framesPath)) {
            Files.readAllLines(framesPath, StandardCharsets.UTF_8).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return DestinationFingerprint(frameLines, manifest)
    }

    override fun dispose() {
        baseDir.toFile().deleteRecursively()
    }

    private companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val FRAMES_FILE_NAME = "frames.ndjson"
        const val UNTOUCHED_MANIFEST = "untouched"
        const val UNTOUCHED_FRAME_LINE = "untouched"
        val HARNESS_COUNTER = AtomicInteger(0)
    }
}
