package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.session.Disposable
import com.calisthenics.diagnostic.study.M05BSustainedProtocol
import com.calisthenics.diagnostic.study.M05BSustainedRuntimeEvidence
import com.calisthenics.diagnostic.telemetry.PipelineTelemetryRecordingMode

/** Narrow live-run authority used by the fixed-duration controller and its deterministic tests. */
interface M05BSustainedLiveRun {
    fun start(cadenceFps: Int): LiveCameraStartResult
    fun stop(): M05BSustainedRuntimeEvidence
    fun elapsedNs(): Long
}

data class M05BSustainedStopObservation(
    val requestedAtNs: Long,
    val invokedAtNs: Long,
    val blockingCodes: List<String>,
    val runtimeEvidence: M05BSustainedRuntimeEvidence,
)

/** Requests exactly one stop at the frozen duration and reports scheduler delay honestly. */
class M05BSustainedRunController(
    private val run: M05BSustainedLiveRun,
    private val scheduler: LiveScheduler,
    private val onStopped: (M05BSustainedStopObservation) -> Unit,
) : Disposable {
    private val monitor = Any()
    private var started = false
    private var stopSchedule: Disposable? = null

    fun start(cadenceFps: Int): LiveCameraStartResult = synchronized(monitor) {
        check(!started) { "sustained controller is single-use" }
        started = true
        val result = run.start(cadenceFps)
        if (result is LiveCameraStartResult.Started) {
            val remainingNs = (M05BSustainedProtocol.TARGET_DURATION_NS - run.elapsedNs()).coerceAtLeast(0L)
            stopSchedule = scheduler.scheduleAfter(remainingNs) { requestStop() }
        }
        result
    }

    private fun requestStop() {
        val invokedAtNs = run.elapsedNs()
        val blocking = buildList {
            if (invokedAtNs > M05BSustainedProtocol.MAX_STOP_REQUEST_NS) add("DURATION.OVERSHOOT")
        }
        val runtimeEvidence = run.stop()
        onStopped(
            M05BSustainedStopObservation(
                requestedAtNs = M05BSustainedProtocol.TARGET_DURATION_NS,
                invokedAtNs = invokedAtNs,
                blockingCodes = blocking,
                runtimeEvidence = runtimeEvidence,
            ),
        )
    }

    override fun dispose() = synchronized(monitor) {
        stopSchedule?.dispose()
        stopSchedule = null
    }

    companion object {
        fun forComposition(
            composition: LiveCameraComposition,
            scheduler: LiveScheduler,
            onStopped: (M05BSustainedStopObservation) -> Unit,
        ): M05BSustainedRunController = M05BSustainedRunController(
            run = object : M05BSustainedLiveRun {
                override fun start(cadenceFps: Int): LiveCameraStartResult = composition.start(
                    configuredInferenceFps = cadenceFps,
                    operatorAttestedNoPerson = true,
                    recordingMode = PipelineTelemetryRecordingMode.SUSTAINED,
                )

                override fun stop(): M05BSustainedRuntimeEvidence {
                    composition.stop()
                    return checkNotNull(composition.sustainedRuntimeEvidence()) {
                        "sustained completion did not expose runtime evidence"
                    }
                }

                override fun elapsedNs(): Long = checkNotNull(composition.sessionElapsedNs()) {
                    "sustained composition has no active session clock"
                }
            },
            scheduler = scheduler,
            onStopped = onStopped,
        )
    }
}
