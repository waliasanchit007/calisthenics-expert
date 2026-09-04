package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.live.LiveAdmissionPredicates
import com.calisthenics.diagnostic.live.LiveFrameAdmissionPort
import com.calisthenics.diagnostic.live.LiveFrameClassification

/** Test-only Arm 0B admission: cadence is always due while single-flight ownership stays real. */
internal class SingleFlightAttributionAdmission : LiveFrameAdmissionPort {
    private var candidateReserved = false
    private var submissionInFlight = false
    private var stopping = false

    override fun predicates(captureTimestampNs: Long): LiveAdmissionPredicates {
        require(captureTimestampNs >= 0L)
        return LiveAdmissionPredicates(
            cadenceDue = true,
            slotAvailable = !stopping && !candidateReserved && !submissionInFlight,
        )
    }

    override fun reserve(captureTimestampNs: Long): LiveFrameClassification {
        require(captureTimestampNs >= 0L)
        if (stopping) return LiveFrameClassification.SESSION_STOPPING
        if (candidateReserved || submissionInFlight) return LiveFrameClassification.BACKPRESSURE
        candidateReserved = true
        return LiveFrameClassification.SUBMIT
    }

    override fun commitCandidate(preprocessingSucceeded: Boolean): LiveFrameClassification {
        check(candidateReserved) { "no candidate is reserved" }
        candidateReserved = false
        if (stopping) return LiveFrameClassification.SESSION_STOPPING
        if (!preprocessingSucceeded) return LiveFrameClassification.PREPROCESSING_FAILED
        submissionInFlight = true
        return LiveFrameClassification.SUBMIT
    }

    override fun completeSubmission() {
        check(submissionInFlight) { "no submission is in flight" }
        submissionInFlight = false
    }

    override fun stop() {
        stopping = true
    }
}
