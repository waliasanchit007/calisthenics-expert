package com.calisthenics.diagnostic.telemetry

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Versioned records that are appended directly to the durable telemetry NDJSON stream. */
sealed interface TelemetryRecord {
    fun encodeNdjson(): String
}

fun interface TelemetrySink {
    fun append(record: TelemetryRecord)
}

fun interface FixedTelemetrySampleRecorder {
    fun recordFixedSample(readings: FixedTelemetryReadings, terminal: Boolean): FixedTelemetrySample
}

enum class PipelineStage {
    CAMERA_CALLBACK,
    YUV_OWNERSHIP,
    PREPROCESSING,
    CADENCE,
    INFERENCE,
    POSE_STREAM,
    /** Extension point only: Task 4 owns production encoder instrumentation. */
    ENCODER,
}

enum class PipelineDisposition {
    RECEIVED,
    SOURCE_NOT_READY,
    CLOCK_FAULT,
    ACQUIRED,
    RELEASED,
    STARTED,
    COMPLETED,
    CADENCE_SELECTION,
    BACKPRESSURE,
    SESSION_STOPPING,
    SOURCE_LOSS,
    SUBMITTED,
    FAILED,
    APPENDED,
    DURABLE_CHECKPOINT,
    ENCODER_ACCEPTED,
    ENCODER_DROPPED,
    ENCODER_WRITTEN,
}

/** The one and only terminal outcome for each callback delivered to the analyzer. */
enum class CallbackTerminalDisposition {
    SOURCE_NOT_READY,
    CLOCK_FAULT,
    SOURCE_LOSS,
    PREPROCESSING_FAILED,
    CADENCE_SELECTION,
    BACKPRESSURE,
    SESSION_STOPPING,
    INFERENCE_FAILED,
    INFERENCE_COMPLETED,
    /** Task 4 will make this live when video encoder fan-out exists. */
    ENCODER_ACCEPTED,
    ENCODER_DROPPED,
    ENCODER_WRITTEN,
}

enum class PipelineTelemetryRecordingMode {
    /** Short 10/15/30 attribution runs retain every durable stage event. */
    SHORT_ATTRIBUTION,

    /** Sustained runs retain only finite one-second buckets aligned with fixed telemetry samples. */
    SUSTAINED,
}

data class PipelineTerminalEvent(
    val stage: PipelineStage,
    val disposition: PipelineDisposition,
)

fun CallbackTerminalDisposition.pipelineTerminalEvent(): PipelineTerminalEvent = when (this) {
    CallbackTerminalDisposition.SOURCE_NOT_READY ->
        PipelineTerminalEvent(PipelineStage.CAMERA_CALLBACK, PipelineDisposition.SOURCE_NOT_READY)
    CallbackTerminalDisposition.CLOCK_FAULT ->
        PipelineTerminalEvent(PipelineStage.CAMERA_CALLBACK, PipelineDisposition.CLOCK_FAULT)
    CallbackTerminalDisposition.SOURCE_LOSS ->
        PipelineTerminalEvent(PipelineStage.YUV_OWNERSHIP, PipelineDisposition.SOURCE_LOSS)
    CallbackTerminalDisposition.PREPROCESSING_FAILED ->
        PipelineTerminalEvent(PipelineStage.PREPROCESSING, PipelineDisposition.FAILED)
    CallbackTerminalDisposition.CADENCE_SELECTION ->
        PipelineTerminalEvent(PipelineStage.CADENCE, PipelineDisposition.CADENCE_SELECTION)
    CallbackTerminalDisposition.BACKPRESSURE ->
        PipelineTerminalEvent(PipelineStage.CADENCE, PipelineDisposition.BACKPRESSURE)
    CallbackTerminalDisposition.SESSION_STOPPING ->
        PipelineTerminalEvent(PipelineStage.CADENCE, PipelineDisposition.SESSION_STOPPING)
    CallbackTerminalDisposition.INFERENCE_FAILED ->
        PipelineTerminalEvent(PipelineStage.INFERENCE, PipelineDisposition.FAILED)
    CallbackTerminalDisposition.INFERENCE_COMPLETED ->
        PipelineTerminalEvent(PipelineStage.INFERENCE, PipelineDisposition.COMPLETED)
    CallbackTerminalDisposition.ENCODER_ACCEPTED ->
        PipelineTerminalEvent(PipelineStage.ENCODER, PipelineDisposition.ENCODER_ACCEPTED)
    CallbackTerminalDisposition.ENCODER_DROPPED ->
        PipelineTerminalEvent(PipelineStage.ENCODER, PipelineDisposition.ENCODER_DROPPED)
    CallbackTerminalDisposition.ENCODER_WRITTEN ->
        PipelineTerminalEvent(PipelineStage.ENCODER, PipelineDisposition.ENCODER_WRITTEN)
}

data class DeliveredCallbackAccounting(
    val deliveredCallbacks: Long,
    val unclosedCallbacks: Long,
    val counts: Map<CallbackTerminalDisposition, Long>,
)

/** Bounded accounting only; it intentionally never retains callback IDs after they close. */
class DeliveredCallbackLedger {
    private val mutableCounts = mutableMapOf<CallbackTerminalDisposition, Long>()
    private val openFrameIndices = mutableSetOf<Long>()
    var deliveredCallbacks: Long = 0L
        private set

    val counts: Map<CallbackTerminalDisposition, Long>
        get() = mutableCounts.toMap()

    val unclosedCallbacks: Long
        get() = openFrameIndices.size.toLong()

    fun begin(frameIndex: Long) {
        require(frameIndex >= 0L) { "frame index must be non-negative" }
        check(frameIndex == deliveredCallbacks) { "frame index must be contiguous and monotonic" }
        check(openFrameIndices.add(frameIndex)) { "delivered callback is already open" }
        deliveredCallbacks += 1L
    }

    fun close(frameIndex: Long, disposition: CallbackTerminalDisposition) {
        require(frameIndex >= 0L) { "frame index must be non-negative" }
        check(openFrameIndices.remove(frameIndex)) { "callback has no open terminal disposition slot" }
        mutableCounts[disposition] = (mutableCounts[disposition] ?: 0L) + 1L
    }

    fun assertClosed(cameraFramesReceived: Long) {
        assertClosed(
            callbacksForwardedToRunner = deliveredCallbacks,
            cameraFramesReceived = cameraFramesReceived,
            diagnosticDropsAfterSeal = 0L,
        )
    }

    fun assertClosed(
        callbacksForwardedToRunner: Long,
        cameraFramesReceived: Long,
        diagnosticDropsAfterSeal: Long,
    ) {
        require(callbacksForwardedToRunner >= 0L) { "forwarded callback count must be non-negative" }
        require(cameraFramesReceived >= 0L) { "camera frame count must be non-negative" }
        require(diagnosticDropsAfterSeal >= 0L) { "post-seal drop count must be non-negative" }
        check(openFrameIndices.isEmpty()) { "delivered callbacks remain unclosed" }
        check(mutableCounts.values.sum() == deliveredCallbacks) {
            "each delivered callback must have exactly one terminal outcome"
        }
        val sourceNotReady = mutableCounts[CallbackTerminalDisposition.SOURCE_NOT_READY] ?: 0L
        check(deliveredCallbacks == callbacksForwardedToRunner + sourceNotReady) {
            "delivered callbacks must equal forwarded callbacks plus source-not-ready outcomes"
        }
        check(callbacksForwardedToRunner == cameraFramesReceived + diagnosticDropsAfterSeal) {
            "forwarded callbacks must equal runner camera frames plus diagnostic post-seal drops"
        }
    }

    fun snapshot() = DeliveredCallbackAccounting(
        deliveredCallbacks = deliveredCallbacks,
        unclosedCallbacks = unclosedCallbacks,
        counts = counts,
    )
}

data class PipelineStageEvent(
    val sequence: Long,
    val tSessionNs: Long,
    val frameIndex: Long,
    val stage: PipelineStage,
    val disposition: PipelineDisposition,
) : TelemetryRecord {
    init {
        require(sequence >= 0L) { "stage sequence must be non-negative" }
        require(tSessionNs >= 0L) { "stage time must be non-negative" }
        require(frameIndex >= 0L) { "stage frame index must be non-negative" }
    }

    override fun encodeNdjson(): String = buildString {
        append('{')
        stringField("record_type", "pipeline_stage_event")
        append(',')
        stringField("schema_version", SCHEMA_VERSION)
        append(",\"sequence\":$sequence")
        append(",\"t_session_ns\":$tSessionNs")
        append(",\"frame_index\":$frameIndex")
        append(',')
        stringField("stage", stage.name)
        append(',')
        stringField("disposition", disposition.name)
        append("}\n")
    }
}

/**
 * The sole state retained for stage attribution is a bounded count per finite stage/disposition
 * pair. Event history lives only in the durable sink.
 */
class PipelineStageRecorder(
    private val nowNs: () -> Long,
    private val sink: TelemetrySink,
    val recordingMode: PipelineTelemetryRecordingMode = PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION,
) : TelemetrySink, FixedTelemetrySampleRecorder {
    private var nextSequence = 0L
    private var lastTimeNs = 0L
    private val mutableCounts = mutableMapOf<Pair<PipelineStage, PipelineDisposition>, Long>()
    private val currentBucketCounts = mutableMapOf<Pair<PipelineStage, PipelineDisposition>, Long>()
    private var nextCounterSequence = 0L
    private var lastFixedSampleSequence = -1L
    private var lastFixedSampleTimeNs = -1L
    private var nextFixedSampleSequence = 0L
    private val recorderMutex = Mutex()

    val counts: Map<Pair<PipelineStage, PipelineDisposition>, Long>
        get() = serialized { mutableCounts.toMap() }

    val retainedStageCount: Int
        get() = serialized { mutableCounts.size }

    fun record(frameIndex: Long, stage: PipelineStage, disposition: PipelineDisposition): PipelineStageEvent = serialized {
        val timeNs = nowNs()
        check(timeNs >= lastTimeNs) { "pipeline stage clock regressed" }
        val event = PipelineStageEvent(nextSequence++, timeNs, frameIndex, stage, disposition)
        val key = stage to disposition
        mutableCounts[key] = (mutableCounts[key] ?: 0L) + 1L
        currentBucketCounts[key] = (currentBucketCounts[key] ?: 0L) + 1L
        if (recordingMode == PipelineTelemetryRecordingMode.SHORT_ATTRIBUTION) sink.append(event)
        lastTimeNs = timeNs
        event
    }

    override fun recordFixedSample(
        readings: FixedTelemetryReadings,
        terminal: Boolean,
    ): FixedTelemetrySample = serialized {
        val timeNs = nowNs()
        check(timeNs >= lastTimeNs) { "telemetry clock regressed" }
        val sample = FixedTelemetrySample(
            sequence = nextFixedSampleSequence++,
            tSessionNs = timeNs,
            thermal = readings.thermal,
            batteryTemperature = readings.batteryTemperature,
            batteryLevelOrCharge = readings.batteryLevelOrCharge,
            charging = readings.charging,
            powerSource = readings.powerSource,
            connectivity = readings.connectivity,
            keepAwake = readings.keepAwake,
            brightness = readings.brightness,
            lifecycle = readings.lifecycle,
            terminal = terminal,
        )
        appendLocked(sample)
        lastTimeNs = timeNs
        sample
    }

    override fun append(record: TelemetryRecord) = serialized { appendLocked(record) }

    private fun appendLocked(record: TelemetryRecord) {
        if (recordingMode != PipelineTelemetryRecordingMode.SUSTAINED || record !is FixedTelemetrySample) {
            sink.append(record)
            return
        }
        check(record.sequence == lastFixedSampleSequence + 1L) { "fixed telemetry sequence must be contiguous" }
        check(record.tSessionNs >= lastFixedSampleTimeNs) { "fixed telemetry time regressed" }
        val bucket = SustainedStageCounterSample(
            sequence = nextCounterSequence++,
            tSessionNs = record.tSessionNs,
            fixedTelemetrySequence = record.sequence,
            counts = currentBucketCounts.entries
                .filter { it.value > 0L }
                .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second.ordinal }))
                .map { (key, count) -> PipelineStageCount(key.first, key.second, count) },
        ).also {
            currentBucketCounts.clear()
            lastFixedSampleSequence = record.sequence
            lastFixedSampleTimeNs = record.tSessionNs
        }
        sink.append(record)
        sink.append(bucket)
    }

    fun assertReconciles(accounting: DeliveredCallbackAccounting) = serialized {
        check(accounting.unclosedCallbacks == 0L) { "delivered callbacks remain unclosed" }
        check(accounting.counts.values.sum() == accounting.deliveredCallbacks) {
            "callback terminal counts do not equal delivered callbacks"
        }
        accounting.counts.forEach { (terminal, expected) ->
            val event = terminal.pipelineTerminalEvent()
            check((mutableCounts[event.stage to event.disposition] ?: 0L) == expected) {
                "stage counts do not reconcile for $terminal"
            }
        }
    }

    private fun <T> serialized(block: () -> T): T = runBlocking { recorderMutex.withLock { block() } }
}

data class PipelineStageCount(
    val stage: PipelineStage,
    val disposition: PipelineDisposition,
    val count: Long,
) {
    init {
        require(count > 0L) { "sustained stage counts must be positive" }
    }
}

data class SustainedStageCounterSample(
    val sequence: Long,
    val tSessionNs: Long,
    val fixedTelemetrySequence: Long,
    val counts: List<PipelineStageCount>,
) : TelemetryRecord {
    init {
        require(sequence >= 0L) { "sustained counter sequence must be non-negative" }
        require(tSessionNs >= 0L) { "sustained counter time must be non-negative" }
        require(fixedTelemetrySequence >= 0L) { "fixed telemetry sequence must be non-negative" }
    }

    override fun encodeNdjson(): String = buildString {
        append('{')
        stringField("record_type", "sustained_stage_counter")
        append(',')
        stringField("schema_version", SCHEMA_VERSION)
        append(",\"sequence\":$sequence")
        append(",\"t_session_ns\":$tSessionNs")
        append(",\"fixed_telemetry_sequence\":$fixedTelemetrySequence")
        append(",\"counts\":[")
        counts.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append('{')
            stringField("stage", value.stage.name)
            append(',')
            stringField("disposition", value.disposition.name)
            append(",\"count\":${value.count}}")
        }
        append("]}\n")
    }
}

object SustainedTelemetryLimits {
    const val MAX_SESSION_SECONDS = 1_500L
    /** Initial sample, one sample per full second, and the mandatory terminal sample. */
    const val MAX_FIXED_SAMPLES_FOR_1500_SECONDS = MAX_SESSION_SECONDS + 2L
    const val MAX_RECORDS_FOR_1500_SECONDS = MAX_FIXED_SAMPLES_FOR_1500_SECONDS * 2L
}

sealed interface TelemetryValue<out T> {
    data class Value<T>(val value: T) : TelemetryValue<T>

    data class Unavailable(val code: String) : TelemetryValue<Nothing> {
        init {
            require(code.isNotBlank()) { "unavailable code must not be blank" }
        }
    }
}

data class ConnectivityState(
    val transport: String,
    val connected: Boolean,
)

enum class BatteryReadingUnit(val wireValue: String) {
    PERCENT("percent"),
    MICROAMP_HOURS("microamp_hours"),
}

data class BatteryReading(
    val unit: BatteryReadingUnit,
    val value: Int,
)

data class BrightnessState(val mode: String, val value: Double) {
    init {
        require(value in 0.0..1.0) { "brightness must be normalized" }
    }
}

data class FixedTelemetryReadings(
    val thermal: TelemetryValue<Int>,
    val batteryTemperature: TelemetryValue<Int>,
    val batteryLevelOrCharge: TelemetryValue<BatteryReading>,
    val charging: TelemetryValue<Boolean>,
    val powerSource: TelemetryValue<String>,
    val connectivity: TelemetryValue<ConnectivityState>,
    val keepAwake: TelemetryValue<Boolean>,
    val brightness: TelemetryValue<BrightnessState>,
    val lifecycle: TelemetryValue<String>,
)

data class FixedTelemetrySample(
    val sequence: Long,
    val tSessionNs: Long,
    val thermal: TelemetryValue<Int>,
    val batteryTemperature: TelemetryValue<Int>,
    val batteryLevelOrCharge: TelemetryValue<BatteryReading>,
    val charging: TelemetryValue<Boolean>,
    val powerSource: TelemetryValue<String>,
    val connectivity: TelemetryValue<ConnectivityState>,
    val keepAwake: TelemetryValue<Boolean>,
    val brightness: TelemetryValue<BrightnessState>,
    val lifecycle: TelemetryValue<String>,
    val terminal: Boolean = false,
) : TelemetryRecord {
    init {
        require(sequence >= 0L) { "sample sequence must be non-negative" }
        require(tSessionNs >= 0L) { "sample time must be non-negative" }
    }

    override fun encodeNdjson(): String = buildString {
        append('{')
        stringField("record_type", "fixed_telemetry_sample")
        append(',')
        stringField("schema_version", FIXED_TELEMETRY_SCHEMA_VERSION)
        append(",\"sequence\":$sequence")
        append(",\"t_session_ns\":$tSessionNs")
        append(",\"terminal\":$terminal")
        append(",\"thermal\":")
        appendIntValue(thermal)
        append(",\"battery_temperature\":")
        appendIntValue(batteryTemperature)
        append(",\"battery_level_or_charge\":")
        appendBatteryReadingValue(batteryLevelOrCharge)
        append(",\"charging\":")
        appendBooleanValue(charging)
        append(",\"power_source\":")
        appendStringValue(powerSource)
        append(",\"connectivity\":")
        appendConnectivityValue(connectivity)
        append(",\"keep_awake\":")
        appendBooleanValue(keepAwake)
        append(",\"brightness\":")
        appendBrightnessValue(brightness)
        append(",\"lifecycle\":")
        appendStringValue(lifecycle)
        append("}\n")
    }
}

interface FixedTelemetrySchedule {
    fun cancel()
    fun join()
}

fun interface FixedTelemetryScheduler {
    fun scheduleAtFixedRate(periodNs: Long, action: () -> Unit): FixedTelemetrySchedule
}

/** Fixed one-second sampler whose terminal fence is cancel, join, append terminal sample. */
class FixedTelemetrySampler(
    private val nowNs: () -> Long,
    private val scheduler: FixedTelemetryScheduler,
    private val probe: () -> FixedTelemetryReadings,
    private val sink: TelemetrySink,
) {
    private var schedule: FixedTelemetrySchedule? = null
    private var started = false
    @kotlin.concurrent.Volatile
    private var stopped = false
    private var terminalSampleAppended = false
    private var nextSequence = 0L
    private var lastSampleNs = 0L

    fun start() {
        check(!started && schedule == null && !stopped) { "telemetry sampler may only start once" }
        appendSample(terminal = false)
        schedule = scheduler.scheduleAtFixedRate(SAMPLE_INTERVAL_NS) {
            if (!stopped) appendSample(terminal = false)
        }
        started = true
    }

    fun stopAndJoinWithTerminalSample() {
        check(started) { "telemetry sampler must start successfully before it can stop" }
        if (terminalSampleAppended) return
        val current = checkNotNull(schedule)
        stopped = true
        current.cancel()
        current.join()
        appendSample(terminal = true)
        terminalSampleAppended = true
    }

    private fun appendSample(terminal: Boolean) {
        val readings = probe()
        val fixedRecorder = sink as? FixedTelemetrySampleRecorder
        if (fixedRecorder != null) {
            val sample = fixedRecorder.recordFixedSample(readings, terminal)
            nextSequence = sample.sequence + 1L
            lastSampleNs = sample.tSessionNs
            return
        }
        val timeNs = nowNs()
        check(timeNs >= lastSampleNs) { "telemetry clock regressed" }
        sink.append(
            FixedTelemetrySample(
                sequence = nextSequence++,
                tSessionNs = timeNs,
                thermal = readings.thermal,
                batteryTemperature = readings.batteryTemperature,
                batteryLevelOrCharge = readings.batteryLevelOrCharge,
                charging = readings.charging,
                powerSource = readings.powerSource,
                connectivity = readings.connectivity,
                keepAwake = readings.keepAwake,
                brightness = readings.brightness,
                lifecycle = readings.lifecycle,
                terminal = terminal,
            ),
        )
        lastSampleNs = timeNs
    }
}

private const val SCHEMA_VERSION = "1.0.0"
private const val FIXED_TELEMETRY_SCHEMA_VERSION = "2.0.0"
private const val SAMPLE_INTERVAL_NS = 1_000_000_000L

private fun StringBuilder.stringField(name: String, value: String) {
    append('"').append(name).append("\":\"")
    append(value.jsonEscaped())
    append('"')
}

private fun String.jsonEscaped(): String = buildString {
    this@jsonEscaped.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

private fun StringBuilder.appendUnavailable(unavailable: TelemetryValue.Unavailable) {
    append("{\"kind\":\"UNAVAILABLE\",\"code\":\"")
    append(unavailable.code.jsonEscaped())
    append("\"}")
}

private fun StringBuilder.appendIntValue(value: TelemetryValue<Int>) = when (value) {
    is TelemetryValue.Value -> append("{\"kind\":\"VALUE\",\"value\":${value.value}}")
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}

private fun StringBuilder.appendBooleanValue(value: TelemetryValue<Boolean>) = when (value) {
    is TelemetryValue.Value -> append("{\"kind\":\"VALUE\",\"value\":${value.value}}")
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}

private fun StringBuilder.appendStringValue(value: TelemetryValue<String>) = when (value) {
    is TelemetryValue.Value -> append("{\"kind\":\"VALUE\",\"value\":\"").append(value.value.jsonEscaped()).append("\"}")
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}

private fun StringBuilder.appendConnectivityValue(value: TelemetryValue<ConnectivityState>) = when (value) {
    is TelemetryValue.Value -> {
        append("{\"kind\":\"VALUE\",\"transport\":\"").append(value.value.transport.jsonEscaped())
        append("\",\"connected\":${value.value.connected}")
        append('}')
    }
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}

private fun StringBuilder.appendBatteryReadingValue(value: TelemetryValue<BatteryReading>) = when (value) {
    is TelemetryValue.Value -> append("{\"kind\":\"VALUE\",\"unit\":\"")
        .append(value.value.unit.wireValue)
        .append("\",\"value\":${value.value.value}}")
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}

private fun StringBuilder.appendBrightnessValue(value: TelemetryValue<BrightnessState>) = when (value) {
    is TelemetryValue.Value -> append("{\"kind\":\"VALUE\",\"mode\":\"").append(value.value.mode.jsonEscaped())
        .append("\",\"value\":${value.value.value}}")
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}
