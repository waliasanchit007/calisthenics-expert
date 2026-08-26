package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.telemetry.FixedTelemetryReadings
import com.calisthenics.diagnostic.telemetry.FixedTelemetrySampler
import com.calisthenics.diagnostic.telemetry.FixedTelemetryScheduler
import com.calisthenics.diagnostic.telemetry.TelemetrySink

/** Android-independent lifecycle seam; Android probes supply the typed readings. */
interface LiveTelemetryProbe {
    fun sample(): FixedTelemetryReadings
}

/** Keeps the stop fence explicit at the call site: cancel, join, terminal sample, then seal. */
class LiveTelemetryController(
    nowNs: () -> Long,
    scheduler: FixedTelemetryScheduler,
    probe: LiveTelemetryProbe,
    sink: TelemetrySink,
) {
    private val sampler = FixedTelemetrySampler(nowNs, scheduler, probe::sample, sink)

    fun start() = sampler.start()

    fun stopAndJoinBeforeSeal() = sampler.stopAndJoinWithTerminalSample()
}
