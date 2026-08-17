package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.MachineInferenceFailure
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.Uuid

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
    val records: List<RawPoseFrame>,
    val failures: List<MachineInferenceFailure>,
    val admissionOpen: Boolean,
)

typealias SessionCounts = com.calisthenics.diagnostic.contract.Counts
