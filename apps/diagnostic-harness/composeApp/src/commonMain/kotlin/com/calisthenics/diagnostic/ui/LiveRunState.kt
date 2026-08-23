package com.calisthenics.diagnostic.ui

enum class LiveInferenceFps(val value: Int) {
    FPS_10(10),
    FPS_15(15),
    FPS_30(30),
}

sealed interface LiveRunPhase {
    data object NotStarted : LiveRunPhase

    data class Running(
        val configuredInferenceFps: Int,
        val cameraFramesReceived: Long,
        val inferenceFramesSubmitted: Long,
        val inferenceRecordsEmitted: Long,
        val framesSkippedBeforeInference: Long,
        val inferenceFailures: Long,
        val configuredCameraFps: Int = 30,
        val deliveredCallbackCount: Long = cameraFramesReceived,
        val latestInferenceLatencyNs: Long? = null,
        val latestThermalStatus: Int? = null,
        val latestThermalObservedAtNs: Long? = null,
        val thermalUnavailableReason: String? = null,
    ) : LiveRunPhase

    data class Completed(
        val sessionId: String,
        val published: Boolean,
        val inferenceRecordsEmitted: Long,
        val framesSkippedBeforeInference: Long,
        val inferenceFailures: Long,
    ) : LiveRunPhase

    data class Failed(val reason: String) : LiveRunPhase
}
