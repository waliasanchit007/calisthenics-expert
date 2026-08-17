package com.calisthenics.diagnostic.export

/**
 * Everything a [StoragePortConformanceSuite] probe needs beyond the twelve production
 * [StoragePort] methods, implemented once per backend so the identical suite drives both. Per
 * design spec §6.1 point 2 / §6.2, this is a test-only surface: no method here is added to
 * production [StoragePort].
 */
interface StoragePortTestHarness {
    /** A new, empty port instance scoped to the calling test scenario. */
    fun freshPort(): StoragePort

    /**
     * The one deliberate backdoor: creates filesystem state the twelve production methods cannot
     * produce on their own — a pre-existing directory at [destinationPath], empty or already
     * carrying a manifest/frame line, entirely outside the port under test.
     */
    fun precreateDestination(destinationPath: String, populated: Boolean)

    /**
     * Backend-neutral snapshot/readback of whatever is at [destinationPath] — `null` when nothing
     * exists there. Used to prove a populated destination's contents are byte-for-byte unchanged
     * after a failed rename attempt, on both the fake and the real Android harness.
     */
    fun destinationFingerprint(destinationPath: String): DestinationFingerprint?

    /** Deletes any real filesystem state a real-port harness created for this test. */
    fun dispose()
}

data class DestinationFingerprint(val frameLines: List<String>, val manifest: String)
