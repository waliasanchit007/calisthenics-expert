package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.viewer.AngleTracePoint
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.measureMovement
import com.calisthenics.diagnostic.viewer.experimental.ArmSide
import com.calisthenics.diagnostic.viewer.experimental.CalibratedArmCycleExperiment
import com.calisthenics.diagnostic.viewer.experimental.ExperimentalMovement

/** Fresh raw observations only. Never accepts a ViewerOverlay or reads its held pose. */
internal enum class MovementFeedStatus { FRESH, WAITING, NO_POSE, RESULT_TIMEOUT, DUPLICATE, BACKWARDS }

internal class ViewerMovementCoordinator {
    var feedStatus = MovementFeedStatus.WAITING
        private set
    var captureGapNs: Long? = null
        private set
    var experiment = CalibratedArmCycleExperiment()
        private set
    var measurements: MovementMeasurements? = null
        private set
    var trace: List<AngleTracePoint> = emptyList()
        private set
    private var lastSeenNs: Long? = null
    private var sessionActive = false

    /** The coordinator owns this lifecycle flag; callers cannot make an active session stopped. */
    fun beginSession() { sessionActive = true }

    fun endSession() { sessionActive = false }

    fun selectMovement(movement: ExperimentalMovement): Boolean {
        if (sessionActive) return false
        if (experiment.movement == movement) return true
        experiment = CalibratedArmCycleExperiment(movement)
        clear()
        return true
    }

    fun update(observation: ViewerObservation?, nowNs: Long) {
        trace = trace.filter { nowNs - it.capturedNs in 0..3_000_000_000L }.takeLast(90)
        val fresh = observation != null && observation.posePresent &&
            nowNs - observation.capturedNs in 0 until SAMPLE_FRESHNESS_NS
        if (!fresh) {
            feedStatus = MovementFeedStatus.WAITING
            if (measurements != null) {
                trace = (trace + AngleTracePoint(nowNs,null,null)).takeLast(90)
            }
            measurements = null
            // Expiry of the displayed measurement is not proof of a capture gap: the next
            // result can still be in flight. Never feed the old pose or advance the analyzer.
            // With capture gap <250ms and result age <250ms, a valid continuation must arrive
            // before lastSeen+500ms. Neither null UI ticks nor stale results renew this deadline.
            val lastCapture = lastSeenNs
            if (observation?.posePresent == false || lastCapture == null ||
                nowNs < lastCapture || nowNs - lastCapture >= RESULT_DEADLINE_NS) {
                feedStatus = if (observation?.posePresent == false) MovementFeedStatus.NO_POSE
                    else MovementFeedStatus.RESULT_TIMEOUT
                experiment.onTrackingGap()
            }
            return
        }
        checkNotNull(observation)
        val previous = lastSeenNs
        if (previous != null && observation.capturedNs <= previous) {
            feedStatus = MovementFeedStatus.DUPLICATE
            if (observation.capturedNs < previous) {
                feedStatus = MovementFeedStatus.BACKWARDS
                measurements = null
                experiment.onBackwardsTimestamp()
            }
            return
        }
        lastSeenNs = observation.capturedNs
        captureGapNs = previous?.let { observation.capturedNs - it }
        feedStatus = MovementFeedStatus.FRESH
        val measured = measureMovement(observation.landmarks,observation.frame.width,
            observation.frame.height,observation.capturedNs)
        measurements = measured
        trace = (trace + AngleTracePoint(measured.capturedNs,measured.left.elbowDegrees,measured.right.elbowDegrees)).takeLast(90)
        experiment.onSample(measured,nowNs)
    }

    fun startCalibration(side: ArmSide, nowNs: Long) { experiment.startCalibration(side,nowNs) }

    fun clear() {
        feedStatus = MovementFeedStatus.WAITING
        captureGapNs = null
        measurements = null
        trace = emptyList()
        lastSeenNs = null
        experiment.reset()
    }

    private companion object {
        const val SAMPLE_FRESHNESS_NS = 250_000_000L
        // Same capture-gap limit enforced by PullUpExperiment on the next fresh sample.
        const val MAX_CAPTURE_GAP_NS = 250_000_000L
        const val RESULT_DEADLINE_NS = MAX_CAPTURE_GAP_NS + SAMPLE_FRESHNESS_NS
    }
}
