package com.calisthenics.diagnostic.export

/**
 * [StoragePortTestHarness] backed by [FakeStorage]'s in-memory maps. `precreateDestination`/
 * `destinationFingerprint` mutate/read the most recently created [FakeStorage] instance directly
 * — the only backing store a single-threaded JVM test scenario ever has live at once.
 */
class FakeStoragePortTestHarness : StoragePortTestHarness {
    private var current: FakeStorage? = null

    override fun freshPort(): StoragePort = FakeStorage().also { current = it }

    override fun precreateDestination(destinationPath: String, populated: Boolean) {
        val storage = requireNotNull(current) { "freshPort() must be called before precreateDestination" }
        storage.rootDirectories += destinationPath
        if (populated) {
            storage.committedDirectories[destinationPath] = FakeCommittedDirectory(listOf("untouched"), "untouched")
        }
    }

    override fun destinationFingerprint(destinationPath: String): DestinationFingerprint? {
        val storage = requireNotNull(current) { "freshPort() must be called before destinationFingerprint" }
        return storage.committedDirectories[destinationPath]?.let { DestinationFingerprint(it.frameLines, it.manifest) }
    }

    override fun dispose() {
        current = null
    }
}
