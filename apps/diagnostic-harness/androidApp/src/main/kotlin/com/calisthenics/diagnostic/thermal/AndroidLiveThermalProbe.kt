package com.calisthenics.diagnostic.thermal

import android.content.Context
import android.os.PowerManager
import com.calisthenics.diagnostic.composition.LiveThermalObservation
import com.calisthenics.diagnostic.composition.LiveThermalProbe

/** Narrow Android API-33 thermal port; status values are recorded as observations, not verdicts. */
class AndroidLiveThermalProbe(
    private val powerManager: PowerManager?,
) : LiveThermalProbe {
    private val monitor = Any()
    private val observations = mutableListOf<LiveThermalObservation>()
    private var relativeNowNs: (() -> Long)? = null
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    override val available: Boolean
        get() = powerManager != null

    override val unavailableReason: String?
        get() = if (powerManager == null) "thermal_api_unavailable" else null

    override fun start(nowNs: () -> Long) {
        val manager = checkNotNull(powerManager) { "thermal_api_unavailable" }
        synchronized(monitor) {
            check(listener == null) { "thermal probe is already started" }
            relativeNowNs = nowNs
            // Kotlin exposes PowerManager.getCurrentThermalStatus() as currentThermalStatus.
            recordLocked(manager.currentThermalStatus)
            var registeredListener: PowerManager.OnThermalStatusChangedListener? = null
            registeredListener = PowerManager.OnThermalStatusChangedListener { status ->
                synchronized(monitor) {
                    // Android may deliver an already-enqueued callback after listener removal.
                    // It is no longer a session observation and must not dereference a cleared clock.
                    if (listener === registeredListener) recordLocked(status)
                }
            }
            listener = checkNotNull(registeredListener)
            manager.addThermalStatusListener(checkNotNull(registeredListener))
        }
    }

    override fun observations(): List<LiveThermalObservation> = synchronized(monitor) { observations.toList() }

    override fun dispose() {
        val listenerToRemove = synchronized(monitor) {
            val value = listener
            listener = null
            relativeNowNs = null
            value
        }
        if (listenerToRemove != null) powerManager?.removeThermalStatusListener(listenerToRemove)
    }

    private fun recordLocked(status: Int) {
        val now = checkNotNull(relativeNowNs) { "thermal clock not started" }.invoke()
        observations += LiveThermalObservation(now, status)
    }

    companion object {
        fun from(context: Context): AndroidLiveThermalProbe =
            AndroidLiveThermalProbe(context.getSystemService(PowerManager::class.java))
    }
}
