package com.calisthenics.diagnostic.study

import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.export.DefaultDurableSessionWriter
import com.calisthenics.diagnostic.export.DurableArtifact
import com.calisthenics.diagnostic.export.DurableStorageException
import com.calisthenics.diagnostic.export.DurableWriterState
import com.calisthenics.diagnostic.export.FakeStorage
import com.calisthenics.diagnostic.export.StoragePort
import com.calisthenics.diagnostic.export.StagingAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M05BEnvelopeAcceptanceTest {
    @Test
    fun ambientAttestationIsRejectedBeforeBaselineAndMustBindTheIssuedChallenge() {
        assertEquals(
            listOf("AMBIENT.ATTESTATION_PRE_SUPPLIED"),
            M05BPostBaselineAttestation.beginBaseline("constant").blockingCodes,
        )
        assertEquals(
            emptyList(),
            M05BPostBaselineAttestation.beginBaseline(null).blockingCodes,
        )

        val wrongChallenge = M05BPostBaselineAttestation.complete(
            expectedChallenge = "a".repeat(64),
            suppliedChallenge = "b".repeat(64),
            suppliedAttestation = "constant",
        )
        assertEquals(listOf("AMBIENT.ATTESTATION_CHALLENGE_MISMATCH"), wrongChallenge.blockingCodes)

        val completed = M05BPostBaselineAttestation.complete(
            expectedChallenge = "a".repeat(64),
            suppliedChallenge = "a".repeat(64),
            suppliedAttestation = "constant",
        )
        assertEquals(M05BAmbientConstancyAttestation.CONSTANT, completed.attestation)
        assertEquals(emptyList(), completed.blockingCodes)
    }

    @Test
    fun observedStartCanonicalJsonRoundTripsPresentAndAbsentBatteryObservation() {
        for (battery in listOf(M05BBatteryObservation("percent", 79.0), null)) {
            val observed = observedStart().copy(batteryObservation = battery)
            val exact = M05BArtifactCodec.canonicalBytes(
                M05BArtifactCodec.canonicalObservedStart(observed),
            )

            if (battery != null) {
                assertTrue(
                    exact.decodeToString().contains(
                        "\"battery_observation\":{\"unit\":\"percent\",\"value\":79.0}",
                    ),
                    exact.decodeToString(),
                )
            }
            assertEquals(observed, M05BArtifactCodec.decodeObservedStart(exact))
        }
    }

    @Test
    fun baselineConsumesAllThreeHundredSamplesAndUsesEndOperatorAttestation() {
        val clock = FakeBaselineClock()
        var samples = 0

        val baseline = M05BIdleBaselineSampler.collect(clock) {
            samples++
            observedStart(batteryTemperatureC = 29.0 + samples / 1_000.0)
        }
        val reduction = M05BBaselineReducer.reduce(
            samples = baseline,
            ambientToken = "ambient-01",
            ambientConstancyAttestation = M05BAmbientConstancyAttestation.CONSTANT,
            order = executionOrder(),
            producerGitSha = "d".repeat(40),
        )

        assertEquals(300, samples)
        assertEquals(300_000_000_000L, clock.deadlines.last())
        val frozen = assertIs<M05BBaselineReduction.Frozen>(reduction)
        assertEquals(29.001, frozen.envelope.batteryTemperatureMinC)
        assertEquals(29.3, frozen.envelope.batteryTemperatureMaxC)
        assertEquals(0.299, frozen.envelope.batteryTemperatureRangeC, 0.000_000_1)
        assertEquals("constant", frozen.finalObservedStart.ambientConstancy)
        assertTrue(frozen.finalObservedStart.ambientConstancyOperatorSupplied == true)
    }

    @Test
    fun unavailableBaselineValueOrMissingOrChangedOperatorAttestationCannotFreeze() {
        val valid = List(300) { observedStart() }
        val unavailable = valid.toMutableList().also {
            it[117] = it[117].copy(brightnessRaw = null)
        }

        val unavailableResult = M05BBaselineReducer.reduce(
            unavailable,
            "ambient-01",
            M05BAmbientConstancyAttestation.CONSTANT,
            executionOrder(),
            "d".repeat(40),
        )
        assertIs<M05BBaselineReduction.Blocked>(unavailableResult)
        assertTrue("DISPLAY.BRIGHTNESS_UNAVAILABLE" in unavailableResult.blockingCodes)

        for (attestation in listOf(null, M05BAmbientConstancyAttestation.CHANGED)) {
            val result = M05BBaselineReducer.reduce(
                valid,
                "ambient-01",
                attestation,
                executionOrder(),
                "d".repeat(40),
            )
            assertIs<M05BBaselineReduction.Blocked>(result)
            assertTrue(result.blockingCodes.any { it.startsWith("AMBIENT.") })
        }
    }

    @Test
    fun anyMidBaselineLoadBearingDriftBlocksWithTypedChangedCode() {
        val mutations = listOf<Pair<String, (M05BObservedStart) -> M05BObservedStart>>(
            "IDENTITY.CHANGED" to { it.copy(appSha256 = "0".repeat(64)) },
            "DEVICE.CHANGED" to { it.copy(deviceBuild = "changed-build") },
            "POWER.CHANGED" to { it.copy(powerSaveMode = true) },
            "CONNECTIVITY.CHANGED" to { it.copy(appTransport = "cellular") },
            "THERMAL.STATUS_CHANGED" to { it.copy(thermalStatus = "light") },
            "DISPLAY.CHANGED" to { it.copy(brightnessRaw = 0.6) },
        )
        for ((code, mutation) in mutations) {
            val samples = MutableList(300) { observedStart() }
            samples[149] = mutation(samples[149])
            val result = M05BBaselineReducer.reduce(
                samples,
                "ambient-01",
                M05BAmbientConstancyAttestation.CONSTANT,
                executionOrder(),
                "d".repeat(40),
            )
            val blocked = assertIs<M05BBaselineReduction.Blocked>(result)
            assertTrue(code in blocked.blockingCodes, code)
        }
    }

    @Test
    fun absentTaggedBatteryObservationAloneDoesNotBlockFreezeOrAdmission() {
        val samples = List(300) { observedStart().copy(batteryObservation = null) }
        val frozen = assertIs<M05BBaselineReduction.Frozen>(
            M05BBaselineReducer.reduce(
                samples,
                "ambient-01",
                M05BAmbientConstancyAttestation.CONSTANT,
                executionOrder(),
                "d".repeat(40),
            ),
        )
        assertEquals(null, frozen.envelope.baselineBatteryObservation)

        val (envelopeArtifact, orderArtifact) = boundArtifacts(
            orderPath = frozen.envelope.orderPath,
            envelopeOverride = frozen.envelope,
        )
        val writer = DefaultDurableSessionWriter(AdmissionRecordingStorage(), sessionId())
        val outcome = M05BDurableAdmissionBoundary(writer).admitAndStart(
            envelopeArtifact,
            orderArtifact,
            frozen.finalObservedStart.copy(batteryObservation = null),
            emptyList(),
            "empty_scene",
            true,
        ) {}
        assertTrue(outcome.result.admitted)
    }

    @Test
    fun deviceUnitDigestIsDomainSeparatedFromAnUndecoratedHash() {
        val derived = M05BDeviceUnitDigest.derive("raw-android-id", "a".repeat(64))
        val wrongDomain = M05BDeviceUnitDigest.deriveForDomain(
            "wrong-domain/v1",
            "raw-android-id",
            "a".repeat(64),
        )

        assertTrue(derived.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(derived != wrongDomain)
    }

    @Test
    fun cooldownUsesScalarTemperatureExactStatusAndReleasedFiniteSchedule() {
        assertEquals("none", M05BThermalStatus.fromAndroid(0))
        assertEquals("light", M05BThermalStatus.fromAndroid(1))
        assertEquals("moderate", M05BThermalStatus.fromAndroid(2))
        assertEquals(null, M05BThermalStatus.fromAndroid(3))

        val clock = FakeBaselineClock()
        var reads = 0
        val result = M05BCooldownSampler.awaitReady(clock, envelope()) {
            reads++
            observedStart(
                thermalStatus = if (reads == 1) "light" else "none",
                batteryTemperatureC = if (reads == 2) 31.0 else 29.5,
            )
        }

        assertIs<M05BCooldownResult.Ready>(result)
        assertEquals(3, reads)
        assertEquals(2_000_000_000L, clock.deadlines.last())

        val timeoutClock = FakeBaselineClock()
        var timeoutReads = 0
        val timeout = M05BCooldownSampler.awaitReady(timeoutClock, envelope()) {
            timeoutReads++
            observedStart(batteryTemperatureC = 31.0)
        }
        assertIs<M05BCooldownResult.Blocked>(timeout)
        assertEquals(listOf("THERMAL.COOLDOWN_TIMEOUT"), timeout.blockingCodes)
        assertEquals(600, timeoutReads)
        assertEquals(599_000_000_000L, timeoutClock.deadlines.last())
    }

    @Test
    fun latencySummaryReportsNearestRankContainingBucketBoundsWithoutInterpolation() {
        val summary = M05BLatencyProtocol.summarize(
            listOf(500_000L, 1_500_000L, 3_000_000L, 9_000_000L),
        )

        assertEquals(M05BLatencyBounds(1_000_001L, 2_000_000L), summary.getValue(50))
        assertEquals(M05BLatencyBounds(8_000_001L, 16_000_000L), summary.getValue(95))
        assertEquals(summary.getValue(95), summary.getValue(99))
        assertEquals(13, M05BLatencyProtocol.BUCKET_UPPER_BOUNDS_NS.size)
    }

    @Test
    fun durableAdmissionOrdersOpenAppendFlushFsyncBeforeCaptureAndRejectsDuplicate() {
        val storage = AdmissionRecordingStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        val boundary = M05BDurableAdmissionBoundary(writer)
        val (envelopeArtifact, orderArtifact) = boundArtifacts(
            envelopePath = "docs/evidence/m05b-envelope/envelope.json",
            orderPath = "docs/evidence/m05b-envelope/execution-order.json",
        )

        val outcome = boundary.admitAndStart(
            envelopeArtifact = envelopeArtifact,
            orderArtifact = orderArtifact,
            observedStart = observedStart(),
            attemptHistory = emptyList(),
            inputClass = "empty_scene",
            operatorAttestedNoPerson = true,
        ) { storage.events += "capture:start" }

        assertTrue(outcome.result.admitted)
        assertEquals(
            listOf("attempt:open", "append:RUN_ADMISSION", "flush:RUN_ADMISSION", "fsync:RUN_ADMISSION", "capture:start"),
            storage.events,
        )
        assertEquals(
            outcome.admissionBytes.toList(),
            storage.delegate.attempts.single().durableArtifactBytes.getValue(DurableArtifact.RUN_ADMISSION).toList(),
        )
        assertEquals(DurableWriterState.OPEN, writer.state)
        assertFailsWith<IllegalStateException> {
            boundary.admitAndStart(
                envelopeArtifact,
                orderArtifact,
                observedStart(),
                emptyList(),
                "empty_scene",
                true,
            ) { storage.events += "capture:duplicate" }
        }
        assertFalse("capture:duplicate" in storage.events)
    }

    @Test
    fun trustedBlockedAdmissionIsDurableThenAbortsWithoutCapture() {
        val storage = AdmissionRecordingStorage()
        val writer = DefaultDurableSessionWriter(storage, sessionId())
        val boundary = M05BDurableAdmissionBoundary(writer)
        val (envelopeArtifact, orderArtifact) = boundArtifacts()

        val outcome = boundary.admitAndStart(
            envelopeArtifact,
            orderArtifact,
            observedStart(powerSaveMode = true),
            emptyList(),
            "empty_scene",
            true,
        ) { storage.events += "capture:start" }

        assertFalse(outcome.result.admitted)
        assertTrue("POWER.SAVE_MODE" in outcome.result.blockingCodes)
        assertEquals(
            listOf(
                "attempt:open",
                "append:RUN_ADMISSION",
                "flush:RUN_ADMISSION",
                "fsync:RUN_ADMISSION",
                "attempt:abort",
            ),
            storage.events,
        )
        assertEquals(DurableWriterState.ABORTED, writer.state)
        assertFalse("capture:start" in storage.events)
    }

    @Test
    fun admissionStorageFailuresLeaveCrashResidueAndNeverCapture() {
        for (failure in listOf("append:RUN_ADMISSION", "flush:RUN_ADMISSION", "fsync:RUN_ADMISSION")) {
            val storage = AdmissionRecordingStorage(failOn = failure)
            val writer = DefaultDurableSessionWriter(storage, sessionId())
            var captured = false
            val (envelopeArtifact, orderArtifact) = boundArtifacts()

            assertFailsWith<DurableStorageException>(failure) {
                M05BDurableAdmissionBoundary(writer).admitAndStart(
                    envelopeArtifact,
                    orderArtifact,
                    observedStart(),
                    emptyList(),
                    "empty_scene",
                    true,
                ) { captured = true }
            }

            assertFalse(captured, failure)
            assertEquals(DurableWriterState.FAILED, writer.state, failure)
            assertEquals(null, storage.delegate.attempts.single().abortMarker, failure)
        }
    }

    @Test
    fun exactByteDigestAndTypedValueMutationsBlockBeforeCapture() {
        val (original, order) = boundArtifacts()
        val staleTyped = original.copy(typed = original.typed.copy(appSha256 = "0".repeat(64)))
        val changedBytes = original.exactBytes.decodeToString().replace("\"CPU\"", "\"GPU\"").encodeToByteArray()
        val digestMutated = original.copy(typed = original.typed.copy(delegate = "GPU"), exactBytes = changedBytes)

        for ((artifact, expectedCode) in listOf(
            staleTyped to "ARTIFACT.ENVELOPE_TYPED_MISMATCH",
            digestMutated to "ARTIFACT.ENVELOPE_DIGEST",
        )) {
            val writer = DefaultDurableSessionWriter(AdmissionRecordingStorage(), sessionId())
            var captured = false
            val result = M05BDurableAdmissionBoundary(writer).admitAndStart(
                artifact,
                order,
                observedStart(),
                emptyList(),
                "empty_scene",
                true,
            ) { captured = true }
            assertFalse(result.result.admitted)
            assertTrue(expectedCode in result.result.blockingCodes)
            assertFalse(captured)
        }
    }

    @Test
    fun immutableAttemptChainDerivesCadenceAndRejectsReplacementOrReordering() {
        val completedTen = M05BAttemptChain.bind(
            M05BAttemptEntry(1, 10, "a".repeat(64), M05BAttemptOutcome.COMPLETED, null),
        )
        val context = M05BAttemptChain.derive(executionOrder(), listOf(completedTen))
        assertEquals(2, context.attemptSequence)
        assertEquals(15, context.cadenceFps)
        assertEquals(M05BPredecessorStatus.COMPLETED, context.predecessorStatus)
        assertEquals(emptyList(), context.blockingCodes)

        val blockedTen = M05BAttemptChain.bind(
            M05BAttemptEntry(1, 10, "b".repeat(64), M05BAttemptOutcome.BLOCKED, null),
        )
        assertEquals(10, M05BAttemptChain.derive(executionOrder(), listOf(blockedTen)).cadenceFps)

        val completedFifteen = M05BAttemptChain.bind(
            M05BAttemptEntry(2, 15, "c".repeat(64), M05BAttemptOutcome.COMPLETED, completedTen.sha256),
        )
        assertTrue(completedFifteen.exactBytes.decodeToString().contains(completedTen.sha256))
        val replaced = completedTen.copy(exactBytes = completedTen.exactBytes.copyOf().also { it[it.lastIndex - 1] = '1'.code.toByte() })
        assertTrue(M05BAttemptChain.derive(executionOrder(), listOf(replaced)).blockingCodes.isNotEmpty())
        assertTrue(M05BAttemptChain.derive(executionOrder(), listOf(completedFifteen, completedTen)).blockingCodes.isNotEmpty())

        val transportLost = M05BAttemptChain.bind(
            M05BAttemptEntry(1, 10, "d".repeat(64), M05BAttemptOutcome.TRANSPORT_LOST, null),
        )
        assertTrue(
            "ATTEMPT.TRANSPORT_RECOVERY_REQUIRED" in
                M05BAttemptChain.derive(executionOrder(), listOf(transportLost)).blockingCodes,
        )

        val completedThirty = M05BAttemptChain.bind(
            M05BAttemptEntry(3, 30, "d".repeat(64), M05BAttemptOutcome.COMPLETED, completedFifteen.sha256),
        )
        val complete = M05BAttemptChain.derive(executionOrder(), listOf(completedTen, completedFifteen, completedThirty))
        assertEquals(0, complete.cadenceFps)
        assertTrue("ATTEMPT.ORDER_COMPLETE" in complete.blockingCodes)
    }

    private fun executionOrder() = M05BExecutionOrder(
        orderId = "order-01",
        cadencesFps = listOf(10, 15, 30),
        producerGitSha = "d".repeat(40),
    )

    private fun boundArtifacts(
        envelopePath: String = "envelope.json",
        orderPath: String = "order.json",
        envelopeOverride: M05BStartEnvelope? = null,
    ): Pair<M05BBoundArtifact<M05BStartEnvelope>, M05BBoundArtifact<M05BExecutionOrder>> {
        val orderArtifact = M05BArtifactCodec.bindOrder(executionOrder(), orderPath)
        val envelopeArtifact = M05BArtifactCodec.bindEnvelope(
            envelopeOverride ?: envelope(orderPath = orderPath, orderSha256 = orderArtifact.sha256),
            envelopePath,
        )
        return envelopeArtifact to orderArtifact
    }

    private fun envelope(
        orderPath: String = "order.json",
        orderSha256: String = M05BArtifactCodec.bindOrder(executionOrder(), orderPath).sha256,
    ) = M05BStartEnvelope(
        envelopeId = "m05b-envelope-01",
        procedureVersion = "1.0.0",
        procedureSha256 = "e".repeat(64),
        appSha256 = "a".repeat(64),
        modelSha256 = "b".repeat(64),
        runtimeVersion = "mediapipe-tasks-vision-1.0.0",
        delegate = "CPU",
        preprocessingVersion = "1.0.0",
        deviceUnitDigest = "c".repeat(64),
        deviceClass = "physical_device",
        manufacturer = "samsung",
        model = "SM-S938B",
        deviceBuild = "build",
        apiLevel = 36,
        charging = false,
        powerSource = "battery",
        hostBatteryOverrideRejected = true,
        powerSaveMode = false,
        baselineBatteryObservation = M05BBatteryObservation("percent", 80.0),
        hostAdbTransport = "wifi",
        appTransport = "wifi",
        networkIdentityPersisted = false,
        ambientToken = "ambient-01",
        ambientConstancy = "constant",
        ambientConstancySource = "operator_attestation_end_of_baseline",
        keepAwake = true,
        brightnessMode = "manual",
        brightnessRaw = 0.5,
        brightnessScaleMin = 0.0,
        brightnessScaleMax = 1.0,
        cameraFps = 30,
        inferenceCadencesFps = listOf(10, 15, 30),
        durationNs = 1_500_000_000_000L,
        overshootNs = 1_000_000_000L,
        cooldownMaxDurationNs = 600_000_000_000L,
        cooldownSamplingIntervalNs = 1_000_000_000L,
        thermalStatus = "none",
        batteryTemperatureMinC = 29.0,
        batteryTemperatureMaxC = 30.0,
        batteryTemperatureRangeC = 1.0,
        orderPath = orderPath,
        orderSha256 = orderSha256,
        producerGitSha = "d".repeat(40),
    )

    private fun observedStart(
        powerSaveMode: Boolean? = false,
        thermalStatus: String? = "none",
        batteryTemperatureC: Double? = 29.5,
    ) = M05BObservedStart(
        appSha256 = "a".repeat(64),
        modelSha256 = "b".repeat(64),
        runtimeVersion = "mediapipe-tasks-vision-1.0.0",
        delegate = "CPU",
        preprocessingVersion = "1.0.0",
        deviceUnitDigest = "c".repeat(64),
        deviceClass = "physical_device",
        manufacturer = "samsung",
        model = "SM-S938B",
        deviceBuild = "build",
        apiLevel = 36,
        charging = false,
        powerSource = "battery",
        hostBatteryOverrideRejected = true,
        powerSaveMode = powerSaveMode,
        batteryObservation = M05BBatteryObservation("percent", 79.0),
        deviceIdleMode = false,
        hostAdbTransport = "wifi",
        appTransport = "wifi",
        networkIdentityPersisted = false,
        ambientToken = "ambient-01",
        ambientConstancy = "constant",
        ambientConstancyOperatorSupplied = true,
        keepAwake = true,
        brightnessMode = "manual",
        brightnessRaw = 0.5,
        brightnessScaleMin = 0.0,
        brightnessScaleMax = 1.0,
        thermalStatus = thermalStatus,
        batteryTemperatureC = batteryTemperatureC,
    )

    private fun sessionId() = Uuid("00000000-0000-4000-8000-000000000042")
}

private class FakeBaselineClock : M05BBaselineClock {
    private var now = 0L
    val deadlines = mutableListOf<Long>()

    override fun nowNs(): Long = now

    override fun sleepUntilNs(deadlineNs: Long) {
        deadlines += deadlineNs
        now = deadlineNs
    }
}

private class AdmissionRecordingStorage(
    private val failOn: String? = null,
    val delegate: FakeStorage = FakeStorage(),
) : StoragePort by delegate {
    val events = mutableListOf<String>()

    private fun record(event: String) {
        events += event
        if (event == failOn) throw IllegalStateException("injected $event failure")
    }

    override fun createStagingAttempt(sessionId: Uuid): StagingAttempt {
        record("attempt:open")
        return delegate.createStagingAttempt(sessionId)
    }

    override fun appendArtifact(attempt: StagingAttempt, artifact: DurableArtifact, bytes: ByteArray) {
        record("append:$artifact")
        delegate.appendArtifact(attempt, artifact, bytes)
    }

    override fun flushArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        record("flush:$artifact")
        delegate.flushArtifact(attempt, artifact)
    }

    override fun fsyncArtifact(attempt: StagingAttempt, artifact: DurableArtifact) {
        record("fsync:$artifact")
        delegate.fsyncArtifact(attempt, artifact)
    }

    override fun writeAbortMarker(attempt: StagingAttempt, reason: String, sealedCounters: Counts) {
        record("attempt:abort")
        delegate.writeAbortMarker(attempt, reason, sealedCounters)
    }
}
