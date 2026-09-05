package com.calisthenics.diagnostic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.calisthenics.diagnostic.viewer.AngleTracePoint
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.experimental.*
import kotlin.math.ceil
import kotlin.math.roundToInt

data class MovementPanelState(
    val measurements: MovementMeasurements? = null,
    val trace: List<AngleTracePoint> = emptyList(),
    val experiment: PullUpExperimentState = PullUpExperimentState(),
    val cameraActive: Boolean = false,
    val nowNs: Long = 0,
)

@Composable
fun MovementMeasurementsPanel(
    state: MovementPanelState,
    onCalibrate: (ArmSide) -> Unit,
    onConfirmRange: () -> Unit,
    onReset: () -> Unit,
) {
    fun degrees(value: Double?) = value?.let { "${it.roundToInt()}°" } ?: "—"
    fun height(value: Double?) = value?.let { "${(it * 100).roundToInt()}%" } ?: "—"
    val measurements = state.measurements
    val experiment = state.experiment
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Movement signal", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Left elbow ${degrees(measurements?.left?.elbowDegrees)}", color = Color(0xFF72E8BF))
                Text("Right elbow ${degrees(measurements?.right?.elbowDegrees)}", color = Color(0xFFFFCC80))
            }
            Text("Available arm joints: ${measurements?.availableArmJoints ?: 0}/6", style = MaterialTheme.typography.bodySmall)
            Text("Wrist above shoulder · left ${height(measurements?.left?.wristHeightFraction)} · right ${height(measurements?.right?.wristHeightFraction)}",
                style = MaterialTheme.typography.bodySmall)
            Text("Projected angles, not anatomical measurements. Wrist height is % of image height; negative means below shoulder.",
                style = MaterialTheme.typography.bodySmall)
            if (state.trace.isNotEmpty()) {
                Canvas(Modifier.fillMaxWidth().height(65.dp)) {
                    val end = state.nowNs
                    val start = end - 3_000_000_000L
                    fun point(sample: AngleTracePoint, angle: Double) = Offset(
                        ((sample.capturedNs - start).toDouble() / 3_000_000_000L * size.width).toFloat(),
                        ((1.0 - angle / 180.0) * size.height).toFloat())
                    for ((first, second) in state.trace.zipWithNext()) {
                        val leftFrom = first.leftDegrees; val leftTo = second.leftDegrees
                        val rightFrom = first.rightDegrees; val rightTo = second.rightDegrees
                        if (leftFrom != null && leftTo != null) drawLine(Color(0xFF72E8BF),
                            point(first,leftFrom),point(second,leftTo),2.dp.toPx())
                        if (rightFrom != null && rightTo != null) drawLine(Color(0xFFFFCC80),
                            point(first,rightFrom),point(second,rightTo),2.dp.toPx())
                    }
                }
                Text("Last 3 seconds · 0–180° · gaps are missing observations", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
            Text("Pull-up experiment", style = MaterialTheme.typography.titleMedium)
            Text("Candidate cycles: ${experiment.candidateCycles}", style = MaterialTheme.typography.headlineSmall)
            Text("${experiment.phase.name.lowercase().replace('_',' ')} · ${experiment.side.name.lowercase()} arm")
            val deadline = when (experiment.stage) {
                ExperimentStage.COUNTDOWN -> experiment.countdownEndsNs
                ExperimentStage.CALIBRATING -> experiment.calibrationEndsNs
                else -> null
            }
            Text(experiment.message + (deadline?.let {
                " (${ceil((it - state.nowNs).coerceAtLeast(0).toDouble() / 1_000_000_000).toInt()}s)"
            } ?: ""))
            experiment.range?.let { range ->
                Text("Observed range ${degrees(range.lowDegrees)}–${degrees(range.highDegrees)} · frozen until reset",
                    style = MaterialTheme.typography.bodySmall)
            }
            when (experiment.stage) {
                ExperimentStage.IDLE, ExperimentStage.ERROR -> {
                    Text("Choose the arm clearest to the camera. After 5 seconds, perform one slow full practice pull-up within 10 seconds, returning to the extended position. Keep that wrist above its shoulder and the phone fixed. Reset if you move the phone. Practice is not counted.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onCalibrate(ArmSide.LEFT) }, enabled = state.cameraActive) { Text("Use left arm") }
                        OutlinedButton(onClick = { onCalibrate(ArmSide.RIGHT) }, enabled = state.cameraActive) { Text("Use right arm") }
                    }
                }
                ExperimentStage.READY -> {
                    Text("Confirm only if this was your full practice cycle. A partial calibration cannot establish full range of motion.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick = onConfirmRange, enabled = state.cameraActive) { Text("Confirm practice range") }
                }
                else -> Unit
            }
            if (experiment.stage != ExperimentStage.IDLE) OutlinedButton(onClick = onReset) { Text("Reset range and count") }
            Text("Experimental: 20/80% range bands, 120 ms endpoint dwell, 20° calibration-spread guard, landmark confidence ≥0.5. These are prototype settings, not validated technique thresholds. No strict-rep or form claims.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
