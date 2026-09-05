package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.live.LiveFrameClassification
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class M05BAttributionV2JsonTest {
    @Test
    fun rendererEmitsClosedSanitizedObservationWithSortedDerivedBlockers() {
        val observation = blockedObservation()

        val rendered = renderM05BAttributionV2Evidence(observation)

        assertContains(rendered, "\"record_kind\":\"m05b_attribution_v2_device_observation\"")
        assertContains(rendered, "\"sanitized\":true")
        assertContains(rendered, "\"non_participant_observation\":true")
        assertContains(rendered, "\"blocking_codes\":[\"ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT\"")
        assertContains(rendered, "\"production_classification\":\"cadence_selection\"")
        assertContains(rendered, "\"terminal_class\":\"cadence_only\"")
        assertFalse(rendered.contains("participant_id", ignoreCase = true))
        assertFalse(rendered.contains("device_serial", ignoreCase = true))
        assertFalse(rendered.contains("image_bytes", ignoreCase = true))
        assertFalse(rendered.contains("landmark", ignoreCase = true))
    }

    @Test
    fun rendererRejectsCallerDeclaredBlockersThatDisagreeWithDerivation() {
        val observation = blockedObservation().copy(blockingCodes = emptyList())

        assertFailsWith<IllegalArgumentException> {
            renderM05BAttributionV2Evidence(observation)
        }
    }

    @Test
    fun rendererRejectsAbsoluteSessionReferences() {
        val replicate = blockedObservation().endToEndReplicates.single()
        val unsafe = replicate.copy(
            runs = replicate.runs.mapIndexed { index, run ->
                if (index == 0) run.copy(sessionReference = "/data/user/0/private") else run
            },
        )

        assertFailsWith<IllegalArgumentException> {
            renderM05BAttributionV2Evidence(
                blockedObservation().copy(endToEndReplicates = listOf(unsafe)),
            )
        }
    }

    @Test
    fun rendererRejectsSourceArraysBeyondTheProtocolMaximum() {
        val source = blockedObservation().sourceArm.copy(
            measuredTimestampsNs = List(M05BAttributionV2Protocol.SOURCE_MEASURED_CALLBACKS + 1) { it.toLong() },
        )

        assertFailsWith<IllegalArgumentException> {
            renderM05BAttributionV2Evidence(blockedObservation().copy(sourceArm = source))
        }
    }

    @Test
    fun rendererIncludesRawBackendCallbackAndPhaseEvidence() {
        val backend = AttributionV2BackendObservation(
            status = AttributionV2ArmStatus.BLOCKED,
            blockingCodes = emptyList(),
            warmupCount = 20,
            warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
            measuredServiceDurationsNs = List(100) { 1_000_000L },
            inferenceFailures = 0,
            thermalStatus = 0,
            armStartedNs = 0L,
            deliveredCallbackIds = listOf(7L),
            terminalCallbacks = listOf(
                AttributionV2Callback(
                    callbackId = 7L,
                    captureTimestampNs = 123_000_000L,
                    productionClassification = LiveFrameClassification.SUBMIT,
                    cadenceDue = true,
                    slotAvailable = true,
                    terminalClass = AttributionV2TerminalClass.ADMITTED_COMPLETED,
                    phase = AttributionV2CallbackPhase.WARMUP,
                    completionTimestampNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
                ),
            ),
            submittedInferenceIds = listOf(7L),
        )

        val withBackend = blockedObservation().copy(backendArm = backend)
        assertEquals(backend, withBackend.backendArm)
        val rendered = renderM05BAttributionV2Evidence(
            withBackend.copy(blockingCodes = deriveM05BAttributionV2BlockingCodes(withBackend)),
        )

        assertContains(rendered, "\"delivered_callback_ids\"")
        assertContains(rendered, "\"terminal_callbacks\"")
        assertContains(rendered, "\"phase\"")
        assertContains(rendered, "\"warmup_predicates\"")
    }

    @Test
    fun droppedRawBackendCallbackDataDerivesTerminalClosureBlocker() {
        val backend = AttributionV2BackendObservation(
            status = AttributionV2ArmStatus.BLOCKED,
            blockingCodes = emptyList(),
            warmupCount = 20,
            warmupElapsedNs = M05BAttributionV2Protocol.BACKEND_WARMUP_ELAPSED_NS,
            measuredServiceDurationsNs = List(100) { 1_000_000L },
            inferenceFailures = 0,
            thermalStatus = 0,
            armStartedNs = 0L,
            deliveredCallbackIds = listOf(7L),
            terminalCallbacks = emptyList(),
            submittedInferenceIds = listOf(7L),
        )
        val observation = blockedObservation().copy(
            sourceArm = blockedObservation().sourceArm.copy(initialCooldownRecovered = true),
            backendArm = backend,
        )

        assertContains(
            deriveM05BAttributionV2BlockingCodes(observation),
            "ATTRIBUTION.TERMINAL_CLOSURE",
        )
    }

    @Test
    fun callbackIdentifiersAreScopedToEachFreshCompositionRun() {
        val observation = blockedObservation()
        val replicate = observation.endToEndReplicates.single()
        val secondRun = replicate.runs.single().copy(
            cadenceFps = 15,
            sessionReference = "exports/00000000-0000-4000-8000-000000000002",
        )

        renderM05BAttributionV2Evidence(
            observation.copy(
                endToEndReplicates = listOf(replicate.copy(runs = replicate.runs + secondRun)),
            ),
        )
    }

    private fun blockedObservation(): M05BAttributionV2DeviceObservation {
        val callback = AttributionV2Callback(
            callbackId = 7,
            captureTimestampNs = 123_000_000,
            productionClassification = LiveFrameClassification.CADENCE_SELECTION,
            cadenceDue = false,
            slotAvailable = true,
            terminalClass = AttributionV2TerminalClass.CADENCE_ONLY,
        )
        val run = AttributionV2EndToEndRun(
            cadenceFps = 10,
            sessionReference = "exports/00000000-0000-4000-8000-000000000001",
            initialThermalStatus = 0,
            finalThermalStatus = 0,
            deliveredCallbackIds = listOf(7),
            terminalCallbacks = listOf(callback),
            submittedInferenceIds = emptyList(),
            pendingInferenceIds = emptySet(),
            measuredSpanNs = 1_000_000_000,
        )
        return M05BAttributionV2DeviceObservation(
            protocolSha256 = "a".repeat(64),
            device = AttributionV2DeviceSource(
                deviceClass = "physical_device",
                deviceIdentity = "samsung/SM-S938B/pa3q/pa3qxins",
                manufacturer = "samsung",
                model = "SM-S938B",
                apiLevel = 36,
                androidRelease = "16",
                osBuild = "BP1A.250605.004",
            ),
            identity = AttributionV2RunIdentity(
                appGitSha = "b".repeat(40),
                probeGitSha = "b".repeat(40),
                appApkSha256 = "c".repeat(64),
                testApkSha256 = "d".repeat(64),
                modelSha256 = "e".repeat(64),
                runtimeVersion = "16",
                backendVersion = "1.0.0",
                delegate = "cpu",
            ),
            sourceArm = AttributionV2SourceObservation(
                status = AttributionV2ArmStatus.RECORDED,
                blockingCodes = emptyList(),
                warmupCount = 30,
                measuredTimestampsNs = List(300) { it * 33_333_333L },
                observedAeTargetFps = AttributionV2FpsRange(30, 30),
                thermalStatus = 0,
            ),
            backendArm = null,
            selectorArm = null,
            endToEndReplicates = listOf(
                AttributionV2ReplicateObservation(
                    order = listOf(10, 15, 30),
                    status = AttributionV2ArmStatus.BLOCKED,
                    blockingCodes = listOf("ATTRIBUTION.REPLICATE_DISAGREEMENT"),
                    cooldownThermalStatuses = listOf(0),
                    runs = listOf(run),
                ),
            ),
            blockingCodes = listOf(
                "ATTRIBUTION.REPLICATE_DISAGREEMENT",
                "ATTRIBUTION.BACKEND_SAMPLE_INSUFFICIENT",
                "ATTRIBUTION.SELECTOR_DIVERGENCE",
            ),
        )
    }
}
