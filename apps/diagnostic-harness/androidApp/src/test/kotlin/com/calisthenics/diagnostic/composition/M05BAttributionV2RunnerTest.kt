package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.study.AttributionV2DeviceSource
import com.calisthenics.diagnostic.study.AttributionV2EndToEndRun
import com.calisthenics.diagnostic.study.AttributionV2Callback
import com.calisthenics.diagnostic.study.AttributionV2ArmStatus
import com.calisthenics.diagnostic.live.LiveFrameClassification
import com.calisthenics.diagnostic.study.AttributionV2RunIdentity
import com.calisthenics.diagnostic.study.AttributionV2TerminalClass
import com.calisthenics.diagnostic.study.M05BAttributionV2Protocol
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class M05BAttributionV2RunnerTest {
    @Test
    fun sourceArmDiscardsThirtyRecordsThreeHundredClosesEveryFrameAndUsesSixtySecondCap() {
        val source = RecordingSource((0 until 500).map { SourceFrame(it * 33_333_333L) })
        val backend = RecordingBackend.successfulCapacity()
        val storage = RecordingStorage()
        val result = runner(source = source, backendFactory = { backend }, storage = storage).run("run-0a")

        assertEquals(M05BAttributionV2Protocol.ARM_CAP_NS, source.requestedCapNs)
        assertEquals(330, source.delivered)
        assertEquals(300, result.observation.sourceArm.measuredTimestampsNs.size)
        assertEquals(330, source.closed)
        assertEquals(7, backend.invocations)
        assertEquals(listOf("temp", "write", "fsync", "rename", "directory-fsync", "reopen"), storage.operations)
        assertEquals(1, storage.statuses)
    }

    @Test
    fun thirtiethSourceCallbackRemainsWarmupAndMeasuredSequenceStartsAtCallbackThirtyOne() {
        val source = RecordingSource(measuredFrames())

        val result = runner(source = source).run("warmup-boundary")

        assertEquals(30 * 33_333_333L, result.observation.sourceArm.measuredTimestampsNs.first())
    }

    @Test
    fun backendArmRequiresBothWarmupConditionsAndOneHundredLaterCompletions() {
        val source = RecordingSource(measuredFrames())
        val backend = RecordingBackend(
            result = successfulBackendRun(
                warmupCompletions = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS,
                warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
            ),
        )

        val result = runner(source = source, backendFactory = { backend }).run("run-0b")

        assertEquals(7, backend.invocations)
        val spec = backend.specs.first()
        assertEquals(M05BAttributionV2Protocol.ARM_CAP_NS, spec.armCapNs)
        assertEquals(M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS, spec.warmupCompletions)
        assertEquals(M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS, spec.warmupElapsedNs)
        assertEquals(M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS, spec.measuredCompletions)
        assertEquals(100, result.observation.backendArm?.measuredServiceDurationsNs?.size)
        assertEquals(20, result.observation.backendArm?.warmupCount)
        assertEquals(2_000_000_000L, result.observation.backendArm?.warmupElapsedNs)
    }

    @Test
    fun backendWarmupRequiresCompletionCountAndElapsedTimeBeforeMeasuredArm() {
        val backend = RecordingBackend(
            result = successfulBackendRun(
                warmupCompletions = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS - 1,
                warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
            ),
        )
        val selector = RecordingSelector()

        val result = runner(
            source = RecordingSource(measuredFrames()),
            backendFactory = { backend },
            selector = selector,
        ).run("warmup-conditions")

        assertEquals(AttributionV2ArmStatus.BLOCKED, result.observation.backendArm?.status)
        assertTrue(result.observation.blockingCodes.contains("ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT"))
        assertEquals(0, selector.invocations)
    }

    @Test
    fun armZeroBackendBlockerPreventsArmTwo() {
        val backend = RecordingBackend(
            successfulBackendRun(
                warmupCompletions = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS - 1,
            ),
        )
        val selector = RecordingSelector()

        val result = runner(
            source = RecordingSource(measuredFrames()),
            backendFactory = { backend },
            selector = selector,
        ).run("backend-blocked")

        assertTrue(result.observation.backendArm?.blockingCodes?.isNotEmpty() == true)
        assertEquals(0, selector.invocations)
        assertTrue(result.observation.endToEndReplicates.isEmpty())
    }

    @Test
    fun blockerInArmZeroPreventsSelectorAndEndToEndArms() {
        val source = RecordingSource(measuredFrames().take(29), hitCap = true)
        val backend = RecordingBackend.successfulCapacity()
        val selector = RecordingSelector()
        val backendFactories = mutableListOf<RecordingBackend>()

        val result = runner(
            source = source,
            backendFactory = {
                backendFactories += backend
                backend
            },
            selector = selector,
        ).run("blocked")

        assertTrue(result.observation.blockingCodes.contains("ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT"))
        assertEquals(0, selector.invocations)
        assertEquals(0, backendFactories.size)
        assertTrue(result.observation.endToEndReplicates.isEmpty())
    }

    @Test
    fun selectorArmUsesTheExactArmZeroATimestampListAndProductionSelector() {
        val source = RecordingSource(measuredFrames())
        val selector = RecordingSelector()

        runner(source = source, selector = selector).run("selector")

        assertEquals(M05BAttributionV2Protocol.TARGET_CADENCES_FPS.size, selector.invocations)
        M05BAttributionV2Protocol.TARGET_CADENCES_FPS.forEachIndexed { index, cadence ->
            assertTrue(selector.timestampArguments[index] === selector.timestampArguments.first())
            assertEquals(cadence, selector.cadenceArguments[index])
        }
    }

    @Test
    fun armTwoRunsBothPrereGisteredOrdersWithFreshCompositionsAndTerminalClosure() {
        val source = RecordingSource(measuredFrames())
        val backends = mutableListOf<RecordingBackend>()
        val result = runner(
            source = source,
            backendFactory = {
                RecordingBackend.successfulEndToEnd().also { backends += it }
            },
        ).run("replicates")

        assertEquals(M05BAttributionV2Protocol.REPLICATE_ORDERS, result.observation.endToEndReplicates.map { it.order })
        assertEquals(7, backends.size)
        assertEquals(7, backends.distinct().size)
        assertTrue(result.observation.endToEndReplicates.all { replicate ->
            replicate.runs.all { run -> run.pendingInferenceIds.isEmpty() &&
                run.deliveredCallbackIds.toSet() == run.terminalCallbacks.map { it.callbackId }.toSet()
            }
        })
    }

    @Test
    fun thermalRecoveryUsesExactBoundaryStatusAndOneSecondScheduleAndStopsWhenItFails() {
        val thermal = RecordingThermal(boundaryStatus = 3, recover = false)
        val backend = RecordingBackend.successfulCapacity()
        val result = runner(source = RecordingSource(measuredFrames()), backendFactory = { backend }, thermal = thermal)
            .run("thermal")

        assertEquals(1, thermal.cooldownCalls)
        assertEquals(3, thermal.requestedBoundaryStatus)
        assertEquals(M05BAttributionV2Protocol.COOLDOWN_SAMPLE_NS, thermal.requestedIntervalNs)
        assertEquals(M05BAttributionV2Protocol.COOLDOWN_MAX_NS, thermal.requestedMaximumNs)
        assertTrue(result.observation.blockingCodes.contains("ATTRIBUTION.THERMAL_STATE_MISMATCH"))
        assertEquals(0, backend.invocations)
    }

    @Test
    fun armTwoStopsWhenThermalDoesNotRecoverBeforeMeasuredRun() {
        val thermal = RecordingThermal(recoverySequence = mutableListOf(true, false))
        val backend = RecordingBackend.successfulEndToEnd()

        val result = runner(
            source = RecordingSource(measuredFrames()),
            backendFactory = { backend },
            thermal = thermal,
        ).run("arm-two-thermal")

        assertEquals(1, backend.invocations)
        assertTrue(result.observation.blockingCodes.contains("ATTRIBUTION.THERMAL_STATE_MISMATCH"))
        assertEquals(1, result.observation.endToEndReplicates.size)
        assertTrue(result.observation.endToEndReplicates.single().runs.isEmpty())
    }

    private fun runner(
        source: RecordingSource,
        backendFactory: () -> RecordingBackend = { RecordingBackend.successfulEndToEnd() },
        selector: RecordingSelector = RecordingSelector(),
        thermal: RecordingThermal = RecordingThermal(),
        storage: RecordingStorage = RecordingStorage(),
    ) = M05BAttributionV2Runner(
        M05BAttributionV2Runner.Dependencies(
            protocolSha256 = "a".repeat(64),
            device = AttributionV2DeviceSource("physical_device", "device", "manufacturer", "model", 36, "16", "build"),
            identity = AttributionV2RunIdentity("b".repeat(40), "b".repeat(40), "c".repeat(64), "d".repeat(64), "e".repeat(64), "16", "backend", "cpu"),
            sourceArmFactory = { source },
            backendArmFactory = backendFactory,
            selector = selector,
            thermal = thermal,
            storage = storage,
            statusSink = { storage.statuses += 1 },
        ),
    )

    private fun measuredFrames() = (0 until 330).map { SourceFrame(it * 33_333_333L) }

    private class RecordingSource(
        private val frames: List<SourceFrame>,
        private val hitCap: Boolean = false,
    ) : M05BAttributionV2SourceArm {
        var requestedCapNs: Long = -1
        var delivered = 0
        var closed = 0

        override fun capture(capNs: Long, onFrame: (M05BAttributionV2SourceFrame) -> Boolean): SourceCaptureResult {
            requestedCapNs = capNs
            for (frame in frames) {
                delivered += 1
                closed += 1
                if (!onFrame(frame)) break
            }
            return SourceCaptureResult(
                elapsedNs = if (hitCap) capNs else 11_000_000_000L,
                stoppedByCap = hitCap,
            )
        }
    }

    private class RecordingBackend(
        private val result: BackendRunResult,
    ) : M05BAttributionV2Backend {
        var invocations = 0
        val specs = mutableListOf<BackendRunSpec>()

        override fun run(spec: BackendRunSpec): BackendRunResult {
            invocations += 1
            specs += spec
            return result
        }

        companion object {
            fun successfulCapacity() = RecordingBackend(successfulBackendRun())

            fun successfulEndToEnd() = RecordingBackend(
                successfulBackendRun(endToEnd = validEndToEndRun()),
            )
        }
    }

    private class RecordingSelector : M05BAttributionV2Selector {
        var invocations = 0
        val timestampArguments = mutableListOf<List<Long>>()
        val cadenceArguments = mutableListOf<Int>()

        override fun select(timestampsNs: List<Long>, cadenceFps: Int): List<Long> {
            invocations += 1
            timestampArguments += timestampsNs
            cadenceArguments += cadenceFps
            return com.calisthenics.diagnostic.study.productionSelectorTimestamps(timestampsNs, cadenceFps)
        }
    }

    private class RecordingThermal(
        private val boundaryStatus: Int = 0,
        private val recover: Boolean = true,
        private val recoverySequence: MutableList<Boolean>? = null,
    ) : M05BAttributionV2Thermal {
        var cooldownCalls = 0
        var requestedBoundaryStatus = -1
        var requestedIntervalNs = -1L
        var requestedMaximumNs = -1L

        override fun boundaryStatus(): Int = boundaryStatus

        override fun awaitBoundary(status: Int, sampleIntervalNs: Long, maximumDurationNs: Long): ThermalCooldown {
            cooldownCalls += 1
            requestedBoundaryStatus = status
            requestedIntervalNs = sampleIntervalNs
            requestedMaximumNs = maximumDurationNs
            return ThermalCooldown(listOf(boundaryStatus), recoverySequence?.removeFirstOrNull() ?: recover)
        }
    }

    private class RecordingStorage : M05BAttributionV2ArtifactStorage {
        val operations = mutableListOf<String>()
        var statuses = 0

        override fun createTemporary(runId: String): String {
            operations += "temp"
            return "temp"
        }

        override fun writeTemporary(token: String, bytes: ByteArray) {
            operations += "write"
        }

        override fun fsyncTemporary(token: String) { operations += "fsync" }

        override fun renameTemporary(token: String, runId: String): String {
            operations += "rename"
            return "attribution-v2/$runId/observation.json"
        }

        override fun fsyncDirectory(runId: String) { operations += "directory-fsync" }

        override fun reopen(relativePath: String): ByteArray {
            operations += "reopen"
            return "{}".encodeToByteArray()
        }
    }

    private data class SourceFrame(
        override val timestampNs: Long,
        override val aeTargetFpsRange: com.calisthenics.diagnostic.study.AttributionV2FpsRange? =
            com.calisthenics.diagnostic.study.AttributionV2FpsRange(30, 30),
    ) : M05BAttributionV2SourceFrame

}

private fun successfulBackendRun(
    warmupCompletions: Int = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS,
    warmupElapsedNs: Long = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
    endToEnd: AttributionV2EndToEndRun? = null,
) = BackendRunResult(
    elapsedNs = 20_000_000_000L,
    stoppedByCap = false,
    warmupCompletions = warmupCompletions,
    warmupElapsedNs = warmupElapsedNs,
    measuredServiceDurationsNs = List(100) { 1_000_000L },
    inferenceFailures = 0,
    endToEndRun = endToEnd,
)

private fun validEndToEndRun() = AttributionV2EndToEndRun(
    cadenceFps = 10,
    sessionReference = "exports/run",
    initialThermalStatus = 0,
    finalThermalStatus = 0,
    deliveredCallbackIds = listOf(0),
    terminalCallbacks = listOf(
        AttributionV2Callback(
            callbackId = 0,
            captureTimestampNs = 0,
            productionClassification = LiveFrameClassification.SUBMIT,
            cadenceDue = true,
            slotAvailable = true,
            terminalClass = AttributionV2TerminalClass.ADMITTED_COMPLETED,
        ),
    ),
    submittedInferenceIds = listOf(0),
    pendingInferenceIds = emptySet(),
    measuredSpanNs = 1_000_000_000L,
)
