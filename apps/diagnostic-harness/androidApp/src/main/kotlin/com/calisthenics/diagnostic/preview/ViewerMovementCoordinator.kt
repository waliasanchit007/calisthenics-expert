package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.viewer.AngleTracePoint
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.measureMovement
import com.calisthenics.diagnostic.viewer.experimental.ArmSide
import com.calisthenics.diagnostic.viewer.experimental.CalibratedArmCycleExperiment
import com.calisthenics.diagnostic.viewer.experimental.ExperimentalMovement
import com.calisthenics.diagnostic.viewer.experimental.MuscleUpVariant
import com.calisthenics.diagnostic.viewer.experimental.OfflineMovement
import com.calisthenics.diagnostic.viewer.experimental.OfflineMovementEngine
import com.calisthenics.diagnostic.viewer.experimental.OfflineMovementUpdate
import com.calisthenics.diagnostic.viewer.experimental.offlineMovementEngine

/** Fresh raw observations only. Never accepts a ViewerOverlay or reads its held pose. */
internal enum class MovementFeedStatus { FRESH, WAITING, NO_POSE, RESULT_TIMEOUT, DUPLICATE, BACKWARDS }

/**
 * Which analyser the live stream feeds.
 *
 * [Calibrated] is the pull-up/push-up engine, which fits a practice range to THIS athlete before it
 * counts. [Offline] is one of the exploratory engines, which instead carry fixed thresholds frozen
 * from someone else's recording. The two are kept as distinct selections rather than one list
 * because that difference is the whole risk: an offline engine has never been shown to transfer to
 * a new camera, athlete or frame rate, and its output here is an unvalidated port.
 */
internal sealed interface ViewerMovementSelection {
    data class Calibrated(val movement: ExperimentalMovement) : ViewerMovementSelection
    data class Offline(
        val movement: OfflineMovement,
        val side: ArmSide,
        val muscleUpVariant: MuscleUpVariant = MuscleUpVariant.BAR_KIPPING,
    ) : ViewerMovementSelection
}

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
    var selection: ViewerMovementSelection = ViewerMovementSelection.Calibrated(ExperimentalMovement.PULL_UP)
        private set
    /** Last result from the selected offline engine, or null when a calibrated movement is selected. */
    var offlineUpdate: OfflineMovementUpdate? = null
        private set
    /**
     * Advice to show the athlete when the selected engine keeps refusing frames, or null when it is
     * measuring. Derived from the engine's OWN rejection reasons rather than a second copy of each
     * engine's required-joint list, so it cannot drift from what the engine actually demands.
     *
     * It exists because 54% of captures in the 2026-09-15 pull-up-bar session were rejected as
     * INVALID_LANDMARK -- a joint outside the frame -- and the athlete had no way to know.
     */
    var framingAdvice: String? = null
        private set
    private val recentRejections = ArrayDeque<String?>()
    private var offlineEngine: OfflineMovementEngine? = null
    private var lastSeenNs: Long? = null
    private var sessionActive = false

    /**
     * ADR-0007 decision 2, made visible. [analysisLate]: results admitted to analysis at an age the
     * display would already call stale (250 ms or more). [analysisExpired]: pose results withheld at
     * or past the result deadline. Both are expected to be 0 in SERIAL mode.
     */
    private var lastNoPoseNs: Long? = null
    var analysisLate = 0
        private set
    var analysisExpired = 0
        private set

    /** The coordinator owns this lifecycle flag; callers cannot make an active session stopped. */
    fun beginSession() { sessionActive = true }

    fun endSession() { sessionActive = false }

    fun selectMovement(movement: ExperimentalMovement): Boolean {
        if (sessionActive) return false
        val already = selection as? ViewerMovementSelection.Calibrated
        selection = ViewerMovementSelection.Calibrated(movement)
        offlineEngine = null
        if (already?.movement == movement && experiment.movement == movement) return true
        experiment = CalibratedArmCycleExperiment(movement)
        clear()
        return true
    }

    /**
     * Select one of the offline engines. Same lifecycle rule as [selectMovement]: refused while a
     * session is active, so the analyser cannot change under a running capture.
     *
     * The calibrated experiment is left constructed but is NOT fed while an offline movement is
     * selected, so its count cannot drift in the background and be mistaken for this movement's.
     */
    fun selectOfflineMovement(
        movement: OfflineMovement,
        side: ArmSide = com.calisthenics.diagnostic.viewer.experimental.defaultSideFor(movement),
        muscleUpVariant: MuscleUpVariant = MuscleUpVariant.BAR_KIPPING,
    ): Boolean {
        if (sessionActive) return false
        selection = ViewerMovementSelection.Offline(movement, side, muscleUpVariant)
        offlineEngine = offlineMovementEngine(movement, side, muscleUpVariant)
        clear()
        return true
    }

    /**
     * The display's tick between results (ADR-0007). It never admits an observation — only
     * [update], fed from the session's completion stream, does — so the display path can no longer
     * hand analysis a newer frame ahead of an older one still in the stream, which the analyser
     * would have to treat as a backwards timestamp. It keeps the two things that path used to do:
     * expire the shown measurement at the display's 250 ms, and run the result deadline.
     */
    fun tick(nowNs: Long) {
        val lastNoPose = lastNoPoseNs
        if (lastNoPose != null && nowNs - lastNoPose in 0 until SAMPLE_FRESHNESS_NS) {
            // Still the latest result: keep reporting it, exactly as re-reading it used to.
            feedStatus = MovementFeedStatus.NO_POSE
            experiment.onTrackingGap()
            return
        }
        val shown = measurements
        if (shown != null && nowNs - shown.capturedNs in 0 until SAMPLE_FRESHNESS_NS) return
        update(null, nowNs)
    }

    fun update(observation: ViewerObservation?, nowNs: Long) {
        trace = trace.filter { nowNs - it.capturedNs in 0..3_000_000_000L }.takeLast(90)
        if (observation != null) lastNoPoseNs = if (observation.posePresent) null else observation.capturedNs
        // ADR-0007: analysis admits a result up to the result deadline, not the display's 250 ms.
        // A result younger than the deadline is late, not lost; the display keeps its own cutoff.
        val ageNs = observation?.let { nowNs - it.capturedNs }
        val fresh = observation != null && observation.posePresent &&
            ageNs!! in 0 until ANALYSIS_ADMISSION_NS
        if (observation != null && observation.posePresent && ageNs!! >= ANALYSIS_ADMISSION_NS) analysisExpired++
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
            // Since ADR-0007 a result aged 250-500 ms is also admitted; one that arrives after this
            // deadline has fired is fed as a fresh start after the gap, never stitched across it.
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
        if (ageNs!! >= SAMPLE_FRESHNESS_NS) analysisLate++
        captureGapNs = previous?.let { observation.capturedNs - it }
        feedStatus = MovementFeedStatus.FRESH
        val measured = measureMovement(observation.landmarks,observation.frame.width,
            observation.frame.height,observation.capturedNs)
        measurements = measured
        trace = (trace + AngleTracePoint(measured.capturedNs,measured.left.elbowDegrees,measured.right.elbowDegrees)).takeLast(90)
        val offline = offlineEngine
        if (offline == null) {
            experiment.onSample(measured,nowNs)
        } else {
            // The offline engines take raw landmarks, not the arm-narrowed measurement, and they
            // enforce their own capture-gap and timestamp rules internally — which is why no gap or
            // backwards-timestamp notification is forwarded to them. Feeding one would double-count
            // a rejection the engine already makes for itself.
            offlineUpdate = offline.onLandmarks(
                observation.capturedNs, observation.frame.width, observation.frame.height,
                observation.landmarks,
            )
            noteRejection(offlineUpdate?.rejection)
        }
    }

    /**
     * A single bad frame means nothing -- tracking drops one routinely. A sustained majority of
     * refusals over the recent window is a framing or lighting problem the athlete can fix.
     */
    private fun noteRejection(reason: String?) {
        recentRejections.addLast(reason)
        while (recentRejections.size > FRAMING_WINDOW) recentRejections.removeFirst()
        if (recentRejections.size < FRAMING_WINDOW) { framingAdvice = null; return }
        val outOfFrame = recentRejections.count { it == "INVALID_LANDMARK" }
        val unconfident = recentRejections.count { it == "LOW_CONFIDENCE" || it == "MISSING_LANDMARK" }
        val refused = recentRejections.count { it != null }
        framingAdvice = when {
            refused * 2 <= recentRejections.size -> null
            outOfFrame >= unconfident -> "Some joints are outside the frame. Move the phone back or turn it upright."
            else -> "Body not clearly visible. Improve the lighting or clear the background."
        }
    }

    fun startCalibration(side: ArmSide, nowNs: Long) { experiment.startCalibration(side,nowNs) }

    fun clear() {
        feedStatus = MovementFeedStatus.WAITING
        captureGapNs = null
        measurements = null
        trace = emptyList()
        lastSeenNs = null
        offlineUpdate = null
        recentRejections.clear()
        framingAdvice = null
        analysisLate = 0
        analysisExpired = 0
        lastNoPoseNs = null
        experiment.reset()
        (selection as? ViewerMovementSelection.Offline)?.let {
            offlineEngine = offlineMovementEngine(it.movement, it.side, it.muscleUpVariant)
        }
    }

    private companion object {
        /** ~2 s at the ~10 Hz the device pose pipeline currently delivers. */
        const val FRAMING_WINDOW = 20
        const val SAMPLE_FRESHNESS_NS = 250_000_000L
        // Same capture-gap limit enforced by PullUpExperiment on the next fresh sample.
        const val MAX_CAPTURE_GAP_NS = 250_000_000L
        const val RESULT_DEADLINE_NS = MAX_CAPTURE_GAP_NS + SAMPLE_FRESHNESS_NS
        /** ADR-0007: how old a result may be and still reach analysis. The display keeps 250 ms. */
        const val ANALYSIS_ADMISSION_NS = RESULT_DEADLINE_NS
    }
}
