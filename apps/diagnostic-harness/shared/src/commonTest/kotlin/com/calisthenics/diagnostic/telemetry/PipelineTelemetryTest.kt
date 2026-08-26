package com.calisthenics.diagnostic.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PipelineTelemetryTest {
    @Test
    fun deliveredCallbackLedgerRequiresExactlyOneDistinctTerminalDispositionPerCallback() {
        val ledger = DeliveredCallbackLedger()
        CallbackTerminalDisposition.entries.forEachIndexed { index, disposition ->
            ledger.begin(index.toLong())
            ledger.close(index.toLong(), disposition)
        }

        assertEquals(CallbackTerminalDisposition.entries.size.toLong(), ledger.deliveredCallbacks)
        assertEquals(0L, ledger.unclosedCallbacks)
        assertEquals(1L, ledger.counts.getValue(CallbackTerminalDisposition.CLOCK_FAULT))
        assertFailsWith<IllegalStateException> { ledger.close(0L, CallbackTerminalDisposition.PREPROCESSING_FAILED) }
    }

    @Test
    fun deliveredCallbackLedgerRejectsMissingClosureWhileAllowingOneInferenceAndOneAnalyzerCallback() {
        val ledger = DeliveredCallbackLedger()
        ledger.begin(0L)
        ledger.begin(1L)
        ledger.close(1L, CallbackTerminalDisposition.CADENCE_SELECTION)

        assertFailsWith<IllegalStateException> { ledger.assertClosed(cameraFramesReceived = 2L) }

        ledger.close(0L, CallbackTerminalDisposition.INFERENCE_COMPLETED)
        ledger.assertClosed(cameraFramesReceived = 2L)
        assertEquals(2L, ledger.deliveredCallbacks)
        assertEquals(0L, ledger.unclosedCallbacks)
    }

    @Test
    fun stageRecorderStreamsContiguousEventsAndDerivesCountsFromThem() {
        val sink = RecordingTelemetrySink()
        val recorder = PipelineStageRecorder(nowNs = { 41L }, sink = sink)

        recorder.record(frameIndex = 7L, stage = PipelineStage.CAMERA_CALLBACK, disposition = PipelineDisposition.RECEIVED)
        recorder.record(frameIndex = 7L, stage = PipelineStage.CADENCE, disposition = PipelineDisposition.CADENCE_SELECTION)

        assertEquals(
            listOf(
                PipelineStageEvent(0L, 41L, 7L, PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED),
                PipelineStageEvent(1L, 41L, 7L, PipelineStage.CADENCE, PipelineDisposition.CADENCE_SELECTION),
            ),
            sink.records.filterIsInstance<PipelineStageEvent>(),
        )
        assertEquals(1L, recorder.counts.getValue(PipelineStage.CAMERA_CALLBACK to PipelineDisposition.RECEIVED))
        assertEquals(1L, recorder.counts.getValue(PipelineStage.CADENCE to PipelineDisposition.CADENCE_SELECTION))
    }

    @Test
    fun fixedSamplerUsesOneSecondScheduleAndAppendsTerminalSampleAfterCancellationAndJoin() {
        val clock = FakeClock(0L)
        val scheduler = RecordingFixedScheduler()
        val sink = RecordingTelemetrySink()
        val sampler = FixedTelemetrySampler(
            nowNs = clock::nowNs,
            scheduler = scheduler,
            probe = { readings() },
            sink = sink,
        )

        sampler.start()
        clock.value = 1_000_000_000L
        scheduler.runScheduled()
        clock.value = 1_250_000_000L
        sampler.stopAndJoinWithTerminalSample()

        assertEquals(1_000_000_000L, scheduler.periodNs)
        assertEquals(listOf("cancel", "join"), scheduler.operations)
        assertEquals(listOf(0L, 1L, 2L), sink.records.filterIsInstance<FixedTelemetrySample>().map { it.sequence })
        assertEquals(listOf(0L, 1_000_000_000L, 1_250_000_000L), sink.records.filterIsInstance<FixedTelemetrySample>().map { it.tSessionNs })
        assertEquals(true, sink.records.filterIsInstance<FixedTelemetrySample>().last().terminal)
    }

    @Test
    fun unavailableReadingsAreTypedAndNeverEncodedAsZero() {
        val sample = FixedTelemetrySample(
            sequence = 0L,
            tSessionNs = 0L,
            thermal = TelemetryValue.Unavailable("thermal_api_unavailable"),
            batteryTemperature = TelemetryValue.Unavailable("battery_temperature_unavailable"),
            batteryLevelOrCharge = TelemetryValue.Unavailable("battery_level_unavailable"),
            charging = TelemetryValue.Unavailable("charging_state_unavailable"),
            powerSource = TelemetryValue.Unavailable("power_source_unavailable"),
            connectivity = TelemetryValue.Unavailable("connectivity_api_unavailable"),
            keepAwake = TelemetryValue.Unavailable("keep_awake_unavailable"),
            brightness = TelemetryValue.Unavailable("brightness_unavailable"),
            lifecycle = TelemetryValue.Unavailable("lifecycle_unavailable"),
        )

        val encoded = sample.encodeNdjson()

        assertEquals(true, encoded.contains("\"kind\":\"UNAVAILABLE\""))
        assertEquals(false, encoded.contains("\"battery_temperature\":{\"kind\":\"VALUE\",\"value\":0"))
        assertEquals(false, encoded.contains("\"thermal\":{\"kind\":\"VALUE\",\"value\":0"))
    }

    @Test
    fun batteryReadingsEncodePercentAndMicroampHoursWithStableUnitsInSchemaTwo() {
        val percent = sampleWithBattery(BatteryReading(BatteryReadingUnit.PERCENT, 75)).encodeNdjson()
        val chargeCounter = sampleWithBattery(
            BatteryReading(BatteryReadingUnit.MICROAMP_HOURS, 4_321_000),
        ).encodeNdjson()

        assertEquals(true, percent.contains("\"schema_version\":\"2.0.0\""))
        assertEquals(
            true,
            percent.contains(
                "\"battery_level_or_charge\":{\"kind\":\"VALUE\",\"unit\":\"percent\",\"value\":75}",
            ),
        )
        assertEquals(
            true,
            chargeCounter.contains(
                "\"battery_level_or_charge\":{\"kind\":\"VALUE\",\"unit\":\"microamp_hours\",\"value\":4321000}",
            ),
        )
    }

    @Test
    fun stoppingBeforeASuccessfulStartFailsExplicitly() {
        val sampler = FixedTelemetrySampler(
            nowNs = { 0L },
            scheduler = RecordingFixedScheduler(),
            probe = { readings() },
            sink = RecordingTelemetrySink(),
        )

        assertFailsWith<IllegalStateException> { sampler.stopAndJoinWithTerminalSample() }
    }

    @Test
    fun schedulerActionFailureIsRethrownBeforeAnyTerminalSampleIsAppended() {
        val failure = IllegalStateException("scheduled telemetry action failed")
        val sink = RecordingTelemetrySink()
        val scheduler = object : FixedTelemetryScheduler {
            override fun scheduleAtFixedRate(
                periodNs: Long,
                action: () -> Unit,
            ): FixedTelemetrySchedule = object : FixedTelemetrySchedule {
                override fun cancel() = Unit

                override fun join(): Unit = throw failure
            }
        }
        val sampler = FixedTelemetrySampler({ 0L }, scheduler, { readings() }, sink)
        sampler.start()

        assertEquals(
            failure,
            assertFailsWith<IllegalStateException> { sampler.stopAndJoinWithTerminalSample() },
        )
        assertEquals(listOf(false), sink.records.filterIsInstance<FixedTelemetrySample>().map { it.terminal })
    }

    @Test
    fun samplerRejectsAClockRegressionRatherThanPublishingAReorderedSample() {
        val clock = FakeClock(2L)
        val scheduler = RecordingFixedScheduler()
        val sampler = FixedTelemetrySampler(clock::nowNs, scheduler, { readings() }, RecordingTelemetrySink())
        sampler.start()
        clock.value = 1L

        assertFailsWith<IllegalStateException> { scheduler.runScheduled() }
    }

    private fun readings() = FixedTelemetryReadings(
        thermal = TelemetryValue.Value(2),
        batteryTemperature = TelemetryValue.Value(320),
        batteryLevelOrCharge = TelemetryValue.Value(BatteryReading(BatteryReadingUnit.PERCENT, 75)),
        charging = TelemetryValue.Value(false),
        powerSource = TelemetryValue.Value("battery"),
        connectivity = TelemetryValue.Value(ConnectivityState("wifi", true)),
        keepAwake = TelemetryValue.Value(true),
        brightness = TelemetryValue.Value(BrightnessState("manual", 0.5)),
        lifecycle = TelemetryValue.Value("foreground"),
    )

    private fun sampleWithBattery(reading: BatteryReading) = FixedTelemetrySample(
        sequence = 0L,
        tSessionNs = 0L,
        thermal = TelemetryValue.Unavailable("thermal_api_unavailable"),
        batteryTemperature = TelemetryValue.Unavailable("battery_temperature_unavailable"),
        batteryLevelOrCharge = TelemetryValue.Value(reading),
        charging = TelemetryValue.Unavailable("charging_state_unavailable"),
        powerSource = TelemetryValue.Unavailable("power_source_unavailable"),
        connectivity = TelemetryValue.Unavailable("connectivity_api_unavailable"),
        keepAwake = TelemetryValue.Unavailable("keep_awake_unavailable"),
        brightness = TelemetryValue.Unavailable("brightness_unavailable"),
        lifecycle = TelemetryValue.Unavailable("lifecycle_unavailable"),
    )
}

private class RecordingTelemetrySink : TelemetrySink {
    val records = mutableListOf<TelemetryRecord>()

    override fun append(record: TelemetryRecord) {
        records += record
    }
}

private class FakeClock(var value: Long) {
    fun nowNs(): Long = value
}

private class RecordingFixedScheduler : FixedTelemetryScheduler {
    var periodNs: Long? = null
    val operations = mutableListOf<String>()
    private var action: (() -> Unit)? = null

    override fun scheduleAtFixedRate(periodNs: Long, action: () -> Unit): FixedTelemetrySchedule {
        this.periodNs = periodNs
        this.action = action
        return object : FixedTelemetrySchedule {
            override fun cancel() {
                operations += "cancel"
            }

            override fun join() {
                operations += "join"
            }
        }
    }

    fun runScheduled() = checkNotNull(action).invoke()
}
