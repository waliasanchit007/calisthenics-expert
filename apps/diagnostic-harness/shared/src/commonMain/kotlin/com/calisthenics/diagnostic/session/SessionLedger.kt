package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.MachineInferenceFailure
import com.calisthenics.diagnostic.contract.RawPoseFrame
import com.calisthenics.diagnostic.contract.nextBackendTimestampMs

class SessionLedger(
    private val configuration: SessionConfiguration,
    private val dispatcher: SessionDispatcher = DirectSessionDispatcher,
) {
    private data class ReceivedFrame(
        val frameIndex: Long,
        val captureTimestampNs: Long,
    )

    private data class PendingSubmission(
        val submissionIndex: Long,
        val frame: ReceivedFrame,
        val inferenceStartNs: Long,
        val backendTimestampMs: Long,
    )

    private val records = mutableListOf<RawPoseFrame>()
    private val failures = mutableListOf<MachineInferenceFailure>()
    private val skipCounts = linkedMapOf<SkipReason, Long>().apply {
        SkipReason.entries.forEach { this[it] = 0L }
    }
    private val pendingSubmissions = linkedMapOf<Long, PendingSubmission>()
    private val completedSubmissionIndices = mutableSetOf<Long>()

    private var cameraFramesReceived = 0L
    private var inferenceFramesSubmitted = 0L
    private var inferenceRecordsEmitted = 0L
    private var poseDetectedRecords = 0L
    private var framesSkippedBeforeInference = 0L
    private var trailingSkipped = 0L
    private var inferenceFailures = 0L
    private var skippedSinceLastEmittedRecord = 0L
    private var previousBackendTimestampMs: Long? = null
    private var lastReceivedFrameIndex: Long? = null
    private var unclassifiedFrame: ReceivedFrame? = null
    private var admissionOpen = true

    fun accept(event: SessionEvent): SessionSnapshot = dispatcher.dispatch {
        reduce(event)
        snapshotInternal()
    }

    fun snapshot(): SessionSnapshot = dispatcher.dispatch { snapshotInternal() }

    private fun reduce(event: SessionEvent) {
        when (event) {
            is SessionEvent.FrameReceived -> receiveFrame(event)
            is SessionEvent.FrameSkipped -> skipFrame(event)
            is SessionEvent.InferenceSubmitted -> submitFrame(event)
            is SessionEvent.InferenceSucceeded -> emitRecord(event)
            is SessionEvent.InferenceFailed -> failSubmission(event)
            SessionEvent.StopRequested -> admissionOpen = false
            SessionEvent.DrainTimedOut -> timeoutPendingSubmissions()
        }
    }

    private fun receiveFrame(event: SessionEvent.FrameReceived) {
        check(admissionOpen) { "session is no longer accepting frames" }
        check(unclassifiedFrame == null) { "received frame must be classified before another frame arrives" }
        require(event.frameIndex >= 0) { "frame index must be non-negative" }
        require(event.captureTimestampNs >= 0) { "capture timestamp must be non-negative" }
        require(event.imageWidth == configuration.imageWidth && event.imageHeight == configuration.imageHeight) {
            "received frame dimensions do not match the session model-input dimensions"
        }
        require(lastReceivedFrameIndex == null || event.frameIndex > lastReceivedFrameIndex!!) {
            "frame indices must strictly increase"
        }

        cameraFramesReceived++
        lastReceivedFrameIndex = event.frameIndex
        unclassifiedFrame = ReceivedFrame(event.frameIndex, event.captureTimestampNs)
    }

    private fun skipFrame(event: SessionEvent.FrameSkipped) {
        check(unclassifiedFrame != null) { "a skipped frame must follow a received frame" }
        unclassifiedFrame = null
        framesSkippedBeforeInference++
        skippedSinceLastEmittedRecord++
        skipCounts[event.reason] = skipCounts.getValue(event.reason) + 1
        trailingSkipped = skippedSinceLastEmittedRecord
    }

    private fun submitFrame(event: SessionEvent.InferenceSubmitted) {
        val frame = requireUnclassifiedFrame()
        check(event.submissionIndex == inferenceFramesSubmitted) {
            "submission indices must be contiguous and start at zero"
        }
        require(event.inferenceStartNs >= frame.captureTimestampNs) {
            "inference must start at or after capture"
        }
        val backendTimestampMs = nextBackendTimestampMs(frame.captureTimestampNs, previousBackendTimestampMs)
        previousBackendTimestampMs = backendTimestampMs
        inferenceFramesSubmitted++
        unclassifiedFrame = null
        pendingSubmissions[event.submissionIndex] = PendingSubmission(
            submissionIndex = event.submissionIndex,
            frame = frame,
            inferenceStartNs = event.inferenceStartNs,
            backendTimestampMs = backendTimestampMs,
        )
    }

    private fun emitRecord(event: SessionEvent.InferenceSucceeded) {
        val submission = takePendingSubmission(event.submissionIndex)
        require(event.inferenceEndNs >= submission.inferenceStartNs) {
            "inference must end at or after inference start"
        }
        val record = RawPoseFrame(
            schemaVersion = configuration.schemaVersion,
            sessionId = configuration.sessionId,
            frameIndex = submission.frame.frameIndex,
            captureTimestampNs = submission.frame.captureTimestampNs,
            backendTimestampMs = submission.backendTimestampMs,
            inferenceStartNs = submission.inferenceStartNs,
            inferenceEndNs = event.inferenceEndNs,
            posePresent = event.posePresent,
            normalizedImageLandmarks = event.normalizedImageLandmarks,
            worldLandmarks = event.worldLandmarks,
            imageWidth = configuration.imageWidth,
            imageHeight = configuration.imageHeight,
            framesSkippedSinceLastEmittedRecord = skippedSinceLastEmittedRecord,
        )
        records += record
        inferenceRecordsEmitted++
        if (event.posePresent) poseDetectedRecords++
        skippedSinceLastEmittedRecord = 0L
        trailingSkipped = 0L
    }

    private fun failSubmission(event: SessionEvent.InferenceFailed) {
        val submission = takePendingSubmission(event.submissionIndex)
        failures += MachineInferenceFailure(
            submissionIndex = submission.submissionIndex,
            captureTimestampNs = submission.frame.captureTimestampNs,
            failureSource = event.failureSource,
            classification = event.classification,
            sourceErrorCode = event.sourceErrorCode,
            detail = event.detail,
        )
        inferenceFailures++
    }

    private fun timeoutPendingSubmissions() {
        admissionOpen = false
        pendingSubmissions.keys.toList().forEach { submissionIndex ->
            failSubmission(
                SessionEvent.InferenceFailed(
                    submissionIndex = submissionIndex,
                    failureSource = "session",
                    classification = "session_teardown_timeout",
                ),
            )
        }
    }

    private fun requireUnclassifiedFrame(): ReceivedFrame {
        val frame = unclassifiedFrame ?: error("inference event must follow a received frame")
        return frame
    }

    private fun takePendingSubmission(submissionIndex: Long): PendingSubmission {
        check(submissionIndex in 0 until inferenceFramesSubmitted) {
            "submission index is outside the accepted submission range"
        }
        check(submissionIndex !in completedSubmissionIndices) { "submission already has a terminal result" }
        val submission = pendingSubmissions.remove(submissionIndex)
            ?: error("submission has no pending inference call")
        completedSubmissionIndices += submissionIndex
        return submission
    }

    private fun snapshotInternal(): SessionSnapshot = SessionSnapshot(
        counts = Counts(
            cameraFramesReceived = cameraFramesReceived,
            inferenceFramesSubmitted = inferenceFramesSubmitted,
            inferenceRecordsEmitted = inferenceRecordsEmitted,
            poseDetectedRecords = poseDetectedRecords,
            framesSkippedBeforeInference = framesSkippedBeforeInference,
            framesSkippedBeforeInferenceByReason = skipCounts.mapKeys { it.key.contractName },
            trailingFramesSkippedAfterLastEmittedRecord = trailingSkipped,
            inferenceFailures = inferenceFailures,
        ),
        records = records.toList(),
        failures = failures.toList(),
        admissionOpen = admissionOpen,
    )
}
