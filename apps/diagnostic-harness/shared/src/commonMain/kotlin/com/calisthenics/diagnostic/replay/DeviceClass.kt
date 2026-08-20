package com.calisthenics.diagnostic.replay

/**
 * Derives a device class from Android build markers. AOSP emulator images report a
 * `generic`/`ranchu`/`goldfish` hardware and an `sdk_`/`emu`/`generic` fingerprint; no physical
 * Samsung device does. See `DetectionTierReplayTopologyTest.androidDeviceClass` (Task 4) for the
 * original, file-private derivation this generalizes for a second call site.
 */
fun androidDeviceClassOf(fingerprint: String, hardware: String): String {
    val markers = listOf("generic", "ranchu", "goldfish", "sdk_gphone", "emu64", "/sdk", "vbox")
    val haystack = "$fingerprint $hardware".lowercase()
    return if (markers.any { it in haystack }) "emulator" else "physical_device"
}
