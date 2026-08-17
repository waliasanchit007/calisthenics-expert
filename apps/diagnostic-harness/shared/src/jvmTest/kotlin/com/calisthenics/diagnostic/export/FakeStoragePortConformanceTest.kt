package com.calisthenics.diagnostic.export

/** Fast, CI-covered concrete instantiation of [StoragePortConformanceSuite] against [FakeStorage]. */
class FakeStoragePortConformanceTest : StoragePortConformanceSuite() {
    private val testHarness = FakeStoragePortTestHarness()

    override fun harness(): StoragePortTestHarness = testHarness
}
