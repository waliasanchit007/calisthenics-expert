package com.calisthenics.diagnostic.session

import com.calisthenics.diagnostic.contract.Landmark
import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.export.SessionExporter
import com.calisthenics.diagnostic.export.StoragePort
import com.calisthenics.diagnostic.preprocessing.scalePadRgb24

/**
 * A complete, platform-neutral fixture replay for conformance and integration tests.
 *
 * The script deliberately exercises the same seams as a live producer: canonical preprocessing,
 * asynchronous inference admission, lifecycle fencing, and crash-safe export. It contains no
 * platform or backend implementation; the scripted inference port is only the deterministic
 * fixture backend used by this core.
 */
class DeterministicCore(
    private val configuration: SessionConfiguration,
    private val manifestTemplate: SessionManifest,
    private val storage: StoragePort,
    private val dispatcher: SessionDispatcher = DirectSessionDispatcher,
) {
    init {
        require(manifestTemplate.sessionId == configuration.sessionId) { "manifest and session IDs must match" }
        require(manifestTemplate.schemaVersion == configuration.schemaVersion) {
            "manifest and session schema versions must match"
        }
        require(manifestTemplate.modelInput.modelInputWidth == configuration.imageWidth)
        require(manifestTemplate.modelInput.modelInputHeight == configuration.imageHeight)
    }

    fun run(
        sourceRgb24: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
    ): DeterministicCoreResult {
        val canonicalInput = scalePadRgb24(
            source = sourceRgb24.copyOf(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = configuration.imageWidth,
            targetHeight = configuration.imageHeight,
        )
        val scheduler = ScriptedDeadlineScheduler()
        val inference = ScriptedInferencePort()
        val runner = SessionRunner(
            configuration = configuration,
            inference = inference,
            deadlineScheduler = scheduler,
            drainTimeoutNs = 1_000_000L,
            finalizer = SessionFinalizer { snapshot ->
                // SessionRunner keeps its internal timeout source explicit as "session". The
                // released contract's failure-source vocabulary calls that producer-side class
                // "harness", so normalize only at the contract-writing boundary.
                SessionExporter(storage, manifestTemplate).finalize(snapshot.toContractSnapshot())
            },
            dispatcher = dispatcher,
        )

        // Selected frame 0: a valid backend result with no pose.
        runner.submit(0, 100_000_000L, 100_000_001L, canonicalInput)
        // Selected frame 1 is observable backpressure, not an invented upstream loss.
        runner.skip(1, 133_333_333L, SkipReason.BACKPRESSURE)
        // Selected frame 2: a valid pose-present result.
        runner.submit(2, 166_666_666L, 166_666_667L, canonicalInput)
        // Selected frame 3: one backend call failure.
        runner.submit(3, 200_000_000L, 200_000_001L, canonicalInput)
        // Selected frame 4 remains in flight so drain fencing classifies it as a timeout.
        runner.submit(4, 233_333_333L, 233_333_334L, canonicalInput)

        runner.stop(nowNs = 1_000_000_000L)
        scheduler.fire()
        // This callback is intentionally after the timeout fence and must be diagnostic-only.
        inference.deliverLateCallback(
            submissionIndex = 3L,
            result = InferenceResult.Success(
                inferenceEndNs = 1_001_000_001L,
                posePresent = true,
                normalizedImageLandmarks = deterministicLandmarks(0.01),
            ),
        )

        val finalization = runner.finalize()
        return DeterministicCoreResult(
            finalization = finalization,
            snapshot = runner.snapshot(),
        )
    }

    private fun SealedSessionSnapshot.toContractSnapshot(): SealedSessionSnapshot = SealedSessionSnapshot(
        counts = counts,
        records = records,
        failures = failures.map { failure ->
            if (failure.failureSource == "session") failure.copy(failureSource = "harness") else failure
        },
        terminalMap = terminalMap,
    )

    private class ScriptedInferencePort : InferencePort {
        private val callbacks = linkedMapOf<Long, (InferenceResult) -> Unit>()

        override fun submit(
            submissionIndex: Long,
            canonicalInput: ByteArray,
            backendTimestampMs: Long,
            callback: (InferenceResult) -> Unit,
        ) {
            require(canonicalInput.isNotEmpty())
            require(backendTimestampMs >= 0)
            when (submissionIndex) {
                0L -> callback(InferenceResult.Success(100_000_002L, posePresent = false))
                1L -> callback(
                    InferenceResult.Success(
                        inferenceEndNs = 166_666_668L,
                        posePresent = true,
                        normalizedImageLandmarks = deterministicLandmarks(0.01),
                        worldLandmarks = deterministicLandmarks(1.0),
                    ),
                )
                2L -> callback(InferenceResult.Failure("backend", "backend_call_failed", "synthetic_backend_error"))
                3L -> callbacks[submissionIndex] = callback
                else -> error("unexpected scripted submission index: $submissionIndex")
            }
        }

        fun deliverLateCallback(submissionIndex: Long, result: InferenceResult) {
            callbacks.getValue(submissionIndex).invoke(result)
        }
    }

    private class ScriptedDeadlineScheduler : DeadlineScheduler {
        private var scheduledAction: (() -> Unit)? = null

        override fun schedule(deadlineNs: Long, action: () -> Unit): Disposable {
            require(deadlineNs >= 0)
            check(scheduledAction == null) { "only one deterministic deadline is expected" }
            scheduledAction = action
            return object : Disposable {
                override fun dispose() {
                    scheduledAction = null
                }
            }
        }

        fun fire() {
            val action = scheduledAction ?: error("deterministic drain deadline was not scheduled")
            scheduledAction = null
            action()
        }
    }
}

data class DeterministicCoreResult(
    val finalization: FinalizationResult,
    val snapshot: SessionRunnerSnapshot,
)

private fun deterministicLandmarks(scale: Double): List<Landmark> = (0 until 33).map { index ->
    Landmark(
        index = index,
        name = null,
        x = index * scale,
        y = index * scale + scale,
        z = index * scale + scale * 2,
        visibility = 0.9,
        presence = 0.8,
    )
}
