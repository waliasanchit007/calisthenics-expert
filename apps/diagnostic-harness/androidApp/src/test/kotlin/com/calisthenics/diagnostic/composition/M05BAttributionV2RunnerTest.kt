package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.study.AttributionV2DeviceSource
import com.calisthenics.diagnostic.study.AttributionV2EndToEndRun
import com.calisthenics.diagnostic.study.AttributionV2Callback
import com.calisthenics.diagnostic.study.AttributionV2CallbackPhase
import com.calisthenics.diagnostic.study.AttributionV2ArmStatus
import com.calisthenics.diagnostic.live.LiveFrameClassification
import com.calisthenics.diagnostic.live.LiveAdmissionPredicates
import com.calisthenics.diagnostic.study.AttributionV2RunIdentity
import com.calisthenics.diagnostic.study.AttributionV2TerminalClass
import com.calisthenics.diagnostic.study.deriveM05BAttributionV2BlockingCodes
import com.calisthenics.diagnostic.study.renderM05BAttributionV2Evidence
import com.calisthenics.diagnostic.telemetry.CallbackTerminalDisposition
import com.calisthenics.diagnostic.study.M05BAttributionV2Protocol
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun backendArmPublishesBoundedRawCallbackIdsTerminalsAndPhases() {
        val result = runner(
            source = RecordingSource(measuredFrames()),
            backendFactory = { RecordingBackend.successfulCapacity() },
        ).run("raw-backend-evidence")
        val backend = checkNotNull(result.observation.backendArm)

        assertEquals(120, backend.deliveredCallbackIds.size)
        assertEquals(120, backend.terminalCallbacks.size)
        assertEquals(20, backend.terminalCallbacks.count { it.phase == AttributionV2CallbackPhase.WARMUP })
        assertEquals(100, backend.terminalCallbacks.count { it.phase == AttributionV2CallbackPhase.MEASURED })
        assertEquals(0, backend.inferenceFailures)
        assertTrue(backend.pendingInferenceIds.isEmpty())
    }

    @Test
    fun concreteBackendResultAssemblyPreservesTerminalCallbacksAndPhases() {
        val admissions = listOf(
            observedAdmission(11L),
            observedAdmission(12L),
        )
        val terminals = listOf(
            AttributionV2ObservedTerminal(
                callbackId = 11L,
                terminal = CallbackTerminalDisposition.INFERENCE_COMPLETED,
                completedNs = 2_000L,
                phase = AttributionV2CallbackPhase.WARMUP,
            ),
            AttributionV2ObservedTerminal(
                callbackId = 12L,
                terminal = CallbackTerminalDisposition.INFERENCE_COMPLETED,
                completedNs = 3_000L,
                phase = AttributionV2CallbackPhase.MEASURED,
            ),
        )

        val result = assembleAndroidM05BAttributionV2BackendResult(
            result = BackendRunResult(
                elapsedNs = 1L,
                stoppedByCap = false,
                warmupCompletions = 1,
                warmupElapsedNs = 1L,
                measuredServiceDurationsNs = listOf(1L),
                inferenceFailures = 0,
            ),
            admissions = admissions,
            terminals = terminals,
            cadenceFps = null,
            byId = admissions.associateBy { it.callbackId },
        )
        val callbacks = result.terminalCallbacks

        assertEquals(listOf(11L, 12L), callbacks.map { it.callbackId })
        assertEquals(
            listOf(AttributionV2CallbackPhase.WARMUP, AttributionV2CallbackPhase.MEASURED),
            callbacks.map { it.phase },
        )
    }

    @Test
    fun endToEndRunDeclaresMeasuredOnlyCaptureSpan() {
        val callbacks = listOf(
            testCallback(0L, 0L, AttributionV2CallbackPhase.WARMUP),
            testCallback(1L, 10L, AttributionV2CallbackPhase.MEASURED),
            testCallback(2L, 30L, AttributionV2CallbackPhase.MEASURED),
        )

        assertEquals(20L, attributionV2MeasuredSpanNs(callbacks))
    }

    @Test
    fun nonInferenceTerminalObservedDuringWarmupSerializesWarmupPhase() {
        val phaseMachine = M05BAttributionV2BackendPhaseMachine(
            warmupCompletions = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS,
            warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
            measuredCompletions = M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS,
        )
        val observation = AttributionV2Observation { terminal, completedNs ->
            phaseMachine.phaseAtTerminal(terminal, completedNs)
        }
        (0L until M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS).forEach { callbackId ->
            observation.onAdmission(
                callbackId = callbackId,
                captureTimestampNs = callbackId,
                observedSessionNs = callbackId,
                productionClassification = LiveFrameClassification.SUBMIT,
                productionPredicates = LiveAdmissionPredicates(cadenceDue = true, slotAvailable = true),
            )
            observation.onTerminal(
                callbackId = callbackId,
                terminal = CallbackTerminalDisposition.INFERENCE_COMPLETED,
                completedNs = 1_000_000_000L + callbackId,
            )
        }
        observation.onAdmission(
            callbackId = 900L,
            captureTimestampNs = 1_000_000_000L,
            observedSessionNs = 1_000_000_000L,
            productionClassification = LiveFrameClassification.CADENCE_SELECTION,
            productionPredicates = LiveAdmissionPredicates(cadenceDue = false, slotAvailable = true),
        )
        observation.onTerminal(
            callbackId = 900L,
            terminal = CallbackTerminalDisposition.CADENCE_SELECTION,
            completedNs = 1_500_000_000L,
        )

        val terminal = rawCallbacksFromObservedTerminals(observation).single { it.callbackId == 900L }
        assertEquals(AttributionV2CallbackPhase.WARMUP, terminal.phase)
        assertEquals(1_500_000_000L, terminal.completionTimestampNs)

        val base = runner(
            source = RecordingSource(measuredFrames()),
            backendFactory = { RecordingBackend.successfulCapacity() },
        ).run("phase-serialization").observation
        val baseBackend = checkNotNull(base.backendArm)
        val augmented = base.copy(
            backendArm = baseBackend.copy(
                deliveredCallbackIds = baseBackend.deliveredCallbackIds + terminal.callbackId,
                terminalCallbacks = baseBackend.terminalCallbacks + terminal,
            ),
        )
        assertEquals(AttributionV2CallbackPhase.WARMUP, augmented.backendArm!!.terminalCallbacks.last().phase)
        assertEquals(1_500_000_000L, augmented.backendArm!!.terminalCallbacks.last().completionTimestampNs)
        val rendered = renderM05BAttributionV2Evidence(
            augmented.copy(blockingCodes = deriveM05BAttributionV2BlockingCodes(augmented)),
        )
        val serialized = Json.parseToJsonElement(rendered).jsonObject["backend_arm"]!!.jsonObject
            .getValue("terminal_callbacks").jsonArray
            .single { it.jsonObject.getValue("callback_id").jsonPrimitive.content.toLong() == 900L }

        assertEquals("warmup", serialized.jsonObject.getValue("phase").jsonPrimitive.content)
    }

    @Test
    fun rawCallbackPreservesExactAdmissionObservedSessionTimestamp() {
        val observation = AttributionV2Observation { _, _ -> AttributionV2CallbackPhase.MEASURED }
        observation.onAdmission(
            callbackId = 901L,
            captureTimestampNs = 123L,
            observedSessionNs = 4_000_000_000L,
            productionClassification = LiveFrameClassification.SUBMIT,
            productionPredicates = LiveAdmissionPredicates(cadenceDue = true, slotAvailable = true),
        )
        observation.onTerminal(
            callbackId = 901L,
            terminal = CallbackTerminalDisposition.INFERENCE_COMPLETED,
            completedNs = 4_001_000_000L,
        )

        val callback = rawCallbacksFromObservedTerminals(observation).single()
        assertEquals(4_000_000_000L, callback.admissionObservedSessionNs)
    }

    @Test
    fun backendRawEvidenceOverflowFailsClosedWithExistingCallbackConservationBlocker() {
        val backend = RecordingBackend(
            successfulBackendRun().copy(rawEvidenceOverflow = true),
        )
        val selector = RecordingSelector()

        val result = runner(
            source = RecordingSource(measuredFrames()),
            backendFactory = { backend },
            selector = selector,
        ).run("raw-overflow")

        assertTrue(result.observation.blockingCodes.contains("ATTRIBUTION.CALLBACK_CONSERVATION"))
        assertEquals(0, selector.invocations)
        assertTrue(result.observation.endToEndReplicates.isEmpty())
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

    @Test
    fun concreteBackendPhaseTransitionRequiresBothWarmupPredicatesBeforeMeasuredCollection() {
        val phase = M05BAttributionV2BackendPhaseMachine(
            warmupCompletions = M05BAttributionV2Protocol.BACKEND_WARMUP_COMPLETIONS,
            warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
            measuredCompletions = M05BAttributionV2Protocol.BACKEND_MEASURED_COMPLETIONS,
        )

        assertEquals(BackendPhase.WARMUP, phase.observe(completions = 20, elapsedNs = 1_999_999_999L).phase)
        assertEquals(BackendPhase.WARMUP, phase.observe(completions = 21, elapsedNs = 1_999_999_999L).phase)
        val transitioned = phase.observe(completions = 21, elapsedNs = 2_000_000_000L)
        assertEquals(BackendPhase.MEASURED, transitioned.phase)
        assertEquals(21, transitioned.warmupCompletionCount)
        assertEquals(0, transitioned.measuredCompletionCount)
        assertEquals(
            100,
            phase.observe(completions = 121, elapsedNs = 3_000_000_000L).measuredCompletionCount,
        )
    }

    @Test
    fun sourceArmFailsClosedWhenCaptureExtendsPastAbsoluteDeadline() {
        val source = RecordingSource(
            frames = measuredFrames(),
            captureResult = SourceCaptureResult(
                elapsedNs = M05BAttributionV2Protocol.ARM_CAP_NS + 5_000_000_000L,
                stoppedByCap = false,
                stopAcknowledged = false,
            ),
        )

        val result = runner(source = source).run("absolute-deadline")

        assertEquals(AttributionV2ArmStatus.BLOCKED, result.observation.sourceArm.status)
        assertTrue(result.observation.blockingCodes.contains("ATTRIBUTION.SOURCE_SAMPLE_INSUFFICIENT"))
        assertTrue(result.observation.endToEndReplicates.isEmpty())
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
            clock = M05BAttributionV2Clock { 0L },
        ),
    )

    private fun measuredFrames() = (0 until 330).map { SourceFrame(it * 33_333_333L) }

    private fun observedAdmission(callbackId: Long) = AttributionV2ObservedAdmission(
        callbackId = callbackId,
        captureTimestampNs = callbackId * 100L,
        observedSessionNs = callbackId * 1_000L,
        productionClassification = LiveFrameClassification.SUBMIT,
        productionPredicates = LiveAdmissionPredicates(cadenceDue = true, slotAvailable = true),
        shadowSelections = emptyMap(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun rawCallbacksFromObservedTerminals(observation: AttributionV2Observation): List<AttributionV2Callback> {
        val converter = Class.forName(
            "com.calisthenics.diagnostic.composition.M05BAttributionV2RunnerKt",
        ).declaredMethods.first { it.name == "toRawCallbacks" }
        converter.isAccessible = true
        val admissions = observation.admissionSnapshot()
        return converter.invoke(
            null,
            admissions,
            observation.terminalSnapshot(),
            null,
            admissions.associateBy { it.callbackId },
        ) as List<AttributionV2Callback>
    }

    private class RecordingSource(
        private val frames: List<SourceFrame>,
        private val hitCap: Boolean = false,
        private val captureResult: SourceCaptureResult? = null,
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
            return captureResult ?: SourceCaptureResult(
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

private fun testCallback(
    callbackId: Long,
    captureTimestampNs: Long,
    phase: AttributionV2CallbackPhase,
) = AttributionV2Callback(
    callbackId = callbackId,
    captureTimestampNs = captureTimestampNs,
    productionClassification = LiveFrameClassification.SUBMIT,
    cadenceDue = true,
    slotAvailable = true,
    terminalClass = AttributionV2TerminalClass.ADMITTED_COMPLETED,
    phase = phase,
    admissionObservedSessionNs = 0L,
    completionTimestampNs = 1L,
)

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
    armStartedNs = 0L,
    deliveredCallbackIds = (0L until 120L).toList(),
    terminalCallbacks = (0L until 120L).map { callbackId ->
        AttributionV2Callback(
            callbackId = callbackId,
            captureTimestampNs = callbackId,
            productionClassification = LiveFrameClassification.SUBMIT,
            cadenceDue = true,
            slotAvailable = true,
            terminalClass = AttributionV2TerminalClass.ADMITTED_COMPLETED,
            phase = if (callbackId < 20L) AttributionV2CallbackPhase.WARMUP else AttributionV2CallbackPhase.MEASURED,
            admissionObservedSessionNs = if (callbackId < 20L) {
                1_999_000_000L + callbackId
            } else {
                2_999_000_000L + callbackId
            },
            completionTimestampNs = if (callbackId < 20L) {
                2_000_000_000L + callbackId
            } else {
                3_000_000_000L + callbackId
            },
        )
    },
    submittedInferenceIds = (0L until 120L).toList(),
    pendingInferenceIds = emptySet(),
)

private fun validEndToEndRun() = AttributionV2EndToEndRun(
    cadenceFps = 10,
    sessionReference = "exports/00000000-0000-4000-8000-000000000099",
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
    armStartedNs = 7_000_000_000L,
    measuredSpanNs = 1_000_000_000L,
)
