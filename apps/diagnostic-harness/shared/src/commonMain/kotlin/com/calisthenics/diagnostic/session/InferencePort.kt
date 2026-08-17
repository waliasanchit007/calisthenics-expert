package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.Landmark

interface InferencePort {
    fun submit(
        submissionIndex: Long,
        canonicalInput: ByteArray,
        backendTimestampMs: Long,
        callback: (InferenceResult) -> Unit,
    )

    fun cancel(submissionIndex: Long) {}
}

sealed interface InferenceResult {
    data class Success(
        val inferenceEndNs: Long,
        val posePresent: Boolean,
        val normalizedImageLandmarks: List<Landmark>? = null,
        val worldLandmarks: List<Landmark>? = null,
    ) : InferenceResult

    data class Failure(
        val failureSource: String,
        val classification: String,
        val sourceErrorCode: String? = null,
        val detail: String? = null,
    ) : InferenceResult
}

interface Disposable {
    fun dispose()
}

interface DeadlineScheduler {
    fun schedule(deadlineNs: Long, action: () -> Unit): Disposable
}
