package com.calisthenics.diagnostic.live

enum class LiveFrameClassification {
    CADENCE_SELECTION,
    BACKPRESSURE,
    PREPROCESSING_FAILED,
    SESSION_STOPPING,
    SUBMIT,
}

class LiveFrameAdmission(configuredFps: Int) {
    init {
        require(configuredFps == 10 || configuredFps == 15 || configuredFps == 30) {
            "configuredFps must be exactly 10, 15, or 30"
        }
    }

    private val minimumIntervalNs = 1_000_000_000L / configuredFps
    private var lastSelectedTimestampNs: Long? = null
    private var submissionInFlight = false
    private var stopping = false

    fun classify(captureTimestampNs: Long, preprocessingSucceeded: Boolean = true): LiveFrameClassification {
        require(captureTimestampNs >= 0L) { "captureTimestampNs must be non-negative" }
        if (stopping) return LiveFrameClassification.SESSION_STOPPING
        if (submissionInFlight) return LiveFrameClassification.BACKPRESSURE

        val previous = lastSelectedTimestampNs
        if (previous != null && captureTimestampNs - previous < minimumIntervalNs) {
            return LiveFrameClassification.CADENCE_SELECTION
        }
        if (!preprocessingSucceeded) return LiveFrameClassification.PREPROCESSING_FAILED

        lastSelectedTimestampNs = captureTimestampNs
        submissionInFlight = true
        return LiveFrameClassification.SUBMIT
    }

    fun completeSubmission() {
        check(submissionInFlight) { "no submission is in flight" }
        submissionInFlight = false
    }

    fun stop() {
        stopping = true
    }
}
