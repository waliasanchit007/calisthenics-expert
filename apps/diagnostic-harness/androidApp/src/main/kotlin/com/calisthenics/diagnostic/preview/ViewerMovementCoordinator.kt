package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.viewer.AngleTracePoint
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.measureMovement
import com.calisthenics.diagnostic.viewer.experimental.ArmSide
import com.calisthenics.diagnostic.viewer.experimental.PullUpExperiment

/** Fresh raw observations only. Never accepts a ViewerOverlay or reads its held pose. */
internal class ViewerMovementCoordinator {
    val experiment = PullUpExperiment()
    var measurements: MovementMeasurements? = null
        private set
    var trace: List<AngleTracePoint> = emptyList()
        private set
    private var lastSeenNs: Long? = null

    fun update(observation: ViewerObservation?, nowNs: Long) {
        trace = trace.filter { nowNs - it.capturedNs in 0..3_000_000_000L }.takeLast(90)
        val fresh = observation != null && observation.posePresent &&
            nowNs - observation.capturedNs in 0 until 250_000_000L
        if (!fresh) {
            if (measurements != null) {
                trace = (trace + AngleTracePoint(nowNs,null,null)).takeLast(90)
            }
            measurements = null
            experiment.onTrackingGap()
            return
        }
        checkNotNull(observation)
        val previous = lastSeenNs
        if (previous != null && observation.capturedNs <= previous) {
            if (observation.capturedNs < previous) {
                measurements = null
                experiment.onTrackingGap()
            }
            return
        }
        lastSeenNs = observation.capturedNs
        val measured = measureMovement(observation.landmarks,observation.frame.width,
            observation.frame.height,observation.capturedNs)
        measurements = measured
        trace = (trace + AngleTracePoint(measured.capturedNs,measured.left.elbowDegrees,measured.right.elbowDegrees)).takeLast(90)
        experiment.onSample(measured,nowNs)
    }

    fun startCalibration(side: ArmSide, nowNs: Long) { experiment.startCalibration(side,nowNs) }

    fun clear() {
        measurements = null
        trace = emptyList()
        lastSeenNs = null
        experiment.reset()
    }
}
