package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.CadenceSample
import com.calisthenics.diagnostic.contract.MachineInferenceFailure
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.live.IncrementalCadenceAccumulator

data class SessionConfiguration(
    val sessionId: Uuid,
    val schemaVersion: SemanticVersion,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    init {
        require(schemaVersion.major == 1) { "unsupported session schema major" }
        require(imageWidth > 0 && imageHeight > 0) { "session image dimensions must be positive" }
    }
}

interface SessionDispatcher {
    fun <T> dispatch(action: () -> T): T
}

object DirectSessionDispatcher : SessionDispatcher {
    override fun <T> dispatch(action: () -> T): T = action()
}

enum class SkipReason(val contractName: String) {
    CADENCE_SELECTION("cadence_selection"),
    BACKPRESSURE("backpressure"),
    PREPROCESSING_FAILED("preprocessing_failed"),
    SESSION_STOPPING("session_stopping"),
}

sealed interface SessionEvent {
    data class FrameReceived(
        val frameIndex: Long,
        val captureTimestampNs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
    ) : SessionEvent

    data class FrameSkipped(val reason: SkipReason) : SessionEvent

    data class InferenceSubmitted(
        val submissionIndex: Long,
        val inferenceStartNs: Long,
    ) : SessionEvent

    data class InferenceSucceeded(
        val submissionIndex: Long,
        val inferenceEndNs: Long,
        val posePresent: Boolean,
        val normalizedImageLandmarks: List<Landmark>? = null,
        val worldLandmarks: List<Landmark>? = null,
    ) : SessionEvent

    data class InferenceFailed(
        val submissionIndex: Long,
        val failureSource: String,
        val classification: String,
        val sourceErrorCode: String? = null,
        val detail: String? = null,
    ) : SessionEvent

    data object StopRequested : SessionEvent

    data object DrainTimedOut : SessionEvent
}

data class SessionSnapshot(
    val counts: SessionCounts,
    val boundedState: BoundedSessionSnapshot,
    val admissionOpen: Boolean,
) {
    val failures: List<MachineInferenceFailure>
        get() = listOfNotNull(boundedState.failure)
}

typealias SessionCounts = com.calisthenics.diagnostic.contract.Counts

fun interface EmittedRecordSink {
    fun appendPose(record: RawPoseFrame)
}

data class BoundedSessionSnapshot(
    val normalizedImageLandmarksAvailable: Boolean,
    val worldLandmarksAvailable: Boolean,
    val achievedInferenceFpsTimeseries: List<CadenceSample>,
    val failure: MachineInferenceFailure?,
    val currentSubmissionIndex: Long?,
    val submittedHighWaterMark: Long?,
    val terminalHighWaterMark: Long?,
    val lastTerminalState: TerminalState?,
    val latestInferenceLatencyNs: Long?,
    val retainedRecordCount: Int,
    val retainedTerminalEntryCount: Int,
)

/**
 * Bounded session-derived state. Raw pose records are streamed to their owner and terminal
 * submissions collapse into monotone high-water marks; neither collection grows with frame count.
 */
class BoundedSessionState {
    private val cadence = IncrementalCadenceAccumulator()
    private var normalizedAvailable = false
    private var worldAvailable = false
    private var retainedFailure: MachineInferenceFailure? = null
    private var currentSubmission: Long? = null
    private var submittedHighWater: Long? = null
    private var terminalHighWater: Long? = null
    private var terminalState: TerminalState? = null
    private var latestLatencyNs: Long? = null

    fun submitted(submissionIndex: Long) {
        check(currentSubmission == null) { "only one inference submission may be in flight" }
        val expected = (submittedHighWater ?: -1L) + 1L
        check(submissionIndex == expected) { "submission indices must be contiguous" }
        currentSubmission = submissionIndex
        submittedHighWater = submissionIndex
    }

    fun emitted(submissionIndex: Long, record: RawPoseFrame) {
        requireCurrent(submissionIndex)
        normalizedAvailable = normalizedAvailable || record.normalizedImageLandmarks != null
        worldAvailable = worldAvailable || record.worldLandmarks != null
        cadence.observe(record.captureTimestampNs)
        latestLatencyNs = record.inferenceEndNs - record.inferenceStartNs
        terminal(submissionIndex, TerminalState.EMITTED)
    }

    fun failed(failure: MachineInferenceFailure, state: TerminalState = TerminalState.FAILED) {
        requireCurrent(failure.submissionIndex)
        check(retainedFailure == null) { "only the first inference failure may be retained" }
        require(state == TerminalState.FAILED || state == TerminalState.TIMED_OUT) {
            "failure terminal state must be FAILED or TIMED_OUT"
        }
        retainedFailure = failure
        terminal(failure.submissionIndex, state)
    }

    fun snapshot(): BoundedSessionSnapshot = BoundedSessionSnapshot(
        normalizedImageLandmarksAvailable = normalizedAvailable,
        worldLandmarksAvailable = worldAvailable,
        achievedInferenceFpsTimeseries = cadence.completedSamples(),
        failure = retainedFailure,
        currentSubmissionIndex = currentSubmission,
        submittedHighWaterMark = submittedHighWater,
        terminalHighWaterMark = terminalHighWater,
        lastTerminalState = terminalState,
        latestInferenceLatencyNs = latestLatencyNs,
        retainedRecordCount = 0,
        retainedTerminalEntryCount = 0,
    )

    private fun requireCurrent(submissionIndex: Long) {
        check(currentSubmission == submissionIndex) { "submission has no pending inference call" }
    }

    private fun terminal(submissionIndex: Long, state: TerminalState) {
        currentSubmission = null
        terminalHighWater = submissionIndex
        terminalState = state
    }
}
