package com.calisthenics.diagnostic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calisthenics.diagnostic.viewer.AngleTracePoint
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.experimental.*
import kotlinx.coroutines.delay
import kotlin.math.ceil

data class MovementPanelState(
    val measurements: MovementMeasurements? = null,
    val trace: List<AngleTracePoint> = emptyList(),
    val experiment: PullUpExperimentState = PullUpExperimentState(),
    val cameraActive: Boolean = false,
    val nowNs: Long = 0,
    /** Set when an exploratory engine is selected; [experiment] is then not being fed at all. */
    val offlineMovement: OfflineMovement? = null,
    val offline: OfflineMovementUpdate? = null,
)

/** Operator guidance only; exact diagnostics continue independently in the recorder. */
@Composable
fun MovementMeasurementsPanel(
    state: MovementPanelState,
    onCalibrate: (ArmSide) -> Unit,
    onConfirmRange: () -> Unit,
    onReset: () -> Unit,
) {
    // An exploratory engine has no calibration, no practice rep and no arm choice, so none of the
    // flow below applies to it. Showing that flow for a knee raise told the athlete to "practice one
    // pull-up" while a completely different engine was being fed.
    state.offlineMovement?.let { movement ->
        OfflineMovementPanel(movement, state.offline, onReset)
        return
    }
    val e = state.experiment
    val side = if (e.side == ArmSide.LEFT) "left" else "right"
    val isPushUp = e.movement == ExperimentalMovement.PUSH_UP
    val needsFraming = (e.stage == ExperimentStage.CALIBRATING && e.calibrationEndsNs == null) ||
        (e.stage == ExperimentStage.COUNTING && e.phase == CyclePhase.UNCERTAIN)
    // Presentation delay only. No measurement, count, recording or consent is delayed.
    var paused by remember(e.stage) { mutableStateOf(false) }
    LaunchedEffect(e.stage, needsFraming) { delay(600); paused = needsFraming }
    var chooseArm by remember(e.stage) { mutableStateOf(false) }
    val starting = e.stage == ExperimentStage.IDLE || e.stage == ExperimentStage.ERROR
    val seconds = e.countdownEndsNs?.let {
        ceil((it-state.nowNs).coerceAtLeast(0).toDouble()/1_000_000_000).toInt()
    } ?: 0
    val title = when(e.stage) {
        ExperimentStage.IDLE -> "Let's set up"
        ExperimentStage.ERROR -> "Let's try practice again"
        ExperimentStage.COUNTDOWN -> if(seconds > 0) "Get ready · $seconds" else
            if (isPushUp) "Start with extended arms" else "Raise your selected arm"
        ExperimentStage.CALIBRATING -> if(paused) "Bring your arm into view" else
            if (isPushUp) "Practice one push-up" else "Practice one pull-up"
        ExperimentStage.READY -> "Practice accepted — counting is OFF"
        ExperimentStage.COUNTING -> if(paused) "Counting paused" else "Counting is ON"
    }
    val instruction = when(e.stage) {
        ExperimentStage.IDLE, ExperimentStage.ERROR -> if (isPushUp)
            "Use a side view with your whole body in frame. Keep your selected shoulder, elbow and wrist clear."
            else "Keep your hands, bar and head in view, even at the top."
        ExperimentStage.COUNTDOWN -> if (isPushUp)
            "Start with extended arms and keep your $side shoulder, elbow and wrist in view. Wait for the practice cue."
            else "Raise your $side arm above your shoulder. Wait for the practice cue."
        ExperimentStage.CALIBRATING -> if(paused) "Keep your $side arm visible. Practice restarts when tracking returns."
            else if (isPushUp) "Lower with control, pause briefly at the bottom, then return to extended arms."
            else "Go up, pause briefly at the top, then return and hold your starting position."
        ExperimentStage.READY -> if (isPushUp)
            "Was that a full practice push-up? Confirm below. Your next push-ups will count."
            else "Was that a full practice pull-up? Confirm below. Your next pull-ups will count."
        ExperimentStage.COUNTING -> if(paused) "Bring your selected arm into view and hold your starting position. Your count is kept."
            else "Keep the same camera position. Practice is not included in your count."
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("${if (isPushUp) "PUSH-UP" else "PULL-UP"} · $side arm",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Text(title,style=MaterialTheme.typography.titleLarge)
            if(e.stage==ExperimentStage.COUNTING) {
                Text("${e.candidateCycles}",style=MaterialTheme.typography.displayLarge)
                Text("Experimental count",style=MaterialTheme.typography.labelMedium)
            }
            Text(instruction,style=MaterialTheme.typography.bodyLarge)
            when {
                starting -> Button(onClick={onCalibrate(e.side)},enabled=state.cameraActive,modifier=Modifier.fillMaxWidth()) { Text("Start practice") }
                e.stage==ExperimentStage.READY -> Button(onClick=onConfirmRange,enabled=state.cameraActive,modifier=Modifier.fillMaxWidth()) { Text("Confirm full practice & start counting") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(starting || paused) TextButton(onClick={chooseArm=!chooseArm}) { Text("Change arm") }
                if(!starting) TextButton(onClick=onReset) { Text("Reset practice & count") }
            }
            if(chooseArm) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={onCalibrate(ArmSide.LEFT)},enabled=state.cameraActive) { Text("Use left arm") }
                OutlinedButton(onClick={onCalibrate(ArmSide.RIGHT)},enabled=state.cameraActive) { Text("Use right arm") }
            }
        }
    }
}


/**
 * The exploratory engines report a count or a hold duration and nothing else. This panel deliberately
 * shows only what they earned — no practice cue, no confirmation step, no form verdict — and states
 * the limit rather than implying the number is as trustworthy as the calibrated one.
 */
@Composable
private fun OfflineMovementPanel(
    movement: OfflineMovement,
    update: OfflineMovementUpdate?,
    onReset: () -> Unit,
) {
    val isHold = offlineMovementEngine(movement).kind == OfflineMovementKind.HOLD
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(offlineMovementLabel(movement), style = MaterialTheme.typography.titleMedium)
            Text(
                "Exploratory engine · no practice rep, no calibration. It uses fixed thresholds "
                    + "frozen from recorded footage of other people.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (update == null) {
                Text("Waiting for a usable pose.", style = MaterialTheme.typography.bodyMedium)
            } else if (isHold) {
                val held = update.activeHoldNs
                Text(
                    if (held != null) "Holding · ${held / 1_000_000_000.0} s"
                    else "Not holding",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Completed ${(update.completedHoldNs ?: 0L) / 1_000_000_000.0} s total",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("${update.completedReps} counted", style = MaterialTheme.typography.headlineSmall)
            }
            if (update != null) {
                Text("Phase · ${update.phase}", style = MaterialTheme.typography.labelMedium)
                update.rejection?.let {
                    // The engine's own reason enum, unrenamed: it is what the trace will show too.
                    Text(
                        "Not measuring · $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = onReset) { Text("Reset count") }
        }
    }
}
