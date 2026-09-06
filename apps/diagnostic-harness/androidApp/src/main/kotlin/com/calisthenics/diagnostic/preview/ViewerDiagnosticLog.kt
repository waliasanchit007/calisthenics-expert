package com.calisthenics.diagnostic.preview

import android.util.Log
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.experimental.ExperimentInputIssue
import com.calisthenics.diagnostic.viewer.experimental.PullUpExperimentState
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlin.math.round

internal const val DIAGNOSTIC_AUTH_MS = 30 * 60 * 1000L

/** The marker lives in private noBackupFilesDir and is created by authorized adb run-as, not an intent. */
internal fun viewerDiagnosticsAuthorized(marker: File, debugBuild: Boolean, nowMs: Long): Boolean =
    debugBuild && marker.isFile && nowMs - marker.lastModified() in 0 until DIAGNOSTIC_AUTH_MS

internal enum class ViewerDebugEvent { START_CAMERA, START_SYNTHETIC, SAMPLE, CALIBRATE, CONFIRM, RESET, STOP, DISABLE, EXPIRED }

internal data class ViewerJointConfidence(val leftVisibility: Double? = null, val leftPresence: Double? = null,
    val rightVisibility: Double? = null, val rightPresence: Double? = null)

/** Allowlisted derived diagnostics only. Never accepts an image, landmark array, path or exception. */
internal class ViewerDiagnosticLog(
    private val build: String,
    private val originNs: Long,
    private val exact: Boolean = false,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val onClosed: () -> Unit = {},
    private val emit: (String) -> Unit = { Log.i("CalisthenicsTrace",it); Unit },
) : AutoCloseable {
    private val session = sessionId
    private val drops = AtomicLong()
    val droppedRows: Long get() = drops.get()
    private var suppressed = 0L
    private var sequence = 0L
    private var lastEmittedNs: Long? = null
    private var previousState: List<Any?>? = null
    private val writer = object : ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(64),
        { task -> Thread(task,"viewer-diagnostic-writer").apply { isDaemon = true } }) {
        override fun terminated() { onClosed() }
    }

    @Synchronized fun record(
        event: ViewerDebugEvent, nowNs: Long, measurements: MovementMeasurements?,
        state: PullUpExperimentState, issue: ExperimentInputIssue?, feed: MovementFeedStatus,
        gapNs: Long?, submitted: Int, completed: Int, pipelineSkipped: Int,
        jointConfidence: ViewerJointConfidence = ViewerJointConfidence(), performance: ViewerPerformance? = null,
        observation: ViewerObservation? = null,
    ) {
        if (writer.isShutdown) return
        val signature = listOf(state.movement,state.stage,state.phase,state.candidateCycles,state.failure,state.lastCycleOutcome,issue,
            measurements?.left?.trackingIssue, measurements?.right?.trackingIssue,
            if (exact) observation?.capturedNs ?: measurements?.capturedNs else null,
            if (exact) feed else null)
        if (event == ViewerDebugEvent.SAMPLE && signature == previousState &&
            lastEmittedNs?.let { nowNs - it in 0 until 100_000_000L } == true) {
            suppressed++; return
        }
        previousState = signature
        lastEmittedNs = nowNs
        val seq = sequence++
        val suppressedAtEnqueue = suppressed
        try {
            writer.execute {
                try {
                    fun numeric(value: Double?, step: Double? = null): Any =
                        value?.takeIf { it.isFinite() }?.let { if (step == null || exact) it else round(it / step) * step }
                            ?: JSONObject.NULL
                    val row = JSONObject()
                        .put("v",if (exact) 2 else 1).put("session",session).put("seq",seq).put("build",build)
                        .put("logging_enabled",true).put("sample_interval_ms",100)
                        .put("angle_quantum_deg",if (exact) 0 else .5).put("height_quantum",if (exact) 0 else .005)
                        .put("capture_ns",measurements?.capturedNs ?: JSONObject.NULL)
                        .put("decision_ns",nowNs)
                        .put("observation_capture_ns",observation?.capturedNs ?: JSONObject.NULL)
                        .put("input_rgb_sha256",if (exact && observation != null) feedbackSha(observation.frame.modelRgb) else JSONObject.NULL)
                        .put("event",event.name).put("elapsed_ms",(nowNs-originNs)/1_000_000.0)
                        .put("capture_ms",numeric(measurements?.let { (it.capturedNs-originNs)/1_000_000.0 }))
                        .put("age_ms",numeric(measurements?.let { (nowNs-it.capturedNs)/1_000_000.0 }))
                        .put("capture_gap_ms",numeric(gapNs?.div(1_000_000.0)))
                        .put("feed",feed.name).put("input_issue",issue?.name ?: JSONObject.NULL)
                        .put("stage",state.stage.name).put("phase",state.phase.name).put("side",state.side.name)
                        .put("movement",state.movement.name)
                        .put("failure_code",state.failure?.name ?: JSONObject.NULL)
                        .put("candidate_cycles",state.candidateCycles)
                        .put("cycle_outcome",state.lastCycleOutcome?.name ?: JSONObject.NULL)
                        .put("range_low",numeric(state.range?.lowDegrees,.5)).put("range_high",numeric(state.range?.highDegrees,.5))
                        .put("left_elbow_deg",numeric(measurements?.left?.elbowDegrees,.5))
                        .put("right_elbow_deg",numeric(measurements?.right?.elbowDegrees,.5))
                        .put("left_wrist_height",numeric(measurements?.left?.wristHeightFraction,.005))
                        .put("right_wrist_height",numeric(measurements?.right?.wristHeightFraction,.005))
                        .put("left_confidence",numeric(measurements?.left?.minimumConfidence,.01))
                        .put("right_confidence",numeric(measurements?.right?.minimumConfidence,.01))
                        .put("left_unavailable_joint",measurements?.left?.trackingIssue?.joint?.name ?: JSONObject.NULL)
                        .put("left_unavailable_reason",measurements?.left?.trackingIssue?.reason?.name ?: JSONObject.NULL)
                        .put("right_unavailable_joint",measurements?.right?.trackingIssue?.joint?.name ?: JSONObject.NULL)
                        .put("right_unavailable_reason",measurements?.right?.trackingIssue?.reason?.name ?: JSONObject.NULL)
                        .put("left_joint_visibility_min",numeric(jointConfidence.leftVisibility,.01))
                        .put("left_joint_presence_min",numeric(jointConfidence.leftPresence,.01))
                        .put("right_joint_visibility_min",numeric(jointConfidence.rightVisibility,.01))
                        .put("right_joint_presence_min",numeric(jointConfidence.rightPresence,.01))
                        .put("capture_to_result_ms",numeric(performance?.captureToResultMs))
                        .put("inference_ms",numeric(performance?.inferenceMs))
                        .put("pose_age_ms",numeric(performance?.poseAgeMs))
                        .put("available_arm_joints",measurements?.availableArmJoints ?: 0)
                        .put("submitted",submitted).put("completed",completed).put("pipeline_skipped",pipelineSkipped)
                        .put("writer_dropped",drops.get()).put("rate_suppressed",suppressedAtEnqueue)
                    emit(row.toString())
                } catch (_: Exception) { drops.incrementAndGet() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { drops.incrementAndGet() }
    }

    @Synchronized override fun close() { writer.shutdown() }
}
