package com.calisthenics.diagnostic.preview

/** Counts native preview callbacks, not analysis completions or requested camera FPS. */
internal class PreviewFrameRate {
    private var first: Long? = null
    private var last: Long? = null
    private var intervals = 0L
    @Synchronized fun onFrame(timestampNs: Long) {
        if (last?.let { timestampNs <= it } == true) return
        if (first == null) first = timestampNs else intervals++
        last = timestampNs
    }
    @Synchronized fun fps(nowNs: Long): Double? {
        val start = first ?: return null
        val end = last ?: return null
        if (nowNs - end >= 1_000_000_000L) return 0.0
        return if (end > start) intervals * 1_000_000_000.0 / (end - start) else null
    }
}
