package com.calisthenics.diagnostic.telemetry

/** Versioned records that are appended directly to the durable telemetry NDJSON stream. */
sealed interface TelemetryRecord {
    fun encodeNdjson(): String
}

fun interface TelemetrySink {
    fun append(record: TelemetryRecord)
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
        require(cameraFramesReceived >= 0L) { "camera frame count must be non-negative" }
        check(openFrameIndices.isEmpty()) { "delivered callbacks remain unclosed" }
        check(mutableCounts.values.sum() == deliveredCallbacks) {
            "each delivered callback must have exactly one terminal outcome"
        }
        check(deliveredCallbacks == cameraFramesReceived) {
            "delivered callbacks must equal camera frames received"
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
) {
    private var nextSequence = 0L
    private var lastTimeNs = 0L
    private val mutableCounts = mutableMapOf<Pair<PipelineStage, PipelineDisposition>, Long>()

    val counts: Map<Pair<PipelineStage, PipelineDisposition>, Long>
        get() = mutableCounts.toMap()

    fun record(frameIndex: Long, stage: PipelineStage, disposition: PipelineDisposition): PipelineStageEvent {
        val timeNs = nowNs()
        check(timeNs >= lastTimeNs) { "pipeline stage clock regressed" }
        val event = PipelineStageEvent(nextSequence++, timeNs, frameIndex, stage, disposition)
        sink.append(event)
        lastTimeNs = timeNs
        val key = stage to disposition
        mutableCounts[key] = (mutableCounts[key] ?: 0L) + 1L
        return event
    }
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
    /** An opaque, non-SSID/non-BSSID Wi-Fi identity such as a locally salted digest. */
    val wifiIdentity: String?,
)

data class BrightnessState(val mode: String, val value: Double) {
    init {
        require(value in 0.0..1.0) { "brightness must be normalized" }
    }
}

data class FixedTelemetryReadings(
    val thermal: TelemetryValue<Int>,
    val batteryTemperature: TelemetryValue<Int>,
    val batteryLevelOrCharge: TelemetryValue<Int>,
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
    val batteryLevelOrCharge: TelemetryValue<Int>,
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
        stringField("schema_version", SCHEMA_VERSION)
        append(",\"sequence\":$sequence")
        append(",\"t_session_ns\":$tSessionNs")
        append(",\"terminal\":$terminal")
        append(",\"thermal\":")
        appendIntValue(thermal)
        append(",\"battery_temperature\":")
        appendIntValue(batteryTemperature)
        append(",\"battery_level_or_charge\":")
        appendIntValue(batteryLevelOrCharge)
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
    @Volatile
    private var stopped = false
    private var nextSequence = 0L
    private var lastSampleNs = 0L

    fun start() {
        check(schedule == null && !stopped) { "telemetry sampler may only start once" }
        appendSample(terminal = false)
        schedule = scheduler.scheduleAtFixedRate(SAMPLE_INTERVAL_NS) {
            if (!stopped) appendSample(terminal = false)
        }
    }

    fun stopAndJoinWithTerminalSample() {
        val current = schedule ?: return
        if (stopped) return
        stopped = true
        current.cancel()
        current.join()
        appendSample(terminal = true)
    }

    private fun appendSample(terminal: Boolean) {
        val timeNs = nowNs()
        check(timeNs >= lastSampleNs) { "telemetry clock regressed" }
        val readings = probe()
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
        value.value.wifiIdentity?.let { append(",\"wifi_identity\":\"").append(it.jsonEscaped()).append('"') }
        append('}')
    }
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}

private fun StringBuilder.appendBrightnessValue(value: TelemetryValue<BrightnessState>) = when (value) {
    is TelemetryValue.Value -> append("{\"kind\":\"VALUE\",\"mode\":\"").append(value.value.mode.jsonEscaped())
        .append("\",\"value\":${value.value.value}}")
    is TelemetryValue.Unavailable -> appendUnavailable(value)
}
