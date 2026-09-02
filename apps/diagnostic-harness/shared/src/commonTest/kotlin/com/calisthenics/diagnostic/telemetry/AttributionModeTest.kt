package com.calisthenics.diagnostic.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttributionModeTest {
    @Test
    fun shortModeStreamsEveryStageEventWhileSustainedModeOnlyEmitsCountersAtFixedSamples() {
        val shortSink = RecordingSink()
        val short = PipelineStageRecorder(
            nowNs = { 4L },
            sink = shortSink,
            recordingMode = PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION,
        )
        short.record(0L, PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED)
        short.record(0L, PipelineStage.INFERENCE, PipelineDisposition.SUBMITTED)

        val sustainedSink = RecordingSink()
        val sustained = PipelineStageRecorder(
            nowNs = { 4L },
            sink = sustainedSink,
            recordingMode = PipelineTelemetryRecordingMode.SUSTAINED,
        )
        sustained.record(0L, PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED)
        sustained.record(0L, PipelineStage.INFERENCE, PipelineDisposition.SUBMITTED)
        sustained.append(sample(sequence = 0L, tSessionNs = 5L))

        assertEquals(2, shortSink.records.filterIsInstance<PipelineStageEvent>().size)
        assertTrue(sustainedSink.records.none { it is PipelineStageEvent })
        val counter = sustainedSink.records.single { it is SustainedStageCounterSample }
            as SustainedStageCounterSample
        assertEquals(0L, counter.fixedTelemetrySequence)
        assertEquals(5L, counter.tSessionNs)
        assertEquals(
            listOf(
                PipelineStageCount(PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED, 1L),
                PipelineStageCount(PipelineStage.INFERENCE, PipelineDisposition.SUBMITTED, 1L),
            ),
            counter.counts,
        )
    }

    @Test
    fun sustainedModeHasAFixedMaximumForAFifteenHundredSecondRunAndCountersAlignToSamples() {
        val sink = RecordingSink()
        val recorder = PipelineStageRecorder(
            nowNs = { 1L },
            sink = sink,
            recordingMode = PipelineTelemetryRecordingMode.SUSTAINED,
        )

        repeat(100_000) { index ->
            recorder.record(index.toLong(), PipelineStage.CADENCE, PipelineDisposition.CADENCE_SELECTION)
        }
        repeat(SustainedTelemetryLimits.MAX_FIXED_SAMPLES_FOR_1500_SECONDS.toInt()) { sequence ->
            recorder.append(
                sample(
                    sequence.toLong(),
                    sequence * 1_000_000_000L,
                    terminal = sequence.toLong() == SustainedTelemetryLimits.MAX_FIXED_SAMPLES_FOR_1500_SECONDS - 1L,
                ),
            )
        }
        recorder.recordSustainedLatencySummary(
            upperBoundsNs = listOf(1L, 2L, null),
            bucketCounts = listOf(4L, 3L, 2L),
        )

        val samples = sink.records.filterIsInstance<FixedTelemetrySample>()
        val counters = sink.records.filterIsInstance<SustainedStageCounterSample>()
        assertEquals(SustainedTelemetryLimits.MAX_FIXED_SAMPLES_FOR_1500_SECONDS, samples.size.toLong())
        assertEquals(samples.map { it.sequence }, counters.map { it.fixedTelemetrySequence })
        assertEquals(samples.map { it.tSessionNs }, counters.map { it.tSessionNs })
        assertEquals(SustainedTelemetryLimits.MAX_RECORDS_FOR_1500_SECONDS, sink.records.size.toLong())
        assertTrue(sink.records.none { it is PipelineStageEvent })
        assertTrue(recorder.retainedStageCount <= PipelineStage.entries.size * PipelineDisposition.entries.size)
    }

    @Test
    fun sustainedLatencySummaryIsOneBoundedFinalRecordAfterTheTerminalSample() {
        val sink = RecordingSink()
        val recorder = PipelineStageRecorder(
            nowNs = { 9L },
            sink = sink,
            recordingMode = PipelineTelemetryRecordingMode.SUSTAINED,
        )
        recorder.recordFixedSample(readings(320), terminal = true)

        val record = recorder.recordSustainedLatencySummary(
            upperBoundsNs = listOf(1_000_000L, 2_000_000L, null),
            bucketCounts = listOf(7L, 2L, 1L),
        )

        assertEquals(record, sink.records.last())
        assertEquals(10L, record.sampleCount)
        assertEquals(
            "{\"record_type\":\"sustained_latency_summary\",\"schema_version\":\"1.0.0\"," +
                "\"sequence\":1,\"t_session_ns\":9," +
                "\"upper_bounds_ns\":[1000000,2000000,null],\"bucket_counts\":[7,2,1]," +
                "\"sample_count\":10}\n",
            record.encodeNdjson(),
        )
    }

    @Test
    fun sustainedLatencySummaryRejectsPrematureDuplicateAndInvalidShapes() {
        val recorder = PipelineStageRecorder(
            nowNs = { 9L },
            sink = RecordingSink(),
            recordingMode = PipelineTelemetryRecordingMode.SUSTAINED,
        )
        kotlin.test.assertFailsWith<IllegalStateException> {
            recorder.recordSustainedLatencySummary(listOf(1L, null), listOf(1L, 0L))
        }
        recorder.recordFixedSample(readings(320), terminal = true)
        recorder.recordSustainedLatencySummary(listOf(1L, null), listOf(1L, 0L))
        kotlin.test.assertFailsWith<IllegalStateException> {
            recorder.recordSustainedLatencySummary(listOf(1L, null), listOf(1L, 0L))
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SustainedLatencySummaryRecord(0L, 0L, listOf(1L, null), listOf(1L), 1L)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SustainedLatencySummaryRecord(0L, 0L, listOf(2L, 1L, null), listOf(1L, 0L, 0L), 1L)
        }
    }

    @Test
    fun shortAttributionModeCannotEmitASustainedLatencySummary() {
        val recorder = PipelineStageRecorder(
            nowNs = { 9L },
            sink = RecordingSink(),
            recordingMode = PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION,
        )
        kotlin.test.assertFailsWith<IllegalStateException> {
            recorder.recordSustainedLatencySummary(listOf(1L, null), listOf(1L, 0L))
        }
    }

    @Test
    fun sustainedModeRetainsOnlyScalarCoverageAndBoundaryBatteryTemperatures() {
        var nowNs = 0L
        val recorder = PipelineStageRecorder(
            nowNs = { nowNs },
            sink = RecordingSink(),
            recordingMode = PipelineTelemetryRecordingMode.SUSTAINED,
        )
        recorder.recordFixedSample(readings(320), terminal = false)
        nowNs = 1_500_000_000L
        recorder.recordFixedSample(readings(335), terminal = true)

        val summary = recorder.sustainedTelemetrySummary()

        assertEquals(2L, summary.fixedSampleCount)
        assertEquals(0L, summary.firstTSessionNs)
        assertEquals(1_500_000_000L, summary.lastTSessionNs)
        assertEquals(1_500_000_000L, summary.maxInterSampleGapNs)
        assertTrue(summary.terminalSample)
        assertEquals(32.0, summary.startBatteryTemperatureC)
        assertEquals(33.5, summary.endBatteryTemperatureC)
    }

    @Test
    fun stageCountsReconcileWithTheClosedCallbackDispositionLattice() {
        val sink = RecordingSink()
        val recorder = PipelineStageRecorder({ 7L }, sink, PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION)
        val ledger = DeliveredCallbackLedger()
        var frameIndex = 0L
        CallbackTerminalDisposition.entries.forEachIndexed { terminalIndex, disposition ->
            repeat(terminalIndex + 1) {
                ledger.begin(frameIndex)
                recorder.record(frameIndex, PipelineStage.CAMERA_CALLBACK, PipelineDisposition.RECEIVED)
                ledger.close(frameIndex, disposition)
                val terminal = expectedTerminalEvents.getValue(disposition)
                recorder.record(frameIndex, terminal.stage, terminal.disposition)
                frameIndex += 1L
            }
        }

        recorder.assertReconciles(ledger.snapshot())
        assertFalse(sink.records.isEmpty())
    }

    private fun sample(sequence: Long, tSessionNs: Long, terminal: Boolean = false) = FixedTelemetrySample(
        sequence = sequence,
        tSessionNs = tSessionNs,
        thermal = TelemetryValue.Unavailable("thermal"),
        batteryTemperature = TelemetryValue.Unavailable("battery_temperature"),
        batteryLevelOrCharge = TelemetryValue.Unavailable("battery_level"),
        charging = TelemetryValue.Unavailable("charging"),
        powerSource = TelemetryValue.Unavailable("power"),
        connectivity = TelemetryValue.Unavailable("connectivity"),
        keepAwake = TelemetryValue.Unavailable("keep_awake"),
        brightness = TelemetryValue.Unavailable("brightness"),
        lifecycle = TelemetryValue.Unavailable("lifecycle"),
        terminal = terminal,
    )

    private fun readings(batteryTemperatureDeciC: Int) = FixedTelemetryReadings(
        thermal = TelemetryValue.Value(2),
        batteryTemperature = TelemetryValue.Value(batteryTemperatureDeciC),
        batteryLevelOrCharge = TelemetryValue.Value(BatteryReading(BatteryReadingUnit.PERCENT, 75)),
        charging = TelemetryValue.Value(false),
        powerSource = TelemetryValue.Value("battery"),
        connectivity = TelemetryValue.Value(ConnectivityState("wifi", true)),
        keepAwake = TelemetryValue.Value(true),
        brightness = TelemetryValue.Value(BrightnessState("manual", 0.5)),
        lifecycle = TelemetryValue.Value("foreground"),
    )

    private val expectedTerminalEvents = mapOf(
        CallbackTerminalDisposition.SOURCE_NOT_READY to PipelineTerminalEvent(PipelineStage.CAMERA_CALLBACK, PipelineDisposition.SOURCE_NOT_READY),
        CallbackTerminalDisposition.CLOCK_FAULT to PipelineTerminalEvent(PipelineStage.CAMERA_CALLBACK, PipelineDisposition.CLOCK_FAULT),
        CallbackTerminalDisposition.SOURCE_LOSS to PipelineTerminalEvent(PipelineStage.YUV_OWNERSHIP, PipelineDisposition.SOURCE_LOSS),
        CallbackTerminalDisposition.PREPROCESSING_FAILED to PipelineTerminalEvent(PipelineStage.PREPROCESSING, PipelineDisposition.FAILED),
        CallbackTerminalDisposition.CADENCE_SELECTION to PipelineTerminalEvent(PipelineStage.CADENCE, PipelineDisposition.CADENCE_SELECTION),
        CallbackTerminalDisposition.BACKPRESSURE to PipelineTerminalEvent(PipelineStage.CADENCE, PipelineDisposition.BACKPRESSURE),
        CallbackTerminalDisposition.SESSION_STOPPING to PipelineTerminalEvent(PipelineStage.CADENCE, PipelineDisposition.SESSION_STOPPING),
        CallbackTerminalDisposition.INFERENCE_FAILED to PipelineTerminalEvent(PipelineStage.INFERENCE, PipelineDisposition.FAILED),
        CallbackTerminalDisposition.INFERENCE_COMPLETED to PipelineTerminalEvent(PipelineStage.INFERENCE, PipelineDisposition.COMPLETED),
        CallbackTerminalDisposition.ENCODER_ACCEPTED to PipelineTerminalEvent(PipelineStage.ENCODER, PipelineDisposition.ENCODER_ACCEPTED),
        CallbackTerminalDisposition.ENCODER_DROPPED to PipelineTerminalEvent(PipelineStage.ENCODER, PipelineDisposition.ENCODER_DROPPED),
        CallbackTerminalDisposition.ENCODER_WRITTEN to PipelineTerminalEvent(PipelineStage.ENCODER, PipelineDisposition.ENCODER_WRITTEN),
    )

    private class RecordingSink : TelemetrySink {
        val records = mutableListOf<TelemetryRecord>()

        override fun append(record: TelemetryRecord) {
            records += record
        }
    }
}
