package com.calisthenics.diagnostic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.calisthenics.diagnostic.viewer.AngleTracePoint
import com.calisthenics.diagnostic.viewer.ArmMeasurements
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
    val experiment = state.experiment
    var showDetails by remember { mutableStateOf(false) }
    val setup = experiment.stage == ExperimentStage.IDLE || experiment.stage == ExperimentStage.ERROR ||
        experiment.stage == ExperimentStage.COUNTDOWN ||
        (experiment.stage == ExperimentStage.CALIBRATING && experiment.calibrationEndsNs == null)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pull-up experiment", style = MaterialTheme.typography.labelLarge)
            Text(when (experiment.stage) {
                ExperimentStage.READY -> "Practice accepted — counting is OFF"
                ExperimentStage.COUNTING -> "Counting is ON"
                ExperimentStage.CALIBRATING -> if (setup) "Practice paused — check framing" else "Perform one full practice pull-up"
                ExperimentStage.COUNTDOWN -> "Check camera framing before practice"
                else -> "1. Frame camera → 2. Practice → 3. Confirm & count"
            }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (experiment.stage == ExperimentStage.COUNTING) {
                Text("Candidate cycles: ${experiment.candidateCycles}", style = MaterialTheme.typography.headlineLarge)
                Text("${experiment.side.name.lowercase()} arm · ${experiment.phase.name.lowercase().replace('_',' ')}")
            } else Text("Practice is not counted", style = MaterialTheme.typography.bodyMedium)
            val deadline = when (experiment.stage) {
                ExperimentStage.COUNTDOWN -> experiment.countdownEndsNs?.takeIf { it > state.nowNs }
                ExperimentStage.CALIBRATING -> experiment.calibrationEndsNs
                else -> null
            }
            Text(experiment.message + (deadline?.let {
                " (${ceil((it-state.nowNs).coerceAtLeast(0).toDouble()/1_000_000_000).toInt()}s)"
            } ?: ""))
            if (experiment.stage == ExperimentStage.READY) {
                Text("If that was a full practice pull-up, tap below. Only your NEXT pull-ups will count.")
                Button(onClick = onConfirmRange, enabled = state.cameraActive, modifier = Modifier.fillMaxWidth()) {
                    Text("Confirm full practice & start counting")
                }
            }
            if (setup) {
                Text("Raise your arms first—no pull-up needed yet. Tilt the camera up or move it back until hands, bar and head stay in view, including at the top. Feet are less important for this experiment.")
                Text("The app checks arm joints, not the bar or full-movement framing. Verify those in the preview.",
                    style = MaterialTheme.typography.bodySmall)
                fun framing(arm: ArmMeasurements?, side: String): String =
                    arm?.trackingIssue?.guidance(side) ?: when {
                        arm?.elbowDegrees == null -> "$side: waiting for arm tracking"
                        arm.wristHeightFraction?.let { it > 0 } != true -> "$side: raise your wrist above your shoulder"
                        else -> "$side: arm joints visible now"
                    }
                Text(framing(state.measurements?.left,"Left"), style=MaterialTheme.typography.bodySmall)
                Text(framing(state.measurements?.right,"Right"), style=MaterialTheme.typography.bodySmall)
                if (experiment.stage != ExperimentStage.COUNTDOWN) {
                    if (experiment.stage == ExperimentStage.CALIBRATING) Text("No retry needed. Wait for ‘Tracking recovered’ before a fresh practice, or choose another arm explicitly.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={ onCalibrate(ArmSide.LEFT) },enabled=state.cameraActive) { Text("Use left arm") }
                        OutlinedButton(onClick={ onCalibrate(ArmSide.RIGHT) },enabled=state.cameraActive) { Text("Use right arm") }
                    }
                }
            }
            if (experiment.stage != ExperimentStage.IDLE) OutlinedButton(onClick=onReset) { Text("Reset range and count") }
            Text("Experimental candidate cycles—not strict-rep or form judgments.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ showDetails = !showDetails }) { Text(if(showDetails) "Hide signal details" else "Show signal details") }
            if(showDetails) MovementSignalDetails(state)
        }
    }
}

@Composable
private fun MovementSignalDetails(state: MovementPanelState) {
    fun degrees(value: Double?) = value?.let { "${it.roundToInt()}°" } ?: "—"
    fun height(value: Double?) = value?.let { "${(it*100).roundToInt()}%" } ?: "—"
    val measurements=state.measurements
    Text("Movement signal",style=MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("Left elbow ${degrees(measurements?.left?.elbowDegrees)}",color=Color(0xFF72E8BF))
        Text("Right elbow ${degrees(measurements?.right?.elbowDegrees)}",color=Color(0xFFFFCC80))
    }
    Text("Available arm joints: ${measurements?.availableArmJoints ?: 0}/6")
    Text("Wrist above shoulder · left ${height(measurements?.left?.wristHeightFraction)} · right ${height(measurements?.right?.wristHeightFraction)}")
    Text("Projected angles, not anatomical measurements. Wrist height is % of image height; negative means below shoulder.",style=MaterialTheme.typography.bodySmall)
    if(state.trace.isNotEmpty()) {
        Canvas(Modifier.fillMaxWidth().height(65.dp)) {
            val start=state.nowNs-3_000_000_000L
            fun point(sample: AngleTracePoint,angle: Double)=Offset(
                ((sample.capturedNs-start).toDouble()/3_000_000_000L*size.width).toFloat(),
                ((1.0-angle/180.0)*size.height).toFloat())
            for((first,second) in state.trace.zipWithNext()) {
                val a=first.leftDegrees; val b=second.leftDegrees
                val c=first.rightDegrees; val d=second.rightDegrees
                if(a!=null && b!=null) drawLine(Color(0xFF72E8BF),point(first,a),point(second,b),2.dp.toPx())
                if(c!=null && d!=null) drawLine(Color(0xFFFFCC80),point(first,c),point(second,d),2.dp.toPx())
            }
        }
        Text("Last 3 seconds · 0–180° · gaps are missing observations",style=MaterialTheme.typography.labelSmall)
    }
    state.experiment.range?.let { Text("Observed range ${degrees(it.lowDegrees)}–${degrees(it.highDegrees)} · frozen until reset") }
    Text("Prototype settings: 20/80% range bands, 120 ms endpoint dwell, 20° calibration-spread guard, landmark confidence ≥0.5. Counting supports a multi-sample top reversal; practice requires endpoint holds. Loss discards unfinished practice; three fresh samples spanning 120 ms start a new practice window. These are not validated technique thresholds.",style=MaterialTheme.typography.bodySmall)
}
