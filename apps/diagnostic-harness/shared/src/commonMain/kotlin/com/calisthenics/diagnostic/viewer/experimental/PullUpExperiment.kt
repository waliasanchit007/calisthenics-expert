package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.ArmJoint
import com.calisthenics.diagnostic.viewer.MovementMeasurements

enum class ArmSide { LEFT, RIGHT }

enum class ExperimentStage { IDLE, COUNTDOWN, CALIBRATING, READY, COUNTING, ERROR }

enum class CyclePhase { WAITING, EXTENDED, FLEXING, FLEXED, EXTENDING, UNCERTAIN }
enum class CycleOutcome { COUNTED, TOP_NOT_CONFIRMED, TRACKING_INTERRUPTED }

enum class ExperimentInputIssue {
    INVALID_TIMESTAMP, DUPLICATE_TIMESTAMP, BACKWARDS_TIMESTAMP, CAPTURE_GAP, STALE_SAMPLE,
    SHOULDER_UNAVAILABLE, ELBOW_UNAVAILABLE, WRIST_UNAVAILABLE, WRIST_NOT_ABOVE_SHOULDER, CONFIDENCE_UNAVAILABLE, TRACKING_GAP,
}

enum class CalibrationFailure { TIMESTAMP_REVERSED, CAPTURE_GAP, TRACKING_LOST, SAMPLE_LIMIT,
    TOO_FEW_SAMPLES, NARROW_RANGE, INCOMPLETE_CYCLE, INPUT_UNUSABLE }

data class ObservedRange(val side: ArmSide, val lowDegrees: Double, val highDegrees: Double)

data class PullUpExperimentState(
    val stage: ExperimentStage = ExperimentStage.IDLE,
    val side: ArmSide = ArmSide.LEFT,
    val phase: CyclePhase = CyclePhase.WAITING,
    val candidateCycles: Int = 0,
    val range: ObservedRange? = null,
    val message: String = "Choose an arm and calibrate one full practice cycle",
    val countdownEndsNs: Long? = null,
    val calibrationEndsNs: Long? = null,
    val failure: CalibrationFailure? = null,
    val lastCycleOutcome: CycleOutcome? = null,
)

class PullUpExperiment {
    var inputIssue: ExperimentInputIssue? = null
        private set
    val state: PullUpExperimentState
        get() = currentState

    private var currentState = PullUpExperimentState()
    private var lastTimestampNs: Long? = null
    private var reacquisitionStartNs: Long? = null
    private val rawCalibrationAngles = mutableListOf<Double>()
    private val filteredCalibrationSamples = mutableListOf<FilteredSample>()
    private var segmenter = Segmenter()

    fun startCalibration(side: ArmSide, nowNs: Long) {
        inputIssue = null
        val startNs = nowNs.coerceAtLeast(0L)
        lastTimestampNs = null
        reacquisitionStartNs = null
        rawCalibrationAngles.clear()
        filteredCalibrationSamples.clear()
        segmenter = Segmenter()
        currentState = PullUpExperimentState(
            stage = ExperimentStage.COUNTDOWN,
            side = side,
            message = "Frame check: raise the selected arm. Keep hands, bar and head inside the image throughout the movement; practice waits for usable arm tracking",
            countdownEndsNs = safeAdd(startNs, COUNTDOWN_NS),
        )
    }

    fun confirmCalibration() {
        if (currentState.stage != ExperimentStage.READY) return
        segmenter = Segmenter(allowBriefTopReversal = true)
        currentState = currentState.copy(
            stage = ExperimentStage.COUNTING,
            phase = CyclePhase.WAITING,
            candidateCycles = 0,
            message = "Counting is ON. Perform your next pull-up; the practice was not counted",
        )
    }

    fun reset() {
        inputIssue = null
        lastTimestampNs = null
        reacquisitionStartNs = null
        rawCalibrationAngles.clear()
        filteredCalibrationSamples.clear()
        segmenter = Segmenter()
        currentState = PullUpExperimentState()
    }

    fun onSample(sample: MovementMeasurements, nowNs: Long) {
        inputIssue = null
        val timestamp = sample.capturedNs
        if (timestamp < 0L) {
            inputIssue = ExperimentInputIssue.INVALID_TIMESTAMP
            invalidateSampleIfNeeded()
            return
        }

        val previousTimestamp = lastTimestampNs
        if (previousTimestamp != null) {
            when {
                timestamp == previousTimestamp -> { inputIssue = ExperimentInputIssue.DUPLICATE_TIMESTAMP; return }
                timestamp < previousTimestamp -> {
                    onBackwardsTimestamp()
                    return
                }
            }
            if (timestamp - previousTimestamp >= GAP_NS) {
                inputIssue = ExperimentInputIssue.CAPTURE_GAP
                when (currentState.stage) {
                    ExperimentStage.CALIBRATING -> {
                        recoverCalibrationLandmarks()
                    }
                    ExperimentStage.COUNTING -> {
                        segmenter.resetPending()
                        syncCountingState()
                    }
                    ExperimentStage.IDLE,
                    ExperimentStage.COUNTDOWN,
                    ExperimentStage.READY,
                    ExperimentStage.ERROR -> Unit
                }
            }
        }
        lastTimestampNs = timestamp

        if (!isFresh(timestamp, nowNs)) {
            inputIssue = ExperimentInputIssue.STALE_SAMPLE
            invalidateSampleIfNeeded()
            return
        }

        val calibrationEndsNs = currentState.calibrationEndsNs
        if (currentState.stage == ExperimentStage.CALIBRATING &&
            calibrationEndsNs != null && timestamp >= calibrationEndsNs
        ) {
            finishCalibration()
            return
        }

        val arm = chosenArm(sample)
        val angle = usableAngle(arm)
        if (angle == null) {
            val guidance = calibrationInputGuidance(arm)
            if (currentState.stage == ExperimentStage.CALIBRATING) recoverCalibrationLandmarks(guidance)
            else if (currentState.stage == ExperimentStage.COUNTDOWN) currentState = currentState.copy(
                message = "Frame check: $guidance. Do not start the pull-up yet",
            )
            else invalidateSampleIfNeeded()
            return
        }

        when (currentState.stage) {
            ExperimentStage.COUNTDOWN -> acceptCountdownSample(timestamp, angle)
            ExperimentStage.CALIBRATING -> acceptCalibrationSample(timestamp, angle)
            ExperimentStage.COUNTING -> {
                segmenter.accept(angle, timestamp, currentState.range!!)
                syncCountingState()
            }
            ExperimentStage.IDLE,
            ExperimentStage.READY,
            ExperimentStage.ERROR -> Unit
        }
    }

    fun onBackwardsTimestamp() {
        inputIssue = ExperimentInputIssue.BACKWARDS_TIMESTAMP
        if (currentState.stage == ExperimentStage.CALIBRATING) {
            failCalibration("Calibration timestamps went backwards; please retry", CalibrationFailure.TIMESTAMP_REVERSED)
        } else {
            invalidateSampleIfNeeded()
        }
    }

    fun onTrackingGap() {
        inputIssue = ExperimentInputIssue.TRACKING_GAP
        when (currentState.stage) {
            ExperimentStage.CALIBRATING -> recoverCalibrationLandmarks()
            ExperimentStage.COUNTING -> {
                segmenter.resetPending()
                syncCountingState()
            }
            ExperimentStage.IDLE,
            ExperimentStage.COUNTDOWN,
            ExperimentStage.READY,
            ExperimentStage.ERROR -> Unit
        }
    }

    private fun acceptCountdownSample(timestamp: Long, angle: Double) {
        val countdownEndsNs = currentState.countdownEndsNs ?: return
        if (timestamp < countdownEndsNs) return

        currentState = currentState.copy(
            stage = ExperimentStage.CALIBRATING,
            message = "Capture one full practice cycle",
            calibrationEndsNs = safeAdd(timestamp, CALIBRATION_NS),
        )
        addCalibrationSample(timestamp, angle)
    }

    private fun acceptCalibrationSample(timestamp: Long, angle: Double) {
        val calibrationEndsNs = currentState.calibrationEndsNs
        if (calibrationEndsNs == null) {
            // Setup may wait for tracking without retaining an unfinished movement. The existing
            // three-frame median and endpoint dwell provide a bounded fresh-evidence warmup.
            if (reacquisitionStartNs == null) reacquisitionStartNs = timestamp
            rawCalibrationAngles += angle
            if (rawCalibrationAngles.size > MEDIAN_WINDOW) rawCalibrationAngles.removeAt(0)
            if (rawCalibrationAngles.size < MEDIAN_WINDOW || timestamp - reacquisitionStartNs!! < DWELL_NS) return
            rawCalibrationAngles.clear()
            reacquisitionStartNs = null
            currentState = currentState.copy(
                phase = CyclePhase.WAITING,
                message = "Tracking recovered. Start a NEW full practice pull-up, then return to extended",
                calibrationEndsNs = safeAdd(timestamp, CALIBRATION_NS),
            )
            // Warmup is not practice. No motion before this cue contributes to the range.
            addCalibrationSample(timestamp, angle)
            return
        }
        if (timestamp >= calibrationEndsNs) {
            finishCalibration()
        } else {
            addCalibrationSample(timestamp, angle)
        }
    }

    private fun addCalibrationSample(timestamp: Long, angle: Double) {
        if (filteredCalibrationSamples.size >= MAX_CALIBRATION_SAMPLES) {
            failCalibration("Calibration exceeded the bounded sample limit; please retry",CalibrationFailure.SAMPLE_LIMIT)
            return
        }

        rawCalibrationAngles += angle
        if (rawCalibrationAngles.size > MEDIAN_WINDOW) rawCalibrationAngles.removeAt(0)
        if (rawCalibrationAngles.size == MEDIAN_WINDOW) {
            filteredCalibrationSamples += FilteredSample(timestamp, median(rawCalibrationAngles))
        }
    }

    private fun calibrationInputGuidance(arm: ArmMeasurements? = null): String {
        val side = if (currentState.side == ArmSide.LEFT) "Left" else "Right"
        return arm?.trackingIssue?.guidance(side) ?: when (inputIssue) {
            ExperimentInputIssue.WRIST_NOT_ABOVE_SHOULDER -> "Raise your $side wrist above its shoulder and keep the hand in view"
            ExperimentInputIssue.SHOULDER_UNAVAILABLE -> "$side shoulder measurement is unavailable"
            ExperimentInputIssue.WRIST_UNAVAILABLE -> "$side wrist measurement is unavailable"
            ExperimentInputIssue.ELBOW_UNAVAILABLE -> "$side arm measurements are unavailable"
            ExperimentInputIssue.CONFIDENCE_UNAVAILABLE -> "$side arm confidence is insufficient"
            else -> "$side arm tracking was interrupted"
        }
    }

    private fun recoverCalibrationLandmarks(guidance: String = calibrationInputGuidance()) {
        // Only previously accepted samples may establish completion. A later landmark glitch
        // cannot erase an already complete practice, or contribute its rejected angle to it.
        if (finishCalibration(failIfIncomplete = false)) return
        // Never splice a practice cycle across an unusable observation, nor let the median
        // combine pre-gap and post-gap angles. Three new usable samples warm it up again.
        rawCalibrationAngles.clear()
        filteredCalibrationSamples.clear()
        reacquisitionStartNs = null
        currentState = currentState.copy(
            phase = CyclePhase.UNCERTAIN,
            calibrationEndsNs = null,
            countdownEndsNs = null,
            message = "$guidance. Practice paused. A fresh practice window starts automatically when tracking is stable",
        )
    }

    private fun finishCalibration(failIfIncomplete: Boolean = true): Boolean {
        if (filteredCalibrationSamples.size < MIN_CALIBRATION_SAMPLES) {
            if (failIfIncomplete) failCalibration("Calibration needs at least five usable samples",CalibrationFailure.TOO_FEW_SAMPLES)
            return false
        }

        val low = filteredCalibrationSamples.minOf { it.angle }
        val high = filteredCalibrationSamples.maxOf { it.angle }
        val spread = high - low
        if (!spread.isFinite() || spread < MIN_SPREAD_DEGREES) {
            if (failIfIncomplete) failCalibration("Calibration movement range is too small; retry with a full cycle",CalibrationFailure.NARROW_RANGE)
            return false
        }

        val range = ObservedRange(currentState.side, low, high)
        val replay = Segmenter()
        filteredCalibrationSamples.forEach { replay.accept(it.angle, it.timestamp, range) }
        if (replay.cycleCount < 1 || replay.phase != CyclePhase.EXTENDED) {
            if (failIfIncomplete) failCalibration("Calibration needs a complete extended-flexed-extended cycle",CalibrationFailure.INCOMPLETE_CYCLE)
            return false
        }

        segmenter = Segmenter()
        currentState = currentState.copy(
            stage = ExperimentStage.READY,
            phase = replay.phase,
            candidateCycles = 0,
            range = range,
            message = "Practice accepted; counting is still OFF. Confirm your full practice below to start counting",
        )
        return true
    }

    private fun failCalibration(message: String, reason: CalibrationFailure) {
        reacquisitionStartNs = null
        rawCalibrationAngles.clear()
        filteredCalibrationSamples.clear()
        segmenter = Segmenter()
        currentState = currentState.copy(
            stage = ExperimentStage.ERROR,
            phase = CyclePhase.WAITING,
            candidateCycles = 0,
            range = null,
            message = message,
            failure = reason,
            countdownEndsNs = null,
            calibrationEndsNs = null,
        )
    }

    private fun chosenArm(sample: MovementMeasurements): ArmMeasurements =
        if (currentState.side == ArmSide.LEFT) sample.left else sample.right

    private fun usableAngle(arm: ArmMeasurements): Double? {
        fun reject(reason: ExperimentInputIssue): Double? { inputIssue = reason; return null }
        arm.trackingIssue?.let { return reject(when (it.joint) {
            ArmJoint.SHOULDER -> ExperimentInputIssue.SHOULDER_UNAVAILABLE
            ArmJoint.ELBOW -> ExperimentInputIssue.ELBOW_UNAVAILABLE
            ArmJoint.WRIST -> ExperimentInputIssue.WRIST_UNAVAILABLE
        }) }
        val angle = arm.elbowDegrees ?: return reject(ExperimentInputIssue.ELBOW_UNAVAILABLE)
        val wristHeight = arm.wristHeightFraction ?: return reject(ExperimentInputIssue.WRIST_UNAVAILABLE)
        val confidence = arm.minimumConfidence ?: return reject(ExperimentInputIssue.CONFIDENCE_UNAVAILABLE)
        if (!angle.isFinite() || angle !in 0.0..180.0) return reject(ExperimentInputIssue.ELBOW_UNAVAILABLE)
        if (!wristHeight.isFinite() || wristHeight <= 0.0) return reject(ExperimentInputIssue.WRIST_NOT_ABOVE_SHOULDER)
        if (!confidence.isFinite() || confidence !in .5..1.0) return reject(ExperimentInputIssue.CONFIDENCE_UNAVAILABLE)
        return angle
    }

    private fun isFresh(timestamp: Long, nowNs: Long): Boolean =
        nowNs >= timestamp && nowNs - timestamp < SAMPLE_AGE_NS

    private fun invalidateSampleIfNeeded() {
        when (currentState.stage) {
            ExperimentStage.CALIBRATING -> {
                if (inputIssue == ExperimentInputIssue.STALE_SAMPLE) recoverCalibrationLandmarks()
                else failCalibration("Calibration received an unusable sample; please retry",CalibrationFailure.INPUT_UNUSABLE)
            }
            ExperimentStage.COUNTING -> {
                segmenter.resetPending()
                syncCountingState()
            }
            ExperimentStage.IDLE,
            ExperimentStage.COUNTDOWN,
            ExperimentStage.READY,
            ExperimentStage.ERROR -> Unit
        }
    }

    private fun syncCountingState() {
        currentState = currentState.copy(
            phase = segmenter.phase,
            candidateCycles = segmenter.cycleCount,
            lastCycleOutcome = segmenter.lastOutcome,
            message = if (segmenter.phase == CyclePhase.UNCERTAIN) {
                "Tracking uncertain; hold extended position to rearm"
            } else {
                when (segmenter.lastOutcome) {
                    CycleOutcome.COUNTED -> "Last candidate cycle: counted"
                    CycleOutcome.TOP_NOT_CONFIRMED -> "Last attempt not counted: top position was not confirmed"
                    CycleOutcome.TRACKING_INTERRUPTED -> "Last attempt not counted: tracking interrupted the cycle"
                    null -> "Counting"
                }
            },
        )
    }

    private class Segmenter(private val allowBriefTopReversal: Boolean = false) {
        var phase: CyclePhase = CyclePhase.WAITING
            private set
        var cycleCount: Int = 0
            private set
        var lastOutcome: CycleOutcome? = null
            private set
        private var lastTimestampNs: Long? = null
        private var dwellBand: Band? = null
        private var dwellStartNs: Long = 0L
        private var dwellSamples: Int = 0
        private val briefTop = BriefTopReversal()
        fun resetPending() {
            if (phase == CyclePhase.FLEXING || phase == CyclePhase.FLEXED || phase == CyclePhase.EXTENDING) {
                lastOutcome = CycleOutcome.TRACKING_INTERRUPTED
            }
            phase = CyclePhase.UNCERTAIN
            clearDwell()
            briefTop.reset()
            lastTimestampNs = null
        }

        fun accept(angle: Double, timestampNs: Long, range: ObservedRange) {
            val previousTimestamp = lastTimestampNs
            if (previousTimestamp != null && timestampNs - previousTimestamp >= GAP_NS) resetPending()
            lastTimestampNs = timestampNs

            val band = bandFor(angle, range)
            // A short top is not a fabricated hold. Require two decreasing MIDDLE samples,
            // two consecutive LOW samples, then two increasing MIDDLE samples. Only then is
            // descent supported. Counting still requires the existing extended-position dwell.
            // Calibration keeps its original median-filtered, hold-based acceptance.
            if (allowBriefTopReversal && (phase == CyclePhase.EXTENDED || phase == CyclePhase.FLEXING)) {
                if (briefTop.accept(band, angle)) {
                    phase = CyclePhase.EXTENDING
                    clearDwell()
                    return
                }
            } else {
                briefTop.reset()
            }
            when (phase) {
                CyclePhase.WAITING,
                CyclePhase.UNCERTAIN -> {
                    if (band == Band.HIGH && confirmedDwell(Band.HIGH, timestampNs)) phase = CyclePhase.EXTENDED
                    else if (band != Band.HIGH) clearDwell()
                }
                CyclePhase.EXTENDED -> when (band) {
                    Band.MIDDLE -> {
                        clearDwell()
                        phase = CyclePhase.FLEXING
                    }
                    Band.LOW -> if (confirmedDwell(Band.LOW, timestampNs)) phase = CyclePhase.FLEXED
                    Band.HIGH -> clearDwell()
                }
                CyclePhase.FLEXING -> when (band) {
                    Band.LOW -> if (confirmedDwell(Band.LOW, timestampNs)) phase = CyclePhase.FLEXED
                    Band.HIGH -> if (confirmedDwell(Band.HIGH, timestampNs)) {
                        lastOutcome = CycleOutcome.TOP_NOT_CONFIRMED
                        phase = CyclePhase.EXTENDED
                    }
                    Band.MIDDLE -> clearDwell()
                }
                CyclePhase.FLEXED -> when (band) {
                    Band.MIDDLE -> {
                        clearDwell()
                        phase = CyclePhase.EXTENDING
                    }
                    Band.HIGH -> if (confirmedDwell(Band.HIGH, timestampNs)) {
                        cycleCount++
                        lastOutcome = CycleOutcome.COUNTED
                        phase = CyclePhase.EXTENDED
                    }
                    Band.LOW -> clearDwell()
                }
                CyclePhase.EXTENDING -> when (band) {
                    Band.HIGH -> if (confirmedDwell(Band.HIGH, timestampNs)) {
                        cycleCount++
                        lastOutcome = CycleOutcome.COUNTED
                        phase = CyclePhase.EXTENDED
                    }
                    Band.LOW -> {
                        clearDwell()
                        phase = CyclePhase.FLEXED
                    }
                    Band.MIDDLE -> clearDwell()
                }
            }
        }

        private fun confirmedDwell(band: Band, timestampNs: Long): Boolean {
            if (dwellBand != band) {
                dwellBand = band
                dwellStartNs = timestampNs
                dwellSamples = 1
                return false
            }
            dwellSamples++
            return dwellSamples >= 2 && timestampNs - dwellStartNs >= DWELL_NS
        }

        private fun clearDwell() {
            dwellBand = null
            dwellSamples = 0
        }

        private fun bandFor(angle: Double, range: ObservedRange): Band {
            val spread = range.highDegrees - range.lowDegrees
            return when {
                angle <= range.lowDegrees + .2 * spread -> Band.LOW
                angle >= range.lowDegrees + .8 * spread -> Band.HIGH
                else -> Band.MIDDLE
            }
        }
    }

    private class BriefTopReversal {
        private enum class Stage { APPROACH, TOP, DEPARTURE }
        private var stage = Stage.APPROACH
        private var middleSamples = 0
        private var topSamples = 0
        private var previousAngle: Double? = null

        fun reset() {
            stage = Stage.APPROACH
            middleSamples = 0
            topSamples = 0
            previousAngle = null
        }

        fun accept(band: Band, angle: Double): Boolean {
            if (band == Band.HIGH) { reset(); return false }
            when (stage) {
                Stage.APPROACH -> if (band == Band.MIDDLE) {
                    middleSamples = if (previousAngle?.let { angle < it } == true)
                        (middleSamples + 1).coerceAtMost(2) else 1
                    previousAngle = angle
                } else if (middleSamples >= 2) {
                    stage = Stage.TOP
                    topSamples = 1
                } else reset()
                Stage.TOP -> if (band == Band.LOW) {
                    topSamples = (topSamples + 1).coerceAtMost(2)
                } else if (topSamples >= 2) {
                    stage = Stage.DEPARTURE
                    previousAngle = angle
                } else reset()
                Stage.DEPARTURE -> {
                    val supported = band == Band.MIDDLE && previousAngle?.let { angle > it } == true
                    reset()
                    return supported
                }
            }
            return false
        }
    }

    private data class FilteredSample(val timestamp: Long, val angle: Double)

    private enum class Band { LOW, MIDDLE, HIGH }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun safeAdd(value: Long, amount: Long): Long =
        if (value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount

    private companion object {
        const val COUNTDOWN_NS = 5_000_000_000L
        const val CALIBRATION_NS = 10_000_000_000L
        const val SAMPLE_AGE_NS = 250_000_000L
        const val GAP_NS = 250_000_000L
        const val DWELL_NS = 120_000_000L
        const val MAX_CALIBRATION_SAMPLES = 600
        const val MIN_CALIBRATION_SAMPLES = 5
        const val MIN_SPREAD_DEGREES = 20.0
        const val MEDIAN_WINDOW = 3
    }
}
