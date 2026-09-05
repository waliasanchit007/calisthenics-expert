package com.calisthenics.diagnostic.viewer.experimental

import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements

enum class ArmSide { LEFT, RIGHT }

enum class ExperimentStage { IDLE, COUNTDOWN, CALIBRATING, READY, COUNTING, ERROR }

enum class CyclePhase { WAITING, EXTENDED, FLEXING, FLEXED, EXTENDING, UNCERTAIN }

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
)

class PullUpExperiment {
    val state: PullUpExperimentState
        get() = currentState

    private var currentState = PullUpExperimentState()
    private var lastTimestampNs: Long? = null
    private val rawCalibrationAngles = mutableListOf<Double>()
    private val filteredCalibrationSamples = mutableListOf<FilteredSample>()
    private var segmenter = Segmenter()

    fun startCalibration(side: ArmSide, nowNs: Long) {
        val startNs = nowNs.coerceAtLeast(0L)
        lastTimestampNs = null
        rawCalibrationAngles.clear()
        filteredCalibrationSamples.clear()
        segmenter = Segmenter()
        currentState = PullUpExperimentState(
            stage = ExperimentStage.COUNTDOWN,
            side = side,
            message = "Hold the selected arm in view; calibration starts after the countdown",
            countdownEndsNs = safeAdd(startNs, COUNTDOWN_NS),
        )
    }

    fun confirmCalibration() {
        if (currentState.stage != ExperimentStage.READY) return
        segmenter = Segmenter()
        currentState = currentState.copy(
            stage = ExperimentStage.COUNTING,
            phase = CyclePhase.WAITING,
            candidateCycles = 0,
            message = "Counting",
        )
    }

    fun reset() {
        lastTimestampNs = null
        rawCalibrationAngles.clear()
        filteredCalibrationSamples.clear()
        segmenter = Segmenter()
        currentState = PullUpExperimentState()
    }

    fun onSample(sample: MovementMeasurements, nowNs: Long) {
        val timestamp = sample.capturedNs
        if (timestamp < 0L) {
            invalidateSampleIfNeeded()
            return
        }

        val previousTimestamp = lastTimestampNs
        if (previousTimestamp != null) {
            when {
                timestamp == previousTimestamp -> return
                timestamp < previousTimestamp -> {
                    if (currentState.stage == ExperimentStage.CALIBRATING) {
                        failCalibration("Calibration timestamps went backwards; please retry")
                    } else {
                        invalidateSampleIfNeeded()
                    }
                    return
                }
            }
            if (timestamp - previousTimestamp >= GAP_NS) {
                when (currentState.stage) {
                    ExperimentStage.CALIBRATING -> {
                        failCalibration("Tracking gap during calibration; please retry")
                        return
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
            invalidateSampleIfNeeded()
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

    fun onTrackingGap() {
        when (currentState.stage) {
            ExperimentStage.CALIBRATING -> failCalibration("Tracking was lost during calibration; please retry")
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
        val calibrationEndsNs = currentState.calibrationEndsNs ?: return
        if (timestamp >= calibrationEndsNs) {
            finishCalibration()
        } else {
            addCalibrationSample(timestamp, angle)
        }
    }

    private fun addCalibrationSample(timestamp: Long, angle: Double) {
        if (filteredCalibrationSamples.size >= MAX_CALIBRATION_SAMPLES) {
            failCalibration("Calibration exceeded the bounded sample limit; please retry")
            return
        }

        rawCalibrationAngles += angle
        if (rawCalibrationAngles.size > MEDIAN_WINDOW) rawCalibrationAngles.removeAt(0)
        if (rawCalibrationAngles.size == MEDIAN_WINDOW) {
            filteredCalibrationSamples += FilteredSample(timestamp, median(rawCalibrationAngles))
        }
    }

    private fun finishCalibration() {
        if (filteredCalibrationSamples.size < MIN_CALIBRATION_SAMPLES) {
            failCalibration("Calibration needs at least five usable samples")
            return
        }

        val low = filteredCalibrationSamples.minOf { it.angle }
        val high = filteredCalibrationSamples.maxOf { it.angle }
        val spread = high - low
        if (!spread.isFinite() || spread < MIN_SPREAD_DEGREES) {
            failCalibration("Calibration movement range is too small; retry with a full cycle")
            return
        }

        val range = ObservedRange(currentState.side, low, high)
        val replay = Segmenter()
        filteredCalibrationSamples.forEach { replay.accept(it.angle, it.timestamp, range) }
        if (replay.cycleCount < 1 || replay.phase != CyclePhase.EXTENDED) {
            failCalibration("Calibration needs a complete extended-flexed-extended cycle")
            return
        }

        segmenter = Segmenter()
        currentState = currentState.copy(
            stage = ExperimentStage.READY,
            phase = replay.phase,
            candidateCycles = 0,
            range = range,
            message = "Confirm this was a full practice cycle",
        )
    }

    private fun failCalibration(message: String) {
        rawCalibrationAngles.clear()
        filteredCalibrationSamples.clear()
        segmenter = Segmenter()
        currentState = currentState.copy(
            stage = ExperimentStage.ERROR,
            phase = CyclePhase.WAITING,
            candidateCycles = 0,
            range = null,
            message = message,
            countdownEndsNs = null,
            calibrationEndsNs = null,
        )
    }

    private fun chosenArm(sample: MovementMeasurements): ArmMeasurements =
        if (currentState.side == ArmSide.LEFT) sample.left else sample.right

    private fun usableAngle(arm: ArmMeasurements): Double? {
        val angle = arm.elbowDegrees ?: return null
        val wristHeight = arm.wristHeightFraction ?: return null
        val confidence = arm.minimumConfidence ?: return null
        if (!angle.isFinite() || angle !in 0.0..180.0) return null
        if (!wristHeight.isFinite() || wristHeight <= 0.0) return null
        if (!confidence.isFinite() || confidence !in .5..1.0) return null
        return angle
    }

    private fun isFresh(timestamp: Long, nowNs: Long): Boolean =
        nowNs >= timestamp && nowNs - timestamp < SAMPLE_AGE_NS

    private fun invalidateSampleIfNeeded() {
        when (currentState.stage) {
            ExperimentStage.CALIBRATING -> failCalibration("Calibration received an unusable sample; please retry")
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
            message = if (segmenter.phase == CyclePhase.UNCERTAIN) {
                "Tracking uncertain; hold extended position to rearm"
            } else {
                "Counting"
            },
        )
    }

    private class Segmenter {
        var phase: CyclePhase = CyclePhase.WAITING
            private set
        var cycleCount: Int = 0
            private set
        private var lastTimestampNs: Long? = null
        private var dwellBand: Band? = null
        private var dwellStartNs: Long = 0L
        private var dwellSamples: Int = 0
        fun resetPending() {
            phase = CyclePhase.UNCERTAIN
            clearDwell()
            lastTimestampNs = null
        }

        fun accept(angle: Double, timestampNs: Long, range: ObservedRange) {
            val previousTimestamp = lastTimestampNs
            if (previousTimestamp != null && timestampNs - previousTimestamp >= GAP_NS) resetPending()
            lastTimestampNs = timestampNs

            val band = bandFor(angle, range)
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
                    Band.HIGH -> if (confirmedDwell(Band.HIGH, timestampNs)) phase = CyclePhase.EXTENDED
                    Band.MIDDLE -> clearDwell()
                }
                CyclePhase.FLEXED -> when (band) {
                    Band.MIDDLE -> {
                        clearDwell()
                        phase = CyclePhase.EXTENDING
                    }
                    Band.HIGH -> if (confirmedDwell(Band.HIGH, timestampNs)) {
                        cycleCount++
                        phase = CyclePhase.EXTENDED
                    }
                    Band.LOW -> clearDwell()
                }
                CyclePhase.EXTENDING -> when (band) {
                    Band.HIGH -> if (confirmedDwell(Band.HIGH, timestampNs)) {
                        cycleCount++
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
