package com.calisthenics.diagnostic.live

enum class LiveFrameClassification {
    CADENCE_SELECTION,
    BACKPRESSURE,
    PREPROCESSING_FAILED,
    SESSION_STOPPING,
    SUBMIT,
}

data class LiveAdmissionPredicates(val cadenceDue: Boolean, val slotAvailable: Boolean)

interface LiveFrameAdmissionPort {
    fun predicates(captureTimestampNs: Long): LiveAdmissionPredicates
    fun reserve(captureTimestampNs: Long): LiveFrameClassification
    fun commitCandidate(preprocessingSucceeded: Boolean): LiveFrameClassification
    fun completeSubmission()
    fun stop()
}

class LiveFrameAdmission(configuredFps: Int) : LiveFrameAdmissionPort {
    init {
        require(configuredFps == 10 || configuredFps == 15 || configuredFps == 30) {
            "configuredFps must be exactly 10, 15, or 30"
        }
    }

    private val minimumIntervalNs = 1_000_000_000L / configuredFps
    private var lastSelectedTimestampNs: Long? = null
    private var submissionInFlight = false
    private var candidateTimestampNs: Long? = null
    private var stopping = false

    override fun predicates(captureTimestampNs: Long): LiveAdmissionPredicates {
        require(captureTimestampNs >= 0L) { "captureTimestampNs must be non-negative" }
        val previous = lastSelectedTimestampNs
        return LiveAdmissionPredicates(
            cadenceDue = previous == null || captureTimestampNs - previous >= minimumIntervalNs,
            slotAvailable = !stopping && !submissionInFlight && candidateTimestampNs == null,
        )
    }

    /** Cheaply reserves the sole prospective inference slot before preprocessing. */
    override fun reserve(captureTimestampNs: Long): LiveFrameClassification {
        require(captureTimestampNs >= 0L) { "captureTimestampNs must be non-negative" }
        if (stopping) return LiveFrameClassification.SESSION_STOPPING
        if (submissionInFlight || candidateTimestampNs != null) return LiveFrameClassification.BACKPRESSURE

        val previous = lastSelectedTimestampNs
        if (previous != null && captureTimestampNs - previous < minimumIntervalNs) {
            return LiveFrameClassification.CADENCE_SELECTION
        }
        candidateTimestampNs = captureTimestampNs
        return LiveFrameClassification.SUBMIT
    }

    /** Commits a reserved candidate after preprocessing, or releases it with one terminal skip. */
    override fun commitCandidate(preprocessingSucceeded: Boolean): LiveFrameClassification {
        val timestamp = checkNotNull(candidateTimestampNs) { "no candidate is reserved" }
        candidateTimestampNs = null
        if (stopping) return LiveFrameClassification.SESSION_STOPPING
        if (!preprocessingSucceeded) return LiveFrameClassification.PREPROCESSING_FAILED
        lastSelectedTimestampNs = timestamp
        submissionInFlight = true
        return LiveFrameClassification.SUBMIT
    }

    fun classify(captureTimestampNs: Long, preprocessingSucceeded: Boolean = true): LiveFrameClassification {
        val reservation = reserve(captureTimestampNs)
        return if (reservation == LiveFrameClassification.SUBMIT) commitCandidate(preprocessingSucceeded) else reservation
    }

    override fun completeSubmission() {
        check(submissionInFlight) { "no submission is in flight" }
        submissionInFlight = false
    }

    override fun stop() {
        stopping = true
    }
}
