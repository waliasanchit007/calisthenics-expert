package com.calisthenics.diagnostic.ui

/**
 * The replay fixtures the common UI may offer for the real replay path (design section 7 /
 * Task 5 brief). The 5x1 contract-tier fixture stays internal conformance input — it is never
 * offered here — so this only ever lists the detection-tier fixture the real MediaPipe backend
 * can fire on.
 */
enum class ReplayFixtureOption(val id: String, val label: String) {
    DETECTION_TIER("fixture_detection", "Detection tier (256×256, 6 frames)"),
}

/** What the completed run's export looked like, for the completion/export summary state. */
data class ReplayRunSummary(
    val sessionId: String,
    val published: Boolean,
    val inferenceRecordsEmitted: Long,
    val poseDetectedRecords: Long,
    val inferenceFailures: Long,
)

/** The design section 12 "active session monitor" / "completion summary" states for one replay run. */
sealed interface ReplayRunPhase {
    data object NotStarted : ReplayRunPhase

    data class Running(
        val framesSubmitted: Long,
        val framesEmitted: Long,
        val poseDetectedRecords: Long,
    ) : ReplayRunPhase

    data class Completed(val summary: ReplayRunSummary) : ReplayRunPhase

    data class Failed(val reason: String) : ReplayRunPhase
}
